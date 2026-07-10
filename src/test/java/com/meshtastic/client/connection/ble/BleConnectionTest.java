package com.meshtastic.client.connection.ble;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.system.AppUi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
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
    void disconnectDisposesOwnedPlatform() throws ConnectionException {
        FakePlatform platform = new FakePlatform();
        platform.connectAction = p -> p.connected = true;

        BleConnection connection = new BleConnection(
                "device", platform, BleProtocolProfile.MESHTASTIC, true);

        connection.connect();
        connection.disconnect();

        assertEquals(1, platform.disposeCalls);
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
    void failedPairingDismissesDialogAndIgnoresStaleActions() {
        FakePlatform platform = new FakePlatform();
        platform.connectAction = p -> {
            p.passkeyRequestHandler.accept("device");
            throw new ConnectionException("pairing timeout");
        };
        RecordingUiBridge ui = new RecordingUiBridge();
        AppUi.install(ui);

        try {
            BleConnection connection = new BleConnection("device", platform);

            assertThrows(ConnectionException.class, connection::connect);
            assertTrue(ui.requestId > 0);
            assertEquals(ui.requestId, ui.dismissedRequestId);

            ui.onSubmit.accept(123456);
            ui.onCancel.run();
            assertEquals(0, platform.respondPasskeyCalls);
            assertEquals(0, platform.cancelPasskeyCalls);
        } finally {
            AppUi.reset();
        }
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
    void responseBleWritesBypassQueuedLowPriorityWrites() throws Exception {
        FakePlatform platform = new FakePlatform();
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch allowFirstWriteFinish = new CountDownLatch(1);
        CountDownLatch writesFinished = new CountDownLatch(3);
        AtomicInteger writeCalls = new AtomicInteger();
        CopyOnWriteArrayList<Byte> payloads = new CopyOnWriteArrayList<>();
        platform.connectAction = p -> p.connected = true;
        platform.writeAction = (p, payload) -> {
            int call = writeCalls.incrementAndGet();
            payloads.add(payload[0]);
            writesFinished.countDown();
            if (call == 1) {
                firstWriteStarted.countDown();
                try {
                    allowFirstWriteFinish.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        };

        BleConnection connection = new BleConnection("device", platform);
        connection.connect();

        connection.sendBytes(frame((byte) 0x11), false);
        assertTrue(firstWriteStarted.await(1, TimeUnit.SECONDS));
        connection.sendBytes(frame((byte) 0x22), false);
        connection.sendBytes(frame((byte) 0x33), true);

        allowFirstWriteFinish.countDown();

        assertTrue(writesFinished.await(1, TimeUnit.SECONDS));
        assertEquals(List.of((byte) 0x11, (byte) 0x33, (byte) 0x22), payloads);
    }

    @Test
    void lowPriorityBleBacklogIsCappedWhileResponseWritesStillPass() throws Exception {
        FakePlatform platform = new FakePlatform();
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch allowFirstWriteFinish = new CountDownLatch(1);
        CountDownLatch writesFinished = new CountDownLatch(10);
        AtomicInteger writeCalls = new AtomicInteger();
        CopyOnWriteArrayList<Byte> payloads = new CopyOnWriteArrayList<>();
        platform.connectAction = p -> p.connected = true;
        platform.writeAction = (p, payload) -> {
            int call = writeCalls.incrementAndGet();
            payloads.add(payload[0]);
            writesFinished.countDown();
            if (call == 1) {
                firstWriteStarted.countDown();
                try {
                    allowFirstWriteFinish.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        };

        BleConnection connection = new BleConnection("device", platform);
        connection.connect();

        connection.sendBytes(frame((byte) 0x11), false);
        assertTrue(firstWriteStarted.await(1, TimeUnit.SECONDS));
        for (int i = 0; i < 24; i++) {
            connection.sendBytes(frame((byte) (0x20 + i)), false);
        }
        connection.sendBytes(frame((byte) 0x7F), true);

        allowFirstWriteFinish.countDown();

        assertTrue(writesFinished.await(1, TimeUnit.SECONDS));
        assertEquals(10, writeCalls.get());
        assertEquals(List.of((byte) 0x11, (byte) 0x7F), payloads.subList(0, 2));
    }

    @Test
    void oversizedLowPriorityBleWriteIsDroppedWhileResponseWriteStillPasses() throws Exception {
        FakePlatform platform = new FakePlatform();
        CountDownLatch written = new CountDownLatch(1);
        AtomicInteger writeCalls = new AtomicInteger();
        platform.connectAction = p -> p.connected = true;
        platform.writeAction = (p, payload) -> {
            writeCalls.incrementAndGet();
            p.lastPayload = payload;
            written.countDown();
            return true;
        };

        BleConnection connection = new BleConnection("device", platform);
        connection.connect();

        connection.sendBytes(frame((byte) 0x44, 257), false);
        connection.sendBytes(frame((byte) 0x55, 257), true);

        assertTrue(written.await(1, TimeUnit.SECONDS));
        assertEquals(1, writeCalls.get());
        assertEquals(257, platform.lastPayload.length);
        assertEquals(0x55, platform.lastPayload[0]);
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

    private static byte[] frame(byte payloadByte) {
        return new byte[]{(byte) 0x94, (byte) 0xC3, 0x00, 0x01, payloadByte};
    }

    private static byte[] frame(byte firstPayloadByte, int payloadSize) {
        byte[] data = new byte[payloadSize + 4];
        data[0] = (byte) 0x94;
        data[1] = (byte) 0xC3;
        data[2] = (byte) ((payloadSize >>> 8) & 0xFF);
        data[3] = (byte) (payloadSize & 0xFF);
        data[4] = firstPayloadByte;
        return data;
    }

    private static final class FakePlatform implements BlePlatform {
        private Consumer<BleState> stateListener;
        private Consumer<String> passkeyRequestHandler;
        private boolean connected;
        private ConnectAction connectAction = p -> p.connected = true;
        private WriteAction writeAction = (p, payload) -> p.connected;
        private volatile String lastWriteThread;
        private volatile BleProtocolProfile profile = BleProtocolProfile.MESHTASTIC;
        private volatile byte[] lastPayload;
        private int disposeCalls;
        private int respondPasskeyCalls;
        private int cancelPasskeyCalls;

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
            return writeAction.run(this, protobufPayload);
        }

        @Override
        public void setFromRadioListener(Consumer<byte[]> listener) {
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
        public void respondPasskey(int passkey) {
            respondPasskeyCalls++;
        }

        @Override
        public void cancelPasskey() {
            cancelPasskeyCalls++;
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
            disposeCalls++;
        }
    }

    private static final class RecordingUiBridge implements AppUi.Bridge {
        private long requestId;
        private long dismissedRequestId;
        private IntConsumer onSubmit;
        private Runnable onCancel;

        @Override
        public void requestBlePasskey(long requestId,
                                      String deviceAddress,
                                      IntConsumer onSubmit,
                                      Runnable onCancel) {
            this.requestId = requestId;
            this.onSubmit = onSubmit;
            this.onCancel = onCancel;
        }

        @Override
        public void dismissBlePasskey(long requestId) {
            dismissedRequestId = requestId;
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
