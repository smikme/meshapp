package com.meshtastic.client.service;

import com.google.protobuf.MessageLite;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.FrameParser;
import com.meshtastic.client.connection.KissFrameParser;
import com.meshtastic.client.connection.ble.BleDevice;
import com.meshtastic.client.connection.ble.BlePlatform;
import com.meshtastic.client.connection.ble.BlePlatform.AdapterState;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.connection.ble.BleState;
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
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

            // Идём через реальный TcpConnection и реальный ProtocolHandler/config exchange.
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
    void connectAutoDetectsMeshCoreKissRuntime() throws Exception {
        try (TcpMeshCoreKissStubServer server = new TcpMeshCoreKissStubServer("meshcore-test")) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry entry = new ConnectionEntry("meshcore", "127.0.0.1", server.port());
            manager.addEntry(entry);

            manager.connect(entry.getId());

            CompletableFuture<?> future = manager.getProtocolReadyFuture(entry.getId());
            assertNotNull(future);
            Object state = future.get(5, TimeUnit.SECONDS);

            assertInstanceOf(MeshCoreKissState.class, state);
            assertEquals(ProtocolType.MESHCORE_KISS, manager.getActiveProtocolType(entry.getId()));
            assertEquals("meshcore-test", manager.getMeshCoreKissState(entry.getId()).getDeviceName());
            assertTrue(server.awaitDeviceNameRequest());

            manager.disconnect(entry.getId());

            assertFalse(entry.isConnected());
            assertFalse(manager.hasActiveConnection());
        }
    }

    @Test
    void connectAutoDetectsMeshCoreCompanionRuntime() throws Exception {
        MeshCoreCompanionPlatform platform = new MeshCoreCompanionPlatform();
        installBlePlatform(platform);

        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("meshcore-companion", "AA:BB:CC:DD:EE:FF", "MeshCore Companion");
        manager.addEntry(entry);

        manager.connect(entry.getId());

        CompletableFuture<?> future = manager.getProtocolReadyFuture(entry.getId());
        assertNotNull(future);
        Object state = future.get(5, TimeUnit.SECONDS);

        assertInstanceOf(MeshCoreCompanionState.class, state);
        assertEquals(ProtocolType.MESHCORE_COMPANION, manager.getActiveProtocolType(entry.getId()));
        assertEquals("meshcore-companion-test", manager.getMeshCoreCompanionState(entry.getId()).getDeviceName());
        DeviceState uiState = manager.getDeviceState(entry.getId());
        assertNotNull(uiState);
        assertEquals("mc:a0a1a2a3a4a5", uiState.getOwnerNodeId());
        assertEquals("meshcore-companion-test", uiState.getOwnerInfo().getLongName());
        assertTrue(uiState.hasEnabledChannel(0));
        assertEquals(BleProtocolProfile.MESHCORE_COMPANION, platform.profile);
        assertTrue(platform.appStartReceived.await(1, TimeUnit.SECONDS));

        manager.disconnect(entry.getId());
    }

    @Test
    void connectAutoDetectsMeshCoreCompanionTcpRuntime() throws Exception {
        try (TcpMeshCoreCompanionStubServer server =
                     new TcpMeshCoreCompanionStubServer("meshcore-companion-tcp")) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry entry = new ConnectionEntry("meshcore-companion", "127.0.0.1", server.port());
            manager.addEntry(entry);

            manager.connect(entry.getId());

            CompletableFuture<?> future = manager.getProtocolReadyFuture(entry.getId());
            assertNotNull(future);
            Object state = future.get(5, TimeUnit.SECONDS);

            assertInstanceOf(MeshCoreCompanionState.class, state);
            assertEquals(ProtocolType.MESHCORE_COMPANION, manager.getActiveProtocolType(entry.getId()));
            assertEquals("meshcore-companion-tcp", manager.getMeshCoreCompanionState(entry.getId()).getDeviceName());
            DeviceState uiState = manager.getDeviceState(entry.getId());
            assertNotNull(uiState);
            assertEquals("mc:a0a1a2a3a4a5", uiState.getOwnerNodeId());
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
    void connectRejectsSecondActiveConnection() throws Exception {
        try (TcpMeshtasticStubServer serverA = new TcpMeshtasticStubServer(0x11111111);
             TcpMeshtasticStubServer serverB = new TcpMeshtasticStubServer(0x22222222)) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry first = new ConnectionEntry("one", "127.0.0.1", serverA.port());
            ConnectionEntry second = new ConnectionEntry("two", "127.0.0.1", serverB.port());
            manager.addEntry(first);
            manager.addEntry(second);

            manager.connect(first.getId());
            manager.getConfigFuture(first.getId()).get(5, TimeUnit.SECONDS);

            ConnectionException error = assertThrows(ConnectionException.class, () -> manager.connect(second.getId()));
            assertTrue(error.getMessage().contains("Уже есть активное подключение"));

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
    void disconnectForDeviceRebootKeepsReconnectEnabled() throws Exception {
        try (TcpMeshtasticStubServer server = new TcpMeshtasticStubServer(0xCAFEBABE)) {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry entry = new ConnectionEntry("stub", "127.0.0.1", server.port());
            manager.addEntry(entry);

            manager.connect(entry.getId());
            manager.getConfigFuture(entry.getId()).get(5, TimeUnit.SECONDS);

            manager.disconnectForDeviceReboot(entry.getId());

            assertFalse(entry.isConnected());
            assertTrue(entry.isReconnecting());
            assertFalse(manager.hasActiveConnection());
        }
    }

    @Test
    void removeEntryDoesNotBlockWhileBleConnectIsPending() throws Exception {
        BlockingBlePlatform platform = new BlockingBlePlatform();
        installBlePlatform(platform);

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

    private static void installBlePlatform(BlePlatform platform) throws Exception {
        BleDeviceDiscoveryService discovery = BleDeviceDiscoveryService.getInstance();
        Field platformField = BleDeviceDiscoveryService.class.getDeclaredField("platform");
        platformField.setAccessible(true);
        platformField.set(discovery, platform);
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
        public void setPasskeyRequestHandler(Consumer<String> handler) {
        }

        @Override
        public AdapterState getAdapterState() {
            return AdapterState.POWERED_ON;
        }

        @Override
        public void dispose() {
        }
    }

    private static final class MeshCoreCompanionPlatform implements BlePlatform {
        private final CountDownLatch appStartReceived = new CountDownLatch(1);
        private volatile Consumer<byte[]> fromRadioListener;
        private volatile Consumer<BleState> stateListener;
        private volatile BleProtocolProfile profile = BleProtocolProfile.MESHTASTIC;
        private volatile boolean connected;

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
            if (protobufPayload.length > 0
                    && (protobufPayload[0] & 0xFF) == MeshCoreCompanionFrames.CMD_APP_START) {
                appStartReceived.countDown();
                Consumer<byte[]> listener = fromRadioListener;
                if (listener != null) {
                    listener.accept(meshCoreSelfInfo("meshcore-companion-test"));
                }
            }
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
        public void setPasskeyRequestHandler(Consumer<String> handler) {
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
        public AdapterState getAdapterState() {
            return AdapterState.POWERED_ON;
        }

        @Override
        public void dispose() {
        }
    }

    private static byte[] meshCoreSelfInfo(String name) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[58 + nameBytes.length];
        packet[0] = (byte) MeshCoreCompanionFrames.PACKET_SELF_INFO;
        packet[1] = 1;
        packet[2] = 10;
        packet[3] = 20;
        for (int i = 0; i < 32; i++) {
            packet[4 + i] = (byte) (0xA0 + i);
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
                            // Минимальный happy-path для config exchange:
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
        private final CountDownLatch appStartLatch = new CountDownLatch(2);
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
                byte[] buffer = new byte[256];

                while (running) {
                    int read = in.read(buffer);
                    if (read < 0) {
                        return;
                    }
                    for (int i = 0; i < read; i++) {
                        int command = buffer[i] & 0xFF;
                        if (command == MeshCoreCompanionFrames.CMD_APP_START) {
                            appStartLatch.countDown();
                            out.write(meshCoreSelfInfo(deviceName));
                            out.flush();
                        } else if (command == MeshCoreCompanionFrames.CMD_DEVICE_QUERY && i + 1 < read && buffer[i + 1] == 0x03) {
                            out.write(meshCoreDeviceInfo());
                            out.flush();
                        } else if (command == MeshCoreCompanionFrames.CMD_GET_BATTERY) {
                            out.write(new byte[]{(byte) MeshCoreCompanionFrames.PACKET_BATTERY, (byte) 0x34, 0x12});
                            out.flush();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private byte[] meshCoreDeviceInfo() {
            byte[] packet = new byte[80];
            packet[0] = (byte) MeshCoreCompanionFrames.PACKET_DEVICE_INFO;
            packet[1] = 3;
            return packet;
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
