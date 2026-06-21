package com.meshtastic.client.protocol;

import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocol;
import com.meshtastic.client.protocol.meshcore.MeshCoreKissProtocol;
import com.meshtastic.client.protocol.meshtastic.MeshtasticProtocol;
import com.meshtastic.client.protocol.rpc.RemoteRpcProtocol;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry of communication protocols available to the application.
 * <p>
 * {@code ConnectionManager} uses the registry to resolve a protocol adapter
 * from the {@link ProtocolType} stored in the connection profile. Adding a new
 * protocol means registering its {@link CommunicationProtocol} implementation here.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ProtocolRegistry {

    private static final Map<ProtocolType, CommunicationProtocol<?>> PROTOCOLS = new EnumMap<>(ProtocolType.class);

    static {
        register(new MeshtasticProtocol());
        register(new MeshCoreKissProtocol());
        register(new MeshCoreCompanionProtocol());
        register(new RemoteRpcProtocol());
    }

    private ProtocolRegistry() {
    }

    /**
     * Registers or replaces a protocol adapter.
     *
     * @param protocol adapter that exposes its {@link ProtocolType}
     */
    public static void register(CommunicationProtocol<?> protocol) {
        PROTOCOLS.put(protocol.getType(), protocol);
    }

    /**
     * Finds a protocol adapter by connection profile type.
     *
     * @param type protocol type
     * @return registered adapter
     * @throws IllegalArgumentException when no adapter is registered for the type
     */
    public static CommunicationProtocol<?> get(ProtocolType type) {
        CommunicationProtocol<?> protocol = PROTOCOLS.get(type);
        if (protocol == null) {
            throw new IllegalArgumentException("Неподдерживаемый протокол: " + type);
        }
        return protocol;
    }
}
