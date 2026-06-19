# `mesh.admin`-Konfigurationsreferenz

**Sprache:** [Русский](lua-admin-config-reference.ru.md) | [English](lua-admin-config-reference.md) | Deutsch

Diese Referenz beschreibt die Parameter, die nach `mesh.admin.load_config` / `request_config` / `request_module_config` in `event.snapshot` zurückgegeben und von `mesh.admin.save_config` akzeptiert werden.

Lua-Feldnamen entsprechen Protobuf-`snake_case`. Enum-Werte sind Strings. `bytes` akzeptiert Hex, `hex:...`, `base64:...` oder Base64. Wiederholte Felder sind Lua-Listen. Standardmäßig führt `save_config` jeden Patch mit dem bereits geladenen Abschnitt zusammen; rufen Sie zuerst `load_config`, `request_config` oder `request_module_config` auf, oder übergeben Sie `{ replace = true, confirm = true }`, wenn ein Abschnitt bewusst aus Default-Werten ersetzt wird.

## `event.snapshot`

| Feld | Lua-Typ | Beschreibung |
| --- | --- | --- |
| `target_node_num` | number | Numerische Zielknoten-ID |
| `target_node_id` | string | Zielknoten-ID in der Form `!abcdef12` |
| `node` | table | Aktueller Datensatz des Zielknotens |
| `owner` | table oder `nil` | Vom Remote-Knoten geladene Owner-/User-Payload |
| `device_metadata` | table oder `nil` | Vom Remote-Knoten geladene Gerätemetadaten |
| `ringtone` | string | Aktueller RTTTL-Klingeltontext |
| `canned_messages` | string | Aktuelle Payload des Canned-Messages-Moduls |
| `canned_messages_loaded` | boolean | Ob Canned Messages geladen wurden |
| `connection_status` | table oder `nil` | Verbindungsstatus des Remote-Geräts |
| `configs` | table | Core-Config-Abschnitte; Felder entsprechen unten `changes.configs` |
| `module_configs` | table | Module-Config-Abschnitte; Felder entsprechen unten `changes.module_configs` |
| `channels` | Liste von table | Geladene Kanäle; Felder entsprechen unten `changes.channels` |
| `query_statuses` | Liste von table | Ladestatus pro Block: `key`, `state`, `detail` |
| `query_summary` | table | Ladezusammenfassung: `total`, `received`, `failed` |

## Top-Level-`save_config`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `owner.long_name` | string |  | Langer Name des Knotens. |
| `owner.short_name` | string |  | Kurzer Name des Knotens. |
| `owner.licensed` | boolean |  | Kennzeichen für den Licensed-Operator-Modus. |
| `position.latitude` | number |  | Breitengrad in Grad; wird als manuelle feste Position gesendet. |
| `position.longitude` | number |  | Längengrad in Grad; wird als manuelle feste Position gesendet. |
| `position.altitude` | number |  | Höhe in Metern. |
| `remove_position` | boolean |  | `true` löscht die manuelle feste Position. |
| `ringtone` | string |  | RTTTL-Klingeltontext. |
| `canned_messages` | string |  | Textpayload des Canned-Messages-Moduls. |
| `configs` | table |  | Core-Config-Abschnitte unten. |
| `module_configs` | table |  | Module-Config-Abschnitte unten. |
| `channels` | Liste von table |  | Liste von Kanal-Patches. |

## Core-Config: `changes.configs`

Verfügbare Abschnitte:

| Abschnitt | Lua-Patch | Top-Level-Felder |
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

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `role` | enum string | `CLIENT`, `CLIENT_MUTE`, `ROUTER`, `ROUTER_CLIENT` veraltet, `REPEATER` veraltet, `TRACKER`, `SENSOR`, `TAK`, `CLIENT_HIDDEN`, `LOST_AND_FOUND`, `TAK_TRACKER`, `ROUTER_LATE`, `CLIENT_BASE` | Legt die Rolle des Knotens im Mesh fest. |
| `serial_enabled` | boolean |  | Veraltet; serielle Konsole wurde in die SecurityConfig verschoben. |
| `button_gpio` | number |  | GPIO-Pin für die Benutzertaste, falls die Platine keine fest verdrahtete Taste hat. |
| `buzzer_gpio` | number |  | GPIO-Pin für einen Buzzer, falls kein PWM-Buzzer fest definiert ist. |
| `rebroadcast_mode` | enum string | `ALL`, `ALL_SKIP_DECODING`, `LOCAL_ONLY`, `KNOWN_ONLY`, `NONE`, `CORE_PORTNUMS_ONLY` | Legt fest, welche Pakete der Knoten erneut aussendet. |
| `node_info_broadcast_secs` | number |  | Intervall in Sekunden für das Senden der eigenen NodeInfo; Standard 900 Sekunden. |
| `double_tap_as_button_press` | boolean |  | Behandelt einen Double-Tap-Interrupt unterstützter Beschleunigungssensoren als Tastendruck. |
| `is_managed` | boolean |  | Veraltet; Managed-Status wurde in die SecurityConfig verschoben. |
| `disable_triple_click` | boolean |  | Deaktiviert den Dreifachklick der Benutzertaste zum Aktivieren oder Deaktivieren von GPS. |
| `tzdef` | string |  | POSIX-Zeitzonendefinition, zum Beispiel aus der posix_tz_db. |
| `led_heartbeat_disabled` | boolean |  | Deaktiviert das Standard-Blinken der Geräte-LED. |
| `buzzer_mode` | enum string | `ALL_ENABLED`, `DISABLED`, `NOTIFICATIONS_ONLY`, `SYSTEM_ONLY`, `DIRECT_MSG_ONLY` | Steuert, wann der Buzzer akustisches Feedback ausgibt. |

### `position`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `position_broadcast_secs` | number |  | Intervall in Sekunden für Positionssendungen, wenn sich die Position relevant geändert hat. |
| `position_broadcast_smart_enabled` | boolean |  | Aktiviert adaptives Positions-Broadcasting. |
| `fixed_position` | boolean |  | Markiert den Knoten als stationär und verwendet die zuletzt gesetzten Koordinaten. |
| `gps_enabled` | boolean |  | Veraltet; steuert, ob GPS für diesen Knoten aktiviert ist. |
| `gps_update_interval` | number |  | Intervall in Sekunden für GPS-Positionsversuche; `0` verwendet den Standard. |
| `gps_attempt_time` | number |  | Veraltet; implizit durch Smart-/Regel-Broadcast-Intervalle ersetzt. |
| `position_flags` | number |  | Bitfeld für Optionen von POSITION-Nachrichten. |
| `rx_gpio` | number |  | GPIO-Pin für GPS_RX_PIN. |
| `tx_gpio` | number |  | GPIO-Pin für GPS_TX_PIN. |
| `broadcast_smart_minimum_distance` | number |  | Mindestdistanz in Metern seit der letzten Sendung für Smart-Positionsbroadcast. |
| `broadcast_smart_minimum_interval_secs` | number |  | Mindestintervall in Sekunden seit der letzten Sendung für Smart-Positionsbroadcast. |
| `gps_en_gpio` | number |  | GPIO-Pin für PIN_GPS_EN. |
| `gps_mode` | enum string | `DISABLED`, `ENABLED`, `NOT_PRESENT` | Legt fest, ob GPS aktiviert, deaktiviert oder nicht vorhanden ist. |

### `power`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `is_power_saving` | boolean |  | Aktiviert möglichst aggressives Energiesparen; besonders für Tracker- und Sensorrollen gedacht. |
| `on_battery_shutdown_after_secs` | number |  | Schaltet das Gerät nach Entfernen externer Versorgung nach dieser Anzahl Sekunden vollständig aus. |
| `adc_multiplier_override` | number |  | Überschreibt den ADC-Multiplikator für die Batteriespannungsberechnung. |
| `wait_bluetooth_secs` | number |  | Wartezeit in Sekunden vor dem Abschalten von BLE in No-Bluetooth-Zuständen. |
| `sds_secs` | number |  | Dauer des Super-Deep-Sleep nach überschrittenem Light-Sleep-Timeout. |
| `ls_secs` | number |  | Light-Sleep-Dauer; CPU schläft, LoRa bleibt aktiv. |
| `min_wake_secs` | number |  | Mindest-Wachzeit nach Empfang von LoRa-Paketen im No-BLE-Modus. |
| `device_battery_ina_address` | number |  | I2C-Adresse des INA_2XX zur Messung der Batteriespannung. |
| `powermon_enables` | number |  | Bitfeld für aktivierte Powermon-Logquellen. |

### `network`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `wifi_enabled` | boolean |  | Aktiviert Wi-Fi und deaktiviert dabei Bluetooth. |
| `wifi_ssid` | string |  | SSID des Wi-Fi-Netzwerks, dem der Knoten beitreten soll. |
| `wifi_psk` | string |  | Wi-Fi-Passwort für die angegebene SSID. |
| `ntp_server` | string |  | NTP-Server bei aktiver Wi-Fi-Verbindung; Standard `meshtastic.pool.ntp.org`. |
| `eth_enabled` | boolean |  | Aktiviert Ethernet. |
| `address_mode` | enum string | `DHCP`, `STATIC` | Wählt DHCP oder statische IP-Adressierung. |
| `ipv4_config` | table |  | Container für statische IPv4-Konfiguration. |
| `ipv4_config.ip` | number |  | Statische IP-Adresse. |
| `ipv4_config.gateway` | number |  | Statische Gateway-Adresse. |
| `ipv4_config.subnet` | number |  | Statische Subnetzmaske. |
| `ipv4_config.dns` | number |  | Statische DNS-Serveradresse. |
| `rsyslog_server` | string |  | rsyslog-Server und Port. |
| `enabled_protocols` | number |  | Flags zum Aktivieren oder Deaktivieren von Netzwerkprotokollen. |
| `ipv6_enabled` | boolean |  | Aktiviert oder deaktiviert IPv6-Unterstützung. |

### `display`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `screen_on_secs` | number |  | Dauer in Sekunden, die der Bildschirm nach Tastendruck oder Nachricht aktiv bleibt. |
| `gps_format` | enum string | `UNUSED` | Veraltet in 2.7.4; früheres Format der GPS-Koordinaten auf dem OLED. |
| `auto_screen_carousel_secs` | number |  | Intervall für automatischen Wechsel zur nächsten Bildschirmseite. |
| `compass_north_top` | boolean |  | Veraltet; zeigt den Kompass immer mit Norden oben an. |
| `flip_screen` | boolean |  | Dreht den Bildschirm vertikal für kopfüber montierte Displays. |
| `units` | enum string | `METRIC`, `IMPERIAL` | Bevorzugte Anzeigeeinheiten. |
| `oled` | enum string | `OLED_AUTO`, `OLED_SSD1306`, `OLED_SH1106`, `OLED_SH1107`, `OLED_SH1107_128_128`, `OLED_SH1107_ROTATED` | Überschreibt die automatische OLED-Erkennung. |
| `displaymode` | enum string | `DEFAULT`, `TWOCOLOR`, `INVERTED`, `COLOR` | Anzeigemodus des Displays. |
| `heading_bold` | boolean |  | Zeigt die erste Zeile pseudo-fett an. |
| `wake_on_tap_or_motion` | boolean |  | Weckt den Bildschirm bei Bewegung oder Tap durch den Beschleunigungssensor. |
| `compass_orientation` | enum string | `DEGREES_0`, `DEGREES_90`, `DEGREES_180`, `DEGREES_270`, `DEGREES_0_INVERTED`, `DEGREES_90_INVERTED`, `DEGREES_180_INVERTED`, `DEGREES_270_INVERTED` | Rotation oder Invertierung der Kompassausgabe für korrekte Anzeige. |
| `use_12h_clock` | boolean |  | Schaltet zwischen 24-Stunden- und 12-Stunden-Zeitanzeige um. |
| `use_long_node_name` | boolean |  | Verwendet lange Knotennamen auf Gerätebildschirmen. |
| `enable_message_bubbles` | boolean |  | Zeigt Nachrichtenblasen auf dem Gerätebildschirm. |

### `lora`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `use_preset` | boolean |  | Verwendet das gewählte Modem-Preset statt manueller Funkparameter. |
| `modem_preset` | enum string | `LONG_FAST`, `LONG_SLOW` veraltet, `VERY_LONG_SLOW` veraltet, `MEDIUM_SLOW`, `MEDIUM_FAST`, `SHORT_SLOW`, `SHORT_FAST`, `LONG_MODERATE`, `SHORT_TURBO`, `LONG_TURBO`, `LITE_FAST`, `LITE_SLOW`, `NARROW_FAST`, `NARROW_SLOW` | Wählt ein vordefiniertes Modemprofil für Bandbreite, Spreading Factor und Coding Rate. |
| `bandwidth` | number |  | LoRa-Bandbreite in MHz. |
| `spread_factor` | number |  | Spreading Factor von 7 bis 12. |
| `coding_rate` | number |  | Nenner der Coding Rate, zum Beispiel 5 für 4/5. |
| `frequency_offset` | number |  | Frequenzoffset für fortgeschrittene Kalibrierung. |
| `region` | enum string | `UNSET`, `US`, `EU_433`, `EU_868`, `CN`, `JP`, `ANZ`, `KR`, `TW`, `RU`, `IN`, `NZ_865`, `TH`, `LORA_24`, `UA_433`, `UA_868`, `MY_433`, `MY_919`, `SG_923`, `PH_433`, `PH_868`, `PH_915`, `ANZ_433`, `KZ_433`, `KZ_863`, `NP_865`, `BR_902`, `ITU1_2M`, `ITU23_2M`, `EU_866`, `EU_874`, `EU_917`, `EU_N_868` | Regulatorischer Regionscode für den Funkbetrieb. |
| `hop_limit` | number |  | Maximale Hop-Anzahl; Werte über 7 werden nicht akzeptiert. |
| `tx_enabled` | boolean |  | Aktiviert oder deaktiviert Senden über das LoRa-Radio. |
| `tx_power` | number |  | Sendeleistung in dBm; `0` verwendet den legalen Standardwert. |
| `channel_num` | number |  | Hardware-Kanalnummer für die tatsächliche Sendefrequenz. |
| `override_duty_cycle` | boolean |  | Ignoriert Duty-Cycle-Grenzen; nur verwenden, wenn lokale Regeln dies erlauben. |
| `sx126x_rx_boosted_gain` | boolean |  | Aktiviert RX-Boosted-Gain bei SX126X-basierten Radios. |
| `override_frequency` | number |  | Überschreibt die berechnete Frequenz; nur für fortgeschrittene oder lizenzierte Nutzer. |
| `pa_fan_disabled` | boolean |  | Deaktiviert den PA-Lüfter. |
| `ignore_incoming` | Liste von number |  | Liste von Knotennummern, deren eingehende Pakete verworfen werden. |
| `ignore_mqtt` | boolean |  | Ignoriert LoRa-Pakete, die über MQTT auf dem Pfad liefen. |
| `config_ok_to_mqtt` | boolean |  | Setzt das `ok_to_mqtt`-Bit für ausgehende Pakete. |
| `fem_lna_mode` | enum string | `DISABLED`, `ENABLED`, `NOT_PRESENT` | Legt fest, ob LORA FEM/LNA aktiviert, deaktiviert oder nicht vorhanden ist. |
| `serial_hal_only` | boolean |  | Initialisiert das Radio nicht über RadioLib, sondern wartet auf eine serialHal-Verbindung. |

### `bluetooth`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Aktiviert Bluetooth am Gerät. |
| `mode` | enum string | `RANDOM_PIN`, `FIXED_PIN`, `NO_PIN` | Legt die Pairing-Strategie fest. |
| `fixed_pin` | number |  | Feste PIN für den Pairing-Modus FixedPin. |

### `security`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `public_key` | string (hex/base64) |  | Öffentlicher Geräteschlüssel zum Aufbau geteilter Geheimnisse im Mesh. |
| `private_key` | string (hex/base64) |  | Privater Geräteschlüssel für geteilte Schlüssel mit entfernten Geräten. |
| `admin_key` | Liste von string (hex/base64) |  | Öffentliche Schlüssel, die Admin-Nachrichten an diesen Knoten senden dürfen. |
| `is_managed` | boolean |  | Markiert das Gerät als durch einen Mesh-Administrator verwaltet. |
| `serial_enabled` | boolean |  | Aktiviert die serielle Konsole über die Stream API. |
| `debug_log_api_enabled` | boolean |  | Erlaubt Live-Debug-Logging über Serial oder Bluetooth. |
| `admin_channel_enabled` | boolean |  | Erlaubt eingehende Gerätesteuerung über den unsicheren Legacy-Admin-Kanal. |

### `device_ui`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `version` | number |  | Konfiguriert `version` im Abschnitt Geräte-UI. |
| `screen_brightness` | number |  | Konfiguriert `screen_brightness` im Abschnitt Geräte-UI. |
| `screen_timeout` | number |  | Konfiguriert `screen_timeout` im Abschnitt Geräte-UI. |
| `screen_lock` | boolean |  | Konfiguriert `screen_lock` im Abschnitt Geräte-UI. |
| `settings_lock` | boolean |  | Konfiguriert `settings_lock` im Abschnitt Geräte-UI. |
| `pin_code` | number |  | Legt den GPIO-/Pin-Wert `pin_code` im Abschnitt Geräte-UI fest. |
| `theme` | enum string | `DARK`, `LIGHT`, `RED` | Konfiguriert `theme` im Abschnitt Geräte-UI. |
| `alert_enabled` | boolean |  | Aktiviert oder deaktiviert `alert_enabled` im Abschnitt Geräte-UI. |
| `banner_enabled` | boolean |  | Aktiviert oder deaktiviert `banner_enabled` im Abschnitt Geräte-UI. |
| `ring_tone_id` | number |  | Konfiguriert `ring_tone_id` im Abschnitt Geräte-UI. |
| `language` | enum string | `ENGLISH`, `FRENCH`, `GERMAN`, `ITALIAN`, `PORTUGUESE`, `SPANISH`, `SWEDISH`, `FINNISH`, `POLISH`, `TURKISH`, `SERBIAN`, `RUSSIAN`, `DUTCH`, `GREEK`, `NORWEGIAN`, `SLOVENIAN`, `UKRAINIAN`, `BULGARIAN`, `CZECH`, `DANISH`, `SIMPLIFIED_CHINESE`, `TRADITIONAL_CHINESE` | Konfiguriert `language` im Abschnitt Geräte-UI. |
| `node_filter` | table |  | Konfiguriert `node_filter` im Abschnitt Geräte-UI. |
| `node_filter.unknown_switch` | boolean |  | Konfiguriert `node_filter.unknown_switch` im Abschnitt Geräte-UI. |
| `node_filter.offline_switch` | boolean |  | Konfiguriert `node_filter.offline_switch` im Abschnitt Geräte-UI. |
| `node_filter.public_key_switch` | boolean |  | Konfiguriert den Schlüssel- oder Passwortwert `node_filter.public_key_switch` im Abschnitt Geräte-UI. |
| `node_filter.hops_away` | number |  | Konfiguriert `node_filter.hops_away` im Abschnitt Geräte-UI. |
| `node_filter.position_switch` | boolean |  | Konfiguriert `node_filter.position_switch` im Abschnitt Geräte-UI. |
| `node_filter.node_name` | string |  | Konfiguriert den Namen `node_filter.node_name` im Abschnitt Geräte-UI. |
| `node_filter.channel` | number |  | Konfiguriert `node_filter.channel` im Abschnitt Geräte-UI. |
| `node_highlight` | table |  | Konfiguriert `node_highlight` im Abschnitt Geräte-UI. |
| `node_highlight.chat_switch` | boolean |  | Konfiguriert `node_highlight.chat_switch` im Abschnitt Geräte-UI. |
| `node_highlight.position_switch` | boolean |  | Konfiguriert `node_highlight.position_switch` im Abschnitt Geräte-UI. |
| `node_highlight.telemetry_switch` | boolean |  | Konfiguriert `node_highlight.telemetry_switch` im Abschnitt Geräte-UI. |
| `node_highlight.iaq_switch` | boolean |  | Konfiguriert `node_highlight.iaq_switch` im Abschnitt Geräte-UI. |
| `node_highlight.node_name` | string |  | Konfiguriert den Namen `node_highlight.node_name` im Abschnitt Geräte-UI. |
| `calibration_data` | string (hex/base64) |  | Konfiguriert `calibration_data` im Abschnitt Geräte-UI. |
| `map_data` | table |  | Konfiguriert `map_data` im Abschnitt Geräte-UI. |
| `map_data.home` | table |  | Konfiguriert `map_data.home` im Abschnitt Geräte-UI. |
| `map_data.home.zoom` | number |  | Konfiguriert `map_data.home.zoom` im Abschnitt Geräte-UI. |
| `map_data.home.latitude` | number |  | Konfiguriert `map_data.home.latitude` im Abschnitt Geräte-UI. |
| `map_data.home.longitude` | number |  | Konfiguriert `map_data.home.longitude` im Abschnitt Geräte-UI. |
| `map_data.style` | string |  | Konfiguriert `map_data.style` im Abschnitt Geräte-UI. |
| `map_data.follow_gps` | boolean |  | Konfiguriert `map_data.follow_gps` im Abschnitt Geräte-UI. |
| `compass_mode` | enum string | `DYNAMIC`, `FIXED_RING`, `FREEZE_HEADING` | Wählt den Modus oder Typ `compass_mode` im Abschnitt Geräte-UI. |
| `screen_rgb_color` | number |  | Legt den Farbwert `screen_rgb_color` im Abschnitt Geräte-UI fest. |
| `is_clockface_analog` | boolean |  | Konfiguriert `is_clockface_analog` im Abschnitt Geräte-UI. |
| `gps_format` | enum string | `DEC`, `DMS`, `UTM`, `MGRS`, `OLC`, `OSGR`, `MLS` | Konfiguriert `gps_format` im Abschnitt Geräte-UI. |

## Module-Config: `changes.module_configs`

Verfügbare Abschnitte:

| Abschnitt | Lua-Patch | Top-Level-Felder |
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

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Aktiviert MQTT-Gateway-Funktionen für geeignete Kanäle. |
| `address` | string |  | MQTT-Serveradresse; leer verwendet den Standardserver. |
| `username` | string |  | MQTT-Benutzername, besonders für eigene MQTT-Server. |
| `password` | string |  | MQTT-Passwort, besonders für eigene MQTT-Server. |
| `encryption_enabled` | boolean |  | Sendet verschlüsselte oder entschlüsselte Pakete an MQTT. |
| `json_enabled` | boolean |  | Veraltet; JSON-Pakete über MQTT wurden entfernt. |
| `tls_enabled` | boolean |  | Aktiviert TLS für die MQTT-Verbindung. |
| `root` | string |  | Root-Topic für MQTT-Nachrichten; Standard `msh`. |
| `proxy_to_client_enabled` | boolean |  | Nutzt den verbundenen Client als Proxy zu MQTT. |
| `map_reporting_enabled` | boolean |  | Meldet unverschlüsselte Knoteninformationen periodisch an eine Karte über MQTT. |
| `map_report_settings` | table |  | Einstellungen für Kartenmeldungen über MQTT. |
| `map_report_settings.publish_interval_secs` | number |  | Intervall in Sekunden für Kartenmeldungen. |
| `map_report_settings.position_precision` | number |  | Präzisionsbits für gesendete Positionen. |
| `map_report_settings.should_report_location` | boolean |  | Gibt an, ob Standortmeldungen an die Karte aktiviert sind. |

### `serial`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Aktiviert oder deaktiviert `enabled` im Abschnitt Serial-Modul. |
| `echo` | boolean |  | Konfiguriert `echo` im Abschnitt Serial-Modul. |
| `rxd` | number |  | Konfiguriert `rxd` im Abschnitt Serial-Modul. |
| `txd` | number |  | Konfiguriert `txd` im Abschnitt Serial-Modul. |
| `baud` | enum string | `BAUD_DEFAULT`, `BAUD_110`, `BAUD_300`, `BAUD_600`, `BAUD_1200`, `BAUD_2400`, `BAUD_4800`, `BAUD_9600`, `BAUD_19200`, `BAUD_38400`, `BAUD_57600`, `BAUD_115200`, `BAUD_230400`, `BAUD_460800`, `BAUD_576000`, `BAUD_921600` | Konfiguriert `baud` im Abschnitt Serial-Modul. |
| `timeout` | number |  | Konfiguriert `timeout` im Abschnitt Serial-Modul. |
| `mode` | enum string | `DEFAULT`, `SIMPLE`, `PROTO`, `TEXTMSG`, `NMEA`, `CALTOPO`, `WS85`, `VE_DIRECT`, `MS_CONFIG`, `LOG`, `LOGTEXT` | Wählt den Modus oder Typ `mode` im Abschnitt Serial-Modul. |
| `override_console_serial_port` | boolean |  | Konfiguriert `override_console_serial_port` im Abschnitt Serial-Modul. |

### `external_notification`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Aktiviert oder deaktiviert `enabled` im Abschnitt External-Notification-Modul. |
| `output_ms` | number |  | Konfiguriert `output_ms` im Abschnitt External-Notification-Modul. |
| `output` | number |  | Konfiguriert `output` im Abschnitt External-Notification-Modul. |
| `output_vibra` | number |  | Konfiguriert `output_vibra` im Abschnitt External-Notification-Modul. |
| `output_buzzer` | number |  | Konfiguriert `output_buzzer` im Abschnitt External-Notification-Modul. |
| `active` | boolean |  | Konfiguriert `active` im Abschnitt External-Notification-Modul. |
| `alert_message` | boolean |  | Konfiguriert `alert_message` im Abschnitt External-Notification-Modul. |
| `alert_message_vibra` | boolean |  | Konfiguriert `alert_message_vibra` im Abschnitt External-Notification-Modul. |
| `alert_message_buzzer` | boolean |  | Konfiguriert `alert_message_buzzer` im Abschnitt External-Notification-Modul. |
| `alert_bell` | boolean |  | Konfiguriert `alert_bell` im Abschnitt External-Notification-Modul. |
| `alert_bell_vibra` | boolean |  | Konfiguriert `alert_bell_vibra` im Abschnitt External-Notification-Modul. |
| `alert_bell_buzzer` | boolean |  | Konfiguriert `alert_bell_buzzer` im Abschnitt External-Notification-Modul. |
| `use_pwm` | boolean |  | Konfiguriert `use_pwm` im Abschnitt External-Notification-Modul. |
| `nag_timeout` | number |  | Konfiguriert `nag_timeout` im Abschnitt External-Notification-Modul. |
| `use_i2s_as_buzzer` | boolean |  | Konfiguriert `use_i2s_as_buzzer` im Abschnitt External-Notification-Modul. |

### `store_forward`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Aktiviert oder deaktiviert `enabled` im Abschnitt Store-and-Forward-Modul. |
| `heartbeat` | boolean |  | Konfiguriert `heartbeat` im Abschnitt Store-and-Forward-Modul. |
| `records` | number |  | Konfiguriert `records` im Abschnitt Store-and-Forward-Modul. |
| `history_return_max` | number |  | Konfiguriert `history_return_max` im Abschnitt Store-and-Forward-Modul. |
| `history_return_window` | number |  | Konfiguriert `history_return_window` im Abschnitt Store-and-Forward-Modul. |
| `is_server` | boolean |  | Konfiguriert `is_server` im Abschnitt Store-and-Forward-Modul. |

### `range_test`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Aktiviert oder deaktiviert `enabled` im Abschnitt Range-Test-Modul. |
| `sender` | number |  | Konfiguriert `sender` im Abschnitt Range-Test-Modul. |
| `save` | boolean |  | Konfiguriert `save` im Abschnitt Range-Test-Modul. |
| `clear_on_reboot` | boolean |  | Konfiguriert `clear_on_reboot` im Abschnitt Range-Test-Modul. |

### `telemetry`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `device_update_interval` | number |  | Legt das Intervall oder die Dauer für `device_update_interval` im Abschnitt Telemetrie-Modul fest. |
| `environment_update_interval` | number |  | Legt das Intervall oder die Dauer für `environment_update_interval` im Abschnitt Telemetrie-Modul fest. |
| `environment_measurement_enabled` | boolean |  | Aktiviert oder deaktiviert `environment_measurement_enabled` im Abschnitt Telemetrie-Modul. |
| `environment_screen_enabled` | boolean |  | Aktiviert oder deaktiviert `environment_screen_enabled` im Abschnitt Telemetrie-Modul. |
| `environment_display_fahrenheit` | boolean |  | Konfiguriert `environment_display_fahrenheit` im Abschnitt Telemetrie-Modul. |
| `air_quality_enabled` | boolean |  | Aktiviert oder deaktiviert `air_quality_enabled` im Abschnitt Telemetrie-Modul. |
| `air_quality_interval` | number |  | Legt das Intervall oder die Dauer für `air_quality_interval` im Abschnitt Telemetrie-Modul fest. |
| `power_measurement_enabled` | boolean |  | Aktiviert oder deaktiviert `power_measurement_enabled` im Abschnitt Telemetrie-Modul. |
| `power_update_interval` | number |  | Legt das Intervall oder die Dauer für `power_update_interval` im Abschnitt Telemetrie-Modul fest. |
| `power_screen_enabled` | boolean |  | Aktiviert oder deaktiviert `power_screen_enabled` im Abschnitt Telemetrie-Modul. |
| `health_measurement_enabled` | boolean |  | Aktiviert oder deaktiviert `health_measurement_enabled` im Abschnitt Telemetrie-Modul. |
| `health_update_interval` | number |  | Legt das Intervall oder die Dauer für `health_update_interval` im Abschnitt Telemetrie-Modul fest. |
| `health_screen_enabled` | boolean |  | Aktiviert oder deaktiviert `health_screen_enabled` im Abschnitt Telemetrie-Modul. |
| `device_telemetry_enabled` | boolean |  | Aktiviert oder deaktiviert `device_telemetry_enabled` im Abschnitt Telemetrie-Modul. |
| `air_quality_screen_enabled` | boolean |  | Aktiviert oder deaktiviert `air_quality_screen_enabled` im Abschnitt Telemetrie-Modul. |

### `canned_message`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `rotary1_enabled` | boolean |  | Aktiviert oder deaktiviert `rotary1_enabled` im Abschnitt Canned-Message-Modul. |
| `inputbroker_pin_a` | number |  | Legt den GPIO-/Pin-Wert `inputbroker_pin_a` im Abschnitt Canned-Message-Modul fest. |
| `inputbroker_pin_b` | number |  | Legt den GPIO-/Pin-Wert `inputbroker_pin_b` im Abschnitt Canned-Message-Modul fest. |
| `inputbroker_pin_press` | number |  | Legt den GPIO-/Pin-Wert `inputbroker_pin_press` im Abschnitt Canned-Message-Modul fest. |
| `inputbroker_event_cw` | enum string | `NONE`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `SELECT`, `BACK`, `CANCEL` | Konfiguriert `inputbroker_event_cw` im Abschnitt Canned-Message-Modul. |
| `inputbroker_event_ccw` | enum string | `NONE`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `SELECT`, `BACK`, `CANCEL` | Konfiguriert `inputbroker_event_ccw` im Abschnitt Canned-Message-Modul. |
| `inputbroker_event_press` | enum string | `NONE`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `SELECT`, `BACK`, `CANCEL` | Konfiguriert `inputbroker_event_press` im Abschnitt Canned-Message-Modul. |
| `updown1_enabled` | boolean |  | Aktiviert oder deaktiviert `updown1_enabled` im Abschnitt Canned-Message-Modul. |
| `enabled` | boolean |  | Aktiviert oder deaktiviert `enabled` im Abschnitt Canned-Message-Modul. |
| `allow_input_source` | string |  | Konfiguriert `allow_input_source` im Abschnitt Canned-Message-Modul. |
| `send_bell` | boolean |  | Konfiguriert `send_bell` im Abschnitt Canned-Message-Modul. |

### `audio`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `codec2_enabled` | boolean |  | Aktiviert oder deaktiviert `codec2_enabled` im Abschnitt Audio-Modul. |
| `ptt_pin` | number |  | Legt den GPIO-/Pin-Wert `ptt_pin` im Abschnitt Audio-Modul fest. |
| `bitrate` | enum string | `CODEC2_DEFAULT`, `CODEC2_3200`, `CODEC2_2400`, `CODEC2_1600`, `CODEC2_1400`, `CODEC2_1300`, `CODEC2_1200`, `CODEC2_700`, `CODEC2_700B` | Konfiguriert `bitrate` im Abschnitt Audio-Modul. |
| `i2s_ws` | number |  | Konfiguriert `i2s_ws` im Abschnitt Audio-Modul. |
| `i2s_sd` | number |  | Konfiguriert `i2s_sd` im Abschnitt Audio-Modul. |
| `i2s_din` | number |  | Konfiguriert `i2s_din` im Abschnitt Audio-Modul. |
| `i2s_sck` | number |  | Konfiguriert `i2s_sck` im Abschnitt Audio-Modul. |

### `remote_hardware`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Aktiviert oder deaktiviert `enabled` im Abschnitt Remote-Hardware-Modul. |
| `allow_undefined_pin_access` | boolean |  | Legt den GPIO-/Pin-Wert `allow_undefined_pin_access` im Abschnitt Remote-Hardware-Modul fest. |
| `available_pins[]` | Liste von table |  | Legt den GPIO-/Pin-Wert `available_pins[]` im Abschnitt Remote-Hardware-Modul fest. |
| `available_pins[].gpio_pin` | number |  | Legt den GPIO-/Pin-Wert `available_pins[].gpio_pin` im Abschnitt Remote-Hardware-Modul fest. |
| `available_pins[].name` | string |  | Legt den GPIO-/Pin-Wert `available_pins[].name` im Abschnitt Remote-Hardware-Modul fest. |
| `available_pins[].type` | enum string | `UNKNOWN`, `DIGITAL_READ`, `DIGITAL_WRITE` | Legt den GPIO-/Pin-Wert `available_pins[].type` im Abschnitt Remote-Hardware-Modul fest. |

### `neighbor_info`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Aktiviert oder deaktiviert `enabled` im Abschnitt Neighbor-Info-Modul. |
| `update_interval` | number |  | Legt das Intervall oder die Dauer für `update_interval` im Abschnitt Neighbor-Info-Modul fest. |
| `transmit_over_lora` | boolean |  | Konfiguriert `transmit_over_lora` im Abschnitt Neighbor-Info-Modul. |

### `ambient_lighting`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `led_state` | boolean |  | Konfiguriert `led_state` im Abschnitt Ambient-Lighting-Modul. |
| `current` | number |  | Konfiguriert `current` im Abschnitt Ambient-Lighting-Modul. |
| `red` | number |  | Legt den Farbwert `red` im Abschnitt Ambient-Lighting-Modul fest. |
| `green` | number |  | Legt den Farbwert `green` im Abschnitt Ambient-Lighting-Modul fest. |
| `blue` | number |  | Legt den Farbwert `blue` im Abschnitt Ambient-Lighting-Modul fest. |

### `detection_sensor`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Aktiviert oder deaktiviert `enabled` im Abschnitt Detection-Sensor-Modul. |
| `minimum_broadcast_secs` | number |  | Legt das Intervall oder die Dauer für `minimum_broadcast_secs` im Abschnitt Detection-Sensor-Modul fest. |
| `state_broadcast_secs` | number |  | Legt das Intervall oder die Dauer für `state_broadcast_secs` im Abschnitt Detection-Sensor-Modul fest. |
| `send_bell` | boolean |  | Konfiguriert `send_bell` im Abschnitt Detection-Sensor-Modul. |
| `name` | string |  | Konfiguriert den Namen `name` im Abschnitt Detection-Sensor-Modul. |
| `monitor_pin` | number |  | Legt den GPIO-/Pin-Wert `monitor_pin` im Abschnitt Detection-Sensor-Modul fest. |
| `detection_trigger_type` | enum string | `LOGIC_LOW`, `LOGIC_HIGH`, `FALLING_EDGE`, `RISING_EDGE`, `EITHER_EDGE_ACTIVE_LOW`, `EITHER_EDGE_ACTIVE_HIGH` | Wählt den Modus oder Typ `detection_trigger_type` im Abschnitt Detection-Sensor-Modul. |
| `use_pullup` | boolean |  | Konfiguriert `use_pullup` im Abschnitt Detection-Sensor-Modul. |

### `paxcounter`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Aktiviert oder deaktiviert `enabled` im Abschnitt Paxcounter-Modul. |
| `paxcounter_update_interval` | number |  | Legt das Intervall oder die Dauer für `paxcounter_update_interval` im Abschnitt Paxcounter-Modul fest. |
| `wifi_threshold` | number |  | Legt den Schwellenwert `wifi_threshold` im Abschnitt Paxcounter-Modul fest. |
| `ble_threshold` | number |  | Legt den Schwellenwert `ble_threshold` im Abschnitt Paxcounter-Modul fest. |

### `statusmessage`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `node_status` | string |  | Konfiguriert `node_status` im Abschnitt Statusmessage-Modul. |

### `traffic_management`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Aktiviert oder deaktiviert `enabled` im Abschnitt Traffic-Management-Modul. |
| `position_dedup_enabled` | boolean |  | Aktiviert oder deaktiviert `position_dedup_enabled` im Abschnitt Traffic-Management-Modul. |
| `position_precision_bits` | number |  | Konfiguriert `position_precision_bits` im Abschnitt Traffic-Management-Modul. |
| `position_min_interval_secs` | number |  | Legt das Intervall oder die Dauer für `position_min_interval_secs` im Abschnitt Traffic-Management-Modul fest. |
| `nodeinfo_direct_response` | boolean |  | Konfiguriert `nodeinfo_direct_response` im Abschnitt Traffic-Management-Modul. |
| `nodeinfo_direct_response_max_hops` | number |  | Konfiguriert `nodeinfo_direct_response_max_hops` im Abschnitt Traffic-Management-Modul. |
| `rate_limit_enabled` | boolean |  | Aktiviert oder deaktiviert `rate_limit_enabled` im Abschnitt Traffic-Management-Modul. |
| `rate_limit_window_secs` | number |  | Legt das Intervall oder die Dauer für `rate_limit_window_secs` im Abschnitt Traffic-Management-Modul fest. |
| `rate_limit_max_packets` | number |  | Konfiguriert `rate_limit_max_packets` im Abschnitt Traffic-Management-Modul. |
| `drop_unknown_enabled` | boolean |  | Aktiviert oder deaktiviert `drop_unknown_enabled` im Abschnitt Traffic-Management-Modul. |
| `unknown_packet_threshold` | number |  | Legt den Schwellenwert `unknown_packet_threshold` im Abschnitt Traffic-Management-Modul fest. |
| `exhaust_hop_telemetry` | boolean |  | Konfiguriert `exhaust_hop_telemetry` im Abschnitt Traffic-Management-Modul. |
| `exhaust_hop_position` | boolean |  | Konfiguriert `exhaust_hop_position` im Abschnitt Traffic-Management-Modul. |
| `router_preserve_hops` | boolean |  | Konfiguriert `router_preserve_hops` im Abschnitt Traffic-Management-Modul. |

### `tak`

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `team` | enum string | `Unspecifed_Color`, `White`, `Yellow`, `Orange`, `Magenta`, `Red`, `Maroon`, `Purple`, `Dark_Blue`, `Blue`, `Cyan`, `Teal`, `Green`, `Dark_Green`, `Brown` | Wählt den Modus oder Typ `team` im Abschnitt TAK-Modul. |
| `role` | enum string | `Unspecifed`, `TeamMember`, `TeamLead`, `HQ`, `Sniper`, `Medic`, `ForwardObserver`, `RTO`, `K9` | Wählt den Modus oder Typ `role` im Abschnitt TAK-Modul. |

## Kanäle: `changes.channels`

Jeder `channels`-Eintrag muss `index` enthalten; ohne `{ replace = true }` muss der bestehende Kanal zuerst mit `load_config` geladen werden.

| Feld | Lua-Typ | Enum-Werte | Beschreibung/Hinweise |
| --- | --- | --- | --- |
| `index` | number |  | Index des Kanals in der Kanaltabelle. |
| `settings` | table |  | Neue Kanaleinstellungen oder `NULL`, um den Kanal zu deaktivieren. |
| `settings.channel_num` | number |  | Veraltet; stattdessen `LoraConfig.channel_num` verwenden. |
| `settings.psk` | string (hex/base64) |  | Vorab geteilter Kanalschlüssel; leer, 16 Byte oder 32 Byte sind üblich. |
| `settings.name` | string |  | Kurzer Kanalname, der in URLs gepackt wird. |
| `settings.id` | number |  | Numerische ID zum Aufbau einer global eindeutigen Kanal-ID. |
| `settings.uplink_enabled` | boolean |  | Sendet Mesh-Nachrichten dieses Kanals über Gateways ins öffentliche Internet. |
| `settings.downlink_enabled` | boolean |  | Leitet im Internet gesehene Nachrichten in das lokale Mesh weiter. |
| `settings.module_settings` | table |  | Modulspezifische Einstellungen pro Kanal. |
| `settings.module_settings.position_precision` | number |  | Präzisionsbits für Positionen in Positionspaketen. |
| `settings.module_settings.is_muted` | boolean |  | Schaltet den aktuellen Kanal für Client/Gerät stumm. |
| `role` | enum string | `DISABLED`, `PRIMARY`, `SECONDARY` | Rolle des Kanals. |

## Beispiel

```lua
mesh.admin.save_config(target, {
    owner = { long_name = "Remote-Knoten", short_name = "RMT", licensed = true },
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

## Vollständige `save_config`-Vorlage

Diese kopierbare Vorlage enthält jeden Abschnitt, den `mesh.admin.save_config` akzeptiert. Senden Sie sie nicht ungeprüft unverändert: Löschen Sie Felder und Abschnitte, die Sie nicht ändern wollen. Für normale Patches laden Sie zuerst den aktuellen Remote-Zustand mit `mesh.admin.load_config(target)` oder gezielt per `request_config` / `request_module_config`, damit `save_config` Ihre Änderungen in die geladenen Werte einfügen kann.

```lua
local target = "!abcdef12"

local changes = {
    owner = {
        long_name = "Remote-Knoten",
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
    canned_messages = "OK|Bin unterwegs|Brauche Hilfe",

    configs = {
        device = {
            role = "CLIENT",
            serial_enabled = false, -- veraltet; bevorzugt security.serial_enabled
            button_gpio = 0,
            buzzer_gpio = 0,
            rebroadcast_mode = "ALL",
            node_info_broadcast_secs = 900,
            double_tap_as_button_press = false,
            is_managed = false, -- veraltet; bevorzugt security.is_managed
            disable_triple_click = false,
            tzdef = "UTC0",
            led_heartbeat_disabled = false,
            buzzer_mode = "ALL_ENABLED"
        },

        position = {
            position_broadcast_secs = 900,
            position_broadcast_smart_enabled = true,
            fixed_position = false,
            gps_enabled = true, -- veraltet; bevorzugt gps_mode
            gps_update_interval = 30,
            gps_attempt_time = 0, -- veraltet
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
            gps_format = "UNUSED", -- veraltet in diesem Abschnitt
            auto_screen_carousel_secs = 0,
            compass_north_top = false, -- veraltet
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
            -- Schlüssel nur bei Bedarf ändern: Remote-Admin-Zugriff kann verloren gehen.
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
            language = "ENGLISH",
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
            json_enabled = false, -- veraltet
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
            enabled = false, -- veraltet
            allow_input_source = "", -- veraltet
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
                channel_num = 0, -- veraltet; bevorzugt configs.lora.channel_num
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
                name = "Sekundär",
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
