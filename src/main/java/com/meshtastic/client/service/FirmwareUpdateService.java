package com.meshtastic.client.service;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.ProtocolHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates the safe first phase of firmware update: local image validation,
 * admin authorization, bootloader command dispatch, and reconnect handoff.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class FirmwareUpdateService {

    private static final Logger log = LoggerFactory.getLogger(
        FirmwareUpdateService.class
    );
    private static final long SESSION_KEY_WAIT_MS = 5_000;
    private static final long COMMAND_ACK_WAIT_MS = 15_000;
    private static final long REBOOT_HANDOFF_DELAY_MS = 1_800;
    private static final long MAX_FIRMWARE_IMAGE_BYTES = 128L * 1024L * 1024L;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Checks whether a firmware update preparation flow is active.
     *
     * @return {@code true} while validation, authorization, or bootloader handoff is running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Validates the selected image and current connection before the user is
     * allowed to send an OTA/DFU bootloader command.
     *
     * @param path selected local firmware file
     * @param mode requested firmware update mode
     * @param entry active connection profile
     * @param state active device state
     * @return validation result with blocking errors and non-blocking warnings
     */
    public FirmwareValidationResult validate(
        Path path,
        FirmwareUpdateMode mode,
        ConnectionEntry entry,
        DeviceState state
    ) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        validateConnection(entry, state, errors);

        FirmwareImage image = null;
        if (path == null) {
            errors.add(I18n.t("settings.firmware.validation.noFile"));
        } else {
            image = analyzeImage(path, errors);
        }

        if (image != null) {
            validateImageForMode(
                image,
                mode != null ? mode : FirmwareUpdateMode.AUTO,
                entry,
                state,
                errors,
                warnings
            );
        }
        return new FirmwareValidationResult(image, errors, warnings);
    }

    /**
     * Resolves {@link FirmwareUpdateMode#AUTO} to a concrete bootloader mode.
     * The decision uses image type first and then active transport/device metadata.
     *
     * @param image analyzed firmware image
     * @param requestedMode user-selected mode
     * @param entry active connection profile
     * @param state active device state
     * @return concrete OTA/DFU mode that should be sent to the device
     */
    public FirmwareUpdateMode resolveMode(
        FirmwareImage image,
        FirmwareUpdateMode requestedMode,
        ConnectionEntry entry,
        DeviceState state
    ) {
        FirmwareUpdateMode mode = requestedMode != null
            ? requestedMode
            : FirmwareUpdateMode.AUTO;
        if (mode != FirmwareUpdateMode.AUTO) {
            return mode;
        }
        if (image == null) {
            return FirmwareUpdateMode.OTA_BLE;
        }
        if (
            image.type() == FirmwareImageType.UF2 ||
            (image.type() == FirmwareImageType.ZIP && image.zipContainsUf2())
        ) {
            return FirmwareUpdateMode.DFU;
        }
        if (
            entry != null && entry.getEffectiveType() == ConnectionType.BLE
        ) {
            return FirmwareUpdateMode.OTA_BLE;
        }
        if (
            entry != null && entry.getEffectiveType() == ConnectionType.TCP
        ) {
            return FirmwareUpdateMode.OTA_WIFI;
        }
        MeshProtos.DeviceMetadata metadata = state != null
            ? state.getDeviceMetadata()
            : null;
        if (metadata != null && metadata.getHasWifi()) {
            return FirmwareUpdateMode.OTA_WIFI;
        }
        return FirmwareUpdateMode.OTA_BLE;
    }

    /**
     * Starts the safe first phase of firmware update: validates the image,
     * requests a session key when needed, sends the OTA/DFU admin command, and
     * hands the connection to the expected reboot/reconnect flow.
     * <p>
     * This method does not upload firmware bytes. The actual image transfer is
     * performed by the device bootloader and its matching OTA/DFU tool.
     *
     * @param request firmware update preparation request
     * @param progressConsumer callback for UI progress updates; may be {@code null}
     * @return future completed with the bootloader command outcome
     */
    public CompletableFuture<FirmwareUpdateResult> start(
        FirmwareUpdateRequest request,
        Consumer<FirmwareUpdateProgress> progressConsumer
    ) {
        Objects.requireNonNull(request, "request");
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(
                new FirmwareUpdateResult(
                    false,
                    request.mode(),
                    false,
                    false,
                    null,
                    I18n.t("settings.firmware.status.busy")
                )
            );
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return runUpdate(request, progressConsumer);
            } finally {
                running.set(false);
            }
        });
    }

    private FirmwareUpdateResult runUpdate(
        FirmwareUpdateRequest request,
        Consumer<FirmwareUpdateProgress> progressConsumer
    ) {
        emit(
            progressConsumer,
            FirmwareUpdateStage.VALIDATING,
            0.10,
            I18n.t("settings.firmware.status.validating")
        );
        FirmwareValidationResult validation = validate(
            request.image() != null ? request.image().path() : null,
            request.mode(),
            request.connectionEntry(),
            request.state()
        );
        if (!validation.valid()) {
            return failed(
                request.mode(),
                I18n.t(
                    "settings.firmware.status.validationFailed",
                    String.join("; ", validation.errors())
                )
            );
        }
        FirmwareUpdateRequest validatedRequest = new FirmwareUpdateRequest(
            request.connectionEntry(),
            request.state(),
            request.handler(),
            validation.image(),
            request.mode()
        );

        FirmwareUpdateMode mode = resolveMode(
            validatedRequest.image(),
            validatedRequest.mode(),
            validatedRequest.connectionEntry(),
            validatedRequest.state()
        );
        try {
            waitForSessionKey(validatedRequest, progressConsumer);
            return sendBootloaderCommand(
                validatedRequest,
                mode,
                progressConsumer
            );
        } catch (Exception e) {
            log.error("Firmware update preparation failed", e);
            ConnectionManager
                .getInstance()
                .clearExpectedDeviceReboot(
                    validatedRequest.connectionEntry().getId()
                );
            emit(
                progressConsumer,
                FirmwareUpdateStage.FAILED,
                1.0,
                I18n.t(
                    "settings.firmware.status.failed",
                    errorDetail(e)
                )
            );
            return failed(
                mode,
                I18n.t("settings.firmware.status.failed", errorDetail(e))
            );
        }
    }

    private void waitForSessionKey(
        FirmwareUpdateRequest request,
        Consumer<FirmwareUpdateProgress> progressConsumer
    ) throws Exception {
        DeviceState state = request.state();
        ProtocolHandler handler = request.handler();
        if (state == null || handler == null) {
            throw new IllegalStateException(
                I18n.t("settings.firmware.validation.noRadio")
            );
        }
        if (state.getSessionPasskey() != null) {
            return;
        }
        emit(
            progressConsumer,
            FirmwareUpdateStage.REQUESTING_SESSION_KEY,
            0.25,
            I18n.t("settings.firmware.status.requestSessionKey")
        );

        CompletableFuture<Void> ready = new CompletableFuture<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        Runnable[] listenerHolder = new Runnable[1];
        listenerHolder[0] = () -> {
            if (completed.compareAndSet(false, true)) {
                state.removeOwnerInfoListener(listenerHolder[0]);
                ready.complete(null);
            }
        };
        state.addOwnerInfoListener(listenerHolder[0]);
        MessageService.requestSessionPasskey(handler, state);
        CompletableFuture.delayedExecutor(
            SESSION_KEY_WAIT_MS,
            TimeUnit.MILLISECONDS
        ).execute(() -> {
            if (completed.compareAndSet(false, true)) {
                state.removeOwnerInfoListener(listenerHolder[0]);
                ready.complete(null);
            }
        });
        ready.get(SESSION_KEY_WAIT_MS + 1_000, TimeUnit.MILLISECONDS);
    }

    private FirmwareUpdateResult sendBootloaderCommand(
        FirmwareUpdateRequest request,
        FirmwareUpdateMode mode,
        Consumer<FirmwareUpdateProgress> progressConsumer
    ) throws Exception {
        ConnectionEntry entry = request.connectionEntry();
        String connectionId = entry.getId();
        ConnectionManager manager = ConnectionManager.getInstance();
        long generation = manager.getConnectionGeneration(connectionId);
        manager.expectDeviceReboot(connectionId);

        emit(
            progressConsumer,
            FirmwareUpdateStage.SENDING_COMMAND,
            0.50,
            I18n.t("settings.firmware.status.sendingCommand")
        );
        CompletableFuture<MeshProtos.Routing.Error> ackFuture =
            switch (mode) {
                case OTA_BLE -> MessageService.requestOtaMode(
                    request.handler(),
                    request.state(),
                    AdminProtos.OTAMode.OTA_BLE,
                    request.image().sha256()
                );
                case OTA_WIFI -> MessageService.requestOtaMode(
                    request.handler(),
                    request.state(),
                    AdminProtos.OTAMode.OTA_WIFI,
                    request.image().sha256()
                );
                case DFU -> MessageService.enterDfuMode(
                    request.handler(),
                    request.state()
                );
                case AUTO -> throw new IllegalStateException(
                    "Unresolved firmware update mode"
                );
            };

        boolean ackReceived = waitForCommandAck(ackFuture, mode);
        emit(
            progressConsumer,
            FirmwareUpdateStage.WAITING_FOR_REBOOT,
            0.78,
            I18n.t("settings.firmware.status.waitingForReboot")
        );
        Thread.sleep(REBOOT_HANDOFF_DELAY_MS);
        boolean handoffStarted = manager.disconnectForDeviceReboot(
            connectionId,
            generation
        );
        String message = I18n.t(
            mode == FirmwareUpdateMode.DFU
                ? "settings.firmware.status.dfuStarted"
                : "settings.firmware.status.otaStarted"
        );
        emit(
            progressConsumer,
            FirmwareUpdateStage.COMPLETE,
            1.0,
            message
        );
        return new FirmwareUpdateResult(
            true,
            mode,
            ackReceived,
            handoffStarted,
            MeshProtos.Routing.Error.NONE,
            message
        );
    }

    private boolean waitForCommandAck(
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        FirmwareUpdateMode mode
    ) throws Exception {
        if (ackFuture == null) {
            return false;
        }
        try {
            MeshProtos.Routing.Error error = ackFuture.get(
                COMMAND_ACK_WAIT_MS,
                TimeUnit.MILLISECONDS
            );
            if (error != null && error != MeshProtos.Routing.Error.NONE) {
                throw new IllegalStateException(
                    I18n.t("settings.firmware.status.routingError", error)
                );
            }
            return true;
        } catch (TimeoutException e) {
            log.info(
                "Firmware {} command ACK timed out, proceeding with reboot handoff",
                mode
            );
            return false;
        } catch (ExecutionException e) {
            log.info(
                "Firmware {} command ACK completed exceptionally, proceeding with reboot handoff: {}",
                mode,
                e.getMessage()
            );
            return false;
        }
    }

    private void validateConnection(
        ConnectionEntry entry,
        DeviceState state,
        List<String> errors
    ) {
        if (entry == null || !entry.isConnected()) {
            errors.add(I18n.t("settings.firmware.validation.noRadio"));
            return;
        }
        if (entry.getEffectiveProtocol() != ProtocolType.MESHTASTIC) {
            errors.add(
                I18n.t("settings.firmware.validation.meshtasticOnly")
            );
        }
        if (state == null || state.getMyNodeNum() == 0) {
            errors.add(I18n.t("settings.firmware.validation.noState"));
        }
    }

    private FirmwareImage analyzeImage(Path path, List<String> errors) {
        try {
            if (!Files.exists(path)) {
                errors.add(I18n.t("settings.firmware.validation.fileMissing"));
                return null;
            }
            if (!Files.isRegularFile(path)) {
                errors.add(I18n.t("settings.firmware.validation.notFile"));
                return null;
            }
            if (Files.size(path) <= 0) {
                errors.add(I18n.t("settings.firmware.validation.emptyFile"));
                return null;
            }
            if (Files.size(path) > MAX_FIRMWARE_IMAGE_BYTES) {
                errors.add(
                    I18n.t("settings.firmware.validation.fileTooLarge")
                );
                return null;
            }
            return FirmwareImage.analyze(path);
        } catch (IOException e) {
            errors.add(
                I18n.t("settings.firmware.validation.readError", e.getMessage())
            );
            return null;
        }
    }

    private void validateImageForMode(
        FirmwareImage image,
        FirmwareUpdateMode requestedMode,
        ConnectionEntry entry,
        DeviceState state,
        List<String> errors,
        List<String> warnings
    ) {
        FirmwareUpdateMode resolved = resolveMode(
            image,
            requestedMode,
            entry,
            state
        );
        if (image.type() == FirmwareImageType.UNKNOWN) {
            errors.add(I18n.t("settings.firmware.validation.unknownType"));
            return;
        }
        if (
            resolved == FirmwareUpdateMode.OTA_BLE ||
            resolved == FirmwareUpdateMode.OTA_WIFI
        ) {
            if (image.type() != FirmwareImageType.ESP32_BIN) {
                errors.add(I18n.t("settings.firmware.validation.otaNeedsBin"));
            }
            if (
                resolved == FirmwareUpdateMode.OTA_WIFI &&
                state != null &&
                state.getDeviceMetadata() != null &&
                !state.getDeviceMetadata().getHasWifi()
            ) {
                warnings.add(
                    I18n.t("settings.firmware.validation.wifiNotAdvertised")
                );
            }
            if (
                resolved == FirmwareUpdateMode.OTA_BLE &&
                state != null &&
                state.getDeviceMetadata() != null &&
                !state.getDeviceMetadata().getHasBluetooth()
            ) {
                warnings.add(
                    I18n.t(
                        "settings.firmware.validation.bluetoothNotAdvertised"
                    )
                );
            }
            return;
        }
        if (resolved == FirmwareUpdateMode.DFU) {
            if (
                image.type() != FirmwareImageType.UF2 &&
                image.type() != FirmwareImageType.ZIP
            ) {
                errors.add(I18n.t("settings.firmware.validation.dfuNeedsUf2"));
            }
            warnings.add(I18n.t("settings.firmware.validation.dfuExternal"));
        }
    }

    private FirmwareUpdateResult failed(
        FirmwareUpdateMode mode,
        String message
    ) {
        return new FirmwareUpdateResult(
            false,
            mode,
            false,
            false,
            null,
            message
        );
    }

    private void emit(
        Consumer<FirmwareUpdateProgress> consumer,
        FirmwareUpdateStage stage,
        double progress,
        String message
    ) {
        if (consumer != null) {
            consumer.accept(new FirmwareUpdateProgress(stage, progress, message));
        }
    }

    private String errorDetail(Exception error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null
            ? current.getMessage()
            : current.getClass().getSimpleName();
    }
}
