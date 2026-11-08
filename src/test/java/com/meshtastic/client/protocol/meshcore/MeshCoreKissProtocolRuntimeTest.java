package com.meshtastic.client.protocol.meshcore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.FrameFormat;
import com.meshtastic.client.connection.FrameFormatAwareConnection;
import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class MeshCoreKissProtocolRuntimeTest {

    @Test
    void startQueriesDeviceAndCompletesReadyOnDeviceName() throws Exception {
        FakeTransportConnection transport = new FakeTransportConnection();
        ConnectionEntry entry =
                new ConnectionEntry("meshcore", "COM3", 115200, ConnectionType.SERIAL);
        MeshCoreKissProtocolRuntime runtime =
                new MeshCoreKissProtocol()
                        .createRuntime(
                                new ProtocolRuntimeContext(
                                        entry.getId(), entry, transport, "type=SERIAL"));

        try {
            assertEquals(ProtocolType.MESHCORE_KISS, runtime.getProtocolType());
            var ready = runtime.start();

            byte[] firstWrite = transport.takeWrite();
            assertArrayEquals(
                    new byte[] {
                        (byte) 0xC0,
                        (byte) MeshCoreKissFrames.CMD_SET_HARDWARE,
                        (byte) MeshCoreKissFrames.REQ_GET_DEVICE_NAME,
                        (byte) 0xC0
                    },
                    firstWrite);
            assertEquals(FrameFormat.KISS, transport.getFrameFormat());

            byte[] name = "meshcore-test".getBytes(StandardCharsets.UTF_8);
            byte[] response = new byte[2 + name.length];
            response[0] = (byte) MeshCoreKissFrames.CMD_SET_HARDWARE;
            response[1] = (byte) MeshCoreKissFrames.RESP_DEVICE_NAME;
            System.arraycopy(name, 0, response, 2, name.length);
            transport.emit(response);

            MeshCoreKissState state = ready.get(1, TimeUnit.SECONDS);
            assertTrue(state.isReady());
            assertEquals("meshcore-test", state.getDeviceName());
        } finally {
            runtime.close();
        }

        assertFalse(transport.hasDataListener());
    }

    @Test
    void unknownOrErrorResponseNeverCompletesHandshake() throws Exception {
        var transport = new FakeTransportConnection();
        var entry = new ConnectionEntry("test", "x", 1);
        var runtime =
                new MeshCoreKissProtocolRuntime(
                        new ProtocolRuntimeContext("kiss", entry, transport, "test"));
        try {
            runtime.start();
            transport.takeWrite();
            transport.emit(new byte[] {6, 0x40});
            assertFalse(runtime.getReadyFuture().isDone());
            transport.emit(new byte[] {6, (byte) 0xf1, 5});
            assertTrue(runtime.getReadyFuture().isCompletedExceptionally());
            assertFalse(runtime.getState().isReady());
        } finally {
            runtime.close();
        }
    }

    @Test
    void rawDataWaitsForTxDoneAndReceivesUnsolicitedFrames() throws Exception {
        var transport = new FakeTransportConnection();
        var entry = new ConnectionEntry("test", "x", 1);
        var runtime =
                new MeshCoreKissProtocolRuntime(
                        new ProtocolRuntimeContext("kiss", entry, transport, "test"));
        try {
            runtime.start();
            transport.takeWrite();
            transport.emit(new byte[] {6, (byte) 0x96, 'M'});
            // Complete the actual sequential metadata exchange with independent wire fixtures.
            for (byte[] response :
                    new byte[][] {
                        {6, (byte) 0x91, 1, 0},
                        new byte[34],
                        {6, (byte) 0x8b, 0, 0, 0, 0, 0, 0, 0, 0, 7, 5},
                        {6, (byte) 0x8c, 10},
                        {6, (byte) 0x93, 0, 16},
                        new byte[14],
                        {6, (byte) 0x97}
                    }) {
                byte[] request = transport.takeWrite();
                if (response.length == 34) {
                    response[0] = 6;
                    response[1] = (byte) 0x81;
                }
                if (response.length == 14) {
                    response[0] = 6;
                    response[1] = (byte) 0x92;
                }
                transport.emit(response);
            }
            var sent = runtime.sendData(new byte[] {(byte) 0xc0, (byte) 0xdb});
            assertArrayEquals(
                    new byte[] {
                        (byte) 0xc0,
                        0,
                        (byte) 0xdb,
                        (byte) 0xdc,
                        (byte) 0xdb,
                        (byte) 0xdd,
                        (byte) 0xc0
                    },
                    transport.takeWrite());
            assertFalse(sent.isDone());
            transport.emit(new byte[] {0, 1, 2, 3});
            transport.emit(new byte[] {6, (byte) 0xf9, 8, (byte) -90});
            assertArrayEquals(new byte[] {1, 2, 3}, runtime.receivedData().getFirst());
            assertEquals(2f, runtime.getState().getLastRxSnrDb());
            transport.emit(new byte[] {6, (byte) 0xf8, 1});
            assertTrue(sent.isDone());
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> runtime.sendData(new byte[256]));
        } finally {
            runtime.close();
        }
    }

    private static final class FakeTransportConnection
            implements TransportConnection, FrameFormatAwareConnection {
        private final BlockingQueue<byte[]> writes = new LinkedBlockingQueue<>();
        private volatile Consumer<byte[]> dataListener;
        private volatile FrameFormat frameFormat;

        @Override
        public void connect() throws ConnectionException {}

        @Override
        public void disconnect() {}

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void sendBytes(byte[] data) {
            writes.offer(data);
        }

        @Override
        public void sendBytes(byte[] data, boolean expectResponseAfterWrite) {
            writes.offer(data);
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            dataListener = listener;
        }

        @Override
        public void setConnectionListener(ConnectionListener listener) {}

        @Override
        public void setFrameFormat(FrameFormat frameFormat) {
            this.frameFormat = frameFormat;
        }

        @Override
        public FrameFormat getFrameFormat() {
            return frameFormat;
        }

        byte[] takeWrite() throws InterruptedException {
            byte[] write = writes.poll(1, TimeUnit.SECONDS);
            assertNotNull(write);
            return write;
        }

        void emit(byte[] frame) {
            Consumer<byte[]> listener = dataListener;
            assertNotNull(listener);
            listener.accept(frame);
        }

        boolean hasDataListener() {
            return dataListener != null;
        }
    }
}
