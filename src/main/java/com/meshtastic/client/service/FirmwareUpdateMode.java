package com.meshtastic.client.service;

/**
 * User-selectable device firmware update entry mode.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public enum FirmwareUpdateMode {
    /**
     * Choose the most likely bootloader mode from file type, transport, and metadata.
     */
    AUTO("settings.firmware.mode.auto"),

    /**
     * Reboot an ESP32 device into the BLE OTA loader.
     */
    OTA_BLE("settings.firmware.mode.otaBle"),

    /**
     * Reboot an ESP32 device into the Wi-Fi OTA loader.
     */
    OTA_WIFI("settings.firmware.mode.otaWifi"),

    /**
     * Reboot a UF2-capable device into DFU mode.
     */
    DFU("settings.firmware.mode.dfu");

    private final String labelKey;

    FirmwareUpdateMode(String labelKey) {
        this.labelKey = labelKey;
    }

    /**
     * Returns the localization key for the user-facing mode name.
     *
     * @return resource bundle key
     */
    public String labelKey() {
        return labelKey;
    }
}
