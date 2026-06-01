package com.meshtastic.client.model;

import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe store for Meshtastic device configuration.
 * <p>
 * Keeps both core device config sections and module config sections received
 * from the radio.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ConfigStore {

    /** Core device configuration sections. */
    private final List<ConfigProtos.Config> configs = Collections.synchronizedList(new ArrayList<>());

    /** Module configuration sections. */
    private final List<ModuleConfigProtos.ModuleConfig> moduleConfigs = Collections.synchronizedList(new ArrayList<>());

    /**
     * Returns core device configuration sections.
     *
     * @return list of {@code ConfigProtos.Config}
     */
    public List<ConfigProtos.Config> getConfigs() {
        return configs;
    }

    /**
     * Adds a core device configuration section.
     *
     * @param config config to add
     */
    public void addConfig(ConfigProtos.Config config) {
        configs.add(config);
    }

    /**
     * Returns module configuration sections.
     *
     * @return list of {@code ModuleConfigProtos.ModuleConfig}
     */
    public List<ModuleConfigProtos.ModuleConfig> getModuleConfigs() {
        return moduleConfigs;
    }

    /**
     * Adds a module configuration section.
     *
     * @param moduleConfig module config to add
     */
    public void addModuleConfig(ModuleConfigProtos.ModuleConfig moduleConfig) {
        moduleConfigs.add(moduleConfig);
    }
    /**
     * Clears all stored configuration sections.
     */
    public void clear() {
        configs.clear();
        moduleConfigs.clear();
    }
}
