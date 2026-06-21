package com.meshtastic.client.rpc;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class RpcClientServerTest {

    private final Executor directExecutor = Runnable::run;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable closeable : closeables.reversed()) {
            closeable.close();
        }
    }

    @Test
    void clientCallsRegisteredServerMethod() throws Exception {
        RpcMethodRegistry registry = new RpcMethodRegistry()
                .register("math.add", (params, context) -> {
                    JsonObject result = new JsonObject();
                    result.addProperty("sum", params.get("a").getAsInt() + params.get("b").getAsInt());
                    result.addProperty("requestId", context.requestId());
                    return CompletableFuture.completedFuture(result);
                });
        RpcClient client = createClientWithServer(registry);

        JsonObject params = new JsonObject();
        params.addProperty("a", 2);
        params.addProperty("b", 5);

        JsonObject result = client.call("math.add", params)
                .get(1, TimeUnit.SECONDS)
                .getAsJsonObject();

        assertEquals(7, result.get("sum").getAsInt());
        assertTrue(result.get("requestId").getAsString().length() > 10);
    }

    @Test
    void unknownMethodReturnsStructuredError() throws Exception {
        RpcClient client = createClientWithServer(new RpcMethodRegistry());

        CompletableFuture<?> future = client.call("missing.method", new JsonObject());

        Throwable error = captureFutureError(future);
        RpcRemoteException remoteError = assertInstanceOf(RpcRemoteException.class, error);
        assertEquals("METHOD_NOT_FOUND", remoteError.getCode());
    }

    @Test
    void serverPublishesEventsToClient() throws Exception {
        InMemoryRpcTransport.Pair pair = InMemoryRpcTransport.createPair(directExecutor);
        RpcClient client = new RpcClient(pair.first());
        RpcServer server = new RpcServer(pair.second(), new RpcMethodRegistry(), directExecutor);
        closeables.add(client);
        closeables.add(server);

        CompletableFuture<JsonObject> eventFuture = new CompletableFuture<>();
        client.addEventListener((event, payload) -> {
            if ("message.created".equals(event)) {
                eventFuture.complete(payload.getAsJsonObject());
            }
        });

        JsonObject payload = new JsonObject();
        payload.addProperty("text", "hello");
        server.publishEvent("message.created", payload);

        JsonObject received = eventFuture.get(1, TimeUnit.SECONDS);
        assertEquals("hello", received.get("text").getAsString());
    }

    @Test
    void directClientCallsDirectServerWithoutRouter() throws Exception {
        RpcAccessKey accessKey = RpcAccessKey.generate();
        RpcMethodRegistry registry = new RpcMethodRegistry()
                .register("system.ping", (params, context) -> {
                    JsonObject result = new JsonObject();
                    result.addProperty("pong", true);
                    return CompletableFuture.completedFuture(result);
                });
        DirectRpcServer server = DirectRpcServer.start(
                InetAddress.getLoopbackAddress(),
                0,
                accessKey,
                registry,
                directExecutor);
        closeables.add(server);
        DirectRpcClient client = DirectRpcClient.connect(
                InetAddress.getLoopbackAddress().getHostAddress(),
                server.getPort(),
                accessKey,
                Duration.ofSeconds(1));
        closeables.add(client);

        JsonObject result = client.call("system.ping", new JsonObject())
                .get(1, TimeUnit.SECONDS)
                .getAsJsonObject();

        assertTrue(result.get("pong").getAsBoolean());

        CompletableFuture<String> eventFuture = new CompletableFuture<>();
        client.addEventListener((event, payload) -> {
            if ("system.notice".equals(event)) {
                eventFuture.complete(payload.getAsJsonObject().get("text").getAsString());
            }
        });
        JsonObject payload = new JsonObject();
        payload.addProperty("text", "direct");
        server.publishEvent("system.notice", payload);
        assertEquals("direct", eventFuture.get(1, TimeUnit.SECONDS));
    }

    @Test
    void directClientRejectsWrongAccessKey() throws Exception {
        RpcAccessKey accessKey = RpcAccessKey.generate();
        DirectRpcServer server = DirectRpcServer.start(
                InetAddress.getLoopbackAddress(),
                0,
                accessKey,
                new RpcMethodRegistry(),
                directExecutor);
        closeables.add(server);

        IOException error = assertThrows(IOException.class, () -> DirectRpcClient.connect(
                InetAddress.getLoopbackAddress().getHostAddress(),
                server.getPort(),
                RpcAccessKey.generate(),
                Duration.ofSeconds(1)));

        assertTrue(error.getMessage().contains("invalid access key"));
    }

    @Test
    void secureSessionCipherEncryptsFramesAndRejectsWrongKey() throws Exception {
        RpcAccessKey accessKey = RpcAccessKey.generate();
        String serverNonce = RpcAccessKey.newNonce();
        String clientNonce = RpcAccessKey.newNonce();
        RpcSessionCipher clientCipher = RpcSessionCipher.client(accessKey, serverNonce, clientNonce);
        RpcSessionCipher serverCipher = RpcSessionCipher.server(accessKey, serverNonce, clientNonce);

        String plaintext = "{\"type\":\"rpc_request\",\"method\":\"chat.messages\"}";
        String frame = clientCipher.encrypt(plaintext);

        assertTrue(frame.startsWith("enc1_"));
        assertTrue(!frame.contains("rpc_request"));
        assertTrue(!frame.contains("chat.messages"));
        assertEquals(plaintext, serverCipher.decrypt(frame));

        RpcSessionCipher wrongServerCipher = RpcSessionCipher.server(
                RpcAccessKey.generate(),
                serverNonce,
                clientNonce);
        IOException error = assertThrows(IOException.class, () -> wrongServerCipher.decrypt(frame));
        assertTrue(error.getMessage().contains("authentication failed"));
    }

    @Test
    void clientCallTimesOutWhenServerDoesNotRespond() throws Exception {
        InMemoryRpcTransport.Pair pair = InMemoryRpcTransport.createPair(command -> {});
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RpcClient client = new RpcClient(pair.first(), scheduler);
        closeables.add(client);
        closeables.add(scheduler::shutdownNow);
        closeables.add(pair.second());

        CompletableFuture<?> future = client.call("system.ping", new JsonObject(), Duration.ofMillis(25));

        Throwable error = captureFutureError(future);
        RpcRemoteException remoteError = assertInstanceOf(RpcRemoteException.class, error);
        assertEquals("TIMEOUT", remoteError.getCode());
    }

    private RpcClient createClientWithServer(RpcMethodRegistry registry) {
        InMemoryRpcTransport.Pair pair = InMemoryRpcTransport.createPair(directExecutor);
        RpcClient client = new RpcClient(pair.first());
        RpcServer server = new RpcServer(pair.second(), registry, directExecutor);
        closeables.add(client);
        closeables.add(server);
        return client;
    }

    private static Throwable captureFutureError(CompletableFuture<?> future) throws Exception {
        try {
            future.get(1, TimeUnit.SECONDS);
            throw new AssertionError("Future completed successfully");
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        }
    }
}
