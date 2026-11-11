package com.meshtastic.client.components.chat;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.MeshMessage;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VirtualMessagePaneTest {
    private VirtualMessagePane pane;
    private ScrollPane scroll;
    private Stage stage;
    private StackPane root;
    private final List<MeshMessage> messages = new ArrayList<>();
    private final Set<HBox> roots = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final HashMap<Integer, Label> pending = new HashMap<>();
    private MessageBubbleFactory factory;

    @BeforeEach void setUp() throws Exception {
        TestEnvironmentSupport.ensureJavaFxStarted();
        fx(() -> {
            pane = new VirtualMessagePane();
            scroll = new ScrollPane(pane);
            scroll.setFitToWidth(true);
            scroll.setFitToHeight(true);
            factory = new MessageBubbleFactory(null, pane.widthProperty(), new Actions(), pending,
                    _ -> new WritableImage(400, 100));
            pane.configure(scroll, (message, recycled) -> {
                var rendered = factory.buildRendered(message, recycled);
                rendered.row().setUserData(message.getDbId());
                roots.add(rendered.row());
                return rendered;
            }, factory::disposeRendered);
            root = new StackPane(scroll);
            stage = new Stage();
            stage.setScene(new Scene(root, 700, 500));
            stage.show();
            for (int i = 1; i <= 150; i++) {
                MeshMessage message = new MeshMessage("!1", "!ffffffff", 0,
                        "Message " + i + "\n" + "variable height text ".repeat(i % 13)
                                + (i % 4 == 0 ? " https://d.privatepractice.app/test00" : ""), i, i % 2 == 0);
                message.setDbId(i);
                message.setPacketId(i);
                message.setReplyContextResolved(true);
                messages.add(message);
            }
            pane.setMessages(messages);
            pane.scrollToMessage(70, 20);
        });
        settle();
    }

    @AfterEach void tearDown() throws Exception {
        fx(() -> { pane.clearMessages(); stage.close(); });
    }

    @Test void prependAndTrimKeepAnchorIncludingInputReceivedBeforeLayout() throws Exception {
        VirtualMessagePane.Anchor[] anchor = new VirtualMessagePane.Anchor[1];
        fx(() -> {
            anchor[0] = pane.captureAnchor();
            List<MeshMessage> next = new ArrayList<>();
            for (int i = -30; i < 0; i++) {
                MeshMessage message = new MeshMessage("!2", "!ffffffff", 0, "older " + i, 1, false);
                message.setDbId(i + 1000);
                next.add(message);
            }
            next.addAll(messages.subList(0, 120));
            pane.setMessages(next);
            // Input can arrive after a page is inserted but before its layout pulse.
            scroll.setVvalue(scroll.getVvalue() - 35 / extent());
        });
        settle();
        fx(() -> assertEquals(anchor[0].offset() - 35, offset(anchor[0].dbId()), 1));
    }

    @Test void imageSizeChangesAboveAnchorAndWindowResizeKeepReadingPosition() throws Exception {
        VirtualMessagePane.Anchor[] anchor = new VirtualMessagePane.Anchor[1];
        fx(() -> {
            anchor[0] = pane.captureAnchor();
            ImageView image = pane.getChildren().stream()
                    .filter(node -> (long) node.getUserData() < anchor[0].dbId())
                    .map(node -> find(node, ImageView.class))
                    .filter(java.util.Objects::nonNull)
                    .filter(view -> view.getImage() instanceof WritableImage).findFirst().orElseThrow();
            image.setImage(new WritableImage(100, 400));
        });
        settle();
        fx(() -> {
            assertEquals(anchor[0].offset(), offset(anchor[0].dbId()), 1);
            stage.setWidth(1000);
        });
        settle();
        fx(() -> {
            assertEquals(anchor[0].offset(), offset(anchor[0].dbId()), 1);
            root.setStyle("-fx-font-size: 22px;");
            pane.invalidateRows();
        });
        settle();
        fx(() -> assertEquals(anchor[0].offset(), offset(anchor[0].dbId()), 1));
    }

    @Test void deletingTheVisibleAnchorPreservesItsSurvivingNeighbor() throws Exception {
        long[] neighborId = new long[1];
        double[] neighborOffset = new double[1];
        fx(() -> {
            var anchor = pane.captureAnchor();
            assertNotNull(anchor);
            neighborId[0] = anchor.dbId() + 1;
            neighborOffset[0] = offset(neighborId[0]);
            messages.removeIf(message -> message.getDbId() == anchor.dbId());
            pane.setMessages(messages);
        });
        settle();
        fx(() -> assertEquals(neighborOffset[0], offset(neighborId[0]), 1));
    }

    @Test void recycledCellsReleaseImagesBindingsAndDeliveryLabels() throws Exception {
        List<ImageView> oldImages = new ArrayList<>();
        fx(() -> {
            for (Node row : pane.getChildren()) collectImages(row, oldImages);
            pane.scrollToBottom();
        });
        settle();
        fx(() -> {
            for (ImageView image : oldImages) {
                if (image.getStyleClass().contains("chat-bubble-image")) {
                    assertNull(image.getImage());
                    assertFalse(image.fitWidthProperty().isBound());
                }
            }
            assertTrue(pane.renderedCount() < 30);
        });
        for (int i = 0; i < 18; i++) {
            int id = 10 + (i * 17) % 130;
            fx(() -> pane.scrollToMessage(id, 10));
            settle();
        }
        fx(() -> {
            assertTrue(roots.size() < 40, "root pool must stay bounded after long scrolling");
            assertTrue(pending.size() <= pane.renderedCount());
            pane.clearMessages();
            assertTrue(pending.isEmpty());
        });
    }

    @Test void bottomRemainsPinnedAfterAnImageChangesHeight() throws Exception {
        fx(pane::scrollToBottom);
        settle();
        fx(() -> {
            var images = new ArrayList<ImageView>();
            pane.getChildren().forEach(row -> collectImages(row, images));
            images.stream().filter(image -> image.getStyleClass().contains("chat-bubble-image"))
                    .forEach(image -> image.setImage(new WritableImage(100, 400)));
        });
        settle();
        fx(() -> assertEquals(1, scroll.getVvalue(), .00001));
    }

    @Test void replacingTheHistoryWindowCanJumpToTheNewBottom() throws Exception {
        fx(() -> {
            pane.clearMessages();
            pane.scrollToBottom();
        });
        settle();
        fx(() -> {
            pane.clearMessages();
            pane.setMessages(messages.subList(100, 150));
            pane.scrollToBottom();
            // The old empty window still has no scrollable extent. A scrollbar
            // update at this point must not turn the pending bottom into a top anchor.
            scroll.setVvalue(1);
        });
        settle();
        fx(() -> {
            assertEquals(1, scroll.getVvalue(), .00001);
            assertTrue(pane.getChildren().stream().anyMatch(row -> Long.valueOf(150).equals(row.getUserData())));
        });
    }

    private double extent() { return pane.getHeight() - scroll.getViewportBounds().getHeight(); }
    private double offset(long id) { return scroll.getVvalue() * extent() - pane.messageTop(id); }
    private void settle() throws Exception {
        for (int i = 0; i < 10; i++) fx(() -> { root.applyCss(); root.layout(); });
    }
    private static <T extends Node> T find(Node node, Class<T> type) {
        if (type.isInstance(node)) return type.cast(node);
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) {
            T result = find(child, type);
            if (result != null) return result;
        }
        return null;
    }
    private static void collectImages(Node node, List<ImageView> images) {
        if (node instanceof ImageView image) images.add(image);
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(child -> collectImages(child, images));
    }
    private static void fx(Runnable task) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try { task.run(); done.complete(null); } catch (Throwable error) { done.completeExceptionally(error); }
        });
        done.get(10, TimeUnit.SECONDS);
    }
    private static class Actions implements MessageBubbleFactory.BubbleActions {
        public void startReply(MeshMessage msg) {}
        public void sendReaction(MeshMessage msg, String emoji) {}
        public void confirmDeleteMessage(MeshMessage msg, HBox row) {}
        public void toggleMessageSelection(MeshMessage msg, HBox row) {}
        public boolean isMessageSelected(MeshMessage msg) { return false; }
        public boolean isMessageSelectionModeActive() { return false; }
        public boolean retryMessage(MeshMessage msg) { return false; }
    }
}
