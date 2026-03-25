package com.meshtastic.client.forms;

import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.components.chat.ChatInputBar;
import com.meshtastic.client.components.chat.ChatListCell;
import com.meshtastic.client.components.chat.ChatNameResolver;
import com.meshtastic.client.components.chat.ChannelPropertiesDialog;
import com.meshtastic.client.components.chat.CreateChannelDialog;
import com.meshtastic.client.components.chat.MessageBubbleFactory;
import com.meshtastic.client.components.chat.NodeInfoFormatter;
import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
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
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.MeshProtos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

@SystemForm(name = "Чат", description = "Чаты пользователя", tags = {"чаты", "каналы"})
public class FormChat extends Form {

    private static final int REQUEST_TIMEOUT_SECONDS = 360;
    private static final int UNREAD_FOCUS_THRESHOLD = 2;
    private static final double BOTTOM_READ_SLOP_PX = 24.0;

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
    private Text headerAvatarText;
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
    private static final int PAGE_SIZE = 50;
    private long oldestLoadedDbId = Long.MAX_VALUE;
    private long newestLoadedDbId = 0;
    private boolean allHistoryLoaded = false;
    private boolean loadingOlderMessages = false;
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

    private final Runnable messageListener = () -> Platform.runLater(() -> {
        reloadChatList();
        refreshCurrentChat();
    });
    private final Runnable connectionListener = () -> Platform.runLater(this::rebindState);

    public FormChat() {
        initComponents();
    }

    @Override
    public void formInit() {
        ConnectionManager.getInstance().addListener(connectionListener);
        // Загрузить сохранённые счётчики прочитанных сообщений из БД
        lastReadCounts.putAll(MessageDbService.getInstance().loadAllReadCounts(currentOwnerNodeId()));
        rebindState();
    }

    @Override
    public void formOpen() {
        formVisible = true;
        rebindState();
        if (selectedChat != null) {
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
        chatListView.setCellFactory(lv -> new ChatListCell(this::deleteChat, this::showChannelProperties));
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
        placeholder.setStyle("-fx-opacity: 0.5; -fx-font-size: 14px;");
        placeholder.setWrapText(true);
        placeholderBox.getChildren().add(placeholder);

        // Заголовок
        headerAvatarPane = new StackPane();
        headerAvatarPane.setMinSize(36, 36);
        headerAvatarPane.setMaxSize(36, 36);
        headerAvatarText = new Text();
        headerAvatarText.setStyle("-fx-font-weight: bold;");
        headerAvatarText.setFill(Color.WHITE);
        headerAvatarPane.getChildren().add(headerAvatarText);

        headerNameLabel = new Label();
        headerNameLabel.setFont(Font.font("Roboto", FontWeight.BOLD, 16));
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
            scrollToBottom();
            newMessageWhileScrolled = 0;
            updateScrollDownBadge();
            if (formVisible && selectedChat != null) { markAsRead(selectedChat); }
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
            if (newVal.doubleValue() < 0.1 && !allHistoryLoaded && !loadingOlderMessages) {
                loadOlderMessages();
            }
            boolean atBottom = isScrolledToBottom();
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
            if (!loadingOlderMessages) {
                saveCurrentChatScrollState();
            }
        });

        // При изменении высоты контента (перенос строк при ресайзе) —
        // держать скролл внизу, чтобы баблы расширялись визуально вверх
        messageContainer.heightProperty().addListener((obs, oldH, newH) -> {
            if (formVisible && isScrolledToBottom()) {
                Platform.runLater(() -> messageScrollPane.setVvalue(1.0));
            }
        });

        chatInputBar = new ChatInputBar(request -> {
            if (selectedChat == null || state == null || protocolHandler == null) {
                return;
            }
            if (selectedChat.getType() == ChatItem.ChatType.CHANNEL) {
                MessageService.sendChannelMessage(
                        protocolHandler, state,
                        selectedChat.getChannelIndex(),
                        request.text(), request.replyId());
            } else {
                MessageService.sendDirectMessage(
                        protocolHandler, state,
                        selectedChat.getPeerNodeId(),
                        request.text(), request.replyId());
            }

            // Локально уже сохранили исходящее сообщение в БД и DeviceState.
            // Подтягиваем его в открытую беседу сразу, не дожидаясь асинхронного
            // messageListener, чтобы UI не выглядел "немым" при проблемах с RX/ACK.
            refreshCurrentChatAfterLocalSend();
        });
    }

    // ==================== Правая панель: открытие/закрытие чата ====================

    /**
     * Программно открыть приватный DM-чат с указанной нодой.
     * Вызывается извне (например, из NodeDetailContent) после навигации на эту форму.
     * Если чат с этим пиром ещё не существует в списке — добавляет его.
     */
    public void openDirectChat(String peerNodeId, NodeData peerNode) {
        saveCurrentChatScrollState();
        ChatItem dm = ChatItem.fromDirectMessage(peerNodeId, peerNode, (MeshMessage) null, 0);
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
        headerAvatarText.setText(chat.getAvatarText());
        headerAvatarText.setFont(Font.font("Roboto", FontWeight.BOLD,
                NodeUtils.avatarFontSize(chat.getAvatarText(), 36)));
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
        loadInitialMessages();
        // Восстановить пузыри активных запросов (Trace/Инфо) для этого чата
        restorePendingCountdowns();

        updateInputEnabled();
        chatInputBar.focusInput();
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
    private void loadInitialMessages() {
        if (selectedChat == null) { return; }
        pendingStatusLabels.clear();

        MessageDbService db = MessageDbService.getInstance();
        String chatType = currentChatType();
        String chatKey = currentChatKey();

        List<MeshMessage> msgs = db.loadLast(chatType, chatKey, PAGE_SIZE, currentOwnerNodeId());
        attachReactions(msgs);

        clearLoadedMessageState();
        messageContainer.getChildren().clear();
        for (MeshMessage msg : msgs) {
            appendLoadedMessageRow(msg);
        }

        if (!msgs.isEmpty()) {
            oldestLoadedDbId = msgs.getFirst().getDbId();
            newestLoadedDbId = msgs.getLast().getDbId();
        } else {
            oldestLoadedDbId = Long.MAX_VALUE;
            newestLoadedDbId = 0;
        }
        allHistoryLoaded = msgs.size() < PAGE_SIZE;
        loadingOlderMessages = false;
        newMessageWhileScrolled = 0;
        updateScrollDownBadge();
        requestMessageViewportLayout();

        if (restoreSavedScrollPosition()) {
            openingChatUnreadCount = 0;
            refreshUnreadTailIndicatorLater();
            return;
        }
        positionInitialMessages(openingChatUnreadCount);
        openingChatUnreadCount = 0;
    }

    /**
     * Подгрузить PAGE_SIZE старых сообщений (скролл вверх). Сохранить позицию скролла.
     */
    private void loadOlderMessages() {
        if (allHistoryLoaded || loadingOlderMessages || selectedChat == null) { return; }
        loadingOlderMessages = true;

        MessageDbService db = MessageDbService.getInstance();
        String chatType = currentChatType();
        String chatKey = currentChatKey();

        List<MeshMessage> older = db.loadBefore(chatType, chatKey, oldestLoadedDbId, PAGE_SIZE, currentOwnerNodeId());

        if (older.isEmpty()) {
            allHistoryLoaded = true;
            loadingOlderMessages = false;
            return;
        }

        // Запомнить высоту контента до вставки
        double scrollHeight = messageContainer.getBoundsInLocal().getHeight();

        prependOlderMessages(older);
        allHistoryLoaded = older.size() < PAGE_SIZE;

        // Восстановить позицию скролла
        Platform.runLater(() -> {
            double newHeight = messageContainer.getBoundsInLocal().getHeight();
            double addedHeight = newHeight - scrollHeight;
            if (newHeight > 0) {
                messageScrollPane.setVvalue(addedHeight / newHeight);
            }
            loadingOlderMessages = false;
        });
    }

    /**
     * Инкрементальное обновление: загрузить новые сообщения из БД (id > newestLoadedDbId).
     * Вызывается при срабатывании messageListener.
     */
    private void refreshCurrentChat() {
        if (selectedChat == null) { return; }
        ChatScrollState preservedScrollState = !formVisible ? captureCurrentChatScrollState() : null;

        // Обновить статусы доставки для отправленных сообщений (ACK/NAK)
        MessageDbService db = MessageDbService.getInstance();
        pendingStatusLabels.entrySet().removeIf(entry -> {
            MeshMessage updated = db.findByPacketId(entry.getKey());
            if (updated != null && updated.getStatus() != null
                    && updated.getStatus() != MeshMessage.DeliveryStatus.SENDING) {
                MessageBubbleFactory.updateStatusLabel(entry.getValue(), updated.getStatus());
                syncLoadedMessageDeliveryStatus(updated);
                return true;
            }
            return false;
        });
        String chatType = currentChatType();
        String chatKey = currentChatKey();

        List<MeshMessage> newMsgs = db.loadAfter(chatType, chatKey, newestLoadedDbId, currentOwnerNodeId());
        if (!newMsgs.isEmpty()) {
            boolean shouldAutoscroll = formVisible && isScrolledToBottom();
            for (MeshMessage msg : newMsgs) {
                appendLoadedMessageRow(msg);
            }
            newestLoadedDbId = newMsgs.getLast().getDbId();
            requestMessageViewportLayout();
            if (shouldAutoscroll) {
                newMessageWhileScrolled = 0;
                updateScrollDownBadge();
                scrollToBottom();
                if (formVisible) { markAsRead(selectedChat); }
            } else {
                if (!formVisible) {
                    if (preservedScrollState != null && !preservedScrollState.atBottom) {
                        restoreSavedScrollPosition(preservedScrollState);
                    } else {
                        focusUnreadMessages(getUnreadCount(selectedChat));
                    }
                }
                refreshUnreadTailIndicatorLater();
            }
        }

        refreshLoadedMessageRows();
    }

    private void syncLoadedMessageDeliveryStatus(MeshMessage updated) {
        if (updated == null || updated.getPacketId() == 0) {
            return;
        }
        for (MeshMessage loaded : loadedMessages) {
            if (loaded.getPacketId() == updated.getPacketId()) {
                loaded.setStatus(updated.getStatus());
                loaded.setErrorReason(updated.getErrorReason());
                return;
            }
        }
    }

    /**
     * Сразу отражает локально отправленное сообщение в открытом чате.
     * Использует тот же DB-backed путь, что и обычный messageListener,
     * чтобы не плодить отдельную логику рендера и не расходиться со статусами.
     */
    private void refreshCurrentChatAfterLocalSend() {
        reloadChatList();
        refreshCurrentChat();
        scrollToBottom();
    }

    private void refreshCurrentChatAfterLocalReaction() {
        refreshLoadedMessageRows();
    }

    private void clearLoadedMessageState() {
        loadedMessages.clear();
        loadedMessageRows.clear();
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
        Map<Integer, List<com.meshtastic.client.model.MessageReaction>> reactionsByTarget =
                MessageDbService.getInstance().loadReactionsByTargetPacketIds(
                        currentChatType(),
                        currentChatKey(),
                        currentOwnerNodeId(),
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

    private String chatScrollStateKey(ChatItem item) {
        if (item == null) {
            return "";
        }
        return item.getType() == ChatItem.ChatType.CHANNEL
                ? "channel:" + item.getChannelIndex()
                : "dm:" + item.getPeerNodeId();
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

        messageScrollPane.applyCss();
        messageScrollPane.layout();
        messageContainer.applyCss();
        messageContainer.layout();

        if (isScrolledToBottom()) {
            return new ChatScrollState(newestLoadedDbId, 0, true);
        }

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
        while (!loadedMessageRows.containsKey(dbId) && !allHistoryLoaded) {
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
        int totalCount = MessageDbService.getInstance().getMessageCount(dbType, dbKey, currentOwnerNodeId());
        int lastRead = lastReadCounts.getOrDefault(readKey, 0);
        return Math.max(0, totalCount - lastRead);
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
        int unreadInLoadedWindow = Math.min(unreadCount, loadedMessages.size());
        int firstUnreadIndex = loadedMessages.size() - unreadInLoadedWindow;
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
        if (selectedChat == null || isScrolledToBottom()) {
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

        int unreadInLoadedWindow = Math.min(totalUnread, loadedMessages.size());
        int firstUnreadIndex = loadedMessages.size() - unreadInLoadedWindow;
        int unreadBelow = 0;
        for (int i = firstUnreadIndex; i < loadedMessages.size(); i++) {
            HBox row = loadedMessageRows.get(loadedMessages.get(i).getDbId());
            if (row != null && row.getBoundsInParent().getMinY() >= viewportBottom) {
                unreadBelow++;
            }
        }
        return unreadBelow;
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
            messageContainer.getChildren().add(bubbleFactory.build(sysMsg));
            newestLoadedDbId = sysMsg.getDbId();
            scrollToBottom();
            markAsRead(selectedChat);
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
            HBox bubble = tracerouteView.buildFromProto(targetName, route, sysMsg);
            messageContainer.getChildren().add(bubble);
            newestLoadedDbId = sysMsg.getDbId();
            scrollToBottom();
            markAsRead(selectedChat);
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

    // ==================== Traceroute / Node Info ====================

    /** Запрос traceroute до ноды — ответ показывается как системное сообщение */
    private void requestTraceroute(MeshMessage msg) {
        if (state == null || protocolHandler == null) { return; }
        NodeData targetNode = state.getNodeByNodeId(msg.getFromNodeId());
        if (targetNode == null) {
            String nodeId = msg.getFromNodeId();
            if (nodeId == null || nodeId.length() < 2) { return; }
            int nodeNum = (int) Long.parseUnsignedLong(nodeId.substring(1), 16);
            targetNode = state.getOrCreateNode(nodeNum);
            NodeCacheService.getInstance().enrichFromCache(targetNode);
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
        if (state == null || protocolHandler == null) { return; }
        NodeData targetNode = state.getNodeByNodeId(msg.getFromNodeId());
        if (targetNode == null) {
            String nodeId = msg.getFromNodeId();
            if (nodeId == null || nodeId.length() < 2) { return; }
            int nodeNum = (int) Long.parseUnsignedLong(nodeId.substring(1), 16);
            targetNode = state.getOrCreateNode(nodeNum);
            NodeCacheService.getInstance().enrichFromCache(targetNode);
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
        updateInputEnabled();
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
            int totalCount = db.getMessageCount("channel", chKey, ownerId);
            int lastRead = lastReadCounts.getOrDefault(readKey, 0);
            int unread = Math.max(0, totalCount - lastRead);
            items.add(ChatItem.fromChannel(channel, lastMsg, unread));
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
            int totalCount = db.getMessageCount("dm", peerNodeId, ownerId);
            int lastRead = lastReadCounts.getOrDefault(readKey, 0);
            int unread = Math.max(0, totalCount - lastRead);
            items.add(ChatItem.fromDirectMessage(peerNodeId, peerNode, lastMsg, unread));
        }

        // Восстановить выделение
        suppressSelectionListener = true;
        try {
            chatItems.setAll(items);
            if (selectedChat != null) {
                chatListView.getItems().stream()
                        .filter(item -> chatItemMatches(item, selectedChat))
                        .findFirst()
                        .ifPresent(chatListView.getSelectionModel()::select);
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
        int count = db.getMessageCount(dbType, dbKey, ownerId);
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
