package com.meshtastic.client.tray;

import com.meshtastic.client.platform.NativeWindowHelper;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Единая точка управления tray icon/status item и поведением minimize-to-tray.
 */
public final class AppTrayManager {

    private static final Logger log = LoggerFactory.getLogger(AppTrayManager.class);

    private static final AppTrayManager INSTANCE = new AppTrayManager();

    private final AtomicBoolean exiting = new AtomicBoolean(false);

    private AppTrayService service = new NoOpTrayService();
    private Stage primaryStage;
    private boolean initialized;
    private boolean trayAvailable;
    private boolean suppressIconifiedHook;
    private boolean macWindowHiddenToTray;

    public static AppTrayManager getInstance() {
        return INSTANCE;
    }

    public synchronized void initialize(Stage stage) {
        if (initialized) {
            primaryStage = stage;
            return;
        }

        exiting.set(false);
        primaryStage = stage;
        service = new NoOpTrayService();

        try {
            service = createService();
            trayAvailable = service.install(this::restoreWindow, this::exitApplication);
        } catch (Throwable t) {
            trayAvailable = false;
            service = new NoOpTrayService();
            log.error("Failed to initialize application tray", t);
        }

        installStageHooks(stage);
        initialized = true;
    }

    public synchronized void dispose() {
        service.dispose();
        service = new NoOpTrayService();
        trayAvailable = false;
        primaryStage = null;
        initialized = false;
        suppressIconifiedHook = false;
        macWindowHiddenToTray = false;
        exiting.set(false);
        runOnFxThread(() -> Platform.setImplicitExit(true));
    }

    public boolean isAvailable() {
        return trayAvailable;
    }

    public void requestMinimize() {
        Stage stage = primaryStage;
        if (stage == null) {
            return;
        }

        if (shouldMinimizeToTray()) {
            hideToTray();
            return;
        }

        runOnFxThread(() -> stage.setIconified(true));
    }

    public void hideToTray() {
        if (!shouldMinimizeToTray()) {
            return;
        }

        Stage stage = primaryStage;
        if (stage == null) {
            return;
        }

        runOnFxThread(() -> {
            if (!trayAvailable || exiting.get()) {
                return;
            }

            suppressIconifiedHook = true;
            try {
                Platform.setImplicitExit(false);
                if (OsDetect.isMacOs()) {
                    if (AppPreferences.isDisableEffectsEffective()) {
                        if (stage.isShowing()) {
                            stage.hide();
                        }
                    } else {
                        MacOsNativeTrayService.hideWindow(stage);
                    }
                    macWindowHiddenToTray = true;
                } else {
                    if (stage.isIconified()) {
                        stage.setIconified(false);
                    }
                    if (stage.isShowing()) {
                        stage.hide();
                    }
                }
            } finally {
                suppressIconifiedHook = false;
            }
        });
    }

    public void restoreWindow() {
        Stage stage = primaryStage;
        if (stage == null) {
            return;
        }

        runOnFxThread(() -> {
            if (exiting.get()) {
                return;
            }

            suppressIconifiedHook = true;
            try {
                Platform.setImplicitExit(true);
                if (OsDetect.isMacOs() && macWindowHiddenToTray) {
                    if (AppPreferences.isDisableEffectsEffective()) {
                        if (stage.isIconified()) {
                            stage.setIconified(false);
                        }
                        if (!stage.isShowing()) {
                            stage.show();
                        }
                    } else {
                        MacOsNativeTrayService.restoreWindow(stage);
                    }
                    macWindowHiddenToTray = false;
                } else {
                    if (stage.isIconified()) {
                        stage.setIconified(false);
                    }
                    if (!stage.isShowing()) {
                        stage.show();
                    }
                }
            } finally {
                suppressIconifiedHook = false;
            }

            Platform.runLater(() -> {
                if (exiting.get()) {
                    return;
                }
                if (OsDetect.isWindows()) {
                    NativeWindowHelper.applyNativeEffects(stage, AppPreferences.isDarkMode());
                }
                stage.toFront();
                stage.requestFocus();
                if (OsDetect.isMacOs()) {
                    MacOsNativeTrayService.activateApplication();
                }
            });
        });
    }

    public void showNotification(String title, String message) {
        service.showNotification(title, message);
    }

    public void exitApplication() {
        if (!exiting.compareAndSet(false, true)) {
            return;
        }
        runOnFxThread(() -> {
            Platform.setImplicitExit(true);
            Platform.exit();
        });
    }

    private void installStageHooks(Stage stage) {
        if (OsDetect.isMacOs() && !AppPreferences.isDisableEffectsEffective()) {
            return;
        }
        stage.iconifiedProperty().addListener((obs, wasIconified, isIconified) -> {
            if (!isIconified || suppressIconifiedHook || exiting.get()) {
                return;
            }
            if (!shouldMinimizeToTray()) {
                return;
            }
            Platform.runLater(this::hideToTray);
        });
    }

    private boolean shouldMinimizeToTray() {
        return trayAvailable && AppPreferences.isMinimizeToTray();
    }

    private AppTrayService createService() {
        return switch (OsDetect.current()) {
            case MACOS -> new MacOsNativeTrayService();
            case WINDOWS -> new AwtAppTrayService();
            case LINUX -> new LinuxGtkTrayService();
            default -> new NoOpTrayService();
        };
    }

    private void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private AppTrayManager() {}

    private static final class NoOpTrayService implements AppTrayService {
        @Override
        public boolean install(Runnable onActivate, Runnable onExit) {
            return false;
        }
    }
}
