package com.meshtastic.client.components.chat;

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
 * Общие helper-методы для отображения имён нод и аватаров в чате.
 *
 * <p>Класс отделяет правила выбора отображаемого имени от JavaFX-рендеринга,
 * чтобы фабрика пузырей и другие чат-компоненты не дублировали одинаковые fallback-цепочки.
 */
final class ChatNodeDisplayHelper {

    private static final String DEFAULT_AVATAR_COLOR = "#5B8DEF";
    private static final String OUTGOING_AVATAR_COLOR = "#1EA97C";
    private static final String SELF_AVATAR_TEXT = "Я";
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
     * Готовые данные для построения аватара сообщения.
     *
     * @param text отображаемый текст внутри аватара
     * @param color css-цвет фона аватара
     */
    record AvatarDescriptor(String text, String color) {
        AvatarDescriptor {
            text = normalizeAvatarText(text);
            color = isBlank(color) ? DEFAULT_AVATAR_COLOR : color;
        }
    }

    /**
     * Подготовленные данные отправителя для входящего сообщения.
     *
     * @param senderName имя над пузырём сообщения
     * @param avatar     текст и цвет аватара
     */
    record IncomingMessagePresentation(String senderName, AvatarDescriptor avatar) {}

    /**
     * Разрешает имя ноды по её номеру.
     *
     * @param state состояние устройства
     * @param nodeNum номер ноды
     * @return {@code longName}, если он известен, иначе {@code !hex}
     */
    static String resolveNodeName(DeviceState state, int nodeNum) {
        NodeData node = NodeUtils.resolveNode(state, nodeNum);
        return firstNonBlank(
                node != null ? node.getLongName() : null,
                "!" + String.format("%08x", nodeNum)
        );
    }

    /**
     * Разрешает имя отправителя для reply-preview в инпуте.
     *
     * @param state состояние устройства
     * @param msg сообщение
     * @return {@code "Вы"} для исходящих сообщений, иначе имя отправителя с fallback-цепочкой
     */
    static String resolveReplySenderName(DeviceState state, MeshMessage msg) {
        return Optional.ofNullable(msg)
                .map(message -> message.isOutgoing() ? "Вы" : resolveIncomingSenderName(state, message))
                .orElse("");
    }

    /**
     * Разрешает все данные отправителя, нужные для рендера входящего пузыря.
     *
     * @param state состояние устройства
     * @param msg входящее сообщение
     * @return имя отправителя и descriptor аватара
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
     * Разрешает данные аватара для исходящих сообщений.
     *
     * @param state состояние устройства
     * @return descriptor аватара локального пользователя
     */
    static AvatarDescriptor resolveOutgoingAvatar(DeviceState state) {
        NodeData myNode = state == null ? null : state.getNodeDb().get(state.getMyNodeNum());
        return new AvatarDescriptor(
                firstNonBlank(myNode != null ? myNode.getShortName() : null, SELF_AVATAR_TEXT),
                OUTGOING_AVATAR_COLOR
        );
    }

    /**
     * Разрешает имя автора реакции с fallback на short name и node id.
     *
     * @param state состояние устройства
     * @param reaction реакция
     * @return отображаемое имя автора реакции
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
     * Ищет ноду для открытия деталей по клику на аватар.
     *
     * <p>Если нода ещё не была загружена в память, метод создаёт bare-node из {@code nodeId}
     * и пытается обогатить её кэшем.
     *
     * @param state состояние устройства
     * @param nodeId идентификатор ноды
     * @return найденная или созданная нода, либо {@code null}, если разрешить её нельзя
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
     * Разрешает display name входящего сообщения без дублирования fallback-цепочки в UI.
     *
     * @param state состояние устройства
     * @param msg входящее сообщение
     * @return longName, сохранённое senderName или nodeId
     */
    private static String resolveIncomingSenderName(DeviceState state, MeshMessage msg) {
        NodeData senderNode = resolveNode(state, msg.getFromNodeId());
        return resolveMessageDisplayName(senderNode, msg.getSenderName(), msg.getFromNodeId());
    }

    /**
     * Выбирает первое доступное имя сообщения в порядке приоритета.
     *
     * @param senderNode найденная нода отправителя
     * @param senderName имя, сохранённое вместе с сообщением
     * @param nodeId идентификатор ноды
     * @return строка для показа в UI
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
     * Формирует текст и цвет аватара входящего сообщения.
     *
     * @param senderNode найденная нода отправителя
     * @param nodeId идентификатор ноды
     * @return descriptor аватара с shortName или хвостом nodeId
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
     * Ищет ноду по {@code nodeId} через общий resolver и сразу отсеивает пустые значения.
     *
     * @param state состояние устройства
     * @param nodeId идентификатор ноды
     * @return найденная нода или {@code null}
     */
    private static NodeData resolveNode(DeviceState state, String nodeId) {
        return Optional.ofNullable(nodeId)
                .filter(Predicate.not(String::isBlank))
                .map(id -> NodeUtils.resolveNode(state, id))
                .orElse(null);
    }

    /**
     * Вычисляет стабильный цвет аватара по hash {@code nodeId}.
     *
     * @param nodeId идентификатор ноды
     * @return hex-цвет из фиксированной палитры
     */
    private static String avatarColor(String nodeId) {
        return isBlank(nodeId)
                ? DEFAULT_AVATAR_COLOR
                : AVATAR_COLORS.get(Math.floorMod(nodeId.hashCode(), AVATAR_COLORS.size()));
    }

    /**
     * Берёт последние символы {@code nodeId}, чтобы получить компактный fallback для аватара.
     *
     * @param nodeId идентификатор ноды
     * @return хвост nodeId длиной до {@value #MAX_AVATAR_TEXT_LENGTH} символов
     */
    private static String nodeIdTail(String nodeId) {
        return isBlank(nodeId)
                ? null
                : UnicodeTextUtils.suffixByCodePoints(nodeId, MAX_AVATAR_TEXT_LENGTH);
    }

    /**
     * Нормализует текст аватара: uppercase и ограничение длины.
     *
     * @param value исходный текст
     * @return безопасный текст для маленького круглого аватара
     */
    private static String normalizeAvatarText(String value) {
        String normalized = UnicodeTextUtils.sanitize(firstNonBlank(value, UNKNOWN_AVATAR_TEXT))
                .toUpperCase(Locale.ROOT);
        return UnicodeTextUtils.prefixByCodePoints(normalized, MAX_AVATAR_TEXT_LENGTH);
    }

    /**
     * Возвращает первый непустой кандидат из списка fallback-значений.
     *
     * @param values кандидаты в порядке приоритета
     * @return первое непустое значение или пустая строка
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
     * Проверяет, что строка отсутствует или состоит только из пробелов.
     *
     * @param value проверяемое значение
     * @return {@code true}, если строка blank
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Пытается создать bare-node по hex-части nodeId для случаев, когда sender ещё не прогружен.
     *
     * @param state состояние устройства
     * @param nodeId идентификатор ноды
     * @return bare-node после обогащения кэшем или {@code null}, если nodeId некорректен
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
