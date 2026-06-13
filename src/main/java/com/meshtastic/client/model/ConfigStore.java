package com.meshtastic.client.model;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.Message;
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
        if (replaceConfig(config)) {
            return;
        }
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
        if (replaceModuleConfig(moduleConfig)) {
            return;
        }
        moduleConfigs.add(moduleConfig);
    }

    private boolean replaceConfig(ConfigProtos.Config config) {
        int variantNumber = activeOneofFieldNumber(config);
        if (variantNumber < 0) {
            return false;
        }
        synchronized (configs) {
            for (int i = 0; i < configs.size(); i++) {
                if (activeOneofFieldNumber(configs.get(i)) == variantNumber) {
                    configs.set(i, config);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean replaceModuleConfig(
        ModuleConfigProtos.ModuleConfig moduleConfig
    ) {
        int variantNumber = activeOneofFieldNumber(moduleConfig);
        if (variantNumber < 0) {
            return false;
        }
        synchronized (moduleConfigs) {
            for (int i = 0; i < moduleConfigs.size(); i++) {
                if (
                    activeOneofFieldNumber(moduleConfigs.get(i)) == variantNumber
                ) {
                    moduleConfigs.set(i, moduleConfig);
                    return true;
                }
            }
        }
        return false;
    }

    private static int activeOneofFieldNumber(Message message) {
        if (message == null) {
            return -1;
        }
        OneofDescriptor oneof = message
            .getDescriptorForType()
            .getOneofs()
            .stream()
            .filter(candidate -> "payload_variant".equals(candidate.getName()))
            .findFirst()
            .orElse(null);
        if (oneof == null) {
            return -1;
        }
        FieldDescriptor field = message.getOneofFieldDescriptor(oneof);
        return field != null ? field.getNumber() : -1;
    }

    /**
     * Clears all stored configuration sections.
     */
    public void clear() {
        configs.clear();
        moduleConfigs.clear();
    }
}
