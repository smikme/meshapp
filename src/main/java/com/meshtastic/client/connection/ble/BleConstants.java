package com.meshtastic.client.connection.ble;

/**
 * BLE protocol constants for Meshtastic and MeshCore Companion.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class BleConstants {

    /** UUID of the primary Meshtastic GATT service. */
    public static final String SERVICE_UUID = "6ba1b218-15a8-461f-9fa8-5dcae273eafd";

    /** Characteristic used to read {@code FromRadio} protobuf data through Read and Notify. */
    public static final String FROM_RADIO_UUID = "2c55e69e-4993-11ed-b878-0242ac120002";

    /** Characteristic used to write {@code ToRadio} protobuf data. */
    public static final String TO_RADIO_UUID = "f75c76d2-129e-4dad-a1dd-7866124401e7";

    /** Counter characteristic that signals available inbound data through Read and Notify. */
    public static final String FROM_NUM_UUID = "ed9da18c-a800-4f66-a670-aa7547e34453";

    /** UUID of the MeshCore Companion GATT service. */
    public static final String MESHCORE_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";

    /** MeshCore RX characteristic: the app writes commands and firmware reads them. */
    public static final String MESHCORE_RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";

    /** MeshCore TX characteristic: firmware sends notifications and the app reads them. */
    public static final String MESHCORE_TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";

    /** Peripheral connection timeout, in milliseconds. */
    public static final int CONNECT_TIMEOUT_MS = 20_000;

    /** GATT service discovery timeout after connect, in milliseconds. */
    public static final int SERVICE_DISCOVERY_TIMEOUT_MS = 20_000;

    /** Length of the serial-frame header [0x94][0xC3][len_msb][len_lsb], unused by BLE. */
    public static final int SERIAL_FRAME_HEADER_SIZE = 4;

    private BleConstants() {}
}
