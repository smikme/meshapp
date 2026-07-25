package com.meshtastic.client.forms;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.service.ConnectionManager;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;

class FormConnectionsTest {

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.ensureJavaFxStarted();
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void connectionStateChangeUpdatesExistingCardInPlace() {
        onFxThread(() -> {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry first = new ConnectionEntry("First", "127.0.0.1", 4403);
            ConnectionEntry second = new ConnectionEntry("Second", "127.0.0.1", 4404);
            manager.addEntry(first);
            manager.addEntry(second);

            FormConnections form = new FormConnections();
            invokeRebuildCards(form);
            VBox cardsBox = readCardsBox(form);
            Node firstCard = cardsBox.getChildren().getFirst();
            Node secondCard = cardsBox.getChildren().get(1);

            first.setConnected(true);
            invokeRebuildCards(form);

            assertSame(firstCard, cardsBox.getChildren().getFirst());
            assertSame(secondCard, cardsBox.getChildren().get(1));
            return null;
        });
    }

    private static void invokeRebuildCards(FormConnections form) throws Exception {
        Method method = FormConnections.class.getDeclaredMethod("rebuildCards");
        method.setAccessible(true);
        method.invoke(form);
    }

    private static VBox readCardsBox(FormConnections form) throws Exception {
        Field field = FormConnections.class.getDeclaredField("cardsBox");
        field.setAccessible(true);
        return (VBox) field.get(form);
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
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for JavaFX");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for JavaFX", e);
        }
        if (failure.get() != null) {
            throw new AssertionError("JavaFX operation failed", failure.get());
        }
        return result.get();
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }
}
