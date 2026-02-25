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
  <img src="https://img.shields.io/badge/JavaFX-21.0.7-blue?logo=java" alt="JavaFX"/>
  <img src="https://img.shields.io/badge/Platform-Win%20%7C%20macOS%20%7C%20Linux-brightgreen" alt="Platform"/>
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="License"/>
  <a href="https://t.me/MeshAppClient">
  <img src="https://img.shields.io/badge/Telegram-@MeshAppClient-blue?logo=telegram" alt="Telegram">
</a>
</p>

<div align="right">

[![Release](https://img.shields.io/badge/Release-v1.0.35-blue?style=flat-square&logo=gitea)](https://git.privatepractice.app/covox/meshapp/releases/tag/v1.1.0-beta)

</div>

---



## O проекте

**MeshApp** — полнофункциональный десктопный клиент для управления устройствами и общения в mesh-сети [Meshtastic](https://meshtastic.org). Приложение заменяет мобильные клиенты на ПК и предоставляет расширенные возможности мониторинга, визуализации телеметрии и настройки радиомодулей.

Meshtastic — открытый проект, превращающий недорогие LoRa-модули в узлы децентрализованной mesh-сети. Сообщения передаются на расстояние от сотен метров до десятков километров — без интернета, вышек сотовой связи и какой-либо инфраструктуры.

```
                       +-----------------------------------------------+
                       |                  MeshApp                      |
                       |                                               |
  +--------+   TCP     |  +----------+  +---------+  +------------+    |
  |  LoRa  |<--------->|  | Protocol |->|  Model  |->|     UI     |    |
  | Device |   USB     |  | Handler  |  |  State  |  |  (JavaFX)  |    |
  +--------+<--------->|  +----------+  +---------+  +------------+    |
                       |                                               |
                       +-----------------------------------------------+
```

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
- **Статусы доставки** — ACK/NAK отслеживание отправленных сообщений
- **Traceroute** — визуализация маршрута до любого узла сети
- **Запрос NodeInfo** — получение актуальных данных об узле по запросу
- **Emoji** — встроенный выбор эмодзи
- **Счётчик непрочитанных** — бейджи на каждом чате
- **История** — полная история сообщений с поиском, хранится в локальной БД

---

### Узлы сети

<p align="center">
  <img src="docs/screenshots/nodes-b.png" width="49%" alt="Узлы — тёмная тема"/>
  <img src="docs/screenshots/nodes-w.png" width="49%" alt="Узлы — светлая тема"/>
</p>

- **Список узлов** с сортировкой по дистанции (хопы) и имени
- **Поиск** по имени, короткому имени, ID или числовому адресу
- **Детальная информация** — железо, роль, координаты, прошивка, SNR/RSSI
- **Кэширование узлов** — локальная база с пагинацией

---

### Телеметрия и мониторинг

<p align="center">
  <img src="docs/screenshots/telemetry-b.png" width="49%" alt="Телеметрия — тёмная тема"/>
  <img src="docs/screenshots/telemetry-w.png" width="49%" alt="Телеметрия — светлая тема"/>
</p>

- **Графики в реальном времени** — батарея, напряжение, загрузка канала, Air Util TX
- **Фильтрация по периодам** — от 1 часа до всей истории
- **Агрегация данных** — автоматическое усреднение для плавных кривых
- **Таблица телеметрии** — детализированные записи с временными метками

---

### Подключения

<p align="center">
  <img src="docs/screenshots/connections-b.png" width="49%" alt="Подключения — тёмная тема"/>
  <img src="docs/screenshots/connections-w.png" width="49%" alt="Подключения — светлая тема"/>
</p>

- **TCP и Serial** — подключение по сети или USB
- **Несколько устройств** — одновременная работа с несколькими радиомодулями
- **Профили подключений** — сохранение и быстрое переключение
- **Автообмен конфигурацией** — автоматическое получение параметров устройства при подключении

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

---

### Интерфейс

<p align="center">
  <img src="docs/screenshots/logs-b.png" width="49%" alt="Логи — тёмная тема"/>
  <img src="docs/screenshots/logs-w.png" width="49%" alt="Логи — светлая тема"/>
</p>

- **Тёмная и светлая тема** — AtlantaFX Cupertino
- **Нативное оформление окна** — эффект Mica (Windows 11), vibrancy (macOS)
- **Кастомный titlebar** — кнопки управления окном в стиле платформы
- **Боковая панель** — быстрая навигация между разделами
- **Toast-уведомления** — информирование о событиях без отрыва от работы
- **Встроенные логи** — просмотр отладочной информации с цветовой кодировкой по уровню

---

## Быстрый старт

### Требования

- **JDK 21+** (скачивается автоматически через Gradle Toolchain)
- **Git** для клонирования репозитория

### Сборка и запуск

```bash
# Клонировать репозиторий
git clone https://github.com/<your-org>/meshapp.git
cd meshapp

# Запустить приложение
./gradlew run

# Собрать нативный инсталлятор (.dmg / .msi / .deb)
./gradlew jpackage
```

### Подключение к устройству

1. Подключите Meshtastic-устройство по USB или убедитесь, что оно доступно по TCP
2. В разделе **Подключения** добавьте новое подключение (IP:порт или COM-порт)
3. Нажмите **Подключить** — MeshApp автоматически обменяется конфигурацией с устройством
4. Переключитесь в **Чат** для обмена сообщениями или в **Узлы** для мониторинга сети

---

## Технологии

| Компонент | Технология | Назначение |
|-----------|-----------|------------|
| UI | JavaFX 21 + AtlantaFX | Интерфейс с нативным оформлением |
| Протокол | Protobuf 4.33 | Сериализация mesh-пакетов |
| База данных | H2 (embedded) | Локальное хранение сообщений и телеметрии |
| Serial | jSerialComm | USB-подключение к устройствам |
| Нативные эффекты | JNA | Mica (Win), vibrancy (macOS) |
| Сборка | Gradle 8.13 | Компиляция, jpackage-инсталляторы |

---

## Структура проекта

```
meshapp/
|-- src/main/java/com/meshtastic/client/
|   |-- MeshApp.java              # Entry point (JavaFX Application)
|   |-- connection/               # Transport layer (TCP, Serial)
|   |-- protocol/                 # Meshtastic protocol parsing
|   |-- model/                    # Data models (thread-safe)
|   |-- service/                  # Business logic & persistence
|   |-- forms/                    # Application screens
|   |-- components/               # Reusable UI components
|   |-- system/                   # App framework (FormManager, RootPane)
|   |-- platform/                 # OS-specific code (Win/Mac/Linux)
|   \-- themes/                   # Theme management
|-- src/main/proto/meshtastic/    # Meshtastic protobuf schemas
|-- src/main/resources/           # CSS, fonts, icons, logos
\-- build.gradle                  # Build configuration
```

---

## Сборка инсталляторов

MeshApp собирается в нативные пакеты через `jpackage`:

| Платформа | Формат | Команда |
|-----------|--------|---------|
| Windows | `.msi` | `./gradlew jpackage` |
| macOS | `.dmg` | `./gradlew jpackage` |
| Linux | `.deb` | `./gradlew jpackage` |

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
