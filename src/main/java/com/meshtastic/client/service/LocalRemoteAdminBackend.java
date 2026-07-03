package com.meshtastic.client.service;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

/**
 * Local Remote Admin backend backed by the currently selected radio connection.
 */
public final class LocalRemoteAdminBackend implements RemoteAdminBackend {

    private final RemoteAdminService service;

    public LocalRemoteAdminBackend(ProtocolHandler handler, DeviceState localState, NodeData targetNode) {
        this(new RemoteAdminService(handler, localState, targetNode));
    }

    public LocalRemoteAdminBackend(RemoteAdminService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public RemoteAdminSession session() {
        return service.session();
    }

    @Override
    public CompletableFuture<RemoteAdminSession> loadSnapshot(
            Consumer<RemoteAdminService.QueryProgress> progressConsumer) {
        return service.loadSnapshot(progressConsumer);
    }

    @Override
    public CompletableFuture<Void> requestConfigSection(AdminProtos.AdminMessage.ConfigType type) {
        return service.requestConfigSection(type);
    }

    @Override
    public CompletableFuture<Void> requestModuleConfigSection(AdminProtos.AdminMessage.ModuleConfigType type) {
        return service.requestModuleConfigSection(type);
    }

    @Override
    public CompletableFuture<Void> saveOwner(String longName, String shortName, boolean isLicensed) {
        return service.saveOwner(longName, shortName, isLicensed);
    }

    @Override
    public CompletableFuture<Void> saveConfigChanges(List<ConfigProtos.Config> configs,
                                                     List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
                                                     List<ChannelProtos.Channel> channels) {
        return service.saveConfigChanges(configs, moduleConfigs, channels);
    }

    @Override
    public CompletableFuture<Void> setFixedPosition(double latDegrees, double lonDegrees, int altMeters) {
        return service.setFixedPosition(latDegrees, lonDegrees, altMeters);
    }

    @Override
    public CompletableFuture<Void> removeFixedPosition() {
        return service.removeFixedPosition();
    }

    @Override
    public CompletableFuture<Void> setRingtone(String ringtone) {
        return service.setRingtone(ringtone);
    }

    @Override
    public CompletableFuture<Void> setCannedMessages(String messages) {
        return service.setCannedMessages(messages);
    }

    @Override
    public CompletableFuture<Void> reboot(int delaySeconds) {
        return service.reboot(delaySeconds);
    }

    @Override
    public CompletableFuture<Void> shutdown(int delaySeconds) {
        return service.shutdown(delaySeconds);
    }

    @Override
    public CompletableFuture<Void> syncTime(long epochSeconds) {
        return service.syncTime(epochSeconds);
    }

    @Override
    public CompletableFuture<Void> backupPreferences(AdminProtos.AdminMessage.BackupLocation location) {
        return service.backupPreferences(location);
    }

    @Override
    public CompletableFuture<Void> restorePreferences(AdminProtos.AdminMessage.BackupLocation location) {
        return service.restorePreferences(location);
    }

    @Override
    public CompletableFuture<Void> removeBackupPreferences(AdminProtos.AdminMessage.BackupLocation location) {
        return service.removeBackupPreferences(location);
    }

    @Override
    public CompletableFuture<Void> factoryResetConfig() {
        return service.factoryResetConfig();
    }

    @Override
    public CompletableFuture<Void> factoryResetDevice() {
        return service.factoryResetDevice();
    }

    @Override
    public CompletableFuture<Void> resetNodeDb(boolean preserveFavorites) {
        return service.resetNodeDb(preserveFavorites);
    }

    @Override
    public CompletableFuture<Void> enterDfuMode() {
        return service.enterDfuMode();
    }

    @Override
    public CompletableFuture<AdminProtos.AdminMessage> refreshConnectionStatus() {
        return service.refreshConnectionStatus();
    }

    @Override
    public void close() {
        service.close();
    }
}
