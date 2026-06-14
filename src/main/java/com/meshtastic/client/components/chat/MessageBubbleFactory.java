package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.components.NodeDetailPanel;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.ExternalUrlLauncher;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.UnicodeTextUtils;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
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
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Factory for incoming, outgoing, and system message bubbles.
 *
 * <p>The class owns JavaFX rendering only. Name resolution, avatars, and
 * reaction aggregation are delegated to specialized helper classes.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
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
    private static final int MQTT_BADGE_SIZE = 18;
    private static final int MQTT_ICON_WIDTH = 14;
    private static final int MQTT_ICON_HEIGHT = 10;
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
    private static final String RETRY_ICON_PATH = "/icons/refresh.svg";
    private static final String OK_STATUS_ICON_PATH = "/icons/status-ok-flat.svg";
    private static final int OK_STATUS_ICON_SIZE = 18;
    private static final Insets MQTT_BADGE_MARGIN = new Insets(3, 4, 0, 0);
    private static final List<List<String>> REACTION_EMOJI_ROWS = List.of(
            List.of("⭐", "✅", "👍", "👋", "💯", "🔥", "🤝", "😁", "😂", "🤣", "😀"),
            List.of("👌", "❎", "👎", "🤔", "👀", "👽", "🙏", "💪", "🤡", "😄", "🫡"),
            List.of("😆", "💩", "😱", "🐰", "🐇", "🔆", "📡", "❤️", "🚀", "🐭", "🥶"),
            List.of("0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟")
    );

    /**
     * Callbacks for actions exposed from bubble context menus.
     */
    public interface BubbleActions {

        /** Starts replying to a message. */
        void startReply(MeshMessage msg);

        /** Sends an emoji reaction to a message. */
        void sendReaction(MeshMessage msg, String emoji);

        /** Confirms and deletes a message. */
        void confirmDeleteMessage(MeshMessage msg, HBox bubbleRow);

        /** Toggles message selection for bulk actions. */
        void toggleMessageSelection(MeshMessage msg, HBox bubbleRow);

        /** Returns whether the message is currently selected. */
        boolean isMessageSelected(MeshMessage msg);

        /** Returns whether message selection mode is currently active. */
        boolean isMessageSelectionModeActive();

        /** Resends an undelivered message. */
        boolean retryMessage(MeshMessage msg);
    }

    /**
     * Rendered message row with direct references to mutable UI nodes.
     *
     * <p>The chat form keeps this object so it can update status, reactions,
     * quote previews, and meta indicators in place without replacing the whole
     * row in {@code messageContainer}.
     */
    public static final class RenderedMessageRow {
        private final HBox row;
        private final VBox content;
        private final VBox quoteSlot;
        private final HBox reactionSlot;
        private final HBox routingMetaSlot;
        private final HBox meta;
        private final StackPane mqttBadge;
        private final double defaultWidthRatio;
        private final boolean incoming;
        private final boolean outgoing;
        private Label statusLabel;

        private RenderedMessageRow(HBox row,
                                   VBox content,
                                   VBox quoteSlot,
                                   HBox reactionSlot,
                                   HBox routingMetaSlot,
                                   HBox meta,
                                   Label statusLabel,
                                   StackPane mqttBadge,
                                   double defaultWidthRatio,
                                   boolean incoming,
                                   boolean outgoing) {
            this.row = row;
            this.content = content;
            this.quoteSlot = quoteSlot;
            this.reactionSlot = reactionSlot;
            this.routingMetaSlot = routingMetaSlot;
            this.meta = meta;
            this.statusLabel = statusLabel;
            this.mqttBadge = mqttBadge;
            this.defaultWidthRatio = defaultWidthRatio;
            this.incoming = incoming;
            this.outgoing = outgoing;
        }

        public HBox row() { return row; }
    }

    private record MqttBubble(StackPane wrapper, StackPane badge) {}

    private DeviceState state;
    private final ReadOnlyDoubleProperty containerWidthProp;
    private final BubbleActions actions;
    private final Map<Integer, Label> pendingStatusLabels;
    private TracerouteView tracerouteView;
    private Popup openReactionPopup;

    /**
     * @param state current device state, or {@code null}
     * @param containerWidthProp width property of messageContainer for maxWidth binding
     * @param actions action callbacks for reply, traceroute, and deletion
     * @param pendingStatusLabels packetId -&gt; Label map for delivery-status tracking
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

    /** Updates DeviceState after connection rebind. */
    public void setState(DeviceState state) {
        this.state = state;
    }

    /** Sets the TracerouteView used to render traceroute bubbles. */
    public void setTracerouteView(TracerouteView tracerouteView) {
        this.tracerouteView = tracerouteView;
    }

    /** Closes the open reaction picker, if any. */
    public void hideOpenReactionPopup() {
        if (openReactionPopup == null) {
            return;
        }
        openReactionPopup.hide();
        openReactionPopup = null;
    }

    /**
     * Builds a bubble according to the message type.
     *
     * @param msg message to render
     * @return rendered JavaFX bubble node
     */
    public HBox build(MeshMessage msg) {
        return buildRendered(msg).row();
    }

    /**
     * Builds a bubble and returns a managed row for later patch-style updates.
     *
     * @param msg message to render
     * @return rendered row with references to mutable UI nodes
     */
    public RenderedMessageRow buildRendered(MeshMessage msg) {
        if (msg.isSystemMessage()) {
            return buildSystemBubble(msg);
        }
        if (msg.isOutgoing()) {
            return buildOutgoingBubble(msg);
        }
        return buildIncomingBubble(msg);
    }

    /**
     * Updates the delivery-status icon on an existing label.
     *
     * @param label target label
     * @param status new delivery status
     */
    public static void updateStatusLabel(Label label,
                                         MeshMessage.DeliveryStatus status) {
        updateStatusLabel(label, status, false);
    }

    private static void updateStatusLabel(Label label, MeshMessage msg) {
        updateStatusLabel(label, msg.getStatus(), msg.isDirectMessage());
    }

    private static void updateStatusLabel(Label label,
                                          MeshMessage.DeliveryStatus status,
                                          boolean directMessage) {
        if (status == null) {
            return;
        }

        label.setText(null);
        label.setGraphic(null);
        label.setContentDisplay(ContentDisplay.TEXT_ONLY);
        label.getStyleClass().remove("chat-bubble-status-failed");
        label.getStyleClass().remove("chat-bubble-status-ok");
        switch (status) {
            case SENDING -> label.setText("⏳");
            case DELIVERED -> label.setText("✓");
            case CONFIRMED -> {
                if (directMessage) {
                    label.setGraphic(createOkStatusIcon());
                    label.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                    label.getStyleClass().add("chat-bubble-status-ok");
                } else {
                    label.setText("✓");
                }
            }
            case FAILED -> label.setText("✗");
        }
        if (status == MeshMessage.DeliveryStatus.FAILED) {
            label.getStyleClass().add("chat-bubble-status-failed");
        }
    }

    private static Node createOkStatusIcon() {
        SVGPath icon = SvgIconLoader.load(OK_STATUS_ICON_PATH, OK_STATUS_ICON_SIZE);
        if (icon != null) {
            icon.getStyleClass().add("chat-bubble-status-ok-icon");
            return icon;
        }
        return createEmojiNode("\uD83C\uDD97", OK_STATUS_ICON_SIZE);
    }

    /**
     * Updates the visuals and interactivity of the outgoing-message status control.
     *
     * @param label status control
     * @param msg message associated with this control
     */
    public void refreshStatusLabel(Label label, MeshMessage msg) {
        if (label == null || msg == null || msg.getStatus() == null) {
            return;
        }
        updateStatusLabel(label, msg);
        configureStatusLabelInteraction(label, msg);
        if (msg.getStatus() == MeshMessage.DeliveryStatus.SENDING && msg.getPacketId() != ZERO_VALUE) {
            pendingStatusLabels.put(msg.getPacketId(), label);
        }
    }

    /**
     * Updates delivery status in an already rendered row without rebuilding the bubble.
     *
     * @param rendered rendered message row
     * @param msg current message
     */
    public void refreshRenderedStatus(RenderedMessageRow rendered, MeshMessage msg) {
        if (rendered == null || msg == null || !rendered.outgoing || rendered.meta == null) {
            return;
        }
        if (rendered.statusLabel == null) {
            rendered.statusLabel = createStatusLabel(msg).orElse(null);
            if (rendered.statusLabel != null) {
                rendered.meta.getChildren().add(rendered.statusLabel);
            }
        }
        refreshStatusLabel(rendered.statusLabel, msg);
    }

    /**
     * Updates the reaction bar in an already rendered row.
     *
     * @param rendered rendered message row
     * @param msg current message with hydrated reactions
     */
    public void refreshRenderedReactions(RenderedMessageRow rendered, MeshMessage msg) {
        if (rendered == null || msg == null || rendered.reactionSlot == null || rendered.content == null) {
            return;
        }
        HBox reactionBar = buildReactionsBar(msg);
        setSlotContent(rendered.reactionSlot, reactionBar);
        bindBubbleWidth(rendered.content, reactionBar != null, rendered.defaultWidthRatio);
    }

    /**
     * Updates quote, meta, and MQTT badge in an already rendered row.
     *
     * @param rendered rendered message row
     * @param msg current message
     */
    public void refreshRenderedMetadata(RenderedMessageRow rendered, MeshMessage msg) {
        if (rendered == null || msg == null) {
            return;
        }
        if (rendered.quoteSlot != null) {
            setSlotContent(rendered.quoteSlot, createQuoteNode(msg).orElse(null));
        }
        if (rendered.incoming && rendered.routingMetaSlot != null) {
            setSlotContent(rendered.routingMetaSlot, createRoutingMetaNode(msg).orElse(null));
        }
        if (rendered.outgoing) {
            refreshRenderedStatus(rendered, msg);
        }
        refreshMqttBadge(rendered.content, rendered.mqttBadge, msg);
    }

    /**
     * Builds an incoming bubble: avatar, sender name, text, reactions, and meta block.
     *
     * @param msg incoming message
     * @return rendered chat row for the incoming message
     */
    private RenderedMessageRow buildIncomingBubble(MeshMessage msg) {
        HBox reactionBar = buildReactionsBar(msg);
        ChatNodeDisplayHelper.IncomingMessagePresentation senderPresentation =
                ChatNodeDisplayHelper.resolveIncomingMessagePresentation(state, msg);

        StackPane avatar = buildAvatar(senderPresentation.avatar());
        configureIncomingAvatar(avatar, msg);

        VBox quoteSlot = createQuoteSlot(createQuoteNode(msg).orElse(null));
        HBox reactionSlot = createReactionSlot(reactionBar);
        HBox routingMetaSlot = createRoutingMetaSlot(createRoutingMetaNode(msg).orElse(null));
        HBox meta = buildIncomingMeta(msg, routingMetaSlot);
        VBox content = createMessageContent("chat-bubble-incoming", reactionBar != null, DEFAULT_BUBBLE_WIDTH_RATIO);
        Optional.of(msg)
                .filter(this::isMentioningMe)
                .ifPresent(ignored -> content.getStyleClass().add("chat-bubble-mentioned"));
        content.getChildren().addAll(nodes(
                createSenderNameLabel(senderPresentation.senderName()),
                quoteSlot,
                createTextNode(msg),
                buildIncomingFooter(msg, reactionSlot, meta)
        ));

        MqttBubble mqttBubble = wrapWithMqttBadge(content, msg);
        HBox row = createMessageRow(Pos.BOTTOM_LEFT, "chat-message-row-incoming", avatar, mqttBubble.wrapper());
        attachMessagePrimaryClickHandler(content, msg, row, true);
        attachIncomingContextMenu(content, msg, row);
        applyMessageSelectionState(row, msg);
        return new RenderedMessageRow(
                row,
                content,
                quoteSlot,
                reactionSlot,
                routingMetaSlot,
                meta,
                null,
                mqttBubble.badge(),
                DEFAULT_BUBBLE_WIDTH_RATIO,
                true,
                false);
    }

    /**
     * Builds an outgoing bubble with right alignment and a delivery-status indicator.
     *
     * @param msg outgoing message
     * @return rendered chat row for the outgoing message
     */
    private RenderedMessageRow buildOutgoingBubble(MeshMessage msg) {
        HBox reactionBar = buildReactionsBar(msg);
        VBox quoteSlot = createQuoteSlot(createQuoteNode(msg).orElse(null));
        HBox reactionSlot = createReactionSlot(reactionBar);
        Label statusLabel = createStatusLabel(msg).orElse(null);
        HBox meta = buildOutgoingMeta(msg, statusLabel);
        VBox content = createMessageContent("chat-bubble-outgoing", reactionBar != null, DEFAULT_BUBBLE_WIDTH_RATIO);
        content.getChildren().addAll(nodes(
                quoteSlot,
                createTextNode(msg),
                buildOutgoingFooter(msg, reactionSlot, meta)
        ));

        StackPane avatar = buildAvatar(ChatNodeDisplayHelper.resolveOutgoingAvatar(state));
        Region spacer = createFlexibleSpacer();

        MqttBubble mqttBubble = wrapWithMqttBadge(content, msg);
        HBox row = createMessageRow(Pos.BOTTOM_RIGHT, "chat-message-row-outgoing", spacer, mqttBubble.wrapper(), avatar);
        attachMessagePrimaryClickHandler(content, msg, row, false);
        attachCopyDeleteMenu(content, msg, row);
        applyMessageSelectionState(row, msg);
        return new RenderedMessageRow(
                row,
                content,
                quoteSlot,
                reactionSlot,
                null,
                meta,
                statusLabel,
                mqttBubble.badge(),
                DEFAULT_BUBBLE_WIDTH_RATIO,
                false,
                true);
    }

    /**
     * Renders a system message, or restores a special traceroute bubble from its text.
     *
     * @param msg system message
     * @return system-message bubble
     */
    private RenderedMessageRow buildSystemBubble(MeshMessage msg) {
        return tryBuildTracerouteBubble(msg).orElseGet(() -> createDefaultSystemBubble(msg));
    }

    private RenderedMessageRow createDefaultSystemBubble(MeshMessage msg) {
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
        attachMessagePrimaryClickHandler(content, msg, row, false);
        attachCopyDeleteMenu(content, msg, row);
        applyMessageSelectionState(row, msg);
        return new RenderedMessageRow(
                row,
                content,
                null,
                null,
                null,
                null,
                null,
                null,
                SYSTEM_BUBBLE_WIDTH_RATIO,
                false,
                false);
    }

    /**
     * Checks whether a traceroute view can replace the regular text system bubble.
     *
     * @param msg system message
     * @return visual traceroute bubble when the message contains a traceroute payload
     */
    private Optional<RenderedMessageRow> tryBuildTracerouteBubble(MeshMessage msg) {
        return Optional.ofNullable(tracerouteView)
                .filter(ignored -> Optional.ofNullable(msg.getText())
                        .filter(text -> text.startsWith(TracerouteView.TRACEROUTE_PREFIX))
                        .isPresent())
                .map(view -> view.tryBuildFromText(msg))
                .map(row -> {
                    attachMessagePrimaryClickHandler(row, msg, row, false);
                    applyMessageSelectionState(row, msg);
                    return new RenderedMessageRow(
                            row,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            SYSTEM_BUBBLE_WIDTH_RATIO,
                            false,
                            false);
                });
    }

    /**
     * Creates a compact bot avatar for system messages.
     *
     * @return avatar pane with the bot emoji
     */
    private static StackPane buildBotAvatar() {
        StackPane avatar = createAvatarPane();
        avatar.getChildren().add(createEmojiNode("\uD83E\uDD16", BOT_AVATAR_EMOJI_SIZE));
        return avatar;
    }

    /**
     * Builds a circular text avatar from a prepared descriptor.
     *
     * @param descriptor avatar text and color
     * @return rendered JavaFX avatar node
     */
    private static StackPane buildAvatar(ChatNodeDisplayHelper.AvatarDescriptor descriptor) {
        StackPane avatar = createAvatarPane();
        avatar.getStyleClass().add("chat-msg-avatar");
        avatar.setStyle("-fx-background-color: " + descriptor.color()
                + "; -fx-background-radius: " + SMALL_AVATAR_RADIUS + ";");

        String safeAvatarText = UnicodeTextUtils.sanitizeForJavaFxDisplay(descriptor.text());
        Label label = new Label(safeAvatarText);
        label.setFont(Font.font("Roboto", FontWeight.BOLD,
                NodeUtils.avatarFontSize(safeAvatarText, (int) SMALL_AVATAR_SIZE)));
        label.setStyle(AVATAR_LABEL_STYLE);
        avatar.getChildren().add(label);
        return avatar;
    }

    /**
     * Creates the fixed-size base avatar container.
     *
     * @return empty centered avatar pane
     */
    private static StackPane createAvatarPane() {
        StackPane avatar = new StackPane();
        avatar.setMinSize(SMALL_AVATAR_SIZE, SMALL_AVATAR_SIZE);
        avatar.setMaxSize(SMALL_AVATAR_SIZE, SMALL_AVATAR_SIZE);
        avatar.setAlignment(Pos.CENTER);
        return avatar;
    }

    /**
     * Adds the click handler that opens the node card from an incoming avatar.
     *
     * @param avatar avatar pane
     * @param msg incoming message
     */
    private void configureIncomingAvatar(StackPane avatar, MeshMessage msg) {
        avatar.setCursor(Cursor.HAND);
        avatar.setOnMouseClicked(e -> {
            showNodeDetails(msg.getFromNodeId());
            e.consume();
        });
    }

    /**
     * Opens the node panel if the sender can be resolved to {@link NodeData}.
     *
     * @param nodeId sender node identifier
     */
    private void showNodeDetails(String nodeId) {
        Optional.ofNullable(state)
                .map(currentState -> ChatNodeDisplayHelper.resolveNodeForDetails(currentState, nodeId))
                .ifPresent(node -> NodeDetailPanel.showForNode(state, node));
    }

    /**
     * Creates the common bubble-content container and binds its maximum width.
     *
     * @param styleClass CSS class for the concrete bubble type
     * @param hasReactions whether the bubble has a reaction bar
     * @param defaultWidthRatio default fraction of the container width
     * @return VBox bubble content
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
     * Overlays a monochrome MQTT indicator in the top-right corner of the bubble.
     *
     * @param content bubble content
     * @param msg message
     * @return original content or a wrapper with the badge
     */
    private MqttBubble wrapWithMqttBadge(VBox content, MeshMessage msg) {
        StackPane badge = createMqttBadge();
        StackPane wrapper = new StackPane(content, badge);
        wrapper.setMinHeight(Region.USE_PREF_SIZE);
        wrapper.setAlignment(Pos.TOP_LEFT);
        StackPane.setAlignment(content, Pos.TOP_LEFT);
        StackPane.setAlignment(badge, Pos.TOP_RIGHT);
        StackPane.setMargin(badge, MQTT_BADGE_MARGIN);
        refreshMqttBadge(content, badge, msg);
        return new MqttBubble(wrapper, badge);
    }

    /**
     * Creates an MQTT cloud without a colored accent; CSS theme rules provide the color.
     *
     * @return badge with a shape icon
     */
    private static StackPane createMqttBadge() {
        Region icon = new Region();
        icon.getStyleClass().add("chat-bubble-mqtt-icon");
        icon.setMinSize(MQTT_ICON_WIDTH, MQTT_ICON_HEIGHT);
        icon.setPrefSize(MQTT_ICON_WIDTH, MQTT_ICON_HEIGHT);
        icon.setMaxSize(MQTT_ICON_WIDTH, MQTT_ICON_HEIGHT);

        StackPane badge = new StackPane(icon);
        badge.getStyleClass().add("chat-bubble-mqtt-badge");
        badge.setMinSize(MQTT_BADGE_SIZE, MQTT_BADGE_SIZE);
        badge.setPrefSize(MQTT_BADGE_SIZE, MQTT_BADGE_SIZE);
        badge.setMaxSize(MQTT_BADGE_SIZE, MQTT_BADGE_SIZE);
        badge.setMouseTransparent(true);
        return badge;
    }

    /**
     * Creates a chat row with shared spacing and alignment.
     *
     * @param alignment horizontal alignment
     * @param styleClass row CSS class
     * @param children row children
     * @return rendered row container
     */
    private static HBox createMessageRow(Pos alignment, String styleClass, Node... children) {
        HBox row = new HBox(MESSAGE_ROW_SPACING, children);
        row.setAlignment(alignment);
        row.setMinHeight(Region.USE_PREF_SIZE);
        row.getStyleClass().add(styleClass);
        return row;
    }

    /**
     * Creates a flexible spacer that separates footer/meta blocks.
     *
     * @return region with grow priority
     */
    private static Region createFlexibleSpacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /**
     * Creates the sender-name label above an incoming message.
     *
     * @param senderName sender name to display
     * @return styled name label
     */
    private static Label createSenderNameLabel(String senderName) {
        Label nameLabel = new Label(UnicodeTextUtils.sanitizeForJavaFxDisplay(senderName));
        nameLabel.getStyleClass().add("chat-bubble-sender");
        return nameLabel;
    }

    private static VBox createQuoteSlot(Node quoteNode) {
        VBox slot = new VBox();
        setSlotContent(slot, quoteNode);
        return slot;
    }

    private static HBox createReactionSlot(HBox reactionBar) {
        HBox slot = new HBox();
        slot.setAlignment(Pos.CENTER_LEFT);
        setSlotContent(slot, reactionBar);
        return slot;
    }

    private static HBox createRoutingMetaSlot(HBox routingMetaNode) {
        HBox slot = new HBox();
        slot.setAlignment(Pos.CENTER_RIGHT);
        setSlotContent(slot, routingMetaNode);
        return slot;
    }

    private static void setSlotContent(Pane slot, Node child) {
        if (slot == null) {
            return;
        }
        if (child == null) {
            slot.getChildren().clear();
            slot.setVisible(false);
            slot.setManaged(false);
            return;
        }
        slot.getChildren().setAll(child);
        slot.setVisible(true);
        slot.setManaged(true);
    }

    /**
     * Creates the common bubble footer with the requested alignment.
     *
     * @param alignment footer alignment
     * @return HBox footer container
     */
    private static HBox createFooter(Pos alignment) {
        HBox footer = new HBox(FOOTER_SPACING);
        footer.setAlignment(alignment);
        footer.getStyleClass().add("chat-bubble-footer");
        return footer;
    }

    /**
     * Builds the incoming-message footer: reaction button, reaction bar, and meta data.
     *
     * @param msg incoming message
     * @param reactionBar prepared reaction bar, or {@code null}
     * @return incoming bubble footer
     */
    private HBox buildIncomingFooter(MeshMessage msg, HBox reactionSlot, HBox meta) {
        HBox footer = createFooter(Pos.CENTER_LEFT);
        footer.getChildren().addAll(nodes(
                buildReactionButton(msg),
                reactionSlot,
                createFlexibleSpacer(),
                meta
        ));
        return footer;
    }

    /**
     * Builds the outgoing-message footer: reactions on the left, status/time on the right.
     *
     * @param msg outgoing message
     * @param reactionBar prepared reaction bar, or {@code null}
     * @return outgoing bubble footer
     */
    private HBox buildOutgoingFooter(MeshMessage msg, HBox reactionSlot, HBox meta) {
        HBox footer = createFooter(Pos.CENTER_RIGHT);
        footer.getChildren().addAll(nodes(
                createFlexibleSpacer(),
                reactionSlot,
                meta
        ));
        return footer;
    }

    private void attachMessagePrimaryClickHandler(Node clickTarget,
                                                  MeshMessage msg,
                                                  HBox row,
                                                  boolean allowDoubleClickReply) {
        clickTarget.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (actions.isMessageSelectionModeActive()) {
                if (e.getClickCount() == 1) {
                    actions.toggleMessageSelection(msg, row);
                    applyMessageSelectionState(row, msg);
                }
                e.consume();
                return;
            }
            if (allowDoubleClickReply && e.getClickCount() == DOUBLE_CLICK_COUNT) {
                actions.startReply(msg);
                e.consume();
            }
        });
    }

    /**
     * Decides whether an incoming message should be highlighted as a mention of the local user.
     *
     * @param msg incoming message
     * @return {@code true} when the text names the local node or replies to an outgoing message
     */
    private boolean isMentioningMe(MeshMessage msg) {
        return Optional.ofNullable(state)
                .map(ignored -> messageMentionsLocalUser(msg) || isReplyToOutgoingMessage(msg))
                .orElse(false);
    }

    /**
     * Checks text mentions against the local node's longName and shortName.
     *
     * @param msg incoming message
     * @return {@code true} if the local node name is found in the text
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
     * Checks whether the message replies to one of our outgoing messages.
     *
     * @param msg incoming message
     * @return {@code true} if the reply target is found in the database and is outgoing
     */
    private boolean isReplyToOutgoingMessage(MeshMessage msg) {
        return Optional.of(msg.getReplyId())
                .filter(replyId -> replyId != ZERO_VALUE)
                .map(replyId -> findReplyTargetInCurrentScope(replyId, msg))
                .map(MeshMessage::isOutgoing)
                .orElse(false);
    }

    private MeshMessage findReplyTargetInCurrentScope(int replyId, MeshMessage msg) {
        if (state == null || msg == null) {
            return null;
        }
        String ownerNodeId = state.getOwnerNodeId();
        if (ownerNodeId == null || ownerNodeId.isBlank()) {
            return null;
        }

        String chatType = msg.isDirectMessage() ? "dm" : "channel";
        String chatKey = msg.isDirectMessage() ? msg.getFromNodeId() : String.valueOf(msg.getChannelIndex());
        if (chatKey == null || chatKey.isBlank()) {
            return null;
        }

        return MessageDbService.getInstance().findByPacketId(replyId, chatType, chatKey, ownerNodeId);
    }

    /**
     * Normalizes text for case-insensitive mention substring searches.
     *
     * @param value source text
     * @return lower-case string, or an empty string
     */
    private static String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /**
     * Checks whether an already normalized name appears in already normalized text.
     *
     * @param normalizedText message text in lower case
     * @param candidate node name in its original form
     * @return {@code true} if the name is found as a substring
     */
    private static boolean containsNormalizedText(String normalizedText, String candidate) {
        return candidate != null
                && !candidate.isBlank()
                && normalizedText.contains(candidate.toLowerCase(Locale.ROOT));
    }

    /**
     * Adds a quote preview for reply messages only.
     *
     * @param msg message
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
     * Adds the main message text to the bubble.
     *
     * @param msg message
     */
    private Node createTextNode(MeshMessage msg) {
        return createBubbleTextFlow(msg.getText(), MESSAGE_TEXT_EMOJI_SIZE, "chat-bubble-text-node", "chat-bubble-text");
    }

    /**
     * Creates a single {@link TextFlow} for message text and quote text.
     *
     * @param text source text
     * @param emojiSize emoji size
     * @param textStyleClass CSS class for text nodes inside the flow
     * @param styleClass CSS class for the flow itself
     * @return configured {@link TextFlow}
     */
    private static TextFlow createBubbleTextFlow(String text,
                                                 double emojiSize,
                                                 String textStyleClass,
                                                 String styleClass) {
        TextFlow textFlow = new TextFlow();
        textFlow.getStyleClass().add(styleClass);
        textFlow.setMinHeight(Region.USE_PREF_SIZE);

        double scaledEmojiSize = TypographyManager.scaleChat(emojiSize);
        for (ChatUrlParser.Segment segment : ChatUrlParser.split(text == null ? "" : text)) {
            if (segment.url()) {
                textFlow.getChildren().add(createUrlTextNode(segment.text(), textStyleClass));
            } else {
                addEmojiTextNodes(textFlow, segment.text(), scaledEmojiSize, textStyleClass);
            }
        }
        return textFlow;
    }

    private static void addEmojiTextNodes(TextFlow textFlow,
                                          String text,
                                          double emojiSize,
                                          String textStyleClass) {
        for (EmojiTextFlow.Segment segment : EmojiTextFlow.parseSegments(text)) {
            if (segment.isEmoji()) {
                ImageView emoji = EmojiImageCache.createImageView(segment.text(), emojiSize);
                if (emoji != null) {
                    textFlow.getChildren().add(emoji);
                    continue;
                }
            }
            textFlow.getChildren().add(createPlainTextNode(segment.text(), textStyleClass));
        }
    }

    private static Text createUrlTextNode(String url, String textStyleClass) {
        Text textNode = createPlainTextNode(url, textStyleClass);
        textNode.getStyleClass().add("chat-bubble-url-node");
        textNode.setCursor(Cursor.HAND);
        textNode.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (event.getClickCount() == 1) {
                ExternalUrlLauncher.open(url);
            }
            event.consume();
        });
        return textNode;
    }

    private static Text createPlainTextNode(String text, String textStyleClass) {
        Text textNode = new Text(UnicodeTextUtils.sanitizeForJavaFxDisplay(text));
        textNode.getStyleClass().add(textStyleClass);
        return textNode;
    }

    /**
     * Binds bubble width, accounting for the presence of a reaction bar.
     *
     * @param content bubble content
     * @param hasReactions whether a reaction bar is present
     * @param defaultWidthRatio base fraction of the container width
     */
    private void bindBubbleWidth(VBox content, boolean hasReactions, double defaultWidthRatio) {
        double widthRatio = hasReactions ? REACTION_BUBBLE_WIDTH_RATIO : defaultWidthRatio;
        content.maxWidthProperty().unbind();
        content.maxWidthProperty().bind(containerWidthProp.multiply(widthRatio));
    }

    private void refreshMqttBadge(VBox content, StackPane badge, MeshMessage msg) {
        if (content == null || badge == null) {
            return;
        }
        boolean visible = msg != null && msg.isViaMqtt();
        setStyleClassPresence(content, "chat-bubble-with-mqtt-badge", visible);
        badge.setVisible(visible);
        badge.setManaged(visible);
    }

    /**
     * Builds a reaction bar from already aggregated reaction summaries.
     *
     * @param msg message
     * @return reaction bar, or {@code null} when there is nothing to display
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
     * Creates one reaction chip: emoji, count, tooltip, and click state.
     *
     * @param msg message that owns the chip
     * @param reactionAvailable whether another click may send a reaction
     * @param summary aggregated reaction data
     * @return one UI reaction chip
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
     * Applies chip visual state: own-style or click handler for sending another reaction.
     *
     * @param chip UI reaction chip
     * @param msg owning message
     * @param reactionAvailable whether sending a reaction is available
     * @param summary aggregated reaction data
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
     * Installs a tooltip only when it has useful text.
     *
     * @param node node that receives the tooltip
     * @param tooltipText tooltip text
     */
    private static void installTooltip(Node node, String tooltipText) {
        Optional.ofNullable(tooltipText)
                .filter(text -> !text.isBlank())
                .map(Tooltip::new)
                .ifPresent(tooltip -> Tooltip.install(node, tooltip));
    }

    /**
     * Builds the incoming-message meta block: hops/signal and time.
     *
     * @param msg incoming message
     * @return meta container
     */
    private HBox buildIncomingMeta(MeshMessage msg, HBox routingMetaSlot) {
        HBox meta = createMetaBox();
        meta.getChildren().addAll(nodes(
                routingMetaSlot,
                createTimeLabel(msg.getTimestamp())
        ));
        return meta;
    }

    /**
     * Builds the outgoing-message meta block: time and delivery indicator.
     *
     * @param msg outgoing message
     * @return meta container
     */
    private HBox buildOutgoingMeta(MeshMessage msg, Label statusLabel) {
        HBox meta = createMetaBox();
        meta.setAlignment(Pos.CENTER_RIGHT);
        meta.getChildren().addAll(nodes(
                createTimeLabel(msg.getTimestamp()),
                statusLabel
        ));
        return meta;
    }

    /**
     * Creates the common bubble metadata container.
     *
     * @return HBox for time, status, and route metrics
     */
    private static HBox createMetaBox() {
        HBox meta = new HBox(MESSAGE_ROW_SPACING);
        meta.setAlignment(Pos.CENTER_RIGHT);
        meta.getStyleClass().add("chat-bubble-meta");
        return meta;
    }

    /**
     * Chooses the secondary meta indicator: hops, signal, or nothing.
     *
     * @param msg incoming message
     * @return meta-indicator node when data is available
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
     * Creates a compact meta item from an emoji and a text value.
     *
     * @param emoji emoji icon
     * @param value indicator text
     * @return HBox with icon and value
     */
    private static HBox createMetaIndicator(String emoji, String value) {
        HBox indicator = new HBox(META_INDICATOR_SPACING);
        indicator.setAlignment(Pos.CENTER);
        indicator.getStyleClass().add("chat-bubble-hops");
        indicator.getChildren().addAll(createEmojiNode(emoji, META_INDICATOR_EMOJI_SIZE), new Label(value));
        return indicator;
    }

    /**
     * Formats RSSI/SNR into one compact string for the meta block.
     *
     * @param msg incoming message
     * @return string such as {@code -90dBm/12.4dB}
     */
    private static String formatSignalMetrics(MeshMessage msg) {
        String snrStr = msg.getRxSnr() == (int) msg.getRxSnr()
                ? String.valueOf((int) msg.getRxSnr())
                : String.format("%.1f", msg.getRxSnr());
        return msg.getRxRssi() + "dB/" + snrStr + "dB";
    }

    /**
     * Creates the reaction button and attaches the popup only when the message has a packet id.
     *
     * @param msg message
     * @return reaction button
     */
    private Button buildReactionButton(MeshMessage msg) {
        return isReactionAvailable(msg)
                ? createEnabledReactionButton(msg)
                : createDisabledReactionButton();
    }

    private Button createEnabledReactionButton(MeshMessage msg) {
        Button reactionButton = createReactionButton();
        AtomicReference<Popup> reactionPopup = new AtomicReference<>();
        reactionButton.setOnAction(e -> {
            Popup popup = reactionPopup.updateAndGet(existing ->
                    existing != null ? existing : buildReactionPopup(msg));
            toggleReactionPopup(reactionButton, popup);
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
     * Creates the base reaction button before enabled/disabled state is applied.
     *
     * @return button with an emoji icon
     */
    private static Button createReactionButton() {
        Button reactionButton = new Button();
        reactionButton.getStyleClass().add("chat-reaction-btn");
        reactionButton.setGraphic(createEmojiNode("😀", REACTION_BUTTON_EMOJI_SIZE));
        reactionButton.setFocusTraversable(false);
        return reactionButton;
    }

    /**
     * Disables the reaction button and adds an explanatory tooltip.
     *
     * @param reactionButton reaction button
     */
    private static void disableReactionButton(Button reactionButton) {
        reactionButton.getStyleClass().add("chat-reaction-btn-disabled");
        reactionButton.setCursor(Cursor.DEFAULT);
        reactionButton.setTooltip(new Tooltip(I18n.t("chat.bubble.reactionUnavailable")));
    }

    /**
     * Checks whether a reaction can be sent for the message.
     *
     * @param msg message
     * @return {@code true} when a packet id is present
     */
    private static boolean isReactionAvailable(MeshMessage msg) {
        return msg.getPacketId() != ZERO_VALUE;
    }

    /**
     * Toggles the reaction popup from the reaction button click.
     *
     * @param anchor source button
     * @param popup reaction picker popup
     */
    private void toggleReactionPopup(Button anchor, Popup popup) {
        Runnable action = popup.isShowing() ? popup::hide : () -> showReactionPopup(anchor, popup);
        action.run();
    }

    /**
     * Creates a reaction picker popup with fixed emoji rows.
     *
     * @param msg message that owns the popup
     * @return configured reaction picker popup
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
     * Builds one picker row from an emoji set.
     *
     * @param msg message
     * @param popup popup to close after selection
     * @param emojiRow emoji row
     * @return HBox for one picker row
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
     * Creates the button for one emoji reaction inside the popup.
     *
     * @param msg message
     * @param popup popup to hide after selection
     * @param emoji selected emoji
     * @return picker button
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
     * Shows the popup below the reaction button and syncs its theme with the current scene.
     *
     * @param anchor anchor button
     * @param popup reaction picker popup
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
     * Copies the light theme into the popup when the root scene is in light mode.
     *
     * @param anchor anchor button
     * @param popup reaction picker popup
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
     * Creates an emoji node with a text fallback when the PNG resource is missing.
     *
     * @param emoji emoji character
     * @param size desired size
     * @return {@link ImageView} or {@link Label}
     */
    private static Node createEmojiNode(String emoji, double size) {
        double scaledSize = TypographyManager.scaleChat(size);
        ImageView image = EmojiImageCache.createImageView(emoji, scaledSize);
        return Optional.ofNullable(image)
                .map(Node.class::cast)
                .orElseGet(() -> createEmojiFallbackLabel(emoji, scaledSize));
    }

    private static Node createEmojiFallbackLabel(String emoji, double size) {
        String safeEmoji = UnicodeTextUtils.sanitizeForJavaFxDisplay(emoji);
        Label fallback = new Label(safeEmoji.isEmpty() ? "□" : safeEmoji);
        fallback.setFont(Font.font(size));
        return fallback;
    }

    /**
     * Creates the message-time label in the chat's standard format.
     *
     * @param timestamp message timestamp in epoch seconds
     * @return time label
     */
    private static Label createTimeLabel(long timestamp) {
        Label timeLabel = new Label(ChatTimeFormatter.formatMessageTime(timestamp));
        timeLabel.getStyleClass().add("chat-bubble-time");
        return timeLabel;
    }

    /**
     * Creates the delivery-status label and registers it for live pending-ACK updates.
     *
     * @param msg outgoing message
     * @return status label when the message has a status
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

        Tooltip.install(retryAction, new Tooltip(I18n.t("chat.bubble.retry")));
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
     * Attaches the incoming-message context menu with reply/delete actions.
     *
     * @param content bubble content
     * @param msg message
     * @param row chat row
     */
    private void attachIncomingContextMenu(VBox content, MeshMessage msg, HBox row) {
        installContextMenu(content, () -> createContextMenu(
                createMenuItem(I18n.t("common.copy"), () -> copyText(msg.getText())),
                createSelectionMenuItem(msg, row),
                createMenuItem(I18n.t("chat.bubble.reply"), () -> actions.startReply(msg)),
                new SeparatorMenuItem(),
                createMenuItem(I18n.t("common.delete"), () -> actions.confirmDeleteMessage(msg, row))
        ));
    }

    /**
     * Attaches a simplified context menu without reply/trace actions.
     *
     * @param content bubble content
     * @param msg message
     * @param row chat row
     */
    private void attachCopyDeleteMenu(VBox content, MeshMessage msg, HBox row) {
        installContextMenu(content, () -> createContextMenu(
                createMenuItem(I18n.t("common.copy"), () -> copyText(msg.getText())),
                createSelectionMenuItem(msg, row),
                new SeparatorMenuItem(),
                createMenuItem(I18n.t("common.delete"), () -> actions.confirmDeleteMessage(msg, row))
        ));
    }

    private ContextMenu createContextMenu(MenuItem... items) {
        return new ContextMenu(Arrays.stream(items)
                .filter(Objects::nonNull)
                .toArray(MenuItem[]::new));
    }

    private MenuItem createSelectionMenuItem(MeshMessage msg, HBox row) {
        if (msg == null || msg.getDbId() <= 0) {
            return null;
        }
        String key = actions.isMessageSelected(msg) ? "chat.bubble.unselect" : "chat.bubble.select";
        return createMenuItem(I18n.t(key), () -> {
            actions.toggleMessageSelection(msg, row);
            applyMessageSelectionState(row, msg);
        });
    }

    /**
     * Creates a context-menu item and binds it to a runnable action.
     *
     * @param title menu-item title
     * @param action click action
     * @return configured menu item
     */
    private static MenuItem createMenuItem(String title, Runnable action) {
        MenuItem menuItem = new MenuItem(title);
        menuItem.setOnAction(ev -> action.run());
        return menuItem;
    }

    /**
     * Attaches a lazily created context menu to bubble content.
     *
     * @param content bubble content
     * @param menuSupplier action-menu factory
     */
    private static void installContextMenu(VBox content, Supplier<ContextMenu> menuSupplier) {
        AtomicReference<ContextMenu> menuRef = new AtomicReference<>();
        content.setOnContextMenuRequested(ev -> {
            Optional.ofNullable(menuRef.get()).ifPresent(ContextMenu::hide);
            ContextMenu menu = menuSupplier.get();
            menuRef.set(menu);
            menu.show(content, ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });
    }

    private void applyMessageSelectionState(HBox row, MeshMessage msg) {
        setStyleClassPresence(row, "chat-message-row-selected", actions.isMessageSelected(msg));
    }

    /**
     * Copies message text to the system clipboard.
     *
     * @param text message text
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
