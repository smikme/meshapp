package com.meshtastic.client.protocol.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meshtastic.client.connection.rpc.RemoteRpcTransportConnection;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.notification.NotificationManager;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;
import com.meshtastic.client.system.AppUi;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Protocol runtime for a direct MeshApp host RPC connection.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RemoteRpcProtocolRuntime implements ProtocolRuntime<RemoteRpcState> {

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(5);

    private final ProtocolRuntimeContext context;
    private final CompletableFuture<RemoteRpcState> readyFuture = new CompletableFuture<>();
    private volatile RemoteRpcState state;

    public RemoteRpcProtocolRuntime(ProtocolRuntimeContext context) {
        this.context = context;
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.REMOTE_RPC;
    }

    @Override
    public RemoteRpcState getState() {
        return state;
    }

    @Override
    public CompletableFuture<RemoteRpcState> getReadyFuture() {
        return readyFuture;
    }

    @Override
    public CompletableFuture<RemoteRpcState> start() {
        if (!(context.transportConnection() instanceof RemoteRpcTransportConnection rpcTransport)) {
            readyFuture.completeExceptionally(new IllegalStateException("Remote RPC transport is required"));
            return readyFuture;
        }

        rpcTransport.getRpcClient()
                .call("system.ping", new JsonObject(), PING_TIMEOUT)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        readyFuture.completeExceptionally(error);
                        return;
                    }
                    JsonObject ping = result != null && result.isJsonObject()
                            ? result.getAsJsonObject()
                            : new JsonObject();
                    rpcTransport.getRpcClient().addEventListener(this::handleHostEvent);
                    state = new RemoteRpcState(rpcTransport.getRpcClient(), ping);
                    readyFuture.complete(state);
                });
        return readyFuture;
    }

    @Override
    public void close() {
        // ConnectionManager owns the transport and closes it separately.
    }

    private void handleHostEvent(String event, JsonElement payload) {
        if (!"message.incoming".equals(event)) {
            return;
        }
        JsonObject object = payload != null && payload.isJsonObject()
                ? payload.getAsJsonObject()
                : new JsonObject();
        NotificationManager.showRemoteNotification(
                stringField(object, "title", "MeshApp"),
                stringField(object, "body", ""));
        AppUi.setChatUnreadDot(true);
    }

    private static String stringField(JsonObject object, String field, String fallback) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
