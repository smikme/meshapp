package com.meshtastic.client.terminal;

import java.util.Locale;

/**
 * Runtime and exception helpers for terminal startup.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class TerminalRuntimeSupport {

    private TerminalRuntimeSupport() {
    }

    static String shortError(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    static boolean isMissingControllingTty(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("/dev/tty") || message.contains("Device not configured"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
