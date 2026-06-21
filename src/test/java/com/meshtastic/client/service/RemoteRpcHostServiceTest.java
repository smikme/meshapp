package com.meshtastic.client.service;

import com.google.gson.JsonObject;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.MessageChangeEvent;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.rpc.DirectRpcClient;
import com.meshtastic.client.rpc.RpcAccessKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class RemoteRpcHostServiceTest {

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
}
