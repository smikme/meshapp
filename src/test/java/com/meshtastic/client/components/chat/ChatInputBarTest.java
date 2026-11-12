package com.meshtastic.client.components.chat;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.components.EmojiRenderingSupport;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.AppPreferences;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import javafx.scene.image.WritableImage;
import javafx.event.Event;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatInputBarTest {

    @Test
    void compactionTogglePreservesOverLimitDraftAndAllowsItToShrink(@TempDir Path home) {
        TestEnvironmentSupport.setUserHome(home);
        onFxThread(() -> {
            try {
                AppPreferences.setCyrillicCompactionEnabled(true);
                ChatInputBar bar = new ChatInputBar(_ -> {}, _ -> false, _ -> List.of(), _ -> List.of());
                bar.setProtocolTextLimit(() -> 6);
                EmojiTextField input = field(bar, "messageInput", EmojiTextField.class);
                SendButtonWithRing ring = field(bar, "sendRing", SendButtonWithRing.class);
                input.insertText(0, "аааааа");
                input.insertText(6, "а");
                assertEquals("аааааа", input.getText());
                assertFalse(ring.getSendButton().isDisabled());
                AppPreferences.setCyrillicCompactionEnabled(false);
                assertEquals("аааааа", input.getText());
                assertTrue(ring.getSendButton().isDisabled());
                assertTrue(input.replaceRange(5, 6, ""));
                assertEquals("ааааа", input.getText());
                assertTrue(ring.getSendButton().isDisabled());
                input.undo();
                assertEquals("ааааа", input.getText(), "undo cannot enlarge an over-limit draft");
                assertTrue(input.replaceRange(3, 5, ""));
                assertFalse(ring.getSendButton().isDisabled());
                AppPreferences.setCyrillicCompactionEnabled(true);
                input.undo();
                assertEquals("ааааа", input.getText());
                AppPreferences.setCyrillicCompactionEnabled(false);
                assertTrue(ring.getSendButton().isDisabled(), "subsequent preference changes must also refresh the counter");
                input.redo();
                assertEquals("ааа", input.getText());
                assertFalse(ring.getSendButton().isDisabled());
            } finally { AppPreferences.setCyrillicCompactionEnabled(false); }
            return null;
        });
    }

    @Test
    void pasteSpellingReplacementAndSendShareTheEncodedBudget(@TempDir Path home) {
        TestEnvironmentSupport.setUserHome(home);
        onFxThread(() -> {
            try {
                AppPreferences.setCyrillicCompactionEnabled(true);
                AtomicReference<ChatInputBar.SendRequest> sent = new AtomicReference<>();
                ChatInputBar bar = new ChatInputBar(sent::set, _ -> false, _ -> List.of(), _ -> List.of());
                bar.setProtocolTextLimit(() -> 6);
                EmojiTextField input = field(bar, "messageInput", EmojiTextField.class);
                var clipboard = new javafx.scene.input.ClipboardContent();
                clipboard.putString("аа😀а");
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(clipboard);
                paste(input);
                assertEquals("аа😀", input.getText());
                assertFalse(input.replaceRange(0, 2, "ааа"), "spelling replacements use the same limit");
                input.undo();
                assertEquals("", input.getText());
                input.redo();
                assertEquals("аа😀", input.getText());
                Event.fireEvent(input, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                        false, false, false, false));
                assertEquals("аа😀", sent.get().text(), "the editor passes original text to the sending service");
                assertEquals("", input.getText());
                input.undo();
                assertEquals("", input.getText());
                clipboard.putString("/аааааааааа");
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(clipboard);
                paste(input);
                assertEquals("/аа", input.getText(), "command prefixes must be measured with their arguments");
            } finally { AppPreferences.setCyrillicCompactionEnabled(false); }
            return null;
        });
    }

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    private static void paste(EmojiTextField input) {
        boolean mac = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
        Event.fireEvent(input, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.V,
                false, !mac, false, mac));
    }

    @ParameterizedTest
    @ValueSource(strings = {"shortcut", "shiftInsert", "contextMenu"})
    void pastesClipboardImageAsPngAttachmentWithoutSending(String gesture) throws Exception {
        var uploaded = new CompletableFuture<byte[]>();
        var pending = new CompletableFuture<ChatImage>();
        var uploadCount = new AtomicInteger();
        var sent = new AtomicReference<ChatInputBar.SendRequest>();
        ChatInputBar bar = onFxThread(() -> {
            var inputBar = new ChatInputBar(sent::set, _ -> false, _ -> List.of(), _ -> List.of(), null,
                    _ -> { throw new AssertionError("clipboard paste must not require a file"); },
                    _ -> new WritableImage(2, 2), bytes -> {
                        assertFalse(Platform.isFxApplicationThread(), "PNG encoding must not block the FX thread");
                        uploadCount.incrementAndGet();
                        uploaded.complete(bytes);
                        return pending;
                    });
            EmojiTextField input = field(inputBar, "messageInput", EmojiTextField.class);
            input.setText("до после");
            input.positionCaret(3);
            putClipboardImage();
            if (gesture.equals("contextMenu")) {
                Stage stage = new Stage();
                try {
                    stage.setScene(new Scene(new VBox(inputBar), 500, 200));
                    stage.show();
                    Event.fireEvent(input, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                            0, 0, 0, 0, true, null));
                    Field contextMenuField = EmojiTextField.class.getDeclaredField("contextMenu");
                    contextMenuField.setAccessible(true);
                    ContextMenu menu = (ContextMenu) contextMenuField.get(input);
                    var pasteItem = menu.getItems().stream()
                            .filter(item -> com.meshtastic.client.i18n.I18n.t("common.paste").equals(item.getText()))
                            .findFirst().orElseThrow();
                    assertFalse(pasteItem.isDisable(), "image-only clipboard must enable Paste");
                    pasteItem.fire();
                    menu.hide();
                } finally {
                    stage.close();
                }
            } else if (gesture.equals("shiftInsert")) {
                Event.fireEvent(input, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.INSERT,
                        true, false, false, false));
            } else {
                // Browsers can offer both pixels and text; the image must take priority.
                var content = new ClipboardContent();
                content.putImage(Clipboard.getSystemClipboard().getImage());
                content.putString("source text");
                Clipboard.getSystemClipboard().setContent(content);
                paste(input);
            }
            assertTrue(field(inputBar, "imageUploadInProgress", Boolean.class));
            assertEquals("до после", input.getText());
            paste(input); // A second paste must not start a parallel upload.
            return inputBar;
        });

        var png = ImageIO.read(new ByteArrayInputStream(uploaded.get(5, TimeUnit.SECONDS)));
        assertEquals(3, png.getWidth());
        assertEquals(2, png.getHeight());
        assertEquals(0xffff0000, png.getRGB(0, 0));
        assertEquals(0x8000ff00, png.getRGB(1, 1));
        assertEquals(0, png.getRGB(2, 1));
        assertEquals(1, uploadCount.get());
        pending.complete(meshpicImage());
        onFxThread(() -> {
            assertEquals("до https://meshpic.org/feZ после", field(bar, "messageInput", EmojiTextField.class).getText());
            assertEquals(meshpicImage(), field(bar, "attachedImage", ChatImage.class));
            assertFalse(field(bar, "imageUploadInProgress", Boolean.class));
            assertNull(sent.get(), "pasting an image must not send the draft");
            bar.clear();
            Clipboard.getSystemClipboard().clear();
            return null;
        });
    }

    private static void putClipboardImage() {
        WritableImage image = new WritableImage(3, 2);
        image.getPixelWriter().setArgb(0, 0, 0xffff0000);
        image.getPixelWriter().setArgb(1, 1, 0x8000ff00);
        var clipboard = new ClipboardContent();
        clipboard.putImage(image);
        Clipboard.getSystemClipboard().setContent(clipboard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "cancelled", "tooLong"})
    void clipboardUploadKeepsDraftOnFailureOrAfterChatSwitch(String outcome) throws Exception {
        var started = new CompletableFuture<Void>();
        var pending = new CompletableFuture<ChatImage>();
        ChatInputBar bar = onFxThread(() -> {
            var inputBar = new ChatInputBar(_ -> {}, _ -> false, _ -> List.of(), _ -> List.of(), null,
                    _ -> { throw new AssertionError("unexpected file upload"); },
                    _ -> new WritableImage(2, 2), _ -> { started.complete(null); return pending; });
            EmojiTextField input = field(inputBar, "messageInput", EmojiTextField.class);
            input.setText("подпись");
            if (outcome.equals("tooLong")) inputBar.setProtocolTextLimit(() -> 15);
            putClipboardImage();
            paste(input);
            if (outcome.equals("cancelled")) {
                inputBar.clear();
                input.setText("новый чат");
            }
            return inputBar;
        });
        started.get(5, TimeUnit.SECONDS);
        if (outcome.equals("failed")) pending.completeExceptionally(new java.io.IOException("offline"));
        else pending.complete(meshpicImage());
        onFxThread(() -> {
            assertEquals(outcome.equals("cancelled") ? "новый чат" : "подпись",
                    field(bar, "messageInput", EmojiTextField.class).getText());
            assertNull(field(bar, "attachedImage", ChatImage.class));
            assertFalse(field(bar, "imageUploadInProgress", Boolean.class));
            bar.clear();
            Clipboard.getSystemClipboard().clear();
            return null;
        });
    }

    @Test
    void clipboardImageIsIgnoredWhenInputIsDisabledOrPickingANode() {
        onFxThread(() -> {
            var bar = new ChatInputBar(_ -> {}, _ -> false, _ -> List.of(), _ -> List.of(), null,
                    _ -> { throw new AssertionError("unexpected file upload"); },
                    _ -> new WritableImage(2, 2), _ -> { throw new AssertionError("unexpected image upload"); });
            EmojiTextField input = field(bar, "messageInput", EmojiTextField.class);
            input.setText("подпись");
            putClipboardImage();
            bar.setInputEnabled(false);
            paste(input);
            assertEquals("подпись", input.getText());
            assertFalse(field(bar, "imageUploadInProgress", Boolean.class));
            bar.setInputEnabled(true);
            bar.startNodePick("нода", "Выберите ноду", _ -> {}, () -> {});
            paste(input);
            assertEquals("нода", input.getText());
            assertFalse(field(bar, "imageUploadInProgress", Boolean.class));
            bar.clear();
            Clipboard.getSystemClipboard().clear();
            return null;
        });
    }

    @Test
    void editingHistoryPreservesByteCounterLimitAndSendBehavior() {
        onFxThread(() -> {
            AtomicReference<ChatInputBar.SendRequest> sent = new AtomicReference<>();
            ChatInputBar bar = new ChatInputBar(sent::set, _ -> false, _ -> List.of(), _ -> List.of());
            bar.setProtocolTextLimit(() -> 6);
            EmojiTextField input = field(bar, "messageInput", EmojiTextField.class);
            SendButtonWithRing ring = field(bar, "sendRing", SendButtonWithRing.class);
            Field ratio = SendButtonWithRing.class.getDeclaredField("currentRatio");
            ratio.setAccessible(true);
            input.insertText(0, "я😀");
            assertEquals(1.0, ratio.getDouble(ring), 0.001);
            input.insertText(input.getCaretPosition(), "x");
            assertEquals("я😀", input.getText());
            assertEquals(3, input.getCaretPosition());
            input.undo();
            assertEquals("", input.getText());
            assertEquals(0.0, ratio.getDouble(ring), 0.001);
            input.redo();
            assertEquals("я😀", input.getText());
            assertEquals(1.0, ratio.getDouble(ring), 0.001);
            Event.fireEvent(input, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                    false, false, false, false));
            assertEquals("я😀", sent.get().text());
            input.undo();
            assertEquals("", input.getText(), "sent drafts must not reappear through undo");
            return null;
        });
    }

    @Test
    void editingAndUndoKeepTheProtectedImageUrlIntact() {
        onFxThread(() -> {
            ChatInputBar bar = new ChatInputBar(_ -> {}, _ -> false, _ -> List.of(), _ -> List.of());
            EmojiTextField input = field(bar, "messageInput", EmojiTextField.class);
            String url = "https://meshpic.org/feZ ";
            setField(bar, "protectedImageBlock", url);
            input.setText(url + "helo");
            input.positionCaret(input.getText().length());
            assertFalse(input.replaceRange(0, input.getText().length(), "hello"));
            assertEquals(url + "helo", input.getText());
            assertTrue(input.replaceRange(url.length(), input.getText().length(), "hello"));
            input.undo();
            assertEquals(url + "helo", input.getText());
            input.redo();
            assertEquals(url + "hello", input.getText());
            bar.setProtocolTextLimit(() -> input.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            assertFalse(input.replaceRange(url.length(), input.getText().length(), "greetings"));
            assertEquals(url + "hello", input.getText());
            return null;
        });
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
    void wrappedInputGrowsUpwardAndShrinksWithItsDraft() {
        onFxThread(() -> {
            int previousFontSize = TypographyManager.getChatFontSize();
            TypographyManager.setChatFontSize(20);
            ChatInputBar bar = new ChatInputBar(
                    _ -> {}, _ -> false, _ -> List.of(), _ -> List.of());
            EmojiTextField input = field(bar, "messageInput", EmojiTextField.class);
            Region messages = new Region();
            messages.setPrefHeight(1000);
            VBox.setVgrow(messages, Priority.ALWAYS);
            VBox root = new VBox(messages, bar.getInputSeparator(), bar);
            Scene scene = new Scene(root, 840, 400);
            ThemeManager.applyTheme(scene, false);
            try {
                layoutChat(root, 840);
                double emptyHeight = bar.getHeight();
                double bottom = bar.getLayoutY() + bar.getHeight();
                List<Node> controls = List.of(bar.lookup(".chat-emoji-btn"),
                        field(bar, "imageBtn", Node.class), field(bar, "sendRing", Node.class));
                List<Double> controlPositions = controls.stream()
                        .map(control -> control.localToScene(control.getLayoutBounds()).getMinY()).toList();
                var inputBounds = input.localToScene(input.getLayoutBounds());
                for (Node control : controls) {
                    var bounds = control.localToScene(control.getLayoutBounds());
                    assertEquals(inputBounds.getCenterY(), bounds.getCenterY(), 1,
                            "controls must keep their original single-line centered alignment");
                }

                input.setText("word ".repeat(45));
                input.positionCaret(input.getText().length());
                layoutChat(root, 840);
                double wrappedHeight = bar.getHeight();
                assertTrue(wrappedHeight > emptyHeight, "wrapped draft must grow the whole input bar");
                assertEquals(bottom, bar.getLayoutY() + bar.getHeight(), 1,
                        "input bar must keep its bottom edge fixed");
                assertInputFitsBar(input, bar);
                assertInputContentVisible(input);
                assertControlPositions(controls, controlPositions);

                layoutChat(root, 420);
                assertTrue(bar.getHeight() > wrappedHeight, "narrower windows must reserve room for more lines");
                assertEquals(bottom, bar.getLayoutY() + bar.getHeight(), 1);
                assertInputFitsBar(input, bar);
                assertCaretVisible(input);
                assertControlPositions(controls, controlPositions);

                input.positionCaret(0);
                layoutChat(root, 420);
                assertCaretVisible(input);
                input.positionCaret(input.getText().length());
                layoutChat(root, 420);
                assertCaretVisible(input);

                layoutChat(root, 840);
                assertEquals(wrappedHeight, bar.getHeight(), 1);
                assertInputContentVisible(input);

                input.clear();
                layoutChat(root, 420);
                assertEquals(emptyHeight, bar.getHeight(), 1, "clearing the draft must restore a single line");
                assertEquals(bottom, bar.getLayoutY() + bar.getHeight(), 1);
                assertControlPositions(controls, controlPositions);
                return null;
            } finally {
                ThemeManager.unregisterScene(scene);
                TypographyManager.setChatFontSize(previousFontSize);
            }
        });
    }

    private static void layoutChat(VBox root, double width) {
        root.resize(width, 400);
        root.applyCss();
        root.layout();
    }

    private static void assertControlPositions(List<Node> controls, List<Double> positions) {
        for (int i = 0; i < controls.size(); i++) {
            Node control = controls.get(i);
            assertEquals(positions.get(i), control.localToScene(control.getLayoutBounds()).getMinY(), 1,
                    "controls must stay at their original vertical positions as the input grows or shrinks");
        }
    }

    private static void assertInputFitsBar(EmojiTextField input, ChatInputBar bar) {
        var inputBounds = input.localToScene(input.getLayoutBounds());
        var barBounds = bar.localToScene(bar.getLayoutBounds());
        assertTrue(inputBounds.getMinY() >= barBounds.getMinY(), "input must not overlap message history");
        assertTrue(inputBounds.getMaxY() <= barBounds.getMaxY(), "input must not extend below the window");
    }

    private static void assertInputContentVisible(EmojiTextField input) {
        Node content = input.getChildren().getFirst();
        assertTrue(content.getBoundsInParent().getMinY() >= 0, "first line must remain visible");
        assertTrue(content.getBoundsInParent().getMaxY() <= input.getHeight(), "last line must remain visible");
        assertCaretVisible(input);
    }

    private static void assertCaretVisible(EmojiTextField input) {
        Node caret = input.lookup(".emoji-text-field-caret");
        assertTrue(caret.getBoundsInParent().getMinY() >= 0, "caret must not scroll above the field");
        assertTrue(caret.getBoundsInParent().getMaxY() <= input.getHeight(), "caret must not scroll below the field");
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
