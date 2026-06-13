package com.meshtastic.client.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Applies a downloaded full-archive update after the main application exits.
 */
final class SelfUpdateInstaller {

    private static final Duration PARENT_WAIT_TIMEOUT = Duration.ofSeconds(30);

    record Request(Path root,
                   Path archive,
                   String targetVersion,
                   String expectedSha256,
                   long parentPid,
                   String launcher) {}

    void apply(Request request) throws Exception {
        validateRequest(request);
        waitForParent(request.parentPid());

        String actualSha256 = sha256(request.archive());
        if (!actualSha256.equalsIgnoreCase(request.expectedSha256())) {
            throw new IOException("Archive checksum mismatch");
        }

        Files.createDirectories(request.root());
        Files.createDirectories(request.root().resolve("versions"));
        Files.createDirectories(request.root().resolve("staging"));

        Path extractDir = request.root()
                .resolve("staging")
                .resolve("extract-" + request.targetVersion());
        Path targetDir = request.root()
                .resolve("versions")
                .resolve(request.targetVersion());
        Path backupDir = request.root()
                .resolve("staging")
                .resolve("replace-" + request.targetVersion());

        deleteTree(extractDir);
        Files.createDirectories(extractDir);
        extractZip(request.archive(), extractDir);

        if (Files.exists(targetDir)) {
            deleteTree(backupDir);
            move(targetDir, backupDir);
        }
        move(extractDir, targetDir);
        writeCurrent(request.root().resolve("current"), request.targetVersion());

        if (request.launcher() != null && !request.launcher().isBlank()) {
            relaunch(request.launcher());
        }
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
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
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
                    Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
                    restoreExecutableBit(entry.getName(), output);
                }
                zip.closeEntry();
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
                || request.expectedSha256().isBlank()) {
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

    private static void relaunch(String launcher) throws IOException {
        String lower = launcher.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".bat") || lower.endsWith(".cmd")) {
            new ProcessBuilder("cmd.exe", "/c", "start", "", launcher).start();
            return;
        }
        new ProcessBuilder(launcher).start();
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
}
