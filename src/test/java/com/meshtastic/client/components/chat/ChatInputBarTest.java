package com.meshtastic.client.components.chat;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.components.EmojiRenderingSupport;
import com.meshtastic.client.model.MeshMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatInputBarTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void restoresProtectedUploadedImageUrlWhenTextEditRemovesIt() {
        onFxThread(() -> {
            ChatInputBar bar = new ChatInputBar(
                    request -> {},
                    command -> false,
                    query -> List.of(),
                    query -> List.of());
            EmojiTextField input = field(bar, "messageInput", EmojiTextField.class);

            String block = "https://d.privatepractice.app/abCD_123 ";
            setField(bar, "protectedImageBlock", block);
            setField(bar, "protectedImageCaretPosition", block.length());
            input.setText(block + "caption");

            input.setText("caption");

            assertEquals(block + "caption", input.getText());
            assertEquals(block.length(), input.getCaretPosition());
            return null;
        });
    }

    @Test
    void explicitImageRemovalRemovesProtectedUrlFromInput() {
        onFxThread(() -> {
            ChatInputBar bar = new ChatInputBar(
                    request -> {},
                    command -> false,
                    query -> List.of(),
                    query -> List.of());
            EmojiTextField input = field(bar, "messageInput", EmojiTextField.class);

            String url = "https://d.privatepractice.app/abCD_123";
            String block = url + " ";
            setField(bar, "attachedImage", new MeshFilesImage("abCD_123", url, url + "/preview"));
            setField(bar, "protectedImageBlock", block);
            setField(bar, "protectedImageCaretPosition", block.length());
            input.setText(block + "caption");

            invoke(bar, "clearAttachedImage", true);

            assertEquals("caption", input.getText());
            assertEquals(0, input.getCaretPosition());
            return null;
        });
    }

    @Test
    void explicitImageRemovalKeepsSurroundingTextSeparated() {
        onFxThread(() -> {
            ChatInputBar bar = new ChatInputBar(
                    request -> {},
                    command -> false,
                    query -> List.of(),
                    query -> List.of());
            EmojiTextField input = field(bar, "messageInput", EmojiTextField.class);

            String url = "https://d.privatepractice.app/abCD_123";
            String block = " " + url + " ";
            setField(bar, "attachedImage", new MeshFilesImage("abCD_123", url, url + "/preview"));
            setField(bar, "protectedImageBlock", block);
            setField(bar, "protectedImageCaretPosition", ("hello" + block).length());
            input.setText("hello" + block + "caption");

            invoke(bar, "clearAttachedImage", true);

            assertEquals("hello caption", input.getText());
            return null;
        });
    }

    @Test
    void supportedImageFileDetectionUsesAcceptedExtensions(@TempDir Path tempDir) throws Exception {
        File jpeg = Files.writeString(tempDir.resolve("PHOTO.JPEG"), "x").toFile();
        File text = Files.writeString(tempDir.resolve("notes.txt"), "x").toFile();

        assertTrue(ChatInputBar.isSupportedImageFile(jpeg));
        assertFalse(ChatInputBar.isSupportedImageFile(text));
    }

    @Test
    void emojiSenderReplyPreviewStaysSingleLineWhenParentHasSurplusHeight() {
        onFxThread(() -> {
            ChatInputBar bar = new ChatInputBar(
                    request -> {},
                    command -> false,
                    query -> List.of(),
                    query -> List.of());
            VBox root = new VBox(bar);
            VBox.setVgrow(bar, Priority.ALWAYS);
            Scene scene = new Scene(root, 1100, 1200);
            EmojiRenderingSupport.install(scene);

            MeshMessage replyTarget = new MeshMessage("!1ba1fd0c", "!04c5b420", 0,
                    "Просто интересно. Я ловлю и на Тропарёво и на Головинском, а по идее она узконаправленная, есть секрет?)",
                    10,
                    false);
            replyTarget.setPacketId(539469284);
            bar.startReply(replyTarget, "Meshcontinental🐸Travel");

            root.resize(1100, 1200);
            root.applyCss();
            root.layout();

            Label replyQuoteLabel = field(bar, "replyQuoteLabel", Label.class);
            HBox replyBar = field(bar, "replyBar", HBox.class);
            assertNull(replyQuoteLabel.getGraphic());
            assertNotEquals(ContentDisplay.GRAPHIC_ONLY, replyQuoteLabel.getContentDisplay());
            assertTrue(bar.getHeight() < 180, "input bar height: " + bar.getHeight());
            assertTrue(replyBar.getHeight() < 80, "reply bar height: " + replyBar.getHeight());
            return null;
        });
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = ChatInputBar.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = ChatInputBar.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(Object target, String name, boolean argument) throws Exception {
        Method method = ChatInputBar.class.getDeclaredMethod(name, boolean.class);
        method.setAccessible(true);
        method.invoke(target, argument);
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
