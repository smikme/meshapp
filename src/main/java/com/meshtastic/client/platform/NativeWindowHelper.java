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
 * Coordinates native window treatments across supported desktop platforms.
 * This is the single entry point for Windows Mica, macOS vibrancy, and
 * platform-specific title bar state.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class NativeWindowHelper {

    private static final Logger log = LoggerFactory.getLogger(NativeWindowHelper.class);

    public static final PseudoClass SEAMLESS_FRAME = PseudoClass.getPseudoClass("seamless-frame");
    public static final PseudoClass SEPARATE_FRAME = PseudoClass.getPseudoClass("separate-frame");
    public static final PseudoClass LIGHT_THEME = PseudoClass.getPseudoClass("light-theme");

    private static boolean seamlessActive = false;

    /**
     * Prepares the stage before {@link Stage#show()} by choosing the window
     * style needed by the custom title bar.
     * <p>
     * Windows and macOS use a transparent stage so the native backdrop can show
     * through the custom frame. Linux uses an undecorated stage to remove the
     * operating-system frame while keeping the custom title bar. Other platforms
     * retain the default decorated style.
     */
    public static void prepareStage(Stage stage) {
        // Windows 10 needs the undocumented uxtheme dark-mode hook before the first window is created.
        if (OsDetect.isWindows()) {
            try { NativeWinWindowControl.initDarkModeSupport(); }
            catch (Throwable t) { log.warn("initDarkModeSupport failed", t); }
        }

        if (AppPreferences.isDisableEffectsEffective()) {
            // Native effects are disabled, so keep the standard decorated OS frame.
            return;
        }
        if (OsDetect.supportsSeamlessFrame()) {
            stage.initStyle(StageStyle.TRANSPARENT);
        } else if (OsDetect.isLinux()) {
            stage.initStyle(StageStyle.UNDECORATED);
        }
    }

    /**
     * Applies native backdrop effects and the matching CSS pseudo-classes after
     * {@link Stage#show()} has created the underlying platform window.
     */
    public static void applyNativeEffects(Stage stage, boolean isDark) {
        if (stage.getScene() == null || stage.getScene().getRoot() == null) {
            setSeamlessState(stage, false);
            return;
        }

        // With effects disabled, only keep the theme state in sync.
        if (AppPreferences.isDisableEffectsEffective()) {
            setSeamlessState(stage, false);
            setThemeState(stage, isDark);
            // Windows still needs its native title bar tint updated in decorated mode.
            if (OsDetect.isWindows()) {
                try {
                    var ctrl = new NativeWinWindowControl(stage);
                    ctrl.applyPlainDecoratedTitleBar(isDark);
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
                        // The first frame of a transparent stage can remain opaque until the next repaint.
                        // Force both JavaFX CSS/layout and DWM to refresh after the backdrop is attached.
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
                    // JavaFX can rewrite NSWindow attributes, so repeat this on the next pulse.
                    Platform.runLater(ctrl::makeVisibleInAppSwitcher);
                }
                default -> { /* Unknown OS or Linux: no native effects are available. */ }
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

        // Seamless mode needs a transparent scene so the native backdrop remains visible.
        if (seamless) {
            stage.getScene().setFill(Color.TRANSPARENT);
        }
    }

    /**
     * Refreshes native window attributes after the application theme changes.
     */
    public static void updateTheme(Stage stage, boolean isDark) {
        if (stage == null || !stage.isShowing()) { return; }

        // Keep CSS theme pseudo-classes in sync with the native title bar.
        setThemeState(stage, isDark);

        try {
            switch (OsDetect.current()) {
                case WINDOWS -> {
                    var ctrl = new NativeWinWindowControl(stage);
                    if (AppPreferences.isDisableEffectsEffective()) {
                        ctrl.applyPlainDecoratedTitleBar(isDark);
                    } else {
                        ctrl.setDarkMode(isDark);
                    }
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

    /**
     * Updates the JavaFX title and repairs its UTF-8 X11 property on Linux.
     */
    public static void setWindowTitle(Stage stage, String title) {
        if (stage == null) {
            return;
        }

        if (OsDetect.isLinux()
                && stage.isShowing()
                && new NativeLinuxWindowControl(stage).setTitle(title)) {
            return;
        }
        stage.setTitle(title);
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
