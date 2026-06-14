package com.meshtastic.client.forms;

import com.meshtastic.client.components.chat.ChannelPropertiesDialog;
import com.meshtastic.client.components.chat.ChatDbKey;
import com.meshtastic.client.components.chat.CreateChannelDialog;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocolRuntime;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.MessageListenerService;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.DrawerManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.UnicodeTextUtils;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
                                    String connectionId) {}

    protected void updateInputEnabled() {
        boolean canSend = state != null
                && (protocolHandler != null || meshCoreCompanionRuntime != null)
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
        String newConnectionId = activeConnection.connectionId();
        boolean connectionChanged = !Objects.equals(newConnectionId, this.boundConnectionId);
        boolean stateChanged = newState != this.state || connectionChanged;

        if (!stateChanged) {
            refreshReadCounts();
            reloadChatList();
            reopenVisibleSelectedChatIfNeeded();
            return;
        }

        if (selectedChat != null) {
            saveCurrentChatScrollState();
            rememberSelectedChatForBoundConnection();
        }
        unbindPreviousState();
        this.state = newState;
        this.protocolHandler = activeConnection.handler();
        this.meshCoreCompanionRuntime = activeConnection.meshCoreRuntime();
        this.boundConnectionId = newConnectionId;
        if (connectionChanged) {
            selectedChat = null;
        }

        bindStateDependentComponents(newState);
        refreshReadCounts();
        registerConnectedStateListeners(mgr);

        reloadChatList();
        if (stateChanged) {
            reopenSelectedChatIfPossible();
        }
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
            return new ActiveConnection(candidateState, mgr.getProtocolHandler(entry.getId()), meshRuntime, entry.getId());
        }
        return new ActiveConnection(null, null, null, null);
    }

    private void unbindPreviousState() {
        if (state != null) {
            state.removeMessageChangeListener(messageChangeListener);
        }
    }

    private void bindStateDependentComponents(DeviceState newState) {
        if (bubbleFactory != null) {
            bubbleFactory.setState(newState);
        }
        if (nameResolver != null) {
            nameResolver.setState(newState);
        }
    }

    private void refreshReadCounts() {
        lastReadCounts.clear();
        lastReadCounts.putAll(MessageDbService.getInstance().loadAllReadCounts(currentOwnerNodeId()));
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
    }

    protected void reopenSelectedChatIfPossible() {
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
            clearLoadedMessageState();
            detailPane.getChildren().clear();
            detailPane.getChildren().add(placeholderBox);
            updateInputEnabled();
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

    protected void reloadChatList() {
        if (state == null) {
            chatItems.clear();
            protocolHandler = null;
            meshCoreCompanionRuntime = null;
            return;
        }

        MessageDbService db = MessageDbService.getInstance();
        String ownerId = currentOwnerNodeId();
        List<ChatItem> items = new ArrayList<>();
        items.addAll(loadChannelChatItems(db, ownerId));
        items.addAll(loadDirectChatItems(db, ownerId));

        applyChatItemsPreservingSelection(items);
        DrawerManager.setChatUnreadDot(chatItems.stream().anyMatch(chat -> chat.getUnreadCount() > 0));
    }

    private List<ChatItem> loadChannelChatItems(MessageDbService db, String ownerId) {
        Map<String, MeshMessage> lastMessages = db.getLastMessagePerChat("channel", ownerId);
        List<ChatItem> items = new ArrayList<>();

        for (ChannelProtos.Channel channel : state.getChannels()) {
            if (channel.getRole() == ChannelProtos.Channel.Role.DISABLED) {
                continue;
            }
            ChatDbKey key = ChatDbKey.channel(channel.getIndex());
            items.add(ChatItem.fromChannel(
                    channel,
                    lastMessages.get(key.dbKey()),
                    unreadCount(db, key, ownerId),
                    isMuted(ownerId, key)));
        }
        return items;
    }

    private List<ChatItem> loadDirectChatItems(MessageDbService db, String ownerId) {
        Map<String, MeshMessage> lastMessages = db.getLastMessagePerChat("dm", ownerId);
        Set<String> dmPeers = new LinkedHashSet<>(db.getDistinctDmPeers(ownerId));
        dmPeers.addAll(state.getAllDirectMessages().keySet());

        List<ChatItem> items = new ArrayList<>();
        for (String peerNodeId : dmPeers) {
            ChatDbKey key = ChatDbKey.direct(peerNodeId);
            items.add(ChatItem.fromDirectMessage(
                    peerNodeId,
                    resolvePeerNode(peerNodeId),
                    lastMessages.get(peerNodeId),
                    unreadCount(db, key, ownerId),
                    isMuted(ownerId, key)));
        }
        return items;
    }

    private NodeData resolvePeerNode(String peerNodeId) {
        NodeData peerNode = state.getNodeByNodeId(peerNodeId);
        return peerNode != null ? peerNode : NodeCacheService.getInstance().get(peerNodeId);
    }

    private int unreadCount(MessageDbService db, ChatDbKey key, String ownerId) {
        int totalCount = db.getUnreadEligibleMessageCount(key.dbType(), key.dbKey(), ownerId);
        int lastRead = lastReadCounts.getOrDefault(key.readKey(), 0);
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
        AppPreferences.setChatMuted(currentOwnerNodeId(), ChatDbKey.from(item).preferenceId(), !item.isMuted());
        reloadChatList();
    }

    /**
     * Deletes a channel on the device or removes a direct chat locally.
     */
    protected void deleteChat(ChatItem item) {
        if (item == null) {
            return;
        }
        MessageDbService db = MessageDbService.getInstance();
        ChatDbKey key = ChatDbKey.from(item);
        String chatId = key.scrollStateKey();
        savedChatScrollStates.remove(chatScrollCacheKey(item));
        AppPreferences.removeChatScrollState(currentOwnerNodeId(), chatId);

        switch (item.getType()) {
            case CHANNEL -> deleteChannelChat(db, item, key);
            case DIRECT_MESSAGE -> deleteDirectChat(db, item, key);
        }

        if (selectedChat != null && chatItemMatches(selectedChat, item)) {
            closeChat();
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
        lastReadCounts.remove(key.readKey());
    }

    /** Confirms and deletes one message. */
    protected void confirmDeleteMessage(MeshMessage msg, HBox bubbleRow) {
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
        messageContainer.getChildren().remove(bubbleRow);
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
        boolean selected;
        if (selectedMessageDbIds.contains(dbId)) {
            selectedMessageDbIds.remove(dbId);
            selected = false;
        } else {
            selectedMessageDbIds.add(dbId);
            selected = true;
        }
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
        for (Long dbId : List.copyOf(selectedMessageDbIds)) {
            setMessageRowSelected(loadedMessageRows.get(dbId), false);
        }
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
        Set<Long> loadedDbIds = new LinkedHashSet<>();
        for (MeshMessage loaded : loadedMessages) {
            if (selectedMessageDbIds.contains(loaded.getDbId())) {
                loadedDbIds.add(loaded.getDbId());
            }
        }
        return List.copyOf(loadedDbIds);
    }

    private void deleteMessagesFromCurrentChat(Collection<Long> dbIds) {
        if (dbIds == null || dbIds.isEmpty()) {
            return;
        }

        Set<Long> targetDbIds = new LinkedHashSet<>(dbIds);
        MessageDbService db = MessageDbService.getInstance();
        for (Long dbId : targetDbIds) {
            if (dbId != null && dbId > 0) {
                db.deleteMessage(dbId);
            }
        }

        loadedMessages.removeIf(loaded -> targetDbIds.contains(loaded.getDbId()));
        for (Long dbId : targetDbIds) {
            HBox row = loadedMessageRows.remove(dbId);
            loadedRenderedMessageRows.remove(dbId);
            selectedMessageDbIds.remove(dbId);
            if (row != null) {
                messageContainer.getChildren().remove(row);
            }
        }

        recalcLoadedBounds();
        updateMessageSelectionBar();
        refreshMessageSearchResults(false);
        reloadChatList();
    }

    protected void setMessageRowSelected(HBox row, boolean selected) {
        if (row == null) { return; }
        if (selected) {
            if (!row.getStyleClass().contains(MESSAGE_ROW_SELECTED_STYLE_CLASS)) {
                row.getStyleClass().add(MESSAGE_ROW_SELECTED_STYLE_CLASS);
            }
            return;
        }
        row.getStyleClass().remove(MESSAGE_ROW_SELECTED_STYLE_CLASS);
    }

    protected void markAsRead(ChatItem item) {
        ChatDbKey key = ChatDbKey.from(item);
        MessageDbService db = MessageDbService.getInstance();
        String ownerId = currentOwnerNodeId();
        int count = db.getUnreadEligibleMessageCount(key.dbType(), key.dbKey(), ownerId);
        if (lastReadCounts.getOrDefault(key.readKey(), -1) == count) {
            return;
        }
        lastReadCounts.put(key.readKey(), count);
        db.saveReadCount(key.dbType(), key.dbKey(), count, ownerId);
        reloadChatList();
        if (state != null) {
            state.fireMessageListeners();
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
