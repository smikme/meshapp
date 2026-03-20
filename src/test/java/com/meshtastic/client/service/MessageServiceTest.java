package com.meshtastic.client.service;

import com.google.protobuf.ByteString;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageServiceTest {

    private final List<ProtocolHandler> handlersToShutdown = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (ProtocolHandler handler : handlersToShutdown) {
            handler.shutdown();
        }
    }

    @Test
    void requestSessionPasskeyKeepsWantResponseEnabled() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);

        MessageService.requestSessionPasskey(handler, state);

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        assertTrue(sent.getPacket().getDecoded().getWantResponse());
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(sent.getPacket().getDecoded().getPayload());
        assertTrue(admin.hasGetConfigRequest());
    }

    @Test
    void setModuleConfigUsesRoutingAckWithoutAdminResponse() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.setSessionPasskey(ByteString.copyFromUtf8("passkey"));

        ModuleConfigProtos.ModuleConfig mqttConfig = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setMqtt(ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder()
                        .setEnabled(false)
                        .build())
                .build();

        MessageService.setModuleConfig(handler, state, mqttConfig);

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        assertFalse(sent.getPacket().getDecoded().getWantResponse());
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(sent.getPacket().getDecoded().getPayload());
        assertTrue(admin.hasSetModuleConfig());
        assertFalse(admin.getSessionPasskey().isEmpty());
    }

    private ProtocolHandler track(ProtocolHandler handler) {
        handlersToShutdown.add(handler);
        return handler;
    }

    private static MeshProtos.ToRadio parseLastToRadio(RecordingConnection connection) throws Exception {
        byte[] frame = connection.lastSentBytes();
        int payloadLength = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        byte[] payload = new byte[payloadLength];
        System.arraycopy(frame, 4, payload, 0, payloadLength);
        return MeshProtos.ToRadio.parseFrom(payload);
    }

    private static final class RecordingConnection implements MeshtasticConnection {
        private volatile byte[] lastSentBytes;

        @Override
        public void connect() throws ConnectionException {
            // no-op
        }

        @Override
        public void disconnect() {
            // no-op
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void sendBytes(byte[] data) {
            lastSentBytes = data;
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            // no-op
        }

        @Override
        public void setConnectionListener(ConnectionListener listener) {
            // no-op
        }

        byte[] lastSentBytes() {
            return lastSentBytes;
        }
    }
}
