package com.meshtastic.client.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    void reportsInstallProgressAndReadyToRestart() throws Exception {
        Path root = tempDir.resolve("MeshApp");
        Path archive = tempDir.resolve("update.zip");
        writeZip(archive, Map.of(
                "bin/MeshApp", "launcher",
                "lib/MeshApp.jar", "jar"
        ));
        List<Double> progressEvents = new ArrayList<>();
        boolean[] ready = {false};

        new SelfUpdateInstaller().apply(
                new SelfUpdateInstaller.Request(
                        root,
                        archive,
                        "v2.1.20",
                        SelfUpdateInstaller.sha256(archive),
                        0,
                        null,
                        SelfUpdateEnvironment.Layout.MANAGED
                ),
                new SelfUpdateInstaller.ProgressListener() {
                    @Override
                    public void onInstallProgress(double progress, long completedBytes, long totalBytes) {
                        progressEvents.add(progress);
                    }

                    @Override
                    public void onReadyToRestart() {
                        ready[0] = true;
                    }
                }
        );

        assertTrue(ready[0]);
        assertTrue(progressEvents.size() >= 4);
        assertEquals(0, progressEvents.getFirst());
        assertEquals(1, progressEvents.getLast());
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

    @Test
    void relaunchCommandStartsInstalledManagedPayloadDirectly() throws Exception {
        Path root = tempDir.resolve("MeshApp");
        Path target = root.resolve("versions/v2.1.20");
        Files.createDirectories(target.resolve("lib/fx"));
        Files.writeString(target.resolve("lib/MeshApp-v2.1.20.jar"), "jar");
        Files.writeString(target.resolve("lib/fx/javafx-controls.jar"), "fx");

        var request = new SelfUpdateInstaller.Request(
                root,
                tempDir.resolve("staging/update.zip"),
                "v2.1.20",
                "sha256",
                0,
                "/Applications/MeshApp.app",
                SelfUpdateEnvironment.Layout.MANAGED
        );

        var command = SelfUpdateInstaller.managedPayloadCommand(request, target);

        assertTrue(command.getFirst().endsWith("java") || command.getFirst().endsWith("java.exe"));
        assertTrue(command.contains("--module-path"));
        assertTrue(command.contains(target.resolve("lib/fx").toAbsolutePath().toString()));
        assertTrue(command.contains("-Dmeshapp.update.root=" + root.toAbsolutePath()));
        assertTrue(command.contains("-Dmeshapp.update.version=v2.1.20"));
        assertTrue(command.contains("-Dmeshapp.update.launcher=/Applications/MeshApp.app"));
        assertTrue(command.contains("-DjSerialComm.library.path=" + target.resolve("lib").toAbsolutePath()));
        assertTrue(command.contains(target.resolve("lib/MeshApp-v2.1.20.jar").toAbsolutePath().toString()));
        assertEquals("com.meshtastic.client.MeshAppLauncher", command.get(command.size() - 1));
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

}
