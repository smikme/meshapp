package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.i18n.I18n;
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
 * Chat-list cell with avatar, name, last-message preview, time, and unread badge.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ChatListCell extends ListCell<ChatItem> {
    private static final String BELL_ICON_PATH = "/drawer/icon/bell.svg";
    private static final String BELL_OFF_ICON_PATH = "/drawer/icon/bell-off.svg";
    private static final double MUTE_ICON_SIZE = 12;
    private static final double PREVIEW_FONT_SIZE = 12;
    private static final double PREVIEW_TWO_LINE_HEIGHT = 30;
    private static final double COMPACT_BADGE_TRANSLATE_X = 8;
    private static final double COMPACT_BADGE_TRANSLATE_Y = -5;
    private static final Insets REGULAR_CELL_PADDING = new Insets(8, 10, 8, 10);
    private static final Insets COMPACT_CELL_PADDING = new Insets(8, 6, 8, 6);
    private static final String ELLIPSIS = "...";

    private final boolean compact;
    private final HBox root = new HBox(10);
    private final StackPane avatarPane = new StackPane();
    private final Label avatarLabel = new Label();
    private final VBox textBox = new VBox(2);
    private final Label nameLabel = new Label();
    private final Label muteIconLabel = new Label();
    private final Tooltip muteIconTooltip = new Tooltip(I18n.t("chat.notifications.tooltip"));
    private final EmojiTextFlow messagePreview = new EmojiTextFlow();
    private final VBox metaBox = new VBox(4);
    private final HBox timeBox = new HBox(6);
    private final Label timeLabel = new Label();
    private final Label unreadBadge = new Label();
    private final ContextMenu chatContextMenu;
    private String previewSourceText = "";
    private boolean previewUpdateQueued;

    /**
     * @param onDeleteChat callback used by the context menu to delete or disable a chat
     * @param onShowProperties callback used by the context menu to open channel properties
     * @param onToggleMute callback used to toggle chat notifications
     */
    public ChatListCell(Consumer<ChatItem> onDeleteChat,
                        Consumer<ChatItem> onShowProperties,
                        Consumer<ChatItem> onToggleMute) {
        this(onDeleteChat, onShowProperties, onToggleMute, item -> {}, false);
    }

    /**
     * @param onDeleteChat callback used by the context menu to delete or disable a chat
     * @param onShowProperties callback used by the context menu to open channel properties
     * @param onToggleMute callback used to toggle chat notifications
     * @param onClearHistory callback used by the context menu to clear chat history
     */
    public ChatListCell(Consumer<ChatItem> onDeleteChat,
                        Consumer<ChatItem> onShowProperties,
                        Consumer<ChatItem> onToggleMute,
                        Consumer<ChatItem> onClearHistory) {
        this(onDeleteChat, onShowProperties, onToggleMute, onClearHistory, false);
    }

    /**
     * @param onDeleteChat callback used by the context menu to delete or disable a chat
     * @param onShowProperties callback used by the context menu to open channel properties
     * @param onToggleMute callback used to toggle chat notifications
     * @param compact whether the cell should show only avatar and unread count
     */
    public ChatListCell(Consumer<ChatItem> onDeleteChat,
                        Consumer<ChatItem> onShowProperties,
                        Consumer<ChatItem> onToggleMute,
                        boolean compact) {
        this(onDeleteChat, onShowProperties, onToggleMute, item -> {}, compact);
    }

    /**
     * @param onDeleteChat callback used by the context menu to delete or disable a chat
     * @param onShowProperties callback used by the context menu to open channel properties
     * @param onToggleMute callback used to toggle chat notifications
     * @param onClearHistory callback used by the context menu to clear chat history
     * @param compact whether the cell should show only avatar and unread count
     */
    public ChatListCell(Consumer<ChatItem> onDeleteChat,
                        Consumer<ChatItem> onShowProperties,
                        Consumer<ChatItem> onToggleMute,
                        Consumer<ChatItem> onClearHistory,
                        boolean compact) {
        this.compact = compact;
        root.setAlignment(compact ? Pos.CENTER : Pos.CENTER_LEFT);
        root.setPadding(compact ? COMPACT_CELL_PADDING : REGULAR_CELL_PADDING);
        root.getStyleClass().add("chat-list-cell-root");
        if (compact) {
            root.getStyleClass().add("chat-list-cell-root-compact");
        }
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
        if (compact) {
            StackPane.setAlignment(unreadBadge, Pos.TOP_RIGHT);
            unreadBadge.setTranslateX(COMPACT_BADGE_TRANSLATE_X);
            unreadBadge.setTranslateY(COMPACT_BADGE_TRANSLATE_Y);
            avatarPane.getChildren().add(unreadBadge);
        } else {
            metaBox.getChildren().addAll(timeBox, unreadBadge);
        }

        if (compact) {
            root.getChildren().add(avatarPane);
        } else {
            root.getChildren().addAll(avatarPane, textBox, metaBox);
        }

        // Context menu opened by right click.
        MenuItem propertiesItem = new MenuItem(I18n.t("chat.menu.properties"));
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

        MenuItem clearHistoryItem = new MenuItem(I18n.t("chat.menu.clearHistory"));
        clearHistoryItem.setOnAction(ev -> {
            ChatItem chatItem = getItem();
            if (chatItem != null) {
                onClearHistory.accept(chatItem);
            }
        });

        MenuItem closeItem = new MenuItem(I18n.t("chat.menu.deleteLocal"));
        chatContextMenu = new ContextMenu(
                propertiesItem,
                notificationsItem,
                new SeparatorMenuItem(),
                clearHistoryItem,
                closeItem);

        // Properties are available only for channels.
        chatContextMenu.setOnShowing(ev -> {
            ChatItem chatItem = getItem();
            if (chatItem == null || isEmpty()) {
                ev.consume();
                chatContextMenu.hide();
                return;
            }
            boolean isChannel = chatItem.getType() == ChatItem.ChatType.CHANNEL;
            propertiesItem.setVisible(isChannel);
            if (chatItem != null) {
                notificationsItem.setText(chatItem.isMuted()
                        ? I18n.t("chat.menu.notificationsEnable")
                        : I18n.t("chat.menu.notificationsDisable"));
            }
            clearHistoryItem.setText(I18n.t("chat.menu.clearHistory"));
            closeItem.setText(isChannel ? I18n.t("chat.menu.disableChannel") : I18n.t("chat.menu.deleteLocal"));
        });

        closeItem.setOnAction(ev -> {
            ChatItem chatItem = getItem();
            if (chatItem == null) {
                return;
            }
            ModalPane.showConfirm(
                    chatItem.getType() == ChatItem.ChatType.CHANNEL
                            ? I18n.t("chat.confirm.disableChannel.title", chatItem.getDisplayName())
                            : I18n.t("chat.confirm.deleteDm.title", chatItem.getDisplayName()),
                    chatItem.getType() == ChatItem.ChatType.CHANNEL
                            ? I18n.t("chat.confirm.disableChannel.message")
                            : I18n.t("chat.confirm.deleteDm.message"),
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
            setContextMenu(null);
            setTooltip(null);
            return;
        }

        String safeAvatarText = UnicodeTextUtils.sanitizeForJavaFxDisplay(item.getAvatarText());
        avatarLabel.setText(safeAvatarText);
        avatarLabel.setFont(Font.font("Roboto", FontWeight.BOLD,
                NodeUtils.chatAvatarFontSize(safeAvatarText, 40)));
        avatarPane.setStyle("-fx-background-color: " + item.getAvatarColor()
                + "; -fx-background-radius: 20;");

        nameLabel.setText(UnicodeTextUtils.sanitizeForJavaFxDisplay(item.getDisplayName()));
        setTooltip(compact ? new Tooltip(nameLabel.getText()) : null);
        muteIconLabel.setText(null);
        muteIconLabel.setGraphic(createMuteIcon(item.isMuted()));
        muteIconTooltip.setText(item.isMuted()
                ? I18n.t("chat.notifications.disabled")
                : I18n.t("chat.notifications.enabled"));
        previewSourceText = item.getLastMessageText() != null ? item.getLastMessageText() : "";
        messagePreview.setEmojiSize(TypographyManager.scaleChat(PREVIEW_FONT_SIZE));
        applyFixedPreviewHeight();
        updatePreviewTextForWidth();

        if (!compact && item.getLastMessageTime() > 0) {
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
        setContextMenu(chatContextMenu);
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
