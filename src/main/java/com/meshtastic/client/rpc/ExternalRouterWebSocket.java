package com.meshtastic.client.rpc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Small JSON WebSocket client used by MeshApp RPC router transports.
 * <p>
 * It owns WebSocket-level ping/pong health checks and delivers complete JSON
 * frames to the host or remote-client router transport.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class ExternalRouterWebSocket implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExternalRouterWebSocket.class);
    private static final Gson GSON = new Gson();
    private static final Duration HEALTH_INTERVAL = Duration.ofSeconds(15);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(45);

    private final WebSocket webSocket;
    private final Consumer<JsonObject> messageHandler;
    private final BiConsumer<String, Throwable> errorHandler;
    private final Runnable closedHandler;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile long lastPongNanos = System.nanoTime();
    private volatile ScheduledFuture<?> healthTask;

    private ExternalRouterWebSocket(WebSocket webSocket,
                                    Consumer<JsonObject> messageHandler,
                                    BiConsumer<String, Throwable> errorHandler,
                                    Runnable closedHandler) {
        this.webSocket = Objects.requireNonNull(webSocket, "webSocket");
        this.messageHandler = Objects.requireNonNull(messageHandler, "messageHandler");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.closedHandler = Objects.requireNonNull(closedHandler, "closedHandler");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "erpc-router-health");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Opens a WebSocket connection to the router and starts channel health checks.
     *
     * @param uri router WebSocket endpoint
     * @param timeout connect timeout
     * @param messageHandler JSON frame handler
     * @param errorHandler connection error handler
     * @param closedHandler close handler
     * @return open WebSocket wrapper
     * @throws IOException when the connection cannot be opened
     */
    static ExternalRouterWebSocket connect(URI uri,
                                           Duration timeout,
                                           Consumer<JsonObject> messageHandler,
                                           BiConsumer<String, Throwable> errorHandler,
                                           Runnable closedHandler) throws IOException {
        Objects.requireNonNull(uri, "uri");
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(5);
        Listener listener = new Listener();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(effectiveTimeout)
                .build();
        WebSocket webSocket;
        try {
            webSocket = client.newWebSocketBuilder()
                    .connectTimeout(effectiveTimeout)
                    .buildAsync(uri, listener)
                    .orTimeout(Math.max(1L, effectiveTimeout.toMillis()), TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException e) {
            throw new IOException("Failed to connect to ERPC Router " + uri, rootCause(e));
        }
        ExternalRouterWebSocket connection = new ExternalRouterWebSocket(
                webSocket,
                messageHandler,
                errorHandler,
                closedHandler);
        listener.bind(connection);
        connection.startHealthcheck();
        return connection;
    }

    /**
     * Sends one JSON frame to the router.
     *
     * @param object frame object
     */
    void send(JsonObject object) {
        if (!isOpen()) {
            throw new IllegalStateException("ERPC Router WebSocket is closed");
        }
        webSocket.sendText(GSON.toJson(object), true)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        reportError("ERPC Router WebSocket write failed", error);
                        close();
                    }
                });
    }

    /**
     * @return {@code true} while both WebSocket directions are open
     */
    boolean isOpen() {
        return open.get() && !webSocket.isInputClosed() && !webSocket.isOutputClosed();
    }

    @Override
    public void close() {
        closeInternal(false);
    }

    private void startHealthcheck() {
        healthTask = scheduler.scheduleAtFixedRate(
                this::sendHealthPing,
                HEALTH_INTERVAL.toMillis(),
                HEALTH_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void sendHealthPing() {
        if (!isOpen()) {
            return;
        }
        long silenceMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastPongNanos);
        if (silenceMs > HEALTH_TIMEOUT.toMillis()) {
            reportError("ERPC Router healthcheck timed out", null);
            close();
            return;
        }
        webSocket.sendPing(ByteBuffer.wrap(new byte[]{1}))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        reportError("ERPC Router healthcheck ping failed", error);
                        close();
                    }
                });
    }

    private void onText(String message) {
        try {
            var parsed = JsonParser.parseString(message);
            if (parsed.isJsonObject()) {
                messageHandler.accept(parsed.getAsJsonObject());
            }
        } catch (JsonParseException e) {
            reportError("Invalid ERPC Router JSON frame", e);
        }
    }

    private void onPong() {
        lastPongNanos = System.nanoTime();
    }

    private void onClosed() {
        closeInternal(true);
    }

    private void reportError(String message, Throwable error) {
        try {
            errorHandler.accept(message, error);
        } catch (RuntimeException e) {
            log.warn("ERPC Router error handler failed", e);
        }
    }

    private void closeInternal(boolean remoteClosed) {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        ScheduledFuture<?> task = healthTask;
        if (task != null) {
            task.cancel(false);
        }
        scheduler.shutdownNow();
        if (!remoteClosed) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "closed");
        }
        closedHandler.run();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current != null ? current : error;
    }

    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();
        private final List<String> pendingText = new ArrayList<>();
        private volatile ExternalRouterWebSocket owner;

        private synchronized void bind(ExternalRouterWebSocket owner) {
            this.owner = owner;
            for (String message : pendingText) {
                owner.onText(message);
            }
            pendingText.clear();
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                ExternalRouterWebSocket current = owner;
                if (current != null) {
                    current.onText(textBuffer.toString());
                } else {
                    synchronized (this) {
                        if (owner != null) {
                            owner.onText(textBuffer.toString());
                        } else {
                            pendingText.add(textBuffer.toString());
                        }
                    }
                }
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            ExternalRouterWebSocket current = owner;
            if (current != null) {
                current.onPong();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            ExternalRouterWebSocket current = owner;
            if (current != null) {
                current.onClosed();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ExternalRouterWebSocket current = owner;
            if (current != null) {
                current.reportError("ERPC Router WebSocket failed", error);
                current.onClosed();
            }
        }
    }
}
