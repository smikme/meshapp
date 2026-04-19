package com.meshtastic.client.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Управляет crash-bundle текущей сессии:
 * <ul>
 *     <li>ведёт ограниченный по размеру session.log;</li>
 *     <li>сохраняет metadata/breadcrumbs/thread dumps в активный каталог диагностики;</li>
 *     <li>при следующем запуске переносит нештатно завершившийся bundle в pending-каталог;</li>
 *     <li>чистит старые bundle по возрасту, количеству и суммарному объёму.</li>
 * </ul>
 */
public final class SessionCrashLogManager {

    static final String APP_DIR_NAME = ".meshapp";
    static final String LOG_DIR_NAME = "logs";
    static final String DIAGNOSTICS_DIR_NAME = "diagnostics";
    static final String PENDING_DIR_NAME = "pending-crash-reports";
    static final String ACTIVE_BUNDLE_DIR_NAME = "active";
    static final String ACTIVE_LOG_NAME = "meshapp-session.log";
    static final String NORMAL_EXIT_MARKER_NAME = "meshapp-session.clean-exit";
    static final String METADATA_FILE_NAME = "meta.json";
    static final String BREADCRUMBS_FILE_NAME = "breadcrumbs.json";
    static final String THREAD_DUMPS_FILE_NAME = "thread-dumps.txt";
    static final String FATAL_MARKER_FILE_NAME = "fatal-marker.json";
    static final String JFR_DUMP_FILE_NAME = "last.jfr";

    private static final Object LOCK = new Object();
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final DateTimeFormatter LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter JSON_TIME_FORMAT =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private static final long MAX_SESSION_LOG_BYTES = 10L * 1024 * 1024;
    private static final long SESSION_LOG_TRIM_TO_BYTES = 8L * 1024 * 1024;
    private static final long MAX_THREAD_DUMPS_BYTES = 2L * 1024 * 1024;
    private static final int MAX_BREADCRUMBS = 200;
    private static final int MAX_PENDING_BUNDLES = 3;
    private static final long MAX_PENDING_BYTES = 64L * 1024 * 1024;
    private static final Duration MAX_PENDING_AGE = Duration.ofDays(14);

    private static final List<String> BREADCRUMB_LOGGER_PREFIXES = List.of(
            "com.meshtastic.client.MeshApp",
            "com.meshtastic.client.service.ConnectionManager",
            "com.meshtastic.client.service.ConfigExchangeService",
            "com.meshtastic.client.components.CrashReportFlow",
            "com.meshtastic.client.service.UpdateCheckService",
            "com.meshtastic.client.service.DatabaseProvider",
            "com.meshtastic.client.service.DatabaseMigrator"
    );

    private static volatile boolean shutdownHookInstalled;

    private static Path appDir = resolveAppDir();
    private static Path logsDir = appDir.resolve(LOG_DIR_NAME);
    private static Path diagnosticsDir = logsDir.resolve(DIAGNOSTICS_DIR_NAME);
    private static Path pendingDir = logsDir.resolve(PENDING_DIR_NAME);
    private static Path activeBundleDir = diagnosticsDir.resolve(ACTIVE_BUNDLE_DIR_NAME);
    private static Path activeLogPath = activeBundleDir.resolve(ACTIVE_LOG_NAME);
    private static Path metadataPath = activeBundleDir.resolve(METADATA_FILE_NAME);
    private static Path breadcrumbsPath = activeBundleDir.resolve(BREADCRUMBS_FILE_NAME);
    private static Path threadDumpsPath = activeBundleDir.resolve(THREAD_DUMPS_FILE_NAME);
    private static Path fatalMarkerPath = activeBundleDir.resolve(FATAL_MARKER_FILE_NAME);
    private static Path normalExitMarkerPath = logsDir.resolve(NORMAL_EXIT_MARKER_NAME);
    private static Path legacyActiveLogPath = logsDir.resolve(ACTIVE_LOG_NAME);

    private static BufferedWriter writer;
    private static boolean prepared;
    private static String sessionId = "unknown";
    private static final Deque<Map<String, Object>> breadcrumbs = new ArrayDeque<>();
    private static final Map<String, Object> metadata = new LinkedHashMap<>();

    private SessionCrashLogManager() {}

    public static void prepareForLaunch() {
        synchronized (LOCK) {
            refreshPaths();
            installShutdownHookIfNeeded();
            closeWriterQuietly();
            ensureDirectories();

            if (Files.exists(normalExitMarkerPath)) {
                deleteRecursivelyQuietly(activeBundleDir);
                deleteQuietly(legacyActiveLogPath);
                deleteQuietly(normalExitMarkerPath);
            } else {
                if (bundleHasPayload(activeBundleDir)) {
                    rotateActiveBundleToPending();
                }
                if (Files.exists(legacyActiveLogPath)) {
                    rotateLegacyActiveLogToPending();
                }
            }

            prunePendingBundles();
            prepareFreshActiveBundle();
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
                trimFileIfNeeded(activeLogPath, MAX_SESSION_LOG_BYTES, SESSION_LOG_TRIM_TO_BYTES,
                        "[MeshApp] session log truncated to stay within diagnostic size limits");

                if (shouldRecordBreadcrumb(event)) {
                    addBreadcrumbLocked(buildBreadcrumb(event));
                }
            } catch (IOException e) {
                System.err.println("[MeshApp] Failed to append session log: " + e.getMessage());
            }
        }
    }

    public static void markNormalShutdown() {
        synchronized (LOCK) {
            refreshPaths();
            ensureDirectories();
            recordBreadcrumbLocked("lifecycle", "Normal shutdown requested", "INFO");
            putMetadataValueLocked("session.shutdownRequestedAt", nowString());
            persistStateFilesLocked();
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
                        .filter(Files::exists)
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
                deleteRecursivelyQuietly(logPath);
            }
        }
    }

    public static Path createReportLogSnapshot() throws IOException {
        Path snapshotDir;
        synchronized (LOCK) {
            refreshPaths();
            ensureDirectories();
            flushWriterQuietly();
            persistStateFilesLocked();
            snapshotDir = Files.createTempDirectory("meshapp-session-report-");
            copyDirectoryContents(activeBundleDir, snapshotDir);
        }

        writeThreadDump(snapshotDir.resolve("thread-dump-manual-report.txt"), "manual-report");
        JfrDiagnosticSupport.dumpSnapshot(snapshotDir.resolve("manual-report.jfr"));
        return snapshotDir;
    }

    public static void recordStartupContext(String applicationVersion, int versionCode) {
        synchronized (LOCK) {
            ensureReadyForAppend();
            putMetadataValueLocked("app.version", safeText(applicationVersion));
            putMetadataValueLocked("app.versionCode", versionCode);
            putMetadataValueLocked("app.startedAt", nowString());
            recordBreadcrumbLocked("lifecycle",
                    "Application startup: version=" + safeText(applicationVersion) + ", build=" + versionCode,
                    "INFO");
        }
    }

    public static void recordUiReady() {
        synchronized (LOCK) {
            ensureReadyForAppend();
            putMetadataValueLocked("ui.stageShownAt", nowString());
            recordBreadcrumbLocked("ui", "Primary stage shown", "INFO");
        }
    }

    public static void updateConnectionMetadata(ConnectionEntry entry, DeviceState deviceState, String stateLabel) {
        if (entry == null) {
            return;
        }
        synchronized (LOCK) {
            ensureReadyForAppend();
            Map<String, Object> connection = ensureObjectMapLocked(metadata, "connection");
            connection.put("status", safeText(stateLabel));
            connection.put("updatedAt", nowString());
            connection.put("id", safeText(entry.getId()));
            connection.put("name", safeText(entry.getName()));
            ConnectionType type = entry.getEffectiveType();
            connection.put("type", type.name());
            connection.put("params", describeConnection(entry));
            if (entry.getNodeId() != null && !entry.getNodeId().isBlank()) {
                connection.put("savedNodeId", entry.getNodeId().trim());
            }

            if (deviceState != null) {
                NodeData node = resolveLocalNode(deviceState);
                connection.put("nodeId", resolveLocalNodeId(deviceState));
                connection.put("nodeName", resolveLocalNodeName(deviceState));
                if (node != null && node.getShortName() != null && !node.getShortName().isBlank()) {
                    connection.put("nodeShortName", node.getShortName().trim());
                }
                if (node != null && node.getHwModel() != null && !node.getHwModel().isBlank()) {
                    connection.put("hwModel", node.getHwModel().trim());
                }
                if (deviceState.getDeviceMetadata() != null
                        && deviceState.getDeviceMetadata().getFirmwareVersion() != null
                        && !deviceState.getDeviceMetadata().getFirmwareVersion().isBlank()) {
                    connection.put("firmwareVersion", deviceState.getDeviceMetadata().getFirmwareVersion().trim());
                }
            }

            recordBreadcrumbLocked(
                    "connection",
                    "Connection metadata updated: state=" + safeText(stateLabel)
                            + ", name=" + safeText(entry.getName())
                            + ", type=" + type.name(),
                    "INFO"
            );
        }
    }

    public static void recordConnectionDisconnected(ConnectionEntry entry, String reason) {
        if (entry == null) {
            return;
        }
        synchronized (LOCK) {
            ensureReadyForAppend();
            Map<String, Object> connection = ensureObjectMapLocked(metadata, "connection");
            connection.put("status", "disconnected");
            connection.put("updatedAt", nowString());
            connection.put("disconnectReason", safeText(reason));
            connection.put("id", safeText(entry.getId()));
            connection.put("name", safeText(entry.getName()));
            connection.put("type", entry.getEffectiveType().name());
            recordBreadcrumbLocked(
                    "connection",
                    "Connection disconnected: name=" + safeText(entry.getName())
                            + ", reason=" + safeText(reason),
                    "WARN"
            );
        }
    }

    public static void captureUncaughtException(Thread thread, Throwable throwable) {
        Path jfrPath;
        synchronized (LOCK) {
            ensureReadyForAppend();
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("type", "uncaught-exception");
            marker.put("capturedAt", nowString());
            marker.put("thread", thread != null ? safeText(thread.getName()) : "unknown");
            if (throwable != null) {
                marker.put("exceptionClass", throwable.getClass().getName());
                marker.put("message", safeText(throwable.getMessage()));
            }
            writeJsonQuietly(fatalMarkerPath, marker);
            recordBreadcrumbLocked(
                    "fatal",
                    "Uncaught exception in thread '"
                            + (thread != null ? safeText(thread.getName()) : "unknown")
                            + "'",
                    "ERROR"
            );
            writeThreadDump(threadDumpsPath, "uncaught-exception");
            jfrPath = activeBundleDir.resolve(JFR_DUMP_FILE_NAME);
        }
        JfrDiagnosticSupport.dumpSnapshot(jfrPath);
    }

    public static void captureUiFreezeDiagnostic(Duration stallDuration) {
        Path jfrPath;
        synchronized (LOCK) {
            ensureReadyForAppend();
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("type", "ui-stall");
            marker.put("capturedAt", nowString());
            marker.put("stallMillis", stallDuration != null ? stallDuration.toMillis() : -1L);
            writeJsonQuietly(fatalMarkerPath, marker);
            recordBreadcrumbLocked(
                    "ui",
                    "JavaFX thread stall detected: "
                            + (stallDuration != null ? stallDuration.toMillis() + " ms" : "unknown duration"),
                    "ERROR"
            );
            writeThreadDump(threadDumpsPath, "ui-stall");
            jfrPath = activeBundleDir.resolve(JFR_DUMP_FILE_NAME);
        }
        JfrDiagnosticSupport.dumpSnapshot(jfrPath);
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

    static Path getActiveBundleDir() {
        synchronized (LOCK) {
            refreshPaths();
            return activeBundleDir;
        }
    }

    static Path getMetadataPath() {
        synchronized (LOCK) {
            refreshPaths();
            return metadataPath;
        }
    }

    static Path getBreadcrumbsPath() {
        synchronized (LOCK) {
            refreshPaths();
            return breadcrumbsPath;
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            closeWriterQuietly();
            refreshPaths();
            prepared = false;
            breadcrumbs.clear();
            metadata.clear();
            sessionId = "unknown";
        }
    }

    private static void ensureReadyForAppend() {
        refreshPaths();
        installShutdownHookIfNeeded();
        ensureDirectories();
        if (!prepared) {
            prepareFreshActiveBundle();
        }
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
                    deleteRecursivelyQuietly(activeBundleDir);
                    deleteQuietly(legacyActiveLogPath);
                }
            }
        }, "meshapp-session-log-shutdown"));
        shutdownHookInstalled = true;
    }

    private static void refreshPaths() {
        appDir = resolveAppDir();
        logsDir = appDir.resolve(LOG_DIR_NAME);
        diagnosticsDir = logsDir.resolve(DIAGNOSTICS_DIR_NAME);
        pendingDir = logsDir.resolve(PENDING_DIR_NAME);
        activeBundleDir = diagnosticsDir.resolve(ACTIVE_BUNDLE_DIR_NAME);
        activeLogPath = activeBundleDir.resolve(ACTIVE_LOG_NAME);
        metadataPath = activeBundleDir.resolve(METADATA_FILE_NAME);
        breadcrumbsPath = activeBundleDir.resolve(BREADCRUMBS_FILE_NAME);
        threadDumpsPath = activeBundleDir.resolve(THREAD_DUMPS_FILE_NAME);
        fatalMarkerPath = activeBundleDir.resolve(FATAL_MARKER_FILE_NAME);
        normalExitMarkerPath = logsDir.resolve(NORMAL_EXIT_MARKER_NAME);
        legacyActiveLogPath = logsDir.resolve(ACTIVE_LOG_NAME);
    }

    private static Path resolveAppDir() {
        return Path.of(System.getProperty("user.home"), APP_DIR_NAME);
    }

    private static void ensureDirectories() {
        try {
            Files.createDirectories(activeBundleDir);
            Files.createDirectories(pendingDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create MeshApp log directories", e);
        }
    }

    private static void prepareFreshActiveBundle() {
        deleteRecursivelyQuietly(activeBundleDir);
        try {
            Files.createDirectories(activeBundleDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create active diagnostics bundle", e);
        }

        breadcrumbs.clear();
        metadata.clear();

        sessionId = FILE_TIME_FORMAT.format(Instant.now()) + "-pid" + ProcessHandle.current().pid();
        putMetadataValueLocked("bundleFormat", 2);
        putMetadataValueLocked("session.id", sessionId);
        putMetadataValueLocked("session.startedAt", nowString());
        putMetadataValueLocked("session.pid", ProcessHandle.current().pid());
        putMetadataValueLocked("environment.osName", System.getProperty("os.name", "unknown").trim());
        putMetadataValueLocked("environment.osVersion", System.getProperty("os.version", "unknown").trim());
        putMetadataValueLocked("environment.osArch", System.getProperty("os.arch", "unknown").trim());
        putMetadataValueLocked("environment.javaVersion", System.getProperty("java.version", "unknown").trim());
        putMetadataValueLocked("environment.javaVendor", System.getProperty("java.vendor", "unknown").trim());
        putMetadataValueLocked("environment.javaVmName", System.getProperty("java.vm.name", "unknown").trim());
        putMetadataValueLocked("environment.javaVmVersion", System.getProperty("java.vm.version", "unknown").trim());
        putMetadataValueLocked("environment.userHome", System.getProperty("user.home", "unknown").trim());
        recordBreadcrumbLocked("lifecycle", "Diagnostics bundle initialized", "INFO");
    }

    private static boolean bundleHasPayload(Path bundleDir) {
        if (!Files.isDirectory(bundleDir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(bundleDir)) {
            return files.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    private static void rotateActiveBundleToPending() {
        Path target = uniquePendingBundlePath();
        movePathRecursively(activeBundleDir, target);
    }

    private static void rotateLegacyActiveLogToPending() {
        Path target = uniquePendingFilePath();
        try {
            Files.move(legacyActiveLogPath, target);
        } catch (IOException moveError) {
            try {
                Files.copy(legacyActiveLogPath, target, StandardCopyOption.REPLACE_EXISTING);
                deleteQuietly(legacyActiveLogPath);
            } catch (IOException copyError) {
                throw new IllegalStateException("Failed to preserve previous legacy crash log", copyError);
            }
        }
    }

    private static Path uniquePendingBundlePath() {
        String baseName = "meshapp-crash-" + FILE_TIME_FORMAT.format(Instant.now());
        Path candidate = pendingDir.resolve(baseName);
        int counter = 1;
        while (Files.exists(candidate)) {
            candidate = pendingDir.resolve(baseName + "-" + counter);
            counter++;
        }
        return candidate;
    }

    private static Path uniquePendingFilePath() {
        String baseName = "meshapp-crash-" + FILE_TIME_FORMAT.format(Instant.now());
        Path candidate = pendingDir.resolve(baseName + ".log");
        int counter = 1;
        while (Files.exists(candidate)) {
            candidate = pendingDir.resolve(baseName + "-" + counter + ".log");
            counter++;
        }
        return candidate;
    }

    private static void prunePendingBundles() {
        if (!Files.isDirectory(pendingDir)) {
            return;
        }

        List<Path> pendingEntries;
        try (Stream<Path> files = Files.list(pendingDir)) {
            pendingEntries = files
                    .filter(Files::exists)
                    .sorted(Comparator.comparing(SessionCrashLogManager::safeLastModifiedMillis))
                    .toList();
        } catch (IOException e) {
            System.err.println("[MeshApp] Failed to list pending diagnostics: " + e.getMessage());
            return;
        }

        long now = System.currentTimeMillis();
        for (Path path : pendingEntries) {
            long ageMillis = now - safeLastModifiedMillis(path);
            if (ageMillis > MAX_PENDING_AGE.toMillis()) {
                deleteRecursivelyQuietly(path);
            }
        }

        pendingEntries = listPendingEntriesOrdered();
        while (pendingEntries.size() > MAX_PENDING_BUNDLES) {
            deleteRecursivelyQuietly(pendingEntries.remove(0));
        }

        pendingEntries = listPendingEntriesOrdered();
        long totalBytes = pendingEntries.stream().mapToLong(SessionCrashLogManager::computePathSize).sum();
        while (totalBytes > MAX_PENDING_BYTES && !pendingEntries.isEmpty()) {
            Path oldest = pendingEntries.remove(0);
            totalBytes -= computePathSize(oldest);
            deleteRecursivelyQuietly(oldest);
        }
    }

    private static List<Path> listPendingEntriesOrdered() {
        if (!Files.isDirectory(pendingDir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> files = Files.list(pendingDir)) {
            return new ArrayList<>(files
                    .filter(Files::exists)
                    .sorted(Comparator.comparing(SessionCrashLogManager::safeLastModifiedMillis))
                    .toList());
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static long safeLastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long computePathSize(Path path) {
        if (!Files.exists(path)) {
            return 0L;
        }
        try {
            if (Files.isRegularFile(path)) {
                return Files.size(path);
            }
            try (Stream<Path> files = Files.walk(path)) {
                return files
                        .filter(Files::isRegularFile)
                        .mapToLong(file -> {
                            try {
                                return Files.size(file);
                            } catch (IOException e) {
                                return 0L;
                            }
                        })
                        .sum();
            }
        } catch (IOException e) {
            return 0L;
        }
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

    private static Map<String, Object> buildBreadcrumb(ILoggingEvent event) {
        Map<String, Object> breadcrumb = new LinkedHashMap<>();
        breadcrumb.put("time", jsonTime(event.getTimeStamp()));
        breadcrumb.put("level", event.getLevel().toString());
        breadcrumb.put("thread", safeText(event.getThreadName()));
        breadcrumb.put("logger", safeText(event.getLoggerName()));
        breadcrumb.put("message", safeText(event.getFormattedMessage()));
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            breadcrumb.put("throwable", safeText(throwable.getClassName()));
        }
        return breadcrumb;
    }

    private static boolean shouldRecordBreadcrumb(ILoggingEvent event) {
        if (event == null || event.getLevel() == null) {
            return false;
        }
        if (event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)) {
            return true;
        }
        String loggerName = event.getLoggerName();
        if (loggerName == null) {
            return false;
        }
        for (String prefix : BREADCRUMB_LOGGER_PREFIXES) {
            if (loggerName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void addBreadcrumbLocked(Map<String, Object> breadcrumb) {
        breadcrumbs.addLast(breadcrumb);
        while (breadcrumbs.size() > MAX_BREADCRUMBS) {
            breadcrumbs.removeFirst();
        }
        persistStateFilesLocked();
    }

    private static void recordBreadcrumbLocked(String category, String message, String level) {
        Map<String, Object> breadcrumb = new LinkedHashMap<>();
        breadcrumb.put("time", nowString());
        breadcrumb.put("level", safeText(level));
        breadcrumb.put("category", safeText(category));
        breadcrumb.put("message", safeText(message));
        addBreadcrumbLocked(breadcrumb);
    }

    private static void persistStateFilesLocked() {
        putMetadataValueLocked("session.lastUpdatedAt", nowString());
        writeJsonQuietly(metadataPath, metadata);
        writeJsonQuietly(breadcrumbsPath, new ArrayList<>(breadcrumbs));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ensureObjectMapLocked(Map<String, Object> root, String key) {
        Object existing = root.get(key);
        if (existing instanceof Map<?, ?> existingMap) {
            return (Map<String, Object>) existingMap;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        root.put(key, created);
        return created;
    }

    private static void putMetadataValueLocked(String dottedKey, Object value) {
        String[] parts = dottedKey.split("\\.");
        Map<String, Object> current = metadata;
        for (int i = 0; i < parts.length - 1; i++) {
            current = ensureObjectMapLocked(current, parts[i]);
        }
        current.put(parts[parts.length - 1], value);
    }

    private static NodeData resolveLocalNode(DeviceState deviceState) {
        if (deviceState == null || deviceState.getMyNodeNum() == 0) {
            return null;
        }
        return deviceState.getNodeDb().get(deviceState.getMyNodeNum());
    }

    private static String resolveLocalNodeId(DeviceState deviceState) {
        NodeData node = resolveLocalNode(deviceState);
        if (node != null && node.getNodeId() != null && !node.getNodeId().isBlank()) {
            return node.getNodeId().trim();
        }
        int myNodeNum = deviceState != null ? deviceState.getMyNodeNum() : 0;
        return myNodeNum != 0 ? String.format("!%08x", myNodeNum) : "?";
    }

    private static String resolveLocalNodeName(DeviceState deviceState) {
        NodeData node = resolveLocalNode(deviceState);
        if (node == null) {
            return resolveLocalNodeId(deviceState);
        }
        if (node.getLongName() != null && !node.getLongName().isBlank()) {
            return node.getLongName().trim();
        }
        if (node.getShortName() != null && !node.getShortName().isBlank()) {
            return node.getShortName().trim();
        }
        return resolveLocalNodeId(deviceState);
    }

    private static String describeConnection(ConnectionEntry entry) {
        ConnectionType type = entry.getEffectiveType();
        return switch (type) {
            case TCP -> String.format("type=TCP, host=%s, port=%d",
                    safeText(entry.getHost()), entry.getPort());
            case SERIAL -> String.format("type=SERIAL, port=%s, baud=%d",
                    safeText(entry.getPortName()), entry.getBaudRate());
            case BLE -> String.format("type=BLE, address=%s, device=%s",
                    safeText(entry.getBleAddress()), safeText(entry.getBleDeviceName()));
        };
    }

    private static void writeThreadDump(Path targetPath, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== Thread dump =====").append(System.lineSeparator());
        sb.append("time: ").append(nowString()).append(System.lineSeparator());
        sb.append("reason: ").append(safeText(reason)).append(System.lineSeparator());

        long[] deadlocked = THREAD_MX_BEAN.findDeadlockedThreads();
        if (deadlocked != null && deadlocked.length > 0) {
            sb.append("deadlockedThreads: ").append(deadlocked.length).append(System.lineSeparator());
        } else {
            sb.append("deadlockedThreads: 0").append(System.lineSeparator());
        }
        sb.append(System.lineSeparator());

        ThreadInfo[] infos = THREAD_MX_BEAN.dumpAllThreads(true, true);
        for (ThreadInfo info : infos) {
            appendThreadInfo(sb, info);
            sb.append(System.lineSeparator());
        }

        try {
            Files.writeString(
                    targetPath,
                    sb.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            trimFileIfNeeded(targetPath, MAX_THREAD_DUMPS_BYTES, MAX_THREAD_DUMPS_BYTES * 3 / 4,
                    "[MeshApp] thread dump log truncated to stay within diagnostic size limits");
        } catch (IOException e) {
            System.err.println("[MeshApp] Failed to write thread dump: " + e.getMessage());
        }
    }

    private static void appendThreadInfo(StringBuilder sb, ThreadInfo info) {
        sb.append('"').append(info.getThreadName()).append('"')
                .append(" Id=").append(info.getThreadId())
                .append(" State=").append(info.getThreadState());
        if (info.getLockName() != null) {
            sb.append(" on ").append(info.getLockName());
        }
        if (info.getLockOwnerName() != null) {
            sb.append(" owned by ").append(info.getLockOwnerName())
                    .append(" Id=").append(info.getLockOwnerId());
        }
        sb.append(System.lineSeparator());

        for (StackTraceElement element : info.getStackTrace()) {
            sb.append("\tat ").append(element).append(System.lineSeparator());
        }

        for (MonitorInfo monitorInfo : info.getLockedMonitors()) {
            sb.append("\t- locked ").append(monitorInfo).append(System.lineSeparator());
        }
        for (LockInfo lockInfo : info.getLockedSynchronizers()) {
            sb.append("\t- locked synchronizer ").append(lockInfo).append(System.lineSeparator());
        }
    }

    private static void trimFileIfNeeded(Path path, long maxBytes, long keepBytes, String markerLine) {
        try {
            if (!Files.exists(path) || Files.size(path) <= maxBytes) {
                return;
            }
            byte[] content = Files.readAllBytes(path);
            int start = (int) Math.max(0, content.length - keepBytes);
            byte[] tail = new byte[content.length - start];
            System.arraycopy(content, start, tail, 0, tail.length);
            String marker = markerLine + System.lineSeparator();
            Files.write(
                    path,
                    marker.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            Files.write(
                    path,
                    tail,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.err.println("[MeshApp] Failed to trim diagnostic file " + path + ": " + e.getMessage());
        }
    }

    private static void writeJsonQuietly(Path path, Object payload) {
        try {
            Files.writeString(
                    path,
                    GSON.toJson(payload),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            System.err.println("[MeshApp] Failed to write diagnostic JSON " + path + ": " + e.getMessage());
        }
    }

    private static void copyDirectoryContents(Path sourceDir, Path targetDir) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            return;
        }
        Files.createDirectories(targetDir);
        try (Stream<Path> files = Files.walk(sourceDir)) {
            for (Path sourcePath : files.toList()) {
                Path relative = sourceDir.relativize(sourcePath);
                if (relative.toString().isEmpty()) {
                    continue;
                }
                Path target = targetDir.resolve(relative);
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(sourcePath, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void movePathRecursively(Path source, Path target) {
        try {
            Files.move(source, target);
            return;
        } catch (IOException ignored) {
            // Fallback below.
        }

        try {
            if (Files.isDirectory(source)) {
                copyDirectoryContents(source, target);
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            deleteRecursivelyQuietly(source);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to preserve previous crash bundle", e);
        }
    }

    private static void closeWriterQuietly() {
        if (writer == null) {
            return;
        }
        flushWriterQuietly();
        try {
            writer.close();
        } catch (IOException e) {
            System.err.println("[MeshApp] Failed to close session log writer: " + e.getMessage());
        } finally {
            writer = null;
        }
    }

    private static void flushWriterQuietly() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
        } catch (IOException e) {
            System.err.println("[MeshApp] Failed to flush session log writer: " + e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.err.println("[MeshApp] Failed to delete " + path + ": " + e.getMessage());
        }
    }

    private static void deleteRecursivelyQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path current : files.sorted(Comparator.reverseOrder()).toList()) {
                deleteQuietly(current);
            }
        } catch (IOException e) {
            System.err.println("[MeshApp] Failed to delete tree " + path + ": " + e.getMessage());
        }
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static String nowString() {
        return JSON_TIME_FORMAT.format(Instant.now());
    }

    private static String jsonTime(long millis) {
        return JSON_TIME_FORMAT.format(Instant.ofEpochMilli(millis));
    }
}
