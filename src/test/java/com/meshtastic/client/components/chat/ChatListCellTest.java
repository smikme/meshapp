package com.meshtastic.client.components.chat;

import com.meshtastic.client.TestEnvironmentSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatListCellTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void previewTextIsFittedToTwoVisibleLinesAtNarrowWidths() throws Exception {
        onFxThread(() -> {
            ChatListCell cell = new ChatListCell(item -> {}, item -> {}, item -> {});
            String text = "7924, принято в Meshtastic MeshApp, требуется проверить перенос длинного превью";
            double width = 72;

            String fitted = fitPreviewToVisibleLines(cell, text, width);

            assertNotEquals(text, fitted);
            assertTrue(fitted.endsWith("..."));
            assertTrue(previewFits(cell, fitted, width));
            return null;
        });
    }

    private static String fitPreviewToVisibleLines(ChatListCell cell,
                                                   String text,
                                                   double width) throws Exception {
        Method method = ChatListCell.class.getDeclaredMethod(
                "fitPreviewToVisibleLines", String.class, double.class);
        method.setAccessible(true);
        return (String) method.invoke(cell, text, width);
    }

    private static boolean previewFits(ChatListCell cell, String text, double width) throws Exception {
        Method method = ChatListCell.class.getDeclaredMethod("previewFits", String.class, double.class);
        method.setAccessible(true);
        return (boolean) method.invoke(cell, text, width);
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
