package com.meshtastic.client.model;

import java.util.Optional;
import org.meshtastic.proto.MeshProtos;

/**
 * Version-gated behavior advertised by one Meshtastic node.
 */
public record FirmwareCapabilities(
        Optional<FirmwareVersion> version,
        boolean firmware28OrNewer,
        boolean xeddsa) {

    public static final FirmwareVersion FIRMWARE_2_8_0 =
            new FirmwareVersion(2, 8, 0);

    public FirmwareCapabilities {
        version = version != null ? version : Optional.empty();
    }

    public static FirmwareCapabilities legacy() {
        return new FirmwareCapabilities(Optional.empty(), false, false);
    }

    public static FirmwareCapabilities fromMetadata(MeshProtos.DeviceMetadata metadata) {
        if (metadata == null) {
            return legacy();
        }
        Optional<FirmwareVersion> version =
                FirmwareVersion.parse(metadata.getFirmwareVersion());
        boolean firmware28 = version
                .map(value -> value.isAtLeast(FIRMWARE_2_8_0))
                .orElse(false);
        return new FirmwareCapabilities(
                version,
                firmware28,
                firmware28 && metadata.getHasXeddsa());
    }
}
