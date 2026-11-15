package com.meshtastic.client.connection.ble;

import com.meshtastic.client.model.ProtocolType;

import java.util.List;

/**
 * BLE GATT profile describing UUIDs and framing rules for a protocol runtime.
 * <p>
 * This is a transport-level BLE profile, not a separate communication protocol:
 * MeshCore Companion remains one protocol type across BLE, TCP, and Serial.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public enum BleProtocolProfile {
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
    /** ESP32 Unified OTA service: raw outbound bytes and direct, line-oriented reply notifications. */
    ESP32_OTA(2, ProtocolType.MESHTASTIC, "ESP32 OTA",
            List.of("4fafc201-1fb5-459e-8fcc-c5c9c331914b"),
            "62ec0272-3ec5-11eb-b378-0242ac130003",
            "62ec0272-3ec5-11eb-b378-0242ac130005", null, false),
    /**
     * Legacy Nordic DFU service. Inbound is the notifying control point, also written via
     * {@link BlePlatform#writeFirmwareControl(byte[])}; outbound is the packet characteristic.
     */
    NORDIC_DFU(3, ProtocolType.MESHTASTIC, "Nordic DFU",
            List.of("00001530-1212-efde-1523-785feabcd123"),
            "00001531-1212-efde-1523-785feabcd123",
            "00001532-1212-efde-1523-785feabcd123", null, false),
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
     * Returns the stable ABI code shared with native BLE backends and Windows helper IPC.
     * This value is independent of enum order and must not be replaced with {@link #ordinal()}.
     *
     * @return stable native profile code: Meshtastic 0, MeshCore 1, ESP32 OTA 2, Nordic DFU 3
     */
    public int nativeCode() {
        return nativeCode;
    }

    /**
     * Returns the communication protocol associated with this BLE profile.
     *
     * @return associated protocol type
     */
    public ProtocolType protocolType() {
        return protocolType;
    }

    /**
     * Returns a human-readable profile name for logs and UI.
     *
     * @return display name
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns the primary service UUID for this profile.
     *
     * @return first service UUID, or {@code null} when the profile has no fixed service
     */
    public String primaryServiceUuid() {
        return serviceUuids.isEmpty() ? null : serviceUuids.getFirst();
    }

    /**
     * Returns the characteristic UUID used for inbound data from the device.
     *
     * @return inbound characteristic UUID, or {@code null}
     */
    public String inboundCharacteristicUuid() {
        return inboundCharacteristicUuid;
    }

    /**
     * Returns the characteristic UUID the application writes to.
     *
     * @return outbound characteristic UUID, or {@code null}
     */
    public String outboundCharacteristicUuid() {
        return outboundCharacteristicUuid;
    }

    /**
     * Returns the characteristic UUID whose write starts the notify/read flow.
     *
     * @return trigger characteristic UUID, or {@code null} for direct notifications
     */
    public String notifyTriggerCharacteristicUuid() {
        return notifyTriggerCharacteristicUuid;
    }

    /**
     * Returns whether this profile needs a separate trigger characteristic.
     *
     * @return {@code true} when notify/read flow requires a trigger write
     */
    public boolean hasNotifyTriggerCharacteristic() {
        return notifyTriggerCharacteristicUuid != null && !notifyTriggerCharacteristicUuid.isBlank();
    }

    /**
     * Returns whether payloads must be wrapped in a Meshtastic serial frame before BLE writes.
     *
     * @return {@code true} for the Meshtastic BLE profile
     */
    public boolean usesSerialFramePayload() {
        return serialFramePayload;
    }

    /**
     * Selects the BLE profile for a connection protocol type.
     *
     * @param protocolType protocol type from the connection profile
     * @return BLE profile to use for the connection
     */
    public static BleProtocolProfile forProtocol(ProtocolType protocolType) {
        if (protocolType == null) {
            return MESHTASTIC;
        }
        return switch (protocolType) {
            case MESHTASTIC -> MESHTASTIC;
            case MESHCORE_KISS -> MESHTASTIC;
            case MESHCORE_COMPANION -> MESHCORE_COMPANION;
            case REMOTE_RPC -> throw new IllegalArgumentException("Remote RPC is not a BLE protocol");
        };
    }
}
