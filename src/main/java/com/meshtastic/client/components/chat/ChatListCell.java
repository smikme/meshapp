package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.UnicodeTextUtils;
import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.util.function.Consumer;

/**
 * Ячейка списка чатов: аватар, имя, превью последнего сообщения, время, badge непрочитанных.
 */
public class ChatListCell extends ListCell<ChatItem> {
    private static final String BELL_ICON_PATH = "/drawer/icon/bell.svg";
    private static final String BELL_OFF_ICON_PATH = "/drawer/icon/bell-off.svg";
    private static final double MUTE_ICON_SIZE = 12;
    private static final double PREVIEW_FONT_SIZE = 12;
    private static final double PREVIEW_TWO_LINE_HEIGHT = 30;
    private static final String ELLIPSIS = "...";

    private final HBox root = new HBox(10);
    private final StackPane avatarPane = new StackPane();
    private final Label avatarLabel = new Label();
    private final VBox textBox = new VBox(2);
    private final Label nameLabel = new Label();
    private final Label muteIconLabel = new Label();
    private final Tooltip muteIconTooltip = new Tooltip("Оповещения чата");
    private final EmojiTextFlow messagePreview = new EmojiTextFlow();
    private final VBox metaBox = new VBox(4);
    private final HBox timeBox = new HBox(6);
    private final Label timeLabel = new Label();
    private final Label unreadBadge = new Label();
    private String previewSourceText = "";
    private boolean previewUpdateQueued;

    /**
     * @param onDeleteChat      колбэк удаления чата (вызывается из контекстного меню удаления/отключения)
     * @param onShowProperties  колбэк свойств канала (вызывается из контекстного меню «Свойства»)
     * @param onToggleMute      колбэк переключения оповещений для чата
     */
    public ChatListCell(Consumer<ChatItem> onDeleteChat,
                        Consumer<ChatItem> onShowProperties,
                        Consumer<ChatItem> onToggleMute) {
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(8, 10, 8, 10));
        root.getStyleClass().add("chat-list-cell-root");
        root.setMinWidth(0);
        root.prefWidthProperty().bind(widthProperty());
        root.maxWidthProperty().bind(widthProperty());

        avatarPane.setMinSize(40, 40);
        avatarPane.setPrefSize(40, 40);
        avatarPane.setMaxSize(40, 40);
        avatarPane.getStyleClass().add("chat-avatar");
        avatarLabel.setTextFill(Color.WHITE);
        avatarLabel.setPadding(Insets.EMPTY);
        avatarPane.getChildren().add(avatarLabel);

        nameLabel.getStyleClass().add("chat-name-label");
        nameLabel.setMinWidth(0);

        muteIconLabel.getStyleClass().add("chat-mute-icon");
        muteIconLabel.setMinWidth(Label.USE_PREF_SIZE);
        muteIconLabel.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        muteIconLabel.setTooltip(muteIconTooltip);
        muteIconLabel.setCursor(Cursor.HAND);
        muteIconLabel.setOnMousePressed(ev -> ev.consume());
        muteIconLabel.setOnMouseClicked(ev -> {
            ChatItem chatItem = getItem();
            if (chatItem != null) {
                onToggleMute.accept(chatItem);
            }
            ev.consume();
        });

        messagePreview.getStyleClass().add("chat-preview-label");
        messagePreview.setTextStyleClass("chat-preview-text-node");
        messagePreview.setMinWidth(0);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(messagePreview.widthProperty());
        clip.heightProperty().bind(messagePreview.heightProperty());
        messagePreview.setClip(clip);

        textBox.getChildren().addAll(nameLabel, messagePreview);
        textBox.setMinWidth(0);
        textBox.setPrefWidth(0);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        nameLabel.maxWidthProperty().bind(textBox.widthProperty());
        messagePreview.prefWidthProperty().bind(textBox.widthProperty());
        messagePreview.maxWidthProperty().bind(textBox.widthProperty());
        textBox.widthProperty().addListener((obs, oldWidth, newWidth) -> queuePreviewTextUpdate());
        sceneProperty().addListener((obs, oldScene, newScene) -> queuePreviewTextUpdate());

        timeLabel.getStyleClass().add("chat-time-label");

        unreadBadge.getStyleClass().add("chat-unread-badge");
        unreadBadge.setAlignment(Pos.CENTER);

        timeBox.setAlignment(Pos.CENTER_RIGHT);
        timeBox.getChildren().addAll(timeLabel, muteIconLabel);

        metaBox.setAlignment(Pos.TOP_RIGHT);
        metaBox.setMinWidth(Region.USE_PREF_SIZE);
        metaBox.getChildren().addAll(timeBox, unreadBadge);

        root.getChildren().addAll(avatarPane, textBox, metaBox);

        // Контекстное меню (правый клик)
        MenuItem propertiesItem = new MenuItem("Свойства");
        propertiesItem.setOnAction(ev -> {
            ChatItem chatItem = getItem();
            if (chatItem != null) {
                onShowProperties.accept(chatItem);
            }
        });

        MenuItem notificationsItem = new MenuItem();
        notificationsItem.setOnAction(ev -> {
            ChatItem chatItem = getItem();
            if (chatItem != null) {
                onToggleMute.accept(chatItem);
            }
        });

        MenuItem closeItem = new MenuItem("Удалить локально");
        ContextMenu ctxMenu = new ContextMenu(propertiesItem, notificationsItem, new SeparatorMenuItem(), closeItem);
        setContextMenu(ctxMenu);

        // «Свойства» только для каналов
        ctxMenu.setOnShowing(ev -> {
            ChatItem chatItem = getItem();
            boolean isChannel = chatItem != null
                    && chatItem.getType() == ChatItem.ChatType.CHANNEL;
            propertiesItem.setVisible(isChannel);
            if (chatItem != null) {
                notificationsItem.setText(chatItem.isMuted()
                        ? "Включить оповещения"
                        : "Выключить оповещения");
            }
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
            previewSourceText = "";
            messagePreview.setText("");
            setGraphic(null);
            setText(null);
            return;
        }

        String safeAvatarText = UnicodeTextUtils.sanitizeForJavaFxDisplay(item.getAvatarText());
        avatarLabel.setText(safeAvatarText);
        avatarLabel.setFont(Font.font("Roboto", FontWeight.BOLD,
                NodeUtils.chatAvatarFontSize(safeAvatarText, 40)));
        avatarPane.setStyle("-fx-background-color: " + item.getAvatarColor()
                + "; -fx-background-radius: 20;");

        nameLabel.setText(UnicodeTextUtils.sanitizeForJavaFxDisplay(item.getDisplayName()));
        muteIconLabel.setText(null);
        muteIconLabel.setGraphic(createMuteIcon(item.isMuted()));
        muteIconTooltip.setText(item.isMuted()
                ? "Оповещения чата выключены"
                : "Оповещения чата включены");
        previewSourceText = item.getLastMessageText() != null ? item.getLastMessageText() : "";
        messagePreview.setEmojiSize(TypographyManager.scaleChat(PREVIEW_FONT_SIZE));
        applyFixedPreviewHeight();
        updatePreviewTextForWidth();

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

    private SVGPath createMuteIcon(boolean muted) {
        return SvgIconLoader.load(muted ? BELL_OFF_ICON_PATH : BELL_ICON_PATH, MUTE_ICON_SIZE);
    }

    private void applyFixedPreviewHeight() {
        double previewHeight = previewTwoLineHeight();
        messagePreview.setMinHeight(previewHeight);
        messagePreview.setPrefHeight(previewHeight);
        messagePreview.setMaxHeight(previewHeight);
    }

    private void queuePreviewTextUpdate() {
        if (previewUpdateQueued) {
            return;
        }
        previewUpdateQueued = true;
        Platform.runLater(() -> {
            previewUpdateQueued = false;
            updatePreviewTextForWidth();
        });
    }

    private void updatePreviewTextForWidth() {
        double width = textBox.getWidth();
        if (width <= 1) {
            messagePreview.setText(previewSourceText);
            return;
        }

        String fitted = fitPreviewToVisibleLines(previewSourceText, width);
        messagePreview.setText(fitted);
    }

    private String fitPreviewToVisibleLines(String text, double width) {
        if (text == null || text.isEmpty() || previewFits(text, width)) {
            return text == null ? "" : text;
        }

        int codePoints = text.codePointCount(0, text.length());
        int low = 0;
        int high = codePoints;
        String best = ELLIPSIS;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            String candidate = previewCandidate(text, mid);
            if (previewFits(candidate, width)) {
                best = candidate;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        return best;
    }

    private String previewCandidate(String text, int codePoints) {
        String prefix = UnicodeTextUtils.prefixByCodePoints(text, codePoints);
        if (prefix == null || prefix.isBlank()) {
            return ELLIPSIS;
        }
        return prefix.stripTrailing() + ELLIPSIS;
    }

    private boolean previewFits(String text, double width) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        if (width <= 1) {
            return false;
        }

        Text measure = new Text(toMeasurementText(text));
        measure.setFont(Font.font("Roboto", TypographyManager.scaleChat(PREVIEW_FONT_SIZE)));
        measure.setWrappingWidth(width);
        double requiredHeight = measure.getLayoutBounds().getHeight();
        return requiredHeight <= previewTwoLineHeight() + 0.5;
    }

    private String toMeasurementText(String text) {
        StringBuilder measured = new StringBuilder(text.length());
        for (EmojiTextFlow.Segment segment : EmojiTextFlow.parseSegments(text)) {
            if (segment.isEmoji()) {
                measured.append('M');
            } else {
                measured.append(UnicodeTextUtils.sanitizeForJavaFxDisplay(segment.text()));
            }
        }
        return measured.toString();
    }

    private double previewTwoLineHeight() {
        return TypographyManager.scaleChat(PREVIEW_TWO_LINE_HEIGHT);
    }
}
