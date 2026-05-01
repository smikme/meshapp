package com.meshtastic.client.protocol;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.FrameFormat;
import com.meshtastic.client.connection.FrameFormatAwareConnection;
import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionFrames;
import com.meshtastic.client.protocol.meshcore.MeshCoreKissFrames;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolAutodetectorTest {

    @Test
    void detectsMeshCoreKissFromSetHardwareResponse() throws Exception {
        FakeTransportConnection transport = new FakeTransportConnection(FakeMode.MESHCORE);
        ConnectionEntry entry = new ConnectionEntry("serial", "COM3", 115200,
                com.meshtastic.client.model.ConnectionType.SERIAL);

        ProtocolType protocolType = ProtocolAutodetector.detect(new ProtocolRuntimeContext(
                entry.getId(), entry, transport, "type=SERIAL"));

        assertEquals(ProtocolType.MESHCORE_KISS, protocolType);
        assertEquals(FrameFormat.AUTO, transport.getFrameFormat());
    }

    @Test
    void detectsMeshtasticFromFromRadioResponse() throws Exception {
        FakeTransportConnection transport = new FakeTransportConnection(FakeMode.MESHTASTIC);
        ConnectionEntry entry = new ConnectionEntry("tcp", "127.0.0.1", 4403);

        ProtocolType protocolType = ProtocolAutodetector.detect(new ProtocolRuntimeContext(
                entry.getId(), entry, transport, "type=TCP"));

        assertEquals(ProtocolType.MESHTASTIC, protocolType);
    }

    @Test
    void detectsMeshCoreCompanionFromSelfInfoResponse() throws Exception {
        FakeTransportConnection transport = new FakeTransportConnection(FakeMode.MESHCORE_COMPANION);
        ConnectionEntry entry = new ConnectionEntry("tcp", "127.0.0.1", 4403);

        ProtocolType protocolType = ProtocolAutodetector.detect(new ProtocolRuntimeContext(
                entry.getId(), entry, transport, "type=TCP"));

        assertEquals(ProtocolType.MESHCORE_COMPANION, protocolType);
        assertEquals(FrameFormat.AUTO, transport.getFrameFormat());
    }

    private enum FakeMode {
        MESHCORE,
        MESHCORE_COMPANION,
        MESHTASTIC
    }

    private static final class FakeTransportConnection implements TransportConnection, FrameFormatAwareConnection {
        private final FakeMode mode;
        private volatile Consumer<byte[]> dataListener;
        private volatile FrameFormat frameFormat;

        private FakeTransportConnection(FakeMode mode) {
            this.mode = mode;
        }

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
            sendBytes(data, true);
        }

        @Override
        public void sendBytes(byte[] data, boolean expectResponseAfterWrite) {
            Consumer<byte[]> listener = dataListener;
            if (listener == null) {
                return;
            }
            if (mode == FakeMode.MESHCORE && data.length > 0 && data[0] == com.meshtastic.client.connection.KissFrameParser.FEND) {
                byte[] payload = "meshcore-test".getBytes(StandardCharsets.UTF_8);
                byte[] frame = new byte[2 + payload.length];
                frame[0] = (byte) MeshCoreKissFrames.CMD_SET_HARDWARE;
                frame[1] = (byte) MeshCoreKissFrames.RESP_DEVICE_NAME;
                System.arraycopy(payload, 0, frame, 2, payload.length);
                listener.accept(frame);
            } else if (mode == FakeMode.MESHCORE_COMPANION
                    && data.length > 0
                    && (data[0] & 0xFF) == MeshCoreCompanionFrames.CMD_APP_START) {
                listener.accept(selfInfo());
            } else if (mode == FakeMode.MESHTASTIC && data.length >= 4 && data[0] == (byte) 0x94) {
                listener.accept(MeshProtos.FromRadio.newBuilder()
                        .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(0x1234).build())
                        .build()
                        .toByteArray());
            }
        }

        private byte[] selfInfo() {
            byte[] packet = new byte[58];
            packet[0] = (byte) MeshCoreCompanionFrames.PACKET_SELF_INFO;
            packet[1] = 1;
            packet[2] = 10;
            packet[3] = 20;
            for (int i = 0; i < 32; i++) {
                packet[4 + i] = (byte) i;
            }
            return packet;
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            this.dataListener = listener;
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
    }
}
