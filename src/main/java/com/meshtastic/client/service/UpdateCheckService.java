package com.meshtastic.client.service;

import com.google.gson.Gson;
import com.meshtastic.client.MeshApp;
import com.meshtastic.client.model.UpdateInfo;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.platform.OsDetect.OsType;
import com.meshtastic.client.platform.OsDetect.PackageFormat;
import com.meshtastic.client.update.ReleaseVersion;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Checks for application updates at startup.
 * <p>
 * Compares release versions and build numbers in the installed release's channels,
 * retaining integer-code compatibility with older manifests. The callback runs on JavaFX.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class UpdateCheckService {

    private static final Logger log = LoggerFactory.getLogger(UpdateCheckService.class);
    private static final Gson GSON = new Gson();
    private static final URI UPDATE_ROOT = URI.create("https://smikme.github.io/meshapp/updates/");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private UpdateCheckService() {}

    /**
     * Checks for updates asynchronously. When a newer version is available, the
     * callback is invoked on the JavaFX application thread.
     */
    public static void checkAsync(Consumer<UpdateInfo> onUpdateAvailable) {
        if (MeshApp.VERSION_CODE == 0) {
            log.debug("Skipping update check: dev build (versionCode=0)");
            return;
        }

        checkForUpdateAsync()
                .thenAccept(update -> update.ifPresent(info ->
                        Platform.runLater(() -> onUpdateAvailable.accept(info))))
                .exceptionally(ex -> {
                    log.debug("Update check failed: {}", ex.getMessage());
                    return null;
                });
    }

    /**
     * Checks for updates asynchronously and completes with update information when
     * the remote manifest contains a newer version.
     */
    public static CompletableFuture<Optional<UpdateInfo>> checkForUpdateAsync() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        return checkForUpdateAsync(client, UPDATE_ROOT, MeshApp.APPLICATION_VERSION, MeshApp.VERSION_CODE);
    }

    static CompletableFuture<Optional<UpdateInfo>> checkForUpdateAsync(
            HttpClient client, URI root, String localVersion, int localCode) {
        if (localCode <= 0) return CompletableFuture.completedFuture(Optional.empty());
        var stable = fetchChannel(client, root, "stable", localVersion, localCode);
        if (!ReleaseVersion.parse(localVersion).map(ReleaseVersion::unstable).orElse(false)) return stable;
        var unstable = fetchChannel(client, root, "unstable", localVersion, localCode);
        return stable.thenCombine(unstable, (a, b) -> {
            if (a.isEmpty()) return b;
            if (b.isEmpty()) return a;
            return ReleaseVersion.compare(a.get().getVersion(), a.get().getVersionCode(),
                    b.get().getVersion(), b.get().getVersionCode()) >= 0 ? a : b;
        });
    }

    private static CompletableFuture<Optional<UpdateInfo>> fetchChannel(
            HttpClient client, URI root, String channel, String localVersion, int localCode) {
        HttpRequest request = buildRequest(
                root.resolve(channel + "/meshapp.json"),
                OsDetect.current(),
                OsDetect.currentPackageFormat(),
                OsDetect.normalizedArch(),
                localVersion,
                localCode
        );

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    // A channel can be absent before its first signed publication.
                    if (response.statusCode() == 404) return Optional.empty();
                    if (response.statusCode() != 200) {
                        log.warn("Update check: HTTP {}", response.statusCode());
                        throw new IllegalStateException("HTTP " + response.statusCode());
                    }
                    return availableUpdate(response.body(), localVersion, localCode, channel);
                });
    }

    static Optional<UpdateInfo> availableUpdate(String body, String localVersion, int localVersionCode, String channel) {
        UpdateInfo info = GSON.fromJson(body, UpdateInfo.class);
        if (info != null && (info.hasInconsistentVersionMetadata() || !channel.equals(info.getChannel()))) {
            throw new IllegalArgumentException("Update manifest version/channel metadata does not match its feed");
        }
        if (info != null && info.getVersionCode() > 0 && ReleaseVersion.compare(info.getVersion(), info.getVersionCode(),
                localVersion, localVersionCode) > 0) {
            log.info("Update available: {} (code {}), current code {}",
                    info.getVersion(), info.getVersionCode(), localVersionCode);
            return Optional.of(info);
        }
        log.debug("No update available (server={}, local={})",
                info != null ? info.getVersionCode() : "null",
                localVersionCode);
        return Optional.empty();
    }

    static HttpRequest buildRequest(URI uri,
                                    OsType osType,
                                    PackageFormat packageFormat,
                                    String arch,
                                    String version,
                                    int versionCode) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("X-MeshApp-OS", normalizeOs(osType))
                .header("X-MeshApp-Package", normalizePackageFormat(packageFormat))
                .header("X-MeshApp-Arch", arch != null && !arch.isBlank() ? arch : "unknown")
                .header("X-MeshApp-Version", version != null && !version.isBlank() ? version : "unknown")
                .header("X-MeshApp-Version-Code", Integer.toString(versionCode))
                .header("X-MeshApp-Build-Number", Long.toString(ReleaseVersion.parse(version)
                        .map(ReleaseVersion::buildNumber).orElse(0L)))
                .GET()
                .build();
    }

    private static String normalizeOs(OsType osType) {
        return switch (osType) {
            case WINDOWS -> "windows";
            case MACOS -> "macos";
            case LINUX -> "linux";
            case UNKNOWN -> "unknown";
        };
    }

    private static String normalizePackageFormat(PackageFormat packageFormat) {
        return switch (packageFormat) {
            case MSI -> "msi";
            case DMG -> "dmg";
            case DEB -> "deb";
            case APPIMAGE -> "appimage";
            case FLATPAK -> "flatpak";
            case UNKNOWN -> "unknown";
        };
    }
}
