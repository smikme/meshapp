package com.meshtastic.client.model;

import com.google.gson.Gson;
import com.meshtastic.client.platform.OsDetect.OsType;
import com.meshtastic.client.platform.OsDetect.PackageFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
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

    @Test
    void russianReleaseNotesPreferLocalizedField() {
        UpdateInfo info = parse("""
                {
                  "releaseNotes": "English notes",
                  "releaseNotes_ru": "Русские заметки"
                }
                """);

        assertEquals("Русские заметки", info.getReleaseNotes("ru"));
        assertEquals("Русские заметки", info.getReleaseNotes("ru-RU"));
    }

    @Test
    void releaseNotesFallbackToDefaultForNonRussianLanguage() {
        UpdateInfo info = parse("""
                {
                  "releaseNotes": "English notes",
                  "releaseNotes_ru": "Русские заметки"
                }
                """);

        assertEquals("English notes", info.getReleaseNotes("en"));
    }

    @Test
    void releaseNotesFallbackToDefaultWhenRussianFieldIsBlank() {
        UpdateInfo info = parse("""
                {
                  "releaseNotes": "English notes",
                  "releaseNotes_ru": " "
                }
                """);

        assertEquals("English notes", info.getReleaseNotes("ru"));
    }

    @Test
    void selfUpdatePrefersPackageAndArchSpecificArtifact() {
        UpdateInfo info = parse("""
                {
                  "selfUpdate": {
                    "linux": {
                      "url": "https://example.invalid/generic.zip",
                      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    },
                    "linux-deb-x86_64": {
                      "url": "https://example.invalid/linux-x64.zip",
                      "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                    }
                  }
                }
                """);

        assertEquals(
                "https://example.invalid/linux-x64.zip",
                info.getSelfUpdateArtifact(OsType.LINUX, PackageFormat.DEB, "x86_64").getUrl()
        );
    }

    @Test
    void selfUpdateSkipsIncompleteArtifact() {
        UpdateInfo info = parse("""
                {
                  "selfUpdate": {
                    "linux-x86_64": {
                      "url": "https://example.invalid/linux-x64.zip"
                    }
                  }
                }
                """);

        assertNull(info.getSelfUpdateArtifact(OsType.LINUX, PackageFormat.DEB, "x86_64"));
    }

    @Test
    void selfUpdateIsUnavailableForFlatpak() {
        UpdateInfo info = parse("""
                {
                  "selfUpdate": {
                    "linux-flatpak-x86_64": {
                      "url": "https://example.invalid/flatpak-selfupdate.zip",
                      "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                    },
                    "linux-x86_64": {
                      "url": "https://example.invalid/linux-selfupdate.zip",
                      "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                    }
                  }
                }
                """);

        assertNull(info.getSelfUpdateArtifact(OsType.LINUX, PackageFormat.FLATPAK, "x86_64"));
    }

    private UpdateInfo parse(String json) {
        return gson.fromJson(json, UpdateInfo.class);
    }
}
