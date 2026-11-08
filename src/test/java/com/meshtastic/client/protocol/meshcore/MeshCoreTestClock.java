package com.meshtastic.client.protocol.meshcore;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.*;

/** Deterministic session clock; no worker threads or sleeping. */
final class MeshCoreTestClock extends AbstractExecutorService implements ScheduledExecutorService {
    private final PriorityQueue<Task<?>> tasks = new PriorityQueue<>();
    private long now, sequence;
    private boolean shutdown;

    void advance(long milliseconds) {
        long target = now + milliseconds;
        while (!tasks.isEmpty() && tasks.peek().due <= target) {
            Task<?> task = tasks.remove();
            now = task.due;
            if (!task.isCancelled()) task.run();
        }
        now = target;
    }

    int pending() {
        return (int) tasks.stream().filter(t -> !t.isCancelled()).count();
    }

    @Override
    public void execute(@NotNull Runnable command) {
        schedule(command, 0, TimeUnit.MILLISECONDS);
    }

    @Override
    public @NotNull ScheduledFuture<?> schedule(
            @NotNull Runnable command, long delay, @NotNull TimeUnit unit) {
        return schedule(Executors.callable(command, null), delay, unit);
    }

    @Override
    public <V> @NotNull ScheduledFuture<V> schedule(
            @NotNull Callable<V> command, long delay, @NotNull TimeUnit unit) {
        if (shutdown) throw new RejectedExecutionException();
        Task<V> task = new Task<>(command, now + unit.toMillis(delay), sequence++);
        tasks.add(task);
        return task;
    }

    @Override
    public @NotNull ScheduledFuture<?> scheduleAtFixedRate(
            @NotNull Runnable r, long initial, long period, @NotNull TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull ScheduledFuture<?> scheduleWithFixedDelay(
            @NotNull Runnable r, long initial, long delay, @NotNull TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public @NotNull List<Runnable> shutdownNow() {
        shutdown = true;
        tasks.forEach(t -> t.cancel(false));
        tasks.clear();
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) {
        return shutdown;
    }

    private final class Task<V> extends FutureTask<V> implements ScheduledFuture<V> {
        final long due, order;

        Task(Callable<V> c, long due, long order) {
            super(c);
            this.due = due;
            this.order = order;
        }

        public long getDelay(@NotNull TimeUnit unit) {
            return unit.convert(due - now, TimeUnit.MILLISECONDS);
        }

        public int compareTo(@NotNull Delayed other) {
            Task<?> t = (Task<?>) other;
            int compared = Long.compare(due, t.due);
            return compared != 0 ? compared : Long.compare(order, t.order);
        }
    }
}
