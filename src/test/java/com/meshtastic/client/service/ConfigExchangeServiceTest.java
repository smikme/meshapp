package com.meshtastic.client.service;

import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import org.meshtastic.proto.*;
import org.junit.jupiter.api.*;

import javafx.application.Platform;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ConfigExchangeServiceTest {

    private static String originalHome;
    private static Path tempDir;

    private StubMeshtasticConnection connection;
    private ProtocolHandler protocolHandler;
    private DeviceState deviceState;
    private ConfigExchangeService service;

    // ═══════════════════════════════════════════════════════════
    //  Изоляция БД: temp home + singleton reset
    // ═══════════════════════════════════════════════════════════

    @BeforeAll
    static void setUpTempHome() throws Exception {
        try { Platform.startup(() -> {}); } catch (IllegalStateException ignored) {}
        originalHome = System.getProperty("user.home");
        tempDir = Files.createTempDirectory("meshapp-test-");
        System.setProperty("user.home", tempDir.toString());
        resetSingleton(NodeCacheService.class);
        resetSingleton(MessageDbService.class);
    }

    @AfterAll
    static void restoreHome() throws Exception {
        NodeCacheService.closeIfInitialized();
        MessageDbService.closeIfInitialized();
        resetSingleton(NodeCacheService.class);
        resetSingleton(MessageDbService.class);
        System.setProperty("user.home", originalHome);

        // Удаляем temp-директорию
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
    }

    private static void resetSingleton(Class<?> clazz) throws Exception {
        Field instanceField = clazz.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    // ═══════════════════════════════════════════════════════════
    //  Setup
    // ═══════════════════════════════════════════════════════════

    @BeforeEach
    void setUp() {
        connection = new StubMeshtasticConnection();
        protocolHandler = new ProtocolHandler(connection);
        deviceState = new DeviceState();
        service = new ConfigExchangeService(protocolHandler, deviceState);
    }

    // ═══════════════════════════════════════════════════════════
    //  Хелперы
    // ═══════════════════════════════════════════════════════════

    private int getSentConfigId() throws Exception {
        Field field = ConfigExchangeService.class.getDeclaredField("sentConfigId");
        field.setAccessible(true);
        return field.getInt(service);
    }

    // ═══════════════════════════════════════════════════════════
    //  Тесты
    // ═══════════════════════════════════════════════════════════

    @Test
    void testStartClearsDeviceState() {
        // Заполняем DeviceState данными
        deviceState.setMyNodeNum(42);
        deviceState.getOrCreateNode(100);

        service.startConfigExchange();

        assertEquals(0, deviceState.getMyNodeNum(), "myNodeNum should be cleared");
        assertEquals(0, deviceState.getNodeCount(), "nodeDb should be cleared");
    }

    @Test
    void testStartSendsToRadio() {
        service.startConfigExchange();

        assertFalse(connection.getSentBytes().isEmpty(), "Should have sent a ToRadio message");
    }

    @Test
    void testStartRegistersListener() {
        service.startConfigExchange();

        // Проверяем, что listener зарегистрирован: вызываем onMyNodeInfo через protocolHandler
        // Если listener не зарегистрирован, myNodeNum останется 0
        MeshProtos.MyNodeInfo myInfo = MeshProtos.MyNodeInfo.newBuilder()
                .setMyNodeNum(99)
                .build();
        service.onMyNodeInfo(myInfo);

        assertEquals(99, deviceState.getMyNodeNum(), "Listener should be registered");
    }

    @Test
    void testOnMyNodeInfoSetsNodeNum() {
        service.startConfigExchange();

        MeshProtos.MyNodeInfo myInfo = MeshProtos.MyNodeInfo.newBuilder()
                .setMyNodeNum(12345)
                .build();
        service.onMyNodeInfo(myInfo);

        assertEquals(12345, deviceState.getMyNodeNum());
    }

    @Test
    void testOnNodeInfoPopulatesNodeData() {
        service.startConfigExchange();

        MeshProtos.User user = MeshProtos.User.newBuilder()
                .setLongName("Alice")
                .setShortName("AL")
                .setId("!aabbccdd")
                .build();
        MeshProtos.Position position = MeshProtos.Position.newBuilder()
                .setLatitudeI(557558000)  // 55.7558 * 1e7
                .setLongitudeI(376173000) // 37.6173 * 1e7
                .setAltitude(150)
                .build();
        MeshProtos.NodeInfo nodeInfo = MeshProtos.NodeInfo.newBuilder()
                .setNum(42)
                .setUser(user)
                .setPosition(position)
                .setSnr(5.5f)
                .setLastHeard(1700000000)
                .build();

        service.onNodeInfo(nodeInfo);

        NodeData node = deviceState.getNodeDb().get(42);
        assertNotNull(node);
        assertEquals("Alice", node.getLongName());
        assertEquals("AL", node.getShortName());
        assertEquals("!aabbccdd", node.getNodeId());
        assertEquals(55.7558, node.getLatitude(), 0.001);
        assertEquals(37.6173, node.getLongitude(), 0.001);
        assertEquals(150, node.getAltitude());
        assertEquals(5.5f, node.getSnr(), 0.01);
        assertEquals(1700000000, node.getLastHeard());
    }

    @Test
    void testOnNodeInfoSkipsEmptyStrings() {
        service.startConfigExchange();

        // Предварительно заполняем ноду
        NodeData existing = deviceState.getOrCreateNode(42);
        existing.setLongName("Alice");
        existing.setShortName("AL");

        // Приходит NodeInfo с пустым longName, но непустым shortName
        MeshProtos.User user = MeshProtos.User.newBuilder()
                .setLongName("")       // пустое — не должно затереть "Alice"
                .setShortName("BO")    // непустое — должно обновить
                .build();
        MeshProtos.NodeInfo nodeInfo = MeshProtos.NodeInfo.newBuilder()
                .setNum(42)
                .setUser(user)
                .build();

        service.onNodeInfo(nodeInfo);

        NodeData node = deviceState.getNodeDb().get(42);
        assertEquals("Alice", node.getLongName(), "Empty longName should not overwrite existing");
        assertEquals("BO", node.getShortName(), "Non-empty shortName should update");
    }

    @Test
    void testOnNodeInfoSkipsZeroCoordinates() {
        service.startConfigExchange();

        // Предварительно заполняем ноду координатами
        NodeData existing = deviceState.getOrCreateNode(42);
        existing.setLatitude(55.7558);
        existing.setLongitude(37.6173);

        // Приходит NodeInfo с нулевыми координатами
        MeshProtos.Position position = MeshProtos.Position.newBuilder()
                .setLatitudeI(0)
                .setLongitudeI(0)
                .build();
        MeshProtos.NodeInfo nodeInfo = MeshProtos.NodeInfo.newBuilder()
                .setNum(42)
                .setPosition(position)
                .build();

        service.onNodeInfo(nodeInfo);

        NodeData node = deviceState.getNodeDb().get(42);
        assertEquals(55.7558, node.getLatitude(), 0.001, "Zero latitude should not overwrite existing");
        assertEquals(37.6173, node.getLongitude(), 0.001, "Zero longitude should not overwrite existing");
    }

    @Test
    void testOnConfigModuleConfigChannelStored() {
        service.startConfigExchange();

        service.onConfig(ConfigProtos.Config.getDefaultInstance());
        service.onModuleConfig(ModuleConfigProtos.ModuleConfig.getDefaultInstance());
        service.onChannel(ChannelProtos.Channel.newBuilder().setIndex(0).build());

        assertEquals(1, deviceState.getConfigs().size());
        assertEquals(1, deviceState.getModuleConfigs().size());
        assertEquals(1, deviceState.getChannels().size());
    }

    @Test
    void testConfigCompleteMatchingIdCompletesFuture() throws Exception {
        CompletableFuture<DeviceState> future = service.startConfigExchange();
        int configId = getSentConfigId();

        service.onConfigComplete(configId);

        assertTrue(future.isDone(), "Future should be completed");
        assertSame(deviceState, future.get(1, TimeUnit.SECONDS),
                "Future should resolve to the same DeviceState");
    }

    @Test
    void testConfigCompleteMismatchingIdDoesNotComplete() throws Exception {
        CompletableFuture<DeviceState> future = service.startConfigExchange();
        int configId = getSentConfigId();

        service.onConfigComplete(configId + 1); // Неправильный ID

        assertFalse(future.isDone(), "Future should NOT be completed with wrong config ID");
    }

    // ═══════════════════════════════════════════════════════════
    //  Stub: MeshtasticConnection
    // ═══════════════════════════════════════════════════════════

    private static class StubMeshtasticConnection implements MeshtasticConnection {

        private Consumer<byte[]> dataListener;
        private final List<byte[]> sentBytes = new ArrayList<>();
        private boolean connected = true;

        @Override
        public void connect() {}

        @Override
        public void disconnect() { connected = false; }

        @Override
        public boolean isConnected() { return connected; }

        @Override
        public void sendBytes(byte[] data) { sentBytes.add(data); }

        @Override
        public void setDataListener(Consumer<byte[]> listener) { this.dataListener = listener; }

        @Override
        public void setConnectionListener(ConnectionListener listener) {}

        public List<byte[]> getSentBytes() { return sentBytes; }
    }
}
