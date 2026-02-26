package com.meshtastic.client;

import com.meshtastic.client.platform.NativeWindowHelper;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.service.DatabaseProvider;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.FormManager;
import com.meshtastic.client.system.RootPane;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.text.Font;
import javafx.stage.Stage;

public class MeshApp extends Application {

    public static final String APPLICATION_VERSION = resolveVersion();

    private static String resolveVersion() {
        // 1. version.properties (генерируется Gradle при каждой сборке)
        try (var is = MeshApp.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                String v = props.getProperty("version");
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Exception ignored) {}
        // 2. MANIFEST.MF (при запуске из jar)
        String v = MeshApp.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
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
        Scene scene = new Scene(rootPane, 1010, 855);

        boolean isDark = AppPreferences.isDarkMode();
        ThemeManager.applyTheme(scene, isDark);

        FormManager.install(rootPane);

        stage.setTitle("MeshApp");
        // На macOS иконка берётся из .app bundle (MeshApp.icns в Contents/Resources).
        // На Windows/Linux — через stage.getIcons().
        if (!OsDetect.isMacOs()) {
            stage.getIcons().addAll(
                    new Image(getClass().getResourceAsStream("/logo/icon_256.png")),
                    new Image(getClass().getResourceAsStream("/logo/icon_128.png")),
                    new Image(getClass().getResourceAsStream("/logo/icon_64.png")),
                    new Image(getClass().getResourceAsStream("/logo/icon_32.png")),
                    new Image(getClass().getResourceAsStream("/logo/icon_16.png"))
            );
        }
        stage.setScene(scene);
        stage.show();

        // Нативные эффекты: ПОСЛЕ show() (HWND/NSWindow уже существует)
        NativeWindowHelper.applyNativeEffects(stage, isDark);
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
