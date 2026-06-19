# Поддержка MeshCore

**Язык:** Русский | [English](meshcore-support.md) | [Deutsch](meshcore-support.de.md)

MeshApp поддерживает MeshCore-устройства через два разных протокольных режима:

- **MeshCore KISS modem protocol** поверх TCP/Serial byte stream.
- **MeshCore Companion Protocol** поверх BLE RX/TX GATT characteristics или raw TCP/Serial byte stream.

Это отдельные protocol runtime-ы рядом с Meshtastic, а не замена Meshtastic-логики. KISS и Companion несовместимы на уровне framing: BLE-подключение MeshCore не использует KISS frames, а TCP/Serial Companion endpoint должен отдавать raw Companion packets без KISS-обёртки.

## Что изменилось

- Новые профили подключений по умолчанию используют `ProtocolType.MESHTASTIC`; legacy-профили без поля `protocol` трактуются как Meshtastic.
- Добавлены `ProtocolType.MESHCORE_KISS`, `ProtocolType.MESHCORE_COMPANION`, `MeshCoreKissProtocolRuntime` и `MeshCoreCompanionProtocolRuntime`.
- Добавлено KISS-фреймирование для TCP и Serial transport-ов.
- Добавлен `FrameFormat.MESHCORE_COMPANION` и stream parser для MeshCore Companion packets на TCP/Serial.
- Добавлен BLE profile для MeshCore Companion service/RX/TX UUID.
- Протокол выбирается явно перед запуском runtime-а: `MeshCore KISS` или `MeshCore Companion` нужно выбрать в профиле подключения.
- В карточке подключения отображается выбранный/активный протокол.
- MeshCore Companion теперь заполняет общий UI state для экранов Chat, Nodes, Dashboard, Settings и LoRa Monitor.
- Добавлена отправка канальных сообщений и DM через MeshCore Companion Protocol.
- Добавлена синхронизация MeshCore contacts, channel info и входящих сообщений из очереди Companion Protocol.
- Существующие Meshtastic-профили остаются совместимыми. Legacy-профили без поля `protocol` по-прежнему считаются `MESHTASTIC`.

## Поддерживаемые транспорты

| Transport | MeshCore режим | Примечание |
|-----------|---------------|------------|
| Serial / USB | KISS | Стандартные настройки MeshCore KISS serial: 115200 baud, 8N1, без flow control. |
| TCP | KISS | Работает с endpoint-ами, которые отдают тот же KISS byte stream поверх TCP. |
| Serial / USB | Companion Protocol | Работает с endpoint-ами, которые отдают raw Companion packets без KISS framing. |
| TCP | Companion Protocol | Работает с bridge/server endpoint-ами, которые передают raw Companion packets через byte stream. |
| BLE | Companion Protocol | Использует отдельный BLE service/RX/TX UUID, TX notifications и raw Companion packets. |

## Как выбирается протокол

Для новых профилей `ConnectionEntry.protocol` по умолчанию равен `MESHTASTIC`.

1. MeshApp открывает выбранный transport.
2. TCP/Serial transport сразу получает `FrameFormat`, соответствующий сохранённому `ProtocolType`.
3. BLE transport сразу выбирает GATT profile сохранённого `ProtocolType`.
4. `ConnectionManager` запускает runtime из `ProtocolRegistry`: `MeshtasticProtocolRuntime`, `MeshCoreKissProtocolRuntime` или `MeshCoreCompanionProtocolRuntime`.
5. Legacy-профили без поля `protocol` используют `MESHTASTIC`.

Для MeshCore нужно явно выбрать `MeshCore KISS` или `MeshCore Companion` в поле протокола при создании подключения.

## MeshCore Companion

MeshCore Companion Protocol использует не KISS framing, а собственный binary packet protocol. Согласно документации MeshCore, BLE companion devices рекламируют Nordic UART-like service:

- Service UUID: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- RX characteristic, App -> Firmware: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- TX characteristic, Firmware -> App: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

Для BLE в MeshApp добавлен отдельный profile для этих UUID:

- scan ищет service UUID выбранного profile-а;
- connect сразу использует выбранный profile;
- для MeshCore Companion transport пишет raw Companion packets в RX characteristic;
- входящие данные приходят из TX characteristic через notifications;
- `MeshCoreCompanionProtocolRuntime` отправляет `APP_START`, разбирает `SELF_INFO`, `DEVICE_INFO`, `BATTERY`, contacts, channel info и queued messages.

Для TCP/Serial используется `FrameFormat.MESHCORE_COMPANION`: transport передаёт raw Companion packets в общий runtime. Так как официальный Companion transport является packet-boundary based BLE GATT, byte stream parser делает best-effort разбор: fixed-size responses отдаёт сразу, а variable-size responses завершает по inter-byte silence/read timeout.

Companion runtime создаёт совместимый `DeviceState`, поэтому существующие экраны MeshApp могут показывать MeshCore contacts как nodes, MeshCore channels как chats, battery voltage как dashboard telemetry, а raw Companion packets как записи LoRa Monitor.

## Объём поддержки MeshCore KISS

Текущий MeshCore runtime выполняет базовый KISS modem handshake и читает metadata устройства через MeshCore `SetHardware` extensions.

Сейчас собирается такое состояние:

- имя устройства
- identity public key, доступный для UI как короткий `mc:<12 hex>`
- версия прошивки
- radio parameters: frequency, bandwidth, spreading factor, coding rate
- transmit power
- напряжение батареи
- packet statistics
- последние RX metadata: RSSI и SNR
- последний TX status
- последний MeshCore error code

KISS runtime остаётся modem/metadata-интеграцией. Chat, DM, contacts и channel workflow реализованы через MeshCore Companion Protocol, потому что именно он предоставляет команды приложения для Companion-клиента.

## Объём поддержки MeshCore Companion

Текущий MeshCore Companion runtime выполняет `APP_START` handshake и собирает:

- self-info packet;
- public key, доступный в runtime полностью и в UI как короткий `mc:<12 hex>`;
- имя устройства из self-info;
- contacts list из `CONTACTS_START` / `CONTACT` / `CONTACTS_END`;
- channel info из `CHANNEL_INFO`;
- входящие channel messages и contact messages, включая V3 variants;
- исходящие channel messages и DM;
- firmware protocol version;
- max contacts / max channels, если устройство отдаёт device-info v3+;
- BLE PIN, firmware build, model и firmware version из device-info v3+;
- battery voltage;
- storage usage, если оно присутствует в battery response;
- последний Companion error code.

Поддержка экранов:

- **Chat**: показывает MeshCore channels, сохраняет историю в общей H2-базе, отправляет channel messages и DM. Реакции, traceroute и Meshtastic bot-команды для MeshCore отключены или показывают локальную информацию.
- **Nodes**: показывает contacts из MeshCore Companion как ноды с public key prefix, именем, ролью, координатами и временем последнего advert, если эти поля пришли от устройства.
- **DM**: личные чаты создаются по MeshCore contact id `mc:<12 hex>` и отправляются через `SEND_TXT_MSG`.
- **Dashboard**: показывает battery voltage как telemetry entry для локального MeshCore-устройства.
- **Settings**: показывает read-only дерево MeshCore metadata, radio-параметры, storage и каналы. Запись Meshtastic Admin protobuf-конфига для MeshCore не выполняется.
- **LoRa Monitor**: пишет входящие и исходящие raw MeshCore Companion packets с transport mechanism `MESHCORE_COMPANION`, отдельными типами packet-а и HEX/ASCII preview.

## KISS-фреймирование

MeshCore KISS использует стандартное KISS TNC framing:

| Byte | Значение |
|------|----------|
| `0xC0` | `FEND`, разделитель frame-ов |
| `0xDB` | `FESC`, escape byte |
| `0xDC` | escaped `FEND` |
| `0xDD` | escaped `FESC` |

MeshApp передаёт протокольному runtime-у уже unescaped frame body:

```text
[type byte][payload...]
```

Для MeshCore metadata type byte использует KISS command `SetHardware` (`0x06`), а первый byte payload-а является MeshCore sub-command. Standard data frames (`0x00`) парсер принимает, но текущий MeshCore runtime только логирует их и пока не декодирует MeshCore packet payload в application models.

## Поведение в интерфейсе

- Пользователь создаёт обычное TCP-, Serial- или BLE-подключение.
- Protocol по умолчанию равен `Meshtastic`; для MeshCore пользователь явно выбирает `MeshCore KISS` или `MeshCore Companion`.
- После нажатия **Подключить** MeshApp запускает runtime выбранного протокола.
- В карточке подключения отображается `Meshtastic`, `MeshCore KISS` или `MeshCore Companion`.
- Для MeshCore Companion открываются Chat, Nodes, Dashboard, Settings и LoRa Monitor. Функции, которые завязаны на Meshtastic Admin/Traceroute/Reaction protobuf, остаются недоступны и явно сообщают об этом в UI.
- Для MeshCore KISS показывается подключение и metadata modem runtime-а; application screens используют Companion Protocol.

## Заметки для разработки

Основные классы:

- `com.meshtastic.client.connection.KissFrameParser`
- `com.meshtastic.client.connection.MeshCoreCompanionFrameParser`
- `com.meshtastic.client.protocol.meshcore.MeshCoreKissProtocol`
- `com.meshtastic.client.protocol.meshcore.MeshCoreKissProtocolRuntime`
- `com.meshtastic.client.protocol.meshcore.MeshCoreKissState`
- `com.meshtastic.client.protocol.meshcore.MeshCoreKissFrames`
- `com.meshtastic.client.connection.ble.BleProtocolProfile`
- `com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocol`
- `com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocolRuntime`
- `com.meshtastic.client.protocol.meshcore.MeshCoreCompanionState`
- `com.meshtastic.client.protocol.meshcore.MeshCoreCompanionFrames`
- `com.meshtastic.client.forms.FormChatData`
- `com.meshtastic.client.forms.FormChatUi`
- `com.meshtastic.client.forms.FormSetting`
- `com.meshtastic.client.service.PacketMonitorService`

Тестами покрыты:

- KISS escaping и parsing
- Companion packet parsing на byte stream transport-ах
- регистрация протокола в registry
- запуск MeshCore KISS runtime при явном выборе протокола
- запуск MeshCore Companion runtime при явном выборе протокола
- end-to-end TCP/BLE runtime selection через `ConnectionManager`
- MeshCore Companion bridge в `DeviceState` для Chat/Nodes/Dashboard
- отправка MeshCore channel messages и DM
- raw MeshCore Companion entries в LoRa Monitor

Ссылки на спецификации:

- MeshCore KISS modem protocol: <https://github.com/meshcore-dev/MeshCore/blob/main/docs/kiss_modem_protocol.md>
- MeshCore Companion Protocol: <https://docs.meshcore.io/companion_protocol/>
- MeshCore packet format: <https://docs.meshcore.io/packet_format/>
