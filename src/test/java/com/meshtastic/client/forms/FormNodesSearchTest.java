package com.meshtastic.client.forms;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.components.NodeDetailContent;
import com.meshtastic.client.model.NodeData;
import javafx.application.Platform;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class FormNodesSearchTest {

    private record DetailScrollWrap(ScrollPane scrollPane, NodeDetailContent content) {}

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void matchesSearchQueryByLegacyHexNodeIdEvenIfUserIdChanged() {
        NodeData node = new NodeData(0x55667788);
        node.setNodeId("alice-custom-id");
        node.setLongName("Alice");

        assertTrue(FormNodes.matchesSearchQuery(node, "!55667788"));
        assertTrue(FormNodes.matchesSearchQuery(node, "55667788"));
    }

    @Test
    void matchesSearchQueryForUnnamedNodeByNumericId() {
        NodeData node = new NodeData(0x01020304);

        assertTrue(FormNodes.matchesSearchQuery(node, "!01020304"));
        assertTrue(FormNodes.matchesSearchQuery(node, "16909060"));
        assertFalse(FormNodes.matchesSearchQuery(node, "mesh-owner"));
    }

    @Test
    void wrapsNodeDetailsInVerticalScrollPane() {
        DetailScrollWrap wrap = onFxThread(() -> {
            FormNodes form = new FormNodes();
            NodeDetailContent content = new NodeDetailContent(null, node("Scrollable"), null);
            return new DetailScrollWrap(invokeCreateDetailScrollPane(form, content), content);
        });
        ScrollPane scrollPane = wrap.scrollPane();

        assertTrue(scrollPane.isFitToWidth());
        assertTrue(scrollPane.isPannable());
        assertEquals(ScrollPane.ScrollBarPolicy.NEVER, scrollPane.getHbarPolicy());
        assertEquals(ScrollPane.ScrollBarPolicy.AS_NEEDED, scrollPane.getVbarPolicy());
        assertTrue(scrollPane.getStyleClass().contains("node-detail-scroll-pane"));
        assertTrue(scrollPane.getStyleClass().contains("edge-to-edge"));
        assertEquals(Priority.ALWAYS, VBox.getVgrow(scrollPane));

        assertSame(wrap.content(), scrollPane.getContent());
        assertTrue(wrap.content().minHeightProperty().isBound());
    }

    @Test
    void detachesNodeDetailScrollBindingWhenDetailContentIsCleared() {
        assertTrue(onFxThread(() -> {
            FormNodes form = new FormNodes();
            NodeDetailContent content = new NodeDetailContent(null, node("Scrollable"), null);
            invokeCreateDetailScrollPane(form, content);
            boolean wasBound = content.minHeightProperty().isBound();

            setCurrentDetailContent(form, content);
            invokeDetachCurrentDetailContent(form);

            return wasBound && !content.minHeightProperty().isBound();
        }));
    }

    private static NodeData node(String longName) {
        NodeData node = new NodeData(0x71A67CF5);
        node.setNodeId("!71a67cf5");
        node.setLongName(longName);
        node.setShortName("SCRL");
        node.setUnmessagable(Boolean.FALSE);
        return node;
    }

    private static ScrollPane invokeCreateDetailScrollPane(FormNodes form, NodeDetailContent content) {
        try {
            Method method = FormNodes.class.getDeclaredMethod("createDetailScrollPane", NodeDetailContent.class);
            method.setAccessible(true);
            return (ScrollPane) method.invoke(form, content);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke createDetailScrollPane", e);
        }
    }

    private static void setCurrentDetailContent(FormNodes form, NodeDetailContent content) {
        try {
            Field field = FormNodes.class.getDeclaredField("currentDetailContent");
            field.setAccessible(true);
            field.set(form, content);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to set currentDetailContent", e);
        }
    }

    private static void invokeDetachCurrentDetailContent(FormNodes form) {
        try {
            Method method = FormNodes.class.getDeclaredMethod("detachCurrentDetailContent");
            method.setAccessible(true);
            method.invoke(form);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke detachCurrentDetailContent", e);
        }
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
        T get();
    }
}
