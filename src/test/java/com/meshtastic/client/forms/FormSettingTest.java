package com.meshtastic.client.forms;

import com.meshtastic.client.model.ConnectionType;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormSettingTest {

    @Test
    void shouldUseImplicitBleModuleSaveOnlyForSingleBleMqttSection() {
        ModuleConfigProtos.ModuleConfig mqttConfig = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setMqtt(ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder().setEnabled(false).build())
                .build();

        assertTrue(FormSetting.shouldUseImplicitBleModuleSave(
                ConnectionType.BLE, false, false, List.of(), List.of(mqttConfig)));
    }

    @Test
    void shouldKeepTransactionalSaveForOtherCases() {
        ModuleConfigProtos.ModuleConfig serialConfig = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setSerial(ModuleConfigProtos.ModuleConfig.SerialConfig.newBuilder().setEnabled(true).build())
                .build();
        ConfigProtos.Config deviceConfig = ConfigProtos.Config.newBuilder()
                .setDevice(ConfigProtos.Config.DeviceConfig.newBuilder().build())
                .build();

        assertFalse(FormSetting.shouldUseImplicitBleModuleSave(
                ConnectionType.TCP, false, false, List.of(), List.of(serialConfig)));
        assertFalse(FormSetting.shouldUseImplicitBleModuleSave(
                ConnectionType.BLE, true, false, List.of(), List.of(serialConfig)));
        assertFalse(FormSetting.shouldUseImplicitBleModuleSave(
                ConnectionType.BLE, false, false, List.of(deviceConfig), List.of()));
        assertFalse(FormSetting.shouldUseImplicitBleModuleSave(
                ConnectionType.BLE, false, false, List.of(), List.of(serialConfig)));
    }
}
