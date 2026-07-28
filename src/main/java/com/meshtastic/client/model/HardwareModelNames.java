package com.meshtastic.client.model;

import org.meshtastic.proto.MeshProtos;

/**
 * Resolves hardware-model names whose numeric identifiers changed meaning in
 * Meshtastic firmware 2.8.0.
 */
public final class HardwareModelNames {

    private HardwareModelNames() {
    }

    /**
     * Returns the model name appropriate for the connected firmware.
     *
     * @param model protobuf hardware model
     * @param capabilities connected-node firmware capabilities
     * @return version-aware display and persistence name
     */
    public static String forFirmware(MeshProtos.HardwareModel model,
                                     FirmwareCapabilities capabilities) {
        if (model == null) {
            return MeshProtos.HardwareModel.UNSET.name();
        }
        if (capabilities == null || !capabilities.firmware28OrNewer()) {
            return model.name();
        }
        return switch (model.getNumber()) {
            case 27 -> "MAKERFABS_TRACKER";
            case 28 -> "MAKERFABS_RESERVED";
            case 128 -> "MESH_TRACKER_X1";
            default -> model.name();
        };
    }

    /**
     * Reinterprets a name cached before firmware metadata arrived.
     *
     * @param modelName cached protobuf enum name
     * @param capabilities connected-node firmware capabilities
     * @return version-aware name
     */
    public static String forFirmware(String modelName,
                                     FirmwareCapabilities capabilities) {
        if (modelName == null
                || capabilities == null
                || !capabilities.firmware28OrNewer()) {
            return modelName;
        }
        return switch (modelName) {
            case "SENSELORA_RP2040" -> "MAKERFABS_TRACKER";
            case "SENSELORA_S3" -> "MAKERFABS_RESERVED";
            case "TRACKER_T1000_E_PRO" -> "MESH_TRACKER_X1";
            default -> modelName;
        };
    }

    /**
     * Converts either a legacy or 2.8.0 display name back to its wire enum.
     *
     * @param modelName stored model name
     * @return protobuf model, or {@code null} for an unknown name
     */
    public static MeshProtos.HardwareModel toProto(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            return null;
        }
        return switch (modelName) {
            case "MAKERFABS_TRACKER" -> MeshProtos.HardwareModel.forNumber(27);
            case "MAKERFABS_RESERVED" -> MeshProtos.HardwareModel.forNumber(28);
            case "MESH_TRACKER_X1" -> MeshProtos.HardwareModel.forNumber(128);
            default -> {
                try {
                    yield MeshProtos.HardwareModel.valueOf(modelName);
                } catch (IllegalArgumentException ignored) {
                    yield null;
                }
            }
        };
    }
}
