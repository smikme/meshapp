package com.meshtastic.client.service;

import org.meshtastic.proto.MeshProtos;

/**
 * Outcome of asking a device to enter a firmware update bootloader.
 *
 * @param success {@code true} when the command flow reached reboot handoff
 * @param resolvedMode actual OTA/DFU mode used after automatic resolution
 * @param ackReceived {@code true} when a routing ACK was received before timeout
 * @param reconnectHandoffStarted {@code true} when the connection manager accepted the reboot handoff
 * @param routingError routing error returned by the device, or {@code null} when unavailable
 * @param message localized status message for the UI
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record FirmwareUpdateResult(
    boolean success,
    FirmwareUpdateMode resolvedMode,
    boolean ackReceived,
    boolean reconnectHandoffStarted,
    MeshProtos.Routing.Error routingError,
    String message
) {}
