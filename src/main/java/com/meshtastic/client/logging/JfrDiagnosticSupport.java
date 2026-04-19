package com.meshtastic.client.logging;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Лёгкая JFR-диагностика: держит bounded recording в фоне и умеет по запросу
 * сбрасывать snapshot в указанный файл.
 */
public final class JfrDiagnosticSupport {

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
            initialized = true;
            try {
                recording = new Recording(Configuration.getConfiguration("profile"));
                recording.setName("MeshApp diagnostics");
                recording.setToDisk(true);
                recording.setMaxAge(MAX_JFR_AGE);
                recording.setMaxSize(MAX_JFR_SIZE_BYTES);
                recording.start();
            } catch (Throwable t) {
                recording = null;
                System.err.println("[MeshApp] JFR diagnostics unavailable: " + t.getMessage());
            }
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
}
