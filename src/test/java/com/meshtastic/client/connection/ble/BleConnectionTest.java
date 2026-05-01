package com.meshtastic.client.connection.ble;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    void sendBytesOffloadsBleWriteFromCallerThread() throws Exception {
        FakePlatform platform = new FakePlatform();
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch allowWriteFinish = new CountDownLatch(1);
        platform.connectAction = p -> p.connected = true;
        platform.writeAction = (p, payload) -> {
            p.lastWriteThread = Thread.currentThread().getName();
            writeStarted.countDown();
            try {
                allowWriteFinish.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        };

        BleConnection connection = new BleConnection("device", platform);
        connection.connect();

        Thread caller = new Thread(() -> connection.sendBytes(frame((byte) 0x2A)), "JavaFX Application Thread");
        caller.start();

        assertTrue(writeStarted.await(1, TimeUnit.SECONDS));
        caller.join(200);
        assertFalse(caller.isAlive());
        assertNotEquals("JavaFX Application Thread", platform.lastWriteThread);

        allowWriteFinish.countDown();
        caller.join(1_000);
    }

    @Test
    void disconnectDropsQueuedBleWrites() throws Exception {
        FakePlatform platform = new FakePlatform();
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch secondWriteStarted = new CountDownLatch(1);
        CountDownLatch allowFirstWriteFinish = new CountDownLatch(1);
        AtomicInteger writeCalls = new AtomicInteger();
        platform.connectAction = p -> p.connected = true;
        platform.writeAction = (p, payload) -> {
            int call = writeCalls.incrementAndGet();
            if (call == 1) {
                firstWriteStarted.countDown();
                try {
                    allowFirstWriteFinish.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }
            secondWriteStarted.countDown();
            return true;
        };

        BleConnection connection = new BleConnection("device", platform);
        connection.connect();

        connection.sendBytes(frame((byte) 0x11));
        connection.sendBytes(frame((byte) 0x22));

        assertTrue(firstWriteStarted.await(1, TimeUnit.SECONDS));

        connection.disconnect();
        allowFirstWriteFinish.countDown();

        assertFalse(secondWriteStarted.await(300, TimeUnit.MILLISECONDS));
        assertEquals(1, writeCalls.get());
    }

    @Test
    void meshCoreBleWritesRawCompanionPacketsWithoutSerialHeader() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.connectAction = p -> p.connected = true;

        BleConnection connection = new BleConnection(
                "device", platform, BleProtocolProfile.MESHCORE_COMPANION);
        connection.connect();

        byte[] companionPacket = new byte[]{0x01, 0x02, 0x03};
        CountDownLatch written = new CountDownLatch(1);
        platform.writeAction = (p, payload) -> {
            p.lastPayload = payload;
            written.countDown();
            return true;
        };

        connection.sendBytes(companionPacket);

        assertTrue(written.await(1, TimeUnit.SECONDS));
        assertEquals(BleProtocolProfile.MESHCORE_COMPANION, platform.profile);
        assertEquals(BleProtocolProfile.MESHCORE_COMPANION, connection.getResolvedProfile());
        assertEquals(3, platform.lastPayload.length);
        assertEquals(0x01, platform.lastPayload[0]);
        assertEquals(0x02, platform.lastPayload[1]);
        assertEquals(0x03, platform.lastPayload[2]);
    }

    @Test
    void autoProfileRetriesMeshCoreAfterMeshtasticGattFails() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.connectAction = p -> {
            if (p.profile == BleProtocolProfile.MESHTASTIC) {
                throw new ConnectionException("missing meshtastic service");
            }
            p.connected = true;
        };

        BleConnection connection = new BleConnection("device", platform, BleProtocolProfile.AUTO);

        connection.connect();

        assertTrue(connection.isConnected());
        assertEquals(BleProtocolProfile.MESHCORE_COMPANION, connection.getResolvedProfile());
        assertEquals(2, platform.connectCalls);
    }

    private static byte[] frame(byte payloadByte) {
        return new byte[]{(byte) 0x94, (byte) 0xC3, 0x00, 0x01, payloadByte};
    }

    private static final class FakePlatform implements BlePlatform {
        private Consumer<byte[]> fromRadioListener;
        private Consumer<BleState> stateListener;
        private Consumer<String> passkeyRequestHandler;
        private boolean connected;
        private ConnectAction connectAction = p -> p.connected = true;
        private WriteAction writeAction = (p, payload) -> p.connected;
        private volatile String lastWriteThread;
        private volatile BleProtocolProfile profile = BleProtocolProfile.MESHTASTIC;
        private volatile byte[] lastPayload;
        private int connectCalls;

        @Override
        public void startScan(Consumer<BleDevice> onDeviceFound) {
        }

        @Override
        public void stopScan() {
        }

        @Override
        public void connect(String address) throws ConnectionException {
            connectCalls++;
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
            return writeAction.run(this, protobufPayload);
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
        public void setProfile(BleProtocolProfile profile) {
            this.profile = profile;
        }

        @Override
        public BleProtocolProfile getProfile() {
            return profile;
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

    @FunctionalInterface
    private interface WriteAction {
        boolean run(FakePlatform platform, byte[] payload);
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
