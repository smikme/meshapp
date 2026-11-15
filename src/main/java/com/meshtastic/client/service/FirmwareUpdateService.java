package com.meshtastic.client.service;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.*;
import com.meshtastic.client.protocol.FromRadioListener;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates validation, bootloader handoff, firmware transfer and application reconnection.
 *
 * <p>Only one update may run across all service instances. During the handoff the selected
 * connection is reserved so that automatic reconnection cannot compete with the uploader.
 * Success requires the original node to reconnect with metadata; ESP images additionally
 * require the reported firmware version to match the application descriptor.
 *
 * <p>Validation performs file I/O synchronously. Updates run on a daemon worker and progress
 * callbacks may also originate from transport helper threads. UI callers must marshal
 * these callbacks to their UI thread.
 */
public final class FirmwareUpdateService {
    private static final Logger log = LoggerFactory.getLogger(FirmwareUpdateService.class);
    // A settings panel can be recreated while an operation is active.
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private volatile Thread worker;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /**
     * Reports whether any service instance owns an update.
     *
     * @return {@code true} while the process-wide update slot is occupied
     */
    public boolean isRunning() { return RUNNING.get(); }

    /**
     * Requests cancellation of this instance's worker without waiting for it to finish.
     * Cleanup releases the reserved connection and attempts reconnection if handoff started.
     * Bytes already written to the device cannot be rolled back.
     */
    public void cancel() {
        cancelled.set(true);
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }

    /**
     * Checks the source file, selected transport and known hardware constraints without
     * sending commands to the device. A temporary payload snapshot is removed before return.
     *
     * @param path selected source file, or {@code null} if none has been selected
     * @param mode requested mode; {@code null} is treated as {@link FirmwareUpdateMode#AUTO}
     * @param entry active connection profile, or {@code null}
     * @param state device state used for capability checks and archive selection, or {@code null}
     * @return analyzed source image, localized errors and confirmation warnings
     */
    public FirmwareValidationResult validate(Path path, FirmwareUpdateMode mode, ConnectionEntry entry, DeviceState state) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        validateConnection(entry, state, errors);
        FirmwareImage image = null;
        if (path == null) errors.add(I18n.t("settings.firmware.validation.noFile"));
        else try {
            if (!Files.isRegularFile(path) || Files.size(path) == 0 || Files.size(path) > FirmwarePayload.MAX_BYTES)
                throw new IOException(I18n.t("settings.firmware.validation.invalidImage", "Missing, empty or oversized file"));
            image = FirmwareImage.analyze(path);
            if (image.type() == FirmwareImageType.UNKNOWN) throw new IOException(I18n.t("settings.firmware.validation.unknownType"));
            try (var payload = FirmwarePayload.prepare(path, state)) {
                validatePayload(payload, payloadMode(payload, mode, entry), entry, state, errors, warnings);
            }
        } catch (Exception e) { errors.add(errorDetail(e)); }
        return new FirmwareValidationResult(image, errors, warnings);
    }

    /**
     * Resolves automatic mode from the extracted payload and active transport.
     * An explicitly selected mode is returned unchanged and still requires validation.
     * ZIP inspection performs synchronous file I/O.
     *
     * @param image analyzed source, or {@code null} to use only the transport
     * @param mode explicit mode, {@code AUTO}, or {@code null}
     * @param entry connection whose transport supplies the default, or {@code null}
     * @param state optional device metadata for selecting an archive member
     * @return resolved mode, or {@code AUTO} if the archive or transport cannot be resolved
     */
    public FirmwareUpdateMode resolveMode(FirmwareImage image, FirmwareUpdateMode mode, ConnectionEntry entry, DeviceState state) {
        if (mode != null && mode != FirmwareUpdateMode.AUTO) return mode;
        if (image != null && image.type() == FirmwareImageType.ZIP) {
            try (var payload = FirmwarePayload.prepare(image.path(), state)) { return payloadMode(payload, mode, entry); }
            catch (IOException ignored) { return FirmwareUpdateMode.AUTO; }
        }
        if (image != null && image.type() == FirmwareImageType.UF2) return FirmwareUpdateMode.DFU;
        return transportMode(entry);
    }

    private static FirmwareUpdateMode transportMode(ConnectionEntry entry) {
        if (entry == null) return FirmwareUpdateMode.AUTO;
        return switch (entry.getEffectiveType()) {
            case SERIAL -> FirmwareUpdateMode.SERIAL;
            case BLE -> FirmwareUpdateMode.OTA_BLE;
            case TCP -> FirmwareUpdateMode.OTA_WIFI;
            default -> FirmwareUpdateMode.AUTO;
        };
    }
    /** Uses the validated payload type rather than the enclosing ZIP's extension. */
    private static FirmwareUpdateMode payloadMode(FirmwarePayload p, FirmwareUpdateMode requested, ConnectionEntry entry) {
        if (requested != null && requested != FirmwareUpdateMode.AUTO) return requested;
        return p.initPacket != null || p.image.type() == FirmwareImageType.UF2 ? FirmwareUpdateMode.DFU : transportMode(entry);
    }

    private static void validateConnection(ConnectionEntry entry, DeviceState state, List<String> errors) {
        if (entry == null || !entry.isConnected()) errors.add(I18n.t("settings.firmware.validation.noRadio"));
        if (entry != null && entry.getEffectiveProtocol() != ProtocolType.MESHTASTIC) errors.add(I18n.t("settings.firmware.validation.meshtasticOnly"));
        if (state == null || state.getMyNodeNum() == 0) errors.add(I18n.t("settings.firmware.validation.noState"));
    }

    /** Accumulates mode-specific errors and warnings; does not modify the device or payload. */
    private static void validatePayload(FirmwarePayload p, FirmwareUpdateMode mode, ConnectionEntry entry,
                                        DeviceState state, List<String> errors, List<String> warnings) {
        if (!FirmwareHardware.compatible(p, state)) errors.add(I18n.t("settings.firmware.validation.wrongHardware"));
        ConnectionType transport = entry != null ? entry.getEffectiveType() : null;
        var metadata = state != null ? state.getDeviceMetadata() : null;
        if (mode == FirmwareUpdateMode.DFU) {
            if (p.initPacket != null) {
                if (transport != ConnectionType.BLE) errors.add(I18n.t("settings.firmware.validation.dfuNeedsBle"));
            } else if (p.image.type() == FirmwareImageType.UF2) {
                if (transport != ConnectionType.SERIAL) errors.add(I18n.t("settings.firmware.validation.uf2NeedsUsb"));
                warnings.add(I18n.t("settings.firmware.confirm.uf2"));
            } else errors.add(I18n.t("settings.firmware.validation.dfuNeedsPackage"));
            return;
        }
        if (p.initPacket != null || p.image.type() != FirmwareImageType.ESP32_BIN) {
            errors.add(I18n.t("settings.firmware.validation.otaNeedsBin")); return;
        }
        if (mode == FirmwareUpdateMode.SERIAL) {
            if (transport != ConnectionType.SERIAL || entry.getPortName() == null || entry.getPortName().isBlank())
                errors.add(I18n.t("settings.firmware.validation.serialRequired"));
            warnings.add(I18n.t("settings.firmware.confirm.serial"));
            return;
        }
        if (mode == FirmwareUpdateMode.OTA_BLE && transport != ConnectionType.BLE)
            errors.add(I18n.t("settings.firmware.validation.bleRequired"));
        if (mode == FirmwareUpdateMode.OTA_WIFI && (transport != ConnectionType.TCP || entry.getHost() == null || entry.getHost().isBlank()))
            errors.add(I18n.t("settings.firmware.validation.wifiNeedsTcp"));
        if (mode == FirmwareUpdateMode.AUTO) errors.add(I18n.t("settings.firmware.validation.noRadio"));
        if (metadata == null) errors.add(I18n.t("settings.firmware.validation.noState"));
        else {
            if (!metadata.getHasWifi()) errors.add(I18n.t("settings.firmware.validation.wrongHardware"));
            if (mode == FirmwareUpdateMode.OTA_BLE && !metadata.getHasBluetooth()) errors.add(I18n.t("settings.firmware.validation.bluetoothNotAdvertised"));
            var version = FirmwareVersion.parse(metadata.getFirmwareVersion());
            if (version.isEmpty() || !version.get().isAtLeast(new FirmwareVersion(2,7,17)))
                errors.add(I18n.t("settings.firmware.validation.otaVersion"));
        }
        warnings.add(I18n.t("settings.firmware.confirm.boardMatch"));
    }

    /**
     * Starts an update after acquiring the process-wide update slot.
     * The source is snapshotted and its hash is rechecked against the confirmed image before
     * disconnecting the application. Expected failures, cancellation and a busy slot are
     * represented by an unsuccessful result rather than exceptional completion.
     *
     * @param request confirmed image, active device and optional target selectors
     * @param progress optional callback; must be thread-safe and must not throw
     * @return future completed after transfer, reconnection verification and cleanup
     * @throws NullPointerException if {@code request} is {@code null}
     * @see #cancel()
     */
    public CompletableFuture<FirmwareUpdateResult> start(FirmwareUpdateRequest request, Consumer<FirmwareUpdateProgress> progress) {
        Objects.requireNonNull(request, "request");
        if (!RUNNING.compareAndSet(false, true)) return CompletableFuture.completedFuture(failed(request.mode(), I18n.t("settings.firmware.status.busy")));
        cancelled.set(false);
        var result = new CompletableFuture<FirmwareUpdateResult>();
        Thread updateWorker = new Thread(() -> {
            FirmwareUpdateResult outcome;
            try { outcome = run(request, progress); }
            catch (Exception | LinkageError e) {
                boolean interrupted = cancelled.get() || e instanceof InterruptedException;
                log.warn("Firmware update failed", e);
                String message = interrupted ? I18n.t("settings.firmware.status.cancelled") : I18n.t("settings.firmware.status.failed", errorDetail(e));
                emit(progress, FirmwareUpdateStage.FAILED, 1, message);
                outcome = failed(request.mode(), message);
            } finally { worker = null; RUNNING.set(false); }
            result.complete(outcome);
        }, "firmware-update");
        updateWorker.setDaemon(true);
        worker = updateWorker;
        updateWorker.start();
        return result;
    }

    /**
     * Owns the payload snapshot and connection reservation for one update. The refusal
     * listener remains attached until cleanup so a routing ACK alone cannot imply success.
     */
    private FirmwareUpdateResult run(FirmwareUpdateRequest request, Consumer<FirmwareUpdateProgress> progress) throws Exception {
        checkCancelled();
        emit(progress, FirmwareUpdateStage.VALIDATING, .05, I18n.t("settings.firmware.status.validating"));
        var errors = new ArrayList<String>(); validateConnection(request.connectionEntry(), request.state(), errors);
        if (!errors.isEmpty() || request.image() == null || request.handler() == null)
            throw new IOException(String.join("; ", errors));
        try (var payload = FirmwarePayload.prepare(request.image().path(), request.state())) {
            if (!MessageDigest.isEqual(request.image().sha256(), payload.sourceHash))
                throw new IOException(I18n.t("settings.firmware.validation.fileChanged"));
            var mode = payloadMode(payload, request.mode(), request.connectionEntry());
            validatePayload(payload, mode, request.connectionEntry(), request.state(), errors, new ArrayList<>());
            if (!errors.isEmpty()) throw new IOException(String.join("; ", errors));
            if (mode == FirmwareUpdateMode.DFU && payload.image.type() == FirmwareImageType.UF2 && request.uf2TargetSelector() == null)
                throw new IOException(I18n.t("settings.firmware.validation.uf2TargetRequired"));
            FirmwareSerialUploader serial = mode == FirmwareUpdateMode.SERIAL ? FirmwareSerialUploader.discover() : null;
            ConnectionManager manager = ConnectionManager.getInstance();
            String id = request.connectionEntry().getId();
            int expectedNode = request.state().getMyNodeNum();
            manager.beginFirmwareUpdate(id, request.handler());
            boolean handoff = false, released = false, ack = false;
            var refusal = new CompletableFuture<String>();
            FromRadioListener listener = new FromRadioListener() {
                @Override public void onClientNotification(MeshProtos.ClientNotification n) {
                    if (isOtaRefusal(n)) refusal.complete(n.getMessage());
                }
            };
            request.handler().addListener(listener);
            try {
                checkCancelled();
                if (mode != FirmwareUpdateMode.SERIAL) {
                    waitForSessionKey(request, progress);
                    emit(progress, FirmwareUpdateStage.SENDING_COMMAND, .15, I18n.t("settings.firmware.status.sendingCommand"));
                    CompletableFuture<MeshProtos.Routing.Error> command = mode == FirmwareUpdateMode.DFU
                            ? MessageService.enterDfuMode(request.handler(), request.state())
                            : MessageService.requestOtaMode(request.handler(), request.state(),
                                    mode == FirmwareUpdateMode.OTA_WIFI ? AdminProtos.OTAMode.OTA_WIFI : AdminProtos.OTAMode.OTA_BLE,
                                    payload.image.sha256());
                    // The command can reboot before its routing ACK arrives. Refusals still win.
                    handoff = true;
                    try {
                        Object response = CompletableFuture.anyOf(command, refusal).get(15, TimeUnit.SECONDS);
                        if (response instanceof String message) throw new IOException(message);
                        if (response != null && response != MeshProtos.Routing.Error.NONE) throw new IOException("Device: " + response);
                        ack = true;
                    } catch (TimeoutException ignored) {
                        log.info("Firmware command ACK timed out; checking bootloader directly");
                    } catch (ExecutionException e) {
                        if (request.connectionEntry().isConnected()) throw e;
                    }
                    emit(progress, FirmwareUpdateStage.WAITING_FOR_REBOOT, .2, I18n.t("settings.firmware.status.waitingForReboot"));
                    for (int i = 0; i < 20; i++) { checkRefusal(refusal); Thread.sleep(100); }
                }
                checkCancelled(); checkRefusal(refusal);
                handoff = true;
                manager.disconnect(id);
                emit(progress, FirmwareUpdateStage.CONNECTING_UPLOADER, .25, I18n.t("settings.firmware.status.startingOta"));
                switch (mode) {
                    case SERIAL -> serial.upload(request.connectionEntry().getPortName(), payload.image, progress);
                    case OTA_WIFI -> uploadWifi(payload.image, request.connectionEntry().getHost(), progress);
                    case OTA_BLE -> new FirmwareBleUploader().upload(payload, request, progress);
                    case DFU -> {
                        if (payload.initPacket != null) new FirmwareBleUploader().upload(payload, request, progress);
                        else new FirmwareUf2Uploader().upload(payload, request, progress);
                    }
                    default -> throw new IllegalStateException("Unresolved firmware mode");
                }
                manager.endFirmwareUpdate(id, true); released = true;
                emit(progress, FirmwareUpdateStage.VERIFYING, .95, I18n.t("settings.firmware.status.waitingForApplication"));
                awaitApplication(manager, id, expectedNode, payload.applicationVersion());
                String message = I18n.t("settings.firmware.status.complete");
                emit(progress, FirmwareUpdateStage.COMPLETE, 1, message);
                return new FirmwareUpdateResult(true, mode, ack, true, MeshProtos.Routing.Error.NONE, message);
            } finally {
                request.handler().removeListener(listener);
                if (!released) manager.endFirmwareUpdate(id, handoff);
            }
        }
    }

    /**
     * Recognizes OTA/partition failures delivered outside the routing ACK channel.
     * Text matching also covers firmware versions that do not assign an error severity.
     *
     * @param notification device-originated client notification
     * @return whether the notification should abort the bootloader handoff
     */
    static boolean isOtaRefusal(MeshProtos.ClientNotification notification) {
        String text = notification.getMessage().toLowerCase(Locale.ROOT);
        return (text.contains("ota") || text.contains("partition")) &&
                (notification.getLevelValue() >= 40 || text.contains("cannot") || text.contains("unable")
                || text.contains("invalid") || text.contains("not support") || text.contains("missing") || text.contains("failed"));
    }
    private static void checkRefusal(CompletableFuture<String> refusal) throws IOException {
        if (refusal.isDone()) throw new IOException(refusal.join());
    }
    /** Requests a missing admin passkey and allows up to five seconds for its asynchronous arrival. */
    @SuppressWarnings("BusyWait") // DeviceState is updated by protocol callbacks and has no passkey future.
    private void waitForSessionKey(FirmwareUpdateRequest request, Consumer<FirmwareUpdateProgress> progress) throws Exception {
        if (request.state().getSessionPasskey() != null) return;
        emit(progress, FirmwareUpdateStage.REQUESTING_SESSION_KEY, .1, I18n.t("settings.firmware.status.requestSessionKey"));
        MessageService.requestSessionPasskey(request.handler(), request.state());
        for (int i = 0; i < 50 && request.state().getSessionPasskey() == null; i++) { checkCancelled(); Thread.sleep(100); }
    }
    private void uploadWifi(FirmwareImage image, String host, Consumer<FirmwareUpdateProgress> progress) throws Exception {
        uploadWifi(image, new FirmwareOtaTcpUploader(host, FirmwareOtaTcpUploader.DEFAULT_PORT), progress);
    }

    /**
     * Retries connection establishment while the OTA loader starts, at most ten times.
     * Once a connection is established, protocol and transfer failures propagate without
     * replaying a potentially accepted image.
     *
     * @param image validated binary snapshot
     * @param uploader uploader for the selected host
     * @param progress optional progress callback
     * @throws Exception if cancelled or if connection or transfer fails
     */
    @SuppressWarnings("BusyWait") // Bounded retry backoff while the rebooted loader opens its TCP port.
    void uploadWifi(FirmwareImage image, FirmwareOtaTcpUploader uploader, Consumer<FirmwareUpdateProgress> progress) throws Exception {
        for (int attempt = 1; ; attempt++) {
            checkCancelled();
            emit(progress, FirmwareUpdateStage.CONNECTING_UPLOADER, .25, I18n.t("settings.firmware.status.connectingOta", attempt, 10));
            try { uploader.upload(image, progress); return; }
            catch (FirmwareOtaTcpUploader.ConnectFailedException e) {
                if (attempt >= 10) throw e;
                Thread.sleep(2000);
            }
        }
    }
    /**
     * Waits up to two minutes for configuration and metadata from the original node.
     * A nonempty expected version must match exactly; an empty version skips that check
     * for payload formats without an ESP application descriptor.
     */
    private void awaitApplication(ConnectionManager manager, String id, int expectedNode, String expectedVersion) throws Exception {
        for (int i = 0; i < 240; i++) {
            checkCancelled();
            var future = manager.getConfigFuture(id);
            if (future != null && future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
                DeviceState state = future.getNow(null);
                if (state != null && state.getMyNodeNum() == expectedNode && state.getDeviceMetadata() != null) {
                    String actual = state.getDeviceMetadata().getFirmwareVersion();
                    if (!expectedVersion.isBlank() && !actual.equals(expectedVersion))
                        throw new IOException(I18n.t("settings.firmware.status.versionMismatch", expectedVersion, actual));
                    return;
                }
            }
            Thread.sleep(500);
        }
        throw new IOException(I18n.t("settings.firmware.status.applicationUnconfirmed"));
    }
    private void checkCancelled() throws InterruptedException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
    private static FirmwareUpdateResult failed(FirmwareUpdateMode mode, String message) {
        return new FirmwareUpdateResult(false, mode, false, false, null, message);
    }
    private static void emit(Consumer<FirmwareUpdateProgress> consumer, FirmwareUpdateStage stage, double value, String message) {
        if (consumer != null) consumer.accept(new FirmwareUpdateProgress(stage, value, message));
    }
    private static String errorDetail(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }
}
