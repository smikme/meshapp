package com.meshtastic.client.connection.ble;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BleConnectionTest {

    @Test
    void connectCallsListenerOnceWhenPlatformReportsConnectedState() throws ConnectionException {
        FakePlatform platform = new FakePlatform();
        platform.connectAction = p -> {
            p.connected = true;
            p.stateListener.accept(new BleState.Connected());
        };

        BleConnection connection = new BleConnection("device", platform);
        TestConnectionListener listener = new TestConnectionListener();
        connection.setConnectionListener(listener);

        connection.connect();

        assertEquals(1, listener.connectedCount);
        assertTrue(connection.isConnected());
    }

    @Test
    void connectCallsListenerOnceWhenPlatformDoesNotReportConnectedState() throws ConnectionException {
        FakePlatform platform = new FakePlatform();
        platform.connectAction = p -> p.connected = true;

        BleConnection connection = new BleConnection("device", platform);
        TestConnectionListener listener = new TestConnectionListener();
        connection.setConnectionListener(listener);

        connection.connect();

        assertEquals(1, listener.connectedCount);
        assertTrue(connection.isConnected());
    }

    @Test
    void connectDoesNotBackfillConnectedAfterErrorState() throws ConnectionException {
        FakePlatform platform = new FakePlatform();
        platform.connectAction = p -> p.stateListener.accept(new BleState.Error("boom", null));

        BleConnection connection = new BleConnection("device", platform);
        TestConnectionListener listener = new TestConnectionListener();
        connection.setConnectionListener(listener);

        connection.connect();

        assertEquals(0, listener.connectedCount);
        assertEquals(1, listener.errorCount);
        assertFalse(connection.isConnected());
    }

    @Test
    void disconnectStillNotifiesWhenPlatformDoesNotEmitState() throws ConnectionException {
        FakePlatform platform = new FakePlatform();
        platform.connectAction = p -> p.connected = true;

        BleConnection connection = new BleConnection("device", platform);
        TestConnectionListener listener = new TestConnectionListener();
        connection.setConnectionListener(listener);

        connection.connect();
        connection.disconnect();

        assertEquals(1, listener.disconnectedCount);
        assertFalse(connection.isConnected());
    }

    @Test
    void connectInstallsPasskeyHandlerBeforePlatformConnect() throws ConnectionException {
        FakePlatform platform = new FakePlatform();
        platform.connectAction = p -> {
            assertNotNull(p.passkeyRequestHandler);
            p.connected = true;
        };

        BleConnection connection = new BleConnection("device", platform);
        connection.connect();
    }

    private static final class FakePlatform implements BlePlatform {
        private Consumer<byte[]> fromRadioListener;
        private Consumer<BleState> stateListener;
        private Consumer<String> passkeyRequestHandler;
        private boolean connected;
        private ConnectAction connectAction = p -> p.connected = true;

        @Override
        public void startScan(Consumer<BleDevice> onDeviceFound) {
        }

        @Override
        public void stopScan() {
        }

        @Override
        public void connect(String address) throws ConnectionException {
            connectAction.run(this);
        }

        @Override
        public void disconnect() {
            connected = false;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public boolean writeToRadio(byte[] protobufPayload) {
            return connected;
        }

        @Override
        public void setFromRadioListener(Consumer<byte[]> listener) {
            this.fromRadioListener = listener;
        }

        @Override
        public void setStateListener(Consumer<BleState> listener) {
            this.stateListener = listener;
        }

        @Override
        public void setPasskeyRequestHandler(Consumer<String> handler) {
            this.passkeyRequestHandler = handler;
        }

        @Override
        public AdapterState getAdapterState() {
            return AdapterState.POWERED_ON;
        }

        @Override
        public void dispose() {
        }
    }

    @FunctionalInterface
    private interface ConnectAction {
        void run(FakePlatform platform) throws ConnectionException;
    }

    private static final class TestConnectionListener implements ConnectionListener {
        private int connectedCount;
        private int disconnectedCount;
        private int errorCount;

        @Override
        public void onConnected() {
            connectedCount++;
        }

        @Override
        public void onDisconnected() {
            disconnectedCount++;
        }

        @Override
        public void onConnectionError(String message, Throwable cause) {
            errorCount++;
        }
    }
}
