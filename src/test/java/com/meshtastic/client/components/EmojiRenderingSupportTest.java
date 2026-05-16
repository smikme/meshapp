package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmojiRenderingSupportTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void labeledControlsKeepTextPropertyAndRenderEmojiAsGraphic() {
        onFxThread(() -> {
            Label label = new Label("Node 🇺🇸");
            VBox root = new VBox(label);
            Scene scene = new Scene(root, 240, 80);

            EmojiRenderingSupport.install(scene);
            root.applyCss();
            root.layout();

            assertEquals("Node 🇺🇸", label.getText());
            assertEquals(ContentDisplay.GRAPHIC_ONLY, label.getContentDisplay());
            assertNotNull(label.getGraphic());

            label.setText("Node");
            assertEquals("Node", label.getText());
            assertNull(label.getGraphic());
            return null;
        });
    }

    @Test
    void singleEmojiLabeledControlUsesDirectImageView() {
        onFxThread(() -> {
            Label label = new Label("😻");
            VBox root = new VBox(label);
            Scene scene = new Scene(root, 240, 80);

            EmojiRenderingSupport.install(scene);
            root.applyCss();
            root.layout();

            assertEquals("😻", label.getText());
            assertEquals(ContentDisplay.GRAPHIC_ONLY, label.getContentDisplay());
            assertNotNull(label.getGraphic());
            assertTrue(label.getGraphic() instanceof ImageView);
            return null;
        });
    }

    private static <T> T onFxThread(FxSupplier<T> supplier) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });

        await(latch);
        if (failure.get() != null) {
            throw new AssertionError("JavaFX task failed", failure.get());
        }
        return result.get();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for JavaFX task");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for JavaFX task", e);
        }
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }
}
