package com.meshtastic.client.connection.ble;

import com.meshtastic.client.model.ProtocolType;

import java.util.List;

/**
 * BLE GATT profile, который описывает UUID и framing rules для protocol runtime-а.
 * <p>
 * Это transport-level профиль BLE-подключения, а не отдельный communication protocol:
 * MeshCore Companion остаётся одним protocol type для BLE, TCP и Serial.
 */
public enum BleProtocolProfile {
    AUTO(
            -1,
            ProtocolType.AUTO,
            "Auto",
            List.of(
                    BleConstants.SERVICE_UUID,
                    BleConstants.MESHCORE_SERVICE_UUID
            ),
            null,
            null,
            null,
            false
    ),
    MESHTASTIC(
            0,
            ProtocolType.MESHTASTIC,
            "Meshtastic",
            List.of(BleConstants.SERVICE_UUID),
            BleConstants.FROM_RADIO_UUID,
            BleConstants.TO_RADIO_UUID,
            BleConstants.FROM_NUM_UUID,
            true
    ),
    MESHCORE_COMPANION(
            1,
            ProtocolType.MESHCORE_COMPANION,
            "MeshCore Companion",
            List.of(BleConstants.MESHCORE_SERVICE_UUID),
            BleConstants.MESHCORE_TX_UUID,
            BleConstants.MESHCORE_RX_UUID,
            null,
            false
    );

    private final int nativeCode;
    private final ProtocolType protocolType;
    private final String displayName;
    private final List<String> serviceUuids;
    private final String inboundCharacteristicUuid;
    private final String outboundCharacteristicUuid;
    private final String notifyTriggerCharacteristicUuid;
    private final boolean serialFramePayload;

    BleProtocolProfile(int nativeCode,
                       ProtocolType protocolType,
                       String displayName,
                       List<String> serviceUuids,
                       String inboundCharacteristicUuid,
                       String outboundCharacteristicUuid,
                       String notifyTriggerCharacteristicUuid,
                       boolean serialFramePayload) {
        this.nativeCode = nativeCode;
        this.protocolType = protocolType;
        this.displayName = displayName;
        this.serviceUuids = serviceUuids;
        this.inboundCharacteristicUuid = inboundCharacteristicUuid;
        this.outboundCharacteristicUuid = outboundCharacteristicUuid;
        this.notifyTriggerCharacteristicUuid = notifyTriggerCharacteristicUuid;
        this.serialFramePayload = serialFramePayload;
    }

    /**
     * Возвращает код profile-а для native BLE backend-ов.
     *
     * @return {@code -1} для AUTO, {@code 0} для Meshtastic, {@code 1} для MeshCore Companion
     */
    public int nativeCode() {
        return nativeCode;
    }

    /**
     * Возвращает communication protocol, связанный с BLE profile-ом.
     *
     * @return protocol type или {@link ProtocolType#AUTO}
     */
    public ProtocolType protocolType() {
        return protocolType;
    }

    /**
     * Возвращает человекочитаемое имя profile-а для логов и UI.
     *
     * @return display name
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Возвращает список BLE service UUID, по которым выполняется scan/connect.
     *
     * @return immutable список UUID строк
     */
    public List<String> serviceUuids() {
        return serviceUuids;
    }

    /**
     * Возвращает основной service UUID profile-а.
     *
     * @return первый service UUID или {@code null}, если profile не задаёт конкретный service
     */
    public String primaryServiceUuid() {
        return serviceUuids.isEmpty() ? null : serviceUuids.get(0);
    }

    /**
     * Возвращает UUID characteristic, из которой приходят данные от устройства.
     *
     * @return inbound characteristic UUID или {@code null}
     */
    public String inboundCharacteristicUuid() {
        return inboundCharacteristicUuid;
    }

    /**
     * Возвращает UUID characteristic, в которую приложение пишет данные.
     *
     * @return outbound characteristic UUID или {@code null}
     */
    public String outboundCharacteristicUuid() {
        return outboundCharacteristicUuid;
    }

    /**
     * Возвращает UUID characteristic, запись в которую инициирует notify/read flow.
     *
     * @return UUID trigger characteristic или {@code null}, если profile использует direct notifications
     */
    public String notifyTriggerCharacteristicUuid() {
        return notifyTriggerCharacteristicUuid;
    }

    /**
     * Проверяет, нужен ли profile-у отдельный trigger characteristic.
     *
     * @return {@code true}, если notify/read flow требует отдельной записи-trigger-а
     */
    public boolean hasNotifyTriggerCharacteristic() {
        return notifyTriggerCharacteristicUuid != null && !notifyTriggerCharacteristicUuid.isBlank();
    }

    /**
     * Проверяет, нужно ли оборачивать payload в Meshtastic serial frame перед BLE write.
     *
     * @return {@code true} для Meshtastic BLE profile-а
     */
    public boolean usesSerialFramePayload() {
        return serialFramePayload;
    }

    /**
     * Проверяет, приходят ли входящие данные напрямую через notifications inbound characteristic.
     *
     * @return {@code true}, если отдельный notify trigger не используется
     */
    public boolean usesDirectInboundNotifications() {
        return !hasNotifyTriggerCharacteristic();
    }

    /**
     * Подбирает BLE profile для выбранного communication protocol.
     *
     * @param protocolType protocol type из профиля подключения
     * @return BLE profile, который нужно использовать при connect-е
     */
    public static BleProtocolProfile forProtocol(ProtocolType protocolType) {
        if (protocolType == null || protocolType == ProtocolType.AUTO) {
            return AUTO;
        }
        return switch (protocolType) {
            case AUTO -> AUTO;
            case MESHTASTIC -> MESHTASTIC;
            case MESHCORE_KISS -> MESHTASTIC;
            case MESHCORE_COMPANION -> MESHCORE_COMPANION;
        };
    }
}
