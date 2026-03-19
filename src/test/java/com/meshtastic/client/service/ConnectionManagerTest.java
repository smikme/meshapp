package com.meshtastic.client.service;

import com.google.protobuf.MessageLite;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.FrameParser;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.PacketFramer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.MeshProtos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
