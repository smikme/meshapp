package com.meshtastic.client.service;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;

/**
 * Inputs for a complete firmware update, including optional bootloader selection callbacks.
 *
 * <p>The record retains live connection and device-state references. Selectors are invoked
 * from the firmware worker; UI implementations must schedule dialogs on the UI thread and
 * complete the returned future with the selection, or {@code null} on cancellation.
 *
 * @param connectionEntry active connection profile
 * @param state active Meshtastic device state
 * @param handler protocol handler used to send admin commands
 * @param image selected and analyzed firmware image
 * @param mode user-selected update entry mode
 * @param uf2TargetSelector selects a bootloader volume from suggested mounted roots; may also
 *                         return another root; required for UF2, otherwise may be {@code null}
 * @param bleTargetSelector selects from discovered bootloaders when address matching fails;
 *                         {@code null} disables this manual fallback
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record FirmwareUpdateRequest(
    ConnectionEntry connectionEntry,
    DeviceState state,
    ProtocolHandler handler,
    FirmwareImage image,
    FirmwareUpdateMode mode,
    java.util.function.Function<java.util.List<java.nio.file.Path>, java.util.concurrent.CompletableFuture<java.nio.file.Path>> uf2TargetSelector,
    java.util.function.Function<java.util.List<com.meshtastic.client.connection.ble.BleDevice>, java.util.concurrent.CompletableFuture<com.meshtastic.client.connection.ble.BleDevice>> bleTargetSelector
) {
    /**
     * Creates a request without interactive target selection.
     * UF2 requires the full constructor; BLE can proceed only if its address matches automatically.
     *
     * @param entry active connection profile
     * @param state active Meshtastic device state
     * @param handler protocol handler used for bootloader commands
     * @param image selected and analyzed source image
     * @param mode requested update mode
     */
    public FirmwareUpdateRequest(ConnectionEntry entry, DeviceState state, ProtocolHandler handler,
                                 FirmwareImage image, FirmwareUpdateMode mode) {
        this(entry, state, handler, image, mode, null, null);
    }
}
