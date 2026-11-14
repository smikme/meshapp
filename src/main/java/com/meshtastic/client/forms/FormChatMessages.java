package com.meshtastic.client.forms;

import com.meshtastic.client.components.chat.MessageBubbleFactory;
import com.meshtastic.client.components.chat.ChatDbKey;
import com.meshtastic.client.components.chat.VirtualMessagePane;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.MessageChangeEvent;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.protocol.rpc.RemoteChatJson;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.ChatHistoryLoader;
import com.meshtastic.client.utils.AppPreferences;

import javafx.application.Platform;
import javafx.scene.control.Label;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
    private final ChatHistoryLoader historyLoader = new ChatHistoryLoader();
    private final Map<ChatHistoryLoader.Request, ChatHistoryLoader.Page> prefetchedPages = new LinkedHashMap<>();
    private final Map<ChatHistoryLoader.Request, PendingHistory> historyRequests = new HashMap<>();
    private long historyWindowGeneration;
    private boolean loadingInitialHistory;
    private boolean refreshAfterHistoryLoad;
    private long historyRetryAfterNanos;
    private long pendingNavigationId;
    private double pendingNavigationOffset;
    private long remoteHistoryRevision;
    private MessageDbService historySource;
    private final List<MessageChangeEvent> deferredRemoteChanges = new ArrayList<>();

    private static final class PendingHistory {
        CompletableFuture<ChatHistoryLoader.Page> future;
        boolean demanded;
        boolean forced;
    }

    protected void loadInitialMessages(boolean restoreSavedState) {
        if (selectedChat == null) return;
        beginHistoryWindow();
        if (remoteRpcState != null) {
            loadRemoteInitialMessages(restoreSavedState);
            return;
        }
        historySource = MessageDbService.getInstance();
        ChatScrollState saved = restoreSavedState ? getSavedScrollState(selectedChat) : null;
        int unread = openingChatUnreadCount;
        pendingStatusLabels.clear();
        clearLoadedMessageState();
        loadingInitialHistory = true;
        var direction = saved != null && !saved.atBottom()
                ? ChatHistoryLoader.Direction.AROUND : ChatHistoryLoader.Direction.LATEST;
        var request = historyRequest(direction, saved == null ? 0 : saved.anchorDbId());
        loadWindow(request, saved, unread);
    }

    protected boolean isHistoryLoading() { return loadingInitialHistory || !historyRequests.isEmpty(); }

    @Override
    protected void cancelHistoryLoading() { beginHistoryWindow(); }

    private void beginHistoryWindow() {
        historyWindowGeneration++;
        chatRequestGeneration++;
        historyRequests.values().forEach(pending -> pending.future.cancel(false));
        historyRequests.clear();
        prefetchedPages.clear();
        deferredRemoteChanges.clear();
        historyLoader.cancel();
        loadingInitialHistory = false;
        loadingOlderMessages = false;
        loadingNewerMessages = false;
        pendingNavigationId = 0;
        refreshAfterHistoryLoad = false;
        historyRetryAfterNanos = 0;
    }

    private ChatHistoryLoader.Request historyRequest(ChatHistoryLoader.Direction direction, long cursor) {
        return new ChatHistoryLoader.Request(new ChatHistoryLoader.Scope(boundConnectionId, currentOwnerNodeId(),
                currentChatType(), currentChatKey(), historyWindowGeneration), direction, cursor, PAGE_SIZE);
    }

    private boolean isStaleHistoryRequest(ChatHistoryLoader.Request request) {
        var scope = request.scope();
        return selectedChat == null || scope.generation() != historyWindowGeneration
                || (remoteRpcState == null && historySource != MessageDbService.getIfInitialized())
                || !Objects.equals(scope.connectionId(), boundConnectionId)
                || !Objects.equals(scope.owner(), currentOwnerNodeId())
                || !Objects.equals(scope.type(), currentChatType()) || !Objects.equals(scope.key(), currentChatKey());
    }

    // The connection manager owns the shared RPC client; this request must not close it.
    @SuppressWarnings("resource")
    protected CompletableFuture<ChatHistoryLoader.Page> fetchHistoryPage(ChatHistoryLoader.Request request) {
        if (remoteRpcState == null) return historyLoader.load(request);
        boolean before = request.direction() == ChatHistoryLoader.Direction.BEFORE;
        long revision = remoteHistoryRevision;
        return remoteRpcState.client().call("chat.messages", RemoteChatJson.chatMessagesParams(
                request.scope().type(), request.scope().key(), PAGE_SIZE,
                before ? request.cursor() : 0, before ? 0 : request.cursor()), REMOTE_RPC_TIMEOUT)
                .thenCompose(json -> historyLoader.submit(() -> {
                    List<MeshMessage> messages = RemoteChatJson.parseMessages(json);
                    return new ChatHistoryLoader.Page(messages, before && messages.size() < PAGE_SIZE,
                            !before && messages.size() < PAGE_SIZE,
                            messages.isEmpty() ? 0 : messages.getLast().getDbId(), revision, 0, 0);
                }));
    }

    private boolean stalePage(ChatHistoryLoader.Page page) {
        if (remoteRpcState != null) return page.revision() != remoteHistoryRevision;
        MessageDbService service = MessageDbService.getIfInitialized();
        return service == null || page.revision() != service.historyRevision();
    }

    private void loadWindow(ChatHistoryLoader.Request request, ChatScrollState saved, int unread) {
        loadWindow(request, saved, unread, false);
    }

    private void loadWindow(ChatHistoryLoader.Request request, ChatScrollState saved, int unread,
                            boolean retainVisibleRows) {
        PendingHistory pending = new PendingHistory();
        pending.future = fetchHistoryPage(request);
        historyRequests.put(request, pending);
        pending.future.whenComplete((page, error) -> Platform.runLater(() -> {
            if (isStaleHistoryRequest(request) || historyRequests.get(request) != pending) return;
            historyRequests.remove(request);
            if (error != null) {
                loadingInitialHistory = false;
                reportHistoryError(error);
                return;
            }
            if (stalePage(page)) { loadWindow(request, saved, unread, retainVisibleRows); return; }
            Set<Long> selection = Set.copyOf(selectedMessageDbIds);
            if (retainVisibleRows) {
                replaceLoadedHistoryWindow(page.messages());
            } else {
                clearLoadedMessageState();
                loadedMessages.addAll(page.messages());
            }
            selectedMessageDbIds.addAll(selection);
            updateMessageSelectionBar();
            loadedChatScrollCacheKey = chatScrollCacheKey(selectedChat);
            recalcLoadedBounds();
            latestKnownDbId = page.latestId();
            allHistoryLoaded = page.first();
            allNewerHistoryLoaded = page.last();
            synchronizeMessageViewport();
            loadingInitialHistory = false;
            newMessageWhileScrolled = 0;
            updateScrollDownBadge();
            if (pendingNavigationId > 0 && messageContainer.containsMessage(pendingNavigationId)) {
                messageContainer.scrollToMessage(pendingNavigationId, pendingNavigationOffset - 12);
                pendingNavigationId = 0;
            } else if (saved != null && !saved.atBottom() && messageContainer.containsMessage(saved.anchorDbId())) {
                restoreSavedScrollPosition(saved);
            } else {
                positionInitialMessages(unread);
            }
            openingChatUnreadCount = 0;
            restorePendingCountdowns();
            requestMessageViewportLayout();
            refreshUnreadTailIndicatorLater();
            if (refreshAfterHistoryLoad) {
                refreshAfterHistoryLoad = false;
                refreshCurrentChat();
            }
        }));
    }

    /** Keeps overlapping bubbles, including their image requests and action handlers. */
    private void replaceLoadedHistoryWindow(List<MeshMessage> messages) {
        Map<Long, MeshMessage> previous = new HashMap<>();
        for (MeshMessage message : loadedMessages) previous.put(message.getDbId(), message);
        loadedMessages.clear();
        for (MeshMessage message : messages) {
            MeshMessage retained = previous.get(message.getDbId());
            if (retained != null) {
                boolean changed = copyLoadedMessageMetadata(retained, message);
                if (reactionsDiffer(retained.getReactions(), message.getReactions())) {
                    retained.setReactions(message.getReactions());
                    changed = true;
                }
                if (changed) {
                    refreshRenderedMessageRow(retained);
                    var rendered = loadedRenderedMessageRows.get(retained.getDbId());
                    if (rendered != null) bubbleFactory.refreshRenderedStatus(rendered, retained);
                }
                message = retained;
            }
            loadedMessages.add(message);
        }
    }

    private void reportHistoryError(Throwable error) {
        historyRetryAfterNanos = System.nanoTime() + 1_000_000_000L;
        if (error instanceof java.util.concurrent.CancellationException) return;
        log.warn("Failed to load chat history", error);
        Toast.show(Toast.Type.ERROR, I18n.t("chat.history.loadError", RemoteChatJson.errorMessage(error)));
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

    // The connection manager owns this shared RPC client.
    @SuppressWarnings("resource")
    private void loadRemoteInitialMessages(boolean restoreSavedState) {
        RemoteRpcState rpcState = remoteRpcState;
        ChatItem requestChat = selectedChat;
        long requestGeneration = chatRequestGeneration;
        long generation = scrollOperationGeneration;
        if (rpcState == null || requestChat == null) {
            return;
        }
        pendingStatusLabels.clear();
        clearLoadedMessageState();
        messageContainer.clearMessages();
        suspendScrollStateSync();
        loadingInitialHistory = true;

        rpcState.client()
                .call("chat.messages",
                        RemoteChatJson.chatMessagesParams(
                                currentChatType(), currentChatKey(), PAGE_SIZE, 0, 0),
                        REMOTE_RPC_TIMEOUT)
                .thenCompose(result -> historyLoader.submit(() -> RemoteChatJson.parseMessages(result)))
                .whenComplete((messages, error) -> Platform.runLater(() -> {
                    try {
                        if (isStaleRemoteChatRequest(rpcState, requestChat, requestGeneration)) {
                            return;
                        }
                        loadingInitialHistory = false;
                        if (error != null) {
                            Toast.show(Toast.Type.ERROR, I18n.t("chat.remote.error", RemoteChatJson.errorMessage(error)));
                            return;
                        }
                        applyRemoteInitialMessages(requestChat, messages, restoreSavedState);
                        List<MessageChangeEvent> deferred = List.copyOf(deferredRemoteChanges);
                        deferredRemoteChanges.clear();
                        processRemoteMessageChangeEvents(deferred);
                        if (refreshAfterHistoryLoad) {
                            refreshAfterHistoryLoad = false;
                            refreshCurrentChat();
                        }
                    } finally {
                        if (isCurrentScrollOperation(generation)) {
                            resumeScrollStateSyncLater();
                        }
                    }
                }));
    }

    private void applyRemoteInitialMessages(ChatItem requestChat,
                                            List<MeshMessage> messages,
                                            boolean restoreSavedState) {
        String loadedChatKey = chatScrollCacheKey(requestChat);
        clearLoadedMessageState();
        loadedChatScrollCacheKey = loadedChatKey;
        messageContainer.clearMessages();
        loadedMessages.addAll(messages);
        synchronizeMessageViewport();

        updateLoadedBoundsAfterInitialLoad(messages);
        allHistoryLoaded = messages.size() < PAGE_SIZE;
        allNewerHistoryLoaded = true;
        loadingOlderMessages = false;
        loadingNewerMessages = false;
        newMessageWhileScrolled = 0;
        updateScrollDownBadge();
        requestMessageViewportLayout();

        restorePendingCountdowns();

        if (restoreSavedState && restoreSavedScrollPosition()) {
            openingChatUnreadCount = 0;
            refreshUnreadTailIndicatorLater();
            return;
        }
        positionInitialMessages(openingChatUnreadCount);
        openingChatUnreadCount = 0;
        refreshMessageSearchResults();
    }

    protected void jumpToLatestMessages() {
        if (selectedChat == null) {
            return;
        }
        openingChatUnreadCount = 0;
        if (remoteRpcState != null) {
            loadInitialMessages(false);
            scrollToBottom();
            return;
        }
        // Keep the current viewport during I/O. The completed latest page is
        // reconciled in one FX turn, then positioned at the new bottom.
        beginHistoryWindow();
        historySource = MessageDbService.getInstance();
        loadingInitialHistory = true;
        loadWindow(historyRequest(ChatHistoryLoader.Direction.LATEST, 0), null, 0, true);
    }

    /**
     * Loads the next page of older messages while preserving scroll position.
     */
    @Override
    protected void prefetchAdjacentHistory(double vvalue) {
        if (selectedChat == null || loadedMessages.isEmpty() || loadingInitialHistory) return;
        double viewport = messageScrollPane.getViewportBounds().getHeight();
        double extent = Math.max(0, messageContainer.getHeight() - viewport);
        double above = vvalue * extent;
        double below = (1 - vvalue) * extent;
        if (!allHistoryLoaded && above < viewport * 3) {
            requestAdjacentPage(true, above < viewport * 1.5, false);
        }
        if (!allNewerHistoryLoaded && below < viewport * 3) {
            requestAdjacentPage(false, below < viewport * 1.5, false);
        }
    }

    protected void loadOlderMessages() { requestAdjacentPage(true, true, true); }
    protected void loadNewerMessages() { requestAdjacentPage(false, true, true); }

    private void requestAdjacentPage(boolean before, boolean demanded, boolean forced) {
        if (selectedChat == null || loadedMessages.isEmpty() || loadingInitialHistory
                || (before ? allHistoryLoaded : allNewerHistoryLoaded)
                || (!forced && System.nanoTime() < historyRetryAfterNanos)) return;
        var request = historyRequest(before ? ChatHistoryLoader.Direction.BEFORE : ChatHistoryLoader.Direction.AFTER,
                before ? oldestLoadedDbId : newestLoadedDbId);
        ChatHistoryLoader.Page cached = prefetchedPages.get(request);
        if (cached != null && stalePage(cached)) { prefetchedPages.remove(request); cached = null; }
        if (cached != null) {
            if (demanded) { prefetchedPages.remove(request); applyAdjacentPage(request, cached); }
            return;
        }
        PendingHistory existing = historyRequests.get(request);
        if (existing != null) { existing.demanded |= demanded; existing.forced |= forced; return; }
        PendingHistory pending = new PendingHistory();
        pending.demanded = demanded;
        pending.forced = forced;
        pending.future = fetchHistoryPage(request);
        historyRequests.put(request, pending);
        if (before) loadingOlderMessages = true; else loadingNewerMessages = true;
        pending.future.whenComplete((page, error) -> Platform.runLater(() -> {
            if (isStaleHistoryRequest(request) || historyRequests.get(request) != pending) return;
            historyRequests.remove(request);
            if (before) loadingOlderMessages = false; else loadingNewerMessages = false;
            if (request.cursor() != (before ? oldestLoadedDbId : newestLoadedDbId)) return;
            if (error != null) { reportHistoryError(error); return; }
            if (stalePage(page)) { requestAdjacentPage(before, pending.demanded, pending.forced); return; }
            double viewport = messageScrollPane.getViewportBounds().getHeight();
            double extent = Math.max(0, messageContainer.getHeight() - viewport);
            double distance = (before ? messageScrollPane.getVvalue() : 1 - messageScrollPane.getVvalue()) * extent;
            if (pending.forced || distance < viewport * 1.5) {
                applyAdjacentPage(request, page);
            } else {
                if (prefetchedPages.size() >= 2) prefetchedPages.remove(prefetchedPages.keySet().iterator().next());
                prefetchedPages.put(request, page);
            }
        }));
    }

    private void applyAdjacentPage(ChatHistoryLoader.Request request, ChatHistoryLoader.Page page) {
        boolean before = request.direction() == ChatHistoryLoader.Direction.BEFORE;
        // Capture at application time, not when the I/O started. The user may
        // have moved hundreds of pixels while the page was being read.
        ChatScrollState anchor = captureViewportAnchor();
        Set<Long> existing = loadedMessages.stream().map(MeshMessage::getDbId)
                .collect(java.util.stream.Collectors.toSet());
        List<MeshMessage> additions = page.messages().stream().filter(message -> existing.add(message.getDbId())).toList();
        if (before) loadedMessages.addAll(0, additions); else loadedMessages.addAll(additions);
        int excess = loadedMessages.size() - MAX_LOADED_MESSAGES;
        if (excess > 0) {
            if (before) { loadedMessages.subList(loadedMessages.size() - excess, loadedMessages.size()).clear(); allNewerHistoryLoaded = false; }
            else { loadedMessages.subList(0, excess).clear(); allHistoryLoaded = false; }
        }
        recalcLoadedBounds();
        latestKnownDbId = Math.max(latestKnownDbId, page.latestId());
        if (before) allHistoryLoaded = page.first();
        else allNewerHistoryLoaded = page.last() && newestLoadedDbId >= latestKnownDbId;
        synchronizeMessageViewport();
        restoreViewportAnchorLater(anchor);
        refreshUnreadTailIndicatorLater();
        prefetchedPages.entrySet().removeIf(entry -> entry.getKey().direction() == request.direction());
    }

    /**
     * Incrementally loads messages newer than {@code newestLoadedDbId}.
     * Triggered by the message listener.
     */
    protected void refreshCurrentChat() {
        if (selectedChat == null) { return; }
        if (loadingInitialHistory) { refreshAfterHistoryLoad = true; return; }
        if (remoteRpcState != null) {
            refreshRemoteCurrentChat();
            return;
        }
        boolean wasAtLiveTail = formVisible && isAtLiveTail();
        ChatScrollState preservedScrollState = formVisible && !wasAtLiveTail ? captureViewportAnchor() : null;
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();

        // Refresh delivery statuses for sent messages, including ACK and NAK.
        MessageDbService db = MessageDbService.getInstance();
        refreshPendingDeliveryStatuses(db, chatType, chatKey, ownerNodeId);
        Set<Long> metadataChangedDbIds = syncLoadedMqttMetadata(db, chatType, chatKey, ownerNodeId);

        List<MeshMessage> newMsgs = db.loadAfter(chatType, chatKey, latestKnownDbId, currentOwnerNodeId());
        if (reloadAfterDatabaseResetIfNeeded(db, chatType, chatKey, newMsgs)) {
            return;
        }
        if (newMsgs.isEmpty()) {
            if (formVisible) {
                refreshLoadedMessageRows(metadataChangedDbIds);
            }
            return;
        }

        handleNewMessages(newMsgs, preservedScrollState, wasAtLiveTail);
        refreshMessageSearchResults();
        if (formVisible) {
            refreshLoadedMessageRows(metadataChangedDbIds);
        }
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
        remoteHistoryRevision++;
        prefetchedPages.clear();
        if (loadingInitialHistory) {
            for (MessageChangeEvent event : events) {
                if (deferredRemoteChanges.size() < MAX_PENDING_MESSAGE_CHANGE_EVENTS) deferredRemoteChanges.add(event);
                else refreshAfterHistoryLoad = true;
            }
            return;
        }
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
                case STATUS_CHANGED -> {
                    refreshMessageStatus(event);
                    yield true;
                }
                case METADATA_CHANGED -> {
                    refreshMessageMetadata(event);
                    yield true;
                }
                default -> false; // Remote live updates support incoming messages, status, and metadata.
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
            if (reactionsDiffer(loaded.getReactions(), message.getReactions())) {
                loaded.setReactions(message.getReactions());
            }
            refreshRenderedMessageRow(loaded);
            return true;
        }

        boolean wasAtLiveTail = formVisible && isAtLiveTail();
        ChatScrollState preservedScrollState = formVisible && !wasAtLiveTail ? captureViewportAnchor() : null;
        handleNewMessages(List.of(message), preservedScrollState, wasAtLiveTail);
        refreshMessageSearchResults();
        return true;
    }

    // The connection manager owns this shared RPC client.
    @SuppressWarnings("resource")
    private void refreshRemoteCurrentChat() {
        RemoteRpcState rpcState = remoteRpcState;
        ChatItem requestChat = selectedChat;
        long requestGeneration = chatRequestGeneration;
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
                    if (isStaleRemoteChatRequest(rpcState, requestChat, requestGeneration)) {
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
                    refreshMessageSearchResults();
                    if (formVisible) {
                        refreshLoadedMessageRows(false);
                    }
                }));
    }

    private boolean isStaleRemoteChatRequest(RemoteRpcState rpcState, ChatItem requestChat, long requestGeneration) {
        return requestGeneration != chatRequestGeneration
                || rpcState != remoteRpcState
                || requestChat == null
                || selectedChat == null
                || !chatItemMatches(selectedChat, requestChat);
    }

    private boolean applyMessageChangeEvent(MessageChangeEvent event) {
        return switch (event.kind()) {
            case NEW_MESSAGE -> {
                refreshCurrentChat();
                refreshMessageMetadata(event);
                yield true;
            }
            case REACTION_CHANGED -> refreshMessageReactions(event.targetPacketId());
            case STATUS_CHANGED -> {
                refreshMessageStatus(event);
                yield true;
            }
            case METADATA_CHANGED -> {
                refreshMessageMetadata(event);
                yield true;
            }
            case DELETE -> {
                refreshCurrentChat();
                yield true;
            }
            case UNKNOWN -> false;
        };
    }

    private boolean refreshMessageReactions(int targetPacketId) {
        if (targetPacketId == 0) {
            return false;
        }
        MeshMessage message = findLoadedMessageByPacketId(targetPacketId);
        if (message == null) {
            return true;
        }

        attachReactions(List.of(message));
        MessageBubbleFactory.RenderedMessageRow rendered = loadedRenderedMessageRows.get(message.getDbId());
        if (rendered == null) {
            return true;
        }
        bubbleFactory.refreshRenderedReactions(rendered, message);
        requestMessageViewportLayoutLater();
        return true;
    }

    private void refreshMessageStatus(MessageChangeEvent event) {
        MeshMessage message = findLoadedMessage(event);
        if (message == null) {
            return;
        }

        MeshMessage updated = event.message();
        if (updated == null && event.packetId() != 0) {
            updated = MessageDbService.getInstance()
                    .findByPacketId(event.packetId(), currentChatType(), currentChatKey(), currentOwnerNodeId());
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
    }

    private void refreshMessageMetadata(MessageChangeEvent event) {
        MeshMessage message = findLoadedMessage(event);
        if (message == null) {
            return;
        }

        MeshMessage updated = event.message();
        if (updated == null && event.packetId() != 0) {
            updated = MessageDbService.getInstance()
                    .findByPacketId(event.packetId(), currentChatType(), currentChatKey(), currentOwnerNodeId());
        }
        if (updated != null) {
            copyLoadedMessageMetadata(message, updated);
            if (reactionsDiffer(message.getReactions(), updated.getReactions())) {
                message.setReactions(updated.getReactions());
            }
        }

        MessageBubbleFactory.RenderedMessageRow rendered = loadedRenderedMessageRows.get(message.getDbId());
        if (rendered != null) {
            bubbleFactory.refreshRenderedMetadata(rendered, message);
            bubbleFactory.refreshRenderedReactions(rendered, message);
            requestMessageViewportLayoutLater();
        }
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

    private void refreshPendingDeliveryStatuses(MessageDbService db,
                                                String chatType,
                                                String chatKey,
                                                String ownerNodeId) {
        // Status belongs to the message model even while its visual cell is recycled.
        for (MeshMessage message : loadedMessages) {
            if (!message.isOutgoing() || message.getPacketId() == 0 || pendingStatusLabels.containsKey(message.getPacketId())) continue;
            if (message.getStatus() == MeshMessage.DeliveryStatus.SENDING || shouldKeepTrackingRecipientAck(message.getPacketId(), message)) {
                MeshMessage updated = db.findByPacketId(message.getPacketId(), chatType, chatKey, ownerNodeId);
                if (updated != null) copyLoadedMessageMetadata(message, updated);
            }
        }
        pendingStatusLabels.entrySet().removeIf(entry ->
                refreshPendingDeliveryStatus(db, chatType, chatKey, ownerNodeId, entry.getKey(), entry.getValue()));
    }

    private boolean refreshPendingDeliveryStatus(MessageDbService db,
                                                 String chatType,
                                                 String chatKey,
                                                 String ownerNodeId,
                                                 int packetId,
                                                 Label statusLabel) {
        MeshMessage updated = db.findByPacketId(packetId, chatType, chatKey, ownerNodeId);
        if (updated == null || updated.getStatus() == null
                || updated.getStatus() == MeshMessage.DeliveryStatus.SENDING) {
            return false;
        }

        MeshMessage loaded = syncLoadedMessageMetadata(updated);
        bubbleFactory.refreshStatusLabel(statusLabel, loaded != null ? loaded : updated);
        return !shouldKeepTrackingRecipientAck(packetId, updated);
    }

    private boolean shouldKeepTrackingRecipientAck(int packetId, MeshMessage updated) {
        return state != null
                && updated != null
                && updated.isDirectMessage()
                && updated.getStatus() == MeshMessage.DeliveryStatus.DELIVERED
                && state.getMessageStore().getPendingAcks().containsKey(packetId);
    }

    private boolean reloadAfterDatabaseResetIfNeeded(MessageDbService db,
                                                     String chatType,
                                                     String chatKey,
                                                     List<MeshMessage> newMessages) {
        if (!newMessages.isEmpty() || !shouldReloadChatAfterDatabaseReset(db, chatType, chatKey)) {
            return false;
        }

        loadInitialMessages(false);
        refreshLoadedMessageRows();
        return true;
    }

    private void handleNewMessages(List<MeshMessage> newMessages,
                                   ChatScrollState preservedScrollState,
                                   boolean wasAtLiveTail) {
        latestKnownDbId = newMessages.getLast().getDbId();
        if (!allNewerHistoryLoaded) {
            refreshUnreadTailIndicatorLater();
            return;
        }

        int excess = loadedMessages.size() + newMessages.size() - MAX_LOADED_MESSAGES;
        if (!wasAtLiveTail && preservedScrollState != null && excess > 0
                && loadedMessages.stream().limit(excess).anyMatch(message -> message.getDbId() == preservedScrollState.anchorDbId())) {
            // Keep the reader's anchor. The live tail remains reachable through
            // forward paging and the existing jump-to-latest button.
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

    protected boolean shouldReloadChatAfterDatabaseReset(MessageDbService db, String chatType, String chatKey) {
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

    protected Set<Long> syncLoadedMqttMetadata(MessageDbService db,
                                        String chatType,
                                        String chatKey,
                                        String ownerNodeId) {
        if (db == null || chatType == null || chatKey == null || ownerNodeId == null) {
            return Set.of();
        }
        Set<Long> changedDbIds = new HashSet<>();
        for (MeshMessage loaded : loadedMessages) {
            if (loaded.getPacketId() == 0 || !loaded.isViaMqtt()) {
                continue;
            }
            MeshMessage updated = db.findByPacketId(loaded.getPacketId(), chatType, chatKey, ownerNodeId);
            if (updated != null) {
                if (copyLoadedMessageMetadata(loaded, updated)) {
                    changedDbIds.add(loaded.getDbId());
                }
            }
        }
        return changedDbIds;
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
            loaded.setReplyContextResolved(updated.isReplyContextResolved());
            loaded.setReplyToOutgoing(updated.isReplyToOutgoing());
            changed = true;
        }
        if (!Objects.equals(loaded.getReplyText(), updated.getReplyText())) {
            loaded.setReplyText(updated.getReplyText());
            changed = true;
        }
        if ((updated.isReplyContextResolved() || updated.isReplyToOutgoing())
                && loaded.isReplyToOutgoing() != updated.isReplyToOutgoing()) {
            loaded.setReplyToOutgoing(updated.isReplyToOutgoing());
            changed = true;
        }
        if (updated.isReplyContextResolved()) loaded.setReplyContextResolved(true);
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

    /**
     * Reflects a locally sent message in the open chat immediately.
     * It still goes through the database-backed path used by the ordinary
     * message listener, keeping rendering and delivery statuses consistent.
     */
    protected void refreshCurrentChatAfterLocalSend() {
        reloadChatList();
        if (!loadingInitialHistory && allNewerHistoryLoaded && !isLoadedMessageWindowStale(selectedChat)) {
            refreshCurrentChat();
            if (allNewerHistoryLoaded) {
                scrollAfterLiveTailAppend(List.of());
                return;
            }
        }
        jumpToLatestMessages();
    }

    protected void refreshCurrentChatAfterLocalReaction() {
        refreshLoadedMessageRows();
    }

    protected void clearLoadedMessageState() {
        selectedMessageDbIds.clear();
        updateMessageSelectionBar();
        messageContainer.clearMessages();
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

    @Override
    protected void synchronizeMessageViewport() {
        messageContainer.setMessages(loadedMessages);
    }

    protected void appendLoadedMessageRow(MeshMessage msg) {
        loadedMessages.add(msg);
        synchronizeMessageViewport();
    }

    protected void appendNewerMessages(List<MeshMessage> newer) {
        attachReactions(newer);
        Set<Long> existing = loadedMessages.stream().map(MeshMessage::getDbId).collect(java.util.stream.Collectors.toSet());
        newer.stream().filter(message -> message.getDbId() <= 0 || existing.add(message.getDbId())).forEach(loadedMessages::add);
        recalcLoadedBounds();
        synchronizeMessageViewport();
    }

    protected void trimLoadedWindowFromTopIfNeeded() {
        int excess = loadedMessages.size() - MAX_LOADED_MESSAGES;
        if (excess <= 0) {
            return;
        }

        loadedMessages.subList(0, excess).clear();
        // Selection belongs to message ids, never to recycled visual cells.
        synchronizeMessageViewport();

        allHistoryLoaded = false;
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
        MessageDbService db = MessageDbService.getInstance();
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();
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
            if (reactionsDiffer(message.getReactions(), nextReactions)) {
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
        messageContainer.invalidateRows();
        synchronizeMessageViewport();
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

    private static boolean reactionsDiffer(List<MessageReaction> current, List<MessageReaction> next) {
        List<MessageReaction> left = current == null ? List.of() : current;
        List<MessageReaction> right = next == null ? List.of() : next;
        if (left.size() != right.size()) {
            return true;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!sameReaction(left.get(i), right.get(i))) {
                return true;
            }
        }
        return false;
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

    /**
     * After switching from a short direct chat to a channel, ScrollPane can keep
     * the previous viewport geometry until the next resize or pulse. Force one
     * viewport invalidation while coalescing repeated requests.
     */
    protected void requestMessageViewportLayoutLater() {
        requestMessageViewportLayout();
    }

    @Override
    protected void requestMessageViewportLayout() {
        viewportLayoutDirty.set(true);
        if (!viewportLayoutQueued.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(this::flushQueuedViewportLayout);
    }

    protected void flushQueuedViewportLayout() {
        viewportLayoutDirty.getAndSet(false);
        relayoutMessageViewport();
        Platform.runLater(() -> {
            relayoutMessageViewport();
            viewportLayoutQueued.set(false);
            if (viewportLayoutDirty.get() && viewportLayoutQueued.compareAndSet(false, true)) {
                Platform.runLater(this::flushQueuedViewportLayout);
            }
        });
    }

    protected void relayoutMessageViewport() {
        if (detailPane == null || messageArea == null || messageScrollPane == null || messageContainer == null) {
            return;
        }

        detailPane.requestLayout();
        messageArea.requestLayout();
        messageScrollPane.requestLayout();
        messageContainer.requestLayout();
    }

    protected void suspendScrollStateSync() {
        scrollStateSyncSuspendCount++;
    }

    protected void resumeScrollStateSyncLater() {
        long generation = scrollOperationGeneration;
        Platform.runLater(() -> Platform.runLater(() -> {
            if (!isCurrentScrollOperation(generation)) {
                return;
            }
            if (scrollStateSyncSuspendCount > 0) {
                scrollStateSyncSuspendCount--;
            }
            if (scrollStateSyncSuspendCount == 0) {
                refreshAfterProgrammaticScroll();
            }
        }));
    }

    private void refreshAfterProgrammaticScroll() {
        if (selectedChat == null || messageScrollPane == null || messageContainer == null) {
            return;
        }
        refreshUnreadTailIndicator();
        markCurrentChatAsReadIfViewingTailLater();
    }

    protected boolean isScrollStateSyncSuspended() {
        return scrollStateSyncSuspendCount > 0;
    }

    @Override
    protected void beginScrollOperation() {
        scrollOperationGeneration++;
        // In-flight RPCs from a previous chat must not suspend the new chat's
        // scrolling until they finish or time out.
        scrollStateSyncSuspendCount = 0;
    }

    protected boolean isCurrentScrollOperation(long generation) {
        return generation == scrollOperationGeneration;
    }

    protected boolean isScrolledToBottom() {
        if (messageContainer == null || messageScrollPane == null) {
            return true;
        }

        return isScrolledToBottomFromMetrics(
                messageContainer.getLayoutBounds().getHeight(),
                messageScrollPane.getViewportBounds().getHeight(),
                messageScrollPane.getVvalue());
    }

    static boolean isScrolledToBottomFromMetrics(double contentHeight, double viewportHeight, double vvalue) {
        if (contentHeight <= 0 || viewportHeight <= 0) {
            return true;
        }

        double maxOffset = Math.max(0, contentHeight - viewportHeight);
        if (maxOffset <= 0) {
            return true;
        }

        double currentOffset = scrollOffset(vvalue, maxOffset);
        return maxOffset - currentOffset <= BOTTOM_READ_SLOP_PX;
    }

    private static double scrollOffset(double vvalue, double maxOffset) {
        // An undefined layout size previously propagated NaN; clamp rejects a NaN bound.
        return Double.isNaN(maxOffset) ? Double.NaN : Math.clamp(vvalue * maxOffset, 0, maxOffset);
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
        if (selectedChat == null || loadedMessages.isEmpty() || isLoadedMessageWindowStale(selectedChat)) {
            return;
        }
        ChatScrollState scrollState = captureCurrentChatScrollState();
        if (scrollState != null) {
            String chatId = chatScrollStateKey(selectedChat);
            savedChatScrollStates.put(chatScrollCacheKey(selectedChat), scrollState);
            try {
                AppPreferences.saveChatScrollState(
                        currentOwnerNodeId(),
                        chatId,
                        scrollState.anchorDbId(),
                        scrollState.anchorOffset(),
                        scrollState.atBottom());
            } catch (RuntimeException e) {
                // A failed preference write must not prevent leaving the chat or closing the form.
                log.warn("Failed to persist chat scroll position; keeping it in memory", e);
            }
        }
    }

    protected ChatScrollState captureCurrentChatScrollState() {
        if (selectedChat == null || loadedMessages.isEmpty() || isLoadedMessageWindowStale(selectedChat)) {
            return null;
        }

        if (isAtLiveTail()) {
            return new ChatScrollState(latestKnownDbId > 0 ? latestKnownDbId : newestLoadedDbId, 0, true);
        }

        return captureViewportAnchor();
    }

    protected ChatScrollState captureViewportAnchor() {
        if (selectedChat == null || loadedMessages.isEmpty() || isLoadedMessageWindowStale(selectedChat)) return null;
        var anchor = messageContainer.captureAnchor();
        return anchor == null ? null : new ChatScrollState(anchor.dbId(), anchor.offset(), anchor.bottom() && isAtLiveTail());
    }

    protected boolean isLoadedMessageWindowStale(ChatItem chat) {
        return chat == null || !Objects.equals(loadedChatScrollCacheKey, chatScrollCacheKey(chat));
    }

    protected void restoreViewportAnchorLater(ChatScrollState anchor) {
        if (anchor != null) messageContainer.restoreAnchor(
                new VirtualMessagePane.Anchor(anchor.anchorDbId(), anchor.anchorOffset(), anchor.atBottom()));
    }

    protected boolean restoreSavedScrollPosition() {
        ChatScrollState savedState = getSavedScrollState(selectedChat);
        if (savedState == null || savedState.atBottom()) {
            return false;
        }
        ensureMessageLoaded(savedState.anchorDbId());
        if (!messageContainer.containsMessage(savedState.anchorDbId())) {
            return false;
        }
        restoreSavedScrollPosition(savedState);
        return true;
    }

    protected void ensureMessageLoaded(long dbId) {
        if (remoteRpcState != null || selectedChat == null || dbId <= 0 || messageContainer.containsMessage(dbId)) return;
        if (loadingInitialHistory && pendingNavigationId == dbId) return;
        beginHistoryWindow();
        loadingInitialHistory = true;
        historySource = MessageDbService.getInstance();
        pendingNavigationId = dbId;
        loadWindow(historyRequest(ChatHistoryLoader.Direction.AROUND, dbId), null, 0);
    }

    protected int getUnreadCount(ChatItem item) {
        if (item == null) {
            return 0;
        }
        if (remoteRpcState != null) {
            return item.getUnreadCount();
        }

        ChatDbKey key = ChatDbKey.from(item);
        int totalCount = MessageDbService.getInstance().getUnreadEligibleMessageCount(
                key.dbType(), key.dbKey(), currentOwnerNodeId());
        int lastRead = lastReadCounts.getOrDefault(key.readKey(), 0);
        return Math.max(0, totalCount - lastRead);
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
        restoreViewportAnchorLater(savedState);
    }

    protected void refreshUnreadTailIndicatorLater() {
        Platform.runLater(() -> {
            refreshUnreadTailIndicator();
            Platform.runLater(this::refreshUnreadTailIndicator);
        });
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

        double contentHeight = messageContainer.getLayoutBounds().getHeight();
        double viewportHeight = messageScrollPane.getViewportBounds().getHeight();
        double maxOffset = Math.max(0, contentHeight - viewportHeight);
        double currentOffset = scrollOffset(messageScrollPane.getVvalue(), maxOffset);
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
            double rowTop = messageContainer.messageTop(message.getDbId());
            if (Double.isFinite(rowTop) && rowTop >= viewportBottom) {
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

    protected void scrollToBottom() {
        messageContainer.scrollToBottom();
    }

    protected void scrollToMessage(int messageIndex) {
        if (messageIndex < 0 || messageIndex >= loadedMessages.size()) {
            return;
        }
        scrollToMessage(loadedMessages.get(messageIndex).getDbId(), 0);
    }

    protected void scrollToMessage(long dbId, double anchorOffset) {
        if (!messageContainer.containsMessage(dbId) && remoteRpcState == null) {
            ensureMessageLoaded(dbId);
            pendingNavigationId = dbId;
            pendingNavigationOffset = anchorOffset;
            return;
        }
        messageContainer.scrollToMessage(dbId, anchorOffset - 12);
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

    private void publishSavedSystemMessage(String chatType, String chatKey, MeshMessage systemMessage) {
        if (isCurrentChat(chatType, chatKey)) {
            appendSystemMessageToCurrentChat(systemMessage);
        }
        reloadChatList();
    }

    private void appendSystemMessageToCurrentChat(MeshMessage systemMessage) {
        latestKnownDbId = Math.max(latestKnownDbId, systemMessage.getDbId());
        if (!allNewerHistoryLoaded) {
            refreshUnreadTailIndicatorLater();
            return;
        }

        appendLoadedMessageRow(systemMessage);
        trimLoadedWindowFromTopIfNeeded();
        allNewerHistoryLoaded = true;
        refreshMessageSearchResults();
        requestMessageViewportLayout();
        scrollToBottom();
        markAsRead(selectedChat);
    }
}
