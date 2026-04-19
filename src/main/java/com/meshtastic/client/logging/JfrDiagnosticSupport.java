package com.meshtastic.client.logging;

import com.meshtastic.client.utils.AppPreferences;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Лёгкая JFR-диагностика: держит bounded recording в фоне и умеет по запросу
 * сбрасывать snapshot в указанный файл.
 */
public final class JfrDiagnosticSupport {

    static final String JFR_ENABLED_PROPERTY = "meshapp.diagnostics.jfr.enabled";
    private static final Object LOCK = new Object();
    private static final long MAX_JFR_SIZE_BYTES = 16L * 1024 * 1024;
    private static final Duration MAX_JFR_AGE = Duration.ofHours(2);

    private static boolean initialized;
    private static Recording recording;

    private JfrDiagnosticSupport() {}

    public static void start() {
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            if (!isEnabled()) {
                recording = null;
                return;
            }
            initialized = true;
            try {
                // JFR is explicitly opt-in because even bounded background
                // recording can add visible overhead in UI-heavy flows.
                Recording newRecording = new Recording(loadPreferredConfiguration());
                applyPlatformSafetyWorkarounds(newRecording,
                        System.getProperty("os.name", ""),
                        System.getProperty("os.version", ""));
                newRecording.setName("MeshApp diagnostics");
                newRecording.setToDisk(true);
                newRecording.setMaxAge(MAX_JFR_AGE);
                newRecording.setMaxSize(MAX_JFR_SIZE_BYTES);
                newRecording.start();
                recording = newRecording;
            } catch (Throwable t) {
                recording = null;
                System.err.println("[MeshApp] JFR diagnostics unavailable: " + t.getMessage());
            }
        }
    }

    static boolean isEnabled() {
        String propertyValue = System.getProperty(JFR_ENABLED_PROPERTY);
        String envValue = System.getenv("MESHAPP_JFR");
        return isEnabled(propertyValue, envValue, AppPreferences.isJfrDiagnosticsEnabled());
    }

    static boolean isEnabled(String propertyValue, String envValue, boolean preferenceEnabled) {
        if (propertyValue != null) {
            return Boolean.parseBoolean(propertyValue);
        }
        if (envValue != null) {
            return Boolean.parseBoolean(envValue);
        }
        return preferenceEnabled;
    }

    private static Configuration loadPreferredConfiguration() throws Throwable {
        try {
            return Configuration.getConfiguration("default");
        } catch (Throwable ignored) {
            return Configuration.getConfiguration("profile");
        }
    }

    public static void dumpSnapshot(Path target) {
        if (target == null) {
            return;
        }
        synchronized (LOCK) {
            if (recording == null) {
                return;
            }
            try {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                recording.dump(target);
            } catch (Throwable t) {
                System.err.println("[MeshApp] Failed to dump JFR snapshot: " + t.getMessage());
            }
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            if (recording == null) {
                initialized = false;
                return;
            }
            try {
                recording.stop();
            } catch (Throwable ignored) {
                // Ничего: закрываем recording в любом случае.
            }
            try {
                recording.close();
            } catch (Throwable t) {
                System.err.println("[MeshApp] Failed to close JFR diagnostics: " + t.getMessage());
            } finally {
                recording = null;
                initialized = false;
            }
        }
    }

    static void applyPlatformSafetyWorkarounds(Recording recording, String osName, String osVersion) {
        if (recording == null || !shouldDisableExecutionSampling(osName)) {
            return;
        }
        // CoreBluetooth/JNA callback threads on macOS can trip HotSpot's JFR sampler
        // (observed as a fatal crash in JFR Thread Sampler / PosixSignals::do_suspend).
        disableEvent(recording, "jdk.ExecutionSample");
        disableEvent(recording, "jdk.NativeMethodSample");
        System.err.println("[MeshApp] JFR execution sampling disabled on "
                + formatPlatform(osName, osVersion)
                + " to avoid a known macOS/JNA JVM crash");
    }

    static boolean shouldDisableExecutionSampling(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    private static void disableEvent(Recording recording, String eventName) {
        try {
            recording.disable(eventName);
        } catch (IllegalArgumentException ignored) {
            // Event is not present in this runtime/profile; nothing to disable.
        }
    }

    private static String formatPlatform(String osName, String osVersion) {
        String normalizedName = osName == null || osName.isBlank() ? "unknown OS" : osName.trim();
        if (osVersion == null || osVersion.isBlank()) {
            return normalizedName;
        }
        return normalizedName + " " + osVersion.trim();
    }
}
