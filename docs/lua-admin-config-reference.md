# `mesh.admin` Configuration Reference

This reference lists the parameters returned in `event.snapshot` after `mesh.admin.load_config` / `request_config` / `request_module_config` and accepted by `mesh.admin.save_config`.

Lua field names match protobuf `snake_case`. Enum values are strings. `bytes` accept hex, `hex:...`, `base64:...`, or Base64. Repeated fields are Lua lists. By default `save_config` merges each patch with the already loaded section; call `load_config`, `request_config`, or `request_module_config` first, or pass `{ replace = true, confirm = true }` when intentionally replacing a section from defaults.

## `event.snapshot`

| Field | Lua type | Description |
| --- | --- | --- |
| `target_node_num` | number | Numeric target node ID |
| `target_node_id` | string | Target node ID in `!abcdef12` form |
| `node` | table | Current target node record |
| `owner` | table or `nil` | Owner/user payload loaded from the remote node |
| `device_metadata` | table or `nil` | Device metadata loaded from the remote node |
| `ringtone` | string | Current RTTTL ringtone text |
| `canned_messages` | string | Current canned messages module payload |
| `canned_messages_loaded` | boolean | Whether canned messages were loaded |
| `connection_status` | table or `nil` | Remote device connection status |
| `configs` | table | Core config sections; fields match `changes.configs` below |
| `module_configs` | table | Module config sections; fields match `changes.module_configs` below |
| `channels` | list of table | Loaded channels; fields match `changes.channels` below |
| `query_statuses` | list of table | Per-block load status entries: `key`, `state`, `detail` |
| `query_summary` | table | Load summary: `total`, `received`, `failed` |

## Top-Level `save_config`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `owner.long_name` | string |  | Node long name. |
| `owner.short_name` | string |  | Node short name. |
| `owner.licensed` | boolean |  | Licensed operator flag. |
| `position.latitude` | number |  | Latitude in degrees; sent as manual fixed position. |
| `position.longitude` | number |  | Longitude in degrees; sent as manual fixed position. |
| `position.altitude` | number |  | Altitude in meters. |
| `remove_position` | boolean |  | `true` clears manual fixed position. |
| `ringtone` | string |  | RTTTL ringtone text. |
| `canned_messages` | string |  | Canned messages module payload text. |
| `configs` | table |  | Core config sections below. |
| `module_configs` | table |  | Module config sections below. |
| `channels` | list of table |  | List of channel patches. |

## Core Config: `changes.configs`

Available sections:

| Section | Lua patch | Top-level fields |
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

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `role` | enum string | `CLIENT`, `CLIENT_MUTE`, `ROUTER`, `ROUTER_CLIENT` deprecated, `REPEATER` deprecated, `TRACKER`, `SENSOR`, `TAK`, `CLIENT_HIDDEN`, `LOST_AND_FOUND`, `TAK_TRACKER`, `ROUTER_LATE`, `CLIENT_BASE` | Sets the role of node |
| `serial_enabled` | boolean |  | deprecated Disabling this will disable the SerialConsole by not initilizing the StreamAPI Moved to SecurityConfig |
| `button_gpio` | number |  | For boards without a hard wired button, this is the pin number that will be used Boards that have more than one button can swap the function with this one. defaults to BUTTON_PIN if defined. |
| `buzzer_gpio` | number |  | For boards without a PWM buzzer, this is the pin number that will be used Defaults to PIN_BUZZER if defined. |
| `rebroadcast_mode` | enum string | `ALL`, `ALL_SKIP_DECODING`, `LOCAL_ONLY`, `KNOWN_ONLY`, `NONE`, `CORE_PORTNUMS_ONLY` | Sets the role of node |
| `node_info_broadcast_secs` | number |  | Send our nodeinfo this often Defaults to 900 Seconds (15 minutes) |
| `double_tap_as_button_press` | boolean |  | Treat double tap interrupt on supported accelerometers as a button press if set to true |
| `is_managed` | boolean |  | deprecated If true, device is considered to be "managed" by a mesh administrator Clients should then limit available configuration and administrative options inside the user interface Moved to SecurityConfig |
| `disable_triple_click` | boolean |  | Disables the triple-press of user button to enable or disable GPS |
| `tzdef` | string |  | POSIX Timezone definition string from https://github.com/nayarsystems/posix_tz_db/blob/master/zones.csv. |
| `led_heartbeat_disabled` | boolean |  | If true, disable the default blinking LED (LED_PIN) behavior on the device |
| `buzzer_mode` | enum string | `ALL_ENABLED`, `DISABLED`, `NOTIFICATIONS_ONLY`, `SYSTEM_ONLY`, `DIRECT_MSG_ONLY` | Controls buzzer behavior for audio feedback Defaults to ENABLED |

### `position`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `position_broadcast_secs` | number |  | We should send our position this often (but only if it has changed significantly) Defaults to 15 minutes |
| `position_broadcast_smart_enabled` | boolean |  | Adaptive position braoadcast, which is now the default. |
| `fixed_position` | boolean |  | If set, this node is at a fixed position. We will generate GPS position updates at the regular interval, but use whatever the last lat/lon/alt we have for the node. The lat/lon/alt can be set by an internal GPS or with the help of the app. |
| `gps_enabled` | boolean |  | deprecated Is GPS enabled for this node? |
| `gps_update_interval` | number |  | How often should we try to get GPS position (in seconds) or zero for the default of once every 30 seconds or a very large value (maxint) to update only once at boot. |
| `gps_attempt_time` | number |  | Deprecated in favor of using smart / regular broadcast intervals as implicit attempt time |
| `position_flags` | number |  | Bit field of boolean configuration options for POSITION messages (bitwise OR of PositionFlags) |
| `rx_gpio` | number |  | (Re)define GPS_RX_PIN for your board. |
| `tx_gpio` | number |  | (Re)define GPS_TX_PIN for your board. |
| `broadcast_smart_minimum_distance` | number |  | The minimum distance in meters traveled (since the last send) before we can send a position to the mesh if position_broadcast_smart_enabled |
| `broadcast_smart_minimum_interval_secs` | number |  | The minimum number of seconds (since the last send) before we can send a position to the mesh if position_broadcast_smart_enabled |
| `gps_en_gpio` | number |  | (Re)define PIN_GPS_EN for your board. |
| `gps_mode` | enum string | `DISABLED`, `ENABLED`, `NOT_PRESENT` | Set where GPS is enabled, disabled, or not present |

### `power`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `is_power_saving` | boolean |  | Description: Will sleep everything as much as possible, for the tracker and sensor role this will also include the lora radio. Don't use this setting if you want to use your device with the phone apps or are using a device without a user button. Technical Details: Works for ESP32 devices and NRF52 devices in the Sensor or Tracker roles |
| `on_battery_shutdown_after_secs` | number |  | Description: If non-zero, the device will fully power off this many seconds after external power is removed. |
| `adc_multiplier_override` | number |  | Ratio of voltage divider for battery pin eg. 3.20 (R1=100k, R2=220k) Overrides the ADC_MULTIPLIER defined in variant for battery voltage calculation. https://meshtastic.org/docs/configuration/radio/power/#adc-multiplier-override Should be set to floating point value between 2 and 6 |
| `wait_bluetooth_secs` | number |  | Description: The number of seconds for to wait before turning off BLE in No Bluetooth states Technical Details: ESP32 Only 0 for default of 1 minute |
| `sds_secs` | number |  | Super Deep Sleep Seconds While in Light Sleep if mesh_sds_timeout_secs is exceeded we will lower into super deep sleep for this value (default 1 year) or a button press 0 for default of one year |
| `ls_secs` | number |  | Description: In light sleep the CPU is suspended, LoRa radio is on, BLE is off an GPS is on Technical Details: ESP32 Only 0 for default of 300 |
| `min_wake_secs` | number |  | Description: While in light sleep when we receive packets on the LoRa radio we will wake and handle them and stay awake in no BLE mode for this value Technical Details: ESP32 Only 0 for default of 10 seconds |
| `device_battery_ina_address` | number |  | I2C address of INA_2XX to use for reading device battery voltage |
| `powermon_enables` | number |  | If non-zero, we want powermon log outputs. With the particular (bitfield) sources enabled. Note: we picked an ID of 32 so that lower more efficient IDs can be used for more frequently used options. |

### `network`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `wifi_enabled` | boolean |  | Enable WiFi (disables Bluetooth) |
| `wifi_ssid` | string |  | If set, this node will try to join the specified wifi network and acquire an address via DHCP |
| `wifi_psk` | string |  | If set, will be use to authenticate to the named wifi |
| `ntp_server` | string |  | NTP server to use if WiFi is conneced, defaults to `meshtastic.pool.ntp.org` |
| `eth_enabled` | boolean |  | Enable Ethernet |
| `address_mode` | enum string | `DHCP`, `STATIC` | acquire an address via DHCP or assign static |
| `ipv4_config` | table |  | struct to keep static address |
| `ipv4_config.ip` | number |  | Static IP address |
| `ipv4_config.gateway` | number |  | Static gateway address |
| `ipv4_config.subnet` | number |  | Static subnet mask |
| `ipv4_config.dns` | number |  | Static DNS server address |
| `rsyslog_server` | string |  | rsyslog Server and Port |
| `enabled_protocols` | number |  | Flags for enabling/disabling network protocols |
| `ipv6_enabled` | boolean |  | Enable/Disable ipv6 support |

### `display`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `screen_on_secs` | number |  | Number of seconds the screen stays on after pressing the user button or receiving a message 0 for default of one minute MAXUINT for always on |
| `gps_format` | enum string | `UNUSED` | Deprecated in 2.7.4: Unused How the GPS coordinates are formatted on the OLED screen. |
| `auto_screen_carousel_secs` | number |  | Automatically toggles to the next page on the screen like a carousel, based the specified interval in seconds. Potentially useful for devices without user buttons. |
| `compass_north_top` | boolean |  | deprecated If this is set, the displayed compass will always point north. if unset, the old behaviour (top of display is heading direction) is used. |
| `flip_screen` | boolean |  | Flip screen vertically, for cases that mount the screen upside down |
| `units` | enum string | `METRIC`, `IMPERIAL` | Perferred display units |
| `oled` | enum string | `OLED_AUTO`, `OLED_SSD1306`, `OLED_SH1106`, `OLED_SH1107`, `OLED_SH1107_128_128`, `OLED_SH1107_ROTATED` | Override auto-detect in screen |
| `displaymode` | enum string | `DEFAULT`, `TWOCOLOR`, `INVERTED`, `COLOR` | Display Mode |
| `heading_bold` | boolean |  | Print first line in pseudo-bold? FALSE is original style, TRUE is bold |
| `wake_on_tap_or_motion` | boolean |  | Should we wake the screen up on accelerometer detected motion or tap |
| `compass_orientation` | enum string | `DEGREES_0`, `DEGREES_90`, `DEGREES_180`, `DEGREES_270`, `DEGREES_0_INVERTED`, `DEGREES_90_INVERTED`, `DEGREES_180_INVERTED`, `DEGREES_270_INVERTED` | Indicates how to rotate or invert the compass output to accurate display on the display. |
| `use_12h_clock` | boolean |  | If false (default), the device will display the time in 24-hour format on screen. If true, the device will display the time in 12-hour format on screen. |
| `use_long_node_name` | boolean |  | If false (default), the device will use short names for various display screens. If true, node names will show in long format |
| `enable_message_bubbles` | boolean |  | If true, the device will display message bubbles on screen. |

### `lora`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `use_preset` | boolean |  | When enabled, the `modem_preset` fields will be adhered to, else the `bandwidth`/`spread_factor`/`coding_rate` will be taked from their respective manually defined fields |
| `modem_preset` | enum string | `LONG_FAST`, `LONG_SLOW` deprecated, `VERY_LONG_SLOW` deprecated, `MEDIUM_SLOW`, `MEDIUM_FAST`, `SHORT_SLOW`, `SHORT_FAST`, `LONG_MODERATE`, `SHORT_TURBO`, `LONG_TURBO`, `LITE_FAST`, `LITE_SLOW`, `NARROW_FAST`, `NARROW_SLOW` | Either modem_config or bandwidth/spreading/coding will be specified - NOT BOTH. As a heuristic: If bandwidth is specified, do not use modem_config. Because protobufs take ZERO space when the value is zero this works out nicely. This value is replaced by bandwidth/spread_factor/coding_rate. If you'd like to experiment with other options add them to MeshRadio.cpp in the device code. |
| `bandwidth` | number |  | Bandwidth in MHz Certain bandwidth numbers are 'special' and will be converted to the appropriate floating point value: 31 -> 31.25MHz |
| `spread_factor` | number |  | A number from 7 to 12. Indicates number of chirps per symbol as 1<<spread_factor. |
| `coding_rate` | number |  | The denominator of the coding rate. ie for 4/5, the value is 5. 4/8 the value is 8. |
| `frequency_offset` | number |  | This parameter is for advanced users with advanced test equipment, we do not recommend most users use it. A frequency offset that is added to to the calculated band center frequency. Used to correct for crystal calibration errors. |
| `region` | enum string | `UNSET`, `US`, `EU_433`, `EU_868`, `CN`, `JP`, `ANZ`, `KR`, `TW`, `RU`, `IN`, `NZ_865`, `TH`, `LORA_24`, `UA_433`, `UA_868`, `MY_433`, `MY_919`, `SG_923`, `PH_433`, `PH_868`, `PH_915`, `ANZ_433`, `KZ_433`, `KZ_863`, `NP_865`, `BR_902`, `ITU1_2M`, `ITU23_2M`, `EU_866`, `EU_874`, `EU_917`, `EU_N_868` | The region code for the radio (US, CN, EU433, etc...) |
| `hop_limit` | number |  | Maximum number of hops. This can't be greater than 7. Default of 3 Attempting to set a value > 7 results in the default |
| `tx_enabled` | boolean |  | Disable TX from the LoRa radio. Useful for hot-swapping antennas and other tests. Defaults to false |
| `tx_power` | number |  | If zero, then use default max legal continuous power (ie. something that won't burn out the radio hardware) In most cases you should use zero here. Units are in dBm. |
| `channel_num` | number |  | This controls the actual hardware frequency the radio transmits on. Most users should never need to be exposed to this field/concept. A channel number between 1 and NUM_CHANNELS (whatever the max is in the current region). If ZERO then the rule is "use the old channel name hash based algorithm to derive the channel number") If using the hash algorithm the channel number will be: hash(channel_name) % NUM_CHANNELS (Where num channels depends on the regulatory region). |
| `override_duty_cycle` | boolean |  | If true, duty cycle limits will be exceeded and thus you're possibly not following the local regulations if you're not a HAM. Has no effect if the duty cycle of the used region is 100%. |
| `sx126x_rx_boosted_gain` | boolean |  | If true, sets RX boosted gain mode on SX126X based radios |
| `override_frequency` | number |  | This parameter is for advanced users and licensed HAM radio operators. Ignore Channel Calculation and use this frequency instead. The frequency_offset will still be applied. This will allow you to use out-of-band frequencies. Please respect your local laws and regulations. If you are a HAM, make sure you enable HAM mode and turn off encryption. |
| `pa_fan_disabled` | boolean |  | If true, disable the build-in PA FAN using pin define in RF95_FAN_EN. |
| `ignore_incoming` | list of number |  | For testing it is useful sometimes to force a node to never listen to particular other nodes (simulating radio out of range). All nodenums listed in ignore_incoming will have packets they send dropped on receive (by router.cpp) |
| `ignore_mqtt` | boolean |  | If true, the device will not process any packets received via LoRa that passed via MQTT anywhere on the path towards it. |
| `config_ok_to_mqtt` | boolean |  | Sets the ok_to_mqtt bit on outgoing packets |
| `fem_lna_mode` | enum string | `DISABLED`, `ENABLED`, `NOT_PRESENT` | Set where LORA FEM is enabled, disabled, or not present |
| `serial_hal_only` | boolean |  | Don't use radiolib to initialize the radio, instead listen for a serialHal connection |

### `bluetooth`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Enable Bluetooth on the device |
| `mode` | enum string | `RANDOM_PIN`, `FIXED_PIN`, `NO_PIN` | Determines the pairing strategy for the device |
| `fixed_pin` | number |  | Specified PIN for PairingMode.FixedPin |

### `security`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `public_key` | string (hex/base64) |  | The public key of the user's device. Sent out to other nodes on the mesh to allow them to compute a shared secret key. |
| `private_key` | string (hex/base64) |  | The private key of the device. Used to create a shared key with a remote device. |
| `admin_key` | list of string (hex/base64) |  | The public key authorized to send admin messages to this node. |
| `is_managed` | boolean |  | If true, device is considered to be "managed" by a mesh administrator via admin messages Device is managed by a mesh administrator. |
| `serial_enabled` | boolean |  | Serial Console over the Stream API." |
| `debug_log_api_enabled` | boolean |  | By default we turn off logging as soon as an API client connects (to keep shared serial link quiet). Output live debug logging over serial or bluetooth is set to true. |
| `admin_channel_enabled` | boolean |  | Allow incoming device control over the insecure legacy admin channel. |

### `device_ui`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `version` | number |  | A version integer used to invalidate saved files when we make incompatible changes. |
| `screen_brightness` | number |  | TFT display brightness 1..255 |
| `screen_timeout` | number |  | Screen timeout 0..900 |
| `screen_lock` | boolean |  | Screen/Settings lock enabled |
| `settings_lock` | boolean |  |  |
| `pin_code` | number |  |  |
| `theme` | enum string | `DARK`, `LIGHT`, `RED` | Color theme |
| `alert_enabled` | boolean |  | Audible message, banner and ring tone |
| `banner_enabled` | boolean |  |  |
| `ring_tone_id` | number |  |  |
| `language` | enum string | `ENGLISH`, `FRENCH`, `GERMAN`, `ITALIAN`, `PORTUGUESE`, `SPANISH`, `SWEDISH`, `FINNISH`, `POLISH`, `TURKISH`, `SERBIAN`, `RUSSIAN`, `DUTCH`, `GREEK`, `NORWEGIAN`, `SLOVENIAN`, `UKRAINIAN`, `BULGARIAN`, `CZECH`, `DANISH`, `SIMPLIFIED_CHINESE`, `TRADITIONAL_CHINESE` | Localization |
| `node_filter` | table |  | Node list filter |
| `node_filter.unknown_switch` | boolean |  | Filter unknown nodes |
| `node_filter.offline_switch` | boolean |  | Filter offline nodes |
| `node_filter.public_key_switch` | boolean |  | Filter nodes w/o public key |
| `node_filter.hops_away` | number |  | Filter based on hops away |
| `node_filter.position_switch` | boolean |  | Filter nodes w/o position |
| `node_filter.node_name` | string |  | Filter nodes by matching name string |
| `node_filter.channel` | number |  | Filter based on channel |
| `node_highlight` | table |  | Node list highlightening |
| `node_highlight.chat_switch` | boolean |  | Hightlight nodes w/ active chat |
| `node_highlight.position_switch` | boolean |  | Highlight nodes w/ position |
| `node_highlight.telemetry_switch` | boolean |  | Highlight nodes w/ telemetry data |
| `node_highlight.iaq_switch` | boolean |  | Highlight nodes w/ iaq data |
| `node_highlight.node_name` | string |  | Highlight nodes by matching name string |
| `calibration_data` | string (hex/base64) |  | 8 integers for screen calibration data |
| `map_data` | table |  | Map related data |
| `map_data.home` | table |  | Home coordinates |
| `map_data.home.zoom` | number |  | Zoom level |
| `map_data.home.latitude` | number |  | Coordinate: latitude |
| `map_data.home.longitude` | number |  | Coordinate: longitude |
| `map_data.style` | string |  | Map tile style |
| `map_data.follow_gps` | boolean |  | Map scroll follows GPS |
| `compass_mode` | enum string | `DYNAMIC`, `FIXED_RING`, `FREEZE_HEADING` | Compass mode |
| `screen_rgb_color` | number |  | RGB color for BaseUI 0xRRGGBB format, e.g. 0xFF0000 for red |
| `is_clockface_analog` | boolean |  | Clockface analog style true for analog clockface, false for digital clockface |
| `gps_format` | enum string | `DEC`, `DMS`, `UTM`, `MGRS`, `OLC`, `OSGR`, `MLS` | How the GPS coordinates are formatted on the OLED screen. |

## Module Config: `changes.module_configs`

Available sections:

| Section | Lua patch | Top-level fields |
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

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `enabled` | boolean |  | If a meshtastic node is able to reach the internet it will normally attempt to gateway any channels that are marked as is_uplink_enabled or is_downlink_enabled. |
| `address` | string |  | The server to use for our MQTT global message gateway feature. If not set, the default server will be used |
| `username` | string |  | MQTT username to use (most useful for a custom MQTT server). If using a custom server, this will be honoured even if empty. If using the default server, this will only be honoured if set, otherwise the device will use the default username |
| `password` | string |  | MQTT password to use (most useful for a custom MQTT server). If using a custom server, this will be honoured even if empty. If using the default server, this will only be honoured if set, otherwise the device will use the default password |
| `encryption_enabled` | boolean |  | Whether to send encrypted or decrypted packets to MQTT. This parameter is only honoured if you also set server (the default official mqtt.meshtastic.org server can handle encrypted packets) Decrypted packets may be useful for external systems that want to consume meshtastic packets |
| `json_enabled` | boolean |  | Deprecated: JSON packet support on MQTT was removed, and this field is ignored. |
| `tls_enabled` | boolean |  | If true, we attempt to establish a secure connection using TLS |
| `root` | string |  | The root topic to use for MQTT messages. Default is "msh". This is useful if you want to use a single MQTT server for multiple meshtastic networks and separate them via ACLs |
| `proxy_to_client_enabled` | boolean |  | If true, we can use the connected phone / client to proxy messages to MQTT instead of a direct connection |
| `map_reporting_enabled` | boolean |  | If true, we will periodically report unencrypted information about our node to a map via MQTT |
| `map_report_settings` | table |  | Settings for reporting information about our node to a map via MQTT |
| `map_report_settings.publish_interval_secs` | number |  | How often we should report our info to the map (in seconds) |
| `map_report_settings.position_precision` | number |  | Bits of precision for the location sent (default of 32 is full precision). |
| `map_report_settings.should_report_location` | boolean |  | Whether we have opted-in to report our location to the map |

### `serial`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Preferences for the SerialModule |
| `echo` | boolean |  |  |
| `rxd` | number |  | RX pin (should match Arduino gpio pin number) |
| `txd` | number |  | TX pin (should match Arduino gpio pin number) |
| `baud` | enum string | `BAUD_DEFAULT`, `BAUD_110`, `BAUD_300`, `BAUD_600`, `BAUD_1200`, `BAUD_2400`, `BAUD_4800`, `BAUD_9600`, `BAUD_19200`, `BAUD_38400`, `BAUD_57600`, `BAUD_115200`, `BAUD_230400`, `BAUD_460800`, `BAUD_576000`, `BAUD_921600` | Serial baud rate |
| `timeout` | number |  |  |
| `mode` | enum string | `DEFAULT`, `SIMPLE`, `PROTO`, `TEXTMSG`, `NMEA`, `CALTOPO`, `WS85`, `VE_DIRECT`, `MS_CONFIG`, `LOG`, `LOGTEXT` | Mode for serial module operation |
| `override_console_serial_port` | boolean |  | Overrides the platform's defacto Serial port instance to use with Serial module config settings This is currently only usable in output modes like NMEA / CalTopo and may behave strangely or not work at all in other modes Existing logging over the Serial Console will still be present |

### `external_notification`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Enable the ExternalNotificationModule |
| `output_ms` | number |  | When using in On/Off mode, keep the output on for this many milliseconds. Default 1000ms (1 second). |
| `output` | number |  | Define the output pin GPIO setting Defaults to EXT_NOTIFY_OUT if set for the board. In standalone devices this pin should drive the LED to match the UI. |
| `output_vibra` | number |  | Optional: Define a secondary output pin for a vibra motor This is used in standalone devices to match the UI. |
| `output_buzzer` | number |  | Optional: Define a tertiary output pin for an active buzzer This is used in standalone devices to to match the UI. |
| `active` | boolean |  | IF this is true, the 'output' Pin will be pulled active high, false means active low. |
| `alert_message` | boolean |  | True: Alert when a text message arrives (output) |
| `alert_message_vibra` | boolean |  | True: Alert when a text message arrives (output_vibra) |
| `alert_message_buzzer` | boolean |  | True: Alert when a text message arrives (output_buzzer) |
| `alert_bell` | boolean |  | True: Alert when the bell character is received (output) |
| `alert_bell_vibra` | boolean |  | True: Alert when the bell character is received (output_vibra) |
| `alert_bell_buzzer` | boolean |  | True: Alert when the bell character is received (output_buzzer) |
| `use_pwm` | boolean |  | use a PWM output instead of a simple on/off output. This will ignore the 'output', 'output_ms' and 'active' settings and use the device.buzzer_gpio instead. |
| `nag_timeout` | number |  | The notification will toggle with 'output_ms' for this time of seconds. Default is 0 which means don't repeat at all. 60 would mean blink and/or beep for 60 seconds |
| `use_i2s_as_buzzer` | boolean |  | When true, enables devices with native I2S audio output to use the RTTTL over speaker like a buzzer T-Watch S3 and T-Deck for example have this capability |

### `store_forward`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Enable the Store and Forward Module |
| `heartbeat` | boolean |  |  |
| `records` | number |  |  |
| `history_return_max` | number |  |  |
| `history_return_window` | number |  |  |
| `is_server` | boolean |  | Set to true to let this node act as a server that stores received messages and resends them upon request. |

### `range_test`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Enable the Range Test Module |
| `sender` | number |  | Send out range test messages from this node |
| `save` | boolean |  | Bool value indicating that this node should save a RangeTest.csv file. ESP32 Only |
| `clear_on_reboot` | boolean |  | Bool indicating that the node should cleanup / destroy it's RangeTest.csv file. ESP32 Only |

### `telemetry`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `device_update_interval` | number |  | Interval in seconds of how often we should try to send our device metrics to the mesh |
| `environment_update_interval` | number |  | Interval in seconds of how often we should try to send our environment measurements to the mesh |
| `environment_measurement_enabled` | boolean |  | Preferences for the Telemetry Module (Environment) Enable/Disable the telemetry measurement module measurement collection |
| `environment_screen_enabled` | boolean |  | Enable/Disable the telemetry measurement module on-device display |
| `environment_display_fahrenheit` | boolean |  | We'll always read the sensor in Celsius, but sometimes we might want to display the results in Fahrenheit as a "user preference". |
| `air_quality_enabled` | boolean |  | Enable/Disable the air quality metrics |
| `air_quality_interval` | number |  | Interval in seconds of how often we should try to send our air quality metrics to the mesh |
| `power_measurement_enabled` | boolean |  | Enable/disable Power metrics |
| `power_update_interval` | number |  | Interval in seconds of how often we should try to send our power metrics to the mesh |
| `power_screen_enabled` | boolean |  | Enable/Disable the power measurement module on-device display |
| `health_measurement_enabled` | boolean |  | Preferences for the (Health) Telemetry Module Enable/Disable the telemetry measurement module measurement collection |
| `health_update_interval` | number |  | Interval in seconds of how often we should try to send our health metrics to the mesh |
| `health_screen_enabled` | boolean |  | Enable/Disable the health telemetry module on-device display |
| `device_telemetry_enabled` | boolean |  | Enable/Disable the device telemetry module to send metrics to the mesh Note: We will still send telemtry to the connected phone / client every minute over the API |
| `air_quality_screen_enabled` | boolean |  | Enable/Disable the air quality telemetry measurement module on-device display |

### `canned_message`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `rotary1_enabled` | boolean |  | Enable the rotary encoder #1. This is a 'dumb' encoder sending pulses on both A and B pins while rotating. |
| `inputbroker_pin_a` | number |  | GPIO pin for rotary encoder A port. |
| `inputbroker_pin_b` | number |  | GPIO pin for rotary encoder B port. |
| `inputbroker_pin_press` | number |  | GPIO pin for rotary encoder Press port. |
| `inputbroker_event_cw` | enum string | `NONE`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `SELECT`, `BACK`, `CANCEL` | Generate input event on CW of this kind. |
| `inputbroker_event_ccw` | enum string | `NONE`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `SELECT`, `BACK`, `CANCEL` | Generate input event on CCW of this kind. |
| `inputbroker_event_press` | enum string | `NONE`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `SELECT`, `BACK`, `CANCEL` | Generate input event on Press of this kind. |
| `updown1_enabled` | boolean |  | Enable the Up/Down/Select input device. Can be RAK rotary encoder or 3 buttons. Uses the a/b/press definitions from inputbroker. |
| `enabled` | boolean |  | deprecated Enable/disable CannedMessageModule. |
| `allow_input_source` | string |  | deprecated Input event origin accepted by the canned message module. Can be e.g. "rotEnc1", "upDownEnc1", "scanAndSelect", "cardkb", "serialkb", or keyword "_any" |
| `send_bell` | boolean |  | CannedMessageModule also sends a bell character with the messages. ExternalNotificationModule can benefit from this feature. |

### `audio`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `codec2_enabled` | boolean |  | Whether Audio is enabled |
| `ptt_pin` | number |  | PTT Pin |
| `bitrate` | enum string | `CODEC2_DEFAULT`, `CODEC2_3200`, `CODEC2_2400`, `CODEC2_1600`, `CODEC2_1400`, `CODEC2_1300`, `CODEC2_1200`, `CODEC2_700`, `CODEC2_700B` | The audio sample rate to use for codec2 |
| `i2s_ws` | number |  | I2S Word Select |
| `i2s_sd` | number |  | I2S Data IN |
| `i2s_din` | number |  | I2S Data OUT |
| `i2s_sck` | number |  | I2S Clock |

### `remote_hardware`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Whether the Module is enabled |
| `allow_undefined_pin_access` | boolean |  | Whether the Module allows consumers to read / write to pins not defined in available_pins |
| `available_pins[]` | list of table |  | Exposes the available pins to the mesh for reading and writing |
| `available_pins[].gpio_pin` | number |  | GPIO Pin number (must match Arduino) |
| `available_pins[].name` | string |  | Name for the GPIO pin (i.e. Front gate, mailbox, etc) |
| `available_pins[].type` | enum string | `UNKNOWN`, `DIGITAL_READ`, `DIGITAL_WRITE` | Type of GPIO access available to consumers on the mesh |

### `neighbor_info`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Whether the Module is enabled |
| `update_interval` | number |  | Interval in seconds of how often we should try to send our Neighbor Info (minimum is 14400, i.e., 4 hours) |
| `transmit_over_lora` | boolean |  | Whether in addition to sending it to MQTT and the PhoneAPI, our NeighborInfo should be transmitted over LoRa. Note that this is not available on a channel with default key and name. |

### `ambient_lighting`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `led_state` | boolean |  | Sets LED to on or off. |
| `current` | number |  | Sets the current for the LED output. Default is 10. |
| `red` | number |  | Sets the red LED level. Values are 0-255. |
| `green` | number |  | Sets the green LED level. Values are 0-255. |
| `blue` | number |  | Sets the blue LED level. Values are 0-255. |

### `detection_sensor`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Whether the Module is enabled |
| `minimum_broadcast_secs` | number |  | Interval in seconds of how often we can send a message to the mesh when a trigger event is detected |
| `state_broadcast_secs` | number |  | Interval in seconds of how often we should send a message to the mesh with the current state regardless of trigger events When set to 0, only trigger events will be broadcasted Works as a sort of status heartbeat for peace of mind |
| `send_bell` | boolean |  | Send ASCII bell with alert message Useful for triggering ext. notification on bell |
| `name` | string |  | Friendly name used to format message sent to mesh Example: A name "Motion" would result in a message "Motion detected" Maximum length of 20 characters |
| `monitor_pin` | number |  | GPIO pin to monitor for state changes |
| `detection_trigger_type` | enum string | `LOGIC_LOW`, `LOGIC_HIGH`, `FALLING_EDGE`, `RISING_EDGE`, `EITHER_EDGE_ACTIVE_LOW`, `EITHER_EDGE_ACTIVE_HIGH` | The type of trigger event to be used |
| `use_pullup` | boolean |  | Whether or not use INPUT_PULLUP mode for GPIO pin Only applicable if the board uses pull-up resistors on the pin |

### `paxcounter`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Enable the Paxcounter Module |
| `paxcounter_update_interval` | number |  | Interval in seconds of how often we should try to send our metrics to the mesh |
| `wifi_threshold` | number |  | WiFi RSSI threshold. Defaults to -80 |
| `ble_threshold` | number |  | BLE RSSI threshold. Defaults to -80 |

### `statusmessage`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `node_status` | string |  | The actual status string |

### `traffic_management`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `enabled` | boolean |  | Master enable for traffic management module |
| `position_dedup_enabled` | boolean |  | Enable position deduplication to drop redundant position broadcasts |
| `position_precision_bits` | number |  | Number of bits of precision for position deduplication (0-32) |
| `position_min_interval_secs` | number |  | Minimum interval in seconds between position updates from the same node |
| `nodeinfo_direct_response` | boolean |  | Enable direct response to NodeInfo requests from local cache |
| `nodeinfo_direct_response_max_hops` | number |  | Minimum hop distance from requestor before responding to NodeInfo requests |
| `rate_limit_enabled` | boolean |  | Enable per-node rate limiting to throttle chatty nodes |
| `rate_limit_window_secs` | number |  | Time window in seconds for rate limiting calculations |
| `rate_limit_max_packets` | number |  | Maximum packets allowed per node within the rate limit window |
| `drop_unknown_enabled` | boolean |  | Enable dropping of unknown/undecryptable packets per rate_limit_window_secs |
| `unknown_packet_threshold` | number |  | Number of unknown packets before dropping from a node |
| `exhaust_hop_telemetry` | boolean |  | Set hop_limit to 0 for relayed telemetry broadcasts (own packets unaffected) |
| `exhaust_hop_position` | boolean |  | Set hop_limit to 0 for relayed position broadcasts (own packets unaffected) |
| `router_preserve_hops` | boolean |  | Preserve hop_limit for router-to-router traffic |

### `tak`

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `team` | enum string | `Unspecifed_Color`, `White`, `Yellow`, `Orange`, `Magenta`, `Red`, `Maroon`, `Purple`, `Dark_Blue`, `Blue`, `Cyan`, `Teal`, `Green`, `Dark_Green`, `Brown` | Team color. Default Unspecifed_Color -> firmware uses Cyan |
| `role` | enum string | `Unspecifed`, `TeamMember`, `TeamLead`, `HQ`, `Sniper`, `Medic`, `ForwardObserver`, `RTO`, `K9` | Member role. Default Unspecifed -> firmware uses TeamMember |

## Channels: `changes.channels`

Each `channels` entry must include `index`; without `{ replace = true }`, the existing channel must first be loaded by `load_config`.

| Field | Lua type | Enum values | Description/notes |
| --- | --- | --- | --- |
| `index` | number |  | The index of this channel in the channel table (from 0 to MAX_NUM_CHANNELS-1) (Someday - not currently implemented) An index of -1 could be used to mean "set by name", in which case the target node will find and set the channel by settings.name. |
| `settings` | table |  | The new settings, or NULL to disable that channel |
| `settings.channel_num` | number |  | Deprecated in favor of LoraConfig.channel_num |
| `settings.psk` | string (hex/base64) |  | A simple pre-shared key for now for crypto. Must be either 0 bytes (no crypto), 16 bytes (AES128), or 32 bytes (AES256). A special shorthand is used for 1 byte long psks. These psks should be treated as only minimally secure, because they are listed in this source code. Those bytes are mapped using the following scheme: `0` = No crypto `1` = The special "default" channel key: {0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x07, 0x59, 0xf0, 0xbc, 0xff, 0xab, 0xcf, 0x4e, 0x69, 0x01} `2` through 10 = The default channel key, except with 1 through 9 added to the last byte. Shown to user as simple1 through 10 |
| `settings.name` | string |  | A SHORT name that will be packed into the URL. Less than 12 bytes. Something for end users to call the channel If this is the empty string it is assumed that this channel is the special (minimally secure) "Default"channel. In user interfaces it should be rendered as a local language translation of "X". For channel_num hashing empty string will be treated as "X". Where "X" is selected based on the English words listed above for ModemPreset |
| `settings.id` | number |  | Used to construct a globally unique channel ID. The full globally unique ID will be: "name.id" where ID is shown as base36. Assuming that the number of meshtastic users is below 20K (true for a long time) the chance of this 64 bit random number colliding with anyone else is super low. And the penalty for collision is low as well, it just means that anyone trying to decrypt channel messages might need to try multiple candidate channels. Any time a non wire compatible change is made to a channel, this field should be regenerated. There are a small number of 'special' globally known (and fairly) insecure standard channels. Those channels do not have a numeric id included in the settings, but instead it is pulled from a table of well known IDs. (see Well Known Channels FIXME) |
| `settings.uplink_enabled` | boolean |  | If true, messages on the mesh will be sent to the *public* internet by any gateway ndoe |
| `settings.downlink_enabled` | boolean |  | If true, messages seen on the internet will be forwarded to the local mesh. |
| `settings.module_settings` | table |  | Per-channel module settings. |
| `settings.module_settings.position_precision` | number |  | Bits of precision for the location sent in position packets. |
| `settings.module_settings.is_muted` | boolean |  | Controls whether or not the client / device should mute the current channel Useful for noisy public channels you don't necessarily want to disable |
| `role` | enum string | `DISABLED`, `PRIMARY`, `SECONDARY` |  |

## Example

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

## Full `save_config` Template

This copyable template includes every section accepted by `mesh.admin.save_config`. Do not send it as-is without review: delete fields and sections you do not want to change. For normal patches, load the current remote state first with `mesh.admin.load_config(target)` or targeted `request_config` / `request_module_config`, so `save_config` can merge your changes into the loaded values.

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
    canned_messages = "OK|On my way|Need help",

    configs = {
        device = {
            role = "CLIENT",
            serial_enabled = false, -- deprecated; prefer security.serial_enabled
            button_gpio = 0,
            buzzer_gpio = 0,
            rebroadcast_mode = "ALL",
            node_info_broadcast_secs = 900,
            double_tap_as_button_press = false,
            is_managed = false, -- deprecated; prefer security.is_managed
            disable_triple_click = false,
            tzdef = "UTC0",
            led_heartbeat_disabled = false,
            buzzer_mode = "ALL_ENABLED"
        },

        position = {
            position_broadcast_secs = 900,
            position_broadcast_smart_enabled = true,
            fixed_position = false,
            gps_enabled = true, -- deprecated; prefer gps_mode
            gps_update_interval = 30,
            gps_attempt_time = 0, -- deprecated
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
            gps_format = "UNUSED", -- deprecated in this section
            auto_screen_carousel_secs = 0,
            compass_north_top = false, -- deprecated
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
            -- Do not change keys unless necessary: you can lose remote-admin access.
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
            json_enabled = false, -- deprecated
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
            enabled = false, -- deprecated
            allow_input_source = "", -- deprecated
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
                channel_num = 0, -- deprecated; prefer configs.lora.channel_num
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
