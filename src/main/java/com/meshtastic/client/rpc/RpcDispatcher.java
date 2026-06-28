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
import java.util.function.Consumer;

/**
 * Shared host-side RPC request dispatcher.
 * <p>
 * Transports are responsible only for delivering complete plaintext RPC
 * envelopes to this class and sending response envelopes produced by it.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RpcDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RpcDispatcher.class);
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final RpcMethodRegistry registry;
    private final Executor executor;

    /**
     * Creates a dispatcher using the common pool for method invocations.
     *
     * @param registry host RPC method registry
     */
    public RpcDispatcher(RpcMethodRegistry registry) {
        this(registry, ForkJoinPool.commonPool());
    }

    /**
     * Creates a dispatcher.
     *
     * @param registry host RPC method registry
     * @param executor executor used for RPC method invocations
     */
    public RpcDispatcher(RpcMethodRegistry registry, Executor executor) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Dispatches one request envelope and sends zero or one response envelopes
     * to the provided response sink.
     *
     * @param message plaintext RPC envelope
     * @param responseSink response sink for this caller/session
     */
    public void dispatch(String message, Consumer<JsonObject> responseSink) {
        Objects.requireNonNull(responseSink, "responseSink");
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
            responseSink.accept(errorResponse(requestId, RpcProtocol.ERROR_METHOD_NOT_FOUND,
                    "RPC method not found: " + (methodName != null ? methodName : "")));
            return;
        }

        CompletableFuture
                .supplyAsync(() -> invokeMethod(method, params, new RpcCallContext(requestId)), executor)
                .thenCompose(stage -> stage)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        responseSink.accept(methodErrorResponse(requestId, error));
                    } else {
                        responseSink.accept(resultResponse(requestId, result));
                    }
                });
    }

    /**
     * Builds a host push-event envelope.
     *
     * @param event event name
     * @param payload event payload, or {@code null}
     * @return event envelope
     */
    public static JsonObject eventEnvelope(String event, JsonElement payload) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", RpcProtocol.TYPE_EVENT);
        envelope.addProperty("event", requireText(event, "event"));
        envelope.add("payload", payload != null ? payload : JsonNull.INSTANCE);
        return envelope;
    }

    /**
     * Serializes one RPC envelope using the shared RPC JSON configuration.
     *
     * @param envelope RPC envelope
     * @return serialized JSON
     */
    public static String toJson(JsonObject envelope) {
        return GSON.toJson(envelope);
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

    private static JsonObject resultResponse(String requestId, JsonElement result) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", RpcProtocol.TYPE_RESPONSE);
        envelope.addProperty("requestId", requestId);
        envelope.addProperty("ok", true);
        envelope.add("result", result != null ? result : JsonNull.INSTANCE);
        return envelope;
    }

    private static JsonObject methodErrorResponse(String requestId, Throwable error) {
        Throwable cause = unwrapCompletion(error);
        if (cause instanceof RpcException rpcException) {
            return errorResponse(requestId, rpcException.getCode(), rpcException.getMessage());
        }
        if (cause instanceof IllegalArgumentException illegalArgumentException) {
            return errorResponse(requestId, RpcProtocol.ERROR_BAD_REQUEST, illegalArgumentException.getMessage());
        }
        if (cause instanceof ConnectionException connectionException) {
            String message = messageWithRootCause(connectionException);
            log.warn("RPC connection method failed: {}", message);
            return errorResponse(requestId, RpcProtocol.ERROR_CONNECTION_FAILED, message);
        }
        log.warn("RPC method failed", cause);
        return errorResponse(requestId, RpcProtocol.ERROR_INTERNAL, messageWithRootCause(cause));
    }

    private static JsonObject errorResponse(String requestId, String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", RpcException.normalizeCode(code));
        error.addProperty("message", message != null ? message : "");

        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", RpcProtocol.TYPE_RESPONSE);
        envelope.addProperty("requestId", requestId);
        envelope.addProperty("ok", false);
        envelope.add("error", error);
        return envelope;
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

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
