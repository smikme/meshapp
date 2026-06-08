package com.meshtastic.client.service;

/**
 * Firmware update preparation stages exposed to the settings UI.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public enum FirmwareUpdateStage {
    /**
     * No firmware update preparation is running.
     */
    IDLE,

    /**
     * The selected file and active connection are being checked.
     */
    VALIDATING,

    /**
     * The session passkey is being requested before sending a mutating command.
     */
    REQUESTING_SESSION_KEY,

    /**
     * The OTA/DFU admin command is being sent to the device.
     */
    SENDING_COMMAND,

    /**
     * The app is waiting for the device to reboot or drop the transport.
     */
    WAITING_FOR_REBOOT,

    /**
     * The app is connecting to the bootloader transport.
     */
    CONNECTING_UPLOADER,

    /**
     * Firmware bytes are being uploaded to the bootloader.
     */
    UPLOADING,

    /**
     * The bootloader is verifying the uploaded image.
     */
    VERIFYING,

    /**
     * The bootloader command flow completed.
     */
    COMPLETE,

    /**
     * The bootloader command flow failed before completion.
     */
    FAILED
}
