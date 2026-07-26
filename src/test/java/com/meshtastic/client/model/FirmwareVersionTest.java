package com.meshtastic.client.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;

class FirmwareVersionTest {

    @Test
    void parsesFirmwareBuildSuffixAndApplies28Threshold() {
        assertTrue(FirmwareVersion.parse("2.8.0.abcdef").orElseThrow()
                .isAtLeast(FirmwareCapabilities.FIRMWARE_2_8_0));
        assertTrue(FirmwareVersion.parse("v2.9.1-beta").orElseThrow()
                .isAtLeast(FirmwareCapabilities.FIRMWARE_2_8_0));
        assertFalse(FirmwareVersion.parse("2.7.27").orElseThrow()
                .isAtLeast(FirmwareCapabilities.FIRMWARE_2_8_0));
        assertTrue(FirmwareVersion.parse("unknown").isEmpty());
    }

    @Test
    void missingOrInvalidMetadataUsesLegacyCapabilities() {
        assertFalse(FirmwareCapabilities.fromMetadata(null)
                .firmware28OrNewer());
        assertFalse(FirmwareCapabilities.fromMetadata(
                MeshProtos.DeviceMetadata.newBuilder()
                        .setFirmwareVersion("dev")
                        .setHasXeddsa(true)
                        .build()).firmware28OrNewer());
    }

    @Test
    void xeddsaRequiresBoth28AndAdvertisedCapability() {
        assertTrue(FirmwareCapabilities.fromMetadata(
                MeshProtos.DeviceMetadata.newBuilder()
                        .setFirmwareVersion("2.8.0")
                        .setHasXeddsa(true)
                        .build()).xeddsa());
        assertFalse(FirmwareCapabilities.fromMetadata(
                MeshProtos.DeviceMetadata.newBuilder()
                        .setFirmwareVersion("2.7.27")
                        .setHasXeddsa(true)
                        .build()).xeddsa());
    }
}
