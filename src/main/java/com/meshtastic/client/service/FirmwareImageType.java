package com.meshtastic.client.service;

/**
 * Coarse firmware image classification used before entering a bootloader.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public enum FirmwareImageType {
    /**
     * Raw ESP32 application firmware binary used by Meshtastic OTA loaders.
     */
    ESP32_BIN,

    /**
     * UF2 image used by DFU-capable boards and mass-storage style loaders.
     */
    UF2,

    /**
     * Packaged firmware archive, usually containing a DFU payload or release files.
     */
    ZIP,

    /**
     * File type could not be inferred from the selected path.
     */
    UNKNOWN
}
