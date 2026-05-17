package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.NodeData;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.TableCell;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class NodeDetailContentTest {

    @TempDir
    Path tempHome;

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void keepsRenderingDetailTableDuringRepeatedUpdates() {
        NodeDetailContent content = onFxThread(() -> new NodeDetailContent(null, node("Node 0", 80, 3.95f), null));

        onFxThread(() -> {
            StackPane root = new StackPane(content);
            Scene scene = new Scene(root, 900, 600);
            assertNotNull(scene);

            root.applyCss();
            root.layout();

            for (int i = 1; i <= 25; i++) {
                content.updateTableData(node("Node " + i, 80 + (i % 10), 3.7f + (i * 0.01f)));
                root.applyCss();
                root.layout();
            }

            return null;
        });

        assertEquals(14, onFxThread(() -> content.lookupAll(".table-row-cell").size()));
    }

    @Test
    void rendersSingleEmojiDetailValueAsCenteredImage() {
        NodeDetailContent content = onFxThread(() -> new NodeDetailContent(null, node("Kitty", "😻", 80, 3.95f), null));

        onFxThread(() -> {
            StackPane root = new StackPane(content);
            Scene scene = new Scene(root, 900, 600);
            EmojiRenderingSupport.install(scene);

            root.applyCss();
            root.layout();

            TableCell<?, ?> emojiCell = null;
            for (Node node : root.lookupAll(".table-cell")) {
                if (node instanceof TableCell<?, ?> cell && "😻".equals(cell.getText())) {
                    emojiCell = cell;
                    break;
                }
            }

            assertNotNull(emojiCell);
            assertEquals(ContentDisplay.GRAPHIC_ONLY, emojiCell.getContentDisplay());
            assertTrue(emojiCell.getGraphic() instanceof ImageView);
            return null;
        });
    }

    private static NodeData node(String longName, int battery, float voltage) {
        return node(longName, "SXT8", battery, voltage);
    }

    private static NodeData node(String longName, String shortName, int battery, float voltage) {
        NodeData node = new NodeData(0x71A67CF5);
        node.setNodeId("!71a67cf5");
        node.setLongName(longName);
        node.setShortName(shortName);
        node.setRole("CLIENT_MUTE");
        node.setHwModel("TRACKER_T1000_E");
        node.setBatteryLevel(battery);
        node.setVoltage(voltage);
        node.setUnmessagable(Boolean.FALSE);
        node.setLastHeard(1_775_588_044);
        return node;
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
