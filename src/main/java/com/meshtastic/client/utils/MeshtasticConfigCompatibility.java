package com.meshtastic.client.utils;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.meshtastic.client.model.FirmwareCapabilities;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;

/**
 * Central firmware-version policy for the reflection-based config editor.
 */
public final class MeshtasticConfigCompatibility {

    private static final Set<String> LEGACY_TRAFFIC_FIELDS = Set.of(
            "enabled",
            "position_dedup_enabled",
            "position_precision_bits",
            "nodeinfo_direct_response",
            "rate_limit_enabled",
            "drop_unknown_enabled",
            "exhaust_hop_telemetry",
            "exhaust_hop_position",
            "router_preserve_hops");

    private MeshtasticConfigCompatibility() {}

    public record Context(
            FirmwareCapabilities capabilities,
            MeshProtos.LoRaRegionPresetMap regionPresetMap,
            int currentRegionValue) {

        public Context {
            capabilities = capabilities != null
                    ? capabilities
                    : FirmwareCapabilities.legacy();
        }

        public static Context legacy() {
            return new Context(FirmwareCapabilities.legacy(), null, 0);
        }
    }

    public static boolean includeSection(String sectionName, Context context) {
        return !"mesh_beacon".equals(sectionName)
                || context.capabilities().firmware28OrNewer();
    }

    public static boolean includeField(FieldDescriptor field, Context context) {
        if (field == null) {
            return false;
        }
        String fullName = field.getFullName();
        if ("meshtastic.Config.SecurityConfig.packet_signature_policy".equals(fullName)) {
            return context.capabilities().firmware28OrNewer()
                    && context.capabilities().xeddsa();
        }
        if (fullName.startsWith(
                "meshtastic.ModuleConfig.TrafficManagementConfig.")) {
            boolean legacyField = LEGACY_TRAFFIC_FIELDS.contains(field.getName());
            return context.capabilities().firmware28OrNewer()
                    ? !legacyField
                    : true;
        }
        if (fullName.startsWith(
                "meshtastic.ModuleConfig.MeshBeaconConfig.")) {
            return context.capabilities().firmware28OrNewer();
        }
        return true;
    }

    public static List<EnumValueDescriptor> allowedEnumValues(
            FieldDescriptor field,
            Object currentValue,
            Context context) {
        List<EnumValueDescriptor> values =
                new ArrayList<>(field.getEnumType().getValues());
        String enumName = field.getEnumType().getFullName();
        int currentNumber = currentValue instanceof EnumValueDescriptor descriptor
                ? descriptor.getNumber()
                : Integer.MIN_VALUE;

        if ("meshtastic.Config.LoRaConfig.RegionCode".equals(enumName)) {
            values.removeIf(value -> !allowedRegion(
                    value,
                    currentNumber,
                    context.capabilities().firmware28OrNewer()));
        } else if ("meshtastic.Config.LoRaConfig.ModemPreset".equals(enumName)) {
            values.removeIf(value ->
                    !context.capabilities().firmware28OrNewer()
                            && value.getNumber() >= 14);
            if (context.capabilities().firmware28OrNewer()
                    && "meshtastic.Config.LoRaConfig.modem_preset"
                            .equals(field.getFullName())) {
                Set<Integer> legal = legalPresetNumbers(
                        context.regionPresetMap(),
                        context.currentRegionValue());
                if (!legal.isEmpty()) {
                    values.removeIf(value ->
                            !legal.contains(value.getNumber())
                                    && value.getNumber() != currentNumber);
                }
            }
        }
        return values;
    }

    private static boolean allowedRegion(
            EnumValueDescriptor value,
            int currentNumber,
            boolean firmware28) {
        if (!firmware28) {
            return value.getNumber() <= 32
                    && !"ITU2_2M".equals(value.getName());
        }
        if ("ITU23_2M".equals(value.getName())) {
            return false;
        }
        if ("UA_868".equals(value.getName())
                && value.getNumber() != currentNumber) {
            return false;
        }
        return true;
    }

    public static Set<Integer> legalPresetNumbers(
            MeshProtos.LoRaRegionPresetMap presetMap,
            int regionValue) {
        if (presetMap == null) {
            return Set.of();
        }
        for (MeshProtos.LoRaRegionPresets region :
                presetMap.getRegionGroupsList()) {
            if (region.getRegionValue() != regionValue) {
                continue;
            }
            int groupIndex = region.getGroupIndex();
            if (groupIndex < 0 || groupIndex >= presetMap.getGroupsCount()) {
                return Set.of();
            }
            Set<Integer> result = new HashSet<>();
            for (int preset :
                    presetMap.getGroups(groupIndex).getPresetsValueList()) {
                result.add(preset);
            }
            return Set.copyOf(result);
        }
        return Set.of();
    }

    public static MeshProtos.LoRaPresetGroup presetGroup(
            MeshProtos.LoRaRegionPresetMap presetMap,
            int regionValue) {
        if (presetMap == null) {
            return null;
        }
        for (MeshProtos.LoRaRegionPresets region :
                presetMap.getRegionGroupsList()) {
            if (region.getRegionValue() == regionValue
                    && region.getGroupIndex() >= 0
                    && region.getGroupIndex() < presetMap.getGroupsCount()) {
                return presetMap.getGroups(region.getGroupIndex());
            }
        }
        return null;
    }

    public static int currentRegionValue(
            List<ConfigProtos.Config> configs) {
        if (configs == null) {
            return ConfigProtos.Config.LoRaConfig.RegionCode.UNSET_VALUE;
        }
        return configs.stream()
                .filter(config ->
                        config.getPayloadVariantCase()
                                == ConfigProtos.Config.PayloadVariantCase.LORA)
                .findFirst()
                .map(config -> config.getLora().getRegionValue())
                .orElse(ConfigProtos.Config.LoRaConfig.RegionCode.UNSET_VALUE);
    }
}
