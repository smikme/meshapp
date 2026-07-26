package com.meshtastic.client.service;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.meshtastic.client.forms.settings.ConfigChangeSet;
import com.meshtastic.client.forms.settings.ConfigCompatibilityValidator;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.FirmwareCapabilities;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.FromRadioListener;
import com.meshtastic.client.protocol.ProtocolHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.meshtastic.proto.Portnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends Meshtastic {@code AdminMessage} packets to a remote node over the mesh.
 * <p>
 * Local admin messages use {@code from=0,to=myNodeNum}; this service uses
 * {@code from=myNodeNum,to=targetNodeNum,pkiEncrypted=true}, which is the PKC
 * remote-admin path for firmware 2.5+.
 */
public final class RemoteAdminService implements FromRadioListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RemoteAdminService.class);

    private static final long QUERY_TIMEOUT_MS = 30_000;
    private static final long MUTATION_ACK_TIMEOUT_MS = 20_000;
    private static final long PASSKEY_TTL_MS = 240_000;
    private static final long QUERY_SPACING_MS = 300;

    private final ProtocolHandler handler;
    private final DeviceState localState;
    private final RemoteAdminSession session;
    private final ConcurrentHashMap<Integer, PendingAdminRequest> pendingByPacketId = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PendingAdminRequest> pendingRequests = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "remote-admin-timeout");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a remote admin service for a single target node and registers it
     * as a listener for inbound radio packets.
     *
     * @param handler protocol handler used to send remote ADMIN_APP packets
     * @param localState state of the locally connected node
     * @param targetNode remote node that will receive admin requests
     */
    public RemoteAdminService(ProtocolHandler handler, DeviceState localState, NodeData targetNode) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.localState = Objects.requireNonNull(localState, "localState");
        Objects.requireNonNull(targetNode, "targetNode");
        this.session = new RemoteAdminSession(targetNode.getNodeNum(), targetNode);
        this.handler.addListener(this);
    }

    /**
     * Returns the mutable remote-admin session for the target node.
     *
     * @return remote session state, including the isolated config snapshot
     */
    public RemoteAdminSession session() {
        return session;
    }

    /**
     * Returns all radio config sections that can be loaded and edited remotely.
     * The session-key config is excluded because it is only used internally to
     * obtain a passkey for mutating admin commands.
     *
     * @return generated config enum values supported by the current protobuf
     */
    public static List<AdminProtos.AdminMessage.ConfigType> editableConfigTypes() {
        return Arrays.stream(AdminProtos.AdminMessage.ConfigType.values())
                .filter(type -> type != AdminProtos.AdminMessage.ConfigType.UNRECOGNIZED)
                .filter(type -> type != AdminProtos.AdminMessage.ConfigType.SESSIONKEY_CONFIG)
                .toList();
    }

    /**
     * Returns all module config sections that can be loaded and edited remotely.
     *
     * @return generated module config enum values supported by the current protobuf
     */
    public static List<AdminProtos.AdminMessage.ModuleConfigType> editableModuleConfigTypes() {
        return Arrays.stream(AdminProtos.AdminMessage.ModuleConfigType.values())
                .filter(type -> type != AdminProtos.AdminMessage.ModuleConfigType.UNRECOGNIZED)
                .toList();
    }

    public static List<AdminProtos.AdminMessage.ModuleConfigType> editableModuleConfigTypes(
            FirmwareCapabilities capabilities) {
        boolean firmware28 = capabilities != null
                && capabilities.firmware28OrNewer();
        return Arrays.stream(AdminProtos.AdminMessage.ModuleConfigType.values())
                .filter(type -> type != AdminProtos.AdminMessage.ModuleConfigType.UNRECOGNIZED)
                .filter(type -> firmware28
                        || type != AdminProtos.AdminMessage.ModuleConfigType.MESHBEACON_CONFIG)
                .toList();
    }

    /**
     * Returns the stable query key used for a device config section.
     *
     * @param type admin config type
     * @return query status key
     */
    public static String configQueryKey(AdminProtos.AdminMessage.ConfigType type) {
        return "get_config/" + type.name();
    }

    /**
     * Returns the stable query key used for a module config section.
     *
     * @param type admin module config type
     * @return query status key
     */
    public static String moduleConfigQueryKey(AdminProtos.AdminMessage.ModuleConfigType type) {
        return "get_module_config/" + type.name();
    }

    /**
     * Returns the matching Config protobuf oneof field number for an admin enum.
     *
     * @param type admin config type
     * @return Config oneof field number
     */
    public static int configVariantNumber(AdminProtos.AdminMessage.ConfigType type) {
        return type.getNumber() + 1;
    }

    /**
     * Returns the matching ModuleConfig protobuf oneof field number for an admin enum.
     *
     * @param type admin module config type
     * @return ModuleConfig oneof field number
     */
    public static int moduleConfigVariantNumber(AdminProtos.AdminMessage.ModuleConfigType type) {
        return type.getNumber() + 1;
    }

    /**
     * Loads a complete remote snapshot into the session.
     * <p>
     * Queries are paced to avoid flooding the mesh. Individual query failures
     * are recorded in {@link RemoteAdminSession#queryStatuses()} while the
     * returned future succeeds as long as at least one remote response arrives.
     *
     * @return future completed with the updated remote session
     */
    public CompletableFuture<RemoteAdminSession> loadSnapshot() {
        return loadSnapshot(null);
    }

    /**
     * Loads a complete remote snapshot into the session and emits progress for
     * every requested block.
     *
     * @param progressConsumer optional progress callback
     * @return future completed with the updated remote session
     */
    public CompletableFuture<RemoteAdminSession> loadSnapshot(Consumer<QueryProgress> progressConsumer) {
        return CompletableFuture.supplyAsync(() -> {
            ensureOpen();
            session.clearSnapshot();
            RequestPlan metadataPlan = deviceMetadataRequestPlan();
            int provisionalTotal = buildSnapshotRequestPlans(FirmwareCapabilities.legacy()).size() + 1;
            notifyProgress(
                    progressConsumer,
                    metadataPlan.key(),
                    RemoteAdminSession.QueryState.SENT,
                    0,
                    provisionalTotal);
            CompletableFuture<AdminProtos.AdminMessage> metadataRequest =
                    sendAdminRequest(
                            metadataPlan.message(),
                            metadataPlan.matcher(),
                            metadataPlan.key());
            boolean metadataSucceeded = false;
            try {
                metadataRequest.join();
                metadataSucceeded = true;
            } catch (CompletionException e) {
                log.debug("Remote metadata query failed for !{}: {}",
                        Integer.toHexString(session.targetNodeNum()),
                        rootMessage(e));
            }

            List<RequestPlan> plans = buildSnapshotRequestPlans(
                    session.remoteState().getFirmwareCapabilities());
            List<CompletableFuture<AdminProtos.AdminMessage>> requests = new ArrayList<>();
            AtomicInteger completed = new AtomicInteger(1);
            int total = plans.size() + 1;
            notifyProgress(
                    progressConsumer,
                    metadataPlan.key(),
                    metadataSucceeded
                            ? RemoteAdminSession.QueryState.RECEIVED
                            : RemoteAdminSession.QueryState.FAILED,
                    completed.get(),
                    total);
            if (!plans.isEmpty()) {
                pauseBetweenQueries();
            }

            for (int i = 0; i < plans.size(); i++) {
                RequestPlan plan = plans.get(i);
                notifyProgress(progressConsumer, plan.key(),
                        RemoteAdminSession.QueryState.SENT, completed.get(), total);
                CompletableFuture<AdminProtos.AdminMessage> request = sendAdminRequest(
                        plan.message(),
                        plan.matcher(),
                        plan.key());
                request.whenComplete((ignored, error) -> notifyProgress(
                        progressConsumer,
                        plan.key(),
                        error == null
                                ? RemoteAdminSession.QueryState.RECEIVED
                                : RemoteAdminSession.QueryState.FAILED,
                        completed.incrementAndGet(),
                        total));
                requests.add(request);
                if (i < plans.size() - 1) {
                    pauseBetweenQueries();
                }
            }

            int successfulResponses = metadataSucceeded ? 1 : 0;
            for (CompletableFuture<AdminProtos.AdminMessage> request : requests) {
                try {
                    request.join();
                    successfulResponses++;
                } catch (CompletionException e) {
                    log.debug("Remote admin query failed for !{}: {}",
                            Integer.toHexString(session.targetNodeNum()), rootMessage(e));
                }
            }
            session.remoteState().setChannelCatalogReady(true);
            if (successfulResponses == 0) {
                throw new CompletionException(new TimeoutException(
                        "Remote node did not answer admin requests"));
            }
            return session;
        });
    }

    /**
     * Re-requests one device config section from the remote node.
     *
     * @param type section to request
     * @return future completed when the section response is received
     */
    public CompletableFuture<Void> requestConfigSection(AdminProtos.AdminMessage.ConfigType type) {
        if (!editableConfigTypes().contains(type)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Unsupported remote config type: " + type));
        }
        return sendAdminRequest(
                AdminProtos.AdminMessage.newBuilder().setGetConfigRequest(type).build(),
                AdminProtos.AdminMessage::hasGetConfigResponse,
                configQueryKey(type))
                .thenApply(ignored -> null);
    }

    /**
     * Re-requests one module config section from the remote node.
     *
     * @param type section to request
     * @return future completed when the section response is received
     */
    public CompletableFuture<Void> requestModuleConfigSection(AdminProtos.AdminMessage.ModuleConfigType type) {
        if (!editableModuleConfigTypes(
                session.remoteState().getFirmwareCapabilities()).contains(type)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Unsupported remote module config type: " + type));
        }
        return sendAdminRequest(
                AdminProtos.AdminMessage.newBuilder().setGetModuleConfigRequest(type).build(),
                AdminProtos.AdminMessage::hasGetModuleConfigResponse,
                moduleConfigQueryKey(type))
                .thenApply(ignored -> null);
    }

    /**
     * Updates the remote node owner information.
     *
     * @param longName long display name to store on the node
     * @param shortName short display name to store on the node
     * @param isLicensed licensed operator flag
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> saveOwner(String longName, String shortName, boolean isLicensed) {
        var compatibilityError =
                ConfigCompatibilityValidator.validateOwnerName(
                        session.remoteState(),
                        longName);
        if (compatibilityError.isPresent()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(compatibilityError.get()));
        }
        return ensureSessionPasskey().thenCompose(ignored -> {
            MeshProtos.User user = MeshProtos.User.newBuilder()
                    .setLongName(longName != null ? longName : "")
                    .setShortName(shortName != null ? shortName : "")
                    .setIsLicensed(isLicensed)
                    .build();
            return sendAdminCommand(withPasskey(
                    AdminProtos.AdminMessage.newBuilder().setSetOwner(user)).build(),
                    "set_owner").thenApply(error -> {
                        session.applyOwner(user);
                        return null;
                    });
        });
    }

    /**
     * Sends changed channel, config, and module config sections to the remote node.
     * <p>
     * Config and module changes are wrapped in a begin/commit edit transaction.
     *
     * @param configs radio config sections to write
     * @param moduleConfigs module config sections to write
     * @param channels channel definitions to write before the config transaction
     * @return future completed when all writes are acknowledged
     */
    public CompletableFuture<Void> saveConfigChanges(List<ConfigProtos.Config> configs,
                                                      List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
                                                      List<ChannelProtos.Channel> channels) {
        List<ConfigProtos.Config> safeConfigs = configs != null ? configs : List.of();
        List<ModuleConfigProtos.ModuleConfig> safeModuleConfigs = moduleConfigs != null ? moduleConfigs : List.of();
        List<ChannelProtos.Channel> safeChannels = channels != null ? channels : List.of();

        boolean licensed = session.remoteState().getOwnerInfo() != null
                && session.remoteState().getOwnerInfo().getIsLicensed();
        ConfigChangeSet compatibilityChanges = new ConfigChangeSet(
                false,
                null,
                null,
                licensed,
                false,
                0,
                0,
                0,
                false,
                "",
                safeConfigs,
                safeModuleConfigs,
                safeChannels);
        var compatibilityError = ConfigCompatibilityValidator.validate(
                session.remoteState(),
                compatibilityChanges,
                session.remoteState().getConfigs());
        if (compatibilityError.isPresent()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(compatibilityError.get()));
        }

        return ensureSessionPasskey()
                .thenCompose(ignored -> sendChannelsSequentially(safeChannels, 0))
                .thenCompose(ignored -> sendSettingsTransaction(safeConfigs, safeModuleConfigs))
                .thenAccept(ignored -> {
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

    private CompletableFuture<Void> sendSettingsTransaction(List<ConfigProtos.Config> configs,
                                                             List<ModuleConfigProtos.ModuleConfig> moduleConfigs) {
        if (configs.isEmpty() && moduleConfigs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return sendAdminCommand(withPasskey(AdminProtos.AdminMessage.newBuilder()
                .setBeginEditSettings(true)).build(), "begin_edit_settings")
                .thenCompose(ignored -> sendConfigsSequentially(configs, 0))
                .thenCompose(ignored -> sendModuleConfigsSequentially(moduleConfigs, 0))
                .thenCompose(ignored -> sendAdminCommand(withPasskey(AdminProtos.AdminMessage.newBuilder()
                        .setCommitEditSettings(true)).build(), "commit_edit_settings"))
                .thenApply(ignored -> null);
    }

    /**
     * Sets a manual fixed position on the remote node.
     *
     * @param latDegrees latitude in decimal degrees
     * @param lonDegrees longitude in decimal degrees
     * @param altMeters altitude in meters
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> setFixedPosition(double latDegrees, double lonDegrees, int altMeters) {
        return ensureSessionPasskey().thenCompose(ignored -> {
            MeshProtos.Position position = MeshProtos.Position.newBuilder()
                    .setLatitudeI((int) Math.round(latDegrees * 1e7))
                    .setLongitudeI((int) Math.round(lonDegrees * 1e7))
                    .setAltitude(altMeters)
                    .setTime((int) (System.currentTimeMillis() / 1000))
                    .setLocationSource(MeshProtos.Position.LocSource.LOC_MANUAL)
                    .setAltitudeSource(MeshProtos.Position.AltSource.ALT_MANUAL)
                    .build();
            return sendAdminCommand(withPasskey(AdminProtos.AdminMessage.newBuilder()
                    .setSetFixedPosition(position)).build(), "set_fixed_position").thenApply(error -> null);
        });
    }

    /**
     * Removes the remote node's manual fixed position.
     *
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> removeFixedPosition() {
        return ensureSessionPasskey().thenCompose(ignored ->
                sendAdminCommand(withPasskey(AdminProtos.AdminMessage.newBuilder()
                        .setRemoveFixedPosition(true)).build(), "remove_fixed_position")
                        .thenApply(error -> null));
    }

    /**
     * Updates the notification ringtone stored on the remote node.
     *
     * @param ringtone RTTTL ringtone text, or an empty value to clear it
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> setRingtone(String ringtone) {
        return ensureSessionPasskey().thenCompose(ignored ->
                sendAdminCommand(withPasskey(AdminProtos.AdminMessage.newBuilder()
                        .setSetRingtoneMessage(ringtone != null ? ringtone : "")).build(), "set_ringtone")
                        .thenApply(error -> {
                            session.remoteState().setRingtone(ringtone);
                            return null;
                        }));
    }

    /**
     * Updates canned message module text on the remote node.
     *
     * @param messages canned message module payload
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> setCannedMessages(String messages) {
        return ensureSessionPasskey().thenCompose(ignored ->
                sendAdminCommand(withPasskey(AdminProtos.AdminMessage.newBuilder()
                        .setSetCannedMessageModuleMessages(messages != null ? messages : "")).build(),
                        "set_canned_messages")
                        .thenApply(error -> {
                            session.setCannedMessages(messages);
                            return null;
                        }));
    }

    /**
     * Requests a delayed reboot on the remote node.
     *
     * @param delaySeconds delay before reboot; firmware treats zero as cancel
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> reboot(int delaySeconds) {
        return sendPasskeyCommand(
                AdminProtos.AdminMessage.newBuilder().setRebootSeconds(delaySeconds),
                "reboot_seconds");
    }

    /**
     * Requests a delayed shutdown on the remote node.
     *
     * @param delaySeconds delay before shutdown; firmware treats zero as cancel
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> shutdown(int delaySeconds) {
        return sendPasskeyCommand(
                AdminProtos.AdminMessage.newBuilder().setShutdownSeconds(delaySeconds),
                "shutdown_seconds");
    }

    /**
     * Sets the remote node clock to a Unix epoch timestamp.
     *
     * @param epochSeconds Unix time in seconds
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> syncTime(long epochSeconds) {
        return sendPasskeyCommand(
                AdminProtos.AdminMessage.newBuilder().setSetTimeOnly((int) epochSeconds),
                "set_time_only");
    }

    /**
     * Requests a preferences backup on the remote node.
     *
     * @param location destination storage location
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> backupPreferences(AdminProtos.AdminMessage.BackupLocation location) {
        return sendPasskeyCommand(
                AdminProtos.AdminMessage.newBuilder().setBackupPreferences(location),
                "backup_preferences/" + location);
    }

    /**
     * Restores preferences from a backup on the remote node.
     *
     * @param location source storage location
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> restorePreferences(AdminProtos.AdminMessage.BackupLocation location) {
        return sendPasskeyCommand(
                AdminProtos.AdminMessage.newBuilder().setRestorePreferences(location),
                "restore_preferences/" + location);
    }

    /**
     * Removes a stored preferences backup from the remote node.
     *
     * @param location storage location to remove from
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> removeBackupPreferences(AdminProtos.AdminMessage.BackupLocation location) {
        return sendPasskeyCommand(
                AdminProtos.AdminMessage.newBuilder().setRemoveBackupPreferences(location),
                "remove_backup_preferences/" + location);
    }

    /**
     * Resets radio configuration on the remote node.
     *
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> factoryResetConfig() {
        return sendPasskeyCommand(
                AdminProtos.AdminMessage.newBuilder().setFactoryResetConfig(1),
                "factory_reset_config");
    }

    /**
     * Requests a full factory reset on the remote node.
     *
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> factoryResetDevice() {
        return sendPasskeyCommand(
                AdminProtos.AdminMessage.newBuilder().setFactoryResetDevice(1),
                "factory_reset_device");
    }

    /**
     * Resets the remote node database.
     *
     * @param preserveFavorites whether firmware should keep favorite nodes
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> resetNodeDb(boolean preserveFavorites) {
        return sendPasskeyCommand(
                AdminProtos.AdminMessage.newBuilder().setNodedbReset(preserveFavorites),
                "nodedb_reset");
    }

    /**
     * Requests the remote node to enter DFU mode.
     *
     * @return future completed when the command ACK is received
     */
    public CompletableFuture<Void> enterDfuMode() {
        return sendPasskeyCommand(
                AdminProtos.AdminMessage.newBuilder().setEnterDfuModeRequest(true),
                "enter_dfu_mode");
    }

    /**
     * Reloads the remote node connection-status payload.
     *
     * @return future completed with the matching admin response
     */
    public CompletableFuture<AdminProtos.AdminMessage> refreshConnectionStatus() {
        return sendAdminRequest(
                AdminProtos.AdminMessage.newBuilder().setGetDeviceConnectionStatusRequest(true).build(),
                AdminProtos.AdminMessage::hasGetDeviceConnectionStatusResponse,
                "get_connection_status");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onMeshPacket(MeshProtos.MeshPacket packet) {
        if (packet == null
                || packet.getFrom() != session.targetNodeNum()
                || !packet.hasDecoded()
                || packet.getDecoded().getPortnum() != Portnums.PortNum.ADMIN_APP) {
            return;
        }

        MeshProtos.Data data = packet.getDecoded();
        try {
            AdminProtos.AdminMessage adminMessage = AdminProtos.AdminMessage.parseFrom(data.getPayload());
            applyAdminResponse(adminMessage);
            completePendingResponse(data.getRequestId(), adminMessage);
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse remote ADMIN_APP response from !{}",
                    Integer.toHexString(packet.getFrom()), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        handler.removeListener(this);
        for (PendingAdminRequest pending : pendingRequests) {
            pending.completeExceptionally(new IllegalStateException("Remote admin session closed"));
        }
        pendingRequests.clear();
        pendingByPacketId.clear();
        scheduler.shutdownNow();
        session.close();
    }

    private CompletableFuture<Void> sendChannelsSequentially(List<ChannelProtos.Channel> channels, int index) {
        if (index >= channels.size()) {
            return CompletableFuture.completedFuture(null);
        }
        ChannelProtos.Channel channel = channels.get(index);
        return sendAdminCommand(withPasskey(AdminProtos.AdminMessage.newBuilder()
                .setSetChannel(channel)).build(), "set_channel/" + channel.getIndex())
                .thenCompose(ignored -> sendChannelsSequentially(channels, index + 1));
    }

    private CompletableFuture<Void> sendConfigsSequentially(List<ConfigProtos.Config> configs, int index) {
        if (index >= configs.size()) {
            return CompletableFuture.completedFuture(null);
        }
        ConfigProtos.Config config = configs.get(index);
        return sendAdminCommand(withPasskey(AdminProtos.AdminMessage.newBuilder()
                .setSetConfig(config)).build(), "set_config/" + config.getPayloadVariantCase())
                .thenCompose(ignored -> sendConfigsSequentially(configs, index + 1));
    }

    private CompletableFuture<Void> sendModuleConfigsSequentially(List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
                                                                   int index) {
        if (index >= moduleConfigs.size()) {
            return CompletableFuture.completedFuture(null);
        }
        ModuleConfigProtos.ModuleConfig moduleConfig = moduleConfigs.get(index);
        return sendAdminCommand(withPasskey(AdminProtos.AdminMessage.newBuilder()
                .setSetModuleConfig(moduleConfig)).build(),
                "set_module_config/" + moduleConfig.getPayloadVariantCase())
                .thenCompose(ignored -> sendModuleConfigsSequentially(moduleConfigs, index + 1));
    }

    private CompletableFuture<AdminProtos.AdminMessage> ensureSessionPasskey() {
        ByteString passkey = session.remoteState().getSessionPasskey();
        long ageMs = System.currentTimeMillis() - session.sessionPasskeyReceivedAtMillis();
        if (passkey != null && !passkey.isEmpty() && ageMs >= 0 && ageMs < PASSKEY_TTL_MS) {
            return CompletableFuture.completedFuture(AdminProtos.AdminMessage.getDefaultInstance());
        }
        return sendAdminRequest(
                AdminProtos.AdminMessage.newBuilder()
                        .setGetConfigRequest(AdminProtos.AdminMessage.ConfigType.SESSIONKEY_CONFIG)
                        .build(),
                adminMessage -> !adminMessage.getSessionPasskey().isEmpty(),
                "get_session_passkey");
    }

    private AdminProtos.AdminMessage.Builder withPasskey(AdminProtos.AdminMessage.Builder builder) {
        ByteString passkey = session.remoteState().getSessionPasskey();
        if (passkey != null && !passkey.isEmpty()) {
            builder.setSessionPasskey(passkey);
        }
        return builder;
    }

    private CompletableFuture<Void> sendPasskeyCommand(AdminProtos.AdminMessage.Builder builder, String description) {
        return ensureSessionPasskey().thenCompose(ignored ->
                sendAdminCommand(withPasskey(builder).build(), description).thenApply(error -> null));
    }

    private CompletableFuture<AdminProtos.AdminMessage> sendAdminRequest(AdminProtos.AdminMessage adminMessage,
                                                                          Predicate<AdminProtos.AdminMessage> matcher,
                                                                          String description) {
        ensureOpen();
        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        CompletableFuture<AdminProtos.AdminMessage> responseFuture = new CompletableFuture<>();
        PendingAdminRequest pending = new PendingAdminRequest(packetId, matcher, responseFuture, description);
        session.markQuerySent(description);
        pendingByPacketId.put(packetId, pending);
        pendingRequests.add(pending);
        pending.timeoutFuture = scheduler.schedule(() ->
                        pending.completeExceptionally(new TimeoutException(
                                "Timed out waiting for " + description + " from remote node")),
                QUERY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS);

        CompletableFuture<MeshProtos.Routing.Error> ackFuture = localState.registerPendingPacketAck(packetId);
        ackFuture.whenComplete((routingError, throwable) -> {
            if (throwable != null) {
                pending.completeExceptionally(throwable);
            } else if (routingError != null && routingError != MeshProtos.Routing.Error.NONE) {
                pending.completeExceptionally(new IllegalStateException(
                        description + " failed: " + routingError.name()));
            }
        });

        handler.sendToRadio(MeshProtos.ToRadio.newBuilder()
                .setPacket(buildRemoteAdminPacket(packetId, adminMessage, true))
                .build());
        return responseFuture;
    }

    private CompletableFuture<MeshProtos.Routing.Error> sendAdminCommand(AdminProtos.AdminMessage adminMessage,
                                                                          String description) {
        ensureOpen();
        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        CompletableFuture<MeshProtos.Routing.Error> ackFuture = localState.registerPendingPacketAck(packetId);
        ScheduledFuture<?> timeout = scheduler.schedule(() ->
                        ackFuture.completeExceptionally(new TimeoutException(
                                "Timed out waiting for " + description + " ACK from remote node")),
                MUTATION_ACK_TIMEOUT_MS,
                TimeUnit.MILLISECONDS);
        ackFuture.whenComplete((routingError, throwable) -> {
            timeout.cancel(false);
            if (throwable == null && routingError != MeshProtos.Routing.Error.NONE) {
                log.warn("Remote admin command {} failed for !{}: {}",
                        description, Integer.toHexString(session.targetNodeNum()), routingError);
            }
        });
        handler.sendToRadio(MeshProtos.ToRadio.newBuilder()
                .setPacket(buildRemoteAdminPacket(packetId, adminMessage, false))
                .build());
        return ackFuture.thenApply(error -> {
            if (error != MeshProtos.Routing.Error.NONE) {
                throw new CompletionException(new IllegalStateException(
                        description + " failed: " + error.name()));
            }
            return error;
        });
    }

    private MeshProtos.MeshPacket buildRemoteAdminPacket(int packetId,
                                                         AdminProtos.AdminMessage adminMessage,
                                                         boolean wantResponse) {
        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.ADMIN_APP)
                .setPayload(adminMessage.toByteString())
                .setWantResponse(wantResponse)
                .build();
        return MeshProtos.MeshPacket.newBuilder()
                .setFrom(localState.getMyNodeNum())
                .setTo(session.targetNodeNum())
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .setPkiEncrypted(true)
                .build();
    }

    private void completePendingResponse(int requestId, AdminProtos.AdminMessage adminMessage) {
        PendingAdminRequest pending = requestId != 0 ? pendingByPacketId.get(requestId) : null;
        if (pending == null) {
            pending = pendingRequests.stream()
                    .filter(candidate -> candidate.matches(adminMessage))
                    .findFirst()
                    .orElse(null);
        }
        if (pending != null) {
            pending.complete(adminMessage);
        } else {
            log.debug("Unmatched remote ADMIN_APP response from !{}: {}",
                    Integer.toHexString(session.targetNodeNum()),
                    adminMessage.getPayloadVariantCase());
        }
    }

    private void applyAdminResponse(AdminProtos.AdminMessage adminMessage) {
        if (!adminMessage.getSessionPasskey().isEmpty()) {
            session.remoteState().setSessionPasskey(adminMessage.getSessionPasskey());
            session.markSessionPasskeyReceived();
        }
        if (adminMessage.hasGetOwnerResponse()) {
            session.applyOwner(adminMessage.getGetOwnerResponse());
        } else if (adminMessage.hasGetDeviceMetadataResponse()) {
            session.remoteState().setDeviceMetadata(
                    adminMessage.getGetDeviceMetadataResponse());
            session.remoteState().setRegionPresetMap(
                    localState.getRegionPresetMap());
        } else if (adminMessage.hasGetRingtoneResponse()) {
            session.remoteState().setRingtone(adminMessage.getGetRingtoneResponse());
        } else if (adminMessage.hasGetCannedMessageModuleMessagesResponse()) {
            session.setCannedMessages(adminMessage.getGetCannedMessageModuleMessagesResponse());
        } else if (adminMessage.hasGetUiConfigResponse()) {
            session.setUiConfig(adminMessage.getGetUiConfigResponse());
        } else if (adminMessage.hasGetDeviceConnectionStatusResponse()) {
            session.setConnectionStatus(adminMessage.getGetDeviceConnectionStatusResponse());
        } else if (adminMessage.hasGetConfigResponse()) {
            session.remoteState().addConfig(adminMessage.getGetConfigResponse());
        } else if (adminMessage.hasGetModuleConfigResponse()) {
            session.remoteState().addModuleConfig(adminMessage.getGetModuleConfigResponse());
        } else if (adminMessage.hasGetChannelResponse()) {
            session.remoteState().addChannel(adminMessage.getGetChannelResponse());
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Remote admin session is closed");
        }
        if (localState.getMyNodeNum() == 0) {
            throw new IllegalStateException("Local node number is unknown");
        }
    }

    private void pauseBetweenQueries() {
        try {
            Thread.sleep(QUERY_SPACING_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
        ensureOpen();
    }

    private static RequestPlan deviceMetadataRequestPlan() {
        return new RequestPlan(
                "get_device_metadata",
                AdminProtos.AdminMessage.newBuilder()
                        .setGetDeviceMetadataRequest(true)
                        .build(),
                AdminProtos.AdminMessage::hasGetDeviceMetadataResponse);
    }

    private static List<RequestPlan> buildSnapshotRequestPlans(
            FirmwareCapabilities capabilities) {
        List<RequestPlan> plans = new ArrayList<>();
        plans.add(new RequestPlan(
                "get_owner",
                AdminProtos.AdminMessage.newBuilder().setGetOwnerRequest(true).build(),
                AdminProtos.AdminMessage::hasGetOwnerResponse));
        plans.add(new RequestPlan(
                "get_ringtone",
                AdminProtos.AdminMessage.newBuilder().setGetRingtoneRequest(true).build(),
                AdminProtos.AdminMessage::hasGetRingtoneResponse));
        plans.add(new RequestPlan(
                "get_canned_messages",
                AdminProtos.AdminMessage.newBuilder().setGetCannedMessageModuleMessagesRequest(true).build(),
                AdminProtos.AdminMessage::hasGetCannedMessageModuleMessagesResponse));
        plans.add(new RequestPlan(
                "get_ui_config",
                AdminProtos.AdminMessage.newBuilder().setGetUiConfigRequest(true).build(),
                AdminProtos.AdminMessage::hasGetUiConfigResponse));
        plans.add(new RequestPlan(
                "get_connection_status",
                AdminProtos.AdminMessage.newBuilder().setGetDeviceConnectionStatusRequest(true).build(),
                AdminProtos.AdminMessage::hasGetDeviceConnectionStatusResponse));

        for (AdminProtos.AdminMessage.ConfigType type : editableConfigTypes()) {
            plans.add(new RequestPlan(
                    configQueryKey(type),
                    AdminProtos.AdminMessage.newBuilder().setGetConfigRequest(type).build(),
                    AdminProtos.AdminMessage::hasGetConfigResponse));
        }
        for (AdminProtos.AdminMessage.ModuleConfigType type :
                editableModuleConfigTypes(capabilities)) {
            plans.add(new RequestPlan(
                    moduleConfigQueryKey(type),
                    AdminProtos.AdminMessage.newBuilder().setGetModuleConfigRequest(type).build(),
                    AdminProtos.AdminMessage::hasGetModuleConfigResponse));
        }
        for (int index = 0; index < 8; index++) {
            int channelRequest = index + 1;
            plans.add(new RequestPlan(
                    "get_channel/" + index,
                    AdminProtos.AdminMessage.newBuilder().setGetChannelRequest(channelRequest).build(),
                    AdminProtos.AdminMessage::hasGetChannelResponse));
        }
        return plans;
    }

    private static void notifyProgress(Consumer<QueryProgress> consumer,
                                       String key,
                                       RemoteAdminSession.QueryState state,
                                       int completed,
                                       int total) {
        if (consumer != null) {
            consumer.accept(new QueryProgress(key, state, completed, total));
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private record RequestPlan(String key,
                               AdminProtos.AdminMessage message,
                               Predicate<AdminProtos.AdminMessage> matcher) {}

    /**
     * Progress event emitted while a remote snapshot is being loaded.
     *
     * @param key stable query key
     * @param state current state for the query
     * @param completed number of finished queries
     * @param total total number of planned queries
     */
    public record QueryProgress(String key,
                                RemoteAdminSession.QueryState state,
                                int completed,
                                int total) {}

    private final class PendingAdminRequest {
        private final int packetId;
        private final Predicate<AdminProtos.AdminMessage> matcher;
        private final CompletableFuture<AdminProtos.AdminMessage> future;
        private final String description;
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> timeoutFuture;

        private PendingAdminRequest(int packetId,
                                    Predicate<AdminProtos.AdminMessage> matcher,
                                    CompletableFuture<AdminProtos.AdminMessage> future,
                                    String description) {
            this.packetId = packetId;
            this.matcher = matcher;
            this.future = future;
            this.description = description;
        }

        private boolean matches(AdminProtos.AdminMessage adminMessage) {
            return matcher.test(adminMessage);
        }

        private void complete(AdminProtos.AdminMessage adminMessage) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            remove();
            session.markQueryReceived(description);
            future.complete(adminMessage);
        }

        private void completeExceptionally(Throwable error) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            remove();
            session.markQueryFailed(description, rootMessage(error));
            future.completeExceptionally(error);
        }

        private void remove() {
            pendingByPacketId.remove(packetId, this);
            pendingRequests.remove(this);
            ScheduledFuture<?> timeout = timeoutFuture;
            if (timeout != null) {
                timeout.cancel(false);
            }
            log.trace("Remote admin request completed: {}", description);
        }
    }
}
