package com.meshtastic.client.themes;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.utils.AppPreferences;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AppearanceDialogTest {
    @TempDir Path home;
    private Stage owner;
    private Stage dialog;

    @BeforeEach void setUp() throws Exception {
        TestEnvironmentSupport.setUserHome(home);
        TestEnvironmentSupport.ensureJavaFxStarted();
        fx(() -> {
            owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 500, 350));
            ThemeManager.applyTheme(owner.getScene(), false);
            owner.show();
        });
    }

    @AfterEach void tearDown() throws Exception {
        fx(() -> {
            if (dialog != null) dialog.close();
            ThemeManager.unregisterScene(owner.getScene());
            owner.close();
        });
    }

    @Test void previewAndCancelDoNotChangePreferencesOrOtherWindows() throws Exception {
        fx(() -> {
            AppearanceDialog.showGlobal(owner);
            dialog = editor();
            ((ToggleButton) dialog.getScene().lookup("#appearance-palette-GREEN")).fire();
            ((CheckBox) dialog.getScene().lookup("#appearance-dark")).setSelected(true);
            ((ToggleButton) dialog.getScene().lookup("#appearance-background-GRID")).fire();
            layout(dialog);
            assertEquals(ThemePalette.STANDARD, AppearancePreferences.palette());
            assertFalse(AppPreferences.isDarkMode());
            assertEquals(ChatBackground.Kind.DEFAULT, AppearancePreferences.globalBackground().kind());
            assertTrue(owner.getScene().getRoot().getStyleClass().contains("light-theme"));
            Region preview = (Region) dialog.getScene().lookup(".appearance-preview");
            assertEquals(Color.web("#172820"), preview.getBackground().getFills().getFirst().getFill());
            snapshot(dialog, "green-dark-grid");
            ((Button) dialog.getScene().lookup("#appearance-cancel")).fire();
            assertEquals(ThemePalette.STANDARD, AppearancePreferences.palette());
            assertEquals(ChatBackground.Kind.DEFAULT, AppearancePreferences.globalBackground().kind());
        });
    }

    @Test void applyUpdatesAllRegisteredWindowsAndCanReturnToStandardTheme() throws Exception {
        fx(() -> {
            AppearanceDialog.showGlobal(owner);
            dialog = editor();
            ((ToggleButton) dialog.getScene().lookup("#appearance-palette-BLUE")).fire();
            ((ToggleButton) dialog.getScene().lookup("#appearance-background-DOTS")).fire();
            snapshot(dialog, "blue-light-dots");
            ((Button) dialog.getScene().lookup("#appearance-apply")).fire();
            layout(owner);
            assertEquals(ThemePalette.BLUE, AppearancePreferences.palette());
            assertEquals(ChatBackground.Kind.DOTS, AppearancePreferences.globalBackground().kind());
            assertTrue(owner.getScene().getRoot().getStyleClass().contains("palette-blue"));
            assertEquals(Color.web("#f4f8fc"), ((Region) owner.getScene().getRoot()).getBackground().getFills().getFirst().getFill());
            AppPreferences.setDarkMode(true);
            ThemeManager.applyTheme(owner.getScene(), true);
            assertEquals(ThemePalette.BLUE, AppearancePreferences.palette());
            assertTrue(owner.getScene().getRoot().getStyleClass().contains("dark-theme"));
            AppearanceDialog.showGlobal(owner);
            dialog = editor();
            ((Button) dialog.getScene().lookup("#appearance-reset")).fire();
            ((Button) dialog.getScene().lookup("#appearance-apply")).fire();
            layout(owner);
            assertEquals(ThemePalette.STANDARD, AppearancePreferences.palette());
            assertFalse(AppPreferences.isDarkMode());
            assertEquals(Color.WHITE, ((Region) owner.getScene().getRoot()).getBackground().getFills().getFirst().getFill());
        });
    }

    @Test void chatOverrideCanBeAppliedAndResetToGlobalBackground() throws Exception {
        fx(() -> {
            AppearancePreferences.setGlobalBackground(new ChatBackground(ChatBackground.Kind.GRADIENT,
                    "#eecc99", "#99ccee", "", .1, 0, false));
            AppearanceDialog.showChat(owner, "owner", "channel:0");
            dialog = editor();
            CheckBox inherit = (CheckBox) dialog.getScene().lookup("#appearance-inherit");
            assertTrue(inherit.isSelected());
            inherit.setSelected(false);
            ((ToggleButton) dialog.getScene().lookup("#appearance-background-COLOR")).fire();
            ((Button) dialog.getScene().lookup("#appearance-apply")).fire();
            assertEquals(ChatBackground.Kind.COLOR, AppearancePreferences.backgroundFor("owner", "channel:0").kind());
            assertEquals(ChatBackground.Kind.GRADIENT, AppearancePreferences.backgroundFor("owner", "channel:1").kind());
            AppearanceDialog.showChat(owner, "owner", "channel:0");
            dialog = editor();
            ((Button) dialog.getScene().lookup("#appearance-reset")).fire();
            ((Button) dialog.getScene().lookup("#appearance-apply")).fire();
            assertNull(AppearancePreferences.chatOverride("owner", "channel:0"));
            assertEquals(ChatBackground.Kind.GRADIENT, AppearancePreferences.backgroundFor("owner", "channel:0").kind());
        });
    }

    @Test void allPalettesAndModesHaveReadableBubblesAndTranslatedControls() throws Exception {
        String previous = I18n.getLanguageTag();
        try {
            for (String language : List.of("ru", "en", "de")) {
                fx(() -> {
                    I18n.setLanguageTagForTests(language);
                    AppearanceDialog.showGlobal(owner);
                    dialog = editor();
                    for (ThemePalette palette : ThemePalette.values()) {
                        ((ToggleButton) dialog.getScene().lookup("#appearance-palette-" + palette)).fire();
                        for (boolean dark : List.of(false, true)) {
                            ((CheckBox) dialog.getScene().lookup("#appearance-dark")).setSelected(dark);
                            layout(dialog);
                            for (var node : dialog.getScene().getRoot().lookupAll(".appearance-preview-message")) {
                                var label = (javafx.scene.control.Label) node;
                                Color background = (Color) label.getBackground().getFills().getFirst().getFill();
                                Color foreground = (Color) label.getTextFill();
                                assertTrue(contrast(background, foreground) >= 4.5,
                                        palette + " dark=" + dark + " must have readable messages");
                            }
                        }
                    }
                    assertFalse(dialog.getTitle().startsWith("!"));
                    snapshot(dialog, "localized-" + language);
                    dialog.close();
                });
            }
        } finally {
            I18n.setLanguageTagForTests(previous);
        }
    }

    private static double contrast(Color a, Color b) {
        double first = luminance(a), second = luminance(b);
        return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
    }

    private static double luminance(Color color) {
        return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen()) + .0722 * linear(color.getBlue());
    }

    private static double linear(double channel) {
        return channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
    }

    private static Stage editor() {
        return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                .filter(w -> w.getScene() != null && "appearance-dialog".equals(w.getScene().getRoot().getId()))
                .findFirst().orElseThrow();
    }

    private static void layout(Stage stage) {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
    }

    private static void snapshot(Stage stage, String name) {
        layout(stage);
        var image = stage.getScene().snapshot(null);
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) {
            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        }
        try {
            Path target = Path.of("build", "reports", "appearance", name + ".png");
            Files.createDirectories(target.getParent());
            ImageIO.write(png, "png", target.toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void fx(Runnable action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { action.run(); done.complete(null); }
            catch (Throwable error) { done.completeExceptionally(error); }
        });
        done.get(30, TimeUnit.SECONDS);
    }
}
