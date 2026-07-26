package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.utils.MeshtasticConfigCompatibility;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

/**
 * Final compatibility guard shared by local and remote config save flows.
 */
public final class ConfigCompatibilityValidator {

    private static final int FIRMWARE_28_LONG_NAME_BYTES = 24;

    private ConfigCompatibilityValidator() {}

    public static Optional<String> validateOwnerName(
            DeviceState state,
            String longName) {
        if (state != null
                && state.getFirmwareCapabilities().firmware28OrNewer()
                && utf8Length(longName) > FIRMWARE_28_LONG_NAME_BYTES) {
            return Optional.of(I18n.t(
                    "settings.config.compatibility.longName28",
                    Integer.toString(FIRMWARE_28_LONG_NAME_BYTES)));
        }
        return Optional.empty();
    }

    public static Optional<String> validate(
            DeviceState state,
            ConfigChangeSet changes,
            List<ConfigProtos.Config> originalConfigs) {
        if (state == null || changes == null) {
            return Optional.of(I18n.t("settings.config.compatibility.unknown"));
        }

        boolean firmware28 =
                state.getFirmwareCapabilities().firmware28OrNewer();
        if (changes.ownerModified()) {
            Optional<String> ownerError =
                    validateOwnerName(state, changes.longName());
            if (ownerError.isPresent()) {
                return ownerError;
            }
        }

        Optional<ConfigProtos.Config.LoRaConfig> changedLoRa =
                changes.configs().stream()
                        .filter(config ->
                                config.getPayloadVariantCase()
                                        == ConfigProtos.Config.PayloadVariantCase.LORA)
                        .map(ConfigProtos.Config::getLora)
                        .findFirst();
        ConfigProtos.Config.LoRaConfig effectiveLoRa = changedLoRa
                .orElseGet(() -> findLoRa(originalConfigs).orElse(null));

        if (!firmware28) {
            if (changes.moduleConfigs().stream().anyMatch(config ->
                    config.getPayloadVariantCase()
                            == ModuleConfigProtos.ModuleConfig.PayloadVariantCase.MESH_BEACON)) {
                return Optional.of(I18n.t(
                        "settings.config.compatibility.requires28"));
            }
            if (effectiveLoRa != null
                    && changedLoRa.isPresent()
                    && (effectiveLoRa.getRegionValue() >= 33
                            || effectiveLoRa.getModemPresetValue() >= 14)) {
                return Optional.of(I18n.t(
                        "settings.config.compatibility.requires28"));
            }
            if (changes.configs().stream().anyMatch(
                    ConfigCompatibilityValidator::hasNonLegacySignaturePolicy)) {
                return Optional.of(I18n.t(
                        "settings.config.compatibility.requires28"));
            }
            return Optional.empty();
        }

        if (changes.configs().stream().anyMatch(
                ConfigCompatibilityValidator::hasNonLegacySignaturePolicy)
                && !state.getFirmwareCapabilities().xeddsa()) {
            return Optional.of(I18n.t(
                    "settings.config.compatibility.xeddsaUnavailable"));
        }

        if (changedLoRa.isPresent()) {
            ConfigProtos.Config.LoRaConfig originalLoRa =
                    findLoRa(originalConfigs).orElse(null);
            if (effectiveLoRa.getRegionValue() == 15
                    && (originalLoRa == null
                            || originalLoRa.getRegionValue() != 15)) {
                return Optional.of(I18n.t(
                        "settings.config.compatibility.ua868Deprecated"));
            }

            MeshProtos.LoRaPresetGroup group =
                    MeshtasticConfigCompatibility.presetGroup(
                            state.getRegionPresetMap(),
                            effectiveLoRa.getRegionValue());
            if (group != null) {
                Set<Integer> legal =
                        MeshtasticConfigCompatibility.legalPresetNumbers(
                                state.getRegionPresetMap(),
                                effectiveLoRa.getRegionValue());
                if (!legal.contains(effectiveLoRa.getModemPresetValue())) {
                    return Optional.of(I18n.t(
                            "settings.config.compatibility.illegalPreset",
                            effectiveLoRa.getModemPreset().name(),
                            effectiveLoRa.getRegion().name()));
                }
                if (group.getLicensedOnly() && !changes.isLicensed()) {
                    return Optional.of(I18n.t(
                            "settings.config.compatibility.licenseRequired"));
                }
            }
        }

        for (ModuleConfigProtos.ModuleConfig moduleConfig :
                changes.moduleConfigs()) {
            if (moduleConfig.getPayloadVariantCase()
                    != ModuleConfigProtos.ModuleConfig.PayloadVariantCase.MESH_BEACON) {
                continue;
            }
            ModuleConfigProtos.ModuleConfig.MeshBeaconConfig beacon =
                    moduleConfig.getMeshBeacon();
            if (utf8Length(beacon.getBroadcastMessage()) > 100) {
                return Optional.of(I18n.t(
                        "settings.config.compatibility.beaconMessage"));
            }
            if (beacon.getBroadcastIntervalSecs() != 0
                    && beacon.getBroadcastIntervalSecs() < 3600) {
                return Optional.of(I18n.t(
                        "settings.config.compatibility.beaconInterval"));
            }
            if (beacon.getBroadcastTargetsList().stream()
                    .filter(target -> target.hasChannelIndex())
                    .anyMatch(target -> target.getChannelIndex() > 7)) {
                return Optional.of(I18n.t(
                        "settings.config.compatibility.beaconChannel"));
            }
        }
        return Optional.empty();
    }

    private static Optional<ConfigProtos.Config.LoRaConfig> findLoRa(
            List<ConfigProtos.Config> configs) {
        if (configs == null) {
            return Optional.empty();
        }
        return configs.stream()
                .filter(config ->
                        config.getPayloadVariantCase()
                                == ConfigProtos.Config.PayloadVariantCase.LORA)
                .map(ConfigProtos.Config::getLora)
                .findFirst();
    }

    private static boolean hasNonLegacySignaturePolicy(
            ConfigProtos.Config config) {
        return config.getPayloadVariantCase()
                        == ConfigProtos.Config.PayloadVariantCase.SECURITY
                && config.getSecurity().getPacketSignaturePolicyValue() != 0;
    }

    private static int utf8Length(String value) {
        return value == null
                ? 0
                : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
