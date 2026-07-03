package com.meshtastic.client.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-side RPC dispatcher.
 * <p>
 * The server receives request envelopes, dispatches only methods present in
 * {@link RpcMethodRegistry}, and writes response envelopes back to the same
 * transport.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RpcServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RpcServer.class);

    private final RpcTransport transport;
    private final RpcDispatcher dispatcher;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RpcServer(RpcTransport transport, RpcMethodRegistry registry) {
        this(transport, registry, ForkJoinPool.commonPool());
    }

    public RpcServer(RpcTransport transport, RpcMethodRegistry registry, Executor executor) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.dispatcher = new RpcDispatcher(
                Objects.requireNonNull(registry, "registry"),
                Objects.requireNonNull(executor, "executor"));
        this.transport.setListener(new ServerTransportListener());
    }

    /**
     * Publishes one host event to the connected remote peer.
     *
     * @param event event name
     * @param payload event payload, or {@code null}
     */
    public void publishEvent(String event, JsonElement payload) {
        if (closed.get()) {
            return;
        }
        send(RpcDispatcher.eventEnvelope(event, payload));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        transport.setListener(null);
        transport.close();
    }

    private void handleMessage(String message) {
        dispatcher.dispatch(message, this::send);
    }

    private void send(JsonObject envelope) {
        if (closed.get() || !transport.isOpen()) {
            return;
        }
        try {
            transport.send(RpcDispatcher.toJson(envelope));
        } catch (RuntimeException e) {
            log.warn("Failed to send RPC server envelope", e);
        }
    }

    private final class ServerTransportListener implements RpcTransportListener {
        @Override
        public void onMessage(String message) {
            if (!closed.get()) {
                handleMessage(message);
            }
        }

        @Override
        public void onClosed() {
            closed.set(true);
        }

        @Override
        public void onError(String message, Throwable cause) {
            log.warn("RPC server transport error: {}", message, cause);
            closed.set(true);
        }
    }
}
