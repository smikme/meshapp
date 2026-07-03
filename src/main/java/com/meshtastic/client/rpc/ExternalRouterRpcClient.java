package com.meshtastic.client.rpc;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * RPC client connected through an External RPC Router.
 * <p>
 * The router is used only as a rendezvous and frame relay. Authentication and
 * encryption are performed end-to-end between the remote client and MeshApp
 * Host with the shared {@link RpcAccessKey}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ExternalRouterRpcClient implements AutoCloseable {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration ROUTER_PING_INTERVAL = Duration.ofSeconds(15);
    private static final Duration ROUTER_PING_TIMEOUT = Duration.ofSeconds(45);

    private final ExternalRouterRpcTransport transport;
    private final RpcClient client;

    private ExternalRouterRpcClient(ExternalRouterRpcTransport transport, RpcClient client) {
        this.transport = transport;
        this.client = client;
    }

    /**
     * Connects to a MeshApp Host through an External RPC Router.
     *
     * @param server router server address
     * @param port router fallback port when the server address has no port
     * @param accessKey shared MeshApp RPC access key
     * @param timeout connect and authentication timeout
     * @param closedHandler callback invoked when the router transport closes
     * @param errorHandler callback invoked for router transport errors
     * @return authenticated router RPC client
     * @throws IOException when the WebSocket connection or key authentication fails
     */
    public static ExternalRouterRpcClient connect(String server,
                                                  int port,
                                                  RpcAccessKey accessKey,
                                                  Duration timeout,
                                                  Runnable closedHandler,
                                                  BiConsumer<String, Throwable> errorHandler) throws IOException {
        ExternalRouterRpcTransport transport = ExternalRouterRpcTransport.connect(
                server,
                port,
                accessKey,
                timeout != null ? timeout : DEFAULT_CONNECT_TIMEOUT,
                closedHandler,
                errorHandler);
        return new ExternalRouterRpcClient(transport, new RpcClient(transport));
    }

    /**
     * Returns the generic MeshApp RPC client bound to this router transport.
     *
     * @return RPC client
     */
    public RpcClient rpcClient() {
        return client;
    }

    /**
     * @return {@code true} while the authenticated router transport can send RPC frames
     */
    public boolean isOpen() {
        return transport.isOpen();
    }

    @Override
    public void close() {
        client.close();
        transport.close();
    }

    private static final class ExternalRouterRpcTransport implements RpcTransport {

        private static final Gson GSON = new Gson();

        private final RpcAccessKey accessKey;
        private final CompletableFuture<Void> authenticated = new CompletableFuture<>();
        private final Runnable closedHandler;
        private final BiConsumer<String, Throwable> errorHandler;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final Object outboundLock = new Object();
        private final ScheduledExecutorService scheduler;

        private volatile ExternalRouterWebSocket webSocket;
        private volatile RpcSessionCipher sessionCipher;
        private volatile RpcTransportListener listener;
        private volatile String serverNonce;
        private volatile String clientNonce;
        private volatile ScheduledFuture<?> pingTask;
        private volatile long lastRouterPongNanos = System.nanoTime();

        private ExternalRouterRpcTransport(RpcAccessKey accessKey,
                                           Runnable closedHandler,
                                           BiConsumer<String, Throwable> errorHandler) {
            this.accessKey = Objects.requireNonNull(accessKey, "accessKey");
            this.closedHandler = closedHandler != null ? closedHandler : () -> {};
            this.errorHandler = errorHandler != null ? errorHandler : (message, error) -> {};
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "erpc-router-client");
                thread.setDaemon(true);
                return thread;
            });
        }

        private static ExternalRouterRpcTransport connect(String server,
                                                          int port,
                                                          RpcAccessKey accessKey,
                                                          Duration timeout,
                                                          Runnable closedHandler,
                                                          BiConsumer<String, Throwable> errorHandler) throws IOException {
            ExternalRouterRpcTransport transport = new ExternalRouterRpcTransport(
                    accessKey,
                    closedHandler,
                    errorHandler);
            transport.webSocket = ExternalRouterWebSocket.connect(
                    ExternalRouterAddress.clientUri(server, port, accessKey),
                    timeout,
                    transport::handleRouterMessage,
                    transport::handleRouterError,
                    transport::handleRouterClosed);
            try {
                transport.authenticated.orTimeout(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS).join();
                transport.startRouterPing();
            } catch (RuntimeException e) {
                transport.close();
                throw new IOException("Failed to authenticate through ERPC Router", rootCause(e));
            }
            return transport;
        }

        @Override
        public void setListener(RpcTransportListener listener) {
            this.listener = listener;
        }

        @Override
        public void send(String message) {
            if (!isOpen()) {
                throw new IllegalStateException("ERPC Router RPC transport is closed");
            }
            RpcSessionCipher cipher = sessionCipher;
            if (cipher == null) {
                throw new IllegalStateException("ERPC Router RPC transport is not authenticated");
            }
            synchronized (outboundLock) {
                sendFrame(cipher.encrypt(Objects.requireNonNull(message, "message")));
            }
        }

        @Override
        public boolean isOpen() {
            ExternalRouterWebSocket current = webSocket;
            return open.get() && current != null && current.isOpen();
        }

        @Override
        public void close() {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            ExternalRouterWebSocket current = webSocket;
            webSocket = null;
            if (current != null) {
                current.close();
            }
            stopRouterPing();
            RpcTransportListener currentListener = listener;
            if (currentListener != null) {
                currentListener.onClosed();
            }
        }

        private void handleRouterMessage(JsonObject message) {
            String type = text(message, "type");
            switch (type) {
                case "host_frame", "broadcast" -> handleHostFrame(payloadObject(message));
                case "client_ready", "host_connected", "router_pong" -> lastRouterPongNanos = System.nanoTime();
                case "host_disconnected" -> failAndClose("ERPC Router host disconnected", null);
                case "router_error" -> failAndClose(text(message, "message"), null);
                default -> {
                    // Authentication starts when the host sends auth_challenge.
                }
            }
        }

        private void handleHostFrame(JsonObject payload) {
            String frame = text(payload, "frame");
            if (frame.isBlank()) {
                return;
            }
            RpcSessionCipher cipher = sessionCipher;
            if (cipher == null) {
                try {
                    handleAuthFrame(frame);
                } catch (RuntimeException e) {
                    failAndClose("Invalid ERPC Router auth frame", e);
                }
                return;
            }
            try {
                String plaintext = cipher.decrypt(frame);
                RpcTransportListener currentListener = listener;
                if (currentListener != null) {
                    currentListener.onMessage(plaintext);
                }
            } catch (IOException e) {
                failAndClose("ERPC Router encrypted frame authentication failed", e);
            }
        }

        private void handleAuthFrame(String frame) {
            JsonObject control = JsonParser.parseString(frame).getAsJsonObject();
            String type = text(control, "type");
            if ("auth_challenge".equals(type)) {
                int version = control.has("version") ? control.get("version").getAsInt() : 0;
                if (version != 2) {
                    failAndClose("Unsupported ERPC Router auth version", null);
                    return;
                }
                serverNonce = text(control, "nonce");
                clientNonce = RpcAccessKey.newNonce();
                JsonObject response = new JsonObject();
                response.addProperty("type", "auth_response");
                response.addProperty("version", 2);
                response.addProperty("clientNonce", clientNonce);
                response.addProperty("proof", accessKey.clientProof(serverNonce, clientNonce));
                sendFrame(GSON.toJson(response));
                return;
            }
            if ("auth_ok".equals(type)) {
                String proof = text(control, "proof");
                if (!accessKey.verifyServerProof(serverNonce, clientNonce, proof)) {
                    failAndClose("ERPC Router host authentication failed", null);
                    return;
                }
                sessionCipher = RpcSessionCipher.client(accessKey, serverNonce, clientNonce);
                authenticated.complete(null);
                return;
            }
            if ("auth_error".equals(type)) {
                failAndClose(text(control, "message"), null);
            }
        }

        private void sendFrame(String frame) {
            JsonObject payload = new JsonObject();
            payload.addProperty("frame", frame);
            JsonObject envelope = new JsonObject();
            envelope.add("payload", payload);
            sendRouterFrame(envelope);
        }

        private void startRouterPing() {
            ScheduledFuture<?> previous = pingTask;
            if (previous != null) {
                previous.cancel(false);
            }
            lastRouterPongNanos = System.nanoTime();
            pingTask = scheduler.scheduleAtFixedRate(
                    this::sendRouterPing,
                    ROUTER_PING_INTERVAL.toMillis(),
                    ROUTER_PING_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS);
        }

        private void sendRouterPing() {
            if (!isOpen()) {
                return;
            }
            long silenceMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastRouterPongNanos);
            if (silenceMs > ROUTER_PING_TIMEOUT.toMillis()) {
                failAndClose("ERPC Router channel healthcheck timed out", null);
                return;
            }
            JsonObject ping = new JsonObject();
            ping.addProperty("type", "router_ping");
            sendRouterFrame(ping);
        }

        private void stopRouterPing() {
            ScheduledFuture<?> ping = pingTask;
            if (ping != null) {
                ping.cancel(false);
            }
            scheduler.shutdownNow();
        }

        private void sendRouterFrame(JsonObject envelope) {
            ExternalRouterWebSocket current = webSocket;
            if (current == null) {
                throw new IllegalStateException("ERPC Router WebSocket is closed");
            }
            current.send(envelope);
        }

        private void handleRouterError(String message, Throwable error) {
            failAndClose(message, error);
        }

        private void handleRouterClosed() {
            if (!open.get()) {
                return;
            }
            open.set(false);
            stopRouterPing();
            authenticated.completeExceptionally(new IOException("ERPC Router WebSocket closed"));
            RpcTransportListener currentListener = listener;
            if (currentListener != null) {
                currentListener.onClosed();
            }
            closedHandler.run();
        }

        private void failAndClose(String message, Throwable error) {
            String safeMessage = message == null || message.isBlank()
                    ? "ERPC Router transport failed"
                    : message;
            authenticated.completeExceptionally(error != null ? error : new IOException(safeMessage));
            errorHandler.accept(safeMessage, error);
            RpcTransportListener currentListener = listener;
            if (currentListener != null) {
                currentListener.onError(safeMessage, error);
            }
            close();
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

        private static Throwable rootCause(Throwable error) {
            Throwable current = error;
            while (current != null && current.getCause() != null && current.getCause() != current) {
                current = current.getCause();
            }
            return current != null ? current : error;
        }
    }
}
