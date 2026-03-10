package com.meshtastic.client.model;

import com.meshtastic.client.platform.OsDetect;

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
        if (downloads == null) return null;
        if (OsDetect.isWindows()) return downloads.get("windows");
        if (OsDetect.isMacOs()) return downloads.get("macos");
        if (OsDetect.isLinux()) return downloads.get("linux");
        return null;
    }
}
