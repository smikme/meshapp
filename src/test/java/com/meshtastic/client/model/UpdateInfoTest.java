package com.meshtastic.client.model;

import com.google.gson.Gson;
import com.meshtastic.client.platform.OsDetect.OsType;
import com.meshtastic.client.platform.OsDetect.PackageFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpdateInfoTest {

    private final Gson gson = new Gson();

    @Test
    void linuxAppImagePrefersDedicatedArtifact() {
        UpdateInfo info = parse("""
                {
                  "downloads": {
                    "linux-appimage": "https://example.invalid/app.AppImage",
                    "linux-deb": "https://example.invalid/app.deb",
                    "linux": "https://example.invalid/release"
                  }
                }
                """);

        assertEquals(
                "https://example.invalid/app.AppImage",
                info.getDownloadUrl(OsType.LINUX, true)
        );
    }

    @Test
    void linuxDebPrefersDedicatedArtifact() {
        UpdateInfo info = parse("""
                {
                  "downloads": {
                    "linux-appimage": "https://example.invalid/app.AppImage",
                    "linux-deb": "https://example.invalid/app.deb",
                    "linux": "https://example.invalid/release"
                  }
                }
                """);

        assertEquals(
                "https://example.invalid/app.deb",
                info.getDownloadUrl(OsType.LINUX, false)
        );
    }

    @Test
    void linuxAppImageFallsBackToLegacyLinuxKey() {
        UpdateInfo info = parse("""
                {
                  "downloads": {
                    "linux": "https://example.invalid/release",
                    "linux-deb": "https://example.invalid/app.deb"
                  }
                }
                """);

        assertEquals(
                "https://example.invalid/release",
                info.getDownloadUrl(OsType.LINUX, true)
        );
    }

    @Test
    void linuxDebFallsBackToLegacyLinuxKey() {
        UpdateInfo info = parse("""
                {
                  "downloads": {
                    "linux": "https://example.invalid/release",
                    "linux-appimage": "https://example.invalid/app.AppImage"
                  }
                }
                """);

        assertEquals(
                "https://example.invalid/release",
                info.getDownloadUrl(OsType.LINUX, false)
        );
    }

    @Test
    void fallsBackToOtherLinuxArtifactWhenLegacyKeyIsMissing() {
        UpdateInfo info = parse("""
                {
                  "downloads": {
                    "linux-appimage": "https://example.invalid/app.AppImage"
                  }
                }
                """);

        assertEquals(
                "https://example.invalid/app.AppImage",
                info.getDownloadUrl(OsType.LINUX, false)
        );
    }

    @Test
    void linuxFlatpakPrefersDedicatedArtifact() {
        UpdateInfo info = parse("""
                {
                  "downloads": {
                    "linux-flatpak": "https://example.invalid/app.flatpak",
                    "linux-deb": "https://example.invalid/app.deb",
                    "linux-appimage": "https://example.invalid/app.AppImage"
                  }
                }
                """);

        assertEquals(
                "https://example.invalid/app.flatpak",
                info.getDownloadUrl(OsType.LINUX, PackageFormat.FLATPAK)
        );
    }

    @Test
    void linuxFlatpakFallsBackToLegacyLinuxKey() {
        UpdateInfo info = parse("""
                {
                  "downloads": {
                    "linux": "https://example.invalid/release",
                    "linux-deb": "https://example.invalid/app.deb"
                  }
                }
                """);

        assertEquals(
                "https://example.invalid/release",
                info.getDownloadUrl(OsType.LINUX, PackageFormat.FLATPAK)
        );
    }

    @Test
    void linuxDebFallsBackToFlatpakWhenNothingElseExists() {
        UpdateInfo info = parse("""
                {
                  "downloads": {
                    "linux-flatpak": "https://example.invalid/app.flatpak"
                  }
                }
                """);

        assertEquals(
                "https://example.invalid/app.flatpak",
                info.getDownloadUrl(OsType.LINUX, PackageFormat.DEB)
        );
    }

    @Test
    void returnsNullWhenPlatformDownloadIsMissing() {
        UpdateInfo info = parse("""
                {
                  "downloads": {
                    "windows": "https://example.invalid/app.msi"
                  }
                }
                """);

        assertNull(info.getDownloadUrl(OsType.MACOS, false));
    }

    private UpdateInfo parse(String json) {
        return gson.fromJson(json, UpdateInfo.class);
    }
}
