package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.utils.TimeZoneSyncUtil;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates device time synchronization from the settings form.
 * The controller separates user-facing flow from the two transport flows:
 * time-only sync and time-plus-GMT sync that requires a config transaction and
 * reconnect handoff.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class DeviceTimeSyncController {

    private static final Logger log = LoggerFactory.getLogger(
        DeviceTimeSyncController.class
    );

    private final Host host;
    private volatile String pendingTimeOnlySyncConnectionId;

    /**
     * Creates a controller bound to a settings form host.
     *
     * @param host host callbacks
     */
    public DeviceTimeSyncController(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Starts synchronization of the radio date/time with the current computer.
     */
    public void syncWithPc() {
        host.refreshConnection();
        DeviceState state = host.state();
        ProtocolHandler handler = host.handler();
        if (state == null || handler == null) {
            host.setStatus(I18n.t("settings.status.noRadio"));
            host.setSyncDateTimeButtonDisabled(true);
            return;
        }

        ConnectionEntry activeEntry = host.findActiveConnectionEntry();
        if (activeEntry == null) {
            host.setStatus(I18n.t("settings.status.noActiveRadio"));
            host.setSyncDateTimeButtonDisabled(true);
            return;
        }
        if (host.isConfigExchangeInProgress(activeEntry)) {
            host.watchConfigExchangeCompletion(activeEntry);
            host.setStatus(I18n.t("settings.status.waitConfigRead"));
            return;
        }

        ConfigProtos.Config deviceConfig = host.findLoadedDeviceConfig();
        if (deviceConfig == null || !deviceConfig.hasDevice()) {
            host.setStatus(I18n.t("settings.status.deviceSectionMissing"));
            return;
        }

        TimeSyncPlan plan = buildTimeSyncPlan(
            activeEntry,
            state,
            handler,
            deviceConfig
        );
        Runnable startSync = () -> requestDateTimeSync(plan.request());

        if (!plan.gmtMatches()) {
            confirmGmtMismatch(plan, startSync);
            return;
        }
        startSync.run();
    }

    /**
     * Completes deferred time-only sync after a GMT update reconnect.
     */
    public void maybeResumeDeferredTimeOnlySync() {
        String pendingConnectionId = pendingTimeOnlySyncConnectionId;
        if (pendingConnectionId == null || pendingConnectionId.isBlank()) {
            return;
        }

        ConnectionEntry activeEntry = host.findActiveConnectionEntry();
        if (
            activeEntry == null ||
            !pendingConnectionId.equals(activeEntry.getId())
        ) {
            return;
        }
        if (host.isConfigExchangeInProgress(activeEntry)) {
            host.watchConfigExchangeCompletion(activeEntry);
            return;
        }

        host.refreshConnection();
        DeviceState state = host.state();
        ProtocolHandler handler = host.handler();
        if (state == null || handler == null) {
            return;
        }

        pendingTimeOnlySyncConnectionId = null;
        Instant now = Instant.now();
        String systemGmtLabel = TimeZoneSyncUtil.formatGmtOffset(
            TimeZoneSyncUtil.systemOffset(now)
        );
        log.info(
            "Time sync: reconnect complete, repeating set_time_only for '{}'",
            activeEntry.getName()
        );
        host.setStatus(I18n.t("settings.timeSync.reconnected"));
        requestDateTimeSync(
            new TimeSyncRequest(
                activeEntry,
                state,
                handler,
                null,
                true,
                null,
                systemGmtLabel
            )
        );
    }

    private TimeSyncPlan buildTimeSyncPlan(
        ConnectionEntry activeEntry,
        DeviceState state,
        ProtocolHandler handler,
        ConfigProtos.Config deviceConfig
    ) {
        Instant now = Instant.now();
        ZoneOffset systemOffset = TimeZoneSyncUtil.systemOffset(now);
        ZoneOffset nodeOffset = TimeZoneSyncUtil
            .resolveCurrentOffset(deviceConfig.getDevice().getTzdef(), now)
            .orElse(null);
        boolean gmtMatches = systemOffset.equals(nodeOffset);
        return new TimeSyncPlan(
            new TimeSyncRequest(
                activeEntry,
                state,
                handler,
                deviceConfig,
                gmtMatches,
                TimeZoneSyncUtil.buildFixedGmtTzDef(systemOffset),
                TimeZoneSyncUtil.formatGmtOffset(systemOffset)
            ),
            Optional
                .ofNullable(nodeOffset)
                .map(TimeZoneSyncUtil::formatGmtOffset)
                .orElse(I18n.t("settings.timeSync.unknown"))
        );
    }

    private void confirmGmtMismatch(
        TimeSyncPlan plan,
        Runnable startSync
    ) {
        String unsavedWarning = host.hasPendingEditorChanges()
            ? I18n.t("settings.timeSync.unsavedWarning")
            : "";
        ModalPane.showConfirm(
            I18n.t("settings.timeSync.gmtMismatch.title"),
            I18n.t(
                "settings.timeSync.gmtMismatch.message",
                plan.nodeGmtLabel(),
                plan.request().systemGmtLabel(),
                unsavedWarning
            ),
            confirmed -> {
                if (confirmed) {
                    startSync.run();
                }
            }
        );
    }

    private void requestDateTimeSync(TimeSyncRequest request) {
        String actionLabel = request.gmtMatches()
            ? I18n.t("settings.timeSync.action.time")
            : I18n.t("settings.timeSync.action.timeAndGmt");
        host.setSyncDateTimeButtonDisabled(true);
        host.setStatus(
            I18n.t("settings.status.requestSessionKeyFor", actionLabel)
        );

        AtomicBoolean dispatchStarted = new AtomicBoolean(false);
        Runnable[] listenerHolder = new Runnable[1];
        listenerHolder[0] = () ->
            Platform.runLater(() -> {
                request.state().removeOwnerInfoListener(listenerHolder[0]);
                if (dispatchStarted.compareAndSet(false, true)) {
                    sendDateTimeSync(request);
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
                if (dispatchStarted.compareAndSet(false, true)) {
                    host.setStatus(
                        I18n.t("settings.timeSync.sendingWithoutKey")
                    );
                    sendDateTimeSync(request);
                }
            });
        }, "time-sync-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        MessageService.requestSessionPasskey(request.handler(), request.state());
    }

    private void sendDateTimeSync(TimeSyncRequest request) {
        host.setStatus(
            request.gmtMatches()
                ? I18n.t("settings.timeSync.sendingTime")
                : I18n.t("settings.timeSync.sendingTimeAndGmt")
        );

        Thread syncThread = new Thread(() -> {
            try {
                if (request.gmtMatches()) {
                    sendTimeOnlySync(request);
                } else {
                    sendTimeAndGmtSync(request);
                }
            } catch (InterruptedException e) {
                handleInterruptedSync(request, e);
            } catch (Exception e) {
                handleFailedSync(request, e);
            }
        }, "time-sync-sender");
        syncThread.setDaemon(true);
        syncThread.start();
    }

    private void sendTimeAndGmtSync(TimeSyncRequest request)
        throws InterruptedException {
        RebootHandoff handoff = prepareExpectedReboot(request);
        sendTimezoneConfigTransaction(request);
        pendingTimeOnlySyncConnectionId = request.activeEntry().getId();
        log.info(
            "Time sync: GMT update requires reboot, deferring set_time_only until reconnect"
        );
        Platform.runLater(() ->
            host.setStatus(I18n.t("settings.timeSync.gmtUpdated"))
        );
        handoffAfterGmtUpdate(request, handoff);
    }

    private void sendTimezoneConfigTransaction(TimeSyncRequest request)
        throws InterruptedException {
        ConnectionType transport = request.transport();
        ConfigProtos.Config deviceTzConfig = buildDeviceTimeZoneConfig(
            request.deviceConfig(),
            request.targetTzDef()
        );

        Thread.sleep(ConfigSavePolicy.baseMessageDelayMs(transport));
        ConfigSavePolicy.waitForTransportRequiredAck(
            transport,
            MessageService.beginEditSettings(
                request.handler(),
                request.state()
            ),
            "beginEditSettings",
            log
        );

        Thread.sleep(ConfigSavePolicy.interTaskDelayMs(transport, 0, 3));
        CompletableFuture<MeshProtos.Routing.Error> setConfigAck =
            MessageService.setConfig(
                request.handler(),
                request.state(),
                deviceTzConfig
            );
        ConfigSavePolicy.observeDeferredAck(
            setConfigAck,
            "setConfig/DEVICE",
            log
        );

        Thread.sleep(ConfigSavePolicy.interTaskDelayMs(transport, 1, 3));
        CompletableFuture<MeshProtos.Routing.Error> commitAck =
            MessageService.commitEditSettings(request.handler(), request.state());
        ConfigSavePolicy.handleCommitAck(
            transport,
            commitAck,
            "commitEditSettings",
            log
        );
    }

    private void sendTimeOnlySync(TimeSyncRequest request) {
        long epochSeconds = Instant.now().getEpochSecond();
        MessageService.sendPhoneTimePosition(
            request.handler(),
            request.state(),
            epochSeconds
        );
        ConfigSavePolicy.waitForTransportRequiredAck(
            request.transport(),
            MessageService.setTimeOnly(
                request.handler(),
                request.state(),
                epochSeconds
            ),
            "setTimeOnly",
            log
        );

        Platform.runLater(() -> {
            host.setSyncDateTimeButtonDisabled(false);
            host.setStatus(
                I18n.t(
                    "settings.timeSync.timeSyncedStatus",
                    request.systemGmtLabel()
                )
            );
            Toast.show(
                Toast.Type.SUCCESS,
                I18n.t("settings.timeSync.timeSyncedToast")
            );
        });
    }

    private RebootHandoff prepareExpectedReboot(TimeSyncRequest request) {
        long generation = ConnectionManager
            .getInstance()
            .getConnectionGeneration(request.activeEntry().getId());
        ConnectionManager
            .getInstance()
            .expectDeviceReboot(request.activeEntry().getId());
        return new RebootHandoff(generation);
    }

    private void handoffAfterGmtUpdate(
        TimeSyncRequest request,
        RebootHandoff handoff
    ) throws InterruptedException {
        Thread.sleep(
            ConfigSavePolicy.devicePowerActionHandoffDelayMs(request.transport())
        );
        boolean handoffStarted = ConnectionManager
            .getInstance()
            .disconnectForDeviceReboot(
                request.activeEntry().getId(),
                handoff.reconnectGeneration()
            );
        if (handoffStarted) {
            Platform.runLater(() -> {
                host.clearConnectionContext(
                    request.state(),
                    request.handler()
                );
                host.reloadConfigTree();
            });
        }
    }

    private void handleInterruptedSync(
        TimeSyncRequest request,
        InterruptedException error
    ) {
        Thread.currentThread().interrupt();
        log.warn("Time sync thread interrupted");
        clearExpectedRebootIfNeeded(request);
        Platform.runLater(() -> host.setSyncDateTimeButtonDisabled(false));
    }

    private void handleFailedSync(TimeSyncRequest request, Exception error) {
        log.error("Time sync failed", error);
        clearExpectedRebootIfNeeded(request);
        clearPendingTimeOnlySyncIfNeeded(request);
        Platform.runLater(() -> {
            host.setSyncDateTimeButtonDisabled(false);
            host.setStatus(
                I18n.t("settings.timeSync.error", host.errorDetail(error))
            );
        });
    }

    private void clearExpectedRebootIfNeeded(TimeSyncRequest request) {
        if (!request.gmtMatches() && request.activeEntry() != null) {
            ConnectionManager
                .getInstance()
                .clearExpectedDeviceReboot(request.activeEntry().getId());
        }
    }

    private void clearPendingTimeOnlySyncIfNeeded(TimeSyncRequest request) {
        if (
            request.activeEntry() != null &&
            request.activeEntry()
                .getId()
                .equals(pendingTimeOnlySyncConnectionId)
        ) {
            pendingTimeOnlySyncConnectionId = null;
        }
    }

    private static ConfigProtos.Config buildDeviceTimeZoneConfig(
        ConfigProtos.Config originalDeviceConfig,
        String tzdef
    ) {
        ConfigProtos.Config baseConfig = Optional
            .ofNullable(originalDeviceConfig)
            .orElseGet(() ->
                ConfigProtos.Config.newBuilder()
                    .setDevice(
                        ConfigProtos.Config.DeviceConfig.getDefaultInstance()
                    )
                    .build()
            );
        ConfigProtos.Config.DeviceConfig.Builder deviceBuilder = baseConfig
                .hasDevice()
            ? baseConfig.getDevice().toBuilder()
            : ConfigProtos.Config.DeviceConfig.newBuilder();
        deviceBuilder.setTzdef(tzdef);
        return ConfigProtos.Config.newBuilder(baseConfig)
            .setDevice(deviceBuilder.build())
            .build();
    }

    /**
     * Host callbacks implemented by the settings form.
     */
    public interface Host {
        void refreshConnection();
        DeviceState state();
        ProtocolHandler handler();
        ConnectionEntry findActiveConnectionEntry();
        boolean isConfigExchangeInProgress(ConnectionEntry entry);
        void watchConfigExchangeCompletion(ConnectionEntry entry);
        ConfigProtos.Config findLoadedDeviceConfig();
        boolean hasPendingEditorChanges();
        void setSyncDateTimeButtonDisabled(boolean disabled);
        void setStatus(String status);
        void clearConnectionContext(
            DeviceState expectedState,
            ProtocolHandler expectedHandler
        );
        void reloadConfigTree();
        String errorDetail(Exception error);
    }

    private record TimeSyncPlan(TimeSyncRequest request, String nodeGmtLabel) {
        boolean gmtMatches() {
            return request.gmtMatches();
        }
    }

    private record TimeSyncRequest(
        ConnectionEntry activeEntry,
        DeviceState state,
        ProtocolHandler handler,
        ConfigProtos.Config deviceConfig,
        boolean gmtMatches,
        String targetTzDef,
        String systemGmtLabel
    ) {
        ConnectionType transport() {
            return activeEntry != null
                ? activeEntry.getEffectiveType()
                : ConnectionType.TCP;
        }
    }

    private record RebootHandoff(long reconnectGeneration) {}
}
