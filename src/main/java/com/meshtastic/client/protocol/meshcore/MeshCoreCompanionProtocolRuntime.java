package com.meshtastic.client.protocol.meshcore;

import com.meshtastic.client.connection.FrameFormat;
import com.meshtastic.client.connection.FrameFormatAwareConnection;
import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.PacketMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime для MeshCore Companion Protocol.
 * <p>
 * Runtime работает поверх BLE RX/TX или raw TCP/Serial byte stream, отправляет
 * {@code APP_START}, запрашивает metadata устройства и сохраняет результат в
 * {@link MeshCoreCompanionState}.
 */
public final class MeshCoreCompanionProtocolRuntime implements ProtocolRuntime<MeshCoreCompanionState> {

    private static final Logger log = LoggerFactory.getLogger(MeshCoreCompanionProtocolRuntime.class);
    private static final long READY_TIMEOUT_MS = 5_000L;

    private final ProtocolRuntimeContext context;
    private final TransportConnection transport;
    private final MeshCoreCompanionState state = new MeshCoreCompanionState();
    private final CompletableFuture<MeshCoreCompanionState> readyFuture = new CompletableFuture<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger localPacketIds = new AtomicInteger(0x4D430000);
    private final Queue<MeshMessage> pendingMessageSends = new ConcurrentLinkedQueue<>();

    private volatile boolean closed;

    MeshCoreCompanionProtocolRuntime(ProtocolRuntimeContext context) {
        this.context = context;
        this.transport = context.transportConnection();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "meshcore-companion-runtime-" + context.connectionId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Возвращает активный protocol type этого runtime-а.
     *
     * @return {@link ProtocolType#MESHCORE_COMPANION}
     */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.MESHCORE_COMPANION;
    }

    /**
     * Возвращает текущее состояние, собранное из Companion responses.
     *
     * @return mutable runtime state
     */
    @Override
    public MeshCoreCompanionState getState() {
        return state;
    }

    /**
     * Возвращает future готовности runtime-а.
     *
     * @return future, завершающийся после получения {@code SELF_INFO}
     */
    @Override
    public CompletableFuture<MeshCoreCompanionState> getReadyFuture() {
        return readyFuture;
    }

    /**
     * Запускает Companion handshake и подписывает runtime на входящие packets.
     *
     * @return future готовности подключения
     */
    @Override
    public CompletableFuture<MeshCoreCompanionState> start() {
        if (transport instanceof FrameFormatAwareConnection frameAware) {
            frameAware.setFrameFormat(FrameFormat.MESHCORE_COMPANION);
        }
        transport.setDataListener(this::handlePacket);
        sendAppStart();
        scheduler.schedule(() -> {
            if (!readyFuture.isDone()) {
                readyFuture.completeExceptionally(new IllegalStateException(
                        "MeshCore Companion device did not respond during handshake"));
            }
        }, READY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return readyFuture;
    }

    /**
     * Возвращает owner id, если public key уже получен от устройства.
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
            readyFuture.completeExceptionally(new IllegalStateException("MeshCore Companion runtime closed"));
        }
    }

    /**
     * Отправляет стартовый {@code APP_START} packet.
     */
    private void sendAppStart() {
        sendCommand(MeshCoreCompanionFrames.appStart("meshapp"), "APP_START");
    }

    /**
     * Отправляет дополнительные metadata-запросы после успешного {@code SELF_INFO}.
     */
    private void sendPostReadyQueries() {
        scheduleCommand(0, MeshCoreCompanionFrames.deviceQuery(), "DEVICE_QUERY");
        scheduleCommand(150, MeshCoreCompanionFrames.getContacts(), "GET_CONTACTS");
        for (int i = 0; i < 8; i++) {
            scheduleCommand(300L + i * 80L, MeshCoreCompanionFrames.getChannel(i), "GET_CHANNEL " + i);
        }
        scheduleCommand(1_050, MeshCoreCompanionFrames.getBattery(), "GET_BATTERY");
        scheduleCommand(1_250, MeshCoreCompanionFrames.getMessage(), "GET_MESSAGE");
    }

    /**
     * Отправляет текстовое сообщение в MeshCore-канал.
     *
     * @param channelIndex индекс канала {@code 0..7}
     * @param text текст сообщения
     * @param replyId идентификатор сообщения-ответа; MeshCore Companion не переносит это поле
     * @return локально созданное сообщение или {@code null}, если отправка невозможна
     */
    public MeshMessage sendChannelMessage(int channelIndex, String text, int replyId) {
        if (closed || text == null || text.isBlank()) {
            return null;
        }
        long timestamp = System.currentTimeMillis() / 1000;
        int packetId = nextPacketId();
        MeshMessage message = new MeshMessage(
                ownerIdOrFallback(),
                "!ffffffff",
                channelIndex,
                text,
                timestamp,
                true);
        message.setReplyId(replyId);
        message.setPacketId(packetId);
        message.setStatus(MeshMessage.DeliveryStatus.SENDING);
        state.getDeviceState().addMessage(message);
        state.getDeviceState().registerPendingAck(packetId, message);
        pendingMessageSends.add(message);
        sendCommand(MeshCoreCompanionFrames.sendChannelText(channelIndex, timestamp, text),
                "SEND_CHANNEL_TEXT ch=" + channelIndex);
        return message;
    }

    /**
     * Отправляет личное сообщение MeshCore contact-у.
     *
     * @param peerNodeId node id контакта вида {@code mc:<12 hex>}
     * @param text текст сообщения
     * @param replyId идентификатор сообщения-ответа; MeshCore Companion не переносит это поле
     * @return локально созданное сообщение или {@code null}, если контакт не может быть адресован
     */
    public MeshMessage sendDirectMessage(String peerNodeId, String text, int replyId) {
        if (closed || text == null || text.isBlank()) {
            return null;
        }
        byte[] prefix = state.publicKeyPrefixForNode(peerNodeId);
        if (prefix.length < 6) {
            return null;
        }
        long timestamp = System.currentTimeMillis() / 1000;
        int packetId = nextPacketId();
        MeshMessage message = new MeshMessage(
                ownerIdOrFallback(),
                peerNodeId,
                0,
                text,
                timestamp,
                true);
        message.setReplyId(replyId);
        message.setPacketId(packetId);
        message.setStatus(MeshMessage.DeliveryStatus.SENDING);
        state.getDeviceState().addDirectMessage(message, peerNodeId);
        state.getDeviceState().registerPendingAck(packetId, message);
        pendingMessageSends.add(message);
        sendCommand(MeshCoreCompanionFrames.sendDirectText(prefix, timestamp, text),
                "SEND_DIRECT_TEXT " + peerNodeId);
        return message;
    }

    /**
     * Повторно отправляет неуспешное MeshCore-сообщение.
     *
     * @param message исходное сообщение со статусом {@code FAILED}
     * @return {@code true}, если повторная отправка поставлена в transport
     */
    public boolean retryMessage(MeshMessage message) {
        if (message == null || !message.isOutgoing()) {
            return false;
        }
        MeshMessage resent = "!ffffffff".equalsIgnoreCase(message.getToNodeId())
                ? sendChannelMessage(message.getChannelIndex(), message.getText(), message.getReplyId())
                : sendDirectMessage(message.getToNodeId(), message.getText(), message.getReplyId());
        if (resent == null) {
            return false;
        }
        if (message.getDbId() > 0) {
            MessageDbService.getInstance().updateMessageForRetry(
                    message.getDbId(),
                    message.getPacketId(),
                    resent.getPacketId(),
                    MeshMessage.DeliveryStatus.SENDING,
                    null);
        }
        return true;
    }

    /**
     * Обрабатывает входящий raw Companion packet.
     */
    private void handlePacket(byte[] packet) {
        if (closed || packet == null || packet.length == 0) {
            return;
        }

        int packetType = packet[0] & 0xFF;
        recordIncomingPacket(packet, packetName(packetType));
        switch (packetType) {
            case MeshCoreCompanionFrames.PACKET_SELF_INFO -> {
                parseSelfInfo(packet);
                completeReady();
                sendPostReadyQueries();
            }
            case MeshCoreCompanionFrames.PACKET_CONTACTS_START -> parseContactsStart(packet);
            case MeshCoreCompanionFrames.PACKET_CONTACT -> parseContact(packet);
            case MeshCoreCompanionFrames.PACKET_CONTACTS_END -> parseContactsEnd(packet);
            case MeshCoreCompanionFrames.PACKET_DEVICE_INFO -> parseDeviceInfo(packet);
            case MeshCoreCompanionFrames.PACKET_BATTERY -> parseBattery(packet);
            case MeshCoreCompanionFrames.PACKET_CHANNEL_INFO -> parseChannelInfo(packet);
            case MeshCoreCompanionFrames.PACKET_CONTACT_MSG_RECV,
                    MeshCoreCompanionFrames.PACKET_CONTACT_MSG_RECV_V3 -> {
                parseContactMessage(packet);
                sendCommand(MeshCoreCompanionFrames.getMessage(), "GET_MESSAGE");
            }
            case MeshCoreCompanionFrames.PACKET_CHANNEL_MSG_RECV,
                    MeshCoreCompanionFrames.PACKET_CHANNEL_MSG_RECV_V3 -> {
                parseChannelMessage(packet);
                sendCommand(MeshCoreCompanionFrames.getMessage(), "GET_MESSAGE");
            }
            case MeshCoreCompanionFrames.PACKET_ERROR -> parseError(packet);
            case MeshCoreCompanionFrames.PACKET_MESSAGES_WAITING -> {
                log.debug("MeshCore Companion reports queued messages");
                sendCommand(MeshCoreCompanionFrames.getMessage(), "GET_MESSAGE");
            }
            case MeshCoreCompanionFrames.PACKET_OK,
                    MeshCoreCompanionFrames.PACKET_MSG_SENT,
                    MeshCoreCompanionFrames.PACKET_ACK -> {
                markOldestPendingDelivered();
                log.debug("MeshCore Companion packet 0x{} received ({} bytes)",
                        Integer.toHexString(packetType), packet.length);
            }
            case MeshCoreCompanionFrames.PACKET_NO_MORE_MSGS,
                    MeshCoreCompanionFrames.PACKET_LOG_DATA -> log.debug(
                    "MeshCore Companion packet 0x{} received ({} bytes)",
                    Integer.toHexString(packetType), packet.length);
            default -> log.debug(
                    "Unhandled MeshCore Companion packet 0x{} ({} bytes)",
                    Integer.toHexString(packetType), packet.length);
        }
    }

    /**
     * Помечает runtime готовым после получения self-info.
     */
    private void completeReady() {
        state.setReady(true);
        readyFuture.complete(state);
    }

    /**
     * Разбирает {@code SELF_INFO}: power limits, public key и имя устройства.
     */
    private void parseSelfInfo(byte[] packet) {
        if (packet.length < 36) {
            log.warn("MeshCore Companion self-info packet too short: {} bytes", packet.length);
            return;
        }
        state.setTxPowerDbm((int) packet[2]);
        state.setMaxTxPowerDbm((int) packet[3]);
        state.setPublicKeyHex(MeshCoreCompanionFrames.hex(MeshCoreCompanionFrames.publicKey(packet)));
        state.setAdvertisementType(packet[1] & 0xFF);
        if (packet.length >= 58) {
            double latitude = MeshCoreCompanionFrames.signedIntLe(packet, 36) / 1_000_000.0;
            double longitude = MeshCoreCompanionFrames.signedIntLe(packet, 40) / 1_000_000.0;
            state.setAdvertisementPosition(latitude, longitude);
            state.setMultiAcks((packet[44] & 0xFF) != 0);
            state.setAdvertisementLocationPolicy(packet[45] & 0xFF);
            int telemetryModes = packet[46] & 0xFF;
            state.setTelemetryModes(
                    telemetryModes & 0b11,
                    (telemetryModes >>> 2) & 0b11,
                    (telemetryModes >>> 4) & 0b11);
            state.setManualAddContacts((packet[47] & 0xFF) != 0);
            state.setRadioParameters(
                    MeshCoreCompanionFrames.unsignedIntLe(packet, 48) / 1000.0,
                    MeshCoreCompanionFrames.unsignedIntLe(packet, 52) / 1000.0,
                    packet[56] & 0xFF,
                    packet[57] & 0xFF);
        }
        if (packet.length >= 58) {
            state.setDeviceName(MeshCoreCompanionFrames.text(packet, 58));
        }
        log.info("MeshCore Companion self-info received: name={}, publicKey={}",
                state.getDeviceName(), state.getPublicKeyHex() == null ? "?" : "present");
    }

    /**
     * Разбирает {@code DEVICE_INFO} и планирует запрос battery/storage metadata.
     */
    private void parseDeviceInfo(byte[] packet) {
        if (packet.length < 2) {
            return;
        }
        int protocolVersion = packet[1] & 0xFF;
        state.setFirmwareProtocolVersion(protocolVersion);
        if (protocolVersion >= 3 && packet.length >= 80) {
            state.setMaxContacts((packet[2] & 0xFF) * 2);
            state.setMaxChannels(packet[3] & 0xFF);
            state.setBlePin((int) MeshCoreCompanionFrames.unsignedIntLe(packet, 4));
            state.setFirmwareBuild(MeshCoreCompanionFrames.fixedText(packet, 8, 12));
            state.setModel(MeshCoreCompanionFrames.fixedText(packet, 20, 40));
            state.setFirmwareVersion(MeshCoreCompanionFrames.fixedText(packet, 60, 20));
        }
        scheduleCommand(250, MeshCoreCompanionFrames.getBattery(), "GET_BATTERY");
    }

    /**
     * Разбирает battery voltage и optional storage counters.
     */
    private void parseBattery(byte[] packet) {
        if (packet.length < 3) {
            return;
        }
        state.setBatteryMillivolts(MeshCoreCompanionFrames.unsignedShortLe(packet, 1));
        if (packet.length >= 11) {
            state.setStorage(
                    MeshCoreCompanionFrames.unsignedIntLe(packet, 3),
                    MeshCoreCompanionFrames.unsignedIntLe(packet, 7));
        }
    }

    /**
     * Сохраняет Companion error и завершает handshake ошибкой, если runtime ещё не готов.
     */
    private void parseError(byte[] packet) {
        String message = packet.length >= 2
                ? "MeshCore Companion error 0x" + String.format("%02X", packet[1] & 0xFF)
                : "MeshCore Companion error";
        state.setLastError(message);
        if (!readyFuture.isDone()) {
            readyFuture.completeExceptionally(new IllegalStateException(message));
        }
        log.warn(message);
    }

    /**
     * Разбирает начало списка MeshCore contacts.
     */
    private void parseContactsStart(byte[] packet) {
        if (packet.length >= 5) {
            state.setContactCount((int) MeshCoreCompanionFrames.unsignedIntLe(packet, 1));
        }
    }

    /**
     * Разбирает один contact из sync-ответа MeshCore.
     */
    private void parseContact(byte[] packet) {
        if (packet.length < 100) {
            log.debug("MeshCore contact packet too short: {} bytes", packet.length);
            return;
        }
        byte[] publicKey = Arrays.copyOfRange(packet, 1, 33);
        int type = packet[33] & 0xFF;
        String name = MeshCoreCompanionFrames.nullTerminatedText(packet, 100, 32);
        long lastAdvert = MeshCoreCompanionFrames.unsignedIntLe(packet, 132);
        Double latitude = null;
        Double longitude = null;
        if (packet.length >= 144) {
            latitude = MeshCoreCompanionFrames.signedIntLe(packet, 136) / 1_000_000.0;
            longitude = MeshCoreCompanionFrames.signedIntLe(packet, 140) / 1_000_000.0;
        }
        state.updateContact(publicKey, type, name, lastAdvert, latitude, longitude);
    }

    /**
     * Завершает sync списка контактов.
     */
    private void parseContactsEnd(byte[] packet) {
        if (packet.length >= 5) {
            state.setContactsLastModified(MeshCoreCompanionFrames.unsignedIntLe(packet, 1));
        }
    }

    /**
     * Разбирает описание канала MeshCore.
     */
    private void parseChannelInfo(byte[] packet) {
        if (packet.length < 2) {
            return;
        }
        int channelIndex = packet[1] & 0xFF;
        String channelName = MeshCoreCompanionFrames.nullTerminatedText(packet, 2, 32);
        boolean hasSecret = false;
        if (packet.length >= 50) {
            for (int i = 34; i < 50; i++) {
                if (packet[i] != 0) {
                    hasSecret = true;
                    break;
                }
            }
        }
        boolean enabled = channelIndex == 0 || (channelName != null && !channelName.isBlank()) || hasSecret;
        state.updateChannel(channelIndex, channelName, enabled);
    }

    /**
     * Разбирает входящее личное сообщение MeshCore.
     */
    private void parseContactMessage(byte[] packet) {
        boolean v3 = (packet[0] & 0xFF) == MeshCoreCompanionFrames.PACKET_CONTACT_MSG_RECV_V3;
        int offset = v3 ? 4 : 1;
        int minLength = v3 ? 16 : 13;
        if (packet.length < minLength) {
            return;
        }
        Float snr = v3 ? ((float) packet[1]) / 4.0f : null;
        byte[] prefix = Arrays.copyOfRange(packet, offset, offset + 6);
        int pathLength = packet[offset + 6] & 0xFF;
        int textType = packet[offset + 7] & 0xFF;
        long timestamp = MeshCoreCompanionFrames.unsignedIntLe(packet, offset + 8);
        int textOffset = offset + 12;
        if (textType == 2 && packet.length >= textOffset + 4) {
            textOffset += 4;
        }
        String text = MeshCoreCompanionFrames.text(packet, textOffset);
        if (text == null) {
            return;
        }
        state.addIncomingDirectMessage(prefix, text, timestamp, pathLength, snr, packetId(packet));
    }

    /**
     * Разбирает входящее сообщение MeshCore-канала.
     */
    private void parseChannelMessage(byte[] packet) {
        boolean v3 = (packet[0] & 0xFF) == MeshCoreCompanionFrames.PACKET_CHANNEL_MSG_RECV_V3;
        int offset = v3 ? 4 : 1;
        int minLength = v3 ? 11 : 8;
        if (packet.length < minLength) {
            return;
        }
        Float snr = v3 ? ((float) packet[1]) / 4.0f : null;
        int channelIndex = packet[offset] & 0xFF;
        int pathLength = packet[offset + 1] & 0xFF;
        int textType = packet[offset + 2] & 0xFF;
        long timestamp = MeshCoreCompanionFrames.unsignedIntLe(packet, offset + 3);
        int textOffset = offset + 7;
        if (textType == 2 && packet.length >= textOffset + 4) {
            textOffset += 4;
        }
        String text = MeshCoreCompanionFrames.text(packet, textOffset);
        if (text == null) {
            return;
        }
        state.addIncomingChannelMessage(channelIndex, text, timestamp, pathLength, snr, packetId(packet));
    }

    private void scheduleCommand(long delayMs, byte[] command, String description) {
        scheduler.schedule(() -> sendCommand(command, description), delayMs, TimeUnit.MILLISECONDS);
    }

    private void sendCommand(byte[] command, String description) {
        if (closed || command == null || command.length == 0) {
            return;
        }
        recordOutgoingPacket(command, description);
        transport.sendBytes(command, false);
    }

    private void markOldestPendingDelivered() {
        MeshMessage message = pendingMessageSends.poll();
        if (message == null) {
            return;
        }
        state.getDeviceState().resolvePendingAck(message.getPacketId());
        message.setStatus(MeshMessage.DeliveryStatus.DELIVERED);
        message.setErrorReason(null);
        MessageDbService.getInstance().updateStatus(message.getPacketId(), message.getStatus(), null);
        state.getDeviceState().fireMessageListeners();
    }

    private int nextPacketId() {
        int packetId = localPacketIds.incrementAndGet();
        return packetId != 0 ? packetId : localPacketIds.incrementAndGet();
    }

    private String ownerIdOrFallback() {
        String ownerId = state.getOwnerId();
        return ownerId != null ? ownerId : "mc:local";
    }

    private int packetId(byte[] packet) {
        int hash = Arrays.hashCode(packet);
        return hash != 0 ? hash : 1;
    }

    private void recordIncomingPacket(byte[] packet, String packetType) {
        PacketMonitorService monitor = PacketMonitorService.getIfInitialized();
        if (monitor != null) {
            monitor.recordRawIncoming(context.connectionId(), packetType, rawPacketPreview(packet), packet);
        }
    }

    private void recordOutgoingPacket(byte[] packet, String packetType) {
        PacketMonitorService monitor = PacketMonitorService.getIfInitialized();
        if (monitor != null) {
            monitor.recordRawOutgoing(context.connectionId(), packetType, rawPacketPreview(packet), packet);
        }
    }

    private static String rawPacketPreview(byte[] packet) {
        if (packet == null || packet.length == 0) {
            return "";
        }
        int type = packet[0] & 0xFF;
        String text = MeshCoreCompanionFrames.text(packet, Math.min(packet.length, 1));
        return text == null
                ? "type=0x%02X, bytes=%d".formatted(type, packet.length)
                : "type=0x%02X, bytes=%d, text=%s".formatted(type, packet.length, text);
    }

    private static String packetName(int packetType) {
        return switch (packetType) {
            case MeshCoreCompanionFrames.PACKET_OK -> "MeshCore Companion OK";
            case MeshCoreCompanionFrames.PACKET_ERROR -> "MeshCore Companion ERROR";
            case MeshCoreCompanionFrames.PACKET_CONTACTS_START -> "MeshCore Companion CONTACTS_START";
            case MeshCoreCompanionFrames.PACKET_CONTACT -> "MeshCore Companion CONTACT";
            case MeshCoreCompanionFrames.PACKET_CONTACTS_END -> "MeshCore Companion CONTACTS_END";
            case MeshCoreCompanionFrames.PACKET_SELF_INFO -> "MeshCore Companion SELF_INFO";
            case MeshCoreCompanionFrames.PACKET_MSG_SENT -> "MeshCore Companion MSG_SENT";
            case MeshCoreCompanionFrames.PACKET_CONTACT_MSG_RECV -> "MeshCore Companion CONTACT_MSG";
            case MeshCoreCompanionFrames.PACKET_CHANNEL_MSG_RECV -> "MeshCore Companion CHANNEL_MSG";
            case MeshCoreCompanionFrames.PACKET_NO_MORE_MSGS -> "MeshCore Companion NO_MORE_MSGS";
            case MeshCoreCompanionFrames.PACKET_BATTERY -> "MeshCore Companion BATTERY";
            case MeshCoreCompanionFrames.PACKET_DEVICE_INFO -> "MeshCore Companion DEVICE_INFO";
            case MeshCoreCompanionFrames.PACKET_CONTACT_MSG_RECV_V3 -> "MeshCore Companion CONTACT_MSG_V3";
            case MeshCoreCompanionFrames.PACKET_CHANNEL_MSG_RECV_V3 -> "MeshCore Companion CHANNEL_MSG_V3";
            case MeshCoreCompanionFrames.PACKET_CHANNEL_INFO -> "MeshCore Companion CHANNEL_INFO";
            case MeshCoreCompanionFrames.PACKET_ADVERTISEMENT -> "MeshCore Companion ADVERTISEMENT";
            case MeshCoreCompanionFrames.PACKET_ACK -> "MeshCore Companion ACK";
            case MeshCoreCompanionFrames.PACKET_MESSAGES_WAITING -> "MeshCore Companion MESSAGES_WAITING";
            case MeshCoreCompanionFrames.PACKET_LOG_DATA -> "MeshCore Companion LOG_DATA";
            default -> "MeshCore Companion 0x%02X".formatted(packetType);
        };
    }
}
