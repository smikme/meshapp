package com.meshtastic.client.protocol.meshtastic;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.MeshProtos;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshtasticProtocolRuntimeTest {

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.ensureJavaFxStarted();
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void startRunsConfigExchangeOverGenericTransportConnection() throws Exception {
        FakeTransportConnection transport = new FakeTransportConnection();
        ConnectionEntry entry = new ConnectionEntry("ble", "AA:BB:CC:DD:EE:FF", "Test BLE");
        ProtocolRuntimeContext context = new ProtocolRuntimeContext(
                entry.getId(),
                entry,
                transport,
                "type=BLE, address=AA:BB:CC:DD:EE:FF, deviceName=Test BLE"
        );
        MeshtasticProtocolRuntime runtime = new MeshtasticProtocol().createRuntime(context);

        try {
            assertEquals(ProtocolType.MESHTASTIC, runtime.getProtocolType());
            assertSame(runtime.getState(), runtime.getDeviceState());
            assertNotNull(runtime.getProtocolHandler());
            assertNotNull(runtime.getMessageListenerService());

            var readyFuture = runtime.start();

            byte[] firstWrite = transport.takeWrite();
            MeshProtos.ToRadio wantConfig = MeshProtos.ToRadio.parseFrom(unframe(firstWrite));
            assertTrue(wantConfig.hasWantConfigId());

            transport.emit(MeshProtos.FromRadio.newBuilder()
                    .setMyInfo(MeshProtos.MyNodeInfo.newBuilder()
                            .setMyNodeNum(0x1234ABCD)
                            .build())
                    .build()
                    .toByteArray());
            transport.emit(MeshProtos.FromRadio.newBuilder()
                    .setConfigCompleteId(wantConfig.getWantConfigId())
                    .build()
                    .toByteArray());

            DeviceState state = readyFuture.get(2, TimeUnit.SECONDS);
            assertEquals(0x1234ABCD, state.getMyNodeNum());
            assertEquals("!1234abcd", runtime.getOwnerId());
        } finally {
            runtime.close();
        }

        assertFalse(transport.hasDataListener());
    }

    @Test
    void closeCompletesUnfinishedReadyFutureExceptionallyAndClearsTransportListener() {
        FakeTransportConnection transport = new FakeTransportConnection();
        ConnectionEntry entry = new ConnectionEntry("ble", "AA:BB:CC:DD:EE:FF", "Test BLE");
        MeshtasticProtocolRuntime runtime = new MeshtasticProtocol().createRuntime(new ProtocolRuntimeContext(
                entry.getId(),
                entry,
                transport,
                "type=BLE"
        ));

        var readyFuture = runtime.start();

        runtime.close();

        assertTrue(readyFuture.isCompletedExceptionally());
        assertFalse(transport.hasDataListener());
    }

    private static byte[] unframe(byte[] frame) {
        return Arrays.copyOfRange(frame, 4, frame.length);
    }

    private static final class FakeTransportConnection implements TransportConnection {
        private final BlockingQueue<byte[]> writes = new LinkedBlockingQueue<>();
        private volatile Consumer<byte[]> dataListener;
        private volatile ConnectionListener connectionListener;
        private volatile boolean connected = true;

        @Override
        public void connect() throws ConnectionException {
            connected = true;
            if (connectionListener != null) {
                connectionListener.onConnected();
            }
        }

        @Override
        public void disconnect() {
            connected = false;
            if (connectionListener != null) {
                connectionListener.onDisconnected();
            }
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void sendBytes(byte[] data) {
            writes.offer(data);
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            dataListener = listener;
        }

        @Override
        public void setConnectionListener(ConnectionListener listener) {
            connectionListener = listener;
        }

        byte[] takeWrite() throws InterruptedException {
            byte[] write = writes.poll(1, TimeUnit.SECONDS);
            assertNotNull(write);
            return write;
        }

        void emit(byte[] data) {
            Consumer<byte[]> listener = dataListener;
            assertNotNull(listener);
            listener.accept(data);
        }

        boolean hasDataListener() {
            return dataListener != null;
        }
    }
}
