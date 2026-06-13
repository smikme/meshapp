package com.meshtastic.client.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfUpdateInstallerTest {

    @TempDir
    Path tempDir;

    @Test
    void appliesArchiveIntoVersionDirectoryAndSwitchesCurrent() throws Exception {
        Path root = tempDir.resolve("MeshApp");
        Path archive = tempDir.resolve("update.zip");
        writeZip(archive, Map.of(
                "bin/MeshApp", "launcher",
                "lib/MeshApp.jar", "jar"
        ));

        new SelfUpdateInstaller().apply(new SelfUpdateInstaller.Request(
                root,
                archive,
                "2147",
                SelfUpdateInstaller.sha256(archive),
                0,
                null,
                SelfUpdateEnvironment.Layout.MANAGED
        ));

        assertTrue(Files.isRegularFile(root.resolve("versions/2147/bin/MeshApp")));
        assertEquals("jar", Files.readString(root.resolve("versions/2147/lib/MeshApp.jar")));
        assertEquals("2147", Files.readString(root.resolve("current")).trim());
    }

    @Test
    void replacesMacAppBundleArchive() throws Exception {
        Path targetApp = tempDir.resolve("Applications/MeshApp.app");
        Files.createDirectories(targetApp.resolve("Contents/app/lib"));
        Files.writeString(targetApp.resolve("Contents/app/lib/old.jar"), "old");

        Path sourceApp = tempDir.resolve("source/MeshApp.app");
        Files.createDirectories(sourceApp.resolve("Contents/MacOS"));
        Files.createDirectories(sourceApp.resolve("Contents/app/lib"));
        Files.writeString(sourceApp.resolve("Contents/MacOS/MeshApp"), "launcher");
        Files.writeString(sourceApp.resolve("Contents/app/lib/new.jar"), "new");

        Path archive = tempDir.resolve("update-app.zip");
        writeZipFromDirectory(archive, sourceApp.getParent());

        new SelfUpdateInstaller().apply(new SelfUpdateInstaller.Request(
                targetApp,
                archive,
                "2148",
                SelfUpdateInstaller.sha256(archive),
                0,
                null,
                SelfUpdateEnvironment.Layout.MAC_APP_BUNDLE
        ));

        assertTrue(Files.isRegularFile(targetApp.resolve("Contents/MacOS/MeshApp")));
        assertEquals("new", Files.readString(targetApp.resolve("Contents/app/lib/new.jar")));
        assertTrue(Files.notExists(targetApp.resolve("Contents/app/lib/old.jar")));
    }

    @Test
    void rejectsZipSlipEntry() throws Exception {
        Path archive = tempDir.resolve("unsafe.zip");
        writeZip(archive, Map.of("../outside.txt", "bad"));

        IOException thrown = assertThrows(
                IOException.class,
                () -> SelfUpdateInstaller.extractZip(archive, tempDir.resolve("target"))
        );
        assertTrue(thrown.getMessage().contains("Unsafe archive entry"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void restoresExecutableBitForUnixLaunchers() throws Exception {
        Path archive = tempDir.resolve("launcher.zip");
        Path target = tempDir.resolve("target");
        writeZip(archive, Map.of("bin/MeshApp", "#!/bin/sh\n"));

        SelfUpdateInstaller.extractZip(archive, target);

        assertTrue(Files.isExecutable(target.resolve("bin/MeshApp")));
    }

    private static void writeZip(Path archive, Map<String, String> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static void writeZipFromDirectory(Path archive, Path sourceDir) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive));
             var stream = Files.walk(sourceDir)) {
            for (Path path : stream.toList()) {
                if (Files.isDirectory(path)) {
                    continue;
                }
                String name = sourceDir.relativize(path).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(name));
                zip.write(Files.readAllBytes(path));
                zip.closeEntry();
            }
        }
    }
}
