package com.meshtastic.client.platform;

import java.util.Map;
import java.util.Locale;

/**
 * Определение операционной системы.
 */
public final class OsDetect {

    public enum OsType { WINDOWS, MACOS, LINUX, UNKNOWN }

    private static final OsType CURRENT;

    static {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            CURRENT = OsType.WINDOWS;
        } else if (os.contains("mac") || os.contains("darwin")) {
            CURRENT = OsType.MACOS;
        } else if (os.contains("nux") || os.contains("nix")) {
            CURRENT = OsType.LINUX;
        } else {
            CURRENT = OsType.UNKNOWN;
        }
    }

    public static OsType current() { return CURRENT; }
    public static boolean isWindows() { return CURRENT == OsType.WINDOWS; }
    public static boolean isMacOs() { return CURRENT == OsType.MACOS; }
    public static boolean isLinux() { return CURRENT == OsType.LINUX; }
    public static boolean isLinuxAppImage() { return isLinuxAppImage(CURRENT, System.getenv()); }

    /** Поддерживает ли ОС объединённый title bar + backdrop эффекты */
    public static boolean supportsSeamlessFrame() {
        return isWindows() || isMacOs();
    }

    static boolean isLinuxAppImage(OsType osType, Map<String, String> env) {
        if (osType != OsType.LINUX || env == null) {
            return false;
        }
        return hasValue(env.get("APPIMAGE")) || hasValue(env.get("APPDIR"));
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private OsDetect() {}
}
