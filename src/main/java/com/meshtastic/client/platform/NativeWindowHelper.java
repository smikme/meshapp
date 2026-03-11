package com.meshtastic.client.platform;

import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Оркестратор нативных оконных эффектов.
 * Единая точка входа для Mica (Windows) и vibrancy (macOS).
 */
public final class NativeWindowHelper {

    private static final Logger log = LoggerFactory.getLogger(NativeWindowHelper.class);

    public static final PseudoClass SEAMLESS_FRAME = PseudoClass.getPseudoClass("seamless-frame");
    public static final PseudoClass SEPARATE_FRAME = PseudoClass.getPseudoClass("separate-frame");
    public static final PseudoClass LIGHT_THEME = PseudoClass.getPseudoClass("light-theme");

    private static boolean seamlessActive = false;

    /**
     * Вызвать ДО stage.show(). Устанавливает StageStyle для кастомного title bar.
     * <p>
     * Windows и macOS: TRANSPARENT — кастомный title bar + нативный backdrop.
     * Linux: UNDECORATED — убирает нативную рамку ОС, оставляя только кастомный title bar.
     * Прочие ОС: по умолчанию (DECORATED).
     */
    public static void prepareStage(Stage stage) {
        if (AppPreferences.isNativeWindow()) {
            // Нативное управление окнами: стандартный DECORATED стиль ОС
            return;
        }
        if (AppPreferences.isDisableTransparency()) {
            // Кастомный title bar, но без прозрачности
            stage.initStyle(StageStyle.UNDECORATED);
            return;
        }
        if (OsDetect.supportsSeamlessFrame()) {
            stage.initStyle(StageStyle.TRANSPARENT);
        } else if (OsDetect.isLinux()) {
            stage.initStyle(StageStyle.UNDECORATED);
        }
    }

    /**
     * Вызвать ПОСЛЕ stage.show(). Применяет нативные backdrop-эффекты и CSS pseudo-classes.
     */
    public static void applyNativeEffects(Stage stage, boolean isDark) {
        if (stage.getScene() == null || stage.getScene().getRoot() == null) {
            setSeamlessState(stage, false);
            return;
        }

        // Нативное управление или отключена прозрачность — без backdrop эффектов
        if (AppPreferences.isNativeWindow() || AppPreferences.isDisableTransparency()) {
            setSeamlessState(stage, false);
            setThemeState(stage, isDark);
            return;
        }

        boolean seamless = false;

        try {
            switch (OsDetect.current()) {
                case WINDOWS -> {
                    var ctrl = new NativeWinWindowControl(stage);
                    seamless = ctrl.prepareMicaWindow(isDark);
                }
                case MACOS -> {
                    var ctrl = new NativeMacOsWindowControl(stage);
                    seamless = ctrl.applyVisualEffect(isDark);
                    ctrl.setDarkMode(isDark);
                    ctrl.makeVisibleInAppSwitcher();
                    // Повторить с задержкой — JavaFX может перезаписывать свойства NSWindow
                    Platform.runLater(ctrl::makeVisibleInAppSwitcher);
                }
                default -> { /* Unknown OS / Linux: без нативных эффектов */ }
            }
        } catch (Throwable t) {
            log.warn("Не удалось применить нативные эффекты окна", t);
        }

        setSeamlessState(stage, seamless);
        setThemeState(stage, isDark);
        seamlessActive = seamless;

        // Для seamless режима — прозрачный фон сцены, чтобы backdrop просвечивал
        if (seamless) {
            stage.getScene().setFill(Color.TRANSPARENT);
        }
    }

    /**
     * Вызывается при смене темы (light ↔ dark). Обновляет нативные атрибуты.
     */
    public static void updateTheme(Stage stage, boolean isDark) {
        if (stage == null || !stage.isShowing()) { return; }

        // Обновить CSS pseudo-class для light/dark
        setThemeState(stage, isDark);

        try {
            switch (OsDetect.current()) {
                case WINDOWS -> {
                    var ctrl = new NativeWinWindowControl(stage);
                    ctrl.setDarkMode(isDark);
                    // Resize nudge для перерисовки DWM title bar
                    double w = stage.getWidth();
                    stage.setWidth(w - 1);
                    Platform.runLater(() -> stage.setWidth(w));
                }
                case MACOS -> {
                    var ctrl = new NativeMacOsWindowControl(stage);
                    ctrl.setDarkMode(isDark);
                    ctrl.updateVisualEffectAppearance(isDark);
                }
                default -> {}
            }
        } catch (Throwable t) {
            log.warn("Не удалось обновить тему нативного окна", t);
        }
    }

    public static boolean isSeamlessActive() {
        return seamlessActive;
    }

    private static void setSeamlessState(Stage stage, boolean seamless) {
        var root = stage.getScene().getRoot();
        root.pseudoClassStateChanged(SEAMLESS_FRAME, seamless);
        root.pseudoClassStateChanged(SEPARATE_FRAME, !seamless);
    }

    private static void setThemeState(Stage stage, boolean isDark) {
        if (stage.getScene() == null || stage.getScene().getRoot() == null) { return; }

        var root = stage.getScene().getRoot();
        root.pseudoClassStateChanged(LIGHT_THEME, !isDark);
    }

    private NativeWindowHelper() {}
}
