package com.meshtastic.client.forms;

import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.components.chat.ChatBotCommandHelper;
import com.meshtastic.client.components.chat.ChatInputBar;
import com.meshtastic.client.components.chat.ChatListCell;
import com.meshtastic.client.components.chat.ChatNameResolver;
import com.meshtastic.client.components.chat.ChannelPropertiesDialog;
import com.meshtastic.client.components.chat.CreateChannelDialog;
import com.meshtastic.client.components.chat.MessageBubbleFactory;
import com.meshtastic.client.components.chat.NodeInfoFormatter;
import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.MessageListenerService;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.DrawerManager;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.SystemForm;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.MeshProtos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

@SystemForm(name = "Чат", description = "Чаты пользователя", tags = {"чаты", "каналы"})
public class FormChat extends Form {

    private static final int REQUEST_TIMEOUT_SECONDS = 360;
    private static final int UNREAD_FOCUS_THRESHOLD = 2;
    private static final double BOTTOM_READ_SLOP_PX = 24.0;
    private static final double PAGE_LOAD_EDGE_THRESHOLD = 0.1;
    private static final int PAGE_SIZE = 50;
    private static final int MAX_WINDOW_PAGES = 3;
    private static final int MAX_LOADED_MESSAGES = PAGE_SIZE * MAX_WINDOW_PAGES;
    private static final String WINDOWS_HIT_TEST_BACKGROUND = "-fx-background-color: rgba(0,0,0,0.004);";

    // === Левая панель: список чатов ===
    private ListView<ChatItem> chatListView;
    private final ObservableList<ChatItem> chatItems = FXCollections.observableArrayList();
    private FilteredList<ChatItem> filteredChats;

    // === Правая панель ===
    private VBox detailPane;
    private ChatItem selectedChat;

    // Плейсхолдер
    private VBox placeholderBox;

    // Заголовок чата
    private HBox chatHeader;
    private StackPane headerAvatarPane;
    private Label headerAvatarLabel;
    private Label headerNameLabel;
    private Separator headerSep;

    // Область сообщений
    private ScrollPane messageScrollPane;
    private VBox messageContainer;
    private StackPane messageArea; // обёртка: scrollPane + кнопка «вниз»
    private Button scrollDownBtn;
    private Label scrollDownBadge;
    private int newMessageWhileScrolled = 0;

    // Панель ввода
    private ChatInputBar chatInputBar;
    private Button newChatBtn;
    private ContextMenu newChatMenu;

    // === Компоненты ===
    private TracerouteView tracerouteView;
    private MessageBubbleFactory bubbleFactory;
    private ChatNameResolver nameResolver;

    // === Данные ===
    private DeviceState state;
    private ProtocolHandler protocolHandler;

    // Трекинг непрочитанных: "ch:INDEX" или "dm:NODEID" → кол-во прочитанных сообщений
    private final Map<String, Integer> lastReadCounts = new HashMap<>();

    // Пагинация сообщений из БД
    private long oldestLoadedDbId = Long.MAX_VALUE;
    private long newestLoadedDbId = 0;
    private long latestKnownDbId = 0;
    private boolean allHistoryLoaded = false;
    private boolean allNewerHistoryLoaded = true;
    private boolean loadingOlderMessages = false;
    private boolean loadingNewerMessages = false;
    private final List<MeshMessage> loadedMessages = new ArrayList<>();
    private final Map<Long, HBox> loadedMessageRows = new HashMap<>();
    private int openingChatUnreadCount = 0;
    private final Map<String, ChatScrollState> savedChatScrollStates = new HashMap<>();
    // Трекинг статусов исходящих сообщений для обновления при ACK/NAK
    private final Map<Integer, Label> pendingStatusLabels = new HashMap<>();

    // Активные запросы с обратным отсчётом (Trace/Инфо), переживают переключение чатов
    private final List<PendingCountdown> pendingCountdowns = new ArrayList<>();

    /** Состояние активного запроса с обратным отсчётом */
    private static class PendingCountdown {
        final String chatType;
        final String chatKey;
        final String prefix;
        final int[] remaining;
        final boolean[] done = {false};
        EmojiTextFlow countdownLabel;  // пересоздаётся при переключении чатов
        HBox tempBubble;       // пересоздаётся при переключении чатов
        Runnable cancelAction; // действие при отмене (останавливает таймер, убирает слушатель)

        PendingCountdown(String chatType, String chatKey, String prefix, int totalSeconds) {
            this.chatType = chatType;
            this.chatKey = chatKey;
            this.prefix = prefix;
            this.remaining = new int[]{totalSeconds};
        }
    }

    private static class ChatScrollState {
        final long anchorDbId;
        final double anchorOffset;
        final boolean atBottom;

        ChatScrollState(long anchorDbId, double anchorOffset, boolean atBottom) {
            this.anchorDbId = anchorDbId;
            this.anchorOffset = anchorOffset;
            this.atBottom = atBottom;
        }
    }

    private boolean suppressSelectionListener;
    private boolean formVisible;
    private int scrollStateSyncSuspendCount;
    private final AtomicBoolean messageRefreshQueued = new AtomicBoolean();
    private final AtomicBoolean messageRefreshDirty = new AtomicBoolean();

    private final Runnable messageListener = this::scheduleMessageRefresh;
    private final Runnable connectionListener = () -> Platform.runLater(this::rebindState);
    private final ChangeListener<Number> chatFontSizeListener =
            (obs, oldValue, newValue) -> Platform.runLater(this::handleChatFontSizeChanged);

    public FormChat() {
        initComponents();
        applyChatTypography();
    }

    /**
     * При бурном трафике отдельный Platform.runLater на каждое событие быстро
     * раздувает FX-очередь. Держим не более одного запланированного refresh-прохода,
     * а параллельные события лишь помечают состояние как dirty.
     */
    private void scheduleMessageRefresh() {
        messageRefreshDirty.set(true);
        if (!messageRefreshQueued.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(this::flushQueuedMessageRefresh);
    }

    private void flushQueuedMessageRefresh() {
        while (messageRefreshDirty.getAndSet(false)) {
            refreshCurrentChat();
            reloadChatList();
        }
        messageRefreshQueued.set(false);
        if (messageRefreshDirty.get() && messageRefreshQueued.compareAndSet(false, true)) {
            Platform.runLater(this::flushQueuedMessageRefresh);
        }
    }

    @Override
    public void formInit() {
        ConnectionManager.getInstance().addListener(connectionListener);
        TypographyManager.chatFontSizeProperty().addListener(chatFontSizeListener);
        // Загрузить сохранённые счётчики прочитанных сообщений из БД
        lastReadCounts.putAll(MessageDbService.getInstance().loadAllReadCounts(currentOwnerNodeId()));
        rebindState();
    }

    @Override
    public void formOpen() {
        formVisible = true;
        rebindState();
        if (selectedChat != null) {
            suspendScrollStateSync();
            try {
                ChatScrollState savedState = getSavedScrollState(selectedChat);
                if (savedState != null && !savedState.atBottom) {
                    restoreSavedScrollPosition(savedState);
                    refreshUnreadTailIndicatorLater();
                    return;
                }
                int unreadCount = getUnreadCount(selectedChat);
                if (focusUnreadMessages(unreadCount)) {
                    refreshUnreadTailIndicatorLater();
                }
            } finally {
                resumeScrollStateSyncLater();
            }
        }
    }

    @Override
    public void formClose() {
        saveCurrentChatScrollState();
        formVisible = false;
        if (bubbleFactory != null) {
            bubbleFactory.hideOpenReactionPopup();
        }
    }

    @Override
    public void formRefresh() {
        reloadChatList();
    }

    // ==================== UI ====================

    private void initComponents() {
        getStyleClass().add("chat-form");

        // --- Левая панель: поиск + список чатов ---
        VBox leftPane = new VBox();
        leftPane.getStyleClass().add("chat-list-pane");
        if (OsDetect.isWindows() && !AppPreferences.isDisableEffectsEffective()) {
            leftPane.setStyle(WINDOWS_HIT_TEST_BACKGROUND);
        }

        TextField searchField = new TextField();
        searchField.setPromptText("🔍 Поиск чатов");
        searchField.getStyleClass().add("chat-search-field");

        newChatBtn = new Button("✎");
        newChatBtn.getStyleClass().add("chat-new-btn");
        newChatBtn.setTooltip(new Tooltip("Новый чат"));
        newChatBtn.setOnAction(e -> showNewChatDialog());

        HBox searchBox = new HBox(8, searchField, newChatBtn);
        searchBox.setPadding(new Insets(8));
        searchBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        filteredChats = new FilteredList<>(chatItems, c -> true);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            String query = newVal == null ? "" : newVal.trim().toLowerCase(Locale.ROOT);
            filteredChats.setPredicate(chat -> query.isEmpty()
                    || containsIgnoreCase(chat.getDisplayName(), query)
                    || containsIgnoreCase(chat.getLastMessageText(), query));
        });

        SortedList<ChatItem> sortedChats = new SortedList<>(filteredChats,
                Comparator.comparingLong(ChatItem::getLastMessageTime).reversed());

        chatListView = new ListView<>(sortedChats);
        chatListView.getStyleClass().add("chat-list-view");
        if (OsDetect.isWindows() && !AppPreferences.isDisableEffectsEffective()) {
            chatListView.setStyle(WINDOWS_HIT_TEST_BACKGROUND);
        }
        chatListView.setCellFactory(lv -> new ChatListCell(
                this::deleteChat,
                this::showChannelProperties,
                this::toggleChatMute));
        chatListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldItem, newItem) -> {
                    if (suppressSelectionListener) { return; }
                    saveCurrentChatScrollState();
                    if (newItem != null) {
                        selectedChat = newItem;
                        int unreadCount = newItem.getUnreadCount();
                        openingChatUnreadCount = unreadCount;
                        openChat(newItem);
                    } else {
                        closeChat();
                    }
                });

        StackPane listWrapper = new StackPane(chatListView);
        VBox.setVgrow(listWrapper, Priority.ALWAYS);

        leftPane.getChildren().addAll(searchBox, listWrapper);

        // --- Правая панель ---
        buildRightPanelComponents();

        detailPane = new VBox();
        detailPane.getStyleClass().add("chat-detail-pane");
        detailPane.setMinWidth(300); // ~30 символов минимальная ширина
        detailPane.getChildren().add(placeholderBox);

        // --- SplitPane ---
        SplitPane splitPane = new SplitPane(leftPane, detailPane);
        splitPane.setDividerPositions(AppPreferences.getChatDividerPos());
        splitPane.getStyleClass().add("chat-split-pane");
        SplitPane.setResizableWithParent(leftPane, false);
        splitPane.getDividers().get(0).positionProperty().addListener((obs, oldVal, newVal) ->
                AppPreferences.setChatDividerPos(newVal.doubleValue()));

        getChildren().add(splitPane);

        splitPane.prefWidthProperty().bind(widthProperty());
        splitPane.prefHeightProperty().bind(heightProperty());
    }

    /** Создать все компоненты правой панели один раз */
    private void buildRightPanelComponents() {
        // Плейсхолдер
        placeholderBox = new VBox();
        placeholderBox.setAlignment(Pos.CENTER);
        VBox.setVgrow(placeholderBox, Priority.ALWAYS);
        Label placeholder = new Label("Выберите, кому хотели бы написать");
        placeholder.getStyleClass().add("form-placeholder-label");
        placeholder.setWrapText(true);
        placeholderBox.getChildren().add(placeholder);

        // Заголовок
        headerAvatarPane = new StackPane();
        headerAvatarPane.setMinSize(36, 36);
        headerAvatarPane.setPrefSize(36, 36);
        headerAvatarPane.setMaxSize(36, 36);
        headerAvatarLabel = new Label();
        headerAvatarLabel.setTextFill(Color.WHITE);
        headerAvatarLabel.setPadding(Insets.EMPTY);
        headerAvatarPane.getChildren().add(headerAvatarLabel);

        headerNameLabel = new Label();
        headerNameLabel.getStyleClass().add("chat-header-name");

        chatHeader = new HBox(10, headerAvatarPane, headerNameLabel);
        chatHeader.setAlignment(Pos.CENTER_LEFT);
        chatHeader.setPadding(new Insets(10, 15, 10, 15));
        chatHeader.getStyleClass().add("chat-header");

        headerSep = new Separator();
        headerSep.getStyleClass().add("chat-header-separator");

        // Область сообщений
        messageContainer = new VBox(6);
        messageContainer.setPadding(new Insets(10, 15, 10, 15));
        messageContainer.getStyleClass().add("chat-message-container");
        messageContainer.setAlignment(Pos.BOTTOM_LEFT);

        nameResolver = new ChatNameResolver(state);

        tracerouteView = new TracerouteView(
                messageContainer.widthProperty(),
                nameResolver::resolveNodeName,
                this::confirmDeleteMessage);

        bubbleFactory = new MessageBubbleFactory(
                state,
                messageContainer.widthProperty(),
                new MessageBubbleFactory.BubbleActions() {
                    @Override public void startReply(MeshMessage msg) { FormChat.this.startReply(msg); }
                    @Override public void requestTraceroute(MeshMessage msg) { FormChat.this.requestTraceroute(msg); }
                    @Override public void requestNodeInfo(MeshMessage msg) { FormChat.this.requestNodeInfo(msg); }
                    @Override public void sendReaction(MeshMessage msg, String emoji) { FormChat.this.sendReaction(msg, emoji); }
                    @Override public void confirmDeleteMessage(MeshMessage msg, HBox row) { FormChat.this.confirmDeleteMessage(msg, row); }
                    @Override public boolean retryMessage(MeshMessage msg) { return FormChat.this.retryMessage(msg); }
                },
                pendingStatusLabels);
        bubbleFactory.setTracerouteView(tracerouteView);

        messageScrollPane = new ScrollPane(messageContainer);
        messageScrollPane.setFitToWidth(true);
        messageScrollPane.setFitToHeight(true);
        messageScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        messageScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        messageScrollPane.getStyleClass().add("chat-message-scroll");
        VBox.setVgrow(messageScrollPane, Priority.ALWAYS);

        // Кнопка «прокрутка вниз» — полупрозрачная стрелка поверх чата
        scrollDownBtn = new Button("↓");
        scrollDownBtn.getStyleClass().add("chat-scroll-down-btn");
        scrollDownBtn.setVisible(false);
        scrollDownBtn.setOnAction(e -> {
            if (allNewerHistoryLoaded) {
                scrollToBottom();
            } else {
                jumpToLatestMessages();
            }
            newMessageWhileScrolled = 0;
            updateScrollDownBadge();
            if (formVisible && selectedChat != null && isAtLiveTail()) { markAsRead(selectedChat); }
        });

        // Бейдж с количеством новых сообщений поверх кнопки
        scrollDownBadge = new Label();
        scrollDownBadge.getStyleClass().add("chat-scroll-down-badge");
        scrollDownBadge.setVisible(false);
        scrollDownBadge.setMouseTransparent(true);

        StackPane scrollDownWrapper = new StackPane(scrollDownBtn, scrollDownBadge);
        scrollDownWrapper.setPickOnBounds(false);
        scrollDownWrapper.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(scrollDownBadge, Pos.TOP_RIGHT);
        StackPane.setAlignment(scrollDownWrapper, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(scrollDownWrapper, new Insets(0, 20, 15, 0));

        // Обёртка: scrollPane + кнопка «вниз» (кнопка поверх содержимого)
        messageArea = new StackPane(messageScrollPane, scrollDownWrapper);
        VBox.setVgrow(messageArea, Priority.ALWAYS);

        // Подгрузка старых сообщений при скролле наверх + показ/скрытие кнопки «вниз»
        messageScrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() < PAGE_LOAD_EDGE_THRESHOLD && !allHistoryLoaded && !loadingOlderMessages) {
                loadOlderMessages();
            }
            if (newVal.doubleValue() > 1.0 - PAGE_LOAD_EDGE_THRESHOLD
                    && !allNewerHistoryLoaded
                    && !loadingNewerMessages) {
                loadNewerMessages();
            }
            boolean atBottom = isAtLiveTail();
            scrollDownBtn.setVisible(!atBottom);
            if (!atBottom) {
                refreshUnreadTailIndicator();
            } else {
                if (newMessageWhileScrolled > 0) {
                    newMessageWhileScrolled = 0;
                    updateScrollDownBadge();
                }
                if (formVisible && selectedChat != null && getUnreadCount(selectedChat) > 0) {
                    markAsRead(selectedChat);
                }
            }
            if (!loadingOlderMessages && !isScrollStateSyncSuspended()) {
                saveCurrentChatScrollState();
            }
        });

        // При изменении высоты контента (перенос строк при ресайзе) —
        // держать скролл внизу, чтобы баблы расширялись визуально вверх
        messageContainer.heightProperty().addListener((obs, oldH, newH) -> {
            if (formVisible && isAtLiveTail()) {
                Platform.runLater(() -> messageScrollPane.setVvalue(1.0));
            }
        });

        chatInputBar = new ChatInputBar(
                request -> {
                    if (selectedChat == null || state == null || protocolHandler == null) {
                        return;
                    }
                    if (selectedChat.getType() == ChatItem.ChatType.CHANNEL) {
                        MessageService.sendChannelMessage(
                                protocolHandler, state,
                                selectedChat.getChannelIndex(),
                                request.text(), request.replyId());
                    } else {
                        NodeData peerNode = NodeUtils.resolveNode(state, selectedChat.getPeerNodeId());
                        if (peerNode != null && peerNode.isUnmessagable()) {
                            Toast.show(Toast.Type.WARNING, "Нода объявила, что не принимает личные сообщения");
                            return;
                        }
                        MeshMessage sent = MessageService.sendDirectMessage(
                                protocolHandler, state,
                                selectedChat.getPeerNodeId(),
                                request.text(), request.replyId());
                        if (sent == null) {
                            if (peerNode != null && peerNode.isUnmessagable()) {
                                Toast.show(Toast.Type.WARNING, "Нода объявила, что не принимает личные сообщения");
                            } else {
                                Toast.show(Toast.Type.ERROR, "Не удалось определить ноду для DM");
                            }
                            return;
                        }
                    }

                    // Локально уже сохранили исходящее сообщение в БД и DeviceState.
                    // Подтягиваем его в открытую беседу сразу, не дожидаясь асинхронного
                    // messageListener, чтобы UI не выглядел "немым" при проблемах с RX/ACK.
                    refreshCurrentChatAfterLocalSend();
                },
                this::handleBotCommand,
                query -> ChatBotCommandHelper.suggestNodes(listBotCommandNodes(), query, 8)
        );
    }

    // ==================== Правая панель: открытие/закрытие чата ====================

    /**
     * Программно открыть приватный DM-чат с указанной нодой.
     * Вызывается извне (например, из NodeDetailContent) после навигации на эту форму.
     * Если чат с этим пиром ещё не существует в списке — добавляет его.
     */
    public void openDirectChat(String peerNodeId, NodeData peerNode) {
        saveCurrentChatScrollState();
        ChatItem dm = ChatItem.fromDirectMessage(
                peerNodeId,
                peerNode,
                (MeshMessage) null,
                0,
                AppPreferences.isChatMuted(
                        currentOwnerNodeId(),
                        AppPreferences.composeChatPreferenceId("dm", peerNodeId)));
        openingChatUnreadCount = 0;

        // Добавить в список если DM с этим пиром ещё нет
        boolean exists = chatItems.stream()
                .anyMatch(item -> item.getType() == ChatItem.ChatType.DIRECT_MESSAGE
                               && Objects.equals(item.getPeerNodeId(), peerNodeId));
        if (!exists) {
            chatItems.add(dm);
        }

        // Выделить в списке (suppress → не вызвать openChat дважды)
        suppressSelectionListener = true;
        try {
            chatListView.getItems().stream()
                    .filter(item -> chatItemMatches(item, dm))
                    .findFirst()
                    .ifPresent(chatListView.getSelectionModel()::select);
        } finally {
            suppressSelectionListener = false;
        }

        openChat(dm);
    }

    private void openChat(ChatItem chat) {
        bubbleFactory.hideOpenReactionPopup();
        this.selectedChat = chat;

        // Обновить заголовок
        headerAvatarLabel.setText(chat.getAvatarText());
        headerAvatarLabel.setFont(Font.font("Roboto", FontWeight.BOLD,
                NodeUtils.chatAvatarFontSize(chat.getAvatarText().length(), 36)));
        headerAvatarPane.setStyle("-fx-background-color: " + chat.getAvatarColor() +
                "; -fx-background-radius: 18;");
        headerNameLabel.setText(chat.getDisplayName());

        // Показать header + messages + input
        detailPane.getChildren().clear();
        detailPane.getChildren().addAll(
                chatHeader, headerSep, messageArea,
                chatInputBar.getInputSeparator(), chatInputBar);

        // Сбросить режим ответа при переключении чата
        chatInputBar.cancelReply();

        // Загрузить последние сообщения из БД
        loadInitialMessages(true);
        // Восстановить пузыри активных запросов (Trace/Инфо) для этого чата
        restorePendingCountdowns();

        updateInputEnabled();
        chatInputBar.focusInput();
    }

    private void handleChatFontSizeChanged() {
        applyChatTypography();
        if (chatListView != null) {
            chatListView.refresh();
        }
        if (selectedChat != null) {
            refreshLoadedMessageRows();
        }
    }

    private void applyChatTypography() {
        setStyle("-fx-font-size: " + TypographyManager.getChatFontSize() + "px;");
        if (getScene() != null) {
            applyCss();
            requestLayout();
        }
    }

    private void closeChat() {
        saveCurrentChatScrollState();
        bubbleFactory.hideOpenReactionPopup();
        this.selectedChat = null;
        clearLoadedMessageState();
        detailPane.getChildren().clear();
        detailPane.getChildren().add(placeholderBox);
    }

    // ==================== Сообщения: загрузка из БД с пагинацией ====================

    /** Определить тип и ключ чата для запросов к MessageDbService */
    private String currentChatType() {
        if (selectedChat == null) { return null; }
        return selectedChat.getType() == ChatItem.ChatType.CHANNEL ? "channel" : "dm";
    }

    private String currentChatKey() {
        if (selectedChat == null) { return null; }
        return selectedChat.getType() == ChatItem.ChatType.CHANNEL
                ? String.valueOf(selectedChat.getChannelIndex()) : selectedChat.getPeerNodeId();
    }

    private String currentOwnerNodeId() {
        return state != null ? String.format("!%08x", state.getMyNodeNum()) : "";
    }

    /**
     * Загрузить последние PAGE_SIZE сообщений из БД.
     * Если накопилось несколько непрочитанных, показать верхнюю границу непрочитанного окна.
     */
    private void loadInitialMessages(boolean restoreSavedState) {
        if (selectedChat == null) { return; }
        pendingStatusLabels.clear();
        suspendScrollStateSync();

        try {
            MessageDbService db = MessageDbService.getInstance();
            String chatType = currentChatType();
            String chatKey = currentChatKey();
            String ownerNodeId = currentOwnerNodeId();

            db.backfillMissingReplyTexts(chatType, chatKey, ownerNodeId);
            List<MeshMessage> msgs = db.loadLast(chatType, chatKey, PAGE_SIZE, ownerNodeId);
            attachReactions(msgs);

            clearLoadedMessageState();
            messageContainer.getChildren().clear();
            for (MeshMessage msg : msgs) {
                appendLoadedMessageRow(msg);
            }

            if (!msgs.isEmpty()) {
                oldestLoadedDbId = msgs.getFirst().getDbId();
                newestLoadedDbId = msgs.getLast().getDbId();
                latestKnownDbId = newestLoadedDbId;
            } else {
                oldestLoadedDbId = Long.MAX_VALUE;
                newestLoadedDbId = 0;
                latestKnownDbId = 0;
            }
            allHistoryLoaded = msgs.size() < PAGE_SIZE;
            allNewerHistoryLoaded = true;
            loadingOlderMessages = false;
            loadingNewerMessages = false;
            newMessageWhileScrolled = 0;
            updateScrollDownBadge();
            requestMessageViewportLayout();

            if (restoreSavedState && restoreSavedScrollPosition()) {
                openingChatUnreadCount = 0;
                refreshUnreadTailIndicatorLater();
                return;
            }
            positionInitialMessages(openingChatUnreadCount);
            openingChatUnreadCount = 0;
        } finally {
            resumeScrollStateSyncLater();
        }
    }

    private void jumpToLatestMessages() {
        if (selectedChat == null) {
            return;
        }
        openingChatUnreadCount = 0;
        loadInitialMessages(false);
        restorePendingCountdowns();
        scrollToBottom();
    }

    /**
     * Подгрузить PAGE_SIZE старых сообщений (скролл вверх). Сохранить позицию скролла.
     */
    private void loadOlderMessages() {
        if (allHistoryLoaded || loadingOlderMessages || selectedChat == null) { return; }
        loadingOlderMessages = true;
        ChatScrollState viewportAnchor = captureViewportAnchor();
        suspendScrollStateSync();

        try {
            MessageDbService db = MessageDbService.getInstance();
            String chatType = currentChatType();
            String chatKey = currentChatKey();

            List<MeshMessage> older = db.loadBefore(chatType, chatKey, oldestLoadedDbId, PAGE_SIZE, currentOwnerNodeId());

            if (older.isEmpty()) {
                allHistoryLoaded = true;
                return;
            }

            prependOlderMessages(older);
            allHistoryLoaded = older.size() < PAGE_SIZE;
            trimLoadedWindowFromBottomIfNeeded();
            requestMessageViewportLayout();
            restoreViewportAnchorLater(viewportAnchor);
        } finally {
            Platform.runLater(() -> {
                loadingOlderMessages = false;
                refreshUnreadTailIndicator();
            });
            resumeScrollStateSyncLater();
        }
    }

    private void loadNewerMessages() {
        if (allNewerHistoryLoaded || loadingNewerMessages || selectedChat == null) { return; }
        loadingNewerMessages = true;
        ChatScrollState viewportAnchor = captureViewportAnchor();
        suspendScrollStateSync();

        try {
            MessageDbService db = MessageDbService.getInstance();
            String chatType = currentChatType();
            String chatKey = currentChatKey();

            List<MeshMessage> newer = db.loadAfter(
                    chatType,
                    chatKey,
                    newestLoadedDbId,
                    PAGE_SIZE,
                    currentOwnerNodeId());

            if (newer.isEmpty()) {
                allNewerHistoryLoaded = newestLoadedDbId >= latestKnownDbId;
                return;
            }

            appendNewerMessages(newer);
            trimLoadedWindowFromTopIfNeeded();
            allNewerHistoryLoaded = newestLoadedDbId >= latestKnownDbId;
            requestMessageViewportLayout();
            restoreViewportAnchorLater(viewportAnchor);
        } finally {
            Platform.runLater(() -> {
                loadingNewerMessages = false;
                refreshUnreadTailIndicator();
            });
            resumeScrollStateSyncLater();
        }
    }

    /**
     * Инкрементальное обновление: загрузить новые сообщения из БД (id > newestLoadedDbId).
     * Вызывается при срабатывании messageListener.
     */
    private void refreshCurrentChat() {
        if (selectedChat == null) { return; }
        ChatScrollState preservedScrollState = !formVisible ? captureViewportAnchor() : null;
        boolean wasAtLiveTail = formVisible && isAtLiveTail();

        // Обновить статусы доставки для отправленных сообщений (ACK/NAK)
        MessageDbService db = MessageDbService.getInstance();
        pendingStatusLabels.entrySet().removeIf(entry -> {
            MeshMessage updated = db.findByPacketId(entry.getKey());
            if (updated != null && updated.getStatus() != null
                    && updated.getStatus() != MeshMessage.DeliveryStatus.SENDING) {
                MeshMessage loaded = syncLoadedMessageDeliveryStatus(updated);
                bubbleFactory.refreshStatusLabel(entry.getValue(), loaded != null ? loaded : updated);
                return true;
            }
            return false;
        });
        String chatType = currentChatType();
        String chatKey = currentChatKey();

        List<MeshMessage> newMsgs = db.loadAfter(chatType, chatKey, latestKnownDbId, currentOwnerNodeId());
        if (newMsgs.isEmpty() && shouldReloadChatAfterDatabaseReset(db, chatType, chatKey)) {
            loadInitialMessages(false);
            refreshLoadedMessageRows();
            return;
        }
        if (!newMsgs.isEmpty()) {
            latestKnownDbId = newMsgs.getLast().getDbId();
            boolean shouldAppendToViewport = allNewerHistoryLoaded;
            boolean shouldAutoscroll = shouldAppendToViewport && wasAtLiveTail;
            boolean shouldMarkReadImmediately =
                    shouldMarkNewMessagesReadImmediately(formVisible, wasAtLiveTail, newMsgs);

            if (shouldAppendToViewport) {
                appendNewerMessages(newMsgs);
                trimLoadedWindowFromTopIfNeeded();
                allNewerHistoryLoaded = true;
                requestMessageViewportLayout();
                if (shouldAutoscroll) {
                    newMessageWhileScrolled = 0;
                    updateScrollDownBadge();
                    scrollToBottom();
                    if (shouldMarkReadImmediately) { markAsRead(selectedChat); }
                    markCurrentChatAsReadIfViewingTailLater();
                } else {
                    if (!formVisible) {
                        restoreViewportAnchorLater(preservedScrollState);
                    }
                    refreshUnreadTailIndicatorLater();
                }
            } else {
                allNewerHistoryLoaded = false;
                refreshUnreadTailIndicatorLater();
            }
        }

        refreshLoadedMessageRows();
    }

    private boolean shouldReloadChatAfterDatabaseReset(MessageDbService db, String chatType, String chatKey) {
        if (db == null || chatType == null || chatKey == null || selectedChat == null || latestKnownDbId <= 0) {
            return false;
        }

        List<MeshMessage> latest = db.loadLast(chatType, chatKey, 1, currentOwnerNodeId());
        return hasDatabaseRewind(latestKnownDbId, latest, loadedMessages);
    }

    static boolean hasDatabaseRewind(long latestKnownDbId,
                                     List<MeshMessage> latestFromDb,
                                     List<MeshMessage> loadedMessages) {
        if (latestKnownDbId <= 0 || latestFromDb == null || latestFromDb.isEmpty()) {
            return false;
        }

        MeshMessage newestPersisted = latestFromDb.getFirst();
        if (newestPersisted == null || newestPersisted.getDbId() >= latestKnownDbId) {
            return false;
        }

        return loadedMessages == null || loadedMessages.stream()
                .noneMatch(msg -> msg.getDbId() == newestPersisted.getDbId());
    }

    static boolean shouldMarkNewMessagesReadImmediately(boolean formVisible,
                                                        boolean wasAtLiveTail,
                                                        List<MeshMessage> newMessages) {
        return formVisible
                && wasAtLiveTail
                && newMessages != null
                && newMessages.stream().anyMatch(FormChat::isUnreadEligible);
    }

    private void markCurrentChatAsReadIfViewingTailLater() {
        if (!formVisible || selectedChat == null) {
            return;
        }
        ChatItem chat = selectedChat;
        Platform.runLater(() -> {
            if (chat == selectedChat && formVisible && isAtLiveTail() && getUnreadCount(chat) > 0) {
                markAsRead(chat);
            }
        });
    }

    private MeshMessage syncLoadedMessageDeliveryStatus(MeshMessage updated) {
        if (updated == null || updated.getPacketId() == 0) {
            return null;
        }
        for (MeshMessage loaded : loadedMessages) {
            if (loaded.getPacketId() == updated.getPacketId()) {
                loaded.setStatus(updated.getStatus());
                loaded.setErrorReason(updated.getErrorReason());
                return loaded;
            }
        }
        return null;
    }

    /**
     * Сразу отражает локально отправленное сообщение в открытом чате.
     * Использует тот же DB-backed путь, что и обычный messageListener,
     * чтобы не плодить отдельную логику рендера и не расходиться со статусами.
     */
    private void refreshCurrentChatAfterLocalSend() {
        reloadChatList();
        jumpToLatestMessages();
    }

    private void refreshCurrentChatAfterLocalReaction() {
        refreshLoadedMessageRows();
    }

    private void clearLoadedMessageState() {
        loadedMessages.clear();
        loadedMessageRows.clear();
        oldestLoadedDbId = Long.MAX_VALUE;
        newestLoadedDbId = 0;
        latestKnownDbId = 0;
        allHistoryLoaded = false;
        allNewerHistoryLoaded = true;
        loadingOlderMessages = false;
        loadingNewerMessages = false;
    }

    private void appendLoadedMessageRow(MeshMessage msg) {
        loadedMessages.add(msg);
        HBox row = bubbleFactory.build(msg);
        loadedMessageRows.put(msg.getDbId(), row);
        messageContainer.getChildren().add(row);
    }

    private void prependLoadedMessageRow(MeshMessage msg) {
        loadedMessages.add(0, msg);
        HBox row = bubbleFactory.build(msg);
        loadedMessageRows.put(msg.getDbId(), row);
        messageContainer.getChildren().addFirst(row);
    }

    private void prependOlderMessages(List<MeshMessage> older) {
        attachReactions(older);
        for (int i = older.size() - 1; i >= 0; i--) {
            prependLoadedMessageRow(older.get(i));
        }
        recalcLoadedBounds();
    }

    private void appendNewerMessages(List<MeshMessage> newer) {
        attachReactions(newer);
        for (MeshMessage msg : newer) {
            appendLoadedMessageRow(msg);
        }
        recalcLoadedBounds();
    }

    private void trimLoadedWindowFromTopIfNeeded() {
        trimLoadedWindowIfNeeded(true);
    }

    private void trimLoadedWindowFromBottomIfNeeded() {
        trimLoadedWindowIfNeeded(false);
    }

    private void trimLoadedWindowIfNeeded(boolean trimFromTop) {
        int excess = loadedMessages.size() - MAX_LOADED_MESSAGES;
        if (excess <= 0) {
            return;
        }

        for (int i = 0; i < excess; i++) {
            MeshMessage removed = trimFromTop ? loadedMessages.removeFirst() : loadedMessages.removeLast();
            HBox row = loadedMessageRows.remove(removed.getDbId());
            if (row != null) {
                messageContainer.getChildren().remove(row);
            }
        }

        if (trimFromTop) {
            allHistoryLoaded = false;
        } else {
            allNewerHistoryLoaded = false;
        }
        recalcLoadedBounds();
    }

    private void recalcLoadedBounds() {
        if (loadedMessages.isEmpty()) {
            oldestLoadedDbId = Long.MAX_VALUE;
            newestLoadedDbId = 0;
            return;
        }
        oldestLoadedDbId = loadedMessages.getFirst().getDbId();
        newestLoadedDbId = loadedMessages.getLast().getDbId();
    }

    private void attachReactions(List<MeshMessage> messages) {
        if (messages == null || messages.isEmpty() || selectedChat == null) {
            return;
        }
        MessageDbService db = MessageDbService.getInstance();
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();
        db.hydrateReplyTexts(messages, chatType, chatKey, ownerNodeId);

        Map<Integer, List<com.meshtastic.client.model.MessageReaction>> reactionsByTarget =
                db.loadReactionsByTargetPacketIds(
                        chatType,
                        chatKey,
                        ownerNodeId,
                        messages.stream().map(MeshMessage::getPacketId).toList());
        for (MeshMessage message : messages) {
            message.setReactions(reactionsByTarget.get(message.getPacketId()));
        }
    }

    private void refreshLoadedMessageRows() {
        if (loadedMessages.isEmpty() || selectedChat == null) {
            return;
        }

        attachReactions(loadedMessages);
        for (MeshMessage message : loadedMessages) {
            HBox oldRow = loadedMessageRows.get(message.getDbId());
            if (oldRow == null) {
                continue;
            }
            int index = messageContainer.getChildren().indexOf(oldRow);
            if (index < 0) {
                continue;
            }
            HBox newRow = bubbleFactory.build(message);
            messageContainer.getChildren().set(index, newRow);
            loadedMessageRows.put(message.getDbId(), newRow);
        }
        requestMessageViewportLayout();
    }

    /**
     * После переключения DM -> channel ScrollPane иногда остаётся в геометрии
     * предыдущего короткого чата до следующего resize/pulse. Принудительно
     * инвалидируем и пересчитываем viewport, чтобы сообщения появились сразу.
     */
    private void requestMessageViewportLayout() {
        relayoutMessageViewport();
        Platform.runLater(() -> {
            relayoutMessageViewport();
            Platform.runLater(this::relayoutMessageViewport);
        });
    }

    private void relayoutMessageViewport() {
        if (detailPane == null || messageArea == null || messageScrollPane == null || messageContainer == null) {
            return;
        }

        detailPane.requestLayout();
        messageArea.requestLayout();
        messageScrollPane.requestLayout();
        messageContainer.requestLayout();

        if (messageScrollPane.getScene() == null) {
            return;
        }

        detailPane.applyCss();
        detailPane.layout();
        messageArea.applyCss();
        messageArea.layout();
        messageScrollPane.applyCss();
        messageScrollPane.layout();
        messageContainer.applyCss();
        messageContainer.layout();
    }

    private void suspendScrollStateSync() {
        scrollStateSyncSuspendCount++;
    }

    private void resumeScrollStateSyncLater() {
        Platform.runLater(() -> Platform.runLater(() -> {
            if (scrollStateSyncSuspendCount > 0) {
                scrollStateSyncSuspendCount--;
            }
        }));
    }

    private boolean isScrollStateSyncSuspended() {
        return scrollStateSyncSuspendCount > 0;
    }

    private boolean isScrolledToBottom() {
        if (messageContainer == null || messageScrollPane == null) {
            return true;
        }

        messageScrollPane.applyCss();
        messageScrollPane.layout();
        messageContainer.applyCss();
        messageContainer.layout();

        double contentHeight = messageContainer.getLayoutBounds().getHeight();
        double viewportHeight = messageScrollPane.getViewportBounds().getHeight();
        double maxOffset = Math.max(0, contentHeight - viewportHeight);
        if (maxOffset <= 0) {
            return true;
        }

        double currentOffset = Math.max(0, Math.min(maxOffset, messageScrollPane.getVvalue() * maxOffset));
        return maxOffset - currentOffset <= BOTTOM_READ_SLOP_PX;
    }

    private boolean isAtLiveTail() {
        return allNewerHistoryLoaded && isScrolledToBottom();
    }

    private String chatScrollStateKey(ChatItem item) {
        if (item == null) {
            return "";
        }
        return item.getType() == ChatItem.ChatType.CHANNEL
                ? "channel:" + item.getChannelIndex()
                : "dm:" + item.getPeerNodeId();
    }

    private String chatPreferenceId(ChatItem item) {
        if (item == null) {
            return "";
        }
        return item.getType() == ChatItem.ChatType.CHANNEL
                ? AppPreferences.composeChatPreferenceId("channel", String.valueOf(item.getChannelIndex()))
                : AppPreferences.composeChatPreferenceId("dm", item.getPeerNodeId());
    }

    private String chatScrollCacheKey(ChatItem item) {
        return currentOwnerNodeId() + "|" + chatScrollStateKey(item);
    }

    private ChatScrollState getSavedScrollState(ChatItem item) {
        String chatId = chatScrollStateKey(item);
        ChatScrollState inMemory = savedChatScrollStates.get(chatScrollCacheKey(item));
        if (inMemory != null) {
            return inMemory;
        }

        AppPreferences.ChatScrollState persisted =
                AppPreferences.loadChatScrollState(currentOwnerNodeId(), chatId);
        if (persisted == null) {
            return null;
        }

        ChatScrollState restored = new ChatScrollState(
                persisted.getAnchorDbId(),
                persisted.getAnchorOffset(),
                persisted.isAtBottom());
        savedChatScrollStates.put(chatScrollCacheKey(item), restored);
        return restored;
    }

    private void saveCurrentChatScrollState() {
        if (selectedChat == null || loadedMessages.isEmpty()) {
            return;
        }
        ChatScrollState scrollState = captureCurrentChatScrollState();
        if (scrollState != null) {
            String chatId = chatScrollStateKey(selectedChat);
            savedChatScrollStates.put(chatScrollCacheKey(selectedChat), scrollState);
            AppPreferences.saveChatScrollState(
                    currentOwnerNodeId(),
                    chatId,
                    scrollState.anchorDbId,
                    scrollState.anchorOffset,
                    scrollState.atBottom);
        }
    }

    private ChatScrollState captureCurrentChatScrollState() {
        if (selectedChat == null || loadedMessages.isEmpty()) {
            return null;
        }

        if (isAtLiveTail()) {
            return new ChatScrollState(latestKnownDbId > 0 ? latestKnownDbId : newestLoadedDbId, 0, true);
        }

        return captureViewportAnchor();
    }

    private ChatScrollState captureViewportAnchor() {
        if (selectedChat == null || loadedMessages.isEmpty()) {
            return null;
        }

        messageScrollPane.applyCss();
        messageScrollPane.layout();
        messageContainer.applyCss();
        messageContainer.layout();

        double contentHeight = messageContainer.getLayoutBounds().getHeight();
        double viewportHeight = messageScrollPane.getViewportBounds().getHeight();
        double maxOffset = Math.max(0, contentHeight - viewportHeight);
        double topOffset = Math.max(0, Math.min(maxOffset, messageScrollPane.getVvalue() * maxOffset));

        for (MeshMessage message : loadedMessages) {
            HBox row = loadedMessageRows.get(message.getDbId());
            if (row == null) {
                continue;
            }
            double rowBottom = row.getBoundsInParent().getMaxY();
            if (rowBottom >= topOffset) {
                double rowTop = row.getBoundsInParent().getMinY();
                return new ChatScrollState(message.getDbId(), Math.max(0, topOffset - rowTop), false);
            }
        }

        MeshMessage lastLoaded = loadedMessages.getLast();
        return new ChatScrollState(lastLoaded.getDbId(), 0, false);
    }

    private void restoreViewportAnchorLater(ChatScrollState viewportAnchor) {
        if (viewportAnchor == null) {
            return;
        }
        Platform.runLater(() -> {
            alignMessageToViewport(viewportAnchor.anchorDbId, viewportAnchor.anchorOffset);
            Platform.runLater(() ->
                    alignMessageToViewport(viewportAnchor.anchorDbId, viewportAnchor.anchorOffset));
        });
    }

    private boolean restoreSavedScrollPosition() {
        ChatScrollState savedState = getSavedScrollState(selectedChat);
        if (savedState == null || savedState.atBottom) {
            return false;
        }
        ensureMessageLoaded(savedState.anchorDbId);
        if (!loadedMessageRows.containsKey(savedState.anchorDbId)) {
            return false;
        }
        restoreSavedScrollPosition(savedState);
        return true;
    }

    private void ensureMessageLoaded(long dbId) {
        if (selectedChat == null || dbId <= 0 || loadedMessageRows.containsKey(dbId)) {
            return;
        }

        MessageDbService db = MessageDbService.getInstance();
        while (!loadedMessageRows.containsKey(dbId)) {
            if (dbId < oldestLoadedDbId && !allHistoryLoaded) {
                List<MeshMessage> older = db.loadBefore(
                        currentChatType(),
                        currentChatKey(),
                        oldestLoadedDbId,
                        PAGE_SIZE,
                        currentOwnerNodeId());
                if (older.isEmpty()) {
                    allHistoryLoaded = true;
                    break;
                }
                prependOlderMessages(older);
                allHistoryLoaded = older.size() < PAGE_SIZE;
                trimLoadedWindowFromBottomIfNeeded();
                continue;
            }

            if (dbId > newestLoadedDbId && !allNewerHistoryLoaded) {
                List<MeshMessage> newer = db.loadAfter(
                        currentChatType(),
                        currentChatKey(),
                        newestLoadedDbId,
                        PAGE_SIZE,
                        currentOwnerNodeId());
                if (newer.isEmpty()) {
                    allNewerHistoryLoaded = newestLoadedDbId >= latestKnownDbId;
                    break;
                }
                appendNewerMessages(newer);
                trimLoadedWindowFromTopIfNeeded();
                allNewerHistoryLoaded = newestLoadedDbId >= latestKnownDbId;
                continue;
            }

            break;
        }
    }

    private int getUnreadCount(ChatItem item) {
        if (item == null) {
            return 0;
        }

        boolean isChannel = item.getType() == ChatItem.ChatType.CHANNEL;
        String dbType = isChannel ? "channel" : "dm";
        String dbKey = isChannel ? String.valueOf(item.getChannelIndex()) : item.getPeerNodeId();
        String readKey = (isChannel ? "ch:" : "dm:") + dbKey;
        int totalCount = MessageDbService.getInstance().getUnreadEligibleMessageCount(
                dbType, dbKey, currentOwnerNodeId());
        int lastRead = lastReadCounts.getOrDefault(readKey, 0);
        return Math.max(0, totalCount - lastRead);
    }

    private static boolean isUnreadEligible(MeshMessage message) {
        return message != null && !message.isOutgoing();
    }

    private int countUnreadEligibleMessagesInLoadedWindow() {
        int count = 0;
        for (MeshMessage message : loadedMessages) {
            if (isUnreadEligible(message)) {
                count++;
            }
        }
        return count;
    }

    private int findFirstUnreadLoadedIndex(int unreadCount) {
        if (unreadCount <= 0 || loadedMessages.isEmpty()) {
            return -1;
        }

        int unreadSeen = 0;
        int firstUnreadIndex = -1;
        for (int i = loadedMessages.size() - 1; i >= 0; i--) {
            if (!isUnreadEligible(loadedMessages.get(i))) {
                continue;
            }
            unreadSeen++;
            firstUnreadIndex = i;
            if (unreadSeen >= unreadCount) {
                break;
            }
        }
        return firstUnreadIndex;
    }

    private void positionInitialMessages(int unreadCount) {
        if (!focusUnreadMessages(unreadCount)) {
            newMessageWhileScrolled = 0;
            updateScrollDownBadge();
            scrollToBottom();
        } else {
            refreshUnreadTailIndicatorLater();
        }
    }

    private boolean focusUnreadMessages(int unreadCount) {
        if (unreadCount < UNREAD_FOCUS_THRESHOLD || loadedMessages.isEmpty()) {
            return false;
        }
        int firstUnreadIndex = findFirstUnreadLoadedIndex(unreadCount);
        if (firstUnreadIndex < 0) {
            return false;
        }
        scrollToMessage(firstUnreadIndex);
        return true;
    }

    private void restoreSavedScrollPosition(ChatScrollState savedState) {
        scrollToMessage(savedState.anchorDbId, savedState.anchorOffset);
    }

    private void refreshUnreadTailIndicatorLater() {
        Platform.runLater(() -> {
            refreshUnreadTailIndicator();
            Platform.runLater(this::refreshUnreadTailIndicator);
        });
    }

    private void refreshUnreadTailIndicator() {
        if (selectedChat == null || isAtLiveTail()) {
            newMessageWhileScrolled = 0;
            updateScrollDownBadge();
            return;
        }

        newMessageWhileScrolled = countUnreadMessagesBelowViewport();
        updateScrollDownBadge();
    }

    private int countUnreadMessagesBelowViewport() {
        if (selectedChat == null || loadedMessages.isEmpty()) {
            return 0;
        }

        int totalUnread = getUnreadCount(selectedChat);
        if (totalUnread <= 0) {
            return 0;
        }

        messageScrollPane.applyCss();
        messageScrollPane.layout();
        messageContainer.applyCss();
        messageContainer.layout();

        double contentHeight = messageContainer.getLayoutBounds().getHeight();
        double viewportHeight = messageScrollPane.getViewportBounds().getHeight();
        double maxOffset = Math.max(0, contentHeight - viewportHeight);
        double currentOffset = Math.max(0, Math.min(maxOffset, messageScrollPane.getVvalue() * maxOffset));
        double viewportBottom = currentOffset + viewportHeight - 1;

        int firstUnreadIndex = findFirstUnreadLoadedIndex(totalUnread);
        if (firstUnreadIndex < 0) {
            return totalUnread;
        }

        int unreadBelow = 0;
        for (int i = firstUnreadIndex; i < loadedMessages.size(); i++) {
            MeshMessage message = loadedMessages.get(i);
            if (!isUnreadEligible(message)) {
                continue;
            }
            HBox row = loadedMessageRows.get(message.getDbId());
            if (row != null && row.getBoundsInParent().getMinY() >= viewportBottom) {
                unreadBelow++;
            }
        }
        int loadedUnread = Math.min(totalUnread, countUnreadEligibleMessagesInLoadedWindow());
        return unreadBelow + Math.max(0, totalUnread - loadedUnread);
    }

    private void updateScrollDownBadge() {
        if (newMessageWhileScrolled > 0) {
            scrollDownBadge.setText(String.valueOf(newMessageWhileScrolled));
            scrollDownBadge.setVisible(true);
        } else {
            scrollDownBadge.setVisible(false);
        }
    }

    /** Прокрутка сообщений вниз с принудительным layout */
    private void scrollToBottom() {
        Platform.runLater(() -> {
            messageScrollPane.applyCss();
            messageScrollPane.layout();
            messageScrollPane.setVvalue(1.0);
            // Повторно после следующего pulse — ScrollPane может ещё не знать новый размер контента
            Platform.runLater(() -> messageScrollPane.setVvalue(1.0));
        });
    }

    private void scrollToMessage(int messageIndex) {
        if (messageIndex < 0 || messageIndex >= loadedMessages.size()) {
            return;
        }
        scrollToMessage(loadedMessages.get(messageIndex).getDbId(), 0);
    }

    private void scrollToMessage(long dbId, double anchorOffset) {
        Platform.runLater(() -> {
            alignMessageToViewport(dbId, anchorOffset);
            Platform.runLater(() -> alignMessageToViewport(dbId, anchorOffset));
        });
    }

    private void alignMessageToViewport(long dbId, double anchorOffset) {
        HBox target = loadedMessageRows.get(dbId);
        if (target == null) {
            return;
        }

        messageScrollPane.applyCss();
        messageScrollPane.layout();
        messageContainer.applyCss();
        messageContainer.layout();

        double contentHeight = messageContainer.getLayoutBounds().getHeight();
        double viewportHeight = messageScrollPane.getViewportBounds().getHeight();
        double maxOffset = Math.max(0, contentHeight - viewportHeight);
        if (maxOffset <= 0) {
            messageScrollPane.setVvalue(0.0);
            return;
        }

        double targetTop = Math.max(0, target.getBoundsInParent().getMinY() + anchorOffset - 12);
        double vvalue = Math.min(1.0, targetTop / maxOffset);
        messageScrollPane.setVvalue(vvalue);
    }

    /**
     * Добавить системное (бот) сообщение в указанный чат.
     * Сохраняет в БД. Обновляет UI и счётчик прочитанных, если чат сейчас открыт.
     */
    private void addSystemMessageTo(String chatType, String chatKey, String text) {
        MeshMessage sysMsg = new MeshMessage("!00000000", "!00000000", 0, text, System.currentTimeMillis() / 1000, false);
        sysMsg.setSystemMessage(true);
        MessageDbService.getInstance().save(sysMsg, chatType, chatKey, currentOwnerNodeId());
        if (isCurrentChat(chatType, chatKey)) {
            latestKnownDbId = Math.max(latestKnownDbId, sysMsg.getDbId());
            if (allNewerHistoryLoaded) {
                appendLoadedMessageRow(sysMsg);
                trimLoadedWindowFromTopIfNeeded();
                allNewerHistoryLoaded = true;
                requestMessageViewportLayout();
                scrollToBottom();
                markAsRead(selectedChat);
            } else {
                allNewerHistoryLoaded = false;
                refreshUnreadTailIndicatorLater();
            }
            reloadChatList();
        } else {
            reloadChatList();
        }
    }

    /**
     * Добавить результат traceroute: кастомный визуальный узел в UI + текстовый fallback в БД.
     */
    private void addTracerouteResult(String chatType, String chatKey,
                                     String targetName, MeshProtos.RouteDiscovery route) {
        String text = tracerouteView.formatText(targetName, route);
        MeshMessage sysMsg = new MeshMessage("!00000000", "!00000000", 0, text, System.currentTimeMillis() / 1000, false);
        sysMsg.setSystemMessage(true);
        MessageDbService.getInstance().save(sysMsg, chatType, chatKey, currentOwnerNodeId());

        if (isCurrentChat(chatType, chatKey)) {
            latestKnownDbId = Math.max(latestKnownDbId, sysMsg.getDbId());
            if (allNewerHistoryLoaded) {
                appendLoadedMessageRow(sysMsg);
                trimLoadedWindowFromTopIfNeeded();
                allNewerHistoryLoaded = true;
                requestMessageViewportLayout();
                scrollToBottom();
                markAsRead(selectedChat);
            } else {
                allNewerHistoryLoaded = false;
                refreshUnreadTailIndicatorLater();
            }
            reloadChatList();
        } else {
            reloadChatList();
        }
    }


    /**
     * Создать PendingCountdown, зарегистрировать и прикрепить UI-пузырь.
     */
    private PendingCountdown createCountdown(String chatType, String chatKey, String prefix) {
        PendingCountdown pc = new PendingCountdown(chatType, chatKey, prefix, REQUEST_TIMEOUT_SECONDS);
        pendingCountdowns.add(pc);
        attachCountdownBubble(pc);
        return pc;
    }

    /** Прикрепить UI-пузырь к PendingCountdown (создать или пересоздать при переключении чата) */
    private void attachCountdownBubble(PendingCountdown pc) {
        MeshMessage tmp = new MeshMessage("!00000000", "!00000000", 0,
                pc.prefix + " ⏱ " + pc.remaining[0], System.currentTimeMillis() / 1000, false);
        tmp.setSystemMessage(true);
        HBox bubble = bubbleFactory.build(tmp);
        messageContainer.getChildren().add(bubble);
        scrollToBottom();
        // buildSystemBubble → HBox(botAvatar, VBox(textLabel, timeLabel))
        VBox content = (VBox) bubble.getChildren().get(1);
        pc.countdownLabel = (EmojiTextFlow) content.getChildren().getFirst();

        // Кнопка «Отменить»
        Label cancelBtn = new Label("Отменить");
        cancelBtn.getStyleClass().add("chat-countdown-cancel");
        cancelBtn.setCursor(Cursor.HAND);
        cancelBtn.setOnMouseClicked(e -> {
            if (!pc.done[0] && pc.cancelAction != null) {
                pc.cancelAction.run();
            }
        });
        // Вставить перед timeLabel
        content.getChildren().add(content.getChildren().size() - 1, cancelBtn);

        pc.tempBubble = bubble;
    }

    /** Завершить countdown: удалить из списка и убрать пузырь из контейнера */
    private void finishCountdown(PendingCountdown pc) {
        pc.done[0] = true;
        pendingCountdowns.remove(pc);
        messageContainer.getChildren().remove(pc.tempBubble);
    }

    /**
     * Создать Timeline-таймер обратного отсчёта для PendingCountdown.
     * Обновляет текст countdownLabel каждую секунду.
     */
    private Timeline createCountdownTimer(PendingCountdown pc, String prefix) {
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), tick -> {
            pc.remaining[0]--;
            if (pc.remaining[0] > 0 && !pc.done[0]) {
                pc.countdownLabel.setText(prefix + " ⏱ " + pc.remaining[0]);
            }
        }));
        timer.setCycleCount(REQUEST_TIMEOUT_SECONDS);
        return timer;
    }

    /** Восстановить пузыри активных запросов при переключении в чат */
    private void restorePendingCountdowns() {
        if (selectedChat == null) { return; }
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        for (PendingCountdown pc : pendingCountdowns) {
            if (!pc.done[0] && Objects.equals(pc.chatType, chatType) && Objects.equals(pc.chatKey, chatKey)) {
                attachCountdownBubble(pc);
            }
        }
    }


    // ==================== Ответ на сообщение ====================

    /** Включить режим ответа на сообщение */
    private void startReply(MeshMessage msg) {
        chatInputBar.startReply(msg, nameResolver.resolveSenderName(msg));
    }

    private void sendReaction(MeshMessage msg, String emoji) {
        if (msg == null || emoji == null || emoji.isEmpty()) { return; }
        if (selectedChat == null || state == null || protocolHandler == null) {
            Toast.show(Toast.Type.WARNING, "Нет подключения к радио");
            return;
        }
        if (msg.getPacketId() == 0) {
            Toast.show(Toast.Type.WARNING, "Реакция недоступна: у сообщения нет packet id");
            return;
        }

        boolean saved;
        if (selectedChat.getType() == ChatItem.ChatType.CHANNEL) {
            saved = MessageService.sendChannelReaction(
                    protocolHandler, state, selectedChat.getChannelIndex(), msg, emoji);
        } else {
            saved = MessageService.sendDirectReaction(
                    protocolHandler, state, selectedChat.getPeerNodeId(), msg, emoji);
        }

        if (!saved) {
            Toast.show(Toast.Type.ERROR, "Не удалось сохранить реакцию локально");
            return;
        }
        refreshCurrentChatAfterLocalReaction();
    }

    private boolean retryMessage(MeshMessage msg) {
        if (msg == null || !msg.isOutgoing() || msg.getStatus() != MeshMessage.DeliveryStatus.FAILED) {
            return false;
        }
        if (state == null || protocolHandler == null) {
            Toast.show(Toast.Type.WARNING, "Нет подключения к радио");
            return false;
        }

        if (!isChannelMessage(msg)) {
            NodeData peerNode = NodeUtils.resolveNode(state, msg.getToNodeId());
            if (peerNode != null && peerNode.isUnmessagable()) {
                Toast.show(Toast.Type.WARNING, "Нода объявила, что не принимает личные сообщения");
                return false;
            }
        }

        boolean retried = MessageService.retryMessage(protocolHandler, state, msg);
        if (!retried) {
            if (!isChannelMessage(msg)) {
                Toast.show(Toast.Type.ERROR, "Не удалось определить ноду для DM");
            } else {
                Toast.show(Toast.Type.ERROR, "Не удалось переотправить сообщение");
            }
            return false;
        }

        reloadChatList();
        return true;
    }

    // ==================== Traceroute / Node Info ====================

    private boolean handleBotCommand(ChatBotCommandHelper.ParsedBotCommand command) {
        if (command == null || !command.isCommand()) {
            return false;
        }
        if (selectedChat == null || state == null || protocolHandler == null) {
            Toast.show(Toast.Type.WARNING, "Нет подключения к радио");
            return false;
        }
        if (command.hasExtraTokens()) {
            Toast.show(Toast.Type.WARNING, "Команда бота принимает только одну ноду");
            return false;
        }
        if (command.targetToken() == null || command.targetToken().isBlank()) {
            Toast.show(Toast.Type.WARNING, switch (command.action()) {
                case TRACEROUTE -> "Используйте: @tracebot имя(!nodeid)";
                case NODE_INFO -> "Используйте: @infobot имя(!nodeid)";
            });
            return false;
        }

        ChatBotCommandHelper.NodeResolution resolution =
                ChatBotCommandHelper.resolveTarget(command.targetToken(), listBotCommandNodes());
        if (resolution.status() == ChatBotCommandHelper.NodeResolutionStatus.AMBIGUOUS) {
            Toast.show(Toast.Type.WARNING, "Найдено несколько нод. Уточните выбор через подсказку");
            return false;
        }
        if (resolution.status() != ChatBotCommandHelper.NodeResolutionStatus.FOUND || resolution.node() == null) {
            Toast.show(Toast.Type.WARNING, "Нода не найдена: " + command.targetToken());
            return false;
        }

        return switch (command.action()) {
            case TRACEROUTE -> {
                requestTraceroute(resolution.node());
                yield true;
            }
            case NODE_INFO -> {
                requestNodeInfo(resolution.node());
                yield true;
            }
        };
    }

    private List<NodeData> listBotCommandNodes() {
        LinkedHashMap<String, NodeData> nodes = new LinkedHashMap<>();
        if (state != null) {
            for (NodeData node : state.getNodeDb().values()) {
                NodeCacheService.getInstance().enrichFromCache(node);
                registerBotNode(nodes, node);
            }
        }
        for (NodeData node : NodeCacheService.getInstance().getAll()) {
            registerBotNode(nodes, node);
        }
        return new ArrayList<>(nodes.values());
    }

    private static void registerBotNode(Map<String, NodeData> nodes, NodeData node) {
        if (node == null) {
            return;
        }
        String nodeId = node.getNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = String.format("!%08x", node.getNodeNum());
        }
        nodes.putIfAbsent(nodeId.toLowerCase(Locale.ROOT), node);
    }

    private static boolean isChannelMessage(MeshMessage msg) {
        return msg != null && "!ffffffff".equalsIgnoreCase(msg.getToNodeId());
    }

    private NodeData resolveTargetNodeFromMessage(MeshMessage msg) {
        if (msg == null || state == null) {
            return null;
        }
        NodeData targetNode = state.getNodeByNodeId(msg.getFromNodeId());
        if (targetNode != null) {
            return targetNode;
        }

        String nodeId = msg.getFromNodeId();
        if (nodeId == null || nodeId.length() < 2) {
            return null;
        }

        int nodeNum = (int) Long.parseUnsignedLong(nodeId.substring(1), 16);
        targetNode = state.getOrCreateNode(nodeNum);
        NodeCacheService.getInstance().enrichFromCache(targetNode);
        return targetNode;
    }

    /** Запрос traceroute до ноды — ответ показывается как системное сообщение */
    private void requestTraceroute(MeshMessage msg) {
        requestTraceroute(resolveTargetNodeFromMessage(msg));
    }

    /** Запрос traceroute до указанной ноды — ответ показывается как системное сообщение */
    private void requestTraceroute(NodeData targetNode) {
        if (state == null || protocolHandler == null) { return; }
        if (targetNode == null) {
            return;
        }
        int targetNum = targetNode.getNodeNum();
        String name = nameResolver.resolveNodeName(targetNum);
        String prefix = "🔍 Traceroute → " + name;

        String chatType = currentChatType();
        String chatKey = currentChatKey();
        if (chatType == null) { return; }

        PendingCountdown pc = createCountdown(chatType, chatKey, prefix);

        @SuppressWarnings("unchecked")
        BiConsumer<Integer, MeshProtos.RouteDiscovery>[] holder = new BiConsumer[1];
        Timeline timer = createCountdownTimer(pc, prefix);

        holder[0] = (fromNodeNum, route) -> {
            // Фильтр: реагируем только на ответ от целевой ноды
            if (fromNodeNum != targetNum) { return; }
            state.removeTracerouteListener(holder[0]);
            Platform.runLater(() -> {
                timer.stop();
                finishCountdown(pc);
                addTracerouteResult(chatType, chatKey, name, route);
            });
        };

        timer.setOnFinished(e -> {
            if (!pc.done[0]) {
                state.removeTracerouteListener(holder[0]);
                finishCountdown(pc);
                addSystemMessageTo(chatType, chatKey, "❌ Traceroute → " + name + ": ответ не получен");
            }
        });

        pc.cancelAction = () -> {
            state.removeTracerouteListener(holder[0]);
            timer.stop();
            finishCountdown(pc);
        };

        state.addTracerouteListener(holder[0]);
        timer.play();
        MessageService.requestTraceroute(protocolHandler, state, targetNum);
    }

    /** Запрос информации о ноде — всегда запрашивает актуальные данные по сети */
    private void requestNodeInfo(MeshMessage msg) {
        requestNodeInfo(resolveTargetNodeFromMessage(msg));
    }

    /** Запрос информации о ноде — всегда запрашивает актуальные данные по сети */
    private void requestNodeInfo(NodeData targetNode) {
        if (state == null || protocolHandler == null) { return; }
        if (targetNode == null) {
            return;
        }
        int targetNum = targetNode.getNodeNum();
        String name = nameResolver.resolveNodeName(targetNum);

        String chatType = currentChatType();
        String chatKey = currentChatKey();
        if (chatType == null) { return; }
        String prefix = "📋 Запрос информации о " + name;

        PendingCountdown pc = createCountdown(chatType, chatKey, prefix);

        IntConsumer[] holder = new IntConsumer[1];
        Timeline timer = createCountdownTimer(pc, prefix);

        holder[0] = nodeNum -> {
            if (nodeNum != targetNum) { return; }
            state.removeNodeUpdateListener(holder[0]);
            Platform.runLater(() -> {
                timer.stop();
                finishCountdown(pc);

                NodeData n = state.getNodeDb().get(targetNum);
                if (n == null) {
                    addSystemMessageTo(chatType, chatKey, "📋 Нода " + name + " не найдена");
                    return;
                }
                addSystemMessageTo(chatType, chatKey, NodeInfoFormatter.format(n));
            });
        };

        timer.setOnFinished(e -> {
            if (!pc.done[0]) {
                state.removeNodeUpdateListener(holder[0]);
                finishCountdown(pc);
                addSystemMessageTo(chatType, chatKey, "❌ Информация о " + name + ": ответ не получен");
            }
        });

        pc.cancelAction = () -> {
            state.removeNodeUpdateListener(holder[0]);
            timer.stop();
            finishCountdown(pc);
        };

        state.addNodeUpdateListener(holder[0]);
        timer.play();
        MessageService.requestNodeInfo(protocolHandler, state, targetNum);
    }

    private void updateInputEnabled() {
        boolean canSend = state != null && protocolHandler != null && selectedChat != null;
        chatInputBar.setInputEnabled(canSend);
    }


    // ==================== Data ====================

    private void rebindState() {
        var mgr = ConnectionManager.getInstance();
        DeviceState newState = null;
        ProtocolHandler newHandler = null;

        for (ConnectionEntry entry : mgr.getEntries()) {
            if (entry.isConnected()) {
                newState = mgr.getDeviceState(entry.getId());
                newHandler = mgr.getProtocolHandler(entry.getId());
                if (newState != null) { break; }
            }
        }

        boolean stateChanged = newState != this.state;

        if (newState == this.state) {
            lastReadCounts.clear();
            lastReadCounts.putAll(MessageDbService.getInstance().loadAllReadCounts(currentOwnerNodeId()));
            reloadChatList();
            return;
        }

        if (this.state != null) {
            this.state.removeMessageListener(messageListener);
        }

        this.state = newState;
        this.protocolHandler = newHandler;

        if (bubbleFactory != null) {
            bubbleFactory.setState(newState);
        }
        if (nameResolver != null) {
            nameResolver.setState(newState);
        }

        // Перезагрузить счётчики прочитанных для нового устройства
        lastReadCounts.clear();
        lastReadCounts.putAll(MessageDbService.getInstance().loadAllReadCounts(currentOwnerNodeId()));

        if (this.state != null) {
            this.state.addMessageListener(messageListener);

            // Регистрация проверки активного чата для подавления уведомлений
            for (ConnectionEntry ce : mgr.getEntries()) {
                if (ce.isConnected()) {
                    MessageListenerService mls = mgr.getMessageListenerService(ce.getId());
                    if (mls != null) {
                        mls.getNotificationManager().setActiveChatChecker(this::isCurrentChat);
                    }
                }
            }
        }

        reloadChatList();
        if (stateChanged) {
            reopenSelectedChatIfPossible();
        }
        updateInputEnabled();
    }

    private void reopenSelectedChatIfPossible() {
        if (detailPane == null) {
            return;
        }

        if (state == null) {
            clearLoadedMessageState();
            detailPane.getChildren().clear();
            detailPane.getChildren().add(placeholderBox);
            return;
        }

        if (selectedChat == null) {
            return;
        }

        ChatItem matched = chatListView.getItems().stream()
                .filter(item -> chatItemMatches(item, selectedChat))
                .findFirst()
                .orElse(null);

        if (matched == null) {
            closeChat();
            return;
        }

        suppressSelectionListener = true;
        try {
            chatListView.getSelectionModel().select(matched);
        } finally {
            suppressSelectionListener = false;
        }
        openChat(matched);
    }

    private void reloadChatList() {
        if (state == null) {
            chatItems.clear();
            return;
        }

        MessageDbService db = MessageDbService.getInstance();
        List<ChatItem> items = new ArrayList<>();

        // Последние сообщения каналов и DM из БД (одним запросом на тип)
        String ownerId = currentOwnerNodeId();
        Map<String, MeshMessage> channelLastMsgs = db.getLastMessagePerChat("channel", ownerId);
        Map<String, MeshMessage> dmLastMsgs = db.getLastMessagePerChat("dm", ownerId);

        // 1. Каналы (не DISABLED)
        for (ChannelProtos.Channel channel : state.getChannels()) {
            if (channel.getRole() == ChannelProtos.Channel.Role.DISABLED) { continue; }
            String chKey = String.valueOf(channel.getIndex());
            MeshMessage lastMsg = channelLastMsgs.get(chKey);
            String readKey = "ch:" + channel.getIndex();
            int totalCount = db.getUnreadEligibleMessageCount("channel", chKey, ownerId);
            int lastRead = lastReadCounts.getOrDefault(readKey, 0);
            int unread = Math.max(0, totalCount - lastRead);
            boolean muted = AppPreferences.isChatMuted(
                    ownerId,
                    AppPreferences.composeChatPreferenceId("channel", chKey));
            items.add(ChatItem.fromChannel(channel, lastMsg, unread, muted));
        }

        // 2. DM-пиры: объединение из БД + текущей сессии
        Set<String> dmPeers = new LinkedHashSet<>(db.getDistinctDmPeers(ownerId));
        dmPeers.addAll(state.getAllDirectMessages().keySet());
        for (String peerNodeId : dmPeers) {
            MeshMessage lastMsg = dmLastMsgs.get(peerNodeId);
            NodeData peerNode = state.getNodeByNodeId(peerNodeId);
            // Если ноды нет в state, попробовать из кэша нод
            if (peerNode == null) {
                peerNode = NodeCacheService.getInstance().get(peerNodeId);
            }
            String readKey = "dm:" + peerNodeId;
            int totalCount = db.getUnreadEligibleMessageCount("dm", peerNodeId, ownerId);
            int lastRead = lastReadCounts.getOrDefault(readKey, 0);
            int unread = Math.max(0, totalCount - lastRead);
            boolean muted = AppPreferences.isChatMuted(
                    ownerId,
                    AppPreferences.composeChatPreferenceId("dm", peerNodeId));
            items.add(ChatItem.fromDirectMessage(peerNodeId, peerNode, lastMsg, unread, muted));
        }

        // Восстановить выделение
        suppressSelectionListener = true;
        try {
            chatItems.setAll(items);
            if (selectedChat != null) {
                chatListView.getItems().stream()
                        .filter(item -> chatItemMatches(item, selectedChat))
                        .findFirst()
                        .ifPresent(item -> {
                            selectedChat = item;
                            chatListView.getSelectionModel().select(item);
                        });
            }
        } finally {
            suppressSelectionListener = false;
        }

        // Обновить красную точку на иконке "Чаты" в боковой панели
        boolean hasUnread = chatItems.stream().anyMatch(c -> c.getUnreadCount() > 0);
        DrawerManager.setChatUnreadDot(hasUnread);
    }

    /**
     * Открывает панель свойств канала.
     */
    private void showChannelProperties(ChatItem item) {
        if (item == null || item.getType() != ChatItem.ChatType.CHANNEL) {
            return;
        }
        if (state == null || protocolHandler == null) {
            Toast.show(Toast.Type.WARNING, "Нет подключения к радио");
            return;
        }
        ChannelPropertiesDialog.show(state, protocolHandler,
                item.getChannelIndex(), this::reloadChatList);
    }

    private void toggleChatMute(ChatItem item) {
        if (item == null) {
            return;
        }
        AppPreferences.setChatMuted(currentOwnerNodeId(), chatPreferenceId(item), !item.isMuted());
        reloadChatList();
    }

    /**
     * Удаляет канал (DISABLED на устройстве) или DM-чат (только локально).
     */
    private void deleteChat(ChatItem item) {
        MessageDbService db = MessageDbService.getInstance();
        String chatId = chatScrollStateKey(item);
        savedChatScrollStates.remove(chatScrollCacheKey(item));
        AppPreferences.removeChatScrollState(currentOwnerNodeId(), chatId);

        if (item.getType() == ChatItem.ChatType.CHANNEL) {
            int idx = item.getChannelIndex();

            // Отправить DISABLED на устройство
            if (state != null && protocolHandler != null) {
                ChannelProtos.Channel disabled = ChannelProtos.Channel.newBuilder()
                        .setIndex(idx)
                        .setRole(ChannelProtos.Channel.Role.DISABLED)
                        .build();
                MessageService.setChannel(protocolHandler, state, disabled, state.getSessionPasskey());
                state.updateChannel(disabled);
            }

            db.deleteChat("channel", String.valueOf(idx), currentOwnerNodeId());
            lastReadCounts.remove("ch:" + idx);
        } else {
            String peerNodeId = item.getPeerNodeId();
            if (state != null) {
                state.removeDirectMessages(peerNodeId);
            }
            db.deleteChat("dm", peerNodeId, currentOwnerNodeId());
            lastReadCounts.remove("dm:" + peerNodeId);
        }

        // Закрыть правую панель, если удалён текущий чат
        if (selectedChat != null && chatItemMatches(selectedChat, item)) {
            closeChat();
        }

        reloadChatList();
    }

    /** Подтверждение и удаление одного сообщения */
    private void confirmDeleteMessage(MeshMessage msg, HBox bubbleRow) {
        String preview = msg.getText();
        if (preview != null && preview.length() > 40) {
            preview = preview.substring(0, 40) + "…";
        }
        ModalPane.showConfirm(
                "Удалить сообщение?",
                preview != null ? preview : "",
                confirmed -> {
                    if (!confirmed) { return; }
                    MessageDbService.getInstance().deleteMessage(msg.getDbId());
                    loadedMessages.removeIf(loaded -> loaded.getDbId() == msg.getDbId());
                    loadedMessageRows.remove(msg.getDbId());
                    recalcLoadedBounds();
                    messageContainer.getChildren().remove(bubbleRow);
                    reloadChatList();
                }
        );
    }

    private void markAsRead(ChatItem item) {
        boolean isChannel = item.getType() == ChatItem.ChatType.CHANNEL;
        String dbType = isChannel ? "channel" : "dm";
        String dbKey = isChannel ? String.valueOf(item.getChannelIndex()) : item.getPeerNodeId();
        String readKey = (isChannel ? "ch:" : "dm:") + dbKey;

        MessageDbService db = MessageDbService.getInstance();
        String ownerId = currentOwnerNodeId();
        int count = db.getUnreadEligibleMessageCount(dbType, dbKey, ownerId);
        if (lastReadCounts.getOrDefault(readKey, -1) == count) {
            return;
        }
        lastReadCounts.put(readKey, count);
        db.saveReadCount(dbType, dbKey, count, ownerId);
        reloadChatList();
    }

    private void showNewChatDialog() {
        if (newChatMenu == null) {
            newChatMenu = new ContextMenu();
            MenuItem createChannel = new MenuItem("Создать канал");
            createChannel.setOnAction(e -> showCreateChannelDialog());
            newChatMenu.getItems().add(createChannel);
        }
        if (newChatMenu.isShowing()) {
            newChatMenu.hide();
        } else {
            newChatMenu.show(newChatBtn, javafx.geometry.Side.BOTTOM, 0, 0);
        }
    }

    private void showCreateChannelDialog() {
        CreateChannelDialog.show(state, protocolHandler, this::reloadChatList);
    }

    private static boolean chatItemMatches(ChatItem a, ChatItem b) {
        if (a.getType() != b.getType()) {
            return false;
        }
        return a.getType() == ChatItem.ChatType.CHANNEL
                ? a.getChannelIndex() == b.getChannelIndex()
                : Objects.equals(a.getPeerNodeId(), b.getPeerNodeId());
    }

    // ==================== Helpers ====================

    private boolean isCurrentChat(String chatType, String chatKey) {
        return selectedChat != null
                && Objects.equals(currentChatType(), chatType)
                && Objects.equals(currentChatKey(), chatKey);
    }

    private static boolean containsIgnoreCase(String text, String query) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query);
    }
}
