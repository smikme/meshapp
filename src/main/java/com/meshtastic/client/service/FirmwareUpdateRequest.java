package com.meshtastic.client.service;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;

/**
 * Immutable firmware update preparation request.
 *
 * @param connectionEntry active connection profile
 * @param state active Meshtastic device state
 * @param handler protocol handler used to send admin commands
 * @param image selected and analyzed firmware image
 * @param mode user-selected update entry mode
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record FirmwareUpdateRequest(
    ConnectionEntry connectionEntry,
    DeviceState state,
    ProtocolHandler handler,
    FirmwareImage image,
    FirmwareUpdateMode mode
) {}
