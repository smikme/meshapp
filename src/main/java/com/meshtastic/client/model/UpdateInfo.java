package com.meshtastic.client.model;

import com.google.gson.annotations.SerializedName;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.platform.OsDetect.OsType;
import com.meshtastic.client.platform.OsDetect.PackageFormat;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Information about a newer application version.
 * <p>
 * Deserialized from update-server JSON.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class UpdateInfo {

    private String version;
    private int versionCode;
    private String releaseNotes;
    @SerializedName("releaseNotes_ru")
    private String releaseNotesRu;
    private Map<String, String> downloads;
    private Map<String, SelfUpdateArtifact> selfUpdate;

    public String getVersion() { return version; }
    public int getVersionCode() { return versionCode; }
    public String getReleaseNotes() { return releaseNotes; }
    public String getReleaseNotesRu() { return releaseNotesRu; }
    public Map<String, String> getDownloads() { return downloads; }
    public Map<String, SelfUpdateArtifact> getSelfUpdate() { return selfUpdate; }

    /**
     * Localized release notes for the effective UI language.
     */
    public String getReleaseNotes(String languageTag) {
        if (isRussian(languageTag) && hasText(releaseNotesRu)) {
            return releaseNotesRu;
        }
        return releaseNotes;
    }

    /**
     * Download URL for the current platform, or {@code null} when unavailable.
     */
    public String getDownloadUrl() {
        return getDownloadUrl(OsDetect.current(), OsDetect.currentPackageFormat());
    }

    /**
     * Full-archive artifact for the current platform, or {@code null} when the
     * manifest does not provide a non-privileged self-update package.
     */
    public SelfUpdateArtifact getSelfUpdateArtifact() {
        return getSelfUpdateArtifact(
                OsDetect.current(),
                OsDetect.currentPackageFormat(),
                OsDetect.normalizedArch()
        );
    }

    SelfUpdateArtifact getSelfUpdateArtifact(OsType osType,
                                             PackageFormat packageFormat,
                                             String arch) {
        if (selfUpdate == null || selfUpdate.isEmpty() || osType == null) {
            return null;
        }
        if (packageFormat == PackageFormat.FLATPAK) {
            return null;
        }

        for (String key : selfUpdateKeys(osType, packageFormat, arch)) {
            SelfUpdateArtifact artifact = selfUpdate.get(key);
            if (artifact != null && artifact.isFullArchive() && artifact.isZip()
                    && artifact.hasDownload()) {
                return artifact;
            }
        }
        return null;
    }

    String getDownloadUrl(OsType osType, boolean linuxAppImage) {
        return getDownloadUrl(
                osType,
                linuxAppImage ? PackageFormat.APPIMAGE : PackageFormat.DEB
        );
    }

    String getDownloadUrl(OsType osType, PackageFormat packageFormat) {
        if (downloads == null || downloads.isEmpty()) {
            return null;
        }

        return switch (osType) {
            case WINDOWS -> firstNonBlank(downloads.get("windows-msi"), downloads.get("windows"));
            case MACOS -> firstNonBlank(downloads.get("macos-dmg"), downloads.get("macos"));
            case LINUX -> switch (packageFormat) {
                case FLATPAK -> firstNonBlank(
                        downloads.get("linux-flatpak"),
                        downloads.get("linux"),
                        downloads.get("linux-deb"),
                        downloads.get("linux-appimage")
                );
                case APPIMAGE -> firstNonBlank(
                        downloads.get("linux-appimage"),
                        downloads.get("linux"),
                        downloads.get("linux-deb"),
                        downloads.get("linux-flatpak")
                );
                case DEB, UNKNOWN, MSI, DMG -> firstNonBlank(
                        downloads.get("linux-deb"),
                        downloads.get("linux"),
                        downloads.get("linux-appimage"),
                        downloads.get("linux-flatpak")
                );
            };
            case UNKNOWN -> null;
        };
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static Set<String> selfUpdateKeys(OsType osType,
                                              PackageFormat packageFormat,
                                              String arch) {
        String os = switch (osType) {
            case WINDOWS -> "windows";
            case MACOS -> "macos";
            case LINUX -> "linux";
            case UNKNOWN -> "unknown";
        };
        String normalizedArch = arch == null ? "" : arch.trim();
        String packageName = packageFormat == null
                ? ""
                : packageFormat.name().toLowerCase(java.util.Locale.ROOT);
        Set<String> keys = new LinkedHashSet<>();
        if (!packageName.isBlank() && !normalizedArch.isBlank()) {
            keys.add(os + "-" + packageName + "-" + normalizedArch);
        }
        if (!normalizedArch.isBlank()) {
            keys.add(os + "-" + normalizedArch);
        }
        if (!packageName.isBlank()) {
            keys.add(os + "-" + packageName);
        }
        keys.add(os);
        return keys;
    }

    private static boolean isRussian(String languageTag) {
        if (languageTag == null || languageTag.isBlank()) {
            return false;
        }
        return "ru".equalsIgnoreCase(
                java.util.Locale.forLanguageTag(languageTag.trim()).getLanguage()
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
