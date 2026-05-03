package com.meshtastic.client.connection;

import com.meshtastic.client.connection.ble.BleConnection;
import com.meshtastic.client.connection.ble.BleDevice;
import com.meshtastic.client.connection.ble.BlePlatform;
import com.meshtastic.client.connection.ble.BlePlatform.AdapterState;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.connection.ble.BleState;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.ProtocolType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class TransportConnectionFactoryTest {

    @Test
    void createsTcpTransportFromTcpProfile() {
        ConnectionEntry entry = new ConnectionEntry("tcp", "192.0.2.10", 4403);

        TransportConnection connection = TransportConnectionFactory.create(entry, FakeBlePlatform::new);

        assertInstanceOf(TcpConnection.class, connection);
        assertEquals(FrameFormat.AUTO, ((FrameFormatAwareConnection) connection).getFrameFormat());
        assertEquals("type=TCP, host=192.0.2.10, port=4403",
                TransportConnectionFactory.describe(entry));
    }

    @Test
    void createsSerialTransportWithDefaultBaudRateWhenProfileBaudRateIsMissing() {
        ConnectionEntry entry = new ConnectionEntry("serial", "/dev/ttyUSB0", 0, ConnectionType.SERIAL);

        TransportConnection connection = TransportConnectionFactory.create(entry, FakeBlePlatform::new);

        assertInstanceOf(SerialConnection.class, connection);
        assertEquals(FrameFormat.AUTO, ((FrameFormatAwareConnection) connection).getFrameFormat());
        assertEquals("type=SERIAL, port=/dev/ttyUSB0, baud=" + SerialConnection.DEFAULT_BAUD_RATE,
                TransportConnectionFactory.describe(entry));
    }

    @Test
    void createsBleTransportOnlyForBleProfiles() {
        AtomicBoolean supplierCalled = new AtomicBoolean(false);
        ConnectionEntry entry = new ConnectionEntry("ble", "AA:BB:CC:DD:EE:FF", "Test BLE");

        TransportConnection connection = TransportConnectionFactory.create(entry, () -> {
            supplierCalled.set(true);
            return new FakeBlePlatform();
        });

        assertInstanceOf(BleConnection.class, connection);
        assertTrue(supplierCalled.get());
        assertEquals("type=BLE, address=AA:BB:CC:DD:EE:FF, deviceName=Test BLE",
                TransportConnectionFactory.describe(entry));
    }

    @Test
    void createsMeshCoreCompanionTransportWithCompanionProfile() {
        ConnectionEntry entry = new ConnectionEntry("meshcore", "AA:BB:CC:DD:EE:FF", "MeshCore");
        entry.setProtocol(ProtocolType.MESHCORE_COMPANION);

        TransportConnection connection = TransportConnectionFactory.create(entry, FakeBlePlatform::new);

        BleConnection bleConnection = assertInstanceOf(BleConnection.class, connection);
        assertEquals(BleProtocolProfile.MESHCORE_COMPANION, bleConnection.getRequestedProfile());
    }

    @Test
    void createsTcpCompanionTransportWithCompanionFrameFormat() {
        ConnectionEntry entry = new ConnectionEntry("meshcore", "127.0.0.1", 4403);
        entry.setProtocol(ProtocolType.MESHCORE_COMPANION);

        TransportConnection connection = TransportConnectionFactory.create(entry, FakeBlePlatform::new);

        assertInstanceOf(TcpConnection.class, connection);
        assertEquals(FrameFormat.MESHCORE_COMPANION,
                ((FrameFormatAwareConnection) connection).getFrameFormat());
    }

    private static final class FakeBlePlatform implements BlePlatform {
        @Override
        public void startScan(Consumer<BleDevice> onDeviceFound) {
        }

        @Override
        public void stopScan() {
        }

        @Override
        public void connect(String address) {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public boolean writeToRadio(byte[] protobufPayload) {
            return false;
        }

        @Override
        public void setFromRadioListener(Consumer<byte[]> listener) {
        }

        @Override
        public void setStateListener(Consumer<BleState> listener) {
        }

        @Override
        public void setPasskeyRequestHandler(Consumer<String> handler) {
        }

        @Override
        public AdapterState getAdapterState() {
            return AdapterState.POWERED_ON;
        }

        @Override
        public void dispose() {
        }
    }
}
