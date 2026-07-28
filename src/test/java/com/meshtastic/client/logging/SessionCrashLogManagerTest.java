package com.meshtastic.client.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.meshtastic.client.TestEnvironmentSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.attribute.FileTime;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class SessionCrashLogManagerTest {

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        System.clearProperty(SessionCrashLogManager.DISABLED_PROPERTY);
        SessionCrashLogManager.resetForTests();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SessionCrashLogManager.DISABLED_PROPERTY);
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
                    assertTrue(Files.readString(pendingDirs.getFirst().resolve(SessionCrashLogManager.FATAL_MARKER_FILE_NAME))
                            .contains("\"type\": \"abnormal-exit\""));
                }
            } else {
                assertEquals(1, pending.size());
                assertEquals("unexpected shutdown", Files.readString(pending.getFirst()));
            }
        }
    }

    @Test
    void prepareForLaunchDropsInterruptedSessionAfterSystemReboot() throws Exception {
        Path activeBundle = SessionCrashLogManager.getActiveBundleDir();
        Files.createDirectories(activeBundle);
        Files.writeString(activeBundle.resolve(SessionCrashLogManager.ACTIVE_LOG_NAME), "session before reboot");
        Files.writeString(activeBundle.resolve(SessionCrashLogManager.SESSION_STATE_FILE_NAME),
                "{\"bootId\":\"boot-before\"}");
        SessionCrashLogManager.setBootIdentityForTests("boot-after");

        SessionCrashLogManager.prepareForLaunch();

        assertTrue(SessionCrashLogManager.peekPendingCrashLog().isEmpty());
        assertFalse(Files.exists(activeBundle.resolve(SessionCrashLogManager.ACTIVE_LOG_NAME)));
    }

    @Test
    void prepareForLaunchKeepsFatalCrashReportEvenAfterSystemReboot() throws Exception {
        Path activeBundle = SessionCrashLogManager.getActiveBundleDir();
        Files.createDirectories(activeBundle);
        Files.writeString(activeBundle.resolve(SessionCrashLogManager.ACTIVE_LOG_NAME), "fatal session");
        Files.writeString(activeBundle.resolve(SessionCrashLogManager.SESSION_STATE_FILE_NAME),
                "{\"bootId\":\"boot-before\"}");
        Files.writeString(activeBundle.resolve(SessionCrashLogManager.FATAL_MARKER_FILE_NAME),
                "{\"type\":\"uncaught-exception\",\"exceptionClass\":\"java.lang.OutOfMemoryError\"}");
        SessionCrashLogManager.setBootIdentityForTests("boot-after");

        SessionCrashLogManager.prepareForLaunch();

        assertTrue(SessionCrashLogManager.peekPendingCrashLog().isPresent());
    }

    @Test
    void appendKeepsFormattedMessageOnSingleLogLine() throws Exception {
        SessionCrashLogManager.prepareForLaunch();
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test");
        LoggingEvent event = new LoggingEvent(
                SessionCrashLogManagerTest.class.getName(),
                logger,
                Level.DEBUG,
                "native error: line one\r\nline two\r\n",
                null,
                null
        );

        SessionCrashLogManager.append(event);

        List<String> lines = Files.readAllLines(SessionCrashLogManager.getActiveLogPath());
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().endsWith("native error: line one\\nline two"));
    }

    @Test
    void disabledSessionLogDoesNotTouchActiveBundleOnAppend() throws Exception {
        Path activeLog = SessionCrashLogManager.getActiveLogPath();
        Files.createDirectories(activeLog.getParent());
        Files.writeString(activeLog, "parent session");
        System.setProperty(SessionCrashLogManager.DISABLED_PROPERTY, "true");

        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test");
        LoggingEvent event = new LoggingEvent(
                SessionCrashLogManagerTest.class.getName(),
                logger,
                Level.INFO,
                "helper log",
                null,
                null
        );

        SessionCrashLogManager.append(event);

        assertEquals("parent session", Files.readString(activeLog));
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

    @Test
    void prepareForLaunchKeepsHeapDumpBundleEvenWhenItExceedsPendingSizeLimit() throws Exception {
        Path pendingDir = SessionCrashLogManager.getPendingDir();
        Files.createDirectories(pendingDir);
        Path bundle = pendingDir.resolve("heap-dump-bundle");
        Files.createDirectories(bundle);
        Files.writeString(bundle.resolve(SessionCrashLogManager.ACTIVE_LOG_NAME), "oom session");
        createSparseFile(
                bundle.resolve("heapdump_pid123.hprof"),
                65L * 1024L * 1024L
        );

        SessionCrashLogManager.prepareForLaunch();

        assertTrue(Files.exists(bundle), "heap dump crash bundle must not be pruned before it can be reported");
        assertTrue(Files.exists(bundle.resolve("heapdump_pid123.hprof")));
    }

    private static void createSparseFile(Path file, long sizeBytes) throws Exception {
        try (SeekableByteChannel channel = Files.newByteChannel(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        )) {
            channel.position(sizeBytes - 1);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
    }
}
