package com.meshtastic.client.components.chat;

import com.meshtastic.client.TestEnvironmentSupport;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class EmojiTextFieldTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void positionCaretAndInsertTextClampInsideSurrogatePairs() {
        onFxThread(() -> {
            EmojiTextField field = new EmojiTextField();
            field.setText("A😀B");

            field.positionCaret(2);
            assertEquals(1, field.getCaretPosition());

            field.insertText(2, "X");
            assertEquals("AX😀B", field.getText());
            return null;
        });
    }

    @Test
    void setTextSanitizesBrokenUnicodeBeforeRender() {
        onFxThread(() -> {
            EmojiTextField field = new EmojiTextField();
            field.setText("A\uD83DB\uDC00C");
            assertEquals("ABC", field.getText());
            return null;
        });
    }

    @Test
    void textKeyPressedIsConsumedButTextIsInsertedByKeyTyped() {
        onFxThread(() -> {
            EmojiTextField field = new EmojiTextField();
            StackPane root = new StackPane(field);
            Scene scene = new Scene(root);
            AtomicReference<KeyEvent> bubbledKeyPressed = new AtomicReference<>();
            AtomicReference<KeyEvent> bubbledKeyTyped = new AtomicReference<>();
            scene.addEventHandler(KeyEvent.KEY_PRESSED, bubbledKeyPressed::set);
            scene.addEventHandler(KeyEvent.KEY_TYPED, bubbledKeyTyped::set);

            KeyEvent pressed = new KeyEvent(
                    KeyEvent.KEY_PRESSED, "", "a", KeyCode.A,
                    false, false, false, false);
            Event.fireEvent(field, pressed);
            assertNull(bubbledKeyPressed.get());
            assertEquals("", field.getText());

            KeyEvent typed = new KeyEvent(
                    KeyEvent.KEY_TYPED, "a", "", KeyCode.UNDEFINED,
                    false, false, false, false);
            Event.fireEvent(field, typed);
            assertNull(bubbledKeyTyped.get());
            assertEquals("a", field.getText());
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
