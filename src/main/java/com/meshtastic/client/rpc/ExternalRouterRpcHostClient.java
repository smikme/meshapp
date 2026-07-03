package com.meshtastic.client.rpc;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maintains MeshApp Host's outgoing connection to an External RPC Router.
 * <p>
 * The host keeps one router WebSocket open, authenticates every remote client
 * session with the MeshApp RPC access key, decrypts authenticated RPC frames,
 * and dispatches them against the local host registry.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ExternalRouterRpcHostClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExternalRouterRpcHostClient.class);
    private static final Gson GSON = new Gson();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration RECONNECT_INITIAL_DELAY = Duration.ofSeconds(2);
    private static final Duration RECONNECT_MAX_DELAY = Duration.ofSeconds(30);
    private static final Duration ROUTER_PING_INTERVAL = Duration.ofSeconds(15);
    private static final Duration ROUTER_PING_TIMEOUT = Duration.ofSeconds(45);

    private final String server;
    private final RpcAccessKey accessKey;
    private final RpcDispatcher dispatcher;
    private final ScheduledExecutorService scheduler;
    private final Runnable statusListener;
    private final ConcurrentMap<String, RouterRemoteSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile ExternalRouterWebSocket webSocket;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile ScheduledFuture<?> pingTask;
    private volatile int reconnectAttempt;
    private volatile String lastError;
    private volatile long lastRouterPongNanos = System.nanoTime();

    /**
     * Creates a host router client using the common pool for RPC method calls.
     *
     * @param server router server address
     * @param accessKey shared MeshApp RPC access key
     * @param registry host RPC method registry
     */
    public ExternalRouterRpcHostClient(String server,
                                       RpcAccessKey accessKey,
                                       RpcMethodRegistry registry) {
        this(server, accessKey, registry, java.util.concurrent.ForkJoinPool.commonPool());
    }

    /**
     * Creates a host router client.
     *
     * @param server router server address
     * @param accessKey shared MeshApp RPC access key
     * @param registry host RPC method registry
     * @param methodExecutor executor used for RPC method invocations
     */
    public ExternalRouterRpcHostClient(String server,
                                       RpcAccessKey accessKey,
                                       RpcMethodRegistry registry,
                                       java.util.concurrent.Executor methodExecutor) {
        this(server, accessKey, registry, methodExecutor, () -> {});
    }

    /**
     * Creates a host router client.
     *
     * @param server router server address
     * @param accessKey shared MeshApp RPC access key
     * @param registry host RPC method registry
     * @param methodExecutor executor used for RPC method invocations
     * @param statusListener callback invoked when router status may have changed
     */
    public ExternalRouterRpcHostClient(String server,
                                       RpcAccessKey accessKey,
                                       RpcMethodRegistry registry,
                                       java.util.concurrent.Executor methodExecutor,
                                       Runnable statusListener) {
        this.server = requireText(server, "server");
        this.accessKey = Objects.requireNonNull(accessKey, "accessKey");
        this.dispatcher = new RpcDispatcher(
                Objects.requireNonNull(registry, "registry"),
                Objects.requireNonNull(methodExecutor, "methodExecutor"));
        this.statusListener = Objects.requireNonNull(statusListener, "statusListener");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "erpc-router-host");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts the router connection loop.
     */
    public void start() {
        if (closed.get()) {
            return;
        }
        scheduler.execute(this::connectNow);
    }

    /**
     * @return whether the host currently has an open router channel
     */
    public boolean isConnected() {
        ExternalRouterWebSocket current = webSocket;
        return current != null && current.isOpen();
    }

    /**
     * @return last router connection error, if any
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Publishes one host event to every authenticated remote client connected
     * through the router.
     */
    public void publishEvent(String event, JsonElement payload) {
        JsonObject envelope = RpcDispatcher.eventEnvelope(event, payload);
        for (RouterRemoteSession session : sessions.values()) {
            session.sendRpcEnvelope(envelope);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> reconnect = reconnectTask;
        if (reconnect != null) {
            reconnect.cancel(false);
        }
        ScheduledFuture<?> ping = pingTask;
        if (ping != null) {
            ping.cancel(false);
        }
        sessions.clear();
        ExternalRouterWebSocket current = webSocket;
        webSocket = null;
        if (current != null) {
            current.close();
        }
        scheduler.shutdownNow();
    }

    private void connectNow() {
        if (closed.get()) {
            return;
        }
        try {
            log.info("Connecting MeshApp Host to ERPC Router {}", server);
            ExternalRouterWebSocket connection = ExternalRouterWebSocket.connect(
                    ExternalRouterAddress.hostUri(server, accessKey),
                    CONNECT_TIMEOUT,
                    this::handleRouterMessage,
                    this::handleRouterError,
                    this::handleRouterClosed);
            webSocket = connection;
            reconnectAttempt = 0;
            lastError = null;
            lastRouterPongNanos = System.nanoTime();
            startRouterPing();
            log.info("MeshApp Host connected to ERPC Router {}", server);
            fireStatusChanged();
        } catch (Exception e) {
            lastError = message(e);
            log.warn("Failed to connect MeshApp Host to ERPC Router {}: {}", server, lastError);
            fireStatusChanged();
            scheduleReconnect();
        }
    }

    private void handleRouterMessage(JsonObject message) {
        String type = text(message, "type");
        switch (type) {
            case "host_ready" -> {
                lastError = null;
                lastRouterPongNanos = System.nanoTime();
            }
            case "router_pong" -> lastRouterPongNanos = System.nanoTime();
            case "client_joined" -> handleClientJoined(text(message, "clientSessionId"));
            case "client_disconnected" -> sessions.remove(text(message, "clientSessionId"));
            case "client_frame" -> handleClientFrame(text(message, "clientSessionId"), payloadObject(message));
            case "router_error" -> log.warn("ERPC Router reported error: {}", text(message, "message"));
            default -> log.debug("Ignoring ERPC Router host frame type '{}'", type);
        }
    }

    private void handleClientJoined(String clientSessionId) {
        if (clientSessionId.isBlank()) {
            return;
        }
        RouterRemoteSession session = new RouterRemoteSession(clientSessionId);
        sessions.put(clientSessionId, session);
        session.sendAuthChallenge();
    }

    private void handleClientFrame(String clientSessionId, JsonObject payload) {
        if (clientSessionId.isBlank()) {
            return;
        }
        RouterRemoteSession session = sessions.computeIfAbsent(clientSessionId, RouterRemoteSession::new);
        session.handlePayload(payload);
    }

    private void startRouterPing() {
        ScheduledFuture<?> previous = pingTask;
        if (previous != null) {
            previous.cancel(false);
        }
        pingTask = scheduler.scheduleAtFixedRate(
                this::sendRouterPing,
                ROUTER_PING_INTERVAL.toMillis(),
                ROUTER_PING_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void sendRouterPing() {
        if (closed.get() || !isConnected()) {
            return;
        }
        long silenceMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastRouterPongNanos);
        if (silenceMs > ROUTER_PING_TIMEOUT.toMillis()) {
            handleRouterError("ERPC Router channel healthcheck timed out", null);
            ExternalRouterWebSocket current = webSocket;
            if (current != null) {
                current.close();
            }
            return;
        }
        JsonObject ping = new JsonObject();
        ping.addProperty("type", "router_ping");
        sendRouterFrame(ping);
    }

    private void handleRouterError(String message, Throwable error) {
        lastError = message != null && !message.isBlank() ? message : "ERPC Router channel failed";
        if (error != null) {
            log.warn("ERPC Router channel error: {}", lastError, error);
        } else {
            log.warn("ERPC Router channel error: {}", lastError);
        }
        fireStatusChanged();
    }

    private void handleRouterClosed() {
        if (closed.get()) {
            return;
        }
        webSocket = null;
        sessions.clear();
        ScheduledFuture<?> ping = pingTask;
        if (ping != null) {
            ping.cancel(false);
        }
        log.warn("MeshApp Host disconnected from ERPC Router {}", server);
        fireStatusChanged();
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed.get()) {
            return;
        }
        ScheduledFuture<?> existing = reconnectTask;
        if (existing != null && !existing.isDone()) {
            return;
        }
        long multiplier = 1L << Math.min(reconnectAttempt, 4);
        long delayMs = Math.min(
                RECONNECT_INITIAL_DELAY.toMillis() * multiplier,
                RECONNECT_MAX_DELAY.toMillis());
        reconnectAttempt++;
        reconnectTask = scheduler.schedule(this::connectNow, delayMs, TimeUnit.MILLISECONDS);
    }

    private void sendRouterFrame(JsonObject envelope) {
        ExternalRouterWebSocket current = webSocket;
        if (current == null || !current.isOpen()) {
            return;
        }
        current.send(envelope);
    }

    private static JsonObject payloadObject(JsonObject message) {
        JsonElement payload = message.get("payload");
        return payload != null && payload.isJsonObject() ? payload.getAsJsonObject() : new JsonObject();
    }

    private static String text(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        return element.getAsString();
    }

    private static String requireText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return normalized;
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (current != null && current.getMessage() != null && !current.getMessage().isBlank()) {
            return current.getMessage();
        }
        return error != null ? error.toString() : "unknown error";
    }

    private void fireStatusChanged() {
        try {
            statusListener.run();
        } catch (RuntimeException e) {
            log.warn("External RPC Router status listener failed", e);
        }
    }

    private final class RouterRemoteSession {
        private final String clientSessionId;
        private final String serverNonce = RpcAccessKey.newNonce();
        private final Object outboundLock = new Object();
        private volatile RpcSessionCipher cipher;
        private volatile boolean authenticated;

        private RouterRemoteSession(String clientSessionId) {
            this.clientSessionId = clientSessionId;
        }

        private void sendAuthChallenge() {
            JsonObject challenge = new JsonObject();
            challenge.addProperty("type", "auth_challenge");
            challenge.addProperty("version", 2);
            challenge.addProperty("cipher", "AES-256-GCM");
            challenge.addProperty("nonce", serverNonce);
            sendPlainControl(challenge);
        }

        private void handlePayload(JsonObject payload) {
            String frame = text(payload, "frame");
            if (frame.isBlank()) {
                return;
            }
            if (!authenticated) {
                try {
                    handleAuthFrame(frame);
                } catch (RuntimeException e) {
                    log.warn("Dropping ERPC Router client {} after invalid auth frame", clientSessionId, e);
                    sessions.remove(clientSessionId, this);
                }
                return;
            }
            try {
                String plaintext = cipher.decrypt(frame);
                dispatcher.dispatch(plaintext, this::sendRpcEnvelope);
            } catch (IOException e) {
                log.warn("Dropping ERPC Router client {} after invalid encrypted frame", clientSessionId, e);
                sessions.remove(clientSessionId, this);
            }
        }

        private void handleAuthFrame(String frame) {
            JsonObject response = JsonParser.parseString(frame).getAsJsonObject();
            String type = text(response, "type");
            String clientNonce = text(response, "clientNonce");
            String proof = text(response, "proof");
            if (!"auth_response".equals(type) || !accessKey.verifyClientProof(serverNonce, clientNonce, proof)) {
                JsonObject denied = new JsonObject();
                denied.addProperty("type", "auth_error");
                denied.addProperty("message", "invalid access key");
                sendPlainControl(denied);
                sessions.remove(clientSessionId, this);
                return;
            }

            JsonObject ok = new JsonObject();
            ok.addProperty("type", "auth_ok");
            ok.addProperty("version", 2);
            ok.addProperty("cipher", "AES-256-GCM");
            ok.addProperty("proof", accessKey.serverProof(serverNonce, clientNonce));
            cipher = RpcSessionCipher.server(accessKey, serverNonce, clientNonce);
            authenticated = true;
            sendPlainControl(ok);
            log.info("ERPC Router remote client authenticated: {}", clientSessionId);
        }

        private void sendPlainControl(JsonObject control) {
            sendFrame(GSON.toJson(control));
        }

        private void sendRpcEnvelope(JsonObject envelope) {
            synchronized (outboundLock) {
                RpcSessionCipher currentCipher = cipher;
                if (!authenticated || currentCipher == null) {
                    return;
                }
                sendFrame(currentCipher.encrypt(RpcDispatcher.toJson(envelope)));
            }
        }

        private void sendFrame(String frame) {
            JsonObject payload = new JsonObject();
            payload.addProperty("frame", frame);
            JsonObject envelope = new JsonObject();
            envelope.addProperty("type", "host_frame");
            envelope.addProperty("clientSessionId", clientSessionId);
            envelope.add("payload", payload);
            sendRouterFrame(envelope);
        }
    }
}
