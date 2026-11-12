package com.meshtastic.client.components.chat;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.service.MessageDbService;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class MessageBubbleFactoryTest {

    @Test
    void meshpicPreviewsWorkForIncomingOutgoingAndRestoredMessages() {
        onFxThread(() -> {
            var opened = new AtomicReference<ChatImage>();
            var loaded = new java.util.ArrayList<String>();
            MessageBubbleFactory factory = new MessageBubbleFactory(null, new SimpleDoubleProperty(600),
                    new NoOpBubbleActions() { @Override public void openChatImage(ChatImage image) { opened.set(image); } },
                    new HashMap<>(), url -> { loaded.add(url); return new javafx.scene.image.WritableImage(2, 2); });
            for (boolean outgoing : new boolean[]{false, true}) {
                var message = new MeshMessage("!2", "!ffffffff", 0, "Фото https://meshpic.org/feZ.", 1, outgoing);
                message.setDbId(42); // The same text path is used for restored history.
                var rendered = factory.buildRendered(message);
                StackPane frame = findNodeWithStyle(rendered.row(), "chat-bubble-image-preview", StackPane.class).orElseThrow();
                frame.getOnMouseClicked().handle(new javafx.scene.input.MouseEvent(
                        javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                        javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false,
                        false, false, false, false, false, false, null));
                assertEquals("https://meshpic.org/image/feZ", opened.get().fullUrl());
                factory.disposeRendered(rendered);
            }
            assertEquals(java.util.List.of("https://meshpic.org/image/feZ?s=t"), loaded);
        });
    }

    @Test
    void missingPreviewKeepsMessageLinkAndShowsErrorImmediately() {
        onFxThread(() -> {
            MessageBubbleFactory factory = new MessageBubbleFactory(null, new SimpleDoubleProperty(600),
                    new NoOpBubbleActions(), new HashMap<>(),
                    _ -> new javafx.scene.image.Image(new java.io.ByteArrayInputStream(new byte[]{1})));
            var message = new MeshMessage("!2", "!ffffffff", 0, "https://meshpic.org/feZ", 1, false);
            var rendered = factory.buildRendered(message);
            assertTrue(findNodeWithStyle(rendered.row(), "chat-bubble-image-error", Label.class).orElseThrow().isVisible());
            assertEquals("https://meshpic.org/feZ", findNodeWithStyle(rendered.row(), "chat-bubble-url-node", Text.class).orElseThrow().getText());
            factory.disposeRendered(rendered);
        });
    }

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void recyclingReusesCompletedPreviewAndEvictsOldImagesAtThePixelBudget() {
        onFxThread(() -> {
            int[] loads = {0};
            MessageBubbleFactory factory = new MessageBubbleFactory(null, new SimpleDoubleProperty(600),
                    new NoOpBubbleActions(), new HashMap<>(), _ -> {
                        loads[0]++;
                        return new javafx.scene.image.WritableImage(1000, 1000);
                    });
            java.util.function.IntFunction<MeshMessage> message = id -> new MeshMessage("!2", "!ffffffff", 0,
                    "https://d.privatepractice.app/test00" + id, 1, false);
            var first = factory.buildRendered(message.apply(1));
            HBox root = first.row();
            factory.disposeRendered(first);
            var repeated = factory.buildRendered(message.apply(1), root);
            assertEquals(root, repeated.row());
            assertEquals(1, loads[0], "returning to a loaded preview must not start another image load");
            factory.disposeRendered(repeated);
            for (int id = 2; id <= 6; id++) factory.disposeRendered(factory.buildRendered(message.apply(id), root));
            factory.disposeRendered(factory.buildRendered(message.apply(1), root));
            assertEquals(7, loads[0], "old previews must be evicted to respect the decoded pixel budget");
        });
    }

    @Test
    void recycledRootDispatchesReplyToItsCurrentMessageAndKeepsItsContextMenu() {
        onFxThread(() -> {
            AtomicReference<MeshMessage> replied = new AtomicReference<>();
            MessageBubbleFactory factory = new MessageBubbleFactory(null, new SimpleDoubleProperty(600),
                    new NoOpBubbleActions() { @Override public void startReply(MeshMessage message) { replied.set(message); } },
                    new HashMap<>());
            MeshMessage old = new MeshMessage("!2", "!ffffffff", 0, "old", 1, false);
            MeshMessage current = new MeshMessage("!3", "!ffffffff", 0, "current", 2, false);
            var first = factory.buildRendered(old);
            HBox root = first.row();
            factory.disposeRendered(first);
            var second = factory.buildRendered(current, root);
            VBox bubble = findNodeWithStyle(second.row(), "chat-bubble-incoming", VBox.class).orElseThrow();
            assertNotNull(bubble.getOnContextMenuRequested());
            bubble.getOnMouseClicked().handle(new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                    javafx.scene.input.MouseButton.PRIMARY, 2, false, false, false, false,
                    false, false, false, false, false, false, null));
            assertEquals(current, replied.get());
            factory.disposeRendered(second);
            assertNull(bubble.getOnContextMenuRequested());
        });
    }

    @Test
    void outgoingBubbleHasPrimaryClickHandlerForSelectionMode() {
        onFxThread(() -> {
            MessageBubbleFactory factory = new MessageBubbleFactory(
                    null,
                    new SimpleDoubleProperty(600),
                    new NoOpBubbleActions(),
                    new HashMap<>());
            MeshMessage outgoing = new MeshMessage("!00000001", "!ffffffff", 0, "own message", 10, true);

            HBox row = factory.build(outgoing);
            VBox content = findNodeWithStyle(row, "chat-bubble-outgoing", VBox.class).orElse(null);

            assertNotNull(content);
            assertNotNull(content.getOnMouseClicked());
        });
    }

    @Test
    void incomingMqttBubbleShowsCloudBadge() {
        onFxThread(() -> {
            MessageBubbleFactory factory = new MessageBubbleFactory(
                    null,
                    new SimpleDoubleProperty(600),
                    new NoOpBubbleActions(),
                    new HashMap<>());
            MeshMessage incoming = new MeshMessage("!00000002", "!ffffffff", 0, "mqtt message", 10, false);
            incoming.setViaMqtt(true);

            HBox row = factory.build(incoming);
            VBox content = findNodeWithStyle(row, "chat-bubble-incoming", VBox.class).orElse(null);
            StackPane badge = findNodeWithStyle(row, "chat-bubble-mqtt-badge", StackPane.class).orElse(null);
            Region icon = findNodeWithStyle(row, "chat-bubble-mqtt-icon", Region.class).orElse(null);

            assertNotNull(content);
            assertNotNull(badge);
            assertNotNull(icon);
            assertTrue(content.getStyleClass().contains("chat-bubble-with-mqtt-badge"));
        });
    }

    @Test
    void incomingBubbleRendersHttpUrlAsClickableText() {
        onFxThread(() -> {
            MessageBubbleFactory factory = new MessageBubbleFactory(
                    null,
                    new SimpleDoubleProperty(600),
                    new NoOpBubbleActions(),
                    new HashMap<>());
            MeshMessage incoming = new MeshMessage("!00000002", "!ffffffff", 0,
                    "open https://meshapp.ru now", 10, false);

            HBox row = factory.build(incoming);
            Text link = findNodeWithStyle(row, "chat-bubble-url-node", Text.class).orElse(null);

            assertNotNull(link);
            assertEquals("https://meshapp.ru", link.getText());
            assertNotNull(link.getOnMouseClicked());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"incoming", "outgoing", "system"})
    void messageContextMenuCopiesLinksAndUpdatesWhenRowIsReused(String kind) {
        onFxThread(() -> {
            MessageBubbleFactory factory = new MessageBubbleFactory(null, new SimpleDoubleProperty(600),
                    new NoOpBubbleActions(), new HashMap<>(), _ -> new javafx.scene.image.WritableImage(2, 2));
            List<String> texts = List.of(
                    "Фото (https://meshpic.org/feZ). Ещё https://meshpic.org/feZ!",
                    "Ссылки https://example.com/a_b?q=1&x=2#part, http://example.org/path(a). https://example.com/a_b?q=1&x=2#part",
                    "Ответ без ссылки",
                    "Повтор https://example.com/shared.",
                    "Основная https://example.com/message.",
                    "Сообщение без ссылки, только https://.");
            List<String> quotes = List.of("", "",
                    "Цитата (https://example.com/quoted?q=1&x=2#fragment).",
                    "Цитата https://example.com/shared!",
                    "Цитата https://example.com/quoted.", "");
            List<List<String>> expected = List.of(
                    List.of("https://meshpic.org/feZ"),
                    List.of("https://example.com/a_b?q=1&x=2#part", "http://example.org/path(a)"),
                    List.of("https://example.com/quoted?q=1&x=2#fragment"),
                    List.of("https://example.com/shared"),
                    List.of("https://example.com/message", "https://example.com/quoted"),
                    List.of());
            StackPane host = new StackPane();
            Stage stage = new Stage();
            try {
                stage.setScene(new Scene(host, 600, 300));
                stage.show();
                HBox reusableRow = null;
                for (int i = 0; i < texts.size(); i++) {
                    if (kind.equals("system") && !quotes.get(i).isEmpty()) continue;
                    MeshMessage message = new MeshMessage("!2", "!ffffffff", 0, texts.get(i), 1, kind.equals("outgoing"));
                    message.setSystemMessage(kind.equals("system"));
                    if (!quotes.get(i).isEmpty()) message.setReplyText(quotes.get(i));
                    var rendered = reusableRow == null ? factory.buildRendered(message)
                            : factory.buildRendered(message, reusableRow);
                    reusableRow = rendered.row();
                    host.getChildren().setAll(rendered.row());
                    host.applyCss();
                    host.layout();
                    VBox bubble = findNodeWithStyle(rendered.row(), "chat-bubble-" + kind, VBox.class).orElseThrow();
                    Node clickTarget = quotes.get(i).isEmpty() ? bubble
                            : findNodeWithStyle(bubble, "chat-bubble-quote", Parent.class).orElseThrow();
                    Event.fireEvent(clickTarget, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                            0, 0, 0, 0, false, null));
                    ContextMenu menu = Window.getWindows().stream()
                            .filter(ContextMenu.class::isInstance).map(ContextMenu.class::cast)
                            .filter(window -> window.isShowing() && window.getOwnerWindow() == stage)
                            .findFirst().orElseThrow();
                    try {
                        MenuItem copyLink = menu.getItems().stream()
                                .filter(item -> I18n.t("chat.bubble.copyLink").equals(item.getText()))
                                .findFirst().orElse(null);
                        if (expected.get(i).isEmpty()) {
                            assertNull(copyLink);
                        } else if (expected.get(i).size() == 1) {
                            assertNotNull(copyLink);
                            assertFalse(copyLink instanceof Menu);
                            copyLink.fire();
                            assertEquals(expected.get(i).getFirst(), Clipboard.getSystemClipboard().getString());
                        } else {
                            Menu links = assertInstanceOf(Menu.class, copyLink);
                            assertEquals(expected.get(i), links.getItems().stream().map(MenuItem::getText).toList());
                            for (MenuItem link : links.getItems()) {
                                assertFalse(link.isMnemonicParsing());
                                link.fire();
                                assertEquals(link.getText(), Clipboard.getSystemClipboard().getString());
                            }
                        }
                        menu.getItems().stream().filter(item -> I18n.t("common.copy").equals(item.getText()))
                                .findFirst().orElseThrow().fire();
                        assertEquals(texts.get(i), Clipboard.getSystemClipboard().getString());
                    } finally {
                        menu.hide();
                        factory.disposeRendered(rendered);
                    }
                }
            } finally {
                stage.close();
                Clipboard.getSystemClipboard().clear();
            }
        });
    }

    @Test
    void outgoingDirectDeliveredBubbleShowsCheckStatus() {
        onFxThread(() -> {
            MessageBubbleFactory factory = new MessageBubbleFactory(
                    null,
                    new SimpleDoubleProperty(600),
                    new NoOpBubbleActions(),
                    new HashMap<>());
            MeshMessage direct = new MeshMessage("!00000001", "!00000002", 0, "direct", 10, true);
            direct.setStatus(MeshMessage.DeliveryStatus.DELIVERED);

            HBox row = factory.build(direct);
            Label status = findNodeWithStyle(row, "chat-bubble-status", Label.class).orElse(null);

            assertNotNull(status);
            assertEquals("✓", status.getText());
            assertFalse(status.getStyleClass().contains("chat-bubble-status-ok"));
        });
    }

    @Test
    void outgoingDirectConfirmedBubbleShowsOkStatus() {
        onFxThread(() -> {
            MessageBubbleFactory factory = new MessageBubbleFactory(
                    null,
                    new SimpleDoubleProperty(600),
                    new NoOpBubbleActions(),
                    new HashMap<>());
            MeshMessage direct = new MeshMessage("!00000001", "!00000002", 0, "direct", 10, true);
            direct.setStatus(MeshMessage.DeliveryStatus.CONFIRMED);

            HBox row = factory.build(direct);
            Label status = findNodeWithStyle(row, "chat-bubble-status", Label.class).orElse(null);

            assertNotNull(status);
            assertNull(status.getText());
            assertNotNull(status.getGraphic());
            assertInstanceOf(SVGPath.class, status.getGraphic());
            assertTrue(status.getStyleClass().contains("chat-bubble-status-ok"));
        });
    }

    @Test
    void replyHighlightIsScopedToCurrentOwnerAndChat(@TempDir Path tempHome) {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();

        DeviceState ownerAState = new DeviceState();
        DeviceState ownerBState = new DeviceState();
        try {
            ownerAState.setMyNodeNum(0x11111111);
            ownerBState.setMyNodeNum(0x22222222);

            MeshMessage ownerAOriginal = new MeshMessage("!11111111", "!ffffffff", 0, "owner a", 10, true);
            ownerAOriginal.setPacketId(9001);
            MessageDbService.getInstance().save(ownerAOriginal, "channel", "0", "!11111111");

            MeshMessage incomingReply = new MeshMessage("!33333333", "!ffffffff", 0, "reply", 20, false);
            incomingReply.setReplyId(9001);

            onFxThread(() -> {
                HBox ownerARow = factoryFor(ownerAState).build(incomingReply);
                HBox ownerBRow = factoryFor(ownerBState).build(incomingReply);

                VBox ownerAContent = findNodeWithStyle(ownerARow, "chat-bubble-incoming", VBox.class).orElseThrow();
                VBox ownerBContent = findNodeWithStyle(ownerBRow, "chat-bubble-incoming", VBox.class).orElseThrow();

                assertTrue(ownerAContent.getStyleClass().contains("chat-bubble-mentioned"));
                assertFalse(ownerBContent.getStyleClass().contains("chat-bubble-mentioned"));
            });
        } finally {
            ownerAState.shutdown();
            ownerBState.shutdown();
            TestEnvironmentSupport.resetSingletons();
        }
    }

    @Test
    void remoteReplyToOutgoingHighlightsWithoutDeviceState() {
        onFxThread(() -> {
            MessageBubbleFactory factory = new MessageBubbleFactory(
                    null,
                    new SimpleDoubleProperty(600),
                    new NoOpBubbleActions(),
                    new HashMap<>());
            MeshMessage incomingReply = new MeshMessage("!33333333", "!ffffffff", 0, "reply", 20, false);
            incomingReply.setReplyId(9001);
            incomingReply.setReplyToOutgoing(true);

            HBox row = factory.build(incomingReply);
            VBox content = findNodeWithStyle(row, "chat-bubble-incoming", VBox.class).orElseThrow();

            assertTrue(content.getStyleClass().contains("chat-bubble-mentioned"));
        });
    }

    private static MessageBubbleFactory factoryFor(DeviceState state) {
        return new MessageBubbleFactory(
                state,
                new SimpleDoubleProperty(600),
                new NoOpBubbleActions(),
                new HashMap<>());
    }

    private static <T extends Node> Optional<T> findNodeWithStyle(Node node, String styleClass, Class<T> type) {
        if (type.isInstance(node) && node.getStyleClass().contains(styleClass)) {
            return Optional.of(type.cast(node));
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Optional<T> match = findNodeWithStyle(child, styleClass, type);
                if (match.isPresent()) {
                    return match;
                }
            }
        }
        return Optional.empty();
    }

    private static void onFxThread(FxTask task) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                task.run();
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
    private interface FxTask {
        void run() throws Exception;
    }

    private static class NoOpBubbleActions implements MessageBubbleFactory.BubbleActions {
        @Override public void startReply(MeshMessage msg) {}
        @Override public void sendReaction(MeshMessage msg, String emoji) {}
        @Override public void confirmDeleteMessage(MeshMessage msg, HBox bubbleRow) {}
        @Override public void toggleMessageSelection(MeshMessage msg, HBox bubbleRow) {}
        @Override public boolean isMessageSelected(MeshMessage msg) { return false; }
        @Override public boolean isMessageSelectionModeActive() { return false; }
        @Override public boolean retryMessage(MeshMessage msg) { return false; }
    }
    @Test
    void verifiedReceiptHasDistinctLabelAndExplanation() {
        onFxThread(() -> {
            Label label = new Label();
            MessageBubbleFactory.updateStatusLabel(label, MeshMessage.DeliveryStatus.VERIFIED);
            assertEquals("✓✓", label.getText());
            assertNotNull(label.getTooltip());
            MessageBubbleFactory.updateStatusLabel(label, MeshMessage.DeliveryStatus.DELIVERED);
            assertEquals("✓", label.getText());
            assertNull(label.getTooltip());
        });
    }

}
