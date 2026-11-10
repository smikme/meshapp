package com.meshtastic.client.protocol;

import com.google.protobuf.ByteString;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.MessageListenerService;
import com.meshtastic.client.service.PacketMonitorService;
import com.meshtastic.client.utils.AppPreferences;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.meshtastic.proto.Portnums;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class ProtocolHandlerTest {

    @TempDir
    Path tempHome;

    private final List<ProtocolHandler> handlersToShutdown = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.ensureJavaFxStarted();
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void tearDown() {
        for (ProtocolHandler handler : handlersToShutdown) {
            handler.shutdown();
        }
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void trackedWriteReportsActualTransportCompletionAndFailure() throws Exception {
        var entered = new CountDownLatch(1);
        var transportResult = new java.util.concurrent.CompletableFuture<Void>();
        var connection = new FakeConnection() {
            @Override public java.util.concurrent.CompletableFuture<Void> sendBytesAsync(byte[] data, boolean expectResponse) {
                entered.countDown();
                return transportResult;
            }
        };
        var handler = track(new ProtocolHandler(connection));
        var result = handler.sendToRadioAsync(MeshProtos.ToRadio.newBuilder().setWantConfigId(17).build(), false);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertFalse(result.isDone());
        var failure = new java.io.IOException("test failure");
        transportResult.completeExceptionally(failure);
        assertSame(failure, assertThrows(java.util.concurrent.ExecutionException.class,
                () -> result.get(5, TimeUnit.SECONDS)).getCause());
    }

    @Test
    void shutdownCompletesPendingWritesWithoutInterruptingActiveIo() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new java.util.concurrent.CompletableFuture<Boolean>();
        var connection = new FakeConnection() {
            @Override public java.util.concurrent.CompletableFuture<Void> sendBytesAsync(byte[] data, boolean expectResponse) {
                entered.countDown();
                try { release.await(); interrupted.complete(Thread.currentThread().isInterrupted()); }
                catch (InterruptedException error) { interrupted.complete(true); }
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };
        var handler = track(new ProtocolHandler(connection));
        try {
            var message = MeshProtos.ToRadio.newBuilder().setWantConfigId(17).build();
            var active = handler.sendToRadioAsync(message, false);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = handler.sendToRadioAsync(message, false);
            handler.shutdown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> active.get(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> queued.get(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> handler.sendToRadioAsync(message, false).get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertFalse(interrupted.get(5, TimeUnit.SECONDS));
    }

    @Test
    void sendToRadioFramesAndWritesToConnection() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setWantConfigId(12345)
                .build();

        handler.sendToRadio(toRadio);

        assertTrue(connection.awaitSendCountAtLeast(1));
        assertArrayEquals(PacketFramer.frame(toRadio), connection.lastSentBytes());
    }

    @Test
    void packetTrafficIsPrioritizedAheadOfQueuedMqttDownlink() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.blockSends();
        ProtocolHandler handler = track(new ProtocolHandler(connection));

        MeshProtos.ToRadio firstMqtt = MeshProtos.ToRadio.newBuilder()
                .setMqttClientProxyMessage(MeshProtos.MqttClientProxyMessage.newBuilder()
                        .setTopic("msh/test/first")
                        .setText("first")
                        .build())
                .build();
        MeshProtos.ToRadio secondMqtt = MeshProtos.ToRadio.newBuilder()
                .setMqttClientProxyMessage(MeshProtos.MqttClientProxyMessage.newBuilder()
                        .setTopic("msh/test/second")
                        .setText("second")
                        .build())
                .build();
        MeshProtos.ToRadio packet = MeshProtos.ToRadio.newBuilder()
                .setPacket(MeshProtos.MeshPacket.newBuilder()
                        .setId(501)
                        .setFrom(1)
                        .setTo(0xFFFFFFFF)
                        .build())
                .build();

        handler.sendToRadio(firstMqtt, false);
        handler.sendToRadio(secondMqtt, false);
        handler.sendToRadio(packet);

        Thread.sleep(50);
        connection.releaseSends();

        assertTrue(connection.awaitSendCountAtLeast(3));

        List<MeshProtos.ToRadio> sentMessages = connection.sentMessages();
        int packetIndex = indexOfPacket(sentMessages);
        int secondMqttIndex = indexOfSecondMqttMessage(sentMessages);

        assertTrue(packetIndex >= 0);
        assertTrue(secondMqttIndex >= 0);
        assertTrue(packetIndex < secondMqttIndex);
    }

    @Test
    void queueStatusRejectionRetriesPacket() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        int packetId = 7001;

        handler.sendToRadio(outboundTextPacket(packetId));

        assertTrue(connection.awaitSendCountAtLeast(1));
        connection.emit(queueStatus(packetId, 1));

        assertTrue(connection.awaitSendCountAtLeast(2));
        List<MeshProtos.ToRadio> sentMessages = connection.sentMessages();
        assertEquals(packetId, sentMessages.get(0).getPacket().getId());
        assertEquals(packetId, sentMessages.get(1).getPacket().getId());
    }

    @Test
    void queueStatusFullDelaysNextPacket() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));

        handler.sendToRadio(outboundTextPacket(7101));
        assertTrue(connection.awaitSendCountAtLeast(1));
        CountDownLatch queueStatusSeen = new CountDownLatch(1);
        handler.addListener(new FromRadioListener() {
            @Override
            public void onQueueStatus(MeshProtos.QueueStatus queueStatus) {
                queueStatusSeen.countDown();
            }
        });
        connection.emit(queueStatus(7101, 0));
        assertTrue(queueStatusSeen.await(1, TimeUnit.SECONDS));

        handler.sendToRadio(outboundTextPacket(7102));
        Thread.sleep(100);

        assertEquals(1, connection.sendCount.get());
        assertTrue(connection.awaitSendCountAtLeast(2));
        List<MeshProtos.ToRadio> sentMessages = connection.sentMessages();
        assertEquals(7102, sentMessages.get(1).getPacket().getId());
    }

    @Test
    void dispatchesIncomingVariantsToRegisteredListener() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        RecordingListener listener = new RecordingListener();
        handler.addListener(listener);

        // Cover the main dispatch paths without involving UI or service-layer collaborators.
        MeshProtos.MyNodeInfo myInfo = MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(0x12345678).build();
        MeshProtos.NodeInfo nodeInfo = MeshProtos.NodeInfo.newBuilder().setNum(0xCAFEBABE).build();
        ConfigProtos.Config config = ConfigProtos.Config.newBuilder()
                .setDevice(ConfigProtos.Config.DeviceConfig.newBuilder().build())
                .build();
        ModuleConfigProtos.ModuleConfig moduleConfig = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setMqtt(ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder().build())
                .build();
        MeshProtos.DeviceMetadata metadata = MeshProtos.DeviceMetadata.newBuilder()
                .setFirmwareVersion("2.7.0")
                .setExcludedModules(MeshProtos.ExcludedModules.STOREFORWARD_CONFIG_VALUE)
                .build();
        MeshProtos.LoRaRegionPresetMap regionPresetMap =
                MeshProtos.LoRaRegionPresetMap.newBuilder()
                        .addGroups(MeshProtos.LoRaPresetGroup.newBuilder()
                                .addPresets(
                                        ConfigProtos.Config.LoRaConfig.ModemPreset.LONG_FAST)
                                .build())
                        .addRegionGroups(MeshProtos.LoRaRegionPresets.newBuilder()
                                .setRegion(
                                        ConfigProtos.Config.LoRaConfig.RegionCode.US)
                                .setGroupIndex(0)
                                .build())
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
        connection.emit(MeshProtos.FromRadio.newBuilder().setMetadata(metadata).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder()
                .setRegionPresets(regionPresetMap).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setChannel(channel).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setConfigCompleteId(99).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setRebooted(true).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setPacket(packet).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setLogRecord(logRecord).build().toByteArray());
        connection.emit(MeshProtos.FromRadio.newBuilder().setQueueStatus(queueStatus).build().toByteArray());

        assertTrue(listener.awaitEvents(12, 1_000));
        assertEquals(0x12345678, listener.myNodeNum.get());
        assertEquals(0xCAFEBABE, listener.nodeNum.get());
        assertEquals(ConfigProtos.Config.PayloadVariantCase.DEVICE, listener.configType);
        assertEquals(ModuleConfigProtos.ModuleConfig.PayloadVariantCase.MQTT, listener.moduleConfigType);
        assertEquals(MeshProtos.ExcludedModules.STOREFORWARD_CONFIG_VALUE, listener.metadataExcludedModules.get());
        assertEquals(1, listener.regionPresetCount.get());
        assertEquals(3, listener.channelIndex.get());
        assertEquals(99, listener.configCompleteId.get());
        assertTrue(listener.rebooted.get());
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
        assertFalse(connection.lastExpectResponseAfterWrite);
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

    @Test
    void shutdownDoesNotInterruptInFlightListener() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean released = new AtomicBoolean(false);

        handler.addListener(new FromRadioListener() {
            @Override
            public void onNodeInfo(MeshProtos.NodeInfo nodeInfo) {
                entered.countDown();
                try {
                    released.set(release.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    interrupted.compareAndSet(false, Thread.currentThread().isInterrupted());
                    finished.countDown();
                }
            }
        });

        connection.emit(MeshProtos.FromRadio.newBuilder()
                .setNodeInfo(MeshProtos.NodeInfo.newBuilder().setNum(0xCAFEBABE).build())
                .build()
                .toByteArray());

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        handler.shutdown();
        release.countDown();

        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertTrue(released.get(), "listener must be released explicitly, not by its timeout");
        assertFalse(interrupted.get());
    }

    @Test
    void logsIncomingAndOutgoingLoraMonitorPacketsWhenMonitorIsEnabled() throws Exception {
        PacketMonitorService monitorService = PacketMonitorService.getInstance();
        CountDownLatch packetLatch = new CountDownLatch(2);
        monitorService.addListener(new PacketMonitorService.Listener() {
            @Override
            public void onPacketLogged(PacketLogEntry entry) {
                packetLatch.countDown();
            }
        });
        monitorService.startCapture();

        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));

        MeshProtos.MeshPacket outgoing = MeshProtos.MeshPacket.newBuilder()
                .setFrom(1)
                .setTo(0xFFFFFFFF)
                .setId(501)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFromUtf8("out"))
                        .build())
                .build();

        MeshProtos.MeshPacket incoming = MeshProtos.MeshPacket.newBuilder()
                .setFrom(2)
                .setTo(1)
                .setId(502)
                .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ROUTING_APP)
                        .setRequestId(501)
                        .setPayload(MeshProtos.Routing.newBuilder()
                                .setErrorReason(MeshProtos.Routing.Error.NONE)
                                .build()
                                .toByteString())
                        .build())
                .build();

        handler.sendToRadio(MeshProtos.ToRadio.newBuilder().setPacket(outgoing).build());
        connection.emit(MeshProtos.FromRadio.newBuilder().setPacket(incoming).build().toByteArray());

        assertTrue(packetLatch.await(1, TimeUnit.SECONDS));

        List<PacketLogEntry> entries = monitorService.loadAll();
        assertEquals(2, entries.size());
        PacketLogEntry incomingEntry = entries.stream()
                .filter(entry -> entry.getDirection() == PacketLogEntry.Direction.INCOMING)
                .findFirst()
                .orElseThrow();
        PacketLogEntry outgoingEntry = entries.stream()
                .filter(entry -> entry.getDirection() == PacketLogEntry.Direction.OUTGOING)
                .findFirst()
                .orElseThrow();
        assertEquals("ROUTING_APP", incomingEntry.getPacketType());
        assertEquals("TRANSPORT_LORA", incomingEntry.getTransportMechanism());
        assertEquals("TEXT_MESSAGE_APP", outgoingEntry.getPacketType());
        assertEquals("TRANSPORT_LORA", outgoingEntry.getTransportMechanism());
    }

    @Test
    void burstIncomingPacketsReachChatDbAndMonitorWithoutDrops() throws Exception {
        boolean notificationsEnabled = AppPreferences.isNotificationsEnabled();
        AppPreferences.setNotificationsEnabled(false);
        try {
            PacketMonitorService monitorService = PacketMonitorService.getInstance();
            monitorService.startCapture();

            FakeConnection connection = new FakeConnection();
            ProtocolHandler handler = track(new ProtocolHandler(connection));

            DeviceState state = new DeviceState();
            state.setMyNodeNum(0x12345678);
            state.addChannel(ChannelProtos.Channel.newBuilder()
                    .setIndex(0)
                    .setRole(ChannelProtos.Channel.Role.PRIMARY)
                    .build());
            state.addChannel(ChannelProtos.Channel.newBuilder()
                    .setIndex(2)
                    .setRole(ChannelProtos.Channel.Role.SECONDARY)
                    .build());
            state.setChannelCatalogReady(true);
            handler.addListener(new MessageListenerService(state));

            CountDownLatch firstPacketEntered = new CountDownLatch(1);
            CountDownLatch releaseFirstPacket = new CountDownLatch(1);
            AtomicBoolean holdFirstPacket = new AtomicBoolean(true);
            AtomicBoolean firstPacketReleased = new AtomicBoolean(false);
            handler.addListener(new FromRadioListener() {
                @Override
                public void onMeshPacket(MeshProtos.MeshPacket packet) {
                    if (!holdFirstPacket.compareAndSet(true, false)) {
                        return;
                    }
                    firstPacketEntered.countDown();
                    try {
                        firstPacketReleased.set(releaseFirstPacket.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });

            int packetCount = 400;
            connection.emit(wrapPacket(channelTextPacket(0)).toByteArray());
            assertTrue(firstPacketEntered.await(1, TimeUnit.SECONDS));
            for (int i = 1; i < packetCount; i++) {
                connection.emit(wrapPacket(channelTextPacket(i)).toByteArray());
            }

            releaseFirstPacket.countDown();

            assertTrue(waitUntil(() ->
                    MessageDbService.getInstance().loadLast("channel", "2", packetCount + 10, "!12345678").size() == packetCount));
            assertTrue(waitUntil(() -> PacketMonitorService.getInstance().loadAll().size() == packetCount));
            assertTrue(firstPacketReleased.get(), "the first packet must wait for the explicit release");

            List<PacketLogEntry> packets = PacketMonitorService.getInstance().loadAll();
            assertEquals(packetCount, packets.size());
            assertEquals(packetCount,
                    MessageDbService.getInstance().loadLast("channel", "2", packetCount + 10, "!12345678").size());

            state.shutdown();
        } finally {
            AppPreferences.setNotificationsEnabled(notificationsEnabled);
        }
    }

    private ProtocolHandler track(ProtocolHandler handler) {
        handlersToShutdown.add(handler);
        return handler;
    }

    private static MeshProtos.FromRadio wrapPacket(MeshProtos.MeshPacket packet) {
        return MeshProtos.FromRadio.newBuilder().setPacket(packet).build();
    }

    private static MeshProtos.ToRadio outboundTextPacket(int packetId) {
        return MeshProtos.ToRadio.newBuilder()
                .setPacket(MeshProtos.MeshPacket.newBuilder()
                        .setId(packetId)
                        .setFrom(0x12345678)
                        .setTo(0xFFFFFFFF)
                        .setChannel(0)
                        .setWantAck(true)
                        .setDecoded(MeshProtos.Data.newBuilder()
                                .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                                .setPayload(ByteString.copyFromUtf8("queued"))
                                .build())
                        .build())
                .build();
    }

    private static byte[] queueStatus(int packetId, int result) {
        return MeshProtos.FromRadio.newBuilder()
                .setQueueStatus(MeshProtos.QueueStatus.newBuilder()
                        .setMeshPacketId(packetId)
                        .setRes(result)
                        .setFree(0)
                        .setMaxlen(1)
                        .build())
                .build()
                .toByteArray();
    }

    private static MeshProtos.MeshPacket channelTextPacket(int sequence) {
        return MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setTo(0xFFFFFFFF)
                .setChannel(2)
                .setId(9_000 + sequence)
                .setRxTime(1_710_100_000 + sequence)
                .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFromUtf8("burst-" + sequence))
                        .build())
                .build();
    }

    private static byte[] unframe(byte[] frame) {
        // PacketFramer adds only the four-byte Meshtastic header.
        int payloadLength = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        byte[] payload = new byte[payloadLength];
        System.arraycopy(frame, 4, payload, 0, payloadLength);
        return payload;
    }

    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return true;
            }
            // Bound polling of database state; listener completion alone does not cover queued writes.
            //noinspection BusyWait
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static class FakeConnection implements MeshtasticConnection {
        // Minimal in-memory transport for deterministic dispatcher and heartbeat tests.
        private volatile Consumer<byte[]> dataListener;
        private volatile ConnectionListener connectionListener;
        private volatile byte[] lastSentBytes;
        private volatile boolean lastExpectResponseAfterWrite = true;
        private volatile boolean connected;
        private final AtomicInteger sendCount = new AtomicInteger();
        private final Object sendMonitor = new Object();
        private final List<byte[]> sentFrames = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch sendBlockLatch;

        @Override
        public void connect() {
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
            sendBytes(data, true);
        }

        @Override
        public void sendBytes(byte[] data, boolean expectResponseAfterWrite) {
            CountDownLatch gate = sendBlockLatch;
            if (gate != null) {
                try {
                    assertTrue(gate.await(1, TimeUnit.SECONDS), "test transport was not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            lastSentBytes = data;
            lastExpectResponseAfterWrite = expectResponseAfterWrite;
            sentFrames.add(data.clone());
            synchronized (sendMonitor) {
                sendCount.incrementAndGet();
                sendMonitor.notifyAll();
            }
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
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            synchronized (sendMonitor) {
                while (sendCount.get() < minimum) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return false;
                    TimeUnit.NANOSECONDS.timedWait(sendMonitor, remaining);
                }
                return true;
            }
        }

        void blockSends() {
            sendBlockLatch = new CountDownLatch(1);
        }

        void releaseSends() {
            CountDownLatch gate = sendBlockLatch;
            sendBlockLatch = null;
            if (gate != null) {
                gate.countDown();
            }
        }

        List<MeshProtos.ToRadio> sentMessages() {
            List<byte[]> snapshot;
            synchronized (sentFrames) {
                snapshot = new ArrayList<>(sentFrames);
            }
            List<MeshProtos.ToRadio> messages = new ArrayList<>(snapshot.size());
            for (byte[] frame : snapshot) {
                try {
                    messages.add(MeshProtos.ToRadio.parseFrom(unframe(frame)));
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to parse sent frame", e);
                }
            }
            return messages;
        }
    }

    private static int indexOfPacket(List<MeshProtos.ToRadio> sentMessages) {
        for (int i = 0; i < sentMessages.size(); i++) {
            if (sentMessages.get(i).hasPacket()) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfSecondMqttMessage(List<MeshProtos.ToRadio> sentMessages) {
        for (int i = 0; i < sentMessages.size(); i++) {
            MeshProtos.ToRadio toRadio = sentMessages.get(i);
            if (toRadio.hasMqttClientProxyMessage()
                    && "msh/test/second".equals(toRadio.getMqttClientProxyMessage().getTopic())) {
                return i;
            }
        }
        return -1;
    }

    private static final class RecordingListener implements FromRadioListener {
        // Keep only the fields needed to verify dispatch, not full protobuf objects.
        private final AtomicInteger eventsSeen = new AtomicInteger();
        private final AtomicInteger myNodeNum = new AtomicInteger();
        private final AtomicInteger nodeNum = new AtomicInteger();
        private final AtomicInteger channelIndex = new AtomicInteger();
        private final AtomicInteger configCompleteId = new AtomicInteger();
        private final AtomicInteger metadataExcludedModules = new AtomicInteger();
        private final AtomicInteger regionPresetCount = new AtomicInteger();
        private final AtomicInteger packetFrom = new AtomicInteger();
        private final AtomicInteger queuePacketId = new AtomicInteger();
        private final AtomicBoolean rebooted = new AtomicBoolean(false);
        private final Object eventMonitor = new Object();
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
        public void onDeviceMetadata(MeshProtos.DeviceMetadata metadata) {
            metadataExcludedModules.set(metadata.getExcludedModules());
            seen();
        }

        @Override
        public void onRegionPresets(
                MeshProtos.LoRaRegionPresetMap regionPresetMap) {
            regionPresetCount.set(regionPresetMap.getRegionGroupsCount());
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
        public void onRebooted() {
            rebooted.set(true);
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

        boolean awaitEvents(int count, long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            synchronized (eventMonitor) {
                while (eventsSeen.get() < count) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return false;
                    TimeUnit.NANOSECONDS.timedWait(eventMonitor, remaining);
                }
                return true;
            }
        }

        private void seen() {
            synchronized (eventMonitor) {
                eventsSeen.incrementAndGet();
                eventMonitor.notifyAll();
            }
        }
    }
}
