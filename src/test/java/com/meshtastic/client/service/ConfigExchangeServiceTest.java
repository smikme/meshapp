package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.meshtastic.proto.Portnums;
import org.meshtastic.proto.TelemetryProtos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class ConfigExchangeServiceTest {

    @TempDir
    Path tempHome;

    private final List<ProtocolHandler> handlersToShutdown = new ArrayList<>();
    private final List<ConfigExchangeService> servicesToAbort = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.ensureJavaFxStarted();
        TestEnvironmentSupport.resetSingletons();
        MessageDbService.getInstance();
        NodeCacheService.getInstance();
    }

    @AfterEach
    void tearDown() {
        for (ConfigExchangeService service : servicesToAbort) {
            service.abort("test cleanup");
        }
        for (ProtocolHandler handler : handlersToShutdown) {
            handler.shutdown();
        }
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void startConfigExchangeClearsStateAndSendsWantConfig() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(1);
        state.getOrCreateNode(1).setLongName("stale");
        ConfigExchangeService service = track(new ConfigExchangeService(handler, state));

        // startConfigExchange должен сбрасывать runtime-state перед новым потоком конфигурации.
        CompletableFuture<DeviceState> future = service.startConfigExchange();

        assertNotNull(future);
        assertEquals(0, state.getMyNodeNum());
        assertTrue(state.getNodeDb().isEmpty());
        assertNotEquals(0, connection.awaitLastWantConfigId());
    }

    @Test
    void onNodeInfoUsesPendingFixedPositionForOwnNode() {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        state.setPendingFixedPosition(55.7558, 37.6173, 205);
        ConfigExchangeService service = track(new ConfigExchangeService(handler, state));

        // Для своей ноды берём сохранённую пользователем fixed position,
        // а не потенциально устаревшие координаты из устройства.
        MeshProtos.NodeInfo nodeInfo = MeshProtos.NodeInfo.newBuilder()
                .setNum(0x12345678)
                .setUser(MeshProtos.User.newBuilder()
                        .setId("!12345678")
                        .setLongName("Owner")
                        .setShortName("OWN")
                        .build())
                .setPosition(MeshProtos.Position.newBuilder()
                        .setLatitudeI(100)
                        .setLongitudeI(200)
                        .setAltitude(10)
                        .build())
                .build();

        service.onNodeInfo(nodeInfo);

        NodeData node = state.getOrCreateNode(0x12345678);
        assertEquals(55.7558, node.getLatitude());
        assertEquals(37.6173, node.getLongitude());
        assertEquals(205, node.getAltitude());
        assertEquals("Owner", node.getLongName());
    }

    @Test
    void onNodeInfoTreatsExplicitZeroHopsAsKnownDirectNeighbor() {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        ConfigExchangeService service = track(new ConfigExchangeService(handler, state));

        service.onNodeInfo(MeshProtos.NodeInfo.newBuilder()
                .setNum(0x12345678)
                .setHopsAway(0)
                .build());

        NodeData node = state.getOrCreateNode(0x12345678);
        assertTrue(node.hasHopsAway());
        assertTrue(node.isDirectNeighbor());
        assertEquals(0, node.getHopsAway());
    }

    @Test
    void onNodeInfoDefersTelemetryUntilMyNodeInfoIsKnown() {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        ConfigExchangeService service = track(new ConfigExchangeService(handler, state));

        service.onNodeInfo(MeshProtos.NodeInfo.newBuilder()
                .setNum(0xCAFEBABE)
                .setLastHeard(1_700_000_000)
                .setUser(MeshProtos.User.newBuilder()
                        .setId("!cafebabe")
                        .setLongName("Alice")
                        .build())
                .setDeviceMetrics(TelemetryProtos.DeviceMetrics.newBuilder()
                        .setBatteryLevel(88)
                        .setVoltage(4.1f)
                        .setChannelUtilization(12.5f)
                        .setAirUtilTx(3.5f)
                        .build())
                .build());

        assertTrue(state.getNodeDb().isEmpty());
        assertTrue(state.getTelemetryHistory().isEmpty());
        assertEquals(0, NodeCacheService.getInstance().countTelemetryEntries("!12345678"));

        service.onMyNodeInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(0x12345678).build());

        NodeData node = state.getOrCreateNode(0xCAFEBABE);
        assertEquals("Alice", node.getLongName());
        assertEquals(88, node.getBatteryLevel());
        assertEquals(4.1f, node.getVoltage());
        assertEquals(1, state.getTelemetryHistory().size());
        assertEquals(1, NodeCacheService.getInstance().countTelemetryEntries("!12345678"));
    }

    @Test
    void onConfigCompleteClearsStaleCachedDirectHopWhenHopsAreUnknown() throws Exception {
        NodeCacheService nodeCacheService = NodeCacheService.getInstance();
        NodeData stale = new NodeData(0xCAFEBABE);
        stale.setNodeId("!cafebabe");
        stale.setLongName("Stale");
        stale.setHopsAway(0);
        nodeCacheService.update(stale);
        assertTrue(nodeCacheService.get("!cafebabe").isDirectNeighbor());

        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        ConfigExchangeService service = track(new ConfigExchangeService(handler, state));

        CompletableFuture<DeviceState> future = service.startConfigExchange();
        int wantConfigId = connection.awaitLastWantConfigId();

        service.onMyNodeInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(0x12345678).build());
        service.onNodeInfo(MeshProtos.NodeInfo.newBuilder()
                .setNum(0xCAFEBABE)
                .setUser(MeshProtos.User.newBuilder()
                        .setId("!cafebabe")
                        .setLongName("Fresh")
                        .build())
                .build());
        service.onConfigComplete(wantConfigId);

        assertTrue(future.isDone());
        NodeData runtimeNode = state.getOrCreateNode(0xCAFEBABE);
        assertFalse(runtimeNode.hasHopsAway());

        TestEnvironmentSupport.resetSingletons();
        NodeCacheService reloadedCache = NodeCacheService.getInstance();
        NodeData reloaded = reloadedCache.get("!cafebabe");
        assertNotNull(reloaded);
        assertFalse(reloaded.hasHopsAway());
    }

    @Test
    void onConfigCompleteWaitsForMyNodeInfoBeforeCompletingFuture() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        ConfigExchangeService service = track(new ConfigExchangeService(handler, state));

        CompletableFuture<DeviceState> future = service.startConfigExchange();
        int wantConfigId = connection.awaitLastWantConfigId();

        service.onNodeInfo(MeshProtos.NodeInfo.newBuilder()
                .setNum(0xCAFEBABE)
                .setUser(MeshProtos.User.newBuilder()
                        .setId("!cafebabe")
                        .setLongName("Alice")
                        .build())
                .build());
        service.onConfigComplete(wantConfigId);

        assertFalse(future.isDone());
        assertFalse(state.isChannelCatalogReady());
        assertTrue(state.getNodeDb().isEmpty());

        service.onMyNodeInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(0x12345678).build());

        assertTrue(future.isDone());
        assertTrue(state.isChannelCatalogReady());
        assertEquals("Alice", state.getOrCreateNode(0xCAFEBABE).getLongName());
    }

    @Test
    void partialConfigResponseBeforeMyNodeInfoSoftRetriesSameConfigId() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        ConfigExchangeService service = track(new ConfigExchangeService(handler, state));

        CompletableFuture<DeviceState> future = service.startConfigExchange();
        int initialWantConfigId = connection.awaitLastWantConfigId();

        service.onChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(2)
                .setRole(ChannelProtos.Channel.Role.SECONDARY)
                .build());

        connection.awaitWantConfigSendCount(2, 4_500);

        assertEquals(initialWantConfigId, connection.awaitLastWantConfigId());
        assertEquals(List.of(initialWantConfigId, initialWantConfigId), connection.snapshotWantConfigIds());
        assertFalse(future.isDone());

        service.onMyNodeInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(0x12345678).build());
        service.onConfigComplete(initialWantConfigId);

        assertTrue(future.isDone());
        assertTrue(state.isChannelCatalogReady());
        assertEquals(1, state.getChannels().size());
        assertEquals(2, state.getChannels().getFirst().getIndex());
    }

    @Test
    void noResponseTriggersHardRetryWithNewConfigId() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        ConfigExchangeService service = track(new ConfigExchangeService(handler, state));

        service.startConfigExchange();
        int initialWantConfigId = connection.awaitLastWantConfigId();

        connection.awaitWantConfigSendCount(2, 4_500);

        List<Integer> sentWantConfigIds = connection.snapshotWantConfigIds();
        assertEquals(2, sentWantConfigIds.size());
        assertEquals(initialWantConfigId, sentWantConfigIds.getFirst());
        assertNotEquals(initialWantConfigId, sentWantConfigIds.get(1));
        assertEquals(0, state.getMyNodeNum());
        assertFalse(state.isChannelCatalogReady());
    }

    @Test
    void onConfigCompletePersistsNodeFlagsAndCompletesFuture() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        ConfigExchangeService service = track(new ConfigExchangeService(handler, state));

        CompletableFuture<DeviceState> future = service.startConfigExchange();
        int wantConfigId = connection.awaitLastWantConfigId();

        service.onMyNodeInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(0x12345678).build());
        service.onChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(0)
                .setRole(ChannelProtos.Channel.Role.PRIMARY)
                .build());
        service.onConfig(ConfigProtos.Config.newBuilder()
                .setDevice(ConfigProtos.Config.DeviceConfig.newBuilder().build())
                .build());
        service.onModuleConfig(ModuleConfigProtos.ModuleConfig.newBuilder()
                .setMqtt(ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder().build())
                .build());
        service.onNodeInfo(MeshProtos.NodeInfo.newBuilder()
                .setNum(0xCAFEBABE)
                .setIsFavorite(true)
                .setIsIgnored(true)
                .setLastHeard(1_700_000_000)
                .setUser(MeshProtos.User.newBuilder()
                        .setId("!cafebabe")
                        .setLongName("Alice")
                        .setShortName("ALC")
                        .build())
                .setDeviceMetrics(TelemetryProtos.DeviceMetrics.newBuilder()
                        .setBatteryLevel(88)
                        .setVoltage(4.1f)
                        .setChannelUtilization(12.5f)
                        .setAirUtilTx(3.5f)
                        .build())
                .build());

        // Завершение exchange должно материализовать накопленные данные в cache/H2
        // и закрыть future для ConnectionManager/UI.
        service.onConfigComplete(wantConfigId);

        assertTrue(future.isDone());
        assertEquals(state, future.get());
        assertFalse(state.hasPendingFixedPosition());
        assertEquals(1, state.getNodeCount());
        assertEquals(1, state.getChannels().size());
        assertEquals(1, state.getConfigs().size());
        assertEquals(1, state.getModuleConfigs().size());

        NodeCacheService nodeCacheService = NodeCacheService.getInstance();
        NodeData cached = nodeCacheService.get("!cafebabe");
        assertNotNull(cached);
        assertEquals("Alice", cached.getLongName());
        assertTrue(nodeCacheService.isFavorite("!cafebabe"));
        assertTrue(nodeCacheService.isIgnored("!cafebabe"));
        assertEquals(1, state.getTelemetryHistory().size());
    }

    @Test
    void onConfigCompleteAutomaticallySyncsNodeTime() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        ConfigExchangeService service = track(new ConfigExchangeService(handler, state));

        CompletableFuture<DeviceState> future = service.startConfigExchange();
        int wantConfigId = connection.awaitLastWantConfigId();

        service.onMyNodeInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(0x12345678).build());
        long beforeSeconds = System.currentTimeMillis() / 1000L;
        service.onConfigComplete(wantConfigId);
        long afterSeconds = System.currentTimeMillis() / 1000L;

        assertTrue(future.isDone());
        MeshProtos.MeshPacket positionSyncPacket = connection.awaitPacket(Portnums.PortNum.POSITION_APP);
        assertEquals(0, positionSyncPacket.getFrom());
        assertEquals(0x12345678, positionSyncPacket.getTo());
        MeshProtos.Position position =
                MeshProtos.Position.parseFrom(positionSyncPacket.getDecoded().getPayload());
        long sentPositionTime = Integer.toUnsignedLong(position.getTime());
        assertTrue(sentPositionTime >= beforeSeconds && sentPositionTime <= afterSeconds);

        MeshProtos.MeshPacket timeSyncPacket = connection.awaitPacket(Portnums.PortNum.ADMIN_APP);
        assertEquals(0, timeSyncPacket.getFrom());
        assertEquals(0x12345678, timeSyncPacket.getTo());
        assertFalse(timeSyncPacket.getDecoded().getWantResponse());

        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(timeSyncPacket.getDecoded().getPayload());
        assertTrue(admin.hasSetTimeOnly());
        long sentTime = Integer.toUnsignedLong(admin.getSetTimeOnly());
        assertTrue(sentTime >= beforeSeconds && sentTime <= afterSeconds);

        assertTrue(state.completePendingPacketAck(timeSyncPacket.getId(), MeshProtos.Routing.Error.NONE));
    }

    @Test
    void onConfigCompleteIgnoresUnexpectedConfigId() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        ConfigExchangeService service = track(new ConfigExchangeService(handler, state));

        CompletableFuture<DeviceState> future = service.startConfigExchange();
        int expectedId = connection.awaitLastWantConfigId();

        service.onConfigComplete(expectedId + 1);

        assertFalse(future.isDone());
    }

    @Test
    void abortStopsFurtherProcessingAndFailsFuture() throws Exception {
        FakeConnection connection = new FakeConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        ConfigExchangeService service = track(new ConfigExchangeService(handler, state));

        CompletableFuture<DeviceState> future = service.startConfigExchange();
        int wantConfigId = connection.awaitLastWantConfigId();

        service.abort("connection cleanup");

        assertThrows(CancellationException.class, future::get);

        service.onMyNodeInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(0x12345678).build());
        service.onNodeInfo(MeshProtos.NodeInfo.newBuilder()
                .setNum(0xCAFEBABE)
                .setUser(MeshProtos.User.newBuilder()
                        .setId("!cafebabe")
                        .setLongName("Alice")
                        .build())
                .build());
        service.onConfigComplete(wantConfigId);

        assertEquals(0, state.getMyNodeNum());
        assertTrue(state.getNodeDb().isEmpty());
        assertFalse(state.isChannelCatalogReady());
    }

    private ProtocolHandler track(ProtocolHandler handler) {
        handlersToShutdown.add(handler);
        return handler;
    }

    private ConfigExchangeService track(ConfigExchangeService service) {
        servicesToAbort.add(service);
        return service;
    }

    private static final class FakeConnection implements MeshtasticConnection {
        // Нам нужен только факт отправки want_config_id; входящие события вызываем напрямую у сервиса.
        private Consumer<byte[]> dataListener;
        private ConnectionListener connectionListener;
        private volatile Integer lastWantConfigId;
        private final List<Integer> sentWantConfigIds = new ArrayList<>();
        private final List<MeshProtos.MeshPacket> sentPackets = new ArrayList<>();

        @Override
        public void connect() throws ConnectionException {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public void sendBytes(byte[] data) {
            try {
                byte[] payload = new byte[data.length - 4];
                System.arraycopy(data, 4, payload, 0, payload.length);
                MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.parseFrom(payload);
                if (toRadio.hasWantConfigId()) {
                    lastWantConfigId = toRadio.getWantConfigId();
                    synchronized (sentWantConfigIds) {
                        sentWantConfigIds.add(lastWantConfigId);
                    }
                } else if (toRadio.hasPacket()) {
                    synchronized (sentPackets) {
                        sentPackets.add(toRadio.getPacket());
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
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

        int awaitLastWantConfigId() throws InterruptedException {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            Integer current = lastWantConfigId;
            while (current == null && System.nanoTime() < deadlineNanos) {
                Thread.sleep(10);
                current = lastWantConfigId;
            }
            if (current == null) {
                throw new AssertionError("Timed out waiting for want_config_id to be sent");
            }
            return current;
        }

        void awaitWantConfigSendCount(int expectedCount, long timeoutMillis) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (snapshotWantConfigIds().size() < expectedCount && System.nanoTime() < deadlineNanos) {
                Thread.sleep(10);
            }
            if (snapshotWantConfigIds().size() < expectedCount) {
                throw new AssertionError("Timed out waiting for " + expectedCount
                        + " want_config_id sends, got " + snapshotWantConfigIds().size());
            }
        }

        List<Integer> snapshotWantConfigIds() {
            synchronized (sentWantConfigIds) {
                return new ArrayList<>(sentWantConfigIds);
            }
        }

        MeshProtos.MeshPacket awaitPacket(Portnums.PortNum portNum) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            MeshProtos.MeshPacket packet = findPacket(portNum);
            while (packet == null && System.nanoTime() < deadlineNanos) {
                Thread.sleep(10);
                packet = findPacket(portNum);
            }
            if (packet == null) {
                throw new AssertionError("Timed out waiting for packet " + portNum);
            }
            return packet;
        }

        private MeshProtos.MeshPacket findPacket(Portnums.PortNum portNum) {
            synchronized (sentPackets) {
                return sentPackets.stream()
                        .filter(packet -> packet.hasDecoded() && packet.getDecoded().getPortnum() == portNum)
                        .reduce((first, second) -> second)
                        .orElse(null);
            }
        }
    }
}
