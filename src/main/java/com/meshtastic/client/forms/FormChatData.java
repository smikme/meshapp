package com.meshtastic.client.forms;

import com.meshtastic.client.components.chat.ChannelPropertiesDialog;
import com.meshtastic.client.components.chat.ChatDbKey;
import com.meshtastic.client.components.chat.CreateChannelDialog;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.meshtastic.proto.ChannelProtos;

/**
 * Связывает интерфейс чата с выбранным подключением к радио и сохранёнными данными.
 *
 * <p>Слой отвечает за перепривязку устройства, составление списка чатов,
 * счётчики прочитанных, удаление каналов и личных чатов, состояние заглушения и
 * точки входа в модальные окна. Окно сообщений и запросы ботов остаются в
 * нижних слоях.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
abstract class FormChatData extends FormChatRequests {

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
     * Перепривязывает форму к выбранному подключённому радио и обновляет зависимое
     * состояние интерфейса. Метод намеренно линейный: найти подключение,
     * отвязать старое состояние, привязать новое, обновить данные и затем
     * по возможности снова открыть выбранный чат.
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
            state.removeMessageListener(messageListener);
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
            state.addMessageListener(messageListener);
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
                selectedChat = null;
                chatListView.getSelectionModel().clearSelection();
                return;
            }
            selectedChat = matched;
            chatListView.getSelectionModel().select(matched);
        } finally {
            suppressSelectionListener = false;
        }
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
     * Открывает панель свойств канала.
     */
    protected void showChannelProperties(ChatItem item) {
        if (item == null || item.getType() != ChatItem.ChatType.CHANNEL) {
            return;
        }
        if (state == null || protocolHandler == null) {
            if (meshCoreCompanionRuntime != null) {
                Toast.show(Toast.Type.WARNING, "Свойства канала доступны только для Meshtastic");
                return;
            }
            Toast.show(Toast.Type.WARNING, "Нет подключения к радио");
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
     * Удаляет канал (DISABLED на устройстве) или личный чат (только локально).
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

    /** Подтверждение и удаление одного сообщения */
    protected void confirmDeleteMessage(MeshMessage msg, HBox bubbleRow) {
        String preview = msg.getText();
        preview = UnicodeTextUtils.truncateWithSuffix(preview, 40, "…");
        ModalPane.showConfirm(
                "Удалить сообщение?",
                preview != null ? preview : "",
                confirmed -> {
                    if (!confirmed) { return; }
                    deleteMessageFromCurrentChat(msg, bubbleRow);
                }
        );
    }

    private void deleteMessageFromCurrentChat(MeshMessage msg, HBox bubbleRow) {
        MessageDbService.getInstance().deleteMessage(msg.getDbId());
        loadedMessages.removeIf(loaded -> loaded.getDbId() == msg.getDbId());
        loadedMessageRows.remove(msg.getDbId());
        recalcLoadedBounds();
        messageContainer.getChildren().remove(bubbleRow);
        reloadChatList();
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
        MenuItem createChannel = new MenuItem("Создать канал");
        createChannel.setOnAction(event -> showCreateChannelDialog());
        menu.getItems().add(createChannel);
        return menu;
    }

    protected void showCreateChannelDialog() {
        if (protocolHandler == null) {
            Toast.show(Toast.Type.WARNING, "Создание каналов доступно только для Meshtastic");
            return;
        }
        CreateChannelDialog.show(state, protocolHandler, this::reloadChatList);
    }

    // ==================== Вспомогательные методы ====================

    protected boolean isCurrentChat(String chatType, String chatKey) {
        return selectedChat != null
                && Objects.equals(currentChatType(), chatType)
                && Objects.equals(currentChatKey(), chatKey);
    }
}
