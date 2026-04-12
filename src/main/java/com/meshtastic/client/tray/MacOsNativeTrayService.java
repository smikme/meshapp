package com.meshtastic.client.tray;

import com.meshtastic.client.platform.NativeMacOsWindowControl;
import javafx.stage.Stage;

/**
 * macOS tray/status item через in-process native bridge.
 */
public class MacOsNativeTrayService implements AppTrayService {

    @Override
    public boolean install(Runnable onActivate, Runnable onExit) {
        return MacOsTrayBridge.install(onActivate);
    }

    @Override
    public void dispose() {
        MacOsTrayBridge.dispose();
    }

    public static void activateApplication() {
        MacOsTrayBridge.activateApplication();
    }

    public static void focusWindow(Stage stage) {
        if (stage == null) {
            return;
        }
        var window = new NativeMacOsWindowControl(stage);
        window.makeVisibleInAppSwitcher();
        if (!MacOsTrayBridge.focusWindow(window.getNativeWindowHandle(), window.getNativeViewHandle())) {
            window.makeKeyAndOrderFront();
            window.focusTextInputView();
        }
    }

    public static void hideWindow(Stage stage) {
        if (stage == null) {
            return;
        }
        new NativeMacOsWindowControl(stage).hideToTray();
    }

    public static void restoreWindow(Stage stage) {
        if (stage == null) {
            return;
        }
        var window = new NativeMacOsWindowControl(stage);
        window.restoreFromTray();
        window.makeVisibleInAppSwitcher();
    }
}
