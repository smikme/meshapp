package com.meshtastic.client;

import com.meshtastic.client.update.SelfUpdateEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppVersionTest {

    @TempDir
    Path tempDir;

    @Test
    void managedPayloadVersionOverridesEmbeddedApplicationVersion() throws Exception {
        Path root = tempDir.resolve("MeshApp");
        Path lib = root.resolve("versions/v2.1.20/lib");
        Files.createDirectories(lib);
        writeVersionJar(lib.resolve("MeshApp-v2.1.20.jar"), "v2.1.20", 698);
        Properties properties = new Properties();

        AppVersion.Info info = AppVersion.resolve(
                Map.of(
                        SelfUpdateEnvironment.ENV_ROOT, root.toString(),
                        SelfUpdateEnvironment.ENV_VERSION, "v2.1.20"
                ),
                properties,
                AppVersionTest.class
        );

        assertEquals("v2.1.20", info.version());
        assertEquals(698, info.versionCode());
    }

    @Test
    void managedPayloadFallsBackToEnvironmentVersionWhenJarMetadataIsUnavailable() {
        Properties properties = new Properties();

        AppVersion.Info info = AppVersion.resolve(
                Map.of(
                        SelfUpdateEnvironment.ENV_ROOT, tempDir.resolve("MeshApp").toString(),
                        SelfUpdateEnvironment.ENV_VERSION, "v2.1.20"
                ),
                properties,
                AppVersionTest.class
        );

        assertEquals("v2.1.20", info.version());
        assertEquals(0, info.versionCode());
    }

    @Test
    void resolvesBuildIdentityFromManagedPayload() throws Exception {
        String version = "v2.4.0+build.126";
        Path root = tempDir.resolve("MeshApp");
        Path lib = root.resolve("versions").resolve(version).resolve("lib");
        Files.createDirectories(lib);
        writeVersionJar(lib.resolve("MeshApp.jar"), version, 2004000);
        var info = AppVersion.resolve(Map.of(SelfUpdateEnvironment.ENV_ROOT, root.toString(),
                SelfUpdateEnvironment.ENV_VERSION, version), new Properties(), AppVersionTest.class);
        assertEquals(version, info.version());
        assertEquals(126, info.buildNumber());
        assertEquals(2004000, info.versionCode());
    }

    private static void writeVersionJar(Path jar, String version, int versionCode) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("version.properties"));
            zip.write(("version=" + version + "\nversionCode=" + versionCode + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
