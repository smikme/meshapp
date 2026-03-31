package com.meshtastic.client;

import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.platform.NativeWindowHelper;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.service.DatabaseProvider;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.UpdateCheckService;
import com.meshtastic.client.system.FormManager;
import com.meshtastic.client.system.RootPane;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.tray.AppTrayManager;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Application;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public class MeshApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MeshApp.class);

    public static final String APPLICATION_VERSION = resolveVersion();
    public static final int VERSION_CODE = resolveVersionCode();

    private static String resolveVersion() {
        // 1. version.properties (генерируется Gradle при каждой сборке)
        try (var is = MeshApp.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                String v = props.getProperty("version");
                if (v != null && !v.isBlank()) { return v; }
            }
        } catch (Exception ignored) {}
        // 2. MANIFEST.MF (при запуске из jar)
        String v = MeshApp.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    private static int resolveVersionCode() {
        try (var is = MeshApp.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                String code = props.getProperty("versionCode");
                if (code != null && !code.isBlank()) {
                    return Integer.parseInt(code.trim());
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static String resolveOperatingSystemName() {
        return System.getProperty("os.name", "unknown").trim();
    }

    private static String resolveOperatingSystemVersion() {
        return System.getProperty("os.version", "unknown").trim();
    }

    private static String resolveOperatingSystemArch() {
        return System.getProperty("os.arch", "unknown").trim();
    }

    private static void logStartupContext() {
        log.info(
                "Starting MeshApp version {} (build {}) on {} {} ({})",
                APPLICATION_VERSION,
                VERSION_CODE,
                resolveOperatingSystemName(),
                resolveOperatingSystemVersion(),
                resolveOperatingSystemArch()
        );
    }

    private static Stage primaryStage;

    @Override
    public void start(Stage stage) {
        primaryStage = stage;

        Font.loadFont(getClass().getResourceAsStream("/fonts/Roboto-Regular.ttf"), 13);
        Font.loadFont(getClass().getResourceAsStream("/fonts/Roboto-Bold.ttf"), 13);

        AppPreferences.init();
        // Инициализировать MessageDbService и выполнить миграцию JSON → H2 при первом запуске
        MessageDbService.getInstance().migrateFromJsonHistory();
        MessageDbService.getInstance().markStaleSendingAsFailed();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            MessageDbService.closeIfInitialized();
            NodeCacheService.closeIfInitialized();
            DatabaseProvider.close();
        }));

        // Seamless frame: StageStyle.UNIFIED ДО создания сцены
        NativeWindowHelper.prepareStage(stage);

        RootPane rootPane = new RootPane();
        Scene scene = new Scene(rootPane, 1010, 750);

        boolean isDark = AppPreferences.isDarkMode();
        ThemeManager.applyTheme(scene, isDark);

        FormManager.install(rootPane);

        stage.setTitle("MeshApp");
        // На macOS иконка берётся из .app bundle (MeshApp.icns в Contents/Resources).
        // На Windows/Linux — через stage.getIcons().
        if (!OsDetect.isMacOs()) {
            stage.getIcons().addAll(
                    new Image(Objects.requireNonNull(getClass().getResourceAsStream("/logo/icon_256.png"))),
                    new Image(Objects.requireNonNull(getClass().getResourceAsStream("/logo/icon_128.png"))),
                    new Image(Objects.requireNonNull(getClass().getResourceAsStream("/logo/icon_64.png"))),
                    new Image(Objects.requireNonNull(getClass().getResourceAsStream("/logo/icon_32.png"))),
                    new Image(Objects.requireNonNull(getClass().getResourceAsStream("/logo/icon_16.png")))
            );
        }

        // Восстановить позицию/размер окна из предыдущей сессии
        restoreWindowBounds(stage);

        stage.setScene(scene);
        stage.show();
        AppTrayManager.getInstance().initialize(stage);

        // Восстановить maximize ПОСЛЕ show()
        if (AppPreferences.isWindowMaximized()) {
            if (OsDetect.isMacOs()) {
                stage.setMaximized(true);
            } else {
                rootPane.maximizeToVisualBounds();
            }
        }

        // Нативные эффекты: ПОСЛЕ show() (HWND/NSWindow уже существует)
        NativeWindowHelper.applyNativeEffects(stage, isDark);

        // Сохранять состояние окна при закрытии (setOnHiding срабатывает и при
        // программном stage.close() из кастомного title bar, и при нативном закрытии)
        stage.setOnCloseRequest(e -> javafx.application.Platform.setImplicitExit(true));
        stage.setOnHiding(e -> saveWindowState(stage, rootPane));

        // Проверка обновлений (асинхронно, не блокирует запуск)
        if (AppPreferences.isCheckUpdates()) {
            UpdateCheckService.checkAsync(ModalPane::showUpdateAvailable);
        }
    }

    private void restoreWindowBounds(Stage stage) {
        if (!AppPreferences.hasWindowBounds()) { return; }

        double x = AppPreferences.getWindowX();
        double y = AppPreferences.getWindowY();
        double w = AppPreferences.getWindowWidth();
        double h = AppPreferences.getWindowHeight();

        // Проверить, что окно попадает хотя бы частично на один из доступных экранов
        ObservableList<Screen> screens = Screen.getScreensForRectangle(x, y, w, h);
        if (screens.isEmpty()) { return; }

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

        if (OsDetect.isMacOs()) {
            maximized = stage.isMaximized();
        } else {
            maximized = rootPane.isCustomMaximized();
        }

        if (maximized && !OsDetect.isMacOs()) {
            // Для custom maximize сохраняем restore-координаты
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

    @Override
    public void stop() {
        AppTrayManager.getInstance().dispose();
        ConnectionManager.getInstance().shutdownAll();
        System.exit(0);
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    public static void main(String[] args) {
        AppPreferences.init();
        if (System.getProperty("prism.order") == null && AppPreferences.isSoftwareRendering()) {
            System.setProperty("prism.order", "sw");
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                log.error("Uncaught exception in thread '{}'", thread.getName(), throwable));
        logStartupContext();
        launch(args);
    }
}
