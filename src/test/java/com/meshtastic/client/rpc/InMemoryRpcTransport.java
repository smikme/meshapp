package com.meshtastic.client.rpc;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only paired transport that delivers complete RPC messages in memory.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class InMemoryRpcTransport implements RpcTransport {

    private final Executor executor;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile RpcTransportListener listener;
    private volatile InMemoryRpcTransport peer;

    private InMemoryRpcTransport(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    static Pair createPair(Executor executor) {
        InMemoryRpcTransport first = new InMemoryRpcTransport(executor);
        InMemoryRpcTransport second = new InMemoryRpcTransport(executor);
        first.peer = second;
        second.peer = first;
        return new Pair(first, second);
    }

    @Override
    public void setListener(RpcTransportListener listener) {
        this.listener = listener;
    }

    @Override
    public void send(String message) {
        if (!isOpen()) {
            throw new IllegalStateException("transport is closed");
        }
        InMemoryRpcTransport target = peer;
        if (target == null || !target.isOpen()) {
            throw new IllegalStateException("peer transport is closed");
        }
        executor.execute(() -> {
            RpcTransportListener targetListener = target.listener;
            if (targetListener != null && target.isOpen()) {
                targetListener.onMessage(message);
            }
        });
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        RpcTransportListener currentListener = listener;
        if (currentListener != null) {
            currentListener.onClosed();
        }
    }

    record Pair(RpcTransport first, RpcTransport second) {}
}
