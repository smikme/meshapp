package com.meshtastic.client.service;

import com.google.gson.Gson;
import com.meshtastic.client.MeshApp;
import com.meshtastic.client.model.UpdateInfo;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.platform.OsDetect.OsType;
import com.meshtastic.client.platform.OsDetect.PackageFormat;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Checks for application updates at startup.
 * <p>
 * Performs an asynchronous HTTP request, compares the remote version code with
 * the local one, and invokes the callback on the JavaFX thread when an update is
 * available.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class UpdateCheckService {

    private static final Logger log = LoggerFactory.getLogger(UpdateCheckService.class);
    private static final String UPDATE_URL = "https://flatpak.privatepractice.app/meshapp.json";
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

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = buildRequest(
                URI.create(UPDATE_URL),
                OsDetect.current(),
                OsDetect.currentPackageFormat(),
                OsDetect.normalizedArch(),
                MeshApp.APPLICATION_VERSION,
                MeshApp.VERSION_CODE
        );

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        log.warn("Update check: HTTP {}", response.statusCode());
                        return;
                    }
                    try {
                        UpdateInfo info = new Gson().fromJson(response.body(), UpdateInfo.class);
                        if (info != null && info.getVersionCode() > MeshApp.VERSION_CODE) {
                            log.info("Update available: {} (code {}), current code {}",
                                    info.getVersion(), info.getVersionCode(), MeshApp.VERSION_CODE);
                            Platform.runLater(() -> onUpdateAvailable.accept(info));
                        } else {
                            log.debug("No update available (server={}, local={})",
                                    info != null ? info.getVersionCode() : "null",
                                    MeshApp.VERSION_CODE);
                        }
                    } catch (Exception e) {
                        log.warn("Update check: failed to parse response", e);
                    }
                })
                .exceptionally(ex -> {
                    log.debug("Update check failed: {}", ex.getMessage());
                    return null;
                });
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
