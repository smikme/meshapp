package com.meshtastic.client.components.chat;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.themes.TypographyManager;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

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
        });
    }

    @Test
    void setTextSanitizesBrokenUnicodeBeforeRender() {
        onFxThread(() -> {
            EmojiTextField field = new EmojiTextField();
            field.setText("A\uD83DB\uDC00C");
            assertEquals("ABC", field.getText());
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
        });
    }

    @Test
    void wordNavigationSelectionDeletionAndUndoUseStandardShortcuts() {
        onFxThread(() -> {
            EmojiTextField field = new EmojiTextField();
            field.setText("Привет hello Welt");
            field.positionCaret(field.getText().length());
            wordLeft(field, false);
            assertEquals(13, field.getCaretPosition());
            wordLeft(field, true);
            assertEquals("hello ", field.getSelectedText());
            key(field, KeyCode.BACK_SPACE, false, false, false);
            assertEquals("Привет Welt", field.getText());
            shortcut(field, KeyCode.Z, false);
            assertEquals("Привет hello Welt", field.getText());
            assertEquals("hello ", field.getSelectedText());
            shortcut(field, KeyCode.Z, true);
            assertEquals("Привет Welt", field.getText());
        });
    }

    @Test
    void oversizedReplacementPreservesSelectionAndUndoCannotBypassChangedLimit() {
        onFxThread(() -> {
            EmojiTextField field = new EmojiTextField();
            AtomicInteger limit = new AtomicInteger(6);
            field.setMaxBytesSupplier(limit::get);
            field.setText("я😀");
            shortcut(field, KeyCode.A, false);
            field.replaceSelection("😀😀");
            assertEquals("я😀", field.getText());
            assertEquals("я😀", field.getSelectedText());
            field.replaceSelection("hi");
            assertEquals("hi", field.getText());
            limit.set(2);
            field.undo();
            assertEquals("hi", field.getText());
            limit.set(6);
            field.undo();
            assertEquals("я😀", field.getText());
            field.redo();
            assertEquals("hi", field.getText());
            field.clear();
            field.undo();
            assertEquals("", field.getText());
        });
    }

    @Test
    void compactFontMetricsDoNotScrollContentThatFitsInTheField() {
        onFxThread(() -> {
            int previousFontSize = TypographyManager.getChatFontSize();
            TypographyManager.setChatFontSize(20);
            try {
                EmojiTextField field = new EmojiTextField();
                field.setText("word ".repeat(20));
                field.positionCaret(field.getText().length());
                TextFlow content = (TextFlow) field.getChildren().getFirst();
                // Model a fallback font with a line height below the editor's estimate.
                content.getChildren().stream().filter(Text.class::isInstance)
                        .map(Text.class::cast).forEach(text -> text.setFont(Font.font("Monospaced", 10)));
                double width = 180;
                field.resize(width, field.prefHeight(width));
                field.layout();

                assertTrue(content.getHeight() < field.getHeight(), "the complete draft fits in the field");
                assertTrue(content.getBoundsInParent().getMinY() >= 0,
                        () -> "first line must remain visible: " + content.getBoundsInParent());
                assertTrue(content.getBoundsInParent().getMaxY() <= field.getHeight(),
                        "last line must remain visible");
            } finally {
                TypographyManager.setChatFontSize(previousFontSize);
            }
        });
    }

    @Test
    void lineNavigationWorksWithinOneLongWrappedWord() {
        onFxThread(() -> {
            EmojiTextField field = new EmojiTextField();
            StackPane root = new StackPane(field);
            new Scene(root, 180, 180);
            field.setText("abcdefghijklmnopqrstuvwxyz".repeat(3));
            root.resize(180, 180);
            root.applyCss();
            root.layout();
            field.positionCaret(30);
            key(field, KeyCode.HOME, false, false, false);
            int lineStart = field.getCaretPosition();
            assertTrue(lineStart > 0 && lineStart < 30);
            key(field, KeyCode.END, false, false, false);
            assertTrue(field.getCaretPosition() >= 30 && field.getCaretPosition() < field.getText().length());
            key(field, KeyCode.UP, false, false, false);
            assertTrue(field.getCaretPosition() <= lineStart);
            shortcut(field, KeyCode.HOME, false);
            assertEquals(0, field.getCaretPosition());
        });
    }

    private static void shortcut(EmojiTextField field, KeyCode code, boolean shift) {
        boolean mac = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
        key(field, code, shift, !mac, mac);
    }

    private static void wordLeft(EmojiTextField field, boolean shift) {
        boolean mac = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
        Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.LEFT, shift, !mac, mac, false));
    }

    private static void key(EmojiTextField field, KeyCode code, boolean shift, boolean control, boolean meta) {
        Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, meta));
    }

    private static void onFxThread(FxAction action) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                action.run();
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
    private interface FxAction {
        void run() throws Exception;
    }
}
