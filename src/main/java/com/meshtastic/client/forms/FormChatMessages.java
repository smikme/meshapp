package com.meshtastic.client.forms;

import com.meshtastic.client.components.chat.MessageBubbleFactory;
import com.meshtastic.client.components.chat.ChatDbKey;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.MessageChangeEvent;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.utils.AppPreferences;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
    private static final int VIEWPORT_ANCHOR_RESTORE_PULSES = 6;

    /**
     * Loads the latest {@code PAGE_SIZE} messages from the database.
     * When several unread messages are pending, the viewport starts at the top
     * of the unread range instead of the absolute bottom.
     */
    protected void loadInitialMessages(boolean restoreSavedState) {
        if (selectedChat == null) { return; }
        pendingStatusLabels.clear();
        suspendScrollStateSync();

        try {
            MessageDbService db = MessageDbService.getInstance();
            String chatType = currentChatType();
            String chatKey = currentChatKey();
            String ownerNodeId = currentOwnerNodeId();

            List<MeshMessage> msgs = db.loadLast(chatType, chatKey, PAGE_SIZE, ownerNodeId);
            attachReactions(msgs);

            String loadedChatKey = chatScrollCacheKey(selectedChat);
            clearLoadedMessageState();
            loadedChatScrollCacheKey = loadedChatKey;
            messageContainer.getChildren().clear();
            for (MeshMessage msg : msgs) {
                appendLoadedMessageRow(msg);
            }

            updateLoadedBoundsAfterInitialLoad(msgs);
            allHistoryLoaded = msgs.size() < PAGE_SIZE;
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
        } finally {
            resumeScrollStateSyncLater();
        }
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

    protected void jumpToLatestMessages() {
        if (selectedChat == null) {
            return;
        }
        openingChatUnreadCount = 0;
        loadInitialMessages(false);
        restorePendingCountdowns();
        scrollToBottom();
    }

    /**
     * Loads the next page of older messages while preserving scroll position.
     */
    protected void loadOlderMessages() {
        if (allHistoryLoaded || loadingOlderMessages || selectedChat == null) { return; }
        loadingOlderMessages = true;
        ChatScrollState viewportAnchor = captureViewportAnchor();
        suspendScrollStateSync();

        try {
            MessageDbService db = MessageDbService.getInstance();
            String chatType = currentChatType();
            String chatKey = currentChatKey();

            List<MeshMessage> older = db.loadBefore(chatType, chatKey, oldestLoadedDbId, PAGE_SIZE, currentOwnerNodeId());

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
            Platform.runLater(() -> {
                loadingOlderMessages = false;
                refreshUnreadTailIndicator();
            });
            resumeScrollStateSyncLater();
        }
    }

    protected void loadNewerMessages() {
        if (allNewerHistoryLoaded || loadingNewerMessages || selectedChat == null) { return; }
        loadingNewerMessages = true;
        ChatScrollState viewportAnchor = captureViewportAnchor();
        suspendScrollStateSync();

        try {
            MessageDbService db = MessageDbService.getInstance();
            String chatType = currentChatType();
            String chatKey = currentChatKey();

            List<MeshMessage> newer = db.loadAfter(
                    chatType,
                    chatKey,
                    newestLoadedDbId,
                    PAGE_SIZE,
                    currentOwnerNodeId());

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
            Platform.runLater(() -> {
                loadingNewerMessages = false;
                refreshUnreadTailIndicator();
            });
            resumeScrollStateSyncLater();
        }
    }

    /**
     * Incrementally loads messages newer than {@code newestLoadedDbId}.
     * Triggered by the message listener.
     */
    protected void refreshCurrentChat() {
        if (selectedChat == null) { return; }
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
        refreshMessageSearchResults(false);
        if (formVisible) {
            refreshLoadedMessageRows(metadataChangedDbIds);
        }
    }

    protected void processMessageChangeEvents(List<MessageChangeEvent> events) {
        if (selectedChat == null || events == null || events.isEmpty()) {
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

    private boolean isMessageChangeForCurrentChat(MessageChangeEvent event) {
        return event.hasChatScope()
                && Objects.equals(event.ownerNodeId(), currentOwnerNodeId())
                && Objects.equals(event.chatType(), currentChatType())
                && Objects.equals(event.chatKey(), currentChatKey());
    }

    private boolean applyMessageChangeEvent(MessageChangeEvent event) {
        return switch (event.kind()) {
            case NEW_MESSAGE -> {
                refreshCurrentChat();
                refreshMessageMetadata(event);
                yield true;
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

    private boolean refreshMessageStatus(MessageChangeEvent event) {
        MeshMessage message = findLoadedMessage(event);
        if (message == null) {
            return true;
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
        return true;
    }

    private boolean refreshMessageMetadata(MessageChangeEvent event) {
        MeshMessage message = findLoadedMessage(event);
        if (message == null) {
            return true;
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
            bubbleFactory.refreshRenderedMetadata(rendered, message);
            requestMessageViewportLayoutLater();
        }
        return true;
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
        if (loaded == null || updated == null || loaded.getPacketId() != updated.getPacketId()) {
            return false;
        }

        boolean changed = false;
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

    /**
     * Reflects a locally sent message in the open chat immediately.
     * It still goes through the database-backed path used by the ordinary
     * message listener, keeping rendering and delivery statuses consistent.
     */
    protected void refreshCurrentChatAfterLocalSend() {
        reloadChatList();
        jumpToLatestMessages();
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
        messageContainer.getChildren().add(rendered.row());
    }

    protected void prependLoadedMessageRow(MeshMessage msg) {
        loadedMessages.add(0, msg);
        MessageBubbleFactory.RenderedMessageRow rendered = bubbleFactory.buildRendered(msg);
        applyMessageSearchHighlight(rendered.row(), msg.getDbId());
        loadedRenderedMessageRows.put(msg.getDbId(), rendered);
        loadedMessageRows.put(msg.getDbId(), rendered.row());
        messageContainer.getChildren().addFirst(rendered.row());
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

        boolean selectionChanged = false;
        for (int i = 0; i < excess; i++) {
            MeshMessage removed = trimFromTop ? loadedMessages.removeFirst() : loadedMessages.removeLast();
            HBox row = loadedMessageRows.remove(removed.getDbId());
            loadedRenderedMessageRows.remove(removed.getDbId());
            selectionChanged |= selectedMessageDbIds.remove(removed.getDbId());
            if (row != null) {
                messageContainer.getChildren().remove(row);
            }
        }
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
        if (messages == null || messages.isEmpty() || selectedChat == null) {
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
        messageContainer.getChildren().clear();
        for (MeshMessage message : loadedMessages) {
            MessageBubbleFactory.RenderedMessageRow rendered = bubbleFactory.buildRendered(message);
            applyMessageSearchHighlight(rendered.row(), message.getDbId());
            loadedRenderedMessageRows.put(message.getDbId(), rendered);
            loadedMessageRows.put(message.getDbId(), rendered.row());
            messageContainer.getChildren().add(rendered.row());
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

    /**
     * After switching from a short direct chat to a channel, ScrollPane can keep
     * the previous viewport geometry until the next resize or pulse. Force one
     * viewport invalidation while coalescing repeated requests.
     */
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

        if (messageScrollPane.getScene() == null) {
            return;
        }
    }

    protected void suspendScrollStateSync() {
        scrollStateSyncSuspendCount++;
    }

    protected void resumeScrollStateSyncLater() {
        Platform.runLater(() -> Platform.runLater(() -> {
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

        messageScrollPane.applyCss();
        messageScrollPane.layout();
        messageContainer.applyCss();
        messageContainer.layout();

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

    protected boolean isLoadedMessageWindowFor(ChatItem chat) {
        return chat != null && Objects.equals(loadedChatScrollCacheKey, chatScrollCacheKey(chat));
    }

    protected void restoreViewportAnchorLater(ChatScrollState viewportAnchor) {
        if (viewportAnchor == null) {
            return;
        }
        long generation = scrollOperationGeneration;
        suspendScrollStateSync();
        restoreViewportAnchorAcrossPulses(viewportAnchor, generation, VIEWPORT_ANCHOR_RESTORE_PULSES);
    }

    private void restoreViewportAnchorAcrossPulses(ChatScrollState viewportAnchor,
                                                   long generation,
                                                   int pulsesRemaining) {
        Platform.runLater(() -> {
            try {
                if (!isCurrentScrollOperation(generation)) {
                    return;
                }
                alignMessageToViewport(viewportAnchor.anchorDbId(), viewportAnchor.anchorOffset());
                if (pulsesRemaining > 1) {
                    restoreViewportAnchorAcrossPulses(viewportAnchor, generation, pulsesRemaining - 1);
                }
            } finally {
                if (pulsesRemaining <= 1 || !isCurrentScrollOperation(generation)) {
                    resumeScrollStateSyncLater();
                }
            }
        });
    }

    protected boolean restoreSavedScrollPosition() {
        ChatScrollState savedState = getSavedScrollState(selectedChat);
        if (savedState == null || savedState.atBottom()) {
            return false;
        }
        ensureMessageLoaded(savedState.anchorDbId());
        if (!loadedMessageRows.containsKey(savedState.anchorDbId())) {
            return false;
        }
        restoreSavedScrollPosition(savedState);
        return true;
    }

    protected void ensureMessageLoaded(long dbId) {
        if (selectedChat == null || dbId <= 0 || loadedMessageRows.containsKey(dbId)) {
            return;
        }

        loadMessageWindowAround(dbId);
    }

    private void loadMessageWindowAround(long dbId) {
        MessageDbService db = MessageDbService.getInstance();
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        String ownerNodeId = currentOwnerNodeId();
        int olderLimit = PAGE_SIZE / 2;
        int newerLimit = PAGE_SIZE - olderLimit - 1;

        MeshMessage target = db.findByDbId(chatType, chatKey, dbId, ownerNodeId);
        if (target == null) {
            return;
        }

        List<MeshMessage> older = db.loadBefore(chatType, chatKey, dbId, olderLimit, ownerNodeId);
        List<MeshMessage> newer = db.loadAfter(chatType, chatKey, dbId, newerLimit, ownerNodeId);
        List<MeshMessage> window = new ArrayList<>(older.size() + 1 + newer.size());
        window.addAll(older);
        window.add(target);
        window.addAll(newer);
        attachReactions(window);

        long previousLatestKnownDbId = Math.max(latestKnownDbId, newestLoadedDbId);
        clearLoadedMessageState();
        loadedChatScrollCacheKey = chatScrollCacheKey(selectedChat);
        messageContainer.getChildren().clear();
        for (MeshMessage message : window) {
            appendLoadedMessageRow(message);
        }
        recalcLoadedBounds();
        allHistoryLoaded = older.size() < olderLimit;
        allNewerHistoryLoaded = newer.size() < newerLimit;
        if (allNewerHistoryLoaded) {
            latestKnownDbId = newestLoadedDbId;
        } else {
            latestKnownDbId = Math.max(previousLatestKnownDbId, newestLoadedDbId);
        }
    }

    protected int getUnreadCount(ChatItem item) {
        if (item == null) {
            return 0;
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
        scrollToMessage(savedState.anchorDbId(), savedState.anchorOffset());
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

        messageScrollPane.applyCss();
        messageScrollPane.layout();
        messageContainer.applyCss();
        messageContainer.layout();

        double contentHeight = messageContainer.getLayoutBounds().getHeight();
        double viewportHeight = messageScrollPane.getViewportBounds().getHeight();
        double maxOffset = Math.max(0, contentHeight - viewportHeight);
        double currentOffset = Math.max(0, Math.min(maxOffset, messageScrollPane.getVvalue() * maxOffset));
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
            HBox row = loadedMessageRows.get(message.getDbId());
            if (row != null && row.getBoundsInParent().getMinY() >= viewportBottom) {
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

    /** Scrolls messages to the bottom after forcing layout. */
    protected void scrollToBottom() {
        long generation = scrollOperationGeneration;
        Platform.runLater(() -> {
            if (!isCurrentScrollOperation(generation)) {
                return;
            }
            messageScrollPane.applyCss();
            messageScrollPane.layout();
            messageScrollPane.setVvalue(1.0);
        // Repeat on the next pulse because ScrollPane may not yet know the new content size.
            Platform.runLater(() -> {
                if (isCurrentScrollOperation(generation)) {
                    messageScrollPane.setVvalue(1.0);
                }
            });
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
        ChatScrollState viewportAnchor = new ChatScrollState(dbId, anchorOffset, false);
        suspendScrollStateSync();
        restoreViewportAnchorAcrossPulses(viewportAnchor, generation, VIEWPORT_ANCHOR_RESTORE_PULSES);
    }

    protected void alignMessageToViewport(long dbId, double anchorOffset) {
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
        reloadChatList();
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
