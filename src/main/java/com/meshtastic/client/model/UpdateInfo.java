package com.meshtastic.client.model;

import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.platform.OsDetect.OsType;
import com.meshtastic.client.platform.OsDetect.PackageFormat;

import java.util.Map;

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
    private Map<String, String> downloads;

    public String getVersion() { return version; }
    public int getVersionCode() { return versionCode; }
    public String getReleaseNotes() { return releaseNotes; }
    public Map<String, String> getDownloads() { return downloads; }

    /**
     * Download URL for the current platform, or {@code null} when unavailable.
     */
    public String getDownloadUrl() {
        return getDownloadUrl(OsDetect.current(), OsDetect.currentPackageFormat());
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
}
