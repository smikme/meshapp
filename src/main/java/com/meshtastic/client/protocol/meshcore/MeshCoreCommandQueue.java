package com.meshtastic.client.protocol.meshcore;

import static com.meshtastic.client.protocol.meshcore.MeshCoreCompanionFrames.PACKET_ERROR;
import static com.meshtastic.client.protocol.meshcore.MeshCoreCompanionFrames.PACKET_MSG_SENT;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/** Serializes untagged Companion responses. Push events never acknowledge an unrelated request. */
final class MeshCoreCommandQueue implements AutoCloseable {
    static final int CAPACITY = 128;
    private final Object lock;
    private final ScheduledExecutorService scheduler;
    private final Function<byte[], CompletableFuture<Void>> writer;
    private final Consumer<Throwable> fatalError;
    private final ArrayDeque<Request> waiting = new ArrayDeque<>();
    private Request active;
    private boolean closed;

    MeshCoreCommandQueue(
            ScheduledExecutorService scheduler,
            Function<byte[], CompletableFuture<Void>> writer,
            Consumer<Throwable> fatalError,
            Object lock) {
        this.lock = lock;
        this.scheduler = scheduler;
        this.writer = writer;
        this.fatalError = fatalError;
    }

    CompletableFuture<byte[]> submit(
            byte[] command,
            int terminal,
            long timeoutMs,
            Consumer<byte[]> receive,
            int... progress) {
        synchronized (lock) {
            if (closed)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("MeshCore session closed; reconnect"));
            if (waiting.size() >= CAPACITY)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("MeshCore command queue full"));
            Request request = new Request(command.clone(), terminal, timeoutMs, receive, progress);
            waiting.add(request);
            request.result.whenComplete((_, _) -> {
                synchronized (lock) {
                    if (request.result.isCancelled()) {
                        if (active == request)
                            abort(
                                    new java.util.concurrent.CancellationException(
                                            "Active MeshCore request cancelled; reconnect"));
                        else waiting.remove(request);
                    }
                }
            });
            pump();
            return request.result;
        }
    }

    private void pump() {
        if (closed || active != null || waiting.isEmpty()) return;
        Request r = active = waiting.remove();
        arm(r);
        try {
            writer.apply(r.command)
                    .whenComplete((_, error) -> {
                        synchronized (lock) {
                            if (error != null) abort(error);
                            else if (r.terminal == -2 && active == r) finish(r, new byte[0], null);
                        }
                    });
        } catch (RuntimeException e) {
            abort(e);
        }
    }

    boolean accept(byte[] packet) {
        synchronized (lock) {
            if (closed || active == null || packet.length == 0) return false;
            int type = packet[0] & 255;
            Request r = active;
            boolean error =
                    type == PACKET_ERROR || type == 15 || (type == 0x86 && active.terminal == 0x85);
            if (!error && type != r.terminal && Arrays.stream(r.progress).noneMatch(x -> x == type))
                return false;
            if (error) {
                if (type == PACKET_ERROR && packet.length < 2) return false;
                if (type == 0x86) {
                    try {
                        r.receive.accept(packet);
                    } catch (IllegalArgumentException unrelated) {
                        return false;
                    }
                }
                finish(
                        r,
                        null,
                        new MeshCoreException(
                                type == 15 ? -1 : type == 0x86 ? -2 : packet[1] & 255));
                return true;
            }
            try {
                r.receive.accept(packet);
            } catch (IllegalArgumentException invalid) {
                // A malformed response cannot complete a command or make the device ready.
                return false;
            } catch (RuntimeException failure) {
                abort(failure);
                return true;
            }
            if (type == PACKET_MSG_SENT && r.terminal != PACKET_MSG_SENT && packet.length >= 10)
                r.timeoutMs =
                        Math.clamp(
                                MeshCoreCompanionFrames.unsignedIntLe(packet, 6) + 1000,
                                500,
                                300_000);
            if (r.terminal < 0 || type == r.terminal) finish(r, packet.clone(), null);
            else arm(r); // contact sync timeout measures inactivity, not total list size
            return true;
        }
    }

    private void arm(Request r) {
        if (r.timer != null) r.timer.cancel(false);
        r.timer =
                scheduler.schedule(
                        () -> {
                            synchronized (lock) {
                                if (active == r)
                                    abort(
                                            new java.util.concurrent.TimeoutException(
                                                    "MeshCore command 0x%02X timed out; reconnect to resynchronize"
                                                            .formatted(r.command[0] & 255)));
                            }
                        },
                        r.timeoutMs,
                        TimeUnit.MILLISECONDS);
    }

    private void finish(Request r, byte[] packet, Throwable error) {
        if (r.timer != null) r.timer.cancel(false);
        active = null;
        if (error == null) r.result.complete(packet);
        else r.result.completeExceptionally(error);
        pump();
    }

    void abort(Throwable error) {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (active != null) {
                if (active.timer != null) active.timer.cancel(false);
                active.result.completeExceptionally(error);
                active = null;
            }
            while (!waiting.isEmpty()) waiting.remove().result.completeExceptionally(error);
            fatalError.accept(error);
        }
    }

    @Override
    public void close() {
        abort(new java.util.concurrent.CancellationException("MeshCore disconnected"));
    }

    private static final class Request {
        final byte[] command;
        final int terminal;
        long timeoutMs;
        final Consumer<byte[]> receive;
        final int[] progress;
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        ScheduledFuture<?> timer;

        Request(
                byte[] command,
                int terminal,
                long timeoutMs,
                Consumer<byte[]> receive,
                int[] progress) {
            this.command = command;
            this.terminal = terminal;
            this.timeoutMs = timeoutMs;
            this.receive = receive;
            this.progress = progress;
        }
    }

    static final class MeshCoreException extends RuntimeException {
        final int code;

        MeshCoreException(int code) {
            super(
                    switch (code) {
                        case -2 -> "Remote login rejected";
                        case -1 -> "Operation disabled in this firmware build";
                        case 1 -> "Command unsupported by this firmware";
                        case 2 -> "Contact or channel not found";
                        case 3 -> "Device table or queue full";
                        case 4 -> "Invalid device state";
                        case 5 -> "Device storage error";
                        case 6 -> "Invalid command argument";
                        default -> "MeshCore error " + code;
                    });
            this.code = code;
        }
    }
}
