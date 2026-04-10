package com.meshtastic.client.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Управляет session-логом приложения:
 * <ul>
 *     <li>при старте переносит неочищенный лог прошлого запуска в pending-каталог;</li>
 *     <li>во время работы дописывает все logback-события в активный файл;</li>
 *     <li>при штатном завершении помечает запуск корректным и очищает активный лог.</li>
 * </ul>
 */
public final class SessionCrashLogManager {

    static final String APP_DIR_NAME = ".meshapp";
    static final String LOG_DIR_NAME = "logs";
    static final String PENDING_DIR_NAME = "pending-crash-reports";
    static final String ACTIVE_LOG_NAME = "meshapp-session.log";
    static final String NORMAL_EXIT_MARKER_NAME = "meshapp-session.clean-exit";

    private static final Object LOCK = new Object();
    private static final DateTimeFormatter LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter PENDING_FILE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());

    private static volatile boolean shutdownHookInstalled;

    private static Path appDir = resolveAppDir();
    private static Path logsDir = appDir.resolve(LOG_DIR_NAME);
    private static Path pendingDir = logsDir.resolve(PENDING_DIR_NAME);
    private static Path activeLogPath = logsDir.resolve(ACTIVE_LOG_NAME);
    private static Path normalExitMarkerPath = logsDir.resolve(NORMAL_EXIT_MARKER_NAME);

    private static BufferedWriter writer;
    private static boolean prepared;

    private SessionCrashLogManager() {}

    public static void prepareForLaunch() {
        synchronized (LOCK) {
            refreshPaths();
            installShutdownHookIfNeeded();
            closeWriterQuietly();
            ensureDirectories();

            if (Files.exists(normalExitMarkerPath)) {
                deleteQuietly(activeLogPath);
                deleteQuietly(normalExitMarkerPath);
            } else if (Files.exists(activeLogPath)) {
                rotateActiveLogToPending();
            }

            prepared = true;
        }
    }

    public static void append(ILoggingEvent event) {
        synchronized (LOCK) {
            ensureReadyForAppend();

            try {
                if (writer == null) {
                    writer = Files.newBufferedWriter(
                            activeLogPath,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND
                    );
                }

                writer.write(renderEvent(event));
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                System.err.println("[MeshApp] Failed to append session log: " + e.getMessage());
            }
        }
    }

    public static void markNormalShutdown() {
        synchronized (LOCK) {
            refreshPaths();
            ensureDirectories();
            try {
                Files.writeString(
                        normalExitMarkerPath,
                        "ok",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (IOException e) {
                System.err.println("[MeshApp] Failed to mark normal shutdown: " + e.getMessage());
            }
        }
    }

    public static Optional<Path> peekPendingCrashLog() {
        synchronized (LOCK) {
            refreshPaths();
            if (!Files.isDirectory(pendingDir)) {
                return Optional.empty();
            }

            try (Stream<Path> files = Files.list(pendingDir)) {
                return files
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .findFirst();
            } catch (IOException e) {
                System.err.println("[MeshApp] Failed to inspect pending crash logs: " + e.getMessage());
                return Optional.empty();
            }
        }
    }

    public static void deletePendingCrashLog(Path logPath) {
        synchronized (LOCK) {
            if (logPath != null) {
                deleteQuietly(logPath);
            }
        }
    }

    static Path getActiveLogPath() {
        synchronized (LOCK) {
            refreshPaths();
            return activeLogPath;
        }
    }

    static Path getPendingDir() {
        synchronized (LOCK) {
            refreshPaths();
            return pendingDir;
        }
    }

    static Path getNormalExitMarkerPath() {
        synchronized (LOCK) {
            refreshPaths();
            return normalExitMarkerPath;
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            closeWriterQuietly();
            refreshPaths();
            prepared = false;
        }
    }

    private static void ensureReadyForAppend() {
        refreshPaths();
        installShutdownHookIfNeeded();
        ensureDirectories();
        prepared = true;
    }

    private static void installShutdownHookIfNeeded() {
        if (shutdownHookInstalled) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (LOCK) {
                closeWriterQuietly();
                refreshPaths();
                if (Files.exists(normalExitMarkerPath)) {
                    deleteQuietly(activeLogPath);
                }
            }
        }, "meshapp-session-log-shutdown"));
        shutdownHookInstalled = true;
    }

    private static void refreshPaths() {
        appDir = resolveAppDir();
        logsDir = appDir.resolve(LOG_DIR_NAME);
        pendingDir = logsDir.resolve(PENDING_DIR_NAME);
        activeLogPath = logsDir.resolve(ACTIVE_LOG_NAME);
        normalExitMarkerPath = logsDir.resolve(NORMAL_EXIT_MARKER_NAME);
    }

    private static Path resolveAppDir() {
        return Path.of(System.getProperty("user.home"), APP_DIR_NAME);
    }

    private static void ensureDirectories() {
        try {
            Files.createDirectories(pendingDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create MeshApp log directories", e);
        }
    }

    private static void rotateActiveLogToPending() {
        Path target = uniquePendingLogPath();
        try {
            Files.move(activeLogPath, target);
        } catch (IOException moveError) {
            try {
                Files.copy(activeLogPath, target);
                deleteQuietly(activeLogPath);
            } catch (IOException copyError) {
                throw new IllegalStateException("Failed to preserve previous crash log", copyError);
            }
        }
    }

    private static Path uniquePendingLogPath() {
        String baseName = "meshapp-crash-" + PENDING_FILE_FORMAT.format(Instant.now());
        Path candidate = pendingDir.resolve(baseName + ".log");
        int counter = 1;
        while (Files.exists(candidate)) {
            candidate = pendingDir.resolve(baseName + "-" + counter + ".log");
            counter++;
        }
        return candidate;
    }

    private static String renderEvent(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append(LOG_TIME_FORMAT.format(Instant.ofEpochMilli(event.getTimeStamp())))
                .append(' ')
                .append('[')
                .append(event.getThreadName())
                .append("] ")
                .append(String.format("%-5s", event.getLevel()))
                .append(' ')
                .append(event.getLoggerName())
                .append(" - ")
                .append(event.getFormattedMessage());

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            sb.append(System.lineSeparator())
                    .append(ThrowableProxyUtil.asString(throwable).stripTrailing());
        }

        return sb.toString();
    }

    private static void closeWriterQuietly() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            System.err.println("[MeshApp] Failed to close session log writer: " + e.getMessage());
        } finally {
            writer = null;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.err.println("[MeshApp] Failed to delete " + path + ": " + e.getMessage());
        }
    }
}
