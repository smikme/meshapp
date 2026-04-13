package com.meshtastic.client.platform;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.util.Map;
import java.util.Locale;

/**
 * Определение операционной системы.
 */
public final class OsDetect {

    public enum OsType { WINDOWS, MACOS, LINUX, UNKNOWN }
    public enum PackageFormat { MSI, DMG, DEB, APPIMAGE, FLATPAK, UNKNOWN }

    private static final OsType CURRENT;
    private static final int WINDOWS_11_BUILD = 22000;
    private static final String WINDOWS_CURRENT_VERSION_KEY = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion";
    private static final Integer WINDOWS_BUILD_NUMBER;

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

        WINDOWS_BUILD_NUMBER = CURRENT == OsType.WINDOWS ? detectWindowsBuildNumber() : null;
    }

    public static OsType current() { return CURRENT; }
    public static boolean isWindows() { return CURRENT == OsType.WINDOWS; }
    public static boolean isWindows10() {
        return isWindows() && WINDOWS_BUILD_NUMBER != null && WINDOWS_BUILD_NUMBER < WINDOWS_11_BUILD;
    }
    public static boolean isWindows11OrGreater() {
        return isWindows() && WINDOWS_BUILD_NUMBER != null && WINDOWS_BUILD_NUMBER >= WINDOWS_11_BUILD;
    }
    public static boolean isMacOs() { return CURRENT == OsType.MACOS; }
    public static boolean isLinux() { return CURRENT == OsType.LINUX; }
    public static boolean isLinuxAppImage() { return isLinuxAppImage(CURRENT, System.getenv()); }
    public static boolean isLinuxFlatpak() { return isLinuxFlatpak(CURRENT, System.getenv()); }
    public static PackageFormat currentPackageFormat() { return detectPackageFormat(CURRENT, System.getenv()); }
    public static String normalizedArch() { return normalizeArch(System.getProperty("os.arch", "")); }

    /** Поддерживает ли ОС объединённый title bar + backdrop эффекты */
    public static boolean supportsSeamlessFrame() {
        return isMacOs() || isWindows11OrGreater();
    }

    static boolean isLinuxAppImage(OsType osType, Map<String, String> env) {
        if (osType != OsType.LINUX || env == null) {
            return false;
        }
        return hasValue(env.get("APPIMAGE")) || hasValue(env.get("APPDIR"));
    }

    static boolean isLinuxFlatpak(OsType osType, Map<String, String> env) {
        if (osType != OsType.LINUX || env == null) {
            return false;
        }
        return hasValue(env.get("FLATPAK_ID"))
                || hasValue(env.get("FLATPAK_APP_ID"))
                || "flatpak".equalsIgnoreCase(env.get("container"));
    }

    static PackageFormat detectPackageFormat(OsType osType, Map<String, String> env) {
        return switch (osType) {
            case WINDOWS -> PackageFormat.MSI;
            case MACOS -> PackageFormat.DMG;
            case LINUX -> {
                if (isLinuxFlatpak(osType, env)) {
                    yield PackageFormat.FLATPAK;
                }
                if (isLinuxAppImage(osType, env)) {
                    yield PackageFormat.APPIMAGE;
                }
                yield PackageFormat.DEB;
            }
            case UNKNOWN -> PackageFormat.UNKNOWN;
        };
    }

    public static String normalizeArch(String arch) {
        String normalized = arch == null ? "" : arch.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> normalized;
        };
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private static Integer detectWindowsBuildNumber() {
        try {
            String build = Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_LOCAL_MACHINE,
                    WINDOWS_CURRENT_VERSION_KEY,
                    "CurrentBuildNumber");
            if (hasValue(build)) {
                return Integer.parseInt(build.trim());
            }
        } catch (Throwable ignored) {
            // Fall through to CurrentBuild/os.version fallback.
        }

        try {
            String build = Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_LOCAL_MACHINE,
                    WINDOWS_CURRENT_VERSION_KEY,
                    "CurrentBuild");
            if (hasValue(build)) {
                return Integer.parseInt(build.trim());
            }
        } catch (Throwable ignored) {
            // Fall through to os.version fallback.
        }

        try {
            String osVersion = System.getProperty("os.version", "").trim();
            if (!osVersion.isEmpty()) {
                String[] parts = osVersion.split("\\.");
                if (parts.length >= 3) {
                    return Integer.parseInt(parts[2]);
                }
            }
        } catch (Throwable ignored) {
            // Unknown Windows build; caller will treat it as unsupported.
        }
        return null;
    }

    private OsDetect() {}
}
