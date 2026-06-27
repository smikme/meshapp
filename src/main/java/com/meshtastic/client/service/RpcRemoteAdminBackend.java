package com.meshtastic.client.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.rpc.RemoteAdminRpcJson;
import com.meshtastic.client.protocol.rpc.RemoteNodeJson;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

/**
 * Remote Admin backend that forwards all work to a MeshApp Host over RPC.
 */
public final class RpcRemoteAdminBackend implements RemoteAdminBackend {

    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(360);
    private static final Duration SECTION_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(90);

    private final RemoteRpcState rpcState;
    private final NodeData targetNode;
    private final RemoteAdminSession session;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RpcRemoteAdminBackend(RemoteRpcState rpcState, NodeData targetNode) {
        this.rpcState = Objects.requireNonNull(rpcState, "rpcState");
        this.targetNode = Objects.requireNonNull(targetNode, "targetNode");
        this.session = new RemoteAdminSession(targetNode.getNodeNum(), targetNode);
    }

    @Override
    public RemoteAdminSession session() {
        return session;
    }

    @Override
    public CompletableFuture<RemoteAdminSession> loadSnapshot(
            Consumer<RemoteAdminService.QueryProgress> progressConsumer) {
        return callSession("admin.load", nodeParams(), LOAD_TIMEOUT, true);
    }

    @Override
    public CompletableFuture<Void> requestConfigSection(AdminProtos.AdminMessage.ConfigType type) {
        JsonObject params = nodeParams();
        params.addProperty("type", type.name());
        return callSession("admin.requestConfig", params, SECTION_TIMEOUT, false).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> requestModuleConfigSection(AdminProtos.AdminMessage.ModuleConfigType type) {
        JsonObject params = nodeParams();
        params.addProperty("type", type.name());
        return callSession("admin.requestModuleConfig", params, SECTION_TIMEOUT, false).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> saveOwner(String longName, String shortName, boolean isLicensed) {
        JsonObject params = nodeParams();
        params.addProperty("longName", longName != null ? longName : "");
        params.addProperty("shortName", shortName != null ? shortName : "");
        params.addProperty("isLicensed", isLicensed);
        return callVoid("admin.saveOwner", params).thenRun(() -> session.applyOwner(MeshProtos.User.newBuilder()
                .setLongName(longName != null ? longName : "")
                .setShortName(shortName != null ? shortName : "")
                .setIsLicensed(isLicensed)
                .build()));
    }

    @Override
    public CompletableFuture<Void> saveConfigChanges(List<ConfigProtos.Config> configs,
                                                     List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
                                                     List<ChannelProtos.Channel> channels) {
        List<ConfigProtos.Config> safeConfigs = configs != null ? configs : List.of();
        List<ModuleConfigProtos.ModuleConfig> safeModuleConfigs = moduleConfigs != null ? moduleConfigs : List.of();
        List<ChannelProtos.Channel> safeChannels = channels != null ? channels : List.of();
        JsonObject params = nodeParams();
        params.add("configs", RemoteAdminRpcJson.configsToJson(safeConfigs));
        params.add("moduleConfigs", RemoteAdminRpcJson.moduleConfigsToJson(safeModuleConfigs));
        params.add("channels", RemoteAdminRpcJson.channelsToJson(safeChannels));
        return callVoid("admin.saveConfigChanges", params).thenRun(() -> {
            for (ChannelProtos.Channel channel : safeChannels) {
                session.remoteState().updateChannel(channel);
            }
            for (ConfigProtos.Config config : safeConfigs) {
                session.remoteState().addConfig(config);
            }
            for (ModuleConfigProtos.ModuleConfig moduleConfig : safeModuleConfigs) {
                session.remoteState().addModuleConfig(moduleConfig);
            }
        });
    }

    @Override
    public CompletableFuture<Void> setFixedPosition(double latDegrees, double lonDegrees, int altMeters) {
        JsonObject params = nodeParams();
        params.addProperty("latDegrees", latDegrees);
        params.addProperty("lonDegrees", lonDegrees);
        params.addProperty("altMeters", altMeters);
        return callVoid("admin.setFixedPosition", params);
    }

    @Override
    public CompletableFuture<Void> removeFixedPosition() {
        return callVoid("admin.removeFixedPosition", nodeParams());
    }

    @Override
    public CompletableFuture<Void> setRingtone(String ringtone) {
        JsonObject params = nodeParams();
        params.addProperty("ringtone", ringtone != null ? ringtone : "");
        return callVoid("admin.setRingtone", params).thenRun(() -> session.remoteState().setRingtone(ringtone));
    }

    @Override
    public CompletableFuture<Void> setCannedMessages(String messages) {
        JsonObject params = nodeParams();
        params.addProperty("messages", messages != null ? messages : "");
        return callVoid("admin.setCannedMessages", params).thenRun(() -> session.setCannedMessages(messages));
    }

    @Override
    public CompletableFuture<Void> reboot(int delaySeconds) {
        JsonObject params = commandParams("reboot");
        params.addProperty("delaySeconds", delaySeconds);
        return callVoid("admin.command", params);
    }

    @Override
    public CompletableFuture<Void> shutdown(int delaySeconds) {
        JsonObject params = commandParams("shutdown");
        params.addProperty("delaySeconds", delaySeconds);
        return callVoid("admin.command", params);
    }

    @Override
    public CompletableFuture<Void> syncTime(long epochSeconds) {
        JsonObject params = commandParams("syncTime");
        params.addProperty("epochSeconds", epochSeconds);
        return callVoid("admin.command", params);
    }

    @Override
    public CompletableFuture<Void> backupPreferences(AdminProtos.AdminMessage.BackupLocation location) {
        JsonObject params = commandParams("backupPreferences");
        params.addProperty("location", location.name());
        return callVoid("admin.command", params);
    }

    @Override
    public CompletableFuture<Void> restorePreferences(AdminProtos.AdminMessage.BackupLocation location) {
        JsonObject params = commandParams("restorePreferences");
        params.addProperty("location", location.name());
        return callVoid("admin.command", params);
    }

    @Override
    public CompletableFuture<Void> removeBackupPreferences(AdminProtos.AdminMessage.BackupLocation location) {
        JsonObject params = commandParams("removeBackupPreferences");
        params.addProperty("location", location.name());
        return callVoid("admin.command", params);
    }

    @Override
    public CompletableFuture<Void> factoryResetConfig() {
        return callVoid("admin.command", commandParams("factoryResetConfig"));
    }

    @Override
    public CompletableFuture<Void> factoryResetDevice() {
        return callVoid("admin.command", commandParams("factoryResetDevice"));
    }

    @Override
    public CompletableFuture<Void> resetNodeDb(boolean preserveFavorites) {
        JsonObject params = commandParams("resetNodeDb");
        params.addProperty("preserveFavorites", preserveFavorites);
        return callVoid("admin.command", params);
    }

    @Override
    public CompletableFuture<Void> enterDfuMode() {
        return callVoid("admin.command", commandParams("enterDfuMode"));
    }

    @Override
    public CompletableFuture<AdminProtos.AdminMessage> refreshConnectionStatus() {
        return call("admin.refreshConnectionStatus", nodeParams(), SECTION_TIMEOUT)
                .thenApply(result -> {
                    RemoteAdminRpcJson.applySnapshot(session, result, false);
                    return RemoteAdminRpcJson.adminMessageFromJson(result);
                });
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            session.close();
        }
    }

    private CompletableFuture<RemoteAdminSession> callSession(String method,
                                                              JsonObject params,
                                                              Duration timeout,
                                                              boolean clearFirst) {
        return call(method, params, timeout).thenApply(result -> {
            RemoteAdminRpcJson.applySnapshot(session, result, clearFirst);
            return session;
        });
    }

    private CompletableFuture<Void> callVoid(String method, JsonObject params) {
        return call(method, params, COMMAND_TIMEOUT).thenApply(ignored -> null);
    }

    private CompletableFuture<JsonElement> call(String method, JsonObject params, Duration timeout) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Remote admin backend is closed"));
        }
        if (rpcState.client() == null || !rpcState.client().isOpen()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RPC client is closed"));
        }
        return rpcState.client().call(method, params, timeout);
    }

    private JsonObject commandParams(String command) {
        JsonObject params = nodeParams();
        params.addProperty("command", command);
        return params;
    }

    private JsonObject nodeParams() {
        return RemoteNodeJson.nodeParams(targetNode);
    }
}
