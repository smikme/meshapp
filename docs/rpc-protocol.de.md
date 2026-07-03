# MeshApp-Remote-RPC-Protokoll

**Sprache:** [Русский](rpc-protocol.ru.md) | [English](rpc-protocol.md) | Deutsch

Diese Seite beschreibt das JSON-RPC-Protokoll, mit dem MeshApp eine MeshApp-Instanz remote mit einer anderen MeshApp-Host-Instanz verbindet. Das Protokoll ist kein JSON-RPC 2.0 und uebertraegt keinen rohen Meshtastic/MeshCore-Byte-Stream. Remote RPC ist eine eigene Anwendungsschicht zum Aufrufen freigegebener Host-Methoden, Empfangen von Host-Events und Arbeiten mit dem Zustand der auf dem Host bereits ausgewaehlten Verbindung.

Wichtige Quelldateien:

- `src/main/java/com/meshtastic/client/rpc/*` - Transport, Handshake, Envelope, Dispatcher.
- `src/main/java/com/meshtastic/client/service/RemoteRpcHostService.java` - Registry der Host-Methoden.
- `src/main/java/com/meshtastic/client/protocol/rpc/*` - JSON-Mapping der Domain-Objekte.
- `src/main/java/com/meshtastic/client/connection/rpc/*` - Remote-RPC-Integration in `ConnectionManager`.

## Begriffe

| Begriff | Bedeutung |
|---------|-----------|
| Host | MeshApp-Instanz, in der der Remote-RPC-Server aktiviert ist oder die mit dem External RPC Router verbunden ist. Der Host fuehrt RPC-Methoden aus. |
| Remote client | MeshApp-Instanz, die sich als `ConnectionType.REMOTE_RPC` / `ProtocolType.REMOTE_RPC` mit dem Host verbindet. |
| Direct mode | Direkte TCP-Verbindung zum Host. Standardmaessig lauscht der Host auf `127.0.0.1:44030`. |
| Router mode | Verbindung ueber den External RPC Router per WebSocket. Der Router fuehrt nur die Teilnehmer eines Raums zusammen und leitet Frames weiter. RPC-Authentifizierung und Verschluesselung bleiben Ende-zu-Ende zwischen Remote client und Host. |
| Envelope | JSON-Objekt der obersten Ebene: Request, Response oder Event. |
| Frame | Eine Zeile im direkten TCP-Stream oder ein `payload.frame` im Router-WebSocket. Nach der Authentifizierung enthaelt der Frame ein verschluesseltes RPC-Envelope. |

## Verbindungsmodi

### Direct TCP

Der direkte Server wird in den Anwendungseinstellungen gestartet: "Remote RPC access" -> "Enable RPC server".

Standardparameter:

| Parameter | Wert |
|-----------|------|
| bind address | `127.0.0.1` |
| port | `44030` |
| access key | Zeichenfolge `mra1_...`, vom Benutzer erzeugt |

Der direkte Server akzeptiert nur eine aktive authentifizierte Sitzung. Wenn bereits eine Sitzung offen ist, wird die neue TCP-Sitzung geschlossen.

Vor erfolgreicher Authentifizierung ist der Transport ein newline-delimited UTF-8-JSON-Control-Stream. Nach der Authentifizierung enthaelt jede Zeile einen verschluesselten Frame der Form `enc1_<base64url>`.

### External RPC Router

Router mode wird verwendet, wenn direkter TCP-Zugriff auf den Host nicht moeglich ist. Eingebauter Cloud-Wert:

| Parameter | Wert |
|-----------|------|
| display host | `cloud.meshapp.privatepractice.app` |
| server | `wss://cloud.meshapp.privatepractice.app` |
| port | `443` |
| default path | `/rpc` |

Die Router-URI wird so aufgebaut:

```text
<ws-or-wss>://<host>:<port>/<path>?roomId=<roomId>&role=<host-or-client>
```

Normalisierungsregeln:

- wenn kein Scheme angegeben ist, wird `ws://` verwendet;
- nur `ws://` und `wss://` sind erlaubt;
- Standardport: `8080` fuer `ws`, `443` fuer `wss`;
- Standardpfad: `/rpc`;
- `roomId` wird aus dem access key abgeleitet und legt den key selbst nicht offen;
- `role` ist `host` fuer den Host und `client` fuer den Remote client.

Beispiel:

```text
ws://router.example.org:8080/rpc?roomId=erpc1_MpmfGysDJIvccpcIQYIfh0aeET-OORKNPAXG-UoAyK0&role=client
```

Das Router-WebSocket uebertraegt JSON-Objekte. Verwendete Router-Nachrichtentypen:

| Richtung | JSON | Zweck |
|----------|------|-------|
| Host -> router | `{"type":"host_frame","clientSessionId":"...","payload":{"frame":"..."}}` | Frame an einen bestimmten remote client senden. |
| Client -> router | `{"payload":{"frame":"..."}}` | Frame an den aktiven Host des Raums senden. |
| Beide Seiten -> router | `{"type":"router_ping"}` | Healthcheck. |
| Router -> Host | `{"type":"host_ready"}` | Host ist im Raum registriert. |
| Router -> Host | `{"type":"client_joined","clientSessionId":"..."}` | Neuer remote client ist dem Raum beigetreten. |
| Router -> Host | `{"type":"client_frame","clientSessionId":"...","payload":{"frame":"..."}}` | Frame von einem remote client. |
| Router -> Host | `{"type":"client_disconnected","clientSessionId":"..."}` | Remote client wurde getrennt. |
| Router -> client | `{"type":"client_ready"}` | Client ist im Raum registriert. |
| Router -> client | `{"type":"host_connected"}` | Host ist verfuegbar. |
| Router -> client | `{"type":"host_frame","payload":{"frame":"..."}}` oder `{"type":"broadcast","payload":{"frame":"..."}}` | Frame vom Host. |
| Router -> client | `{"type":"host_disconnected"}` | Host wurde getrennt. |
| Router -> beide Seiten | `{"type":"router_pong"}` | Antwort auf Healthcheck. |
| Router -> beide Seiten | `{"type":"router_error","message":"..."}` | Fehler im Router-Kanal. |

Host und client senden alle 15 Sekunden `router_ping`. Wenn laenger als 45 Sekunden kein `router_pong` und keine anderen Router-Nachrichten eintreffen, wird die Verbindung als fehlgeschlagen geschlossen.

## Access Key

Der access key ist das gemeinsame Geheimnis von Host und Remote client.

Format:

```text
mra1_<base64url-without-padding-32-random-bytes>
```

Eigenschaften:

- key material: 32 Byte;
- nonce: 24 Zufallsbytes, base64url ohne Padding codiert;
- der access key wird nie ueber das Netzwerk gesendet;
- proof-Werte und verschluesselte Session-Keys werden mit HMAC-SHA256 berechnet;
- die router room id wird als `erpc1_` + base64url ohne Padding ueber HMAC-SHA256 abgeleitet.

Room id:

```text
roomId = "erpc1_" + base64url_no_padding(HMAC_SHA256(keyBytes, "ERPC-Router room id v1"))
```

## Authentifizierung und Verschluesselung

Direct mode und Router mode verwenden dasselbe verschluesselte RPC-Session-Protokoll Version 2. Im router mode werden Control-Frames ueber `payload.frame` weitergeleitet, kryptografisch bleibt die Sitzung aber Ende-zu-Ende zwischen Host und Remote client.

### Schritt 1. Host challenge

Der Host erzeugt `serverNonce` und sendet einen Plaintext-Control-Frame:

```json
{
  "type": "auth_challenge",
  "version": 2,
  "cipher": "AES-256-GCM",
  "nonce": "serverNonce"
}
```

### Schritt 2. Client response

Der Client erzeugt `clientNonce` und sendet:

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

### Schritt 3. Host ok/error

Wenn der proof falsch ist:

```json
{
  "type": "auth_error",
  "message": "invalid access key"
}
```

Wenn der proof korrekt ist:

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

`transcript(context, serverNonce, clientNonce)` wird so codiert:

```text
utf8(context)
uint32_be(len(utf8(serverNonce)))
utf8(serverNonce)
uint32_be(len(utf8(clientNonce)))
utf8(clientNonce)
```

Der Client prueft `serverProof`. Danach leiten beide Seiten Session-Keys ab.

### Session-Key-Ableitung

Salt transcript:

```text
utf8("meshapp-rpc-secure-v1:salt")
uint32_be(len(utf8(serverNonce)))
utf8(serverNonce)
uint32_be(len(utf8(clientNonce)))
utf8(clientNonce)
```

HKDF-aehnliche Ableitung:

```text
prk = HMAC_SHA256(saltTranscript, keyBytes)
clientToServerKey = HKDF-Expand(prk, "meshapp-rpc-secure-v1:client-to-server:key", 32)
clientToServerNoncePrefix = HKDF-Expand(prk, "meshapp-rpc-secure-v1:client-to-server:nonce-prefix", 4)
serverToClientKey = HKDF-Expand(prk, "meshapp-rpc-secure-v1:server-to-client:key", 32)
serverToClientNoncePrefix = HKDF-Expand(prk, "meshapp-rpc-secure-v1:server-to-client:nonce-prefix", 4)
```

`HKDF-Expand` verwendet HMAC-SHA256-Bloecke:

```text
T(0) = empty
T(n) = HMAC_SHA256(prk, T(n-1) || info || uint8(n))
output = T(1) || T(2) || ...
```

### Verschluesselte Frames

Jedes Plaintext-RPC-Envelope wird separat verschluesselt:

| Parameter | Wert |
|-----------|------|
| Cipher | `AES/GCM/NoPadding` |
| Key | richtungsspezifischer 32-Byte-Key |
| Nonce | 4-Byte-Richtungs-Nonce-Prefix + 8-Byte-Big-Endian-Sequence |
| Sequence | eigener Zaehler pro Richtung, beginnt bei `0` |
| AAD | `meshapp-rpc-secure-v1:frame` |
| Tag | 128 bit |
| Frame prefix | `enc1_` |
| Encoding | base64url ohne Padding |

Frame:

```text
enc1_<base64url_no_padding(ciphertext_plus_gcm_tag)>
```

Wenn ein Frame nicht mit `enc1_` beginnt, die Sequence ausgeschoepft ist oder die GCM-Authentifizierung fehlschlaegt, wird der Transport mit Fehler geschlossen.

## RPC Envelope

Alle RPC-Envelopes werden als kompaktes JSON ueber Gson mit `serializeNulls` serialisiert. Felder mit `null` koennen explizit vorhanden sein. Der direkte Transport sendet nach dem verschluesselten Framing ein Envelope pro Zeile. Der Router-Transport legt den verschluesselten Frame in `payload.frame`.

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

Regeln:

- `type` muss `rpc_request` sein;
- `requestId` ist erforderlich und darf nicht blank sein;
- `method` ist clientseitig erforderlich und wird vor dem Senden getrimmt;
- `params` muss ein JSON object sein; fehlt das Feld oder ist es kein object, verwendet der Host `{}`;
- der Host verarbeitet nur Methoden, die explizit in `RpcMethodRegistry` registriert sind;
- malformed JSON, non-object envelope, nicht unterstuetzter `type` und Request ohne `requestId` werden ohne Response ignoriert.

### Erfolgreiche Response

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

`result` kann object, array, primitive oder `null` sein. Die meisten aktuellen Methoden geben ein object zurueck.

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

| Code | Wann es auftritt |
|------|------------------|
| `BAD_REQUEST` | Handler hat `IllegalArgumentException` geworfen, meist wegen eines fehlenden oder ungueltigen Parameters. |
| `METHOD_NOT_FOUND` | Methode ist auf dem Host nicht registriert. |
| `CONNECTION_FAILED` | Handler hat `ConnectionException` geworfen; die root cause wird zur message ergaenzt, wenn vorhanden. |
| `INTERNAL_ERROR` | Unerwarteter Handler-Fehler oder custom `RpcException` ohne code. |
| `TIMEOUT` | Clientseitiger Timeout beim Warten auf die Response. |
| `TRANSPORT_CLOSED` | RPC client/transport wurde vor oder waehrend des Aufrufs geschlossen. |

`RpcException` innerhalb eines Host-Handlers kann einen custom code zurueckgeben; der code wird getrimmt, blank wird zu `INTERNAL_ERROR`.

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

Events sind Push-Nachrichten Host -> Remote client und haben kein `requestId`/response. Wenn `payload` fehlt, uebergibt der client den listenern einen `null` JSON value. Ein Event ohne nicht-leeres `event` wird ignoriert.

## Timeouts

| Bereich | Wert |
|---------|------|
| Default RPC call timeout | 30 Sekunden |
| Remote protocol startup `system.ping` | 5 Sekunden |
| UI remote RPC calls in forms | normalerweise 15 Sekunden |
| Direct TCP connect | 5 Sekunden |
| Router connect/auth | 8 Sekunden |
| Router ping interval | 15 Sekunden |
| Router ping timeout | 45 Sekunden |
| Lua remote form value wait | 2 Sekunden |
| Traceroute pending cleanup | 365 Sekunden |

## Allgemeine JSON-Regeln

- Erforderliche String-Felder muessen JSON primitive strings sein und duerfen nach trim nicht blank sein.
- Numeric-Felder werden als Java `int`, `long`, `double` oder `float` gelesen; Bereichspruefungen erfolgen nur dort, wo es explizit angegeben ist.
- Boolean-Felder werden nur aus JSON primitive boolean gelesen.
- Fehlende optionale string-Felder werden meist als `""` behandelt.
- Fehlende optionale number-Felder werden meist als `0` behandelt.
- Fehlende optionale boolean-Felder werden meist als `false` behandelt.
- `nodeId` hat meist die Form `!` + 8 Hex-Zeichen.
- `nodeNum` wird als Java signed `int` uebergeben; fuer unsigned-Anzeige wird `nodeId` verwendet.
- Chat timestamp und telemetry timestamp sind Unix epoch seconds.
- Packet monitor `capturedAt` ist Unix epoch milliseconds.
- Protobuf payloads, `publicKey`, `routeData` und `adminMessage` werden als normales Base64 codiert, nicht base64url.

## Host State Model

Der Remote-RPC-Client ruft Host-Methoden auf, und der Host wendet sie auf seinen lokalen Zustand an:

- `connection.*` verwaltet die Verbindungsliste des Hosts.
- Die meisten `chat.*`-, `node.*`-, `telemetry.*`-, `settings.*`- und `admin.*`-Methoden benoetigen eine ausgewaehlte und verbundene Host-Verbindung.
- Eine clientseitige Remote-RPC-Verbindung fuehrt `system.ping` aus; danach speichert `RemoteRpcState` den `RpcClient` und das ping-result.
- Der Remote-RPC-Transport akzeptiert keine raw radio bytes ueber `TransportConnection.sendBytes`.

## Methoden

### `system.ping`

Prueft die Verfuegbarkeit des Host RPC.

Params:

```json
{}
```

Result:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `app` | string | Immer `MeshApp`. |
| `version` | string | Anwendungsversion. |
| `versionCode` | number | Numeric version code. |
| `remoteRpc` | boolean | Immer `true`. |
| `activeConnections` | number | Anzahl aktiver Verbindungen auf dem Host. |

### `connection.list`

Gibt gespeicherte Host-Verbindungen zurueck.

Params: `{}`.

Result:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `selectedConnectionId` | string/null | ID der ausgewaehlten Host-Verbindung. |
| `items` | `Connection[]` | Host-Verbindungen. |

`Connection`:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `id` | string | Stable connection profile id. |
| `name` | string | Profilname. |
| `type` | string | `TCP`, `SERIAL`, `BLE`, `REMOTE_RPC`. |
| `protocol` | string | `MESHTASTIC`, `MESHCORE_KISS`, `MESHCORE_COMPANION`, `REMOTE_RPC`. |
| `connected` | boolean | Ob aktuell verbunden. |
| `reconnecting` | boolean | Ob reconnect laeuft. |
| `selected` | boolean | Ob auf dem Host ausgewaehlt. |
| `nodeId` | string/null | Node id, falls bekannt. |
| `address` | string | Menschenlesbare Adresse. |

### `connection.connect`

Verbindet das Host-Profil und waehlt es aus.

Params:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `id` | string | ja | Connection id. |

Result: dieselbe shape wie `connection.list`, plus `connection`, wenn das Profil gefunden wurde.

### `connection.disconnect`

Trennt ein Host-Profil.

Params: `{"id":"..."}`.

Result: dieselbe shape wie `connection.connect`.

### `connection.select`

Waehlt ein Host-Profil aus, ohne eine Verbindung herzustellen.

Params: `{"id":"..."}`.

Result: dieselbe shape wie `connection.connect`.

## Chat-Methoden

Die Chat-API verwendet:

- `chatType`: `channel` oder `dm`;
- `chatKey`: fuer `channel` der Kanalindex als Zeichenfolge, z. B. `"0"`; fuer `dm` die peer `nodeId`, z. B. `"!12345678"`;
- `ownerNodeId`: lokale node id der ausgewaehlten Host-Verbindung.

### `chat.list`

Gibt die Liste der Kanaele und direkten Dialoge zurueck.

Params: `{}`.

Result:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `ownerNodeId` | string | Lokale node id der Host-Verbindung. |
| `connectionId` | string | ID der ausgewaehlten Host-Verbindung. |
| `items` | `ChatItem[]` | Chats. |

`ChatItem`:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `type` | string | `CHANNEL` oder `DIRECT_MESSAGE`. |
| `displayName` | string | Chatname. |
| `avatarText` | string | Avatartext. |
| `avatarColor` | string | Avatarfarbe, z. B. `#5B8DEF`. |
| `lastMessageText` | string | Letzter Text. |
| `lastMessageTime` | number | Unix epoch seconds. |
| `unreadCount` | number | Ungelesene. |
| `channelIndex` | number | Kanalindex oder `0`. |
| `peerNodeId` | string | Peer node id fuer DM oder `""`. |
| `muted` | boolean | Muted flag. |

### `chat.messages`

Laedt Chat-Nachrichten.

Params:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `chatType` | string | ja | `channel` oder `dm`. |
| `chatKey` | string | ja | Channel index string oder peer node id. |
| `limit` | number | nein | 1..200, default `50`. |
| `beforeDbId` | number | nein | Wenn > 0, Nachrichten vor dieser DB id laden. |
| `afterDbId` | number | nein | Wenn > 0 und `beforeDbId == 0`, Nachrichten nach dieser DB id laden. |

Result:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `ownerNodeId` | string | Lokale node id. |
| `items` | `Message[]` | Nachrichten. |

### `chat.markRead`

Markiert einen Chat als gelesen.

Params:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `chatType` | string | ja |
| `chatKey` | string | ja |

Result: `chat.list`.

### `chat.send`

Sendet eine Nachricht.

Params:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `chatType` | string | ja | `channel` oder `dm`. |
| `chatKey` | string | ja | Channel index string oder peer node id. |
| `text` | string | ja | Nicht-leerer Text. |
| `replyId` | number | nein | Packet id der beantworteten Nachricht; kann negativ sein. |
| `clientRequestId` | string | nein | Idempotency key. Eine Wiederholung innerhalb von 5 Minuten gibt die bereits erzeugte Nachricht zurueck. |

Result:

| Feld | Typ |
|------|-----|
| `message` | `Message` |
| `chat` | Ergebnis von `chat.list` |

Dedup-cache-Limit: 512 Eintraege, TTL 5 Minuten.

### `chat.retry`

Wiederholt das Senden einer fehlgeschlagenen outgoing-Nachricht.

Params:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `chatType` | string | ja | `channel` oder `dm`. |
| `chatKey` | string | ja | Chat key. |
| `dbId` | number | bedingt | DB id der Nachricht. Benoetigt `dbId > 0` oder `packetId != 0`. |
| `packetId` | number | bedingt | Packet id der Nachricht. |

Result: wie `chat.send`.

Einschraenkungen: funktioniert nur fuer fehlgeschlagene outgoing-Nachrichten und wird fuer MeshCore Companion runtime nicht unterstuetzt.

### `chat.react`

Sendet eine Reaktion auf eine Nachricht.

Params:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `chatType` | string | ja |
| `chatKey` | string | ja |
| `targetPacketId` | number | ja, nicht `0` |
| `emoji` | string | ja |

Result:

| Feld | Typ |
|------|-----|
| `message` | aktualisierte target `Message` |
| `chat` | Ergebnis von `chat.list` |

Einschraenkung: Reaktionen werden fuer MeshCore Companion runtime nicht unterstuetzt.

### `Message`

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `fromNodeId` | string | Sender node id. |
| `toNodeId` | string | Recipient node id; Broadcast normalerweise `!ffffffff`. |
| `channelIndex` | number | Kanalindex. |
| `text` | string | Text. |
| `timestamp` | number | Unix epoch seconds. |
| `outgoing` | boolean | Nachricht wurde vom lokalen Geraet gesendet. |
| `status` | string/null | `SENDING`, `DELIVERED`, `CONFIRMED`, `FAILED`. |
| `packetId` | number | Meshtastic packet id. |
| `errorReason` | string/null | Grund fuer Sendefehler. |
| `replyId` | number | Packet id der urspruenglichen Nachricht. |
| `replyText` | string/null | Text der urspruenglichen Nachricht. |
| `replyToOutgoing` | boolean | Reply target war outgoing. |
| `hopStart` | number | Urspruengliches hop limit. |
| `hopLimit` | number | Verbleibendes hop limit. |
| `rxRssi` | number | RSSI. |
| `rxSnr` | number | SNR. |
| `senderName` | string | Aufgeloester sender display name. |
| `viaMqtt` | boolean | Nachricht kam ueber MQTT. |
| `systemMessage` | boolean | Systemnachricht. |
| `dbId` | number | Lokale DB id. |
| `reactions` | `Reaction[]` | Reaktionen. |

`Reaction`:

| Feld | Typ |
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

## Node-Methoden

### `node.list`

Gibt nodes der ausgewaehlten Host-Verbindung zurueck.

Params:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `includeFavorites` | boolean | nein | Offline favorites aus dem Cache hinzufuegen. |
| `includeIgnored` | boolean | nein | Offline ignored nodes aus dem Cache hinzufuegen. |

Result:

| Feld | Typ |
|------|-----|
| `ownerNodeId` | string |
| `items` | `Node[]` |

### `node.get`

Gibt einen node zurueck.

Params:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `nodeId` | string | ja |

Result: `{"ownerNodeId":"...","items":[Node]}`.

### `node.traceroute`

Startet traceroute zu einem node. Das Ergebnis kommt als separates Event `node.traceroute.result`.

Params:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `nodeId` | string | bedingt | Target node id. |
| `nodeNum` | number | bedingt | Target node num. Benoetigt `nodeId` oder non-zero `nodeNum`. |
| `requestId` | string | nein | Wenn fehlt, erzeugt der Host eine UUID. |

Result:

| Feld | Typ |
|------|-----|
| `requestId` | string |
| `targetNodeNum` | number |
| `targetNodeId` | string |
| `targetName` | string |
| `nodeNames` | object map unsigned node num string -> display name |

Einschraenkung: benoetigt einen protocol handler; nicht verfuegbar fuer ausgewaehlte runtimes, die kein traceroute senden koennen.

### `node.refresh`

Fordert frische UserInfo fuer einen node an.

Params:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `nodeNum` | number | ja, nicht `0` |
| `includeFavorites` | boolean | nein |
| `includeIgnored` | boolean | nein |

Result: `node.list` mit denselben Filter-Flags.

### `node.delete`

Loescht einen node aus runtime/cache.

Params:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `nodeId` | string | ja | Node id. |
| `nodeNum` | number | nein | Wenn non-zero, wird der node aus dem aktuellen `DeviceState` entfernt. |
| `includeFavorites` | boolean | nein | Wird an das result `node.list` uebergeben. |
| `includeIgnored` | boolean | nein | Wird an das result `node.list` uebergeben. |

Result: `node.list`.

### `node.favorite`

Aktiviert/deaktiviert das favorite flag.

Params:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `nodeId` | string | ja |
| `enabled` | boolean | nein, default `false` |
| `includeFavorites` | boolean | nein |
| `includeIgnored` | boolean | nein |

Result: `node.list`.

### `node.ignored`

Aktiviert/deaktiviert das ignored flag.

Params: wie `node.favorite`.

Result: `node.list`.

### `Node`

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `nodeNum` | number | Java int node number. |
| `nodeId` | string | Stable id der Form `!12345678`. |
| `longName` | string |
| `shortName` | string |
| `latitude` | number |
| `longitude` | number |
| `altitude` | number |
| `snr` | number |
| `lastHeard` | number | Unix epoch seconds oder runtime value. |
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
| `publicKey` | string | Base64 public key oder `""`. |
| `unmessagable` | boolean |
| `licensed` | boolean |
| `favorite` | boolean |
| `ignored` | boolean |

## Telemetry-Methoden

### `telemetry.dashboard`

Gibt das telemetry dashboard fuer einen node zurueck.

Params:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `nodeId` | string | nein | Wenn blank, wird die lokale node id der Host-Verbindung verwendet. |
| `sinceEpoch` | number | nein | Untere Grenze in Unix epoch seconds, default `0`. |
| `maxFutureTs` | number | nein | Obere Grenze in Unix epoch seconds. Wenn `<= 0`, verwendet der Host `now + 300`. |

Result:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `ownerNodeId` | string | Lokale node id. |
| `nodeId` | string | Node id, fuer die telemetry geladen wurde. |
| `entries` | `TelemetryEntry[]` | Telemetry des ausgewaehlten node. |
| `qualityEntries` | `TelemetryEntry[]` | Quality telemetry des Netzes. |

`TelemetryEntry` wird per Gson aus dem Java model serialisiert. Aktuelle Felder:

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

## Packet-Monitor-Methoden

### `packetMonitor.page`

Gibt eine LoRa-packet-monitor-Seite zurueck.

Params:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `request` | string | nein | `older`, `newer` oder ein anderer/blank Wert fuer latest. |
| `query` | `PacketQuery` | nein | Filter query. |
| `cursor` | `PageCursor` | nein | Cursor fuer older/newer. |
| `limit` | number | nein | 1..10000, default `200`. |

`PacketQuery`:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `direction` | string | `INCOMING`, `OUTGOING`, `INTERNAL` oder fehlend. |
| `packetType` | string | Packet type filter. |
| `transportMechanism` | string | Transport filter. |
| `searchText` | string | Full-text filter. |
| `capturedAtFromMillis` | number | Untere capturedAt-Grenze. |
| `capturedAtToMillis` | number | Obere capturedAt-Grenze. |

`PageCursor`:

| Feld | Typ |
|------|-----|
| `capturedAt` | number |
| `id` | number |

Result:

| Feld | Typ |
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

| Feld | Typ |
|------|-----|
| `matching` | number |
| `total` | number |

### `packetMonitor.captureState`

Params: `{}`.

Result: `{"enabled": boolean}`.

### `packetMonitor.start`

Aktiviert capture.

Params: `{}`.

Result: `{"enabled": true}`.

### `packetMonitor.stop`

Deaktiviert capture.

Params: `{}`.

Result: `{"enabled": false}`.

### `packetMonitor.clear`

Leert den packet monitor.

Params: `{}`.

Result: `{"matching":0,"total":0}`.

### `PacketLogEntry`

Wird per Gson aus dem immutable Java model serialisiert:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
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

## Lua-Methoden

Lua RPC verwaltet MeshApp-IDE-Skripte auf dem Host. `scriptId` ist erforderlich und muss in allen Methoden, in denen es angegeben ist, `> 0` sein.

### `lua.list`

Params: `{}`.

Result: `{"items": LuaScript[]}`.

### `lua.get`

Params: `{"scriptId": number}`.

Result: `{"script": LuaScript}`.

### `lua.draft`

Erzeugt ein draft script.

Params: `{}`.

Result: `{"script": LuaScript}`.

### `lua.createDefault`

Erzeugt ein script mit Standardwerten.

Params: `{}`.

Result: `{"script": LuaScript}`.

### `lua.create`

Params:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `name` | string | ja |
| `code` | string | nein |
| `enabled` | boolean | nein |
| `icon` | string | nein |
| `nodeId` | string | nein |
| `botType` | string | nein, default `AIR_BOT` |
| `automationName` | string | nein |
| `description` | string | nein |
| `author` | string | nein |

Result: `{"script": LuaScript}`.

### `lua.save`

Params:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `scriptId` | number | ja |
| `name` | string | ja |
| `code` | string | nein |
| `enabled` | boolean | nein |

Result: `{"script": LuaScript}`.

### `lua.saveSettings`

Params: `scriptId` + Felder aus `lua.create`, ausser `code`.

Result: `{"script": LuaScript}`.

### `lua.delete`

Stoppt ein script und loescht es.

Params: `{"scriptId": number}`.

Result: `lua.list`.

### `lua.importJson`

Params:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `json` | string | ja |

Result:

| Feld | Typ |
|------|-----|
| `script` | `LuaScript` |
| `updated` | boolean |

### `lua.importExport`

Params:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `exportFile` | object | Gson-Serialisierung von `LuaScriptService.LuaScriptExportFile`. |

Result: wie `lua.importJson`.

### `lua.export`

Params: `{"scriptId": number}`.

Result: `{"json": string}`.

### `lua.runningState`

Params: `{"scriptId": number}`.

Result:

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `running` | boolean |
| `paused` | boolean |

### `lua.run`

Startet ein script. Fuer `EXTENSION` wird die remote form bridge verwendet.

Params: `{"scriptId": number}`.

Result: running state.

### `lua.automation.run`

Startet einen automation bot command.

Params:

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `command` | `LuaAutomationCommand` |

`LuaAutomationCommand`:

| Feld | Typ |
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

Liefert dem runtime das Ergebnis einer node-Auswahl fuer ein zuvor gesendetes Event `lua.ui.nodePick.request`.

Params:

| Feld | Typ |
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

Liefert ein Event einer extension form component.

Params:

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `componentId` | string |
| `type` | string |
| `value` | any |
| `text` | string |

Result: running state.

### `lua.form.valueResult`

Antwort des Remote client auf den Host-Befehl `lua.form.command` mit `command="value"`.

Params:

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `requestId` | string |
| `value` | any |

Result: `{}`.

### `lua.debug`

Startet ein script im debug-Modus.

Params:

| Feld | Typ |
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

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `snapshot` | `LuaDebugSnapshot`, optional | Fehlt, wenn es keinen snapshot gibt. |

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

| Feld | Typ | Pflicht |
|------|-----|---------|
| `scriptId` | number | ja |
| `key` | string | ja |
| `value` | string | nein |

Result: `{}`.

### `lua.kv.delete`

Params: `{"scriptId": number, "key": "..."}`.

Result: `{"deleted": boolean}`.

### `lua.kv.clear`

Params: `{"scriptId": number}`.

Result: `{}`.

### `LuaScript`

| Feld | Typ | Beschreibung |
|------|-----|--------------|
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
| `running` | boolean | Nur wenn das result aus dem runtime service gesammelt wurde. |
| `paused` | boolean | Nur wenn das result aus dem runtime service gesammelt wurde. |

### `LuaFormComponentSpec`

Die extension form spec wird als JSON object serialisiert:

`id`, `type`, `parentId`, `text`, `prompt`, `value`, `items`, `min`, `max`, `disabled`, `visible`, `style`, `orientation`, `width`, `height`, `minWidth`, `minHeight`, `maxWidth`, `maxHeight`, `readOnly`, `wrap`, `monospace`, `grow`, `rows`, `chartType`, `xLabel`, `yLabel`, `xType`, `legend`, `symbols`, `series`.

`series[]`: `name`, `color`, `points`; `points[]`: `x`, `y`.

## Settings-Methoden

`settings.*` arbeitet mit dem lokalen node der ausgewaehlten Host-Verbindung. Viele Methoden versuchen zuerst, den session passkey zu erhalten. Die meisten Methoden geben einen settings snapshot zurueck.

### `settings.snapshot`

Params: `{}`.

Result: `AdminSnapshot` fuer den lokalen Host node.

### `settings.saveOwner`

Params:

| Feld | Typ |
|------|-----|
| `longName` | string |
| `shortName` | string |
| `isLicensed` | boolean |

Result: `AdminSnapshot`.

### `settings.saveConfigChanges`

Params:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `configs` | string[] | Base64 `ConfigProtos.Config`. |
| `moduleConfigs` | string[] | Base64 `ModuleConfigProtos.ModuleConfig`. |
| `channels` | string[] | Base64 `ChannelProtos.Channel`. |

Result: `AdminSnapshot`.

### `settings.setFixedPosition`

Params:

| Feld | Typ |
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

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `command` | string | `reboot`, `shutdown`, `syncTime`, `enterDfuMode`. |
| `delaySeconds` | number | Fuer `reboot` und `shutdown`, default `0`. |
| `epochSeconds` | number | Fuer `syncTime`. |

Result: `AdminSnapshot`.

## Remote-Admin-Methoden

`admin.*` sendet Meshtastic remote admin packets ueber die ausgewaehlte Host-Verbindung an einen anderen node.

Allgemeine Anforderungen:

- die ausgewaehlte Host-Verbindung muss einen `ProtocolHandler` haben;
- target wird ueber `nodeId` oder non-zero `nodeNum` angegeben;
- target darf nicht der lokale Host node sein;
- target node muss einen public key haben.

Allgemeine target params:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `nodeId` | string | bedingt |
| `nodeNum` | number | bedingt |

### `admin.load`

Params: target params.

Result: `AdminSnapshot`.

### `admin.requestConfig`

Params:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `nodeId`/`nodeNum` | string/number | Target. |
| `type` | string | Enum-Name `AdminProtos.AdminMessage.ConfigType`, ausser `UNRECOGNIZED`. |

Result: `AdminSnapshot`.

### `admin.requestModuleConfig`

Params:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `nodeId`/`nodeNum` | string/number | Target. |
| `type` | string | Enum-Name `AdminProtos.AdminMessage.ModuleConfigType`, ausser `UNRECOGNIZED`. |

Result: `AdminSnapshot`.

### `admin.saveOwner`

Params: target params + `longName`, `shortName`, `isLicensed`.

Result: `{"ok": true}`.

### `admin.saveConfigChanges`

Params: target params + `configs`, `moduleConfigs`, `channels` wie in `settings.saveConfigChanges`.

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

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `nodeId`/`nodeNum` | string/number | Target. |
| `command` | string | Siehe Liste unten. |
| `delaySeconds` | number | Fuer `reboot`/`shutdown`. |
| `epochSeconds` | number | Fuer `syncTime`. |
| `location` | string | Fuer backup commands: enum `AdminProtos.AdminMessage.BackupLocation`, ausser `UNRECOGNIZED`. |
| `preserveFavorites` | boolean | Fuer `resetNodeDb`. |

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

Result: `AdminSnapshot` plus Feld:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `adminMessage` | string | Base64 `AdminProtos.AdminMessage`. |

### `AdminSnapshot`

| Feld | Typ | Beschreibung |
|------|-----|--------------|
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

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `key` | string |
| `state` | string | `SENT`, `RECEIVED`, `FAILED`. |
| `detail` | string |

## Host-Events

### `message.incoming`

Wird bei einer eingehenden Nachricht veroeffentlicht. Die remote runtime verwendet es fuer desktop notification und unread indicator.

Payload:

| Feld | Typ |
|------|-----|
| `chatType` | string |
| `chatKey` | string |
| `message` | `Message` |
| `title` | string |
| `body` | string |

### `message.changed`

Wird bei einer non-notification-Chat-Aenderung veroeffentlicht, z. B. Reaktionen oder metadata.

Payload:

| Feld | Typ |
|------|-----|
| `chatType` | string |
| `chatKey` | string |
| `targetPacketId` | number |
| `message` | `Message`, optional |

### `message.status`

Wird veroeffentlicht, wenn sich der delivery status einer outgoing-Nachricht aendert.

Payload:

| Feld | Typ |
|------|-----|
| `chatType` | string |
| `chatKey` | string |
| `packetId` | number |
| `status` | string/null |
| `errorReason` | string/null |
| `message` | `Message` |

### `node.traceroute.result`

Payload:

| Feld | Typ |
|------|-----|
| `requestId` | string |
| `status` | string, aktuell `ok` |
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

| Feld | Typ |
|------|-----|
| `type` | string: `INFO`, `OUTPUT`, `WARNING`, `ERROR`, `STARTED`, `STOPPED`, `UI_BOT_NOTICE`, `DEBUG_PAUSED`, `DEBUG_RESUMED` |
| `scriptId` | number |
| `message` | string |
| `errorMessage` | string, optional |
| `payload` | object, optional; fuer `UI_BOT_NOTICE` ist es `LuaUiBotNotice` |

`LuaUiBotNotice`: `scriptId`, `source`, `name`, `chatType`, `chatKey`, `text`.

### `lua.ui.nodePick.request`

Payload:

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `requestId` | string |
| `source` | string |
| `name` | string |
| `prompt` | string |
| `query` | string |
| `chatType` | string |
| `chatKey` | string |

Der Remote client muss mit der Methode `lua.ui.nodeSelection` antworten.

### `lua.form.command`

Payload:

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `command` | string: `show`, `title`, `clear`, `add`, `update`, `remove`, `value` |
| `requestId` | string |
| `componentId` | string |
| `title` | string |
| `script` | `LuaScript` |
| `spec` | `LuaFormComponentSpec`, optional |

Fuer `command="value"` muss der Remote client `lua.form.valueResult` mit derselben `requestId` aufrufen. Die aktuelle Implementierung der remote form bridge prueft availability ueber den direct server state, daher haengt die tatsaechliche Verfuegbarkeit von extension forms vom aktiven host endpoint ab.

## Beispiele

### Request/response

Plaintext-RPC-Envelope vor encryption:

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

Nach `auth_ok` enthaelt die tatsaechliche TCP-Zeile kein JSON mehr:

```text
enc1_xs3B9XjglG8FjQg4N0rQLj2r8eOJlYkq7H...
```

## Kompatibilitaet

- Neue methods werden nur durch explizite Registrierung in `RpcMethodRegistry` hinzugefuegt.
- Unknown methods muessen clientseitig immer als `METHOD_NOT_FOUND` behandelt werden.
- Clients muessen unbekannte Felder in result/event payloads ignorieren.
- Enum-Felder sollten mit tolerant parsing verarbeitet werden: Eine neue Host-Version kann einen Wert senden, den ein alter client nicht kennt.
- Fuer protobuf payloads ist der Vertrag Base64 des binaeren protobuf, nicht JSON protobuf mapping.
- Ein Remote-RPC-Profil muss `ProtocolType.REMOTE_RPC` verwenden; `ConnectionManager` lehnt jedes andere protocol fuer `ConnectionType.REMOTE_RPC` ab.
