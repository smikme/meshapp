package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.utils.NodeUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.function.Consumer;

/**
 * Ячейка списка чатов: аватар, имя, превью последнего сообщения, время, badge непрочитанных.
 */
public class ChatListCell extends ListCell<ChatItem> {

    private final HBox root = new HBox(10);
    private final StackPane avatarPane = new StackPane();
    private final Label avatarLabel = new Label();
    private final VBox textBox = new VBox(2);
    private final Label nameLabel = new Label();
    private final EmojiTextFlow messagePreview = new EmojiTextFlow();
    private final VBox metaBox = new VBox(4);
    private final Label timeLabel = new Label();
    private final Label unreadBadge = new Label();

    /**
     * @param onDeleteChat      колбэк удаления чата (вызывается из контекстного меню удаления/отключения)
     * @param onShowProperties  колбэк свойств канала (вызывается из контекстного меню «Свойства»)
     */
    public ChatListCell(Consumer<ChatItem> onDeleteChat, Consumer<ChatItem> onShowProperties) {
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(8, 10, 8, 10));
        root.getStyleClass().add("chat-list-cell-root");

        avatarPane.setMinSize(40, 40);
        avatarPane.setPrefSize(40, 40);
        avatarPane.setMaxSize(40, 40);
        avatarPane.getStyleClass().add("chat-avatar");
        avatarLabel.setTextFill(Color.WHITE);
        avatarLabel.setPadding(Insets.EMPTY);
        avatarPane.getChildren().add(avatarLabel);

        nameLabel.setFont(Font.font("Roboto", FontWeight.BOLD, 14));
        nameLabel.getStyleClass().add("chat-name-label");

        messagePreview.getStyleClass().add("chat-preview-label");
        messagePreview.setTextStyleClass("chat-preview-text-node");
        messagePreview.setEmojiSize(12);
        messagePreview.setMinHeight(34);
        messagePreview.setPrefHeight(34);
        messagePreview.setMaxHeight(34);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(messagePreview.widthProperty());
        clip.heightProperty().bind(messagePreview.heightProperty());
        messagePreview.setClip(clip);

        textBox.getChildren().addAll(nameLabel, messagePreview);
        textBox.setMinWidth(0);
        textBox.setPrefWidth(0);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        nameLabel.maxWidthProperty().bind(textBox.widthProperty());
        messagePreview.maxWidthProperty().bind(textBox.widthProperty());

        timeLabel.getStyleClass().add("chat-time-label");

        unreadBadge.getStyleClass().add("chat-unread-badge");
        unreadBadge.setAlignment(Pos.CENTER);

        metaBox.setAlignment(Pos.TOP_RIGHT);
        metaBox.setMinWidth(50);
        metaBox.getChildren().addAll(timeLabel, unreadBadge);

        root.getChildren().addAll(avatarPane, textBox, metaBox);

        // Контекстное меню (правый клик)
        MenuItem propertiesItem = new MenuItem("Свойства");
        propertiesItem.setOnAction(ev -> {
            ChatItem chatItem = getItem();
            if (chatItem != null) {
                onShowProperties.accept(chatItem);
            }
        });

        MenuItem closeItem = new MenuItem("Удалить локально");
        ContextMenu ctxMenu = new ContextMenu(propertiesItem, closeItem);
        setContextMenu(ctxMenu);

        // «Свойства» только для каналов
        ctxMenu.setOnShowing(ev -> {
            ChatItem chatItem = getItem();
            boolean isChannel = chatItem != null
                    && chatItem.getType() == ChatItem.ChatType.CHANNEL;
            propertiesItem.setVisible(isChannel);
            closeItem.setText(isChannel ? "Отключить канал" : "Удалить локально");
        });

        closeItem.setOnAction(ev -> {
            ChatItem chatItem = getItem();
            if (chatItem == null) {
                return;
            }
            ModalPane.showConfirm(
                    (chatItem.getType() == ChatItem.ChatType.CHANNEL
                            ? "Отключить канал «"
                            : "Удалить локально «")
                            + chatItem.getDisplayName() + "»?",
                    chatItem.getType() == ChatItem.ChatType.CHANNEL
                            ? "Канал будет отключён на устройстве, а сообщения удалены локально."
                            : "История этого DM будет удалена только на этом клиенте.",
                    confirmed -> {
                        if (confirmed) {
                            onDeleteChat.accept(chatItem);
                        }
                    }
            );
        });
    }

    @Override
    protected void updateItem(ChatItem item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
            setText(null);
            return;
        }

        avatarLabel.setText(item.getAvatarText());
        avatarLabel.setFont(Font.font("Roboto", FontWeight.BOLD,
                NodeUtils.chatAvatarFontSize(item.getAvatarText().length(), 40)));
        avatarPane.setStyle("-fx-background-color: " + item.getAvatarColor()
                + "; -fx-background-radius: 20;");

        nameLabel.setText(item.getDisplayName());
        messagePreview.setText(
                item.getLastMessageText() != null ? item.getLastMessageText() : "");

        if (item.getLastMessageTime() > 0) {
            timeLabel.setText(
                    ChatTimeFormatter.formatChatTime(item.getLastMessageTime()));
            timeLabel.setVisible(true);
            timeLabel.setManaged(true);
        } else {
            timeLabel.setVisible(false);
            timeLabel.setManaged(false);
        }

        if (item.getUnreadCount() > 0) {
            unreadBadge.setText(String.valueOf(item.getUnreadCount()));
            unreadBadge.setVisible(true);
            unreadBadge.setManaged(true);
        } else {
            unreadBadge.setVisible(false);
            unreadBadge.setManaged(false);
        }

        setGraphic(root);
        setText(null);
    }
}
