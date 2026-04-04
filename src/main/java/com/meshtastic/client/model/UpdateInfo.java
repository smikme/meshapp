package com.meshtastic.client.model;

import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.platform.OsDetect.OsType;

import java.util.Map;

/**
 * Информация о новой версии приложения.
 * Десериализуется из JSON с сервера обновлений.
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
     * URL для скачивания для текущей платформы, или null если недоступен.
     */
    public String getDownloadUrl() {
        return getDownloadUrl(OsDetect.current(), OsDetect.isLinuxAppImage());
    }

    String getDownloadUrl(OsType osType, boolean linuxAppImage) {
        if (downloads == null || downloads.isEmpty()) {
            return null;
        }

        return switch (osType) {
            case WINDOWS -> firstNonBlank(downloads.get("windows"));
            case MACOS -> firstNonBlank(downloads.get("macos"));
            case LINUX -> linuxAppImage
                    ? firstNonBlank(downloads.get("linux-appimage"), downloads.get("linux"), downloads.get("linux-deb"))
                    : firstNonBlank(downloads.get("linux-deb"), downloads.get("linux"), downloads.get("linux-appimage"));
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
