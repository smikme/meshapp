package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.MessageDbService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteAdminPanelTest {

    @TempDir
    Path tempHome;

    private final List<ProtocolHandler> handlersToShutdown = new ArrayList<>();
    private final List<DeviceState> statesToShutdown = new ArrayList<>();

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        MessageDbService.getInstance();
    }

    @AfterEach
    void tearDown() {
        for (ProtocolHandler handler : handlersToShutdown) {
            handler.shutdown();
        }
        for (DeviceState state : statesToShutdown) {
            state.shutdown();
        }
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void opensWithSectionCatalogWithoutSendingMeshRequests() {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState localState = track(stateWithLocalNode(0x11111111));
        NodeData remoteNode = remoteNode(0x22222222);

        RemoteAdminPanel panel = onFxThread(() -> {
            RemoteAdminPanel created = new RemoteAdminPanel(localState, remoteNode, handler);
            StackPane root = new StackPane(created);
            Scene scene = new Scene(root, 900, 700);
            assertNotNull(scene);
            root.applyCss();
            root.layout();
            return created;
        });

        try {
            assertEquals(0, connection.sentFrameCount());
            assertTrue(onFxThread(() -> {
                TreeTableView<?> tree = findFirst(panel, TreeTableView.class);
                assertNotNull(tree);
                return countActionRows(tree.getRoot()) > 0;
            }));
        } finally {
            onFxThread(() -> {
                panel.close();
                return null;
            });
        }
    }

    private ProtocolHandler track(ProtocolHandler handler) {
        handlersToShutdown.add(handler);
        return handler;
    }

    private DeviceState track(DeviceState state) {
        statesToShutdown.add(state);
        return state;
    }

    private static DeviceState stateWithLocalNode(int nodeNum) {
        DeviceState state = new DeviceState();
        state.setMyNodeNum(nodeNum);
        state.getOrCreateNode(nodeNum).setLongName("Local");
        return state;
    }

    private static NodeData remoteNode(int nodeNum) {
        NodeData node = new NodeData(nodeNum);
        node.setLongName("Remote");
        node.setShortName("RMT");
        node.setPublicKey(new byte[] {1, 2, 3});
        return node;
    }

    private static long countActionRows(TreeItem<?> item) {
        if (item == null) {
            return 0;
        }
        long ownCount = item.getValue() instanceof ConfigTreeItem config && config.hasAction() ? 1 : 0;
        return ownCount + item.getChildren().stream().mapToLong(RemoteAdminPanelTest::countActionRows).sum();
    }

    private static <T> T findFirst(javafx.scene.Node root, Class<T> type) {
        return root.lookupAll("*").stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElse(null);
    }

    private static <T> T onFxThread(FxSupplier<T> supplier) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });

        await(latch);
        if (failure.get() != null) {
            throw new AssertionError("JavaFX task failed", failure.get());
        }
        return result.get();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for JavaFX task");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for JavaFX task", e);
        }
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }

    private static final class RecordingConnection implements MeshtasticConnection {
        private final List<byte[]> sentFrames = new ArrayList<>();

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
            synchronized (sentFrames) {
                sentFrames.add(data);
            }
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            // no-op
        }

        @Override
        public void setConnectionListener(ConnectionListener listener) {
            // no-op
        }

        int sentFrameCount() {
            synchronized (sentFrames) {
                return sentFrames.size();
            }
        }
    }
}
