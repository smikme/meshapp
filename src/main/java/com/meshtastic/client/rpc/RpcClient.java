package com.meshtastic.client.rpc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remote-side RPC client.
 * <p>
 * The client sends request envelopes, tracks responses by request id, exposes
 * futures to callers, and dispatches host push events to listeners.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RpcClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RpcClient.class);
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final RpcTransport transport;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final ConcurrentMap<String, CompletableFuture<JsonElement>> pendingCalls = new ConcurrentHashMap<>();
    private final List<RpcEventListener> eventListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RpcClient(RpcTransport transport) {
        this(transport, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "rpc-client-timeouts");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public RpcClient(RpcTransport transport, ScheduledExecutorService scheduler) {
        this(transport, scheduler, false);
    }

    private RpcClient(RpcTransport transport, ScheduledExecutorService scheduler, boolean ownsScheduler) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
        this.transport.setListener(new ClientTransportListener());
    }

    /**
     * Calls a host method using the default timeout.
     *
     * @param method RPC method name
     * @param params request params, or {@code null}
     * @return future with JSON result
     */
    public CompletableFuture<JsonElement> call(String method, JsonElement params) {
        return call(method, params, DEFAULT_TIMEOUT);
    }

    /**
     * Calls a host method.
     * <p>
     * Registers the request before scheduling its deadline so an early timeout
     * cannot be lost. Completing or cancelling the returned future removes the
     * pending request and cancels its deadline task.
     *
     * @param method RPC method name
     * @param params request params, or {@code null}
     * @param timeout request timeout
     * @return future with JSON result
     */
    public CompletableFuture<JsonElement> call(String method, JsonElement params, Duration timeout) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new RpcRemoteException(
                    RpcProtocol.ERROR_TRANSPORT_CLOSED,
                    "RPC client is closed"));
        }
        if (!transport.isOpen()) {
            return CompletableFuture.failedFuture(new RpcRemoteException(
                    RpcProtocol.ERROR_TRANSPORT_CLOSED,
                    "RPC transport is closed"));
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;

        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", RpcProtocol.TYPE_REQUEST);
        envelope.addProperty("requestId", requestId);
        envelope.addProperty("method", requireMethod(method));
        envelope.add("params", params != null ? params : new JsonObject());

        pendingCalls.put(requestId, future);
        future.whenComplete((_, _) -> pendingCalls.remove(requestId, future));
        try {
            ScheduledFuture<?> timeoutFuture = scheduler.schedule(
                    () -> timeoutCall(requestId),
                    Math.max(1L, effectiveTimeout.toMillis()),
                    TimeUnit.MILLISECONDS);
            // Also runs when the deadline or transport closure completed the call
            // before schedule returned its task handle.
            future.whenComplete((_, _) -> timeoutFuture.cancel(false));
            if (!future.isDone()) {
                transport.send(GSON.toJson(envelope));
            }
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Calls a host method and deserializes its result.
     *
     * @param method RPC method name
     * @param params request params, or {@code null}
     * @param resultType result type
     * @return future with typed result
     * @param <T> result type
     */
    public <T> CompletableFuture<T> call(String method, JsonElement params, Class<T> resultType) {
        return call(method, params).thenApply(result -> GSON.fromJson(result, resultType));
    }

    /**
     * Adds a listener for host push events.
     *
     * @param listener listener to add
     */
    public void addEventListener(RpcEventListener listener) {
        eventListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Removes a previously added event listener.
     *
     * @param listener listener to remove
     */
    public void removeEventListener(RpcEventListener listener) {
        eventListeners.remove(listener);
    }

    /**
     * @return {@code true} when the client and its transport can still send RPC calls
     */
    public boolean isOpen() {
        return !closed.get() && transport.isOpen();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        transport.setListener(null);
        transport.close();
        failAllPending(new RpcRemoteException(RpcProtocol.ERROR_TRANSPORT_CLOSED, "RPC client closed"));
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private void handleMessage(String message) {
        JsonObject envelope;
        try {
            envelope = parseEnvelope(message);
        } catch (RpcException e) {
            log.debug("Ignoring malformed RPC client envelope: {}", e.getMessage());
            return;
        }

        String type = stringField(envelope, "type");
        if (RpcProtocol.TYPE_RESPONSE.equals(type)) {
            handleResponse(envelope);
        } else if (RpcProtocol.TYPE_EVENT.equals(type)) {
            handleEvent(envelope);
        } else {
            log.debug("Ignoring unsupported client-side RPC envelope type '{}'", type);
        }
    }

    private void handleResponse(JsonObject envelope) {
        String requestId = stringField(envelope, "requestId");
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        CompletableFuture<JsonElement> pending = pendingCalls.remove(requestId);
        if (pending == null) {
            return;
        }
        boolean ok = isSuccessfulResponse(envelope);
        if (ok) {
            JsonElement result = envelope.has("result") ? envelope.get("result") : JsonNull.INSTANCE;
            pending.complete(result != null ? result : JsonNull.INSTANCE);
            return;
        }

        JsonObject error = responseError(envelope);
        String code = stringField(error, "code");
        String errorMessage = stringField(error, "message");
        pending.completeExceptionally(new RpcRemoteException(
                code,
                errorMessage != null ? errorMessage : "Remote RPC call failed"));
    }

    private void handleEvent(JsonObject envelope) {
        String event = stringField(envelope, "event");
        if (event == null || event.isBlank()) {
            return;
        }
        JsonElement payload = envelope.has("payload") ? envelope.get("payload") : JsonNull.INSTANCE;
        JsonElement safePayload = payload != null ? payload : JsonNull.INSTANCE;
        for (RpcEventListener listener : eventListeners) {
            try {
                listener.onEvent(event, safePayload);
            } catch (RuntimeException e) {
                log.warn("RPC event listener failed for '{}'", event, e);
            }
        }
    }

    private void timeoutCall(String requestId) {
        CompletableFuture<JsonElement> pending = pendingCalls.remove(requestId);
        if (pending != null) {
            pending.completeExceptionally(new RpcRemoteException(
                    RpcProtocol.ERROR_TIMEOUT,
                    "RPC request timed out"));
        }
    }

    private void failAllPending(Throwable error) {
        for (String requestId : pendingCalls.keySet()) {
            CompletableFuture<JsonElement> pending = pendingCalls.remove(requestId);
            if (pending != null) {
                pending.completeExceptionally(error);
            }
        }
    }

    private static JsonObject parseEnvelope(String message) throws RpcException {
        if (message == null || message.isBlank()) {
            throw new RpcException(RpcProtocol.ERROR_BAD_REQUEST, "Empty RPC message");
        }
        try {
            JsonElement parsed = JsonParser.parseString(message);
            if (!parsed.isJsonObject()) {
                throw new RpcException(RpcProtocol.ERROR_BAD_REQUEST, "RPC envelope must be a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new RpcException(RpcProtocol.ERROR_BAD_REQUEST, "Invalid RPC JSON", e);
        }
    }

    /**
     * Extracts a response's structured error, falling back to an empty object.
     *
     * @param response response envelope to inspect
     * @return error object, or an empty object if the field is missing or is not an object
     */
    private static JsonObject responseError(JsonObject response) {
        JsonElement element = response.get("error");
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String stringField(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    /**
     * Reads the response's success flag.
     *
     * @param response response envelope to inspect
     * @return {@code true} when the primitive {@code ok} field evaluates to true
     */
    private static boolean isSuccessfulResponse(JsonObject response) {
        JsonElement element = response.get("ok");
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    /**
     * Validates and trims an RPC method name.
     *
     * @param method requested method name
     * @return method name without surrounding whitespace
     * @throws IllegalArgumentException if the method name is null or blank
     */
    private static String requireMethod(String method) {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method is required");
        }
        return method.trim();
    }

    private final class ClientTransportListener implements RpcTransportListener {
        @Override
        public void onMessage(String message) {
            if (!closed.get()) {
                handleMessage(message);
            }
        }

        @Override
        public void onClosed() {
            closed.set(true);
            failAllPending(new RpcRemoteException(
                    RpcProtocol.ERROR_TRANSPORT_CLOSED,
                    "RPC transport closed"));
        }

        @Override
        public void onError(String message, Throwable cause) {
            log.warn("RPC client transport error: {}", message, cause);
            closed.set(true);
            failAllPending(new RpcRemoteException(
                    RpcProtocol.ERROR_TRANSPORT_CLOSED,
                    message != null ? message : "RPC transport error"));
        }
    }
}
