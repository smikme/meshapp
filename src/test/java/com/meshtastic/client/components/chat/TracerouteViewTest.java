package com.meshtastic.client.components.chat;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.MeshMessage;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class TracerouteViewTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void formatTextIncludesReverseRouteWhenOnlyBackSnrPresent() {
        TracerouteView view = createView();
        MeshProtos.RouteDiscovery route = directRouteWithBackSnr();

        String text = view.formatText("Target", route);

        assertTrue(text.contains("Target \u21925.0dB\u2192 \u042f"));
    }

    @Test
    void buildFromProtoShowsReverseSectionWhenOnlyBackSnrPresent() {
        assertTrue(onFxThread(() -> {
            TracerouteView view = createView();
            MeshMessage msg = new MeshMessage("!00000000", "!00000000", 0, "", 10, false);

            return containsLabelText(
                    view.buildFromProto("Target", directRouteWithBackSnr(), msg),
                    "\u041e\u0431\u0440\u0430\u0442\u043d\u044b\u0439:");
        }));
    }

    private static TracerouteView createView() {
        return new TracerouteView(
                new SimpleDoubleProperty(600),
                nodeNum -> String.format("!%08x", nodeNum),
                (msg, row) -> {});
    }

    private static MeshProtos.RouteDiscovery directRouteWithBackSnr() {
        return MeshProtos.RouteDiscovery.newBuilder()
                .addSnrTowards(28)
                .addSnrBack(20)
                .build();
    }

    private static boolean containsLabelText(Node node, String text) {
        if (node instanceof Label label && text.equals(label.getText())) {
            return true;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (containsLabelText(child, text)) {
                    return true;
                }
            }
        }
        return false;
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
