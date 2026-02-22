package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.NodeDetailPanel;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.utils.NodeUtils;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Map;

/**
 * Фабрика пузырей сообщений: входящие, исходящие и системные.
 *
 * <p>Не зависит от FormChat напрямую — получает зависимости через конструктор и колбэки.
 */
public class MessageBubbleFactory {

    private static final String[] AVATAR_COLORS = {
            "#5B8DEF", "#E57C23", "#9B59B6", "#1EA97C",
            "#E74C3C", "#3498DB", "#F39C12", "#1ABC9C"
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

        /** Подтвердить и удалить сообщение. */
        void confirmDeleteMessage(MeshMessage msg, HBox bubbleRow);
    }

    private DeviceState state;
    private final ReadOnlyDoubleProperty containerWidthProp;
    private final BubbleActions actions;
    private final Map<Integer, Label> pendingStatusLabels;
    private TracerouteView tracerouteView;

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

        avatar.setCursor(Cursor.HAND);
        avatar.setOnMouseClicked(e -> {
            if (state != null) {
                NodeData node = state.getNodeDb().get(msg.getFromNum());
                if (node != null) {
                    NodeDetailPanel.showForNode(state, node);
                }
            }
            e.consume();
        });

        VBox content = new VBox(2);
        content.getStyleClass().add("chat-bubble-incoming");
        content.maxWidthProperty().bind(containerWidthProp.multiply(0.75));
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

        // Мета: хопы + время
        HBox meta = new HBox(6);
        meta.setAlignment(Pos.CENTER_RIGHT);
        meta.getStyleClass().add("chat-bubble-meta");

        int hops = msg.getHopsTraveled();
        if (hops > 0) {
            Label hopLabel = new Label("\uD83D\uDC07 " + hops);
            hopLabel.getStyleClass().add("chat-bubble-hops");
            meta.getChildren().add(hopLabel);
        }

        addTimeToMeta(meta, msg);
        content.getChildren().add(meta);

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
        VBox content = new VBox(2);
        content.getStyleClass().add("chat-bubble-outgoing");
        content.maxWidthProperty().bind(containerWidthProp.multiply(0.75));
        content.setMinHeight(Region.USE_PREF_SIZE);

        addQuoteIfPresent(content, msg);
        addTextLabel(content, msg);

        HBox meta = new HBox(6);
        meta.setAlignment(Pos.CENTER_RIGHT);
        meta.getStyleClass().add("chat-bubble-meta");

        addTimeToMeta(meta, msg);
        addStatusToMeta(meta, msg);
        content.getChildren().add(meta);

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

        Label botAvatar = new Label("\uD83E\uDD16");
        botAvatar.setFont(Font.font(20));
        botAvatar.setMinSize(28, 28);
        botAvatar.setAlignment(Pos.CENTER);

        VBox content = new VBox(2);
        content.getStyleClass().add("chat-bubble-system");
        content.maxWidthProperty().bind(containerWidthProp.multiply(0.85));
        content.setMinHeight(Region.USE_PREF_SIZE);

        Label textLabel = new Label(msg.getText());
        textLabel.setWrapText(true);
        textLabel.setMinHeight(Region.USE_PREF_SIZE);
        textLabel.getStyleClass().add("chat-bubble-text");
        content.getChildren().add(textLabel);

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

    // === Аватар ===

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
            NodeData senderNode = state.getNodeDb().get(msg.getFromNum());
            if (senderNode != null
                    && senderNode.getShortName() != null
                    && !senderNode.getShortName().isEmpty()) {
                text = senderNode.getShortName().toUpperCase();
            } else {
                text = String.format("!%04x", msg.getFromNum() & 0xFFFF)
                        .toUpperCase();
            }
            color = AVATAR_COLORS[Math.abs(msg.getFromNum())
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
                NodeUtils.avatarFontSize(text.length(), 28)));
        label.setStyle("-fx-text-fill: white;");
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
        String text = msg.getText() != null ? msg.getText().toLowerCase() : "";

        if (myNode != null) {
            String longName = myNode.getLongName();
            if (longName != null && !longName.isEmpty()
                    && text.contains(longName.toLowerCase())) {
                return true;
            }
            String shortName = myNode.getShortName();
            if (shortName != null && !shortName.isEmpty()
                    && text.contains(shortName.toLowerCase())) {
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
        if (state != null) {
            NodeData senderNode = state.getNodeDb().get(msg.getFromNum());
            if (senderNode != null
                    && senderNode.getLongName() != null
                    && !senderNode.getLongName().isEmpty()) {
                return senderNode.getLongName();
            }
        }
        if (msg.getSenderName() != null && !msg.getSenderName().isEmpty()) {
            return msg.getSenderName();
        }
        return "!" + String.format("%08x", msg.getFromNum());
    }

    private void addQuoteIfPresent(VBox content, MeshMessage msg) {
        if (msg.getReplyText() != null && !msg.getReplyText().isEmpty()) {
            Label quoteLabel = new Label(msg.getReplyText());
            quoteLabel.getStyleClass().add("chat-bubble-quote");
            quoteLabel.setWrapText(true);
            quoteLabel.setMinHeight(Region.USE_PREF_SIZE);
            content.getChildren().add(quoteLabel);
        }
    }

    private void addTextLabel(VBox content, MeshMessage msg) {
        Label textLabel = new Label(msg.getText());
        textLabel.setWrapText(true);
        textLabel.setMinHeight(Region.USE_PREF_SIZE);
        textLabel.getStyleClass().add("chat-bubble-text");
        content.getChildren().add(textLabel);
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
