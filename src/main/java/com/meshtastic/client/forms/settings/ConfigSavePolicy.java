package com.meshtastic.client.forms.settings;

import com.meshtastic.client.model.ConnectionType;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.slf4j.Logger;

/**
 * Transport-aware rules for sending configuration changes to a device.
 * This class owns save-flow delays, reconnect decisions, and routing ACK
 * handling so form code can focus on user interaction and status updates.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigSavePolicy {

    /**
     * Base delay between admin packets while saving configuration.
     * For Serial/TCP, 200 ms is usually enough for firmware to process
     * begin/set/commit without packets bunching together.
     */
    public static final long CONFIG_SAVE_MESSAGE_DELAY_MS = 200;
    /**
     * BLE uses a longer delay between admin packets. Heltec V3 and similar
     * devices may reboot immediately after commit, and short intervals increase
     * the chance of a race between the last GATT writes and session shutdown.
     */
    public static final long BLE_CONFIG_SAVE_MESSAGE_DELAY_MS = 350;
    /**
     * Extra pause before commit after the last mutating step.
     * Even over TCP/Serial, firmware may need time to apply the final
     * set_config/set_module_config before a reboot-triggering commit.
     */
    public static final long CONFIG_SAVE_PRE_COMMIT_SETTLE_DELAY_MS = 1_000;
    /**
     * After commit, TCP/Serial can keep the socket alive for tens of seconds and
     * close it only when the actual reboot starts. For non-BLE transports we do
     * not force an immediate disconnect; we wait for the natural break and use
     * this value only as a fallback if the device never closes the transport.
     */
    public static final long CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS = 60_000;
    public static final long BLE_CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS = 4_000;
    /**
     * The final BLE set_config/set_module_config is sent asynchronously at the
     * GATT-write level. Before commit, we give the write-with-response extra time
     * to physically reach the device before commit places the reboot-triggering
     * packet into the same queue.
     */
    public static final long BLE_CONFIG_SAVE_PRE_COMMIT_SETTLE_DELAY_MS =
        1_000;
    /** Timeout for waiting for a routing ACK during a configuration-save step. */
    public static final long CONFIG_SAVE_ACK_TIMEOUT_MS = 8_000;
    /** Short delay before reboot/shutdown so the routing ACK can arrive before the link drops. */
    public static final int DEVICE_POWER_ACTION_DELAY_SECONDS = 1;
    public static final long DEVICE_POWER_ACTION_HANDOFF_DELAY_MS = 1_000;
    /** Maximum wait for a reboot/shutdown routing ACK before falling back. */
    public static final long DEVICE_POWER_ACTION_ACK_TIMEOUT_MS = 2_500;

    private ConfigSavePolicy() {}

    /**
     * Returns the delay between two save-flow steps.
     * Before {@code commitEditSettings}, every transport gets a separate settle
     * window after the last mutating step.
     *
     * @param transport      active connection transport
     * @param taskIndex      current task index
     * @param totalTaskCount total number of save tasks
     * @return delay in milliseconds
     */
    public static long interTaskDelayMs(
        ConnectionType transport,
        int taskIndex,
        int totalTaskCount
    ) {
        long delayMs = baseMessageDelayMs(transport);
        boolean nextTaskIsCommit = taskIndex + 1 == totalTaskCount - 1;
        if (!nextTaskIsCommit) {
            return delayMs;
        }
        return delayMs +
            (
                transport == ConnectionType.BLE
                    ? BLE_CONFIG_SAVE_PRE_COMMIT_SETTLE_DELAY_MS
                    : CONFIG_SAVE_PRE_COMMIT_SETTLE_DELAY_MS
            );
    }

    /**
     * Returns the base delay between admin packets for a transport.
     *
     * @param transport active connection transport
     * @return delay in milliseconds
     */
    public static long baseMessageDelayMs(ConnectionType transport) {
        return transport == ConnectionType.BLE
            ? BLE_CONFIG_SAVE_MESSAGE_DELAY_MS
            : CONFIG_SAVE_MESSAGE_DELAY_MS;
    }

    /**
     * Returns the delay before handing a power action to reconnect/disconnect flow.
     *
     * @param transport active connection transport
     * @return delay in milliseconds
     */
    public static long devicePowerActionHandoffDelayMs(
        ConnectionType transport
    ) {
        return transport == ConnectionType.BLE
            ? BLE_CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS
            : DEVICE_POWER_ACTION_HANDOFF_DELAY_MS;
    }

    /**
     * Returns the delay before handing config-save reboot to reconnect flow.
     *
     * @param transport active connection transport
     * @return delay in milliseconds
     */
    public static long configSaveRebootHandoffDelayMs(
        ConnectionType transport
    ) {
        return transport == ConnectionType.BLE
            ? BLE_CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS
            : CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS;
    }

    /**
     * Detects the narrow BLE MQTT case where transactional save can fail because
     * the device disconnects immediately after {@code set_module_config(MQTT)}.
     *
     * @param transport     active connection transport
     * @param ownerModified whether owner info changed
     * @param positionModified whether fixed position changed
     * @param configs       modified device configs
     * @param moduleConfigs modified module configs
     * @return {@code true} when implicit BLE module save should be used
     */
    public static boolean shouldUseImplicitBleModuleSave(
        ConnectionType transport,
        boolean ownerModified,
        boolean positionModified,
        List<ConfigProtos.Config> configs,
        List<ModuleConfigProtos.ModuleConfig> moduleConfigs
    ) {
        return transport == ConnectionType.BLE &&
            !ownerModified &&
            !positionModified &&
            Optional
                .ofNullable(configs)
                .map(List::isEmpty)
                .orElse(true) &&
            Optional
                .ofNullable(moduleConfigs)
                .filter(list -> list.size() == 1)
                .stream()
                .flatMap(List::stream)
                .findFirst()
                .map(ModuleConfigProtos.ModuleConfig::getPayloadVariantCase)
                .filter(ModuleConfigProtos.ModuleConfig.PayloadVariantCase.MQTT::equals)
                .isPresent();
    }

    /**
     * Checks whether changed settings require the radio to reboot/reconnect.
     *
     * @param ownerModified whether owner info changed
     * @param configs       modified device configs
     * @param moduleConfigs modified module configs
     * @return {@code true} when reconnect flow is needed
     */
    public static boolean requiresReconnect(
        boolean ownerModified,
        List<ConfigProtos.Config> configs,
        List<ModuleConfigProtos.ModuleConfig> moduleConfigs
    ) {
        return ownerModified || hasItems(configs) || hasItems(moduleConfigs);
    }

    /**
     * Waits for a required routing ACK and converts errors into save exceptions.
     *
     * @param ackFuture ACK future
     * @param stepName  diagnostic step name
     */
    public static void waitForRequiredAck(
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName
    ) {
        if (ackFuture == null) {
            return;
        }

        try {
            MeshProtos.Routing.Error error = ackFuture
                .orTimeout(CONFIG_SAVE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .get();
            if (error != MeshProtos.Routing.Error.NONE) {
                throw new IllegalStateException(
                    "Config save step '" + stepName + "' failed with " + error
                );
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                "Config save step '" + stepName + "' ACK failed",
                e
            );
        }
    }

    /**
     * Waits for ACK only when the transport requires strict packet ordering.
     *
     * @param transport active transport
     * @param ackFuture ACK future
     * @param stepName  diagnostic step name
     * @param log       logger for optional ACK diagnostics
     */
    public static void waitForTransportRequiredAck(
        ConnectionType transport,
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName,
        Logger log
    ) {
        if (transport == ConnectionType.BLE) {
            waitForRequiredAck(ackFuture, stepName);
            return;
        }
        observeOptionalAck(ackFuture, stepName, log);
    }

    /**
     * Handles mutating settings-step ACKs according to transport semantics.
     * BLE still requires strict ACK ordering; TCP/Serial can lose routing ACK
     * waiters while the local admin command is still accepted by firmware.
     *
     * @param transport active transport
     * @param ackFuture ACK future
     * @param stepName  diagnostic step name
     * @param log       logger for optional ACK diagnostics
     */
    public static void waitForMutatingStepAck(
        ConnectionType transport,
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName,
        Logger log
    ) {
        waitForTransportRequiredAck(transport, ackFuture, stepName, log);
    }

    /**
     * Handles commit ACK without failing when expected reboot causes timeout or
     * disconnect before the ACK reaches the client.
     *
     * @param ackFuture ACK future
     * @param stepName  diagnostic step name
     * @param log       logger
     */
    public static void waitForCommitAckOrExpectedReboot(
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName,
        Logger log
    ) {
        if (ackFuture == null) {
            return;
        }

        try {
            MeshProtos.Routing.Error error = ackFuture.get(
                CONFIG_SAVE_ACK_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            );
            if (error != MeshProtos.Routing.Error.NONE) {
                throw new IllegalStateException(
                    "Config save step '" + stepName + "' failed with " + error
                );
            }
        } catch (TimeoutException e) {
            log.info(
                "Config save: commit '{}' ACK timed out after {} ms, continuing with reconnect flow",
                stepName,
                CONFIG_SAVE_ACK_TIMEOUT_MS
            );
        } catch (Exception e) {
            if (isExpectedRebootAckLoss(e)) {
                log.info(
                    "Config save: commit '{}' lost ACK during expected reboot/disconnect: {}",
                    stepName,
                    rootCauseMessage(e)
                );
                return;
            }
            throw new IllegalStateException(
                "Config save step '" + stepName + "' ACK failed",
                e
            );
        }
    }

    /**
     * Handles commit ACK according to transport requirements.
     *
     * @param transport active transport
     * @param ackFuture ACK future
     * @param stepName  diagnostic step name
     * @param log       logger
     */
    public static void handleCommitAck(
        ConnectionType transport,
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName,
        Logger log
    ) {
        observeDeferredAck(ackFuture, stepName, log);
        waitForCommitAckOrExpectedReboot(ackFuture, stepName, log);
    }

    /**
     * Checks whether an ACK failure is expected during reboot/disconnect.
     *
     * @param error thrown ACK error
     * @return {@code true} for expected reboot ACK loss
     */
    public static boolean isExpectedRebootAckLoss(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }

            String message = current.getMessage();
            if (
                message != null &&
                (message.contains("Packet ACK waiter aborted: DISCONNECTED") ||
                    message.contains(
                        "Packet ACK waiter aborted: STATE_CLEARED"
                    ))
            ) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Extracts a compact root-cause message for logs and UI details.
     *
     * @param error thrown error
     * @return root-cause message
     */
    public static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current != null && current.getMessage() != null
            ? current.getMessage()
            : error.getClass().getSimpleName();
    }

    /**
     * Observes an ACK used only for diagnostics.
     *
     * @param ackFuture ACK future
     * @param stepName  diagnostic step name
     * @param log       logger
     */
    public static void observeOptionalAck(
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName,
        Logger log
    ) {
        Optional
            .ofNullable(ackFuture)
            .ifPresent(future ->
                future
                    .orTimeout(
                        CONFIG_SAVE_ACK_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS
                    )
                    .whenComplete((error, ex) -> {
                        if (ex != null) {
                            log.debug(
                                "Config save: optional ACK for '{}' was not observed: {}",
                                stepName,
                                rootCauseMessage(ex)
                            );
                        } else if (
                            error != null &&
                            error != MeshProtos.Routing.Error.NONE
                        ) {
                            log.warn(
                                "Config save: optional ACK for '{}' returned {}",
                                stepName,
                                error
                            );
                        } else {
                            log.debug(
                                "Config save: optional ACK received for '{}'",
                                stepName
                            );
                        }
                    })
            );
    }

    /**
     * Attaches diagnostics to an ACK that must not block commit/reconnect flow.
     *
     * @param ackFuture ACK future
     * @param stepName  diagnostic step name
     * @param log       logger
     */
    public static void observeDeferredAck(
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName,
        Logger log
    ) {
        Optional
            .ofNullable(ackFuture)
            .ifPresent(future ->
                future.whenComplete((error, ex) -> {
                    if (ex != null) {
                        log.info(
                            "Config save: deferred ACK for '{}' completed exceptionally: {}",
                            stepName,
                            ex.getMessage()
                        );
                    } else if (
                        error != null && error != MeshProtos.Routing.Error.NONE
                    ) {
                        log.warn(
                            "Config save: deferred ACK for '{}' returned {}",
                            stepName,
                            error
                        );
                    } else {
                        log.debug(
                            "Config save: deferred ACK received for '{}'",
                            stepName
                        );
                    }
                })
            );
    }

    private static boolean hasItems(List<?> values) {
        return Optional.ofNullable(values).map(list -> !list.isEmpty()).orElse(false);
    }
}
