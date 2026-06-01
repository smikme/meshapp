package com.meshtastic.client.protocol;

import com.meshtastic.client.model.ProtocolType;

/**
 * Factory for a communication protocol runtime.
 * <p>
 * A protocol adapter parses inbound payloads, builds outbound commands, and
 * starts services specific to that protocol. It receives an already opened byte
 * transport from {@code ConnectionManager} and returns a runtime that owns the
 * lifecycle and state.
 *
 * @param <S> state type maintained by the concrete protocol
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface CommunicationProtocol<S> {

    /**
     * Returns the protocol type used to register this adapter in {@link ProtocolRegistry}.
     *
     * @return protocol type from the connection profile
     */
    ProtocolType getType();

    /**
     * Creates a protocol runtime for one transport connection.
     *
     * @param context immutable connection parameters and opened transport
     * @return runtime to be started by the connection manager
     */
    ProtocolRuntime<S> createRuntime(ProtocolRuntimeContext context);
}
