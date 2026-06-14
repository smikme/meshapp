<p align="center">
  <img src="docs/logo/MeshApp.png" width="128" alt="MeshApp Logo"/>
</p>

<h1 align="center">MeshApp</h1>

<p align="center">
  Настольный клиент для сетей
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

<p align="center">
  <a href="#установка">Установка</a>
</p>

<div align="right">

<strong>Русский</strong> | <a href="README.md">English</a>

</div>

---

## О проекте

MeshApp — настольное приложение для устройств Meshtastic и MeshCore. Оно подключается к устройству по сети, USB или BLE.

В приложении можно обмениваться сообщениями, видеть узлы сети на карте, просматривать телеметрию, менять настройки устройства, разбирать LoRa-пакеты и запускать Lua-скрипты.

Новые профили подключения по умолчанию работают с Meshtastic. Для MeshCore при создании профиля нужно выбрать режим работы:

- `MeshCore KISS` — для TCP и Serial / USB
- `MeshCore Companion` — для BLE, TCP и Serial / USB

Тип подключения и протокол выбираются отдельно. Например, можно подключиться по TCP к Meshtastic-устройству, по USB к модему MeshCore KISS или по BLE к устройству с MeshCore Companion.

![Архитектура MeshApp](docs/meshapp-architecture.jpg)

---

## Установка

Готовые пакеты публикуются на [странице релизов Gitea](https://git.privatepractice.app/covox/meshapp/releases).

- macOS: скачайте `.dmg`, откройте его и перенесите MeshApp в Applications.
- Windows: скачайте и запустите `.msi`-инсталлятор.
- Debian / Ubuntu: скачайте `.deb`-пакет и установите его через `apt` или пакетный менеджер.
- Linux AppImage: скачайте `.AppImage`, сделайте файл исполняемым и запустите его.

Пользователи Flatpak могут установить MeshApp через опубликованный Flatpak ref:

```bash
flatpak install --user https://flatpak.privatepractice.app/app.privatepractice.meshapp.flatpakref
flatpak run app.privatepractice.meshapp
```

Если репозиторий `meshapp` был добавлен старой командой напрямую на `/repo/` и Flatpak сообщает `public key not found`, удалите старый remote и добавьте его заново:

```bash
flatpak remote-delete --user --force meshapp
flatpak install --user https://flatpak.privatepractice.app/app.privatepractice.meshapp.flatpakref
```

Обновление Flatpak-установки:

```bash
flatpak update app.privatepractice.meshapp
```

---

## Что умеет приложение

В MeshApp есть:

- Чаты в сетевых каналах и личные диалоги.
- Список узлов сети с поиском, фильтрами, избранными и игнорируемыми устройствами.
- Карта с сетевыми и офлайн-тайлами, узлами сети, измерениями и сохранёнными маршрутами.
- Телеметрия устройства и сети: текущие показатели, история, графики и таблица.
- Настройки Meshtastic-устройства: конфигурация, каналы и редакторы сложных полей.
- Подключения: TCP, USB/Serial и BLE; Meshtastic, MeshCore KISS и MeshCore Companion.
- Журналы работы приложения и просмотр LoRa-пакетов с фильтрами, поиском и экспортом.
- Lua-скрипты: редактор, отладчик, автозапуск, боты, хранилище данных и магазин скриптов.
- Локальная база для сообщений, телеметрии, узлов сети, маршрутов, скриптов и журнала пакетов.
- Терминальный режим без JavaFX.

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

В чатах доступны сетевые каналы и личные диалоги. Сообщения сохраняются локально; поддерживаются ответы, реакции, статусы доставки и счётчики непрочитанных.

Из строки ввода можно запускать команды `@tracebot` и `@infobot`. Они проверяют маршрут до выбранного узла и запрашивают сведения о нём; при вводе приложение подсказывает имена узлов и их адреса.

Каналы можно создавать и редактировать: менять имя, ключ доступа, параметры передачи позиции и её точность. Уведомления включаются и выключаются отдельно для каждого канала и личного диалога.

---

## Узлы

<p align="center">
  <img src="docs/screenshots/nodes-b.jpg" width="49%" alt="Узлы — тёмная тема"/>
  <img src="docs/screenshots/nodes-w.jpg" width="49%" alt="Узлы — светлая тема"/>
</p>

Экран узлов показывает устройства, которые сейчас видны в сети, и те, которые уже были сохранены в локальной базе. Есть поиск по имени, короткому имени, идентификатору и числовому адресу, а также фильтры по последнему отклику, расстоянию, качеству сигнала, числу переходов, каналу, избранным, игнорируемым, прямым и недоступным узлам.

В карточке узла отображаются роль, модель устройства, координаты, версия прошивки, уровень сигнала и график телеметрии. Из карточки можно открыть личный чат, проверить маршрут до узла, обновить сведения о нём или удалить узел из локального списка.

История проверок маршрута хранится отдельно для каждого узла. Сохранённый маршрут можно открыть на карте.

---

## Карта

<p align="center">
  <img src="docs/screenshots/map-b.jpg" width="49%" alt="Карта — тёмная тема"/>
  <img src="docs/screenshots/map-w.jpg" width="49%" alt="Карта — светлая тема"/>
</p>

Карта показывает узлы с координатами и сохранённые маршруты. Поддерживаются сетевые тайлы OpenStreetMap, локальный кэш и офлайн-каталог с тайлами в формате `z/x/y.png|jpg|jpeg`.

На карте есть поиск, фильтры, переход к своему устройству, обзор всех узлов с координатами, ночной режим, измерение расстояний и выделение прямоугольной области. Выбранную область можно скачать для работы без интернета; загрузку можно поставить на паузу или отменить.

---

## Телеметрия

<p align="center">
  <img src="docs/screenshots/telemetry-b.jpg" width="49%" alt="Телеметрия — тёмная тема"/>
  <img src="docs/screenshots/telemetry-w.jpg" width="49%" alt="Телеметрия — светлая тема"/>
</p>

Телеметрия показывает состояние устройства и сети: заряд батареи, напряжение, загрузку канала, время передачи в эфире, качество приёма, количество отправленных, потерянных и повторно переданных пакетов, уровень сигнала и данные о переходах через другие узлы.

Данные можно смотреть на графиках или в таблице. Период выбирается от 1 часа до всей истории. Для длинных периодов значения усредняются, чтобы графики оставались читаемыми.

---

## Подключения

<p align="center">
  <img src="docs/screenshots/connections-b.jpg" width="49%" alt="Подключения — тёмная тема"/>
  <img src="docs/screenshots/connections-w.jpg" width="49%" alt="Подключения — светлая тема"/>
</p>

MeshApp может работать с несколькими подключениями параллельно. В профилях сохраняются адрес, порт, выбранное BLE-устройство, настройки USB / Serial и протокол. Для нужного профиля можно включить автоматическое подключение при запуске приложения.

Поддерживаются:

- TCP
- Serial / USB
- BLE

Для Serial / USB есть поиск портов и настройка линий DTR/RTS. Поддерживаются распространённые USB-UART-чипы CH340, CP210x и FTDI; для Windows с Silicon Labs / CP210x добавлена отдельная обработка подключения.

Для BLE поддерживаются поиск устройств, подключение через GATT и сопряжение с кодом доступа, если этого требует устройство или операционная система.

После подключения MeshApp выполняет начальный обмен с устройством:

- Meshtastic: обмен настройками
- MeshCore KISS: согласование через `SetHardware`
- MeshCore Companion: запуск обмена через `APP_START`

### MeshCore

MeshCore поддерживается в двух вариантах:

- `MeshCore KISS` работает поверх TCP или Serial / USB.
- `MeshCore Companion` работает поверх BLE, TCP или Serial / USB.

MeshCore Companion не использует формат KISS. Для BLE используются:

- служба `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- характеристика RX `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- уведомления TX `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

При подключении по TCP или Serial устройство должно передавать пакеты Companion без дополнительной KISS-обёртки.

Что сейчас поддерживается:

- формат KISS (`FEND`, `FESC` и escape-последовательности) для TCP и Serial
- чтение сведений об устройстве через MeshCore `SetHardware`: имя, версия, идентификатор, параметры LoRa, мощность передачи, батарея, статистика, данные RSSI/SNR и статус передачи
- BLE-профиль MeshCore Companion с RX/TX UUID, подпиской на уведомления TX и `APP_START`
- `FrameFormat.MESHCORE_COMPANION` для пакетов Companion по TCP и Serial
- сведения о своём устройстве, открытый ключ, данные устройства, батарея и хранилище
- контакты и сведения о каналах в общих экранах узлов и чатов
- входящие и исходящие сообщения каналов и личные сообщения через Companion Protocol
- просмотр сведений MeshCore в настройках без редактирования
- пакеты MeshCore Companion в LoRa Debug
- отображение активного протокола в карточке подключения

Ограничения:

- MeshCore KISS работает только через TCP и Serial.
- MeshCore Companion по TCP/Serial работает только с устройствами, которые передают пакеты Companion без KISS-обёртки.
- MeshCore KISS сейчас используется для режима модема и чтения сведений об устройстве; чаты, личные сообщения и основные пользовательские сценарии реализованы через MeshCore Companion.
- В MeshCore Companion нет функций, которые относятся только к Meshtastic: сохранение настроек через Admin protobuf, реакции, проверка маршрута и команды ботов Meshtastic.

Подробнее: [docs/meshcore-support.ru.md](docs/meshcore-support.ru.md).

---

## Настройки устройства

<p align="center">
  <img src="docs/screenshots/settings-b.jpg" width="49%" alt="Настройки — тёмная тема"/>
  <img src="docs/screenshots/settings-w.jpg" width="49%" alt="Настройки — светлая тема"/>
</p>

Настройки Meshtastic-устройства открываются в виде дерева разделов: устройство, LoRa, позиция, питание, сеть, Bluetooth, экран и другие параметры.

В интерфейсе можно менять длинное и короткое имя устройства, редактировать поля конфигурации, сохранять несколько изменений одной операцией, синхронизировать время устройства с компьютером, перезапускать и выключать устройство.

Для полей, которые неудобно редактировать вручную, есть отдельные редакторы: IPv4-адреса, идентификаторы узлов, шестнадцатеричные значения, битовые маски и списки значений вроде `admin_key` и `ignore_incoming`.

Конфигурацию можно экспортировать и импортировать:

- `.mcf` — полная копия конфигурации
- `.mtp` — шаблон без персональных и секретных данных

Из интерфейса также можно очистить локальную базу данных H2: сообщения, реакции, телеметрию, кэш узлов и журнал пакетов.

---

## Логи и LoRa-пакеты

<p align="center">
  <img src="docs/screenshots/logs-b.jpg" width="49%" alt="Логи — тёмная тема"/>
  <img src="docs/screenshots/logs-w.jpg" width="49%" alt="Логи — светлая тема"/>
</p>

<p align="center">
  <img src="docs/screenshots/loradebug-b.jpg" width="49%" alt="LoRa Debug — тёмная тема"/>
  <img src="docs/screenshots/loradebug-w.jpg" width="49%" alt="LoRa Debug — светлая тема"/>
</p>

Встроенный просмотр логов поддерживает цветовое выделение уровней, паузу автопрокрутки, копирование, очистку и экспорт в `.log`.

Окно LoRa Debug показывает входящие, исходящие и внутренние `MeshPacket`. Пакеты можно фильтровать по направлению, типу, времени, узлам и содержимому. Для выбранного пакета доступны просмотр в HEX и ASCII, дерево protobuf и подсветка полей.

Выбранный пакет можно скопировать или сохранить. Отфильтрованный набор экспортируется в JSON или CSV.

Если приложение аварийно завершилось или нужно отправить проблему разработчикам, отчёт можно отправить после сбоя или вручную из окна помощи.

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
- настройки скрипта: описание, автозапуск, тип бота, привязка к узлу или имени автоматизации
- редактор Lua с подсветкой, номерами строк, автоотступами и автодополнением `mesh.*`
- проверка синтаксиса и вывод ошибок выполнения
- отладчик с точками останова, пошаговым выполнением и просмотром локальных и глобальных переменных
- изолированное хранилище данных для каждого скрипта
- магазин скриптов с установкой, обновлением и удалением локальных копий

Скрипты работают в изолированной среде LuaJ. Отключены опасные API: `io`, `os`, `debug`, `package`, `require`, `dofile`, `loadfile`, `luajava`.

Точки входа:

- `on_message(msg)` — реакция на новое сообщение
- `on_command(command)` — обработка команды
- `on_node_selected(event)`, `on_traceroute(event)`, `on_node_info(event)` — обработчики асинхронных событий

Документация API: [docs/lua-api.ru.md](docs/lua-api.ru.md).

---

## Интерфейс и локальные данные

<p align="center">
  <img src="docs/screenshots/info-b.jpg" width="49%" alt="Справка и информация — тёмная тема"/>
  <img src="docs/screenshots/info-w.jpg" width="49%" alt="Справка и информация — светлая тема"/>
</p>

В интерфейсе есть тёмная и светлая тема AtlantaFX Cupertino, боковая панель, системный значок, всплывающие уведомления и быстрые переключатели темы и уведомлений. На Windows 11 используется Mica, на macOS — vibrancy. Размер и положение окна, а также положение разделителей сохраняются между сессиями.

Системные уведомления приходят для новых сообщений, если соответствующий чат не открыт. Проверку обновлений при запуске можно включить или выключить в настройках.

Локально сохраняются сообщения, реакции, непрочитанные чаты, избранные и игнорируемые узлы, телеметрия, скрипты, данные скриптов, история проверок маршрута и журнал LoRa-пакетов.

Также поддерживаются импорт публичного кэша OneMesh и локальный MQTT-мост для `MQTT proxy_to_client`. Параметры брокера берутся из MQTT-конфигурации устройства.

Для работы без JavaFX есть терминальный режим на Lanterna.

---

## Быстрый старт

### Требования

Для сборки и запуска из исходников нужны:

- Git
- JDK 25; Gradle может скачать нужный комплект автоматически
- macOS: Xcode Command Line Tools (`cc`) для `libmeshapp-serial.dylib` и `libmeshapp-tray.dylib`
- Windows: CMake + MSVC Build Tools для `meshapp-ble.dll`
- Linux: CMake + комплект инструментов C/C++ + `libsystemd-dev` / `systemd-devel` для `libmeshapp-ble.so`

Для готовых пакетов (`.dmg`, `.msi`, `.deb`, `.AppImage`, `.flatpak`) эти зависимости для сборки не нужны.

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
6. Если устройство или операционная система запросят код доступа, подтвердите сопряжение.
7. Нажмите **Подключить**.

После подключения Meshtastic доступны чаты, узлы, карта, настройки и остальные основные экраны. Для MeshCore Companion доступны чаты, узлы, личные сообщения, телеметрия, настройки и LoRa Debug. Для MeshCore KISS отображаются сведения о модеме.

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

Программный рендеринг для проверки проблем с macOS `CVDisplayLink` по умолчанию выключен. Для разовой проверки:

```bash
./gradlew run -PvisualVmSoftwareRenderingEnabled=true
```

Если VisualVM показывает `Provided Memory settings are invalid`, откройте настройки профилировщика памяти и замените шаблон в `Profile classes` на корректный фильтр, например `com.meshtastic.client.**` или `**`.

---

## Технологии

| Компонент | Технология | Назначение |
|-----------|------------|------------|
| Интерфейс | JavaFX 25.0.3 + AtlantaFX | Основной интерфейс |
| Meshtastic | Protobuf 4.33.4 + схемы Meshtastic | `ToRadio` / `FromRadio` и mesh-пакеты |
| MeshCore KISS | Формат KISS + MeshCore `SetHardware` | Начальный обмен и сведения о модеме MeshCore KISS |
| MeshCore Companion | Companion Protocol + BLE RX/TX или TCP/Serial без KISS-обёртки | Начальный обмен, сведения об устройстве, контакты, каналы, чаты и просмотр пакетов |
| Транспорт | `TransportConnection` | Общий контракт для TCP, Serial, BLE и будущих способов подключения |
| База данных | Встроенная H2 | Сообщения, телеметрия, скрипты, маршруты и журналы |
| Карты | JavaFX `TileMapView` + тайлы OpenStreetMap | Онлайн- и офлайн-карта, сохранённые маршруты |
| Среда Lua | LuaJ 3.0.1 | Изолированные скрипты, боты, хранилище данных и API `mesh.*` |
| Редактор Lua | RichTextFX | Подсветка, строки, автодополнение и отладчик |
| Терминальный режим | Lanterna | Текстовый интерфейс без JavaFX |
| MQTT-мост | Eclipse Paho MQTT | Локальный прокси для `proxy_to_client` |
| TCP | `java.net.Socket` | TCP API Meshtastic, MeshCore KISS или Companion-подключение без KISS-обёртки |
| Serial | Нативные JNA-бэкенды + поиск через jSerialComm | Нативный доступ к COM/tty |
| BLE | CoreBluetooth / WinRT / BlueZ через JNA | Поиск BLE-устройств, GATT и сопряжение |
| Нативные интеграции | JNA + интеграции с ОС | Mica, vibrancy, системный значок и платформенные слои |
| Сборка | Gradle 9.4.1 + Protobuf + CMake + jpackage | Сборка Java-кода, нативных библиотек и инсталляторов |

---

## Архитектура протоколов

Этот раздел нужен разработчикам. Для работы с приложением достаточно выбрать тип подключения и протокол в профиле.

Подключение в MeshApp разделено на два уровня:

- **Транспорт** открывает соединение, пишет байты и передаёт входящие данные на уровень протокола. Общий контракт — `TransportConnection`, фабрика — `TransportConnectionFactory`.
- **Среда протокола** отвечает за формат кадров, разбор пакетов, начальный обмен, состояние протокола и сервисы конкретного протокола. Общие контракты — `CommunicationProtocol`, `ProtocolRuntime`, `ProtocolRuntimeContext` и `ProtocolRegistry`.

Зарегистрированные протоколы:

| ProtocolType | Среда | Назначение |
|--------------|---------|------------|
| `MESHTASTIC` | `MeshtasticProtocolRuntime` | `ProtocolHandler`, `DeviceState`, обмен настройками, входящие mesh-пакеты, MQTT-прокси |
| `MESHCORE_KISS` | `MeshCoreKissProtocolRuntime` | Начальный обмен KISS `SetHardware`, имя устройства, версия, идентификатор, LoRa, батарея и статистика |
| `MESHCORE_COMPANION` | `MeshCoreCompanionProtocolRuntime` | Companion `APP_START`, сведения о своём устройстве, батарея, контакты, каналы, чаты и личные сообщения |

Выбор среды берётся из сохранённого `ProtocolType`. TCP/Serial получают соответствующий `FrameFormat`, BLE подключается к GATT-профилю выбранного протокола. Старые профили без поля `protocol` считаются Meshtastic-профилями.

Чтобы добавить новый протокол:

1. Добавить значение в `ProtocolType`
2. Реализовать `CommunicationProtocol<S>` и `ProtocolRuntime<S>`
3. Зарегистрировать адаптер в `ProtocolRegistry`
4. Добавить интерфейс и сервисы для состояния нового протокола
5. При необходимости расширить `ConnectionEntry` и `TransportConnectionFactory`

Часть интерфейса пока использует совместимые с Meshtastic методы доступа из `ConnectionManager` (`getDeviceState`, `getProtocolHandler`, `getConfigFuture`). Новым протоколам лучше получать своё состояние через абстракцию среды протокола или типизированные методы доступа.

---

## Структура проекта

```text
meshapp/
|-- src/main/java/com/meshtastic/client/
|   |-- MeshAppLauncher.java      # Точка входа: JavaFX или терминальный режим
|   |-- MeshApp.java              # JavaFX-приложение
|   |-- connection/               # TransportConnection и транспорт TCP/Serial/BLE
|   |   |-- ble/                  # BLE-транспорт и платформенные реализации
|   |   \-- serial/               # Нативный ввод-вывод Serial для Windows/macOS/Linux
|   |-- lua/                      # Среда Lua, изолированный API, магазин скриптов, импорт/экспорт
|   |-- protocol/                 # Общие API сред протоколов и реестр
|   |   |-- meshcore/             # Адаптеры и среды MeshCore KISS и Companion
|   |   \-- meshtastic/           # Адаптер и среда Meshtastic
|   |-- terminal/                 # Текстовый интерфейс на Lanterna
|   |-- model/                    # Модели данных и состояние выполнения
|   |-- service/                  # Хранение данных, поиск устройств, переподключение, обмен настройками
|   |-- forms/                    # Основные экраны приложения
|   |-- components/               # Переиспользуемые компоненты интерфейса
|   |   \-- map/                  # Компоненты карты с тайлами OpenStreetMap
|   |-- notification/             # Системные уведомления
|   |-- platform/                 # Интеграции с интерфейсом и возможностями ОС
|   |-- system/                   # Каркас приложения: FormManager, RootPane
|   |-- tray/                     # Системный значок
|   \-- themes/                   # Управление темами
|-- native/
|   |-- windows-ble/              # WinRT BLE DLL
|   |-- linux-ble/                # BlueZ BLE shared library
|   |-- macos-serial/             # Вспомогательная serial-библиотека для macOS
|   \-- macos-tray/               # Нативная интеграция системного значка macOS
|-- src/main/proto/meshtastic/    # Protobuf-схемы Meshtastic
|-- src/main/resources/           # CSS, fonts, icons, logos
\-- build.gradle                  # Настройки сборки
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

Для `Flatpak` нужны `flatpak`, `flatpak-builder`, среда выполнения и SDK. По умолчанию используется `org.freedesktop.Platform//25.08` и `org.freedesktop.Sdk//25.08`.

```bash
flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak --user install -y flathub org.freedesktop.Platform//25.08 org.freedesktop.Sdk//25.08
./gradlew flatpak
```

Среду выполнения можно переопределить через `-PflatpakRuntime=...`, `-PflatpakRuntimeVersion=...`, `-PflatpakSdk=...` и `-PflatpakBranch=...`.

Для публикации на Flathub используется `app.privatepractice.meshapp.yml`. После изменения Gradle-зависимостей нужно пересобрать список Maven-источников для офлайн-сборки:

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

Для `jpackage` можно явно указать JDK для встроенной среды выполнения: `-PpackagingJavaHome=/path/to/jdk` или `PACKAGING_JAVA_HOME=/path/to/jdk`. На macOS сборка проверяет `.app` через `otool -L` и завершается ошибкой, если внутри пакета остаются внешние зависимости вроде `/opt/homebrew/...` или `/usr/local/...`.

Во время `processResources` Gradle собирает нативные компоненты:

- Windows: `meshapp-ble.dll` для BLE через WinRT
- Linux: `libmeshapp-ble.so` для BLE через BlueZ
- macOS: `libmeshapp-serial.dylib` для линий управления serial-модемом
- macOS: `libmeshapp-tray.dylib` для системного значка

### Подпись и нотаризация на macOS

По умолчанию `./gradlew jpackage` на macOS делает ad-hoc-подпись `.app`. Для локальной проверки этого достаточно, но скачанный из браузера `.dmg` может получить от Gatekeeper сообщение **«Приложение повреждено, его не удается открыть»**.

Для релизной сборки нужны учётные данные для подписи `Developer ID`:

- `MAC_SIGNING_KEY_USER_NAME` или `-PmacSigningKeyUserName=...`
- `MAC_SIGNING_KEYCHAIN` или `-PmacSigningKeychain=...`
- `MAC_PACKAGE_SIGNING_PREFIX` или `-PmacPackageSigningPrefix=...`, по умолчанию `com.meshtastic`

Для раннера Gitea в режиме демона лучше импортировать сертификат из секретов во временную связку ключей:

- `MAC_SIGNING_CERTIFICATE_P12`
- `MAC_SIGNING_CERTIFICATE_PASSWORD`
- `MAC_SIGNING_KEYCHAIN_PASSWORD`

Для скачиваемых сборок нужен `Developer ID Application`. `Apple Development` подходит для разработки, но не для релизного DMG.

Нотаризацию можно включить одним из способов:

- `MAC_NOTARY_KEYCHAIN_PROFILE` или `-PmacNotaryKeychainProfile=...`
- `MAC_NOTARY_APPLE_ID` + `MAC_NOTARY_TEAM_ID` + `MAC_NOTARY_PASSWORD`
- `MAC_NOTARY_KEY_FILE` + `MAC_NOTARY_KEY_ID` + `MAC_NOTARY_ISSUER`
- В CI вместо `MAC_NOTARY_KEY_FILE` можно передать `MAC_NOTARY_KEY_FILE_BASE64`

После этого `./gradlew jpackage` соберёт подписанные `.app` и `.dmg`, затем выполнит `notarytool submit --wait` и `stapler`.

Если в раннере Gitea нет `Developer ID Application`, рабочий процесс всё равно соберёт macOS-артефакт с прежним именем, но пропустит проверки `spctl` и нотаризации.

### Установка на macOS

Если сборка сделана без `Developer ID` и нотаризации, macOS может показать предупреждение **«от неизвестного разработчика»** или **«Приложение повреждено, его не удается открыть»**. Для локальной ad-hoc-сборки это ожидаемо.

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
