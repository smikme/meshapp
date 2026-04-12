package com.meshtastic.client.model;

import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Управление конфигурацией Meshtastic-устройства.
 * <p>
 * Хранит основные конфиги и модульные конфиги устройства.
 * Потокобезопасен через synchronized блоки.
 * <p>
 * Ответственность:
 * <ul>
 *   <li>Хранение ConfigProtos.Config</li>
 *   <li>Хранение ModuleConfigProtos.ModuleConfig</li>
 *   <li>Добавление и получение конфигов</li>
 * </ul>
 */
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    /** Основные конфиги устройства */
    private final List<ConfigProtos.Config> configs = Collections.synchronizedList(new ArrayList<>());

    /** Конфиги модулей устройства */
    private final List<ModuleConfigProtos.ModuleConfig> moduleConfigs = Collections.synchronizedList(new ArrayList<>());

    /**
     * Возвращает основные конфиги устройства.
     *
     * @return список ConfigProtos.Config
     */
    public List<ConfigProtos.Config> getConfigs() {
        return configs;
    }

    /**
     * Добавляет основной конфиг.
     *
     * @param config конфиг для добавления
     */
    public void addConfig(ConfigProtos.Config config) {
        configs.add(config);
    }

    /**
     * Возвращает конфиги модулей устройства.
     *
     * @return список ModuleConfigProtos.ModuleConfig
     */
    public List<ModuleConfigProtos.ModuleConfig> getModuleConfigs() {
        return moduleConfigs;
    }

    /**
     * Добавляет конфиг модуля.
     *
     * @param moduleConfig конфиг для добавления
     */
    public void addModuleConfig(ModuleConfigProtos.ModuleConfig moduleConfig) {
        moduleConfigs.add(moduleConfig);
    }
    /**
     * Очищает все конфиги.
     */
    public void clear() {
        configs.clear();
        moduleConfigs.clear();
    }
}