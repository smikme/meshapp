package com.meshtastic.client.components.chat;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.UnicodeTextUtils;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Shared helpers for displaying node names and avatars in chat.
 *
 * <p>The class separates display-name selection rules from JavaFX rendering so
 * the bubble factory and other chat components do not duplicate the same
 * fallback chains.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class ChatNodeDisplayHelper {

    private static final String DEFAULT_AVATAR_COLOR = "#5B8DEF";
    private static final String OUTGOING_AVATAR_COLOR = "#1EA97C";
    private static final String UNKNOWN_AVATAR_TEXT = "?";
    private static final int MAX_AVATAR_TEXT_LENGTH = 4;
    private static final int MIN_NODE_ID_LENGTH = 2;
    private static final int NODE_ID_PREFIX_LENGTH = 1;
    private static final int NODE_ID_HEX_RADIX = 16;
    private static final List<String> AVATAR_COLORS = List.of(
            "#5B8DEF", "#E57C23", "#9B59B6", "#1EA97C",
            "#E74C3C", "#3498DB", "#F39C12", "#1ABC9C"
    );

    private ChatNodeDisplayHelper() {}

    /**
     * Prepared data for building a message avatar.
 *
     * @param text  text shown inside the avatar
     * @param color CSS background color for the avatar
     */
    record AvatarDescriptor(String text, String color) {
        AvatarDescriptor {
            text = normalizeAvatarText(text);
            color = isBlank(color) ? DEFAULT_AVATAR_COLOR : color;
        }
    }

    /**
     * Prepared sender data for an incoming message.
 *
     * @param senderName name shown above the message bubble
     * @param avatar     avatar text and color
     */
    record IncomingMessagePresentation(String senderName, AvatarDescriptor avatar) {}

    /**
     * Resolves a node name from its node number.
 *
     * @param state   device state
     * @param nodeNum node number
     * @return {@code longName} when known, otherwise {@code !hex}
     */
    static String resolveNodeName(DeviceState state, int nodeNum) {
        NodeData node = NodeUtils.resolveNode(state, nodeNum);
        return firstNonBlank(
                node != null ? node.getLongName() : null,
                "!" + String.format("%08x", nodeNum)
        );
    }

    /**
     * Resolves the sender name used in the input reply preview.
 *
     * @param state device state
     * @param msg   message
     * @return localized self label for outgoing messages, otherwise the sender name with fallbacks
     */
    static String resolveReplySenderName(DeviceState state, MeshMessage msg) {
        return Optional.ofNullable(msg)
                .map(message -> message.isOutgoing() ? I18n.t("chat.self") : resolveIncomingSenderName(state, message))
                .orElse("");
    }

    /**
     * Resolves all sender data needed to render an incoming bubble.
 *
     * @param state device state
     * @param msg   incoming message
     * @return sender name and avatar descriptor
     */
    static IncomingMessagePresentation resolveIncomingMessagePresentation(DeviceState state,
                                                                          MeshMessage msg) {
        return Optional.ofNullable(msg)
                .map(message -> {
                    NodeData senderNode = resolveNode(state, message.getFromNodeId());
                    return new IncomingMessagePresentation(
                            resolveMessageDisplayName(senderNode, message.getSenderName(), message.getFromNodeId()),
                            buildIncomingAvatarDescriptor(senderNode, message.getFromNodeId())
                    );
                })
                .orElseGet(() -> new IncomingMessagePresentation(
                        "",
                        new AvatarDescriptor(UNKNOWN_AVATAR_TEXT, DEFAULT_AVATAR_COLOR)
                ));
    }

    /**
     * Resolves avatar data for outgoing messages.
 *
     * @param state device state
     * @return local-user avatar descriptor
     */
    static AvatarDescriptor resolveOutgoingAvatar(DeviceState state) {
        NodeData myNode = state == null ? null : state.getNodeDb().get(state.getMyNodeNum());
        return new AvatarDescriptor(
                firstNonBlank(myNode != null ? myNode.getShortName() : null, I18n.t("chat.self.avatar")),
                OUTGOING_AVATAR_COLOR
        );
    }

    /**
     * Resolves a reaction author's display name, falling back to short name and node id.
 *
     * @param state    device state
     * @param reaction reaction
     * @return displayed reaction author name
     */
    static String resolveReactionSenderDisplayName(DeviceState state, MessageReaction reaction) {
        return Optional.ofNullable(reaction)
                .map(currentReaction -> {
                    NodeData senderNode = resolveNode(state, currentReaction.getFromNodeId());
                    return firstNonBlank(
                            senderNode != null ? senderNode.getLongName() : null,
                            currentReaction.getSenderName(),
                            senderNode != null ? senderNode.getShortName() : null,
                            currentReaction.getFromNodeId()
                    );
                })
                .orElse("");
    }

    /**
     * Finds the node used to open details after an avatar click.
 *
     * <p>If the node is not loaded in memory yet, the method creates a bare node
     * from {@code nodeId} and tries to enrich it from the cache.
 *
     * @param state  device state
     * @param nodeId node identifier
     * @return found or created node, or {@code null} when it cannot be resolved
     */
    static NodeData resolveNodeForDetails(DeviceState state, String nodeId) {
        return Optional.ofNullable(state)
                .filter(ignored -> !isBlank(nodeId))
                .map(currentState -> Stream.of(
                                resolveNode(currentState, nodeId),
                                createBareNodeCandidate(currentState, nodeId)
                        )
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null))
                .orElse(null);
    }

    /**
     * Resolves an incoming message display name without duplicating the fallback chain in the UI.
 *
     * @param state device state
     * @param msg   incoming message
     * @return longName, stored senderName, or nodeId
     */
    private static String resolveIncomingSenderName(DeviceState state, MeshMessage msg) {
        NodeData senderNode = resolveNode(state, msg.getFromNodeId());
        return resolveMessageDisplayName(senderNode, msg.getSenderName(), msg.getFromNodeId());
    }

    /**
     * Selects the first available message name in priority order.
 *
     * @param senderNode resolved sender node
     * @param senderName name stored with the message
     * @param nodeId     node identifier
     * @return text shown in the UI
     */
    private static String resolveMessageDisplayName(NodeData senderNode,
                                                    String senderName,
                                                    String nodeId) {
        return firstNonBlank(
                senderNode != null ? senderNode.getLongName() : null,
                senderName,
                nodeId
        );
    }

    /**
     * Builds avatar text and color for an incoming message.
 *
     * @param senderNode resolved sender node
     * @param nodeId     node identifier
     * @return avatar descriptor using shortName or the nodeId tail
     */
    private static AvatarDescriptor buildIncomingAvatarDescriptor(NodeData senderNode, String nodeId) {
        return new AvatarDescriptor(
                firstNonBlank(
                        senderNode != null ? senderNode.getShortName() : null,
                        nodeIdTail(nodeId),
                        UNKNOWN_AVATAR_TEXT
                ),
                avatarColor(nodeId)
        );
    }

    /**
     * Finds a node by {@code nodeId} through the shared resolver, ignoring blank values.
 *
     * @param state  device state
     * @param nodeId node identifier
     * @return resolved node, or {@code null}
     */
    private static NodeData resolveNode(DeviceState state, String nodeId) {
        return Optional.ofNullable(nodeId)
                .filter(Predicate.not(String::isBlank))
                .map(id -> NodeUtils.resolveNode(state, id))
                .orElse(null);
    }

    /**
     * Calculates a stable avatar color from the {@code nodeId} hash.
 *
     * @param nodeId node identifier
     * @return hex color from the fixed palette
     */
    private static String avatarColor(String nodeId) {
        return isBlank(nodeId)
                ? DEFAULT_AVATAR_COLOR
                : AVATAR_COLORS.get(Math.floorMod(nodeId.hashCode(), AVATAR_COLORS.size()));
    }

    /**
     * Uses the last characters of {@code nodeId} as a compact avatar fallback.
 *
     * @param nodeId node identifier
     * @return nodeId tail up to {@value #MAX_AVATAR_TEXT_LENGTH} characters
     */
    private static String nodeIdTail(String nodeId) {
        return isBlank(nodeId)
                ? null
                : UnicodeTextUtils.suffixByCodePoints(nodeId, MAX_AVATAR_TEXT_LENGTH);
    }

    /**
     * Normalizes avatar text: uppercase with a length cap.
 *
     * @param value source text
     * @return safe text for a small round avatar
     */
    private static String normalizeAvatarText(String value) {
        String normalized = UnicodeTextUtils.sanitize(firstNonBlank(value, UNKNOWN_AVATAR_TEXT))
                .toUpperCase(Locale.ROOT);
        return UnicodeTextUtils.prefixByCodePoints(normalized, MAX_AVATAR_TEXT_LENGTH);
    }

    /**
     * Returns the first non-blank candidate from a fallback list.
 *
     * @param values candidates in priority order
     * @return first non-blank value, or an empty string
     */
    private static String firstNonBlank(String... values) {
        return Stream.of(values)
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(UnicodeTextUtils::sanitize)
                .filter(Predicate.not(String::isBlank))
                .findFirst()
                .orElse("");
    }

    /**
     * Checks whether a string is missing or contains only whitespace.
 *
     * @param value value to check
     * @return {@code true} when the string is blank
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Attempts to create a bare node from the hex part of nodeId when the sender is not loaded yet.
 *
     * @param state  device state
     * @param nodeId node identifier
     * @return cache-enriched bare node, or {@code null} when nodeId is invalid
     */
    private static NodeData createBareNodeCandidate(DeviceState state, String nodeId) {
        if (nodeId.length() < MIN_NODE_ID_LENGTH) {
            return null;
        }

        try {
            int nodeNum = (int) Long.parseUnsignedLong(
                    nodeId.substring(NODE_ID_PREFIX_LENGTH),
                    NODE_ID_HEX_RADIX
            );
            NodeData bareNode = state.getOrCreateNode(nodeNum);
            NodeCacheService.getInstance().enrichFromCache(bareNode);
            return bareNode;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
