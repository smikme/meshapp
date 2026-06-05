package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class NodeDetailPanelTest {

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
        onFxThread(() -> {
            Stage stage = installedModalStage();
            if (stage != null) {
                stage.hide();
            }
            ModalPane.install(null);
            return null;
        });
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void closingModalUnbindsTelemetryChartListener() {
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x1234ABCD);
        NodeData node = state.getOrCreateNode(0x71A67CF5);
        node.setNodeId("!71a67cf5");
        node.setLongName("Traceable");
        node.setShortName("TRC");
        node.setUnmessagable(Boolean.FALSE);

        onFxThread(() -> {
            ModalPane pane = new ModalPane();
            ModalPane.install(pane);
            StackPane root = new StackPane(pane);
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 900, 600));
            stage.show();

            NodeDetailPanel.showForNode(state, node);
            return null;
        });

        assertEquals(1, telemetryListenerCount(state));

        onFxThread(() -> {
            ModalPane.getInstance().hide();
            return null;
        });

        awaitCondition(() -> telemetryListenerCount(state) == 0);
    }

    private static Stage installedModalStage() {
        ModalPane pane = ModalPane.getInstance();
        if (pane == null || pane.getScene() == null || !(pane.getScene().getWindow() instanceof Stage stage)) {
            return null;
        }
        return stage;
    }

    private static int telemetryListenerCount(DeviceState state) {
        try {
            Field field = DeviceState.class.getDeclaredField("telemetryListeners");
            field.setAccessible(true);
            Object value = field.get(state);
            return value instanceof Collection<?> collection ? collection.size() : 0;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to inspect telemetry listeners", e);
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

    private static void awaitCondition(BooleanSupplier condition) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for condition", e);
            }
        }
        throw new AssertionError("Timed out waiting for condition");
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
