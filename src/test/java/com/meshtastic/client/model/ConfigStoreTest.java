package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class ConfigStoreTest {

    @Test
    void getConfigsReturnsEmptyListByDefault() {
        ConfigStore store = new ConfigStore();
        
        List<ConfigProtos.Config> configs = store.getConfigs();
        assertNotNull(configs);
        assertTrue(configs.isEmpty());
    }

    @Test
    void addConfigAddsConfigToList() {
        ConfigStore store = new ConfigStore();
        
        ConfigProtos.Config config = ConfigProtos.Config.getDefaultInstance();
        store.addConfig(config);
        
        List<ConfigProtos.Config> configs = store.getConfigs();
        assertEquals(1, configs.size());
        assertTrue(configs.contains(config));
    }

    @Test
    void addConfigMultipleConfigs() {
        ConfigStore store = new ConfigStore();
        
        ConfigProtos.Config config1 = ConfigProtos.Config.getDefaultInstance();
        ConfigProtos.Config config2 = ConfigProtos.Config.newBuilder().build();
        
        store.addConfig(config1);
        store.addConfig(config2);
        
        List<ConfigProtos.Config> configs = store.getConfigs();
        assertEquals(2, configs.size());
    }

    @Test
    void getModuleConfigsReturnsEmptyListByDefault() {
        ConfigStore store = new ConfigStore();
        
        List<ModuleConfigProtos.ModuleConfig> configs = store.getModuleConfigs();
        assertNotNull(configs);
        assertTrue(configs.isEmpty());
    }

    @Test
    void addModuleConfigAddsConfigToList() {
        ConfigStore store = new ConfigStore();
        
        ModuleConfigProtos.ModuleConfig moduleConfig = ModuleConfigProtos.ModuleConfig.getDefaultInstance();
        store.addModuleConfig(moduleConfig);
        
        List<ModuleConfigProtos.ModuleConfig> configs = store.getModuleConfigs();
        assertEquals(1, configs.size());
        assertTrue(configs.contains(moduleConfig));
    }

    @Test
    void addModuleConfigMultipleConfigs() {
        ConfigStore store = new ConfigStore();
        
        ModuleConfigProtos.ModuleConfig config1 = ModuleConfigProtos.ModuleConfig.getDefaultInstance();
        ModuleConfigProtos.ModuleConfig config2 = ModuleConfigProtos.ModuleConfig.newBuilder().build();
        
        store.addModuleConfig(config1);
        store.addModuleConfig(config2);
        
        List<ModuleConfigProtos.ModuleConfig> configs = store.getModuleConfigs();
        assertEquals(2, configs.size());
    }

    @Test
    void clearRemovesAllConfigsAndModuleConfigs() {
        ConfigStore store = new ConfigStore();
        
        store.addConfig(ConfigProtos.Config.getDefaultInstance());
        store.addModuleConfig(ModuleConfigProtos.ModuleConfig.getDefaultInstance());
        
        store.clear();
        
        assertTrue(store.getConfigs().isEmpty());
        assertTrue(store.getModuleConfigs().isEmpty());
    }

    @Test
    void getConfigsAndGetModuleConfigsAreIndependent() {
        ConfigStore store = new ConfigStore();
        
        store.addConfig(ConfigProtos.Config.getDefaultInstance());
        store.addModuleConfig(ModuleConfigProtos.ModuleConfig.getDefaultInstance());
        
        assertEquals(1, store.getConfigs().size());
        assertEquals(1, store.getModuleConfigs().size());
    }
}