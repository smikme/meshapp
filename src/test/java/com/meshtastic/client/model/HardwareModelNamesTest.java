package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HardwareModelNamesTest {

    @Test
    void keepsLegacyNamesBeforeFirmware28() {
        assertEquals(
                "SENSELORA_RP2040",
                HardwareModelNames.forFirmware(
                        MeshProtos.HardwareModel.SENSELORA_RP2040,
                        FirmwareCapabilities.legacy()));
        assertEquals(
                "TRACKER_T1000_E_PRO",
                HardwareModelNames.forFirmware(
                        MeshProtos.HardwareModel.TRACKER_T1000_E_PRO,
                        FirmwareCapabilities.legacy()));
    }

    @Test
    void usesRepurposedNamesStartingWithFirmware28() {
        FirmwareCapabilities firmware28 = new FirmwareCapabilities(
                Optional.of(FirmwareCapabilities.FIRMWARE_2_8_0),
                true,
                false);

        assertEquals(
                "MAKERFABS_TRACKER",
                HardwareModelNames.forFirmware(
                        MeshProtos.HardwareModel.SENSELORA_RP2040,
                        firmware28));
        assertEquals(
                "MAKERFABS_RESERVED",
                HardwareModelNames.forFirmware(
                        MeshProtos.HardwareModel.SENSELORA_S3,
                        firmware28));
        assertEquals(
                "MESH_TRACKER_X1",
                HardwareModelNames.forFirmware(
                        MeshProtos.HardwareModel.TRACKER_T1000_E_PRO,
                        firmware28));
        assertEquals(
                128,
                HardwareModelNames.toProto("MESH_TRACKER_X1").getNumber());
    }

    @Test
    void remapsNodeInfoReceivedBeforeMetadata() {
        DeviceState state = new DeviceState();
        state.getOrCreateNode(123).setHwModel("TRACKER_T1000_E_PRO");

        state.setDeviceMetadata(MeshProtos.DeviceMetadata.newBuilder()
                .setFirmwareVersion("2.8.0")
                .build());

        assertEquals("MESH_TRACKER_X1", state.getNodeDb().get(123).getHwModel());
    }
}
