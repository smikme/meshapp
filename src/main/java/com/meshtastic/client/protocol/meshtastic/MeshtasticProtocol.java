package com.meshtastic.client.protocol.meshtastic;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.CommunicationProtocol;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;

/**
 * Адаптер протокола Meshtastic.
 * <p>
 * Отвечает за создание runtime-а Meshtastic поверх уже открытого транспорта.
 * Сам адаптер не хранит состояние подключения: всё runtime-состояние находится
 * в {@link MeshtasticProtocolRuntime}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshtasticProtocol implements CommunicationProtocol<DeviceState> {

    /**
     * @return тип протокола, по которому адаптер доступен в реестре
     */
    @Override
    public ProtocolType getType() {
        return ProtocolType.MESHTASTIC;
    }

    /**
     * Создаёт Meshtastic runtime для конкретного подключения.
     *
     * @param context параметры подключения и transport
     * @return runtime Meshtastic-протокола
     */
    @Override
    public MeshtasticProtocolRuntime createRuntime(ProtocolRuntimeContext context) {
        return new MeshtasticProtocolRuntime(context);
    }

    /**
     * Проверяет, нужен ли Meshtastic heartbeat для выбранного транспорта.
     * <p>
     * Heartbeat является частью протокола Meshtastic, но необходимость его
     * отправки зависит от транспорта: TCP и Serial требуют keepalive-записей,
     * а BLE не требует.
     *
     * @param entry профиль подключения
     * @return {@code true}, если для подключения нужно запускать heartbeat
     */
    public static boolean shouldStartHeartbeat(ConnectionEntry entry) {
        ConnectionType type = entry.getEffectiveType();
        return type == ConnectionType.TCP || type == ConnectionType.SERIAL;
    }
}
