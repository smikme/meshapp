package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.system.FormManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates saving changed configuration sections to the connected radio.
 * This controller owns the protocol transaction, transport-aware ACK handling,
 * reconnect handoff, and navigation lock used while a rebooting device returns.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigSaveController {

    private static final Logger log = LoggerFactory.getLogger(
        ConfigSaveController.class
    );

    private final Host host;
    private volatile String navigationLockConnectionId;
    private volatile boolean navigationLockAwaitingReconnect;
    private volatile boolean navigationLockDisconnectObserved;

    /**
     * Creates a controller bound to a settings form host.
     *
     * @param host host callbacks
     */
    public ConfigSaveController(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Saves pending editor changes to the radio.
     */
    public void save() {
        DeviceState actionState = host.state();
        ProtocolHandler actionHandler = host.handler();
        if (actionState == null || actionHandler == null) {
            host.setStatus(I18n.t("settings.status.noRadio"));
            return;
        }

        ConnectionEntry activeEntry = host.findActiveConnectionEntry();
        if (host.isConfigExchangeInProgress(activeEntry)) {
            host.watchConfigExchangeCompletion(activeEntry);
            host.setStatus(I18n.t("settings.status.waitConfigRead"));
            host.setSaveConfigButtonDisabled(true);
            return;
        }

        TreeItem<ConfigTreeItem> root = host.currentEditorRoot();
        if (root == null) {
            return;
        }

        ConfigChangeSet changes = ConfigChangeCollector.collect(
            root,
            host.originalConfigs(),
            host.originalModuleConfigs(),
            host.collectModifiedChannels(),
            actionState.getOwnerInfo(),
            actionState.getNodeDb().get(actionState.getMyNodeNum())
        );

        if (!changes.hasChanges()) {
            host.setStatus(I18n.t("settings.config.status.noChanges"));
            return;
        }

        beginNavigationBlock(activeEntry);
        host.setSaveConfigButtonDisabled(true);
        host.setStatus(I18n.t("settings.status.requestSessionKey"));
        dispatchWhenSessionKeyIsReady(
            new SaveRequest(activeEntry, actionState, actionHandler, changes)
        );
    }

    /**
     * Completes the save navigation lock after the rebooted device reconnects.
     *
     * @param activeEntry              current active connection
     * @param configExchangeInProgress whether initial config exchange is still running
     */
    public void maybeFinishNavigationBlockAfterReconnect(
        ConnectionEntry activeEntry,
        boolean configExchangeInProgress
    ) {
        String lockedConnectionId = navigationLockConnectionId;
        if (
            lockedConnectionId == null ||
            !navigationLockAwaitingReconnect
        ) {
            return;
        }

        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry lockedEntry = findConnectionEntryById(
            lockedConnectionId
        );
        boolean activeOrPending = manager.isConnectionActiveOrPending(
            lockedConnectionId
        );
        if (
            lockedEntry == null ||
            (!lockedEntry.isReconnecting() && !activeOrPending)
        ) {
            finishNavigationBlock();
            return;
        }

        if (
            lockedEntry.isReconnecting() ||
            !activeOrPending ||
            activeEntry == null ||
            !activeEntry.isConnected()
        ) {
            navigationLockDisconnectObserved = true;
        }

        if (
            navigationLockDisconnectObserved &&
            activeEntry != null &&
            lockedConnectionId.equals(activeEntry.getId()) &&
            activeEntry.isConnected() &&
            !configExchangeInProgress
        ) {
            finishNavigationBlock();
        }
    }

    private void dispatchWhenSessionKeyIsReady(SaveRequest request) {
        AtomicBoolean saveDispatchStarted = new AtomicBoolean(false);
        Runnable[] listenerHolder = new Runnable[1];
        listenerHolder[0] = () ->
            Platform.runLater(() -> {
                request.state().removeOwnerInfoListener(listenerHolder[0]);
                if (saveDispatchStarted.compareAndSet(false, true)) {
                    sendConfigChanges(request);
                }
            });
        request.state().addOwnerInfoListener(listenerHolder[0]);

        Thread timeoutThread = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                return;
            }
            Platform.runLater(() -> {
                request.state().removeOwnerInfoListener(listenerHolder[0]);
                if (saveDispatchStarted.compareAndSet(false, true)) {
                    host.setStatus(
                        I18n.t("settings.status.sendingWithoutSessionKey")
                    );
                    sendConfigChanges(request);
                }
            });
        }, "config-save-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        try {
            MessageService.requestSessionPasskey(
                request.handler(),
                request.state()
            );
        } catch (RuntimeException e) {
            request.state().removeOwnerInfoListener(listenerHolder[0]);
            finishNavigationBlock();
            throw e;
        }
    }

    private void sendConfigChanges(SaveRequest request) {
        host.setStatus(I18n.t("settings.config.status.sendingSettings"));
        ConfigChangeSet changes = request.changes();
        ConnectionEntry activeEntry = request.activeEntry();
        ConnectionType activeTransport = request.transport();
        boolean requiresReconnect = changes.requiresReconnect();
        ReconnectHandoff handoff = prepareReconnectHandoff(request);

        sendVirtualSections(request);

        if (changes.hasPacketConfigChanges()) {
            sendPacketConfigChanges(request, activeTransport, handoff);
        } else if (requiresReconnect && activeEntry != null) {
            completeVirtualReconnectSave(request, activeTransport, handoff);
        } else {
            completeSaveWithoutReconnect(changes.totalChanges());
        }
    }

    private ReconnectHandoff prepareReconnectHandoff(SaveRequest request) {
        if (
            !request.changes().requiresReconnect() ||
            request.activeEntry() == null
        ) {
            return new ReconnectHandoff(-1);
        }

        ConnectionEntry activeEntry = request.activeEntry();
        long generation = ConnectionManager
            .getInstance()
            .getConnectionGeneration(activeEntry.getId());
        ConnectionManager.getInstance().expectDeviceReboot(activeEntry.getId());
        if (request.changes().ownerModified()) {
            markNavigationBlockAwaitingReconnect(activeEntry);
        }
        return new ReconnectHandoff(generation);
    }

    private void sendVirtualSections(SaveRequest request) {
        ConfigChangeSet changes = request.changes();
        if (
            changes.ownerModified() &&
            changes.longName() != null &&
            changes.shortName() != null
        ) {
            sendOwnerInfo(request);
        }
        if (changes.positionModified()) {
            sendFixedPosition(request);
        }
        if (changes.ringtoneModified()) {
            sendRingtone(request);
        }
    }

    private void sendOwnerInfo(SaveRequest request) {
        ConfigChangeSet changes = request.changes();
        DeviceState actionState = request.state();
        MessageService.setOwnerInfo(
            request.handler(),
            actionState,
            changes.longName(),
            changes.shortName(),
            changes.isLicensed(),
            actionState.getSessionPasskey()
        );
        MeshProtos.User currentOwnerInfo = actionState.getOwnerInfo();
        MeshProtos.User updatedOwnerInfo = (currentOwnerInfo != null
            ? currentOwnerInfo.toBuilder()
            : MeshProtos.User.newBuilder()
        )
            .setLongName(changes.longName())
            .setShortName(changes.shortName())
            .setIsLicensed(changes.isLicensed())
            .build();
        actionState.setOwnerInfo(updatedOwnerInfo);

        NodeData myNode = actionState
            .getNodeDb()
            .get(actionState.getMyNodeNum());
        if (myNode != null) {
            myNode.setLongName(changes.longName());
            myNode.setShortName(changes.shortName());
            myNode.setLicensed(changes.isLicensed());
            actionState.fireNodeUpdateListeners(actionState.getMyNodeNum());
        }
    }

    private void sendFixedPosition(SaveRequest request) {
        ConfigChangeSet changes = request.changes();
        DeviceState actionState = request.state();
        if (
            changes.latitude() == 0 &&
            changes.longitude() == 0 &&
            changes.altitude() == 0
        ) {
            MessageService.removeFixedPosition(request.handler(), actionState);
            return;
        }

        MessageService.setFixedPosition(
            request.handler(),
            actionState,
            changes.latitude(),
            changes.longitude(),
            changes.altitude()
        );
        actionState.setPendingFixedPosition(
            changes.latitude(),
            changes.longitude(),
            changes.altitude()
        );
        updateNodeFixedPosition(actionState, changes);
    }

    private void updateNodeFixedPosition(
        DeviceState actionState,
        ConfigChangeSet changes
    ) {
        NodeData myNode = actionState
            .getNodeDb()
            .get(actionState.getMyNodeNum());
        if (myNode == null) {
            return;
        }

        // Round-trip through int to show what the device will actually store.
        myNode.setLatitude(Math.round(changes.latitude() * 1e7) * 1e-7);
        myNode.setLongitude(Math.round(changes.longitude() * 1e7) * 1e-7);
        myNode.setAltitude(changes.altitude());
        actionState.fireNodeUpdateListeners(actionState.getMyNodeNum());
    }

    private void sendRingtone(SaveRequest request) {
        String ringtone = request.changes().ringtone() != null
            ? request.changes().ringtone()
            : "";
        ConfigSavePolicy.observeOptionalAck(
            MessageService.setRingtone(
                request.handler(),
                request.state(),
                ringtone
            ),
            "setRingtone",
            log
        );
        request.state().setRingtone(ringtone);
    }

    private void sendPacketConfigChanges(
        SaveRequest request,
        ConnectionType activeTransport,
        ReconnectHandoff handoff
    ) {
        ConfigChangeSet changes = request.changes();
        List<Runnable> tasks = buildConfigSaveTasks(
            activeTransport,
            request.handler(),
            request.state(),
            changes.configs(),
            changes.moduleConfigs(),
            changes.channels(),
            changes.ownerModified(),
            changes.positionModified(),
            changes.requiresReconnect()
        );
        AtomicBoolean saveFailed = new AtomicBoolean(false);
        AtomicBoolean saveCompletionAnnounced = new AtomicBoolean(false);
        long rebootHandoffDelay = ConfigSavePolicy.configSaveRebootHandoffDelayMs(
            activeTransport
        );

        Thread saveThread = new Thread(() -> {
            try {
                runConfigSaveTasks(
                    request,
                    activeTransport,
                    tasks,
                    saveFailed,
                    saveCompletionAnnounced
                );
                if (!saveFailed.get()) {
                    completePacketSaveHandoff(
                        request,
                        activeTransport,
                        handoff,
                        rebootHandoffDelay,
                        saveFailed
                    );
                }
            } catch (InterruptedException e) {
                handleInterruptedSave(request, e);
            } catch (Exception e) {
                handleDisconnectFailure(request, e);
            }
        }, "config-save-sender");
        saveThread.setDaemon(true);
        saveThread.start();
    }

    private void runConfigSaveTasks(
        SaveRequest request,
        ConnectionType activeTransport,
        List<Runnable> tasks,
        AtomicBoolean saveFailed,
        AtomicBoolean saveCompletionAnnounced
    ) throws InterruptedException {
        for (int i = 0; i < tasks.size(); i++) {
            if (saveFailed.get()) {
                return;
            }
            try {
                tasks.get(i).run();
            } catch (Exception e) {
                handleFailedSaveTask(
                    request,
                    i,
                    e,
                    saveFailed,
                    saveCompletionAnnounced
                );
                return;
            }

            if (i + 1 < tasks.size()) {
                waitBeforeNextSaveTask(activeTransport, i, tasks.size());
            }
        }

        if (saveFailed.get()) {
            return;
        }

        saveCompletionAnnounced.set(true);
        if (request.changes().requiresReconnect()) {
            markNavigationBlockAwaitingReconnect(request.activeEntry());
        }
        Platform.runLater(() ->
            completeEditorSave(
                activeTransport,
                request.changes().totalChanges(),
                request.changes().requiresReconnect()
            )
        );
    }

    private void handleFailedSaveTask(
        SaveRequest request,
        int taskIndex,
        Exception error,
        AtomicBoolean saveFailed,
        AtomicBoolean saveCompletionAnnounced
    ) {
        if (saveCompletionAnnounced.get()) {
            log.warn(
                "Config save task {} failed after completion was announced",
                taskIndex,
                error
            );
            return;
        }
        saveFailed.set(true);
        log.error(
            "Config save task {} failed: {}",
            taskIndex,
            error.getMessage() != null
                ? error.getMessage()
                : error.getClass().getSimpleName(),
            error
        );
        clearExpectedRebootIfNeeded(request);
        Platform.runLater(() -> {
            finishNavigationBlock();
            host.setSaveConfigButtonDisabled(false);
            host.setStatus(
                I18n.t(
                    "settings.config.status.saveError",
                    host.errorDetail(error)
                )
            );
        });
    }

    private void waitBeforeNextSaveTask(
        ConnectionType activeTransport,
        int taskIndex,
        int totalTaskCount
    ) throws InterruptedException {
        long interTaskDelayMs = ConfigSavePolicy.interTaskDelayMs(
            activeTransport,
            taskIndex,
            totalTaskCount
        );
        log.debug(
            "Config save: waiting {}ms before {}",
            interTaskDelayMs,
            taskIndex + 1 == totalTaskCount - 1
                ? "commitEditSettings"
                : "next step"
        );
        Thread.sleep(interTaskDelayMs);
    }

    private void completePacketSaveHandoff(
        SaveRequest request,
        ConnectionType activeTransport,
        ReconnectHandoff handoff,
        long rebootHandoffDelay,
        AtomicBoolean saveFailed
    ) throws InterruptedException {
        if (!request.changes().requiresReconnect()) {
            finishNavigationBlock();
            return;
        }
        Thread.sleep(rebootHandoffDelay);
        if (saveFailed.get()) {
            return;
        }
        handoffToReconnect(request, activeTransport, handoff);
    }

    private void completeVirtualReconnectSave(
        SaveRequest request,
        ConnectionType activeTransport,
        ReconnectHandoff handoff
    ) {
        Platform.runLater(() ->
            completeEditorSave(
                activeTransport,
                request.changes().totalChanges(),
                true
            )
        );

        Thread reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(
                    ConfigSavePolicy.configSaveRebootHandoffDelayMs(
                        activeTransport
                    )
                );
                log.info(
                    "Config save: handoff to reboot reconnect flow after owner info update (transport={})",
                    activeTransport
                );
                handoffToReconnect(request, activeTransport, handoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Config save owner reconnect handoff interrupted");
                clearExpectedRebootIfNeeded(request);
                finishNavigationBlock();
            } catch (Exception e) {
                log.error("Config save: owner reconnect handoff failed", e);
                clearExpectedRebootIfNeeded(request);
                finishNavigationBlock();
            }
        }, "config-save-owner-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private void completeSaveWithoutReconnect(int totalChanges) {
        completeEditorSave(ConnectionType.TCP, totalChanges, false);
        finishNavigationBlock();
    }

    private void completeEditorSave(
        ConnectionType activeTransport,
        int totalChanges,
        boolean requiresReconnect
    ) {
        host.resetModifiedFlags(host.currentEditorRoot());
        host.setOriginalChannels(host.workingChannelsSnapshot());
        host.setSaveConfigButtonDisabled(false);
        if (requiresReconnect) {
            host.setStatus(
                configSaveReconnectMessage(activeTransport, totalChanges)
            );
            return;
        }
        host.setStatus(
            I18n.t("settings.config.status.sentSections", totalChanges)
        );
    }

    private void handoffToReconnect(
        SaveRequest request,
        ConnectionType activeTransport,
        ReconnectHandoff handoff
    ) {
        if (request.activeEntry() != null) {
            log.info(
                "Config save: handoff to reboot reconnect flow (transport={})",
                activeTransport
            );
            boolean handoffStarted = ConnectionManager
                .getInstance()
                .disconnectForDeviceReboot(
                    request.activeEntry().getId(),
                    handoff.reconnectGeneration()
                );
            if (!handoffStarted) {
                return;
            }
        } else {
            log.warn("Config save: no active connection to hand off after commit");
            finishNavigationBlock();
        }
        Platform.runLater(() -> {
            host.clearConnectionContext(request.state(), request.handler());
            host.reloadConfigTree();
        });
    }

    private void handleInterruptedSave(
        SaveRequest request,
        InterruptedException error
    ) {
        Thread.currentThread().interrupt();
        log.warn("Config save thread interrupted");
        clearExpectedRebootIfNeeded(request);
        finishNavigationBlock();
    }

    private void handleDisconnectFailure(SaveRequest request, Exception error) {
        log.error("Config save: disconnect failed", error);
        clearExpectedRebootIfNeeded(request);
        finishNavigationBlock();
    }

    private List<Runnable> buildConfigSaveTasks(
        ConnectionType activeTransport,
        ProtocolHandler actionHandler,
        DeviceState actionState,
        List<ConfigProtos.Config> configs,
        List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
        List<ChannelProtos.Channel> channels,
        boolean ownerModified,
        boolean positionModified,
        boolean requiresReconnect
    ) {
        List<Runnable> tasks = new ArrayList<>();
        tasks.addAll(
            channels
                .stream()
                .map(channel ->
                    channelSaveTask(
                        activeTransport,
                        actionHandler,
                        actionState,
                        channel
                    )
                )
                .toList()
        );

        boolean useImplicitBleModuleSave =
            ConfigSavePolicy.shouldUseImplicitBleModuleSave(
                activeTransport,
                ownerModified,
                positionModified,
                configs,
                moduleConfigs
            ) && channels.isEmpty();
        if (useImplicitBleModuleSave) {
            moduleConfigs
                .stream()
                .findFirst()
                .map(moduleConfig ->
                    implicitBleModuleSaveTask(
                        actionHandler,
                        actionState,
                        moduleConfig
                    )
                )
                .ifPresent(tasks::add);
        } else if (requiresReconnect) {
            tasks.add(
                beginEditSettingsTask(
                    activeTransport,
                    actionHandler,
                    actionState
                )
            );
            int totalMutatingSteps = configs.size() + moduleConfigs.size();
            tasks.addAll(
                Stream
                    .concat(
                        IntStream
                            .range(0, configs.size())
                            .mapToObj(index ->
                                configSaveTask(
                                    activeTransport,
                                    actionHandler,
                                    actionState,
                                    configs.get(index),
                                    index + 1 < totalMutatingSteps
                                )
                            ),
                        IntStream
                            .range(0, moduleConfigs.size())
                            .mapToObj(index ->
                                moduleConfigSaveTask(
                                    activeTransport,
                                    actionHandler,
                                    actionState,
                                    moduleConfigs.get(index),
                                    configs.size() + index + 1 <
                                    totalMutatingSteps
                                )
                            )
                    )
                    .toList()
            );
            tasks.add(
                commitEditSettingsTask(
                    activeTransport,
                    actionHandler,
                    actionState
                )
            );
        }
        return tasks;
    }

    private Runnable channelSaveTask(
        ConnectionType activeTransport,
        ProtocolHandler actionHandler,
        DeviceState actionState,
        ChannelProtos.Channel channel
    ) {
        return () -> {
            String stepName = "setChannel/" + channel.getIndex();
            log.info(
                "Config save: setChannel index={} role={}",
                channel.getIndex(),
                channel.getRole()
            );
            ConfigSavePolicy.waitForTransportRequiredAck(
                activeTransport,
                MessageService.setChannel(
                    actionHandler,
                    actionState,
                    channel,
                    actionState.getSessionPasskey()
                ),
                stepName,
                log
            );
            actionState.updateChannel(channel);
        };
    }

    private Runnable implicitBleModuleSaveTask(
        ProtocolHandler actionHandler,
        DeviceState actionState,
        ModuleConfigProtos.ModuleConfig mqttConfig
    ) {
        return () -> {
            String stepName =
                "setModuleConfig/" + mqttConfig.getPayloadVariantCase();
            log.info(
                "Config save: implicit BLE {} variant={} size={}",
                stepName,
                mqttConfig.getPayloadVariantCase(),
                mqttConfig.getSerializedSize()
            );
            CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                MessageService.setModuleConfig(
                    actionHandler,
                    actionState,
                    mqttConfig
                );
            ConfigSavePolicy.observeDeferredAck(ackFuture, stepName, log);
        };
    }

    private Runnable beginEditSettingsTask(
        ConnectionType activeTransport,
        ProtocolHandler actionHandler,
        DeviceState actionState
    ) {
        return () -> {
            log.info("Config save: beginEditSettings");
            ConfigSavePolicy.waitForTransportRequiredAck(
                activeTransport,
                MessageService.beginEditSettings(actionHandler, actionState),
                "beginEditSettings",
                log
            );
        };
    }

    private Runnable configSaveTask(
        ConnectionType activeTransport,
        ProtocolHandler actionHandler,
        DeviceState actionState,
        ConfigProtos.Config config,
        boolean waitForAckBeforeCommit
    ) {
        return () -> {
            String stepName = "setConfig/" + config.getPayloadVariantCase();
            log.info(
                "Config save: setConfig variant={} size={}",
                config.getPayloadVariantCase(),
                config.getSerializedSize()
            );
            CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                MessageService.setConfig(actionHandler, actionState, config);
            observeOrWaitForMutatingStepAck(
                activeTransport,
                ackFuture,
                stepName,
                waitForAckBeforeCommit
            );
        };
    }

    private Runnable moduleConfigSaveTask(
        ConnectionType activeTransport,
        ProtocolHandler actionHandler,
        DeviceState actionState,
        ModuleConfigProtos.ModuleConfig moduleConfig,
        boolean waitForAckBeforeCommit
    ) {
        return () -> {
            String stepName =
                "setModuleConfig/" + moduleConfig.getPayloadVariantCase();
            log.info(
                "Config save: setModuleConfig variant={} size={}",
                moduleConfig.getPayloadVariantCase(),
                moduleConfig.getSerializedSize()
            );
            CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                MessageService.setModuleConfig(
                    actionHandler,
                    actionState,
                    moduleConfig
                );
            observeOrWaitForMutatingStepAck(
                activeTransport,
                ackFuture,
                stepName,
                waitForAckBeforeCommit
            );
        };
    }

    private Runnable commitEditSettingsTask(
        ConnectionType activeTransport,
        ProtocolHandler actionHandler,
        DeviceState actionState
    ) {
        return () -> {
            String stepName = "commitEditSettings";
            log.info("Config save: commitEditSettings");
            CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                MessageService.commitEditSettings(actionHandler, actionState);
            ConfigSavePolicy.handleCommitAck(
                activeTransport,
                ackFuture,
                stepName,
                log
            );
        };
    }

    private void observeOrWaitForMutatingStepAck(
        ConnectionType activeTransport,
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName,
        boolean waitForAckBeforeCommit
    ) {
        if (waitForAckBeforeCommit) {
            ConfigSavePolicy.waitForTransportRequiredAck(
                activeTransport,
                ackFuture,
                stepName,
                log
            );
            return;
        }
        ConfigSavePolicy.observeDeferredAck(ackFuture, stepName, log);
    }

    private void beginNavigationBlock(ConnectionEntry activeEntry) {
        navigationLockConnectionId =
            activeEntry != null ? activeEntry.getId() : null;
        navigationLockAwaitingReconnect = false;
        navigationLockDisconnectObserved = false;
        FormManager.setConfigSaveNavigationBlocked(true);
    }

    private void markNavigationBlockAwaitingReconnect(
        ConnectionEntry activeEntry
    ) {
        if (activeEntry == null) {
            return;
        }
        String lockedConnectionId = navigationLockConnectionId;
        if (
            lockedConnectionId == null ||
            lockedConnectionId.equals(activeEntry.getId())
        ) {
            navigationLockConnectionId = activeEntry.getId();
            navigationLockAwaitingReconnect = true;
            navigationLockDisconnectObserved = false;
        }
    }

    private void finishNavigationBlock() {
        navigationLockConnectionId = null;
        navigationLockAwaitingReconnect = false;
        navigationLockDisconnectObserved = false;
        FormManager.setConfigSaveNavigationBlocked(false);
    }

    private ConnectionEntry findConnectionEntryById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return ConnectionManager
            .getInstance()
            .getEntries()
            .stream()
            .filter(entry -> id.equals(entry.getId()))
            .findFirst()
            .orElse(null);
    }

    private void clearExpectedRebootIfNeeded(SaveRequest request) {
        if (request.activeEntry() != null) {
            ConnectionManager
                .getInstance()
                .clearExpectedDeviceReboot(request.activeEntry().getId());
        }
    }

    private String configSaveReconnectMessage(
        ConnectionType transport,
        int totalChanges
    ) {
        return transport == ConnectionType.BLE
            ? I18n.t("settings.config.status.sentSectionsBle", totalChanges)
            : I18n.t(
                  "settings.config.status.sentSectionsReconnect",
                  totalChanges
              );
    }

    /**
     * Host callbacks implemented by the settings form.
     */
    public interface Host {
        DeviceState state();
        ProtocolHandler handler();
        ConnectionEntry findActiveConnectionEntry();
        boolean isConfigExchangeInProgress(ConnectionEntry entry);
        void watchConfigExchangeCompletion(ConnectionEntry entry);
        TreeItem<ConfigTreeItem> currentEditorRoot();
        List<ConfigProtos.Config> originalConfigs();
        List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs();
        List<ChannelProtos.Channel> collectModifiedChannels();
        List<ChannelProtos.Channel> workingChannelsSnapshot();
        void setOriginalChannels(List<ChannelProtos.Channel> channels);
        void resetModifiedFlags(TreeItem<ConfigTreeItem> item);
        void setSaveConfigButtonDisabled(boolean disabled);
        void setStatus(String status);
        void clearConnectionContext(
            DeviceState expectedState,
            ProtocolHandler expectedHandler
        );
        void reloadConfigTree();
        String errorDetail(Exception error);
    }

    private record SaveRequest(
        ConnectionEntry activeEntry,
        DeviceState state,
        ProtocolHandler handler,
        ConfigChangeSet changes
    ) {
        ConnectionType transport() {
            return activeEntry != null
                ? activeEntry.getEffectiveType()
                : ConnectionType.TCP;
        }
    }

    private record ReconnectHandoff(long reconnectGeneration) {}
}
