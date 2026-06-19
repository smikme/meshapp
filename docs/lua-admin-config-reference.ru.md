# Справочник `mesh.admin` по конфигурации

**Язык:** Русский | [English](lua-admin-config-reference.md) | [Deutsch](lua-admin-config-reference.de.md)

Этот справочник перечисляет параметры, которые возвращаются в `event.snapshot` после `mesh.admin.load_config` / `request_config` / `request_module_config` и которые можно передавать в `mesh.admin.save_config`.

Имена полей в Lua совпадают с protobuf `snake_case`. Enum-значения передаются строками. `bytes` можно передавать как hex, `hex:...`, `base64:...` или Base64. Повторяющиеся поля передаются Lua-списками. По умолчанию `save_config` сливает патч с уже загруженной секцией; сначала вызовите `load_config`, `request_config` или `request_module_config`, либо используйте `{ replace = true, confirm = true }` для осознанной замены из значений по умолчанию.

## `event.snapshot`

| Поле | Тип Lua | Описание |
| --- | --- | --- |
| `target_node_num` | number | Числовой ID целевой ноды. |
| `target_node_id` | string | ID целевой ноды в виде `!abcdef12`. |
| `node` | table | Текущая запись целевой ноды. |
| `owner` | table или `nil` | Данные владельца/пользователя, загруженные с удаленной ноды. |
| `device_metadata` | table или `nil` | Метаданные устройства, загруженные с удаленной ноды. |
| `ringtone` | string | Текущий RTTTL-текст рингтона. |
| `canned_messages` | string | Текущее содержимое модуля canned messages. |
| `canned_messages_loaded` | boolean | Признак, что canned messages были загружены. |
| `connection_status` | table или `nil` | Статус соединений удаленного устройства. |
| `configs` | table | Основные секции конфигурации; поля совпадают с `changes.configs` ниже. |
| `module_configs` | table | Секции конфигурации модулей; поля совпадают с `changes.module_configs` ниже. |
| `channels` | список table | Загруженные каналы; поля совпадают с `changes.channels` ниже. |
| `query_statuses` | список table | Статусы загрузки блоков: `key`, `state`, `detail`. |
| `query_summary` | table | Итог загрузки: `total`, `received`, `failed`. |

## Верхний уровень `save_config`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `owner.long_name` | string |  | Длинное имя ноды. |
| `owner.short_name` | string |  | Короткое имя ноды. |
| `owner.licensed` | boolean |  | Флаг лицензированного оператора. |
| `position.latitude` | number |  | Широта в градусах; отправляется как ручная фиксированная позиция. |
| `position.longitude` | number |  | Долгота в градусах; отправляется как ручная фиксированная позиция. |
| `position.altitude` | number |  | Высота в метрах. |
| `remove_position` | boolean |  | `true` очищает ручную фиксированную позицию. |
| `ringtone` | string |  | RTTTL-текст рингтона. |
| `canned_messages` | string |  | Текстовое содержимое модуля canned messages. |
| `configs` | table |  | Основные секции конфигурации ниже. |
| `module_configs` | table |  | Секции конфигурации модулей ниже. |
| `channels` | список table |  | Список патчей каналов. |

## Основная конфигурация: `changes.configs`

Доступные секции:

| Секция | Lua-патч | Поля верхнего уровня |
| --- | --- | --- |
| `device` | `configs.device` | `role`, `serial_enabled`, `button_gpio`, `buzzer_gpio`, `rebroadcast_mode`, `node_info_broadcast_secs`, `double_tap_as_button_press`, `is_managed`, `disable_triple_click`, `tzdef`, `led_heartbeat_disabled`, `buzzer_mode` |
| `position` | `configs.position` | `position_broadcast_secs`, `position_broadcast_smart_enabled`, `fixed_position`, `gps_enabled`, `gps_update_interval`, `gps_attempt_time`, `position_flags`, `rx_gpio`, `tx_gpio`, `broadcast_smart_minimum_distance`, `broadcast_smart_minimum_interval_secs`, `gps_en_gpio`, `gps_mode` |
| `power` | `configs.power` | `is_power_saving`, `on_battery_shutdown_after_secs`, `adc_multiplier_override`, `wait_bluetooth_secs`, `sds_secs`, `ls_secs`, `min_wake_secs`, `device_battery_ina_address`, `powermon_enables` |
| `network` | `configs.network` | `wifi_enabled`, `wifi_ssid`, `wifi_psk`, `ntp_server`, `eth_enabled`, `address_mode`, `ipv4_config`, `ipv4_config.ip`, `ipv4_config.gateway`, `ipv4_config.subnet`, `ipv4_config.dns`, `rsyslog_server`, `enabled_protocols`, `ipv6_enabled` |
| `display` | `configs.display` | `screen_on_secs`, `gps_format`, `auto_screen_carousel_secs`, `compass_north_top`, `flip_screen`, `units`, `oled`, `displaymode`, `heading_bold`, `wake_on_tap_or_motion`, `compass_orientation`, `use_12h_clock`, `use_long_node_name`, `enable_message_bubbles` |
| `lora` | `configs.lora` | `use_preset`, `modem_preset`, `bandwidth`, `spread_factor`, `coding_rate`, `frequency_offset`, `region`, `hop_limit`, `tx_enabled`, `tx_power`, `channel_num`, `override_duty_cycle`, `sx126x_rx_boosted_gain`, `override_frequency`, `pa_fan_disabled`, `ignore_incoming`, `ignore_mqtt`, `config_ok_to_mqtt`, `fem_lna_mode`, `serial_hal_only` |
| `bluetooth` | `configs.bluetooth` | `enabled`, `mode`, `fixed_pin` |
| `security` | `configs.security` | `public_key`, `private_key`, `admin_key`, `is_managed`, `serial_enabled`, `debug_log_api_enabled`, `admin_channel_enabled` |
| `device_ui` | `configs.device_ui` | `version`, `screen_brightness`, `screen_timeout`, `screen_lock`, `settings_lock`, `pin_code`, `theme`, `alert_enabled`, `banner_enabled`, `ring_tone_id`, `language`, `node_filter`, `node_filter.unknown_switch`, `node_filter.offline_switch`, `node_filter.public_key_switch`, `node_filter.hops_away`, `node_filter.position_switch`, `node_filter.node_name`, `node_filter.channel`, `node_highlight`, `node_highlight.chat_switch`, `node_highlight.position_switch`, `node_highlight.telemetry_switch`, `node_highlight.iaq_switch`, `node_highlight.node_name`, `calibration_data`, `map_data`, `map_data.home`, `map_data.home.zoom`, `map_data.home.latitude`, `map_data.home.longitude`, `map_data.style`, `map_data.follow_gps`, `compass_mode`, `screen_rgb_color`, `is_clockface_analog`, `gps_format` |

### `device`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `role` | enum string | `CLIENT`, `CLIENT_MUTE`, `ROUTER`, `ROUTER_CLIENT` устарело, `REPEATER` устарело, `TRACKER`, `SENSOR`, `TAK`, `CLIENT_HIDDEN`, `LOST_AND_FOUND`, `TAK_TRACKER`, `ROUTER_LATE`, `CLIENT_BASE` | Роль устройства в mesh-сети. |
| `serial_enabled` | boolean |  | Устарело. Включение serial console перенесено в `security.serial_enabled`. |
| `button_gpio` | number |  | GPIO кнопки для плат без аппаратной кнопки; на платах с несколькими кнопками может переопределять назначение. |
| `buzzer_gpio` | number |  | GPIO пищалки для плат без PWM buzzer. |
| `rebroadcast_mode` | enum string | `ALL`, `ALL_SKIP_DECODING`, `LOCAL_ONLY`, `KNOWN_ONLY`, `NONE`, `CORE_PORTNUMS_ONLY` | Режим ретрансляции пакетов. |
| `node_info_broadcast_secs` | number |  | Как часто отправлять NodeInfo; по умолчанию 900 секунд. |
| `double_tap_as_button_press` | boolean |  | Считать двойной тап акселерометра нажатием кнопки. |
| `is_managed` | boolean |  | Устарело. Флаг управляемого устройства перенесен в `security.is_managed`. |
| `disable_triple_click` | boolean |  | Отключает тройное нажатие кнопки для включения/выключения GPS. |
| `tzdef` | string |  | POSIX-строка таймзоны. |
| `led_heartbeat_disabled` | boolean |  | Отключает стандартное мигание индикатора heartbeat. |
| `buzzer_mode` | enum string | `ALL_ENABLED`, `DISABLED`, `NOTIFICATIONS_ONLY`, `SYSTEM_ONLY`, `DIRECT_MSG_ONLY` | Режим звуковой обратной связи. |

### `position`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `position_broadcast_secs` | number |  | Как часто отправлять позицию, если она существенно изменилась. |
| `position_broadcast_smart_enabled` | boolean |  | Включает адаптивную отправку позиции. |
| `fixed_position` | boolean |  | Фиксированная позиция ноды; устройство будет публиковать последние заданные широту, долготу и высоту. |
| `gps_enabled` | boolean |  | Устарело. Используйте `gps_mode`. |
| `gps_update_interval` | number |  | Интервал попыток получения GPS-позиции в секундах; `0` означает значение по умолчанию. |
| `gps_attempt_time` | number |  | Устарело. Время попытки теперь выводится из интервалов отправки. |
| `position_flags` | number |  | Битовая маска дополнительных полей POSITION. |
| `rx_gpio` | number |  | GPIO RX для GPS. |
| `tx_gpio` | number |  | GPIO TX для GPS. |
| `broadcast_smart_minimum_distance` | number |  | Минимальная дистанция в метрах для smart-отправки позиции. |
| `broadcast_smart_minimum_interval_secs` | number |  | Минимальный интервал в секундах для smart-отправки позиции. |
| `gps_en_gpio` | number |  | GPIO включения GPS. |
| `gps_mode` | enum string | `DISABLED`, `ENABLED`, `NOT_PRESENT` | Состояние GPS: включен, выключен или отсутствует. |

### `power`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `is_power_saving` | boolean |  | Максимально агрессивный режим энергосбережения; полезен для ролей tracker/sensor, но может мешать работе с приложением. |
| `on_battery_shutdown_after_secs` | number |  | Если не `0`, устройство полностью выключится через это число секунд после отключения внешнего питания. |
| `adc_multiplier_override` | number |  | Коэффициент делителя напряжения батареи; обычно 2-6. |
| `wait_bluetooth_secs` | number |  | Сколько секунд ждать перед выключением BLE в режимах без Bluetooth. |
| `sds_secs` | number |  | Длительность super deep sleep; `0` означает значение по умолчанию. |
| `ls_secs` | number |  | Длительность light sleep; `0` означает значение по умолчанию. |
| `min_wake_secs` | number |  | Минимальное время бодрствования после приема LoRa-пакета в light sleep. |
| `device_battery_ina_address` | number |  | I2C-адрес INA_2XX для измерения напряжения батареи. |
| `powermon_enables` | number |  | Битовая маска источников powermon-логов. |

### `network`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `wifi_enabled` | boolean |  | Включает Wi-Fi и отключает Bluetooth. |
| `wifi_ssid` | string |  | SSID Wi-Fi сети. |
| `wifi_psk` | string |  | Пароль Wi-Fi сети. |
| `ntp_server` | string |  | NTP-сервер при подключенном Wi-Fi. |
| `eth_enabled` | boolean |  | Включает Ethernet. |
| `address_mode` | enum string | `DHCP`, `STATIC` | Получать IP по DHCP или использовать статический адрес. |
| `ipv4_config` | table |  | Настройки статического IPv4. |
| `ipv4_config.ip` | number |  | Статический IP-адрес. |
| `ipv4_config.gateway` | number |  | Статический gateway. |
| `ipv4_config.subnet` | number |  | Маска подсети. |
| `ipv4_config.dns` | number |  | DNS-сервер. |
| `rsyslog_server` | string |  | Сервер и порт rsyslog. |
| `enabled_protocols` | number |  | Битовая маска включенных сетевых протоколов. |
| `ipv6_enabled` | boolean |  | Включает IPv6. |

### `display`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `screen_on_secs` | number |  | Сколько секунд держать экран включенным после кнопки или сообщения; `0` означает значение по умолчанию. |
| `gps_format` | enum string | `UNUSED` | Устарело. Формат координат на экране больше не используется в этой секции. |
| `auto_screen_carousel_secs` | number |  | Интервал автоматического переключения экранов. |
| `compass_north_top` | boolean |  | Устарело. Фиксирует север сверху компаса. |
| `flip_screen` | boolean |  | Переворачивает экран по вертикали. |
| `units` | enum string | `METRIC`, `IMPERIAL` | Единицы отображения: метрические или имперские. |
| `oled` | enum string | `OLED_AUTO`, `OLED_SSD1306`, `OLED_SH1106`, `OLED_SH1107`, `OLED_SH1107_128_128`, `OLED_SH1107_ROTATED` | Тип OLED-дисплея при необходимости переопределить автоопределение. |
| `displaymode` | enum string | `DEFAULT`, `TWOCOLOR`, `INVERTED`, `COLOR` | Режим отображения экрана. |
| `heading_bold` | boolean |  | Показывать первую строку псевдожирным стилем. |
| `wake_on_tap_or_motion` | boolean |  | Будить экран по тапу или движению. |
| `compass_orientation` | enum string | `DEGREES_0`, `DEGREES_90`, `DEGREES_180`, `DEGREES_270`, `DEGREES_0_INVERTED`, `DEGREES_90_INVERTED`, `DEGREES_180_INVERTED`, `DEGREES_270_INVERTED` | Поворот или инверсия компаса для корректной ориентации на устройстве. |
| `use_12h_clock` | boolean |  | Использовать 12-часовой формат времени вместо 24-часового. |
| `use_long_node_name` | boolean |  | Показывать длинные имена нод вместо коротких. |
| `enable_message_bubbles` | boolean |  | Показывать сообщения на экране в виде пузырьков. |

### `lora`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `use_preset` | boolean |  | Использовать `modem_preset`; если выключено, применяются ручные `bandwidth`, `spread_factor`, `coding_rate`. |
| `modem_preset` | enum string | `LONG_FAST`, `LONG_SLOW` устарело, `VERY_LONG_SLOW` устарело, `MEDIUM_SLOW`, `MEDIUM_FAST`, `SHORT_SLOW`, `SHORT_FAST`, `LONG_MODERATE`, `SHORT_TURBO`, `LONG_TURBO`, `LITE_FAST`, `LITE_SLOW`, `NARROW_FAST`, `NARROW_SLOW` | Преднастроенный режим модема LoRa. |
| `bandwidth` | number |  | Ширина полосы; обычно используется только при ручной настройке LoRa. |
| `spread_factor` | number |  | Коэффициент расширения спектра от 7 до 12. |
| `coding_rate` | number |  | Знаменатель coding rate, например `5` для 4/5. |
| `frequency_offset` | number |  | Частотная поправка для калибровки; поле для опытных пользователей. |
| `region` | enum string | `UNSET`, `US`, `EU_433`, `EU_868`, `CN`, `JP`, `ANZ`, `KR`, `TW`, `RU`, `IN`, `NZ_865`, `TH`, `LORA_24`, `UA_433`, `UA_868`, `MY_433`, `MY_919`, `SG_923`, `PH_433`, `PH_868`, `PH_915`, `ANZ_433`, `KZ_433`, `KZ_863`, `NP_865`, `BR_902`, `ITU1_2M`, `ITU23_2M`, `EU_866`, `EU_874`, `EU_917`, `EU_N_868` | Регион радиомодуля. |
| `hop_limit` | number |  | Максимальное число hops; прошивка не допускает значение больше 7. |
| `tx_enabled` | boolean |  | Разрешает передачу LoRa; выключение полезно для тестов и смены антенны. |
| `tx_power` | number |  | Мощность передачи в dBm; `0` означает безопасное значение по умолчанию. |
| `channel_num` | number |  | Номер радиоканала в регионе; `0` использует расчет по имени канала. |
| `override_duty_cycle` | boolean |  | Разрешает превышение лимита duty cycle; использовать только при понимании местных правил. |
| `sx126x_rx_boosted_gain` | boolean |  | Включает RX boosted gain для SX126X. |
| `override_frequency` | number |  | Ручная частота вместо расчета канала; поле для опытных пользователей и лицензированных HAM-операторов. |
| `pa_fan_disabled` | boolean |  | Отключает встроенный вентилятор PA. |
| `ignore_incoming` | список number |  | Список node_num, пакеты от которых будут игнорироваться при приеме. |
| `ignore_mqtt` | boolean |  | Игнорировать LoRa-пакеты, которые проходили через MQTT. |
| `config_ok_to_mqtt` | boolean |  | Выставлять флаг `ok_to_mqtt` на исходящих пакетах. |
| `fem_lna_mode` | enum string | `DISABLED`, `ENABLED`, `NOT_PRESENT` | Состояние FEM/LNA: включен, выключен или отсутствует. |
| `serial_hal_only` | boolean |  | Не инициализировать радиомодуль через RadioLib, а ждать serial HAL. |

### `bluetooth`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Включает Bluetooth. |
| `mode` | enum string | `RANDOM_PIN`, `FIXED_PIN`, `NO_PIN` | Режим сопряжения. |
| `fixed_pin` | number |  | Фиксированный PIN для режима `FIXED_PIN`. |

### `security`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `public_key` | string (hex/base64) |  | Публичный ключ устройства для вычисления общего секрета с другими нодами. |
| `private_key` | string (hex/base64) |  | Приватный ключ устройства. |
| `admin_key` | список string (hex/base64) |  | Публичные ключи клиентов, которым разрешены admin-команды на эту ноду. |
| `is_managed` | boolean |  | Помечает устройство как управляемое администратором. |
| `serial_enabled` | boolean |  | Включает serial-консоль через Stream API. |
| `debug_log_api_enabled` | boolean |  | Разрешает live debug logging через serial или Bluetooth после подключения API-клиента. |
| `admin_channel_enabled` | boolean |  | Разрешает входящее управление через небезопасный legacy admin channel. |

### `device_ui`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `version` | number |  | Версия структуры UI-настроек для инвалидирования несовместимых сохраненных данных. |
| `screen_brightness` | number |  | Яркость TFT-дисплея, 1-255. |
| `screen_timeout` | number |  | Таймаут экрана, 0-900 секунд. |
| `screen_lock` | boolean |  | Включает блокировку экрана/настроек. |
| `settings_lock` | boolean |  | Блокировка настроек. |
| `pin_code` | number |  | PIN-код. |
| `theme` | enum string | `DARK`, `LIGHT`, `RED` | Цветовая тема. |
| `alert_enabled` | boolean |  | Включает звуковые уведомления, banner и ringtone. |
| `banner_enabled` | boolean |  | Включает banner-уведомления. |
| `ring_tone_id` | number |  | ID рингтона. |
| `language` | enum string | `ENGLISH`, `FRENCH`, `GERMAN`, `ITALIAN`, `PORTUGUESE`, `SPANISH`, `SWEDISH`, `FINNISH`, `POLISH`, `TURKISH`, `SERBIAN`, `RUSSIAN`, `DUTCH`, `GREEK`, `NORWEGIAN`, `SLOVENIAN`, `UKRAINIAN`, `BULGARIAN`, `CZECH`, `DANISH`, `SIMPLIFIED_CHINESE`, `TRADITIONAL_CHINESE` | Язык интерфейса. |
| `node_filter` | table |  | Фильтр списка нод. |
| `node_filter.unknown_switch` | boolean |  | Фильтровать неизвестные ноды. |
| `node_filter.offline_switch` | boolean |  | Фильтровать ноды вне сети. |
| `node_filter.public_key_switch` | boolean |  | Фильтровать ноды без публичного ключа. |
| `node_filter.hops_away` | number |  | Фильтр по удаленности в hops. |
| `node_filter.position_switch` | boolean |  | Фильтровать ноды без позиции. |
| `node_filter.node_name` | string |  | Фильтр нод по совпадению имени. |
| `node_filter.channel` | number |  | Фильтр по каналу. |
| `node_highlight` | table |  | Подсветка нод в списке. |
| `node_highlight.chat_switch` | boolean |  | Подсвечивать ноды с активным чатом. |
| `node_highlight.position_switch` | boolean |  | Подсвечивать ноды с позицией. |
| `node_highlight.telemetry_switch` | boolean |  | Подсвечивать ноды с телеметрией. |
| `node_highlight.iaq_switch` | boolean |  | Подсвечивать ноды с IAQ-данными. |
| `node_highlight.node_name` | string |  | Подсвечивать ноды по совпадению имени. |
| `calibration_data` | string (hex/base64) |  | Данные калибровки экрана. |
| `map_data` | table |  | Данные карты. |
| `map_data.home` | table |  | Домашние координаты карты. |
| `map_data.home.zoom` | number |  | Уровень масштаба. |
| `map_data.home.latitude` | number |  | Широта домашней точки. |
| `map_data.home.longitude` | number |  | Долгота домашней точки. |
| `map_data.style` | string |  | Стиль тайлов карты. |
| `map_data.follow_gps` | boolean |  | Прокручивать карту вслед за GPS. |
| `compass_mode` | enum string | `DYNAMIC`, `FIXED_RING`, `FREEZE_HEADING` | Режим компаса. |
| `screen_rgb_color` | number |  | RGB-цвет экрана в формате `0xRRGGBB`. |
| `is_clockface_analog` | boolean |  | Аналоговый clockface при `true`, цифровой при `false`. |
| `gps_format` | enum string | `DEC`, `DMS`, `UTM`, `MGRS`, `OLC`, `OSGR`, `MLS` | Формат отображения GPS-координат. |

## Конфигурация модулей: `changes.module_configs`

Доступные секции:

| Секция | Lua-патч | Поля верхнего уровня |
| --- | --- | --- |
| `mqtt` | `module_configs.mqtt` | `enabled`, `address`, `username`, `password`, `encryption_enabled`, `json_enabled`, `tls_enabled`, `root`, `proxy_to_client_enabled`, `map_reporting_enabled`, `map_report_settings`, `map_report_settings.publish_interval_secs`, `map_report_settings.position_precision`, `map_report_settings.should_report_location` |
| `serial` | `module_configs.serial` | `enabled`, `echo`, `rxd`, `txd`, `baud`, `timeout`, `mode`, `override_console_serial_port` |
| `external_notification` | `module_configs.external_notification` | `enabled`, `output_ms`, `output`, `output_vibra`, `output_buzzer`, `active`, `alert_message`, `alert_message_vibra`, `alert_message_buzzer`, `alert_bell`, `alert_bell_vibra`, `alert_bell_buzzer`, `use_pwm`, `nag_timeout`, `use_i2s_as_buzzer` |
| `store_forward` | `module_configs.store_forward` | `enabled`, `heartbeat`, `records`, `history_return_max`, `history_return_window`, `is_server` |
| `range_test` | `module_configs.range_test` | `enabled`, `sender`, `save`, `clear_on_reboot` |
| `telemetry` | `module_configs.telemetry` | `device_update_interval`, `environment_update_interval`, `environment_measurement_enabled`, `environment_screen_enabled`, `environment_display_fahrenheit`, `air_quality_enabled`, `air_quality_interval`, `power_measurement_enabled`, `power_update_interval`, `power_screen_enabled`, `health_measurement_enabled`, `health_update_interval`, `health_screen_enabled`, `device_telemetry_enabled`, `air_quality_screen_enabled` |
| `canned_message` | `module_configs.canned_message` | `rotary1_enabled`, `inputbroker_pin_a`, `inputbroker_pin_b`, `inputbroker_pin_press`, `inputbroker_event_cw`, `inputbroker_event_ccw`, `inputbroker_event_press`, `updown1_enabled`, `enabled`, `allow_input_source`, `send_bell` |
| `audio` | `module_configs.audio` | `codec2_enabled`, `ptt_pin`, `bitrate`, `i2s_ws`, `i2s_sd`, `i2s_din`, `i2s_sck` |
| `remote_hardware` | `module_configs.remote_hardware` | `enabled`, `allow_undefined_pin_access`, `available_pins[]` |
| `neighbor_info` | `module_configs.neighbor_info` | `enabled`, `update_interval`, `transmit_over_lora` |
| `ambient_lighting` | `module_configs.ambient_lighting` | `led_state`, `current`, `red`, `green`, `blue` |
| `detection_sensor` | `module_configs.detection_sensor` | `enabled`, `minimum_broadcast_secs`, `state_broadcast_secs`, `send_bell`, `name`, `monitor_pin`, `detection_trigger_type`, `use_pullup` |
| `paxcounter` | `module_configs.paxcounter` | `enabled`, `paxcounter_update_interval`, `wifi_threshold`, `ble_threshold` |
| `statusmessage` | `module_configs.statusmessage` | `node_status` |
| `traffic_management` | `module_configs.traffic_management` | `enabled`, `position_dedup_enabled`, `position_precision_bits`, `position_min_interval_secs`, `nodeinfo_direct_response`, `nodeinfo_direct_response_max_hops`, `rate_limit_enabled`, `rate_limit_window_secs`, `rate_limit_max_packets`, `drop_unknown_enabled`, `unknown_packet_threshold`, `exhaust_hop_telemetry`, `exhaust_hop_position`, `router_preserve_hops` |
| `tak` | `module_configs.tak` | `team`, `role` |

### `mqtt`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Включает MQTT gateway для каналов с uplink/downlink. |
| `address` | string |  | Адрес MQTT-сервера; пустое значение использует сервер по умолчанию. |
| `username` | string |  | Имя пользователя MQTT. |
| `password` | string |  | Пароль MQTT. |
| `encryption_enabled` | boolean |  | Отправлять в MQTT зашифрованные или расшифрованные пакеты. |
| `json_enabled` | boolean |  | Устарело. JSON packet support в MQTT удален и поле игнорируется. |
| `tls_enabled` | boolean |  | Включает TLS для MQTT. |
| `root` | string |  | Корневой topic для MQTT-сообщений, по умолчанию `msh`. |
| `proxy_to_client_enabled` | boolean |  | Разрешает использовать подключенный клиент как MQTT proxy. |
| `map_reporting_enabled` | boolean |  | Периодически отправлять незашифрованную информацию о ноде на карту через MQTT. |
| `map_report_settings` | table |  | Настройки отправки данных на карту. |
| `map_report_settings.publish_interval_secs` | number |  | Интервал публикации на карту в секундах. |
| `map_report_settings.position_precision` | number |  | Точность позиции в битах. |
| `map_report_settings.should_report_location` | boolean |  | Согласие отправлять позицию на карту. |

### `serial`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Включает модуль Serial. |
| `echo` | boolean |  | Включает echo для модуля Serial. |
| `rxd` | number |  | GPIO RX для модуля Serial. |
| `txd` | number |  | GPIO TX для модуля Serial. |
| `baud` | enum string | `BAUD_DEFAULT`, `BAUD_110`, `BAUD_300`, `BAUD_600`, `BAUD_1200`, `BAUD_2400`, `BAUD_4800`, `BAUD_9600`, `BAUD_19200`, `BAUD_38400`, `BAUD_57600`, `BAUD_115200`, `BAUD_230400`, `BAUD_460800`, `BAUD_576000`, `BAUD_921600` | Скорость serial. |
| `timeout` | number |  | Таймаут serial. |
| `mode` | enum string | `DEFAULT`, `SIMPLE`, `PROTO`, `TEXTMSG`, `NMEA`, `CALTOPO`, `WS85`, `VE_DIRECT`, `MS_CONFIG`, `LOG`, `LOGTEXT` | Режим работы модуля Serial. |
| `override_console_serial_port` | boolean |  | Переопределяет serial port платформы для модуля Serial; применимо в основном к режимам вывода. |

### `external_notification`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Включает модуль ExternalNotification. |
| `output_ms` | number |  | Сколько миллисекунд держать основной выход включенным. |
| `output` | number |  | GPIO основного выхода. |
| `output_vibra` | number |  | GPIO дополнительного vibra-выхода. |
| `output_buzzer` | number |  | GPIO дополнительного buzzer-выхода. |
| `active` | boolean |  | `true` означает active high, `false` означает active low. |
| `alert_message` | boolean |  | Срабатывать при текстовом сообщении на основном выходе. |
| `alert_message_vibra` | boolean |  | Срабатывать при текстовом сообщении на vibra-выходе. |
| `alert_message_buzzer` | boolean |  | Срабатывать при текстовом сообщении на buzzer-выходе. |
| `alert_bell` | boolean |  | Срабатывать при получении bell character на основном выходе. |
| `alert_bell_vibra` | boolean |  | Срабатывать при bell character на vibra-выходе. |
| `alert_bell_buzzer` | boolean |  | Срабатывать при bell character на buzzer-выходе. |
| `use_pwm` | boolean |  | Использовать PWM-выход вместо обычного on/off выхода. |
| `nag_timeout` | number |  | Сколько секунд повторять уведомление; `0` отключает повтор. |
| `use_i2s_as_buzzer` | boolean |  | Использовать native I2S-аудиовыход как buzzer, если устройство это поддерживает. |

### `store_forward`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Включает модуль Store and Forward. |
| `heartbeat` | boolean |  | Включает heartbeat Store and Forward. |
| `records` | number |  | Количество записей, сохраняемых модулем. |
| `history_return_max` | number |  | Максимальное количество сообщений, возвращаемых из истории. |
| `history_return_window` | number |  | Окно истории для возврата сообщений. |
| `is_server` | boolean |  | Разрешает ноде быть сервером Store and Forward. |

### `range_test`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Включает модуль Range Test. |
| `sender` | number |  | Отправлять сообщения range test с этой ноды. |
| `save` | boolean |  | Сохранять `RangeTest.csv` на ESP32. |
| `clear_on_reboot` | boolean |  | Очищать `RangeTest.csv` при перезагрузке. |

### `telemetry`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `device_update_interval` | number |  | Интервал отправки метрик устройства в mesh. |
| `environment_update_interval` | number |  | Интервал отправки показаний окружения в mesh. |
| `environment_measurement_enabled` | boolean |  | Включает сбор телеметрии окружения. |
| `environment_screen_enabled` | boolean |  | Показывать телеметрию окружения на экране устройства. |
| `environment_display_fahrenheit` | boolean |  | Показывать температуру в Fahrenheit. |
| `air_quality_enabled` | boolean |  | Включает метрики качества воздуха. |
| `air_quality_interval` | number |  | Интервал отправки метрик качества воздуха. |
| `power_measurement_enabled` | boolean |  | Включает метрики питания. |
| `power_update_interval` | number |  | Интервал отправки метрик питания. |
| `power_screen_enabled` | boolean |  | Показывать метрики питания на экране устройства. |
| `health_measurement_enabled` | boolean |  | Включает health-телеметрию. |
| `health_update_interval` | number |  | Интервал отправки health-метрик. |
| `health_screen_enabled` | boolean |  | Показывать health-телеметрию на экране устройства. |
| `device_telemetry_enabled` | boolean |  | Включает отправку телеметрии устройства в mesh. |
| `air_quality_screen_enabled` | boolean |  | Показывать телеметрию качества воздуха на экране устройства. |

### `canned_message`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `rotary1_enabled` | boolean |  | Включает поворотный энкодер #1. |
| `inputbroker_pin_a` | number |  | GPIO A поворотного энкодера. |
| `inputbroker_pin_b` | number |  | GPIO B поворотного энкодера. |
| `inputbroker_pin_press` | number |  | GPIO кнопки поворотного энкодера. |
| `inputbroker_event_cw` | enum string | `NONE`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `SELECT`, `BACK`, `CANCEL` | Событие input broker при вращении по часовой стрелке. |
| `inputbroker_event_ccw` | enum string | `NONE`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `SELECT`, `BACK`, `CANCEL` | Событие input broker при вращении против часовой стрелки. |
| `inputbroker_event_press` | enum string | `NONE`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `SELECT`, `BACK`, `CANCEL` | Событие input broker при нажатии. |
| `updown1_enabled` | boolean |  | Включает устройство ввода Up/Down/Select. |
| `enabled` | boolean |  | Устарело. Включение/выключение модуля CannedMessage. |
| `allow_input_source` | string |  | Устарело. Разрешенный источник input events для canned messages. |
| `send_bell` | boolean |  | Добавлять bell character к canned messages. |

### `audio`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `codec2_enabled` | boolean |  | Включает audio/codec2. |
| `ptt_pin` | number |  | GPIO PTT. |
| `bitrate` | enum string | `CODEC2_DEFAULT`, `CODEC2_3200`, `CODEC2_2400`, `CODEC2_1600`, `CODEC2_1400`, `CODEC2_1300`, `CODEC2_1200`, `CODEC2_700`, `CODEC2_700B` | Частота аудиосэмплов для codec2. |
| `i2s_ws` | number |  | I2S Word Select. |
| `i2s_sd` | number |  | I2S Data IN. |
| `i2s_din` | number |  | I2S Data OUT. |
| `i2s_sck` | number |  | I2S Clock. |

### `remote_hardware`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Включает модуль RemoteHardware. |
| `allow_undefined_pin_access` | boolean |  | Разрешает читать/писать GPIO, не перечисленные в `available_pins`. |
| `available_pins[]` | список table |  | GPIO, доступные другим участникам mesh. |
| `available_pins[].gpio_pin` | number |  | Номер GPIO pin. |
| `available_pins[].name` | string |  | Человекочитаемое имя GPIO pin. |
| `available_pins[].type` | enum string | `UNKNOWN`, `DIGITAL_READ`, `DIGITAL_WRITE` | Тип доступа к GPIO. |

### `neighbor_info`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Включает модуль NeighborInfo. |
| `update_interval` | number |  | Интервал отправки Neighbor Info; минимум 14400 секунд. |
| `transmit_over_lora` | boolean |  | Отправлять NeighborInfo также через LoRa. |

### `ambient_lighting`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `led_state` | boolean |  | Включает или выключает LED. |
| `current` | number |  | Ток LED-выхода. |
| `red` | number |  | Уровень красного канала, 0-255. |
| `green` | number |  | Уровень зеленого канала, 0-255. |
| `blue` | number |  | Уровень синего канала, 0-255. |

### `detection_sensor`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Включает модуль Detection Sensor. |
| `minimum_broadcast_secs` | number |  | Минимальный интервал отправки сообщения при событии срабатывания. |
| `state_broadcast_secs` | number |  | Интервал heartbeat-сообщения с текущим состоянием; `0` оставляет только события срабатывания. |
| `send_bell` | boolean |  | Добавлять ASCII bell к alert-сообщению. |
| `name` | string |  | Имя датчика для текста сообщения. |
| `monitor_pin` | number |  | GPIO pin для мониторинга. |
| `detection_trigger_type` | enum string | `LOGIC_LOW`, `LOGIC_HIGH`, `FALLING_EDGE`, `RISING_EDGE`, `EITHER_EDGE_ACTIVE_LOW`, `EITHER_EDGE_ACTIVE_HIGH` | Тип события срабатывания. |
| `use_pullup` | boolean |  | Использовать `INPUT_PULLUP`, если плата поддерживает pull-up на pin. |

### `paxcounter`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Включает модуль Paxcounter. |
| `paxcounter_update_interval` | number |  | Интервал отправки метрик paxcounter. |
| `wifi_threshold` | number |  | Порог Wi-Fi RSSI, по умолчанию -80. |
| `ble_threshold` | number |  | Порог BLE RSSI, по умолчанию -80. |

### `statusmessage`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `node_status` | string |  | Текст статуса ноды. |

### `traffic_management`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Главный переключатель модуля Traffic Management. |
| `position_dedup_enabled` | boolean |  | Включает дедупликацию position-пакетов. |
| `position_precision_bits` | number |  | Точность дедупликации позиции в битах, 0-32. |
| `position_min_interval_secs` | number |  | Минимальный интервал между обновлениями позиции от одной ноды. |
| `nodeinfo_direct_response` | boolean |  | Отвечать на запросы NodeInfo из локального кеша. |
| `nodeinfo_direct_response_max_hops` | number |  | Минимальная дистанция в hops до отправителя запроса для прямого ответа. |
| `rate_limit_enabled` | boolean |  | Включает ограничение частоты по каждой ноде. |
| `rate_limit_window_secs` | number |  | Окно ограничения частоты в секундах. |
| `rate_limit_max_packets` | number |  | Максимум пакетов от одной ноды в окне ограничения частоты. |
| `drop_unknown_enabled` | boolean |  | Включает сброс неизвестных или нерасшифровываемых пакетов. |
| `unknown_packet_threshold` | number |  | Количество неизвестных пакетов до сброса. |
| `exhaust_hop_telemetry` | boolean |  | Ставить `hop_limit = 0` для ретранслируемой телеметрии. |
| `exhaust_hop_position` | boolean |  | Ставить `hop_limit = 0` для ретранслируемой позиции. |
| `router_preserve_hops` | boolean |  | Сохранять hop_limit для трафика router-to-router. |

### `tak`

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `team` | enum string | `Unspecifed_Color`, `White`, `Yellow`, `Orange`, `Magenta`, `Red`, `Maroon`, `Purple`, `Dark_Blue`, `Blue`, `Cyan`, `Teal`, `Green`, `Dark_Green`, `Brown` | Цвет команды TAK. |
| `role` | enum string | `Unspecifed`, `TeamMember`, `TeamLead`, `HQ`, `Sniper`, `Medic`, `ForwardObserver`, `RTO`, `K9` | Роль участника TAK. |

## Каналы: `changes.channels`

Каждый элемент `channels` должен содержать `index`; без `{ replace = true }` существующий channel сначала должен быть загружен через `load_config`.

| Поле | Тип Lua | Enum-значения | Описание/заметки |
| --- | --- | --- | --- |
| `index` | number |  | Индекс канала в таблице каналов, обычно 0..MAX_NUM_CHANNELS-1. |
| `settings` | table |  | Настройки канала; `nil` отключает канал. |
| `settings.channel_num` | number |  | Устарело. Используйте `configs.lora.channel_num`. |
| `settings.psk` | string (hex/base64) |  | Предварительный общий ключ канала: 0 байт без шифрования, 16 байт AES128 или 32 байта AES256. |
| `settings.name` | string |  | Короткое имя канала, обычно меньше 12 байт. |
| `settings.id` | number |  | Случайный ID канала для формирования глобально уникального ID канала. |
| `settings.uplink_enabled` | boolean |  | Разрешает gateway-ноды отправлять сообщения канала в публичный интернет. |
| `settings.downlink_enabled` | boolean |  | Разрешает пересылать сообщения из интернета в локальный mesh. |
| `settings.module_settings` | table |  | Настройки модулей для конкретного канала. |
| `settings.module_settings.position_precision` | number |  | Точность позиции в position-пакетах. |
| `settings.module_settings.is_muted` | boolean |  | Отключает уведомления текущего канала на клиенте/устройстве. |
| `role` | enum string | `DISABLED`, `PRIMARY`, `SECONDARY` | Роль канала: выключен, primary или secondary. |

## Пример

```lua
mesh.admin.save_config(target, {
    owner = { long_name = "Remote Node", short_name = "RMT", licensed = true },
    configs = {
        power = { ls_secs = 300, min_wake_secs = 10 },
        device = { role = "CLIENT" }
    },
    module_configs = {
        mqtt = { enabled = true, address = "mqtt.example.com" },
        telemetry = { device_update_interval = 900 }
    },
    channels = {
        { index = 0, role = "PRIMARY", settings = { name = "LongFast" } }
    }
}, { confirm = true })
```

## Шаблон полного `save_config`

Это копируемый шаблон всех секций, которые принимает `mesh.admin.save_config`. Не отправляйте его целиком без проверки: удалите поля и секции, которые не хотите менять. Для обычного патча сначала загрузите текущее состояние через `mesh.admin.load_config(target)` или точечные `request_config` / `request_module_config`, чтобы `save_config` слил ваши изменения с уже загруженными значениями.

```lua
local target = "!abcdef12"

local changes = {
    owner = {
        long_name = "Remote Node",
        short_name = "RMT",
        licensed = true
    },

    position = {
        latitude = 55.7558,
        longitude = 37.6173,
        altitude = 180
    },

    -- remove_position = true,
    ringtone = "beep:d=4,o=5,b=120:c6",
    canned_messages = "OK|Иду|Нужна помощь",

    configs = {
        device = {
            role = "CLIENT",
            serial_enabled = false, -- устарело; лучше security.serial_enabled
            button_gpio = 0,
            buzzer_gpio = 0,
            rebroadcast_mode = "ALL",
            node_info_broadcast_secs = 900,
            double_tap_as_button_press = false,
            is_managed = false, -- устарело; лучше security.is_managed
            disable_triple_click = false,
            tzdef = "UTC0",
            led_heartbeat_disabled = false,
            buzzer_mode = "ALL_ENABLED"
        },

        position = {
            position_broadcast_secs = 900,
            position_broadcast_smart_enabled = true,
            fixed_position = false,
            gps_enabled = true, -- устарело; лучше gps_mode
            gps_update_interval = 30,
            gps_attempt_time = 0, -- устарело
            position_flags = 0,
            rx_gpio = 0,
            tx_gpio = 0,
            broadcast_smart_minimum_distance = 100,
            broadcast_smart_minimum_interval_secs = 30,
            gps_en_gpio = 0,
            gps_mode = "ENABLED"
        },

        power = {
            is_power_saving = false,
            on_battery_shutdown_after_secs = 0,
            adc_multiplier_override = 0,
            wait_bluetooth_secs = 0,
            sds_secs = 0,
            ls_secs = 300,
            min_wake_secs = 10,
            device_battery_ina_address = 0,
            powermon_enables = 0
        },

        network = {
            wifi_enabled = false,
            wifi_ssid = "",
            wifi_psk = "",
            ntp_server = "meshtastic.pool.ntp.org",
            eth_enabled = false,
            address_mode = "DHCP",
            ipv4_config = {
                ip = 0,
                gateway = 0,
                subnet = 0,
                dns = 0
            },
            rsyslog_server = "",
            enabled_protocols = 0,
            ipv6_enabled = false
        },

        display = {
            screen_on_secs = 60,
            gps_format = "UNUSED", -- устарело в этой секции
            auto_screen_carousel_secs = 0,
            compass_north_top = false, -- устарело
            flip_screen = false,
            units = "METRIC",
            oled = "OLED_AUTO",
            displaymode = "DEFAULT",
            heading_bold = false,
            wake_on_tap_or_motion = false,
            compass_orientation = "DEGREES_0",
            use_12h_clock = false,
            use_long_node_name = false,
            enable_message_bubbles = true
        },

        lora = {
            use_preset = true,
            modem_preset = "LONG_FAST",
            bandwidth = 0,
            spread_factor = 0,
            coding_rate = 0,
            frequency_offset = 0,
            region = "UNSET",
            hop_limit = 3,
            tx_enabled = true,
            tx_power = 0,
            channel_num = 0,
            override_duty_cycle = false,
            sx126x_rx_boosted_gain = false,
            override_frequency = 0,
            pa_fan_disabled = false,
            ignore_incoming = {},
            ignore_mqtt = false,
            config_ok_to_mqtt = false,
            fem_lna_mode = "NOT_PRESENT",
            serial_hal_only = false
        },

        bluetooth = {
            enabled = true,
            mode = "RANDOM_PIN",
            fixed_pin = 123456
        },

        security = {
            -- Не меняйте ключи без необходимости: можно потерять доступ к remote admin.
            public_key = "",
            private_key = "",
            admin_key = {},
            is_managed = false,
            serial_enabled = false,
            debug_log_api_enabled = false,
            admin_channel_enabled = false
        },

        device_ui = {
            version = 0,
            screen_brightness = 128,
            screen_timeout = 60,
            screen_lock = false,
            settings_lock = false,
            pin_code = 0,
            theme = "DARK",
            alert_enabled = true,
            banner_enabled = true,
            ring_tone_id = 0,
            language = "RUSSIAN",
            node_filter = {
                unknown_switch = false,
                offline_switch = false,
                public_key_switch = false,
                hops_away = 0,
                position_switch = false,
                node_name = "",
                channel = 0
            },
            node_highlight = {
                chat_switch = true,
                position_switch = true,
                telemetry_switch = true,
                iaq_switch = true,
                node_name = ""
            },
            calibration_data = "",
            map_data = {
                home = {
                    zoom = 10,
                    latitude = 55.7558,
                    longitude = 37.6173
                },
                style = "",
                follow_gps = true
            },
            compass_mode = "DYNAMIC",
            screen_rgb_color = 16777215,
            is_clockface_analog = false,
            gps_format = "DEC"
        }
    },

    module_configs = {
        mqtt = {
            enabled = false,
            address = "",
            username = "",
            password = "",
            encryption_enabled = true,
            json_enabled = false, -- устарело
            tls_enabled = true,
            root = "msh",
            proxy_to_client_enabled = false,
            map_reporting_enabled = false,
            map_report_settings = {
                publish_interval_secs = 900,
                position_precision = 32,
                should_report_location = false
            }
        },

        serial = {
            enabled = false,
            echo = false,
            rxd = 0,
            txd = 0,
            baud = "BAUD_115200",
            timeout = 0,
            mode = "DEFAULT",
            override_console_serial_port = false
        },

        external_notification = {
            enabled = false,
            output_ms = 1000,
            output = 0,
            output_vibra = 0,
            output_buzzer = 0,
            active = true,
            alert_message = true,
            alert_message_vibra = false,
            alert_message_buzzer = false,
            alert_bell = true,
            alert_bell_vibra = false,
            alert_bell_buzzer = false,
            use_pwm = false,
            nag_timeout = 0,
            use_i2s_as_buzzer = false
        },

        store_forward = {
            enabled = false,
            heartbeat = false,
            records = 0,
            history_return_max = 0,
            history_return_window = 0,
            is_server = false
        },

        range_test = {
            enabled = false,
            sender = 0,
            save = false,
            clear_on_reboot = false
        },

        telemetry = {
            device_update_interval = 900,
            environment_update_interval = 900,
            environment_measurement_enabled = false,
            environment_screen_enabled = false,
            environment_display_fahrenheit = false,
            air_quality_enabled = false,
            air_quality_interval = 900,
            power_measurement_enabled = false,
            power_update_interval = 900,
            power_screen_enabled = false,
            health_measurement_enabled = false,
            health_update_interval = 900,
            health_screen_enabled = false,
            device_telemetry_enabled = true,
            air_quality_screen_enabled = false
        },

        canned_message = {
            rotary1_enabled = false,
            inputbroker_pin_a = 0,
            inputbroker_pin_b = 0,
            inputbroker_pin_press = 0,
            inputbroker_event_cw = "NONE",
            inputbroker_event_ccw = "NONE",
            inputbroker_event_press = "NONE",
            updown1_enabled = false,
            enabled = false, -- устарело
            allow_input_source = "", -- устарело
            send_bell = false
        },

        audio = {
            codec2_enabled = false,
            ptt_pin = 0,
            bitrate = "CODEC2_DEFAULT",
            i2s_ws = 0,
            i2s_sd = 0,
            i2s_din = 0,
            i2s_sck = 0
        },

        remote_hardware = {
            enabled = false,
            allow_undefined_pin_access = false,
            available_pins = {
                { gpio_pin = 0, name = "pin0", type = "DIGITAL_READ" }
            }
        },

        neighbor_info = {
            enabled = false,
            update_interval = 14400,
            transmit_over_lora = false
        },

        ambient_lighting = {
            led_state = false,
            current = 10,
            red = 0,
            green = 0,
            blue = 0
        },

        detection_sensor = {
            enabled = false,
            minimum_broadcast_secs = 45,
            state_broadcast_secs = 0,
            send_bell = false,
            name = "Sensor",
            monitor_pin = 0,
            detection_trigger_type = "LOGIC_HIGH",
            use_pullup = false
        },

        paxcounter = {
            enabled = false,
            paxcounter_update_interval = 900,
            wifi_threshold = -80,
            ble_threshold = -80
        },

        statusmessage = {
            node_status = ""
        },

        traffic_management = {
            enabled = false,
            position_dedup_enabled = false,
            position_precision_bits = 32,
            position_min_interval_secs = 0,
            nodeinfo_direct_response = false,
            nodeinfo_direct_response_max_hops = 0,
            rate_limit_enabled = false,
            rate_limit_window_secs = 60,
            rate_limit_max_packets = 0,
            drop_unknown_enabled = false,
            unknown_packet_threshold = 0,
            exhaust_hop_telemetry = false,
            exhaust_hop_position = false,
            router_preserve_hops = false
        },

        tak = {
            team = "Unspecifed_Color",
            role = "Unspecifed"
        }
    },

    channels = {
        {
            index = 0,
            role = "PRIMARY",
            settings = {
                channel_num = 0, -- устарело; лучше configs.lora.channel_num
                psk = "AQ==",
                name = "LongFast",
                id = 0,
                uplink_enabled = false,
                downlink_enabled = false,
                module_settings = {
                    position_precision = 32,
                    is_muted = false
                }
            }
        },
        {
            index = 1,
            role = "SECONDARY",
            settings = {
                psk = "AQ==",
                name = "Secondary",
                id = 0,
                uplink_enabled = false,
                downlink_enabled = false,
                module_settings = {
                    position_precision = 32,
                    is_muted = false
                }
            }
        }
    }
}

mesh.admin.load_config(target)

function on_admin(event)
    if event.action == "load_config" and event.ok then
        mesh.admin.save_config(target, changes, { confirm = true })
    end
end
```
