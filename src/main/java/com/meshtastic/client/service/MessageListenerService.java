package com.meshtastic.client.service;

import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import org.meshtastic.proto.TelemetryProtos;
import com.google.protobuf.InvalidProtocolBufferException;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageChangeEvent;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.notification.NotificationManager;
import com.meshtastic.client.protocol.FromRadioListener;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.system.AppUi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles incoming mesh packets ({@code MeshPacket}).
 * <p>
 * The service implements {@link FromRadioListener#onMeshPacket}, routes packets
 * by portnum, updates {@link com.meshtastic.client.model.DeviceState}, persists
 * messages through {@link MessageDbService}, and keeps the node cache in sync
 * through {@link NodeCacheService}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
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

    private record DeferredMeshPacket(long channelCatalogEpoch,
                                      MeshProtos.MeshPacket packet,
                                      long receivedAtSeconds) {}

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
        AppUi.runLater(this::flushDeferredMeshPacketsIfOwnerKnown);
    }

    @Override
    public void onConfigComplete(int configCompleteId) {
        AppUi.runLater(this::flushDeferredMeshPacketsIfOwnerKnown);
    }

    @Override
    public void onNodeInfo(MeshProtos.NodeInfo nodeInfo) {
        if (nodeInfo == null || nodeInfo.getNum() == 0) {
            return;
        }
        NodeData node = applyNodeInfo(nodeInfo);
        int nodeNum = nodeInfo.getNum();
        deviceState.fireNodeUpdateListeners(nodeNum);
        NodeCacheService.getInstance().update(node);
        log.info("Received NodeInfo from !{}: {}", Integer.toHexString(nodeNum), node.getLongName());
    }

    @Override
    public void onMeshPacket(MeshProtos.MeshPacket packet) {
        onMeshPacket(packet, System.currentTimeMillis() / 1000);
    }

    @Override
    public void onToRadioSendFailed(MeshProtos.ToRadio toRadio, String reason) {
        if (toRadio == null || !toRadio.hasPacket()) {
            return;
        }
        int packetId = toRadio.getPacket().getId();
        if (packetId == 0) {
            return;
        }

        var pendingEntry = deviceState.getMessageStore().getPendingAcks().get(packetId);
        MeshMessage pending = pendingEntry != null ? pendingEntry.message() : null;
        if (pending != null) {
            failPendingMessageSend(packetId, pending, reason);
            return;
        }

        boolean completedPacketAck = deviceState.completePendingPacketAck(
                packetId, MeshProtos.Routing.Error.MAX_RETRANSMIT);
        MessageDbService db = MessageDbService.getInstance();
        MessageDbService.ReactionScope reactionScope = db.findReactionScopeByPacketId(packetId);
        boolean updatedReaction = db.updateReactionStatus(
                packetId,
                MeshMessage.DeliveryStatus.FAILED,
                reason != null ? reason : MeshProtos.Routing.Error.MAX_RETRANSMIT.name());

        if (updatedReaction) {
            fireReactionChanged(reactionScope);
            deviceState.fireMessageListeners();
            log.warn("Local send failed for reaction packet {}: {}", packetId, reason);
        } else if (completedPacketAck) {
            log.warn("Local send failed for non-message packet {}: {}", packetId, reason);
        } else {
            log.debug("Local send failure for packet {} did not match pending message, ACK waiter, or reaction",
                    packetId);
        }
    }

    private void onMeshPacket(MeshProtos.MeshPacket packet, long receivedAtSeconds) {
        if (!packet.hasDecoded()) { return; }
        if (deviceState.getMyNodeNum() == 0) {
            deferMeshPacket(packet, receivedAtSeconds, "local node id is unknown");
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
        dispatchDecodedPacket(packet, data, receivedAtSeconds);
    }

    private void dispatchDecodedPacket(MeshProtos.MeshPacket packet,
                                       MeshProtos.Data data,
                                       long receivedAtSeconds) {
        if (data.getPortnum() == Portnums.PortNum.TEXT_MESSAGE_APP) {
            handleTextMessage(packet, data, receivedAtSeconds);
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

    private void handleTextMessage(MeshProtos.MeshPacket packet,
                                   MeshProtos.Data data,
                                   long receivedAtSeconds) {
        int from = packet.getFrom();
        int to = packet.getTo();
        boolean isDirect = to != 0xFFFFFFFF;
        if (!isDirect && !deviceState.isChannelCatalogReady()) {
            deferMeshPacket(packet, receivedAtSeconds, "channel catalog is not ready");
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

        processIncomingTextMessage(packet, data, isDirect, receivedAtSeconds);
    }

    private void processIncomingTextMessage(MeshProtos.MeshPacket packet,
                                            MeshProtos.Data data,
                                            boolean isDirect,
                                            long receivedAtSeconds) {
        int from = packet.getFrom();
        int to = packet.getTo();
        int channel = packet.getChannel();
        String text = data.getPayload().toString(StandardCharsets.UTF_8);
        long timestamp = receivedAtSeconds;

        NodeData fromNode = deviceState.getOrCreateNode(from);
        String fromNodeId = fromNode.getNodeId();
        String ignoredOwnerNodeId = deviceState.getOwnerNodeId();
        if (ignoredOwnerNodeId != null && IgnoredNodeService.getInstance().isIgnored(fromNodeId, ignoredOwnerNodeId)) {
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
            RemoteRpcHostService.getInstance().publishIncomingMessage(msg, "dm", fromNodeId);
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
            RemoteRpcHostService.getInstance().publishIncomingMessage(msg, "channel", String.valueOf(channel));
        }

        // Show the unread dot on the Chats icon.
        AppUi.setChatUnreadDot(true);
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

    private boolean deferMeshPacket(MeshProtos.MeshPacket packet, long receivedAtSeconds, String reason) {
        if (packet == null) {
            return false;
        }

        int queuedPackets;
        synchronized (deferredMeshLock) {
            deferredMeshPackets.add(new DeferredMeshPacket(
                    deviceState.getChannelCatalogEpoch(), packet, receivedAtSeconds));
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
            onMeshPacket(packet, queued.receivedAtSeconds());
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
        String chatType = channelMessage ? "channel" : "dm";
        String chatKey = channelMessage ? String.valueOf(packet.getChannel()) : fromNodeId;
        if (channelMessage) {
            MessageDbService.getInstance().saveReaction(
                    reaction, chatType, chatKey, ownerNodeId);
        } else {
            MessageDbService.getInstance().saveReaction(reaction, chatType, chatKey, ownerNodeId);
        }

        deviceState.fireMessageChange(MessageChangeEvent.reactionChanged(
                chatType,
                chatKey,
                ownerNodeId,
                data.getReplyId()));
        RemoteRpcHostService.getInstance().publishChatChanged(chatType, chatKey, data.getReplyId());
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
            var pendingEntry = deviceState.getMessageStore().getPendingAcks().get(requestId);
            MeshMessage pending = pendingEntry != null ? pendingEntry.message() : null;
            if (pending != null) {
                handlePendingMessageRoutingAck(packet, routing, requestId, pending);
                return;
            }
            boolean completedPacketAck = deviceState.completePendingPacketAck(requestId, routing.getErrorReason());
            MeshMessage.DeliveryStatus reactionStatus =
                    routing.getErrorReason() == MeshProtos.Routing.Error.NONE
                            ? MeshMessage.DeliveryStatus.DELIVERED
                            : MeshMessage.DeliveryStatus.FAILED;
            String reactionError = routing.getErrorReason() == MeshProtos.Routing.Error.NONE
                    ? null
                    : routing.getErrorReason().name();
            MessageDbService db = MessageDbService.getInstance();
            MessageDbService.ReactionScope reactionScope = db.findReactionScopeByPacketId(requestId);
            boolean updatedReaction = db.updateReactionStatus(requestId, reactionStatus, reactionError);

            if (pending == null && !completedPacketAck && !updatedReaction) {
                log.debug("No pending message or packet ACK waiter found for requestId={}", requestId);
                return;
            }

            if (pending == null) {
                if (updatedReaction) {
                    fireReactionChanged(reactionScope);
                    deviceState.fireMessageListeners();
                    log.debug("Routing ACK received for reaction packet {}", requestId);
                } else {
                    log.debug("Routing ACK received for non-message packet {}", requestId);
                }
                return;
            }
        } catch (Exception e) {
            log.warn("Failed to parse routing ACK for packet {}", requestId, e);
        }
    }

    private void handlePendingMessageRoutingAck(MeshProtos.MeshPacket packet,
                                                MeshProtos.Routing routing,
                                                int requestId,
                                                MeshMessage pending) {
        if (routing.getErrorReason() != MeshProtos.Routing.Error.NONE) {
            deviceState.resolvePendingAck(requestId);
            pending.setStatus(MeshMessage.DeliveryStatus.FAILED);
            pending.setErrorReason(routing.getErrorReason().name());
            MessageDbService.getInstance().updateStatus(requestId, pending.getStatus(), pending.getErrorReason());
            fireMessageStatusChanged(pending);
            deviceState.fireMessageListeners();
            log.warn("NAK received for packet {}: {}", requestId, routing.getErrorReason());
            return;
        }

        if (pending.isDirectMessage() && nodeIdMatchesNodeNum(pending.getToNodeId(), packet.getFrom())) {
            deviceState.resolvePendingAck(requestId);
            pending.setStatus(MeshMessage.DeliveryStatus.CONFIRMED);
            pending.setErrorReason(null);
            MessageDbService.getInstance().updateStatus(requestId, pending.getStatus(), null);
            fireMessageStatusChanged(pending);
            deviceState.fireMessageListeners();
            log.debug("Recipient ACK received for DM packet {}", requestId);
            return;
        }

        pending.setStatus(MeshMessage.DeliveryStatus.DELIVERED);
        pending.setErrorReason(null);
        MessageDbService.getInstance().updateStatus(requestId, pending.getStatus(), null);
        fireMessageStatusChanged(pending);
        deviceState.fireMessageListeners();
        if (pending.isDirectMessage()) {
            log.debug("Non-recipient ACK received for DM packet {} from !{}; waiting for ACK from {}",
                    requestId, Integer.toHexString(packet.getFrom()), pending.getToNodeId());
        } else {
            deviceState.resolvePendingAck(requestId);
            log.debug("ACK received for packet {}", requestId);
        }
    }

    private void failPendingMessageSend(int packetId, MeshMessage pending, String reason) {
        deviceState.resolvePendingAck(packetId);
        pending.setStatus(MeshMessage.DeliveryStatus.FAILED);
        pending.setErrorReason(reason != null ? reason : MeshProtos.Routing.Error.MAX_RETRANSMIT.name());
        MessageDbService.getInstance().updateStatus(packetId, pending.getStatus(), pending.getErrorReason());
        fireMessageStatusChanged(pending);
        deviceState.fireMessageListeners();
        log.warn("Local send failed for packet {}: {}", packetId, pending.getErrorReason());
    }

    private static boolean nodeIdMatchesNodeNum(String nodeId, int nodeNum) {
        if (nodeId == null || nodeId.length() != 9 || nodeId.charAt(0) != '!') {
            return false;
        }
        try {
            return (int) Long.parseUnsignedLong(nodeId.substring(1), 16) == nodeNum;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void fireMessageStatusChanged(MeshMessage message) {
        if (message == null) {
            return;
        }
        deviceState.fireMessageChange(MessageChangeEvent.statusChanged(
                chatType(message),
                chatKey(message),
                currentOwnerNodeId(),
                message));
    }

    private void fireReactionChanged(MessageDbService.ReactionScope reactionScope) {
        if (reactionScope == null) {
            return;
        }
        deviceState.fireMessageChange(MessageChangeEvent.reactionChanged(
                reactionScope.chatType(),
                reactionScope.chatKey(),
                reactionScope.ownerNodeId(),
                reactionScope.targetPacketId()));
        RemoteRpcHostService.getInstance().publishChatChanged(
                reactionScope.chatType(),
                reactionScope.chatKey(),
                reactionScope.targetPacketId());
    }

    private static String chatType(MeshMessage message) {
        return message.isDirectMessage() ? "dm" : "channel";
    }

    private static String chatKey(MeshMessage message) {
        if (!message.isDirectMessage()) {
            return String.valueOf(message.getChannelIndex());
        }
        return message.isOutgoing() ? message.getToNodeId() : message.getFromNodeId();
    }

    private String currentOwnerNodeId() {
        return String.format("!%08x", deviceState.getMyNodeNum());
    }

    private void handleNodeInfoResponse(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        int fromNum = packet.getFrom();
        try {
            MeshProtos.User user = MeshProtos.User.parseFrom(data.getPayload());
            NodeData node = deviceState.getOrCreateNode(fromNum);
            int rxTime = packet.getRxTime() > 0 ? packet.getRxTime() : (int)(System.currentTimeMillis() / 1000);
            node.setLastHeard(rxTime);
        // Protobuf returns "" for unset string fields; empty values must not erase existing data.
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
            node.setLicensed(user.getIsLicensed());
            if (user.hasIsUnmessagable()) {
                node.setUnmessagable(user.getIsUnmessagable());
            }
            deviceState.fireNodeUpdateListeners(fromNum);
            NodeCacheService.getInstance().update(node);
            maybePrepareDirectThreadFromNodeInfo(packet, node);
            log.info("Received NODEINFO_APP from !{}: {} (unmessagable={})",
                    Integer.toHexString(fromNum), user.getLongName(),
                    user.hasIsUnmessagable() ? user.getIsUnmessagable() : null);
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse User from NODEINFO_APP packet from !{}", Integer.toHexString(fromNum), e);
        }
    }

    private NodeData applyNodeInfo(MeshProtos.NodeInfo nodeInfo) {
        NodeData node = deviceState.getOrCreateNode(nodeInfo.getNum());

        if (nodeInfo.hasUser()) {
            applyUserInfo(node, nodeInfo.getUser());
        }

        if (nodeInfo.hasPosition()) {
            MeshProtos.Position position = nodeInfo.getPosition();
            if (position.getLatitudeI() != 0) {
                node.setLatitude(position.getLatitudeI() * 1e-7);
            }
            if (position.getLongitudeI() != 0) {
                node.setLongitude(position.getLongitudeI() * 1e-7);
            }
            if (position.getAltitude() != 0) {
                node.setAltitude(position.getAltitude());
            }
        }

        if (nodeInfo.getSnr() != 0) {
            node.setSnr(nodeInfo.getSnr());
        }
        if (nodeInfo.getLastHeard() != 0) {
            node.setLastHeard(nodeInfo.getLastHeard());
        } else {
            node.setLastHeard((int) (System.currentTimeMillis() / 1000));
        }
        if (nodeInfo.hasHopsAway()) {
            node.setHopsAway((int) nodeInfo.getHopsAway());
        }
        if (nodeInfo.getChannel() != 0) {
            node.setChannel((int) nodeInfo.getChannel());
        }

        if (nodeInfo.hasDeviceMetrics()) {
            org.meshtastic.proto.TelemetryProtos.DeviceMetrics metrics = nodeInfo.getDeviceMetrics();
            applyBatteryLevel(metrics.getBatteryLevel(), node, null);
            if (metrics.getVoltage() != 0) {
                node.setVoltage(metrics.getVoltage());
            }
            if (metrics.getChannelUtilization() != 0) {
                node.setChannelUtilization(metrics.getChannelUtilization());
            }
            if (metrics.getAirUtilTx() != 0) {
                node.setAirUtilTx(metrics.getAirUtilTx());
            }
            if (metrics.getUptimeSeconds() != 0) {
                node.setUptimeSeconds(metrics.getUptimeSeconds());
            }
        }

        return node;
    }

    private static void applyUserInfo(NodeData node, MeshProtos.User user) {
        if (!user.getLongName().isEmpty()) {
            node.setLongName(user.getLongName());
        }
        if (!user.getShortName().isEmpty()) {
            node.setShortName(user.getShortName());
        }
        if (!user.getId().isEmpty()) {
            node.setNodeId(user.getId());
        }
        if (user.getRole() != ConfigProtos.Config.DeviceConfig.Role.CLIENT || node.getRole() == null) {
            node.setRole(user.getRole().name());
        }
        if (user.getHwModel() != MeshProtos.HardwareModel.UNSET || node.getHwModel() == null) {
            node.setHwModel(user.getHwModel().name());
        }
        if (!user.getPublicKey().isEmpty()) {
            node.setPublicKey(user.getPublicKey().toByteArray());
        }
        node.setLicensed(user.getIsLicensed());
        if (user.hasIsUnmessagable()) {
            node.setUnmessagable(user.getIsUnmessagable());
        }
    }

    private void maybePrepareDirectThreadFromNodeInfo(MeshProtos.MeshPacket packet, NodeData node) {
        if (protocolHandler == null || node == null) { return; }
        if (packet.getFrom() == 0 || packet.getFrom() == deviceState.getMyNodeNum()) { return; }
        if (packet.getTo() != deviceState.getMyNodeNum()) { return; }

        String nodeId = node.getNodeId();
        if (nodeId == null || nodeId.isBlank()) { return; }

        deviceState.ensureDirectMessageThread(nodeId);
        log.debug("Prepared local direct message thread for {} after directed NODEINFO_APP", nodeId);
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
            // Zero coordinates mean missing data, so keep the existing position.
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
            TelemetryProtos.Telemetry telemetry = TelemetryProtos.Telemetry.parseFrom(data.getPayload());

            long ts = telemetry.getTime() > 0 ? telemetry.getTime()
                    : packet.getRxTime() > 0 ? packet.getRxTime() : System.currentTimeMillis() / 1000;

            NodeData node = deviceState.getOrCreateNode(fromNum);
            TelemetryEntry entry = new TelemetryEntry(ts, node.getNodeId());
            TelemetryProtos.Telemetry.VariantCase variantCase = telemetry.getVariantCase();
            entry.setTelemetryVariant(variantCase.name());
            if (!node.hasName()) {
                NodeCacheService.getInstance().enrichFromCache(node);
            }
            node.setLastHeard((int) ts);

            boolean nodeChanged = false;
            switch (variantCase) {
                case DEVICE_METRICS -> {
                    TelemetryProtos.DeviceMetrics dm = telemetry.getDeviceMetrics();
                    applyDeviceMetrics(dm, node, entry);
                    nodeChanged = true;
                    log.info("Received TELEMETRY_APP (device) from !{}", Integer.toHexString(fromNum));
                }
                case ENVIRONMENT_METRICS -> {
                    applyEnvironmentMetrics(telemetry.getEnvironmentMetrics(), node, entry);
                    nodeChanged = true;
                    log.info("Received TELEMETRY_APP (environment) from !{}", Integer.toHexString(fromNum));
                }
                case AIR_QUALITY_METRICS -> {
                    applyAirQualityMetrics(telemetry.getAirQualityMetrics(), entry);
                    log.info("Received TELEMETRY_APP (airQuality) from !{}", Integer.toHexString(fromNum));
                }
                case POWER_METRICS -> {
                    applyPowerMetrics(telemetry.getPowerMetrics(), entry);
                    log.info("Received TELEMETRY_APP (power) from !{}", Integer.toHexString(fromNum));
                }
                case LOCAL_STATS -> {
                    TelemetryProtos.LocalStats ls = telemetry.getLocalStats();
                    applyLocalStats(ls, node, entry);
                    nodeChanged = true;
                    log.info("Received TELEMETRY_APP (localStats) from !{}: rx={}, bad={}, dupe={}, tx={}, dropped={}, relay={}, relayCanceled={}, chUtil={}, airUtil={}",
                            Integer.toHexString(fromNum), ls.getNumPacketsRx(), ls.getNumPacketsRxBad(), ls.getNumRxDupe(),
                            ls.getNumPacketsTx(), ls.getNumTxDropped(), ls.getNumTxRelay(), ls.getNumTxRelayCanceled(),
                            ls.getChannelUtilization(), ls.getAirUtilTx());
                }
                case HEALTH_METRICS -> {
                    applyHealthMetrics(telemetry.getHealthMetrics(), entry);
                    log.info("Received TELEMETRY_APP (health) from !{}", Integer.toHexString(fromNum));
                }
                case HOST_METRICS -> {
                    applyHostMetrics(telemetry.getHostMetrics(), entry);
                    log.info("Received TELEMETRY_APP (host) from !{}", Integer.toHexString(fromNum));
                }
                case TRAFFIC_MANAGEMENT_STATS -> {
                    applyTrafficManagementStats(telemetry.getTrafficManagementStats(), entry);
                    log.info("Received TELEMETRY_APP (trafficManagement) from !{}", Integer.toHexString(fromNum));
                }
                case VARIANT_NOT_SET -> log.debug("Received TELEMETRY_APP without variant from !{}", Integer.toHexString(fromNum));
            }

            if (nodeChanged) {
                deviceState.fireNodeUpdateListeners(fromNum);
                NodeCacheService.getInstance().update(node);
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

    private static void applyDeviceMetrics(TelemetryProtos.DeviceMetrics dm, NodeData node, TelemetryEntry entry) {
        if (dm.hasBatteryLevel()) {
            applyBatteryLevel(dm.getBatteryLevel(), node, entry);
        }
        if (dm.hasVoltage()) {
            node.setVoltage(dm.getVoltage());
            entry.setVoltage(dm.getVoltage());
        }
        if (dm.hasChannelUtilization()) {
            node.setChannelUtilization(dm.getChannelUtilization());
            entry.setChannelUtilization(dm.getChannelUtilization());
        }
        if (dm.hasAirUtilTx()) {
            node.setAirUtilTx(dm.getAirUtilTx());
            entry.setAirUtilTx(dm.getAirUtilTx());
        }
        if (dm.hasUptimeSeconds()) {
            node.setUptimeSeconds(dm.getUptimeSeconds());
            entry.setDeviceUptimeSeconds(unsignedInt(dm.getUptimeSeconds()));
        }
    }

    private static void applyEnvironmentMetrics(TelemetryProtos.EnvironmentMetrics em, NodeData node, TelemetryEntry entry) {
        if (em.hasTemperature()) {
            entry.setTemperature(em.getTemperature());
            if (em.getTemperature() != 0) { node.setTemperature(em.getTemperature()); }
        }
        if (em.hasRelativeHumidity()) {
            entry.setRelativeHumidity(em.getRelativeHumidity());
            if (em.getRelativeHumidity() != 0) { node.setRelativeHumidity(em.getRelativeHumidity()); }
        }
        if (em.hasBarometricPressure()) {
            entry.setBarometricPressure(em.getBarometricPressure());
            if (em.getBarometricPressure() != 0) { node.setBarometricPressure(em.getBarometricPressure()); }
        }
        if (em.hasGasResistance()) { entry.setGasResistance(em.getGasResistance()); }
        if (em.hasVoltage()) { entry.setEnvironmentVoltage(em.getVoltage()); }
        if (em.hasCurrent()) { entry.setEnvironmentCurrent(em.getCurrent()); }
        if (em.hasIaq()) { entry.setIaq(unsignedInt(em.getIaq())); }
        if (em.hasDistance()) { entry.setDistance(em.getDistance()); }
        if (em.hasLux()) { entry.setLux(em.getLux()); }
        if (em.hasWhiteLux()) { entry.setWhiteLux(em.getWhiteLux()); }
        if (em.hasIrLux()) { entry.setIrLux(em.getIrLux()); }
        if (em.hasUvLux()) { entry.setUvLux(em.getUvLux()); }
        if (em.hasWindDirection()) { entry.setWindDirection(unsignedInt(em.getWindDirection())); }
        if (em.hasWindSpeed()) { entry.setWindSpeed(em.getWindSpeed()); }
        if (em.hasWeight()) { entry.setWeight(em.getWeight()); }
        if (em.hasWindGust()) { entry.setWindGust(em.getWindGust()); }
        if (em.hasWindLull()) { entry.setWindLull(em.getWindLull()); }
        if (em.hasRadiation()) { entry.setRadiation(em.getRadiation()); }
        if (em.hasRainfall1H()) { entry.setRainfall1h(em.getRainfall1H()); }
        if (em.hasRainfall24H()) { entry.setRainfall24h(em.getRainfall24H()); }
        if (em.hasSoilMoisture()) { entry.setSoilMoisture(unsignedInt(em.getSoilMoisture())); }
        if (em.hasSoilTemperature()) { entry.setSoilTemperature(em.getSoilTemperature()); }
        for (Float oneWireTemperature : em.getOneWireTemperatureList()) {
            entry.addOneWireTemperature(oneWireTemperature);
        }
    }

    private static void applyAirQualityMetrics(TelemetryProtos.AirQualityMetrics aq, TelemetryEntry entry) {
        if (aq.hasPm10Standard()) { entry.setPm10Standard(unsignedInt(aq.getPm10Standard())); }
        if (aq.hasPm25Standard()) { entry.setPm25Standard(unsignedInt(aq.getPm25Standard())); }
        if (aq.hasPm100Standard()) { entry.setPm100Standard(unsignedInt(aq.getPm100Standard())); }
        if (aq.hasPm10Environmental()) { entry.setPm10Environmental(unsignedInt(aq.getPm10Environmental())); }
        if (aq.hasPm25Environmental()) { entry.setPm25Environmental(unsignedInt(aq.getPm25Environmental())); }
        if (aq.hasPm100Environmental()) { entry.setPm100Environmental(unsignedInt(aq.getPm100Environmental())); }
        if (aq.hasParticles03Um()) { entry.setParticles03um(unsignedInt(aq.getParticles03Um())); }
        if (aq.hasParticles05Um()) { entry.setParticles05um(unsignedInt(aq.getParticles05Um())); }
        if (aq.hasParticles10Um()) { entry.setParticles10um(unsignedInt(aq.getParticles10Um())); }
        if (aq.hasParticles25Um()) { entry.setParticles25um(unsignedInt(aq.getParticles25Um())); }
        if (aq.hasParticles50Um()) { entry.setParticles50um(unsignedInt(aq.getParticles50Um())); }
        if (aq.hasParticles100Um()) { entry.setParticles100um(unsignedInt(aq.getParticles100Um())); }
        if (aq.hasCo2()) { entry.setCo2(unsignedInt(aq.getCo2())); }
        if (aq.hasCo2Temperature()) { entry.setCo2Temperature(aq.getCo2Temperature()); }
        if (aq.hasCo2Humidity()) { entry.setCo2Humidity(aq.getCo2Humidity()); }
        if (aq.hasFormFormaldehyde()) { entry.setFormFormaldehyde(aq.getFormFormaldehyde()); }
        if (aq.hasFormHumidity()) { entry.setFormHumidity(aq.getFormHumidity()); }
        if (aq.hasFormTemperature()) { entry.setFormTemperature(aq.getFormTemperature()); }
        if (aq.hasPm40Standard()) { entry.setPm40Standard(unsignedInt(aq.getPm40Standard())); }
        if (aq.hasParticles40Um()) { entry.setParticles40um(unsignedInt(aq.getParticles40Um())); }
        if (aq.hasPmTemperature()) { entry.setPmTemperature(aq.getPmTemperature()); }
        if (aq.hasPmHumidity()) { entry.setPmHumidity(aq.getPmHumidity()); }
        if (aq.hasPmVocIdx()) { entry.setPmVocIdx(aq.getPmVocIdx()); }
        if (aq.hasPmNoxIdx()) { entry.setPmNoxIdx(aq.getPmNoxIdx()); }
        if (aq.hasParticlesTps()) { entry.setParticlesTps(aq.getParticlesTps()); }
    }

    private static void applyPowerMetrics(TelemetryProtos.PowerMetrics pm, TelemetryEntry entry) {
        if (pm.hasCh1Voltage()) { entry.setCh1Voltage(pm.getCh1Voltage()); }
        if (pm.hasCh1Current()) { entry.setCh1Current(pm.getCh1Current()); }
        if (pm.hasCh2Voltage()) { entry.setCh2Voltage(pm.getCh2Voltage()); }
        if (pm.hasCh2Current()) { entry.setCh2Current(pm.getCh2Current()); }
        if (pm.hasCh3Voltage()) { entry.setCh3Voltage(pm.getCh3Voltage()); }
        if (pm.hasCh3Current()) { entry.setCh3Current(pm.getCh3Current()); }
        if (pm.hasCh4Voltage()) { entry.setCh4Voltage(pm.getCh4Voltage()); }
        if (pm.hasCh4Current()) { entry.setCh4Current(pm.getCh4Current()); }
        if (pm.hasCh5Voltage()) { entry.setCh5Voltage(pm.getCh5Voltage()); }
        if (pm.hasCh5Current()) { entry.setCh5Current(pm.getCh5Current()); }
        if (pm.hasCh6Voltage()) { entry.setCh6Voltage(pm.getCh6Voltage()); }
        if (pm.hasCh6Current()) { entry.setCh6Current(pm.getCh6Current()); }
        if (pm.hasCh7Voltage()) { entry.setCh7Voltage(pm.getCh7Voltage()); }
        if (pm.hasCh7Current()) { entry.setCh7Current(pm.getCh7Current()); }
        if (pm.hasCh8Voltage()) { entry.setCh8Voltage(pm.getCh8Voltage()); }
        if (pm.hasCh8Current()) { entry.setCh8Current(pm.getCh8Current()); }
    }

    private static void applyLocalStats(TelemetryProtos.LocalStats ls, NodeData node, TelemetryEntry entry) {
        entry.setLocalUptimeSeconds(unsignedInt(ls.getUptimeSeconds()));
        entry.setChannelUtilization(ls.getChannelUtilization());
        entry.setAirUtilTx(ls.getAirUtilTx());
        entry.setNumPacketsTx(ls.getNumPacketsTx());
        entry.setNumPacketsRx(ls.getNumPacketsRx());
        entry.setNumPacketsRxBad(ls.getNumPacketsRxBad());
        entry.setNumOnlineNodes(unsignedInt(ls.getNumOnlineNodes()));
        entry.setNumTotalNodes(unsignedInt(ls.getNumTotalNodes()));
        entry.setNumRxDupe(ls.getNumRxDupe());
        entry.setNumTxRelay(ls.getNumTxRelay());
        entry.setNumTxRelayCanceled(ls.getNumTxRelayCanceled());
        entry.setHeapTotalBytes(unsignedInt(ls.getHeapTotalBytes()));
        entry.setHeapFreeBytes(unsignedInt(ls.getHeapFreeBytes()));
        entry.setNumTxDropped(ls.getNumTxDropped());
        entry.setNoiseFloor(ls.getNoiseFloor());
        node.setChannelUtilization(ls.getChannelUtilization());
        node.setAirUtilTx(ls.getAirUtilTx());
    }

    private static void applyHealthMetrics(TelemetryProtos.HealthMetrics hm, TelemetryEntry entry) {
        if (hm.hasHeartBpm()) { entry.setHealthHeartBpm(unsignedInt(hm.getHeartBpm())); }
        if (hm.hasSpO2()) { entry.setHealthSpO2(unsignedInt(hm.getSpO2())); }
        if (hm.hasTemperature()) { entry.setHealthTemperature(hm.getTemperature()); }
    }

    private static void applyHostMetrics(TelemetryProtos.HostMetrics hm, TelemetryEntry entry) {
        entry.setHostUptimeSeconds(unsignedInt(hm.getUptimeSeconds()));
        entry.setHostFreememBytes(hm.getFreememBytes());
        entry.setHostDiskfree1Bytes(hm.getDiskfree1Bytes());
        if (hm.hasDiskfree2Bytes()) { entry.setHostDiskfree2Bytes(hm.getDiskfree2Bytes()); }
        if (hm.hasDiskfree3Bytes()) { entry.setHostDiskfree3Bytes(hm.getDiskfree3Bytes()); }
        entry.setHostLoad1(unsignedInt(hm.getLoad1()));
        entry.setHostLoad5(unsignedInt(hm.getLoad5()));
        entry.setHostLoad15(unsignedInt(hm.getLoad15()));
        if (hm.hasUserString()) { entry.setHostUserString(hm.getUserString()); }
    }

    private static void applyTrafficManagementStats(TelemetryProtos.TrafficManagementStats stats, TelemetryEntry entry) {
        entry.setTrafficPacketsInspected(unsignedInt(stats.getPacketsInspected()));
        entry.setTrafficPositionDedupDrops(unsignedInt(stats.getPositionDedupDrops()));
        entry.setTrafficNodeinfoCacheHits(unsignedInt(stats.getNodeinfoCacheHits()));
        entry.setTrafficRateLimitDrops(unsignedInt(stats.getRateLimitDrops()));
        entry.setTrafficUnknownPacketDrops(unsignedInt(stats.getUnknownPacketDrops()));
        entry.setTrafficHopExhaustedPackets(unsignedInt(stats.getHopExhaustedPackets()));
        entry.setTrafficRouterHopsPreserved(unsignedInt(stats.getRouterHopsPreserved()));
    }

    private static long unsignedInt(int value) {
        return Integer.toUnsignedLong(value);
    }

    private static void applyBatteryLevel(int rawBatteryLevel, NodeData node, TelemetryEntry entry) {
        if (rawBatteryLevel > 100) {
            node.setExternallyPowered(true);
            if (entry != null) { entry.setExternallyPowered(true); }
        } else if (rawBatteryLevel > 0) {
            node.setBatteryLevel(rawBatteryLevel);
            node.setExternallyPowered(false);
            if (entry != null) { entry.setBatteryLevel(rawBatteryLevel); }
        }
    }

    @Override
    public void onDeviceMetadata(MeshProtos.DeviceMetadata metadata) {
        deviceState.setDeviceMetadata(metadata);
        deviceState.fireDeviceMetadataListeners();
        log.debug("Received device metadata: firmwareVersion='{}', role={}, excludedModules={}",
                metadata.getFirmwareVersion(), metadata.getRole(), metadata.getExcludedModules());
    }

    @SuppressWarnings("PMD.UnusedFormalParameter") // consistent handler signature
    private void handleAdminResponse(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        if (packet.getFrom() != 0
                && deviceState.getMyNodeNum() != 0
                && packet.getFrom() != deviceState.getMyNodeNum()) {
            log.debug("Ignoring remote ADMIN_APP response from !{} in local message listener",
                    Integer.toHexString(packet.getFrom()));
            return;
        }
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
            } else if (adminMsg.hasGetRingtoneResponse()) {
                deviceState.setRingtone(adminMsg.getGetRingtoneResponse());
                deviceState.fireRingtoneListeners();
                log.debug("Received ringtone response ({} chars)", adminMsg.getGetRingtoneResponse().length());
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
        node.setLicensed(owner.getIsLicensed());
        if (owner.hasIsUnmessagable()) {
            node.setUnmessagable(owner.getIsUnmessagable());
        }
        deviceState.fireNodeUpdateListeners(myNodeNum);
        NodeCacheService.getInstance().update(node);
    }

    private void handleTracerouteResponse(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        // Ignore our outbound request echoed by the radio; only process the response.
        int myNodeNum = deviceState.getMyNodeNum();
        if (packet.getFrom() == myNodeNum) {
            log.debug("Ignoring outgoing TRACEROUTE_APP echo from self");
            return;
        }
        try {
            MeshProtos.RouteDiscovery route = MeshProtos.RouteDiscovery.parseFrom(data.getPayload());
            if (data.getRequestId() == 0) {
                if (packet.getTo() != myNodeNum) {
                    log.debug("Ignoring TRACEROUTE_APP route data without requestId addressed to !{}",
                            Integer.toHexString(packet.getTo()));
                    return;
                }
                if (!hasRouteDiscoveryData(route)) {
                    log.debug("Accepting empty TRACEROUTE_APP response from !{} addressed to local node",
                            Integer.toHexString(packet.getFrom()));
                }
            }
            deviceState.fireTracerouteListeners(packet.getFrom(), route);
            log.info("Received TRACEROUTE_APP response from !{}: route={}, route_back={}",
                    Integer.toHexString(packet.getFrom()), route.getRouteList(), route.getRouteBackList());
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse RouteDiscovery from TRACEROUTE_APP packet", e);
        }
    }

    private static boolean hasRouteDiscoveryData(MeshProtos.RouteDiscovery route) {
        return route.getRouteCount() > 0
                || route.getSnrTowardsCount() > 0
                || route.getRouteBackCount() > 0
                || route.getSnrBackCount() > 0;
    }
}
