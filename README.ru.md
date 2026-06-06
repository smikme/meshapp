<p align="center">
  <img src="docs/logo/MeshApp.png" width="128" alt="MeshApp Logo"/>
</p>

<h1 align="center">MeshApp</h1>

<p align="center">
  Настольный клиент для mesh-сетей
  <a href="https://meshtastic.org">Meshtastic</a> и MeshCore
  <br/>
  <b>Java 25 &nbsp;·&nbsp; JavaFX &nbsp;·&nbsp; Protobuf &nbsp;·&nbsp; LoRa</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-orange?logo=openjdk" alt="Java 25"/>
  <img src="https://img.shields.io/badge/JavaFX-25.0.3-blue?logo=java" alt="JavaFX"/>
  <img src="https://img.shields.io/badge/Platform-Win%20%7C%20macOS%20%7C%20Linux-brightgreen" alt="Platform"/>
  <img src="https://img.shields.io/badge/License-AGPL--3.0-blue" alt="License"/>
  <a href="https://t.me/+SRaOd1gftoo5MWRi">
    <img src="https://img.shields.io/badge/Telegram-@MeshAppClient-blue?logo=telegram" alt="Telegram">
  </a>
</p>

<p align="center">
  Основная площадка разработки:
  <a href="https://git.privatepractice.app/covox/meshapp">git.privatepractice.app/covox/meshapp</a>
</p>

<div align="right">

<strong>Русский</strong> | <a href="README.md">English</a>

</div>

---

## О проекте

MeshApp — настольный клиент для устройств Meshtastic и MeshCore. Он подключается к устройству по TCP, USB/Serial или BLE.

В приложении можно обмениваться сообщениями, смотреть ноды на карте, просматривать телеметрию, менять настройки устройства, анализировать LoRa-пакеты и запускать Lua-скрипты.

Новые профили подключения по умолчанию используют Meshtastic. Для MeshCore в профиле нужно явно выбрать режим работы:

- `MeshCore KISS` для TCP и Serial / USB
- `MeshCore Companion` для BLE, TCP и Serial / USB

Тип подключения и протокол выбираются отдельно. Например, можно подключиться по TCP к Meshtastic-устройству, по USB к MeshCore KISS modem или по BLE к устройству с MeshCore Companion Protocol.

![Архитектура MeshApp](docs/meshapp-architecture.jpg)

---

## Что умеет приложение

- Чаты в mesh-каналах и личные сообщения.
- Список нод, поиск, фильтры, избранные и игнорируемые ноды.
- Карта с онлайн- и офлайн-тайлами, отображением нод и сохранённых traceroute-маршрутов.
- Телеметрия устройства и история показаний.
- Редактирование конфигурации Meshtastic-устройства.
- Подключение по TCP, USB/Serial и BLE.
- Поддержка Meshtastic, MeshCore KISS и MeshCore Companion.
- Просмотр логов и LoRa-пакетов.
- Lua-скрипты, боты, редактор, отладчик, KV-хранилище и магазин скриптов.
- Локальное хранение сообщений, телеметрии, нод, трейсов, скриптов и журнала пакетов.
- Терминальный режим без JavaFX-интерфейса.

---

## Чаты

<p align="center">
  <img src="docs/screenshots/chat-b.jpg" width="49%" alt="Chat — тёмная тема"/>
  <img src="docs/screenshots/chat-w.jpg" width="49%" alt="Chat — светлая тема"/>
</p>

<p align="center">
  <img src="docs/screenshots/chat-node-info-b.jpg" width="49%" alt="Информация об узле в чате — тёмная тема"/>
  <img src="docs/screenshots/chat-node-info-w.jpg" width="49%" alt="Информация об узле в чате — светлая тема"/>
</p>

В чатах доступны mesh-каналы и личные диалоги. Сообщения сохраняются локально, поддерживаются ответы, реакции, статусы доставки ACK/NAK и счётчики непрочитанных.

Из строки ввода можно запускать команды `@tracebot` и `@infobot`. Они вызывают traceroute и запрос `NodeInfo`; при вводе подсказываются имена нод и `!nodeid`.

Каналы можно создавать и редактировать: имя, PSK, uplink/downlink и точность публикации позиции. Уведомления включаются и выключаются отдельно для каждого канала и личного диалога.

---

## Ноды

<p align="center">
  <img src="docs/screenshots/nodes-b.jpg" width="49%" alt="Узлы — тёмная тема"/>
  <img src="docs/screenshots/nodes-w.jpg" width="49%" alt="Узлы — светлая тема"/>
</p>

Экран нод показывает текущие и сохранённые узлы сети. Есть поиск по имени, Short Name, ID и числовому адресу, а также фильтры по последнему отклику, расстоянию, SNR, хопам, каналу, избранным, игнорируемым, прямым и офлайн-нодам.

В карточке ноды отображаются роль, модель устройства, координаты, версия прошивки, RSSI/SNR и график телеметрии. Из карточки можно открыть личный чат, запустить traceroute, запросить свежий `NodeInfo` или удалить ноду из локального списка.

История traceroute хранится отдельно для каждой ноды. Сохранённый маршрут можно открыть на карте.

---

## Карта

<p align="center">
  <img src="docs/screenshots/map-b.jpg" width="49%" alt="Карта — тёмная тема"/>
  <img src="docs/screenshots/map-w.jpg" width="49%" alt="Карта — светлая тема"/>
</p>

Карта показывает ноды с координатами и сохранённые traceroute-маршруты. Поддерживаются сетевые OSM-тайлы, локальный кэш и офлайн-каталог с тайлами в формате `z/x/y.png|jpg|jpeg`.

На карте есть поиск, фильтры, переход к своей ноде, автообзор всех нод с координатами, ночной режим, измерение расстояний и выделение прямоугольной области. Выбранную область можно скачать для офлайн-работы; загрузка показывает прогресс и может быть поставлена на паузу или отменена.

---

## Телеметрия

<p align="center">
  <img src="docs/screenshots/telemetry-b.jpg" width="49%" alt="Телеметрия — тёмная тема"/>
  <img src="docs/screenshots/telemetry-w.jpg" width="49%" alt="Телеметрия — светлая тема"/>
</p>

Телеметрия показывает состояние устройства и сети: батарею, напряжение, channel utilization, Air Util TX и дополнительные метрики вроде Good RX, Bad RX, Dupe RX, TX, Dropped, Relayed, RSSI, SNR и hop-данных.

Данные можно смотреть на графиках или в таблице. Период выбирается от 1 часа до всей истории. Для длинных периодов значения усредняются, чтобы графики оставались читаемыми.

---

## Подключения

<p align="center">
  <img src="docs/screenshots/connections-b.jpg" width="49%" alt="Подключения — тёмная тема"/>
  <img src="docs/screenshots/connections-w.jpg" width="49%" alt="Подключения — светлая тема"/>
</p>

MeshApp работает с одним активным подключением за раз. Профили хранят адреса, порты, BLE-устройства, serial-настройки и выбранный протокол. Для нужного профиля можно включить автоподключение при запуске приложения.

Поддерживаются:

- TCP
- Serial / USB
- BLE

Для Serial / USB есть поиск портов и настройка DTR/RTS. Serial-слой рассчитан на CH340, CP210x и FTDI, включая отдельные обходы для Windows + Silicon Labs / CP210x.

Для BLE поддерживаются сканирование, GATT-подключение и pairing/passkey, если этого требует устройство или операционная система.

После подключения MeshApp выполняет нужный стартовый обмен:

- Meshtastic: config exchange
- MeshCore KISS: `SetHardware` handshake
- MeshCore Companion: `APP_START` handshake

### MeshCore

MeshCore поддерживается в двух вариантах:

- `MeshCore KISS` работает поверх TCP или Serial / USB.
- `MeshCore Companion` работает поверх BLE, TCP или Serial / USB.

MeshCore Companion не использует KISS framing. Для BLE используются:

- service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- RX characteristic `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- TX notifications `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

При подключении по TCP или Serial устройство должно передавать raw Companion packets без KISS-обёртки.

Что сейчас поддерживается:

- KISS framing (`FEND`, `FESC`, escape-последовательности) для TCP и Serial
- чтение metadata через MeshCore `SetHardware`: имя устройства, версия, identity, LoRa parameters, TX power, battery, stats, RSSI/SNR metadata и TX status
- BLE-профиль MeshCore Companion с RX/TX UUID, подпиской на TX notifications и `APP_START`
- `FrameFormat.MESHCORE_COMPANION` для raw Companion packets по TCP и Serial
- self-info, public key, device info, battery/storage packet
- contacts и channel info в общих экранах Nodes/Chat
- входящие и исходящие channel messages и DM через Companion Protocol
- read-only MeshCore metadata в Settings
- raw MeshCore Companion packets в LoRa Debug
- отображение активного протокола в карточке подключения

Ограничения:

- MeshCore KISS работает только через TCP и Serial.
- MeshCore Companion по TCP/Serial работает только с устройствами, которые передают raw Companion packets.
- MeshCore KISS сейчас используется для режима модема и чтения metadata; чаты, DM и основные пользовательские сценарии реализованы через MeshCore Companion.
- В MeshCore Companion нет Meshtastic-only функций: Admin protobuf save-flow, reactions, traceroute и bot-команд Meshtastic.

Подробнее: [docs/meshcore-support.ru.md](docs/meshcore-support.ru.md).

---

## Настройки устройства

<p align="center">
  <img src="docs/screenshots/settings-b.jpg" width="49%" alt="Настройки — тёмная тема"/>
  <img src="docs/screenshots/settings-w.jpg" width="49%" alt="Настройки — светлая тема"/>
</p>

Настройки Meshtastic-устройства открываются в виде дерева модулей: Device, LoRa, Position, Power, Network, Bluetooth, Display и другие разделы.

В интерфейсе можно менять Long Name и Short Name, редактировать поля конфигурации, сохранять изменения группой через begin/commit, синхронизировать время устройства с компьютером, перезапускать и выключать устройство.

Для полей, которые неудобно редактировать вручную, есть отдельные редакторы: IPv4-адреса, node ID, hex-значения, bitmask-поля и списки значений вроде `admin_key` и `ignore_incoming`.

Конфигурацию можно экспортировать и импортировать:

- `.mcf` — полный snapshot конфигурации
- `.mtp` — шаблон без персональных и секретных данных

Из интерфейса также можно очистить локальную H2-базу: сообщения, реакции, телеметрию, кэш нод и журнал пакетов.

---

## Логи и LoRa Debug

<p align="center">
  <img src="docs/screenshots/logs-b.jpg" width="49%" alt="Логи — тёмная тема"/>
  <img src="docs/screenshots/logs-w.jpg" width="49%" alt="Логи — светлая тема"/>
</p>

<p align="center">
  <img src="docs/screenshots/loradebug-b.jpg" width="49%" alt="LoRa Debug — тёмная тема"/>
  <img src="docs/screenshots/loradebug-w.jpg" width="49%" alt="LoRa Debug — светлая тема"/>
</p>

Встроенный просмотр логов поддерживает цветовое выделение уровней, паузу автопрокрутки, копирование, очистку и экспорт в `.log`.

LoRa Debug показывает входящие, исходящие и внутренние `MeshPacket`. Пакеты можно фильтровать по направлению, типу, времени, нодам и payload. Для выбранного пакета доступны HEX / ASCII preview, protobuf-дерево и подсветка полей.

Выбранный пакет можно скопировать или сохранить. Отфильтрованный набор экспортируется в JSON или CSV.

Если приложение аварийно завершилось или нужно отправить проблему разработчикам, отчёт можно отправить после crash или вручную из окна помощи.

---

## Lua-скрипты и MeshApp IDE

<p align="center">
  <img src="docs/screenshots/luascripts-b.jpg" width="49%" alt="Lua-скрипты — тёмная тема"/>
  <img src="docs/screenshots/luascripts-w.jpg" width="49%" alt="Lua-скрипты — светлая тема"/>
</p>

<p align="center">
  <img src="docs/screenshots/ide-b.jpg" width="49%" alt="Редактор Lua — тёмная тема"/>
  <img src="docs/screenshots/ide-w.jpg" width="49%" alt="Редактор Lua — светлая тема"/>
</p>

<p align="center">
  <img src="docs/screenshots/shop-b.jpg" width="49%" alt="Магазин скриптов — тёмная тема"/>
  <img src="docs/screenshots/shop-w.jpg" width="49%" alt="Магазин скриптов — светлая тема"/>
</p>

MeshApp IDE — встроенная среда для Lua-скриптов и ботов. Скрипты создаются и запускаются прямо из приложения, хранятся в локальной базе и могут экспортироваться в `.meshapp-script.json`.

В IDE есть:

- карточки скриптов с именем, иконкой, автором, версией, типом и статусом
- настройки скрипта: описание, автозапуск, тип бота, привязка к ноде или имени автоматизации
- редактор Lua с подсветкой, номерами строк, автоотступами и автодополнением `mesh.*`
- проверка синтаксиса и вывод runtime-ошибок
- отладчик с breakpoints, step/continue и просмотром local/global переменных
- изолированное KV-хранилище для каждого скрипта
- магазин скриптов с установкой, обновлением и удалением локальных копий

Скрипты работают в sandbox LuaJ. Отключены опасные API: `io`, `os`, `debug`, `package`, `require`, `dofile`, `loadfile`, `luajava`.

Точки входа:

- `on_message(msg)` — реакция на новое сообщение
- `on_command(command)` — обработка команды
- `on_node_selected(event)`, `on_traceroute(event)`, `on_node_info(event)` — async callbacks

Документация API: [docs/lua-api.ru.md](docs/lua-api.ru.md).

---

## Интерфейс и локальные данные

<p align="center">
  <img src="docs/screenshots/info-b.jpg" width="49%" alt="Справка и информация — тёмная тема"/>
  <img src="docs/screenshots/info-w.jpg" width="49%" alt="Справка и информация — светлая тема"/>
</p>

В интерфейсе есть тёмная и светлая тема AtlantaFX Cupertino, боковая панель, системный tray/status item, toast-уведомления и быстрые переключатели темы и уведомлений. На Windows 11 используется Mica, на macOS — vibrancy. Размер, положение окна и splitters сохраняются между сессиями.

Системные уведомления приходят для новых сообщений, если соответствующий чат не открыт. Проверку обновлений при запуске можно включить или выключить в настройках.

Локально сохраняются сообщения, реакции, непрочитанные чаты, избранные и игнорируемые ноды, телеметрия, скрипты, KV-данные, traceroute-история и журнал LoRa-пакетов.

Также поддерживаются импорт публичного кэша OneMesh и desktop-side MQTT proxy bridge для `MQTT proxy_to_client`. Параметры брокера берутся из MQTT-конфигурации устройства.

Для работы без JavaFX есть терминальный режим на Lanterna.

---

## Быстрый старт

### Требования

Для сборки и запуска из исходников нужны:

- Git
- JDK 25 toolchain; Gradle может скачать его автоматически
- macOS: Xcode Command Line Tools (`cc`) для `libmeshapp-serial.dylib` и `libmeshapp-tray.dylib`
- Windows: CMake + MSVC Build Tools для `meshapp-ble.dll`
- Linux: CMake + C/C++ toolchain + `libsystemd-dev` / `systemd-devel` для `libmeshapp-ble.so`

Для готовых пакетов (`.dmg`, `.msi`, `.deb`, `.AppImage`, `.flatpak`) эти build-зависимости не нужны.

### Запуск из исходников

```bash
git clone https://git.privatepractice.app/covox/meshapp.git
cd meshapp

# JavaFX-приложение
./gradlew run

# Терминальный режим
./gradlew runTerminal

# Терминальный режим с временным TCP-профилем
./gradlew runTerminal --args="--host 192.168.1.10 --protocol meshtastic"
```

### Подключение к устройству

1. Подключите устройство по USB, TCP или BLE.
2. Откройте раздел **Подключения**.
3. Добавьте профиль: **TCP**, **Serial / USB** или **BLE**.
4. Выберите протокол. Для новых профилей по умолчанию выбран **Meshtastic**; для MeshCore выберите **MeshCore KISS** или **MeshCore Companion**.
5. Для **Serial / USB** выберите порт. Для **BLE** запустите сканирование и выберите устройство.
6. Если требуется pairing или passkey, подтвердите сопряжение.
7. Нажмите **Подключить**.

После подключения Meshtastic доступны чаты, ноды, карта, настройки и остальные основные экраны. Для MeshCore Companion доступны чаты, ноды, личные сообщения, телеметрия, настройки и LoRa Debug. Для MeshCore KISS отображается modem metadata.

### Linux: доступ к USB Serial

Если USB-порт виден, но подключение падает с `Permission denied`, у пользователя нет прав на `/dev/ttyUSB*` или `/dev/ttyACM*`.

```bash
ls -l /dev/ttyUSB0
sudo usermod -aG dialout "$USER"
```

На некоторых дистрибутивах группа называется `uucp` или `lock`; используйте группу из вывода `ls -l`. После изменения групп нужно выйти из системы и войти снова.

`.deb`-пакет MeshApp устанавливает udev-правила для типичных USB-UART Meshtastic-плат. Активный локальный пользователь получает `uaccess` ACL, а ModemManager не занимает порт.

Если ошибка выглядит как `Device or resource busy`, порт уже открыт другим процессом: serial monitor, CLI или ModemManager.

---

## Отладочный запуск и профилирование

Локальный JMX для VisualVM, JConsole или JMC:

```bash
./gradlew run -PjmxDebugEnabled=true
./gradlew run -PjmxDebugEnabled=true -PjmxDebugPort=9011
```

При включённом JMX приложение слушает только `127.0.0.1`. Адрес подключения:
`service:jmx:rmi:///jndi/rmi://127.0.0.1:9010/jmxrmi`. Для другого порта замените `9010` на значение `jmxDebugPort`.

То же самое можно включить через переменные окружения:

```bash
MESHAPP_JMX_DEBUG=true
MESHAPP_JMX_PORT=9011
```

Для VisualVM обычно достаточно `Sampler > Memory` через локальное JMX-подключение. `Profiler > Memory` инструментирует классы; на Java 25/GraalVM/JavaFX/macOS его нативный агент может аварийно завершить целевую JVM.

Если нужен именно `Profiler > Memory`, запускайте приложение так:

```bash
./gradlew run -PvisualVmProfilerEnabled=true
./gradlew run -PvisualVmProfilerEnabled=true -PjmxDebugPort=9011
```

Этот режим включает JMX, отключает class data sharing (`-Xshare:off`) и Graal/JVMCI JIT на время профилирования. Через окружение режим включается переменной `MESHAPP_VISUALVM_PROFILER=true`.

Если профилируется собранный macOS `.app`, его нужно пересобрать с тем же флагом:

```bash
./gradlew jpackage -PvisualVmProfilerEnabled=true
```

Software rendering для обхода macOS `CVDisplayLink` по умолчанию выключен. Для разовой проверки:

```bash
./gradlew run -PvisualVmSoftwareRenderingEnabled=true
```

Если VisualVM показывает `Provided Memory settings are invalid`, откройте настройки memory profiler и замените placeholder в `Profile classes` на валидный фильтр, например `com.meshtastic.client.**` или `**`.

---

## Технологии

| Компонент | Технология | Назначение |
|-----------|------------|------------|
| UI | JavaFX 25.0.3 + AtlantaFX | Основной интерфейс |
| Meshtastic | Protobuf 4.33.4 + Meshtastic schemas | `ToRadio` / `FromRadio` и mesh-пакеты |
| MeshCore KISS | KISS framing + MeshCore `SetHardware` | Handshake и metadata MeshCore KISS modem |
| MeshCore Companion | Companion Protocol + BLE RX/TX или raw TCP/Serial | Handshake, metadata, contacts, channels, chat/DM и packet monitor |
| Transport | `TransportConnection` | Общий контракт для TCP, Serial, BLE и будущих transport-ов |
| База данных | H2 embedded | Сообщения, телеметрия, скрипты, трейсы и журналы |
| Карты | JavaFX `TileMapView` + OSM tiles | Онлайн/офлайн карта и traceroute |
| Lua runtime | LuaJ 3.0.1 | Sandbox-скрипты, боты, KV и `mesh.*` API |
| Lua editor | RichTextFX | Подсветка, строки, автодополнение и отладчик |
| Terminal mode | Lanterna | TUI без JavaFX |
| MQTT bridge | Eclipse Paho MQTT | Desktop-side proxy для `proxy_to_client` |
| TCP | `java.net.Socket` | Meshtastic TCP API, MeshCore KISS или raw Companion-подключение |
| Serial | Native JNA backends + jSerialComm discovery | Нативный доступ к COM/tty |
| BLE | CoreBluetooth / WinRT / BlueZ через JNA | BLE scan, GATT и pairing |
| Нативные интеграции | JNA + platform bridges | Mica, vibrancy, tray/status item и системные bridge-слои |
| Сборка | Gradle 9.4.1 + Protobuf + CMake + jpackage | Java/native сборка и инсталляторы |

---

## Архитектура протоколов

Этот раздел нужен разработчикам. Для работы с приложением достаточно выбрать тип подключения и протокол в профиле.

Подключение в MeshApp разделено на два уровня:

- **Transport** открывает соединение, пишет байты и передаёт входящий payload на уровень протокола. Общий контракт — `TransportConnection`, фабрика — `TransportConnectionFactory`.
- **Protocol runtime** отвечает за framing, parsing, handshake/config exchange, runtime state и сервисы конкретного протокола. Общие контракты — `CommunicationProtocol`, `ProtocolRuntime`, `ProtocolRuntimeContext` и `ProtocolRegistry`.

Зарегистрированные протоколы:

| ProtocolType | Runtime | Назначение |
|--------------|---------|------------|
| `MESHTASTIC` | `MeshtasticProtocolRuntime` | `ProtocolHandler`, `DeviceState`, config exchange, входящие mesh-пакеты, MQTT proxy |
| `MESHCORE_KISS` | `MeshCoreKissProtocolRuntime` | KISS SetHardware handshake, device name/version/identity/LoRa/battery/stats metadata |
| `MESHCORE_COMPANION` | `MeshCoreCompanionProtocolRuntime` | Companion `APP_START`, self-info/device-info/battery, contacts, channel info, chat/DM |

Выбор runtime-а берётся из сохранённого `ProtocolType`. TCP/Serial получают соответствующий `FrameFormat`, BLE подключается к GATT profile выбранного протокола. Старые профили без поля `protocol` считаются Meshtastic-профилями.

Чтобы добавить новый протокол:

1. Добавить значение в `ProtocolType`
2. Реализовать `CommunicationProtocol<S>` и `ProtocolRuntime<S>`
3. Зарегистрировать адаптер в `ProtocolRegistry`
4. Добавить UI/сервисы для состояния нового runtime-а
5. При необходимости расширить `ConnectionEntry` и `TransportConnectionFactory`

Часть UI пока использует совместимые Meshtastic accessors из `ConnectionManager` (`getDeviceState`, `getProtocolHandler`, `getConfigFuture`). Новым протоколам лучше получать своё состояние через runtime-абстракцию или typed accessors.

---

## Структура проекта

```text
meshapp/
|-- src/main/java/com/meshtastic/client/
|   |-- MeshAppLauncher.java      # Entry point: JavaFX или terminal mode
|   |-- MeshApp.java              # JavaFX Application
|   |-- connection/               # TransportConnection, TCP/Serial/BLE transport layer
|   |   |-- ble/                  # BLE transport + platform backends
|   |   \-- serial/               # Native serial I/O (Win/macOS/Linux)
|   |-- lua/                      # Lua runtime, sandbox API, script store/import/export
|   |-- protocol/                 # Общие protocol runtime API и registry
|   |   |-- meshcore/             # MeshCore KISS и Companion adapters/runtimes
|   |   \-- meshtastic/           # Meshtastic adapter/runtime
|   |-- terminal/                 # Lanterna TUI
|   |-- model/                    # Data models и runtime state
|   |-- service/                  # Persistence, discovery, reconnect, config exchange
|   |-- forms/                    # Основные экраны приложения
|   |-- components/               # Reusable UI components
|   |   \-- map/                  # OSM tile map components
|   |-- notification/             # Системные уведомления
|   |-- platform/                 # OS-specific UI / system integration
|   |-- system/                   # App framework (FormManager, RootPane)
|   |-- tray/                     # System tray / status item
|   \-- themes/                   # Theme management
|-- native/
|   |-- windows-ble/              # WinRT BLE DLL
|   |-- linux-ble/                # BlueZ BLE shared library
|   |-- macos-serial/             # macOS serial helper dylib
|   \-- macos-tray/               # macOS native tray/status item bridge
|-- src/main/proto/meshtastic/    # Meshtastic protobuf schemas
|-- src/main/resources/           # CSS, fonts, icons, logos
\-- build.gradle                  # Build configuration
```

---

## Сборка инсталляторов

MeshApp собирается через `jpackage`. Для Linux дополнительно поддерживаются AppImage и Flatpak.

| Платформа | Формат | Команда |
|-----------|--------|---------|
| Windows | `.msi` | `./gradlew jpackage` |
| macOS | `.dmg` | `./gradlew jpackage` |
| Linux | `.deb` | `./gradlew jpackage` |
| Linux | `.AppImage` | `./gradlew appImage -Pappimagetool=/path/to/appimagetool.AppImage` |
| Linux | `.flatpak` | `./gradlew flatpak` |

Для `AppImage` нужен `appimagetool`: либо в `PATH`, либо через `-Pappimagetool=...` / `APPIMAGETOOL=...`. Если используется `.AppImage`-версия самого `appimagetool`, может понадобиться `APPIMAGE_EXTRACT_AND_RUN=1`.

Для `Flatpak` нужны `flatpak`, `flatpak-builder` и runtime/SDK. По умолчанию используется `org.freedesktop.Platform//25.08` и `org.freedesktop.Sdk//25.08`.

```bash
flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak --user install -y flathub org.freedesktop.Platform//25.08 org.freedesktop.Sdk//25.08
./gradlew flatpak
```

Runtime можно переопределить через `-PflatpakRuntime=...`, `-PflatpakRuntimeVersion=...`, `-PflatpakSdk=...` и `-PflatpakBranch=...`.

Для публикации на Flathub используется `app.privatepractice.meshapp.yml`. После изменения Gradle-зависимостей нужно пересобрать Maven sources для offline-сборки:

```bash
scripts/update-flatpak-sources.sh
scripts/update-flatpak-sources.sh aarch64
```

В git должны попадать `flatpak-sources-x86_64.json`, `flatpak-sources-aarch64.json` и `flatpak-sources-foojay.json`. Каталог `offline-repository/` — только локальный кэш.

Локальная проверка через Flathub Builder:

```bash
flatpak install -y flathub org.flatpak.Builder org.freedesktop.Sdk.Extension.openjdk25//25.08
flatpak run --command=flathub-build org.flatpak.Builder --install app.privatepractice.meshapp.yml
```

Для `jpackage` можно явно указать JDK для bundled runtime: `-PpackagingJavaHome=/path/to/jdk` или `PACKAGING_JAVA_HOME=/path/to/jdk`. На macOS сборка проверяет `.app` через `otool -L` и завершается ошибкой, если внутри bundle остаются внешние зависимости вроде `/opt/homebrew/...` или `/usr/local/...`.

Во время `processResources` Gradle собирает native-компоненты:

- Windows: `meshapp-ble.dll` для BLE через WinRT
- Linux: `libmeshapp-ble.so` для BLE через BlueZ
- macOS: `libmeshapp-serial.dylib` для serial modem lines
- macOS: `libmeshapp-tray.dylib` для status item / tray bridge

### Подпись и notarization на macOS

По умолчанию `./gradlew jpackage` на macOS делает ad-hoc подпись `.app`. Для локальной проверки этого достаточно, но скачанный из браузера `.dmg` может получить от Gatekeeper сообщение **«Приложение повреждено, его не удается открыть»**.

Для release-сборки нужны credentials для `Developer ID` подписи:

- `MAC_SIGNING_KEY_USER_NAME` или `-PmacSigningKeyUserName=...`
- `MAC_SIGNING_KEYCHAIN` или `-PmacSigningKeychain=...`
- `MAC_PACKAGE_SIGNING_PREFIX` или `-PmacPackageSigningPrefix=...`, по умолчанию `com.meshtastic`

Для Gitea runner в daemon-режиме лучше импортировать сертификат в temporary keychain из secrets:

- `MAC_SIGNING_CERTIFICATE_P12`
- `MAC_SIGNING_CERTIFICATE_PASSWORD`
- `MAC_SIGNING_KEYCHAIN_PASSWORD`

Для скачиваемых сборок нужен `Developer ID Application`. `Apple Development` подходит для разработки, но не для release DMG.

Notarization можно включить одним из способов:

- `MAC_NOTARY_KEYCHAIN_PROFILE` или `-PmacNotaryKeychainProfile=...`
- `MAC_NOTARY_APPLE_ID` + `MAC_NOTARY_TEAM_ID` + `MAC_NOTARY_PASSWORD`
- `MAC_NOTARY_KEY_FILE` + `MAC_NOTARY_KEY_ID` + `MAC_NOTARY_ISSUER`
- В CI вместо `MAC_NOTARY_KEY_FILE` можно передать `MAC_NOTARY_KEY_FILE_BASE64`

После этого `./gradlew jpackage` соберёт signed `.app`, signed `.dmg` и выполнит `notarytool submit --wait` + `stapler`.

Если в Gitea runner нет `Developer ID Application`, workflow всё равно соберёт macOS artifact с прежним именем, но пропустит `spctl`/notarization-проверку.

### Установка на macOS

Если сборка сделана без `Developer ID` и notarization, macOS может показать предупреждение **«от неизвестного разработчика»** или **«Приложение повреждено, его не удается открыть»**. Для локального ad-hoc build это ожидаемо.

Через Finder:

1. Откройте папку Applications или каталог, куда установлен MeshApp.
2. Нажмите правой кнопкой мыши или Control-click на MeshApp.
3. Выберите **Открыть** и подтвердите запуск. Это нужно сделать один раз.

Через терминал:

```bash
xattr -cr /Applications/MeshApp.app
```

---

## Лицензия

Распространяется под лицензией [AGPL-3.0](LICENSE).

---

<p align="center">
  Создано Konstantin A. Smirnov
  <br>
  <a href="https://t.me/coVox">
    <img src="https://img.shields.io/badge/Telegram-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram">
  </a>
</p>
