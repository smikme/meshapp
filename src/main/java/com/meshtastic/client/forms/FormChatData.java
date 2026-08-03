package com.meshtastic.client.forms;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meshtastic.client.components.chat.ChannelPropertiesDialog;
import com.meshtastic.client.components.chat.ChatDbKey;
import com.meshtastic.client.components.chat.CreateChannelDialog;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageChangeEvent;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocolRuntime;
import com.meshtastic.client.protocol.rpc.RemoteChatJson;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.MessageListenerService;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.DrawerManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.UnicodeTextUtils;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.meshtastic.proto.ChannelProtos;

/**
 * Binds the chat UI to the selected radio connection and persisted chat data.
 *
 * <p>This layer handles device rebinding, chat-list composition, read counters,
 * channel and direct-chat deletion, mute state, and modal entry points. Message
 * windows and bot requests are handled by lower layers.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
abstract class FormChatData extends FormChatRequests {

    private static final String MESSAGE_ROW_SELECTED_STYLE_CLASS = "chat-message-row-selected";

    private record ActiveConnection(DeviceState state,
                                    ProtocolHandler handler,
                                    MeshCoreCompanionProtocolRuntime meshCoreRuntime,
                                    RemoteRpcState remoteRpcState,
                                    String connectionId) {}

    private record LocalChatListData(List<ChatItem> items,
                                     Map<String, Integer> readCounts) {}

    protected void updateInputEnabled() {
        boolean canSend = (remoteRpcState != null
                || (state != null && (protocolHandler != null || meshCoreCompanionRuntime != null)))
                && selectedChat != null;
        chatInputBar.setInputEnabled(canSend);
    }

    /**
     * Rebinds the form to the selected connected radio and refreshes dependent UI state.
     * The method is deliberately linear: find the connection, detach old state,
     * attach new state, refresh data, and reopen the selected chat when possible.
     */
    protected void rebindState() {
        var mgr = ConnectionManager.getInstance();
        ActiveConnection activeConnection = findActiveConnection(mgr);
        DeviceState newState = activeConnection.state();
        RemoteRpcState newRemoteRpcState = activeConnection.remoteRpcState();
        String newConnectionId = activeConnection.connectionId();
        boolean connectionChanged = !Objects.equals(newConnectionId, this.boundConnectionId);
        boolean stateChanged = newState != this.state || newRemoteRpcState != this.remoteRpcState || connectionChanged;

        if (!stateChanged) {
            prepareLocalChatListSkeletonIfNeeded();
            reopenVisibleSelectedChatIfNeeded();
            reloadChatListAsync(true);
            return;
        }

        if (selectedChat != null) {
            saveCurrentChatScrollState();
            rememberSelectedChatForBoundConnection();
        }
        closeLuaScriptDataSource();
        unbindPreviousState();
        this.state = newState;
        this.protocolHandler = activeConnection.handler();
        this.meshCoreCompanionRuntime = activeConnection.meshCoreRuntime();
        this.remoteRpcState = newRemoteRpcState;
        this.boundConnectionId = newConnectionId;
        if (connectionChanged) {
            selectedChat = null;
        }

        bindStateDependentComponents(newState);
        if (remoteRpcState != null) {
            lastReadCounts.clear();
        } else {
            prepareLocalChatListSkeletonIfNeeded();
        }
        registerConnectedStateListeners(mgr);

        reopenSelectedChatIfPossible();
        reloadChatListAsync(true);
        updateInputEnabled();
    }

    protected void reopenVisibleSelectedChatIfNeeded() {
        if (formVisible && selectedChat != null && !isChatDetailOpenFor(selectedChat)) {
            reopenSelectedChatIfPossible();
        }
    }

    private ActiveConnection findActiveConnection(ConnectionManager mgr) {
        ConnectionEntry entry = mgr.getSelectedConnectionEntry();
        if (entry != null && entry.isConnected()) {
            DeviceState candidateState = mgr.getDeviceState(entry.getId());
            ProtocolRuntime<?> runtime = mgr.getProtocolRuntime(entry.getId());
            MeshCoreCompanionProtocolRuntime meshRuntime =
                    runtime instanceof MeshCoreCompanionProtocolRuntime companionRuntime
                            ? companionRuntime
                            : null;
            RemoteRpcState rpcState = runtime != null && runtime.getState() instanceof RemoteRpcState remoteState
                    ? remoteState
                    : null;
            return new ActiveConnection(candidateState, mgr.getProtocolHandler(entry.getId()), meshRuntime, rpcState, entry.getId());
        }
        return new ActiveConnection(null, null, null, null, null);
    }

    private void unbindPreviousState() {
        if (state != null) {
            state.removeMessageChangeListener(messageChangeListener);
        }
        if (remoteRpcState != null && remoteChatEventListener != null) {
            remoteRpcState.client().removeEventListener(remoteChatEventListener);
            remoteChatEventListener = null;
        }
        remoteNodeFavoriteFlags.clear();
        remoteNodeIgnoredFlags.clear();
        pendingRemoteReadKeys.clear();
        pendingLocalReadKeys.clear();
    }

    private void bindStateDependentComponents(DeviceState newState) {
        if (bubbleFactory != null) {
            bubbleFactory.setState(newState);
        }
        if (nameResolver != null) {
            nameResolver.setState(newState);
        }
    }

    private void registerConnectedStateListeners(ConnectionManager mgr) {
        String selectedConnectionId = mgr.getSelectedConnectionId();
        for (ConnectionEntry entry : mgr.getEntries()) {
            MessageListenerService messageService = mgr.getMessageListenerService(entry.getId());
            if (messageService != null) {
                messageService.getNotificationManager().setActiveChatChecker(
                        state != null && Objects.equals(entry.getId(), selectedConnectionId)
                                ? this::isCurrentChat
                                : null);
            }
        }

        if (state != null) {
            state.addMessageChangeListener(messageChangeListener);
        }
        if (remoteRpcState != null) {
            remoteChatEventListener = (event, payload) -> {
                if ("message.incoming".equals(event)) {
                    scheduleRemoteMessageRefresh(payload, MessageChangeEvent.Kind.NEW_MESSAGE);
                } else if ("message.status".equals(event)) {
                    scheduleRemoteMessageRefresh(payload, MessageChangeEvent.Kind.STATUS_CHANGED);
                } else if ("message.changed".equals(event)) {
                    scheduleRemoteMessageRefresh(payload, MessageChangeEvent.Kind.METADATA_CHANGED);
                }
            };
            remoteRpcState.client().addEventListener(remoteChatEventListener);
        }
    }

    private void scheduleRemoteMessageRefresh(JsonElement payload, MessageChangeEvent.Kind kind) {
        JsonObject object = payload != null && payload.isJsonObject()
                ? payload.getAsJsonObject()
                : new JsonObject();
        MeshMessage message = RemoteChatJson.parseResultMessage(object);
        String chatType = stringField(object, "chatType");
        String chatKey = stringField(object, "chatKey");
        if (message == null || chatType.isBlank() || chatKey.isBlank()) {
            scheduleMessageChangeRefresh(MessageChangeEvent.unknown());
            return;
        }
        scheduleMessageChangeRefresh(switch (kind) {
            case NEW_MESSAGE -> MessageChangeEvent.newMessage(chatType, chatKey, currentOwnerNodeId(), message);
            case STATUS_CHANGED -> MessageChangeEvent.statusChanged(chatType, chatKey, currentOwnerNodeId(), message);
            case METADATA_CHANGED -> MessageChangeEvent.metadataChanged(chatType, chatKey, currentOwnerNodeId(), message);
            default -> MessageChangeEvent.unknown();
        });
    }

    private static String stringField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString();
        return value == null ? "" : value.trim();
    }

    protected void reopenSelectedChatIfPossible() {
        if (detailPane == null) {
            return;
        }

        if (state == null && remoteRpcState == null) {
            clearLoadedMessageState();
            detailPane.getChildren().clear();
            detailPane.getChildren().add(placeholderBox);
            return;
        }

        if (selectedChat == null && selectedChatForBoundConnection() == null) {
            clearLoadedMessageState();
            detailPane.getChildren().clear();
            detailPane.getChildren().add(placeholderBox);
            updateInputEnabled();
            return;
        }

        ChatSelection savedSelection = selectedChatForBoundConnection();
        ChatItem currentSelection = selectedChat;
        ChatItem matched = chatListView.getItems().stream()
                .filter(item -> currentSelection != null
                        ? chatItemMatches(item, currentSelection)
                        : chatItemMatchesSelection(item, savedSelection))
                .findFirst()
                .orElse(null);

        if (matched == null) {
            closeChat();
            clearSelectedChatForBoundConnection();
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

    protected void reloadChatList() {
        if (state == null) {
            if (remoteRpcState != null) {
                reloadRemoteChatList();
                return;
            }
            chatItems.clear();
            protocolHandler = null;
            meshCoreCompanionRuntime = null;
            return;
        }

        MessageDbService db = MessageDbService.getInstance();
        String ownerId = currentOwnerNodeId();
        Map<String, MessageDbService.ChatSummary> summaries = db.getChatSummaries(ownerId);
        List<ChatItem> items = new ArrayList<>();
        items.addAll(loadChannelChatItems(summaries, ownerId));
        items.addAll(loadDirectChatItems(db, summaries, ownerId));

        applyChatItemsPreservingSelection(items);
        clearChatUnreadDotIfFormVisible();
    }

    @Override
    protected void refreshChatListAfterMessageEvents(List<MessageChangeEvent> events) {
        if (events == null || events.isEmpty()) {
            reloadChatListAsync();
            return;
        }

        boolean requiresFullReload = false;
        for (MessageChangeEvent event : events) {
            if (event == null || event.kind() == MessageChangeEvent.Kind.UNKNOWN
                    || event.kind() == MessageChangeEvent.Kind.DELETE) {
                requiresFullReload = true;
                continue;
            }
            if (event.kind() != MessageChangeEvent.Kind.NEW_MESSAGE) {
                continue;
            }
            requiresFullReload |= !patchChatListForNewMessage(event);
        }
        if (requiresFullReload) {
            reloadChatListAsync();
        } else {
            clearChatUnreadDotIfFormVisible();
        }
    }

    private boolean patchChatListForNewMessage(MessageChangeEvent event) {
        MeshMessage message = event.message();
        if (message == null || !event.hasChatScope()
                || !Objects.equals(event.ownerNodeId(), currentOwnerNodeId())) {
            return false;
        }

        for (int i = 0; i < chatItems.size(); i++) {
            ChatItem current = chatItems.get(i);
            if (!chatItemMatchesScope(current, event.chatType(), event.chatKey())) {
                continue;
            }
            boolean viewingLiveTail = selectedChat != null
                    && chatItemMatches(selectedChat, current)
                    && formVisible
                    && (isAtLiveTail()
                        || pendingLocalReadKeys.contains(ChatDbKey.from(current).readKey()));
            int unreadCount = current.getUnreadCount();
            if (!message.isOutgoing()) {
                unreadCount = viewingLiveTail ? 0 : unreadCount + 1;
            }
            replaceChatItem(i, current.withLastMessage(message, unreadCount));
            return true;
        }
        return false;
    }

    private static boolean chatItemMatchesScope(ChatItem item, String chatType, String chatKey) {
        if (item == null || chatType == null || chatKey == null) {
            return false;
        }
        return item.getType() == ChatItem.ChatType.CHANNEL
                ? "channel".equals(chatType) && Integer.toString(item.getChannelIndex()).equals(chatKey)
                : "dm".equals(chatType) && Objects.equals(item.getPeerNodeId(), chatKey);
    }

    private void replaceChatItem(int index, ChatItem replacement) {
        ChatItem previous = chatItems.get(index);
        boolean replacesSelectedChat = selectedChat != null
                && chatItemMatches(selectedChat, previous);
        suppressSelectionListener = true;
        try {
            // SortedList may move the updated item immediately. Suppress the
            // selection listener before mutating the source list so ListView
            // cannot interpret the old numeric index as a different chat.
            chatItems.set(index, replacement);
            if (replacesSelectedChat) {
                selectedChat = replacement;
                chatListView.getSelectionModel().select(replacement);
            }
        } finally {
            suppressSelectionListener = false;
        }
    }

    @Override
    protected void reloadChatListAsync() {
        reloadChatListAsync(false);
    }

    protected void reloadChatListAsync(boolean refreshReadCounts) {
        if (remoteRpcState != null) {
            reloadRemoteChatList();
            return;
        }
        DeviceState requestState = state;
        String requestOwnerId = currentOwnerNodeId();
        String requestConnectionId = boundConnectionId;
        if (requestState == null) {
            chatItems.clear();
            return;
        }

        Map<String, Integer> currentReadCounts = Map.copyOf(lastReadCounts);
        supplyChatDb(() -> buildLocalChatItems(
                        requestState,
                        requestOwnerId,
                        refreshReadCounts
                                ? MessageDbService.getInstance().loadAllReadCounts(requestOwnerId)
                                : currentReadCounts))
                .whenComplete((data, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        return;
                    }
                    if (requestState != state
                            || !Objects.equals(requestConnectionId, boundConnectionId)
                            || !Objects.equals(requestOwnerId, currentOwnerNodeId())) {
                        return;
                    }
                    if (refreshReadCounts) {
                        lastReadCounts.clear();
                        lastReadCounts.putAll(data.readCounts());
                    }
                    applyChatItemsPreservingSelection(data.items());
                    clearChatUnreadDotIfFormVisible();
                }));
    }

    private LocalChatListData buildLocalChatItems(DeviceState requestState,
                                                  String ownerId,
                                                  Map<String, Integer> readCounts) {
        MessageDbService db = MessageDbService.getInstance();
        Map<String, MessageDbService.ChatSummary> summaries = db.getChatSummaries(ownerId);
        List<ChatItem> items = new ArrayList<>();
        items.addAll(loadChannelChatItems(requestState, summaries, ownerId, readCounts));
        items.addAll(loadDirectChatItems(requestState, db, summaries, ownerId, readCounts));
        return new LocalChatListData(items, readCounts);
    }

    private void prepareLocalChatListSkeletonIfNeeded() {
        if (state == null || remoteRpcState != null || !chatItems.isEmpty()) {
            return;
        }

        List<ChatItem> items = new ArrayList<>();
        for (ChannelProtos.Channel channel : state.getChannels()) {
            if (channel.getRole() != ChannelProtos.Channel.Role.DISABLED) {
                items.add(ChatItem.fromChannel(channel, (MeshMessage) null, 0,
                        isMuted(currentOwnerNodeId(), ChatDbKey.channel(channel.getIndex()))));
            }
        }

        Set<String> dmPeers = new LinkedHashSet<>(state.getAllDirectMessages().keySet());
        ChatSelection savedSelection = selectedChatForBoundConnection();
        if (savedSelection != null
                && savedSelection.type() == ChatItem.ChatType.DIRECT_MESSAGE
                && savedSelection.peerNodeId() != null
                && !savedSelection.peerNodeId().isBlank()) {
            dmPeers.add(savedSelection.peerNodeId());
        }
        for (String peerNodeId : dmPeers) {
            ChatDbKey key = ChatDbKey.direct(peerNodeId);
            NodeData peerNode = state.getNodeByNodeId(peerNodeId);
            if (peerNode == null) {
                peerNode = NodeCacheService.getInstance().getCached(peerNodeId);
            }
            items.add(ChatItem.fromDirectMessage(
                    peerNodeId, peerNode, (MeshMessage) null, 0, isMuted(currentOwnerNodeId(), key)));
        }
        applyChatItemsPreservingSelection(items);
    }

    private void reloadRemoteChatList() {
        RemoteRpcState rpcState = remoteRpcState;
        if (rpcState == null) {
            chatItems.clear();
            return;
        }
        rpcState.client()
                .call("chat.list", new JsonObject(), REMOTE_RPC_TIMEOUT)
                .whenComplete((result, error) -> javafx.application.Platform.runLater(() -> {
                    if (error != null || rpcState != remoteRpcState) {
                        if (error != null) {
                            Toast.show(Toast.Type.ERROR, I18n.t("chat.remote.error", RemoteChatJson.errorMessage(error)));
                        }
                        return;
                    }
                    List<ChatItem> items = RemoteChatJson.parseChatItems(result, currentOwnerNodeId());
                    applyChatItemsPreservingSelection(items);
                    clearChatUnreadDotIfFormVisible();
                    reopenVisibleSelectedChatIfNeeded();
                }));
    }

    private List<ChatItem> loadChannelChatItems(Map<String, MessageDbService.ChatSummary> summaries,
                                                String ownerId) {
        return loadChannelChatItems(state, summaries, ownerId);
    }

    private List<ChatItem> loadChannelChatItems(DeviceState sourceState,
                                                Map<String, MessageDbService.ChatSummary> summaries,
                                                String ownerId) {
        return loadChannelChatItems(sourceState, summaries, ownerId, lastReadCounts);
    }

    private List<ChatItem> loadChannelChatItems(DeviceState sourceState,
                                                Map<String, MessageDbService.ChatSummary> summaries,
                                                String ownerId,
                                                Map<String, Integer> readCounts) {
        List<ChatItem> items = new ArrayList<>();

        for (ChannelProtos.Channel channel : sourceState.getChannels()) {
            if (channel.getRole() == ChannelProtos.Channel.Role.DISABLED) {
                continue;
            }
            ChatDbKey key = ChatDbKey.channel(channel.getIndex());
            MessageDbService.ChatSummary summary = summaries.get(
                    MessageDbService.chatSummaryKey(key.dbType(), key.dbKey()));
            items.add(ChatItem.fromChannel(
                    channel,
                    summary != null ? summary.lastMessage() : null,
                    unreadCount(summary, key, readCounts),
                    isMuted(ownerId, key)));
        }
        return items;
    }

    private List<ChatItem> loadDirectChatItems(MessageDbService db,
                                               Map<String, MessageDbService.ChatSummary> summaries,
                                               String ownerId) {
        return loadDirectChatItems(state, db, summaries, ownerId);
    }

    private List<ChatItem> loadDirectChatItems(DeviceState sourceState,
                                               MessageDbService db,
                                               Map<String, MessageDbService.ChatSummary> summaries,
                                               String ownerId) {
        return loadDirectChatItems(sourceState, db, summaries, ownerId, lastReadCounts);
    }

    private List<ChatItem> loadDirectChatItems(DeviceState sourceState,
                                               MessageDbService db,
                                               Map<String, MessageDbService.ChatSummary> summaries,
                                               String ownerId,
                                               Map<String, Integer> readCounts) {
        Set<String> dmPeers = new LinkedHashSet<>(db.getDistinctDmPeers(ownerId));
        dmPeers.addAll(sourceState.getAllDirectMessages().keySet());
        ChatSelection savedSelection = selectedChatForBoundConnection();
        if (savedSelection != null
                && savedSelection.type() == ChatItem.ChatType.DIRECT_MESSAGE
                && savedSelection.peerNodeId() != null
                && !savedSelection.peerNodeId().isBlank()) {
            dmPeers.add(savedSelection.peerNodeId());
            db.ensureChatThread("dm", savedSelection.peerNodeId(), ownerId);
        }

        List<ChatItem> items = new ArrayList<>();
        for (String peerNodeId : dmPeers) {
            ChatDbKey key = ChatDbKey.direct(peerNodeId);
            MessageDbService.ChatSummary summary = summaries.get(
                    MessageDbService.chatSummaryKey(key.dbType(), key.dbKey()));
            items.add(ChatItem.fromDirectMessage(
                    peerNodeId,
                    resolvePeerNode(sourceState, peerNodeId),
                    summary != null ? summary.lastMessage() : null,
                    unreadCount(summary, key, readCounts),
                    isMuted(ownerId, key)));
        }
        return items;
    }

    private NodeData resolvePeerNode(DeviceState sourceState, String peerNodeId) {
        NodeData peerNode = sourceState.getNodeByNodeId(peerNodeId);
        return peerNode != null ? peerNode : NodeCacheService.getInstance().get(peerNodeId);
    }

    private static int unreadCount(MessageDbService.ChatSummary summary,
                                   ChatDbKey key,
                                   Map<String, Integer> readCounts) {
        int totalCount = summary != null ? summary.unreadEligibleCount() : 0;
        int lastRead = readCounts.getOrDefault(key.readKey(), 0);
        return Math.max(0, totalCount - lastRead);
    }

    private boolean isMuted(String ownerId, ChatDbKey key) {
        return AppPreferences.isChatMuted(ownerId, key.preferenceId());
    }

    private void applyChatItemsPreservingSelection(List<ChatItem> items) {
        suppressSelectionListener = true;
        try {
            chatItems.setAll(items);
            ChatItem matched = findRestorableChatItem();
            if (matched == null) {
                clearUnavailableChatSelection();
                return;
            }
            selectedChat = matched;
            chatListView.getSelectionModel().select(matched);
        } finally {
            suppressSelectionListener = false;
        }
    }

    private void clearUnavailableChatSelection() {
        if (selectedChat != null) {
            closeChat();
            clearSelectedChatForBoundConnection();
        } else {
            clearSelectedChatForBoundConnection();
            clearLoadedMessageState();
            if (detailPane != null) {
                detailPane.getChildren().clear();
                detailPane.getChildren().add(placeholderBox);
            }
        }
        chatListView.getSelectionModel().clearSelection();
        updateInputEnabled();
    }

    private ChatItem findRestorableChatItem() {
        ChatSelection savedSelection = selectedChatForBoundConnection();
        if (savedSelection != null) {
            return chatListView.getItems().stream()
                    .filter(item -> chatItemMatchesSelection(item, savedSelection))
                    .findFirst()
                    .orElse(null);
        }
        if (selectedChat == null) {
            return null;
        }
        return chatListView.getItems().stream()
                .filter(item -> chatItemMatches(item, selectedChat))
                .findFirst()
                .orElse(null);
    }

    /**
     * Opens the channel properties panel.
     */
    protected void showChannelProperties(ChatItem item) {
        if (item == null || item.getType() != ChatItem.ChatType.CHANNEL) {
            return;
        }
        if (state == null || protocolHandler == null) {
            if (meshCoreCompanionRuntime != null) {
                Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.channelPropertiesMeshtasticOnly"));
                return;
            }
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.radioNotConnected"));
            return;
        }
        ChannelPropertiesDialog.show(state, protocolHandler,
                item.getChannelIndex(), this::reloadChatList);
    }

    protected void toggleChatMute(ChatItem item) {
        if (item == null) {
            return;
        }
        boolean muted = !item.isMuted();
        AppPreferences.setChatMuted(currentOwnerNodeId(), ChatDbKey.from(item).preferenceId(), muted);
        for (int i = 0; i < chatItems.size(); i++) {
            if (chatItemMatches(chatItems.get(i), item)) {
                replaceChatItem(i, chatItems.get(i).withMuted(muted));
                break;
            }
        }
    }

    /**
     * Confirms and clears the local message history for a channel or DM.
     */
    protected void confirmClearChatHistory(ChatItem item) {
        if (item == null) {
            return;
        }
        if (remoteRpcState != null) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.remoteManagementUnavailable"));
            return;
        }
        ModalPane pane = ModalPane.getInstance();
        if (pane == null) {
            return;
        }
        pane.show(buildClearHistoryConfirmationPanel(item, () -> clearChatHistory(item)));
    }

    private VBox buildClearHistoryConfirmationPanel(ChatItem item, Runnable onConfirm) {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(380);
        panel.setMaxWidth(380);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        String displayName = UnicodeTextUtils.sanitizeForJavaFxDisplay(item.getDisplayName());
        Label titleLabel = new Label(I18n.t("chat.confirm.clearHistory.title", displayName));
        titleLabel.getStyleClass().add("dialog-title");

        Label messageLabel = new Label(I18n.t("chat.confirm.clearHistory.message"));
        messageLabel.setWrapText(true);

        CheckBox acknowledgeCheckBox = new CheckBox(I18n.t("chat.confirm.clearHistory.acknowledge"));
        acknowledgeCheckBox.setWrapText(true);

        Button cancelButton = new Button(I18n.t("common.cancel"));
        cancelButton.setOnAction(e -> Optional.ofNullable(ModalPane.getInstance()).ifPresent(ModalPane::hide));

        Button confirmButton = new Button(I18n.t("chat.confirm.clearHistory.action"));
        confirmButton.getStyleClass().add("accent");
        confirmButton.disableProperty().bind(acknowledgeCheckBox.selectedProperty().not());
        confirmButton.setOnAction(e -> {
            Optional.ofNullable(ModalPane.getInstance()).ifPresent(ModalPane::hide);
            onConfirm.run();
        });

        HBox buttonRow = new HBox(10, cancelButton, confirmButton);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);
        buttonRow.setPadding(new Insets(10, 0, 0, 0));

        panel.getChildren().addAll(
                titleLabel,
                new Separator(),
                messageLabel,
                acknowledgeCheckBox,
                buttonRow);
        return panel;
    }

    private void clearChatHistory(ChatItem item) {
        if (item == null) {
            return;
        }

        MessageDbService db = MessageDbService.getInstance();
        ChatDbKey key = ChatDbKey.from(item);
        String ownerId = currentOwnerNodeId();

        savedChatScrollStates.remove(chatScrollCacheKey(item));
        AppPreferences.removeChatScrollState(ownerId, key.scrollStateKey());

        db.deleteChat(key.dbType(), key.dbKey(), ownerId);
        lastReadCounts.remove(key.readKey());
        preserveChatListItemAfterHistoryClear(db, key, ownerId, item);

        if (isChatDetailOpenFor(item)) {
            closeMessageSearch(false);
            openingChatUnreadCount = 0;
            loadInitialMessages(false);
            refreshMessageSearchResults(false);
        }

        reloadChatList();
    }

    private void preserveChatListItemAfterHistoryClear(MessageDbService db,
                                                       ChatDbKey key,
                                                       String ownerId,
                                                       ChatItem item) {
        if (item.getType() != ChatItem.ChatType.DIRECT_MESSAGE) {
            return;
        }
        db.ensureChatThread(key.dbType(), key.dbKey(), ownerId);
        if (state != null) {
            state.ensureDirectMessageThread(item.getPeerNodeId());
        }
    }

    /**
     * Deletes a channel on the device or removes a direct chat locally.
     */
    protected void deleteChat(ChatItem item) {
        if (item == null) {
            return;
        }
        if (remoteRpcState != null) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.remoteManagementUnavailable"));
            return;
        }
        MessageDbService db = MessageDbService.getInstance();
        ChatDbKey key = ChatDbKey.from(item);
        String chatId = key.scrollStateKey();
        boolean wasSelected = selectedChat != null && chatItemMatches(selectedChat, item);
        boolean wasSavedSelection = chatItemMatchesSelection(item, selectedChatForBoundConnection());
        savedChatScrollStates.remove(chatScrollCacheKey(item));
        AppPreferences.removeChatScrollState(currentOwnerNodeId(), chatId);

        switch (item.getType()) {
            case CHANNEL -> deleteChannelChat(db, item, key);
            case DIRECT_MESSAGE -> deleteDirectChat(db, item, key);
        }

        if (wasSelected) {
            closeChat();
        }
        if (wasSelected || wasSavedSelection) {
            clearSelectedChatForBoundConnection();
        }

        reloadChatList();
    }

    private void deleteChannelChat(MessageDbService db, ChatItem item, ChatDbKey key) {
        if (state != null && protocolHandler != null) {
            ChannelProtos.Channel disabled = ChannelProtos.Channel.newBuilder()
                    .setIndex(item.getChannelIndex())
                    .setRole(ChannelProtos.Channel.Role.DISABLED)
                    .build();
            MessageService.setChannel(protocolHandler, state, disabled, state.getSessionPasskey());
            state.updateChannel(disabled);
        }

        db.deleteChat(key.dbType(), key.dbKey(), currentOwnerNodeId());
        lastReadCounts.remove(key.readKey());
    }

    private void deleteDirectChat(MessageDbService db, ChatItem item, ChatDbKey key) {
        if (state != null) {
            state.removeDirectMessages(item.getPeerNodeId());
        }

        db.deleteChat(key.dbType(), key.dbKey(), currentOwnerNodeId());
        db.deleteChatThread(key.dbType(), key.dbKey(), currentOwnerNodeId());
        lastReadCounts.remove(key.readKey());
    }

    /** Confirms and deletes one message. */
    protected void confirmDeleteMessage(MeshMessage msg, HBox bubbleRow) {
        if (remoteRpcState != null) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.remoteManagementUnavailable"));
            return;
        }
        String preview = msg.getText();
        preview = UnicodeTextUtils.truncateWithSuffix(preview, 40, "…");
        ModalPane.showConfirm(
                I18n.t("chat.confirm.deleteMessage.title"),
                preview != null ? preview : "",
                confirmed -> {
                    if (!confirmed) { return; }
                    deleteMessageFromCurrentChat(msg, bubbleRow);
                }
        );
    }

    private void deleteMessageFromCurrentChat(MeshMessage msg, HBox bubbleRow) {
        MessageDbService.getInstance().deleteMessage(msg.getDbId());
        selectedMessageDbIds.remove(msg.getDbId());
        loadedMessages.removeIf(loaded -> loaded.getDbId() == msg.getDbId());
        loadedMessageRows.remove(msg.getDbId());
        loadedRenderedMessageRows.remove(msg.getDbId());
        recalcLoadedBounds();
        messageRows.remove(bubbleRow);
        updateMessageSelectionBar();
        refreshMessageSearchResults(false);
        reloadChatList();
    }

    @Override
    protected void toggleMessageSelection(MeshMessage msg, HBox row) {
        if (msg == null || msg.getDbId() <= 0) {
            return;
        }

        long dbId = msg.getDbId();
        boolean selected = selectedMessageDbIds.remove(dbId)
                ? false
                : selectedMessageDbIds.add(dbId);
        setMessageRowSelected(row, selected);
        updateMessageSelectionBar();
    }

    @Override
    protected boolean isMessageSelected(MeshMessage msg) {
        return msg != null && selectedMessageDbIds.contains(msg.getDbId());
    }

    @Override
    protected boolean isMessageSelectionModeActive() {
        return !selectedMessageDbIds.isEmpty();
    }

    @Override
    protected void clearSelectedMessages() {
        if (selectedMessageDbIds.isEmpty()) {
            updateMessageSelectionBar();
            return;
        }
        selectedMessageDbIds.stream()
                .map(loadedMessageRows::get)
                .forEach(row -> setMessageRowSelected(row, false));
        selectedMessageDbIds.clear();
        updateMessageSelectionBar();
    }

    @Override
    protected void deleteSelectedMessagesWithConfirmation() {
        List<Long> targetDbIds = selectedLoadedMessageDbIds();
        if (targetDbIds.isEmpty()) {
            clearSelectedMessages();
            return;
        }

        ModalPane.showConfirm(
                I18n.t("chat.confirm.deleteMessages.title"),
                I18n.t("chat.confirm.deleteMessages.message", targetDbIds.size()),
                confirmed -> {
                    if (confirmed) {
                        deleteMessagesFromCurrentChat(targetDbIds);
                    }
                }
        );
    }

    private List<Long> selectedLoadedMessageDbIds() {
        return loadedMessages.stream()
                .map(MeshMessage::getDbId)
                .filter(selectedMessageDbIds::contains)
                .toList();
    }

    private void deleteMessagesFromCurrentChat(Collection<Long> dbIds) {
        if (dbIds == null || dbIds.isEmpty()) {
            return;
        }

        Set<Long> targetDbIds = dbIds.stream()
                .filter(Objects::nonNull)
                .filter(dbId -> dbId > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (targetDbIds.isEmpty()) {
            return;
        }

        MessageDbService db = MessageDbService.getInstance();
        targetDbIds.forEach(db::deleteMessage);

        loadedMessages.removeIf(loaded -> targetDbIds.contains(loaded.getDbId()));
        targetDbIds.forEach(loadedRenderedMessageRows::remove);
        targetDbIds.forEach(selectedMessageDbIds::remove);
        targetDbIds.stream()
                .map(loadedMessageRows::remove)
                .filter(Objects::nonNull)
                .forEach(messageRows::remove);

        recalcLoadedBounds();
        updateMessageSelectionBar();
        refreshMessageSearchResults(false);
        reloadChatList();
    }

    protected void setMessageRowSelected(HBox row, boolean selected) {
        Optional.ofNullable(row).ifPresent(target -> {
            if (selected && !target.getStyleClass().contains(MESSAGE_ROW_SELECTED_STYLE_CLASS)) {
                target.getStyleClass().add(MESSAGE_ROW_SELECTED_STYLE_CLASS);
                return;
            }
            if (!selected) {
                target.getStyleClass().remove(MESSAGE_ROW_SELECTED_STYLE_CLASS);
            }
        });
    }

    protected void markAsRead(ChatItem item) {
        if (remoteRpcState != null) {
            markRemoteChatAsRead(item);
            return;
        }
        ChatDbKey key = ChatDbKey.from(item);
        MessageDbService db = MessageDbService.getInstance();
        String ownerId = currentOwnerNodeId();
        if (!pendingLocalReadKeys.add(key.readKey())) {
            return;
        }
        for (int i = 0; i < chatItems.size(); i++) {
            if (chatItemMatches(chatItems.get(i), item)) {
                replaceChatItem(i, chatItems.get(i).withUnreadCount(0));
                break;
            }
        }
        DeviceState requestState = state;
        supplyChatDb(() -> {
            int count = db.getUnreadEligibleMessageCount(key.dbType(), key.dbKey(), ownerId);
            db.saveReadCount(key.dbType(), key.dbKey(), count, ownerId);
            return count;
        }).whenComplete((count, error) -> Platform.runLater(() -> {
            pendingLocalReadKeys.remove(key.readKey());
            if (error != null || count == null || requestState != state
                    || !Objects.equals(ownerId, currentOwnerNodeId())) {
                return;
            }
            lastReadCounts.put(key.readKey(), count);
            for (int i = 0; i < chatItems.size(); i++) {
                if (chatItemMatches(chatItems.get(i), item)) {
                    replaceChatItem(i, chatItems.get(i).withUnreadCount(0));
                    break;
                }
            }
            if (state != null) {
                state.fireMessageListeners();
            }
        }));
    }

    private void markRemoteChatAsRead(ChatItem item) {
        RemoteRpcState rpcState = remoteRpcState;
        if (rpcState == null || item == null) {
            return;
        }
        ChatDbKey key = ChatDbKey.from(item);
        String requestKey = key.readKey();
        if (!pendingRemoteReadKeys.add(requestKey)) {
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("chatType", key.dbType());
        params.addProperty("chatKey", key.dbKey());
        rpcState.client()
                .call("chat.markRead", params, REMOTE_RPC_TIMEOUT)
                .whenComplete((result, error) -> javafx.application.Platform.runLater(() -> {
                    pendingRemoteReadKeys.remove(requestKey);
                    if (rpcState != remoteRpcState) {
                        return;
                    }
                    if (error != null) {
                        Toast.show(Toast.Type.ERROR, I18n.t("chat.remote.error", RemoteChatJson.errorMessage(error)));
                        return;
                    }
                    List<ChatItem> items = RemoteChatJson.parseChatItems(result, currentOwnerNodeId());
                    applyChatItemsPreservingSelection(items);
                    clearChatUnreadDotIfFormVisible();
                    refreshUnreadTailIndicatorLater();
                }));
    }

    private void clearChatUnreadDotIfFormVisible() {
        if (formVisible) {
            DrawerManager.setChatUnreadDot(false);
        }
    }

    protected void showNewChatDialog() {
        newChatMenu = ensureNewChatMenu();
        if (newChatMenu.isShowing()) {
            newChatMenu.hide();
            return;
        }
        newChatMenu.show(newChatBtn, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private ContextMenu ensureNewChatMenu() {
        if (newChatMenu != null) {
            return newChatMenu;
        }

        ContextMenu menu = new ContextMenu();
        MenuItem createChannel = new MenuItem(I18n.t("chat.menu.createChannel"));
        createChannel.setOnAction(event -> showCreateChannelDialog());
        menu.getItems().add(createChannel);
        return menu;
    }

    protected void showCreateChannelDialog() {
        if (protocolHandler == null) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.createChannelsMeshtasticOnly"));
            return;
        }
        CreateChannelDialog.show(state, protocolHandler, this::reloadChatList);
    }

    // Helpers.

    protected boolean isCurrentChat(String chatType, String chatKey) {
        return selectedChat != null
                && Objects.equals(currentChatType(), chatType)
                && Objects.equals(currentChatKey(), chatKey);
    }
}
