package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageService;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates restart and shutdown commands for the connected radio.
 * The controller keeps the protocol flow outside the settings form: it asks for
 * a session key, sends the selected power command, handles ACK diagnostics, and
 * hands the connection to reconnect/disconnect flow.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class DevicePowerActionController {

    private static final Logger log = LoggerFactory.getLogger(
        DevicePowerActionController.class
    );

    private final Host host;

    /**
     * Creates a controller bound to a settings form host.
     *
     * @param host host callbacks
     */
    public DevicePowerActionController(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Asks the user to confirm and then restarts the connected radio.
     */
    public void restart() {
        confirmPowerAction(PowerAction.RESTART);
    }

    /**
     * Asks the user to confirm and then shuts down the connected radio.
     */
    public void shutdown() {
        confirmPowerAction(PowerAction.SHUTDOWN);
    }

    private void confirmPowerAction(PowerAction action) {
        ModalPane.showConfirm(
            action.title(),
            action.confirmMessage(),
            confirmed -> {
                if (confirmed) {
                    requestDevicePowerAction(action);
                }
            }
        );
    }

    private void requestDevicePowerAction(PowerAction action) {
        host.refreshConnection();
        DeviceState state = host.state();
        ProtocolHandler handler = host.handler();
        if (state == null || handler == null) {
            host.setStatus(I18n.t("settings.status.noRadio"));
            host.setDevicePowerButtonsDisabled(true);
            return;
        }

        ConnectionEntry activeEntry = host.findActiveConnectionEntry();
        if (activeEntry == null) {
            host.setStatus(I18n.t("settings.status.noActiveRadio"));
            host.setDevicePowerButtonsDisabled(true);
            return;
        }

        host.setDevicePowerButtonsDisabled(true);
        host.setStatus(
            I18n.t("settings.status.requestSessionKeyFor", action.label())
        );
        dispatchWhenSessionKeyIsReady(
            new PowerActionRequest(activeEntry, state, handler, action)
        );
    }

    private void dispatchWhenSessionKeyIsReady(PowerActionRequest request) {
        AtomicBoolean dispatchStarted = new AtomicBoolean(false);
        Runnable[] listenerHolder = new Runnable[1];
        listenerHolder[0] = () ->
            Platform.runLater(() -> {
                request.state().removeOwnerInfoListener(listenerHolder[0]);
                if (dispatchStarted.compareAndSet(false, true)) {
                    sendDevicePowerAction(request);
                }
            });
        request.state().addOwnerInfoListener(listenerHolder[0]);

        Thread timeoutThread = new Thread(
            () -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    return;
                }
                Platform.runLater(() -> {
                    request.state().removeOwnerInfoListener(listenerHolder[0]);
                    if (dispatchStarted.compareAndSet(false, true)) {
                        host.setStatus(
                            I18n.t(
                                "settings.devicePower.sendingWithoutKey",
                                request.action().label()
                            )
                        );
                        sendDevicePowerAction(request);
                    }
                });
            },
            request.action().timeoutThreadName()
        );
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        MessageService.requestSessionPasskey(
            request.handler(),
            request.state()
        );
    }

    private void sendDevicePowerAction(PowerActionRequest request) {
        PowerAction action = request.action();
        ConnectionEntry activeEntry = request.activeEntry();
        ReconnectHandoff handoff = prepareReconnectHandoff(request);

        host.setStatus(
            I18n.t("settings.devicePower.sending", action.label())
        );

        CompletableFuture<MeshProtos.Routing.Error> ackFuture;
        try {
            ackFuture = sendPowerCommand(request);
        } catch (Exception e) {
            handleCommandSendFailure(request, e);
            return;
        }

        observeDevicePowerActionAck(ackFuture, action.stepName());
        Thread actionThread = new Thread(
            () ->
                runPowerActionHandoff(
                    request,
                    activeEntry.getEffectiveType(),
                    handoff,
                    ackFuture
                ),
            action.senderThreadName()
        );
        actionThread.setDaemon(true);
        actionThread.start();
    }

    private ReconnectHandoff prepareReconnectHandoff(PowerActionRequest request) {
        if (!request.action().reboots()) {
            return new ReconnectHandoff(-1);
        }
        String connectionId = request.activeEntry().getId();
        long generation = ConnectionManager
            .getInstance()
            .getConnectionGeneration(connectionId);
        ConnectionManager.getInstance().expectDeviceReboot(connectionId);
        return new ReconnectHandoff(generation);
    }

    private CompletableFuture<MeshProtos.Routing.Error> sendPowerCommand(
        PowerActionRequest request
    ) {
        return switch (request.action()) {
            case RESTART -> MessageService.rebootDevice(
                request.handler(),
                request.state(),
                ConfigSavePolicy.DEVICE_POWER_ACTION_DELAY_SECONDS
            );
            case SHUTDOWN -> MessageService.shutdownDevice(
                request.handler(),
                request.state(),
                ConfigSavePolicy.DEVICE_POWER_ACTION_DELAY_SECONDS
            );
        };
    }

    private void handleCommandSendFailure(
        PowerActionRequest request,
        Exception error
    ) {
        PowerAction action = request.action();
        log.error("Device {} command send failed", action.stepName(), error);
        clearExpectedRebootIfNeeded(request);
        host.setDevicePowerButtonsDisabled(false);
        host.setStatus(
            I18n.t("settings.devicePower.sendError", action.label())
        );
    }

    private void runPowerActionHandoff(
        PowerActionRequest request,
        ConnectionType transport,
        ReconnectHandoff handoff,
        CompletableFuture<MeshProtos.Routing.Error> ackFuture
    ) {
        try {
            boolean ackConfirmed = waitForPowerActionAck(
                ackFuture,
                request.action().stepName()
            );
            Platform.runLater(() ->
                host.setStatus(request.action().sentStatus())
            );
            Thread.sleep(
                ConfigSavePolicy.devicePowerActionHandoffDelayMs(transport)
            );
            finishPowerActionHandoff(request, handoff, ackConfirmed);
        } catch (InterruptedException e) {
            handleInterruptedPowerAction(request, e);
        } catch (Exception e) {
            handleFailedPowerAction(request, e);
        }
    }

    private boolean waitForPowerActionAck(
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName
    ) throws Exception {
        if (ackFuture == null) {
            return false;
        }
        try {
            MeshProtos.Routing.Error error = ackFuture.get(
                ConfigSavePolicy.DEVICE_POWER_ACTION_ACK_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            );
            if (error != null && error != MeshProtos.Routing.Error.NONE) {
                throw new IllegalStateException(
                    stepName + " failed with " + error
                );
            }
            return true;
        } catch (TimeoutException e) {
            log.info(
                "Device power action '{}' ACK timed out, proceeding with fallback flow",
                stepName
            );
            return false;
        }
    }

    private void finishPowerActionHandoff(
        PowerActionRequest request,
        ReconnectHandoff handoff,
        boolean ackConfirmed
    ) {
        if (request.action().reboots()) {
            handoffRestart(request, handoff);
            return;
        }
        handoffShutdown(request, ackConfirmed);
    }

    private void handoffRestart(
        PowerActionRequest request,
        ReconnectHandoff handoff
    ) {
        boolean handoffStarted = ConnectionManager
            .getInstance()
            .disconnectForDeviceReboot(
                request.activeEntry().getId(),
                handoff.reconnectGeneration()
            );
        if (handoffStarted) {
            clearConnectionContextOnUiThread(request);
        }
    }

    private void handoffShutdown(
        PowerActionRequest request,
        boolean ackConfirmed
    ) {
        if (ackConfirmed) {
            ConnectionManager.getInstance().disconnect(
                request.activeEntry().getId()
            );
            clearConnectionContextOnUiThread(request);
            return;
        }
        Platform.runLater(() -> host.setDevicePowerButtonsDisabled(false));
    }

    private void handleInterruptedPowerAction(
        PowerActionRequest request,
        InterruptedException error
    ) {
        Thread.currentThread().interrupt();
        log.warn(
            "Device power action thread interrupted: {}",
            request.action().stepName()
        );
        clearExpectedRebootIfNeeded(request);
        Platform.runLater(() -> host.setDevicePowerButtonsDisabled(false));
    }

    private void handleFailedPowerAction(
        PowerActionRequest request,
        Exception error
    ) {
        log.error(
            "Device power action '{}' failed",
            request.action().stepName(),
            error
        );
        clearExpectedRebootIfNeeded(request);
        Platform.runLater(() -> {
            host.setDevicePowerButtonsDisabled(false);
            host.setStatus(
                I18n.t(
                    "settings.devicePower.sendErrorDetails",
                    request.action().label(),
                    host.errorDetail(error)
                )
            );
        });
    }

    private void observeDevicePowerActionAck(
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName
    ) {
        if (ackFuture == null) {
            return;
        }

        ackFuture.whenComplete((error, ex) -> {
            if (ex != null) {
                log.info(
                    "Device power action '{}' ACK completed exceptionally: {}",
                    stepName,
                    ex.getMessage()
                );
            } else if (
                error != null && error != MeshProtos.Routing.Error.NONE
            ) {
                log.warn(
                    "Device power action '{}' returned {}",
                    stepName,
                    error
                );
            } else {
                log.debug("Device power action '{}' ACK received", stepName);
            }
        });
    }

    private void clearConnectionContextOnUiThread(PowerActionRequest request) {
        Platform.runLater(() -> {
            host.clearConnectionContext(request.state(), request.handler());
            host.reloadConfigTree();
        });
    }

    private void clearExpectedRebootIfNeeded(PowerActionRequest request) {
        if (request.action().reboots()) {
            ConnectionManager
                .getInstance()
                .clearExpectedDeviceReboot(request.activeEntry().getId());
        }
    }

    /**
     * Host callbacks implemented by the settings form.
     */
    public interface Host {
        void refreshConnection();
        DeviceState state();
        ProtocolHandler handler();
        ConnectionEntry findActiveConnectionEntry();
        void setDevicePowerButtonsDisabled(boolean disabled);
        void setStatus(String status);
        void clearConnectionContext(
            DeviceState expectedState,
            ProtocolHandler expectedHandler
        );
        void reloadConfigTree();
        String errorDetail(Exception error);
    }

    private enum PowerAction {
        RESTART(
            true,
            "rebootDevice",
            "device-restart-timeout",
            "device-restart-sender"
        ),
        SHUTDOWN(
            false,
            "shutdownDevice",
            "device-shutdown-timeout",
            "device-shutdown-sender"
        );

        private final boolean reboots;
        private final String stepName;
        private final String timeoutThreadName;
        private final String senderThreadName;

        PowerAction(
            boolean reboots,
            String stepName,
            String timeoutThreadName,
            String senderThreadName
        ) {
            this.reboots = reboots;
            this.stepName = stepName;
            this.timeoutThreadName = timeoutThreadName;
            this.senderThreadName = senderThreadName;
        }

        boolean reboots() {
            return reboots;
        }

        String title() {
            return switch (this) {
                case RESTART -> I18n.t("settings.devicePower.restart.title");
                case SHUTDOWN -> I18n.t("settings.devicePower.shutdown.title");
            };
        }

        String confirmMessage() {
            return switch (this) {
                case RESTART -> I18n.t("settings.devicePower.restart.confirm");
                case SHUTDOWN -> I18n.t("settings.devicePower.shutdown.confirm");
            };
        }

        String label() {
            return switch (this) {
                case RESTART -> I18n.t("settings.devicePower.action.restart");
                case SHUTDOWN -> I18n.t("settings.devicePower.action.shutdown");
            };
        }

        String sentStatus() {
            return switch (this) {
                case RESTART -> I18n.t("settings.devicePower.restartSent");
                case SHUTDOWN -> I18n.t("settings.devicePower.shutdownSent");
            };
        }

        String stepName() {
            return stepName;
        }

        String timeoutThreadName() {
            return timeoutThreadName;
        }

        String senderThreadName() {
            return senderThreadName;
        }
    }

    private record PowerActionRequest(
        ConnectionEntry activeEntry,
        DeviceState state,
        ProtocolHandler handler,
        PowerAction action
    ) {}

    private record ReconnectHandoff(long reconnectGeneration) {}
}
