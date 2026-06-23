package com.meshtastic.client.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

/**
 * Backend used by the Remote Admin UI.
 */
public interface RemoteAdminBackend extends AutoCloseable {

    RemoteAdminSession session();

    CompletableFuture<RemoteAdminSession> loadSnapshot(Consumer<RemoteAdminService.QueryProgress> progressConsumer);

    CompletableFuture<Void> requestConfigSection(AdminProtos.AdminMessage.ConfigType type);

    CompletableFuture<Void> requestModuleConfigSection(AdminProtos.AdminMessage.ModuleConfigType type);

    CompletableFuture<Void> saveOwner(String longName, String shortName, boolean isLicensed);

    CompletableFuture<Void> saveConfigChanges(List<ConfigProtos.Config> configs,
                                               List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
                                               List<ChannelProtos.Channel> channels);

    CompletableFuture<Void> setFixedPosition(double latDegrees, double lonDegrees, int altMeters);

    CompletableFuture<Void> removeFixedPosition();

    CompletableFuture<Void> setRingtone(String ringtone);

    CompletableFuture<Void> setCannedMessages(String messages);

    CompletableFuture<Void> reboot(int delaySeconds);

    CompletableFuture<Void> shutdown(int delaySeconds);

    CompletableFuture<Void> syncTime(long epochSeconds);

    CompletableFuture<Void> backupPreferences(AdminProtos.AdminMessage.BackupLocation location);

    CompletableFuture<Void> restorePreferences(AdminProtos.AdminMessage.BackupLocation location);

    CompletableFuture<Void> removeBackupPreferences(AdminProtos.AdminMessage.BackupLocation location);

    CompletableFuture<Void> factoryResetConfig();

    CompletableFuture<Void> factoryResetDevice();

    CompletableFuture<Void> resetNodeDb(boolean preserveFavorites);

    CompletableFuture<Void> enterDfuMode();

    CompletableFuture<AdminProtos.AdminMessage> refreshConnectionStatus();

    @Override
    void close();
}
