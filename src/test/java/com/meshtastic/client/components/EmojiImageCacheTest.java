package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class EmojiImageCacheTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void loadsVariationSequenceEmojiResources() {
        onFxThread(() -> {
            assertAll(
                    () -> assertNotNull(EmojiImageCache.createImageView("❤️‍🔥", 24)),
                    () -> assertNotNull(EmojiImageCache.createImageView("❤️‍🩹", 24)),
                    () -> assertNotNull(EmojiImageCache.createImageView("🐻‍❄️", 24)),
                    () -> assertNotNull(EmojiImageCache.createImageView("🇺🇸", 24)),
                    () -> assertNotNull(EmojiImageCache.createImageView("👩🏽‍💻", 24))
            );
            return null;
        });
    }

    @Test
    void distinguishesTextAndEmojiPresentationForAmbiguousSymbols() {
        assertAll(
                () -> assertFalse(EmojiImageCache.isKnownEmoji("©")),
                () -> assertTrue(EmojiImageCache.isKnownEmoji("©️")),
                () -> assertTrue(EmojiImageCache.isKnownEmoji("⚪"))
        );
    }

    @Test
    void emojiImageViewBaselineKeepsInlineEmojiSlightlyRaised() {
        onFxThread(() -> {
            var imageView = EmojiImageCache.createImageView("😺", 24);

            assertNotNull(imageView);
            assertEquals(19.2, imageView.getBaselineOffset(), 0.01);
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
