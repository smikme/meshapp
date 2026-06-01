package com.meshtastic.client.tray;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.utils.NativeResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JNI bridge к нативной macOS tray-библиотеке.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class MacOsTrayBridge {

    private static final Logger log = LoggerFactory.getLogger(MacOsTrayBridge.class);

    private static final Runnable NO_OP = () -> {};
    private static final AtomicBoolean LIBRARY_LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private static volatile Runnable activateHandler = NO_OP;
    private static volatile Path trayIconPath;

    static synchronized boolean install(Runnable onActivate) {
        activateHandler = onActivate != null ? onActivate : NO_OP;
        ensureLibraryLoaded();

        boolean installed = install0(resolveTrayIconPath(), I18n.t("app.title"));
        INSTALLED.set(installed);
        if (installed) {
            log.info("macOS tray bridge initialized");
        } else {
            log.warn("macOS tray bridge install returned false");
        }
        if (!installed) {
            activateHandler = NO_OP;
        }
        return installed;
    }

    static synchronized void dispose() {
        activateHandler = NO_OP;
        if (!INSTALLED.get()) {
            return;
        }
        try {
            dispose0();
        } finally {
            INSTALLED.set(false);
        }
    }

    static void activateApplication() {
        if (!LIBRARY_LOADED.get()) {
            return;
        }
        try {
            activate0();
        } catch (UnsatisfiedLinkError e) {
            log.debug("macOS tray bridge activate failed", e);
        }
    }

    static boolean focusWindow(long nsWindow, long nsView) {
        if (!LIBRARY_LOADED.get() || nsWindow == 0) {
            return false;
        }
        try {
            return focusWindow0(nsWindow, nsView);
        } catch (UnsatisfiedLinkError e) {
            log.debug("macOS tray bridge focusWindow failed", e);
            return false;
        }
    }

    @SuppressWarnings("unused") // called from JNI
    private static void handleClickFromNative() {
        Runnable handler = activateHandler;
        if (handler != null) {
            handler.run();
        }
    }

    private static synchronized void ensureLibraryLoaded() {
        if (LIBRARY_LOADED.get()) {
            return;
        }
        NativeResourceLoader.loadLibrary("meshapp-tray");
        LIBRARY_LOADED.set(true);
    }

    private static String resolveTrayIconPath() {
        Path current = trayIconPath;
        if (current != null) {
            return current.toAbsolutePath().toString();
        }

        try {
            Path extracted = TrayIconResources.extractMacOsTrayIcon();
            trayIconPath = extracted;
            return extracted.toAbsolutePath().toString();
        } catch (RuntimeException e) {
            log.debug("Failed to extract tray icon resource", e);
            return null;
        }
    }

    private static native boolean install0(String iconPath, String toolTip);
    private static native void dispose0();
    private static native void activate0();
    private static native boolean focusWindow0(long nsWindow, long nsView);

    private MacOsTrayBridge() {}
}
