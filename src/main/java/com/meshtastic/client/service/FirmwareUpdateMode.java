package com.meshtastic.client.service;

/**
 * User-selectable firmware transfer mode.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public enum FirmwareUpdateMode {
    /**
     * Choose the most likely bootloader mode from file type, transport, and metadata.
     */
    AUTO("settings.firmware.mode.auto"),

    /**
     * Update an ESP32 device through the BLE OTA loader.
     */
    OTA_BLE("settings.firmware.mode.otaBle"),

    /**
     * Update an ESP32 device through the Wi-Fi OTA loader.
     */
    OTA_WIFI("settings.firmware.mode.otaWifi"),

    /**
     * Update through Nordic BLE DFU or a USB UF2 volume.
     */
    DFU("settings.firmware.mode.dfu"),

    /**
     * Write an ESP application through the selected serial port using external esptool.
     */
    SERIAL("settings.firmware.mode.serial");

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
