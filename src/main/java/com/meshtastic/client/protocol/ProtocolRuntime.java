package com.meshtastic.client.protocol;

import com.meshtastic.client.model.ProtocolType;

import java.util.concurrent.CompletableFuture;

/**
 * Runtime instance for one communication protocol over one transport connection.
 * <p>
 * A runtime connects the transport to protocol services: incoming-message
 * listeners, initial handshake or config exchange, device state, and
 * post-connect actions.
 *
 * @param <S> protocol-specific state type
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface ProtocolRuntime<S> extends AutoCloseable {

    /**
     * @return protocol type handled by this runtime
     */
    ProtocolType getProtocolType();

    /**
     * @return current protocol or device state
     */
    S getState();

    /**
     * Returns the runtime readiness future.
     * <p>
     * For Meshtastic this means config exchange completion; other protocols may
     * define readiness as authorization, handshake, or another initial sync.
     *
     * @return future completed when the protocol is ready
     */
    CompletableFuture<S> getReadyFuture();

    /**
     * Starts protocol listeners and the initial handshake or config exchange.
     *
     * @return readiness future with the same meaning as {@link #getReadyFuture()}
     */
    CompletableFuture<S> start();

    /**
     * Returns a stable id for the local or owner device once the protocol knows it.
     *
     * @return owner id, or {@code null} when it is not known yet
     */
    default String getOwnerId() {
        return null;
    }

    /**
     * Called by the connection manager after runtime readiness, while transport is still active.
     */
    default void onReady() {
    }

    /**
     * Releases protocol resources such as listeners, schedulers, pending ACKs,
     * helper services, and state.
     */
    @Override
    void close();
}
