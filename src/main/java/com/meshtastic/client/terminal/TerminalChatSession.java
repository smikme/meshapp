package com.meshtastic.client.terminal;

import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.service.MessageDbService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.meshtastic.client.terminal.TerminalLayout.clamp;

/**
 * Loaded terminal chat history, selection, paging, and viewport restoration.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class TerminalChatSession {

    private final int pageSize;
    private final int maxLoadedMessages;
    private final Map<String, ChatViewport> savedViewports = new HashMap<>();
    private final List<MeshMessage> loadedMessages = new ArrayList<>();

    private TerminalChat loadedChat;
    private String loadedOwnerNodeId = "";
    private int selectedMessageIndex = -1;
    private int messageTopIndex;
    private boolean allOlderMessagesLoaded = true;
    private boolean allNewerMessagesLoaded = true;
    private int lastVisibleMessageCount = 6;

    TerminalChatSession(int pageSize, int maxLoadedMessages) {
        this.pageSize = pageSize;
        this.maxLoadedMessages = maxLoadedMessages;
    }

    TerminalChat loadedChat() {
        return loadedChat;
    }

    String loadedOwnerNodeId() {
        return loadedOwnerNodeId;
    }

    List<MeshMessage> loadedMessages() {
        return loadedMessages;
    }

    int selectedMessageIndex() {
        return selectedMessageIndex;
    }

    int messageTopIndex() {
        return messageTopIndex;
    }

    int lastVisibleMessageCount() {
        return lastVisibleMessageCount;
    }

    void applyRenderResult(TerminalRenderResult result) {
        selectedMessageIndex = result.selectedMessageIndex();
        messageTopIndex = result.messageTopIndex();
        lastVisibleMessageCount = result.lastVisibleMessageCount();
    }

    void reset() {
        loadedChat = null;
        loadedOwnerNodeId = "";
        loadedMessages.clear();
        selectedMessageIndex = -1;
        messageTopIndex = 0;
        allOlderMessagesLoaded = true;
        allNewerMessagesLoaded = true;
    }

    boolean shouldLoad(TerminalChat chat, String ownerNodeId) {
        return chat != null && (!chat.sameChat(loadedChat) || !ownerNodeId.equals(loadedOwnerNodeId));
    }

    void loadInitialHistory(TerminalChat chat, String ownerNodeId) {
        if (chat == null) {
            return;
        }
        loadedChat = chat;
        loadedOwnerNodeId = ownerNodeId;
        loadedMessages.clear();
        loadedMessages.addAll(MessageDbService.getInstance()
                .loadLast(chat.dbType(), chat.dbKey(), pageSize, loadedOwnerNodeId));
        hydrateLoadedReplyTexts();
        selectedMessageIndex = loadedMessages.isEmpty() ? -1 : loadedMessages.size() - 1;
        messageTopIndex = 0;
        allOlderMessagesLoaded = loadedMessages.size() < pageSize;
        allNewerMessagesLoaded = true;
        restoreViewport(chat);
    }

    void saveCurrentViewport() {
        if (loadedChat == null || loadedMessages.isEmpty() || selectedMessageIndex < 0
                || selectedMessageIndex >= loadedMessages.size()) {
            return;
        }
        savedViewports.put(loadedOwnerNodeId + "|" + loadedChat.key(),
                new ChatViewport(loadedMessages.get(selectedMessageIndex).getDbId(), messageTopIndex));
    }

    void syncLatestMessages() {
        if (loadedChat == null) {
            return;
        }
        if (loadedMessages.isEmpty()) {
            List<MeshMessage> latest = MessageDbService.getInstance()
                    .loadLast(loadedChat.dbType(), loadedChat.dbKey(), pageSize, loadedOwnerNodeId);
            if (!latest.isEmpty()) {
                loadedMessages.addAll(latest);
                hydrateLoadedReplyTexts();
                selectedMessageIndex = loadedMessages.size() - 1;
                messageTopIndex = 0;
                allOlderMessagesLoaded = loadedMessages.size() < pageSize;
                allNewerMessagesLoaded = true;
            }
            return;
        }
        long newest = loadedMessages.get(loadedMessages.size() - 1).getDbId();
        List<MeshMessage> newer = MessageDbService.getInstance()
                .loadAfter(loadedChat.dbType(), loadedChat.dbKey(), newest, pageSize, loadedOwnerNodeId);
        if (newer.isEmpty()) {
            allNewerMessagesLoaded = true;
            return;
        }
        boolean atTail = selectedMessageIndex >= loadedMessages.size() - 1;
        loadedMessages.addAll(newer);
        hydrateLoadedReplyTexts();
        trimLoadedMessagesFromTopIfNeeded();
        if (atTail) {
            selectedMessageIndex = loadedMessages.size() - 1;
        }
        allNewerMessagesLoaded = newer.size() < pageSize;
    }

    void refreshFromLatest(TerminalChat chat, String ownerNodeId) {
        if (chat != null) {
            loadInitialHistory(chat, ownerNodeId);
        }
    }

    void pageMessages(int direction) {
        if (loadedMessages.isEmpty()) {
            return;
        }
        if (direction < 0 && selectedMessageIndex <= Math.max(0, messageTopIndex)) {
            loadOlderMessages();
        } else if (direction > 0 && selectedMessageIndex >= loadedMessages.size() - 1) {
            loadNewerMessages();
        }
        int delta = Math.max(1, lastVisibleMessageCount - 1) * Integer.signum(direction);
        selectedMessageIndex = clamp(selectedMessageIndex + delta, 0, loadedMessages.size() - 1);
    }

    void selectRelativeMessage(int delta) {
        if (loadedMessages.isEmpty()) {
            return;
        }
        if (delta < 0 && selectedMessageIndex <= 0) {
            loadOlderMessages();
        } else if (delta > 0 && selectedMessageIndex >= loadedMessages.size() - 1) {
            loadNewerMessages();
        }
        selectedMessageIndex = clamp(selectedMessageIndex + delta, 0, loadedMessages.size() - 1);
    }

    MeshMessage selectedMessage() {
        if (loadedMessages.isEmpty() || selectedMessageIndex < 0 || selectedMessageIndex >= loadedMessages.size()) {
            return null;
        }
        return loadedMessages.get(selectedMessageIndex);
    }

    void markRead(String ownerNodeId) {
        if (loadedChat == null) {
            return;
        }
        MessageDbService db = MessageDbService.getInstance();
        int count = db.getUnreadEligibleMessageCount(loadedChat.dbType(), loadedChat.dbKey(), ownerNodeId);
        db.saveReadCount(loadedChat.dbType(), loadedChat.dbKey(), count, ownerNodeId);
    }

    void hydrateSentReplyText(MeshMessage sent, String ownerNodeId) {
        if (sent == null || sent.getReplyId() == 0 || loadedChat == null) {
            return;
        }
        MessageDbService.getInstance().hydrateReplyTexts(List.of(sent),
                loadedChat.dbType(), loadedChat.dbKey(), ownerNodeId);
    }

    private void hydrateLoadedReplyTexts() {
        if (loadedChat == null || loadedMessages.isEmpty()) {
            return;
        }
        MessageDbService.getInstance().hydrateReplyTexts(
                loadedMessages, loadedChat.dbType(), loadedChat.dbKey(), loadedOwnerNodeId);
    }

    private void restoreViewport(TerminalChat chat) {
        ChatViewport viewport = savedViewports.get(loadedOwnerNodeId + "|" + chat.key());
        if (viewport == null) {
            return;
        }
        for (int i = 0; i < loadedMessages.size(); i++) {
            if (loadedMessages.get(i).getDbId() == viewport.selectedDbId()) {
                selectedMessageIndex = i;
                messageTopIndex = clamp(viewport.topIndex(), 0, Math.max(0, loadedMessages.size() - 1));
                return;
            }
        }
    }

    private void loadOlderMessages() {
        if (loadedChat == null || loadedMessages.isEmpty() || allOlderMessagesLoaded) {
            return;
        }
        long oldest = loadedMessages.getFirst().getDbId();
        List<MeshMessage> older = MessageDbService.getInstance()
                .loadBefore(loadedChat.dbType(), loadedChat.dbKey(), oldest, pageSize, loadedOwnerNodeId);
        if (older.isEmpty()) {
            allOlderMessagesLoaded = true;
            return;
        }
        loadedMessages.addAll(0, older);
        selectedMessageIndex += older.size();
        messageTopIndex += older.size();
        hydrateLoadedReplyTexts();
        allOlderMessagesLoaded = older.size() < pageSize;
        trimLoadedMessagesFromBottomIfNeeded();
    }

    private void loadNewerMessages() {
        if (loadedChat == null || loadedMessages.isEmpty() || allNewerMessagesLoaded) {
            return;
        }
        long newest = loadedMessages.getLast().getDbId();
        List<MeshMessage> newer = MessageDbService.getInstance()
                .loadAfter(loadedChat.dbType(), loadedChat.dbKey(), newest, pageSize, loadedOwnerNodeId);
        if (newer.isEmpty()) {
            allNewerMessagesLoaded = true;
            return;
        }
        loadedMessages.addAll(newer);
        hydrateLoadedReplyTexts();
        allNewerMessagesLoaded = newer.size() < pageSize;
        trimLoadedMessagesFromTopIfNeeded();
    }

    private void trimLoadedMessagesFromTopIfNeeded() {
        int overflow = loadedMessages.size() - maxLoadedMessages;
        if (overflow <= 0) {
            return;
        }
        loadedMessages.subList(0, overflow).clear();
        selectedMessageIndex = Math.max(0, selectedMessageIndex - overflow);
        messageTopIndex = Math.max(0, messageTopIndex - overflow);
        allOlderMessagesLoaded = false;
    }

    private void trimLoadedMessagesFromBottomIfNeeded() {
        int overflow = loadedMessages.size() - maxLoadedMessages;
        if (overflow <= 0) {
            return;
        }
        loadedMessages.subList(loadedMessages.size() - overflow, loadedMessages.size()).clear();
        selectedMessageIndex = clamp(selectedMessageIndex, 0, Math.max(0, loadedMessages.size() - 1));
        allNewerMessagesLoaded = false;
    }
}
