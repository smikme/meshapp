package com.meshtastic.client.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.rpc.RemoteAdminRpcJson;
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
 * RPC backend for the Settings/Configuration form targeting the host's locally connected node.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RpcHostSettingsBackend implements RemoteAdminBackend {

    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(90);

    private final RemoteRpcState rpcState;
    private final RemoteAdminSession session = new RemoteAdminSession(0, new NodeData(0));
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RpcHostSettingsBackend(RemoteRpcState rpcState) {
        this.rpcState = Objects.requireNonNull(rpcState, "rpcState");
    }

    @Override
    public RemoteAdminSession session() {
        return session;
    }

    @Override
    public CompletableFuture<RemoteAdminSession> loadSnapshot(
            Consumer<RemoteAdminService.QueryProgress> progressConsumer) {
        return callSession("settings.snapshot", new JsonObject(), LOAD_TIMEOUT, true);
    }

    @Override
    public CompletableFuture<Void> requestConfigSection(AdminProtos.AdminMessage.ConfigType type) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> requestModuleConfigSection(AdminProtos.AdminMessage.ModuleConfigType type) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> saveOwner(String longName, String shortName, boolean isLicensed) {
        JsonObject params = new JsonObject();
        params.addProperty("longName", longName != null ? longName : "");
        params.addProperty("shortName", shortName != null ? shortName : "");
        params.addProperty("isLicensed", isLicensed);
        return callSession("settings.saveOwner", params, COMMAND_TIMEOUT, false).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> saveConfigChanges(List<ConfigProtos.Config> configs,
                                                     List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
                                                     List<ChannelProtos.Channel> channels) {
        JsonObject params = new JsonObject();
        params.add("configs", RemoteAdminRpcJson.configsToJson(configs != null ? configs : List.of()));
        params.add("moduleConfigs", RemoteAdminRpcJson.moduleConfigsToJson(
                moduleConfigs != null ? moduleConfigs : List.of()));
        params.add("channels", RemoteAdminRpcJson.channelsToJson(channels != null ? channels : List.of()));
        return callSession("settings.saveConfigChanges", params, COMMAND_TIMEOUT, false).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> setFixedPosition(double latDegrees, double lonDegrees, int altMeters) {
        JsonObject params = new JsonObject();
        params.addProperty("latDegrees", latDegrees);
        params.addProperty("lonDegrees", lonDegrees);
        params.addProperty("altMeters", altMeters);
        return callSession("settings.setFixedPosition", params, COMMAND_TIMEOUT, false).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> removeFixedPosition() {
        return callSession("settings.removeFixedPosition", new JsonObject(), COMMAND_TIMEOUT, false)
                .thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> setRingtone(String ringtone) {
        JsonObject params = new JsonObject();
        params.addProperty("ringtone", ringtone != null ? ringtone : "");
        return callSession("settings.setRingtone", params, COMMAND_TIMEOUT, false).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> setCannedMessages(String messages) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> reboot(int delaySeconds) {
        JsonObject params = commandParams("reboot");
        params.addProperty("delaySeconds", delaySeconds);
        return callSession("settings.command", params, COMMAND_TIMEOUT, false).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> shutdown(int delaySeconds) {
        JsonObject params = commandParams("shutdown");
        params.addProperty("delaySeconds", delaySeconds);
        return callSession("settings.command", params, COMMAND_TIMEOUT, false).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> syncTime(long epochSeconds) {
        JsonObject params = commandParams("syncTime");
        params.addProperty("epochSeconds", epochSeconds);
        return callSession("settings.command", params, COMMAND_TIMEOUT, false).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> backupPreferences(AdminProtos.AdminMessage.BackupLocation location) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> restorePreferences(AdminProtos.AdminMessage.BackupLocation location) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> removeBackupPreferences(AdminProtos.AdminMessage.BackupLocation location) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> factoryResetConfig() {
        return callSession("settings.command", commandParams("factoryResetConfig"), COMMAND_TIMEOUT)
                .thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> factoryResetDevice() {
        return callSession("settings.command", commandParams("factoryResetDevice"), COMMAND_TIMEOUT)
                .thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> resetNodeDb(boolean preserveFavorites) {
        JsonObject params = commandParams("resetNodeDb");
        params.addProperty("preserveFavorites", preserveFavorites);
        return callSession("settings.command", params, COMMAND_TIMEOUT).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> enterDfuMode() {
        return callSession("settings.command", commandParams("enterDfuMode"), COMMAND_TIMEOUT)
                .thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<AdminProtos.AdminMessage> refreshConnectionStatus() {
        return CompletableFuture.completedFuture(AdminProtos.AdminMessage.getDefaultInstance());
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

    private CompletableFuture<RemoteAdminSession> callSession(String method,
                                                              JsonObject params,
                                                              Duration timeout) {
        return callSession(method, params, timeout, false);
    }

    private CompletableFuture<JsonElement> call(String method, JsonObject params, Duration timeout) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Settings RPC backend is closed"));
        }
        if (rpcState.client() == null || !rpcState.client().isOpen()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RPC client is closed"));
        }
        return rpcState.client().call(method, params, timeout);
    }

    private static JsonObject commandParams(String command) {
        JsonObject params = new JsonObject();
        params.addProperty("command", command);
        return params;
    }
}
