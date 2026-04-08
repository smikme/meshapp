package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.components.NodeDetailPanel;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.utils.NodeUtils;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Popup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Фабрика пузырей сообщений: входящие, исходящие и системные.
 *
 * <p>Не зависит от FormChat напрямую — получает зависимости через конструктор и колбэки.
 */
public class MessageBubbleFactory {

    private static final double DEFAULT_BUBBLE_WIDTH_RATIO = 0.75;
    private static final double REACTION_BUBBLE_WIDTH_RATIO = 0.90;
    private static final double SYSTEM_BUBBLE_WIDTH_RATIO = 0.85;

    private static final String[] AVATAR_COLORS = {
            "#5B8DEF", "#E57C23", "#9B59B6", "#1EA97C",
            "#E74C3C", "#3498DB", "#F39C12", "#1ABC9C"
    };
    private static final String[][] REACTION_EMOJI_ROWS = {
            {"⭐", "✅", "👍", "👋", "💯", "🔥", "🤝", "😁", "😂", "🤣", "😀"},
            {"👌", "❎", "👎", "🤔", "👀", "👽", "🙏", "💪", "🤡", "😄", "🫡"},
            {"😆", "💩", "😱", "🐰", "🐇", "🔆", "📡", "❤️", "🚀", "🐭", "🥶"},
            {"0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟"}
    };

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
    }

    private DeviceState state;
    private final ReadOnlyDoubleProperty containerWidthProp;
    private final BubbleActions actions;
    private final Map<Integer, Label> pendingStatusLabels;
    private TracerouteView tracerouteView;
    private Popup openReactionPopup;

    /**
     * @param state              текущее состояние устройства (может быть {@code null})
     * @param containerWidthProp свойство ширины messageContainer для maxWidth binding
     * @param actions            колбэки действий (ответ, traceroute, удаление)
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
        if (openReactionPopup != null) {
            openReactionPopup.hide();
            openReactionPopup = null;
        }
    }

    /** Построить пузырь сообщения — делегирует к incoming/outgoing/system. */
    public HBox build(MeshMessage msg) {
        if (msg.isSystemMessage()) {
            return buildSystemBubble(msg);
        } else if (msg.isOutgoing()) {
            return buildOutgoingBubble(msg);
        } else {
            return buildIncomingBubble(msg);
        }
    }

    /** Обновить иконку статуса доставки на Label. */
    public static void updateStatusLabel(Label label,
                                         MeshMessage.DeliveryStatus status) {
        if (status == null) {
            return;
        }
        String icon = switch (status) {
            case SENDING -> "\u23F3";
            case DELIVERED -> "\u2713";
            case FAILED -> "\u2717";
        };
        label.setText(icon);
        label.getStyleClass().remove("chat-bubble-status-failed");
        if (status == MeshMessage.DeliveryStatus.FAILED) {
            label.getStyleClass().add("chat-bubble-status-failed");
        }
    }

    // === Входящее сообщение ===

    private HBox buildIncomingBubble(MeshMessage msg) {
        StackPane avatar = buildSmallAvatar(msg);
        HBox reactionBar = buildReactionsBar(msg);

        avatar.setCursor(Cursor.HAND);
        avatar.setOnMouseClicked(e -> {
            if (state != null) {
                NodeData node = NodeUtils.resolveNode(state, msg.getFromNodeId());
                if (node == null) {
                    node = state.getNodeByNodeId(msg.getFromNodeId());
                }
                if (node == null) {
                    node = NodeCacheService.getInstance().get(msg.getFromNodeId());
                }
                if (node == null) {
                    // Нода не найдена нигде — создаём bare-ноду из nodeId
                    String nodeId = msg.getFromNodeId();
                    if (nodeId != null && nodeId.length() >= 2) {
                        int nodeNum = (int) Long.parseUnsignedLong(nodeId.substring(1), 16);
                        node = state.getOrCreateNode(nodeNum);
                        NodeCacheService.getInstance().enrichFromCache(node);
                    }
                }
                if (node != null) {
                    NodeDetailPanel.showForNode(state, node);
                }
            }
            e.consume();
        });

        VBox content = new VBox(2);
        content.getStyleClass().add("chat-bubble-incoming");
        bindBubbleWidth(content, reactionBar != null, DEFAULT_BUBBLE_WIDTH_RATIO);
        content.setMinHeight(Region.USE_PREF_SIZE);

        if (state != null && isMentioningMe(msg)) {
            content.getStyleClass().add("chat-bubble-mentioned");
        }

        String senderName = resolveSenderDisplayName(msg);
        Label nameLabel = new Label(senderName);
        nameLabel.getStyleClass().add("chat-bubble-sender");
        content.getChildren().add(nameLabel);

        addQuoteIfPresent(content, msg);
        addTextLabel(content, msg);

        HBox meta = buildIncomingMeta(msg);
        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("chat-bubble-footer");

        Button reactionButton = buildReactionButton(msg);
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        footer.getChildren().add(reactionButton);
        if (reactionBar != null) {
            footer.getChildren().add(reactionBar);
        }
        footer.getChildren().addAll(footerSpacer, meta);
        content.getChildren().add(footer);

        HBox row = new HBox(6, avatar, content);
        row.setAlignment(Pos.BOTTOM_LEFT);
        row.setMinHeight(Region.USE_PREF_SIZE);
        row.getStyleClass().add("chat-message-row-incoming");

        content.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                actions.startReply(msg);
                e.consume();
            }
        });

        attachIncomingContextMenu(content, msg, row);
        return row;
    }

    // === Исходящее сообщение ===

    private HBox buildOutgoingBubble(MeshMessage msg) {
        HBox reactionBar = buildReactionsBar(msg);
        VBox content = new VBox(2);
        content.getStyleClass().add("chat-bubble-outgoing");
        bindBubbleWidth(content, reactionBar != null, DEFAULT_BUBBLE_WIDTH_RATIO);
        content.setMinHeight(Region.USE_PREF_SIZE);

        addQuoteIfPresent(content, msg);
        addTextLabel(content, msg);

        HBox meta = new HBox(6);
        meta.setAlignment(Pos.CENTER_RIGHT);
        meta.getStyleClass().add("chat-bubble-meta");

        addTimeToMeta(meta, msg);
        addStatusToMeta(meta, msg);

        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("chat-bubble-footer");
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        footer.getChildren().add(footerSpacer);
        if (reactionBar != null) {
            footer.getChildren().add(reactionBar);
        }
        footer.getChildren().add(meta);
        content.getChildren().add(footer);

        StackPane avatar = buildSmallAvatar(msg);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(6, spacer, content, avatar);
        row.setAlignment(Pos.BOTTOM_RIGHT);
        row.setMinHeight(Region.USE_PREF_SIZE);
        row.getStyleClass().add("chat-message-row-outgoing");

        content.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                actions.startReply(msg);
                e.consume();
            }
        });

        attachCopyDeleteMenu(content, msg, row);
        return row;
    }

    // === Системное сообщение ===

    private HBox buildSystemBubble(MeshMessage msg) {
        // Traceroute — восстановить визуализацию из текста
        String text = msg.getText();
        if (tracerouteView != null
                && text != null
                && text.startsWith(TracerouteView.TRACEROUTE_PREFIX)) {
            HBox visual = tracerouteView.tryBuildFromText(msg);
            if (visual != null) {
                return visual;
            }
        }

        StackPane botAvatar = buildBotAvatar();

        VBox content = new VBox(2);
        content.getStyleClass().add("chat-bubble-system");
        content.maxWidthProperty().bind(containerWidthProp.multiply(SYSTEM_BUBBLE_WIDTH_RATIO));
        content.setMinHeight(Region.USE_PREF_SIZE);

        EmojiTextFlow textFlow = new EmojiTextFlow(msg.getText(), 18);
        textFlow.setTextStyleClass("chat-bubble-text-node");
        textFlow.getStyleClass().add("chat-bubble-text");
        textFlow.setMinHeight(Region.USE_PREF_SIZE);
        content.getChildren().add(textFlow);

        Label timeLabel = new Label(
                ChatTimeFormatter.formatMessageTime(msg.getTimestamp()));
        timeLabel.getStyleClass().add("chat-bubble-time");
        content.getChildren().add(timeLabel);

        HBox row = new HBox(6, botAvatar, content);
        row.setAlignment(Pos.BOTTOM_LEFT);
        row.getStyleClass().add("chat-message-row-system");

        attachCopyDeleteMenu(content, msg, row);
        return row;
    }

    // === Аватары ===

    /** Аватар бота (🤖) — изображение или fallback на текст. */
    private static StackPane buildBotAvatar() {
        StackPane avatar = new StackPane();
        avatar.setMinSize(28, 28);
        avatar.setMaxSize(28, 28);
        avatar.setAlignment(Pos.CENTER);
        ImageView botImg = EmojiImageCache.createImageView("\uD83E\uDD16", 20);
        if (botImg != null) {
            avatar.getChildren().add(botImg);
        } else {
            Label fallback = new Label("\uD83E\uDD16");
            fallback.setFont(Font.font(20));
            avatar.getChildren().add(fallback);
        }
        return avatar;
    }

    private StackPane buildSmallAvatar(MeshMessage msg) {
        StackPane avatar = new StackPane();
        avatar.setMinSize(28, 28);
        avatar.setMaxSize(28, 28);
        avatar.getStyleClass().add("chat-msg-avatar");

        String text;
        String color;

        if (msg.isOutgoing() && state != null) {
            NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
            if (myNode != null
                    && myNode.getShortName() != null
                    && !myNode.getShortName().isEmpty()) {
                text = myNode.getShortName().toUpperCase();
            } else {
                text = "Я";
            }
            color = "#1EA97C";
        } else if (state != null) {
            NodeData senderNode = NodeUtils.resolveNode(state, msg.getFromNodeId());
            if (senderNode != null
                    && senderNode.getShortName() != null
                    && !senderNode.getShortName().isEmpty()) {
                text = senderNode.getShortName().toUpperCase(java.util.Locale.ROOT);
            } else {
                String nid = msg.getFromNodeId();
                text = nid.length() >= 4
                        ? nid.substring(nid.length() - 4).toUpperCase(java.util.Locale.ROOT)
                        : nid.toUpperCase(java.util.Locale.ROOT);
            }
            color = AVATAR_COLORS[Math.abs(msg.getFromNodeId().hashCode())
                    % AVATAR_COLORS.length];
        } else {
            text = "?";
            color = "#5B8DEF";
        }

        if (text.length() > 4) {
            text = text.substring(0, 4);
        }
        Label label = new Label(text);
        label.setFont(Font.font("Roboto", FontWeight.BOLD,
                NodeUtils.avatarFontSize(text, 28)));
        label.setStyle("-fx-text-fill: white; -fx-padding: 0;");
        avatar.setStyle("-fx-background-color: " + color
                + "; -fx-background-radius: 14;");
        avatar.getChildren().add(label);
        return avatar;
    }

    // === Вспомогательные методы ===

    private boolean isMentioningMe(MeshMessage msg) {
        if (state == null) {
            return false;
        }

        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        String text = msg.getText() != null ? msg.getText().toLowerCase(java.util.Locale.ROOT) : "";

        if (myNode != null) {
            String longName = myNode.getLongName();
            if (longName != null && !longName.isEmpty()
                    && text.contains(longName.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
            String shortName = myNode.getShortName();
            if (shortName != null && !shortName.isEmpty()
                    && text.contains(shortName.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }

        if (msg.getReplyId() != 0) {
            MeshMessage original = MessageDbService.getInstance()
                    .findByPacketId(msg.getReplyId());
            if (original != null && original.isOutgoing()) {
                return true;
            }
        }

        return false;
    }

    private String resolveSenderDisplayName(MeshMessage msg) {
        NodeData senderNode = NodeUtils.resolveNode(state, msg.getFromNodeId());
        if (senderNode != null
                && senderNode.getLongName() != null
                && !senderNode.getLongName().isEmpty()) {
            return senderNode.getLongName();
        }
        if (msg.getSenderName() != null && !msg.getSenderName().isEmpty()) {
            return msg.getSenderName();
        }
        return msg.getFromNodeId();
    }

    private void addQuoteIfPresent(VBox content, MeshMessage msg) {
        if (msg.getReplyText() != null && !msg.getReplyText().isEmpty()) {
            EmojiTextFlow quoteFlow = new EmojiTextFlow(msg.getReplyText(), 14);
            quoteFlow.setTextStyleClass("chat-bubble-quote-node");
            quoteFlow.getStyleClass().add("chat-bubble-quote");
            quoteFlow.setMinHeight(Region.USE_PREF_SIZE);
            content.getChildren().add(quoteFlow);
        }
    }

    private void addTextLabel(VBox content, MeshMessage msg) {
        EmojiTextFlow textFlow = new EmojiTextFlow(msg.getText(), 18);
        textFlow.setTextStyleClass("chat-bubble-text-node");
        textFlow.getStyleClass().add("chat-bubble-text");
        textFlow.setMinHeight(Region.USE_PREF_SIZE);
        content.getChildren().add(textFlow);
    }

    private void bindBubbleWidth(VBox content, boolean hasReactions, double defaultWidthRatio) {
        double widthRatio = hasReactions ? REACTION_BUBBLE_WIDTH_RATIO : defaultWidthRatio;
        content.maxWidthProperty().bind(containerWidthProp.multiply(widthRatio));
    }

    private HBox buildReactionsBar(MeshMessage msg) {
        if (!msg.hasReactions()) {
            return null;
        }
        boolean reactionAvailable = msg.getPacketId() != 0;

        record ReactionAggregate(String emoji, boolean own, int count) {}

        Map<String, ReactionAggregate> aggregates = new LinkedHashMap<>();
        for (MessageReaction reaction : msg.getReactions()) {
            if (reaction == null || !reaction.isVisible()) {
                continue;
            }
            ReactionAggregate current = aggregates.get(reaction.getEmoji());
            if (current == null) {
                aggregates.put(reaction.getEmoji(),
                        new ReactionAggregate(reaction.getEmoji(), reaction.isOutgoing(), 1));
            } else {
                aggregates.put(reaction.getEmoji(),
                        new ReactionAggregate(
                                reaction.getEmoji(),
                                current.own() || reaction.isOutgoing(),
                                current.count() + 1));
            }
        }

        if (aggregates.isEmpty()) {
            return null;
        }

        HBox reactionBar = new HBox(6);
        reactionBar.setAlignment(Pos.CENTER_LEFT);
        reactionBar.getStyleClass().add("chat-bubble-reactions");

        for (ReactionAggregate aggregate : aggregates.values()) {
            HBox chip = new HBox(4);
            chip.setAlignment(Pos.CENTER);
            chip.getStyleClass().add("chat-reaction-chip");
            if (aggregate.own()) {
                chip.getStyleClass().add("chat-reaction-chip-own");
            } else if (reactionAvailable) {
                chip.getStyleClass().add("chat-reaction-chip-clickable");
                chip.setCursor(Cursor.HAND);
                chip.setOnMouseClicked(e -> {
                    if (e.getButton() != MouseButton.PRIMARY) {
                        return;
                    }
                    actions.sendReaction(msg, aggregate.emoji());
                    e.consume();
                });
            }

            chip.getChildren().add(createEmojiNode(aggregate.emoji(), 14));
            if (aggregate.count() > 1) {
                Label count = new Label(String.valueOf(aggregate.count()));
                count.getStyleClass().add("chat-reaction-chip-count");
                chip.getChildren().add(count);
            }
            reactionBar.getChildren().add(chip);
        }

        return reactionBar;
    }

    private HBox buildIncomingMeta(MeshMessage msg) {
        HBox meta = new HBox(6);
        meta.setAlignment(Pos.CENTER_RIGHT);
        meta.getStyleClass().add("chat-bubble-meta");

        int hops = msg.getHopsTraveled();
        if (hops > 0) {
            HBox hopBox = new HBox(2);
            hopBox.setAlignment(Pos.CENTER);
            hopBox.getStyleClass().add("chat-bubble-hops");
            ImageView hopImg = EmojiImageCache.createImageView("\uD83D\uDC07", 12);
            if (hopImg != null) {
                hopBox.getChildren().add(hopImg);
            }
            hopBox.getChildren().add(new Label(String.valueOf(hops)));
            meta.getChildren().add(hopBox);
        } else if (msg.getRxRssi() != 0 || msg.getRxSnr() != 0) {
            HBox signalBox = new HBox(2);
            signalBox.setAlignment(Pos.CENTER);
            signalBox.getStyleClass().add("chat-bubble-hops");
            ImageView sigImg = EmojiImageCache.createImageView("\uD83D\uDCF6", 12);
            if (sigImg != null) {
                signalBox.getChildren().add(sigImg);
            }
            String snrStr = msg.getRxSnr() == (int) msg.getRxSnr()
                    ? String.valueOf((int) msg.getRxSnr())
                    : String.format("%.1f", msg.getRxSnr());
            signalBox.getChildren().add(new Label(msg.getRxRssi() + "dBm/" + snrStr + "dB"));
            meta.getChildren().add(signalBox);
        }

        addTimeToMeta(meta, msg);
        return meta;
    }

    private Button buildReactionButton(MeshMessage msg) {
        Button reactionButton = new Button();
        reactionButton.getStyleClass().add("chat-reaction-btn");
        reactionButton.setGraphic(createEmojiNode("😀", 14));
        reactionButton.setFocusTraversable(false);
        boolean reactionAvailable = msg.getPacketId() != 0;
        if (!reactionAvailable) {
            reactionButton.getStyleClass().add("chat-reaction-btn-disabled");
            reactionButton.setCursor(Cursor.DEFAULT);
            reactionButton.setTooltip(new Tooltip("Реакция недоступна: у сообщения нет packet id"));
        }

        Popup reactionPopup = reactionAvailable ? buildReactionPopup(msg) : null;
        reactionButton.setOnAction(e -> {
            if (!reactionAvailable) {
                e.consume();
                return;
            }
            if (reactionPopup.isShowing()) {
                reactionPopup.hide();
            } else {
                showReactionPopup(reactionButton, reactionPopup);
            }
            e.consume();
        });
        return reactionButton;
    }

    private Popup buildReactionPopup(MeshMessage msg) {
        VBox picker = new VBox(4);
        picker.setAlignment(Pos.CENTER_LEFT);
        picker.getStyleClass().add("chat-reaction-picker");

        StackPane popupRoot = new StackPane(picker);
        popupRoot.getStyleClass().add("chat-reaction-popup");

        Popup popup = new Popup();
        popup.setAutoFix(true);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.getContent().add(popupRoot);
        popup.setOnHidden(e -> {
            if (openReactionPopup == popup) {
                openReactionPopup = null;
            }
        });

        for (String[] emojiRow : REACTION_EMOJI_ROWS) {
            HBox row = new HBox(4);
            row.setAlignment(Pos.CENTER_LEFT);
            for (String emoji : emojiRow) {
                Button emojiButton = new Button();
                emojiButton.getStyleClass().add("chat-reaction-picker-btn");
                emojiButton.setGraphic(createEmojiNode(emoji, 18));
                emojiButton.setFocusTraversable(false);
                emojiButton.setOnAction(e -> {
                    popup.hide();
                    actions.sendReaction(msg, emoji);
                    e.consume();
                });
                row.getChildren().add(emojiButton);
            }
            picker.getChildren().add(row);
        }

        return popup;
    }

    private void showReactionPopup(Button anchor, Popup popup) {
        if (openReactionPopup != null && openReactionPopup != popup) {
            openReactionPopup.hide();
        }

        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds == null) {
            return;
        }

        syncReactionPopupTheme(anchor, popup);
        popup.show(anchor, bounds.getMinX(), bounds.getMaxY() + 6);
        openReactionPopup = popup;
    }

    private void syncReactionPopupTheme(Button anchor, Popup popup) {
        if (popup.getContent().isEmpty() || anchor.getScene() == null || anchor.getScene().getRoot() == null) {
            return;
        }
        javafx.scene.Node popupRoot = popup.getContent().get(0);
        boolean isLight = anchor.getScene().getRoot().getStyleClass().contains("light-theme");
        if (isLight && !popupRoot.getStyleClass().contains("light-theme")) {
            popupRoot.getStyleClass().add("light-theme");
        } else if (!isLight) {
            popupRoot.getStyleClass().remove("light-theme");
        }
    }

    private javafx.scene.Node createEmojiNode(String emoji, double size) {
        ImageView image = EmojiImageCache.createImageView(emoji, size);
        if (image != null) {
            return image;
        }
        Label fallback = new Label(emoji);
        fallback.setFont(Font.font(size));
        return fallback;
    }

    private void addTimeToMeta(HBox meta, MeshMessage msg) {
        Label timeLabel = new Label(
                ChatTimeFormatter.formatMessageTime(msg.getTimestamp()));
        timeLabel.getStyleClass().add("chat-bubble-time");
        meta.getChildren().add(timeLabel);
    }

    private void addStatusToMeta(HBox meta, MeshMessage msg) {
        String statusIcon = "";
        if (msg.getStatus() != null) {
            statusIcon = switch (msg.getStatus()) {
                case SENDING -> "\u23F3";
                case DELIVERED -> "\u2713";
                case FAILED -> "\u2717";
            };
        }
        if (!statusIcon.isEmpty()) {
            Label statusLabel = new Label(statusIcon);
            statusLabel.getStyleClass().add("chat-bubble-status");
            if (msg.getStatus() == MeshMessage.DeliveryStatus.FAILED) {
                statusLabel.getStyleClass().add("chat-bubble-status-failed");
            }
            meta.getChildren().add(statusLabel);
            if (msg.getStatus() == MeshMessage.DeliveryStatus.SENDING
                    && msg.getPacketId() != 0) {
                pendingStatusLabels.put(msg.getPacketId(), statusLabel);
            }
        }
    }

    private void attachIncomingContextMenu(VBox content, MeshMessage msg,
                                           HBox row) {
        MenuItem copyItem = new MenuItem("Копировать");
        copyItem.setOnAction(ev -> copyText(msg.getText()));

        MenuItem replyItem = new MenuItem("Ответить");
        replyItem.setOnAction(ev -> actions.startReply(msg));

        MenuItem traceItem = new MenuItem("Trace");
        traceItem.setOnAction(ev -> actions.requestTraceroute(msg));

        MenuItem infoItem = new MenuItem("Инфо");
        infoItem.setOnAction(ev -> actions.requestNodeInfo(msg));

        MenuItem deleteItem = new MenuItem("Удалить");
        deleteItem.setOnAction(ev -> actions.confirmDeleteMessage(msg, row));

        ContextMenu menu = new ContextMenu(
                copyItem, replyItem, traceItem, infoItem,
                new SeparatorMenuItem(), deleteItem);
        content.setOnContextMenuRequested(ev -> {
            menu.show(content, ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });
    }

    private void attachCopyDeleteMenu(VBox content, MeshMessage msg,
                                      HBox row) {
        MenuItem copyItem = new MenuItem("Копировать");
        copyItem.setOnAction(ev -> copyText(msg.getText()));

        MenuItem deleteItem = new MenuItem("Удалить");
        deleteItem.setOnAction(ev -> actions.confirmDeleteMessage(msg, row));

        ContextMenu menu = new ContextMenu(
                copyItem, new SeparatorMenuItem(), deleteItem);
        content.setOnContextMenuRequested(ev -> {
            menu.show(content, ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });
    }

    private static void copyText(String text) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
    }
}
