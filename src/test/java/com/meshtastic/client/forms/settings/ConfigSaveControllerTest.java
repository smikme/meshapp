package com.meshtastic.client.forms.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

class ConfigSaveControllerTest {

    @Test
    void firstExcludedModuleNameDetectsStoreForwardMetadataBit() {
        MeshProtos.DeviceMetadata metadata = MeshProtos.DeviceMetadata.newBuilder()
            .setExcludedModules(MeshProtos.ExcludedModules.STOREFORWARD_CONFIG_VALUE)
            .build();
        ModuleConfigProtos.ModuleConfig storeForward =
            ModuleConfigProtos.ModuleConfig.newBuilder()
                .setStoreForward(
                    ModuleConfigProtos.ModuleConfig.StoreForwardConfig
                        .newBuilder()
                        .setEnabled(true)
                        .build()
                )
                .build();

        assertEquals(
            Optional.of("Store & Forward"),
            ConfigSaveController.firstExcludedModuleName(
                metadata,
                List.of(storeForward)
            )
        );
    }

    @Test
    void firstExcludedModuleNameIgnoresUnsetOrUnrelatedMetadataBits() {
        ModuleConfigProtos.ModuleConfig storeForward =
            ModuleConfigProtos.ModuleConfig.newBuilder()
                .setStoreForward(
                    ModuleConfigProtos.ModuleConfig.StoreForwardConfig
                        .newBuilder()
                        .setEnabled(true)
                        .build()
                )
                .build();
        MeshProtos.DeviceMetadata mqttExcluded = MeshProtos.DeviceMetadata.newBuilder()
            .setExcludedModules(MeshProtos.ExcludedModules.MQTT_CONFIG_VALUE)
            .build();

        assertTrue(ConfigSaveController
            .firstExcludedModuleName(null, List.of(storeForward))
            .isEmpty());
        assertTrue(ConfigSaveController
            .firstExcludedModuleName(mqttExcluded, List.of(storeForward))
            .isEmpty());
    }
}
