package com.meshtastic.client.protocol;

import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocol;
import com.meshtastic.client.protocol.meshcore.MeshCoreKissProtocol;
import com.meshtastic.client.protocol.meshtastic.MeshtasticProtocol;

import java.util.EnumMap;
import java.util.Map;

/**
 * Реестр коммуникационных протоколов, доступных приложению.
 * <p>
 * {@code ConnectionManager} использует реестр, чтобы по значению
 * {@link ProtocolType} из профиля подключения получить нужный протокольный
 * адаптер. Для добавления нового протокола нужно зарегистрировать здесь
 * соответствующую реализацию {@link CommunicationProtocol}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ProtocolRegistry {

    private static final Map<ProtocolType, CommunicationProtocol<?>> PROTOCOLS = new EnumMap<>(ProtocolType.class);

    static {
        register(new MeshtasticProtocol());
        register(new MeshCoreKissProtocol());
        register(new MeshCoreCompanionProtocol());
    }

    private ProtocolRegistry() {
    }

    /**
     * Регистрирует или заменяет адаптер протокола.
     *
     * @param protocol адаптер, возвращающий свой {@link ProtocolType}
     */
    public static void register(CommunicationProtocol<?> protocol) {
        PROTOCOLS.put(protocol.getType(), protocol);
    }

    /**
     * Находит адаптер протокола по типу из профиля подключения.
     *
     * @param type тип протокола
     * @return зарегистрированный адаптер
     * @throws IllegalArgumentException если адаптер для типа не зарегистрирован
     */
    public static CommunicationProtocol<?> get(ProtocolType type) {
        CommunicationProtocol<?> protocol = PROTOCOLS.get(type);
        if (protocol == null) {
            throw new IllegalArgumentException("Неподдерживаемый протокол: " + type);
        }
        return protocol;
    }
}
