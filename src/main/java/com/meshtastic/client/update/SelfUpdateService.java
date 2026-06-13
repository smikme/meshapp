package com.meshtastic.client.update;

import com.meshtastic.client.model.SelfUpdateArtifact;
import com.meshtastic.client.model.UpdateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Downloads and stages non-privileged full-archive self-updates.
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
                                       Consumer<String> onStatus,
                                       Consumer<Throwable> onError) {
        Thread.ofVirtual().start(() -> {
            try {
                UpdatePlan plan = plan(info)
                        .orElseThrow(() -> new IllegalStateException("Self-update is not available"));
                status(onStatus, "download");
                Path archive = download(plan);
                status(onStatus, "handoff");
                startInstaller(plan, archive);
                status(onStatus, "restart");
            } catch (Throwable t) {
                log.warn("Self-update failed", t);
                if (onError != null) {
                    onError.accept(t);
                }
            }
        });
    }

    Path download(UpdatePlan plan) throws Exception {
        Files.createDirectories(plan.environment().stagingDir());
        String fileName = archiveFileName(plan.artifact().getUrl(), plan.targetVersion());
        Path archive = plan.environment()
                .stagingDir()
                .resolve(plan.targetVersion() + "-" + fileName);
        Path partial = archive.resolveSibling(archive.getFileName() + ".part");
        Files.deleteIfExists(partial);

        URI uri = URI.create(plan.artifact().getUrl().trim());
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            Files.copy(Path.of(uri), partial, StandardCopyOption.REPLACE_EXISTING);
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
                Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        String actualSha256 = SelfUpdateInstaller.sha256(partial);
        if (!actualSha256.equalsIgnoreCase(plan.artifact().getSha256())) {
            Files.deleteIfExists(partial);
            throw new IOException("Downloaded update checksum mismatch");
        }
        Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING);
        return archive;
    }

    void startInstaller(UpdatePlan plan, Path archive) throws IOException {
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
        if (plan.environment().launcher() != null && !plan.environment().launcher().isBlank()) {
            command.add("--launcher");
            command.add(plan.environment().launcher());
        }
        new ProcessBuilder(command).start();
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

    private static void status(Consumer<String> onStatus, String status) {
        if (onStatus != null) {
            onStatus.accept(status);
        }
    }

    public record UpdatePlan(UpdateInfo info,
                             SelfUpdateArtifact artifact,
                             SelfUpdateEnvironment environment,
                             String targetVersion) {}
}
