package com.meshtastic.client.service;

import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import com.google.protobuf.InvalidProtocolBufferException;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.notification.NotificationManager;
import com.meshtastic.client.protocol.FromRadioListener;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.system.DrawerManager;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Сервис обработки входящих mesh-пакетов ({@code MeshPacket}).
 * <p>
 * Реализует {@link FromRadioListener#onMeshPacket} и распределяет пакеты
 * по типу portnum:
 * <ul>
 *   <li>{@code TEXT_MESSAGE_APP} — текстовые сообщения (канальные и DM)</li>
 *   <li>{@code ROUTING_APP} — ACK/NAK для отправленных сообщений</li>
 *   <li>{@code NODEINFO_APP} — обновление информации о ноде</li>
 *   <li>{@code POSITION_APP} — обновление координат ноды</li>
 *   <li>{@code TELEMETRY_APP} — метрики устройства и окружения</li>
 *   <li>{@code TRACEROUTE_APP} — ответ на traceroute</li>
 *   <li>{@code ADMIN_APP} — admin-ответы (owner info и др.)</li>
 * </ul>
 * Обновляет {@link com.meshtastic.client.model.DeviceState}, сохраняет сообщения
 * в БД через {@link MessageDbService}, синхронизирует кэш нод через {@link NodeCacheService}.
 */
public class MessageListenerService implements FromRadioListener {

    private static final Logger log = LoggerFactory.getLogger(MessageListenerService.class);
    private static final int DEFERRED_MESH_WARN_THRESHOLD = 200;
    private static final int DEFERRED_MESH_WARN_STEP = 200;

    private final DeviceState deviceState;
    private final NotificationManager notificationManager;
    private final ProtocolHandler protocolHandler;
    private final Object deferredMeshLock = new Object();
    private final List<DeferredMeshPacket> deferredMeshPackets = new ArrayList<>();
    private int deferredMeshWarnBucket;

    private record DeferredMeshPacket(long channelCatalogEpoch, MeshProtos.MeshPacket packet) {}

    public MessageListenerService(DeviceState deviceState) {
        this(deviceState, null);
    }

    public MessageListenerService(DeviceState deviceState, ProtocolHandler protocolHandler) {
        this.deviceState = deviceState;
        this.notificationManager = new NotificationManager(deviceState);
        this.protocolHandler = protocolHandler;
    }

    public NotificationManager getNotificationManager() {
        return notificationManager;
    }

    @Override
    public void onMyNodeInfo(MeshProtos.MyNodeInfo myInfo) {
        Platform.runLater(this::flushDeferredMeshPacketsIfOwnerKnown);
    }

    @Override
    public void onConfigComplete(int configCompleteId) {
        Platform.runLater(this::flushDeferredMeshPacketsIfOwnerKnown);
    }

    @Override
    public void onMeshPacket(MeshProtos.MeshPacket packet) {
        if (!packet.hasDecoded()) { return; }
        if (deviceState.getMyNodeNum() == 0) {
            deferMeshPacket(packet, "local node id is unknown");
            return;
        }

        // Track channel index per node from incoming packets
        int fromNum = packet.getFrom();
        if (fromNum != 0 && fromNum != deviceState.getMyNodeNum() && packet.getChannel() != 0) {
            NodeData channelNode = deviceState.getNodeDb().get(fromNum);
            if (channelNode != null) {
                channelNode.setChannel(packet.getChannel());
            }
        }

        MeshProtos.Data data = packet.getDecoded();
        dispatchDecodedPacket(packet, data);
    }

    private void dispatchDecodedPacket(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        if (data.getPortnum() == Portnums.PortNum.TEXT_MESSAGE_APP) {
            handleTextMessage(packet, data);
        } else if (data.getPortnum() == Portnums.PortNum.ROUTING_APP) {
            handleRoutingAck(packet, data);
        } else if (data.getPortnum() == Portnums.PortNum.NODEINFO_APP) {
            handleNodeInfoResponse(packet, data);
        } else if (data.getPortnum() == Portnums.PortNum.POSITION_APP) {
            handlePositionResponse(packet, data);
        } else if (data.getPortnum() == Portnums.PortNum.TELEMETRY_APP) {
            handleTelemetryResponse(packet, data);
        } else if (data.getPortnum() == Portnums.PortNum.TRACEROUTE_APP) {
            handleTracerouteResponse(packet, data);
        } else if (data.getPortnum() == Portnums.PortNum.ADMIN_APP) {
            handleAdminResponse(packet, data);
        }
    }

    private void handleTextMessage(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        int from = packet.getFrom();
        int to = packet.getTo();
        boolean isDirect = to != 0xFFFFFFFF;
        if (!isDirect && !deviceState.isChannelCatalogReady()) {
            deferMeshPacket(packet, "channel catalog is not ready");
            return;
        }

        boolean outgoing = from == deviceState.getMyNodeNum();
        if (outgoing) {
            log.debug("Skipping outgoing text packet {} from local node: channel={} viaMqtt={} transport={}",
                    packet.getId(), packet.getChannel(), isMqttPacket(packet), packet.getTransportMechanism());
            return; // outgoing messages are already added by MessageService
        }
        if (!isDirect && !deviceState.hasEnabledChannel(packet.getChannel())) {
            log.warn("Dropping broadcast packet {}: channel {} is still unknown after deferred processing (viaMqtt={}, transport={})",
                    packet.getId(), packet.getChannel(), isMqttPacket(packet), packet.getTransportMechanism());
            return;
        }

        processIncomingTextMessage(packet, data, isDirect);
    }

    private void processIncomingTextMessage(MeshProtos.MeshPacket packet,
                                            MeshProtos.Data data,
                                            boolean isDirect) {
        int from = packet.getFrom();
        int to = packet.getTo();
        int channel = packet.getChannel();
        String text = data.getPayload().toString(StandardCharsets.UTF_8);
        long timestamp = packet.getRxTime() > 0 ? packet.getRxTime() : System.currentTimeMillis() / 1000;

        NodeData fromNode = deviceState.getOrCreateNode(from);
        String fromNodeId = fromNode.getNodeId();
        if (IgnoredNodeService.getInstance().isIgnored(fromNodeId)) {
            log.info("Dropping incoming {} from ignored node {}",
                    isReactionPacket(data) ? "reaction" : "message", fromNodeId);
            return;
        }
        if (!isDirect && !deviceState.hasEnabledChannel(channel)) {
            log.warn("Dropping broadcast packet {} from {}: unknown or disabled channel {} (viaMqtt={}, transport={})",
                    packet.getId(), fromNodeId, channel, isMqttPacket(packet), packet.getTransportMechanism());
            return;
        }
        if (isReactionPacket(data)) {
            handleReactionPacket(packet, data, timestamp, to == 0xFFFFFFFF, fromNode);
            return;
        }

        String toNodeId = (to == 0xFFFFFFFF) ? "!ffffffff" : deviceState.getOrCreateNode(to).getNodeId();

        MeshMessage msg = new MeshMessage(fromNodeId, toNodeId, channel, text, timestamp, false);
        msg.setPacketId(packet.getId());
        msg.setHopStart(packet.getHopStart());
        msg.setHopLimit(packet.getHopLimit());
        msg.setRxRssi(packet.getRxRssi());
        msg.setRxSnr(packet.getRxSnr());
        msg.setViaMqtt(isMqttPacket(packet));

        if (data.getReplyId() != 0) {
            log.info("REPLY_DEBUG recv: reply_id={} (0x{}) from {}",
                    data.getReplyId(), Integer.toHexString(data.getReplyId()), fromNodeId);
            msg.setReplyId(data.getReplyId());
        }

        if (fromNode.getLongName() != null) {
            msg.setSenderName(fromNode.getLongName());
        }

        String ownerNodeId = String.format("!%08x", deviceState.getMyNodeNum());
        int payloadBytes = data.getPayload().size();
        int textLength = text.length();
        boolean viaMqtt = msg.isViaMqtt();
        if (isDirect) {
            msg.setStatus(MeshMessage.DeliveryStatus.DELIVERED);
            hydrateReplyText(msg, ownerNodeId, "dm", fromNodeId);
            MessageDbService.getInstance().save(msg, "dm", fromNodeId, ownerNodeId);
            deviceState.addDirectMessage(msg, fromNodeId);
            log.info("Received DM from {} (packetId={}, channel={}, chars={}, bytes={}, replyId={}, viaMqtt={}, transport={})",
                    fromNodeId, packet.getId(), channel, textLength, payloadBytes, data.getReplyId(),
                    viaMqtt, packet.getTransportMechanism());
            try {
                notificationManager.onIncomingMessage(msg, "dm", fromNodeId);
            } catch (Throwable t) {
                log.error("Notification error", t);
            }
        } else {
            msg.setStatus(MeshMessage.DeliveryStatus.DELIVERED);
            String chatKey = String.valueOf(channel);
            hydrateReplyText(msg, ownerNodeId, "channel", chatKey);
            MessageDbService.getInstance().save(msg, "channel", chatKey, ownerNodeId);
            deviceState.addMessage(msg);
            log.info("Received channel {} message from {} (packetId={}, chars={}, bytes={}, replyId={}, viaMqtt={}, transport={})",
                    channel, fromNodeId, packet.getId(), textLength, payloadBytes, data.getReplyId(),
                    viaMqtt, packet.getTransportMechanism());
            try {
                notificationManager.onIncomingMessage(msg, "channel", String.valueOf(channel));
            } catch (Throwable t) {
                log.error("Notification error", t);
            }
        }

        // Показать красную точку на иконке "Чаты"
        Platform.runLater(() -> DrawerManager.setChatUnreadDot(true));
    }

    private void hydrateReplyText(MeshMessage msg, String ownerNodeId, String chatType, String chatKey) {
        if (msg.getReplyId() == 0 || msg.getReplyText() != null) {
            return;
        }

        MeshMessage original = MessageDbService.getInstance()
                .findByPacketId(msg.getReplyId(), chatType, chatKey, ownerNodeId);
        if (original == null) {
            original = findInMemoryReplyTarget(msg.getReplyId(), chatType, chatKey);
        }
        if (original != null) {
            msg.setReplyText(original.getText());
        }
    }

    private MeshMessage findInMemoryReplyTarget(int replyId, String chatType, String chatKey) {
        MeshMessage original = deviceState.findRuntimeMessageByPacketId(replyId);
        if (original == null) {
            return null;
        }
        return isMessageInChatScope(original, chatType, chatKey) ? original : null;
    }

    private static boolean isMessageInChatScope(MeshMessage message, String chatType, String chatKey) {
        if ("channel".equals(chatType)) {
            return "!ffffffff".equalsIgnoreCase(message.getToNodeId())
                    && String.valueOf(message.getChannelIndex()).equals(chatKey);
        }
        if ("dm".equals(chatType)) {
            return chatKey != null
                    && (chatKey.equalsIgnoreCase(message.getFromNodeId())
                    || chatKey.equalsIgnoreCase(message.getToNodeId()));
        }
        return false;
    }

    private static boolean isMqttPacket(MeshProtos.MeshPacket packet) {
        return packet.getViaMqtt()
                || packet.getTransportMechanism() == MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_MQTT;
    }

    private boolean deferMeshPacket(MeshProtos.MeshPacket packet, String reason) {
        if (packet == null) {
            return false;
        }

        int queuedPackets;
        synchronized (deferredMeshLock) {
            deferredMeshPackets.add(new DeferredMeshPacket(deviceState.getChannelCatalogEpoch(), packet));
            queuedPackets = deferredMeshPackets.size();
            maybeLogDeferredMeshBacklogLocked(queuedPackets, reason);
        }
        log.info("Deferring mesh packet {} on channel {} until {} (backlog={})",
                packet.getId(), packet.getChannel(), reason, queuedPackets);
        return true;
    }

    private void flushDeferredMeshPackets() {
        List<DeferredMeshPacket> deferred;
        synchronized (deferredMeshLock) {
            if (deferredMeshPackets.isEmpty()) {
                return;
            }
            deferred = new ArrayList<>(deferredMeshPackets);
            deferredMeshPackets.clear();
            deferredMeshWarnBucket = 0;
        }

        long currentEpoch = deviceState.getChannelCatalogEpoch();
        for (DeferredMeshPacket queued : deferred) {
            if (queued.channelCatalogEpoch() != currentEpoch) {
                log.debug("Discarding deferred mesh packet {} from stale config epoch {}",
                        queued.packet().getId(), queued.channelCatalogEpoch());
                continue;
            }
            MeshProtos.MeshPacket packet = queued.packet();
            if (!packet.hasDecoded()) {
                continue;
            }
            onMeshPacket(packet);
        }
    }

    private void flushDeferredMeshPacketsIfOwnerKnown() {
        if (deviceState.getMyNodeNum() == 0) {
            return;
        }
        flushDeferredMeshPackets();
    }

    private void maybeLogDeferredMeshBacklogLocked(int queuedPackets, String reason) {
        if (queuedPackets < DEFERRED_MESH_WARN_THRESHOLD) {
            return;
        }

        int bucket = ((queuedPackets - DEFERRED_MESH_WARN_THRESHOLD) / DEFERRED_MESH_WARN_STEP) + 1;
        if (bucket <= deferredMeshWarnBucket) {
            return;
        }

        deferredMeshWarnBucket = bucket;
        log.warn("Deferred mesh backlog grew to {} packets while waiting for {}",
                queuedPackets, reason);
    }

    private boolean isReactionPacket(MeshProtos.Data data) {
        return data.getReplyId() != 0 && data.getEmoji() != 0;
    }

    private void handleReactionPacket(MeshProtos.MeshPacket packet,
                                      MeshProtos.Data data,
                                      long timestamp,
                                      boolean channelMessage,
                                      NodeData fromNode) {
        String emoji = data.getPayload().toString(StandardCharsets.UTF_8);
        if (emoji == null || emoji.isEmpty()) {
            log.debug("Ignoring reaction packet {} with empty payload", packet.getId());
            return;
        }

        String fromNodeId = fromNode.getNodeId();

        MessageReaction reaction = new MessageReaction(
                data.getReplyId(),
                fromNodeId,
                emoji,
                timestamp,
                false
        );
        reaction.setPacketId(packet.getId());
        reaction.setStatus(MeshMessage.DeliveryStatus.DELIVERED);
        if (fromNode.getLongName() != null) {
            reaction.setSenderName(fromNode.getLongName());
        }

        String ownerNodeId = String.format("!%08x", deviceState.getMyNodeNum());
        if (channelMessage) {
            MessageDbService.getInstance().saveReaction(
                    reaction, "channel", String.valueOf(packet.getChannel()), ownerNodeId);
        } else {
            MessageDbService.getInstance().saveReaction(reaction, "dm", fromNodeId, ownerNodeId);
        }

        deviceState.fireMessageListeners();
    }

    private void handleRoutingAck(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        int requestId = data.getRequestId();
        if (requestId == 0) {
            log.debug("Ignoring routing packet with requestId=0 from !{}",
                    Integer.toHexString(packet.getFrom()));
            return;
        }

        try {
            MeshProtos.Routing routing = MeshProtos.Routing.parseFrom(data.getPayload());
            MeshMessage pending = deviceState.resolvePendingAck(requestId);
            boolean completedPacketAck = deviceState.completePendingPacketAck(requestId, routing.getErrorReason());
            MeshMessage.DeliveryStatus reactionStatus =
                    routing.getErrorReason() == MeshProtos.Routing.Error.NONE
                            ? MeshMessage.DeliveryStatus.DELIVERED
                            : MeshMessage.DeliveryStatus.FAILED;
            String reactionError = routing.getErrorReason() == MeshProtos.Routing.Error.NONE
                    ? null
                    : routing.getErrorReason().name();
            boolean updatedReaction = MessageDbService.getInstance()
                    .updateReactionStatus(requestId, reactionStatus, reactionError);

            if (pending == null && !completedPacketAck && !updatedReaction) {
                log.debug("No pending message or packet ACK waiter found for requestId={}", requestId);
                return;
            }

            if (pending == null) {
                if (updatedReaction) {
                    deviceState.fireMessageListeners();
                    log.debug("Routing ACK received for reaction packet {}", requestId);
                } else {
                    log.debug("Routing ACK received for non-message packet {}", requestId);
                }
                return;
            }

            if (routing.getErrorReason() == MeshProtos.Routing.Error.NONE) {
                pending.setStatus(MeshMessage.DeliveryStatus.DELIVERED);
                MessageDbService.getInstance().updateStatus(requestId, pending.getStatus(), null);
                deviceState.fireMessageListeners();
                log.debug("ACK received for packet {}", requestId);
            } else {
                pending.setStatus(MeshMessage.DeliveryStatus.FAILED);
                pending.setErrorReason(routing.getErrorReason().name());
                MessageDbService.getInstance().updateStatus(requestId, pending.getStatus(), pending.getErrorReason());
                deviceState.fireMessageListeners();
                log.warn("NAK received for packet {}: {}", requestId, routing.getErrorReason());
            }
        } catch (Exception e) {
            log.warn("Failed to parse routing ACK for packet {}", requestId, e);
        }
    }

    private void handleNodeInfoResponse(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        int fromNum = packet.getFrom();
        try {
            MeshProtos.User user = MeshProtos.User.parseFrom(data.getPayload());
            NodeData node = deviceState.getOrCreateNode(fromNum);
            int rxTime = packet.getRxTime() > 0 ? packet.getRxTime() : (int)(System.currentTimeMillis() / 1000);
            node.setLastHeard(rxTime);
            // Protobuf возвращает "" для незаполненных строковых полей —
            // пустые значения не должны затирать существующие данные
            if (!user.getLongName().isEmpty()) { node.setLongName(user.getLongName()); }
            if (!user.getShortName().isEmpty()) { node.setShortName(user.getShortName()); }
            if (!user.getId().isEmpty()) { node.setNodeId(user.getId()); }
            if (user.getRole() != ConfigProtos.Config.DeviceConfig.Role.CLIENT || node.getRole() == null) {
                node.setRole(user.getRole().name());
            }
            if (user.getHwModel() != MeshProtos.HardwareModel.UNSET || node.getHwModel() == null) {
                node.setHwModel(user.getHwModel().name());
            }
            if (!user.getPublicKey().isEmpty()) {
                node.setPublicKey(user.getPublicKey().toByteArray());
            }
            if (user.hasIsUnmessagable()) {
                node.setUnmessagable(user.getIsUnmessagable());
            }
            deviceState.fireNodeUpdateListeners(fromNum);
            NodeCacheService.getInstance().update(node);
            maybeSeedDirectContactFromNodeInfo(packet, node);
            log.info("Received NODEINFO_APP from !{}: {} (unmessagable={})",
                    Integer.toHexString(fromNum), user.getLongName(),
                    user.hasIsUnmessagable() ? user.getIsUnmessagable() : null);
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse User from NODEINFO_APP packet from !{}", Integer.toHexString(fromNum), e);
        }
    }

    private void maybeSeedDirectContactFromNodeInfo(MeshProtos.MeshPacket packet, NodeData node) {
        if (protocolHandler == null || node == null) { return; }
        if (packet.getFrom() == 0 || packet.getFrom() == deviceState.getMyNodeNum()) { return; }
        if (packet.getTo() != deviceState.getMyNodeNum()) { return; }

        byte[] publicKey = node.getPublicKey();
        if (publicKey == null || publicKey.length == 0) { return; }

        deviceState.ensureDirectMessageThread(node.getNodeId());
        MessageService.seedPeerContactForPki(protocolHandler, deviceState, node);
        log.debug("Prepared local PKI contact for {} after directed NODEINFO_APP", node.getNodeId());
    }

    private void handlePositionResponse(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        int fromNum = packet.getFrom();
        try {
            MeshProtos.Position position = MeshProtos.Position.parseFrom(data.getPayload());
            NodeData node = deviceState.getOrCreateNode(fromNum);
            if (!node.hasName()) {
                NodeCacheService.getInstance().enrichFromCache(node);
            }
            int rxTime = packet.getRxTime() > 0 ? packet.getRxTime() : (int)(System.currentTimeMillis() / 1000);
            node.setLastHeard(rxTime);
            log.debug("Position update: nodeNum={}, latI={}, lonI={}, alt={}",
                    fromNum, position.getLatitudeI(), position.getLongitudeI(), position.getAltitude());

            // If user recently saved a fixed position for our own node,
            // skip overwriting with potentially stale device position
            boolean isMyNode = fromNum == deviceState.getMyNodeNum();
            if (isMyNode && deviceState.hasPendingFixedPosition()) {
                log.info("Ignoring position update for own node — pending fixed position active");
            } else {
                // Нулевые координаты означают отсутствие данных — не затираем существующие
                if (position.getLatitudeI() != 0) { node.setLatitude(position.getLatitudeI() * 1e-7); }
                if (position.getLongitudeI() != 0) { node.setLongitude(position.getLongitudeI() * 1e-7); }
                if (position.getAltitude() != 0) { node.setAltitude(position.getAltitude()); }
            }

            deviceState.fireNodeUpdateListeners(fromNum);
            NodeCacheService.getInstance().update(node);
            log.info("Received POSITION_APP from !{}", Integer.toHexString(fromNum));
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse Position from POSITION_APP packet from !{}", Integer.toHexString(fromNum), e);
        }
    }

    private void handleTelemetryResponse(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        int fromNum = packet.getFrom();
        try {
            org.meshtastic.proto.TelemetryProtos.Telemetry telemetry =
                    org.meshtastic.proto.TelemetryProtos.Telemetry.parseFrom(data.getPayload());

            long ts = telemetry.getTime() > 0 ? telemetry.getTime()
                    : packet.getRxTime() > 0 ? packet.getRxTime() : System.currentTimeMillis() / 1000;

            NodeData node = deviceState.getOrCreateNode(fromNum);
            TelemetryEntry entry = new TelemetryEntry(ts, node.getNodeId());
            if (!node.hasName()) {
                NodeCacheService.getInstance().enrichFromCache(node);
            }
            node.setLastHeard((int) ts);

            if (telemetry.hasDeviceMetrics()) {
                org.meshtastic.proto.TelemetryProtos.DeviceMetrics dm = telemetry.getDeviceMetrics();
                node.setBatteryLevel(dm.getBatteryLevel());
                node.setVoltage(dm.getVoltage());
                node.setChannelUtilization(dm.getChannelUtilization());
                node.setAirUtilTx(dm.getAirUtilTx());
                node.setUptimeSeconds(dm.getUptimeSeconds());

                entry.setBatteryLevel(dm.getBatteryLevel());
                entry.setVoltage(dm.getVoltage());
                entry.setChannelUtilization(dm.getChannelUtilization());
                entry.setAirUtilTx(dm.getAirUtilTx());

                deviceState.fireNodeUpdateListeners(fromNum);
                NodeCacheService.getInstance().update(node);
                log.info("Received TELEMETRY_APP (device) from !{}", Integer.toHexString(fromNum));
            }

            if (telemetry.hasEnvironmentMetrics()) {
                org.meshtastic.proto.TelemetryProtos.EnvironmentMetrics em = telemetry.getEnvironmentMetrics();
                if (em.getTemperature() != 0) { node.setTemperature(em.getTemperature()); }

                if (em.getRelativeHumidity() != 0) { node.setRelativeHumidity(em.getRelativeHumidity()); }

                if (em.getBarometricPressure() != 0) { node.setBarometricPressure(em.getBarometricPressure()); }


                entry.setTemperature(em.getTemperature());
                entry.setRelativeHumidity(em.getRelativeHumidity());
                entry.setBarometricPressure(em.getBarometricPressure());

                deviceState.fireNodeUpdateListeners(fromNum);
                NodeCacheService.getInstance().update(node);
                log.info("Received TELEMETRY_APP (environment) from !{}", Integer.toHexString(fromNum));
            }

            if (telemetry.hasLocalStats()) {
                org.meshtastic.proto.TelemetryProtos.LocalStats ls = telemetry.getLocalStats();
                entry.setNumPacketsRx(ls.getNumPacketsRx());
                entry.setNumPacketsRxBad(ls.getNumPacketsRxBad());
                entry.setNumRxDupe(ls.getNumRxDupe());
                entry.setNumPacketsTx(ls.getNumPacketsTx());
                entry.setNumTxDropped(ls.getNumTxDropped());
                entry.setNumTxRelay(ls.getNumTxRelay());
                entry.setNumTxRelayCanceled(ls.getNumTxRelayCanceled());

                // LocalStats also carries channel_utilization and air_util_tx
                entry.setChannelUtilization(ls.getChannelUtilization());
                entry.setAirUtilTx(ls.getAirUtilTx());
                node.setChannelUtilization(ls.getChannelUtilization());
                node.setAirUtilTx(ls.getAirUtilTx());

                deviceState.fireNodeUpdateListeners(fromNum);
                NodeCacheService.getInstance().update(node);
                log.info("Received TELEMETRY_APP (localStats) from !{}: rx={}, bad={}, dupe={}, tx={}, dropped={}, relay={}, relayCanceled={}, chUtil={}, airUtil={}",
                        Integer.toHexString(fromNum), ls.getNumPacketsRx(), ls.getNumPacketsRxBad(), ls.getNumRxDupe(),
                        ls.getNumPacketsTx(), ls.getNumTxDropped(), ls.getNumTxRelay(), ls.getNumTxRelayCanceled(),
                        ls.getChannelUtilization(), ls.getAirUtilTx());
            }

            entry.setRxSnr(packet.getRxSnr());
            entry.setRxRssi(packet.getRxRssi());
            entry.setHopStart(packet.getHopStart());
            entry.setHopLimit(packet.getHopLimit());

            deviceState.addTelemetryEntry(entry);
            String ownerNodeId = String.format("!%08x", deviceState.getMyNodeNum());
            NodeCacheService.getInstance().persistTelemetry(entry, ownerNodeId);
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse Telemetry from TELEMETRY_APP packet from !{}", Integer.toHexString(fromNum), e);
        }
    }

    @SuppressWarnings("PMD.UnusedFormalParameter") // consistent handler signature
    private void handleAdminResponse(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        try {
            AdminProtos.AdminMessage adminMsg = AdminProtos.AdminMessage.parseFrom(data.getPayload());
            boolean hasSessionPasskey = !adminMsg.getSessionPasskey().isEmpty();
            if (hasSessionPasskey) {
                deviceState.setSessionPasskey(adminMsg.getSessionPasskey());
            }

            if (adminMsg.hasGetOwnerResponse()) {
                MeshProtos.User owner = adminMsg.getGetOwnerResponse();
                deviceState.setOwnerInfo(owner);
                mergeOwnerInfoIntoLocalNode(owner);
                deviceState.fireOwnerInfoListeners();
                log.info("Received owner info: longName='{}', shortName='{}'",
                        owner.getLongName(), owner.getShortName());
            } else if (adminMsg.hasGetDeviceMetadataResponse()) {
                MeshProtos.DeviceMetadata metadata = adminMsg.getGetDeviceMetadataResponse();
                deviceState.setDeviceMetadata(metadata);
                deviceState.fireDeviceMetadataListeners();
                log.debug("Received device metadata: firmwareVersion='{}', role={}",
                        metadata.getFirmwareVersion(), metadata.getRole());
            } else if (hasSessionPasskey) {
                // Session passkey is attached to get_x_response packets, not only owner info.
                // Save/channel edit flows wait on the same listener to unblock when the key arrives.
                deviceState.fireOwnerInfoListeners();
                log.debug("Received session passkey via ADMIN_APP response: {}",
                        adminMsg.getPayloadVariantCase());
            } else {
                log.debug("Received ADMIN_APP response: {}", adminMsg.getPayloadVariantCase());
            }
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse AdminMessage from ADMIN_APP packet", e);
        }
    }

    private void mergeOwnerInfoIntoLocalNode(MeshProtos.User owner) {
        int myNodeNum = deviceState.getMyNodeNum();
        if (myNodeNum == 0 || owner == null) { return; }

        NodeData node = deviceState.getOrCreateNode(myNodeNum);
        if (!owner.getLongName().isEmpty()) { node.setLongName(owner.getLongName()); }
        if (!owner.getShortName().isEmpty()) { node.setShortName(owner.getShortName()); }
        if (!owner.getId().isEmpty()) { node.setNodeId(owner.getId()); }
        if (owner.getRole() != ConfigProtos.Config.DeviceConfig.Role.CLIENT || node.getRole() == null) {
            node.setRole(owner.getRole().name());
        }
        if (owner.getHwModel() != MeshProtos.HardwareModel.UNSET || node.getHwModel() == null) {
            node.setHwModel(owner.getHwModel().name());
        }
        if (!owner.getPublicKey().isEmpty()) {
            node.setPublicKey(owner.getPublicKey().toByteArray());
        }
        if (owner.hasIsUnmessagable()) {
            node.setUnmessagable(owner.getIsUnmessagable());
        }
        deviceState.fireNodeUpdateListeners(myNodeNum);
        NodeCacheService.getInstance().update(node);
    }

    private void handleTracerouteResponse(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        // Игнорируем исходящий запрос (эхо от радио) — обрабатываем только ответ
        int myNodeNum = deviceState.getMyNodeNum();
        if (packet.getFrom() == myNodeNum) {
            log.debug("Ignoring outgoing TRACEROUTE_APP echo from self");
            return;
        }
        if (data.getRequestId() == 0) {
            log.debug("Ignoring TRACEROUTE_APP packet without requestId (not a response)");
            return;
        }

        try {
            MeshProtos.RouteDiscovery route = MeshProtos.RouteDiscovery.parseFrom(data.getPayload());
            deviceState.fireTracerouteListeners(packet.getFrom(), route);
            log.info("Received TRACEROUTE_APP response from !{}: route={}, route_back={}",
                    Integer.toHexString(packet.getFrom()), route.getRouteList(), route.getRouteBackList());
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse RouteDiscovery from TRACEROUTE_APP packet", e);
        }
    }
}
