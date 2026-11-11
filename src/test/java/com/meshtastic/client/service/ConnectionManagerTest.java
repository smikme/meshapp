package com.meshtastic.client.service;

import com.google.protobuf.MessageLite;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.FrameParser;
import com.meshtastic.client.connection.KissFrameParser;
import com.meshtastic.client.connection.ble.BleDevice;
import com.meshtastic.client.connection.ble.BlePlatform;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.connection.ble.BleState;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.PacketFramer;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionFrames;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionState;
import com.meshtastic.client.protocol.meshcore.MeshCoreKissFrames;
import com.meshtastic.client.protocol.meshcore.MeshCoreKissState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.MeshProtos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class ConnectionManagerTest {

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
    void connectCompletesConfigExchangeAndDisconnectCleansRuntimeState() throws Exception {
        try (TcpMeshtasticStubServer server = new TcpMeshtasticStubServer(0x1234ABCD)) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry entry = new ConnectionEntry("stub", "127.0.0.1", server.port());
            manager.addEntry(entry);

        // Exercise the path through a real TcpConnection and real ProtocolHandler/config exchange.
            manager.connect(entry.getId());

            CompletableFuture<DeviceState> future = manager.getConfigFuture(entry.getId());
            assertNotNull(future);
            DeviceState state = future.get(5, TimeUnit.SECONDS);

            assertEquals(0x1234ABCD, state.getMyNodeNum());
            assertEquals("!1234abcd", manager.getOwnerNodeId(entry.getId()));
            assertTrue(entry.isConnected());
            assertTrue(manager.hasActiveConnection());
            assertTrue(server.awaitWantConfig());

            manager.disconnect(entry.getId());

            assertFalse(entry.isConnected());
            assertFalse(manager.hasActiveConnection());
            assertNull(manager.getDeviceState(entry.getId()));
            assertNull(manager.getProtocolHandler(entry.getId()));
            assertNull(manager.getMessageListenerService(entry.getId()));
            assertNull(manager.getConfigFuture(entry.getId()));
        }
    }

    @Test
    void connectAutostartsMatchingLuaScriptsAfterConfigExchange() throws Exception {
        try (TcpMeshtasticStubServer server = new TcpMeshtasticStubServer(0x1234ABCD)) {
            ConnectionManager manager = ConnectionManager.getInstance();
            LuaScriptService scriptService = LuaScriptService.getInstance();
            LuaScript matching = scriptService.createScript(
                    "matching-autostart",
                    "mesh.kv.set('ready_node', mesh.owner().node_id)",
                    true,
                    "!1234ABCD",
                    LuaScript.BotType.AIR_BOT,
                    "");
            LuaScript otherNode = scriptService.createScript(
                    "other-node-autostart",
                    "mesh.kv.set('ready_node', 'wrong-node')",
                    true,
                    "!00000000",
                    LuaScript.BotType.AIR_BOT,
                    "");
            LuaScript disabled = scriptService.createScript(
                    "disabled-autostart",
                    "mesh.kv.set('ready_node', 'disabled')",
                    false,
                    "!1234ABCD",
                    LuaScript.BotType.AIR_BOT,
                    "");
            ConnectionEntry entry = new ConnectionEntry("stub", "127.0.0.1", server.port());
            manager.addEntry(entry);

            manager.connect(entry.getId());

            CompletableFuture<DeviceState> future = manager.getConfigFuture(entry.getId());
            assertNotNull(future);
            DeviceState state = future.get(5, TimeUnit.SECONDS);

            assertEquals(0x1234ABCD, state.getMyNodeNum());
            assertTrue(waitUntil(() -> "!1234abcd".equals(scriptService.getKv(matching.getId(), "ready_node")),
                    4_000));
            assertNull(scriptService.getKv(otherNode.getId(), "ready_node"));
            assertNull(scriptService.getKv(disabled.getId(), "ready_node"));

            manager.disconnect(entry.getId());
        }
    }

    @Test
    void connectUsesExplicitMeshCoreKissRuntime() throws Exception {
        try (TcpMeshCoreKissStubServer server = new TcpMeshCoreKissStubServer("meshcore-test")) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry entry = new ConnectionEntry("meshcore", "127.0.0.1", server.port());
            entry.setProtocol(ProtocolType.MESHCORE_KISS);
            manager.addEntry(entry);

            manager.connect(entry.getId());

            CompletableFuture<?> future = manager.getProtocolReadyFuture(entry.getId());
            assertNotNull(future);
            Object state = future.get(5, TimeUnit.SECONDS);

            assertInstanceOf(MeshCoreKissState.class, state);
            assertEquals(ProtocolType.MESHCORE_KISS, manager.getActiveProtocolType(entry.getId()));
            var meshCoreState = manager.getMeshCoreKissState(entry.getId());            assertNotNull(meshCoreState);            assertEquals("meshcore-test", meshCoreState.getDeviceName());
            assertTrue(server.awaitDeviceNameRequest());

            manager.disconnect(entry.getId());

            assertFalse(entry.isConnected());
            assertFalse(manager.hasActiveConnection());
        }
    }

    @Test
    void connectUsesExplicitMeshCoreCompanionBleRuntime() throws Exception {
        MeshCoreCompanionPlatform platform = new MeshCoreCompanionPlatform();
        installBlePlatformFactory(() -> platform, true);

        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("meshcore-companion", "AA:BB:CC:DD:EE:FF", "MeshCore Companion");
        entry.setProtocol(ProtocolType.MESHCORE_COMPANION);
        manager.addEntry(entry);

        manager.connect(entry.getId());

        CompletableFuture<?> future = manager.getProtocolReadyFuture(entry.getId());
        assertNotNull(future);
        Object state = future.get(5, TimeUnit.SECONDS);

        assertInstanceOf(MeshCoreCompanionState.class, state);
        assertEquals(ProtocolType.MESHCORE_COMPANION, manager.getActiveProtocolType(entry.getId()));
        var meshCoreState = manager.getMeshCoreCompanionState(entry.getId());        assertNotNull(meshCoreState);        assertEquals("meshcore-companion-test", meshCoreState.getDeviceName());
        DeviceState uiState = manager.getDeviceState(entry.getId());
        assertNotNull(uiState);
        assertEquals("mc:a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf", uiState.getOwnerNodeId());
        assertEquals("meshcore-companion-test", uiState.getOwnerInfo().getLongName());
        assertTrue(uiState.hasEnabledChannel(0));
        assertEquals(BleProtocolProfile.MESHCORE_COMPANION, platform.profile);
        assertTrue(platform.appStartReceived.await(1, TimeUnit.SECONDS));

        manager.disconnect(entry.getId());
    }

    @Test
    void connectUsesSeparateBlePlatformForParallelBleConnections() throws Exception {
        MeshCoreCompanionPlatform firstPlatform =
                new MeshCoreCompanionPlatform("meshcore-one", (byte) 0xA0);
        MeshCoreCompanionPlatform secondPlatform =
                new MeshCoreCompanionPlatform("meshcore-two", (byte) 0xC0);
        Queue<BlePlatform> platforms = new ArrayDeque<>();
        platforms.add(firstPlatform);
        platforms.add(secondPlatform);
        installBlePlatformFactory(platforms::remove, true);

        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry first = new ConnectionEntry("ble-one", "AA:BB:CC:DD:EE:01", "BLE One");
        first.setProtocol(ProtocolType.MESHCORE_COMPANION);
        ConnectionEntry second = new ConnectionEntry("ble-two", "AA:BB:CC:DD:EE:02", "BLE Two");
        second.setProtocol(ProtocolType.MESHCORE_COMPANION);
        manager.addEntry(first);
        manager.addEntry(second);

        manager.connect(first.getId());
        manager.getProtocolReadyFuture(first.getId()).get(5, TimeUnit.SECONDS);
        manager.connect(second.getId());
        manager.getProtocolReadyFuture(second.getId()).get(5, TimeUnit.SECONDS);

        assertTrue(first.isConnected());
        assertTrue(second.isConnected());
        assertEquals(2, manager.getActiveConnectionEntries().size());
        assertTrue(firstPlatform.appStartReceived.await(1, TimeUnit.SECONDS));
        assertTrue(secondPlatform.appStartReceived.await(1, TimeUnit.SECONDS));

        manager.disconnect(second.getId());
        manager.disconnect(first.getId());
    }

    @Test
    void connectRejectsParallelBleWhenPlatformBackendIsSingleton() throws Exception {
        MeshCoreCompanionPlatform platform = new MeshCoreCompanionPlatform();
        installBlePlatformFactory(() -> platform, false);

        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry first = new ConnectionEntry("ble-one", "AA:BB:CC:DD:EE:01", "BLE One");
        first.setProtocol(ProtocolType.MESHCORE_COMPANION);
        ConnectionEntry second = new ConnectionEntry("ble-two", "AA:BB:CC:DD:EE:02", "BLE Two");
        second.setProtocol(ProtocolType.MESHCORE_COMPANION);
        manager.addEntry(first);
        manager.addEntry(second);

        manager.connect(first.getId());
        manager.getProtocolReadyFuture(first.getId()).get(5, TimeUnit.SECONDS);

        ConnectionException error = assertThrows(ConnectionException.class,
                () -> manager.connect(second.getId()));

        assertEquals(I18n.t("connection.error.parallelBleUnsupported", first.getName(), second.getName()),
                error.getMessage());
        assertTrue(first.isConnected());
        assertFalse(second.isConnected());

        manager.disconnect(first.getId());
    }

    @Test
    void failedBleConnectDisconnectsAndDisposesTransport() {
        FailingBlePlatform platform = new FailingBlePlatform();
        installBlePlatformFactory(() -> platform, true);

        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("ble", "AA:BB:CC:DD:EE:FF", "Test BLE");
        manager.addEntry(entry);

        ConnectionException error = assertThrows(ConnectionException.class,
                () -> manager.connect(entry.getId()));

        assertEquals("Pairing failed", error.getMessage());
        assertEquals(1, platform.connectCalls);
        assertEquals(1, platform.disconnectCalls);
        assertEquals(1, platform.disposeCalls);
        assertFalse(entry.isConnected());
        assertFalse(entry.isReconnecting());
        assertFalse(manager.hasActiveConnection());
        assertFalse(pendingReconnects().containsKey(entry.getId()));
    }

    @Test
    void connectUsesExplicitMeshCoreCompanionTcpRuntime() throws Exception {
        try (TcpMeshCoreCompanionStubServer server =
                     new TcpMeshCoreCompanionStubServer("meshcore-companion-tcp")) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry entry = new ConnectionEntry("meshcore-companion", "127.0.0.1", server.port());
            entry.setProtocol(ProtocolType.MESHCORE_COMPANION);
            manager.addEntry(entry);

            manager.connect(entry.getId());

            CompletableFuture<?> future = manager.getProtocolReadyFuture(entry.getId());
            assertNotNull(future);
            Object state = future.get(5, TimeUnit.SECONDS);

            assertInstanceOf(MeshCoreCompanionState.class, state);
            assertEquals(ProtocolType.MESHCORE_COMPANION, manager.getActiveProtocolType(entry.getId()));
            var meshCoreState = manager.getMeshCoreCompanionState(entry.getId());            assertNotNull(meshCoreState);            assertEquals("meshcore-companion-tcp", meshCoreState.getDeviceName());
            DeviceState uiState = manager.getDeviceState(entry.getId());
            assertNotNull(uiState);
            assertEquals("mc:a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf", uiState.getOwnerNodeId());
            assertEquals("meshcore-companion-tcp", uiState.getOwnerInfo().getLongName());
            assertTrue(uiState.hasEnabledChannel(0));
            assertTrue(server.awaitRuntimeAppStart());

            manager.disconnect(entry.getId());
        }
    }

    @Test
    void shutdownAllDoesNotBlockOnTcpReaderJoinTimeout() throws Exception {
        try (TcpMeshtasticStubServer server = new TcpMeshtasticStubServer(0x1234ABCD)) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry entry = new ConnectionEntry("stub", "127.0.0.1", server.port());
            manager.addEntry(entry);

            manager.connect(entry.getId());
            manager.getConfigFuture(entry.getId()).get(5, TimeUnit.SECONDS);

            long startedAt = System.nanoTime();
            manager.shutdownAll();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue(elapsedMs < 1_200, "shutdownAll should not wait for the TCP reader join timeout");
            assertFalse(entry.isConnected());
            assertFalse(manager.hasActiveConnection());
        }
    }

    @Test
    void connectAutoconnectEntriesConnectsOnlyFlaggedProfiles() throws Exception {
        try (TcpMeshtasticStubServer autoconnectServer = new TcpMeshtasticStubServer(0x1234ABCD);
             TcpMeshtasticStubServer manualServer = new TcpMeshtasticStubServer(0x22222222)) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry autoconnect = new ConnectionEntry("auto", "127.0.0.1", autoconnectServer.port());
            autoconnect.setAutoconnect(true);
            ConnectionEntry manual = new ConnectionEntry("manual", "127.0.0.1", manualServer.port());
            manager.addEntry(autoconnect);
            manager.addEntry(manual);

            manager.connectAutoconnectEntries();

            assertTrue(waitUntil(() -> manager.getProtocolReadyFuture(autoconnect.getId()) != null, 2_000));
            CompletableFuture<DeviceState> future = manager.getConfigFuture(autoconnect.getId());
            assertNotNull(future);
            assertEquals(0x1234ABCD, future.get(5, TimeUnit.SECONDS).getMyNodeNum());
            assertTrue(autoconnect.isConnected());
            assertFalse(manual.isConnected());
            assertNull(manager.getConfigFuture(manual.getId()));

            manager.disconnect(autoconnect.getId());
        }
    }

    @Test
    void autoconnectFlagIsStoredInConnectionsJson() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("auto", "127.0.0.1", 4403);
        entry.setAutoconnect(true);
        manager.addEntry(entry);

        TestEnvironmentSupport.resetSingletons();
        ConnectionEntry loaded = ConnectionManager.getInstance().getEntries().getFirst();

        assertTrue(loaded.isAutoconnect());
    }

    @Test
    void loadNormalizesRemoteRpcProfileProtocol() throws Exception {
        Path configDir = tempHome.resolve(".meshapp");
        Files.createDirectories(configDir);
        Files.writeString(
                configDir.resolve("connections.json"),
                """
                [
                  {
                    "id": "rpc-1",
                    "name": "RPC",
                    "protocol": "MESHTASTIC",
                    "type": "REMOTE_RPC",
                    "host": "127.0.0.1",
                    "port": 44030,
                    "remoteAccessKey": "mra1_test"
                  }
                ]
                """,
                StandardCharsets.UTF_8);

        ConnectionEntry loaded = ConnectionManager.getInstance().getEntries().getFirst();

        assertEquals(ConnectionType.REMOTE_RPC, loaded.getType());
        assertEquals(ProtocolType.REMOTE_RPC, loaded.getProtocol());
        assertEquals(ConnectionType.REMOTE_RPC, loaded.getEffectiveType());
        assertEquals(ProtocolType.REMOTE_RPC, loaded.getEffectiveProtocol());
        String savedJson = Files.readString(configDir.resolve("connections.json"), StandardCharsets.UTF_8);
        assertTrue(savedJson.contains("\"protocol\": \"REMOTE_RPC\""));
    }

    @Test
    void connectAllowsMultipleActiveConnectionsAndTracksSelectedConnection() throws Exception {
        try (TcpMeshtasticStubServer serverA = new TcpMeshtasticStubServer(0x11111111);
             TcpMeshtasticStubServer serverB = new TcpMeshtasticStubServer(0x22222222)) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry first = new ConnectionEntry("one", "127.0.0.1", serverA.port());
            ConnectionEntry second = new ConnectionEntry("two", "127.0.0.1", serverB.port());
            manager.addEntry(first);
            manager.addEntry(second);

            manager.connect(first.getId());
            manager.getConfigFuture(first.getId()).get(5, TimeUnit.SECONDS);
            assertEquals(first.getId(), manager.getSelectedConnectionId());

            manager.connect(second.getId());
            manager.getConfigFuture(second.getId()).get(5, TimeUnit.SECONDS);

            assertTrue(first.isConnected());
            assertTrue(second.isConnected());
            assertEquals(2, manager.getActiveConnectionEntries().size());

            manager.setSelectedConnectionId(second.getId());
            assertEquals(second.getId(), manager.getSelectedConnectionId());

            manager.disconnect(second.getId());
            assertFalse(second.isConnected());
            assertEquals(first.getId(), manager.getSelectedConnectionId());

            manager.disconnect(first.getId());
            assertNull(manager.getSelectedConnectionId());
        }
    }

    @Test
    void connectRejectsKnownDuplicateNodeIdBeforeOpeningTransport() throws Exception {
        try (TcpMeshtasticStubServer server = new TcpMeshtasticStubServer(0x11111111)) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry active = new ConnectionEntry("tcp", "127.0.0.1", server.port());
            ConnectionEntry duplicate = new ConnectionEntry("ble", "AA:BB:CC:DD:EE:FF", "same node");
            duplicate.setNodeId("!11111111");
            manager.addEntry(active);
            manager.addEntry(duplicate);

            manager.connect(active.getId());
            manager.getConfigFuture(active.getId()).get(5, TimeUnit.SECONDS);

            ConnectionException error = assertThrows(ConnectionException.class,
                    () -> manager.connect(duplicate.getId()));

            assertEquals(I18n.t("connection.error.duplicateNode",
                    duplicate.getNodeId(),
                    active.getName(),
                    active.getEffectiveType()), error.getMessage());
            assertFalse(duplicate.isConnected());
            assertEquals(1, manager.getActiveConnectionEntries().size());

            manager.disconnect(active.getId());
        }
    }

    @Test
    void connectDisconnectsDuplicateWhenNodeIdIsResolvedAfterHandshake() throws Exception {
        try (TcpMeshtasticStubServer serverA = new TcpMeshtasticStubServer(0x11111111);
             TcpMeshtasticStubServer serverB = new TcpMeshtasticStubServer(0x11111111)) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry first = new ConnectionEntry("tcp", "127.0.0.1", serverA.port());
            ConnectionEntry duplicate = new ConnectionEntry("tcp duplicate", "127.0.0.1", serverB.port());
            manager.addEntry(first);
            manager.addEntry(duplicate);

            manager.connect(first.getId());
            manager.getConfigFuture(first.getId()).get(5, TimeUnit.SECONDS);

            manager.connect(duplicate.getId());
            // Duplicate detection can disconnect and remove the config future before
            // connect() returns. Wait for the resulting state, not a removed future.
            assertTrue(waitUntil(() -> "!11111111".equals(duplicate.getNodeId())
                    && !duplicate.isConnected()
                    && manager.getDeviceState(duplicate.getId()) == null, 5_000));
            assertTrue(first.isConnected());
            assertFalse(duplicate.isConnected());
            assertEquals("!11111111", duplicate.getNodeId());
            assertNull(manager.getDeviceState(duplicate.getId()));
            assertEquals(1, manager.getActiveConnectionEntries().size());

            manager.disconnect(first.getId());
        }
    }

    @Test
    void shouldStartHeartbeatForTcpAndSerialConnections() {
        ConnectionEntry tcp = new ConnectionEntry("tcp", "127.0.0.1", 4403);
        ConnectionEntry serial = new ConnectionEntry("serial", "COM3", 115200, ConnectionType.SERIAL);
        ConnectionEntry ble = new ConnectionEntry("ble", "AA:BB:CC:DD:EE:FF", "test");

        assertTrue(ConnectionManager.shouldStartHeartbeat(tcp));
        assertTrue(ConnectionManager.shouldStartHeartbeat(serial));
        assertFalse(ConnectionManager.shouldStartHeartbeat(ble));
    }

    @Test
    void updateEntryReplacesStoredParametersAndPreservesProfileMetadata() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("home", "127.0.0.1", 4403);
        entry.setNodeId("!12345678");
        manager.addEntry(entry);

        ConnectionEntry updated = new ConnectionEntry("office", "192.0.2.10", 4404);
        updated.setId(entry.getId());
        updated.setProtocol(ProtocolType.MESHCORE_KISS);
        updated.setAutoconnect(true);

        manager.updateEntry(updated);

        ConnectionEntry stored = manager.findEntry(entry.getId());
        assertNotNull(stored);
        assertEquals(1, manager.getEntries().size());
        assertEquals(entry.getId(), stored.getId());
        assertEquals("office", stored.getName());
        assertEquals("192.0.2.10", stored.getHost());
        assertEquals(4404, stored.getPort());
        assertEquals("!12345678", stored.getNodeId());
        assertEquals(ProtocolType.MESHCORE_KISS, stored.getProtocol());
        assertTrue(stored.isAutoconnect());
    }

    @Test
    void updateEntryRejectsActiveProfile() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("home", "127.0.0.1", 4403);
        manager.addEntry(entry);
        entry.setConnected(true);

        ConnectionEntry updated = new ConnectionEntry("office", "192.0.2.10", 4404);
        updated.setId(entry.getId());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> manager.updateEntry(updated));

        assertEquals(I18n.t("connection.error.editActive"), error.getMessage());
        var storedEntry = manager.findEntry(entry.getId());
        assertNotNull(storedEntry);
        assertEquals("home", storedEntry.getName());
    }

    @Test
    void disconnectForDeviceRebootKeepsReconnectEnabled() throws Exception {
        try (TcpMeshtasticStubServer server = new TcpMeshtasticStubServer(0xCAFEBABE)) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry entry = new ConnectionEntry("stub", "127.0.0.1", server.port());
            manager.addEntry(entry);

            manager.connect(entry.getId());
            manager.getConfigFuture(entry.getId()).get(5, TimeUnit.SECONDS);

            assertTrue(manager.disconnectForDeviceReboot(entry.getId()));

            // A concurrent transport error can own cleanup while disconnect()
            // returns through the stale-callback path. Wait until that callback
            // has published the reconnect task before checking its final state.
            assertTrue(waitUntil(() -> pendingReconnects().containsKey(entry.getId()), 5_000),
                    "Device reboot must schedule reconnect after transport cleanup");

            assertFalse(entry.isConnected());
            assertTrue(entry.isReconnecting());
            assertFalse(manager.hasActiveConnection());
            assertTrue(deviceRebootReconnectIds().contains(entry.getId()));
            assertFalse(expectedDeviceRebootIds(manager).contains(entry.getId()));
        }
    }

    @Test
    void fromRadioRebootMarkerStartsRebootAwareReconnect() throws Exception {
        try (TcpMeshtasticStubServer server = new TcpMeshtasticStubServer(0xCAFEBABE)) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry entry = new ConnectionEntry("stub", "127.0.0.1", server.port());
            manager.addEntry(entry);

            manager.connect(entry.getId());
            manager.getConfigFuture(entry.getId()).get(5, TimeUnit.SECONDS);
            assertTrue(entry.isConnected());
            assertTrue(manager.hasActiveConnection());

            server.sendRebooted();

            assertTrue(waitUntil(entry::isReconnecting, 2_000));
            assertFalse(entry.isConnected());
            assertFalse(manager.hasActiveConnection());
            assertTrue(deviceRebootReconnectIds().contains(entry.getId()));
            assertFalse(expectedDeviceRebootIds(manager).contains(entry.getId()));

            ReconnectService.getInstance().cancelReconnect(entry.getId());
        }
    }

    @Test
    void expectedDeviceRebootDisconnectUsesRebootAwareReconnect() throws Exception {
        ControlledBlePlatform platform = new ControlledBlePlatform();
        installBlePlatformFactory(() -> platform, true);

        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("ble", "AA:BB:CC:DD:EE:FF", "Test BLE");
        manager.addEntry(entry);

        manager.connect(entry.getId());
        assertTrue(entry.isConnected());

        manager.expectDeviceReboot(entry.getId());
        platform.emitDisconnected();

        assertTrue(waitUntil(entry::isReconnecting, 1_000));
        assertFalse(entry.isConnected());
        assertFalse(manager.hasActiveConnection());
        assertTrue(deviceRebootReconnectIds().contains(entry.getId()));
        assertFalse(expectedDeviceRebootIds(manager).contains(entry.getId()));
    }

    @Test
    void lateDeviceRebootHandoffDoesNotLeaveExpectedRebootFlag() throws Exception {
        ControlledBlePlatform platform = new ControlledBlePlatform();
        installBlePlatformFactory(() -> platform, true);

        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("ble", "AA:BB:CC:DD:EE:FF", "Test BLE");
        manager.addEntry(entry);

        manager.connect(entry.getId());
        assertTrue(entry.isConnected());

        manager.expectDeviceReboot(entry.getId());
        platform.emitDisconnected();
        assertTrue(waitUntil(entry::isReconnecting, 1_000));

        manager.disconnectForDeviceReboot(entry.getId());

        assertTrue(entry.isReconnecting());
        assertTrue(deviceRebootReconnectIds().contains(entry.getId()));
        assertFalse(expectedDeviceRebootIds(manager).contains(entry.getId()));
    }

    @Test
    void staleDeviceRebootHandoffDoesNotDisconnectFreshReconnect() throws Exception {
        ControlledBlePlatform firstPlatform = new ControlledBlePlatform();
        ControlledBlePlatform secondPlatform = new ControlledBlePlatform();
        Queue<BlePlatform> platforms = new ArrayDeque<>();
        platforms.add(firstPlatform);
        platforms.add(secondPlatform);
        installBlePlatformFactory(platforms::remove, true);

        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("ble", "AA:BB:CC:DD:EE:FF", "Test BLE");
        manager.addEntry(entry);

        manager.connect(entry.getId());
        assertTrue(entry.isConnected());
        long oldGeneration = manager.getConnectionGeneration(entry.getId());

        manager.expectDeviceReboot(entry.getId());
        firstPlatform.emitDisconnected();
        assertTrue(waitUntil(entry::isReconnecting, 1_000));

        manager.connect(entry.getId());
        assertTrue(entry.isConnected());
        assertFalse(entry.isReconnecting());
        assertTrue(manager.getConnectionGeneration(entry.getId()) > oldGeneration);

        manager.disconnectForDeviceReboot(entry.getId(), oldGeneration);

        assertTrue(entry.isConnected());
        assertFalse(entry.isReconnecting());
        assertTrue(manager.hasActiveConnection());

        manager.disconnect(entry.getId());
    }

    @Test
    void rebootAwareReconnectReschedulesExistingShortReconnect() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("stub", "127.0.0.1", 4403);
        manager.addEntry(entry);

        ReconnectService service = ReconnectService.getInstance();
        service.startReconnect(entry.getId());
        ScheduledFuture<?> normalFuture = pendingReconnects().get(entry.getId());
        assertNotNull(normalFuture);
        long normalDelayMs = normalFuture.getDelay(TimeUnit.MILLISECONDS);

        service.startReconnectAfterDeviceReboot(entry.getId());

        ScheduledFuture<?> rebootFuture = pendingReconnects().get(entry.getId());
        assertNotNull(rebootFuture);
        assertNotSame(normalFuture, rebootFuture);
        assertTrue(deviceRebootReconnectIds().contains(entry.getId()));
        assertTrue(rebootFuture.getDelay(TimeUnit.MILLISECONDS) > normalDelayMs + 2_000);

        service.cancelReconnect(entry.getId());
    }

    @Test
    void staleDisconnectCallbackDoesNotStartReconnectAfterNewConnectionIsActive() throws Exception {
        ControlledBlePlatform firstPlatform = new ControlledBlePlatform();
        ControlledBlePlatform secondPlatform = new ControlledBlePlatform();
        Queue<BlePlatform> platforms = new ArrayDeque<>();
        platforms.add(firstPlatform);
        platforms.add(secondPlatform);
        installBlePlatformFactory(platforms::remove, true);

        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("ble", "AA:BB:CC:DD:EE:FF", "Test BLE");
        manager.addEntry(entry);

        manager.connect(entry.getId());
        assertTrue(entry.isConnected());

        firstPlatform.emitDisconnected();
        assertTrue(waitUntil(entry::isReconnecting, 1_000));
        assertFalse(entry.isConnected());
        assertFalse(manager.hasActiveConnection());

        manager.connect(entry.getId());
        assertTrue(entry.isConnected());
        assertFalse(entry.isReconnecting());
        assertTrue(manager.hasActiveConnection());

        firstPlatform.emitDisconnected();

        assertTrue(entry.isConnected());
        assertFalse(entry.isReconnecting());
        assertTrue(manager.hasActiveConnection());

        manager.disconnect(entry.getId());
    }

    @Test
    void removeEntryDoesNotBlockWhileBleConnectIsPending() throws Exception {
        BlockingBlePlatform platform = new BlockingBlePlatform();
        installBlePlatformFactory(() -> platform, true);

        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("ble", "AA:BB:CC:DD:EE:FF", "Test BLE");
        manager.addEntry(entry);

        Thread connectThread = new Thread(() -> {
            try {
                manager.connect(entry.getId());
            } catch (ConnectionException ignored) {
            }
        }, "test-ble-connect");
        connectThread.setDaemon(true);
        connectThread.start();

        assertTrue(platform.connectStarted.await(1, TimeUnit.SECONDS));

        long startedAt = System.nanoTime();
        manager.removeEntry(entry.getId());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(elapsedMs < 500, "removeEntry should not wait for a hung BLE connect");
        assertNull(manager.findEntry(entry.getId()));

        platform.allowConnectFinish.countDown();
        connectThread.join(2_000);

        assertFalse(connectThread.isAlive());
        assertFalse(manager.hasActiveConnection());
        assertTrue(platform.disconnectCalls.await(1, TimeUnit.SECONDS),
                "late BLE connect should be disconnected after removal");
    }

    private static void installBlePlatformFactory(java.util.function.Supplier<BlePlatform> platformFactory,
                                                  boolean supportsParallelConnections) {
        BleDeviceDiscoveryService discovery = BleDeviceDiscoveryService.getInstance();
        discovery.setPlatformFactoryForTests(platformFactory);
        discovery.setParallelConnectionSupportForTests(supportsParallelConnections);
    }

    private static boolean waitUntil(CheckedBooleanSupplier condition, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            // The connection manager exposes no completion event for these state predicates.
            //noinspection BusyWait
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> deviceRebootReconnectIds() {
        return (Set<String>) readField(ReconnectService.getInstance(), "deviceRebootReconnects");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> expectedDeviceRebootIds(ConnectionManager manager) {
        return (Set<String>) readField(manager, "expectedDeviceRebootIds");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ScheduledFuture<?>> pendingReconnects() {
        return (Map<String, ScheduledFuture<?>>) readField(ReconnectService.getInstance(), "pendingReconnects");
    }

    private static Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read field " + fieldName, e);
        }
    }

    private static final class ControlledBlePlatform implements BlePlatform {
        private volatile Consumer<BleState> stateListener;
        private volatile boolean connected;

        @Override
        public void startScan(Consumer<BleDevice> onDeviceFound) {
        }

        @Override
        public void stopScan() {
        }

        @Override
        public void connect(String address) {
            connected = true;
            Consumer<BleState> listener = stateListener;
            if (listener != null) {
                listener.accept(new BleState.Connected());
            }
        }

        @Override
        public void disconnect() {
            connected = false;
            Consumer<BleState> listener = stateListener;
            if (listener != null) {
                listener.accept(new BleState.Disconnected());
            }
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public boolean writeToRadio(byte[] protobufPayload) {
            return connected;
        }

        @Override
        public void setFromRadioListener(Consumer<byte[]> listener) {
        }

        @Override
        public void setStateListener(Consumer<BleState> listener) {
            this.stateListener = listener;
        }

        @Override
        public BlePlatform.AdapterState getAdapterState() {
            return BlePlatform.AdapterState.POWERED_ON;
        }

        @Override
        public void dispose() {
        }

        private void emitDisconnected() {
            connected = false;
            Consumer<BleState> listener = stateListener;
            if (listener != null) {
                listener.accept(new BleState.Disconnected());
            }
        }
    }

    private static final class BlockingBlePlatform implements BlePlatform {
        private final CountDownLatch connectStarted = new CountDownLatch(1);
        private final CountDownLatch allowConnectFinish = new CountDownLatch(1);
        private final CountDownLatch disconnectCalls = new CountDownLatch(1);
        private volatile Consumer<BleState> stateListener;
        private volatile boolean connected;

        @Override
        public void startScan(Consumer<BleDevice> onDeviceFound) {
        }

        @Override
        public void stopScan() {
        }

        @Override
        public void connect(String address) throws ConnectionException {
            connectStarted.countDown();
            try {
                if (!allowConnectFinish.await(5, TimeUnit.SECONDS)) {
                    throw new ConnectionException("Timed out waiting for test release");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConnectionException("Interrupted while waiting for test release", e);
            }
            connected = true;
            Consumer<BleState> listener = stateListener;
            if (listener != null) {
                listener.accept(new BleState.Connected());
            }
        }

        @Override
        public void disconnect() {
            connected = false;
            disconnectCalls.countDown();
            Consumer<BleState> listener = stateListener;
            if (listener != null) {
                listener.accept(new BleState.Disconnected());
            }
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public boolean writeToRadio(byte[] protobufPayload) {
            return connected;
        }

        @Override
        public void setFromRadioListener(Consumer<byte[]> listener) {
        }

        @Override
        public void setStateListener(Consumer<BleState> listener) {
            this.stateListener = listener;
        }

        @Override
        public BlePlatform.AdapterState getAdapterState() {
            return BlePlatform.AdapterState.POWERED_ON;
        }

        @Override
        public void dispose() {
        }
    }

    private static final class FailingBlePlatform implements BlePlatform {
        private volatile Consumer<BleState> stateListener;
        private int connectCalls;
        private int disconnectCalls;
        private int disposeCalls;

        @Override
        public void startScan(Consumer<BleDevice> onDeviceFound) {
        }

        @Override
        public void stopScan() {
        }

        @Override
        public void connect(String address) throws ConnectionException {
            connectCalls++;
            throw new ConnectionException("Pairing failed");
        }

        @Override
        public void disconnect() {
            disconnectCalls++;
            Consumer<BleState> listener = stateListener;
            if (listener != null) {
                listener.accept(new BleState.Disconnected());
            }
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public boolean writeToRadio(byte[] protobufPayload) {
            return false;
        }

        @Override
        public void setFromRadioListener(Consumer<byte[]> listener) {
        }

        @Override
        public void setStateListener(Consumer<BleState> listener) {
            this.stateListener = listener;
        }

        @Override
        public BlePlatform.AdapterState getAdapterState() {
            return BlePlatform.AdapterState.POWERED_ON;
        }

        @Override
        public void dispose() {
            disposeCalls++;
        }
    }

    private static final class MeshCoreCompanionPlatform implements BlePlatform {
        private final CountDownLatch appStartReceived = new CountDownLatch(1);
        private final String deviceName;
        private final byte publicKeyBase;
        private volatile Consumer<byte[]> fromRadioListener;
        private volatile Consumer<BleState> stateListener;
        private volatile BleProtocolProfile profile = BleProtocolProfile.MESHTASTIC;
        private volatile boolean connected;

        private MeshCoreCompanionPlatform() {
            this("meshcore-companion-test", (byte) 0xA0);
        }

        private MeshCoreCompanionPlatform(String deviceName, byte publicKeyBase) {
            this.deviceName = deviceName;
            this.publicKeyBase = publicKeyBase;
        }

        @Override
        public void startScan(Consumer<BleDevice> onDeviceFound) {
        }

        @Override
        public void stopScan() {
        }

        @Override
        public void connect(String address) throws ConnectionException {
            if (profile == BleProtocolProfile.MESHTASTIC) {
                throw new ConnectionException("Meshtastic service not found");
            }
            connected = true;
            Consumer<BleState> listener = stateListener;
            if (listener != null) {
                listener.accept(new BleState.Connected());
            }
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
            if(protobufPayload[0]==1)appStartReceived.countDown();
            if(fromRadioListener!=null)for(byte[] response:companionResponses(protobufPayload,deviceName,publicKeyBase))fromRadioListener.accept(response);
            return connected;
        }

        @Override
        public void setFromRadioListener(Consumer<byte[]> listener) {
            this.fromRadioListener = listener;
        }

        @Override
        public void setStateListener(Consumer<BleState> listener) {
            this.stateListener = listener;
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
        public BlePlatform.AdapterState getAdapterState() {
            return BlePlatform.AdapterState.POWERED_ON;
        }

        @Override
        public void dispose() {
        }
    }

    private static java.util.List<byte[]> companionResponses(byte[] command,String name,byte base) {
        return switch(command[0]&255) {
            case 1 -> java.util.List.of(meshCoreSelfInfo(name,base));
            case 22 -> {byte[] p=new byte[82];p[0]=13;p[1]=14;p[2]=16;p[3]=1;yield java.util.List.of(p);}
            case 4 -> java.util.List.of(new byte[]{2,0,0,0,0},new byte[]{4,0,0,0,0});
            case 31 -> {byte[] p=new byte[50];p[0]=18;p[1]=command[1];p[2]='P';p[34]=1;yield java.util.List.of(p);}
            case 20 -> java.util.List.of(new byte[]{12,0x34,0x12});
            case 10 -> java.util.List.of(new byte[]{10});
            default -> java.util.List.of(new byte[]{1,1});
        };
    }

    private static byte[] meshCoreSelfInfo(String name, byte publicKeyBase) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[58 + nameBytes.length];
        packet[0] = (byte) MeshCoreCompanionFrames.PACKET_SELF_INFO;
        packet[1] = 1;
        packet[2] = 10;
        packet[3] = 20;
        for (int i = 0; i < 32; i++) {
            packet[4 + i] = (byte) (publicKeyBase + i);
        }
        System.arraycopy(nameBytes, 0, packet, 58, nameBytes.length);
        return packet;
    }

    private static final class TcpMeshtasticStubServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final int myNodeNum;
        private final CountDownLatch wantConfigLatch = new CountDownLatch(1);
        private final Thread acceptThread;
        private volatile Socket clientSocket;
        private volatile OutputStream clientOutput;
        private final CountDownLatch outputReady = new CountDownLatch(1);
        private volatile boolean running = true;

        private TcpMeshtasticStubServer(int myNodeNum) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.myNodeNum = myNodeNum;
            this.acceptThread = new Thread(this::acceptLoop, "meshapp-test-server");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        boolean awaitWantConfig() throws InterruptedException {
            return wantConfigLatch.await(5, TimeUnit.SECONDS);
        }

        private void acceptLoop() {
            try (Socket socket = serverSocket.accept()) {
                clientSocket = socket;
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                clientOutput = out;
                outputReady.countDown();
                FrameParser parser = new FrameParser();
                byte[] buffer = new byte[256];

                while (running) {
                    int read = in.read(buffer);
                    if (read < 0) {
                        return;
                    }
                    for (int i = 0; i < read; i++) {
                        byte[] payload = parser.processByte(buffer[i]);
                        if (payload == null) {
                            continue;
                        }
                        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.parseFrom(payload);
                        if (toRadio.hasWantConfigId()) {
        // Minimal config-exchange happy path:
                            // MyNodeInfo + matching config_complete_id.
                            wantConfigLatch.countDown();
                            send(out, MeshProtos.FromRadio.newBuilder()
                                    .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(myNodeNum).build())
                                    .build());
                            send(out, MeshProtos.FromRadio.newBuilder()
                                    .setConfigCompleteId(toRadio.getWantConfigId())
                                    .build());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private void send(OutputStream out, MessageLite message) throws IOException {
            out.write(PacketFramer.frame(message));
            out.flush();
        }

        void sendRebooted() throws Exception {
            assertTrue(outputReady.await(1, TimeUnit.SECONDS));
            OutputStream out = clientOutput;
            assertNotNull(out);
            send(out, MeshProtos.FromRadio.newBuilder()
                    .setRebooted(true)
                    .build());
        }

        @Override
        public void close() throws Exception {
            running = false;
            Socket socket = clientSocket;
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            serverSocket.close();
            acceptThread.join(2000);
        }
    }

    private static final class TcpMeshCoreKissStubServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final String deviceName;
        private final CountDownLatch deviceNameRequestLatch = new CountDownLatch(1);
        private final Thread acceptThread;
        private volatile Socket clientSocket;
        private volatile boolean running = true;

        private TcpMeshCoreKissStubServer(String deviceName) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.deviceName = deviceName;
            this.acceptThread = new Thread(this::acceptLoop, "meshapp-test-meshcore-server");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        boolean awaitDeviceNameRequest() throws InterruptedException {
            return deviceNameRequestLatch.await(5, TimeUnit.SECONDS);
        }

        private void acceptLoop() {
            try (Socket socket = serverSocket.accept()) {
                clientSocket = socket;
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                KissFrameParser parser = new KissFrameParser();
                byte[] buffer = new byte[256];

                while (running) {
                    int read = in.read(buffer);
                    if (read < 0) {
                        return;
                    }
                    for (int i = 0; i < read; i++) {
                        byte[] frame = parser.processByte(buffer[i]);
                        if (!MeshCoreKissFrames.isSetHardwareFrame(frame)) {
                            continue;
                        }
                        int subCommand = MeshCoreKissFrames.subCommand(frame);
                        if (subCommand == MeshCoreKissFrames.REQ_GET_DEVICE_NAME) {
                            deviceNameRequestLatch.countDown();
                            sendDeviceName(out);
                        } else if (subCommand == MeshCoreKissFrames.REQ_PING) {
                            sendSetHardware(out, MeshCoreKissFrames.RESP_PONG, new byte[0]);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private void sendDeviceName(OutputStream out) throws IOException {
            sendSetHardware(out, MeshCoreKissFrames.RESP_DEVICE_NAME,
                    deviceName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        private void sendSetHardware(OutputStream out, int subCommand, byte[] payload) throws IOException {
            byte[] body = new byte[2 + payload.length];
            body[0] = (byte) MeshCoreKissFrames.CMD_SET_HARDWARE;
            body[1] = (byte) subCommand;
            System.arraycopy(payload, 0, body, 2, payload.length);
            out.write(MeshCoreKissFrames.frame(body));
            out.flush();
        }

        @Override
        public void close() throws Exception {
            running = false;
            Socket socket = clientSocket;
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            serverSocket.close();
            acceptThread.join(2000);
        }
    }

    private static final class TcpMeshCoreCompanionStubServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final String deviceName;
        private final CountDownLatch appStartLatch = new CountDownLatch(1);
        private final Thread acceptThread;
        private volatile Socket clientSocket;
        private volatile boolean running = true;

        private TcpMeshCoreCompanionStubServer(String deviceName) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.deviceName = deviceName;
            this.acceptThread = new Thread(this::acceptLoop, "meshapp-test-meshcore-companion-server");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        boolean awaitRuntimeAppStart() throws InterruptedException {
            return appStartLatch.await(5, TimeUnit.SECONDS);
        }

        private void acceptLoop() {
            try (Socket socket = serverSocket.accept()) {
                clientSocket = socket;
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                while(running) {
                    int marker=in.read();if(marker<0)return;
                    if(marker!=0x3c)throw new IOException("Missing Companion stream header");
                    int low=in.read(),high=in.read();if(low<0||high<0)return;
                    byte[] command=in.readNBytes(low|(high<<8));
                    if(command.length==0)return;
                    if(command[0]==1)appStartLatch.countDown();
                    for(byte[] response:companionResponses(command,deviceName,(byte)0xa0)) {
                        out.write(new byte[]{0x3e,(byte)response.length,(byte)(response.length>>>8)});
                        out.write(response);
                    }
                    out.flush();
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void close() throws Exception {
            running = false;
            Socket socket = clientSocket;
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            serverSocket.close();
            acceptThread.join(2000);
        }
    }
}
