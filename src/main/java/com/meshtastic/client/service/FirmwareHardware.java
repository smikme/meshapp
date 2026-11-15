package com.meshtastic.client.service;

import com.meshtastic.client.model.DeviceState;

/** Conservative checks for unambiguous hardware IDs; unknown boards still require user review. */
final class FirmwareHardware {
    private FirmwareHardware() {}
    /**
     * Rejects known contradictions between image processor identifiers and device metadata.
     * An unknown board or missing metadata is inconclusive and returns {@code true}; callers
     * must still validate transport capabilities and obtain confirmation of the board variant.
     *
     * @param image validated application payload
     * @param state device state, or {@code null} if metadata is unavailable
     * @return {@code false} only when the known hardware identifiers contradict the payload
     */
    static boolean compatible(FirmwarePayload image, DeviceState state) {
        if (state == null || state.getDeviceMetadata() == null) return true;
        String model = state.getDeviceMetadata().getHwModel().name();
        Integer esp = switch (model) {
            case "HELTEC_V3", "HELTEC_WSL_V3", "LILYGO_TBEAM_S3_CORE", "HELTEC_WIRELESS_TRACKER",
                 "HELTEC_WIRELESS_TRACKER_V1_0", "UNPHONE", "CDEBYTE_EORA_S3" -> 9;
            case "TLORA_V2", "TLORA_V1", "TLORA_V2_1_1P6", "TBEAM", "TBEAM_V0P7", "TLORA_V1_1P3",
                 "HELTEC_V2_0", "HELTEC_V2_1", "HELTEC_V1", "RAK11200" -> 0;
            default -> null;
        };
        boolean nrf = model.startsWith("NRF52") || switch (model) {
            case "T_ECHO", "RAK4631", "TD_LORAC", "TWC_MESH_V4" -> true;
            default -> false;
        };
        boolean pico = model.equals("RPI_PICO");
        if (image.chipId >= 0) return !nrf && !pico && (esp == null || esp == image.chipId);
        if (image.initPacket != null) return esp == null && !pico;
        if (esp != null) return false;
        if (nrf) return image.uf2Family == 0xada52840;
        if (pico) return image.uf2Family == 0xe48bff56;
        return true;
    }
}
