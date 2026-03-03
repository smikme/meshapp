package com.meshtastic.client.forms;

import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.components.chat.ChatInputBar;
import com.meshtastic.client.components.chat.ChatListCell;
import com.meshtastic.client.components.chat.ChatNameResolver;
import com.meshtastic.client.components.chat.CreateChannelDialog;
import com.meshtastic.client.components.chat.MessageBubbleFactory;
import com.meshtastic.client.components.chat.NodeInfoFormatter;
import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.service.NodeCacheService;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.MeshProtos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

@SystemForm(name = "Чат", description = "Чаты пользователя", tags = {"чаты", "каналы"})
public class FormChat extends Form {

    private static final int REQUEST_TIMEOUT_SECONDS = 360;

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

    private boolean suppressSelectionListener;

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
        lastReadCounts.putAll(MessageDbService.getInstance().loadAllReadCounts());
        rebindState();
    }

    @Override
    public void formOpen() {
        rebindState();
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
            String query = newVal == null ? "" : newVal.trim().toLowerCase();
            filteredChats.setPredicate(chat -> query.isEmpty()
                    || containsIgnoreCase(chat.getDisplayName(), query)
                    || containsIgnoreCase(chat.getLastMessageText(), query));
        });

        SortedList<ChatItem> sortedChats = new SortedList<>(filteredChats,
                Comparator.comparingLong(ChatItem::getLastMessageTime).reversed());

        chatListView = new ListView<>(sortedChats);
        chatListView.getStyleClass().add("chat-list-view");
        chatListView.setCellFactory(lv -> new ChatListCell(this::deleteChat));
        chatListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldItem, newItem) -> {
                    if (suppressSelectionListener) { return; }
                    if (newItem != null) {
                        selectedChat = newItem;
                        markAsRead(newItem);
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
        splitPane.setDividerPositions(0.35);
        splitPane.getStyleClass().add("chat-split-pane");
        SplitPane.setResizableWithParent(leftPane, false);

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
        headerAvatarLabel = new Label();
        headerAvatarLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        headerAvatarPane.getChildren().add(headerAvatarLabel);

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
        scrollDownBtn.setOnAction(e -> scrollToBottom());
        StackPane.setAlignment(scrollDownBtn, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(scrollDownBtn, new Insets(0, 20, 15, 0));

        // Обёртка: scrollPane + кнопка «вниз» (кнопка поверх содержимого)
        messageArea = new StackPane(messageScrollPane, scrollDownBtn);
        VBox.setVgrow(messageArea, Priority.ALWAYS);

        // Подгрузка старых сообщений при скролле наверх + показ/скрытие кнопки «вниз»
        messageScrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() < 0.1 && !allHistoryLoaded && !loadingOlderMessages) {
                loadOlderMessages();
            }
            scrollDownBtn.setVisible(newVal.doubleValue() < 0.95);
        });

        // При изменении высоты контента (перенос строк при ресайзе) —
        // держать скролл внизу, чтобы баблы расширялись визуально вверх
        messageContainer.heightProperty().addListener((obs, oldH, newH) -> {
            if (messageScrollPane.getVvalue() > 0.85) {
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
        });
    }

    // ==================== Правая панель: открытие/закрытие чата ====================

    /**
     * Программно открыть приватный DM-чат с указанной нодой.
     * Вызывается извне (например, из NodeDetailContent) после навигации на эту форму.
     * Если чат с этим пиром ещё не существует в списке — добавляет его.
     */
    public void openDirectChat(String peerNodeId, NodeData peerNode) {
        ChatItem dm = ChatItem.fromDirectMessage(peerNodeId, peerNode, (MeshMessage) null, 0);

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
        this.selectedChat = chat;

        // Обновить заголовок
        headerAvatarLabel.setText(chat.getAvatarText());
        headerAvatarLabel.setFont(Font.font("Roboto", FontWeight.BOLD,
                NodeUtils.avatarFontSize(chat.getAvatarText().length(), 36)));
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
        this.selectedChat = null;
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

    /**
     * Загрузить последние PAGE_SIZE сообщений из БД. Скролл в самый низ.
     */
    private void loadInitialMessages() {
        if (selectedChat == null) { return; }
        pendingStatusLabels.clear();

        MessageDbService db = MessageDbService.getInstance();
        String chatType = currentChatType();
        String chatKey = currentChatKey();

        List<MeshMessage> msgs = db.loadLast(chatType, chatKey, PAGE_SIZE);

        messageContainer.getChildren().clear();
        for (MeshMessage msg : msgs) {
            messageContainer.getChildren().add(bubbleFactory.build(msg));
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

        // Автоскролл вниз (последнее сообщение видно)
        scrollToBottom();
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

        List<MeshMessage> older = db.loadBefore(chatType, chatKey, oldestLoadedDbId, PAGE_SIZE);

        if (older.isEmpty()) {
            allHistoryLoaded = true;
            loadingOlderMessages = false;
            return;
        }

        // Запомнить высоту контента до вставки
        double scrollHeight = messageContainer.getBoundsInLocal().getHeight();

        // Prepend: older уже в хронологическом порядке (старые → новые)
        for (int i = older.size() - 1; i >= 0; i--) {
            messageContainer.getChildren().addFirst(bubbleFactory.build(older.get(i)));
        }

        oldestLoadedDbId = older.getFirst().getDbId();
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

        // Обновить статусы доставки для отправленных сообщений (ACK/NAK)
        MessageDbService db = MessageDbService.getInstance();
        pendingStatusLabels.entrySet().removeIf(entry -> {
            MeshMessage updated = db.findByPacketId(entry.getKey());
            if (updated != null && updated.getStatus() != null
                    && updated.getStatus() != MeshMessage.DeliveryStatus.SENDING) {
                MessageBubbleFactory.updateStatusLabel(entry.getValue(), updated.getStatus());
                return true;
            }
            return false;
        });
        String chatType = currentChatType();
        String chatKey = currentChatKey();

        List<MeshMessage> newMsgs = db.loadAfter(chatType, chatKey, newestLoadedDbId);
        if (!newMsgs.isEmpty()) {
            for (MeshMessage msg : newMsgs) {
                messageContainer.getChildren().add(bubbleFactory.build(msg));
            }
            newestLoadedDbId = newMsgs.getLast().getDbId();
            scrollToBottom();
            markAsRead(selectedChat);
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

    /**
     * Добавить системное (бот) сообщение в указанный чат.
     * Сохраняет в БД. Обновляет UI и счётчик прочитанных, если чат сейчас открыт.
     */
    private void addSystemMessageTo(String chatType, String chatKey, String text) {
        MeshMessage sysMsg = new MeshMessage("!00000000", "!00000000", 0, text, System.currentTimeMillis() / 1000, false);
        sysMsg.setSystemMessage(true);
        MessageDbService.getInstance().save(sysMsg, chatType, chatKey);
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
        MessageDbService.getInstance().save(sysMsg, chatType, chatKey);

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

    // ==================== Traceroute / Node Info ====================

    /** Запрос traceroute до ноды — ответ показывается как системное сообщение */
    private void requestTraceroute(MeshMessage msg) {
        if (state == null || protocolHandler == null) { return; }
        NodeData targetNode = state.getNodeByNodeId(msg.getFromNodeId());
        if (targetNode == null) { return; }
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
        if (targetNode == null) { return; }
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
                if (newState != null) break;
            }
        }

        if (newState == this.state) {
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

        if (this.state != null) {
            this.state.addMessageListener(messageListener);
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
        Map<String, MeshMessage> channelLastMsgs = db.getLastMessagePerChat("channel");
        Map<String, MeshMessage> dmLastMsgs = db.getLastMessagePerChat("dm");

        // 1. Каналы (не DISABLED)
        for (ChannelProtos.Channel channel : state.getChannels()) {
            if (channel.getRole() == ChannelProtos.Channel.Role.DISABLED) { continue; }
            String chKey = String.valueOf(channel.getIndex());
            MeshMessage lastMsg = channelLastMsgs.get(chKey);
            String readKey = "ch:" + channel.getIndex();
            int totalCount = db.getMessageCount("channel", chKey);
            int lastRead = lastReadCounts.getOrDefault(readKey, 0);
            int unread = Math.max(0, totalCount - lastRead);
            items.add(ChatItem.fromChannel(channel, lastMsg, unread));
        }

        // 2. DM-пиры: объединение из БД + текущей сессии
        Set<String> dmPeers = new LinkedHashSet<>(db.getDistinctDmPeers());
        dmPeers.addAll(state.getAllDirectMessages().keySet());
        for (String peerNodeId : dmPeers) {
            MeshMessage lastMsg = dmLastMsgs.get(peerNodeId);
            NodeData peerNode = state.getNodeByNodeId(peerNodeId);
            // Если ноды нет в state, попробовать из кэша нод
            if (peerNode == null) {
                peerNode = NodeCacheService.getInstance().get(peerNodeId);
            }
            String readKey = "dm:" + peerNodeId;
            int totalCount = db.getMessageCount("dm", peerNodeId);
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
    }

    /**
     * Удаляет канал (DISABLED на устройстве) или DM-чат (только локально).
     */
    private void deleteChat(ChatItem item) {
        MessageDbService db = MessageDbService.getInstance();

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

            db.deleteChat("channel", String.valueOf(idx));
            lastReadCounts.remove("ch:" + idx);
        } else {
            String peerNodeId = item.getPeerNodeId();
            if (state != null) {
                state.removeDirectMessages(peerNodeId);
            }
            db.deleteChat("dm", peerNodeId);
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
        int count = db.getMessageCount(dbType, dbKey);
        lastReadCounts.put(readKey, count);
        db.saveReadCount(dbType, dbKey, count);
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
        return text != null && text.toLowerCase().contains(query);
    }
}
