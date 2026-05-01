package com.meshtastic.client.protocol;

import com.google.protobuf.InvalidProtocolBufferException;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.FrameFormat;
import com.meshtastic.client.connection.FrameFormatAwareConnection;
import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.connection.ble.BleConnection;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.meshcore.MeshCoreKissFrames;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionFrames;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Выполняет probe уже открытого transport-а и выбирает поддерживаемый protocol runtime.
 * <p>
 * Для TCP/Serial transport временно переводится в {@link FrameFormat#AUTO}, после чего
 * последовательно проверяются MeshCore KISS, MeshCore Companion и Meshtastic. Для BLE
 * итоговый protocol type берётся из выбранного GATT profile.
 */
public final class ProtocolAutodetector {

    private static final Logger log = LoggerFactory.getLogger(ProtocolAutodetector.class);
    private static final long DETECT_TIMEOUT_MS = 2_000L;
    private static final long KISS_FIRST_TIMEOUT_MS = 250L;
    private static final long COMPANION_TIMEOUT_MS = 750L;

    private ProtocolAutodetector() {
    }

    /**
     * Определяет protocol type для открытого transport-а.
     * <p>
     * Метод не открывает и не закрывает transport. Он временно устанавливает listener
     * входящих frame-ов, отправляет probe-команды и возвращает первый распознанный протокол.
     * При полном таймауте используется Meshtastic fallback для совместимости со старыми
     * профилями и прошивками.
     *
     * @param context runtime context с открытым transport-ом и профилем подключения
     * @return определённый protocol type
     * @throws ConnectionException если auto-detect был прерван или завершился ошибкой transport-а
     */
    public static ProtocolType detect(ProtocolRuntimeContext context) throws ConnectionException {
        TransportConnection transport = context.transportConnection();
        if (context.connectionEntry().getEffectiveType() == ConnectionType.BLE) {
            if (transport instanceof BleConnection bleConnection) {
                BleProtocolProfile profile = bleConnection.getResolvedProfile();
                if (profile != null && profile != BleProtocolProfile.AUTO) {
                    return profile.protocolType();
                }
            }
            return ProtocolType.MESHTASTIC;
        }

        if (transport instanceof FrameFormatAwareConnection frameAware) {
            frameAware.setFrameFormat(FrameFormat.AUTO);
        }

        CompletableFuture<ProtocolType> detected = new CompletableFuture<>();
        AtomicReference<Throwable> parseError = new AtomicReference<>();
        transport.setDataListener(frame -> inspectFrame(frame, detected, parseError));

        try {
            sendMeshCoreProbes(transport);
            try {
                ProtocolType protocolType = detected.get(KISS_FIRST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                log.info("Auto-detected protocol {} for connection '{}'",
                        protocolType, context.connectionEntry().getName());
                return protocolType;
            } catch (TimeoutException ignored) {
                sendMeshCoreCompanionProbe(transport);
            }

            try {
                ProtocolType protocolType = detected.get(COMPANION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                log.info("Auto-detected protocol {} for connection '{}'",
                        protocolType, context.connectionEntry().getName());
                return protocolType;
            } catch (TimeoutException ignored) {
                sendMeshtasticProbe(transport);
            }

            long remainingTimeoutMs = Math.max(1L,
                    DETECT_TIMEOUT_MS - KISS_FIRST_TIMEOUT_MS - COMPANION_TIMEOUT_MS);
            ProtocolType protocolType = detected.get(remainingTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("Auto-detected protocol {} for connection '{}'",
                    protocolType, context.connectionEntry().getName());
            return protocolType;
        } catch (TimeoutException e) {
            Throwable lastParseError = parseError.get();
            if (lastParseError != null) {
                log.debug("Protocol auto-detect timed out after parser error", lastParseError);
            }
            log.info("Protocol auto-detect timed out for '{}'; falling back to Meshtastic",
                    context.connectionEntry().getName());
            return ProtocolType.MESHTASTIC;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionException("Protocol auto-detect interrupted", e);
        } catch (Exception e) {
            throw new ConnectionException("Protocol auto-detect failed: " + e.getMessage(), e);
        } finally {
            transport.setDataListener(null);
        }
    }

    /**
     * Анализирует входящий frame и завершает future, если frame однозначно принадлежит
     * одному из поддерживаемых протоколов.
     */
    private static void inspectFrame(byte[] frame,
                                     CompletableFuture<ProtocolType> detected,
                                     AtomicReference<Throwable> parseError) {
        if (frame == null || frame.length == 0 || detected.isDone()) {
            return;
        }

        if (MeshCoreKissFrames.isRecognizedResponseFrame(frame)) {
            detected.complete(ProtocolType.MESHCORE_KISS);
            return;
        }

        try {
            MeshProtos.FromRadio fromRadio = MeshProtos.FromRadio.parseFrom(frame);
            if (fromRadio.getPayloadVariantCase() != MeshProtos.FromRadio.PayloadVariantCase.PAYLOADVARIANT_NOT_SET) {
                detected.complete(ProtocolType.MESHTASTIC);
                return;
            }
        } catch (InvalidProtocolBufferException e) {
            parseError.set(e);
        }

        if (MeshCoreCompanionFrames.isRecognizedResponsePacket(frame)) {
            detected.complete(ProtocolType.MESHCORE_COMPANION);
        }
    }

    /**
     * Отправляет быстрые MeshCore KISS probes через {@code SetHardware}.
     */
    private static void sendMeshCoreProbes(TransportConnection transport) {
        transport.sendBytes(MeshCoreKissFrames.setHardwareRequest(MeshCoreKissFrames.REQ_GET_DEVICE_NAME), false);
        transport.sendBytes(MeshCoreKissFrames.setHardwareRequest(MeshCoreKissFrames.REQ_PING), false);
    }

    /**
     * Отправляет стартовый probe MeshCore Companion Protocol.
     */
    private static void sendMeshCoreCompanionProbe(TransportConnection transport) {
        transport.sendBytes(MeshCoreCompanionFrames.appStart("meshapp"), false);
    }

    /**
     * Отправляет Meshtastic {@code want_config_id}, чтобы получить валидный {@code FromRadio}.
     */
    private static void sendMeshtasticProbe(TransportConnection transport) {
        int wantConfigId = (int) (System.nanoTime() & 0x7FFFFFFF);
        if (wantConfigId == 0) {
            wantConfigId = 1;
        }
        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setWantConfigId(wantConfigId)
                .build();
        transport.sendBytes(PacketFramer.frame(toRadio), false);
    }
}
