package com.meshtastic.client.components.chat;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.MeshMessage;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageBubbleFactoryTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void outgoingBubbleDoesNotStartReplyOnDoubleClick() {
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
            assertNull(content.getOnMouseClicked());
            return null;
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
            return null;
        });
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

    private static final class NoOpBubbleActions implements MessageBubbleFactory.BubbleActions {
        @Override public void startReply(MeshMessage msg) {}
        @Override public void requestTraceroute(MeshMessage msg) {}
        @Override public void requestNodeInfo(MeshMessage msg) {}
        @Override public void sendReaction(MeshMessage msg, String emoji) {}
        @Override public void confirmDeleteMessage(MeshMessage msg, HBox bubbleRow) {}
        @Override public boolean retryMessage(MeshMessage msg) { return false; }
    }
}
