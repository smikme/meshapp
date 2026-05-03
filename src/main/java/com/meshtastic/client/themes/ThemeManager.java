package com.meshtastic.client.themes;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import com.meshtastic.client.MeshApp;
import com.meshtastic.client.platform.NativeWindowHelper;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ThemeManager {

    private ThemeManager() {} // utility class

    private static final String APP_CSS = "/css/app.css";
    private static final String LIGHT_THEME_CLASS = "light-theme";
    private static final Set<Scene> MANAGED_SCENES =
            Collections.newSetFromMap(new WeakHashMap<>());

    public static void applyTheme(Scene scene, boolean isDark) {
        // Устанавливаем тему AtlantaFX как UserAgentStylesheet
        if (isDark) {
            Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());
        } else {
            Application.setUserAgentStylesheet(new CupertinoLight().getUserAgentStylesheet());
        }

        registerScene(scene);

        for (Scene managedScene : snapshotManagedScenes()) {
            applyThemeToScene(managedScene, isDark);
            if (managedScene != null && managedScene.getWindow() instanceof Stage stage) {
                NativeWindowHelper.updateTheme(stage, isDark);
            }
        }

        Stage stage = scene != null && scene.getWindow() instanceof Stage s ? s : MeshApp.getPrimaryStage();
        if (stage != null) {
            NativeWindowHelper.updateTheme(stage, isDark);
        }
    }

    public static void registerScene(Scene scene) {
        if (scene == null) {
            return;
        }
        synchronized (MANAGED_SCENES) {
            MANAGED_SCENES.add(scene);
        }
    }

    public static void unregisterScene(Scene scene) {
        if (scene == null) {
            return;
        }
        synchronized (MANAGED_SCENES) {
            MANAGED_SCENES.remove(scene);
        }
    }

    private static java.util.List<Scene> snapshotManagedScenes() {
        synchronized (MANAGED_SCENES) {
            return new ArrayList<>(MANAGED_SCENES);
        }
    }

    private static void applyThemeToScene(Scene scene, boolean isDark) {
        if (scene == null || scene.getRoot() == null) {
            return;
        }

        if (!isDark) {
            if (!scene.getRoot().getStyleClass().contains(LIGHT_THEME_CLASS)) {
                scene.getRoot().getStyleClass().add(LIGHT_THEME_CLASS);
            }
        } else {
            scene.getRoot().getStyleClass().remove(LIGHT_THEME_CLASS);
        }

        scene.getStylesheets().clear();
        String appCss = ThemeManager.class.getResource(APP_CSS) != null
                ? ThemeManager.class.getResource(APP_CSS).toExternalForm() : null;
        if (appCss != null) {
            scene.getStylesheets().add(appCss);
        }
        applyTypographyToRoot(scene.getRoot());
    }

    public static void refreshTypography() {
        for (Scene managedScene : snapshotManagedScenes()) {
            if (managedScene != null) {
                applyTypographyToRoot(managedScene.getRoot());
            }
        }
    }

    private static void applyTypographyToRoot(Parent root) {
        if (root == null) {
            return;
        }

        String typographyStyle = String.format(Locale.US,
                "-fx-font-size: %dpx; -chat-font-size: %dpx;",
                TypographyManager.getAppFontSize(),
                TypographyManager.getChatFontSize());
        root.setStyle(mergeRootStyle(root.getStyle(), typographyStyle));
        root.applyCss();
    }

    private static String mergeRootStyle(String existingStyle, String typographyStyle) {
        List<String> preservedDeclarations = java.util.Arrays.stream(
                        existingStyle == null ? new String[0] : existingStyle.split(";"))
                .map(String::trim)
                .filter(declaration -> !declaration.isBlank())
                .filter(declaration -> !declaration.startsWith("-fx-font-size"))
                .filter(declaration -> !declaration.startsWith("-chat-font-size"))
                .collect(Collectors.toCollection(ArrayList::new));
        preservedDeclarations.add(typographyStyle);
        return preservedDeclarations.stream()
                .collect(Collectors.joining(" "));
    }
}
