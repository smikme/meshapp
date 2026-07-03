# Протокол MeshApp Remote RPC

Эта страница описывает JSON RPC-протокол, который MeshApp использует для удаленного подключения одного экземпляра MeshApp к другому экземпляру MeshApp Host. Протокол не является JSON-RPC 2.0 и не передает raw Meshtastic/MeshCore byte stream. Remote RPC - это отдельный прикладной слой для вызова whitelisted host-методов, получения host-событий и работы с состоянием уже выбранного подключения на Host.

Основные исходники:

- `src/main/java/com/meshtastic/client/rpc/*` - transport, handshake, envelope, dispatcher.
- `src/main/java/com/meshtastic/client/service/RemoteRpcHostService.java` - реестр host-методов.
- `src/main/java/com/meshtastic/client/protocol/rpc/*` - JSON-маппинг доменных объектов.
- `src/main/java/com/meshtastic/client/connection/rpc/*` - интеграция Remote RPC в `ConnectionManager`.

## Термины

| Термин | Значение |
|--------|----------|
| Host | Экземпляр MeshApp, в котором включен Remote RPC server или подключение к External RPC Router. Именно Host выполняет RPC-методы. |
| Remote client | Экземпляр MeshApp, который подключается к Host как `ConnectionType.REMOTE_RPC` / `ProtocolType.REMOTE_RPC`. |
| Direct mode | Прямое TCP-подключение к Host. По умолчанию Host слушает `127.0.0.1:44030`. |
| Router mode | Подключение через External RPC Router по WebSocket. Router только сводит участников комнаты и пересылает фреймы. RPC-аутентификация и шифрование остаются end-to-end между Remote client и Host. |
| Envelope | JSON-объект верхнего уровня: request, response или event. |
| Frame | Одна строка direct TCP stream или один `payload.frame` в router WebSocket. После аутентификации frame содержит encrypted RPC envelope. |

## Режимы подключения

### Direct TCP

Direct server запускается в настройках приложения: "Удаленный RPC-доступ" -> "Включить RPC-сервер".

Параметры по умолчанию:

| Параметр | Значение |
|----------|----------|
| bind address | `127.0.0.1` |
| port | `44030` |
| access key | строка `mra1_...`, генерируется пользователем |

Direct server принимает только одну активную authenticated-сессию. Если сессия уже открыта, новая TCP-сессия закрывается.

До успешной аутентификации транспорт является newline-delimited UTF-8 JSON control stream. После аутентификации каждая строка содержит encrypted frame вида `enc1_<base64url>`.

### External RPC Router

Router mode используется, когда прямой TCP-доступ к Host невозможен. Встроенное облачное значение:

| Параметр | Значение |
|----------|----------|
| display host | `cloud.meshapp.privatepractice.app` |
| server | `wss://cloud.meshapp.privatepractice.app` |
| port | `443` |
| path по умолчанию | `/rpc` |

Router URI строится так:

```text
<ws-or-wss>://<host>:<port>/<path>?roomId=<roomId>&role=<host-or-client>
```

Правила нормализации:

- если scheme не указан, используется `ws://`;
- разрешены только `ws://` и `wss://`;
- порт по умолчанию: `8080` для `ws`, `443` для `wss`;
- path по умолчанию: `/rpc`;
- `roomId` выводится из access key и не раскрывает сам key;
- `role` равен `host` для Host и `client` для Remote client.

Пример:

```text
ws://router.example.org:8080/rpc?roomId=erpc1_MpmfGysDJIvccpcIQYIfh0aeET-OORKNPAXG-UoAyK0&role=client
```

Router WebSocket передает JSON-объекты. Используемые типы router-сообщений:

| Направление | JSON | Назначение |
|-------------|------|------------|
| Host -> router | `{"type":"host_frame","clientSessionId":"...","payload":{"frame":"..."}}` | Передать frame конкретному remote client. |
| Client -> router | `{"payload":{"frame":"..."}}` | Передать frame активному Host комнаты. |
| Любая сторона -> router | `{"type":"router_ping"}` | Healthcheck. |
| Router -> Host | `{"type":"host_ready"}` | Host зарегистрирован в комнате. |
| Router -> Host | `{"type":"client_joined","clientSessionId":"..."}` | Новый remote client подключился к комнате. |
| Router -> Host | `{"type":"client_frame","clientSessionId":"...","payload":{"frame":"..."}}` | Frame от remote client. |
| Router -> Host | `{"type":"client_disconnected","clientSessionId":"..."}` | Remote client отключился. |
| Router -> client | `{"type":"client_ready"}` | Client зарегистрирован в комнате. |
| Router -> client | `{"type":"host_connected"}` | Host доступен. |
| Router -> client | `{"type":"host_frame","payload":{"frame":"..."}}` или `{"type":"broadcast","payload":{"frame":"..."}}` | Frame от Host. |
| Router -> client | `{"type":"host_disconnected"}` | Host отключился. |
| Router -> любая сторона | `{"type":"router_pong"}` | Ответ на healthcheck. |
| Router -> любая сторона | `{"type":"router_error","message":"..."}` | Ошибка router-канала. |

Host и client отправляют `router_ping` каждые 15 секунд. Если `router_pong` или другие router-сообщения не приходят больше 45 секунд, соединение закрывается как failed.

## Access key

Access key - общий секрет Host и Remote client.

Формат:

```text
mra1_<base64url-without-padding-32-random-bytes>
```

Свойства:

- key material: 32 байта;
- nonce: 24 случайных байта, кодируются base64url без padding;
- access key никогда не отправляется по сети;
- proof и encrypted session keys считаются через HMAC-SHA256;
- router room id выводится как `erpc1_` + base64url без padding от HMAC-SHA256.

Room id:

```text
roomId = "erpc1_" + base64url_no_padding(HMAC_SHA256(keyBytes, "ERPC-Router room id v1"))
```

## Аутентификация и шифрование

Direct mode и Router mode используют один и тот же encrypted RPC session protocol версии 2. В router mode control frames relay-ятся через `payload.frame`, но криптографически сессия остается end-to-end между Host и Remote client.

### Шаг 1. Host challenge

Host генерирует `serverNonce` и отправляет plaintext control frame:

```json
{
  "type": "auth_challenge",
  "version": 2,
  "cipher": "AES-256-GCM",
  "nonce": "serverNonce"
}
```

### Шаг 2. Client response

Client генерирует `clientNonce` и отправляет:

```json
{
  "type": "auth_response",
  "version": 2,
  "clientNonce": "clientNonce",
  "proof": "base64urlClientProof"
}
```

Client proof:

```text
clientProof = base64url_no_padding(
  HMAC_SHA256(keyBytes, transcript("meshapp-rpc-client-auth-v2", serverNonce, clientNonce))
)
```

### Шаг 3. Host ok/error

Если proof неверный:

```json
{
  "type": "auth_error",
  "message": "invalid access key"
}
```

Если proof верный:

```json
{
  "type": "auth_ok",
  "version": 2,
  "cipher": "AES-256-GCM",
  "proof": "base64urlServerProof"
}
```

Server proof:

```text
serverProof = base64url_no_padding(
  HMAC_SHA256(keyBytes, transcript("meshapp-rpc-server-auth-v2", serverNonce, clientNonce))
)
```

`transcript(context, serverNonce, clientNonce)` кодируется как:

```text
utf8(context)
uint32_be(len(utf8(serverNonce)))
utf8(serverNonce)
uint32_be(len(utf8(clientNonce)))
utf8(clientNonce)
```

Client проверяет `serverProof`. После этого обе стороны выводят session keys.

### Session key derivation

Salt transcript:

```text
utf8("meshapp-rpc-secure-v1:salt")
uint32_be(len(utf8(serverNonce)))
utf8(serverNonce)
uint32_be(len(utf8(clientNonce)))
utf8(clientNonce)
```

HKDF-подобный вывод:

```text
prk = HMAC_SHA256(saltTranscript, keyBytes)
clientToServerKey = HKDF-Expand(prk, "meshapp-rpc-secure-v1:client-to-server:key", 32)
clientToServerNoncePrefix = HKDF-Expand(prk, "meshapp-rpc-secure-v1:client-to-server:nonce-prefix", 4)
serverToClientKey = HKDF-Expand(prk, "meshapp-rpc-secure-v1:server-to-client:key", 32)
serverToClientNoncePrefix = HKDF-Expand(prk, "meshapp-rpc-secure-v1:server-to-client:nonce-prefix", 4)
```

`HKDF-Expand` использует HMAC-SHA256 blocks:

```text
T(0) = empty
T(n) = HMAC_SHA256(prk, T(n-1) || info || uint8(n))
output = T(1) || T(2) || ...
```

### Encrypted frames

Каждый plaintext RPC envelope шифруется отдельно:

| Параметр | Значение |
|----------|----------|
| Cipher | `AES/GCM/NoPadding` |
| Key | direction-specific 32-byte key |
| Nonce | 4-byte direction nonce prefix + 8-byte big-endian sequence |
| Sequence | отдельный счетчик для каждого направления, начинается с `0` |
| AAD | `meshapp-rpc-secure-v1:frame` |
| Tag | 128 bit |
| Frame prefix | `enc1_` |
| Encoding | base64url без padding |

Frame:

```text
enc1_<base64url_no_padding(ciphertext_plus_gcm_tag)>
```

Если frame не начинается с `enc1_`, sequence исчерпан или GCM authentication не проходит, транспорт закрывается с ошибкой.

## RPC envelope

Все RPC envelopes сериализуются compact JSON через Gson с `serializeNulls`. Поля с `null` могут присутствовать явно. Direct transport передает один envelope на строку после encrypted framing. Router transport кладет encrypted frame в `payload.frame`.

### Request

```json
{
  "type": "rpc_request",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "method": "chat.messages",
  "params": {
    "chatType": "channel",
    "chatKey": "0",
    "limit": 50
  }
}
```

Правила:

- `type` должен быть `rpc_request`;
- `requestId` обязателен и не должен быть blank;
- `method` обязателен на client side, trim-ится перед отправкой;
- `params` должен быть JSON object; если поле отсутствует или не object, Host использует `{}`;
- Host обрабатывает только методы, явно зарегистрированные в `RpcMethodRegistry`;
- malformed JSON, non-object envelope, unsupported `type` и request без `requestId` игнорируются без response.

### Successful response

```json
{
  "type": "rpc_response",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "ok": true,
  "result": {
    "pong": true
  }
}
```

`result` может быть object, array, primitive или `null`. Большинство текущих методов возвращают object.

### Error response

```json
{
  "type": "rpc_response",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "ok": false,
  "error": {
    "code": "METHOD_NOT_FOUND",
    "message": "RPC method not found: missing.method"
  }
}
```

Error codes:

| Code | Когда возникает |
|------|-----------------|
| `BAD_REQUEST` | Handler бросил `IllegalArgumentException`, обычно из-за отсутствующего или некорректного параметра. |
| `METHOD_NOT_FOUND` | Метод не зарегистрирован на Host. |
| `CONNECTION_FAILED` | Handler бросил `ConnectionException`; в message добавляется root cause, если он есть. |
| `INTERNAL_ERROR` | Непредвиденная ошибка handler-а или custom `RpcException` без code. |
| `TIMEOUT` | Client-side timeout ожидания response. |
| `TRANSPORT_CLOSED` | RPC client/transport закрыт до или во время вызова. |

`RpcException` внутри host handler-а может вернуть custom code; code trim-ится, blank превращается в `INTERNAL_ERROR`.

### Event

```json
{
  "type": "event",
  "event": "message.incoming",
  "payload": {
    "chatType": "dm",
    "chatKey": "!12345678"
  }
}
```

Events являются push-сообщениями Host -> Remote client и не имеют `requestId`/response. Если `payload` отсутствует, client передает listener-ам `null` JSON value. Event без непустого `event` игнорируется.

## Таймауты

| Участок | Значение |
|---------|----------|
| Default RPC call timeout | 30 секунд |
| Remote protocol startup `system.ping` | 5 секунд |
| UI remote RPC calls в формах | обычно 15 секунд |
| Direct TCP connect | 5 секунд |
| Router connect/auth | 8 секунд |
| Router ping interval | 15 секунд |
| Router ping timeout | 45 секунд |
| Lua remote form value wait | 2 секунды |
| Traceroute pending cleanup | 365 секунд |

## Общие правила JSON

- Строковые required-поля должны быть JSON primitive string и не blank после trim.
- Numeric-поля читаются как Java `int`, `long`, `double` или `float`; out-of-range проверяется только там, где явно указано.
- Boolean-поля читаются только из JSON primitive boolean.
- Отсутствующие optional string-поля обычно трактуются как `""`.
- Отсутствующие optional number-поля обычно трактуются как `0`.
- Отсутствующие optional boolean-поля обычно трактуются как `false`.
- `nodeId` обычно имеет вид `!` + 8 hex-символов.
- `nodeNum` передается как Java signed `int`; для отображения unsigned используется `nodeId`.
- Chat timestamp и telemetry timestamp - Unix epoch seconds.
- Packet monitor `capturedAt` - Unix epoch milliseconds.
- Protobuf payloads, `publicKey`, `routeData`, `adminMessage` кодируются обычным Base64, не base64url.

## Host state model

Remote RPC client вызывает методы Host, а Host применяет их к своему локальному состоянию:

- `connection.*` управляют списком подключений Host.
- Большинство `chat.*`, `node.*`, `telemetry.*`, `settings.*` и `admin.*` требуют выбранное и подключенное Host-подключение.
- Remote RPC connection на client-side проходит `system.ping`; затем `RemoteRpcState` хранит `RpcClient` и ping-result.
- Remote RPC transport не принимает raw radio bytes через `TransportConnection.sendBytes`.

## Методы

### `system.ping`

Проверяет доступность Host RPC.

Params:

```json
{}
```

Result:

| Поле | Тип | Описание |
|------|-----|----------|
| `app` | string | Всегда `MeshApp`. |
| `version` | string | Версия приложения. |
| `versionCode` | number | Numeric version code. |
| `remoteRpc` | boolean | Всегда `true`. |
| `activeConnections` | number | Количество активных подключений на Host. |

### `connection.list`

Возвращает сохраненные подключения Host.

Params: `{}`.

Result:

| Поле | Тип | Описание |
|------|-----|----------|
| `selectedConnectionId` | string/null | ID выбранного подключения Host. |
| `items` | `Connection[]` | Подключения Host. |

`Connection`:

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | string | Stable connection profile id. |
| `name` | string | Имя профиля. |
| `type` | string | `TCP`, `SERIAL`, `BLE`, `REMOTE_RPC`. |
| `protocol` | string | `MESHTASTIC`, `MESHCORE_KISS`, `MESHCORE_COMPANION`, `REMOTE_RPC`. |
| `connected` | boolean | Подключено ли сейчас. |
| `reconnecting` | boolean | Идет ли reconnect. |
| `selected` | boolean | Является ли выбранным на Host. |
| `nodeId` | string/null | Node id, если известен. |
| `address` | string | Человекочитаемый адрес. |

### `connection.connect`

Подключает профиль Host и выбирает его.

Params:

| Поле | Тип | Обяз. | Описание |
|------|-----|-------|----------|
| `id` | string | да | Connection id. |

Result: тот же shape, что `connection.list`, плюс `connection`, если профиль найден.

### `connection.disconnect`

Отключает профиль Host.

Params: `{"id":"..."}`.

Result: тот же shape, что `connection.connect`.

### `connection.select`

Выбирает профиль Host без подключения.

Params: `{"id":"..."}`.

Result: тот же shape, что `connection.connect`.

## Chat methods

В chat API используются:

- `chatType`: `channel` или `dm`;
- `chatKey`: для `channel` - индекс канала строкой, например `"0"`; для `dm` - peer `nodeId`, например `"!12345678"`;
- `ownerNodeId`: локальный node id выбранного Host-подключения.

### `chat.list`

Возвращает список каналов и личных диалогов.

Params: `{}`.

Result:

| Поле | Тип | Описание |
|------|-----|----------|
| `ownerNodeId` | string | Локальный node id Host-подключения. |
| `connectionId` | string | ID выбранного Host-подключения. |
| `items` | `ChatItem[]` | Чаты. |

`ChatItem`:

| Поле | Тип | Описание |
|------|-----|----------|
| `type` | string | `CHANNEL` или `DIRECT_MESSAGE`. |
| `displayName` | string | Название чата. |
| `avatarText` | string | Текст аватара. |
| `avatarColor` | string | Цвет аватара, например `#5B8DEF`. |
| `lastMessageText` | string | Последний текст. |
| `lastMessageTime` | number | Unix epoch seconds. |
| `unreadCount` | number | Непрочитанные. |
| `channelIndex` | number | Индекс канала или `0`. |
| `peerNodeId` | string | Peer node id для DM или `""`. |
| `muted` | boolean | Muted flag. |

### `chat.messages`

Загружает сообщения чата.

Params:

| Поле | Тип | Обяз. | Описание |
|------|-----|-------|----------|
| `chatType` | string | да | `channel` или `dm`. |
| `chatKey` | string | да | Channel index string или peer node id. |
| `limit` | number | нет | 1..200, default `50`. |
| `beforeDbId` | number | нет | Если > 0, загрузить сообщения до DB id. |
| `afterDbId` | number | нет | Если > 0 и `beforeDbId == 0`, загрузить сообщения после DB id. |

Result:

| Поле | Тип | Описание |
|------|-----|----------|
| `ownerNodeId` | string | Локальный node id. |
| `items` | `Message[]` | Сообщения. |

### `chat.markRead`

Помечает чат прочитанным.

Params:

| Поле | Тип | Обяз. |
|------|-----|-------|
| `chatType` | string | да |
| `chatKey` | string | да |

Result: `chat.list`.

### `chat.send`

Отправляет сообщение.

Params:

| Поле | Тип | Обяз. | Описание |
|------|-----|-------|----------|
| `chatType` | string | да | `channel` или `dm`. |
| `chatKey` | string | да | Channel index string или peer node id. |
| `text` | string | да | Непустой текст. |
| `replyId` | number | нет | Packet id сообщения-ответа; может быть отрицательным. |
| `clientRequestId` | string | нет | Idempotency key. Повтор в течение 5 минут возвращает уже созданное сообщение. |

Result:

| Поле | Тип |
|------|-----|
| `message` | `Message` |
| `chat` | результат `chat.list` |

Ограничение dedup cache: 512 записей, TTL 5 минут.

### `chat.retry`

Повторяет отправку failed outgoing сообщения.

Params:

| Поле | Тип | Обяз. | Описание |
|------|-----|-------|----------|
| `chatType` | string | да | `channel` или `dm`. |
| `chatKey` | string | да | Chat key. |
| `dbId` | number | условно | DB id сообщения. Нужен `dbId > 0` или `packetId != 0`. |
| `packetId` | number | условно | Packet id сообщения. |

Result: как `chat.send`.

Ограничения: работает только для failed outgoing сообщений и не поддерживается для MeshCore Companion runtime.

### `chat.react`

Отправляет реакцию на сообщение.

Params:

| Поле | Тип | Обяз. |
|------|-----|-------|
| `chatType` | string | да |
| `chatKey` | string | да |
| `targetPacketId` | number | да, не `0` |
| `emoji` | string | да |

Result:

| Поле | Тип |
|------|-----|
| `message` | обновленное target `Message` |
| `chat` | результат `chat.list` |

Ограничение: реакции не поддерживаются для MeshCore Companion runtime.

### `Message`

| Поле | Тип | Описание |
|------|-----|----------|
| `fromNodeId` | string | Sender node id. |
| `toNodeId` | string | Recipient node id; broadcast обычно `!ffffffff`. |
| `channelIndex` | number | Индекс канала. |
| `text` | string | Текст. |
| `timestamp` | number | Unix epoch seconds. |
| `outgoing` | boolean | Сообщение отправлено локальным устройством. |
| `status` | string/null | `SENDING`, `DELIVERED`, `CONFIRMED`, `FAILED`. |
| `packetId` | number | Meshtastic packet id. |
| `errorReason` | string/null | Причина ошибки отправки. |
| `replyId` | number | Packet id исходного сообщения. |
| `replyText` | string/null | Текст исходного сообщения. |
| `replyToOutgoing` | boolean | Reply target был outgoing. |
| `hopStart` | number | Исходный hop limit. |
| `hopLimit` | number | Оставшийся hop limit. |
| `rxRssi` | number | RSSI. |
| `rxSnr` | number | SNR. |
| `senderName` | string | Resolved sender display name. |
| `viaMqtt` | boolean | Сообщение пришло через MQTT. |
| `systemMessage` | boolean | Системное сообщение. |
| `dbId` | number | Локальный DB id. |
| `reactions` | `Reaction[]` | Реакции. |

`Reaction`:

| Поле | Тип |
|------|-----|
| `targetPacketId` | number |
| `fromNodeId` | string |
| `emoji` | string |
| `timestamp` | number |
| `outgoing` | boolean |
| `dbId` | number |
| `packetId` | number |
| `status` | string/null |
| `errorReason` | string/null |
| `senderName` | string |

## Node methods

### `node.list`

Возвращает nodes выбранного Host-подключения.

Params:

| Поле | Тип | Обяз. | Описание |
|------|-----|-------|----------|
| `includeFavorites` | boolean | нет | Добавить offline favorites из cache. |
| `includeIgnored` | boolean | нет | Добавить offline ignored nodes из cache. |

Result:

| Поле | Тип |
|------|-----|
| `ownerNodeId` | string |
| `items` | `Node[]` |

### `node.get`

Возвращает один node.

Params:

| Поле | Тип | Обяз. |
|------|-----|-------|
| `nodeId` | string | да |

Result: `{"ownerNodeId":"...","items":[Node]}`.

### `node.traceroute`

Запускает traceroute до node. Результат приходит отдельным event `node.traceroute.result`.

Params:

| Поле | Тип | Обяз. | Описание |
|------|-----|-------|----------|
| `nodeId` | string | условно | Target node id. |
| `nodeNum` | number | условно | Target node num. Нужен `nodeId` или non-zero `nodeNum`. |
| `requestId` | string | нет | Если отсутствует, Host сгенерирует UUID. |

Result:

| Поле | Тип |
|------|-----|
| `requestId` | string |
| `targetNodeNum` | number |
| `targetNodeId` | string |
| `targetName` | string |
| `nodeNames` | object map unsigned node num string -> display name |

Ограничение: требует protocol handler; недоступно для выбранных runtime-ов, которые не умеют отправлять traceroute.

### `node.refresh`

Запрашивает свежий UserInfo для node.

Params:

| Поле | Тип | Обяз. |
|------|-----|-------|
| `nodeNum` | number | да, не `0` |
| `includeFavorites` | boolean | нет |
| `includeIgnored` | boolean | нет |

Result: `node.list` с теми же filter flags.

### `node.delete`

Удаляет node из runtime/cache.

Params:

| Поле | Тип | Обяз. | Описание |
|------|-----|-------|----------|
| `nodeId` | string | да | Node id. |
| `nodeNum` | number | нет | Если non-zero, node удаляется из текущего `DeviceState`. |
| `includeFavorites` | boolean | нет | Передается в result `node.list`. |
| `includeIgnored` | boolean | нет | Передается в result `node.list`. |

Result: `node.list`.

### `node.favorite`

Включает/выключает favorite flag.

Params:

| Поле | Тип | Обяз. |
|------|-----|-------|
| `nodeId` | string | да |
| `enabled` | boolean | нет, default `false` |
| `includeFavorites` | boolean | нет |
| `includeIgnored` | boolean | нет |

Result: `node.list`.

### `node.ignored`

Включает/выключает ignored flag.

Params: как `node.favorite`.

Result: `node.list`.

### `Node`

| Поле | Тип | Описание |
|------|-----|----------|
| `nodeNum` | number | Java int node number. |
| `nodeId` | string | Stable id вида `!12345678`. |
| `longName` | string |
| `shortName` | string |
| `latitude` | number |
| `longitude` | number |
| `altitude` | number |
| `snr` | number |
| `lastHeard` | number | Unix epoch seconds или runtime value. |
| `batteryLevel` | number |
| `externallyPowered` | boolean |
| `voltage` | number |
| `channelUtilization` | number |
| `airUtilTx` | number |
| `uptimeSeconds` | number |
| `temperature` | number |
| `relativeHumidity` | number |
| `barometricPressure` | number |
| `hasHopsAway` | boolean |
| `hopsAway` | number |
| `channel` | number |
| `role` | string |
| `hwModel` | string |
| `publicKey` | string | Base64 public key или `""`. |
| `unmessagable` | boolean |
| `licensed` | boolean |
| `favorite` | boolean |
| `ignored` | boolean |

## Telemetry methods

### `telemetry.dashboard`

Возвращает telemetry dashboard для node.

Params:

| Поле | Тип | Обяз. | Описание |
|------|-----|-------|----------|
| `nodeId` | string | нет | Если blank, используется локальный node id Host-подключения. |
| `sinceEpoch` | number | нет | Нижняя граница Unix epoch seconds, default `0`. |
| `maxFutureTs` | number | нет | Верхняя граница Unix epoch seconds. Если `<= 0`, Host использует `now + 300`. |

Result:

| Поле | Тип | Описание |
|------|-----|----------|
| `ownerNodeId` | string | Локальный node id. |
| `nodeId` | string | Node id, для которого загружена telemetry. |
| `entries` | `TelemetryEntry[]` | Telemetry выбранного node. |
| `qualityEntries` | `TelemetryEntry[]` | Quality telemetry по сети. |

`TelemetryEntry` сериализуется Gson-ом из Java model. Текущие поля:

- core: `timestamp`, `nodeId`, `telemetryVariant`;
- device metrics: `batteryLevel`, `externallyPowered`, `voltage`, `channelUtilization`, `airUtilTx`, `deviceUptimeSeconds`;
- environment metrics: `temperature`, `relativeHumidity`, `barometricPressure`, `gasResistance`, `environmentVoltage`, `environmentCurrent`, `iaq`, `distance`, `lux`, `whiteLux`, `irLux`, `uvLux`, `windDirection`, `windSpeed`, `weight`, `windGust`, `windLull`, `radiation`, `rainfall1h`, `rainfall24h`, `soilMoisture`, `soilTemperature`, `oneWireTemperatures`;
- air quality metrics: `pm10Standard`, `pm25Standard`, `pm100Standard`, `pm10Environmental`, `pm25Environmental`, `pm100Environmental`, `particles03um`, `particles05um`, `particles10um`, `particles25um`, `particles50um`, `particles100um`, `co2`, `co2Temperature`, `co2Humidity`, `formFormaldehyde`, `formHumidity`, `formTemperature`, `pm40Standard`, `particles40um`, `pmTemperature`, `pmHumidity`, `pmVocIdx`, `pmNoxIdx`, `particlesTps`;
- power metrics: `ch1Voltage`..`ch8Voltage`, `ch1Current`..`ch8Current`;
- local stats: `numPacketsRx`, `numPacketsRxBad`, `numRxDupe`, `numPacketsTx`, `numTxDropped`, `numTxRelay`, `numTxRelayCanceled`, `localUptimeSeconds`, `numOnlineNodes`, `numTotalNodes`, `heapTotalBytes`, `heapFreeBytes`, `noiseFloor`;
- health metrics: `healthHeartBpm`, `healthSpO2`, `healthTemperature`;
- host metrics: `hostUptimeSeconds`, `hostFreememBytes`, `hostDiskfree1Bytes`, `hostDiskfree2Bytes`, `hostDiskfree3Bytes`, `hostLoad1`, `hostLoad5`, `hostLoad15`, `hostUserString`;
- traffic stats: `trafficPacketsInspected`, `trafficPositionDedupDrops`, `trafficNodeinfoCacheHits`, `trafficRateLimitDrops`, `trafficUnknownPacketDrops`, `trafficHopExhaustedPackets`, `trafficRouterHopsPreserved`;
- RF/hops: `rxSnr`, `rxRssi`, `hopStart`, `hopLimit`.

## Packet monitor methods

### `packetMonitor.page`

Возвращает страницу LoRa packet monitor.

Params:

| Поле | Тип | Обяз. | Описание |
|------|-----|-------|----------|
| `request` | string | нет | `older`, `newer` или любое другое/blank для latest. |
| `query` | `PacketQuery` | нет | Filter query. |
| `cursor` | `PageCursor` | нет | Cursor для older/newer. |
| `limit` | number | нет | 1..10000, default `200`. |

`PacketQuery`:

| Поле | Тип | Описание |
|------|-----|----------|
| `direction` | string | `INCOMING`, `OUTGOING`, `INTERNAL` или отсутствует. |
| `packetType` | string | Packet type filter. |
| `transportMechanism` | string | Transport filter. |
| `searchText` | string | Full-text filter. |
| `capturedAtFromMillis` | number | Lower capturedAt bound. |
| `capturedAtToMillis` | number | Upper capturedAt bound. |

`PageCursor`:

| Поле | Тип |
|------|-----|
| `capturedAt` | number |
| `id` | number |

Result:

| Поле | Тип |
|------|-----|
| `entries` | `PacketLogEntry[]` |
| `hasNewer` | boolean |
| `hasOlder` | boolean |
| `totalMatchingCount` | number |
| `totalStoredCount` | number |

### `packetMonitor.types`

Params: `{"query": PacketQuery}`.

Result: `{"items":["TEXT_MESSAGE_APP","POSITION_APP",...]}`.

### `packetMonitor.counts`

Params: `{"query": PacketQuery}`.

Result:

| Поле | Тип |
|------|-----|
| `matching` | number |
| `total` | number |

### `packetMonitor.captureState`

Params: `{}`.

Result: `{"enabled": boolean}`.

### `packetMonitor.start`

Включает capture.

Params: `{}`.

Result: `{"enabled": true}`.

### `packetMonitor.stop`

Выключает capture.

Params: `{}`.

Result: `{"enabled": false}`.

### `packetMonitor.clear`

Очищает packet monitor.

Params: `{}`.

Result: `{"matching":0,"total":0}`.

### `PacketLogEntry`

Сериализуется Gson-ом из immutable Java model:

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | number | DB id. |
| `ownerNodeId` | string |
| `capturedAt` | number | Unix epoch milliseconds. |
| `direction` | string | `INCOMING`, `OUTGOING`, `INTERNAL`. |
| `packetType` | string |
| `transportMechanism` | string |
| `fromNode` | string |
| `toNode` | string |
| `payloadText` | string |
| `packetBytes` | byte array | Gson byte-array encoding. |

## Lua methods

Lua RPC управляет скриптами MeshApp IDE на Host. `scriptId` обязателен и должен быть `> 0` во всех методах, где он указан.

### `lua.list`

Params: `{}`.

Result: `{"items": LuaScript[]}`.

### `lua.get`

Params: `{"scriptId": number}`.

Result: `{"script": LuaScript}`.

### `lua.draft`

Создает draft script.

Params: `{}`.

Result: `{"script": LuaScript}`.

### `lua.createDefault`

Создает script со значениями по умолчанию.

Params: `{}`.

Result: `{"script": LuaScript}`.

### `lua.create`

Params:

| Поле | Тип | Обяз. |
|------|-----|-------|
| `name` | string | да |
| `code` | string | нет |
| `enabled` | boolean | нет |
| `icon` | string | нет |
| `nodeId` | string | нет |
| `botType` | string | нет, default `AIR_BOT` |
| `automationName` | string | нет |
| `description` | string | нет |
| `author` | string | нет |

Result: `{"script": LuaScript}`.

### `lua.save`

Params:

| Поле | Тип | Обяз. |
|------|-----|-------|
| `scriptId` | number | да |
| `name` | string | да |
| `code` | string | нет |
| `enabled` | boolean | нет |

Result: `{"script": LuaScript}`.

### `lua.saveSettings`

Params: `scriptId` + поля `lua.create`, кроме `code`.

Result: `{"script": LuaScript}`.

### `lua.delete`

Останавливает script и удаляет его.

Params: `{"scriptId": number}`.

Result: `lua.list`.

### `lua.importJson`

Params:

| Поле | Тип | Обяз. |
|------|-----|-------|
| `json` | string | да |

Result:

| Поле | Тип |
|------|-----|
| `script` | `LuaScript` |
| `updated` | boolean |

### `lua.importExport`

Params:

| Поле | Тип | Описание |
|------|-----|----------|
| `exportFile` | object | Gson-сериализация `LuaScriptService.LuaScriptExportFile`. |

Result: как `lua.importJson`.

### `lua.export`

Params: `{"scriptId": number}`.

Result: `{"json": string}`.

### `lua.runningState`

Params: `{"scriptId": number}`.

Result:

| Поле | Тип |
|------|-----|
| `scriptId` | number |
| `running` | boolean |
| `paused` | boolean |

### `lua.run`

Запускает script. Для `EXTENSION` используется remote form bridge.

Params: `{"scriptId": number}`.

Result: running state.

### `lua.automation.run`

Запускает automation bot command.

Params:

| Поле | Тип |
|------|-----|
| `scriptId` | number |
| `command` | `LuaAutomationCommand` |

`LuaAutomationCommand`:

| Поле | Тип |
|------|-----|
| `chatType` | string |
| `chatKey` | string |
| `handle` | string |
| `text` | string |
| `arguments` | string |
| `argumentTokens` | string[] |
| `requestId` | string |

Result: running state.

### `lua.ui.nodeSelection`

Доставляет в runtime результат выбора node для ранее отправленного event `lua.ui.nodePick.request`.

Params:

| Поле | Тип |
|------|-----|
| `scriptId` | number |
| `requestId` | string |
| `source` | string |
| `name` | string |
| `selected` | boolean |
| `chatType` | string |
| `chatKey` | string |
| `node` | `Node`, optional |

Result: running state.

### `lua.form.event`

Доставляет событие extension form component.

Params:

| Поле | Тип |
|------|-----|
| `scriptId` | number |
| `componentId` | string |
| `type` | string |
| `value` | any |
| `text` | string |

Result: running state.

### `lua.form.valueResult`

Ответ Remote client на Host-команду `lua.form.command` с `command="value"`.

Params:

| Поле | Тип |
|------|-----|
| `scriptId` | number |
| `requestId` | string |
| `value` | any |

Result: `{}`.

### `lua.debug`

Запускает script в debug-режиме.

Params:

| Поле | Тип |
|------|-----|
| `scriptId` | number |
| `breakpoints` | number[] |

Result: running state.

### `lua.stop`

Params: `{"scriptId": number}`.

Result: running state.

### `lua.debugContinue`

Params: `{"scriptId": number}`.

Result: running state.

### `lua.debugStep`

Params: `{"scriptId": number}`.

Result: running state.

### `lua.debugSnapshot`

Params: `{"scriptId": number}`.

Result:

| Поле | Тип | Описание |
|------|-----|----------|
| `snapshot` | `LuaDebugSnapshot`, optional | Отсутствует, если snapshot нет. |

`LuaDebugSnapshot`: `scriptId`, `line`, `reason`, `variables`. `variables[]`: `scope`, `name`, `value`.

### `lua.kv.list`

Params: `{"scriptId": number}`.

Result:

```json
{
  "items": {
    "key": "value"
  }
}
```

### `lua.kv.set`

Params:

| Поле | Тип | Обяз. |
|------|-----|-------|
| `scriptId` | number | да |
| `key` | string | да |
| `value` | string | нет |

Result: `{}`.

### `lua.kv.delete`

Params: `{"scriptId": number, "key": "..."}`.

Result: `{"deleted": boolean}`.

### `lua.kv.clear`

Params: `{"scriptId": number}`.

Result: `{}`.

### `LuaScript`

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | number |
| `guid` | string |
| `icon` | string |
| `name` | string |
| `code` | string |
| `version` | number |
| `description` | string |
| `author` | string |
| `enabled` | boolean |
| `nodeId` | string |
| `botType` | string | `AIR_BOT`, `AUTOMATION_BOT`, `EXTENSION`. |
| `automationName` | string |
| `createdAt` | number |
| `updatedAt` | number |
| `lastRunAt` | number |
| `lastStatus` | string |
| `lastError` | string/null |
| `running` | boolean | Только когда result собран с runtime service. |
| `paused` | boolean | Только когда result собран с runtime service. |

### `LuaFormComponentSpec`

Extension form spec сериализуется как JSON object:

`id`, `type`, `parentId`, `text`, `prompt`, `value`, `items`, `min`, `max`, `disabled`, `visible`, `style`, `orientation`, `width`, `height`, `minWidth`, `minHeight`, `maxWidth`, `maxHeight`, `readOnly`, `wrap`, `monospace`, `grow`, `rows`, `chartType`, `xLabel`, `yLabel`, `xType`, `legend`, `symbols`, `series`.

`series[]`: `name`, `color`, `points`; `points[]`: `x`, `y`.

## Settings methods

`settings.*` работают с локальным node выбранного Host-подключения. Многие методы предварительно пытаются получить session passkey. Большинство методов возвращают settings snapshot.

### `settings.snapshot`

Params: `{}`.

Result: `AdminSnapshot` для локального Host node.

### `settings.saveOwner`

Params:

| Поле | Тип |
|------|-----|
| `longName` | string |
| `shortName` | string |
| `isLicensed` | boolean |

Result: `AdminSnapshot`.

### `settings.saveConfigChanges`

Params:

| Поле | Тип | Описание |
|------|-----|----------|
| `configs` | string[] | Base64 `ConfigProtos.Config`. |
| `moduleConfigs` | string[] | Base64 `ModuleConfigProtos.ModuleConfig`. |
| `channels` | string[] | Base64 `ChannelProtos.Channel`. |

Result: `AdminSnapshot`.

### `settings.setFixedPosition`

Params:

| Поле | Тип |
|------|-----|
| `latDegrees` | number |
| `lonDegrees` | number |
| `altMeters` | number |

Result: `AdminSnapshot`.

### `settings.removeFixedPosition`

Params: `{}`.

Result: `AdminSnapshot`.

### `settings.setRingtone`

Params: `{"ringtone": string}`.

Result: `AdminSnapshot`.

### `settings.command`

Params:

| Поле | Тип | Описание |
|------|-----|----------|
| `command` | string | `reboot`, `shutdown`, `syncTime`, `enterDfuMode`. |
| `delaySeconds` | number | Для `reboot` и `shutdown`, default `0`. |
| `epochSeconds` | number | Для `syncTime`. |

Result: `AdminSnapshot`.

## Remote admin methods

`admin.*` отправляют Meshtastic remote admin packets к другому node через выбранное Host-подключение.

Общие требования:

- выбранное Host-подключение должно иметь `ProtocolHandler`;
- target задается через `nodeId` или non-zero `nodeNum`;
- target не может быть локальным Host node;
- target node должен иметь public key.

Общие target params:

| Поле | Тип | Обяз. |
|------|-----|-------|
| `nodeId` | string | условно |
| `nodeNum` | number | условно |

### `admin.load`

Params: target params.

Result: `AdminSnapshot`.

### `admin.requestConfig`

Params:

| Поле | Тип | Описание |
|------|-----|----------|
| `nodeId`/`nodeNum` | string/number | Target. |
| `type` | string | Имя enum `AdminProtos.AdminMessage.ConfigType`, кроме `UNRECOGNIZED`. |

Result: `AdminSnapshot`.

### `admin.requestModuleConfig`

Params:

| Поле | Тип | Описание |
|------|-----|----------|
| `nodeId`/`nodeNum` | string/number | Target. |
| `type` | string | Имя enum `AdminProtos.AdminMessage.ModuleConfigType`, кроме `UNRECOGNIZED`. |

Result: `AdminSnapshot`.

### `admin.saveOwner`

Params: target params + `longName`, `shortName`, `isLicensed`.

Result: `{"ok": true}`.

### `admin.saveConfigChanges`

Params: target params + `configs`, `moduleConfigs`, `channels` как в `settings.saveConfigChanges`.

Result: `{"ok": true}`.

### `admin.setFixedPosition`

Params: target params + `latDegrees`, `lonDegrees`, `altMeters`.

Result: `{"ok": true}`.

### `admin.removeFixedPosition`

Params: target params.

Result: `{"ok": true}`.

### `admin.setRingtone`

Params: target params + `ringtone`.

Result: `{"ok": true}`.

### `admin.setCannedMessages`

Params: target params + `messages`.

Result: `{"ok": true}`.

### `admin.command`

Params:

| Поле | Тип | Описание |
|------|-----|----------|
| `nodeId`/`nodeNum` | string/number | Target. |
| `command` | string | См. список ниже. |
| `delaySeconds` | number | Для `reboot`/`shutdown`. |
| `epochSeconds` | number | Для `syncTime`. |
| `location` | string | Для backup commands: enum `AdminProtos.AdminMessage.BackupLocation`, кроме `UNRECOGNIZED`. |
| `preserveFavorites` | boolean | Для `resetNodeDb`. |

Commands:

- `reboot`
- `shutdown`
- `syncTime`
- `backupPreferences`
- `restorePreferences`
- `removeBackupPreferences`
- `factoryResetConfig`
- `factoryResetDevice`
- `resetNodeDb`
- `enterDfuMode`

Result: `{"ok": true}`.

### `admin.refreshConnectionStatus`

Params: target params.

Result: `AdminSnapshot` плюс поле:

| Поле | Тип | Описание |
|------|-----|----------|
| `adminMessage` | string | Base64 `AdminProtos.AdminMessage`. |

### `AdminSnapshot`

| Поле | Тип | Описание |
|------|-----|----------|
| `targetNodeNum` | number |
| `node` | `Node` |
| `owner` | string | Base64 `MeshProtos.User`. |
| `deviceMetadata` | string | Base64 `MeshProtos.DeviceMetadata`. |
| `configs` | string[] | Base64 `ConfigProtos.Config`. |
| `moduleConfigs` | string[] | Base64 `ModuleConfigProtos.ModuleConfig`. |
| `channels` | string[] | Base64 `ChannelProtos.Channel`. |
| `channelCatalogReady` | boolean |
| `ringtone` | string |
| `ringtoneLoaded` | boolean |
| `cannedMessages` | string |
| `cannedMessagesLoaded` | boolean |
| `uiConfig` | string | Base64 `DeviceUIProtos.DeviceUIConfig`. |
| `connectionStatus` | string | Base64 `ConnStatusProtos.DeviceConnectionStatus`. |
| `queryStatuses` | `QueryStatus[]` |

`QueryStatus`:

| Поле | Тип | Описание |
|------|-----|----------|
| `key` | string |
| `state` | string | `SENT`, `RECEIVED`, `FAILED`. |
| `detail` | string |

## Host events

### `message.incoming`

Публикуется при входящем сообщении. Remote runtime использует его для desktop notification и unread indicator.

Payload:

| Поле | Тип |
|------|-----|
| `chatType` | string |
| `chatKey` | string |
| `message` | `Message` |
| `title` | string |
| `body` | string |

### `message.changed`

Публикуется при non-notification изменении чата, например реакции или metadata.

Payload:

| Поле | Тип |
|------|-----|
| `chatType` | string |
| `chatKey` | string |
| `targetPacketId` | number |
| `message` | `Message`, optional |

### `message.status`

Публикуется при изменении delivery status outgoing сообщения.

Payload:

| Поле | Тип |
|------|-----|
| `chatType` | string |
| `chatKey` | string |
| `packetId` | number |
| `status` | string/null |
| `errorReason` | string/null |
| `message` | `Message` |

### `node.traceroute.result`

Payload:

| Поле | Тип |
|------|-----|
| `requestId` | string |
| `status` | string, сейчас `ok` |
| `targetNodeNum` | number |
| `targetNodeId` | string |
| `targetName` | string |
| `responseFromNodeNum` | number |
| `responseFromNodeId` | string |
| `routeData` | string, Base64 `MeshProtos.RouteDiscovery` |
| `formattedText` | string |
| `timestamp` | number, Unix epoch seconds |
| `nodeNames` | object map unsigned node num string -> display name |

### `packet.monitor.logged`

Payload: `{"entry": PacketLogEntry}`.

### `packet.monitor.capture`

Payload: `{"enabled": boolean}`.

### `packet.monitor.cleared`

Payload: `{}`.

### `lua.runtime.event`

Payload:

| Поле | Тип |
|------|-----|
| `type` | string: `INFO`, `OUTPUT`, `WARNING`, `ERROR`, `STARTED`, `STOPPED`, `UI_BOT_NOTICE`, `DEBUG_PAUSED`, `DEBUG_RESUMED` |
| `scriptId` | number |
| `message` | string |
| `errorMessage` | string, optional |
| `payload` | object, optional; для `UI_BOT_NOTICE` это `LuaUiBotNotice` |

`LuaUiBotNotice`: `scriptId`, `source`, `name`, `chatType`, `chatKey`, `text`.

### `lua.ui.nodePick.request`

Payload:

| Поле | Тип |
|------|-----|
| `scriptId` | number |
| `requestId` | string |
| `source` | string |
| `name` | string |
| `prompt` | string |
| `query` | string |
| `chatType` | string |
| `chatKey` | string |

Remote client должен ответить методом `lua.ui.nodeSelection`.

### `lua.form.command`

Payload:

| Поле | Тип |
|------|-----|
| `scriptId` | number |
| `command` | string: `show`, `title`, `clear`, `add`, `update`, `remove`, `value` |
| `requestId` | string |
| `componentId` | string |
| `title` | string |
| `script` | `LuaScript` |
| `spec` | `LuaFormComponentSpec`, optional |

Для `command="value"` Remote client должен вызвать `lua.form.valueResult` с тем же `requestId`. Текущая реализация remote form bridge проверяет availability через direct server state, поэтому фактическая доступность extension forms зависит от активного host endpoint.

## Примеры

### Request/response

Plaintext RPC envelope до encryption:

```json
{
  "type": "rpc_request",
  "requestId": "83c2cb3a-132f-4555-bfa5-0f5b8a94944d",
  "method": "system.ping",
  "params": {}
}
```

Response:

```json
{
  "type": "rpc_response",
  "requestId": "83c2cb3a-132f-4555-bfa5-0f5b8a94944d",
  "ok": true,
  "result": {
    "app": "MeshApp",
    "version": "1.0.0",
    "versionCode": 1,
    "remoteRpc": true,
    "activeConnections": 1
  }
}
```

### Unknown method

```json
{
  "type": "rpc_response",
  "requestId": "83c2cb3a-132f-4555-bfa5-0f5b8a94944d",
  "ok": false,
  "error": {
    "code": "METHOD_NOT_FOUND",
    "message": "RPC method not found: missing.method"
  }
}
```

### Chat send

Request params:

```json
{
  "chatType": "channel",
  "chatKey": "0",
  "text": "hello",
  "replyId": 0,
  "clientRequestId": "client-generated-id-1"
}
```

Result shape:

```json
{
  "message": {
    "fromNodeId": "!11111111",
    "toNodeId": "!ffffffff",
    "channelIndex": 0,
    "text": "hello",
    "timestamp": 1700000000,
    "outgoing": true,
    "status": "SENDING",
    "packetId": 123,
    "dbId": 456,
    "reactions": []
  },
  "chat": {
    "ownerNodeId": "!11111111",
    "connectionId": "connection-id",
    "items": []
  }
}
```

### Direct encrypted line

После `auth_ok` фактическая TCP-строка уже не содержит JSON:

```text
enc1_xs3B9XjglG8FjQg4N0rQLj2r8eOJlYkq7H...
```

## Совместимость

- Новые methods добавляются только через явную регистрацию в `RpcMethodRegistry`.
- Unknown methods всегда должны восприниматься client-side как `METHOD_NOT_FOUND`.
- Клиенты должны игнорировать неизвестные поля в result/event payloads.
- Enum-поля нужно обрабатывать tolerant parsing: новая версия Host может отправить значение, которого старый client не знает.
- Для protobuf payloads контрактом является Base64 от бинарного protobuf, а не JSON protobuf mapping.
- Remote RPC profile обязан использовать `ProtocolType.REMOTE_RPC`; `ConnectionManager` отклонит другой protocol для `ConnectionType.REMOTE_RPC`.
