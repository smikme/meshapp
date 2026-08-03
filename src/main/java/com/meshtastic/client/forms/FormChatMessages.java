package com.meshtastic.client.forms;

import com.meshtastic.client.components.chat.MessageBubbleFactory;
import com.meshtastic.client.components.chat.ChatDbKey;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.MessageChangeEvent;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.protocol.rpc.RemoteChatJson;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.utils.AppPreferences;

import javafx.application.Platform;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the loaded message window for the selected chat.
 *
 * <p>Message history can be long, so this layer keeps a bounded window, loads
 * reactions and quoted messages, restores scroll anchors, and updates unread
 * indicators as new messages arrive.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
abstract class FormChatMessages extends FormChatUi {

    private static final Logger log = LoggerFactory.getLogger(FormChatMessages.class);
    private record LiveRefreshData(List<MeshMessage> newMessages,
                                   Map<Integer, MeshMessage> metadataByPacketId,
                                   boolean databaseRewound) {}

    private record MessageWindow(List<MeshMessage> messages,
                                 int olderCount,
                                 int newerCount) {}

    /**
     * Loads the latest {@code PAGE_SIZE} messages from the database.
     * When several unread messages are pending, the viewport starts at the top
     * of the unread range instead of the absolute bottom.
     */
    protected void loadInitialMessages(boolean restoreSavedState) {
        loadInitialMessages(restoreSavedState, () -> {});
    }

    @Override
    protected void loadInitialMessages(boolean restoreSavedState, Runnable afterLoad) {
        if (selectedChat == null) { return; }
        if (remoteRpcState != null) {
            loadRemoteInitialMessages(restoreSavedState, afterLoad);
            return;
        }
        pendingStatusLabels.clear();
        suspendScrollStateSync();
        MessageDbService db = MessageDbService.getInstance();
        ChatItem requestChat = selectedChat;
        long generation = scrollOperationGeneration;
        initialMessageLoadGeneration = generation;
        initialMessageLoadPending = true;
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();
        String loadedChatKey = chatScrollCacheKey(requestChat);

        clearLoadedMessageState();
        loadedChatScrollCacheKey = loadedChatKey;
        messageRows.clear();

        supplyChatDb(() -> {
            List<MeshMessage> messages = db.loadLast(chatType, chatKey, PAGE_SIZE, ownerNodeId);
            attachReactions(messages, chatType, chatKey, ownerNodeId);
            return messages;
        }).whenComplete((messages, error) -> Platform.runLater(() -> {
            try {
                if (error != null) {
                    log.error("Failed to load initial chat messages", error);
                    return;
                }
                if (!isCurrentScrollOperation(generation)
                        || selectedChat == null
                        || !chatItemMatches(selectedChat, requestChat)
                        || !Objects.equals(ownerNodeId, currentOwnerNodeId())) {
                    return;
                }
                applyLocalInitialMessages(messages, loadedChatKey, restoreSavedState);
                afterLoad.run();
            } finally {
                if (initialMessageLoadGeneration == generation) {
                    initialMessageLoadPending = false;
                }
                resumeScrollStateSyncLater();
            }
        }));
    }

    private void applyLocalInitialMessages(List<MeshMessage> messages,
                                           String loadedChatKey,
                                           boolean restoreSavedState) {
        clearLoadedMessageState();
        loadedChatScrollCacheKey = loadedChatKey;
        messageRows.clear();
        for (MeshMessage message : messages) {
            appendLoadedMessageRow(message);
        }

        updateLoadedBoundsAfterInitialLoad(messages);
        allHistoryLoaded = messages.size() < PAGE_SIZE;
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
    }

    private void updateLoadedBoundsAfterInitialLoad(List<MeshMessage> messages) {
        if (messages.isEmpty()) {
            oldestLoadedDbId = Long.MAX_VALUE;
            newestLoadedDbId = 0;
            latestKnownDbId = 0;
            return;
        }

        oldestLoadedDbId = messages.getFirst().getDbId();
        newestLoadedDbId = messages.getLast().getDbId();
        latestKnownDbId = newestLoadedDbId;
    }

    private void loadRemoteInitialMessages(boolean restoreSavedState, Runnable afterLoad) {
        RemoteRpcState rpcState = remoteRpcState;
        ChatItem requestChat = selectedChat;
        if (rpcState == null || requestChat == null) {
            return;
        }
        pendingStatusLabels.clear();
        suspendScrollStateSync();
        long generation = scrollOperationGeneration;
        initialMessageLoadGeneration = generation;
        initialMessageLoadPending = true;

        rpcState.client()
                .call("chat.messages",
                        RemoteChatJson.chatMessagesParams(
                                currentChatType(), currentChatKey(), PAGE_SIZE, 0, 0),
                        REMOTE_RPC_TIMEOUT)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    try {
                        if (!isCurrentScrollOperation(generation)
                                || !isRemoteChatRequestCurrent(rpcState, requestChat)) {
                            return;
                        }
                        if (error != null) {
                            Toast.show(Toast.Type.ERROR, I18n.t("chat.remote.error", RemoteChatJson.errorMessage(error)));
                            return;
                        }
                        applyRemoteInitialMessages(requestChat, RemoteChatJson.parseMessages(result), restoreSavedState);
                        afterLoad.run();
                    } finally {
                        if (initialMessageLoadGeneration == generation) {
                            initialMessageLoadPending = false;
                        }
                        resumeScrollStateSyncLater();
                    }
                }));
    }

    private void applyRemoteInitialMessages(ChatItem requestChat,
                                            List<MeshMessage> messages,
                                            boolean restoreSavedState) {
        String loadedChatKey = chatScrollCacheKey(requestChat);
        clearLoadedMessageState();
        loadedChatScrollCacheKey = loadedChatKey;
        messageRows.clear();
        for (MeshMessage msg : messages) {
            appendLoadedMessageRow(msg);
        }

        updateLoadedBoundsAfterInitialLoad(messages);
        allHistoryLoaded = messages.size() < PAGE_SIZE;
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
        refreshMessageSearchResults(false);
    }

    protected void jumpToLatestMessages() {
        if (selectedChat == null) {
            return;
        }
        scrollOperationGeneration++;
        openingChatUnreadCount = 0;
        loadInitialMessages(false, () -> {
            restorePendingCountdowns();
            scrollToBottom();
        });
    }

    /**
     * Loads the next page of older messages while preserving scroll position.
     */
    protected void loadOlderMessages() {
        if (allHistoryLoaded || loadingOlderMessages || selectedChat == null) { return; }
        if (remoteRpcState != null) {
            loadRemoteOlderMessages();
            return;
        }
        loadingOlderMessages = true;
        ChatScrollState viewportAnchor = captureViewportAnchor();
        suspendScrollStateSync();
        MessageDbService db = MessageDbService.getInstance();
        ChatItem requestChat = selectedChat;
        long generation = scrollOperationGeneration;
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();
        long beforeDbId = oldestLoadedDbId;

        supplyChatDb(() -> {
            List<MeshMessage> messages = db.loadBefore(
                    chatType, chatKey, beforeDbId, PAGE_SIZE, ownerNodeId);
            attachReactions(messages, chatType, chatKey, ownerNodeId);
            return messages;
        }).whenComplete((older, error) -> Platform.runLater(() -> {
            try {
                if (error != null) {
                    log.error("Failed to load older chat messages", error);
                    return;
                }
                if (!isCurrentScrollOperation(generation)
                        || selectedChat == null
                        || !chatItemMatches(selectedChat, requestChat)) {
                    return;
                }
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
                loadingOlderMessages = false;
                refreshUnreadTailIndicator();
                resumeScrollStateSyncLater();
            }
        }));
    }

    protected void loadNewerMessages() {
        if (allNewerHistoryLoaded || loadingNewerMessages || selectedChat == null) { return; }
        if (remoteRpcState != null) {
            loadRemoteNewerMessages();
            return;
        }
        loadingNewerMessages = true;
        ChatScrollState viewportAnchor = captureViewportAnchor();
        suspendScrollStateSync();
        MessageDbService db = MessageDbService.getInstance();
        ChatItem requestChat = selectedChat;
        long generation = scrollOperationGeneration;
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();
        long afterDbId = newestLoadedDbId;

        supplyChatDb(() -> {
            List<MeshMessage> messages = db.loadAfter(
                    chatType, chatKey, afterDbId, PAGE_SIZE, ownerNodeId);
            attachReactions(messages, chatType, chatKey, ownerNodeId);
            return messages;
        }).whenComplete((newer, error) -> Platform.runLater(() -> {
            try {
                if (error != null) {
                    log.error("Failed to load newer chat messages", error);
                    return;
                }
                if (!isCurrentScrollOperation(generation)
                        || selectedChat == null
                        || !chatItemMatches(selectedChat, requestChat)) {
                    return;
                }
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
                loadingNewerMessages = false;
                refreshUnreadTailIndicator();
                resumeScrollStateSyncLater();
            }
        }));
    }

    /**
     * Incrementally loads messages newer than {@code newestLoadedDbId}.
     * Triggered by the message listener.
     */
    protected void refreshCurrentChat() {
        if (selectedChat == null) { return; }
        if (remoteRpcState != null) {
            refreshRemoteCurrentChat();
            return;
        }
        boolean wasAtLiveTail = formVisible && isAtLiveTail();
        ChatScrollState preservedScrollState = formVisible && !wasAtLiveTail ? captureViewportAnchor() : null;
        ChatItem requestChat = selectedChat;
        long generation = scrollOperationGeneration;
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();
        MessageDbService db = MessageDbService.getInstance();
        long afterDbId = latestKnownDbId;
        List<MeshMessage> loadedSnapshot = List.copyOf(loadedMessages);
        List<Integer> metadataPacketIds = loadedSnapshot.stream()
                .filter(message -> message.isViaMqtt()
                        || pendingStatusLabels.containsKey(message.getPacketId()))
                .map(MeshMessage::getPacketId)
                .filter(packetId -> packetId != 0)
                .distinct()
                .toList();

        supplyChatDb(() -> {
            List<MeshMessage> newMessages = db.loadAfter(
                    chatType, chatKey, afterDbId, ownerNodeId);
            attachReactions(newMessages, chatType, chatKey, ownerNodeId);
            Map<Integer, MeshMessage> metadata = db.findByPacketIds(
                    metadataPacketIds, chatType, chatKey, ownerNodeId);
            List<MeshMessage> latest = newMessages.isEmpty()
                    ? db.loadLast(chatType, chatKey, 1, ownerNodeId)
                    : List.of();
            boolean rewound = newMessages.isEmpty()
                    && hasDatabaseRewind(afterDbId, latest, loadedSnapshot);
            return new LiveRefreshData(newMessages, metadata, rewound);
        }).whenComplete((data, error) -> Platform.runLater(() -> {
            if (error != null) {
                log.error("Failed to refresh current chat", error);
                return;
            }
            if (!isCurrentScrollOperation(generation)
                    || selectedChat == null
                    || !chatItemMatches(selectedChat, requestChat)) {
                return;
            }
            if (data.databaseRewound()) {
                loadInitialMessages(false);
                return;
            }
            Set<Long> metadataChangedDbIds = applyLoadedMetadata(data.metadataByPacketId());
            refreshPendingStatusLabels(data.metadataByPacketId());
            if (!data.newMessages().isEmpty()) {
                handleNewMessages(data.newMessages(), preservedScrollState, wasAtLiveTail);
                refreshMessageSearchResults(false);
            }
            if (formVisible) {
                refreshLoadedMessageRows(metadataChangedDbIds);
            }
        }));
    }

    private Set<Long> applyLoadedMetadata(Map<Integer, MeshMessage> metadataByPacketId) {
        if (metadataByPacketId == null || metadataByPacketId.isEmpty()) {
            return Set.of();
        }
        Set<Long> changedDbIds = new HashSet<>();
        for (MeshMessage loaded : loadedMessages) {
            MeshMessage updated = metadataByPacketId.get(loaded.getPacketId());
            if (updated != null && copyLoadedMessageMetadata(loaded, updated)) {
                changedDbIds.add(loaded.getDbId());
            }
        }
        return changedDbIds;
    }

    private void refreshPendingStatusLabels(Map<Integer, MeshMessage> metadataByPacketId) {
        if (metadataByPacketId == null || metadataByPacketId.isEmpty()) {
            return;
        }
        pendingStatusLabels.entrySet().removeIf(entry -> {
            MeshMessage updated = metadataByPacketId.get(entry.getKey());
            if (updated == null || updated.getStatus() == null
                    || updated.getStatus() == MeshMessage.DeliveryStatus.SENDING) {
                return false;
            }
            MeshMessage loaded = syncLoadedMessageMetadata(updated);
            bubbleFactory.refreshStatusLabel(entry.getValue(), loaded != null ? loaded : updated);
            return !shouldKeepTrackingRecipientAck(entry.getKey(), updated);
        });
    }

    protected void processMessageChangeEvents(List<MessageChangeEvent> events) {
        if (selectedChat == null || events == null || events.isEmpty()) {
            return;
        }
        if (remoteRpcState != null) {
            processRemoteMessageChangeEvents(events);
            return;
        }

        long startedNanos = System.nanoTime();
        boolean fallbackRefresh = false;
        for (MessageChangeEvent event : events) {
            if (event == null || event.kind() == MessageChangeEvent.Kind.UNKNOWN) {
                fallbackRefresh = true;
                continue;
            }
            if (!isMessageChangeForCurrentChat(event)) {
                continue;
            }
            fallbackRefresh |= !applyMessageChangeEvent(event);
        }

        if (fallbackRefresh) {
            refreshCurrentChat();
        }
        if (log.isDebugEnabled()) {
            log.debug("Applied {} chat message change events in {} ms (fallback={})",
                    events.size(), elapsedMillis(startedNanos), fallbackRefresh);
        }
    }

    private void processRemoteMessageChangeEvents(List<MessageChangeEvent> events) {
        int skippedEvents = 0;
        for (MessageChangeEvent event : events) {
            if (event == null || event.kind() == MessageChangeEvent.Kind.UNKNOWN) {
                skippedEvents++;
                continue;
            }
            if (!isRemoteMessageChangeForCurrentChat(event)) {
                continue;
            }
            boolean applied = switch (event.kind()) {
                case NEW_MESSAGE -> appendRemoteIncomingMessage(event);
                case STATUS_CHANGED -> refreshMessageStatus(event);
                case METADATA_CHANGED -> refreshMessageMetadata(event);
                case REACTION_CHANGED, DELETE, UNKNOWN -> false;
            };
            if (!applied) {
                skippedEvents++;
            }
        }
        if (skippedEvents > 0 && log.isDebugEnabled()) {
            log.debug("Skipped {} incomplete remote chat live events without reloading message history", skippedEvents);
        }
    }

    private boolean isMessageChangeForCurrentChat(MessageChangeEvent event) {
        return event.hasChatScope()
                && Objects.equals(event.ownerNodeId(), currentOwnerNodeId())
                && Objects.equals(event.chatType(), currentChatType())
                && Objects.equals(event.chatKey(), currentChatKey());
    }

    private boolean isRemoteMessageChangeForCurrentChat(MessageChangeEvent event) {
        return event != null
                && Objects.equals(event.ownerNodeId(), currentOwnerNodeId())
                && Objects.equals(event.chatType(), currentChatType())
                && Objects.equals(event.chatKey(), currentChatKey());
    }

    private boolean appendRemoteIncomingMessage(MessageChangeEvent event) {
        MeshMessage message = event.message();
        if (message == null || message.getDbId() <= 0) {
            return false;
        }

        MeshMessage loaded = findLoadedMessage(event);
        if (loaded != null) {
            copyLoadedMessageMetadata(loaded, message);
            if (!sameReactions(loaded.getReactions(), message.getReactions())) {
                loaded.setReactions(message.getReactions());
            }
            refreshRenderedMessageRow(loaded);
            return true;
        }

        boolean wasAtLiveTail = formVisible && isAtLiveTail();
        ChatScrollState preservedScrollState = formVisible && !wasAtLiveTail ? captureViewportAnchor() : null;
        handleNewMessages(List.of(message), preservedScrollState, wasAtLiveTail);
        refreshMessageSearchResults(false);
        return true;
    }

    private void loadRemoteOlderMessages() {
        RemoteRpcState rpcState = remoteRpcState;
        ChatItem requestChat = selectedChat;
        if (rpcState == null || requestChat == null) {
            return;
        }
        loadingOlderMessages = true;
        ChatScrollState viewportAnchor = captureViewportAnchor();
        suspendScrollStateSync();

        rpcState.client()
                .call("chat.messages",
                        RemoteChatJson.chatMessagesParams(
                                currentChatType(), currentChatKey(), PAGE_SIZE, oldestLoadedDbId, 0),
                        REMOTE_RPC_TIMEOUT)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    boolean current = isRemoteChatRequestCurrent(rpcState, requestChat);
                    try {
                        if (!current) {
                            return;
                        }
                        if (error != null) {
                            Toast.show(Toast.Type.ERROR, I18n.t("chat.remote.error", RemoteChatJson.errorMessage(error)));
                            return;
                        }
                        List<MeshMessage> older = RemoteChatJson.parseMessages(result);
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
                        if (current) {
                            loadingOlderMessages = false;
                            refreshUnreadTailIndicator();
                        }
                        resumeScrollStateSyncLater();
                    }
                }));
    }

    private void loadRemoteNewerMessages() {
        RemoteRpcState rpcState = remoteRpcState;
        ChatItem requestChat = selectedChat;
        if (rpcState == null || requestChat == null) {
            return;
        }
        loadingNewerMessages = true;
        ChatScrollState viewportAnchor = captureViewportAnchor();
        suspendScrollStateSync();

        rpcState.client()
                .call("chat.messages",
                        RemoteChatJson.chatMessagesParams(
                                currentChatType(), currentChatKey(), PAGE_SIZE, 0, newestLoadedDbId),
                        REMOTE_RPC_TIMEOUT)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    boolean current = isRemoteChatRequestCurrent(rpcState, requestChat);
                    try {
                        if (!current) {
                            return;
                        }
                        if (error != null) {
                            Toast.show(Toast.Type.ERROR, I18n.t("chat.remote.error", RemoteChatJson.errorMessage(error)));
                            return;
                        }
                        List<MeshMessage> newer = RemoteChatJson.parseMessages(result);
                        if (newer.isEmpty()) {
                            allNewerHistoryLoaded = newestLoadedDbId >= latestKnownDbId;
                            return;
                        }
                        appendNewerMessages(newer);
                        trimLoadedWindowFromTopIfNeeded();
                        allNewerHistoryLoaded = newer.size() < PAGE_SIZE;
                        if (allNewerHistoryLoaded) {
                            latestKnownDbId = newestLoadedDbId;
                        } else {
                            latestKnownDbId = Math.max(latestKnownDbId, newestLoadedDbId);
                        }
                        requestMessageViewportLayout();
                        restoreViewportAnchorLater(viewportAnchor);
                    } finally {
                        if (current) {
                            loadingNewerMessages = false;
                            refreshUnreadTailIndicator();
                        }
                        resumeScrollStateSyncLater();
                    }
                }));
    }

    private void refreshRemoteCurrentChat() {
        RemoteRpcState rpcState = remoteRpcState;
        ChatItem requestChat = selectedChat;
        if (rpcState == null || requestChat == null) {
            return;
        }
        boolean wasAtLiveTail = formVisible && isAtLiveTail();
        ChatScrollState preservedScrollState = formVisible && !wasAtLiveTail ? captureViewportAnchor() : null;

        rpcState.client()
                .call("chat.messages",
                        RemoteChatJson.chatMessagesParams(
                                currentChatType(), currentChatKey(), PAGE_SIZE, 0, latestKnownDbId),
                        REMOTE_RPC_TIMEOUT)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (!isRemoteChatRequestCurrent(rpcState, requestChat)) {
                        return;
                    }
                    if (error != null) {
                        Toast.show(Toast.Type.ERROR, I18n.t("chat.remote.error", RemoteChatJson.errorMessage(error)));
                        return;
                    }
                    List<MeshMessage> newMsgs = RemoteChatJson.parseMessages(result);
                    if (newMsgs.isEmpty()) {
                        allNewerHistoryLoaded = true;
                        latestKnownDbId = Math.max(latestKnownDbId, newestLoadedDbId);
                        return;
                    }
                    handleNewMessages(newMsgs, preservedScrollState, wasAtLiveTail);
                    refreshMessageSearchResults(false);
                    if (formVisible) {
                        refreshLoadedMessageRows(false);
                    }
                }));
    }

    private boolean isRemoteChatRequestCurrent(RemoteRpcState rpcState, ChatItem requestChat) {
        return rpcState == remoteRpcState
                && requestChat != null
                && selectedChat != null
                && chatItemMatches(selectedChat, requestChat);
    }

    private boolean applyMessageChangeEvent(MessageChangeEvent event) {
        return switch (event.kind()) {
            case NEW_MESSAGE -> {
                yield appendLocalIncomingMessage(event);
            }
            case REACTION_CHANGED -> refreshMessageReactions(event.targetPacketId());
            case STATUS_CHANGED -> refreshMessageStatus(event);
            case METADATA_CHANGED -> refreshMessageMetadata(event);
            case DELETE -> {
                refreshCurrentChat();
                yield true;
            }
            case UNKNOWN -> false;
        };
    }

    private boolean appendLocalIncomingMessage(MessageChangeEvent event) {
        MeshMessage message = event.message();
        if (message == null || message.getDbId() <= 0) {
            return false;
        }
        MeshMessage loaded = findLoadedMessage(event);
        if (loaded != null) {
            copyLoadedMessageMetadata(loaded, message);
            refreshRenderedMessageRow(loaded);
            return true;
        }

        boolean wasAtLiveTail = formVisible && isAtLiveTail();
        ChatScrollState preservedScrollState = formVisible && !wasAtLiveTail
                ? captureViewportAnchor()
                : null;
        handleNewMessages(List.of(message), preservedScrollState, wasAtLiveTail);
        refreshMessageSearchResults(false);
        return true;
    }

    private boolean refreshMessageReactions(int targetPacketId) {
        if (targetPacketId == 0) {
            return false;
        }
        MeshMessage message = findLoadedMessageByPacketId(targetPacketId);
        if (message == null) {
            return true;
        }

        long dbId = message.getDbId();
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();
        long generation = scrollOperationGeneration;
        supplyChatDb(() -> MessageDbService.getInstance()
                .loadReactionsByTargetPacketIds(
                        chatType, chatKey, ownerNodeId, List.of(targetPacketId))
                .getOrDefault(targetPacketId, List.of()))
                .whenComplete((reactions, error) -> Platform.runLater(() -> {
                    if (error != null || !isCurrentScrollOperation(generation)) {
                        return;
                    }
                    MeshMessage current = findLoadedMessageByDbId(dbId);
                    if (current == null) {
                        return;
                    }
                    current.setReactions(reactions);
                    MessageBubbleFactory.RenderedMessageRow rendered =
                            loadedRenderedMessageRows.get(dbId);
                    if (rendered != null) {
                        bubbleFactory.refreshRenderedReactions(rendered, current);
                        requestMessageViewportLayoutLater();
                    }
                }));
        return true;
    }

    private boolean refreshMessageStatus(MessageChangeEvent event) {
        MeshMessage message = findLoadedMessage(event);
        if (message == null) {
            return true;
        }

        MeshMessage updated = event.message();
        if (updated == null && event.packetId() != 0) {
            refreshMessageFromDatabaseAsync(event, true);
            return true;
        }
        if (updated != null) {
            copyLoadedMessageMetadata(message, updated);
        }

        MessageBubbleFactory.RenderedMessageRow rendered = loadedRenderedMessageRows.get(message.getDbId());
        if (rendered != null) {
            bubbleFactory.refreshRenderedStatus(rendered, message);
            bubbleFactory.refreshRenderedMetadata(rendered, message);
            requestMessageViewportLayoutLater();
        }
        return true;
    }

    private boolean refreshMessageMetadata(MessageChangeEvent event) {
        MeshMessage message = findLoadedMessage(event);
        if (message == null) {
            return true;
        }

        MeshMessage updated = event.message();
        if (updated == null && event.packetId() != 0) {
            refreshMessageFromDatabaseAsync(event, false);
            return true;
        }
        if (updated != null) {
            copyLoadedMessageMetadata(message, updated);
            if (!sameReactions(message.getReactions(), updated.getReactions())) {
                message.setReactions(updated.getReactions());
            }
        }

        MessageBubbleFactory.RenderedMessageRow rendered = loadedRenderedMessageRows.get(message.getDbId());
        if (rendered != null) {
            bubbleFactory.refreshRenderedMetadata(rendered, message);
            bubbleFactory.refreshRenderedReactions(rendered, message);
            requestMessageViewportLayoutLater();
        }
        return true;
    }

    private void refreshMessageFromDatabaseAsync(MessageChangeEvent event, boolean statusOnly) {
        int packetId = event.packetId();
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();
        long generation = scrollOperationGeneration;
        supplyChatDb(() -> MessageDbService.getInstance()
                .findByPacketId(packetId, chatType, chatKey, ownerNodeId))
                .whenComplete((updated, error) -> Platform.runLater(() -> {
                    if (error != null || updated == null || !isCurrentScrollOperation(generation)) {
                        return;
                    }
                    MeshMessage current = findLoadedMessageByPacketId(packetId);
                    if (current == null) {
                        return;
                    }
                    copyLoadedMessageMetadata(current, updated);
                    MessageBubbleFactory.RenderedMessageRow rendered =
                            loadedRenderedMessageRows.get(current.getDbId());
                    if (rendered == null) {
                        return;
                    }
                    bubbleFactory.refreshRenderedStatus(rendered, current);
                    bubbleFactory.refreshRenderedMetadata(rendered, current);
                    if (!statusOnly) {
                        current.setReactions(updated.getReactions());
                        bubbleFactory.refreshRenderedReactions(rendered, current);
                    }
                    requestMessageViewportLayoutLater();
                }));
    }

    private MeshMessage findLoadedMessage(MessageChangeEvent event) {
        if (event.dbId() > 0) {
            MeshMessage byDbId = findLoadedMessageByDbId(event.dbId());
            if (byDbId != null) {
                return byDbId;
            }
        }
        if (event.packetId() != 0) {
            MeshMessage byPacketId = findLoadedMessageByPacketId(event.packetId());
            if (byPacketId != null) {
                return byPacketId;
            }
        }
        MeshMessage eventMessage = event.message();
        if (eventMessage == null) {
            return null;
        }
        if (eventMessage.getDbId() > 0) {
            MeshMessage byDbId = findLoadedMessageByDbId(eventMessage.getDbId());
            if (byDbId != null) {
                return byDbId;
            }
        }
        return eventMessage.getPacketId() != 0 ? findLoadedMessageByPacketId(eventMessage.getPacketId()) : null;
    }

    private MeshMessage findLoadedMessageByDbId(long dbId) {
        for (MeshMessage message : loadedMessages) {
            if (message.getDbId() == dbId) {
                return message;
            }
        }
        return null;
    }

    private MeshMessage findLoadedMessageByPacketId(int packetId) {
        for (MeshMessage message : loadedMessages) {
            if (message.getPacketId() == packetId) {
                return message;
            }
        }
        return null;
    }

    private boolean shouldKeepTrackingRecipientAck(int packetId, MeshMessage updated) {
        return state != null
                && updated != null
                && updated.isDirectMessage()
                && updated.getStatus() == MeshMessage.DeliveryStatus.DELIVERED
                && state.getMessageStore().getPendingAcks().containsKey(packetId);
    }

    private void handleNewMessages(List<MeshMessage> newMessages,
                                   ChatScrollState preservedScrollState,
                                   boolean wasAtLiveTail) {
        latestKnownDbId = newMessages.getLast().getDbId();
        if (!allNewerHistoryLoaded) {
            allNewerHistoryLoaded = false;
            refreshUnreadTailIndicatorLater();
            return;
        }

        appendNewerMessages(newMessages);
        trimLoadedWindowFromTopIfNeeded();
        allNewerHistoryLoaded = true;
        if (!wasAtLiveTail) {
            if (formVisible) {
                requestMessageViewportLayoutLater();
            }
            restoreOrRefreshTailIndicator(preservedScrollState);
            return;
        }
        scrollAfterLiveTailAppend(newMessages);
    }

    private void scrollAfterLiveTailAppend(List<MeshMessage> newMessages) {
        newMessageWhileScrolled = 0;
        updateScrollDownBadge();
        scrollToBottom();
        if (shouldMarkNewMessagesReadImmediately(formVisible, true, newMessages)) {
            markAsRead(selectedChat);
        }
        markCurrentChatAsReadIfViewingTailLater();
    }

    private void restoreOrRefreshTailIndicator(ChatScrollState preservedScrollState) {
        if (!formVisible) {
            return;
        }
        if (preservedScrollState != null) {
            restoreViewportAnchorLater(preservedScrollState);
        }
        refreshUnreadTailIndicatorLater();
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
                && newMessages.stream().anyMatch(FormChatMessages::isUnreadEligible);
    }

    protected void markCurrentChatAsReadIfViewingTailLater() {
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

    protected MeshMessage syncLoadedMessageMetadata(MeshMessage updated) {
        if (updated == null || updated.getPacketId() == 0) {
            return null;
        }
        for (MeshMessage loaded : loadedMessages) {
            if (loaded.getPacketId() == updated.getPacketId()) {
                copyLoadedMessageMetadata(loaded, updated);
                return loaded;
            }
        }
        return null;
    }

    static boolean copyLoadedMessageMetadata(MeshMessage loaded, MeshMessage updated) {
        if (loaded == null || updated == null || !isSameLoadedMessage(loaded, updated)) {
            return false;
        }

        boolean changed = false;
        if (loaded.getPacketId() != updated.getPacketId()) {
            loaded.setPacketId(updated.getPacketId());
            changed = true;
        }
        if (loaded.getStatus() != updated.getStatus()) {
            loaded.setStatus(updated.getStatus());
            changed = true;
        }
        if (!Objects.equals(loaded.getErrorReason(), updated.getErrorReason())) {
            loaded.setErrorReason(updated.getErrorReason());
            changed = true;
        }
        if (loaded.getReplyId() != updated.getReplyId()) {
            loaded.setReplyId(updated.getReplyId());
            changed = true;
        }
        if (!Objects.equals(loaded.getReplyText(), updated.getReplyText())) {
            loaded.setReplyText(updated.getReplyText());
            changed = true;
        }
        if (loaded.isReplyToOutgoing() != updated.isReplyToOutgoing()) {
            loaded.setReplyToOutgoing(updated.isReplyToOutgoing());
            changed = true;
        }
        if (loaded.getHopStart() != updated.getHopStart()) {
            loaded.setHopStart(updated.getHopStart());
            changed = true;
        }
        if (loaded.getHopLimit() != updated.getHopLimit()) {
            loaded.setHopLimit(updated.getHopLimit());
            changed = true;
        }
        if (loaded.getRxRssi() != updated.getRxRssi()) {
            loaded.setRxRssi(updated.getRxRssi());
            changed = true;
        }
        if (Float.compare(loaded.getRxSnr(), updated.getRxSnr()) != 0) {
            loaded.setRxSnr(updated.getRxSnr());
            changed = true;
        }
        if (!Objects.equals(loaded.getSenderName(), updated.getSenderName())) {
            loaded.setSenderName(updated.getSenderName());
            changed = true;
        }
        if (loaded.isViaMqtt() != updated.isViaMqtt()) {
            loaded.setViaMqtt(updated.isViaMqtt());
            changed = true;
        }
        return changed;
    }

    private static boolean isSameLoadedMessage(MeshMessage loaded, MeshMessage updated) {
        if (loaded.getDbId() > 0 && updated.getDbId() > 0) {
            return loaded.getDbId() == updated.getDbId();
        }
        return loaded.getPacketId() != 0 && loaded.getPacketId() == updated.getPacketId();
    }

    /** Reflects a locally persisted outgoing message without waiting for a DB reload. */
    protected void refreshCurrentChatAfterLocalSend(MeshMessage sentMessage) {
        // Sending explicitly moves the user to the live tail. Invalidate older
        // pagination and saved-anchor callbacks before they can restore a stale
        // viewport after the outgoing row has been appended.
        scrollOperationGeneration++;
        reloadChatListAsync();
        if (appendLocallySentMessage(sentMessage)) {
            scrollToBottom();
            return;
        }
        jumpToLatestMessages();
    }

    protected boolean appendLocallySentMessage(MeshMessage sentMessage) {
        if (sentMessage == null || sentMessage.getDbId() <= 0 || selectedChat == null
                || initialMessageLoadPending || !isLoadedMessageWindowFor(selectedChat)
                || !allNewerHistoryLoaded) {
            return false;
        }
        MessageChangeEvent event = MessageChangeEvent.newMessage(
                currentChatType(), currentChatKey(), currentOwnerNodeId(), sentMessage);
        return appendLocalIncomingMessage(event);
    }

    protected void refreshCurrentChatAfterLocalReaction() {
        refreshLoadedMessageRows();
    }

    protected void clearLoadedMessageState() {
        selectedMessageDbIds.clear();
        updateMessageSelectionBar();
        loadedMessages.clear();
        loadedMessageRows.clear();
        loadedRenderedMessageRows.clear();
        loadedChatScrollCacheKey = null;
        oldestLoadedDbId = Long.MAX_VALUE;
        newestLoadedDbId = 0;
        latestKnownDbId = 0;
        allHistoryLoaded = false;
        allNewerHistoryLoaded = true;
        loadingOlderMessages = false;
        loadingNewerMessages = false;
    }

    protected void appendLoadedMessageRow(MeshMessage msg) {
        loadedMessages.add(msg);
        MessageBubbleFactory.RenderedMessageRow rendered = bubbleFactory.buildRendered(msg);
        applyMessageSearchHighlight(rendered.row(), msg.getDbId());
        loadedRenderedMessageRows.put(msg.getDbId(), rendered);
        loadedMessageRows.put(msg.getDbId(), rendered.row());
        messageRows.add(rendered.row());
    }

    protected void prependLoadedMessageRow(MeshMessage msg) {
        loadedMessages.add(0, msg);
        MessageBubbleFactory.RenderedMessageRow rendered = bubbleFactory.buildRendered(msg);
        applyMessageSearchHighlight(rendered.row(), msg.getDbId());
        loadedRenderedMessageRows.put(msg.getDbId(), rendered);
        loadedMessageRows.put(msg.getDbId(), rendered.row());
        messageRows.addFirst(rendered.row());
    }

    protected void prependOlderMessages(List<MeshMessage> older) {
        attachReactions(older);
        for (int i = older.size() - 1; i >= 0; i--) {
            prependLoadedMessageRow(older.get(i));
        }
        recalcLoadedBounds();
    }

    protected void appendNewerMessages(List<MeshMessage> newer) {
        attachReactions(newer);
        for (MeshMessage msg : newer) {
            appendLoadedMessageRow(msg);
        }
        recalcLoadedBounds();
    }

    protected void trimLoadedWindowFromTopIfNeeded() {
        trimLoadedWindowIfNeeded(true);
    }

    protected void trimLoadedWindowFromBottomIfNeeded() {
        trimLoadedWindowIfNeeded(false);
    }

    protected void trimLoadedWindowIfNeeded(boolean trimFromTop) {
        int excess = loadedMessages.size() - MAX_LOADED_MESSAGES;
        if (excess <= 0) {
            return;
        }

        boolean selectionChanged = IntStream.range(0, excess)
                .mapToObj(ignored -> trimFromTop ? loadedMessages.removeFirst() : loadedMessages.removeLast())
                .map(MeshMessage::getDbId)
                .map(dbId -> {
                    HBox row = loadedMessageRows.remove(dbId);
                    loadedRenderedMessageRows.remove(dbId);
                    if (row != null) {
                        messageRows.remove(row);
                    }
                    return selectedMessageDbIds.remove(dbId);
                })
                .reduce(false, Boolean::logicalOr);
        if (selectionChanged) {
            updateMessageSelectionBar();
        }

        if (trimFromTop) {
            allHistoryLoaded = false;
            recalcLoadedBounds();
            return;
        }
        allNewerHistoryLoaded = false;
        recalcLoadedBounds();
    }

    protected void recalcLoadedBounds() {
        if (loadedMessages.isEmpty()) {
            oldestLoadedDbId = Long.MAX_VALUE;
            newestLoadedDbId = 0;
            return;
        }
        oldestLoadedDbId = loadedMessages.getFirst().getDbId();
        newestLoadedDbId = loadedMessages.getLast().getDbId();
    }

    protected Set<Long> attachReactions(List<MeshMessage> messages) {
        if (remoteRpcState != null || messages == null || messages.isEmpty() || selectedChat == null) {
            return Set.of();
        }
        return attachReactions(
                messages,
                currentChatType(),
                currentChatKey(),
                currentOwnerNodeId());
    }

    private Set<Long> attachReactions(List<MeshMessage> messages,
                                      String chatType,
                                      String chatKey,
                                      String ownerNodeId) {
        if (messages == null || messages.isEmpty()) {
            return Set.of();
        }
        MessageDbService db = MessageDbService.getInstance();
        Map<Long, String> replyTextBefore = messages.stream()
                .collect(java.util.stream.Collectors.toMap(
                        MeshMessage::getDbId,
                        msg -> normalizedReplyText(msg.getReplyText()),
                        (first, ignored) -> first));
        db.hydrateReplyTexts(messages, chatType, chatKey, ownerNodeId);

        Map<Integer, List<MessageReaction>> reactionsByTarget =
                db.loadReactionsByTargetPacketIds(
                        chatType,
                        chatKey,
                        ownerNodeId,
                        messages.stream().map(MeshMessage::getPacketId).toList());
        Set<Long> changedDbIds = new HashSet<>();
        for (MeshMessage message : messages) {
            if (!Objects.equals(
                    replyTextBefore.getOrDefault(message.getDbId(), ""),
                    normalizedReplyText(message.getReplyText()))) {
                changedDbIds.add(message.getDbId());
            }
            List<MessageReaction> nextReactions = reactionsByTarget.get(message.getPacketId());
            if (!sameReactions(message.getReactions(), nextReactions)) {
                changedDbIds.add(message.getDbId());
            }
            message.setReactions(nextReactions);
        }
        return changedDbIds;
    }

    private static String normalizedReplyText(String value) {
        return value == null ? "" : value;
    }

    protected void refreshLoadedMessageRows() {
        refreshLoadedMessageRows(false);
    }

    protected void refreshLoadedMessageRows(boolean force) {
        if (loadedMessages.isEmpty() || selectedChat == null) {
            return;
        }

        if (remoteRpcState != null) {
            if (force) {
                rebuildLoadedMessageRows();
            }
            return;
        }

        if (force) {
            rebuildLoadedMessageRows();
            return;
        }

        Set<Long> changedDbIds = attachReactions(loadedMessages);
        refreshLoadedMessageRows(changedDbIds);
    }

    private void refreshLoadedMessageRows(Set<Long> changedDbIds) {
        if (changedDbIds == null || changedDbIds.isEmpty()) {
            return;
        }

        long startedNanos = System.nanoTime();
        int refreshedRows = 0;
        boolean refreshedAny = false;
        for (MeshMessage message : loadedMessages) {
            if (!changedDbIds.contains(message.getDbId())) {
                continue;
            }
            if (refreshRenderedMessageRow(message)) {
                refreshedAny = true;
                refreshedRows++;
            }
        }
        if (refreshedAny) {
            requestMessageViewportLayout();
        }
        if (log.isDebugEnabled()) {
            log.debug("Patched {} loaded chat rows in {} ms", refreshedRows, elapsedMillis(startedNanos));
        }
    }

    private void rebuildLoadedMessageRows() {
        loadedMessageRows.clear();
        loadedRenderedMessageRows.clear();
        messageRows.clear();
        for (MeshMessage message : loadedMessages) {
            MessageBubbleFactory.RenderedMessageRow rendered = bubbleFactory.buildRendered(message);
            applyMessageSearchHighlight(rendered.row(), message.getDbId());
            loadedRenderedMessageRows.put(message.getDbId(), rendered);
            loadedMessageRows.put(message.getDbId(), rendered.row());
            messageRows.add(rendered.row());
        }
        requestMessageViewportLayout();
    }

    private boolean refreshRenderedMessageRow(MeshMessage message) {
        MessageBubbleFactory.RenderedMessageRow rendered = loadedRenderedMessageRows.get(message.getDbId());
        if (rendered == null) {
            return false;
        }
        bubbleFactory.refreshRenderedMetadata(rendered, message);
        bubbleFactory.refreshRenderedReactions(rendered, message);
        return true;
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static boolean sameReactions(List<MessageReaction> current, List<MessageReaction> next) {
        List<MessageReaction> left = current == null ? List.of() : current;
        List<MessageReaction> right = next == null ? List.of() : next;
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!sameReaction(left.get(i), right.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameReaction(MessageReaction left, MessageReaction right) {
        return left.getDbId() == right.getDbId()
                && left.getPacketId() == right.getPacketId()
                && left.getTargetPacketId() == right.getTargetPacketId()
                && left.isOutgoing() == right.isOutgoing()
                && left.getTimestamp() == right.getTimestamp()
                && Objects.equals(left.getFromNodeId(), right.getFromNodeId())
                && Objects.equals(left.getEmoji(), right.getEmoji())
                && left.getStatus() == right.getStatus()
                && Objects.equals(left.getErrorReason(), right.getErrorReason())
                && Objects.equals(left.getSenderName(), right.getSenderName());
    }

    /** Coalesces list layout invalidations into one JavaFX pulse. */
    protected void requestMessageViewportLayout() {
        requestMessageViewportLayout(false);
    }

    protected void requestMessageViewportLayoutLater() {
        requestMessageViewportLayout(false);
    }

    private void requestMessageViewportLayout(boolean immediate) {
        viewportLayoutDirty.set(true);
        if (!viewportLayoutQueued.compareAndSet(false, true)) {
            return;
        }
        if (immediate) {
            relayoutMessageViewport();
        }
        Platform.runLater(this::flushQueuedViewportLayout);
    }

    protected void flushQueuedViewportLayout() {
        viewportLayoutDirty.getAndSet(false);
        relayoutMessageViewport();
        viewportLayoutQueued.set(false);
        if (viewportLayoutDirty.get() && viewportLayoutQueued.compareAndSet(false, true)) {
            Platform.runLater(this::flushQueuedViewportLayout);
        }
    }

    protected void relayoutMessageViewport() {
        if (detailPane == null || messageArea == null || messageListView == null) {
            return;
        }

        detailPane.requestLayout();
        messageArea.requestLayout();
        messageListView.requestLayout();
    }

    protected void suspendScrollStateSync() {
        scrollStateSyncSuspendCount++;
    }

    protected void resumeScrollStateSyncLater() {
        Platform.runLater(() -> {
            if (scrollStateSyncSuspendCount > 0) {
                scrollStateSyncSuspendCount--;
            }
            if (scrollStateSyncSuspendCount == 0) {
                refreshAfterProgrammaticScroll();
            }
        });
    }

    private void refreshAfterProgrammaticScroll() {
        if (selectedChat == null || messageListView == null) {
            return;
        }
        refreshUnreadTailIndicator();
        markCurrentChatAsReadIfViewingTailLater();
    }

    protected boolean isScrollStateSyncSuspended() {
        return scrollStateSyncSuspendCount > 0;
    }

    protected boolean isCurrentScrollOperation(long generation) {
        return generation == scrollOperationGeneration;
    }

    protected boolean isScrolledToBottom() {
        if (messageListView == null || messageRows.isEmpty()) {
            return true;
        }
        int lastVisible = lastVisibleMessageRowIndex();
        if (lastVisible >= 0) {
            return lastVisible >= messageRows.size() - 1;
        }
        return messageScrollBar == null
                || messageScrollBar.getMax() <= messageScrollBar.getMin()
                || messageScrollBar.getValue() >= messageScrollBar.getMax() - 0.0001;
    }

    private int firstVisibleMessageRowIndex() {
        return visibleMessageRowIndices().stream().mapToInt(Integer::intValue).min().orElse(-1);
    }

    private int lastVisibleMessageRowIndex() {
        return visibleMessageRowIndices().stream().mapToInt(Integer::intValue).max().orElse(-1);
    }

    private List<Integer> visibleMessageRowIndices() {
        if (messageListView == null || messageListView.getSkin() == null) {
            return List.of();
        }
        return messageListView.lookupAll(".list-cell").stream()
                .filter(ListCell.class::isInstance)
                .map(ListCell.class::cast)
                .filter(cell -> cell.isVisible() && !cell.isEmpty() && cell.getIndex() >= 0)
                .map(ListCell::getIndex)
                .distinct()
                .toList();
    }

    static boolean isScrolledToBottomFromMetrics(double contentHeight, double viewportHeight, double vvalue) {
        if (contentHeight <= 0 || viewportHeight <= 0) {
            return true;
        }

        double maxOffset = Math.max(0, contentHeight - viewportHeight);
        if (maxOffset <= 0) {
            return true;
        }

        double currentOffset = Math.max(0, Math.min(maxOffset, vvalue * maxOffset));
        return maxOffset - currentOffset <= BOTTOM_READ_SLOP_PX;
    }

    protected boolean isAtLiveTail() {
        return allNewerHistoryLoaded && isScrolledToBottom();
    }

    protected String chatScrollStateKey(ChatItem item) {
        return item == null ? "" : ChatDbKey.from(item).scrollStateKey();
    }

    protected String chatScrollCacheKey(ChatItem item) {
        return currentOwnerNodeId() + "|" + chatScrollStateKey(item);
    }

    protected ChatScrollState getSavedScrollState(ChatItem item) {
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

    protected void saveCurrentChatScrollState() {
        if (selectedChat == null || loadedMessages.isEmpty() || !isLoadedMessageWindowFor(selectedChat)) {
            return;
        }
        ChatScrollState scrollState = captureCurrentChatScrollState();
        if (scrollState != null) {
            String chatId = chatScrollStateKey(selectedChat);
            savedChatScrollStates.put(chatScrollCacheKey(selectedChat), scrollState);
            AppPreferences.saveChatScrollState(
                    currentOwnerNodeId(),
                    chatId,
                    scrollState.anchorDbId(),
                    scrollState.anchorOffset(),
                    scrollState.atBottom());
        }
    }

    protected ChatScrollState captureCurrentChatScrollState() {
        if (selectedChat == null || loadedMessages.isEmpty() || !isLoadedMessageWindowFor(selectedChat)) {
            return null;
        }

        if (isAtLiveTail()) {
            return new ChatScrollState(latestKnownDbId > 0 ? latestKnownDbId : newestLoadedDbId, 0, true);
        }

        return captureViewportAnchor();
    }

    protected ChatScrollState captureViewportAnchor() {
        if (selectedChat == null || loadedMessages.isEmpty() || !isLoadedMessageWindowFor(selectedChat)) {
            return null;
        }

        int firstVisible = firstVisibleMessageRowIndex();
        if (firstVisible >= 0 && firstVisible < messageRows.size()) {
            HBox visibleRow = messageRows.get(firstVisible);
            for (MeshMessage message : loadedMessages) {
                if (loadedMessageRows.get(message.getDbId()) == visibleRow) {
                    return new ChatScrollState(message.getDbId(), 0, false);
                }
            }
        }

        MeshMessage lastLoaded = loadedMessages.getLast();
        return new ChatScrollState(lastLoaded.getDbId(), 0, false);
    }

    protected boolean isLoadedMessageWindowFor(ChatItem chat) {
        return chat != null && Objects.equals(loadedChatScrollCacheKey, chatScrollCacheKey(chat));
    }

    protected void restoreViewportAnchorLater(ChatScrollState viewportAnchor) {
        if (viewportAnchor == null) {
            return;
        }
        long generation = scrollOperationGeneration;
        long commandGeneration = ++viewportScrollCommandGeneration;
        suspendScrollStateSync();
        Platform.runLater(() -> {
            try {
                if (!isCurrentScrollOperation(generation)
                        || commandGeneration != viewportScrollCommandGeneration) {
                    return;
                }
                alignMessageToViewport(viewportAnchor.anchorDbId(), viewportAnchor.anchorOffset());
            } finally {
                resumeScrollStateSyncLater();
            }
        });
    }

    protected boolean restoreSavedScrollPosition() {
        ChatScrollState savedState = getSavedScrollState(selectedChat);
        if (savedState == null || savedState.atBottom()) {
            return false;
        }
        ensureMessageLoaded(savedState.anchorDbId(), () -> restoreSavedScrollPosition(savedState));
        return true;
    }

    protected void ensureMessageLoaded(long dbId) {
        ensureMessageLoaded(dbId, () -> {});
    }

    @Override
    protected void ensureMessageLoaded(long dbId, Runnable afterLoad) {
        if (selectedChat == null || dbId <= 0) {
            return;
        }
        if (remoteRpcState != null || loadedMessageRows.containsKey(dbId)) {
            afterLoad.run();
            return;
        }
        loadMessageWindowAround(dbId, afterLoad);
    }

    private void loadMessageWindowAround(long dbId, Runnable afterLoad) {
        MessageDbService db = MessageDbService.getInstance();
        ChatItem requestChat = selectedChat;
        long generation = scrollOperationGeneration;
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();
        int olderLimit = PAGE_SIZE / 2;
        int newerLimit = PAGE_SIZE - olderLimit - 1;
        long previousLatestKnownDbId = Math.max(latestKnownDbId, newestLoadedDbId);

        supplyChatDb(() -> {
            MeshMessage target = db.findByDbId(chatType, chatKey, dbId, ownerNodeId);
            if (target == null) {
                return null;
            }
            List<MeshMessage> older = db.loadBefore(
                    chatType, chatKey, dbId, olderLimit, ownerNodeId);
            List<MeshMessage> newer = db.loadAfter(
                    chatType, chatKey, dbId, newerLimit, ownerNodeId);
            List<MeshMessage> window = new ArrayList<>(older.size() + 1 + newer.size());
            window.addAll(older);
            window.add(target);
            window.addAll(newer);
            attachReactions(window, chatType, chatKey, ownerNodeId);
            return new MessageWindow(window, older.size(), newer.size());
        }).whenComplete((window, error) -> Platform.runLater(() -> {
            if (error != null) {
                log.error("Failed to load chat window around message {}", dbId, error);
                return;
            }
            if (window == null || !isCurrentScrollOperation(generation)
                    || selectedChat == null || !chatItemMatches(selectedChat, requestChat)) {
                return;
            }
            clearLoadedMessageState();
            loadedChatScrollCacheKey = chatScrollCacheKey(selectedChat);
            messageRows.clear();
            for (MeshMessage message : window.messages()) {
                appendLoadedMessageRow(message);
            }
            recalcLoadedBounds();
            allHistoryLoaded = window.olderCount() < olderLimit;
            allNewerHistoryLoaded = window.newerCount() < newerLimit;
            latestKnownDbId = allNewerHistoryLoaded
                    ? newestLoadedDbId
                    : Math.max(previousLatestKnownDbId, newestLoadedDbId);
            afterLoad.run();
        }));
    }

    protected int getUnreadCount(ChatItem item) {
        if (item == null) {
            return 0;
        }
        return item.getUnreadCount();
    }

    protected static boolean isUnreadEligible(MeshMessage message) {
        return message != null && !message.isOutgoing();
    }

    protected int countUnreadEligibleMessagesInLoadedWindow() {
        int count = 0;
        for (MeshMessage message : loadedMessages) {
            if (isUnreadEligible(message)) {
                count++;
            }
        }
        return count;
    }

    protected int findFirstUnreadLoadedIndex(int unreadCount) {
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

    protected void positionInitialMessages(int unreadCount) {
        if (focusUnreadMessages(unreadCount)) {
            refreshUnreadTailIndicatorLater();
            return;
        }

        newMessageWhileScrolled = 0;
        updateScrollDownBadge();
        scrollToBottom();
    }

    protected boolean focusUnreadMessages(int unreadCount) {
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

    protected void restoreSavedScrollPosition(ChatScrollState savedState) {
        scrollToMessage(savedState.anchorDbId(), savedState.anchorOffset());
    }

    protected void refreshUnreadTailIndicatorLater() {
        Platform.runLater(this::refreshUnreadTailIndicator);
    }

    protected void refreshUnreadTailIndicator() {
        if (selectedChat == null) {
            newMessageWhileScrolled = 0;
            scrollDownBtn.setVisible(false);
            updateScrollDownBadge();
            return;
        }

        boolean atLiveTail = isAtLiveTail();
        scrollDownBtn.setVisible(!atLiveTail);
        if (atLiveTail) {
            newMessageWhileScrolled = 0;
            updateScrollDownBadge();
            return;
        }

        newMessageWhileScrolled = countUnreadMessagesBelowViewport();
        updateScrollDownBadge();
    }

    protected int countUnreadMessagesBelowViewport() {
        if (selectedChat == null || loadedMessages.isEmpty()) {
            return 0;
        }

        int totalUnread = getUnreadCount(selectedChat);
        if (totalUnread <= 0) {
            return 0;
        }

        int lastVisibleRow = lastVisibleMessageRowIndex();

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
            if (row != null && messageRows.indexOf(row) > lastVisibleRow) {
                unreadBelow++;
            }
        }
        int loadedUnread = Math.min(totalUnread, countUnreadEligibleMessagesInLoadedWindow());
        return unreadBelow + Math.max(0, totalUnread - loadedUnread);
    }

    protected void updateScrollDownBadge() {
        if (newMessageWhileScrolled > 0) {
            scrollDownBadge.setText(String.valueOf(newMessageWhileScrolled));
            scrollDownBadge.setVisible(true);
        } else {
            scrollDownBadge.setVisible(false);
        }
    }

    /** Scrolls the virtualized message list to its final row. */
    protected void scrollToBottom() {
        long generation = scrollOperationGeneration;
        long commandGeneration = ++viewportScrollCommandGeneration;
        suspendScrollStateSync();
        scrollToBottomAcrossPulses(generation, commandGeneration, 3);
    }

    private void scrollToBottomAcrossPulses(long generation,
                                            long commandGeneration,
                                            int remainingPulses) {
        Platform.runLater(() -> {
            if (!isCurrentScrollOperation(generation)
                    || commandGeneration != viewportScrollCommandGeneration
                    || messageRows.isEmpty()) {
                resumeScrollStateSyncLater();
                return;
            }
            messageListView.scrollTo(messageRows.size() - 1);
            messageListView.requestLayout();
            if (messageScrollBar != null) {
                messageScrollBar.setValue(messageScrollBar.getMax());
            }
            if (remainingPulses > 1) {
                scrollToBottomAcrossPulses(
                        generation, commandGeneration, remainingPulses - 1);
                return;
            }
            newMessageWhileScrolled = 0;
            updateScrollDownBadge();
            resumeScrollStateSyncLater();
        });
    }

    protected void scrollToMessage(int messageIndex) {
        if (messageIndex < 0 || messageIndex >= loadedMessages.size()) {
            return;
        }
        scrollToMessage(loadedMessages.get(messageIndex).getDbId(), 0);
    }

    protected void scrollToMessage(long dbId, double anchorOffset) {
        long generation = scrollOperationGeneration;
        long commandGeneration = ++viewportScrollCommandGeneration;
        suspendScrollStateSync();
        Platform.runLater(() -> {
            try {
                if (isCurrentScrollOperation(generation)
                        && commandGeneration == viewportScrollCommandGeneration) {
                    alignMessageToViewport(dbId, anchorOffset);
                }
            } finally {
                resumeScrollStateSyncLater();
            }
        });
    }

    protected void alignMessageToViewport(long dbId, double anchorOffset) {
        HBox target = loadedMessageRows.get(dbId);
        if (target == null) {
            return;
        }

        int index = messageRows.indexOf(target);
        if (index >= 0) {
            messageListView.scrollTo(index);
        }
    }

    /**
     * Adds a system or bot message to the requested chat.
     * The message is saved to the database and updates the open UI and read
     * counter when that chat is currently selected.
     */
    protected void addSystemMessageTo(String chatType, String chatKey, String text) {
        MeshMessage sysMsg = new MeshMessage("!00000000", "!00000000", 0, text, System.currentTimeMillis() / 1000, false);
        sysMsg.setSystemMessage(true);
        MessageDbService.getInstance().save(sysMsg, chatType, chatKey, currentOwnerNodeId());
        publishSavedSystemMessage(chatType, chatKey, sysMsg);
    }

    /**
     * Shows a temporary system message in the current UI without saving it to history.
     */
    protected void showTransientSystemMessageTo(String chatType, String chatKey, String text) {
        if (!isCurrentChat(chatType, chatKey)) {
            return;
        }
        MeshMessage sysMsg = new MeshMessage("!00000000", "!00000000", 0, text, System.currentTimeMillis() / 1000, false);
        sysMsg.setSystemMessage(true);
        appendSystemMessageToCurrentChat(sysMsg);
    }

    /**
     * Adds a traceroute result to {@code traceroute_results} and shows a temporary node in the current UI.
     */
    protected void addTracerouteResult(String chatType, String chatKey,
                                     String targetName, MeshProtos.RouteDiscovery route) {
        String text = tracerouteView.formatText(targetName, route);
        long timestamp = System.currentTimeMillis() / 1000;
        MessageDbService.getInstance().saveTracerouteResult(
                currentOwnerNodeId(),
                chatType,
                chatKey,
                "java.traceroute",
                "java:" + timestamp + ":" + UUID.randomUUID(),
                0,
                0,
                null,
                targetName,
                0,
                null,
                route != null ? route.toByteArray() : null,
                text,
                timestamp);
        if (!isCurrentChat(chatType, chatKey)) {
            return;
        }
        MeshMessage sysMsg = new MeshMessage("!00000000", "!00000000", 0, text, timestamp, false);
        sysMsg.setSystemMessage(true);
        appendSystemMessageToCurrentChat(sysMsg);
    }

    private void publishSavedSystemMessage(String chatType, String chatKey, MeshMessage systemMessage) {
        if (isCurrentChat(chatType, chatKey)) {
            appendSystemMessageToCurrentChat(systemMessage);
        }
        reloadChatListAsync();
    }

    private void appendSystemMessageToCurrentChat(MeshMessage systemMessage) {
        latestKnownDbId = Math.max(latestKnownDbId, systemMessage.getDbId());
        if (!allNewerHistoryLoaded) {
            allNewerHistoryLoaded = false;
            refreshUnreadTailIndicatorLater();
            return;
        }

        appendLoadedMessageRow(systemMessage);
        trimLoadedWindowFromTopIfNeeded();
        allNewerHistoryLoaded = true;
        refreshMessageSearchResults(false);
        requestMessageViewportLayout();
        scrollToBottom();
        markAsRead(selectedChat);
    }
}
