package com.meshtastic.client.forms;

import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.components.chat.ChatBotCommandHelper;
import com.meshtastic.client.components.chat.ChatInputBar;
import com.meshtastic.client.components.chat.ChatNameResolver;
import com.meshtastic.client.components.chat.MessageBubbleFactory;
import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageChangeEvent;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocolRuntime;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import com.meshtastic.client.rpc.RpcEventListener;
import com.meshtastic.client.system.Form;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Shared state and contracts for the chat form implementation layers.
 *
 * <p>The public form stays small while package-level layers split UI building,
 * the loaded-message window, request handling, and data binding. State shared
 * by those layers lives here.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
abstract class FormChatBase extends Form {

    protected static final int REQUEST_TIMEOUT_SECONDS = 360;
    protected static final int UNREAD_FOCUS_THRESHOLD = 2;
    protected static final double BOTTOM_READ_SLOP_PX = 24.0;
    protected static final double PAGE_LOAD_EDGE_THRESHOLD = 0.1;
    protected static final int PAGE_SIZE = 50;
    protected static final int MAX_WINDOW_PAGES = 3;
    protected static final int MAX_LOADED_MESSAGES = PAGE_SIZE * MAX_WINDOW_PAGES;
    protected static final int MAX_PENDING_MESSAGE_CHANGE_EVENTS = 512;
    protected static final java.time.Duration REMOTE_RPC_TIMEOUT = java.time.Duration.ofSeconds(15);
    protected static final String WINDOWS_HIT_TEST_BACKGROUND = "-fx-background-color: rgba(0,0,0,0.004);";

    // === Left pane: chat list ===
    protected ListView<ChatItem> chatListView;
    protected final ObservableList<ChatItem> chatItems = FXCollections.observableArrayList();
    protected FilteredList<ChatItem> filteredChats;

    // === Right pane ===
    protected VBox detailPane;
    protected ChatItem selectedChat;

    // Placeholder
    protected VBox placeholderBox;

    // Chat header
    protected HBox chatHeader;
    protected StackPane headerAvatarPane;
    protected Label headerAvatarLabel;
    protected Label headerNameLabel;
    protected Separator headerSep;

    // Message area
    protected ScrollPane messageScrollPane;
    protected VBox messageContainer;
    protected StackPane messageArea; // Wrapper: scrollPane plus the jump-down button.
    protected Button scrollDownBtn;
    protected Label scrollDownBadge;
    protected int newMessageWhileScrolled = 0;
    protected HBox messageSelectionBar;
    protected Label messageSelectionLabel;
    protected Button deleteSelectedMessagesBtn;
    protected Button clearSelectedMessagesBtn;

    // Input bar
    protected ChatInputBar chatInputBar;
    protected Button newChatBtn;
    protected ContextMenu newChatMenu;

    // === Components ===
    protected TracerouteView tracerouteView;
    protected MessageBubbleFactory bubbleFactory;
    protected ChatNameResolver nameResolver;

    // === Data ===
    protected DeviceState state;
    protected ProtocolHandler protocolHandler;
    protected MeshCoreCompanionProtocolRuntime meshCoreCompanionRuntime;
    protected RemoteRpcState remoteRpcState;
    /** Connection id currently bound to the chat form. */
    protected String boundConnectionId;
    protected RpcEventListener remoteChatEventListener;
    protected final Map<String, Boolean> remoteNodeFavoriteFlags = new ConcurrentHashMap<>();
    protected final Map<String, Boolean> remoteNodeIgnoredFlags = new ConcurrentHashMap<>();

    // Unread tracking: keys such as "ch:INDEX" or "dm:NODEID" map to read-message counts.
    protected final Map<String, Integer> lastReadCounts = new HashMap<>();
    protected final Set<String> pendingRemoteReadKeys = new LinkedHashSet<>();
    /** Last selected channel or DM for each connection. */
    protected final Map<String, ChatSelection> selectedChatsByConnectionId = new HashMap<>();

    // Database-backed message pagination.
    protected long oldestLoadedDbId = Long.MAX_VALUE;
    protected long newestLoadedDbId = 0;
    protected long latestKnownDbId = 0;
    protected boolean allHistoryLoaded = false;
    protected boolean allNewerHistoryLoaded = true;
    protected boolean loadingOlderMessages = false;
    protected boolean loadingNewerMessages = false;
    protected final List<MeshMessage> loadedMessages = new ArrayList<>();
    protected final Map<Long, HBox> loadedMessageRows = new HashMap<>();
    protected final Map<Long, MessageBubbleFactory.RenderedMessageRow> loadedRenderedMessageRows = new HashMap<>();
    protected final Set<Long> selectedMessageDbIds = new LinkedHashSet<>();
    protected String loadedChatScrollCacheKey;
    protected int openingChatUnreadCount = 0;
    protected final Map<String, ChatScrollState> savedChatScrollStates = new HashMap<>();
    // Outgoing status tracking, used when ACK/NAK updates arrive.
    protected final Map<Integer, Label> pendingStatusLabels = new HashMap<>();

    // Active countdown requests such as traceroute/info; they survive chat switches.
    protected final List<PendingCountdown> pendingCountdowns = new ArrayList<>();

    /** State for an active countdown request. */
    protected static class PendingCountdown {
        final String chatType;
        final String chatKey;
        final String prefix;
        final int[] remaining;
        final boolean[] done = {false};
        EmojiTextFlow countdownLabel;  // Recreated when switching chats.
        HBox tempBubble;       // Recreated when switching chats.
        Runnable cancelAction; // Cancels the timer and removes the listener.

        PendingCountdown(String chatType, String chatKey, String prefix, int totalSeconds) {
            this.chatType = chatType;
            this.chatKey = chatKey;
            this.prefix = prefix;
            this.remaining = new int[]{totalSeconds};
        }
    }

    /**
     * Persisted scroll anchor for the message area.
 *
     * @param anchorDbId   database id of the message used as the viewport anchor
     * @param anchorOffset pixel offset from the top edge of the anchor row
     * @param atBottom     whether the viewport was at the live tail of the chat
     */
    protected record ChatScrollState(long anchorDbId, double anchorOffset, boolean atBottom) {}

    /**
     * Stable key for the selected chat, independent of {@link ChatItem}
     * recreation during chat-list refreshes.
 *
     * @param type         chat type
     * @param channelIndex channel index for {@link ChatItem.ChatType#CHANNEL}
     * @param peerNodeId   peer node id for {@link ChatItem.ChatType#DIRECT_MESSAGE}
     */
    protected record ChatSelection(ChatItem.ChatType type, int channelIndex, String peerNodeId) {
        static ChatSelection from(ChatItem item) {
            return new ChatSelection(item.getType(), item.getChannelIndex(), item.getPeerNodeId());
        }
    }

    protected boolean suppressSelectionListener;
    protected boolean formVisible;
    protected int scrollStateSyncSuspendCount;
    protected long scrollOperationGeneration;
    protected final AtomicBoolean messageRefreshQueued = new AtomicBoolean();
    protected final AtomicBoolean messageRefreshDirty = new AtomicBoolean();
    protected final Queue<MessageChangeEvent> pendingMessageChanges = new ConcurrentLinkedQueue<>();
    protected final AtomicInteger pendingMessageChangeCount = new AtomicInteger();
    protected final AtomicBoolean pendingMessageChangesOverflowed = new AtomicBoolean();
    protected final AtomicBoolean viewportLayoutQueued = new AtomicBoolean();
    protected final AtomicBoolean viewportLayoutDirty = new AtomicBoolean();

    protected final Runnable messageListener = this::scheduleMessageRefresh;
    protected final Consumer<MessageChangeEvent> messageChangeListener = this::scheduleMessageChangeRefresh;
    protected final Runnable connectionListener = () -> Platform.runLater(this::rebindState);
    protected final ChangeListener<Number> chatFontSizeListener =
            (obs, oldValue, newValue) -> Platform.runLater(this::handleChatFontSizeChanged);

    /**
     * Under heavy traffic, scheduling one Platform.runLater call per event can
     * flood the FX queue. We keep at most one refresh pass scheduled; concurrent
     * events only mark the state as needing another pass.
     */
    protected void scheduleMessageRefresh() {
        messageRefreshDirty.set(true);
        if (!messageRefreshQueued.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(this::flushQueuedMessageRefresh);
    }

    protected void scheduleMessageChangeRefresh(MessageChangeEvent event) {
        enqueueMessageChangeEvent(event != null ? event : MessageChangeEvent.unknown());
        scheduleMessageRefresh();
    }

    private void enqueueMessageChangeEvent(MessageChangeEvent event) {
        if (pendingMessageChangesOverflowed.get()) {
            return;
        }

        int queued = pendingMessageChangeCount.incrementAndGet();
        if (queued <= MAX_PENDING_MESSAGE_CHANGE_EVENTS) {
            pendingMessageChanges.add(event);
            return;
        }

        pendingMessageChangeCount.decrementAndGet();
        pendingMessageChanges.clear();
        pendingMessageChangeCount.set(0);
        pendingMessageChangesOverflowed.set(true);
    }

    protected void flushQueuedMessageRefresh() {
        messageRefreshDirty.getAndSet(false);
        List<MessageChangeEvent> events = drainPendingMessageChanges();
        if (events.isEmpty()) {
            if (remoteRpcState == null) {
                refreshCurrentChat();
            }
        } else {
            processMessageChangeEvents(events);
        }
        reloadChatList();
        messageRefreshQueued.set(false);
        if (messageRefreshDirty.get() && messageRefreshQueued.compareAndSet(false, true)) {
            Platform.runLater(this::flushQueuedMessageRefresh);
        }
    }

    private List<MessageChangeEvent> drainPendingMessageChanges() {
        List<MessageChangeEvent> events = new ArrayList<>();
        MessageChangeEvent event;
        while ((event = pendingMessageChanges.poll()) != null) {
            pendingMessageChangeCount.updateAndGet(value -> Math.max(0, value - 1));
            events.add(event);
        }
        if (pendingMessageChangesOverflowed.getAndSet(false)) {
            pendingMessageChanges.clear();
            pendingMessageChangeCount.set(0);
            events.clear();
            events.add(MessageChangeEvent.unknown());
        }
        return events;
    }

    protected abstract void refreshCurrentChat();
    protected abstract void processMessageChangeEvents(List<MessageChangeEvent> events);
    protected abstract void reloadChatList();
    protected abstract void rebindState();
    protected abstract void handleChatFontSizeChanged();
    protected abstract void saveCurrentChatScrollState();
    protected abstract void suspendScrollStateSync();
    protected abstract void resumeScrollStateSyncLater();
    protected abstract boolean isScrollStateSyncSuspended();
    protected abstract boolean isCurrentScrollOperation(long generation);
    protected abstract void clearLoadedMessageState();
    protected abstract void showNewChatDialog();
    protected abstract void deleteChat(ChatItem item);
    protected abstract void showChannelProperties(ChatItem item);
    protected abstract void toggleChatMute(ChatItem item);
    protected abstract void loadOlderMessages();
    protected abstract void loadNewerMessages();
    protected abstract boolean isAtLiveTail();
    protected abstract void scrollToBottom();
    protected abstract void jumpToLatestMessages();
    protected abstract void updateScrollDownBadge();
    protected abstract void markAsRead(ChatItem item);
    protected abstract int getUnreadCount(ChatItem item);
    protected abstract void refreshUnreadTailIndicator();
    protected abstract void loadInitialMessages(boolean restoreSavedState);
    protected abstract void ensureMessageLoaded(long dbId);
    protected abstract void scrollToMessage(long dbId, double anchorOffset);
    protected abstract void requestMessageViewportLayout();
    protected abstract void restorePendingCountdowns();
    protected abstract void updateInputEnabled();
    protected abstract void refreshLoadedMessageRows();
    protected abstract void refreshLoadedMessageRows(boolean force);
    protected abstract void startReply(MeshMessage msg);
    protected abstract void sendReaction(MeshMessage msg, String emoji);
    protected abstract void confirmDeleteMessage(MeshMessage msg, HBox row);
    protected abstract void toggleMessageSelection(MeshMessage msg, HBox row);
    protected abstract boolean isMessageSelected(MeshMessage msg);
    protected abstract boolean isMessageSelectionModeActive();
    protected abstract void clearSelectedMessages();
    protected abstract void deleteSelectedMessagesWithConfirmation();
    protected abstract boolean retryMessage(MeshMessage msg);
    protected abstract void refreshCurrentChatAfterLocalSend();
    protected abstract boolean handleBotCommand(ChatBotCommandHelper.ParsedBotCommand command);
    protected abstract List<NodeData> listBotCommandNodes();
    protected abstract boolean isCurrentChat(String chatType, String chatKey);

    protected static boolean chatItemMatches(ChatItem a, ChatItem b) {
        if (a.getType() != b.getType()) {
            return false;
        }
        return a.getType() == ChatItem.ChatType.CHANNEL
                ? a.getChannelIndex() == b.getChannelIndex()
                : Objects.equals(a.getPeerNodeId(), b.getPeerNodeId());
    }

    /**
     * Remembers the currently selected chat for the connection bound to the form.
     */
    protected void rememberSelectedChatForBoundConnection() {
        if (boundConnectionId != null && selectedChat != null) {
            selectedChatsByConnectionId.put(boundConnectionId, ChatSelection.from(selectedChat));
        }
    }

    /**
     * Clears the saved selected chat for the current connection.
     */
    protected void clearSelectedChatForBoundConnection() {
        if (boundConnectionId != null) {
            selectedChatsByConnectionId.remove(boundConnectionId);
        }
    }

    /**
     * Returns the saved selected chat for the current connection.
     */
    protected ChatSelection selectedChatForBoundConnection() {
        return boundConnectionId != null ? selectedChatsByConnectionId.get(boundConnectionId) : null;
    }

    /**
     * Checks whether a list item matches the saved chat key.
     */
    protected static boolean chatItemMatchesSelection(ChatItem item, ChatSelection selection) {
        if (item == null || selection == null || item.getType() != selection.type()) {
            return false;
        }
        return item.getType() == ChatItem.ChatType.CHANNEL
                ? item.getChannelIndex() == selection.channelIndex()
                : Objects.equals(item.getPeerNodeId(), selection.peerNodeId());
    }

    protected static boolean containsIgnoreCase(String text, String query) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query);
    }

}
