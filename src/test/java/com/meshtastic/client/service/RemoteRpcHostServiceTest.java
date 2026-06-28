package com.meshtastic.client.service;

import com.google.gson.JsonObject;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageChangeEvent;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.rpc.DirectRpcClient;
import com.meshtastic.client.rpc.RpcAccessKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class RemoteRpcHostServiceTest {

    private static final int START_AND_STOP_STATUS_NOTIFICATION_COUNT = 2;
    private static final int ROUTER_START_STATUS_NOTIFICATION_COUNT = 1;

    @Test
    void notifiesStatusListenersWhenHostStatusChanges(@TempDir Path tempHome) {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();

        RemoteRpcHostService host = RemoteRpcHostService.getInstance();
        AtomicInteger notifications = new AtomicInteger();
        Runnable listener = notifications::incrementAndGet;
        try {
            host.addStatusListener(listener);

            host.start("127.0.0.1", 0, RpcAccessKey.generate().value());
            host.stop();

            assertTrue(notifications.get() >= START_AND_STOP_STATUS_NOTIFICATION_COUNT);
        } finally {
            host.removeStatusListener(listener);
            host.stop();
            TestEnvironmentSupport.resetSingletons();
        }
    }

    @Test
    void startsRouterConnectorWhenRequestedWithDirectHost(@TempDir Path tempHome) {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();

        RemoteRpcHostService host = RemoteRpcHostService.getInstance();
        AtomicInteger notifications = new AtomicInteger();
        Runnable listener = notifications::incrementAndGet;
        try {
            host.addStatusListener(listener);

            host.start(
                    "127.0.0.1",
                    0,
                    RpcAccessKey.generate().value(),
                    true,
                    "127.0.0.1:1");

            assertTrue(host.isRunning());
            assertTrue(notifications.get() >= ROUTER_START_STATUS_NOTIFICATION_COUNT);
            assertTrue(waitForRouterError(host));
        } finally {
            host.removeStatusListener(listener);
            host.stop();
            TestEnvironmentSupport.resetSingletons();
        }
    }

    @Test
    void publishesMessageStatusEventsToRemoteClient(@TempDir Path tempHome) throws Exception {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();

        RpcAccessKey accessKey = RpcAccessKey.generate();
        RemoteRpcHostService host = RemoteRpcHostService.getInstance();
        DirectRpcClient client = null;
        try {
            host.start("127.0.0.1", 0, accessKey.value());
            client = DirectRpcClient.connect("127.0.0.1", host.getPort(), accessKey, Duration.ofSeconds(1));

            CompletableFuture<JsonObject> eventFuture = new CompletableFuture<>();
            client.addEventListener((event, payload) -> {
                if ("message.status".equals(event)) {
                    eventFuture.complete(payload.getAsJsonObject());
                }
            });

            MeshMessage message = new MeshMessage("!11111111", "!ffffffff", 0, "sent", 20, true);
            message.setPacketId(12345);
            message.setDbId(77);
            message.setStatus(MeshMessage.DeliveryStatus.DELIVERED);
            host.publishMessageStatusChanged(MessageChangeEvent.statusChanged(
                    "channel",
                    "0",
                    "!11111111",
                    message));

            JsonObject event = eventFuture.get(2, TimeUnit.SECONDS);
            assertEquals("channel", event.get("chatType").getAsString());
            assertEquals("0", event.get("chatKey").getAsString());
            assertEquals(12345, event.get("packetId").getAsInt());
            assertEquals("DELIVERED", event.get("status").getAsString());
            assertEquals("DELIVERED", event.getAsJsonObject("message").get("status").getAsString());
        } finally {
            if (client != null) {
                client.close();
            }
            host.stop();
            TestEnvironmentSupport.resetSingletons();
        }
    }

    @Test
    void resolvesRemoteDmNamesFromNodeCache(@TempDir Path tempHome) throws Exception {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        try {
            NodeData cached = new NodeData(0x1ba3b8c4);
            cached.setNodeId("!1ba3b8c4");
            cached.setLongName("Jox (Base)");
            cached.setShortName("JOX");
            NodeCacheService.getInstance().update(cached);

            Method method = RemoteRpcHostService.class
                    .getDeclaredMethod("resolvePeerNode", DeviceState.class, String.class);
            method.setAccessible(true);
            NodeData resolved = (NodeData) method.invoke(null, new DeviceState(), "!1ba3b8c4");

            assertNotNull(resolved);
            assertEquals("Jox (Base)", resolved.getLongName());
            assertEquals("JOX", resolved.getShortName());
        } finally {
            TestEnvironmentSupport.resetSingletons();
        }
    }

    private static boolean waitForRouterError(RemoteRpcHostService host) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            String error = host.getLastRouterError();
            if (error != null && !error.isBlank()) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
