package com.meshtastic.client.service;

import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.meshtastic.proto.Portnums;
import com.google.protobuf.ByteString;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.utils.ConfigDebugFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service for sending messages and admin commands to a Meshtastic device.
 * <p>
 * Stateless utility class that builds protobuf {@code ToRadio} packets for:
 * <ul>
 *   <li>text messages, both channel and direct</li>
 *   <li>node information requests (NODEINFO_APP)</li>
 *   <li>traceroute requests (TRACEROUTE_APP)</li>
 *   <li>admin operations: owner info, channels, and configuration</li>
 * </ul>
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private static final String BROADCAST_NODE_ID = "!ffffffff";
    private static final long OWNER_INFO_EXCHANGE_WAIT_MS = 2_500;
    private static final long PKI_PREP_ACK_WAIT_MS = 10_000;

    private MessageService() {}

    /**
     * Sends a text message to a channel as broadcast, {@code to=0xFFFFFFFF}.
     * Creates a {@link MeshMessage}, saves it to the database, adds it to
     * {@link DeviceState}, and registers it for ACK tracking.
     *
     * @param handler      protocol handler used for sending
     * @param state        device state
     * @param channelIndex channel index; 0 is Primary
     * @param text         message text
     * @param replyId      packetId of the quoted message; 0 means no quote
     * @return created message in {@code SENDING} status
     */
    public static MeshMessage sendChannelMessage(ProtocolHandler handler, DeviceState state, int channelIndex, String text, int replyId) {
        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        long now = System.currentTimeMillis() / 1000;

        MeshProtos.Data.Builder dataBuilder = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                .setPayload(ByteString.copyFrom(text, StandardCharsets.UTF_8));
        if (replyId != 0) { dataBuilder.setReplyId(replyId); }
        MeshProtos.Data data = dataBuilder.build();

        if (replyId != 0) {
            log.info("REPLY_DEBUG send channel: replyId={} (0x{}), payloadBytes={}",
                    replyId, Integer.toHexString(replyId), data.getPayload().size());
        }

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(0xFFFFFFFF)
                .setChannel(channelIndex)
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .build();

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(packet)
                .build();

        handler.sendToRadio(toRadio);

        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        String myNodeId = myNode != null ? myNode.getNodeId() : null;

        MeshMessage msg = new MeshMessage(myNodeId, "!ffffffff", channelIndex, text, now, true);
        msg.setStatus(MeshMessage.DeliveryStatus.SENDING);
        msg.setPacketId(packetId);
        if (replyId != 0) {
            msg.setReplyId(replyId);
        }

        if (myNode != null && myNode.getLongName() != null) {
            msg.setSenderName(myNode.getLongName());
        }

        String ownerNodeId = String.format("!%08x", state.getMyNodeNum());
        String chatType = "channel";
        String chatKey = String.valueOf(channelIndex);
        hydrateReplyText(state, msg, ownerNodeId, chatType, chatKey);
        MessageDbService.getInstance().save(msg, chatType, chatKey, ownerNodeId);
        state.addMessage(msg);
        state.registerPendingAck(packetId, msg);
        return msg;
    }

    /**
     * Sends a direct text message to a specific node.
     * Creates a {@link MeshMessage}, saves it to the database, adds it to
     * {@link DeviceState}, and registers it for ACK tracking.
     *
     * @param handler      protocol handler used for sending
     * @param state        device state
     * @param peerNodeId   recipient node_id, for example {@code "!9e755af0"}
     * @param text         message text
     * @param replyId      packetId of the quoted message; 0 means no quote
     * @return created message in {@code SENDING} status
     */
    public static MeshMessage sendDirectMessage(ProtocolHandler handler, DeviceState state, String peerNodeId, String text, int replyId) {
        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        long now = System.currentTimeMillis() / 1000;

        NodeData peerNode = resolvePeerNode(state, peerNodeId);
        if (peerNode == null) {
            log.warn("Cannot send DM: failed to resolve peer '{}' to nodeNum", peerNodeId);
            return null;
        }
        if (peerNode.isUnmessagable()) {
            log.warn("Cannot send DM to '{}': peer declared is_unmessagable", peerNodeId);
            return null;
        }
        int peerNodeNum = peerNode.getNodeNum();

        MeshProtos.Data.Builder dataBuilder = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                .setPayload(ByteString.copyFrom(text, StandardCharsets.UTF_8));
        if (replyId != 0) { dataBuilder.setReplyId(replyId); }
        MeshProtos.Data data = dataBuilder.build();

        if (replyId != 0) {
            log.info("REPLY_DEBUG send DM: replyId={} (0x{}), payloadBytes={}",
                    replyId, Integer.toHexString(replyId), data.getPayload().size());
        }

        boolean usePkiTransport = shouldUsePkiDirectMessage(state, peerNodeId, peerNode);
        int directChannel = usePkiTransport ? 0 : resolveDirectMessageChannel(state, peerNodeId, peerNode);

        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        String myNodeId = myNode != null ? myNode.getNodeId() : null;

        MeshMessage msg = new MeshMessage(myNodeId, peerNodeId, directChannel, text, now, true);
        msg.setStatus(MeshMessage.DeliveryStatus.SENDING);
        msg.setPacketId(packetId);
        if (replyId != 0) {
            msg.setReplyId(replyId);
        }

        if (myNode != null && myNode.getLongName() != null) {
            msg.setSenderName(myNode.getLongName());
        }

        String ownerNodeId = String.format("!%08x", state.getMyNodeNum());
        String chatType = "dm";
        String chatKey = peerNodeId;
        hydrateReplyText(state, msg, ownerNodeId, chatType, chatKey);
        MessageDbService.getInstance().save(msg, chatType, chatKey, ownerNodeId);
        state.addDirectMessage(msg, peerNodeId);
        if (usePkiTransport) {
            preparePeerForPkiDirectMessage(handler, state, peerNode)
                    .completeOnTimeout(MeshProtos.Routing.Error.TIMEOUT, PKI_PREP_ACK_WAIT_MS, TimeUnit.MILLISECONDS)
                    .whenComplete((routingError, throwable) -> {
                        if (throwable != null) {
                            log.debug("PKI preparation for {} failed before DM send", peerNodeId, throwable);
                        } else if (routingError == MeshProtos.Routing.Error.TIMEOUT) {
                            log.debug("PKI preparation for {} timed out after {} ms, sending DM anyway",
                                    peerNodeId, PKI_PREP_ACK_WAIT_MS);
                        } else if (routingError != null && routingError != MeshProtos.Routing.Error.NONE) {
                            log.debug("PKI preparation for {} completed with {}, sending DM anyway",
                                    peerNodeId, routingError);
                        } else {
                            log.debug("PKI preparation for {} acknowledged, sending DM", peerNodeId);
                        }
                        dispatchDirectMessagePacket(handler, state, peerNodeId, peerNode, peerNodeNum, data,
                                packetId, directChannel, true, msg);
                    });
        } else {
            dispatchDirectMessagePacket(handler, state, peerNodeId, peerNode, peerNodeNum, data,
                    packetId, directChannel, false, msg);
        }
        return msg;
    }

    private static void hydrateReplyText(DeviceState state,
                                         MeshMessage msg,
                                         String ownerNodeId,
                                         String chatType,
                                         String chatKey) {
        if (msg.getReplyId() == 0 || msg.getReplyText() != null) {
            return;
        }

        MeshMessage original = MessageDbService.getInstance()
                .findByPacketId(msg.getReplyId(), chatType, chatKey, ownerNodeId);
        if (original == null) {
            original = findInMemoryReplyTarget(state, msg.getReplyId(), chatType, chatKey);
        }
        if (original != null) {
            msg.setReplyText(original.getText());
        }
    }

    private static MeshMessage findInMemoryReplyTarget(DeviceState state,
                                                       int replyId,
                                                       String chatType,
                                                       String chatKey) {
        MeshMessage original = state.findRuntimeMessageByPacketId(replyId);
        if (original == null) {
            return null;
        }
        return isMessageInChatScope(original, chatType, chatKey) ? original : null;
    }

    private static boolean isMessageInChatScope(MeshMessage message, String chatType, String chatKey) {
        if ("channel".equals(chatType)) {
            return BROADCAST_NODE_ID.equalsIgnoreCase(message.getToNodeId())
                    && String.valueOf(message.getChannelIndex()).equals(chatKey);
        }
        if ("dm".equals(chatType)) {
            return chatKey != null
                    && (chatKey.equalsIgnoreCase(message.getFromNodeId())
                    || chatKey.equalsIgnoreCase(message.getToNodeId()));
        }
        return false;
    }

    /**
     * Resends a previously undelivered outgoing message without creating a new history row.
     *
     * @return {@code true} if a new send attempt was started
     */
    public static boolean retryMessage(ProtocolHandler handler, DeviceState state, MeshMessage message) {
        if (handler == null || state == null || message == null || !message.isOutgoing()) {
            return false;
        }
        if (message.getStatus() != MeshMessage.DeliveryStatus.FAILED) {
            return false;
        }

        return isChannelMessage(message)
                ? retryChannelMessage(handler, state, message)
                : retryDirectMessage(handler, state, message);
    }

    /**
     * Sends an emoji reaction to a channel message.
     * Reactions are stored separately from regular messages and do not affect chat previews.
     */
    public static boolean sendChannelReaction(ProtocolHandler handler,
                                              DeviceState state,
                                              int channelIndex,
                                              MeshMessage targetMessage,
                                              String emoji) {
        if (targetMessage == null || targetMessage.getPacketId() == 0 || emoji == null || emoji.isEmpty()) {
            return false;
        }

        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        long now = System.currentTimeMillis() / 1000;

        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                .setPayload(ByteString.copyFrom(emoji, StandardCharsets.UTF_8))
                .setReplyId(targetMessage.getPacketId())
                .setEmoji(1)
                .build();

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(0xFFFFFFFF)
                .setChannel(channelIndex)
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .build();

        handler.sendToRadio(MeshProtos.ToRadio.newBuilder().setPacket(packet).build());
        return saveOutgoingReaction(state, "channel", String.valueOf(channelIndex), targetMessage, emoji, now, packetId);
    }

    /**
     * Sends an emoji reaction to a direct message.
     */
    public static boolean sendDirectReaction(ProtocolHandler handler,
                                             DeviceState state,
                                             String peerNodeId,
                                             MeshMessage targetMessage,
                                             String emoji) {
        if (targetMessage == null || targetMessage.getPacketId() == 0 || emoji == null || emoji.isEmpty()) {
            return false;
        }

        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        long now = System.currentTimeMillis() / 1000;

        NodeData peerNode = resolvePeerNode(state, peerNodeId);
        if (peerNode == null) {
            log.warn("Cannot send DM reaction: failed to resolve peer '{}' to nodeNum", peerNodeId);
            return false;
        }
        if (peerNode.isUnmessagable()) {
            log.warn("Cannot send DM reaction to '{}': peer declared is_unmessagable", peerNodeId);
            return false;
        }
        int peerNodeNum = peerNode.getNodeNum();

        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                .setPayload(ByteString.copyFrom(emoji, StandardCharsets.UTF_8))
                .setReplyId(targetMessage.getPacketId())
                .setEmoji(1)
                .build();

        boolean usePkiTransport = shouldUsePkiDirectMessage(state, peerNodeId, peerNode);
        int directChannel = usePkiTransport ? 0 : resolveDirectMessageChannel(state, peerNodeId, peerNode);
        if (usePkiTransport) {
            preparePeerForPkiDirectMessage(handler, state, peerNode);
        }

        MeshProtos.MeshPacket.Builder packetBuilder = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(peerNodeNum)
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true);
        if (usePkiTransport) {
            packetBuilder.setPkiEncrypted(true);
        } else if (directChannel > 0) {
            packetBuilder.setChannel(directChannel);
        }
        MeshProtos.MeshPacket packet = packetBuilder.build();

        handler.sendToRadio(MeshProtos.ToRadio.newBuilder().setPacket(packet).build());
        return saveOutgoingReaction(state, "dm", peerNodeId, targetMessage, emoji, now, packetId);
    }

    private static boolean retryChannelMessage(ProtocolHandler handler,
                                               DeviceState state,
                                               MeshMessage message) {
        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        MeshProtos.Data data = buildTextMessageData(message.getText(), message.getReplyId());
        logRetryReplyDebug("channel", message.getReplyId(), data);
        if (!prepareMessageForRetry(message, packetId)) {
            return false;
        }

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(0xFFFFFFFF)
                .setChannel(message.getChannelIndex())
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .build();

        handler.sendToRadio(MeshProtos.ToRadio.newBuilder().setPacket(packet).build());
        state.registerPendingAck(packetId, message);
        return true;
    }

    private static boolean retryDirectMessage(ProtocolHandler handler,
                                              DeviceState state,
                                              MeshMessage message) {
        String peerNodeId = message.getToNodeId();
        NodeData peerNode = resolvePeerNode(state, peerNodeId);
        if (peerNode == null) {
            log.warn("Cannot retry DM: failed to resolve peer '{}' to nodeNum", peerNodeId);
            return false;
        }
        if (peerNode.isUnmessagable()) {
            log.warn("Cannot retry DM to '{}': peer declared is_unmessagable", peerNodeId);
            return false;
        }

        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        int peerNodeNum = peerNode.getNodeNum();
        MeshProtos.Data data = buildTextMessageData(message.getText(), message.getReplyId());
        logRetryReplyDebug("DM", message.getReplyId(), data);

        boolean usePkiTransport = shouldUsePkiDirectMessage(state, peerNodeId, peerNode);
        int directChannel = usePkiTransport ? 0 : resolveDirectMessageChannel(state, peerNodeId, peerNode);
        if (!prepareMessageForRetry(message, packetId)) {
            return false;
        }

        if (usePkiTransport) {
            preparePeerForPkiDirectMessage(handler, state, peerNode)
                    .completeOnTimeout(MeshProtos.Routing.Error.TIMEOUT, PKI_PREP_ACK_WAIT_MS, TimeUnit.MILLISECONDS)
                    .whenComplete((routingError, throwable) -> {
                        if (throwable != null) {
                            log.debug("PKI preparation for {} failed before DM retry", peerNodeId, throwable);
                        } else if (routingError == MeshProtos.Routing.Error.TIMEOUT) {
                            log.debug("PKI preparation for {} timed out after {} ms, retrying DM anyway",
                                    peerNodeId, PKI_PREP_ACK_WAIT_MS);
                        } else if (routingError != null && routingError != MeshProtos.Routing.Error.NONE) {
                            log.debug("PKI preparation for {} completed with {}, retrying DM anyway",
                                    peerNodeId, routingError);
                        } else {
                            log.debug("PKI preparation for {} acknowledged, retrying DM", peerNodeId);
                        }
                        dispatchDirectMessagePacket(handler, state, peerNodeId, peerNode, peerNodeNum, data,
                                packetId, directChannel, true, message);
                    });
        } else {
            dispatchDirectMessagePacket(handler, state, peerNodeId, peerNode, peerNodeNum, data,
                    packetId, directChannel, false, message);
        }
        return true;
    }

    /**
     * Requests node information by sending a NODEINFO_APP packet with want_response=true.
     * The remote node responds with NODEINFO_APP containing User data.
     */
    public static void requestNodeInfo(ProtocolHandler handler, DeviceState state, int targetNodeNum) {
        sendNodeInfoPacket(handler, state, targetNodeNum, null, true);
    }

    /**
     * Exchanges user information with the selected node: first sends our local
     * User data to it, then requests a fresh NODEINFO_APP response from it.
     */
    public static void exchangeNodeUserInfo(ProtocolHandler handler, DeviceState state, int targetNodeNum) {
        if (handler == null || state == null || targetNodeNum == 0) { return; }
        if (targetNodeNum == state.getMyNodeNum()) {
            requestNodeInfo(handler, state, targetNodeNum);
            return;
        }

        MeshProtos.User localUser = buildLocalUserInfo(state);
        if (shouldRefreshOwnerInfoBeforeExchange(state, localUser)) {
            requestOwnerInfoThenExchange(handler, state, targetNodeNum);
            return;
        }

        continueNodeUserInfoExchange(handler, state, targetNodeNum, localUser);
    }

    /**
     * Requests a traceroute to a node by sending TRACEROUTE_APP with want_response=true.
     * The response contains RouteDiscovery with the route and per-hop SNR.
     */
    public static void requestTraceroute(ProtocolHandler handler, DeviceState state, int targetNodeNum) {
        MeshProtos.RouteDiscovery emptyRoute = MeshProtos.RouteDiscovery.newBuilder().build();

        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.TRACEROUTE_APP)
                .setWantResponse(true)
                .setDest(targetNodeNum)
                .setPayload(emptyRoute.toByteString())
                .build();

        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(targetNodeNum)
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .build();

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(packet)
                .build();

        handler.sendToRadio(toRadio);
    }

    /**
     * Requests owner info (User) and session_passkey from the connected radio.
     * The response arrives as AdminMessage.get_owner_response through ADMIN_APP.
     */
    public static void requestOwnerInfo(ProtocolHandler handler, DeviceState state) {
        AdminProtos.AdminMessage adminMsg = AdminProtos.AdminMessage.newBuilder()
                .setGetOwnerRequest(true)
                .build();
        sendAdminMessage(handler, state, adminMsg, true);
    }

    /**
     * Requests only {@code session_passkey} from the connected radio.
     * <p>
     * Uses {@code get_config_request = SESSIONKEY_CONFIG} because some
     * devices/firmware do not answer {@code get_owner_request}, but still return
     * the session key correctly with the config response.
     */
    public static void requestSessionPasskey(ProtocolHandler handler, DeviceState state) {
        AdminProtos.AdminMessage adminMsg = AdminProtos.AdminMessage.newBuilder()
                .setGetConfigRequest(AdminProtos.AdminMessage.ConfigType.SESSIONKEY_CONFIG)
                .build();
        sendAdminMessage(handler, state, adminMsg, true);
    }

    /**
     * Requests device metadata, including firmware version, from the connected radio.
     * The response arrives as AdminMessage.get_device_metadata_response through ADMIN_APP.
     */
    public static CompletableFuture<MeshProtos.Routing.Error> requestDeviceMetadata(ProtocolHandler handler,
                                                                                    DeviceState state) {
        AdminProtos.AdminMessage adminMsg = AdminProtos.AdminMessage.newBuilder()
                .setGetDeviceMetadataRequest(true)
                .build();
        return sendAdminMessage(handler, state, adminMsg, true);
    }

    /**
     * Requests the RTTTL ringtone used by External Notification.
     * The response arrives as AdminMessage.get_ringtone_response through ADMIN_APP.
     */
    public static CompletableFuture<MeshProtos.Routing.Error> requestRingtone(ProtocolHandler handler,
                                                                              DeviceState state) {
        AdminProtos.AdminMessage adminMsg = AdminProtos.AdminMessage.newBuilder()
                .setGetRingtoneRequest(true)
                .build();
        return sendAdminMessage(handler, state, adminMsg, true);
    }

    /**
     * Proxies an MQTT payload from the desktop/phone client to the device.
     * Payload is always sent as bytes so binary data is not lost.
     */
    public static void sendMqttClientProxyMessage(ProtocolHandler handler,
                                                  String topic,
                                                  byte[] payload,
                                                  boolean retained) {
        if (handler == null || topic == null || topic.isBlank() || payload == null) {
            return;
        }

        MeshProtos.MqttClientProxyMessage proxyMessage = MeshProtos.MqttClientProxyMessage.newBuilder()
                .setTopic(topic)
                .setData(ByteString.copyFrom(payload))
                .setRetained(retained)
                .build();

        handler.sendToRadio(MeshProtos.ToRadio.newBuilder()
                .setMqttClientProxyMessage(proxyMessage)
                .build(), false);
    }

    /**
     * Sets only the current Unix time on the node without changing other Position fields.
     *
     * @param epochSeconds current time in Unix epoch seconds
     * @return future with routing ACK/NAK for the sent admin packet
     */
    public static CompletableFuture<MeshProtos.Routing.Error> setTimeOnly(ProtocolHandler handler,
                                                                          DeviceState state,
                                                                          long epochSeconds) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetTimeOnly((int) epochSeconds);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        return sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Sends the current Unix time through {@code POSITION_APP} as a time-only
     * packet from the local PhoneAPI client. Some firmware handles phone-provided
     * time through this path before {@code set_time_only} is available.
     */
    public static void sendPhoneTimePosition(ProtocolHandler handler,
                                             DeviceState state,
                                             long epochSeconds) {
        if (handler == null || state == null || state.getMyNodeNum() == 0) {
            return;
        }

        MeshProtos.Position position = MeshProtos.Position.newBuilder()
                .setTime((int) epochSeconds)
                .build();
        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.POSITION_APP)
                .setPayload(position.toByteString())
                .build();
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0)
                .setTo(state.getMyNodeNum())
                .setDecoded(data)
                .build();

        handler.sendToRadio(MeshProtos.ToRadio.newBuilder()
                .setPacket(packet)
                .build());
    }

    /**
     * Sets owner info (longName, shortName) on the connected radio.
     * Requires session_passkey obtained by a prior get_owner_request.
     */
    public static void setOwnerInfo(ProtocolHandler handler, DeviceState state,
                                    String longName, String shortName, ByteString sessionPasskey) {
        setOwnerInfo(handler, state, longName, shortName, resolveOwnerLicensed(state), sessionPasskey);
    }

    /**
     * Sets owner info (longName, shortName, isLicensed) on the connected radio.
     * Requires session_passkey obtained by a prior get_owner_request.
     */
    public static void setOwnerInfo(ProtocolHandler handler, DeviceState state,
                                    String longName, String shortName, boolean isLicensed,
                                    ByteString sessionPasskey) {
        MeshProtos.User user = MeshProtos.User.newBuilder()
                .setLongName(longName)
                .setShortName(shortName)
                .setIsLicensed(isLicensed)
                .build();

        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetOwner(user);
        if (sessionPasskey != null) {
            adminBuilder.setSessionPasskey(sessionPasskey);
        }
        AdminProtos.AdminMessage adminMsg = adminBuilder.build();

        sendAdminMessage(handler, state, adminMsg);
    }

    private static boolean resolveOwnerLicensed(DeviceState state) {
        if (state == null) { return false; }
        MeshProtos.User ownerInfo = state.getOwnerInfo();
        if (ownerInfo != null) {
            return ownerInfo.getIsLicensed();
        }
        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        return myNode != null && myNode.isLicensed();
    }

    /**
     * Creates or updates a channel on the connected radio through AdminMessage.set_channel.
     * Requires session_passkey for protected devices; it may be null for local devices.
     */
    public static CompletableFuture<MeshProtos.Routing.Error> setChannel(ProtocolHandler handler, DeviceState state,
                                   ChannelProtos.Channel channel, ByteString sessionPasskey) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetChannel(channel);
        if (sessionPasskey != null) {
            adminBuilder.setSessionPasskey(sessionPasskey);
        }
        AdminProtos.AdminMessage adminMsg = adminBuilder.build();

        return sendAdminMessage(handler, state, adminMsg);
    }

    // ==================== Config Admin Methods ====================

    /**
     * Sends begin_edit_settings to start a settings transaction.
     * Prevents the device from rebooting between individual set_config/set_module_config calls.
     *
     * @return future with routing ACK/NAK for the sent admin packet
     */
    public static CompletableFuture<MeshProtos.Routing.Error> beginEditSettings(ProtocolHandler handler, DeviceState state) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setBeginEditSettings(true);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        return sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Sends commit_edit_settings to finish the settings transaction.
     * The device applies all changes and reboots.
     *
     * @return future with routing ACK/NAK for the sent admin packet
     */
    public static CompletableFuture<MeshProtos.Routing.Error> commitEditSettings(ProtocolHandler handler, DeviceState state) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setCommitEditSettings(true);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        return sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Sends set_config, one Config section, to the device.
     *
     * @return future with routing ACK/NAK for the sent admin packet
     */
    public static CompletableFuture<MeshProtos.Routing.Error> setConfig(ProtocolHandler handler, DeviceState state, ConfigProtos.Config config) {
        if (config.getPayloadVariantCase() == ConfigProtos.Config.PayloadVariantCase.LORA) {
            log.debug("setConfig LORA ignore_incoming {}", ConfigDebugFormatter.describeIgnoreIncoming(config));
        }
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetConfig(config);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        return sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Sends set_module_config, one ModuleConfig section, to the device.
     *
     * @return future with routing ACK/NAK for the sent admin packet
     */
    public static CompletableFuture<MeshProtos.Routing.Error> setModuleConfig(ProtocolHandler handler, DeviceState state,
                                        ModuleConfigProtos.ModuleConfig moduleConfig) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetModuleConfig(moduleConfig);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        return sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Sends set_ringtone_message to the device.
     *
     * @return future with routing ACK/NAK for the sent admin packet
     */
    public static CompletableFuture<MeshProtos.Routing.Error> setRingtone(ProtocolHandler handler,
                                                                          DeviceState state,
                                                                          String ringtone) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetRingtoneMessage(ringtone != null ? ringtone : "");
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        return sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Sends a device reboot command through {@code reboot_seconds}.
     *
     * @param delaySeconds delay before reboot, in seconds
     * @return future with routing ACK/NAK for the sent admin packet
     */
    public static CompletableFuture<MeshProtos.Routing.Error> rebootDevice(ProtocolHandler handler,
                                                                           DeviceState state,
                                                                           int delaySeconds) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setRebootSeconds(delaySeconds);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        return sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Sends a device shutdown command through {@code shutdown_seconds}.
     *
     * @param delaySeconds delay before shutdown, in seconds
     * @return future with routing ACK/NAK for the sent admin packet
     */
    public static CompletableFuture<MeshProtos.Routing.Error> shutdownDevice(ProtocolHandler handler,
                                                                             DeviceState state,
                                                                             int delaySeconds) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setShutdownSeconds(delaySeconds);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        return sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Sets a fixed position on the device.
     * Sends AdminMessage.set_fixed_position with coordinates. Firmware sets
     * position.fixed_position = true automatically.
     *
     * @param latDegrees latitude in degrees (-90..90)
     * @param lonDegrees longitude in degrees (-180..180)
     * @param altMeters  altitude in meters above sea level
     */
    public static void setFixedPosition(ProtocolHandler handler, DeviceState state,
                                         double latDegrees, double lonDegrees, int altMeters) {
        int latI = (int) Math.round(latDegrees * 1e7);
        int lonI = (int) Math.round(lonDegrees * 1e7);
        log.info("setFixedPosition: lat={}→latI={}, lon={}→lonI={}, alt={}",
                latDegrees, latI, lonDegrees, lonI, altMeters);

        MeshProtos.Position position = MeshProtos.Position.newBuilder()
                .setLatitudeI(latI)
                .setLongitudeI(lonI)
                .setAltitude(altMeters)
                .setTime((int) (System.currentTimeMillis() / 1000))
                .setLocationSource(MeshProtos.Position.LocSource.LOC_MANUAL)
                .setAltitudeSource(MeshProtos.Position.AltSource.ALT_MANUAL)
                .build();

        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetFixedPosition(position);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Removes the fixed position from the device.
     * Sends AdminMessage.remove_fixed_position = true. Firmware sets
     * position.fixed_position = false automatically.
     */
    public static void removeFixedPosition(ProtocolHandler handler, DeviceState state) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setRemoveFixedPosition(true);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Sends AdminMessage.set_favorite_node to the device.
     */
    public static void setFavoriteNode(ProtocolHandler handler, DeviceState state, int nodeNum) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetFavoriteNode(nodeNum);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }
        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Sends AdminMessage.remove_favorite_node to the device.
     */
    public static void removeFavoriteNode(ProtocolHandler handler, DeviceState state, int nodeNum) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setRemoveFavoriteNode(nodeNum);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }
        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Sends AdminMessage.set_ignored_node to the device.
     */
    public static void setIgnoredNode(ProtocolHandler handler, DeviceState state, int nodeNum) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetIgnoredNode(nodeNum);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }
        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Sends AdminMessage.remove_ignored_node to the device.
     */
    public static void removeIgnoredNode(ProtocolHandler handler, DeviceState state, int nodeNum) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setRemoveIgnoredNode(nodeNum);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }
        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Helper for sending AdminMessage to the local device.
     *
     * @return future with routing ACK/NAK for the sent admin packet
     */
    /**
     * Sends a mutating admin command to the local device.
     * <p>
     * For begin/set/commit and other write operations, only routing ACK is needed.
     * Requesting an ADMIN_APP response here is harmful: some firmware drops the
     * BLE session while processing set_module_config(MQTT), while the client does
     * not use the service response anyway.
     */
    private static CompletableFuture<MeshProtos.Routing.Error> sendAdminMessage(ProtocolHandler handler,
                                                                                DeviceState state,
                                                                                AdminProtos.AdminMessage adminMsg) {
        return sendAdminMessage(handler, state, adminMsg, false);
    }

    /**
     * Sends AdminMessage to the local device.
     *
     * @param wantResponse {@code true} only for read/query requests where the client
     *                     actually waits for an ADMIN_APP response; mutating commands
     *                     use {@code false} and rely on routing ACK
     * @return future with routing ACK/NAK for the sent admin packet
     */
    private static CompletableFuture<MeshProtos.Routing.Error> sendAdminMessage(ProtocolHandler handler,
                                          DeviceState state,
                                          AdminProtos.AdminMessage adminMsg,
                                          boolean wantResponse) {
        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.ADMIN_APP)
                .setPayload(adminMsg.toByteString())
                .setWantResponse(wantResponse)
                .build();

        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

        // PhoneAPI treats from=0 as a local client command; from=myNodeNum is
        // handled by firmware as remote admin and requires admin-channel/PKI auth.
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0)
                .setTo(state.getMyNodeNum())
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .build();

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(packet)
                .build();

        CompletableFuture<MeshProtos.Routing.Error> ackFuture = state.registerPendingPacketAck(packetId);
        handler.sendToRadio(toRadio);
        return ackFuture;
    }

    private static void requestOwnerInfoThenExchange(ProtocolHandler handler, DeviceState state, int targetNodeNum) {
        AtomicBoolean completed = new AtomicBoolean(false);
        Runnable[] holder = new Runnable[1];
        holder[0] = () -> {
            if (completed.compareAndSet(false, true)) {
                state.removeOwnerInfoListener(holder[0]);
                continueNodeUserInfoExchange(handler, state, targetNodeNum, buildLocalUserInfo(state));
            }
        };

        state.addOwnerInfoListener(holder[0]);
        CompletableFuture.delayedExecutor(OWNER_INFO_EXCHANGE_WAIT_MS, TimeUnit.MILLISECONDS).execute(() -> {
            if (completed.compareAndSet(false, true)) {
                state.removeOwnerInfoListener(holder[0]);
                log.debug("Owner info request timed out, continuing NODEINFO_APP exchange with current local state");
                continueNodeUserInfoExchange(handler, state, targetNodeNum, buildLocalUserInfo(state));
            }
        });
        requestOwnerInfo(handler, state);
    }

    private static CompletableFuture<MeshProtos.Routing.Error> preparePeerForPkiDirectMessage(ProtocolHandler handler,
                                                                                                DeviceState state,
                                                                                                NodeData peerNode) {
        CompletableFuture<MeshProtos.Routing.Error> shareFuture = shareLocalUserInfoForPki(handler, state, peerNode);
        seedPeerContactForPki(handler, state, peerNode);
        return shareFuture;
    }

    private static CompletableFuture<MeshProtos.Routing.Error> shareLocalUserInfoForPki(ProtocolHandler handler,
                                                                                         DeviceState state,
                                                                                         NodeData peerNode) {
        if (handler == null || state == null || peerNode == null || peerNode.getNodeNum() == 0) {
            return CompletableFuture.completedFuture(MeshProtos.Routing.Error.NONE);
        }
        MeshProtos.User localUser = buildLocalUserInfo(state);
        if (localUser == null) {
            log.debug("Skipping outgoing NODEINFO_APP before PKI DM: local user info is unavailable");
            return CompletableFuture.completedFuture(MeshProtos.Routing.Error.NONE);
        }
        log.debug("Sharing local user info with {} before PKI DM", peerNode.getNodeId());
        return sendNodeInfoPacketAwaitRoutingAck(handler, state, peerNode.getNodeNum(), localUser.toByteString(), false);
    }

    static void seedPeerContactForPki(ProtocolHandler handler, DeviceState state, NodeData peerNode) {
        if (handler == null || state == null || peerNode == null) { return; }
        byte[] publicKey = peerNode.getPublicKey();
        if (publicKey == null || publicKey.length == 0) { return; }

        AdminProtos.SharedContact contact = AdminProtos.SharedContact.newBuilder()
                .setNodeNum(peerNode.getNodeNum())
                .setUser(buildUserFromNode(peerNode))
                .build();
        AdminProtos.AdminMessage adminMsg = AdminProtos.AdminMessage.newBuilder()
                .setAddContact(contact)
                .build();

        log.debug("Seeding local NodeDB with PKI contact {} (nodeNum={})",
                peerNode.getNodeId(), Integer.toUnsignedString(peerNode.getNodeNum()));
        sendAdminMessage(handler, state, adminMsg).whenComplete((error, throwable) -> {
            if (throwable != null) {
                log.debug("Failed to seed PKI contact {} into local NodeDB",
                        peerNode.getNodeId(), throwable);
            } else if (error != null && error != MeshProtos.Routing.Error.NONE) {
                log.debug("Local NodeDB rejected PKI contact {} with {}",
                        peerNode.getNodeId(), error);
            }
        });
    }

    private static MeshProtos.User buildUserFromNode(NodeData node) {
        MeshProtos.User.Builder builder = MeshProtos.User.newBuilder();
        if (node == null) { return builder.build(); }
        if (node.getNodeId() != null && !node.getNodeId().isEmpty()) {
            builder.setId(node.getNodeId());
        }
        if (node.getLongName() != null && !node.getLongName().isEmpty()) {
            builder.setLongName(node.getLongName());
        }
        if (node.getShortName() != null && !node.getShortName().isEmpty()) {
            builder.setShortName(node.getShortName());
        }
        if (node.getRole() != null && !node.getRole().isEmpty()) {
            try {
                builder.setRole(ConfigProtos.Config.DeviceConfig.Role.valueOf(node.getRole()));
            } catch (IllegalArgumentException ignored) {
                log.debug("Skipping unknown role '{}' for PKI contact {}", node.getRole(), node.getNodeId());
            }
        }
        if (node.getHwModel() != null && !node.getHwModel().isEmpty()) {
            try {
                builder.setHwModel(MeshProtos.HardwareModel.valueOf(node.getHwModel()));
            } catch (IllegalArgumentException ignored) {
                log.debug("Skipping unknown hwModel '{}' for PKI contact {}", node.getHwModel(), node.getNodeId());
            }
        }
        if (node.getPublicKey() != null && node.getPublicKey().length > 0) {
            builder.setPublicKey(ByteString.copyFrom(node.getPublicKey()));
        }
        if (node.getUnmessagable() != null) {
            builder.setIsUnmessagable(node.getUnmessagable());
        }
        return builder.build();
    }

    private static boolean shouldRefreshOwnerInfoBeforeExchange(DeviceState state, MeshProtos.User localUser) {
        if (state == null || state.getOwnerInfo() != null) { return false; }
        if (localUser == null) { return true; }

        return localUser.getLongName().isEmpty() && localUser.getShortName().isEmpty();
    }

    private static void continueNodeUserInfoExchange(ProtocolHandler handler,
                                                     DeviceState state,
                                                     int targetNodeNum,
                                                     MeshProtos.User localUser) {
        if (localUser != null) {
            sendNodeInfoPacket(handler, state, targetNodeNum, localUser.toByteString(), false);
        } else {
            log.debug("Skipping outgoing NODEINFO_APP exchange: local user info is unavailable");
        }

        requestNodeInfo(handler, state, targetNodeNum);
    }

    private static MeshProtos.Data buildTextMessageData(String text, int replyId) {
        MeshProtos.Data.Builder dataBuilder = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                .setPayload(ByteString.copyFrom(text, StandardCharsets.UTF_8));
        if (replyId != 0) {
            dataBuilder.setReplyId(replyId);
        }
        return dataBuilder.build();
    }

    private static void logRetryReplyDebug(String destination, int replyId, MeshProtos.Data data) {
        if (replyId == 0) {
            return;
        }
        log.info("REPLY_DEBUG retry {}: replyId={} (0x{}), payloadBytes={}",
                destination, replyId, Integer.toHexString(replyId), data.getPayload().size());
    }

    private static NodeData resolvePeerNode(DeviceState state, String peerNodeId) {
        if (state == null || peerNodeId == null || peerNodeId.isEmpty()) { return null; }

        NodeData peerNode = state.getNodeByNodeId(peerNodeId);
        if (peerNode != null) {
            NodeCacheService.getInstance().enrichFromCache(peerNode);
            return peerNode;
        }

        if (peerNodeId.length() > 1 && peerNodeId.charAt(0) == '!') {
            try {
                int peerNodeNum = (int) Long.parseUnsignedLong(peerNodeId.substring(1), 16);
                NodeData resolvedByNum = state.getNodeDb().get(peerNodeNum);
                if (resolvedByNum != null) {
                    NodeCacheService.getInstance().enrichFromCache(resolvedByNum);
                    if (!peerNodeId.equals(resolvedByNum.getNodeId())) {
                        NodeData cachedByLegacyId = NodeCacheService.getInstance().get(peerNodeId);
                        mergeLegacyDirectMessageHints(resolvedByNum, cachedByLegacyId);
                    }
                    return resolvedByNum;
                }
                return NodeCacheService.getInstance().get(peerNodeId);
            } catch (NumberFormatException e) {
                log.debug("Peer nodeId '{}' is not a valid hex node number", peerNodeId);
            }
        }
        return null;
    }

    private static void mergeLegacyDirectMessageHints(NodeData resolvedNode, NodeData legacyCachedNode) {
        if (resolvedNode == null || legacyCachedNode == null) { return; }

        if ((resolvedNode.getLongName() == null || resolvedNode.getLongName().isEmpty())
                && legacyCachedNode.getLongName() != null
                && !legacyCachedNode.getLongName().isEmpty()) {
            resolvedNode.setLongName(legacyCachedNode.getLongName());
        }
        if ((resolvedNode.getShortName() == null || resolvedNode.getShortName().isEmpty())
                && legacyCachedNode.getShortName() != null
                && !legacyCachedNode.getShortName().isEmpty()) {
            resolvedNode.setShortName(legacyCachedNode.getShortName());
        }
        if (resolvedNode.getChannel() == 0 && legacyCachedNode.getChannel() != 0) {
            resolvedNode.setChannel(legacyCachedNode.getChannel());
        }
        if ((resolvedNode.getPublicKey() == null || resolvedNode.getPublicKey().length == 0)
                && legacyCachedNode.getPublicKey() != null
                && legacyCachedNode.getPublicKey().length > 0) {
            resolvedNode.setPublicKey(legacyCachedNode.getPublicKey().clone());
        }
        if (resolvedNode.getUnmessagable() == null && legacyCachedNode.getUnmessagable() != null) {
            resolvedNode.setUnmessagable(legacyCachedNode.getUnmessagable());
        }
    }

    private static int resolveDirectMessageChannel(DeviceState state, String peerNodeId, NodeData peerNode) {
        if (state == null) { return 0; }

        for (String chatKey : directMessageChatKeys(peerNodeId, peerNode)) {
            int recentChannel = findRecentSecondaryDirectMessageChannel(state, chatKey);
            if (recentChannel > 0) {
                return recentChannel;
            }
        }

        return normalizeDirectMessageChannel(state, peerNode != null ? peerNode.getChannel() : 0);
    }

    private static boolean shouldUsePkiDirectMessage(DeviceState state, String peerNodeId, NodeData peerNode) {
        byte[] publicKey = peerNode != null ? peerNode.getPublicKey() : null;
        if (publicKey == null || publicKey.length == 0) {
            log.debug("DM transport for {} stays legacy: peer public key is unavailable", peerNodeId);
            return false;
        }
        log.debug("DM transport for {} switches to PKI: peer public key is available", peerNodeId);
        return true;
    }

    private static String[] directMessageChatKeys(String peerNodeId, NodeData peerNode) {
        if (peerNodeId == null || peerNodeId.isEmpty()) {
            return new String[0];
        }

        String resolvedPeerNodeId = peerNode != null ? peerNode.getNodeId() : null;
        if (resolvedPeerNodeId != null
                && !resolvedPeerNodeId.isEmpty()
                && !resolvedPeerNodeId.equals(peerNodeId)) {
            return new String[] { peerNodeId, resolvedPeerNodeId };
        }

        return new String[] { peerNodeId };
    }

    private static int findRecentSecondaryDirectMessageChannel(DeviceState state, String chatKey) {
        if (state == null || chatKey == null || chatKey.isEmpty()) { return 0; }

        List<MeshMessage> recentMessages = state.getDirectMessages(chatKey);
        synchronized (recentMessages) {
            for (int i = recentMessages.size() - 1; i >= 0; i--) {
                int channelIndex = normalizeDirectMessageChannel(state, recentMessages.get(i).getChannelIndex());
                if (channelIndex > 0) {
                    return channelIndex;
                }
            }
        }

        String ownerNodeId = String.format("!%08x", state.getMyNodeNum());
        List<MeshMessage> persistedMessages = MessageDbService.getInstance().loadLast("dm", chatKey, 20, ownerNodeId);
        for (int i = persistedMessages.size() - 1; i >= 0; i--) {
            int channelIndex = normalizeDirectMessageChannel(state, persistedMessages.get(i).getChannelIndex());
            if (channelIndex > 0) {
                return channelIndex;
            }
        }

        return 0;
    }

    private static int normalizeDirectMessageChannel(DeviceState state, int channelIndex) {
        if (channelIndex <= 0) { return 0; }
        if (state == null) { return channelIndex; }

        List<ChannelProtos.Channel> channels = state.getChannels();
        if (channels.isEmpty()) {
            return channelIndex;
        }

        synchronized (channels) {
            for (ChannelProtos.Channel channel : channels) {
                if (channel.getIndex() == channelIndex) {
                    return channel.getRole() == ChannelProtos.Channel.Role.DISABLED ? 0 : channelIndex;
                }
            }
        }

        log.debug("Ignoring stale DM channel {} because it is not present in the local channel list", channelIndex);
        return 0;
    }
    private static void sendNodeInfoPacket(ProtocolHandler handler,
                                           DeviceState state,
                                           int targetNodeNum,
                                           ByteString payload,
                                           boolean wantResponse) {
        if (handler == null || state == null || targetNodeNum == 0) { return; }

        MeshProtos.Data.Builder dataBuilder = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.NODEINFO_APP)
                .setWantResponse(wantResponse);
        if (payload != null && !payload.isEmpty()) {
            dataBuilder.setPayload(payload);
        }

        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(targetNodeNum)
                .setDecoded(dataBuilder.build())
                .setId(packetId)
                .setWantAck(true)
                .build();

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(packet)
                .build();

        handler.sendToRadio(toRadio);
    }

    private static CompletableFuture<MeshProtos.Routing.Error> sendNodeInfoPacketAwaitRoutingAck(ProtocolHandler handler,
                                                                                                  DeviceState state,
                                                                                                  int targetNodeNum,
                                                                                                  ByteString payload,
                                                                                                  boolean wantResponse) {
        if (handler == null || state == null || targetNodeNum == 0) {
            return CompletableFuture.completedFuture(MeshProtos.Routing.Error.BAD_REQUEST);
        }

        MeshProtos.Data.Builder dataBuilder = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.NODEINFO_APP)
                .setWantResponse(wantResponse);
        if (payload != null && !payload.isEmpty()) {
            dataBuilder.setPayload(payload);
        }

        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(targetNodeNum)
                .setDecoded(dataBuilder.build())
                .setId(packetId)
                .setWantAck(true)
                .build();

        CompletableFuture<MeshProtos.Routing.Error> ackFuture = state.registerPendingPacketAck(packetId);
        handler.sendToRadio(MeshProtos.ToRadio.newBuilder().setPacket(packet).build());
        return ackFuture;
    }

    private static void dispatchDirectMessagePacket(ProtocolHandler handler,
                                                    DeviceState state,
                                                    String peerNodeId,
                                                    NodeData peerNode,
                                                    int peerNodeNum,
                                                    MeshProtos.Data data,
                                                    int packetId,
                                                    int directChannel,
                                                    boolean usePkiTransport,
                                                    MeshMessage msg) {
        rememberDirectMessageRoutingHint(peerNodeId, peerNode, directChannel, usePkiTransport);

        MeshProtos.MeshPacket.Builder packetBuilder = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(peerNodeNum)
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true);
        if (usePkiTransport) {
            packetBuilder.setPkiEncrypted(true);
        } else if (directChannel > 0) {
            packetBuilder.setChannel(directChannel);
        }
        log.debug("Sending DM to {} (nodeNum={}) via {} transport, channel={}",
                peerNodeId, Integer.toUnsignedString(peerNodeNum), usePkiTransport ? "PKI" : "legacy", directChannel);
        handler.sendToRadio(MeshProtos.ToRadio.newBuilder().setPacket(packetBuilder.build()).build());
        state.registerPendingAck(packetId, msg);
    }

    private static boolean prepareMessageForRetry(MeshMessage message, int newPacketId) {
        if (message == null || newPacketId == 0) {
            return false;
        }

        int previousPacketId = message.getPacketId();
        MeshMessage.DeliveryStatus previousStatus = message.getStatus();
        String previousErrorReason = message.getErrorReason();

        message.setPacketId(newPacketId);
        message.setStatus(MeshMessage.DeliveryStatus.SENDING);
        message.setErrorReason(null);

        boolean updated = MessageDbService.getInstance().updateMessageForRetry(
                message.getDbId(),
                previousPacketId,
                newPacketId,
                message.getStatus(),
                null);
        if (updated) {
            return true;
        }

        message.setPacketId(previousPacketId);
        message.setStatus(previousStatus);
        message.setErrorReason(previousErrorReason);
        return false;
    }

    private static boolean isChannelMessage(MeshMessage message) {
        return message != null && BROADCAST_NODE_ID.equalsIgnoreCase(message.getToNodeId());
    }

    private static void rememberDirectMessageRoutingHint(String peerNodeId,
                                                         NodeData peerNode,
                                                         int directChannel,
                                                         boolean usePkiTransport) {
        if (usePkiTransport || peerNode == null || directChannel <= 0) { return; }

        if (peerNode.getChannel() != directChannel) {
            peerNode.setChannel(directChannel);
        }
        if (peerNode.getNodeId() != null && !peerNode.getNodeId().isEmpty()) {
            NodeCacheService.getInstance().update(peerNode);
        }

        String currentPeerNodeId = peerNode.getNodeId();
        if (peerNodeId == null || peerNodeId.isEmpty() || peerNodeId.equals(currentPeerNodeId)) {
            return;
        }

        NodeData legacyAlias = new NodeData(peerNode.getNodeNum());
        legacyAlias.setNodeId(peerNodeId);
        legacyAlias.setChannel(directChannel);
        if (peerNode.getPublicKey() != null && peerNode.getPublicKey().length > 0) {
            legacyAlias.setPublicKey(peerNode.getPublicKey().clone());
        }
        if (peerNode.getUnmessagable() != null) {
            legacyAlias.setUnmessagable(peerNode.getUnmessagable());
        }
        NodeCacheService.getInstance().update(legacyAlias);
    }

    private static MeshProtos.User buildLocalUserInfo(DeviceState state) {
        if (state == null) { return null; }

        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        MeshProtos.User ownerInfo = state.getOwnerInfo();
        MeshProtos.DeviceMetadata deviceMetadata = state.getDeviceMetadata();
        ByteString securityPublicKey = extractSecurityPublicKey(state);
        if (myNode == null && ownerInfo == null && deviceMetadata == null
                && (securityPublicKey == null || securityPublicKey.isEmpty())) {
            return null;
        }

        MeshProtos.User.Builder builder = MeshProtos.User.newBuilder();
        boolean hasMeaningfulField = false;

        String nodeId = firstNonEmpty(
                myNode != null ? myNode.getNodeId() : null,
                ownerInfo != null ? ownerInfo.getId() : null,
                state.getMyNodeNum() != 0 ? String.format("!%08x", state.getMyNodeNum()) : null
        );
        if (nodeId != null) {
            builder.setId(nodeId);
        }

        String longName = firstNonEmpty(
                ownerInfo != null ? ownerInfo.getLongName() : null,
                myNode != null ? myNode.getLongName() : null
        );
        if (longName != null) {
            builder.setLongName(longName);
            hasMeaningfulField = true;
        }

        String shortName = firstNonEmpty(
                ownerInfo != null ? ownerInfo.getShortName() : null,
                myNode != null ? myNode.getShortName() : null
        );
        if (shortName != null) {
            builder.setShortName(shortName);
            hasMeaningfulField = true;
        }

        Boolean licensed = resolveLocalUserLicensed(myNode, ownerInfo);
        if (Boolean.TRUE.equals(licensed)) {
            builder.setIsLicensed(true);
            hasMeaningfulField = true;
        }

        if (securityPublicKey != null && !securityPublicKey.isEmpty()) {
            builder.setPublicKey(securityPublicKey);
            hasMeaningfulField = true;
        }

        ConfigProtos.Config.DeviceConfig.Role role = resolveLocalUserRole(myNode, ownerInfo, deviceMetadata);
        if (role != null && role != ConfigProtos.Config.DeviceConfig.Role.CLIENT) {
            builder.setRole(role);
            hasMeaningfulField = true;
        }

        MeshProtos.HardwareModel hwModel = resolveLocalUserHwModel(myNode, ownerInfo, deviceMetadata);
        if (hwModel != null && hwModel != MeshProtos.HardwareModel.UNSET) {
            builder.setHwModel(hwModel);
            hasMeaningfulField = true;
        }

        Boolean unmessagable = resolveLocalUserUnmessagable(myNode, ownerInfo);
        if (unmessagable != null) {
            builder.setIsUnmessagable(unmessagable);
            hasMeaningfulField = true;
        }

        return hasMeaningfulField ? builder.build() : null;
    }

    private static Boolean resolveLocalUserLicensed(NodeData myNode, MeshProtos.User ownerInfo) {
        if (ownerInfo != null && ownerInfo.getIsLicensed()) {
            return true;
        }
        return myNode != null ? myNode.getLicensed() : null;
    }

    private static ConfigProtos.Config.DeviceConfig.Role resolveLocalUserRole(NodeData myNode,
                                                                              MeshProtos.User ownerInfo,
                                                                              MeshProtos.DeviceMetadata deviceMetadata) {
        if (ownerInfo != null && ownerInfo.getRole() != ConfigProtos.Config.DeviceConfig.Role.CLIENT) {
            return ownerInfo.getRole();
        }
        if (deviceMetadata != null && deviceMetadata.getRole() != ConfigProtos.Config.DeviceConfig.Role.CLIENT) {
            return deviceMetadata.getRole();
        }
        return parseNodeRole(myNode);
    }

    private static MeshProtos.HardwareModel resolveLocalUserHwModel(NodeData myNode,
                                                                    MeshProtos.User ownerInfo,
                                                                    MeshProtos.DeviceMetadata deviceMetadata) {
        if (ownerInfo != null && ownerInfo.getHwModel() != MeshProtos.HardwareModel.UNSET) {
            return ownerInfo.getHwModel();
        }
        if (deviceMetadata != null && deviceMetadata.getHwModel() != MeshProtos.HardwareModel.UNSET) {
            return deviceMetadata.getHwModel();
        }
        return parseNodeHwModel(myNode);
    }

    private static Boolean resolveLocalUserUnmessagable(NodeData myNode, MeshProtos.User ownerInfo) {
        if (ownerInfo != null && ownerInfo.hasIsUnmessagable()) {
            return ownerInfo.getIsUnmessagable();
        }
        return myNode != null ? myNode.getUnmessagable() : null;
    }

    private static ConfigProtos.Config.DeviceConfig.Role parseNodeRole(NodeData node) {
        if (node == null || node.getRole() == null || node.getRole().isEmpty()) {
            return null;
        }
        try {
            return ConfigProtos.Config.DeviceConfig.Role.valueOf(node.getRole());
        } catch (IllegalArgumentException ignored) {
            log.debug("Skipping unknown local role '{}'", node.getRole());
            return null;
        }
    }

    private static MeshProtos.HardwareModel parseNodeHwModel(NodeData node) {
        if (node == null || node.getHwModel() == null || node.getHwModel().isEmpty()) {
            return null;
        }
        try {
            return MeshProtos.HardwareModel.valueOf(node.getHwModel());
        } catch (IllegalArgumentException ignored) {
            log.debug("Skipping unknown local hwModel '{}'", node.getHwModel());
            return null;
        }
    }

    private static ByteString extractSecurityPublicKey(DeviceState state) {
        if (state == null) { return null; }

        List<ConfigProtos.Config> configs = state.getConfigs();
        synchronized (configs) {
            for (ConfigProtos.Config config : configs) {
                if (config.getPayloadVariantCase() == ConfigProtos.Config.PayloadVariantCase.SECURITY) {
                    ByteString publicKey = config.getSecurity().getPublicKey();
                    if (!publicKey.isEmpty()) {
                        return publicKey;
                    }
                }
            }
        }
        return null;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) { return null; }
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static boolean saveOutgoingReaction(DeviceState state,
                                                String chatType,
                                                String chatKey,
                                                MeshMessage targetMessage,
                                                String emoji,
                                                long now,
                                                int packetId) {
        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        String ownerNodeId = String.format("!%08x", state.getMyNodeNum());
        String myNodeId = myNode != null && myNode.getNodeId() != null && !myNode.getNodeId().isEmpty()
                ? myNode.getNodeId()
                : ownerNodeId;

        MessageReaction reaction = new MessageReaction(
                targetMessage.getPacketId(),
                myNodeId,
                emoji,
                now,
                true
        );
        reaction.setPacketId(packetId);
        reaction.setStatus(MeshMessage.DeliveryStatus.SENDING);
        if (myNode != null && myNode.getLongName() != null) {
            reaction.setSenderName(myNode.getLongName());
        }

        return MessageDbService.getInstance().saveReaction(reaction, chatType, chatKey, ownerNodeId);
    }
}
