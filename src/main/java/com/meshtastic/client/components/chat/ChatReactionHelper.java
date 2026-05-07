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
 * Подготавливает данные реакций для рендера в чате.
 *
 * <p>Helper агрегирует одинаковые emoji, сохраняет их исходный порядок появления
 * и вычисляет tooltip авторов за один проход группировки, чтобы UI-слой
 * не выполнял повторные линейные сканирования по всем реакциям.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class ChatReactionHelper {

    private ChatReactionHelper() {}

    /**
     * Итоговая модель одного reaction-chip в UI.
     *
     * @param emoji emoji реакции
     * @param own есть ли среди реакций локальная
     * @param count количество одинаковых реакций
     * @param tooltipText текст tooltip с именами авторов
     */
    record ReactionSummary(String emoji, boolean own, int count, String tooltipText) {}

    private record SenderKey(String nodeId, String senderName) {}

    /**
     * Агрегирует набор видимых реакций для рендера reaction-bar.
     *
     * @param state состояние устройства
     * @param reactions список реакций сообщения
     * @return список summaries в порядке первого появления emoji
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
     * Собирает итоговую модель одного emoji из уже сгруппированных реакций.
     *
     * @param state состояние устройства
     * @param emoji emoji группы
     * @param groupedReactions все реакции с этим emoji
     * @param senderNameCache локальный cache разрешённых имён авторов
     * @return summary для одного reaction-chip
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
     * Разрешает имя автора реакции и кэширует результат по nodeId/senderName.
     *
     * @param state состояние устройства
     * @param reaction реакция
     * @param senderNameCache cache имён внутри одной операции агрегации
     * @return отображаемое имя автора реакции
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
     * Отфильтровывает технические/битые реакции без emoji.
     *
     * @param reaction реакция
     * @return {@code true}, если emoji можно отрисовать
     */
    private static boolean hasEmoji(MessageReaction reaction) {
        return reaction.getEmoji() != null && !reaction.getEmoji().isBlank();
    }
}
