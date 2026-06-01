package com.meshtastic.client.components.chat;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageReaction;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Prepares message reactions for chat rendering.
 *
 * <p>The helper groups identical emoji, preserves first-seen order, and builds
 * author tooltips in one pass so the UI layer does not repeatedly scan every reaction.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class ChatReactionHelper {

    private ChatReactionHelper() {}

    /**
     * Final model for one reaction chip in the UI.
     *
     * @param emoji reaction emoji
     * @param own whether the local user is among the authors
     * @param count number of identical reactions
     * @param tooltipText tooltip text with author names
     */
    record ReactionSummary(String emoji, boolean own, int count, String tooltipText) {}

    private record SenderKey(String nodeId, String senderName) {}

    /**
     * Aggregates visible reactions for the reaction bar.
     *
     * @param state device state
     * @param reactions message reactions
     * @return summaries in first-seen emoji order
     */
    static List<ReactionSummary> summarize(DeviceState state, List<MessageReaction> reactions) {
        Map<SenderKey, String> senderNameCache = new HashMap<>();
        return Stream.ofNullable(reactions)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .filter(MessageReaction::isVisible)
                .filter(ChatReactionHelper::hasEmoji)
                .collect(Collectors.groupingBy(
                        MessageReaction::getEmoji,
                        LinkedHashMap::new,
                        Collectors.toList()
                ))
                .entrySet().stream()
                .map(entry -> toSummary(state, entry.getKey(), entry.getValue(), senderNameCache))
                .toList();
    }

    /**
     * Builds the final model for one emoji from an existing reaction group.
     *
     * @param state device state
     * @param emoji group emoji
     * @param groupedReactions all reactions with this emoji
     * @param senderNameCache local cache of resolved author names
     * @return summary for one reaction chip
     */
    private static ReactionSummary toSummary(DeviceState state,
                                             String emoji,
                                             List<MessageReaction> groupedReactions,
                                             Map<SenderKey, String> senderNameCache) {
        String tooltipText = groupedReactions.stream()
                .map(reaction -> resolveSenderName(state, reaction, senderNameCache))
                .filter(Predicate.not(String::isBlank))
                .collect(Collectors.joining("\n"));

        boolean own = groupedReactions.stream().anyMatch(MessageReaction::isOutgoing);
        return new ReactionSummary(emoji, own, groupedReactions.size(), tooltipText);
    }

    /**
     * Resolves a reaction author name and caches it by node id or sender name.
     *
     * @param state device state
     * @param reaction reaction
     * @param senderNameCache name cache scoped to one aggregation run
     * @return display name of the reaction author
     */
    private static String resolveSenderName(DeviceState state,
                                            MessageReaction reaction,
                                            Map<SenderKey, String> senderNameCache) {
        SenderKey key = new SenderKey(reaction.getFromNodeId(), reaction.getSenderName());
        return senderNameCache.computeIfAbsent(
                key,
                ignored -> ChatNodeDisplayHelper.resolveReactionSenderDisplayName(state, reaction)
        );
    }

    /**
     * Filters technical or malformed reactions that have no emoji.
     *
     * @param reaction reaction
     * @return {@code true} when the emoji can be rendered
     */
    private static boolean hasEmoji(MessageReaction reaction) {
        return reaction.getEmoji() != null && !reaction.getEmoji().isBlank();
    }
}
