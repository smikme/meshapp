package com.meshtastic.client.protocol;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolHandlerTest {

    private final List<ProtocolHandler> handlersToShutdown = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (ProtocolHandler handler : handlersToShutdown) {
            handler.shutdown();
        }
    }

    @Test
    void sendToRadioFramesAndWritesToConnection() {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setWantConfigId(12345)
                .build();

        handler.sendToRadio(toRadio);

        assertArrayEquals(PacketFramer.frame(toRadio), connection.lastSentBytes());
    }

    @Test
    void dispatchesIncomingVariantsToRegisteredListener() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        RecordingListener listener = new RecordingListener();
        handler.addListener(listener);

        // Проверяем весь основной fan-out без привязки к UI/service-слою.
        MeshProtos.MyNodeInfo myInfo = MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(0x12345678).build();
        MeshProtos.NodeInfo nodeInfo = MeshProtos.NodeInfo.newBuilder().setNum(0xCAFEBABE).build();
        ConfigProtos.Config config = ConfigProtos.Config.newBuilder()
                .setDevice(ConfigProtos.Config.DeviceConfig.newBuilder().build())
                .build();
        ModuleConfigProtos.ModuleConfig moduleConfig = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setMqtt(ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder().build())
                .build();
        ChannelProtos.Channel channel = ChannelProtos.Channel.newBuilder().setIndex(3).build();
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(1)
                .setTo(2)
                .build();
        MeshProtos.LogRecord logRecord = MeshProtos.LogRecord.newBuilder().setMessage("log").build();
        MeshProtos.QueueStatus queueStatus = MeshProtos.QueueStatus.newBuilder().setMeshPacketId(77).build();

        connection.emit(MeshProtos.FromRadio.newBuilder().setMyInfo(myInfo).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setNodeInfo(nodeInfo).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setConfig(config).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setModuleConfig(moduleConfig).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setChannel(channel).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setConfigCompleteId(99).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setPacket(packet).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setLogRecord(logRecord).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setQueueStatus(queueStatus).build().toByteArray());

        assertTrue(listener.awaitEvents(9));
        assertEquals(0x12345678, listener.myNodeNum.get());
        assertEquals(0xCAFEBABE, listener.nodeNum.get());
        assertEquals(ConfigProtos.Config.PayloadVariantCase.DEVICE, listener.configType);
        assertEquals(ModuleConfigProtos.ModuleConfig.PayloadVariantCase.MQTT, listener.moduleConfigType);
        assertEquals(3, listener.channelIndex.get());
        assertEquals(99, listener.configCompleteId.get());
        assertEquals(1, listener.packetFrom.get());
        assertEquals("log", listener.logMessage);
        assertEquals(77, listener.queuePacketId.get());
    }

    @Test
    void removeListenerStopsFurtherDispatch() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        RecordingListener listener = new RecordingListener();
        handler.addListener(listener);
        handler.removeListener(listener);

        connection.emit(MeshProtos.FromRadio.newBuilder()
                .setConfigCompleteId(42)
                .build().toByteArray());

        assertFalse(listener.awaitEvents(1, 250));
        assertEquals(0, listener.eventsSeen.get());
    }

    @Test
    void startHeartbeatSendsHeartbeatWhenConnectionIsActive() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.connected = true;
        ProtocolHandler handler = track(new ProtocolHandler(connection));

        handler.startHeartbeat();

        assertTrue(connection.awaitSendCountAtLeast(1));
        MeshProtos.ToRadio sent = MeshProtos.ToRadio.parseFrom(unframe(connection.lastSentBytes()));
        assertTrue(sent.hasHeartbeat());
        assertTrue(sent.getHeartbeat().getNonce() >= 1);
    }

    @Test
    void startHeartbeatSkipsSendingWhenConnectionIsInactive() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.connected = false;
        ProtocolHandler handler = track(new ProtocolHandler(connection));

        handler.startHeartbeat();

        Thread.sleep(200);
        assertEquals(0, connection.sendCount.get());
    }

    private ProtocolHandler track(ProtocolHandler handler) {
        handlersToShutdown.add(handler);
        return handler;
    }

    private static byte[] unframe(byte[] frame) {
        // PacketFramer добавляет только 4-байтный заголовок Meshtastic.
        int payloadLength = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        byte[] payload = new byte[payloadLength];
        System.arraycopy(frame, 4, payload, 0, payloadLength);
        return payload;
    }

    private static final class FakeConnection implements MeshtasticConnection {
        // Минимальный in-memory transport для deterministic тестов dispatcher/heartbeat.
        private volatile Consumer<byte[]> dataListener;
        private volatile ConnectionListener connectionListener;
        private volatile byte[] lastSentBytes;
        private volatile boolean connected;
        private final AtomicInteger sendCount = new AtomicInteger();
        private final CountDownLatch firstSendLatch = new CountDownLatch(1);

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
            lastSentBytes = data;
            sendCount.incrementAndGet();
            firstSendLatch.countDown();
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            this.dataListener = listener;
        }

        @Override
        public void setConnectionListener(ConnectionListener listener) {
            this.connectionListener = listener;
        }

        void emit(byte[] payload) {
            Consumer<byte[]> listener = dataListener;
            if (listener != null) {
                listener.accept(payload);
            }
        }

        byte[] lastSentBytes() {
            return lastSentBytes;
        }

        boolean awaitSendCountAtLeast(int minimum) throws InterruptedException {
            if (sendCount.get() >= minimum) {
                return true;
            }
            return firstSendLatch.await(1, TimeUnit.SECONDS) && sendCount.get() >= minimum;
        }
    }

    private static final class RecordingListener implements FromRadioListener {
        // Храним только минимальные срезы данных, чтобы тестировать dispatch, а не protobuf-модели целиком.
        private final AtomicInteger eventsSeen = new AtomicInteger();
        private final AtomicInteger myNodeNum = new AtomicInteger();
        private final AtomicInteger nodeNum = new AtomicInteger();
        private final AtomicInteger channelIndex = new AtomicInteger();
        private final AtomicInteger configCompleteId = new AtomicInteger();
        private final AtomicInteger packetFrom = new AtomicInteger();
        private final AtomicInteger queuePacketId = new AtomicInteger();
        private final CountDownLatch eventLatch = new CountDownLatch(9);
        private volatile ConfigProtos.Config.PayloadVariantCase configType;
        private volatile ModuleConfigProtos.ModuleConfig.PayloadVariantCase moduleConfigType;
        private volatile String logMessage;

        @Override
        public void onMyNodeInfo(MeshProtos.MyNodeInfo myInfo) {
            myNodeNum.set(myInfo.getMyNodeNum());
            seen();
        }

        @Override
        public void onNodeInfo(MeshProtos.NodeInfo nodeInfo) {
            nodeNum.set(nodeInfo.getNum());
            seen();
        }

        @Override
        public void onConfig(ConfigProtos.Config config) {
            configType = config.getPayloadVariantCase();
            seen();
        }

        @Override
        public void onModuleConfig(ModuleConfigProtos.ModuleConfig moduleConfig) {
            moduleConfigType = moduleConfig.getPayloadVariantCase();
            seen();
        }

        @Override
        public void onChannel(ChannelProtos.Channel channel) {
            channelIndex.set(channel.getIndex());
            seen();
        }

        @Override
        public void onConfigComplete(int configCompleteId) {
            this.configCompleteId.set(configCompleteId);
            seen();
        }

        @Override
        public void onMeshPacket(MeshProtos.MeshPacket packet) {
            packetFrom.set(packet.getFrom());
            seen();
        }

        @Override
        public void onLogRecord(MeshProtos.LogRecord logRecord) {
            logMessage = logRecord.getMessage();
            seen();
        }

        @Override
        public void onQueueStatus(MeshProtos.QueueStatus queueStatus) {
            queuePacketId.set(queueStatus.getMeshPacketId());
            seen();
        }

        boolean awaitEvents(int count) throws InterruptedException {
            return awaitEvents(count, 1000);
        }

        boolean awaitEvents(int count, long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                if (eventsSeen.get() >= count) {
                    return true;
                }
                Thread.sleep(10);
            }
            return eventsSeen.get() >= count;
        }

        private void seen() {
            eventsSeen.incrementAndGet();
            eventLatch.countDown();
        }
    }
}
