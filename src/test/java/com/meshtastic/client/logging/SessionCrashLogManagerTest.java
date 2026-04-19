package com.meshtastic.client.logging;

import com.meshtastic.client.TestEnvironmentSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCrashLogManagerTest {

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        SessionCrashLogManager.resetForTests();
    }

    @AfterEach
    void tearDown() {
        SessionCrashLogManager.resetForTests();
    }

    @Test
    void prepareForLaunchMovesUnexpectedSessionLogToPendingDirectory() throws Exception {
        Path activeLog = SessionCrashLogManager.getActiveLogPath();
        Files.createDirectories(activeLog.getParent());
        Files.writeString(activeLog, "unexpected shutdown");

        SessionCrashLogManager.prepareForLaunch();

        assertFalse(Files.exists(activeLog));
        try (Stream<Path> files = Files.list(SessionCrashLogManager.getPendingDir())) {
            List<Path> pending = files.filter(Files::isRegularFile).toList();
            if (pending.isEmpty()) {
                try (Stream<Path> dirs = Files.list(SessionCrashLogManager.getPendingDir())) {
                    List<Path> pendingDirs = dirs.filter(Files::isDirectory).toList();
                    assertEquals(1, pendingDirs.size());
                    assertEquals("unexpected shutdown",
                            Files.readString(pendingDirs.getFirst().resolve(SessionCrashLogManager.ACTIVE_LOG_NAME)));
                }
            } else {
                assertEquals(1, pending.size());
                assertEquals("unexpected shutdown", Files.readString(pending.getFirst()));
            }
        }
    }

    @Test
    void prepareForLaunchClearsNormalExitMarkerWithoutOpeningCrashFlow() throws Exception {
        Path activeLog = SessionCrashLogManager.getActiveLogPath();
        Files.createDirectories(activeLog.getParent());
        Files.writeString(activeLog, "normal shutdown residue");
        Files.writeString(SessionCrashLogManager.getNormalExitMarkerPath(), "ok");

        SessionCrashLogManager.prepareForLaunch();

        assertFalse(Files.exists(activeLog));
        assertFalse(Files.exists(SessionCrashLogManager.getNormalExitMarkerPath()));
        assertTrue(SessionCrashLogManager.peekPendingCrashLog().isEmpty());
    }

    @Test
    void createReportLogSnapshotCopiesActiveBundleWithoutDeletingIt() throws Exception {
        Path activeLog = SessionCrashLogManager.getActiveLogPath();
        Files.createDirectories(activeLog.getParent());
        Files.writeString(activeLog, "current session log");

        Path snapshot = SessionCrashLogManager.createReportLogSnapshot();
        try {
            assertTrue(Files.exists(activeLog));
            assertTrue(Files.isDirectory(snapshot));
            assertEquals("current session log", Files.readString(activeLog));
            assertEquals("current session log", Files.readString(snapshot.resolve(SessionCrashLogManager.ACTIVE_LOG_NAME)));
            assertTrue(Files.exists(snapshot.resolve("thread-dump-manual-report.txt")));
        } finally {
            try (Stream<Path> files = Files.walk(snapshot)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // best effort cleanup for tests
                    }
                });
            }
        }
    }

    @Test
    void prepareForLaunchPrunesOldPendingBundles() throws Exception {
        Path pendingDir = SessionCrashLogManager.getPendingDir();
        Files.createDirectories(pendingDir);
        for (int i = 0; i < 5; i++) {
            Path bundle = pendingDir.resolve("bundle-" + i);
            Files.createDirectories(bundle);
            Files.writeString(bundle.resolve("meshapp-session.log"), "bundle-" + i);
            Files.setLastModifiedTime(bundle, FileTime.from(Instant.now().minusSeconds(300L - i)));
        }

        SessionCrashLogManager.prepareForLaunch();

        try (Stream<Path> files = Files.list(SessionCrashLogManager.getPendingDir())) {
            assertTrue(files.count() <= 3, "pending diagnostics should be capped");
        }
    }
}
