package com.meshtastic.client.forms.settings;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.meshtastic.client.model.DeviceState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

class ConfigCompatibilityValidatorTest {

    @Test
    void legacyFirmwareRejectsFirmware28OnlyConfiguration() {
        DeviceState state = state("2.7.27", false);
        ModuleConfigProtos.ModuleConfig meshBeacon =
                ModuleConfigProtos.ModuleConfig.newBuilder()
                        .setMeshBeacon(
                                ModuleConfigProtos.ModuleConfig.MeshBeaconConfig
                                        .getDefaultInstance())
                        .build();

        assertTrue(ConfigCompatibilityValidator.validate(
                state,
                changes(false, List.of(), List.of(meshBeacon)),
                List.of()).isPresent());
    }

    @Test
    void firmware28LongNameLimitUsesUtf8Bytes() {
        DeviceState state = state("2.8.0", true);
        ConfigChangeSet changes = new ConfigChangeSet(
                true,
                "Тестовое имя ноды",
                "NODE",
                false,
                false,
                0,
                0,
                0,
                false,
                "",
                List.of(),
                List.of(),
                List.of());

        assertTrue(ConfigCompatibilityValidator.validate(
                state,
                changes,
                List.of()).isPresent());
    }

    @Test
    void firmware28UsesFirmwareRegionPresetAndLicenseMap() {
        DeviceState state = state("2.8.0", true);
        state.setRegionPresetMap(
                MeshProtos.LoRaRegionPresetMap.newBuilder()
                        .addGroups(MeshProtos.LoRaPresetGroup.newBuilder()
                                .addPresets(
                                        ConfigProtos.Config.LoRaConfig.ModemPreset.LONG_FAST)
                                .setDefaultPreset(
                                        ConfigProtos.Config.LoRaConfig.ModemPreset.LONG_FAST)
                                .setLicensedOnly(true)
                                .build())
                        .addRegionGroups(MeshProtos.LoRaRegionPresets.newBuilder()
                                .setRegion(
                                        ConfigProtos.Config.LoRaConfig.RegionCode.US)
                                .setGroupIndex(0)
                                .build())
                        .build());
        ConfigProtos.Config illegalPreset = lora(
                ConfigProtos.Config.LoRaConfig.RegionCode.US,
                ConfigProtos.Config.LoRaConfig.ModemPreset.SHORT_FAST);
        ConfigProtos.Config legalPreset = lora(
                ConfigProtos.Config.LoRaConfig.RegionCode.US,
                ConfigProtos.Config.LoRaConfig.ModemPreset.LONG_FAST);

        assertTrue(ConfigCompatibilityValidator.validate(
                state,
                changes(false, List.of(illegalPreset), List.of()),
                List.of()).isPresent());
        assertTrue(ConfigCompatibilityValidator.validate(
                state,
                changes(false, List.of(legalPreset), List.of()),
                List.of()).isPresent());
    }

    private static DeviceState state(String version, boolean hasXeddsa) {
        DeviceState state = new DeviceState();
        state.setDeviceMetadata(MeshProtos.DeviceMetadata.newBuilder()
                .setFirmwareVersion(version)
                .setHasXeddsa(hasXeddsa)
                .build());
        return state;
    }

    private static ConfigChangeSet changes(
            boolean licensed,
            List<ConfigProtos.Config> configs,
            List<ModuleConfigProtos.ModuleConfig> moduleConfigs) {
        return new ConfigChangeSet(
                false,
                null,
                null,
                licensed,
                false,
                0,
                0,
                0,
                false,
                "",
                configs,
                moduleConfigs,
                List.of());
    }

    private static ConfigProtos.Config lora(
            ConfigProtos.Config.LoRaConfig.RegionCode region,
            ConfigProtos.Config.LoRaConfig.ModemPreset preset) {
        return ConfigProtos.Config.newBuilder()
                .setLora(ConfigProtos.Config.LoRaConfig.newBuilder()
                        .setRegion(region)
                        .setUsePreset(true)
                        .setModemPreset(preset)
                        .build())
                .build();
    }
}
