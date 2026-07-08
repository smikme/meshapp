package com.meshtastic.client.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

/**
 * Applies a downloaded payload update after the main application exits.
 */
final class SelfUpdateInstaller {

    private static final Duration PARENT_WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final String MAIN_CLASS = "com.meshtastic.client.MeshAppLauncher";

    record Request(Path root,
                   Path archive,
                   String targetVersion,
                   String expectedSha256,
                   long parentPid,
                   String launcher,
                   SelfUpdateEnvironment.Layout layout) {}

    void apply(Request request) throws Exception {
        apply(request, ProgressListener.noop());
    }

    void apply(Request request, ProgressListener progress) throws Exception {
        validateRequest(request);
        ProgressListener listener = progress != null ? progress : ProgressListener.noop();
        listener.onInstallProgress(0, 0, 0);

        String actualSha256 = sha256(request.archive());
        if (!actualSha256.equalsIgnoreCase(request.expectedSha256())) {
            throw new IOException("Archive checksum mismatch");
        }
        listener.onInstallProgress(0.05, 0, 0);

        Path targetDir = applyManagedLayout(request, listener);
        listener.onInstallProgress(1, 1, 1);
        listener.onReadyToRestart();
        if (request.launcher() != null && !request.launcher().isBlank()) {
            waitForParent(request.parentPid());
            relaunchManagedPayload(request, targetDir);
        }
    }

    private Path applyManagedLayout(Request request, ProgressListener progress) throws Exception {
        Files.createDirectories(request.root());
        Files.createDirectories(request.root().resolve("versions"));
        Files.createDirectories(stagingRoot(request));

        Path extractDir = stagingRoot(request)
                .resolve("extract-" + request.targetVersion());
        Path targetDir = request.root()
                .resolve("versions")
                .resolve(request.targetVersion());
        Path backupDir = stagingRoot(request)
                .resolve("replace-" + request.targetVersion());

        deleteTree(extractDir);
        Files.createDirectories(extractDir);
        extractZip(request.archive(), extractDir, progress, 0.05, 0.85);

        if (Files.exists(targetDir)) {
            deleteTree(backupDir);
            move(targetDir, backupDir);
        }
        move(extractDir, targetDir);
        progress.onInstallProgress(0.95, 0, 0);
        writeCurrent(request.root().resolve("current"), request.targetVersion());

        return targetDir;
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 128];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void extractZip(Path archive, Path targetDir) throws IOException {
        extractZip(archive, targetDir, ProgressListener.noop(), 0, 1);
    }

    private static void extractZip(Path archive,
                                   Path targetDir,
                                   ProgressListener progress,
                                   double startProgress,
                                   double endProgress) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        long totalBytes = zipUncompressedSize(archive);
        long[] completedBytes = {0};
        ProgressListener listener = progress != null ? progress : ProgressListener.noop();
        listener.onInstallProgress(startProgress, 0, totalBytes);

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            for (ZipEntry entry : entries) {
                Path output = normalizedTarget.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedTarget)) {
                    throw new IOException("Unsafe archive entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Path parent = output.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    copyZipEntry(zip, entry, output, completedBytes, totalBytes, listener, startProgress, endProgress);
                    restoreExecutableBit(entry.getName(), output);
                }
            }
        }
        listener.onInstallProgress(endProgress, totalBytes, totalBytes);
    }

    private static long zipUncompressedSize(Path archive) throws IOException {
        long total = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                long size = entry.getSize();
                if (size < 0) {
                    return -1;
                }
                total += size;
            }
        }
        return total;
    }

    private static void copyZipEntry(ZipFile zip,
                                     ZipEntry entry,
                                     Path output,
                                     long[] completedBytes,
                                     long totalBytes,
                                     ProgressListener progress,
                                     double startProgress,
                                     double endProgress) throws IOException {
        try (InputStream input = zip.getInputStream(entry);
             var outputStream = Files.newOutputStream(output)) {
            byte[] buffer = new byte[1024 * 128];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read <= 0) {
                    continue;
                }
                outputStream.write(buffer, 0, read);
                if (totalBytes > 0) {
                    completedBytes[0] += read;
                    double fraction = Math.min(1, (double) completedBytes[0] / totalBytes);
                    progress.onInstallProgress(
                            startProgress + ((endProgress - startProgress) * fraction),
                            completedBytes[0],
                            totalBytes
                    );
                }
            }
        }
    }

    static void writeCurrent(Path currentFile, String targetVersion) throws IOException {
        Files.createDirectories(currentFile.getParent());
        Path tmp = currentFile.resolveSibling(currentFile.getFileName() + ".tmp");
        Files.writeString(tmp, targetVersion + System.lineSeparator());
        move(tmp, currentFile);
    }

    static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void validateRequest(Request request) {
        if (request == null
                || request.root() == null
                || request.archive() == null
                || request.targetVersion() == null
                || request.targetVersion().isBlank()
                || request.expectedSha256() == null
                || request.expectedSha256().isBlank()
                || request.layout() == null) {
            throw new IllegalArgumentException("Incomplete self-update request");
        }
        if (request.targetVersion().contains("/")
                || request.targetVersion().contains("\\")
                || request.targetVersion().contains("..")) {
            throw new IllegalArgumentException("Unsafe target version");
        }
    }

    private static void waitForParent(long parentPid) {
        if (parentPid <= 0) {
            return;
        }
        Optional<ProcessHandle> parent = ProcessHandle.of(parentPid);
        if (parent.isEmpty() || !parent.get().isAlive()) {
            return;
        }
        try {
            parent.get().onExit().get(
                    PARENT_WAIT_TIMEOUT.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS
            );
        } catch (Exception ignored) {
            // Continue anyway; the target layout is versioned so we are not
            // replacing files used by the running JVM.
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static List<String> managedPayloadCommand(Request request, Path targetDir) throws IOException {
        Path libDir = payloadLibDir(targetDir);
        Path fxDir = Files.isDirectory(libDir.resolve("fx"))
                ? libDir.resolve("fx")
                : targetDir.resolve("fx");
        String classPath = payloadClassPath(libDir);
        if (classPath.isBlank()) {
            throw new IOException("Payload classpath is empty: " + libDir);
        }

        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.addAll(payloadJvmArgs());
        if (Files.isDirectory(fxDir)) {
            command.add("--module-path");
            command.add(fxDir.toAbsolutePath().toString());
            command.add("--add-modules");
            command.add("javafx.controls");
        }
        command.add("-D" + SelfUpdateEnvironment.PROP_ROOT + "="
                + request.root().toAbsolutePath());
        command.add("-D" + SelfUpdateEnvironment.PROP_VERSION + "="
                + request.targetVersion());
        command.add("-D" + SelfUpdateEnvironment.PROP_STAGING_DIR + "="
                + stagingRoot(request).toAbsolutePath());
        if (request.launcher() != null && !request.launcher().isBlank()) {
            command.add("-D" + SelfUpdateEnvironment.PROP_LAUNCHER + "="
                    + request.launcher());
        }
        command.add("-DjSerialComm.library.path=" + libDir.toAbsolutePath());
        command.add("-cp");
        command.add(classPath);
        command.add(MAIN_CLASS);
        return command;
    }

    private static void relaunchManagedPayload(Request request, Path targetDir) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(managedPayloadCommand(request, targetDir));
        builder.directory(targetDir.toFile());
        Map<String, String> env = builder.environment();
        env.put(SelfUpdateLauncher.ENV_PAYLOAD_ACTIVE, "true");
        env.put(SelfUpdateEnvironment.ENV_ROOT, request.root().toAbsolutePath().toString());
        env.put(SelfUpdateEnvironment.ENV_VERSION, request.targetVersion());
        if (request.launcher() != null && !request.launcher().isBlank()) {
            env.put(SelfUpdateEnvironment.ENV_LAUNCHER, request.launcher());
        }
        builder.start();
    }

    private static Path payloadLibDir(Path versionDir) {
        Path lib = versionDir.resolve("lib");
        return Files.isDirectory(lib) ? lib : versionDir;
    }

    private static String payloadClassPath(Path libDir) throws IOException {
        try (Stream<Path> stream = Files.list(libDir)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .map(path -> path.toAbsolutePath().toString())
                    .reduce((left, right) -> left + java.io.File.pathSeparator + right)
                    .orElse("");
        }
    }

    private static Path javaExecutable() {
        String bin = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", bin);
    }

    private static List<String> payloadJvmArgs() {
        List<String> args = new ArrayList<>(List.of(
                "-Xmx512m",
                "-Xms128m",
                "-XX:+IgnoreUnrecognizedVMOptions",
                "--sun-misc-unsafe-memory-access=allow",
                "-XX:ErrorFile=%h/.meshapp/logs/diagnostics/active/hs_err_pid%p.log",
                "-XX:+HeapDumpOnOutOfMemoryError",
                "-XX:HeapDumpPath=%h/.meshapp/logs/diagnostics/active/heapdump_pid%p.hprof",
                "--add-opens", "javafx.graphics/javafx.stage=ALL-UNNAMED",
                "--add-exports", "javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED",
                "--add-opens", "javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED",
                "--add-opens", "javafx.graphics/com.sun.javafx.tk.quantum=ALL-UNNAMED",
                "--add-opens", "javafx.graphics/com.sun.glass.ui=ALL-UNNAMED"
        ));
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (osName.contains("win")) {
            args.add("--add-opens");
            args.add("javafx.graphics/com.sun.glass.ui.win=ALL-UNNAMED");
        } else if (osName.contains("mac")) {
            args.add("--add-opens");
            args.add("javafx.graphics/com.sun.glass.ui.mac=ALL-UNNAMED");
        }
        return args;
    }

    private static void restoreExecutableBit(String entryName, Path output) {
        if (System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win")) {
            return;
        }
        String normalized = entryName.replace('\\', '/');
        if ((normalized.startsWith("bin/") || normalized.contains("/bin/"))
                && !normalized.endsWith(".bat")) {
            output.toFile().setExecutable(true, false);
        }
    }

    private static Path stagingRoot(Request request) {
        Path archiveParent = request.archive().getParent();
        if (archiveParent != null) {
            return archiveParent;
        }
        return request.root().resolve("staging");
    }

    interface ProgressListener {
        void onInstallProgress(double progress, long completedBytes, long totalBytes);

        default void onReadyToRestart() {}

        static ProgressListener noop() {
            return new ProgressListener() {
                @Override
                public void onInstallProgress(double progress, long completedBytes, long totalBytes) {}
            };
        }
    }

}
