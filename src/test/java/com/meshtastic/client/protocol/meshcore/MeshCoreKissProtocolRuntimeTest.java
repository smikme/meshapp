package com.meshtastic.client.protocol.meshcore;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class MeshCoreKissProtocolRuntimeTest {

    @Test
    void startQueriesDeviceAndCompletesReadyOnDeviceName() throws Exception {
        FakeTransportConnection transport = new FakeTransportConnection();
        ConnectionEntry entry = new ConnectionEntry("meshcore", "COM3", 115200, ConnectionType.SERIAL);
        MeshCoreKissProtocolRuntime runtime = new MeshCoreKissProtocol().createRuntime(new ProtocolRuntimeContext(
                entry.getId(), entry, transport, "type=SERIAL"));

        try {
            assertEquals(ProtocolType.MESHCORE_KISS, runtime.getProtocolType());
            var ready = runtime.start();

            byte[] firstWrite = transport.takeWrite();
            assertArrayEquals(new byte[]{
                    (byte) 0xC0,
                    (byte) MeshCoreKissFrames.CMD_SET_HARDWARE,
                    (byte) MeshCoreKissFrames.REQ_GET_DEVICE_NAME,
                    (byte) 0xC0
            }, firstWrite);
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

    private static final class FakeTransportConnection implements TransportConnection, FrameFormatAwareConnection {
        private final BlockingQueue<byte[]> writes = new LinkedBlockingQueue<>();
        private volatile Consumer<byte[]> dataListener;
        private volatile FrameFormat frameFormat;

        @Override
        public void connect() throws ConnectionException {
        }

        @Override
        public void disconnect() {
        }

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
        public void setConnectionListener(ConnectionListener listener) {
        }

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
