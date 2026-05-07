package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class EmojiPickerTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void searchPromptUsesPlainText() {
        onFxThread(() -> {
            EmojiPicker picker = new EmojiPicker(emoji -> {});
            Field searchFieldRef = EmojiPicker.class.getDeclaredField("searchField");
            searchFieldRef.setAccessible(true);
            TextField searchField = (TextField) searchFieldRef.get(picker);
            assertEquals("Поиск emoji...", searchField.getPromptText());
            return null;
        });
    }

    @Test
    void pickerCellUsesImageForVariationSequenceEmoji() {
        onFxThread(() -> {
            EmojiPicker picker = new EmojiPicker(emoji -> {});
            Method createEmojiCell = EmojiPicker.class.getDeclaredMethod("createEmojiCell", String.class);
            createEmojiCell.setAccessible(true);
            StackPane cell = (StackPane) createEmojiCell.invoke(picker, "❤️‍🔥");

            assertTrue(cell.getChildren().stream().anyMatch(javafx.scene.image.ImageView.class::isInstance));
            return null;
        });
    }

    @Test
    void recentCategoryIconUsesImageAsset() {
        onFxThread(() -> {
            EmojiPicker picker = new EmojiPicker(emoji -> {});
            Field iconRef = EmojiPicker.class.getDeclaredField("RECENT_CATEGORY_ICON");
            iconRef.setAccessible(true);
            String recentIcon = (String) iconRef.get(null);

            Method createCategoryButton = EmojiPicker.class.getDeclaredMethod(
                    "createCategoryButton", String.class, String.class, String.class);
            createCategoryButton.setAccessible(true);
            StackPane button = (StackPane) createCategoryButton.invoke(picker, "recent", recentIcon, "Недавние");

            assertTrue(button.getChildren().stream().anyMatch(javafx.scene.image.ImageView.class::isInstance));
            return null;
        });
    }

    @Test
    void missingEmojiAssetFallsBackToSanitizedText() {
        onFxThread(() -> {
            EmojiPicker picker = new EmojiPicker(emoji -> {});
            Method createEmojiGraphic = EmojiPicker.class.getDeclaredMethod("createEmojiGraphic", String.class, double.class);
            createEmojiGraphic.setAccessible(true);
            Node fallback = (Node) createEmojiGraphic.invoke(picker, "⚠️", 24d);

            assertTrue(fallback instanceof Label);
            assertEquals("⚠", ((Label) fallback).getText());
            return null;
        });
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
