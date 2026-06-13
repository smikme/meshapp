package com.meshtastic.client.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfUpdateEnvironmentTest {

    @TempDir
    Path tempDir;

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
        assertEquals(SelfUpdateEnvironment.Layout.MANAGED, env.get().layout());
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

    @Test
    void infersMacAppBundleFromClasspath() throws Exception {
        Path app = tempDir.resolve("MeshApp.app");
        Path jar = app.resolve("Contents/app/lib/MeshApp.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");

        Properties properties = new Properties();
        properties.setProperty("os.name", "Mac OS X");
        properties.setProperty("user.home", tempDir.resolve("home").toString());
        properties.setProperty("java.class.path", jar.toString());
        properties.setProperty("jpackage.app-version", "2.1.20");

        var env = SelfUpdateEnvironment.from(Map.of(), properties);

        assertTrue(env.isPresent());
        assertEquals(app, env.get().root());
        assertEquals("2.1.20", env.get().version());
        assertEquals(app.toString(), env.get().launcher());
        assertEquals(SelfUpdateEnvironment.Layout.MAC_APP_BUNDLE, env.get().layout());
        assertEquals(
                tempDir.resolve("home/Library/Caches/MeshApp/self-update"),
                env.get().stagingDir()
        );
    }
}
