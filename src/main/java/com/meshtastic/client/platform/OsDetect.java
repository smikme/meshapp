package com.meshtastic.client.platform;

/**
 * Определение операционной системы.
 */
public final class OsDetect {

    public enum OsType { WINDOWS, MACOS, LINUX, UNKNOWN }

    private static final OsType CURRENT;

    static {
        String os = System.getProperty("os.name", "").toLowerCase();
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

    /** Поддерживает ли ОС объединённый title bar + backdrop эффекты */
    public static boolean supportsSeamlessFrame() {
        return isWindows() || isMacOs();
    }

    private OsDetect() {}
}
