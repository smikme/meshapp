package com.meshtastic.client.protocol.meshcore;

import com.meshtastic.client.connection.FrameFormat;
import com.meshtastic.client.connection.FrameFormatAwareConnection;
import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runtime для MeshCore KISS modem devices.
 * <p>
 * Runtime переключает byte-stream transport в KISS framing, отправляет базовые
 * {@code SetHardware} requests и собирает metadata устройства в {@link MeshCoreKissState}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshCoreKissProtocolRuntime implements ProtocolRuntime<MeshCoreKissState> {

    private static final Logger log = LoggerFactory.getLogger(MeshCoreKissProtocolRuntime.class);
    private static final long READY_TIMEOUT_MS = 3_000L;

    private final ProtocolRuntimeContext context;
    private final TransportConnection transport;
    private final MeshCoreKissState state = new MeshCoreKissState();
    private final CompletableFuture<MeshCoreKissState> readyFuture = new CompletableFuture<>();
    private final ScheduledExecutorService scheduler;

    private volatile boolean closed;

    MeshCoreKissProtocolRuntime(ProtocolRuntimeContext context) {
        this.context = context;
        this.transport = context.transportConnection();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "meshcore-kiss-runtime-" + context.connectionId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Возвращает активный protocol type этого runtime-а.
     *
     * @return {@link ProtocolType#MESHCORE_KISS}
     */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.MESHCORE_KISS;
    }

    /**
     * Возвращает текущее состояние, собранное из MeshCore KISS responses.
     *
     * @return mutable runtime state
     */
    @Override
    public MeshCoreKissState getState() {
        return state;
    }

    /**
     * Возвращает future готовности runtime-а.
     *
     * @return future, завершающийся после первого валидного KISS response-а
     */
    @Override
    public CompletableFuture<MeshCoreKissState> getReadyFuture() {
        return readyFuture;
    }

    /**
     * Запускает KISS handshake и подписывает runtime на входящие frame-ы.
     *
     * @return future готовности подключения
     */
    @Override
    public CompletableFuture<MeshCoreKissState> start() {
        if (transport instanceof FrameFormatAwareConnection frameAware) {
            frameAware.setFrameFormat(FrameFormat.KISS);
        }
        transport.setDataListener(this::handleFrame);
        sendInitialRequests();
        scheduler.schedule(() -> {
            if (!readyFuture.isDone()) {
                readyFuture.completeExceptionally(new IllegalStateException(
                        "MeshCore KISS device did not respond during handshake"));
            }
        }, READY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return readyFuture;
    }

    /**
     * Возвращает owner id, если identity уже получен от устройства.
     *
     * @return короткий owner id вида {@code mc:<12 hex>} или {@code null}
     */
    @Override
    public String getOwnerId() {
        return state.getOwnerId();
    }

    /**
     * Останавливает runtime, снимает listener и завершает pending future ошибкой.
     */
    @Override
    public void close() {
        closed = true;
        transport.setDataListener(null);
        scheduler.shutdownNow();
        if (!readyFuture.isDone()) {
            readyFuture.completeExceptionally(new IllegalStateException("MeshCore KISS runtime closed"));
        }
    }

    /**
     * Отправляет стартовый набор metadata-запросов MeshCore KISS.
     */
    private void sendInitialRequests() {
        sendSetHardware(MeshCoreKissFrames.REQ_GET_DEVICE_NAME);
        sendSetHardware(MeshCoreKissFrames.REQ_GET_VERSION);
        sendSetHardware(MeshCoreKissFrames.REQ_GET_IDENTITY);
        sendSetHardware(MeshCoreKissFrames.REQ_GET_RADIO);
        sendSetHardware(MeshCoreKissFrames.REQ_GET_TX_POWER);
        sendSetHardware(MeshCoreKissFrames.REQ_GET_BATTERY);
        sendSetHardware(MeshCoreKissFrames.REQ_GET_STATS);
        sendSetHardware(MeshCoreKissFrames.REQ_PING);
    }

    /**
     * Отправляет один {@code SetHardware} request.
     */
    private void sendSetHardware(int subCommand) {
        transport.sendBytes(MeshCoreKissFrames.setHardwareRequest(subCommand), false);
    }

    /**
     * Обрабатывает входящий unescaped KISS frame.
     */
    private void handleFrame(byte[] frame) {
        if (closed || frame == null || frame.length == 0) {
            return;
        }

        int command = frame[0] & 0x0F;
        if (command == MeshCoreKissFrames.CMD_DATA) {
            log.debug("MeshCore KISS data frame received ({} bytes)", Math.max(0, frame.length - 1));
            return;
        }
        if (command != MeshCoreKissFrames.CMD_SET_HARDWARE || frame.length < 2) {
            log.debug("Ignoring non-MeshCore KISS frame: command=0x{}", Integer.toHexString(command));
            return;
        }

        int subCommand = frame[1] & 0xFF;
        byte[] payload = MeshCoreKissFrames.setHardwarePayload(frame);
        applySetHardwareResponse(subCommand, payload);
        completeReady();
    }

    /**
     * Помечает runtime готовым после первого распознанного ответа.
     */
    private void completeReady() {
        state.setReady(true);
        readyFuture.complete(state);
    }

    /**
     * Применяет {@code SetHardware} response к runtime state.
     */
    private void applySetHardwareResponse(int subCommand, byte[] payload) {
        switch (subCommand) {
            case MeshCoreKissFrames.RESP_DEVICE_NAME -> {
                String name = new String(payload, StandardCharsets.UTF_8).trim();
                state.setDeviceName(name.isBlank() ? null : name);
                log.info("MeshCore KISS device name: {}", state.getDeviceName());
            }
            case MeshCoreKissFrames.RESP_IDENTITY -> {
                state.setIdentityHex(MeshCoreKissFrames.hex(payload));
                log.info("MeshCore KISS identity received ({} bytes)", payload.length);
            }
            case MeshCoreKissFrames.RESP_VERSION -> {
                if (payload.length >= 1) {
                    state.setFirmwareVersion(payload[0] & 0xFF);
                }
            }
            case MeshCoreKissFrames.RESP_RADIO -> parseRadio(payload);
            case MeshCoreKissFrames.RESP_TX_POWER -> {
                if (payload.length >= 1) {
                    state.setTxPowerDbm((int) payload[0]);
                }
            }
            case MeshCoreKissFrames.RESP_BATTERY -> {
                if (payload.length >= 2) {
                    state.setBatteryMillivolts(unsignedShortLe(payload, 0));
                }
            }
            case MeshCoreKissFrames.RESP_STATS -> parseStats(payload);
            case MeshCoreKissFrames.RESP_TX_DONE -> {
                if (payload.length >= 1) {
                    state.setLastTxSuccess(payload[0] != 0);
                }
            }
            case MeshCoreKissFrames.RESP_RX_META -> {
                if (payload.length >= 2) {
                    float snrDb = ((int) payload[0]) / 4.0f;
                    int rssiDbm = (int) payload[1];
                    state.setLastRxMeta(snrDb, rssiDbm);
                }
            }
            case MeshCoreKissFrames.RESP_ERROR -> {
                String message = payload.length >= 1
                        ? "MeshCore KISS error 0x" + String.format("%02X", payload[0] & 0xFF)
                        : "MeshCore KISS error";
                state.setLastError(message);
                log.warn(message);
            }
            case MeshCoreKissFrames.RESP_OK, MeshCoreKissFrames.RESP_PONG -> {
                // Valid handshake responses without additional state.
            }
            default -> log.debug("Unhandled MeshCore KISS response 0x{}", Integer.toHexString(subCommand));
        }
    }

    /**
     * Разбирает параметры LoRa radio из little-endian payload-а.
     */
    private void parseRadio(byte[] payload) {
        if (payload.length < 10) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        long frequencyHz = Integer.toUnsignedLong(buffer.getInt());
        long bandwidthHz = Integer.toUnsignedLong(buffer.getInt());
        int spreadingFactor = buffer.get() & 0xFF;
        int codingRate = buffer.get() & 0xFF;
        state.setRadioParameters(new MeshCoreKissState.RadioParameters(
                frequencyHz, bandwidthHz, spreadingFactor, codingRate));
    }

    /**
     * Разбирает счётчики packet statistics из little-endian payload-а.
     */
    private void parseStats(byte[] payload) {
        if (payload.length < 12) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        state.setStats(new MeshCoreKissState.Stats(
                Integer.toUnsignedLong(buffer.getInt()),
                Integer.toUnsignedLong(buffer.getInt()),
                Integer.toUnsignedLong(buffer.getInt())));
    }

    /**
     * Читает unsigned 16-bit little-endian значение из payload-а.
     */
    private static int unsignedShortLe(byte[] payload, int offset) {
        return (payload[offset] & 0xFF) | ((payload[offset + 1] & 0xFF) << 8);
    }
}
