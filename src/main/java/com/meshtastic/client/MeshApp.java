package com.meshtastic.client;

import com.meshtastic.client.components.CrashReportFlow;
import com.meshtastic.client.components.DatabaseRecoveryDialog;
import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.components.EmojiRenderingSupport;
import com.meshtastic.client.components.LuaDevWindow;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.logging.JfrDiagnosticSupport;
import com.meshtastic.client.logging.SessionCrashLogManager;
import com.meshtastic.client.lua.LuaScriptRuntimeService;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.platform.NativeWindowHelper;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.service.BleDeviceDiscoveryService;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.DatabaseProvider;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.service.PacketMonitorService;
import com.meshtastic.client.service.UpdateCheckService;
import com.meshtastic.client.system.AppUi;
import com.meshtastic.client.system.FormManager;
import com.meshtastic.client.system.JavaFxAppUiBridge;
import com.meshtastic.client.system.RootPane;
import com.meshtastic.client.system.SingleInstanceGuard;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.tray.AppTrayManager;
import com.meshtastic.client.utils.AppPreferences;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class MeshApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MeshApp.class);
    private static final AppVersion.Info APPLICATION_INFO = AppVersion.resolve(MeshApp.class);

    public static final String APPLICATION_VERSION = APPLICATION_INFO.version();
    public static final int VERSION_CODE = APPLICATION_INFO.versionCode();

    private static void logStartupContext() {
        log.info(
            "Starting MeshApp version {} (build {}) on {} {} ({})",
            APPLICATION_VERSION,
            VERSION_CODE,
            System.getProperty("os.name", "unknown").trim(),
            System.getProperty("os.version", "unknown").trim(),
            System.getProperty("os.arch", "unknown").trim()
        );
    }

    private static Stage primaryStage;
    private static final Object SINGLE_INSTANCE_LOCK = new Object();
    private static final long UI_THREAD_STALL_THRESHOLD_NANOS =
        TimeUnit.SECONDS.toNanos(15);
    private static final long UI_THREAD_STALL_CAPTURE_COOLDOWN_NANOS =
        TimeUnit.MINUTES.toNanos(1);

    private static SingleInstanceGuard singleInstanceGuard;

    private final AtomicBoolean deferredStartupTasksStarted = new AtomicBoolean(
        false
    );
    private final AtomicLong lastUiHeartbeatNanos = new AtomicLong(
        System.nanoTime()
    );
    private final AtomicBoolean uiFreezeCaptureInProgress = new AtomicBoolean(
        false
    );

    private ScheduledExecutorService uiWatchdog;
    private volatile long lastUiFreezeCaptureNanos;

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        AppUi.install(new JavaFxAppUiBridge());

        Font.loadFont(
            getClass().getResourceAsStream("/fonts/Roboto-Regular.ttf"),
            13
        );
        Font.loadFont(
            getClass().getResourceAsStream("/fonts/Roboto-Bold.ttf"),
            13
        );

        AppPreferences.init();
        I18n.initFromPreferences();
        DatabaseProvider.setRecoveryExecutor((dbFile, task) ->
                DatabaseRecoveryDialog.run(stage, dbFile, task));

        // Preload common emoji to make first rendering faster.
        EmojiImageCache.preloadCommonEmojis();

        // Initialize MessageDbService and run JSON -> H2 migration on first launch.
        MessageDbService.getInstance().migrateFromJsonHistory();
        MessageDbService.getInstance().markStaleSendingAsFailed();
        Runtime.getRuntime().addShutdownHook(
            new Thread(() -> {
                MessageDbService.closeIfInitialized();
                NodeCacheService.closeIfInitialized();
                PacketMonitorService.closeIfInitialized();
                DatabaseProvider.close();
            })
        );

        // Seamless frame: StageStyle.UNIFIED must be applied before the scene is created.
        NativeWindowHelper.prepareStage(stage);

        RootPane rootPane = new RootPane();
        Scene scene = new Scene(rootPane, 1010, 750);
        if (
            !AppPreferences.isDisableEffectsEffective() &&
            OsDetect.supportsSeamlessFrame()
        ) {
            // For a transparent stage, fill must be transparent before the first show().
            // Otherwise the first frame can render as opaque, and the backdrop appears
            // only after the next major repaint, such as a theme switch.
            scene.setFill(Color.TRANSPARENT);
        }

        boolean isDark = AppPreferences.isDarkMode();
        ThemeManager.applyTheme(scene, isDark);

        FormManager.install(rootPane);
        EmojiRenderingSupport.install(scene);

        stage.setTitle(I18n.t("app.title"));
        // On macOS, the icon comes from the .app bundle: MeshApp.icns in Contents/Resources.
        // On Windows/Linux, JavaFX uses stage.getIcons().
        if (!OsDetect.isMacOs()) {
            stage
                .getIcons()
                .addAll(
                    new Image(
                        Objects.requireNonNull(
                            getClass().getResourceAsStream("/logo/icon_256.png")
                        )
                    ),
                    new Image(
                        Objects.requireNonNull(
                            getClass().getResourceAsStream("/logo/icon_128.png")
                        )
                    ),
                    new Image(
                        Objects.requireNonNull(
                            getClass().getResourceAsStream("/logo/icon_64.png")
                        )
                    ),
                    new Image(
                        Objects.requireNonNull(
                            getClass().getResourceAsStream("/logo/icon_32.png")
                        )
                    ),
                    new Image(
                        Objects.requireNonNull(
                            getClass().getResourceAsStream("/logo/icon_16.png")
                        )
                    )
                );
        }

        stage.setScene(scene);
        // Restore window position and size after setScene(); otherwise JavaFX can
        // reapply scene dimensions and overwrite saved Stage bounds.
        restoreWindowBounds(stage);
        stage.show();
        AppTrayManager.getInstance().initialize(stage);
        installSingleInstanceActivationHandler();

        // Restore maximized state after show().
        if (AppPreferences.isWindowMaximized()) {
            if (usesNativeMaximize()) {
                stage.setMaximized(true);
            } else {
                rootPane.maximizeToVisualBounds();
            }
        }

        // Native effects must be applied after show(), once HWND/NSWindow exists.
        NativeWindowHelper.applyNativeEffects(stage, isDark);
        installWindowStateGuards(stage, rootPane);

        // Save window state on close. setOnHiding fires for both stage.close()
        // from the custom title bar and native window closing.
        stage.setOnCloseRequest(e -> {
            e.consume();
            savePrimaryWindowStateIfPossible();
            AppTrayManager.getInstance().exitApplication();
        });
        stage.setOnHiding(e -> saveWindowState(stage, rootPane));

        startUiWatchdog();
        handlePendingCrashLog(stage);
        ConnectionManager.getInstance().connectAutoconnectEntries();
    }

    private void restoreWindowBounds(Stage stage) {
        if (!AppPreferences.hasWindowBounds()) {
            return;
        }

        double x = AppPreferences.getWindowX();
        double y = AppPreferences.getWindowY();
        double w = AppPreferences.getWindowWidth();
        double h = AppPreferences.getWindowHeight();

        // Ensure the window is at least partially visible on an available screen.
        ObservableList<Screen> screens = Screen.getScreensForRectangle(
            x,
            y,
            w,
            h
        );
        if (screens.isEmpty()) {
            return;
        }

        stage.setX(x);
        stage.setY(y);
        stage.setWidth(w);
        stage.setHeight(h);
    }

    private void saveWindowState(Stage stage, RootPane rootPane) {
        boolean maximized;
        double x;
        double y;
        double w;
        double h;

        if (rootPane.isCustomMaximized()) {
            maximized = true;
        } else if (OsDetect.isMacOs()) {
            maximized = stage.isMaximized();
        } else {
            maximized = false;
        }

        if (rootPane.isCustomMaximized()) {
            // For custom maximize, save restore coordinates.
            x = rootPane.getRestoreX();
            y = rootPane.getRestoreY();
            w = rootPane.getRestoreW();
            h = rootPane.getRestoreH();
        } else {
            x = stage.getX();
            y = stage.getY();
            w = stage.getWidth();
            h = stage.getHeight();
        }

        AppPreferences.saveWindowBounds(x, y, w, h, maximized);
    }

    private void savePrimaryWindowStateIfPossible() {
        Stage stage = primaryStage;
        if (stage == null) {
            return;
        }

        Scene scene = stage.getScene();
        if (scene == null || !(scene.getRoot() instanceof RootPane)) {
            return;
        }

        saveWindowState(stage, (RootPane) scene.getRoot());
    }

    private void installWindowStateGuards(Stage stage, RootPane rootPane) {
        if (OsDetect.isMacOs() || AppPreferences.isDisableEffectsEffective()) {
            return;
        }

        AtomicBoolean nativeMaximizeNormalizationInProgress = new AtomicBoolean(
            false
        );

        stage
            .maximizedProperty()
            .addListener((obs, wasMaximized, isMaximized) -> {
                if (!isMaximized) {
                    return;
                }
                Platform.runLater(() ->
                    normalizeNativeMaximize(
                        stage,
                        rootPane,
                        nativeMaximizeNormalizationInProgress
                    )
                );
            });

        stage
            .iconifiedProperty()
            .addListener((obs, wasIconified, isIconified) -> {
                if (isIconified) {
                    return;
                }
                Platform.runLater(() -> {
                    normalizeNativeMaximize(
                        stage,
                        rootPane,
                        nativeMaximizeNormalizationInProgress
                    );
                    if (OsDetect.isWindows() && stage.isShowing()) {
                        NativeWindowHelper.applyNativeEffects(
                            stage,
                            AppPreferences.isDarkMode()
                        );
                    }
                });
            });
    }

    private void normalizeNativeMaximize(
        Stage stage,
        RootPane rootPane,
        AtomicBoolean normalizationInProgress
    ) {
        if (
            normalizationInProgress.get() ||
            !stage.isShowing() ||
            stage.isIconified() ||
            !stage.isMaximized()
        ) {
            return;
        }

        log.warn(
            "Detected native maximize on a custom-framed window; translating to custom maximize"
        );
        normalizationInProgress.set(true);
        stage.setMaximized(false);
        Platform.runLater(() -> {
            try {
                if (!stage.isShowing() || stage.isIconified()) {
                    return;
                }
                if (!rootPane.isCustomMaximized()) {
                    rootPane.maximizeToVisualBounds();
                }
            } finally {
                normalizationInProgress.set(false);
            }
        });
    }

    @Override
    public void stop() {
        LuaDevWindow.saveWindowStateIfOpen();
        savePrimaryWindowStateIfPossible();
        stopUiWatchdog();
        AppTrayManager.getInstance().dispose();
        LuaScriptRuntimeService.getInstance().stopAll();
        ConnectionManager.getInstance().shutdownAll();
        BleDeviceDiscoveryService.getInstance().dispose();
        SessionCrashLogManager.markNormalShutdown();
        JfrDiagnosticSupport.stop();
        releaseSingleInstanceGuard();
        System.exit(0);
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    public static void main(String[] args) {
        AppPreferences.init();
        if (!acquireSingleInstanceGuard()) {
            return;
        }
        if (
            System.getProperty("prism.order") == null &&
            AppPreferences.isSoftwareRendering()
        ) {
            System.setProperty("prism.order", "sw");
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            SessionCrashLogManager.captureUncaughtException(thread, throwable);
            log.error(
                "Uncaught exception in thread '{}'",
                thread.getName(),
                throwable
            );
        });
        logStartupContext();
        launch(args);
    }

    /**
     * Acquires the cross-process single-instance guard before JavaFX starts.
     * If an active instance already exists, it receives a window-activation
     * command and the current process exits without showing UI.
 *
     * @return {@code true} when the current process may continue startup
     */
    public static boolean acquireSingleInstanceGuard() {
        synchronized (SINGLE_INSTANCE_LOCK) {
            if (singleInstanceGuard != null) {
                return true;
            }
            try {
                Optional<SingleInstanceGuard> guard =
                    SingleInstanceGuard.acquire(resolveAppDirectory());
                if (guard.isEmpty()) {
                    return false;
                }
                singleInstanceGuard = guard.get();
                return true;
            } catch (IOException e) {
                // Do not block startup because ~/.meshapp is unavailable; letting
                // the user open the application is more important here.
                log.warn(
                    "Failed to initialize single-instance guard; continuing without duplicate launch protection",
                    e
                );
                return true;
            }
        }
    }

    private static void installSingleInstanceActivationHandler() {
        SingleInstanceGuard guard;
        synchronized (SINGLE_INSTANCE_LOCK) {
            guard = singleInstanceGuard;
        }
        if (guard != null) {
            guard.setActivationHandler(() ->
                AppTrayManager.getInstance().restoreWindow()
            );
        }
    }

    private static void releaseSingleInstanceGuard() {
        SingleInstanceGuard guard;
        synchronized (SINGLE_INSTANCE_LOCK) {
            guard = singleInstanceGuard;
            singleInstanceGuard = null;
        }
        if (guard != null) {
            guard.close();
        }
    }

    private static Path resolveAppDirectory() {
        return Path.of(System.getProperty("user.home", "."), ".meshapp");
    }

    private static boolean usesNativeMaximize() {
        return OsDetect.isMacOs() && AppPreferences.isDisableEffectsEffective();
    }

    private void handlePendingCrashLog(Stage stage) {
        Optional<Path> pendingCrashLog =
            SessionCrashLogManager.peekPendingCrashLog();
        if (pendingCrashLog.isEmpty()) {
            runDeferredStartupTasks();
            return;
        }

        CrashReportFlow.showPendingCrashPrompt(
            stage,
            pendingCrashLog.get(),
            this::runDeferredStartupTasks
        );
    }

    private void runDeferredStartupTasks() {
        if (!deferredStartupTasksStarted.compareAndSet(false, true)) {
            return;
        }
        if (AppPreferences.isCheckUpdates()) {
            UpdateCheckService.checkAsync(ModalPane::showUpdateAvailable);
        }
    }

    private void startUiWatchdog() {
        stopUiWatchdog();
        lastUiHeartbeatNanos.set(System.nanoTime());
        uiWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javafx-ui-watchdog");
            t.setDaemon(true);
            return t;
        });
        uiWatchdog.scheduleAtFixedRate(
            () -> {
                try {
                    Platform.runLater(() ->
                        lastUiHeartbeatNanos.set(System.nanoTime())
                    );
                } catch (IllegalStateException ignored) {
                    // JavaFX toolkit is already shutting down.
                }
            },
            0,
            1,
            TimeUnit.SECONDS
        );
        uiWatchdog.scheduleAtFixedRate(
            this::checkUiThreadHealth,
            5,
            5,
            TimeUnit.SECONDS
        );
    }

    private void stopUiWatchdog() {
        ScheduledExecutorService watchdog = uiWatchdog;
        uiWatchdog = null;
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
    }

    private void checkUiThreadHealth() {
        long stallNanos = System.nanoTime() - lastUiHeartbeatNanos.get();
        if (stallNanos < UI_THREAD_STALL_THRESHOLD_NANOS) {
            return;
        }

        long now = System.nanoTime();
        if (
            now - lastUiFreezeCaptureNanos <
            UI_THREAD_STALL_CAPTURE_COOLDOWN_NANOS
        ) {
            return;
        }
        if (!uiFreezeCaptureInProgress.compareAndSet(false, true)) {
            return;
        }

        lastUiFreezeCaptureNanos = now;
        try {
            SessionCrashLogManager.captureUiFreezeDiagnostic(
                Duration.ofNanos(stallNanos)
            );
        } catch (Exception e) {
            log.warn("Failed to capture JavaFX thread stall diagnostics", e);
        } finally {
            uiFreezeCaptureInProgress.set(false);
        }
    }
}
