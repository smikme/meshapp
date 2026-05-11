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
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.UnicodeTextUtils;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    private static final Duration MESSAGE_SEARCH_INPUT_PAUSE = Duration.millis(500);

    private Region headerSpacer;
    private PauseTransition messageSearchInputPause;
    private final ContextMenu messageSearchNodeMenu = new ContextMenu();
    private boolean messageSearchTextDirty = false;
    private Button messageSearchNodeButton;
    private boolean messageSearchNodeLookupActive = false;
    private String messageSearchNodeFilterId = "";
    private String messageSearchNodeFilterLabel = "";
    private String messageSearchTextBeforeNodeLookup = "";
    private List<NodeData> currentMessageSearchNodeMatches = List.of();
    private List<CustomMenuItem> currentMessageSearchNodeItems = List.of();
    private int selectedMessageSearchNodeIndex = -1;

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
        rememberSelectedChatForBoundConnection();
        openingChatUnreadCount = newItem.getUnreadCount();
        openChat(newItem);
    }

    private VBox buildDetailPane() {
        VBox pane = new VBox();
        pane.getStyleClass().add("chat-detail-pane");
        pane.setMinWidth(300);
        pane.addEventFilter(KeyEvent.KEY_PRESSED, this::handleDetailPaneKeyPressed);
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

        messageSearchButton = createMessageSearchButton();
        messageSearchControls = createMessageSearchControls();
        headerSpacer = new Region();

        chatHeader = new HBox(10,
                headerAvatarPane,
                headerNameLabel,
                headerSpacer,
                messageSearchButton,
                messageSearchControls);
        chatHeader.setAlignment(Pos.CENTER_LEFT);
        chatHeader.setPadding(new Insets(10, 15, 10, 15));
        chatHeader.getStyleClass().add("chat-header");
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox.setHgrow(messageSearchControls, Priority.ALWAYS);

        headerSep = new Separator();
        headerSep.getStyleClass().add("chat-header-separator");
    }

    private Button createMessageSearchButton() {
        Button button = createHeaderIconButton("/icons/search.svg", "Поиск сообщений", "🔍");
        button.setOnAction(event -> openMessageSearch());
        return button;
    }

    private HBox createMessageSearchControls() {
        messageSearchField = new TextField();
        messageSearchField.setPromptText("Поиск сообщений");
        messageSearchField.setMinWidth(54);
        messageSearchField.getStyleClass().add("chat-message-search-field");
        messageSearchInputPause = new PauseTransition(MESSAGE_SEARCH_INPUT_PAUSE);
        messageSearchInputPause.setOnFinished(event -> runPendingMessageSearch(true));
        messageSearchField.textProperty().addListener((obs, oldValue, newValue) ->
                handleMessageSearchTextChanged(newValue));
        messageSearchField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleMessageSearchFieldKeyPressed);
        messageSearchNodeMenu.addEventFilter(KeyEvent.KEY_PRESSED, this::handleMessageSearchNodeMenuKeyPressed);

        messageSearchNodeButton = createHeaderIconButton("/icons/user.svg", "Фильтр по ноде", "👤");
        messageSearchNodeButton.setOnAction(event -> toggleMessageSearchNodeLookup());

        messageSearchCounterLabel = new Label();
        messageSearchCounterLabel.getStyleClass().add("chat-message-search-counter");

        messageSearchPreviousButton = createMessageSearchNavButton("↑", "Предыдущее совпадение");
        messageSearchPreviousButton.setOnAction(event -> showPreviousMessageSearchResult());

        messageSearchNextButton = createMessageSearchNavButton("↓", "Следующее совпадение");
        messageSearchNextButton.setOnAction(event -> showNextMessageSearchResult());

        messageSearchCloseButton = createHeaderIconButton("/icons/close.svg", "Закрыть поиск", "×");
        messageSearchCloseButton.getStyleClass().add("chat-message-search-close-btn");
        messageSearchCloseButton.setOnAction(event -> closeMessageSearch(true));

        HBox controls = new HBox(4,
                messageSearchNodeButton,
                messageSearchField,
                messageSearchCounterLabel,
                messageSearchPreviousButton,
                messageSearchNextButton,
                messageSearchCloseButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.getStyleClass().add("chat-message-search-controls");
        HBox.setHgrow(messageSearchField, Priority.ALWAYS);
        setVisibleManaged(controls, false);
        updateMessageSearchControlsState();
        return controls;
    }

    private Button createHeaderIconButton(String iconPath, String tooltip, String fallbackText) {
        Button button = new Button();
        button.getStyleClass().add("chat-header-icon-btn");
        Node icon = SvgIconLoader.load(iconPath, 17);
        if (icon != null) {
            button.setGraphic(icon);
        } else {
            button.setText(fallbackText);
        }
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private Button createMessageSearchNavButton(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().addAll("chat-header-icon-btn", "chat-message-search-nav-btn");
        button.setTooltip(new Tooltip(tooltip));
        return button;
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

    private void handleDetailPaneKeyPressed(KeyEvent event) {
        if (selectedChat == null) {
            return;
        }
        if (event.isShortcutDown() && event.getCode() == KeyCode.F) {
            openMessageSearch();
            event.consume();
            return;
        }
        if (messageSearchActive && event.getCode() == KeyCode.ESCAPE) {
            closeMessageSearch(true);
            event.consume();
        }
    }

    private void handleMessageSearchFieldKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            if (messageSearchNodeLookupActive) {
                cancelMessageSearchNodeLookup();
            } else {
                closeMessageSearch(true);
            }
            event.consume();
            return;
        }
        if (messageSearchNodeLookupActive && event.getCode() == KeyCode.DOWN) {
            ensureMessageSearchNodeSuggestions();
            moveMessageSearchNodeSelection(1);
            event.consume();
            return;
        }
        if (messageSearchNodeLookupActive && event.getCode() == KeyCode.UP) {
            ensureMessageSearchNodeSuggestions();
            moveMessageSearchNodeSelection(-1);
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.ENTER) {
            if (messageSearchNodeLookupActive) {
                selectCurrentMessageSearchNode();
                event.consume();
                return;
            }
            if (messageSearchTextDirty) {
                runPendingMessageSearch(true);
                event.consume();
                return;
            }
            if (event.isShiftDown()) {
                showPreviousMessageSearchResult();
            } else {
                showNextMessageSearchResult();
            }
            event.consume();
        }
    }

    private void handleMessageSearchNodeMenuKeyPressed(KeyEvent event) {
        if (!messageSearchNodeLookupActive) {
            return;
        }
        if (event.getCode() == KeyCode.ENTER) {
            selectCurrentMessageSearchNode();
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.DOWN) {
            moveMessageSearchNodeSelection(1);
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.UP) {
            moveMessageSearchNodeSelection(-1);
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.ESCAPE) {
            cancelMessageSearchNodeLookup();
            event.consume();
        }
    }

    private void openMessageSearch() {
        if (selectedChat == null) {
            return;
        }
        if (!messageSearchActive) {
            messageSearchActive = true;
            setVisibleManaged(messageSearchButton, false);
            setVisibleManaged(headerNameLabel, false);
            setVisibleManaged(headerSpacer, false);
            setVisibleManaged(messageSearchControls, true);
            messageSearchButton.getStyleClass().add("chat-header-icon-btn-active");
            updateMessageSearchFieldPrompt();
            updateMessageSearchControlsState();
        }
        Platform.runLater(() -> {
            messageSearchField.requestFocus();
            messageSearchField.selectAll();
        });
    }

    protected void closeMessageSearch(boolean focusInput) {
        messageSearchActive = false;
        stopMessageSearchInputPause();
        messageSearchTextDirty = false;
        messageSearchQuery = "";
        messageSearchResultCount = 0;
        messageSearchResultIndex = -1;
        messageSearchResultCountLimited = false;
        messageSearchHasPrevious = false;
        messageSearchHasNext = false;
        highlightedSearchDbId = 0;
        messageSearchNodeLookupActive = false;
        messageSearchNodeFilterId = "";
        messageSearchNodeFilterLabel = "";
        messageSearchTextBeforeNodeLookup = "";
        hideMessageSearchNodeSuggestions();
        if (messageSearchButton != null) {
            messageSearchButton.getStyleClass().remove("chat-header-icon-btn-active");
            setVisibleManaged(messageSearchButton, true);
        }
        if (messageSearchField != null) {
            messageSearchField.clear();
        }
        if (headerNameLabel != null) {
            setVisibleManaged(headerNameLabel, true);
        }
        if (headerSpacer != null) {
            setVisibleManaged(headerSpacer, true);
        }
        if (messageSearchControls != null) {
            setVisibleManaged(messageSearchControls, false);
        }
        refreshMessageSearchHighlight();
        updateMessageSearchFieldPrompt();
        updateMessageSearchControlsState();
        if (focusInput && selectedChat != null && chatInputBar != null) {
            chatInputBar.focusInput();
        }
    }

    protected void refreshMessageSearchResults(boolean jumpToLatest) {
        if (!messageSearchActive || messageSearchField == null || selectedChat == null
                || messageSearchNodeLookupActive) {
            return;
        }
        if (messageSearchTextDirty) {
            return;
        }

        long previousHighlightedDbId = highlightedSearchDbId;
        int previousResultIndex = messageSearchResultIndex;
        String query = messageSearchField.getText() == null ? "" : messageSearchField.getText().trim();
        messageSearchQuery = query;
        messageSearchResultCount = 0;
        messageSearchResultIndex = -1;
        messageSearchResultCountLimited = false;
        messageSearchHasPrevious = false;
        messageSearchHasNext = false;
        highlightedSearchDbId = 0;

        if (!query.isEmpty()) {
            restoreMessageSearchResult(query, jumpToLatest, previousHighlightedDbId, previousResultIndex);
        }

        updateMessageSearchControlsState();
        refreshMessageSearchHighlight();
        if (jumpToLatest && highlightedSearchDbId > 0) {
            showCurrentMessageSearchResult();
        }
    }

    private void handleMessageSearchTextChanged(String newValue) {
        if (!messageSearchActive) {
            return;
        }

        stopMessageSearchInputPause();
        resetMessageSearchResultState();
        messageSearchQuery = newValue == null ? "" : newValue.trim();
        if (messageSearchNodeLookupActive) {
            messageSearchTextDirty = false;
            refreshMessageSearchHighlight();
            updateMessageSearchControlsState();
            refreshMessageSearchNodeSuggestions();
            return;
        }
        if (messageSearchQuery.isEmpty()) {
            messageSearchTextDirty = false;
            refreshMessageSearchHighlight();
            updateMessageSearchControlsState();
            return;
        }

        messageSearchTextDirty = true;
        refreshMessageSearchHighlight();
        updateMessageSearchControlsState();
        messageSearchInputPause.playFromStart();
    }

    private void runPendingMessageSearch(boolean jumpToLatest) {
        stopMessageSearchInputPause();
        if (!messageSearchActive || messageSearchField == null || messageSearchNodeLookupActive) {
            return;
        }
        messageSearchTextDirty = false;
        refreshMessageSearchResults(jumpToLatest);
    }

    private void stopMessageSearchInputPause() {
        if (messageSearchInputPause != null) {
            messageSearchInputPause.stop();
        }
    }

    private void resetMessageSearchResultState() {
        messageSearchResultCount = 0;
        messageSearchResultIndex = -1;
        messageSearchResultCountLimited = false;
        messageSearchHasPrevious = false;
        messageSearchHasNext = false;
        highlightedSearchDbId = 0;
    }

    private void restoreMessageSearchResult(String query,
                                            boolean jumpToLatest,
                                            long previousHighlightedDbId,
                                            int previousResultIndex) {
        MessageDbService db = MessageDbService.getInstance();
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();
        String fromNodeId = currentMessageSearchNodeFilterId();
        if (!jumpToLatest && previousHighlightedDbId > 0
                && db.messageMatchesSearch(chatType, chatKey, query, ownerNodeId, fromNodeId, previousHighlightedDbId)) {
            highlightedSearchDbId = previousHighlightedDbId;
            refreshMessageSearchCount(db, chatType, chatKey, query, ownerNodeId, fromNodeId);
            messageSearchResultIndex = messageSearchResultCountLimited
                    ? 0
                    : Math.min(Math.max(previousResultIndex, 0), Math.max(0, messageSearchResultCount - 1));
            refreshMessageSearchNavigationAvailability();
            return;
        }

        highlightedSearchDbId = db.findLatestMessageSearchMatch(chatType, chatKey, query, ownerNodeId, fromNodeId);
        if (highlightedSearchDbId > 0) {
            refreshMessageSearchCount(db, chatType, chatKey, query, ownerNodeId, fromNodeId);
            messageSearchResultIndex = messageSearchResultCountLimited ? 0 : Math.max(0, messageSearchResultCount - 1);
            refreshMessageSearchNavigationAvailability();
        }
    }

    private void refreshMessageSearchCount(MessageDbService db,
                                           String chatType,
                                           String chatKey,
                                           String query,
                                           String ownerNodeId,
                                           String fromNodeId) {
        MessageDbService.MessageSearchCount count = db.countMessageSearchMatchesLimited(
                chatType,
                chatKey,
                query,
                ownerNodeId,
                fromNodeId);
        messageSearchResultCount = Math.max(count.count(), highlightedSearchDbId > 0 ? 1 : 0);
        messageSearchResultCountLimited = count.limited();
    }

    private void showPreviousMessageSearchResult() {
        if (!messageSearchHasPrevious || messageSearchQuery.isBlank() || highlightedSearchDbId <= 0) {
            return;
        }
        long previousDbId = MessageDbService.getInstance().findPreviousMessageSearchMatch(
                currentChatType(),
                currentChatKey(),
                messageSearchQuery,
                currentOwnerNodeId(),
                currentMessageSearchNodeFilterId(),
                highlightedSearchDbId);
        if (previousDbId <= 0) {
            return;
        }
        highlightedSearchDbId = previousDbId;
        if (!messageSearchResultCountLimited && messageSearchResultIndex > 0) {
            messageSearchResultIndex--;
        }
        refreshMessageSearchNavigationAvailability();
        showCurrentMessageSearchResult();
    }

    private void showNextMessageSearchResult() {
        if (!messageSearchHasNext
                || messageSearchQuery.isBlank()
                || highlightedSearchDbId <= 0) {
            return;
        }
        long nextDbId = MessageDbService.getInstance().findNextMessageSearchMatch(
                currentChatType(),
                currentChatKey(),
                messageSearchQuery,
                currentOwnerNodeId(),
                currentMessageSearchNodeFilterId(),
                highlightedSearchDbId);
        if (nextDbId <= 0) {
            return;
        }
        highlightedSearchDbId = nextDbId;
        if (!messageSearchResultCountLimited && messageSearchResultIndex < messageSearchResultCount - 1) {
            messageSearchResultIndex++;
        }
        refreshMessageSearchNavigationAvailability();
        showCurrentMessageSearchResult();
    }

    private void showCurrentMessageSearchResult() {
        if (messageSearchResultIndex < 0
                || messageSearchResultCount <= 0
                || highlightedSearchDbId <= 0) {
            highlightedSearchDbId = 0;
            refreshMessageSearchHighlight();
            updateMessageSearchControlsState();
            return;
        }

        ensureMessageLoaded(highlightedSearchDbId);
        requestMessageViewportLayout();
        refreshMessageSearchHighlight();
        scrollToMessage(highlightedSearchDbId, 0);
        updateMessageSearchControlsState();
    }

    private void refreshMessageSearchNavigationAvailability() {
        messageSearchHasPrevious = false;
        messageSearchHasNext = false;
        if (highlightedSearchDbId <= 0 || messageSearchQuery.isBlank()) {
            return;
        }

        MessageDbService db = MessageDbService.getInstance();
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();
        String fromNodeId = currentMessageSearchNodeFilterId();
        messageSearchHasPrevious = db.findPreviousMessageSearchMatch(
                chatType,
                chatKey,
                messageSearchQuery,
                ownerNodeId,
                fromNodeId,
                highlightedSearchDbId) > 0;
        messageSearchHasNext = db.findNextMessageSearchMatch(
                chatType,
                chatKey,
                messageSearchQuery,
                ownerNodeId,
                fromNodeId,
                highlightedSearchDbId) > 0;
    }

    protected void refreshMessageSearchHighlight() {
        if (loadedMessageRows == null || loadedMessageRows.isEmpty()) {
            return;
        }
        for (var entry : loadedMessageRows.entrySet()) {
            applyMessageSearchHighlight(entry.getValue(), entry.getKey());
        }
    }

    protected void applyMessageSearchHighlight(HBox row, long dbId) {
        if (row == null) {
            return;
        }
        row.getStyleClass().remove("chat-message-search-hit");
        if (highlightedSearchDbId > 0 && highlightedSearchDbId == dbId) {
            row.getStyleClass().add("chat-message-search-hit");
        }
    }

    private void toggleMessageSearchNodeLookup() {
        if (!messageSearchActive || messageSearchField == null) {
            return;
        }
        if (messageSearchNodeLookupActive) {
            cancelMessageSearchNodeLookup();
            return;
        }
        activateMessageSearchNodeLookup();
    }

    private void activateMessageSearchNodeLookup() {
        messageSearchTextBeforeNodeLookup = messageSearchField.getText() == null
                ? ""
                : messageSearchField.getText();
        messageSearchNodeLookupActive = true;
        stopMessageSearchInputPause();
        messageSearchTextDirty = false;
        messageSearchQuery = "";
        resetMessageSearchResultState();
        updateMessageSearchFieldPrompt();
        messageSearchField.clear();
        refreshMessageSearchHighlight();
        updateMessageSearchControlsState();
        Platform.runLater(() -> {
            messageSearchField.requestFocus();
            refreshMessageSearchNodeSuggestions();
        });
    }

    private void cancelMessageSearchNodeLookup() {
        String restoredText = messageSearchTextBeforeNodeLookup == null ? "" : messageSearchTextBeforeNodeLookup;
        messageSearchTextBeforeNodeLookup = "";
        messageSearchNodeLookupActive = false;
        hideMessageSearchNodeSuggestions();
        updateMessageSearchFieldPrompt();
        messageSearchField.setText(restoredText);
        messageSearchField.positionCaret(messageSearchField.getText().length());
        if (restoredText == null || restoredText.trim().isEmpty()) {
            resetMessageSearchResultState();
            updateMessageSearchControlsState();
        } else {
            runPendingMessageSearch(true);
        }
        messageSearchField.requestFocus();
    }

    private void selectMessageSearchNode(NodeData node) {
        if (node == null || messageSearchField == null) {
            return;
        }

        messageSearchNodeFilterId = messageSearchNodeId(node);
        messageSearchNodeFilterLabel = ChatBotCommandHelper.displayName(node);
        String restoredText = messageSearchTextBeforeNodeLookup == null ? "" : messageSearchTextBeforeNodeLookup;
        messageSearchTextBeforeNodeLookup = "";
        messageSearchNodeLookupActive = false;
        hideMessageSearchNodeSuggestions();
        updateMessageSearchFieldPrompt();
        messageSearchField.setText(restoredText);
        messageSearchField.positionCaret(messageSearchField.getText().length());
        if (restoredText == null || restoredText.trim().isEmpty()) {
            resetMessageSearchResultState();
            refreshMessageSearchHighlight();
            updateMessageSearchControlsState();
        } else {
            runPendingMessageSearch(true);
        }
        messageSearchField.requestFocus();
    }

    private void refreshMessageSearchNodeSuggestions() {
        if (!messageSearchNodeLookupActive || messageSearchField == null) {
            hideMessageSearchNodeSuggestions();
            return;
        }

        String query = messageSearchField.getText() == null ? "" : messageSearchField.getText().trim();
        List<NodeData> candidates = listBotCommandNodes();
        List<ChatBotCommandHelper.NodeSuggestion> suggestions =
                ChatBotCommandHelper.suggestNodes(candidates, query, 8);
        if (suggestions.isEmpty()) {
            hideMessageSearchNodeSuggestions();
            return;
        }

        List<NodeData> matches = new ArrayList<>();
        List<CustomMenuItem> items = new ArrayList<>();
        for (ChatBotCommandHelper.NodeSuggestion suggestion : suggestions) {
            ChatBotCommandHelper.NodeResolution resolution =
                    ChatBotCommandHelper.resolveTarget(suggestion.insertText(), candidates);
            if (resolution.status() != ChatBotCommandHelper.NodeResolutionStatus.FOUND || resolution.node() == null) {
                continue;
            }
            NodeData node = resolution.node();
            matches.add(node);
            CustomMenuItem item = buildMessageSearchNodeSuggestionItem(suggestion, node);
            int index = items.size();
            item.getContent().setOnMouseEntered(event -> {
                selectedMessageSearchNodeIndex = index;
                updateMessageSearchNodeSelection();
            });
            items.add(item);
        }

        if (items.isEmpty()) {
            hideMessageSearchNodeSuggestions();
            return;
        }

        currentMessageSearchNodeMatches = List.copyOf(matches);
        currentMessageSearchNodeItems = List.copyOf(items);
        selectedMessageSearchNodeIndex = 0;
        messageSearchNodeMenu.getItems().setAll(items);
        updateMessageSearchNodeSelection();
        if (!messageSearchNodeMenu.isShowing() && messageSearchField.getScene() != null) {
            messageSearchNodeMenu.show(messageSearchField, Side.BOTTOM, 0, 0);
        }
    }

    private CustomMenuItem buildMessageSearchNodeSuggestionItem(ChatBotCommandHelper.NodeSuggestion suggestion,
                                                                NodeData node) {
        Label primary = new Label(suggestion.primaryText());
        primary.getStyleClass().add("chat-command-suggestion-primary");

        Label secondary = new Label(suggestion.secondaryText());
        secondary.getStyleClass().add("chat-command-suggestion-secondary");
        boolean hasSecondary = suggestion.secondaryText() != null && !suggestion.secondaryText().isBlank();
        secondary.setVisible(hasSecondary);
        secondary.setManaged(hasSecondary);

        VBox labels = new VBox(2, primary, secondary);
        labels.setAlignment(Pos.CENTER_LEFT);
        labels.getStyleClass().add("map-search-suggestion-row");
        labels.setPrefWidth(Math.max(220, messageSearchField.getWidth()));

        CustomMenuItem item = new CustomMenuItem(labels, true);
        item.setOnAction(event -> selectMessageSearchNode(node));
        return item;
    }

    private void ensureMessageSearchNodeSuggestions() {
        if (currentMessageSearchNodeMatches.isEmpty()) {
            refreshMessageSearchNodeSuggestions();
        } else if (!messageSearchNodeMenu.isShowing() && messageSearchField.getScene() != null) {
            messageSearchNodeMenu.show(messageSearchField, Side.BOTTOM, 0, 0);
        }
    }

    private void moveMessageSearchNodeSelection(int delta) {
        if (currentMessageSearchNodeMatches.isEmpty()) {
            return;
        }
        if (selectedMessageSearchNodeIndex < 0
                || selectedMessageSearchNodeIndex >= currentMessageSearchNodeMatches.size()) {
            selectedMessageSearchNodeIndex = delta > 0 ? 0 : currentMessageSearchNodeMatches.size() - 1;
        } else {
            selectedMessageSearchNodeIndex = Math.floorMod(
                    selectedMessageSearchNodeIndex + delta,
                    currentMessageSearchNodeMatches.size());
        }
        updateMessageSearchNodeSelection();
    }

    private void selectCurrentMessageSearchNode() {
        ensureMessageSearchNodeSuggestions();
        if (selectedMessageSearchNodeIndex < 0
                || selectedMessageSearchNodeIndex >= currentMessageSearchNodeMatches.size()) {
            return;
        }
        selectMessageSearchNode(currentMessageSearchNodeMatches.get(selectedMessageSearchNodeIndex));
    }

    private void updateMessageSearchNodeSelection() {
        for (int i = 0; i < currentMessageSearchNodeItems.size(); i++) {
            Node content = currentMessageSearchNodeItems.get(i).getContent();
            content.getStyleClass().remove("map-search-suggestion-row-selected");
            if (i == selectedMessageSearchNodeIndex) {
                content.getStyleClass().add("map-search-suggestion-row-selected");
            }
        }
    }

    private void hideMessageSearchNodeSuggestions() {
        currentMessageSearchNodeMatches = List.of();
        currentMessageSearchNodeItems = List.of();
        selectedMessageSearchNodeIndex = -1;
        messageSearchNodeMenu.hide();
    }

    private String currentMessageSearchNodeFilterId() {
        return messageSearchNodeFilterId == null || messageSearchNodeFilterId.isBlank()
                ? null
                : messageSearchNodeFilterId;
    }

    private String messageSearchNodeId(NodeData node) {
        String nodeId = node.getNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            return String.format("!%08x", node.getNodeNum());
        }
        return nodeId.trim();
    }

    private void updateMessageSearchFieldPrompt() {
        if (messageSearchField == null) {
            return;
        }
        if (messageSearchNodeLookupActive) {
            messageSearchField.setPromptText("Поиск ноды");
        } else if (currentMessageSearchNodeFilterId() != null) {
            messageSearchField.setPromptText("Поиск сообщений от " + messageSearchNodeFilterLabel);
        } else {
            messageSearchField.setPromptText("Поиск сообщений");
        }
    }

    private void updateMessageSearchNodeButtonState() {
        if (messageSearchNodeButton == null) {
            return;
        }
        messageSearchNodeButton.getStyleClass().remove("chat-header-icon-btn-active");
        if (messageSearchNodeLookupActive || currentMessageSearchNodeFilterId() != null) {
            messageSearchNodeButton.getStyleClass().add("chat-header-icon-btn-active");
        }
        String tooltip = messageSearchNodeLookupActive
                ? "Выбор ноды"
                : currentMessageSearchNodeFilterId() == null
                        ? "Фильтр по ноде"
                        : "Фильтр: " + messageSearchNodeFilterLabel;
        messageSearchNodeButton.setTooltip(new Tooltip(tooltip));
    }

    private void updateMessageSearchControlsState() {
        if (messageSearchCounterLabel == null) {
            return;
        }

        updateMessageSearchNodeButtonState();
        if (messageSearchNodeLookupActive) {
            messageSearchCounterLabel.setText("");
            messageSearchPreviousButton.setDisable(true);
            messageSearchNextButton.setDisable(true);
            return;
        }

        boolean hasQuery = messageSearchField != null
                && messageSearchField.getText() != null
                && !messageSearchField.getText().trim().isEmpty();
        if (hasQuery && messageSearchTextDirty) {
            messageSearchCounterLabel.setText("...");
            messageSearchPreviousButton.setDisable(true);
            messageSearchNextButton.setDisable(true);
            return;
        }
        boolean hasResults = messageSearchResultCount > 0 && messageSearchResultIndex >= 0 && highlightedSearchDbId > 0;
        messageSearchCounterLabel.setText(!hasQuery
                ? ""
                : hasResults
                        ? messageSearchCounterText()
                        : "0");
        messageSearchPreviousButton.setDisable(!hasResults || !messageSearchHasPrevious);
        messageSearchNextButton.setDisable(!hasResults || !messageSearchHasNext);
    }

    private String messageSearchCounterText() {
        if (messageSearchResultCountLimited) {
            return messageSearchResultCount + "+";
        }
        return (messageSearchResultIndex + 1) + "/" + messageSearchResultCount;
    }

    private void setVisibleManaged(Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
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
            closeMessageSearch(false);
            bubbleFactory.hideOpenReactionPopup();
            scrollOperationGeneration++;
            this.selectedChat = chat;
            rememberSelectedChatForBoundConnection();

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
        closeMessageSearch(false);
        clearSelectedChatForBoundConnection();
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
