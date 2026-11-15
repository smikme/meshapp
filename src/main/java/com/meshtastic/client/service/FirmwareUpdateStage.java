package com.meshtastic.client.service;

/**
 * Firmware update lifecycle stages exposed to the settings UI.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public enum FirmwareUpdateStage {
    /**
     * No firmware update is running.
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
     * The bootloader is verifying the image or the app is awaiting the original node's return.
     */
    VERIFYING,

    /**
     * Transfer completed and the original node reconnected with application metadata.
     */
    COMPLETE,

    /**
     * Validation, transfer or reconnection failed, or the operation was cancelled.
     */
    FAILED
}
