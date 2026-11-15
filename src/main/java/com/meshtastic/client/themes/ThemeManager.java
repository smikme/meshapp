package com.meshtastic.client.themes;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import com.meshtastic.client.MeshApp;
import com.meshtastic.client.platform.NativeWindowHelper;
import com.meshtastic.client.utils.AppPreferences;
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
 * Applies AtlantaFX day/night themes, application palettes and typography to JavaFX scenes.
 *
 * <p>Registered scenes are held weakly and updated together when a theme is applied.
 * Palette selection comes from {@link AppearancePreferences}; applying or previewing
 * a theme does not itself persist the day/night preference. Operations that modify
 * scene graphs or the application stylesheet must run on the JavaFX application thread.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ThemeManager {

    private ThemeManager() {} // utility class

    private static final String APP_CSS = "/css/app.css";
    private static final String APPEARANCE_CSS = "/css/appearance.css";
    private static final String LIGHT_THEME_CLASS = "light-theme";
    private static final Set<Scene> MANAGED_SCENES =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Installs the application-wide Cupertino stylesheet and updates all registered scenes.
     * The supplied scene is registered first. Each scene receives the saved palette,
     * application stylesheets and current typography; native stage chrome is updated
     * where supported. Appearance observers are notified after application.
     *
     * @param scene additional scene to register, or {@code null} to update existing registrations
     * @param isDark {@code true} for the dark theme, {@code false} for the light theme;
     *               the caller is responsible for persisting this choice
     */
    public static void applyTheme(Scene scene, boolean isDark) {
        // Install the AtlantaFX theme as the user-agent stylesheet.
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
        AppearancePreferences.changed();
    }

    /**
     * Registers a scene for future theme and typography updates without styling it immediately.
     * Duplicate registrations have no effect, and the registry does not keep the scene alive.
     *
     * @param scene scene to track; {@code null} is ignored
     */
    public static void registerScene(Scene scene) {
        if (scene == null) {
            return;
        }
        synchronized (MANAGED_SCENES) {
            MANAGED_SCENES.add(scene);
        }
    }

    /**
     * Stops tracking a scene without removing its existing stylesheets or CSS classes.
     *
     * @param scene scene to unregister; {@code null} and unregistered scenes are ignored
     */
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

        decorate(scene.getRoot(), AppearancePreferences.palette(), isDark);
        addStylesheetIfPresent(scene, APP_CSS);
        addStylesheetIfPresent(scene, APPEARANCE_CSS);
        applyTypographyToRoot(scene.getRoot());
    }

    /** Adds a bundled stylesheet once, tolerating resources absent from a partial classpath. */
    private static void addStylesheetIfPresent(Scene scene, String path) {
        var resource = ThemeManager.class.getResource(path);
        if (resource == null) return;
        String stylesheet = resource.toExternalForm();
        if (!scene.getStylesheets().contains(stylesheet)) scene.getStylesheets().add(stylesheet);
    }

    /**
     * Registers and styles a scene using the saved palette, day/night mode and font sizes.
     * Suitable for opening a dialog after the application's user-agent stylesheet has
     * been installed: this method does not replace that stylesheet or restyle other scenes.
     *
     * @param scene scene to initialize; {@code null} is ignored
     */
    public static void styleScene(Scene scene) {
        registerScene(scene);
        applyThemeToScene(scene, AppPreferences.isDarkMode());
    }

    /**
     * Replaces palette and mode classes on a root while preserving unrelated style classes.
     * This supports an isolated preview without changing preferences or the application-wide
     * stylesheet. The containing scene must already provide the application appearance CSS.
     *
     * @param root non-null scene or preview root to decorate
     * @param palette non-null palette to select
     * @param dark whether to add the dark-mode class instead of the light-mode class
     */
    public static void decorate(Parent root, ThemePalette palette, boolean dark) {
        root.getStyleClass().removeAll("palette-standard", "palette-blue", "palette-green",
                LIGHT_THEME_CLASS, "dark-theme");
        root.getStyleClass().addAll(palette.styleClass(), dark ? "dark-theme" : LIGHT_THEME_CLASS);
    }

    /**
     * Reapplies current {@link TypographyManager} font sizes to all registered scene roots.
     * Existing inline declarations unrelated to application and chat font sizes are preserved.
     */
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
        return String.join(" ", preservedDeclarations);
    }
}
