package com.meshtastic.client.themes;

import com.meshtastic.client.TestEnvironmentSupport;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ChatBackgroundViewTest {
    @TempDir Path home;
    private Stage stage;
    private ChatBackgroundView view;
    private final HashMap<String, CompletableFuture<Image>> pending = new HashMap<>();
    private static final String A = "00000000-0000-0000-0000-000000000001.png";
    private static final String B = "00000000-0000-0000-0000-000000000002.png";

    @BeforeEach void setUp() throws Exception {
        TestEnvironmentSupport.setUserHome(home);
        TestEnvironmentSupport.ensureJavaFxStarted();
        fx(() -> {
            view = new ChatBackgroundView(id -> pending.computeIfAbsent(id, _ -> new CompletableFuture<>()));
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 200, 200));
            ThemeManager.applyTheme(stage.getScene(), false);
            stage.show();
        });
    }

    @AfterEach void tearDown() throws Exception {
        fx(() -> { ThemeManager.unregisterScene(stage.getScene()); stage.close(); });
    }

    @Test void cssPassesDoNotOverrideSolidGradientOrImageBackgrounds() throws Exception {
        fx(() -> {
            view.setConfiguration(new ChatBackground(ChatBackground.Kind.COLOR, "#ff0000", null, "", 0, 0, false));
            assertCenterPixel(Color.RED);
            view.setConfiguration(new ChatBackground(ChatBackground.Kind.GRADIENT, "#ff0000", "#0000ff", "", 0, 0, false));
            var snapshot = snapshot();
            assertTrue(snapshot.getPixelReader().getColor(5, 5).getRed() > .9);
            Color end = snapshot.getPixelReader().getColor((int) snapshot.getWidth() - 5,
                    (int) snapshot.getHeight() - 5);
            assertTrue(end.getBlue() > .9, "gradient end=" + end + ", size=" + snapshot.getWidth()
                    + "x" + snapshot.getHeight() + ", view=" + view.getLayoutBounds());
            view.setConfiguration(wallpaper(A));
            pending.get(A).complete(solid(Color.LIME));
        });
        fx(() -> assertCenterPixel(Color.LIME));
    }

    @Test void lateImageFromPreviousChatCannotReplaceCurrentBackgroundAndMissingFilesUseDefault() throws Exception {
        fx(() -> {
            view.setConfiguration(wallpaper(A));
            view.setConfiguration(wallpaper(B));
            pending.get(B).complete(solid(Color.BLUE));
        });
        fx(() -> {
            assertCenterPixel(Color.BLUE);
            pending.get(A).complete(solid(Color.RED));
        });
        fx(() -> {
            assertCenterPixel(Color.BLUE);
            view.setConfiguration(new ChatBackground(ChatBackground.Kind.IMAGE, null, null,
                    "00000000-0000-0000-0000-000000000003.png", 0, 0, false));
            pending.get("00000000-0000-0000-0000-000000000003.png").complete(null);
        });
        fx(() -> assertCenterPixel(Color.WHITE));
    }

    @Test void dimmingAndBlurAreRestrictedToTheWallpaperLayer() throws Exception {
        fx(() -> {
            view.setConfiguration(new ChatBackground(ChatBackground.Kind.COLOR, "#ffffff", null, "", .5, 10, false));
            Color pixel = snapshot().getPixelReader().getColor(100, 100);
            assertEquals(.5, pixel.getRed(), .02);
            assertNull(view.getEffect(), "blur belongs to the artwork, not the parent or messages");
            assertTrue(view.isMouseTransparent());
            assertEquals(0, view.minHeight(-1));
            assertEquals(0, view.prefHeight(-1));
            view.setConfiguration(new ChatBackground(ChatBackground.Kind.COLOR, "#000000", null, "", 0, 10, false));
            assertCenterPixel(Color.BLACK);
        });
    }

    private static ChatBackground wallpaper(String id) {
        return new ChatBackground(ChatBackground.Kind.IMAGE, null, null, id, 0, 0, false);
    }

    private static WritableImage solid(Color color) {
        WritableImage image = new WritableImage(20, 20);
        for (int y = 0; y < 20; y++) for (int x = 0; x < 20; x++) image.getPixelWriter().setColor(x, y, color);
        return image;
    }

    private WritableImage snapshot() {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        return view.snapshot(null, null);
    }

    private void assertCenterPixel(Color expected) {
        var image = snapshot();
        Color actual = image.getPixelReader().getColor((int) image.getWidth() / 2, (int) image.getHeight() / 2);
        assertEquals(expected.getRed(), actual.getRed(), .01);
        assertEquals(expected.getGreen(), actual.getGreen(), .01);
        assertEquals(expected.getBlue(), actual.getBlue(), .01);
    }

    private static void fx(Runnable action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { action.run(); done.complete(null); }
            catch (Throwable error) { done.completeExceptionally(error); }
        });
        done.get(20, TimeUnit.SECONDS);
    }
}
