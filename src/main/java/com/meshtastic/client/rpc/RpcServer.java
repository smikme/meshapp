package com.meshtastic.client.rpc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.meshtastic.client.connection.ConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-side RPC dispatcher.
 * <p>
 * The server receives request envelopes, dispatches only methods present in
 * {@link RpcMethodRegistry}, and writes response envelopes back to the same
 * transport.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RpcServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RpcServer.class);
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final RpcTransport transport;
    private final RpcMethodRegistry registry;
    private final Executor executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RpcServer(RpcTransport transport, RpcMethodRegistry registry) {
        this(transport, registry, ForkJoinPool.commonPool());
    }

    public RpcServer(RpcTransport transport, RpcMethodRegistry registry, Executor executor) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.transport.setListener(new ServerTransportListener());
    }

    /**
     * Publishes one host event to the connected remote peer.
     *
     * @param event event name
     * @param payload event payload, or {@code null}
     */
    public void publishEvent(String event, JsonElement payload) {
        if (closed.get()) {
            return;
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", RpcProtocol.TYPE_EVENT);
        envelope.addProperty("event", requireText(event, "event"));
        envelope.add("payload", payload != null ? payload : JsonNull.INSTANCE);
        send(envelope);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        transport.setListener(null);
        transport.close();
    }

    private void handleMessage(String message) {
        JsonObject envelope;
        try {
            envelope = parseEnvelope(message);
        } catch (RpcException e) {
            log.debug("Ignoring malformed RPC envelope: {}", e.getMessage());
            return;
        }

        String type = stringField(envelope, "type");
        if (!RpcProtocol.TYPE_REQUEST.equals(type)) {
            log.debug("Ignoring unsupported server-side RPC envelope type '{}'", type);
            return;
        }

        String requestId = stringField(envelope, "requestId");
        if (requestId == null || requestId.isBlank()) {
            log.debug("Ignoring RPC request without requestId");
            return;
        }

        String methodName = stringField(envelope, "method");
        JsonObject params = objectFieldOrEmpty(envelope, "params");
        RpcMethod method = registry.find(methodName).orElse(null);
        if (method == null) {
            sendError(requestId, RpcProtocol.ERROR_METHOD_NOT_FOUND,
                    "RPC method not found: " + (methodName != null ? methodName : ""));
            return;
        }

        CompletableFuture
                .supplyAsync(() -> invokeMethod(method, params, new RpcCallContext(requestId)), executor)
                .thenCompose(stage -> stage)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        sendMethodError(requestId, error);
                    } else {
                        sendResult(requestId, result);
                    }
                });
    }

    private CompletionStage<JsonElement> invokeMethod(RpcMethod method,
                                                      JsonObject params,
                                                      RpcCallContext context) {
        try {
            CompletionStage<JsonElement> result = method.invoke(params, context);
            return result != null ? result : CompletableFuture.completedFuture(JsonNull.INSTANCE);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void sendResult(String requestId, JsonElement result) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", RpcProtocol.TYPE_RESPONSE);
        envelope.addProperty("requestId", requestId);
        envelope.addProperty("ok", true);
        envelope.add("result", result != null ? result : JsonNull.INSTANCE);
        send(envelope);
    }

    private void sendMethodError(String requestId, Throwable error) {
        Throwable cause = unwrapCompletion(error);
        if (cause instanceof RpcException rpcException) {
            sendError(requestId, rpcException.getCode(), rpcException.getMessage());
        } else if (cause instanceof IllegalArgumentException illegalArgumentException) {
            sendError(requestId, RpcProtocol.ERROR_BAD_REQUEST, illegalArgumentException.getMessage());
        } else if (cause instanceof ConnectionException connectionException) {
            String message = messageWithRootCause(connectionException);
            log.warn("RPC connection method failed: {}", message);
            sendError(requestId, RpcProtocol.ERROR_CONNECTION_FAILED, message);
        } else {
            log.warn("RPC method failed", cause);
            sendError(requestId, RpcProtocol.ERROR_INTERNAL, messageWithRootCause(cause));
        }
    }

    private static String messageWithRootCause(Throwable error) {
        String message = error != null && error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage()
                : "RPC method failed";
        Throwable root = rootCause(error);
        if (root != null
                && root != error
                && root.getMessage() != null
                && !root.getMessage().isBlank()
                && !message.contains(root.getMessage())) {
            return message + ": " + root.getMessage();
        }
        return message;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private void sendError(String requestId, String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", RpcException.normalizeCode(code));
        error.addProperty("message", message != null ? message : "");

        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", RpcProtocol.TYPE_RESPONSE);
        envelope.addProperty("requestId", requestId);
        envelope.addProperty("ok", false);
        envelope.add("error", error);
        send(envelope);
    }

    private void send(JsonObject envelope) {
        if (closed.get() || !transport.isOpen()) {
            return;
        }
        try {
            transport.send(GSON.toJson(envelope));
        } catch (RuntimeException e) {
            log.warn("Failed to send RPC server envelope", e);
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

    private static JsonObject objectFieldOrEmpty(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String stringField(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static Throwable unwrapCompletion(Throwable error) {
        Throwable current = error;
        while (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) {
            if (current.getCause() == null) {
                break;
            }
            current = current.getCause();
        }
        return current;
    }

    private final class ServerTransportListener implements RpcTransportListener {
        @Override
        public void onMessage(String message) {
            if (!closed.get()) {
                handleMessage(message);
            }
        }

        @Override
        public void onClosed() {
            closed.set(true);
        }

        @Override
        public void onError(String message, Throwable cause) {
            log.warn("RPC server transport error: {}", message, cause);
            closed.set(true);
        }
    }
}
