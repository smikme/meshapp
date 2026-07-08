package com.meshtastic.client.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Manages the current session crash bundle:
 * <ul>
 *     <li>maintains a size-limited {@code session.log}</li>
 *     <li>writes thread dumps, fatal markers, and JFR snapshots to the active diagnostics directory</li>
 *     <li>moves an abnormally ended bundle to the pending directory on the next launch</li>
 *     <li>removes old bundles by age, count, and total size</li>
 * </ul>
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class SessionCrashLogManager {

    static final String APP_DIR_NAME = ".meshapp";
    static final String LOG_DIR_NAME = "logs";
    static final String DIAGNOSTICS_DIR_NAME = "diagnostics";
    static final String PENDING_DIR_NAME = "pending-crash-reports";
    static final String ACTIVE_BUNDLE_DIR_NAME = "active";
    static final String ACTIVE_LOG_NAME = "meshapp-session.log";
    static final String NORMAL_EXIT_MARKER_NAME = "meshapp-session.clean-exit";
    static final String THREAD_DUMPS_FILE_NAME = "thread-dumps.txt";
    static final String FATAL_MARKER_FILE_NAME = "fatal-marker.json";
    static final String SESSION_STATE_FILE_NAME = "session-state.json";
    static final String JFR_DUMP_FILE_NAME = "last.jfr";
    static final String HEAP_DUMP_EXTENSION = ".hprof";
    static final String DISABLED_PROPERTY = "meshapp.sessionLog.disabled";

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
    private static final int MAX_PENDING_BUNDLES = 3;
    private static final long MAX_PENDING_BYTES = 64L * 1024 * 1024;
    private static final Duration MAX_PENDING_AGE = Duration.ofDays(14);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private static volatile boolean shutdownHookInstalled;
    private static volatile ScheduledExecutorService heartbeatExecutor;
    private static volatile Supplier<BootIdentity> bootIdentitySupplier = SessionCrashLogManager::detectBootIdentity;
    private static volatile BootIdentity cachedBootIdentity;

    private static Path appDir = resolveAppDir();
    private static Path logsDir = appDir.resolve(LOG_DIR_NAME);
    private static Path diagnosticsDir = logsDir.resolve(DIAGNOSTICS_DIR_NAME);
    private static Path pendingDir = logsDir.resolve(PENDING_DIR_NAME);
    private static Path activeBundleDir = diagnosticsDir.resolve(ACTIVE_BUNDLE_DIR_NAME);
    private static Path activeLogPath = activeBundleDir.resolve(ACTIVE_LOG_NAME);
    private static Path threadDumpsPath = activeBundleDir.resolve(THREAD_DUMPS_FILE_NAME);
    private static Path fatalMarkerPath = activeBundleDir.resolve(FATAL_MARKER_FILE_NAME);
    private static Path sessionStatePath = activeBundleDir.resolve(SESSION_STATE_FILE_NAME);
    private static Path normalExitMarkerPath = logsDir.resolve(NORMAL_EXIT_MARKER_NAME);
    private static Path legacyActiveLogPath = logsDir.resolve(ACTIVE_LOG_NAME);

    private static BufferedWriter writer;
    private static boolean prepared;
    private static boolean suspendedForTests;
    private static String sessionId;
    private static Instant sessionStartedAt;

    private SessionCrashLogManager() {}

    public static void prepareForLaunch() {
        synchronized (LOCK) {
            suspendedForTests = false;
            refreshPaths();
            installShutdownHookIfNeeded();
            stopHeartbeatLocked();
            closeWriterQuietly();
            ensureDirectories();

            if (Files.exists(normalExitMarkerPath)) {
                deleteRecursivelyQuietly(activeBundleDir);
                deleteQuietly(legacyActiveLogPath);
                deleteQuietly(normalExitMarkerPath);
            } else {
                if (bundleHasPayload(activeBundleDir)) {
                    if (shouldSuppressPreviousBundleReport(activeBundleDir)) {
                        deleteRecursivelyQuietly(activeBundleDir);
                    } else {
                        writeAbnormalExitMarkerIfAbsent(activeBundleDir);
                        rotateActiveBundleToPending();
                    }
                }
                if (Files.exists(legacyActiveLogPath)) {
                    rotateLegacyActiveLogToPending();
                }
            }

            prunePendingBundles();
            prepareFreshActiveBundle();
            initializeSessionState();
            prepared = true;
            startHeartbeatLocked();
        }
    }

    public static void append(ILoggingEvent event) {
        synchronized (LOCK) {
            if (isDisabled()) {
                return;
            }
            if (suspendedForTests) {
                return;
            }
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
            } catch (IOException e) {
                System.err.println("[MeshApp] Failed to append session log: " + e.getMessage());
            }
        }
    }

    private static boolean isDisabled() {
        return Boolean.getBoolean(DISABLED_PROPERTY);
    }

    public static void markNormalShutdown() {
        synchronized (LOCK) {
            if (suspendedForTests) {
                return;
            }
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
            stopHeartbeatLocked();
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
            snapshotDir = Files.createTempDirectory("meshapp-session-report-");
            copyDirectoryContents(activeBundleDir, snapshotDir);
        }

        writeThreadDump(snapshotDir.resolve("thread-dump-manual-report.txt"), "manual-report");
        JfrDiagnosticSupport.dumpSnapshot(snapshotDir.resolve("manual-report.jfr"));
        return snapshotDir;
    }

    public static void captureUncaughtException(Thread thread, Throwable throwable) {
        Path jfrPath;
        synchronized (LOCK) {
            if (suspendedForTests) {
                return;
            }
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
            writeThreadDump(threadDumpsPath, "uncaught-exception");
            jfrPath = activeBundleDir.resolve(JFR_DUMP_FILE_NAME);
        }
        JfrDiagnosticSupport.dumpSnapshot(jfrPath);
    }

    public static void captureUiFreezeDiagnostic(Duration stallDuration) {
        Path jfrPath;
        synchronized (LOCK) {
            if (suspendedForTests) {
                return;
            }
            ensureReadyForAppend();
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("type", "ui-stall");
            marker.put("capturedAt", nowString());
            marker.put("stallMillis", stallDuration != null ? stallDuration.toMillis() : -1L);
            writeJsonQuietly(fatalMarkerPath, marker);
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

    static void resetForTests() {
        synchronized (LOCK) {
            suspendedForTests = false;
            stopHeartbeatLocked();
            closeWriterQuietly();
            refreshPaths();
            prepared = false;
            sessionId = null;
            sessionStartedAt = null;
            cachedBootIdentity = null;
            bootIdentitySupplier = SessionCrashLogManager::detectBootIdentity;
        }
    }

    static void suspendForTests() {
        synchronized (LOCK) {
            resetForTests();
            suspendedForTests = true;
        }
    }

    static void setBootIdentityForTests(String bootId) {
        synchronized (LOCK) {
            cachedBootIdentity = null;
            bootIdentitySupplier = () -> new BootIdentity("test", bootId == null ? "" : bootId.trim());
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
        threadDumpsPath = activeBundleDir.resolve(THREAD_DUMPS_FILE_NAME);
        fatalMarkerPath = activeBundleDir.resolve(FATAL_MARKER_FILE_NAME);
        sessionStatePath = activeBundleDir.resolve(SESSION_STATE_FILE_NAME);
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
    }

    private static void initializeSessionState() {
        sessionId = UUID.randomUUID().toString();
        sessionStartedAt = Instant.now();
        writeSessionStateLocked();
    }

    private static void startHeartbeatLocked() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            return;
        }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "meshapp-session-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleWithFixedDelay(
                SessionCrashLogManager::writeSessionStateSafely,
                HEARTBEAT_INTERVAL.toSeconds(),
                HEARTBEAT_INTERVAL.toSeconds(),
                TimeUnit.SECONDS);
    }

    private static void stopHeartbeatLocked() {
        ScheduledExecutorService executor = heartbeatExecutor;
        heartbeatExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static void writeSessionStateSafely() {
        synchronized (LOCK) {
            if (!prepared || sessionId == null) {
                return;
            }
            refreshPaths();
            ensureDirectories();
            writeSessionStateLocked();
        }
    }

    private static void writeSessionStateLocked() {
        BootIdentity boot = currentBootIdentity();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "session-state");
        state.put("sessionId", sessionId != null ? sessionId : "");
        state.put("pid", ProcessHandle.current().pid());
        state.put("startedAt", sessionStartedAt != null ? JSON_TIME_FORMAT.format(sessionStartedAt) : "");
        state.put("lastHeartbeatAt", nowString());
        state.put("bootId", boot.value());
        state.put("bootIdSource", boot.source());
        writeJsonQuietly(sessionStatePath, state);
    }

    private static boolean shouldSuppressPreviousBundleReport(Path bundleDir) {
        if (hasFatalCrashEvidence(bundleDir)) {
            return false;
        }
        return previousSessionWasInterruptedByReboot(bundleDir);
    }

    private static boolean hasFatalCrashEvidence(Path bundleDir) {
        if (hasJvmCrashLog(bundleDir)) {
            return true;
        }
        Path marker = bundleDir.resolve(FATAL_MARKER_FILE_NAME);
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        Map<?, ?> json = readJsonMapQuietly(marker);
        Object type = json.get("type");
        return !"ui-stall".equals(type);
    }

    private static boolean hasJvmCrashLog(Path bundleDir) {
        if (!Files.isDirectory(bundleDir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(bundleDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.startsWith("hs_err_pid") && name.endsWith(".log"));
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeAbnormalExitMarkerIfAbsent(Path bundleDir) {
        Path markerPath = bundleDir.resolve(FATAL_MARKER_FILE_NAME);
        if (Files.isRegularFile(markerPath)) {
            return;
        }

        Map<?, ?> previousState = readJsonMapQuietly(bundleDir.resolve(SESSION_STATE_FILE_NAME));
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("type", "abnormal-exit");
        marker.put("capturedAt", nowString());
        marker.put("reason", "previous session ended without a normal shutdown marker");
        if (!previousState.isEmpty()) {
            marker.put("previousSession", previousState);
        }
        writeJsonQuietly(markerPath, marker);
    }

    private static boolean previousSessionWasInterruptedByReboot(Path bundleDir) {
        Path statePath = bundleDir.resolve(SESSION_STATE_FILE_NAME);
        if (!Files.isRegularFile(statePath)) {
            return false;
        }
        Map<?, ?> state = readJsonMapQuietly(statePath);
        String previousBootId = stringValue(state.get("bootId"));
        String currentBootId = currentBootIdentity().value();
        return !previousBootId.isBlank()
                && !currentBootId.isBlank()
                && !previousBootId.equals(currentBootId);
    }

    private static Map<?, ?> readJsonMapQuietly(Path path) {
        try {
            Object parsed = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Map.class);
            return parsed instanceof Map<?, ?> map ? map : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String stringValue(Object value) {
        return value instanceof String s ? s.trim() : "";
    }

    private static BootIdentity currentBootIdentity() {
        BootIdentity cached = cachedBootIdentity;
        if (cached != null) {
            return cached;
        }
        BootIdentity detected = bootIdentitySupplier.get();
        cachedBootIdentity = detected != null ? detected : BootIdentity.UNKNOWN;
        return cachedBootIdentity;
    }

    private static BootIdentity detectBootIdentity() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("linux")) {
            return detectLinuxBootIdentity();
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return detectMacBootIdentity();
        }
        if (osName.contains("win")) {
            return detectWindowsBootIdentity();
        }
        return BootIdentity.UNKNOWN;
    }

    private static BootIdentity detectLinuxBootIdentity() {
        try {
            Path bootIdPath = Path.of("/proc/sys/kernel/random/boot_id");
            if (Files.isRegularFile(bootIdPath)) {
                String bootId = Files.readString(bootIdPath, StandardCharsets.UTF_8).trim();
                if (!bootId.isBlank()) {
                    return new BootIdentity("linux-proc-boot-id", bootId);
                }
            }
        } catch (Exception ignored) {
            // Fall through to unknown.
        }
        return BootIdentity.UNKNOWN;
    }

    private static BootIdentity detectMacBootIdentity() {
        return runCommand(List.of("/usr/sbin/sysctl", "-n", "kern.boottime"))
                .filter(value -> !value.isBlank())
                .map(value -> new BootIdentity("macos-kern-boottime", value))
                .orElse(BootIdentity.UNKNOWN);
    }

    private static BootIdentity detectWindowsBootIdentity() {
        Optional<String> powershellBootTime = runCommand(List.of(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "(Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('o')"));
        if (powershellBootTime.isPresent() && !powershellBootTime.get().isBlank()) {
            return new BootIdentity("windows-cim-last-boot-time", powershellBootTime.get());
        }

        return runCommand(List.of("wmic", "os", "get", "lastbootuptime", "/value"))
                .flatMap(SessionCrashLogManager::parseWmicBootTime)
                .map(value -> new BootIdentity("windows-wmic-last-boot-time", value))
                .orElse(BootIdentity.UNKNOWN);
    }

    private static Optional<String> parseWmicBootTime(String output) {
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("LastBootUpTime=")) {
                String value = trimmed.substring("LastBootUpTime=".length()).trim();
                return value.isBlank() ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> runCommand(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(1500, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 && !output.isBlank()
                    ? Optional.of(output)
                    : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
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
            if (ageMillis > MAX_PENDING_AGE.toMillis() && !isProtectedPendingEntry(path)) {
                deleteRecursivelyQuietly(path);
            }
        }

        pendingEntries = listPendingEntriesOrdered();
        while (pendingEntries.size() > MAX_PENDING_BUNDLES) {
            Path oldest = removeOldestUnprotectedEntry(pendingEntries);
            if (oldest == null) {
                break;
            }
            deleteRecursivelyQuietly(oldest);
        }

        pendingEntries = listPendingEntriesOrdered();
        long totalBytes = pendingEntries.stream().mapToLong(SessionCrashLogManager::computePathSize).sum();
        while (totalBytes > MAX_PENDING_BYTES && !pendingEntries.isEmpty()) {
            Path oldest = removeOldestUnprotectedEntry(pendingEntries);
            if (oldest == null) {
                break;
            }
            totalBytes -= computePathSize(oldest);
            deleteRecursivelyQuietly(oldest);
        }
    }

    private static Path removeOldestUnprotectedEntry(List<Path> pendingEntries) {
        for (int i = 0; i < pendingEntries.size(); i++) {
            Path candidate = pendingEntries.get(i);
            if (!isProtectedPendingEntry(candidate)) {
                return pendingEntries.remove(i);
            }
        }
        return null;
    }

    private static boolean isProtectedPendingEntry(Path path) {
        return containsHeapDump(path);
    }

    private static boolean containsHeapDump(Path path) {
        if (!Files.exists(path)) {
            return false;
        }
        if (Files.isRegularFile(path)) {
            return isHeapDumpFile(path);
        }
        try (Stream<Path> files = Files.walk(path)) {
            return files
                    .filter(Files::isRegularFile)
                    .anyMatch(SessionCrashLogManager::isHeapDumpFile);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isHeapDumpFile(Path path) {
        Path fileName = path.getFileName();
        return fileName != null
                && fileName.toString().toLowerCase(Locale.ROOT).endsWith(HEAP_DUMP_EXTENSION);
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
                .append(sanitizeEventMessage(event.getFormattedMessage()));

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            sb.append(System.lineSeparator())
                    .append(ThrowableProxyUtil.asString(throwable).stripTrailing());
        }

        return sb.toString();
    }

    private static String sanitizeEventMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.stripTrailing()
                .replace("\r\n", "\\n")
                .replace('\r', '\n')
                .replace("\n", "\\n");
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

    private record BootIdentity(String source, String value) {
        static final BootIdentity UNKNOWN = new BootIdentity("unknown", "");
    }
}
