package com.meshtastic.client.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessageSearchServiceTest {
    enum Cancellation { NEW_SEARCH, INVALIDATE, CLOSE }

    @ParameterizedTest
    @EnumSource(Cancellation.class)
    void cancellationLetsActiveDatabaseWorkFinishButSuppressesItsCallback(Cancellation cancellation) throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var completed = new CompletableFuture<Boolean>();
        var staleCallback = new AtomicBoolean();
        try (var service = new ChatMessageSearchService()) {
            long oldGeneration = service.beginWork();
            submit(service, oldGeneration, () -> {
                started.countDown();
                try {
                    release.await();
                    completed.complete(Thread.currentThread().isInterrupted());
                } catch (InterruptedException error) {
                    completed.complete(true);
                }
                return 1;
            }, _ -> staleCallback.set(true), (_, _) -> staleCallback.set(true));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            switch (cancellation) {
                case NEW_SEARCH -> service.beginWork();
                case INVALIDATE -> service.invalidate();
                case CLOSE -> service.close();
            }
            assertFalse(service.isCurrent(oldGeneration));
            release.countDown();
            assertFalse(completed.get(5, TimeUnit.SECONDS), "embedded H2 work must never be interrupted");
            if (cancellation == Cancellation.CLOSE) {
                var field = ChatMessageSearchService.class.getDeclaredField("executor");
                field.setAccessible(true);
                assertTrue(((java.util.concurrent.ExecutorService) field.get(service))
                        .awaitTermination(5, TimeUnit.SECONDS));
            } else {
                var current = new CompletableFuture<Integer>();
                submit(service, service.beginWork(), () -> 2, current::complete,
                        (_, error) -> current.completeExceptionally(error));
                assertEquals(2, current.get(5, TimeUnit.SECONDS));
            }
            assertFalse(staleCallback.get(), "a completed obsolete search must not update the current chat");
        } finally {
            release.countDown();
        }
    }

    private static void submit(ChatMessageSearchService service, long generation, Supplier<Integer> work,
                               Consumer<Integer> success, BiConsumer<Long, Exception> failure) throws Exception {
        var method = ChatMessageSearchService.class.getDeclaredMethod("submit", long.class, Supplier.class,
                Consumer.class, BiConsumer.class);
        method.setAccessible(true);
        method.invoke(service, generation, work, success, failure);
    }
}
