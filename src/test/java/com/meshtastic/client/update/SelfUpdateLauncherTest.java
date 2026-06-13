package com.meshtastic.client.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfUpdateLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsPackagedMacAppAndPlansManagedPayloadRoot() throws Exception {
        Path app = tempDir.resolve("MeshApp.app");
        Path packagedAppDir = app.resolve("Contents/app");
        Path jar = packagedAppDir.resolve("MeshApp.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");

        Properties properties = new Properties();
        properties.setProperty("os.name", "Mac OS X");
        properties.setProperty("user.home", tempDir.resolve("home").toString());
        properties.setProperty("java.class.path", jar.toString());
        properties.setProperty("jpackage.app-version", "2.1.21");

        var plan = SelfUpdateLauncher.plan(
                Map.of(),
                properties,
                new SelfUpdateLauncher.VersionInfo("v2.1.21", 2121)
        );

        assertTrue(plan.isPresent());
        assertEquals(
                tempDir.resolve("home/Library/Application Support/MeshApp"),
                plan.get().root()
        );
        assertEquals(
                tempDir.resolve("home/Library/Caches/MeshApp/self-update"),
                plan.get().stagingDir()
        );
        assertEquals(packagedAppDir, plan.get().packagedAppDir());
        assertEquals(app.toString(), plan.get().launcher());
    }

    @Test
    void skipsPayloadLauncherInsideFlatpak() throws Exception {
        Path jar = tempDir.resolve("app/MeshApp.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");

        Properties properties = new Properties();
        properties.setProperty("os.name", "Linux");
        properties.setProperty("user.home", tempDir.resolve("home").toString());
        properties.setProperty("java.class.path", jar.toString());
        properties.setProperty("jpackage.app-version", "2.1.21");

        var plan = SelfUpdateLauncher.plan(
                Map.of("FLATPAK_ID", "app.privatepractice.meshapp"),
                properties,
                new SelfUpdateLauncher.VersionInfo("v2.1.21", 2121)
        );

        assertFalse(plan.isPresent());
    }

    @Test
    void bootstrapsPackagedPayloadWithNativeFiles() throws Exception {
        Path packagedAppDir = tempDir.resolve("package-app");
        Files.createDirectories(packagedAppDir.resolve("fx"));
        Files.writeString(packagedAppDir.resolve("MeshApp.jar"), "jar");
        Files.writeString(packagedAppDir.resolve("fx/javafx-controls.jar"), "fx");
        Files.writeString(packagedAppDir.resolve("meshapp-ble.dll"), "dll");
        Files.writeString(packagedAppDir.resolve("libmeshapp-ble.so"), "so");

        var plan = new SelfUpdateLauncher.PayloadPlan(
                tempDir.resolve("root"),
                tempDir.resolve("staging"),
                packagedAppDir,
                "v2.1.21",
                2121,
                "MeshApp"
        );

        String current = SelfUpdateLauncher.ensurePayload(plan);

        assertEquals("v2.1.21", current);
        assertEquals("v2.1.21", Files.readString(plan.currentFile()).trim());
        assertEquals("jar", Files.readString(plan.versionDir(current).resolve("lib/MeshApp.jar")));
        assertEquals("fx", Files.readString(plan.versionDir(current).resolve("lib/fx/javafx-controls.jar")));
        assertEquals("dll", Files.readString(plan.versionDir(current).resolve("lib/meshapp-ble.dll")));
        assertEquals("so", Files.readString(plan.versionDir(current).resolve("lib/libmeshapp-ble.so")));
    }

    @Test
    void bundledPayloadWinsWhenNativePackageIsNewerThanCurrentPayload() throws Exception {
        Path packagedAppDir = tempDir.resolve("package-app");
        Files.createDirectories(packagedAppDir);
        Files.writeString(packagedAppDir.resolve("MeshApp.jar"), "bundled");

        var plan = new SelfUpdateLauncher.PayloadPlan(
                tempDir.resolve("root"),
                tempDir.resolve("staging"),
                packagedAppDir,
                "v2.1.21",
                2121,
                "MeshApp"
        );

        Files.createDirectories(plan.versionDir("v2.1.20").resolve("lib"));
        writeVersionJar(plan.versionDir("v2.1.20").resolve("lib/MeshApp-v2.1.20.jar"), "v2.1.20", 2120);
        SelfUpdateInstaller.writeCurrent(plan.currentFile(), "v2.1.20");

        String current = SelfUpdateLauncher.ensurePayload(plan);

        assertEquals("v2.1.21", current);
        assertEquals("v2.1.21", Files.readString(plan.currentFile()).trim());
        assertEquals("bundled", Files.readString(plan.versionDir(current).resolve("lib/MeshApp.jar")));
    }

    @Test
    void newerSelfUpdatedPayloadWinsOverOlderNativePackage() throws Exception {
        Path packagedAppDir = tempDir.resolve("package-app");
        Files.createDirectories(packagedAppDir);
        Files.writeString(packagedAppDir.resolve("MeshApp.jar"), "bundled");

        var plan = new SelfUpdateLauncher.PayloadPlan(
                tempDir.resolve("root"),
                tempDir.resolve("staging"),
                packagedAppDir,
                "v2.1.21",
                2121,
                "MeshApp"
        );

        Files.createDirectories(plan.versionDir("v2.1.22").resolve("lib"));
        writeVersionJar(plan.versionDir("v2.1.22").resolve("lib/MeshApp-v2.1.22.jar"), "v2.1.22", 2122);
        SelfUpdateInstaller.writeCurrent(plan.currentFile(), "v2.1.22");

        String current = SelfUpdateLauncher.ensurePayload(plan);

        assertEquals("v2.1.22", current);
        assertEquals("v2.1.22", Files.readString(plan.currentFile()).trim());
    }

    private static void writeVersionJar(Path jar, String version, int versionCode) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("version.properties"));
            zip.write(("version=" + version + "\nversionCode=" + versionCode + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
