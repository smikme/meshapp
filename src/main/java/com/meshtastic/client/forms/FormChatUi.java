package com.meshtastic.client.forms;

import com.meshtastic.client.components.chat.ChatBotCommandHelper;
import com.meshtastic.client.components.chat.ChatDbKey;
import com.meshtastic.client.components.chat.ChatInputBar;
import com.meshtastic.client.components.chat.ChatListCell;
import com.meshtastic.client.components.chat.ChatNameResolver;
import com.meshtastic.client.components.chat.MessageBubbleFactory;
import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.UnicodeTextUtils;

import javafx.application.Platform;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
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

import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

/**
 * Создаёт и хранит JavaFX-структуру формы чата.
 *
 * <p>Слой намеренно отвечает только за интерфейс: связывает контролы,
 * делегирует загрузку сообщений слою сообщений и делегирует сохранение
 * слою данных.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
abstract class FormChatUi extends FormChatBase {

    /**
     * Создаёт раздельную компоновку и переиспользуемые контролы активного чата.
     * Правая панель сохраняется при переключении чатов, чтобы не пересоздавать
     * слушатели и тяжёлые элементы управления.
     */
    protected void initComponents() {
        getStyleClass().add("chat-form");

        VBox leftPane = buildLeftPane();
        buildRightPanelComponents();
        detailPane = buildDetailPane();

        SplitPane splitPane = buildSplitPane(leftPane, detailPane);
        getChildren().add(splitPane);
        splitPane.prefWidthProperty().bind(widthProperty());
        splitPane.prefHeightProperty().bind(heightProperty());
    }

    private VBox buildLeftPane() {
        VBox leftPane = new VBox();
        leftPane.getStyleClass().add("chat-list-pane");
        applyWindowsHitTestBackground(leftPane);

        TextField searchField = createSearchField();
        newChatBtn = createNewChatButton();
        configureChatFilter(searchField);
        chatListView = createChatListView();
        StackPane listWrapper = wrapChatList(chatListView);
        leftPane.getChildren().addAll(createSearchBox(searchField), listWrapper);
        return leftPane;
    }

    private TextField createSearchField() {
        TextField searchField = new TextField();
        searchField.setPromptText("🔍 Поиск чатов");
        searchField.getStyleClass().add("chat-search-field");
        return searchField;
    }

    private Button createNewChatButton() {
        Button button = new Button("✎");
        button.getStyleClass().add("chat-new-btn");
        button.setTooltip(new Tooltip("Новый чат"));
        button.setOnAction(event -> showNewChatDialog());
        return button;
    }

    private HBox createSearchBox(TextField searchField) {
        HBox searchBox = new HBox(8, searchField, newChatBtn);
        searchBox.setPadding(new Insets(8));
        searchBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        return searchBox;
    }

    private void configureChatFilter(TextField searchField) {
        filteredChats = new FilteredList<>(chatItems, chat -> true);
        searchField.textProperty().addListener((obs, oldVal, newVal) ->
                filteredChats.setPredicate(chat -> chatMatchesSearch(chat, newVal)));
    }

    private boolean chatMatchesSearch(ChatItem chat, String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        return query.isEmpty()
                || containsIgnoreCase(chat.getDisplayName(), query)
                || containsIgnoreCase(chat.getLastMessageText(), query);
    }

    private ListView<ChatItem> createChatListView() {
        SortedList<ChatItem> sortedChats = new SortedList<>(
                filteredChats,
                Comparator.comparingLong(ChatItem::getLastMessageTime).reversed());

        ListView<ChatItem> listView = new ListView<>(sortedChats);
        listView.getStyleClass().add("chat-list-view");
        applyWindowsHitTestBackground(listView);
        listView.setCellFactory(view -> new ChatListCell(
                this::deleteChat,
                this::showChannelProperties,
                this::toggleChatMute));
        listView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldItem, newItem) -> handleChatSelection(newItem));

        return listView;
    }

    private StackPane wrapChatList(ListView<ChatItem> listView) {
        StackPane wrapper = new StackPane(listView);
        VBox.setVgrow(wrapper, Priority.ALWAYS);
        return wrapper;
    }

    private void handleChatSelection(ChatItem newItem) {
        if (suppressSelectionListener) {
            return;
        }
        saveCurrentChatScrollState();
        if (newItem == null) {
            closeChat();
            return;
        }

        selectedChat = newItem;
        openingChatUnreadCount = newItem.getUnreadCount();
        openChat(newItem);
    }

    private VBox buildDetailPane() {
        VBox pane = new VBox();
        pane.getStyleClass().add("chat-detail-pane");
        pane.setMinWidth(300);
        pane.getChildren().add(placeholderBox);
        return pane;
    }

    private SplitPane buildSplitPane(VBox leftPane, VBox rightPane) {
        SplitPane splitPane = new SplitPane(leftPane, rightPane);
        splitPane.setDividerPositions(AppPreferences.getChatDividerPos());
        splitPane.getStyleClass().add("chat-split-pane");
        SplitPane.setResizableWithParent(leftPane, false);
        splitPane.getDividers().get(0).positionProperty().addListener((obs, oldVal, newVal) ->
                AppPreferences.setChatDividerPos(newVal.doubleValue()));
        return splitPane;
    }

    private void applyWindowsHitTestBackground(javafx.scene.Node node) {
        if (OsDetect.isWindows() && !AppPreferences.isDisableEffectsEffective()) {
            node.setStyle(WINDOWS_HIT_TEST_BACKGROUND);
        }
    }

    /**
     * Один раз создаёт контролы правой панели и переиспользует их для всех чатов.
     */
    protected void buildRightPanelComponents() {
        placeholderBox = createPlaceholderBox();
        buildHeader();
        messageContainer = createMessageContainer();
        nameResolver = new ChatNameResolver(state);
        tracerouteView = createTracerouteView();
        bubbleFactory = createBubbleFactory();
        bubbleFactory.setTracerouteView(tracerouteView);

        messageScrollPane = createMessageScrollPane();
        messageArea = new StackPane(messageScrollPane, scrollDownWrapper);
        VBox.setVgrow(messageArea, Priority.ALWAYS);
        registerMessageScrollListeners();
        chatInputBar = createChatInputBar();
    }

    private VBox createPlaceholderBox() {
        VBox box = new VBox();
        box.setAlignment(Pos.CENTER);
        VBox.setVgrow(box, Priority.ALWAYS);

        Label placeholder = new Label("Выберите, кому хотели бы написать");
        placeholder.getStyleClass().add("form-placeholder-label");
        placeholder.setWrapText(true);
        box.getChildren().add(placeholder);
        return box;
    }

    private void buildHeader() {
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
    }

    private VBox createMessageContainer() {
        VBox container = new VBox(6);
        container.setPadding(new Insets(10, 15, 10, 15));
        container.getStyleClass().add("chat-message-container");
        container.setAlignment(Pos.BOTTOM_LEFT);
        return container;
    }

    private TracerouteView createTracerouteView() {
        return new TracerouteView(
                messageContainer.widthProperty(),
                nameResolver::resolveNodeName,
                this::confirmDeleteMessage);
    }

    private MessageBubbleFactory createBubbleFactory() {
        return new MessageBubbleFactory(
                state,
                messageContainer.widthProperty(),
                new MessageBubbleFactory.BubbleActions() {
                    @Override public void startReply(MeshMessage msg) { FormChatUi.this.startReply(msg); }
                    @Override public void requestTraceroute(MeshMessage msg) { FormChatUi.this.requestTraceroute(msg); }
                    @Override public void requestNodeInfo(MeshMessage msg) { FormChatUi.this.requestNodeInfo(msg); }
                    @Override public void sendReaction(MeshMessage msg, String emoji) { FormChatUi.this.sendReaction(msg, emoji); }
                    @Override public void confirmDeleteMessage(MeshMessage msg, HBox row) { FormChatUi.this.confirmDeleteMessage(msg, row); }
                    @Override public boolean retryMessage(MeshMessage msg) { return FormChatUi.this.retryMessage(msg); }
                },
                pendingStatusLabels);
    }

    private ScrollPane createMessageScrollPane() {
        ScrollPane scrollPane = new ScrollPane(messageContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("chat-message-scroll");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        createScrollDownWrapper();
        return scrollPane;
    }

    private StackPane scrollDownWrapper;

    private void createScrollDownWrapper() {
        scrollDownBtn = new Button("↓");
        scrollDownBtn.getStyleClass().add("chat-scroll-down-btn");
        scrollDownBtn.setVisible(false);
        scrollDownBtn.setOnAction(event -> handleScrollDown());

        scrollDownBadge = new Label();
        scrollDownBadge.getStyleClass().add("chat-scroll-down-badge");
        scrollDownBadge.setVisible(false);
        scrollDownBadge.setMouseTransparent(true);

        scrollDownWrapper = new StackPane(scrollDownBtn, scrollDownBadge);
        scrollDownWrapper.setPickOnBounds(false);
        scrollDownWrapper.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(scrollDownBadge, Pos.TOP_RIGHT);
        StackPane.setAlignment(scrollDownWrapper, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(scrollDownWrapper, new Insets(0, 20, 15, 0));
    }

    private void handleScrollDown() {
        if (allNewerHistoryLoaded) {
            scrollToBottom();
        } else {
            jumpToLatestMessages();
        }
        newMessageWhileScrolled = 0;
        updateScrollDownBadge();
        markSelectedChatReadWhenViewingTail();
    }

    private void registerMessageScrollListeners() {
        messageScrollPane.vvalueProperty().addListener((obs, oldVal, newVal) ->
                handleMessageScroll(newVal.doubleValue()));
        messageContainer.heightProperty().addListener((obs, oldH, newH) ->
                keepLiveTailPinnedAfterResize());
    }

    private void handleMessageScroll(double vvalue) {
        if (!formVisible || isScrollStateSyncSuspended()) {
            return;
        }
        loadAdjacentPagesIfNeeded(vvalue);
        boolean atBottom = isAtLiveTail();
        scrollDownBtn.setVisible(!atBottom);
        refreshTailState(atBottom);
        saveScrollStateAfterUserScroll();
    }

    private void loadAdjacentPagesIfNeeded(double vvalue) {
        if (vvalue < PAGE_LOAD_EDGE_THRESHOLD && !allHistoryLoaded && !loadingOlderMessages) {
            loadOlderMessages();
        }
        if (vvalue > 1.0 - PAGE_LOAD_EDGE_THRESHOLD && !allNewerHistoryLoaded && !loadingNewerMessages) {
            loadNewerMessages();
        }
    }

    private void refreshTailState(boolean atBottom) {
        if (!atBottom) {
            refreshUnreadTailIndicator();
            return;
        }
        if (newMessageWhileScrolled > 0) {
            newMessageWhileScrolled = 0;
            updateScrollDownBadge();
        }
        markSelectedChatReadWhenViewingTail();
    }

    private void markSelectedChatReadWhenViewingTail() {
        if (formVisible && selectedChat != null && isAtLiveTail() && getUnreadCount(selectedChat) > 0) {
            markAsRead(selectedChat);
        }
    }

    private void saveScrollStateAfterUserScroll() {
        if (!loadingOlderMessages && !isScrollStateSyncSuspended()) {
            saveCurrentChatScrollState();
        }
    }

    private void keepLiveTailPinnedAfterResize() {
        if (!formVisible || isScrollStateSyncSuspended() || !isAtLiveTail()) {
            return;
        }

        long generation = scrollOperationGeneration;
        Platform.runLater(() -> {
            if (isCurrentScrollOperation(generation) && !isScrollStateSyncSuspended() && isAtLiveTail()) {
                messageScrollPane.setVvalue(1.0);
            }
        });
    }

    private ChatInputBar createChatInputBar() {
        return new ChatInputBar(
                this::sendChatMessage,
                this::handleBotCommand,
                query -> ChatBotCommandHelper.suggestNodes(listBotCommandNodes(), query, 8)
        );
    }

    private void sendChatMessage(ChatInputBar.SendRequest request) {
        if (selectedChat == null || state == null
                || (protocolHandler == null && meshCoreCompanionRuntime == null)) {
            return;
        }

        boolean sent = switch (selectedChat.getType()) {
            case CHANNEL -> sendChannelMessage(request);
            case DIRECT_MESSAGE -> sendDirectMessage(request);
        };
        if (sent) {
            refreshCurrentChatAfterLocalSend();
        }
    }

    private boolean sendChannelMessage(ChatInputBar.SendRequest request) {
        if (meshCoreCompanionRuntime != null) {
            return meshCoreCompanionRuntime.sendChannelMessage(
                    selectedChat.getChannelIndex(),
                    request.text(),
                    request.replyId()) != null;
        }
        MessageService.sendChannelMessage(
                protocolHandler,
                state,
                selectedChat.getChannelIndex(),
                request.text(),
                request.replyId());
        return true;
    }

    private boolean sendDirectMessage(ChatInputBar.SendRequest request) {
        NodeData peerNode = NodeUtils.resolveNode(state, selectedChat.getPeerNodeId());
        if (peerNode != null && peerNode.isUnmessagable()) {
            Toast.show(Toast.Type.WARNING, "Нода объявила, что не принимает личные сообщения");
            return false;
        }

        if (meshCoreCompanionRuntime != null) {
            MeshMessage sent = meshCoreCompanionRuntime.sendDirectMessage(
                    selectedChat.getPeerNodeId(),
                    request.text(),
                    request.replyId());
            if (sent != null) {
                return true;
            }
            Toast.show(Toast.Type.ERROR, "Не удалось определить MeshCore contact для DM");
            return false;
        }

        MeshMessage sent = MessageService.sendDirectMessage(
                protocolHandler,
                state,
                selectedChat.getPeerNodeId(),
                request.text(),
                request.replyId());
        if (sent != null) {
            return true;
        }

        Toast.show(Toast.Type.ERROR, "Не удалось определить ноду для DM");
        return false;
    }

    // ==================== Правая панель: открытие/закрытие чата ====================

    /**
     * Программно открыть личный чат с указанной нодой.
     * Вызывается извне (например, из NodeDetailContent) после навигации на эту форму.
     * Если чат с этим собеседником ещё не существует в списке — добавляет его.
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

        // Добавить в список, если личного чата с этим собеседником ещё нет
        boolean exists = chatItems.stream()
                .anyMatch(item -> item.getType() == ChatItem.ChatType.DIRECT_MESSAGE
                               && Objects.equals(item.getPeerNodeId(), peerNodeId));
        if (!exists) {
            chatItems.add(dm);
        }

        // Выделить в списке с подавлением слушателя, чтобы не вызвать openChat дважды
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

    protected void openChat(ChatItem chat) {
        suspendScrollStateSync();
        try {
            bubbleFactory.hideOpenReactionPopup();
            scrollOperationGeneration++;
            this.selectedChat = chat;

            // Обновить заголовок
            String safeAvatarText = UnicodeTextUtils.sanitizeForJavaFxDisplay(chat.getAvatarText());
            headerAvatarLabel.setText(safeAvatarText);
            headerAvatarLabel.setFont(Font.font("Roboto", FontWeight.BOLD,
                    NodeUtils.chatAvatarFontSize(safeAvatarText, 36)));
            headerAvatarPane.setStyle("-fx-background-color: " + chat.getAvatarColor() +
                    "; -fx-background-radius: 18;");
            headerNameLabel.setText(UnicodeTextUtils.sanitizeForJavaFxDisplay(chat.getDisplayName()));

            // Показать заголовок, сообщения и панель ввода
            detailPane.getChildren().clear();
            detailPane.getChildren().addAll(
                    chatHeader, headerSep, messageArea,
                    chatInputBar.getInputSeparator(), chatInputBar);

            // Сбросить режим ответа при переключении чата
            chatInputBar.cancelReply();

            // Загрузить последние сообщения из БД
            loadInitialMessages(true);
            // Восстановить пузыри активных запросов (трассировка/инфо) для этого чата
            restorePendingCountdowns();

            updateInputEnabled();
            chatInputBar.focusInput();
        } finally {
            resumeScrollStateSyncLater();
        }
    }

    protected void handleChatFontSizeChanged() {
        applyChatTypography();
        if (chatListView != null) {
            chatListView.refresh();
        }
        if (selectedChat != null) {
            refreshLoadedMessageRows();
        }
    }

    protected void applyChatTypography() {
        setStyle("-fx-font-size: " + TypographyManager.getChatFontSize() + "px;");
        if (getScene() != null) {
            applyCss();
            requestLayout();
        }
    }

    protected void closeChat() {
        saveCurrentChatScrollState();
        bubbleFactory.hideOpenReactionPopup();
        scrollOperationGeneration++;
        this.selectedChat = null;
        clearLoadedMessageState();
        detailPane.getChildren().clear();
        detailPane.getChildren().add(placeholderBox);
    }

    // ==================== Сообщения: загрузка из БД с пагинацией ====================

    /** Определить тип и ключ чата для запросов к MessageDbService */
    protected String currentChatType() {
        ChatDbKey key = currentChatDbKey();
        return key == null ? null : key.dbType();
    }

    protected String currentChatKey() {
        ChatDbKey key = currentChatDbKey();
        return key == null ? null : key.dbKey();
    }

    protected ChatDbKey currentChatDbKey() {
        return selectedChat == null ? null : ChatDbKey.from(selectedChat);
    }

    protected String currentOwnerNodeId() {
        return state != null && state.getOwnerNodeId() != null ? state.getOwnerNodeId() : "";
    }
}
