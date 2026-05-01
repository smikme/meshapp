package com.meshtastic.client.protocol;

import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocol;
import com.meshtastic.client.protocol.meshcore.MeshCoreKissProtocol;
import com.meshtastic.client.protocol.meshtastic.MeshtasticProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolRegistryTest {

    @Test
    void returnsMeshtasticAdapterRegisteredByDefault() {
        CommunicationProtocol<?> protocol = ProtocolRegistry.get(ProtocolType.MESHTASTIC);

        assertInstanceOf(MeshtasticProtocol.class, protocol);
    }

    @Test
    void returnsMeshCoreKissAdapterRegisteredByDefault() {
        CommunicationProtocol<?> protocol = ProtocolRegistry.get(ProtocolType.MESHCORE_KISS);

        assertInstanceOf(MeshCoreKissProtocol.class, protocol);
    }

    @Test
    void returnsMeshCoreCompanionAdapterRegisteredByDefault() {
        CommunicationProtocol<?> protocol = ProtocolRegistry.get(ProtocolType.MESHCORE_COMPANION);

        assertInstanceOf(MeshCoreCompanionProtocol.class, protocol);
    }

    @Test
    void rejectsUnknownProtocolTypes() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProtocolRegistry.get(null));

        assertTrue(error.getMessage().contains("Неподдерживаемый протокол"));
    }
}
