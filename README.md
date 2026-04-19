<p align="center">
  <img src="docs/logo/MeshApp.png" width="128" alt="MeshApp Logo"/>
</p>

<h1 align="center">MeshApp</h1>

<p align="center">
  Кросс-платформенный десктопный клиент для mesh-сети
  <a href="https://meshtastic.org">Meshtastic</a>
  <br/>
  <b>Java 21 &nbsp;·&nbsp; JavaFX &nbsp;·&nbsp; Protobuf &nbsp;·&nbsp; LoRa</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk" alt="Java 21"/>
  <img src="https://img.shields.io/badge/JavaFX-21.0.10-blue?logo=java" alt="JavaFX"/>
  <img src="https://img.shields.io/badge/Platform-Win%20%7C%20macOS%20%7C%20Linux-brightgreen" alt="Platform"/>
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="License"/>
  <a href="https://t.me/+SRaOd1gftoo5MWRi">
  <img src="https://img.shields.io/badge/Telegram-@MeshAppClient-blue?logo=telegram" alt="Telegram">
</a>
</p>

<div align="right">



</div>

---



## О проекте

**MeshApp** — полнофункциональный кроссплатформенный десктопный клиент для [Meshtastic](https://meshtastic.org), работающий по **TCP**, **Serial / USB** и **BLE**. Приложение предназначено для управления устройствами, обмена сообщениями, мониторинга сети и редактирования конфигурации радиомодулей с ПК на Windows, macOS и Linux.

Meshtastic — открытый проект, превращающий недорогие LoRa-модули в узлы децентрализованной mesh-сети. Сообщения передаются на расстояние от сотен метров до десятков километров — без интернета, вышек сотовой связи и какой-либо инфраструктуры.

```
                     +------------------------------------------------------+
                     |                       MeshApp                         |
                     |                                                      |
 TCP (IP:4403) ----->|  +----------------+  +-----------+  +-------------+  |
 USB Serial -------->|  |   Transport    |->| Protocol  |->|  UI / Forms |  |
 BLE / GATT -------->|  | TCP / USB / BLE|  |  Handler  |  |   JavaFX    |  |
                     |  +----------------+  +-----------+  +-------------+  |
                     |           |                 |               |         |
                     |           v                 v               v         |
                     |   Native serial / BLE   DeviceState      H2 / Logs   |
                     +------------------------------------------------------+
```

---

## Новые возможности

- **Команды в поле ввода** — `@tracebot` и `@infobot` с автодополнением по нодам для быстрого `Traceroute` и запроса `NodeInfo`
- **Оповещения по чатам** — mute/unmute отдельно для каждого канала и личного диалога, с сохранением настройки локально
- **Crash / problem reporting** — предложение отправить лог после аварийного завершения и ручная отправка отчёта из окна помощи
- **Мониторинг LoRa-пакетов** — отдельное окно live-захвата с фильтрами по направлению, типу, диапазону даты/времени, поиском, HEX / ASCII предпросмотром и экспортом в текст/JSON
- **Синхронизация времени с ПК** — установка текущего времени на радио с обновлением GMT при необходимости
- **Очистка локальной БД** — полный сброс сообщений, реакций, кэша нод, телеметрии и журнала LoRa-пакетов из интерфейса
- **Расширенный редактор конфигурации** — человекочитаемый ввод IPv4, node ID, hex-значений и bitmask-полей, плюс поэлементное редактирование repeated-полей

---

## Возможности

### Chat и обмен сообщениями

<p align="center">
  <img src="docs/screenshots/chat-b.png" width="49%" alt="Chat — тёмная тема"/>
  <img src="docs/screenshots/chat-w.png" width="49%" alt="Chat — светлая тема"/>
</p>

- **Многоканальный чат** — отправка и приём сообщений в нескольких mesh-каналах
- **Личные сообщения** — приватная переписка с отдельными узлами
- **Ответы на сообщения** — цитирование с контекстом
- **Реакции на сообщения** — быстрые emoji-реакции с сохранением и отслеживанием статуса доставки
- **Статусы доставки** — ACK/NAK отслеживание отправленных сообщений
- **Traceroute** — визуализация маршрута до любого узла сети
- **Запрос NodeInfo** — получение актуальных данных об узле по запросу
- **Команды `@tracebot` и `@infobot`** — запуск traceroute и запроса информации прямо из строки ввода с подсказками по имени и `!nodeid`
- **Управление каналами** — создание secondary-каналов и редактирование имени, PSK, uplink/downlink и точности публикации позиции
- **Emoji** — встроенный выбор эмодзи
- **Счётчик непрочитанных** — бейджи на каждом чате
- **Оповещения по чатам** — включение и отключение уведомлений отдельно для каждого канала и DM через иконку звонка и контекстное меню
- **История** — полная история сообщений с поиском, хранится в локальной БД
- **Локальная очистка истории** — удаление отдельных сообщений и целых чатов из встроенной базы

---

### Узлы сети

<p align="center">
  <img src="docs/screenshots/nodes-b.png" width="49%" alt="Узлы — тёмная тема"/>
  <img src="docs/screenshots/nodes-w.png" width="49%" alt="Узлы — светлая тема"/>
</p>

- **Гибкая сортировка и фильтры** — последний отклик, дистанция, SNR, хопы, канал, избранные, игнорируемые, прямые и офлайн-ноды
- **Поиск** по имени, короткому имени, ID или числовому адресу
- **Детальная карточка узла** — железо, роль, координаты, прошивка, SNR/RSSI и график телеметрии
- **Быстрые действия** — открыть приватный чат, запросить свежий NodeInfo, удалить узел из локального списка
- **Избранные и игнорируемые узлы** — локальное хранение и синхронизация статуса с устройством
- **Кэширование узлов** — локальная база с пагинацией

---

### Телеметрия и мониторинг

<p align="center">
  <img src="docs/screenshots/telemetry-b.png" width="49%" alt="Телеметрия — тёмная тема"/>
  <img src="docs/screenshots/telemetry-w.png" width="49%" alt="Телеметрия — светлая тема"/>
</p>

- **Дашборд устройства** — отдельный экран со вкладками «Графики» и «Данные»
- **Графики в реальном времени** — батарея, напряжение, загрузка канала, Air Util TX
- **Фильтрация по периодам** — от 1 часа до всей истории
- **Агрегация данных** — автоматическое усреднение для плавных кривых
- **Таблица телеметрии** — детализированные записи с временными метками
- **Расширенные метрики** — Good RX, Bad RX, Dupe RX, TX, Dropped, Relayed, RSSI, SNR и hop-данные
- **Ленивая подгрузка журнала** — догрузка длинной истории при прокрутке

---

### Подключения

<p align="center">
  <img src="docs/screenshots/connections-b.png" width="49%" alt="Подключения — тёмная тема"/>
  <img src="docs/screenshots/connections-w.png" width="49%" alt="Подключения — светлая тема"/>
</p>

- **TCP, Serial / USB и BLE** — подключение по сети, через COM/tty-порт или Bluetooth LE
- **Поиск устройств** — автопоиск serial-портов и BLE-сканирование Meshtastic-устройств
- **Профили подключений** — сохранение адресов, портов и BLE-устройств для быстрого повторного подключения
- **Одно активное подключение** — в каждый момент времени приложение работает с одним выбранным устройством
- **BLE-сопряжение** — passkey/pairing flow, когда этого требует устройство или платформа
- **Автообмен конфигурацией** — автоматическое получение параметров устройства при подключении
- **Автопереподключение** — повторные попытки восстановления соединения после разрыва
- **Надёжный Serial для USB-UART мостов** — корректная работа с CH340/CP210x/FTDI, чтобы не провоцировать лишний reset ESP32; отдельная совместимость для Windows + Silicon Labs / CP210x

---

### Конфигурация устройства

<p align="center">
  <img src="docs/screenshots/settings-b.png" width="49%" alt="Настройки — тёмная тема"/>
  <img src="docs/screenshots/settings-w.png" width="49%" alt="Настройки — светлая тема"/>
</p>

- **Редактирование параметров** — полное управление настройками LoRa-модуля через древовидный интерфейс
- **Имя узла** — установка Long Name (40 символов) и Short Name (4 символа)
- **Атомарное сохранение** — транзакционный механизм begin/commit для групповых изменений
- **Все модули** — настройка Device, LoRa, Position, Power, Network, Bluetooth, Display и других модулей
- **Человекочитаемые редакторы полей** — IPv4-адреса, node ID, hex-значения и bitmask-поля редактируются без ручного пересчёта protobuf-значений
- **Repeated-поля как отдельные слоты** — списки вроде `admin_key` и `ignore_incoming` можно добавлять, удалять и править поэлементно
- **Синхронизация времени с ПК** — установка времени на ноде из системных часов компьютера с обновлением GMT и повторной синхронизацией после переподключения, если нужен reboot
- **Snapshot-файлы конфигурации** — экспорт и импорт полной конфигурации в формате `.mcf`
- **Шаблоны конфигурации** — экспорт и импорт обезличенных шаблонов `.mtp` без персональных и секретных полей
- **Очистка локальной базы данных** — полный сброс встроенной H2-базы с удалением сообщений, реакций, телеметрии, кэша нод и журнала пакетов
- **Управление питанием устройства** — перезапуск и выключение оборудования из интерфейса

---

### Диагностика и логирование

<p align="center">
  <img src="docs/screenshots/logs-b.png" width="49%" alt="Логи — тёмная тема"/>
  <img src="docs/screenshots/logs-w.png" width="49%" alt="Логи — светлая тема"/>
</p>

- **Встроенные логи** — просмотр отладочной информации с цветовой кодировкой по уровню
- **Управление логами** — пауза/возобновление автопрокрутки, копирование, очистка и экспорт в `.log`
- **Мониторинг LoRa-пакетов** — отдельное окно live-захвата входящих и исходящих mesh-пакетов
- **Управление захватом** — запуск, остановка и полная очистка накопленного журнала пакетов
- **Фильтры и поиск** — отбор по направлению, типу, диапазону даты/времени, узлам и payload, плюс пагинация для длинных журналов
- **HEX / ASCII и дерево пакета** — просмотр сырых байт, protobuf-структуры и подсветка выбранных полей
- **Экспорт пакетов** — копирование и сохранение выбранного пакета в текстовом виде или protobuf-style JSON
- **Отчёты о сбоях и проблемах** — отправка технического лога разработчикам после crash или вручную из окна «Помощь»

#### LoRa Debug

<p align="center">
  <img src="docs/screenshots/loradebug-b.png" width="49%" alt="LoRa Debug — тёмная тема"/>
  <img src="docs/screenshots/loradebug-w.png" width="49%" alt="LoRa Debug — светлая тема"/>
</p>

`LoRa Debug` помогает разбирать реальный mesh-трафик на уровне отдельных пакетов. Окно показывает входящие, исходящие и внутренние `MeshPacket`, позволяет быстро отфильтровать журнал по направлению, типу сообщения и содержимому, а выбранный тип фильтра сохраняется при обновлении списка доступных типов.

Инструмент полезен для диагностики доставки сообщений, проверки `NodeInfo` / `Telemetry` / `Position` пакетов, анализа `MQTT proxy` трафика и просмотра сырых protobuf-данных через HEX / ASCII предпросмотр и дерево структуры пакета.

---

### Интерфейс

- **Тёмная и светлая тема** — AtlantaFX Cupertino
- **Нативное оформление окна** — эффект Mica (Windows 11), vibrancy (macOS)
- **Кастомный titlebar** — кнопки управления окном в стиле платформы
- **Боковая панель** — быстрая навигация между разделами
- **Системный трей / status item** — сворачивание в трей, восстановление окна и выход из приложения
- **Системные уведомления** — нативные OS-уведомления для входящих сообщений с подавлением при активном открытом чате
- **Переключатели в боковой панели** — быстрое переключение темы и уведомлений без захода в настройки
- **Проверка обновлений** — опциональная проверка новой версии при запуске
- **Настройки приложения** — отключение визуальных эффектов, программный рендеринг и выбор режима минимизации в трей
- **Запоминание состояния окна** — восстановление размера, позиции, развёрнутого состояния и сплиттеров между сессиями
- **Toast-уведомления** — информирование о событиях без отрыва от работы

---

### Кэш и интеграции

- **Импорт из OneMesh** — загрузка публичного кэша узлов в локальную H2-базу для быстрого старта и обогащения карточек узлов
- **MQTT proxy bridge** — при включённом `MQTT proxy_to_client` MeshApp автоматически поднимает клиентский мост к брокеру и проксирует сообщения между устройством и MQTT
- **Параметры брокера из устройства** — адрес, root topic, TLS, логин/пароль и retained-публикации берутся из конфигурации модуля MQTT, с подавлением локального loopback
- **Локальная персистентность** — сообщения, реакции, непрочитанные чаты, избранные/игнорируемые узлы, телеметрия и журнал LoRa-пакетов сохраняются между сессиями

---

## Быстрый старт

### Требования

Для сборки и запуска из исходников:

- **JDK 21+** (скачивается автоматически через Gradle Toolchain)
- **Git** для клонирования репозитория
- **macOS**: Xcode Command Line Tools (`cc`) для сборки `libmeshapp-serial.dylib` и `libmeshapp-tray.dylib`
- **Windows**: CMake + MSVC Build Tools для сборки `meshapp-ble.dll`
- **Linux**: CMake + C/C++ toolchain + `libsystemd-dev` / `systemd-devel` для сборки `libmeshapp-ble.so`

Для готовых релизных пакетов (`.dmg`, `.msi`, `.deb`, `.AppImage`, `.flatpak`) эти build-зависимости не нужны.

### Сборка и запуск

```bash
# Клонировать репозиторий
git clone https://git.privatepractice.app/covox/meshapp.git
cd meshapp

# Запустить приложение
./gradlew run

# Собрать нативный инсталлятор (.dmg / .msi / .deb)
./gradlew jpackage

# Linux: собрать portable AppImage
./gradlew appImage -Pappimagetool=/path/to/appimagetool.AppImage

# Linux: собрать Flatpak bundle
./gradlew flatpak
```

### Подключение к устройству

1. Подключите Meshtastic-устройство по USB, убедитесь, что оно доступно по TCP, или включите на нём BLE
2. В разделе **Подключения** добавьте новый профиль и выберите тип: **TCP**, **Serial / USB** или **BLE**
3. Для **Serial / USB** выберите найденный порт, для **BLE** запустите сканирование и выберите устройство из списка
4. Если платформа или устройство требуют сопряжения, подтвердите pairing / введите passkey
5. Нажмите **Подключить** — MeshApp автоматически обменяется конфигурацией с устройством
6. Переключитесь в **Чат** для обмена сообщениями, **Узлы** для мониторинга сети или **Настройки** для конфигурации устройства

---

## Технологии

| Компонент | Технология | Назначение |
|-----------|-----------|------------|
| UI | JavaFX 21 + AtlantaFX | Интерфейс с нативным оформлением |
| Протокол | Protobuf 4.33 | Сериализация mesh-пакетов |
| База данных | H2 (embedded) | Локальное хранение сообщений и телеметрии |
| MQTT bridge | Eclipse Paho MQTT | Desktop-side proxy к внешнему MQTT-брокеру для `proxy_to_client` |
| TCP | `java.net.Socket` | Подключение к Meshtastic TCP API |
| Serial | Native JNA backends + jSerialComm discovery | Нативный доступ к COM/tty без jSerialComm I/O |
| BLE | CoreBluetooth / WinRT / BlueZ через JNA | BLE-сканирование, GATT и pairing на поддерживаемых платформах |
| Нативные интеграции | JNA + platform bridges | Mica (Win), vibrancy (macOS), tray/status item, системные bridge-слои |
| Сборка | Gradle 8.13 + Protobuf + CMake + jpackage | Компиляция Java/native слоёв и сборка инсталляторов |

---

## Структура проекта

```
meshapp/
|-- src/main/java/com/meshtastic/client/
|   |-- MeshApp.java              # Entry point (JavaFX Application)
|   |-- connection/               # TCP transport и общие connection API
|   |   |-- ble/                  # BLE transport + platform backends
|   |   \-- serial/               # Native serial I/O (Win/macOS/Linux)
|   |-- protocol/                 # Meshtastic protocol parsing
|   |-- model/                    # Data models и runtime state
|   |-- service/                  # Persistence, discovery, reconnect, config exchange
|   |-- forms/                    # Основные экраны приложения
|   |-- components/               # Reusable UI components
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

MeshApp собирается в нативные пакеты через `jpackage`, а для Linux дополнительно поддерживает portable `AppImage` и sandboxed `Flatpak`:

| Платформа | Формат | Команда |
|-----------|--------|---------|
| Windows | `.msi` | `./gradlew jpackage` |
| macOS | `.dmg` | `./gradlew jpackage` |
| Linux | `.deb` | `./gradlew jpackage` |
| Linux | `.AppImage` | `./gradlew appImage -Pappimagetool=/path/to/appimagetool.AppImage` |
| Linux | `.flatpak` | `./gradlew flatpak` |

Для `AppImage` нужен `appimagetool`: либо в `PATH`, либо через `-Pappimagetool=...` / `APPIMAGETOOL=...`. Если используется `.AppImage`-версия самого `appimagetool`, может понадобиться `APPIMAGE_EXTRACT_AND_RUN=1`.

Для `Flatpak` нужны `flatpak` и `flatpak-builder`, а также установленный runtime/SDK. По умолчанию задача использует `org.freedesktop.Platform//24.08` и `org.freedesktop.Sdk//24.08`:

```bash
flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak --user install -y flathub org.freedesktop.Platform//24.08 org.freedesktop.Sdk//24.08
./gradlew flatpak
```

При необходимости runtime можно переопределить через `-PflatpakRuntime=...`, `-PflatpakRuntimeVersion=...`, `-PflatpakSdk=...` и `-PflatpakBranch=...`.

Для `jpackage` можно явно указать JDK, из которого будет собран bundled runtime: `-PpackagingJavaHome=/path/to/jdk` или `PACKAGING_JAVA_HOME=/path/to/jdk`. На macOS сборка дополнительно проверяет `.app` через `otool -L` и завершится ошибкой, если внутри bundle останутся внешние зависимости вроде `/opt/homebrew/...` или `/usr/local/...`.

Во время `processResources` Gradle автоматически собирает платформенные native-компоненты:

- **Windows** — `meshapp-ble.dll` для BLE через WinRT
- **Linux** — `libmeshapp-ble.so` для BLE через BlueZ
- **macOS** — `libmeshapp-serial.dylib` для безопасного управления serial modem lines
- **macOS** — `libmeshapp-tray.dylib` для нативного status item / tray bridge

### Подпись и notarization на macOS

По умолчанию `./gradlew jpackage` на macOS делает только ad-hoc подпись `.app`. Такой `.dmg` подходит для локальной проверки, но для скачивания из браузера этого недостаточно: Gatekeeper может показать **«Приложение повреждено, его не удается открыть»**.

Для release-сборки нужно передать credentials для `Developer ID` подписи:

- `MAC_SIGNING_KEY_USER_NAME` или `-PmacSigningKeyUserName=...` — Team/User name из Apple Developer certificate
- `MAC_SIGNING_KEYCHAIN` или `-PmacSigningKeychain=...` — optional keychain с сертификатом
- `MAC_PACKAGE_SIGNING_PREFIX` или `-PmacPackageSigningPrefix=...` — optional signing prefix, по умолчанию `com.meshtastic`

И один из вариантов notarization:

- `MAC_NOTARY_KEYCHAIN_PROFILE` или `-PmacNotaryKeychainProfile=...`
- `MAC_NOTARY_APPLE_ID` + `MAC_NOTARY_TEAM_ID` + `MAC_NOTARY_PASSWORD`
- `MAC_NOTARY_KEY_FILE` + `MAC_NOTARY_KEY_ID` + `MAC_NOTARY_ISSUER`

После этого обычный `./gradlew jpackage` соберёт signed `.app`, signed `.dmg` и выполнит `notarytool submit --wait` + `stapler`.

### Установка на macOS

Если сборка сделана без `Developer ID` и notarization, macOS может показать предупреждение **«от неизвестного разработчика»** или **«Приложение повреждено, его не удается открыть»**. Это ожидаемо для локального ad-hoc build.

**Способ 1** — через Finder:
1. Откройте папку Applications (или куда вы установили MeshApp)
2. Нажмите **правой кнопкой мыши** (или Control+клик) на MeshApp → **Открыть**
3. В диалоге подтвердите открытие — это нужно сделать только один раз

**Способ 2** — через терминал:
```bash
xattr -cr /Applications/MeshApp.app
```

---

## Лицензия

Распространяется под лицензией [GPL-3.0](LICENSE).

---

<p align="center">
  Создано Konstantin A. Smirnov <a href="mailto:covox@covox.ru">covox@covox.ru</a>
  <br>
<a href="https://t.me/coVox">
  <img src="https://img.shields.io/badge/Telegram-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram">
</a>
</p>
