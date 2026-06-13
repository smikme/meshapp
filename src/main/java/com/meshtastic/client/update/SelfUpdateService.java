package com.meshtastic.client.update;

import com.meshtastic.client.model.SelfUpdateArtifact;
import com.meshtastic.client.model.UpdateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Downloads and stages non-privileged application payload self-updates.
 */
public final class SelfUpdateService {

    private static final Logger log = LoggerFactory.getLogger(SelfUpdateService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final SelfUpdateService INSTANCE = new SelfUpdateService(
            HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build(),
            UpdateSignatureVerifier.current()
    );

    private final HttpClient httpClient;
    private final UpdateSignatureVerifier signatureVerifier;

    SelfUpdateService(HttpClient httpClient,
                      UpdateSignatureVerifier signatureVerifier) {
        this.httpClient = httpClient;
        this.signatureVerifier = signatureVerifier;
    }

    public static SelfUpdateService getInstance() {
        return INSTANCE;
    }

    public Optional<UpdatePlan> plan(UpdateInfo info) {
        Optional<SelfUpdateEnvironment> env = SelfUpdateEnvironment.current();
        if (env.isEmpty() || info == null) {
            return Optional.empty();
        }
        SelfUpdateArtifact artifact = info.getSelfUpdateArtifact();
        if (artifact == null) {
            return Optional.empty();
        }
        if (!signatureVerifier.isTrusted(info, artifact)) {
            log.warn("Self-update artifact for {} is not trusted; falling back to manual download",
                    info.getVersion());
            return Optional.empty();
        }
        String targetVersion = artifact.getVersion();
        if (targetVersion == null || targetVersion.isBlank()) {
            targetVersion = Integer.toString(info.getVersionCode());
        }
        return Optional.of(new UpdatePlan(info, artifact, env.get(), targetVersion));
    }

    public void installAndRestartAsync(UpdateInfo info,
                                       Consumer<UpdateProgress> onProgress,
                                       Consumer<Throwable> onError) {
        Thread.ofVirtual().start(() -> {
            try {
                UpdatePlan plan = plan(info)
                        .orElseThrow(() -> new IllegalStateException("Self-update is not available"));
                long downloadBytes = downloadSize(plan);
                progress(onProgress, ProgressPhase.DOWNLOAD, progressValue(0, downloadBytes), 0, downloadBytes);
                Path archive = download(plan, onProgress);
                progress(onProgress, ProgressPhase.INSTALL, 0, 0, 0);
                startInstaller(plan, archive, onProgress);
                progress(onProgress, ProgressPhase.RESTART, 1, 1, 1);
            } catch (Throwable t) {
                log.warn("Self-update failed", t);
                if (onError != null) {
                    onError.accept(t);
                }
            }
        });
    }

    Path download(UpdatePlan plan, Consumer<UpdateProgress> onProgress) throws Exception {
        Files.createDirectories(plan.environment().stagingDir());
        String fileName = archiveFileName(plan.artifact().getUrl(), plan.targetVersion());
        Path archive = plan.environment()
                .stagingDir()
                .resolve(plan.targetVersion() + "-" + fileName);
        Path partial = archive.resolveSibling(archive.getFileName() + ".part");
        Files.deleteIfExists(partial);

        try {
            URI uri = URI.create(plan.artifact().getUrl().trim());
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Path source = Path.of(uri);
                try (InputStream input = Files.newInputStream(source)) {
                    copyWithProgress(input, partial, Files.size(source), ProgressPhase.DOWNLOAD, onProgress);
                }
            } else {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<InputStream> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream()
                );
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Update archive download failed: HTTP " + response.statusCode());
                }
                try (InputStream input = response.body()) {
                    long totalBytes = plan.artifact().getSize() > 0
                            ? plan.artifact().getSize()
                            : response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    copyWithProgress(input, partial, totalBytes, ProgressPhase.DOWNLOAD, onProgress);
                }
            }

            String actualSha256 = SelfUpdateInstaller.sha256(partial);
            if (!actualSha256.equalsIgnoreCase(plan.artifact().getSha256())) {
                throw new IOException("Downloaded update checksum mismatch");
            }
            Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING);
            progress(onProgress, ProgressPhase.DOWNLOAD, 1, Files.size(archive), Files.size(archive));
            return archive;
        } catch (Exception e) {
            deletePartial(partial, e);
            throw e;
        }
    }

    void startInstaller(UpdatePlan plan,
                        Path archive,
                        Consumer<UpdateProgress> onProgress) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(SelfUpdateInstallerMain.class.getName());
        command.add("--root");
        command.add(plan.environment().root().toAbsolutePath().toString());
        command.add("--archive");
        command.add(archive.toAbsolutePath().toString());
        command.add("--target-version");
        command.add(plan.targetVersion());
        command.add("--sha256");
        command.add(plan.artifact().getSha256());
        command.add("--parent-pid");
        command.add(Long.toString(ProcessHandle.current().pid()));
        command.add("--layout");
        command.add(plan.environment().layout().id());
        if (plan.environment().launcher() != null && !plan.environment().launcher().isBlank()) {
            command.add("--launcher");
            command.add(plan.environment().launcher());
        }
        command.add("--progress");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        waitForInstallerReady(process, onProgress);
    }

    private static Path javaExecutable() {
        String bin = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", bin);
    }

    private static String archiveFileName(String url, String targetVersion) {
        try {
            String path = URI.create(url).getPath();
            int slash = path == null ? -1 : path.lastIndexOf('/');
            String candidate = slash >= 0 ? path.substring(slash + 1) : path;
            if (candidate != null && !candidate.isBlank()) {
                return candidate.replaceAll("[^A-Za-z0-9._-]", "_");
            }
        } catch (Exception ignored) {}
        return "MeshApp-" + targetVersion + ".zip";
    }

    private static void copyWithProgress(InputStream input,
                                         Path target,
                                         long totalBytes,
                                         ProgressPhase phase,
                                         Consumer<UpdateProgress> onProgress) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        long completed = 0;
        try (OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[1024 * 128];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read <= 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                completed += read;
                progress(onProgress, phase, progressValue(completed, totalBytes), completed, totalBytes);
            }
        }
    }

    private static void waitForInstallerReady(Process process,
                                              Consumer<UpdateProgress> onProgress)
            throws IOException, InterruptedException {
        boolean ready = false;
        List<String> diagnostics = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if ("meshapp-ready".equals(line.trim())) {
                    ready = true;
                    break;
                }
                Optional<UpdateProgress> progress = parseInstallerProgress(line);
                progress.ifPresent(updateProgress -> {
                    if (onProgress != null) {
                        onProgress.accept(updateProgress);
                    }
                });
                if (progress.isEmpty() && !line.isBlank()) {
                    appendDiagnostic(diagnostics, line);
                }
            }
        }
        if (!ready) {
            int exitCode = process.waitFor();
            String details = String.join(System.lineSeparator(), diagnostics);
            String message = "Self-update installer exited before restart handoff: " + exitCode;
            if (!details.isBlank()) {
                message += ". " + details;
            }
            throw new IOException(message);
        }
    }

    private static void appendDiagnostic(List<String> diagnostics, String line) {
        if (diagnostics.size() >= 12) {
            diagnostics.removeFirst();
        }
        diagnostics.add(line);
    }

    private static Optional<UpdateProgress> parseInstallerProgress(String line) {
        if (line == null || !line.startsWith("meshapp-progress ")) {
            return Optional.empty();
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 5 || !"install".equals(parts[1])) {
            return Optional.empty();
        }
        try {
            return Optional.of(new UpdateProgress(
                    ProgressPhase.INSTALL,
                    Double.parseDouble(parts[2]),
                    Long.parseLong(parts[3]),
                    Long.parseLong(parts[4])
            ));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static long downloadSize(UpdatePlan plan) {
        return plan.artifact().getSize() > 0 ? plan.artifact().getSize() : -1;
    }

    private static double progressValue(long completedBytes, long totalBytes) {
        if (totalBytes <= 0) {
            return -1;
        }
        return Math.max(0, Math.min(1, (double) completedBytes / totalBytes));
    }

    private static void progress(Consumer<UpdateProgress> onProgress,
                                 ProgressPhase phase,
                                 double progress,
                                 long completedBytes,
                                 long totalBytes) {
        if (onProgress != null) {
            onProgress.accept(new UpdateProgress(phase, progress, completedBytes, totalBytes));
        }
    }

    private static void deletePartial(Path partial, Exception cause) {
        try {
            Files.deleteIfExists(partial);
        } catch (IOException cleanupError) {
            cause.addSuppressed(cleanupError);
        }
    }

    public enum ProgressPhase {
        DOWNLOAD,
        INSTALL,
        RESTART
    }

    public record UpdateProgress(ProgressPhase phase,
                                 double progress,
                                 long completedBytes,
                                 long totalBytes) {
        public boolean isDeterminate() {
            return progress >= 0;
        }
    }

    public record UpdatePlan(UpdateInfo info,
                             SelfUpdateArtifact artifact,
                             SelfUpdateEnvironment environment,
                             String targetVersion) {}
}
