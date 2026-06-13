package com.meshtastic.client.update;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfUpdateEnvironmentTest {

    @Test
    void readsManagedLayoutFromEnvironment() {
        Properties properties = new Properties();

        var env = SelfUpdateEnvironment.from(
                Map.of(
                        SelfUpdateEnvironment.ENV_ROOT, "MeshAppRoot",
                        SelfUpdateEnvironment.ENV_VERSION, "2147",
                        SelfUpdateEnvironment.ENV_LAUNCHER, "MeshApp/bin/MeshApp"
                ),
                properties
        );

        assertTrue(env.isPresent());
        assertEquals("MeshAppRoot", env.get().root().toString());
        assertEquals("2147", env.get().version());
        assertEquals("MeshApp/bin/MeshApp", env.get().launcher());
    }

    @Test
    void systemPropertiesOverrideEnvironment() {
        Properties properties = new Properties();
        properties.setProperty(SelfUpdateEnvironment.PROP_ROOT, "PropMeshApp");
        properties.setProperty(SelfUpdateEnvironment.PROP_VERSION, "prop-version");

        var env = SelfUpdateEnvironment.from(
                Map.of(
                        SelfUpdateEnvironment.ENV_ROOT, "EnvMeshApp",
                        SelfUpdateEnvironment.ENV_VERSION, "env-version"
                ),
                properties
        );

        assertTrue(env.isPresent());
        assertEquals("PropMeshApp", env.get().root().toString());
        assertEquals("prop-version", env.get().version());
    }
}
