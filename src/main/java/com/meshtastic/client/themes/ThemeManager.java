package com.meshtastic.client.themes;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import com.meshtastic.client.MeshApp;
import com.meshtastic.client.platform.NativeWindowHelper;
import javafx.application.Application;
import javafx.scene.Scene;

public class ThemeManager {

    private static final String APP_CSS = "/css/app.css";
    private static final String LIGHT_THEME_CLASS = "light-theme";

    public static void applyTheme(Scene scene, boolean isDark) {
        // Устанавливаем тему AtlantaFX как UserAgentStylesheet
        if (isDark) {
            Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());
        } else {
            Application.setUserAgentStylesheet(new CupertinoLight().getUserAgentStylesheet());
        }

        // Переключить style class .light-theme на корне сцены
        if (!isDark) {
            if (!scene.getRoot().getStyleClass().contains(LIGHT_THEME_CLASS)) {
                scene.getRoot().getStyleClass().add(LIGHT_THEME_CLASS);
            }
        } else {
            scene.getRoot().getStyleClass().remove(LIGHT_THEME_CLASS);
        }

        // Добавляем только кастомные стили приложения
        scene.getStylesheets().clear();
        String appCss = ThemeManager.class.getResource(APP_CSS) != null
                ? ThemeManager.class.getResource(APP_CSS).toExternalForm() : null;
        if (appCss != null) {
            scene.getStylesheets().add(appCss);
        }

        // Обновляем нативные атрибуты окна (dark mode title bar, Mica и т.д.)
        NativeWindowHelper.updateTheme(MeshApp.getPrimaryStage(), isDark);
    }
}
