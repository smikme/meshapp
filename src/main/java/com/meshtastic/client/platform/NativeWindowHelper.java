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
        // Windows 10: инициализировать undocumented uxtheme dark mode support
        // Должен быть вызван один раз до создания окна
        if (OsDetect.isWindows()) {
            try { NativeWinWindowControl.initDarkModeSupport(); }
            catch (Throwable t) { log.warn("initDarkModeSupport failed", t); }
        }

        if (AppPreferences.isDisableEffects()) {
            // Выключены эффекты оформления: стандартный DECORATED стиль ОС
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

        // Выключены эффекты оформления — без backdrop эффектов
        if (AppPreferences.isDisableEffects()) {
            setSeamlessState(stage, false);
            setThemeState(stage, isDark);
            // Windows: установить тёмный/светлый title bar в нативном режиме
            if (OsDetect.isWindows()) {
                try {
                    var ctrl = new NativeWinWindowControl(stage);
                    ctrl.setDarkMode(isDark);
                    ctrl.redrawFrame();
                } catch (Throwable t) {
                    log.warn("Не удалось установить тёмный title bar", t);
                }
            }
            return;
        }

        boolean seamless = false;

        try {
            switch (OsDetect.current()) {
                case WINDOWS -> {
                    var ctrl = new NativeWinWindowControl(stage);
                    seamless = ctrl.prepareMicaWindow(isDark);
                    if (seamless) {
                        // Первый кадр у transparent stage иногда остаётся opaque
                        // до следующего явного repaint. Принудительно обновляем
                        // CSS и DWM после применения backdrop.
                        stage.getScene().setFill(Color.TRANSPARENT);
                        var root = stage.getScene().getRoot();
                        root.applyCss();
                        root.requestLayout();
                        ctrl.redrawFrame();
                        Platform.runLater(() -> {
                            stage.getScene().setFill(Color.TRANSPARENT);
                            root.applyCss();
                            root.requestLayout();
                            ctrl.redrawFrame();
                        });
                    }
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

        var root = stage.getScene().getRoot();
        root.applyCss();
        root.requestLayout();
        if (seamless) {
            Platform.runLater(() -> {
                root.applyCss();
                root.requestLayout();
            });
        }

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
                    ctrl.redrawFrame();
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
