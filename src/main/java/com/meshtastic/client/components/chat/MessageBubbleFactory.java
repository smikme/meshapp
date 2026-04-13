package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.components.NodeDetailPanel;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.NodeUtils;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Popup;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Фабрика пузырей сообщений: входящие, исходящие и системные.
 *
 * <p>Класс отвечает только за JavaFX-рендеринг и делегирует выбор имён,
 * аватаров и агрегирование реакций специализированным helper-классам.
 */
public class MessageBubbleFactory {

    private static final int ZERO_VALUE = 0;
    private static final int BASE_VERTICAL_SPACING = 2;
    private static final int MESSAGE_ROW_SPACING = 6;
    private static final int FOOTER_SPACING = 8;
    private static final int REACTION_BAR_SPACING = 6;
    private static final int REACTION_CHIP_SPACING = 4;
    private static final int REACTION_POPUP_SPACING = 4;
    private static final int META_INDICATOR_SPACING = 2;
    private static final int DOUBLE_CLICK_COUNT = 2;
    private static final int REACTION_COUNT_DISPLAY_THRESHOLD = 1;
    private static final int META_PRESENT_THRESHOLD = 0;
    private static final int POPUP_VERTICAL_OFFSET = 6;
    private static final int RETRY_ICON_SIZE = 12;
    private static final int RETRY_ACTION_GAP = 4;
    private static final double MESSAGE_TEXT_EMOJI_SIZE = 18;
    private static final double QUOTE_TEXT_EMOJI_SIZE = 14;
    private static final double REACTION_BUTTON_EMOJI_SIZE = 14;
    private static final double REACTION_POPUP_EMOJI_SIZE = 18;
    private static final double REACTION_CHIP_EMOJI_SIZE = 14;
    private static final double META_INDICATOR_EMOJI_SIZE = 12;
    private static final double BOT_AVATAR_EMOJI_SIZE = 20;
    private static final double DEFAULT_BUBBLE_WIDTH_RATIO = 0.75;
    private static final double REACTION_BUBBLE_WIDTH_RATIO = 0.90;
    private static final double SYSTEM_BUBBLE_WIDTH_RATIO = 0.85;
    private static final double SMALL_AVATAR_SIZE = 28;
    private static final double SMALL_AVATAR_RADIUS = SMALL_AVATAR_SIZE / 2.0;
    private static final String AVATAR_LABEL_STYLE = "-fx-text-fill: white; -fx-padding: 0;";
    private static final String LIGHT_THEME_STYLE_CLASS = "light-theme";
    private static final String REACTION_UNAVAILABLE_TOOLTIP = "Реакция недоступна: у сообщения нет packet id";
    private static final String RETRY_TOOLTIP = "Повторить отправку";
    private static final String RETRY_ICON_PATH = "/icons/refresh.svg";
    private static final List<List<String>> REACTION_EMOJI_ROWS = List.of(
            List.of("⭐", "✅", "👍", "👋", "💯", "🔥", "🤝", "😁", "😂", "🤣", "😀"),
            List.of("👌", "❎", "👎", "🤔", "👀", "👽", "🙏", "💪", "🤡", "😄", "🫡"),
            List.of("😆", "💩", "😱", "🐰", "🐇", "🔆", "📡", "❤️", "🚀", "🐭", "🥶"),
            List.of("0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟")
    );

    /**
     * Колбэки для действий из контекстного меню пузырей.
     */
    public interface BubbleActions {

        /** Начать ответ на сообщение. */
        void startReply(MeshMessage msg);

        /** Запросить traceroute до ноды-отправителя. */
        void requestTraceroute(MeshMessage msg);

        /** Запросить информацию о ноде-отправителе. */
        void requestNodeInfo(MeshMessage msg);

        /** Отправить emoji-реакцию на сообщение. */
        void sendReaction(MeshMessage msg, String emoji);

        /** Подтвердить и удалить сообщение. */
        void confirmDeleteMessage(MeshMessage msg, HBox bubbleRow);

        /** Повторно отправить недоставленное сообщение. */
        boolean retryMessage(MeshMessage msg);
    }

    private DeviceState state;
    private final ReadOnlyDoubleProperty containerWidthProp;
    private final BubbleActions actions;
    private final Map<Integer, Label> pendingStatusLabels;
    private TracerouteView tracerouteView;
    private Popup openReactionPopup;

    /**
     * @param state текущее состояние устройства (может быть {@code null})
     * @param containerWidthProp свойство ширины messageContainer для maxWidth binding
     * @param actions колбэки действий (ответ, traceroute, удаление)
     * @param pendingStatusLabels карта packetId -&gt; Label для трекинга статусов доставки
     */
    public MessageBubbleFactory(DeviceState state,
                                ReadOnlyDoubleProperty containerWidthProp,
                                BubbleActions actions,
                                Map<Integer, Label> pendingStatusLabels) {
        this.state = state;
        this.containerWidthProp = containerWidthProp;
        this.actions = actions;
        this.pendingStatusLabels = pendingStatusLabels;
    }

    /** Обновить DeviceState (при rebind подключения). */
    public void setState(DeviceState state) {
        this.state = state;
    }

    /** Установить TracerouteView для визуализации traceroute-пузырей. */
    public void setTracerouteView(TracerouteView tracerouteView) {
        this.tracerouteView = tracerouteView;
    }

    /** Закрыть открытый picker реакций, если он есть. */
    public void hideOpenReactionPopup() {
        if (openReactionPopup == null) {
            return;
        }
        openReactionPopup.hide();
        openReactionPopup = null;
    }

    /**
     * Строит bubble по типу сообщения.
     *
     * @param msg сообщение для рендера
     * @return готовый JavaFX-узел пузыря
     */
    public HBox build(MeshMessage msg) {
        if (msg.isSystemMessage()) {
            return buildSystemBubble(msg);
        }
        if (msg.isOutgoing()) {
            return buildOutgoingBubble(msg);
        }
        return buildIncomingBubble(msg);
    }

    /**
     * Обновляет иконку статуса доставки на существующем label.
     *
     * @param label целевой label
     * @param status новый статус доставки
     */
    public static void updateStatusLabel(Label label,
                                         MeshMessage.DeliveryStatus status) {
        if (status == null) {
            return;
        }

        label.setText(null);
        label.setGraphic(null);
        label.setContentDisplay(ContentDisplay.TEXT_ONLY);
        label.getStyleClass().remove("chat-bubble-status-failed");
        switch (status) {
            case SENDING -> label.setText("⏳");
            case DELIVERED -> label.setText("✓");
            case FAILED -> label.setText("✗");
        }
        if (status == MeshMessage.DeliveryStatus.FAILED) {
            label.getStyleClass().add("chat-bubble-status-failed");
        }
    }

    /**
     * Обновляет визуал и интерактивность статус-контрола исходящего сообщения.
     *
     * @param label контрол статуса
     * @param msg сообщение, связанное с этим контролом
     */
    public void refreshStatusLabel(Label label, MeshMessage msg) {
        if (label == null || msg == null || msg.getStatus() == null) {
            return;
        }
        updateStatusLabel(label, msg.getStatus());
        configureStatusLabelInteraction(label, msg);
        if (msg.getStatus() == MeshMessage.DeliveryStatus.SENDING && msg.getPacketId() != ZERO_VALUE) {
            pendingStatusLabels.put(msg.getPacketId(), label);
        }
    }

    /**
     * Собирает входящий bubble: аватар, имя отправителя, текст, реакции и meta-блок.
     *
     * @param msg входящее сообщение
     * @return готовая строка чата для входящего сообщения
     */
    private HBox buildIncomingBubble(MeshMessage msg) {
        HBox reactionBar = buildReactionsBar(msg);
        ChatNodeDisplayHelper.IncomingMessagePresentation senderPresentation =
                ChatNodeDisplayHelper.resolveIncomingMessagePresentation(state, msg);

        StackPane avatar = buildAvatar(senderPresentation.avatar());
        configureIncomingAvatar(avatar, msg);

        VBox content = createMessageContent("chat-bubble-incoming", reactionBar != null, DEFAULT_BUBBLE_WIDTH_RATIO);
        Optional.of(msg)
                .filter(this::isMentioningMe)
                .ifPresent(ignored -> content.getStyleClass().add("chat-bubble-mentioned"));
        content.getChildren().addAll(nodes(
                createSenderNameLabel(senderPresentation.senderName()),
                createQuoteNode(msg).orElse(null),
                createTextNode(msg),
                buildIncomingFooter(msg, reactionBar)
        ));

        HBox row = createMessageRow(Pos.BOTTOM_LEFT, "chat-message-row-incoming", avatar, content);
        attachReplyOnDoubleClick(content, msg);
        attachIncomingContextMenu(content, msg, row);
        return row;
    }

    /**
     * Собирает исходящий bubble с правым выравниванием и индикатором статуса доставки.
     *
     * @param msg исходящее сообщение
     * @return готовая строка чата для исходящего сообщения
     */
    private HBox buildOutgoingBubble(MeshMessage msg) {
        HBox reactionBar = buildReactionsBar(msg);
        VBox content = createMessageContent("chat-bubble-outgoing", reactionBar != null, DEFAULT_BUBBLE_WIDTH_RATIO);
        content.getChildren().addAll(nodes(
                createQuoteNode(msg).orElse(null),
                createTextNode(msg),
                buildOutgoingFooter(msg, reactionBar)
        ));

        StackPane avatar = buildAvatar(ChatNodeDisplayHelper.resolveOutgoingAvatar(state));
        Region spacer = createFlexibleSpacer();

        HBox row = createMessageRow(Pos.BOTTOM_RIGHT, "chat-message-row-outgoing", spacer, content, avatar);
        attachReplyOnDoubleClick(content, msg);
        attachCopyDeleteMenu(content, msg, row);
        return row;
    }

    /**
     * Рендерит системное сообщение или восстанавливает специальный traceroute bubble из текста.
     *
     * @param msg системное сообщение
     * @return bubble системного сообщения
     */
    private HBox buildSystemBubble(MeshMessage msg) {
        return tryBuildTracerouteBubble(msg).orElseGet(() -> createDefaultSystemBubble(msg));
    }

    private HBox createDefaultSystemBubble(MeshMessage msg) {
        StackPane botAvatar = buildBotAvatar();
        VBox content = new VBox(BASE_VERTICAL_SPACING);
        content.getStyleClass().add("chat-bubble-system");
        content.maxWidthProperty().bind(containerWidthProp.multiply(SYSTEM_BUBBLE_WIDTH_RATIO));
        content.setMinHeight(Region.USE_PREF_SIZE);
        content.getChildren().addAll(nodes(
                createBubbleTextFlow(msg.getText(), MESSAGE_TEXT_EMOJI_SIZE, "chat-bubble-text-node", "chat-bubble-text"),
                createTimeLabel(msg.getTimestamp())
        ));

        HBox row = createMessageRow(Pos.BOTTOM_LEFT, "chat-message-row-system", botAvatar, content);
        attachCopyDeleteMenu(content, msg, row);
        return row;
    }

    /**
     * Проверяет, можно ли вместо обычного текстового system bubble показать traceroute-представление.
     *
     * @param msg системное сообщение
     * @return визуальный traceroute bubble, если сообщение содержит traceroute payload
     */
    private Optional<HBox> tryBuildTracerouteBubble(MeshMessage msg) {
        return Optional.ofNullable(tracerouteView)
                .filter(ignored -> Optional.ofNullable(msg.getText())
                        .filter(text -> text.startsWith(TracerouteView.TRACEROUTE_PREFIX))
                        .isPresent())
                .map(view -> view.tryBuildFromText(msg));
    }

    /**
     * Создаёт компактный аватар бота для системных сообщений.
     *
     * @return avatar pane с emoji бота
     */
    private static StackPane buildBotAvatar() {
        StackPane avatar = createAvatarPane();
        avatar.getChildren().add(createEmojiNode("\uD83E\uDD16", BOT_AVATAR_EMOJI_SIZE));
        return avatar;
    }

    /**
     * Строит круглый текстовый аватар на основе уже подготовленного descriptor.
     *
     * @param descriptor текст и цвет аватара
     * @return готовый JavaFX-узел аватара
     */
    private static StackPane buildAvatar(ChatNodeDisplayHelper.AvatarDescriptor descriptor) {
        StackPane avatar = createAvatarPane();
        avatar.getStyleClass().add("chat-msg-avatar");
        avatar.setStyle("-fx-background-color: " + descriptor.color()
                + "; -fx-background-radius: " + SMALL_AVATAR_RADIUS + ";");

        Label label = new Label(descriptor.text());
        label.setFont(Font.font("Roboto", FontWeight.BOLD, NodeUtils.avatarFontSize(descriptor.text(), (int) SMALL_AVATAR_SIZE)));
        label.setStyle(AVATAR_LABEL_STYLE);
        avatar.getChildren().add(label);
        return avatar;
    }

    /**
     * Создаёт базовый контейнер аватара фиксированного размера.
     *
     * @return пустой центрированный avatar pane
     */
    private static StackPane createAvatarPane() {
        StackPane avatar = new StackPane();
        avatar.setMinSize(SMALL_AVATAR_SIZE, SMALL_AVATAR_SIZE);
        avatar.setMaxSize(SMALL_AVATAR_SIZE, SMALL_AVATAR_SIZE);
        avatar.setAlignment(Pos.CENTER);
        return avatar;
    }

    /**
     * Назначает обработчик клика по входящему аватару для открытия карточки ноды.
     *
     * @param avatar avatar pane
     * @param msg входящее сообщение
     */
    private void configureIncomingAvatar(StackPane avatar, MeshMessage msg) {
        avatar.setCursor(Cursor.HAND);
        avatar.setOnMouseClicked(e -> {
            showNodeDetails(msg.getFromNodeId());
            e.consume();
        });
    }

    /**
     * Открывает панель ноды, если отправителя удалось разрешить в {@link NodeData}.
     *
     * @param nodeId идентификатор ноды отправителя
     */
    private void showNodeDetails(String nodeId) {
        Optional.ofNullable(state)
                .map(currentState -> ChatNodeDisplayHelper.resolveNodeForDetails(currentState, nodeId))
                .ifPresent(node -> NodeDetailPanel.showForNode(state, node));
    }

    /**
     * Создаёт общий контейнер bubble-контента и сразу биндинг максимальной ширины.
     *
     * @param styleClass css-класс конкретного типа bubble
     * @param hasReactions есть ли у bubble панель реакций
     * @param defaultWidthRatio стандартная доля ширины контейнера
     * @return VBox-контент bubble
     */
    private VBox createMessageContent(String styleClass,
                                      boolean hasReactions,
                                      double defaultWidthRatio) {
        VBox content = new VBox(BASE_VERTICAL_SPACING);
        content.getStyleClass().add(styleClass);
        bindBubbleWidth(content, hasReactions, defaultWidthRatio);
        content.setMinHeight(Region.USE_PREF_SIZE);
        return content;
    }

    /**
     * Создаёт строку чата с общими отступами и выравниванием.
     *
     * @param alignment выравнивание по горизонтали
     * @param styleClass css-класс строки
     * @param children дочерние узлы строки
     * @return готовый row container
     */
    private static HBox createMessageRow(Pos alignment, String styleClass, Node... children) {
        HBox row = new HBox(MESSAGE_ROW_SPACING, children);
        row.setAlignment(alignment);
        row.setMinHeight(Region.USE_PREF_SIZE);
        row.getStyleClass().add(styleClass);
        return row;
    }

    /**
     * Создаёт эластичный spacer для раздвигания footer/meta блоков.
     *
     * @return region с grow priority
     */
    private static Region createFlexibleSpacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /**
     * Создаёт label с именем отправителя над входящим сообщением.
     *
     * @param senderName отображаемое имя отправителя
     * @return стилизованный label имени
     */
    private static Label createSenderNameLabel(String senderName) {
        Label nameLabel = new Label(senderName);
        nameLabel.getStyleClass().add("chat-bubble-sender");
        return nameLabel;
    }

    /**
     * Создаёт общий footer bubble с заданным направлением выравнивания.
     *
     * @param alignment выравнивание footer
     * @return HBox footer-контейнер
     */
    private static HBox createFooter(Pos alignment) {
        HBox footer = new HBox(FOOTER_SPACING);
        footer.setAlignment(alignment);
        footer.getStyleClass().add("chat-bubble-footer");
        return footer;
    }

    /**
     * Формирует footer входящего сообщения: кнопка реакции, bar реакций и meta-данные.
     *
     * @param msg входящее сообщение
     * @param reactionBar готовая панель реакций или {@code null}
     * @return footer входящего bubble
     */
    private HBox buildIncomingFooter(MeshMessage msg, HBox reactionBar) {
        HBox footer = createFooter(Pos.CENTER_LEFT);
        footer.getChildren().addAll(nodes(
                buildReactionButton(msg),
                reactionBar,
                createFlexibleSpacer(),
                buildIncomingMeta(msg)
        ));
        return footer;
    }

    /**
     * Формирует footer исходящего сообщения: реакции слева и статус/время справа.
     *
     * @param msg исходящее сообщение
     * @param reactionBar готовая панель реакций или {@code null}
     * @return footer исходящего bubble
     */
    private HBox buildOutgoingFooter(MeshMessage msg, HBox reactionBar) {
        HBox footer = createFooter(Pos.CENTER_RIGHT);
        footer.getChildren().addAll(nodes(
                createFlexibleSpacer(),
                reactionBar,
                buildOutgoingMeta(msg)
        ));
        return footer;
    }

    /**
     * Вешает double-click обработчик для быстрого ответа на сообщение.
     *
     * @param content bubble-контент
     * @param msg сообщение, на которое будет создан reply
     */
    private void attachReplyOnDoubleClick(VBox content, MeshMessage msg) {
        content.setOnMouseClicked(e -> {
            if (e.getClickCount() != DOUBLE_CLICK_COUNT) {
                return;
            }
            actions.startReply(msg);
            e.consume();
        });
    }

    /**
     * Определяет, нужно ли подсветить входящее сообщение как mention локального пользователя.
     *
     * @param msg входящее сообщение
     * @return {@code true}, если текст содержит имя локальной ноды или это reply на исходящее
     */
    private boolean isMentioningMe(MeshMessage msg) {
        return Optional.ofNullable(state)
                .map(ignored -> messageMentionsLocalUser(msg) || isReplyToOutgoingMessage(msg))
                .orElse(false);
    }

    /**
     * Проверяет текстовое упоминание по longName и shortName локальной ноды.
     *
     * @param msg входящее сообщение
     * @return {@code true}, если в тексте найдено имя локальной ноды
     */
    private boolean messageMentionsLocalUser(MeshMessage msg) {
        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        return Optional.ofNullable(myNode)
                .map(node -> {
                    String normalizedText = normalizeText(msg.getText());
                    return Stream.of(node.getLongName(), node.getShortName())
                            .anyMatch(candidate -> containsNormalizedText(normalizedText, candidate));
                })
                .orElse(false);
    }

    /**
     * Проверяет, является ли сообщение ответом на одно из наших исходящих сообщений.
     *
     * @param msg входящее сообщение
     * @return {@code true}, если reply target найден в БД и он исходящий
     */
    private boolean isReplyToOutgoingMessage(MeshMessage msg) {
        return Optional.of(msg.getReplyId())
                .filter(replyId -> replyId != ZERO_VALUE)
                .map(replyId -> MessageDbService.getInstance().findByPacketId(replyId))
                .map(MeshMessage::isOutgoing)
                .orElse(false);
    }

    /**
     * Нормализует текст для регистронезависимых поисков mention-подстрок.
     *
     * @param value исходный текст
     * @return lower-case строка или пустая строка
     */
    private static String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /**
     * Проверяет вхождение уже нормализованного имени в уже нормализованный текст.
     *
     * @param normalizedText текст сообщения в lower-case
     * @param candidate имя ноды в исходном виде
     * @return {@code true}, если имя найдено как подстрока
     */
    private static boolean containsNormalizedText(String normalizedText, String candidate) {
        return candidate != null
                && !candidate.isBlank()
                && normalizedText.contains(candidate.toLowerCase(Locale.ROOT));
    }

    /**
     * Добавляет quote preview только для reply-сообщений.
     *
     * @param msg сообщение
     */
    private Optional<Node> createQuoteNode(MeshMessage msg) {
        return Optional.ofNullable(msg.getReplyText())
                .filter(replyText -> !replyText.isEmpty())
                .map(replyText -> createBubbleTextFlow(
                        replyText,
                        QUOTE_TEXT_EMOJI_SIZE,
                        "chat-bubble-quote-node",
                        "chat-bubble-quote"
                ));
    }

    /**
     * Добавляет основной текст сообщения в bubble.
     *
     * @param msg сообщение
     */
    private Node createTextNode(MeshMessage msg) {
        return createBubbleTextFlow(msg.getText(), MESSAGE_TEXT_EMOJI_SIZE, "chat-bubble-text-node", "chat-bubble-text");
    }

    /**
     * Создаёт единый {@link EmojiTextFlow} для текста сообщения и цитаты.
     *
     * @param text исходный текст
     * @param emojiSize размер emoji
     * @param textStyleClass css-класс текстовых нод внутри flow
     * @param styleClass css-класс самого flow
     * @return настроенный {@link EmojiTextFlow}
     */
    private static EmojiTextFlow createBubbleTextFlow(String text,
                                                      double emojiSize,
                                                      String textStyleClass,
                                                      String styleClass) {
        EmojiTextFlow textFlow = new EmojiTextFlow(
                text == null ? "" : text,
                TypographyManager.scaleChat(emojiSize));
        textFlow.setTextStyleClass(textStyleClass);
        textFlow.getStyleClass().add(styleClass);
        textFlow.setMinHeight(Region.USE_PREF_SIZE);
        return textFlow;
    }

    /**
     * Биндит ширину bubble с поправкой на наличие reaction bar.
     *
     * @param content bubble-контент
     * @param hasReactions есть ли бар реакций
     * @param defaultWidthRatio базовая доля ширины контейнера
     */
    private void bindBubbleWidth(VBox content, boolean hasReactions, double defaultWidthRatio) {
        double widthRatio = hasReactions ? REACTION_BUBBLE_WIDTH_RATIO : defaultWidthRatio;
        content.maxWidthProperty().bind(containerWidthProp.multiply(widthRatio));
    }

    /**
     * Строит панель реакций из уже агрегированных reaction summary.
     *
     * @param msg сообщение
     * @return bar реакций или {@code null}, если отображать нечего
     */
    private HBox buildReactionsBar(MeshMessage msg) {
        List<ChatReactionHelper.ReactionSummary> reactionSummaries =
                ChatReactionHelper.summarize(state, msg.getReactions());
        return Optional.of(reactionSummaries)
                .filter(Predicate.not(List::isEmpty))
                .map(summaries -> createReactionBar(msg, summaries))
                .orElse(null);
    }

    private HBox createReactionBar(MeshMessage msg,
                                   List<ChatReactionHelper.ReactionSummary> reactionSummaries) {
        boolean reactionAvailable = isReactionAvailable(msg);
        HBox reactionBar = new HBox(REACTION_BAR_SPACING);
        reactionBar.setAlignment(Pos.CENTER_LEFT);
        reactionBar.getStyleClass().add("chat-bubble-reactions");
        reactionBar.getChildren().addAll(reactionSummaries.stream()
                .map(summary -> buildReactionChip(msg, reactionAvailable, summary))
                .toList());
        return reactionBar;
    }

    /**
     * Создаёт один reaction-chip: emoji, count, tooltip и click-state.
     *
     * @param msg сообщение-владелец чипа
     * @param reactionAvailable можно ли отправлять реакцию повторным кликом
     * @param summary агрегированные данные реакции
     * @return один UI-чип реакции
     */
    private HBox buildReactionChip(MeshMessage msg,
                                   boolean reactionAvailable,
                                   ChatReactionHelper.ReactionSummary summary) {
        HBox chip = new HBox(REACTION_CHIP_SPACING);
        chip.setAlignment(Pos.CENTER);
        chip.getStyleClass().add("chat-reaction-chip");

        applyReactionChipState(chip, msg, reactionAvailable, summary);
        chip.getChildren().addAll(nodes(
                createEmojiNode(summary.emoji(), REACTION_CHIP_EMOJI_SIZE),
                createReactionCountLabel(summary.count()).orElse(null)
        ));
        installTooltip(chip, summary.tooltipText());
        return chip;
    }

    private Optional<Node> createReactionCountLabel(int count) {
        return Optional.of(count)
                .filter(value -> value > REACTION_COUNT_DISPLAY_THRESHOLD)
                .map(String::valueOf)
                .map(Label::new)
                .map(label -> {
                    label.getStyleClass().add("chat-reaction-chip-count");
                    return (Node) label;
                });
    }

    /**
     * Назначает визуальное состояние чипа: own-style или click handler для повторной реакции.
     *
     * @param chip ui-чип реакции
     * @param msg сообщение-владелец
     * @param reactionAvailable доступна ли отправка реакции
     * @param summary агрегированные данные реакции
     */
    private void applyReactionChipState(HBox chip,
                                        MeshMessage msg,
                                        boolean reactionAvailable,
                                        ChatReactionHelper.ReactionSummary summary) {
        if (summary.own()) {
            chip.getStyleClass().add("chat-reaction-chip-own");
            return;
        }
        if (!reactionAvailable) {
            return;
        }

        chip.getStyleClass().add("chat-reaction-chip-clickable");
        chip.setCursor(Cursor.HAND);
        chip.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            actions.sendReaction(msg, summary.emoji());
            e.consume();
        });
    }

    /**
     * Устанавливает tooltip только если в нём есть полезный текст.
     *
     * @param node узел, на который ставится tooltip
     * @param tooltipText текст tooltip
     */
    private static void installTooltip(Node node, String tooltipText) {
        Optional.ofNullable(tooltipText)
                .filter(text -> !text.isBlank())
                .map(Tooltip::new)
                .ifPresent(tooltip -> Tooltip.install(node, tooltip));
    }

    /**
     * Собирает meta-блок входящего сообщения: hops/сигнал и время.
     *
     * @param msg входящее сообщение
     * @return meta container
     */
    private HBox buildIncomingMeta(MeshMessage msg) {
        HBox meta = createMetaBox();
        meta.getChildren().addAll(nodes(
                createRoutingMetaNode(msg).orElse(null),
                createTimeLabel(msg.getTimestamp())
        ));
        return meta;
    }

    /**
     * Собирает meta-блок исходящего сообщения: время и индикатор доставки.
     *
     * @param msg исходящее сообщение
     * @return meta container
     */
    private HBox buildOutgoingMeta(MeshMessage msg) {
        HBox meta = createMetaBox();
        meta.setAlignment(Pos.CENTER_RIGHT);
        meta.getChildren().addAll(nodes(
                createTimeLabel(msg.getTimestamp()),
                createStatusLabel(msg).orElse(null)
        ));
        return meta;
    }

    /**
     * Создаёт общий контейнер meta-информации bubble.
     *
     * @return HBox для времени, статуса и route metrics
     */
    private static HBox createMetaBox() {
        HBox meta = new HBox(MESSAGE_ROW_SPACING);
        meta.setAlignment(Pos.CENTER_RIGHT);
        meta.getStyleClass().add("chat-bubble-meta");
        return meta;
    }

    /**
     * Подбирает вторичный meta-индикатор: либо hops, либо сигнал, либо ничего.
     *
     * @param msg входящее сообщение
     * @return узел meta-индикатора, если данные доступны
     */
    private Optional<HBox> createRoutingMetaNode(MeshMessage msg) {
        int hops = msg.getHopsTraveled();
        return hops > META_PRESENT_THRESHOLD
                ? Optional.of(createMetaIndicator("\uD83D\uDC07", String.valueOf(hops)))
                : msg.getRxRssi() == ZERO_VALUE && msg.getRxSnr() == ZERO_VALUE
                ? Optional.empty()
                : Optional.of(createMetaIndicator("\uD83D\uDCF6", formatSignalMetrics(msg)));
    }

    /**
     * Создаёт компактный meta-элемент из emoji и текстового значения.
     *
     * @param emoji emoji-иконка
     * @param value текст индикатора
     * @return HBox с иконкой и значением
     */
    private static HBox createMetaIndicator(String emoji, String value) {
        HBox indicator = new HBox(META_INDICATOR_SPACING);
        indicator.setAlignment(Pos.CENTER);
        indicator.getStyleClass().add("chat-bubble-hops");
        indicator.getChildren().addAll(createEmojiNode(emoji, META_INDICATOR_EMOJI_SIZE), new Label(value));
        return indicator;
    }

    /**
     * Форматирует RSSI/SNR в одну короткую строку для meta-блока.
     *
     * @param msg входящее сообщение
     * @return строка вида {@code -90dBm/12.4dB}
     */
    private static String formatSignalMetrics(MeshMessage msg) {
        String snrStr = msg.getRxSnr() == (int) msg.getRxSnr()
                ? String.valueOf((int) msg.getRxSnr())
                : String.format("%.1f", msg.getRxSnr());
        return msg.getRxRssi() + "dB/" + snrStr + "dB";
    }

    /**
     * Создаёт кнопку выбора реакции и привязывает popup только когда сообщение имеет packet id.
     *
     * @param msg сообщение
     * @return кнопка реакции
     */
    private Button buildReactionButton(MeshMessage msg) {
        return isReactionAvailable(msg)
                ? createEnabledReactionButton(msg)
                : createDisabledReactionButton();
    }

    private Button createEnabledReactionButton(MeshMessage msg) {
        Button reactionButton = createReactionButton();
        Popup reactionPopup = buildReactionPopup(msg);
        reactionButton.setOnAction(e -> {
            toggleReactionPopup(reactionButton, reactionPopup);
            e.consume();
        });
        return reactionButton;
    }

    private Button createDisabledReactionButton() {
        Button reactionButton = createReactionButton();
        disableReactionButton(reactionButton);
        reactionButton.setOnAction(e -> e.consume());
        return reactionButton;
    }

    /**
     * Создаёт базовую кнопку реакции без состояния enabled/disabled.
     *
     * @return кнопка с emoji-иконкой
     */
    private static Button createReactionButton() {
        Button reactionButton = new Button();
        reactionButton.getStyleClass().add("chat-reaction-btn");
        reactionButton.setGraphic(createEmojiNode("😀", REACTION_BUTTON_EMOJI_SIZE));
        reactionButton.setFocusTraversable(false);
        return reactionButton;
    }

    /**
     * Переводит кнопку реакции в disabled-состояние с поясняющим tooltip.
     *
     * @param reactionButton кнопка реакции
     */
    private static void disableReactionButton(Button reactionButton) {
        reactionButton.getStyleClass().add("chat-reaction-btn-disabled");
        reactionButton.setCursor(Cursor.DEFAULT);
        reactionButton.setTooltip(new Tooltip(REACTION_UNAVAILABLE_TOOLTIP));
    }

    /**
     * Проверяет, можно ли отправить реакцию на сообщение.
     *
     * @param msg сообщение
     * @return {@code true}, если есть packet id
     */
    private static boolean isReactionAvailable(MeshMessage msg) {
        return msg.getPacketId() != ZERO_VALUE;
    }

    /**
     * Переключает popup реакций по клику на кнопку.
     *
     * @param anchor кнопка-источник
     * @param popup popup выбора реакции
     */
    private void toggleReactionPopup(Button anchor, Popup popup) {
        Runnable action = popup.isShowing() ? popup::hide : () -> showReactionPopup(anchor, popup);
        action.run();
    }

    /**
     * Создаёт popup-пикер реакций с фиксированными строками emoji.
     *
     * @param msg сообщение, к которому относится popup
     * @return настроенный popup выбора реакции
     */
    private Popup buildReactionPopup(MeshMessage msg) {
        VBox picker = new VBox(REACTION_POPUP_SPACING);
        picker.setAlignment(Pos.CENTER_LEFT);
        picker.getStyleClass().add("chat-reaction-picker");

        StackPane popupRoot = new StackPane(picker);
        popupRoot.getStyleClass().add("chat-reaction-popup");

        Popup popup = new Popup();
        popup.setAutoFix(true);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.getContent().add(popupRoot);
        popup.setOnHidden(e -> clearOpenReactionPopup(popup));

        REACTION_EMOJI_ROWS.stream()
                .map(emojiRow -> buildReactionPopupRow(msg, popup, emojiRow))
                .forEach(picker.getChildren()::add);

        return popup;
    }

    /**
     * Строит одну строку popup-пикера из набора emoji.
     *
     * @param msg сообщение
     * @param popup popup, который надо закрыть после выбора
     * @param emojiRow строка emoji
     * @return HBox одной строки picker-а
     */
    private HBox buildReactionPopupRow(MeshMessage msg, Popup popup, List<String> emojiRow) {
        HBox row = new HBox(REACTION_POPUP_SPACING);
        row.setAlignment(Pos.CENTER_LEFT);
        emojiRow.stream()
                .map(emoji -> buildReactionPopupButton(msg, popup, emoji))
                .forEach(row.getChildren()::add);
        return row;
    }

    /**
     * Создаёт кнопку отдельной emoji-реакции внутри popup.
     *
     * @param msg сообщение
     * @param popup popup для скрытия после выбора
     * @param emoji выбранная emoji
     * @return кнопка picker-а
     */
    private Button buildReactionPopupButton(MeshMessage msg, Popup popup, String emoji) {
        Button emojiButton = new Button();
        emojiButton.getStyleClass().add("chat-reaction-picker-btn");
        emojiButton.setGraphic(createEmojiNode(emoji, REACTION_POPUP_EMOJI_SIZE));
        emojiButton.setFocusTraversable(false);
        emojiButton.setOnAction(e -> {
            popup.hide();
            actions.sendReaction(msg, emoji);
            e.consume();
        });
        return emojiButton;
    }

    private void clearOpenReactionPopup(Popup popup) {
        openReactionPopup = openReactionPopup == popup ? null : openReactionPopup;
    }

    /**
     * Показывает popup под кнопкой реакции и синхронизирует тему с текущей сценой.
     *
     * @param anchor кнопка-якорь
     * @param popup popup выбора реакции
     */
    private void showReactionPopup(Button anchor, Popup popup) {
        Optional.ofNullable(openReactionPopup)
                .filter(openPopup -> openPopup != popup)
                .ifPresent(Popup::hide);

        Optional.ofNullable(anchor.localToScreen(anchor.getBoundsInLocal()))
                .ifPresent(bounds -> showReactionPopup(anchor, popup, bounds));
    }

    private void showReactionPopup(Button anchor, Popup popup, Bounds bounds) {
        syncReactionPopupTheme(anchor, popup);
        popup.show(anchor, bounds.getMinX(), bounds.getMaxY() + POPUP_VERTICAL_OFFSET);
        openReactionPopup = popup;
    }

    /**
     * Копирует в popup светлую тему, если корневая сцена сейчас в light-mode.
     *
     * @param anchor кнопка-якорь
     * @param popup popup выбора реакции
     */
    private void syncReactionPopupTheme(Button anchor, Popup popup) {
        popup.getContent().stream()
                .findFirst()
                .ifPresent(popupRoot -> setStyleClassPresence(
                        popupRoot,
                        LIGHT_THEME_STYLE_CLASS,
                        Optional.ofNullable(anchor.getScene())
                                .map(javafx.scene.Scene::getRoot)
                                .filter(Objects::nonNull)
                                .map(sceneRoot -> sceneRoot.getStyleClass().contains(LIGHT_THEME_STYLE_CLASS))
                                .orElse(false)
                ));
    }

    private void setStyleClassPresence(Node node, String styleClass, boolean enabled) {
        if (enabled && !node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
            return;
        }
        if (!enabled) {
            node.getStyleClass().remove(styleClass);
        }
    }

    /**
     * Создаёт emoji-узел с fallback на текст, если PNG-ресурс не найден.
     *
     * @param emoji emoji-символ
     * @param size желаемый размер
     * @return {@link ImageView} или {@link Label}
     */
    private static Node createEmojiNode(String emoji, double size) {
        double scaledSize = TypographyManager.scaleChat(size);
        ImageView image = EmojiImageCache.createImageView(emoji, scaledSize);
        return Optional.ofNullable(image)
                .map(Node.class::cast)
                .orElseGet(() -> createEmojiFallbackLabel(emoji, scaledSize));
    }

    private static Node createEmojiFallbackLabel(String emoji, double size) {
        Label fallback = new Label(emoji);
        fallback.setFont(Font.font(size));
        return fallback;
    }

    /**
     * Создаёт label времени сообщения в принятом для чата формате.
     *
     * @param timestamp epoch seconds сообщения
     * @return label времени
     */
    private static Label createTimeLabel(long timestamp) {
        Label timeLabel = new Label(ChatTimeFormatter.formatMessageTime(timestamp));
        timeLabel.getStyleClass().add("chat-bubble-time");
        return timeLabel;
    }

    /**
     * Создаёт label статуса доставки и регистрирует его для live-обновления pending-ACK.
     *
     * @param msg исходящее сообщение
     * @return label статуса, если у сообщения есть status
     */
    private Optional<Label> createStatusLabel(MeshMessage msg) {
        return Optional.ofNullable(msg.getStatus())
                .map(status -> {
                    Label statusLabel = new Label();
                    statusLabel.getStyleClass().add("chat-bubble-status");
                    statusLabel.setGraphicTextGap(ZERO_VALUE);
                    refreshStatusLabel(statusLabel, msg);
                    return statusLabel;
                });
    }

    private void configureStatusLabelInteraction(Label statusLabel, MeshMessage msg) {
        statusLabel.setGraphic(null);
        statusLabel.setContentDisplay(ContentDisplay.TEXT_ONLY);
        statusLabel.setGraphicTextGap(ZERO_VALUE);

        if (msg.getStatus() != MeshMessage.DeliveryStatus.FAILED || !msg.isOutgoing()) {
            return;
        }

        statusLabel.setGraphic(createRetryAction(statusLabel, msg));
        statusLabel.setContentDisplay(ContentDisplay.RIGHT);
        statusLabel.setGraphicTextGap(RETRY_ACTION_GAP);
    }

    private StackPane createRetryAction(Label statusLabel, MeshMessage msg) {
        StackPane retryAction = new StackPane();
        retryAction.getStyleClass().add("chat-bubble-status-retry-action");
        retryAction.setCursor(Cursor.HAND);

        SVGPath retryIcon = SvgIconLoader.load(RETRY_ICON_PATH, RETRY_ICON_SIZE);
        if (retryIcon != null) {
            retryAction.getChildren().add(retryIcon);
        } else {
            Label fallback = new Label("↻");
            fallback.getStyleClass().add("chat-bubble-status-retry-fallback");
            retryAction.getChildren().add(fallback);
        }

        Tooltip.install(retryAction, new Tooltip(RETRY_TOOLTIP));
        retryAction.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (actions.retryMessage(msg)) {
                refreshStatusLabel(statusLabel, msg);
            }
            event.consume();
        });
        return retryAction;
    }

    /**
     * Навешивает контекстное меню входящего сообщения с reply/trace/info действиями.
     *
     * @param content bubble-контент
     * @param msg сообщение
     * @param row строка чата
     */
    private void attachIncomingContextMenu(VBox content, MeshMessage msg, HBox row) {
        ContextMenu menu = new ContextMenu(
                createMenuItem("Копировать", () -> copyText(msg.getText())),
                createMenuItem("Ответить", () -> actions.startReply(msg)),
                createMenuItem("Trace", () -> actions.requestTraceroute(msg)),
                createMenuItem("Инфо", () -> actions.requestNodeInfo(msg)),
                new SeparatorMenuItem(),
                createMenuItem("Удалить", () -> actions.confirmDeleteMessage(msg, row))
        );
        installContextMenu(content, menu);
    }

    /**
     * Навешивает упрощённое контекстное меню без reply/trace действий.
     *
     * @param content bubble-контент
     * @param msg сообщение
     * @param row строка чата
     */
    private void attachCopyDeleteMenu(VBox content, MeshMessage msg, HBox row) {
        ContextMenu menu = new ContextMenu(
                createMenuItem("Копировать", () -> copyText(msg.getText())),
                new SeparatorMenuItem(),
                createMenuItem("Удалить", () -> actions.confirmDeleteMessage(msg, row))
        );
        installContextMenu(content, menu);
    }

    /**
     * Создаёт элемент контекстного меню и связывает его с runnable-действием.
     *
     * @param title заголовок menu item
     * @param action действие по клику
     * @return настроенный item меню
     */
    private static MenuItem createMenuItem(String title, Runnable action) {
        MenuItem menuItem = new MenuItem(title);
        menuItem.setOnAction(ev -> action.run());
        return menuItem;
    }

    /**
     * Привязывает готовое контекстное меню к bubble-контенту.
     *
     * @param content bubble-контент
     * @param menu меню действий
     */
    private static void installContextMenu(VBox content, ContextMenu menu) {
        content.setOnContextMenuRequested(ev -> {
            menu.show(content, ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });
    }

    /**
     * Копирует текст сообщения в системный clipboard.
     *
     * @param text текст сообщения
     */
    private static void copyText(String text) {
        Optional.ofNullable(text)
                .filter(Predicate.not(String::isEmpty))
                .ifPresent(MessageBubbleFactory::copyToClipboard);
    }

    private static void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static List<Node> nodes(Node... nodes) {
        return Arrays.stream(nodes)
                .filter(Objects::nonNull)
                .toList();
    }
}
