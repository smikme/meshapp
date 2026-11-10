package com.meshtastic.client.tray;

import com.meshtastic.client.components.PacketMonitorWindow;
import com.meshtastic.client.platform.NativeWindowHelper;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.awt.desktop.AppReopenedListener;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Single control point for the tray icon/status item and minimize-to-tray behavior.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class AppTrayManager {

    private static final Logger log = LoggerFactory.getLogger(AppTrayManager.class);
    private static final long HARD_EXIT_FALLBACK_DELAY_MS = 1500L;

    private static final AppTrayManager INSTANCE = new AppTrayManager();

    private final AtomicBoolean exiting = new AtomicBoolean(false);
    private final Supplier<AppTrayService> serviceFactory;
    private final Runnable shutdownAction;

    private AppTrayService service = new NoOpTrayService();
    private Stage primaryStage;
    private boolean initialized;
    private boolean trayAvailable;
    private boolean hideToTrayPending;
    private boolean suppressIconifiedHook;
    private boolean macWindowHiddenToTray;
    private AppReopenedListener macAppReopenedListener;

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
            service = serviceFactory.get();
            trayAvailable = service.install(this::restoreWindow, this::exitApplication);
        } catch (Throwable t) {
            trayAvailable = false;
            service = new NoOpTrayService();
            log.error("Failed to initialize application tray", t);
        }

        // The tray owns application lifetime even while no JavaFX windows are visible.
        // Keep this policy stable across hide/show cycles; explicit Exit still calls Platform.exit().
        runOnFxThread(() -> Platform.setImplicitExit(!trayAvailable));
        installStageHooks(stage);
        installMacAppReopenedHandler();
        initialized = true;
    }

    public synchronized void dispose() {
        service.dispose();
        uninstallMacAppReopenedHandler();
        service = new NoOpTrayService();
        trayAvailable = false;
        primaryStage = null;
        initialized = false;
        hideToTrayPending = false;
        suppressIconifiedHook = false;
        macWindowHiddenToTray = false;
        macAppReopenedListener = null;
        exiting.set(false);
        runOnFxThread(() -> Platform.setImplicitExit(true));
    }

    public boolean isAvailable() {
        return trayAvailable;
    }

    /** Handles the primary window's close button or a system close request. */
    public void handleCloseRequest(WindowEvent event) {
        // The default close handler would hide the stage synchronously and bypass
        // both the deferred tray transition and explicit application shutdown.
        event.consume();
        runOnFxThread(() -> {
            if (exiting.get()) {
                return;
            }
            if (shouldMinimizeToTray()) {
                hideToTray();
            } else {
                exitApplication();
            }
        });
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
        runOnFxThread(() -> {
            Stage stage = primaryStage;
            if (stage == null || !shouldMinimizeToTray() || exiting.get() || hideToTrayPending) {
                return;
            }

            hideToTrayPending = true;
            // Stage.hide() disposes the native window. Let the current button/native
            // event finish before destroying the window that is dispatching it.
            Platform.runLater(() -> hideWindowToTray(stage));
        });
    }

    private void hideWindowToTray(Stage stage) {
        if (stage != primaryStage || !hideToTrayPending) {
            return;
        }
        hideToTrayPending = false;
        if (!shouldMinimizeToTray() || exiting.get()) {
            return;
        }

        suppressIconifiedHook = true;
        try {
            Platform.setImplicitExit(false);
            PacketMonitorWindow.hideWindowIfOpen();
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
    }

    public void restoreWindow() {
        Stage stage = primaryStage;
        if (stage == null) {
            return;
        }

        runOnFxThread(() -> {
            hideToTrayPending = false;
            if (exiting.get()) {
                return;
            }

            suppressIconifiedHook = true;
            try {
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
                PacketMonitorWindow.restoreWindowIfOpen();
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
        shutdownAction.run();
    }

    private static void shutdownApplication() {
        scheduleHardExitFallback();
        runOnFxThread(() -> {
            PacketMonitorWindow.closeWindowIfOpen();
            Platform.setImplicitExit(true);
            Platform.exit();
        });
    }

    private void installStageHooks(Stage stage) {
        if (OsDetect.isMacOs()) {
            stage.showingProperty().addListener((_, _, isShowing) -> {
                if (!isShowing || exiting.get()) {
                    return;
                }
                Platform.runLater(this::restoreAuxiliaryWindows);
            });
            stage.iconifiedProperty().addListener((_, _, isIconified) -> {
                if (isIconified || suppressIconifiedHook || exiting.get()) {
                    return;
                }
                Platform.runLater(this::restoreAuxiliaryWindows);
            });
            if (!AppPreferences.isDisableEffectsEffective()) {
                return;
            }
        }
        stage.iconifiedProperty().addListener((_, _, isIconified) -> {
            if (!isIconified || suppressIconifiedHook || exiting.get()) {
                return;
            }
            if (!shouldMinimizeToTray()) {
                return;
            }
            hideToTray();
        });
    }

    private void restoreAuxiliaryWindows() {
        if (exiting.get()) {
            return;
        }
        PacketMonitorWindow.restoreWindowIfOpen();
    }

    private synchronized void installMacAppReopenedHandler() {
        if (!OsDetect.isMacOs() || macAppReopenedListener != null) {
            return;
        }
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                return;
            }
            macAppReopenedListener = _ -> restoreWindow();
            desktop.addAppEventListener(macAppReopenedListener);
        } catch (Throwable t) {
            macAppReopenedListener = null;
            log.debug("Failed to install macOS app reopen handler", t);
        }
    }

    private synchronized void uninstallMacAppReopenedHandler() {
        if (macAppReopenedListener == null || !Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().removeAppEventListener(macAppReopenedListener);
        } catch (Throwable t) {
            log.debug("Failed to remove macOS app reopen handler", t);
        }
    }

    private boolean shouldMinimizeToTray() {
        return trayAvailable && AppPreferences.isMinimizeToTray();
    }

    private static AppTrayService createService() {
        return switch (OsDetect.current()) {
            case MACOS -> new MacOsNativeTrayService();
            case WINDOWS -> new AwtAppTrayService();
            case LINUX -> new LinuxGtkTrayService();
            default -> new NoOpTrayService();
        };
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private static void scheduleHardExitFallback() {
        Thread fallbackThread = new Thread(() -> {
            try {
                Thread.sleep(HARD_EXIT_FALLBACK_DELAY_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            log.warn("JavaFX shutdown fallback triggered, forcing JVM exit");
            System.exit(0);
        }, "meshapp-exit-fallback");
        fallbackThread.setDaemon(true);
        fallbackThread.start();
    }

    private AppTrayManager() {
        this(AppTrayManager::createService, AppTrayManager::shutdownApplication);
    }

    AppTrayManager(Supplier<AppTrayService> serviceFactory, Runnable shutdownAction) {
        this.serviceFactory = serviceFactory;
        this.shutdownAction = shutdownAction;
    }

    private static final class NoOpTrayService implements AppTrayService {
        @Override
        public boolean install(Runnable onActivate, Runnable onExit) {
            return false;
        }
    }
}
