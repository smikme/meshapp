package com.meshtastic.client.service;

import com.meshtastic.client.model.MeshMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One bounded reader per chat view; neither JDBC nor worker callbacks touch UI state. */
public final class ChatHistoryLoader implements AutoCloseable {
    public enum Direction { LATEST, BEFORE, AFTER, AROUND }
    public record Scope(String connectionId, String owner, String type, String key, long generation) {}
    public record Request(Scope scope, Direction direction, long cursor, int limit) {}
    public record Page(List<MeshMessage> messages, boolean first, boolean last, long latestId,
                       long revision, long readNanos, long hydrationNanos) {
        public Page { messages = List.copyOf(messages); }
    }
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(4), task -> {
                Thread thread = new Thread(task, "chat-history-reader");
                thread.setDaemon(true);
                return thread;
            });
    private final Map<Future<?>, CompletableFuture<?>> tasks = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong cancellationGeneration = new java.util.concurrent.atomic.AtomicLong();

    public ChatHistoryLoader() { executor.allowCoreThreadTimeOut(true); }

    public CompletableFuture<Page> load(Request request) {
        MessageDbService service = MessageDbService.getInstance();
        return submit(() -> service.loadHistoryPage(request));
    }

    /** Also used to prepare remote page DTOs without parsing on the JavaFX thread. */
    public <T> CompletableFuture<T> submit(Callable<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        long generation = cancellationGeneration.get();
        final Future<?> task;
        try {
            task = executor.submit(() -> {
                if (generation != cancellationGeneration.get()) { result.cancel(false); return; }
                try { result.complete(work.call()); }
                catch (Exception error) { result.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(error);
            return result;
        }
        tasks.put(task, result);
        result.whenComplete((_, _) -> {
            tasks.remove(task);
            // H2 connections share the embedded MVStore's FileChannel. Thread
            // interruption during disk I/O closes that channel for every service.
            // Discard results and remove queued work, but let active JDBC finish.
            if (result.isCancelled()) { task.cancel(false); executor.purge(); }
        });
        return result;
    }

    public void cancel() {
        cancellationGeneration.incrementAndGet();
        tasks.forEach((task, result) -> { result.cancel(false); task.cancel(false); });
        tasks.clear();
        executor.purge();
    }
    @Override public void close() { cancel(); executor.shutdown(); }
}
