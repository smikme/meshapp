package com.meshtastic.client.connection.serial;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.platform.OsDetect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;

/**
 * Linux serial-port access diagnostics shared by discovery and native open().
 */
public final class SerialPortAccessAdvisor {

    private static final int EPERM = 1;
    private static final int ENOENT = 2;
    private static final int EACCES = 13;
    private static final int EBUSY = 16;

    private SerialPortAccessAdvisor() {}

    public record PortAccess(boolean accessible, String warning) {}

    public static PortAccess check(String portName) {
        if (!OsDetect.isLinux()) {
            return new PortAccess(true, null);
        }

        Path path = portPath(portName);
        try {
            if (!Files.exists(path) || (Files.isReadable(path) && Files.isWritable(path))) {
                return new PortAccess(true, null);
            }
            return new PortAccess(false, linuxPermissionMessage(path));
        } catch (SecurityException e) {
            return new PortAccess(false, linuxPermissionMessage(path));
        }
    }

    static Path portPath(String portName) {
        String value = portName == null ? "" : portName.trim();
        return Path.of(value.startsWith("/dev/") ? value : "/dev/" + value);
    }

    public static String openFailureMessage(String path, String nativeError, int errno, boolean linux) {
        String fallbackError = nativeError == null || nativeError.isBlank()
                ? "unknown error"
                : nativeError;
        if (errno == 0 && "success".equalsIgnoreCase(fallbackError)) {
            fallbackError = "unknown error";
        }
        if (isMissingError(errno, fallbackError) || (errno == 0 && deviceMissing(path))) {
            return I18n.t("connection.serial.error.deviceNotFound", path);
        }
        if (linux && isPermissionError(errno, fallbackError)) {
            return linuxPermissionMessage(Path.of(path));
        }
        if (linux && isBusyError(errno, fallbackError)) {
            return linuxBusyMessage(path);
        }
        return I18n.t("connection.serial.error.open", path, fallbackError);
    }

    static boolean isPermissionError(int errno, String nativeError) {
        if (errno == EPERM || errno == EACCES) {
            return true;
        }
        String normalized = nativeError == null ? "" : nativeError.toLowerCase(Locale.ROOT);
        return normalized.contains("permission denied") || normalized.contains("operation not permitted");
    }

    static boolean isMissingError(int errno, String nativeError) {
        if (errno == ENOENT) {
            return true;
        }
        String normalized = nativeError == null ? "" : nativeError.toLowerCase(Locale.ROOT);
        return normalized.contains("no such file") || normalized.contains("not found");
    }

    static boolean isBusyError(int errno, String nativeError) {
        if (errno == EBUSY) {
            return true;
        }
        String normalized = nativeError == null ? "" : nativeError.toLowerCase(Locale.ROOT);
        return normalized.contains("device or resource busy") || normalized.contains("resource busy");
    }

    static String linuxPermissionMessage(Path path) {
        PosixDetails details = readPosixDetails(path);
        String group = recommendedGroup(details.group());
        StringBuilder message = new StringBuilder(I18n.t("connection.serial.permission", path));
        if (details.present()) {
            message.append(I18n.t("connection.serial.permissionDetails",
                    details.permissions(),
                    details.owner(),
                    details.group()));
        }
        message.append(I18n.t("connection.serial.permissionFix", group));
        return message.toString();
    }

    private static String linuxBusyMessage(String path) {
        return I18n.t("connection.serial.busy", path);
    }

    private static boolean deviceMissing(String path) {
        try {
            return Files.notExists(Path.of(path));
        } catch (Exception e) {
            return false;
        }
    }

    private static String recommendedGroup(String group) {
        if (group == null || group.isBlank() || "root".equals(group)) {
            return "dialout";
        }
        return group;
    }

    private static PosixDetails readPosixDetails(Path path) {
        try {
            PosixFileAttributes attrs = Files.readAttributes(path, PosixFileAttributes.class);
            return new PosixDetails(
                    true,
                    attrs.owner().getName(),
                    attrs.group().getName(),
                    formatPermissions(attrs.permissions())
            );
        } catch (Exception e) {
            return new PosixDetails(false, "", "", "");
        }
    }

    private static String formatPermissions(Set<PosixFilePermission> permissions) {
        StringBuilder value = new StringBuilder(9);
        appendPermission(value, permissions, PosixFilePermission.OWNER_READ, 'r');
        appendPermission(value, permissions, PosixFilePermission.OWNER_WRITE, 'w');
        appendPermission(value, permissions, PosixFilePermission.OWNER_EXECUTE, 'x');
        appendPermission(value, permissions, PosixFilePermission.GROUP_READ, 'r');
        appendPermission(value, permissions, PosixFilePermission.GROUP_WRITE, 'w');
        appendPermission(value, permissions, PosixFilePermission.GROUP_EXECUTE, 'x');
        appendPermission(value, permissions, PosixFilePermission.OTHERS_READ, 'r');
        appendPermission(value, permissions, PosixFilePermission.OTHERS_WRITE, 'w');
        appendPermission(value, permissions, PosixFilePermission.OTHERS_EXECUTE, 'x');
        return value.toString();
    }

    private static void appendPermission(StringBuilder value,
                                         Set<PosixFilePermission> permissions,
                                         PosixFilePermission permission,
                                         char marker) {
        value.append(permissions.contains(permission) ? marker : '-');
    }

    private record PosixDetails(boolean present, String owner, String group, String permissions) {}
}
