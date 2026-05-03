package com.meshtastic.client.protocol.meshcore;

import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.CommunicationProtocol;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;

/**
 * Адаптер MeshCore KISS modem protocol для общего protocol registry.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshCoreKissProtocol implements CommunicationProtocol<MeshCoreKissState> {

    /**
     * Возвращает тип протокола, которым регистрируется этот адаптер.
     *
     * @return {@link ProtocolType#MESHCORE_KISS}
     */
    @Override
    public ProtocolType getType() {
        return ProtocolType.MESHCORE_KISS;
    }

    /**
     * Создаёт runtime MeshCore KISS поверх уже открытого transport-а.
     *
     * @param context контекст подключения и transport-а
     * @return runtime, выполняющий KISS handshake и сбор metadata
     */
    @Override
    public MeshCoreKissProtocolRuntime createRuntime(ProtocolRuntimeContext context) {
        return new MeshCoreKissProtocolRuntime(context);
    }
}
