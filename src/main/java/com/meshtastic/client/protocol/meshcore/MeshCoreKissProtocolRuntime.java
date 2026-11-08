package com.meshtastic.client.protocol.meshcore;

import com.meshtastic.client.connection.*;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.*;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** KISS modem: serialized hardware operations and one DATA transmission until TxDone. */
public final class MeshCoreKissProtocolRuntime implements ProtocolRuntime<MeshCoreKissState> {
    private final TransportConnection transport;
    private final MeshCoreKissState state = new MeshCoreKissState();
    private final CompletableFuture<MeshCoreKissState> ready = new CompletableFuture<>();
    private final ScheduledExecutorService scheduler;
    private final MeshCoreCommandQueue commands;
    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "meshcore-kiss-write");
                t.setDaemon(true);
                return t;
            });
    private final CopyOnWriteArrayList<Consumer<byte[]>> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<byte[]> received = new ArrayDeque<>();
    private boolean started, closed;

    MeshCoreKissProtocolRuntime(ProtocolRuntimeContext context) {
        transport = context.transportConnection();
        scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "meshcore-kiss-" + context.connectionId());
                    t.setDaemon(true);
                    return t;
                });
        commands =
                new MeshCoreCommandQueue(
                        scheduler,
                        b ->
                                CompletableFuture.supplyAsync(
                                                () ->
                                                        transport.sendBytesAsync(
                                                                MeshCoreKissFrames.frame(b), true),
                                                writer)
                                        .thenCompose(java.util.function.Function.identity()),
                        e -> {
                            state.setReady(false);
                            state.setLastError(e.getMessage());
                            ready.completeExceptionally(e);
                        },
                        this);
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.MESHCORE_KISS;
    }

    @Override
    public MeshCoreKissState getState() {
        return state;
    }

    @Override
    public String getOwnerId() {
        return state.getOwnerId();
    }

    @Override
    public CompletableFuture<MeshCoreKissState> getReadyFuture() {
        return ready;
    }

    @Override
    public synchronized CompletableFuture<MeshCoreKissState> start() {
        if (started) return ready;
        started = true;
        if (transport instanceof FrameFormatAwareConnection stream)
            stream.setFrameFormat(FrameFormat.KISS);
        transport.setDataListener(this::receive);
        hardware(0x16, new byte[0])
                .whenComplete((v, e) -> {
                    if (e != null) {
                        ready.completeExceptionally(e);
                        return;
                    }
                    state.setReady(true);
                    ready.complete(state);
                    refresh();
                });
        return ready;
    }

    public CompletableFuture<Void> refresh() {
        CompletableFuture<byte[]> chain = CompletableFuture.completedFuture(new byte[0]);
        for (int cmd : new int[] {0x11, 0x01, 0x0b, 0x0c, 0x13, 0x12, 0x17}) {
            final int c = cmd;
            chain = chain.thenCompose(v -> hardware(c, new byte[0]));
        }
        return chain.thenAccept(v -> {});
    }

    /** All official SetHardware requests, including crypto and sensors, are available to API clients. */
    public CompletableFuture<byte[]> hardware(int command, byte[] payload) {
        if (command < 1 || command > 0x1a || payload.length > 510)
            throw new IllegalArgumentException("Invalid KISS hardware command or size");
        byte[] body = new byte[2 + payload.length];
        body[0] = 6;
        body[1] = (byte) command;
        System.arraycopy(payload, 0, body, 2, payload.length);
        int response =
                switch (command) {
                    case 9, 10, 0x18, 0x19 -> 0xf0;
                    default -> command | 0x80;
                };
        return commands.submit(body, response, 5_000, this::applyResponse)
                .thenApply(p -> Arrays.copyOfRange(p, 1, p.length));
    }

    public CompletableFuture<Void> sendData(byte[] data) {
        if (data.length < 1 || data.length > 255)
            throw new IllegalArgumentException("KISS DATA requires 1..255 bytes");
        byte[] body = new byte[data.length + 1];
        System.arraycopy(data, 0, body, 1, data.length);
        return commands.submit(body, 0xf8, 120_000, this::applyResponse)
                .thenAccept(p -> {
                    if (p[1] != 1)
                        throw new CompletionException(
                                new IllegalStateException("Radio transmission failed"));
                });
    }

    public CompletableFuture<Void> setRadio(long frequencyHz, long bandwidthHz, int sf, int cr) {
        if (frequencyHz < 150_000_000L
                || frequencyHz > 2_500_000_000L
                || bandwidthHz < 7000
                || bandwidthHz > 500000
                || sf < 5
                || sf > 12
                || cr < 5
                || cr > 8) throw new IllegalArgumentException("Invalid modem radio parameters");
        byte[] p =
                ByteBuffer.allocate(10)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt((int) frequencyHz)
                        .putInt((int) bandwidthHz)
                        .put((byte) sf)
                        .put((byte) cr)
                        .array();
        return hardware(9, p).thenCompose(v -> hardware(0x0b, new byte[0])).thenAccept(v -> {});
    }

    public CompletableFuture<Void> setPower(int dbm) {
        if (dbm < -9 || dbm > 30) throw new IllegalArgumentException("TX power out of range");
        return hardware(10, new byte[] {(byte) dbm})
                .thenCompose(v -> hardware(0x0c, new byte[0]))
                .thenAccept(v -> {});
    }

    public CompletableFuture<Void> setCsma(int command, int value) {
        if (command < 1 || command > 5 || value < 0 || value > 255)
            throw new IllegalArgumentException("Invalid CSMA setting");
        return commands.submit(new byte[] {(byte) command, (byte) value}, -2, 5000, p -> {})
                .thenAccept(v -> {});
    }

    public void addDataListener(Consumer<byte[]> listener) {
        listeners.add(listener);
    }

    public void removeDataListener(Consumer<byte[]> listener) {
        listeners.remove(listener);
    }

    public synchronized List<byte[]> receivedData() {
        return received.stream().map(byte[]::clone).toList();
    }

    private synchronized void receive(byte[] frame) {
        if (closed || frame == null || frame.length == 0 || (frame[0] & 0xf0) != 0) return;
        int command = frame[0] & 15;
        if (command == 0) {
            if (frame.length < 2 || frame.length > 256) return;
            byte[] data = Arrays.copyOfRange(frame, 1, frame.length);
            received.addLast(data);
            while (received.size() > 100) received.removeFirst();
            for (var listener : listeners) listener.accept(data.clone());
            return;
        }
        if (command != 6 || frame.length < 2) return;
        byte[] response = Arrays.copyOfRange(frame, 1, frame.length);
        int type = response[0] & 255;
        if (type == 0xf9) {
            if (response.length >= 3) state.setLastRxMeta(response[1] / 4.0f, response[2]);
            return;
        }
        if (type == 0xf1) {
            if (response.length < 2) return;
            state.setLastError("KISS error " + (response[1] & 255));
            commands.accept(new byte[] {1, response[1]});
            return;
        }
        commands.accept(response);
    }

    private void applyResponse(byte[] p) {
        int type = p[0] & 255;
        int size =
                switch (type) {
                    case 0x81, 0x87, 0x88 -> 33;
                    case 0x84 -> 65;
                    case 0x8b -> 11;
                    case 0x92 -> 13;
                    case 0x93, 0x94, 0x90, 0x91 -> 3;
                    case 0x8f -> 5;
                    case 0x83, 0x8c, 0x8d, 0x8e, 0x96, 0x9a, 0xf8 -> 2;
                    default -> 1;
                };
        MeshCoreCompanionProtocolRuntime.requireLength(p, size);
        switch (type) {
            case 0x81 -> state.setIdentityHex(HexFormat.of().formatHex(p, 1, 33));
            case 0x91 -> state.setFirmwareVersion(p[1] & 255);
            case 0x96 -> {
                String name = new String(p, 1, p.length - 1, StandardCharsets.UTF_8);
                if (name.isBlank()) throw new IllegalArgumentException("Empty modem name");
                state.setDeviceName(name);
            }
            case 0x8b ->
                    state.setRadioParameters(
                            new MeshCoreKissState.RadioParameters(
                                    MeshCoreCompanionFrames.unsignedIntLe(p, 1),
                                    MeshCoreCompanionFrames.unsignedIntLe(p, 5),
                                    p[9] & 255,
                                    p[10] & 255));
            case 0x8c -> state.setTxPowerDbm((int) p[1]);
            case 0x93 -> state.setBatteryMillivolts(MeshCoreCompanionFrames.unsignedShortLe(p, 1));
            case 0x92 ->
                    state.setStats(
                            new MeshCoreKissState.Stats(
                                    MeshCoreCompanionFrames.unsignedIntLe(p, 1),
                                    MeshCoreCompanionFrames.unsignedIntLe(p, 5),
                                    MeshCoreCompanionFrames.unsignedIntLe(p, 9)));
            case 0xf8 -> state.setLastTxSuccess(p[1] == 1);
            default -> {}
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        transport.setDataListener(null);
        commands.close();
        scheduler.shutdownNow();
        writer.shutdownNow();
        state.setReady(false);
        listeners.clear();
        ready.completeExceptionally(new CancellationException("KISS disconnected"));
    }
}
