package com.meshtastic.client.logging;

import com.meshtastic.client.TestEnvironmentSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
        Path activeLog = tempHome
                .resolve(SessionCrashLogManager.APP_DIR_NAME)
                .resolve(SessionCrashLogManager.LOG_DIR_NAME)
                .resolve(SessionCrashLogManager.ACTIVE_LOG_NAME);
        Files.createDirectories(activeLog.getParent());
        Files.writeString(activeLog, "unexpected shutdown");

        SessionCrashLogManager.prepareForLaunch();

        assertFalse(Files.exists(activeLog));
        try (Stream<Path> files = Files.list(SessionCrashLogManager.getPendingDir())) {
            List<Path> pending = files.filter(Files::isRegularFile).toList();
            assertEquals(1, pending.size());
            assertEquals("unexpected shutdown", Files.readString(pending.getFirst()));
        }
    }

    @Test
    void prepareForLaunchClearsNormalExitMarkerWithoutOpeningCrashFlow() throws Exception {
        Path activeLog = tempHome
                .resolve(SessionCrashLogManager.APP_DIR_NAME)
                .resolve(SessionCrashLogManager.LOG_DIR_NAME)
                .resolve(SessionCrashLogManager.ACTIVE_LOG_NAME);
        Files.createDirectories(activeLog.getParent());
        Files.writeString(activeLog, "normal shutdown residue");
        Files.writeString(SessionCrashLogManager.getNormalExitMarkerPath(), "ok");

        SessionCrashLogManager.prepareForLaunch();

        assertFalse(Files.exists(activeLog));
        assertFalse(Files.exists(SessionCrashLogManager.getNormalExitMarkerPath()));
        assertTrue(SessionCrashLogManager.peekPendingCrashLog().isEmpty());
    }
}
