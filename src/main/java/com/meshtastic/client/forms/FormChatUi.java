package com.meshtastic.client.forms;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meshtastic.client.components.NodeDetailContent;
import com.meshtastic.client.components.RemoteAdminPanel;
import com.meshtastic.client.components.RemoteNodeTracerouteWindow;
import com.meshtastic.client.components.chat.ChatBotCommandHelper;
import com.meshtastic.client.components.chat.ChatDbKey;
import com.meshtastic.client.components.chat.ChatInputBar;
import com.meshtastic.client.components.chat.ChatListCell;
import com.meshtastic.client.components.chat.ChatNameResolver;
import com.meshtastic.client.components.chat.MeshFilesImage;
import com.meshtastic.client.components.chat.MessageBubbleFactory;
import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.MessageChangeEvent;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.rpc.RemoteChatJson;
import com.meshtastic.client.protocol.rpc.RemoteNodeJson;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Builds and owns the JavaFX structure of the chat form.
 *
 * <p>This layer is deliberately limited to UI composition: it wires controls,
 * delegates message loading to the message layer, and leaves persistence to the
 * data layer.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
abstract class FormChatUi extends FormChatBase {

    private Region headerSpacer;
    private Button quickScriptButton;
    private ContextMenu quickScriptMenu;
    private FormChatMessageSearchController messageSearchController;
    private StackPane imageViewerOverlay;

    /**
     * Creates the split layout and reusable controls for the active chat.
     * The right pane is kept across chat switches so listeners and heavier
     * controls are not recreated.
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
        FormChatUiSupport.applyWindowsHitTestBackground(leftPane);

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
        searchField.setPromptText(I18n.t("chat.search.placeholder"));
        searchField.getStyleClass().add("chat-search-field");
        return searchField;
    }

    private Button createNewChatButton() {
        Button button = new Button("✎");
        button.getStyleClass().add("chat-new-btn");
        button.setTooltip(new Tooltip(I18n.t("chat.new.tooltip")));
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
        String query = Optional.ofNullable(rawQuery)
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .orElse("");
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
        FormChatUiSupport.applyWindowsHitTestBackground(listView);
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
        Optional.ofNullable(newItem).ifPresentOrElse(this::selectChat, this::closeChat);
    }

    private void selectChat(ChatItem chat) {
        selectedChat = chat;
        rememberSelectedChatForBoundConnection();
        openingChatUnreadCount = chat.getUnreadCount();
        openChat(chat);
    }

    private VBox buildDetailPane() {
        VBox pane = new VBox();
        pane.getStyleClass().add("chat-detail-pane");
        pane.setMinWidth(300);
        pane.addEventFilter(KeyEvent.KEY_PRESSED, this::handleDetailPaneKeyPressed);
        pane.addEventFilter(DragEvent.DRAG_OVER, this::handleChatImageDragOver);
        pane.addEventFilter(DragEvent.DRAG_DROPPED, this::handleChatImageDragDropped);
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

    /**
     * Creates right-pane controls once and reuses them for every chat.
     */
    protected void buildRightPanelComponents() {
        placeholderBox = createPlaceholderBox();
        buildHeader();
        messageSelectionBar = createMessageSelectionBar();
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

        Label placeholder = new Label(I18n.t("chat.emptySelection"));
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

        headerSpacer = new Region();
        quickScriptButton = createQuickScriptButton();
        messageSearchController = createMessageSearchController();

        chatHeader = new HBox(10,
                headerAvatarPane,
                headerNameLabel,
                headerSpacer,
                quickScriptButton,
                messageSearchController.searchButton(),
                messageSearchController.controls());
        chatHeader.setAlignment(Pos.CENTER_LEFT);
        chatHeader.setPadding(new Insets(10, 15, 10, 15));
        chatHeader.getStyleClass().add("chat-header");
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox.setHgrow(messageSearchController.controls(), Priority.ALWAYS);

        headerSep = new Separator();
        headerSep.getStyleClass().add("chat-header-separator");
    }

    /**
     * Creates the message search component and gives it only the form hooks it
     * needs. Current chat, loaded rows, and scrolling remain in the outer layer;
     * search behavior stays inside the component.
     */
    private FormChatMessageSearchController createMessageSearchController() {
        return new FormChatMessageSearchController(
                new FormChatMessageSearchHost(this),
                headerNameLabel,
                headerSpacer);
    }

    private Button createQuickScriptButton() {
        Button button = FormChatUiSupport.createHeaderIconButton(
                "/icons/autoplay.svg",
                I18n.t("chat.quickBots.tooltip"),
                "▶");
        button.setOnAction(event -> toggleQuickScriptMenu(button));
        return button;
    }

    private void toggleQuickScriptMenu(Button anchor) {
        if (quickScriptMenu != null && quickScriptMenu.isShowing()) {
            quickScriptMenu.hide();
            return;
        }
        quickScriptMenu = buildQuickScriptMenu();
        quickScriptMenu.setOnShowing(event -> anchor.getStyleClass().add("chat-header-icon-btn-active"));
        quickScriptMenu.setOnHidden(event -> anchor.getStyleClass().remove("chat-header-icon-btn-active"));
        quickScriptMenu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 4);
    }

    private ContextMenu buildQuickScriptMenu() {
        ContextMenu menu = new ContextMenu();
        List<LuaScript> scripts = quickLaunchScripts();
        if (scripts.isEmpty()) {
            MenuItem emptyItem = new MenuItem(I18n.t("chat.quickBots.empty"));
            emptyItem.setDisable(true);
            menu.getItems().add(emptyItem);
            return menu;
        }

        scripts.forEach(script -> menu.getItems().add(createQuickScriptMenuItem(script)));
        return menu;
    }

    private List<LuaScript> quickLaunchScripts() {
        try {
            return luaScripts().listScripts().stream()
                    .filter(LuaScript::isEnabled)
                    .filter(script -> script.getBotType() == LuaScript.BotType.AUTOMATION_BOT)
                    .filter(script -> hasText(script.getAutomationName()))
                    .sorted(Comparator
                            .comparing(FormChatUi::quickScriptDisplayName, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(script -> script.getAutomationName().trim(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private MenuItem createQuickScriptMenuItem(LuaScript script) {
        String name = quickScriptDisplayName(script);
        String handle = script.getAutomationName().trim();
        MenuItem item = new MenuItem(UnicodeTextUtils.sanitizeForJavaFxDisplay(
                scriptIcon(script) + "  " + name + "  " + handle));
        item.setOnAction(event -> launchQuickScript(script));
        return item;
    }

    private void launchQuickScript(LuaScript script) {
        ChatBotCommandHelper.ParsedBotCommand command = new ChatBotCommandHelper.ParsedBotCommand(
                ChatBotCommandHelper.BotAction.AUTOMATION,
                script.getAutomationName().trim(),
                "",
                false,
                "",
                List.of(),
                script.getId());
        handleBotCommand(command);
    }

    private static String quickScriptDisplayName(LuaScript script) {
        return hasText(script.getName()) ? script.getName().trim() : script.getAutomationName().trim();
    }

    private static String scriptIcon(LuaScript script) {
        return hasText(script.getIcon()) ? script.getIcon().trim() : LuaScript.DEFAULT_ICON;
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
        MessageBubbleFactory factory = new MessageBubbleFactory(
                state,
                messageContainer.widthProperty(),
                new MessageBubbleFactory.BubbleActions() {
                    @Override public void startReply(MeshMessage msg) { FormChatUi.this.startReply(msg); }
                    @Override public void sendReaction(MeshMessage msg, String emoji) {
                        FormChatUi.this.sendReaction(msg, emoji);
                    }
                    @Override public void confirmDeleteMessage(MeshMessage msg, HBox row) {
                        FormChatUi.this.confirmDeleteMessage(msg, row);
                    }
                    @Override public void toggleMessageSelection(MeshMessage msg, HBox row) {
                        FormChatUi.this.toggleMessageSelection(msg, row);
                    }
                    @Override public boolean isMessageSelected(MeshMessage msg) {
                        return FormChatUi.this.isMessageSelected(msg);
                    }
                    @Override public boolean isMessageSelectionModeActive() {
                        return FormChatUi.this.isMessageSelectionModeActive();
                    }
                    @Override public boolean retryMessage(MeshMessage msg) { return FormChatUi.this.retryMessage(msg); }
                    @Override public void openMeshFilesImage(MeshFilesImage image) {
                        FormChatUi.this.showMeshFilesImage(image);
                    }
                },
                pendingStatusLabels);
        factory.setRemoteNodeDetailsProvider(this::resolveRemoteNodeForDetails, this::remoteNodeActionDelegate);
        return factory;
    }

    private CompletableFuture<NodeData> resolveRemoteNodeForDetails(String nodeId) {
        RemoteRpcState rpcState = remoteRpcState;
        if (rpcState == null || nodeId == null || nodeId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return rpcState.client()
                .call("node.get", RemoteNodeJson.nodeIdParams(nodeId), REMOTE_RPC_TIMEOUT)
                .thenApply(result -> {
                    if (rpcState != remoteRpcState) {
                        return null;
                    }
                    updateRemoteNodeFlagCaches(result);
                    List<NodeData> nodes = RemoteNodeJson.parseNodes(result);
                    return nodes.isEmpty() ? null : nodes.getFirst();
                });
    }

    private NodeDetailContent.ActionDelegate remoteNodeActionDelegate() {
        RemoteRpcState rpcState = remoteRpcState;
        return new NodeDetailContent.ActionDelegate() {
            @Override
            public boolean isFavorite(String nodeId) {
                return nodeId != null && remoteNodeFavoriteFlags.getOrDefault(nodeId, false);
            }

            @Override
            public boolean isIgnored(String nodeId) {
                return nodeId != null && remoteNodeIgnoredFlags.getOrDefault(nodeId, false);
            }

            @Override
            public void openDirectChat(NodeData node) {
                if (node != null && node.getNodeId() != null && !node.getNodeId().isBlank()) {
                    FormChatUi.this.openDirectChat(node.getNodeId(), node);
                }
            }

            @Override
            public boolean canTraceroute(NodeData node) {
                return rpcState != null
                        && rpcState.client() != null
                        && rpcState.client().isOpen()
                        && node != null
                        && node.getNodeNum() != 0;
            }

            @Override
            public void tracerouteNode(NodeData node) {
                RemoteNodeTracerouteWindow.showWindow(rpcState, node);
            }

            @Override
            public boolean canRemoteAdmin(NodeData node) {
                return rpcState != null
                        && rpcState.client() != null
                        && rpcState.client().isOpen()
                        && node != null
                        && node.getNodeNum() != 0
                        && node.getPublicKey() != null
                        && node.getPublicKey().length > 0;
            }

            @Override
            public void remoteAdminNode(NodeData node) {
                RemoteAdminPanel.showForRemoteNode(rpcState, node);
            }

            @Override
            public void refreshNode(NodeData node) {
                callRemoteNodeAction(rpcState, "node.refresh", RemoteNodeJson.nodeParams(node), ignored -> { });
            }

            @Override
            public void deleteNode(NodeData node) {
                callRemoteNodeAction(rpcState, "node.delete", RemoteNodeJson.nodeParams(node), result -> {
                    if (node != null && node.getNodeId() != null) {
                        remoteNodeFavoriteFlags.remove(node.getNodeId());
                        remoteNodeIgnoredFlags.remove(node.getNodeId());
                    }
                    reloadChatList();
                });
            }

            @Override
            public void setFavorite(NodeData node, boolean favorite, Consumer<Boolean> callback) {
                callRemoteNodeAction(rpcState, "node.favorite", RemoteNodeJson.flagParams(node, favorite), result -> {
                    updateRemoteNodeFlagMap("node.favorite", node != null ? node.getNodeId() : null, favorite);
                    Optional.ofNullable(callback).ifPresent(cb -> cb.accept(favorite));
                    reloadChatList();
                });
            }

            @Override
            public void setIgnored(NodeData node, boolean ignored, Consumer<Boolean> callback) {
                callRemoteNodeAction(rpcState, "node.ignored", RemoteNodeJson.flagParams(node, ignored), result -> {
                    updateRemoteNodeFlagMap("node.ignored", node != null ? node.getNodeId() : null, ignored);
                    Optional.ofNullable(callback).ifPresent(cb -> cb.accept(ignored));
                    reloadChatList();
                });
            }

            @Override
            public RemoteRpcState remoteRpcState() {
                return rpcState;
            }
        };
    }

    private void callRemoteNodeAction(RemoteRpcState rpcState,
                                      String method,
                                      JsonObject params,
                                      Consumer<JsonElement> onSuccess) {
        if (rpcState == null) {
            return;
        }
        rpcState.client().call(method, params, REMOTE_RPC_TIMEOUT)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (rpcState != remoteRpcState) {
                        return;
                    }
                    if (error != null) {
                        Toast.show(Toast.Type.ERROR, I18n.t("chat.remote.error", RemoteChatJson.errorMessage(error)));
                        return;
                    }
                    updateRemoteNodeFlagCaches(result);
                    if (onSuccess != null) {
                        onSuccess.accept(result);
                    }
                }));
    }

    private void updateRemoteNodeFlagCaches(JsonElement result) {
        remoteNodeFavoriteFlags.putAll(RemoteNodeJson.parseFavoriteFlags(result));
        remoteNodeIgnoredFlags.putAll(RemoteNodeJson.parseIgnoredFlags(result));
    }

    private void updateRemoteNodeFlagMap(String method, String nodeId, boolean enabled) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        if ("node.favorite".equals(method)) {
            remoteNodeFavoriteFlags.put(nodeId, enabled);
        } else if ("node.ignored".equals(method)) {
            remoteNodeIgnoredFlags.put(nodeId, enabled);
        }
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
        Optional.ofNullable(selectedChat)
                .filter(chat -> formVisible)
                .filter(chat -> isAtLiveTail())
                .filter(chat -> getUnreadCount(chat) > 0)
                .ifPresent(this::markAsRead);
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
        Optional.ofNullable(messageSearchController)
                .ifPresent(controller -> controller.handleDetailPaneKeyPressed(event));
    }

    /**
     * Accepts image drag-over gestures anywhere in the open chat detail pane.
     *
     * @param event JavaFX drag event
     */
    private void handleChatImageDragOver(DragEvent event) {
        if (chatInputBar == null || !chatInputBar.canAcceptImageDrop()) {
            return;
        }
        Dragboard dragboard = event.getDragboard();
        if (dragboard.hasFiles()
                && dragboard.getFiles().stream().anyMatch(ChatInputBar::isSupportedImageFile)) {
            event.acceptTransferModes(TransferMode.COPY);
            event.consume();
        }
    }

    /**
     * Delegates dropped image files from the chat detail pane to the input bar.
     *
     * @param event JavaFX drop event
     */
    private void handleChatImageDragDropped(DragEvent event) {
        if (chatInputBar == null) {
            return;
        }
        Dragboard dragboard = event.getDragboard();
        boolean completed = dragboard.hasFiles() && chatInputBar.acceptDroppedImageFiles(dragboard.getFiles());
        event.setDropCompleted(completed);
        if (completed) {
            event.consume();
        }
    }

    protected void closeMessageSearch(boolean focusInput) {
        Optional.ofNullable(messageSearchController)
                .ifPresent(controller -> controller.close(focusInput));
    }

    protected void refreshMessageSearchResults(boolean jumpToLatest) {
        Optional.ofNullable(messageSearchController)
                .ifPresent(controller -> controller.refreshResults(jumpToLatest));
    }

    protected void refreshMessageSearchHighlight() {
        Optional.ofNullable(messageSearchController)
                .ifPresent(FormChatMessageSearchController::refreshHighlight);
    }

    protected void applyMessageSearchHighlight(HBox row, long dbId) {
        Optional.ofNullable(messageSearchController)
                .ifPresent(controller -> controller.applyHighlight(row, dbId));
    }

    private ChatInputBar createChatInputBar() {
        return new ChatInputBar(
                this::sendChatMessage,
                this::handleBotCommand,
                this::suggestBotCommands,
                query -> ChatBotCommandHelper.suggestNodes(listBotCommandNodes(), query, 8),
                this::showMeshFilesImage
        );
    }

    /**
     * Shows a full-size MeshFiles image overlay scaled to the chat message area.
     *
     * @param image normalized MeshFiles image URLs
     */
    private void showMeshFilesImage(MeshFilesImage image) {
        if (image == null || messageArea == null) {
            return;
        }

        hideMeshFilesImage();

        ImageView imageView = new ImageView(new Image(image.url(), true));
        imageView.getStyleClass().add("chat-image-viewer-image");
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.fitWidthProperty().bind(messageArea.widthProperty().subtract(48));
        imageView.fitHeightProperty().bind(messageArea.heightProperty().subtract(48));

        StackPane imageFrame = new StackPane(imageView);
        imageFrame.getStyleClass().add("chat-image-viewer-frame");
        imageFrame.setOnMouseClicked(event -> event.consume());

        Button closeButton = new Button("✕");
        closeButton.getStyleClass().add("chat-image-viewer-close");
        closeButton.setTooltip(new Tooltip(I18n.t("common.close")));
        closeButton.setOnAction(event -> hideMeshFilesImage());

        StackPane overlay = new StackPane(imageFrame, closeButton);
        overlay.getStyleClass().add("chat-image-viewer-overlay");
        overlay.setFocusTraversable(true);
        overlay.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                hideMeshFilesImage();
            }
            event.consume();
        });
        overlay.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                hideMeshFilesImage();
                event.consume();
            }
        });
        StackPane.setAlignment(closeButton, Pos.TOP_RIGHT);
        StackPane.setMargin(closeButton, new Insets(12));

        imageViewerOverlay = overlay;
        messageArea.getChildren().add(overlay);
        Platform.runLater(overlay::requestFocus);
    }

    /**
     * Closes the active MeshFiles image overlay, if any.
     */
    private void hideMeshFilesImage() {
        if (imageViewerOverlay == null) {
            return;
        }
        if (messageArea != null) {
            messageArea.getChildren().remove(imageViewerOverlay);
        }
        imageViewerOverlay = null;
    }

    private List<ChatBotCommandHelper.BotDefinition> suggestBotCommands(String query) {
        return ChatBotCommandHelper.suggestBots(query, automationBotDefinitions());
    }

    private List<ChatBotCommandHelper.BotDefinition> automationBotDefinitions() {
        try {
            return luaScripts().listScripts().stream()
                    .filter(LuaScript::isEnabled)
                    .filter(script -> script.getBotType() == LuaScript.BotType.AUTOMATION_BOT)
                    .filter(script -> hasText(script.getAutomationName()))
                    .map(script -> new ChatBotCommandHelper.BotDefinition(
                            script.getAutomationName().trim(),
                            automationSuggestionDescription(script),
                            ChatBotCommandHelper.BotAction.AUTOMATION,
                            script.getId()))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String automationSuggestionDescription(LuaScript script) {
        String scriptName = hasText(script.getName()) ? script.getName().trim() : "Lua";
        String description = script.getDescription();
        return hasText(description)
                ? I18n.t("chat.automation.descriptionWithDetails", scriptName, description.trim())
                : I18n.t("chat.automation.description", scriptName);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void sendChatMessage(ChatInputBar.SendRequest request) {
        if (remoteRpcState != null) {
            sendRemoteChatMessage(request);
            return;
        }
        if (Optional.ofNullable(selectedChat).isEmpty()
                || Optional.ofNullable(state).isEmpty()
                || (Optional.ofNullable(protocolHandler).isEmpty()
                        && Optional.ofNullable(meshCoreCompanionRuntime).isEmpty())) {
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

    private void sendRemoteChatMessage(ChatInputBar.SendRequest request) {
        if (selectedChat == null || remoteRpcState == null || request == null) {
            return;
        }
        var rpcState = remoteRpcState;
        ChatItem requestChat = selectedChat;
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();
        rpcState.client()
                .call("chat.send",
                        RemoteChatJson.sendParams(
                                chatType,
                                chatKey,
                                request.text(),
                                request.replyId()),
                        REMOTE_RPC_TIMEOUT)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (rpcState != remoteRpcState
                            || selectedChat == null
                            || !chatItemMatches(selectedChat, requestChat)) {
                        return;
                    }
                    if (error != null) {
                        Toast.show(Toast.Type.ERROR, I18n.t("chat.remote.error", RemoteChatJson.errorMessage(error)));
                        return;
                    }
                    MeshMessage sent = RemoteChatJson.parseResultMessage(result);
                    if (sent == null) {
                        reloadChatList();
                        return;
                    }
                    scheduleMessageChangeRefresh(MessageChangeEvent.newMessage(chatType, chatKey, ownerNodeId, sent));
                }));
    }

    private boolean sendChannelMessage(ChatInputBar.SendRequest request) {
        return Optional.ofNullable(meshCoreCompanionRuntime)
                .map(runtime -> Optional.ofNullable(runtime.sendChannelMessage(
                        selectedChat.getChannelIndex(),
                        request.text(),
                        request.replyId())).isPresent())
                .orElseGet(() -> {
                    MessageService.sendChannelMessage(
                            protocolHandler,
                            state,
                            selectedChat.getChannelIndex(),
                            request.text(),
                            request.replyId());
                    return true;
                });
    }

    private boolean sendDirectMessage(ChatInputBar.SendRequest request) {
        NodeData peerNode = NodeUtils.resolveNode(state, selectedChat.getPeerNodeId());
        if (Optional.ofNullable(peerNode).filter(NodeData::isUnmessagable).isPresent()) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.unmessagable"));
            return false;
        }

        return Optional.ofNullable(meshCoreCompanionRuntime)
                .map(runtime -> {
                    MeshMessage sent = runtime.sendDirectMessage(
                            selectedChat.getPeerNodeId(),
                            request.text(),
                            request.replyId());
                    if (Optional.ofNullable(sent).isPresent()) {
                        return true;
                    }
                    Toast.show(Toast.Type.ERROR, I18n.t("chat.toast.meshcoreDmContactMissing"));
                    return false;
                })
                .orElseGet(() -> {
                    MeshMessage sent = MessageService.sendDirectMessage(
                            protocolHandler,
                            state,
                            selectedChat.getPeerNodeId(),
                            request.text(),
                            request.replyId());
                    if (Optional.ofNullable(sent).isPresent()) {
                        return true;
                    }
                    Toast.show(Toast.Type.ERROR, I18n.t("chat.toast.dmNodeMissing"));
                    return false;
                });
    }

    // Right pane: opening and closing chats.

    /**
     * Opens a direct chat with the requested node programmatically.
     * External callers use this after navigating to the chat form. If the
     * conversation is not yet in the list, it is added first.
     */
    public void openDirectChat(String peerNodeId, NodeData peerNode) {
        saveCurrentChatScrollState();
        if (state != null) {
            state.ensureDirectMessageThread(peerNodeId);
        }
        ChatItem dm = ChatItem.fromDirectMessage(
                peerNodeId,
                peerNode,
                (MeshMessage) null,
                0,
                AppPreferences.isChatMuted(
                        currentOwnerNodeId(),
                        AppPreferences.composeChatPreferenceId("dm", peerNodeId)));
        openingChatUnreadCount = 0;

        // Add the conversation when it is not already visible in the chat list.
        boolean exists = chatItems.stream()
                .anyMatch(item -> item.getType() == ChatItem.ChatType.DIRECT_MESSAGE
                               && Objects.equals(item.getPeerNodeId(), peerNodeId));
        if (!exists) {
            chatItems.add(dm);
        }

        // Select the row while suppressing the listener to avoid opening it twice.
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
            hideMeshFilesImage();
            bubbleFactory.hideOpenReactionPopup();
            scrollOperationGeneration++;
            this.selectedChat = chat;
            rememberSelectedChatForBoundConnection();

            // Refresh the header.
            String safeAvatarText = UnicodeTextUtils.sanitizeForJavaFxDisplay(chat.getAvatarText());
            headerAvatarLabel.setText(safeAvatarText);
            headerAvatarLabel.setFont(Font.font("Roboto", FontWeight.BOLD,
                    NodeUtils.chatAvatarFontSize(safeAvatarText, 36)));
            headerAvatarPane.setStyle("-fx-background-color: " + chat.getAvatarColor() +
                    "; -fx-background-radius: 18;");
            headerNameLabel.setText(UnicodeTextUtils.sanitizeForJavaFxDisplay(chat.getDisplayName()));

            // Show header, messages, and input controls.
            detailPane.getChildren().clear();
            detailPane.getChildren().addAll(
                    chatHeader, headerSep, messageSelectionBar, messageArea,
                    chatInputBar.getInputSeparator(), chatInputBar);

            // Leaving a chat also leaves reply mode.
            chatInputBar.cancelPendingImageUpload();
            chatInputBar.cancelReply();

            // Load the latest messages from the database.
            loadInitialMessages(true);
            // Restore pending request bubbles such as traceroute and node info.
            restorePendingCountdowns();

            updateInputEnabled();
            chatInputBar.focusInput();
        } finally {
            resumeScrollStateSyncLater();
        }
    }

    protected void handleChatFontSizeChanged() {
        applyChatTypography();
        Optional.ofNullable(chatListView).ifPresent(ListView::refresh);
        Optional.ofNullable(selectedChat).ifPresent(chat -> refreshLoadedMessageRows(true));
    }

    protected void applyChatTypography() {
        setStyle("-fx-font-size: " + TypographyManager.getChatFontSize() + "px;");
        Optional.ofNullable(getScene()).ifPresent(scene -> {
            applyCss();
            requestLayout();
        });
    }

    protected void closeChat() {
        saveCurrentChatScrollState();
        closeMessageSearch(false);
        hideMeshFilesImage();
        chatInputBar.cancelPendingImageUpload();
        clearSelectedChatForBoundConnection();
        bubbleFactory.hideOpenReactionPopup();
        scrollOperationGeneration++;
        this.selectedChat = null;
        clearLoadedMessageState();
        detailPane.getChildren().clear();
        detailPane.getChildren().add(placeholderBox);
    }

    protected boolean isChatDetailOpenFor(ChatItem chat) {
        return chat != null
                && selectedChat != null
                && chatItemMatches(selectedChat, chat)
                && detailPane != null
                && detailPane.getChildren().contains(messageArea);
    }

    private HBox createMessageSelectionBar() {
        messageSelectionLabel = new Label();
        messageSelectionLabel.getStyleClass().add("chat-message-selection-label");
        HBox.setHgrow(messageSelectionLabel, Priority.ALWAYS);

        deleteSelectedMessagesBtn = FormChatUiSupport.createHeaderIconButton(
                "/drawer/icon/delete-node.svg",
                I18n.t("chat.selection.delete"),
                "×");
        deleteSelectedMessagesBtn.getStyleClass().add("chat-message-selection-delete-btn");
        deleteSelectedMessagesBtn.setOnAction(event -> deleteSelectedMessagesWithConfirmation());

        clearSelectedMessagesBtn = FormChatUiSupport.createHeaderIconButton(
                "/icons/close.svg",
                I18n.t("chat.selection.clear"),
                "×");
        clearSelectedMessagesBtn.setOnAction(event -> clearSelectedMessages());

        HBox bar = new HBox(8, messageSelectionLabel, deleteSelectedMessagesBtn, clearSelectedMessagesBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 15, 8, 15));
        bar.getStyleClass().add("chat-message-selection-bar");
        FormChatUiSupport.setVisibleManaged(bar, false);
        return bar;
    }

    protected void updateMessageSelectionBar() {
        int selectedCount = selectedMessageDbIds.size();
        boolean visible = selectedChat != null && selectedCount > 0;
        FormChatUiSupport.setVisibleManaged(messageSelectionBar, visible);
        if (messageSelectionLabel != null) {
            messageSelectionLabel.setText(messageSelectionCountText(selectedCount));
        }
        if (deleteSelectedMessagesBtn != null) {
            deleteSelectedMessagesBtn.setDisable(selectedCount == 0);
        }
        if (clearSelectedMessagesBtn != null) {
            clearSelectedMessagesBtn.setDisable(selectedCount == 0);
        }
    }

    private static String messageSelectionCountText(int count) {
        return I18n.t("chat.selection.selected." + I18n.pluralCategory(count), count);
    }

    // Messages: paged database loading.

    /** Returns the chat type used by {@link com.meshtastic.client.service.MessageDbService}. */
    protected String currentChatType() {
        return Optional.ofNullable(currentChatDbKey())
                .map(ChatDbKey::dbType)
                .orElse(null);
    }

    protected String currentChatKey() {
        return Optional.ofNullable(currentChatDbKey())
                .map(ChatDbKey::dbKey)
                .orElse(null);
    }

    protected ChatDbKey currentChatDbKey() {
        return Optional.ofNullable(selectedChat)
                .map(ChatDbKey::from)
                .orElse(null);
    }

    protected String currentOwnerNodeId() {
        if (remoteRpcState != null) {
            return boundConnectionId == null || boundConnectionId.isBlank()
                    ? "remote"
                    : "remote:" + boundConnectionId;
        }
        return Optional.ofNullable(state)
                .map(deviceState -> deviceState.getOwnerNodeId())
                .orElse("");
    }
}
