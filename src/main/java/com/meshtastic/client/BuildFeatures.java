package com.meshtastic.client;

import java.util.Properties;

public final class BuildFeatures {

    private static final String VERSION_PROPERTIES = "/version.properties";
    private static final String MESH_APP_IDE_ENABLED = "meshAppIdeEnabled";

    private static final Properties PROPERTIES = loadProperties();

    private BuildFeatures() {}

    public static boolean isMeshAppIdeEnabled() {
        return Boolean.parseBoolean(PROPERTIES.getProperty(MESH_APP_IDE_ENABLED, "false"));
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (var input = BuildFeatures.class.getResourceAsStream(VERSION_PROPERTIES)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (Exception ignored) {
            // Missing build metadata means optional build features stay disabled.
        }
        return properties;
    }
}
