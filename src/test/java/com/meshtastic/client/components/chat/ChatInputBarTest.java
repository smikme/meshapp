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
import java.util.concurrent.CompletableFuture;
import javafx.scene.image.WritableImage;

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
                    _ -> {},
                    _ -> false,
                    _ -> List.of(),
                    _ -> List.of());
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
                    _ -> {},
                    _ -> false,
                    _ -> List.of(),
                    _ -> List.of());
            EmojiTextField input = field(bar, "messageInput", EmojiTextField.class);

            String url = "https://d.privatepractice.app/abCD_123";
            String block = url + " ";
            setField(bar, "attachedImage", new ChatImage("abCD_123", url, url + "/preview", url, null));
            setField(bar, "protectedImageBlock", block);
            setField(bar, "protectedImageCaretPosition", block.length());
            input.setText(block + "caption");

            removeAttachedImage(bar);

            assertEquals("caption", input.getText());
            assertEquals(0, input.getCaretPosition());
            return null;
        });
    }

    @Test
    void explicitImageRemovalKeepsSurroundingTextSeparated() {
        onFxThread(() -> {
            ChatInputBar bar = new ChatInputBar(
                    _ -> {},
                    _ -> false,
                    _ -> List.of(),
                    _ -> List.of());
            EmojiTextField input = field(bar, "messageInput", EmojiTextField.class);

            String url = "https://d.privatepractice.app/abCD_123";
            String block = " " + url + " ";
            setField(bar, "attachedImage", new ChatImage("abCD_123", url, url + "/preview", url, null));
            setField(bar, "protectedImageBlock", block);
            setField(bar, "protectedImageCaretPosition", ("hello" + block).length());
            input.setText("hello" + block + "caption");

            removeAttachedImage(bar);

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
        assertTrue(ChatInputBar.isSupportedImageFile(Files.writeString(tempDir.resolve("PHOTO.WEBP"), "x").toFile()));
        assertFalse(ChatInputBar.isSupportedImageFile(Files.writeString(tempDir.resolve("old.bmp"), "x").toFile()));
        assertFalse(ChatInputBar.isSupportedImageFile(Files.writeString(tempDir.resolve("old.tiff"), "x").toFile()));
    }

    @Test
    void successfulUploadInsertsShortUrlAtCaretAndLoadsOnlyThumbnail(@TempDir Path directory) throws Exception {
        var pending = new CompletableFuture<ChatImage>();
        var loaded = new AtomicReference<String>();
        var sent = new AtomicReference<ChatInputBar.SendRequest>();
        File file = Files.writeString(directory.resolve("test.webp"), "fixture").toFile();
        ChatInputBar bar = onFxThread(() -> {
            var inputBar = new ChatInputBar(sent::set, _ -> false, _ -> List.of(), _ -> List.of(), null,
                    _ -> pending, url -> { loaded.set(url); return new WritableImage(2, 2); });
            field(inputBar, "messageInput", EmojiTextField.class).setText("до после");
            setField(inputBar, "savedCaretPosition", 3);
            startUpload(inputBar, file);
            return inputBar;
        });
        pending.complete(meshpicImage());
        onFxThread(() -> {
            assertEquals("до https://meshpic.org/feZ после", field(bar, "messageInput", EmojiTextField.class).getText());
            assertEquals(meshpicImage().previewUrl(), loaded.get());
            assertEquals(meshpicImage(), field(bar, "attachedImage", ChatImage.class));
            assertNull(sent.get(), "upload must not send a message");
            assertFalse(field(bar, "imageUploadInProgress", Boolean.class));
            bar.clear();
            return null;
        });
    }

    @Test
    void failedReplacementKeepsOldAttachmentAndCaption(@TempDir Path directory) throws Exception {
        var pending = new CompletableFuture<ChatImage>();
        File file = Files.writeString(directory.resolve("test.png"), "fixture").toFile();
        ChatInputBar bar = onFxThread(() -> {
            var inputBar = testBar(pending);
            String block = meshpicImage().shareUrl() + " ";
            setField(inputBar, "attachedImage", meshpicImage());
            setField(inputBar, "protectedImageBlock", block);
            field(inputBar, "messageInput", EmojiTextField.class).setText(block + "подпись");
            startUpload(inputBar, file);
            return inputBar;
        });
        pending.completeExceptionally(new java.io.IOException("offline"));
        onFxThread(() -> {
            assertEquals(meshpicImage().shareUrl() + " подпись", field(bar, "messageInput", EmojiTextField.class).getText());
            assertEquals(meshpicImage(), field(bar, "attachedImage", ChatImage.class));
            bar.clear();
            return null;
        });
    }

    @Test
    void lateUploadCannotModifyAnotherChatDraft(@TempDir Path directory) throws Exception {
        var pending = new CompletableFuture<ChatImage>();
        File file = Files.writeString(directory.resolve("test.png"), "fixture").toFile();
        ChatInputBar bar = onFxThread(() -> {
            var inputBar = testBar(pending);
            startUpload(inputBar, file);
            inputBar.clear();
            field(inputBar, "messageInput", EmojiTextField.class).setText("новый чат");
            return inputBar;
        });
        pending.complete(meshpicImage());
        onFxThread(() -> {
            assertEquals("новый чат", field(bar, "messageInput", EmojiTextField.class).getText());
            assertNull(field(bar, "attachedImage", ChatImage.class));
            return null;
        });
    }

    @Test
    void utf8LimitFailurePreservesDraft(@TempDir Path directory) throws Exception {
        var pending = new CompletableFuture<ChatImage>();
        File file = Files.writeString(directory.resolve("test.png"), "fixture").toFile();
        ChatInputBar bar = onFxThread(() -> {
            var inputBar = testBar(pending);
            inputBar.setProtocolTextLimit(() -> 30);
            field(inputBar, "messageInput", EmojiTextField.class).setText("Привет");
            startUpload(inputBar, file);
            return inputBar;
        });
        pending.complete(meshpicImage());
        onFxThread(() -> {
            assertEquals("Привет", field(bar, "messageInput", EmojiTextField.class).getText());
            assertNull(field(bar, "attachedImage", ChatImage.class));
            return null;
        });
    }

    private static ChatInputBar testBar(CompletableFuture<ChatImage> pending) {
        return new ChatInputBar(_ -> {}, _ -> false, _ -> List.of(), _ -> List.of(), null,
                _ -> pending, _ -> new WritableImage(2, 2));
    }

    @Test
    void successfulReplacementDoesNotJoinWordsAroundOldUrl(@TempDir Path directory) throws Exception {
        var pending = new CompletableFuture<ChatImage>();
        File file = Files.writeString(directory.resolve("test.png"), "fixture").toFile();
        ChatInputBar bar = onFxThread(() -> {
            var inputBar = testBar(pending);
            String block = " " + meshpicImage().shareUrl() + " ";
            String text = "hello" + block + "caption";
            setField(inputBar, "attachedImage", meshpicImage());
            setField(inputBar, "protectedImageBlock", block);
            field(inputBar, "messageInput", EmojiTextField.class).setText(text);
            setField(inputBar, "savedCaretPosition", text.length());
            startUpload(inputBar, file);
            return inputBar;
        });
        pending.complete(ChatUrlParser.chatImage("https://meshpic.org/7L3").orElseThrow());
        onFxThread(() -> {
            assertEquals("hello caption https://meshpic.org/7L3 ", field(bar, "messageInput", EmojiTextField.class).getText());
            bar.clear();
            return null;
        });
    }

    private static ChatImage meshpicImage() {
        return ChatUrlParser.chatImage("https://meshpic.org/feZ").orElseThrow();
    }

    private static void startUpload(ChatInputBar bar, File file) throws Exception {
        Method method = ChatInputBar.class.getDeclaredMethod("uploadSelectedImage", File.class);
        method.setAccessible(true);
        method.invoke(bar, file);
    }

    @Test
    void emojiSenderReplyPreviewStaysSingleLineWhenParentHasSurplusHeight() {
        onFxThread(() -> {
            ChatInputBar bar = new ChatInputBar(
                    _ -> {},
                    _ -> false,
                    _ -> List.of(),
                    _ -> List.of());
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

    private static void removeAttachedImage(ChatInputBar bar) throws Exception {
        Method method = ChatInputBar.class.getDeclaredMethod("clearAttachedImage", boolean.class);
        method.setAccessible(true);
        method.invoke(bar, true);
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
