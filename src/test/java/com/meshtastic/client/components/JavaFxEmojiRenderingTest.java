package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class JavaFxEmojiRenderingTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void javafxTextNodesAcceptEmojiAndZwJSequences() {
        onFxThread(() -> {
            String text = "emoji 😀 😁 😂 ❤️‍🔥 ⚠️ A\u0301";
            Label label = new Label(text);
            TextFlow flow = new TextFlow(new Text(text), new Text("\nplain after emoji"));
            VBox root = new VBox(label, flow);

            new Scene(root, 320, 160);
            root.applyCss();
            root.layout();
            root.snapshot(null, null);

            assertEquals(text, label.getText());
            assertEquals(text, ((Text) flow.getChildren().getFirst()).getText());
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
