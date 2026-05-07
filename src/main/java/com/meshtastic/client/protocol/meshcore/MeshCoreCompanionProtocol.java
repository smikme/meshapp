package com.meshtastic.client.protocol.meshcore;

import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.CommunicationProtocol;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;

/**
 * Адаптер MeshCore Companion Protocol для общего protocol registry.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshCoreCompanionProtocol implements CommunicationProtocol<MeshCoreCompanionState> {

    /**
     * Возвращает тип протокола, которым регистрируется этот адаптер.
     *
     * @return {@link ProtocolType#MESHCORE_COMPANION}
     */
    @Override
    public ProtocolType getType() {
        return ProtocolType.MESHCORE_COMPANION;
    }

    /**
     * Создаёт runtime MeshCore Companion поверх уже открытого transport-а.
     *
     * @param context контекст подключения и transport-а
     * @return runtime, выполняющий Companion handshake и сбор metadata
     */
    @Override
    public MeshCoreCompanionProtocolRuntime createRuntime(ProtocolRuntimeContext context) {
        return new MeshCoreCompanionProtocolRuntime(context);
    }
}
