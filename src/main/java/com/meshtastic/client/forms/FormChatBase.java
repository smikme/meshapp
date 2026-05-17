package com.meshtastic.client.forms;

import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.components.chat.ChatBotCommandHelper;
import com.meshtastic.client.components.chat.ChatInputBar;
import com.meshtastic.client.components.chat.ChatNameResolver;
import com.meshtastic.client.components.chat.MessageBubbleFactory;
import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocolRuntime;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Общее состояние и контракты между слоями реализации формы чата.
 *
 * <p>Публичная форма остаётся небольшой, а пакетные слои разделяют построение
 * интерфейса, окно загруженных сообщений, обработку запросов и привязку данных.
 * Состояние, общее для этих слоёв, хранится здесь.
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
    protected static final String WINDOWS_HIT_TEST_BACKGROUND = "-fx-background-color: rgba(0,0,0,0.004);";

    // === Левая панель: список чатов ===
    protected ListView<ChatItem> chatListView;
    protected final ObservableList<ChatItem> chatItems = FXCollections.observableArrayList();
    protected FilteredList<ChatItem> filteredChats;

    // === Правая панель ===
    protected VBox detailPane;
    protected ChatItem selectedChat;

    // Плейсхолдер
    protected VBox placeholderBox;

    // Заголовок чата
    protected HBox chatHeader;
    protected StackPane headerAvatarPane;
    protected Label headerAvatarLabel;
    protected Label headerNameLabel;
    protected Separator headerSep;

    // Область сообщений
    protected ScrollPane messageScrollPane;
    protected VBox messageContainer;
    protected StackPane messageArea; // обёртка: scrollPane + кнопка «вниз»
    protected Button scrollDownBtn;
    protected Label scrollDownBadge;
    protected int newMessageWhileScrolled = 0;

    // Панель ввода
    protected ChatInputBar chatInputBar;
    protected Button newChatBtn;
    protected ContextMenu newChatMenu;

    // === Компоненты ===
    protected TracerouteView tracerouteView;
    protected MessageBubbleFactory bubbleFactory;
    protected ChatNameResolver nameResolver;

    // === Данные ===
    protected DeviceState state;
    protected ProtocolHandler protocolHandler;
    protected MeshCoreCompanionProtocolRuntime meshCoreCompanionRuntime;
    /** Идентификатор подключения, к которому сейчас привязана форма чата. */
    protected String boundConnectionId;

    // Трекинг непрочитанных: ключи вида "ch:INDEX" или "dm:NODEID" → кол-во прочитанных сообщений
    protected final Map<String, Integer> lastReadCounts = new HashMap<>();
    /** Последний выбранный канал/DM для каждого подключения. */
    protected final Map<String, ChatSelection> selectedChatsByConnectionId = new HashMap<>();

    // Пагинация сообщений из БД
    protected long oldestLoadedDbId = Long.MAX_VALUE;
    protected long newestLoadedDbId = 0;
    protected long latestKnownDbId = 0;
    protected boolean allHistoryLoaded = false;
    protected boolean allNewerHistoryLoaded = true;
    protected boolean loadingOlderMessages = false;
    protected boolean loadingNewerMessages = false;
    protected final List<MeshMessage> loadedMessages = new ArrayList<>();
    protected final Map<Long, HBox> loadedMessageRows = new HashMap<>();
    protected String loadedChatScrollCacheKey;
    protected int openingChatUnreadCount = 0;
    protected final Map<String, ChatScrollState> savedChatScrollStates = new HashMap<>();
    // Трекинг статусов исходящих сообщений для обновления при ACK/NAK
    protected final Map<Integer, Label> pendingStatusLabels = new HashMap<>();

    // Активные запросы с обратным отсчётом (трассировка/инфо), переживают переключение чатов
    protected final List<PendingCountdown> pendingCountdowns = new ArrayList<>();

    /** Состояние активного запроса с обратным отсчётом */
    protected static class PendingCountdown {
        final String chatType;
        final String chatKey;
        final String prefix;
        final int[] remaining;
        final boolean[] done = {false};
        EmojiTextFlow countdownLabel;  // пересоздаётся при переключении чатов
        HBox tempBubble;       // пересоздаётся при переключении чатов
        Runnable cancelAction; // действие при отмене (останавливает таймер, убирает слушатель)

        PendingCountdown(String chatType, String chatKey, String prefix, int totalSeconds) {
            this.chatType = chatType;
            this.chatKey = chatKey;
            this.prefix = prefix;
            this.remaining = new int[]{totalSeconds};
        }
    }

    /**
     * Сохраняемая точка привязки прокрутки для области сообщений.
     *
     * @param anchorDbId id сообщения в БД, используемый как якорь области просмотра
     * @param anchorOffset смещение в пикселях от верхней границы строки-якоря
     * @param atBottom была ли область просмотра в живом хвосте чата
     */
    protected record ChatScrollState(long anchorDbId, double anchorOffset, boolean atBottom) {}

    /**
     * Стабильный ключ выбранного чата, не зависящий от пересоздания {@link ChatItem}
     * при обновлении списка чатов.
     *
     * @param type тип чата
     * @param channelIndex индекс канала для {@link ChatItem.ChatType#CHANNEL}
     * @param peerNodeId nodeId собеседника для {@link ChatItem.ChatType#DIRECT_MESSAGE}
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
    protected final AtomicBoolean viewportLayoutQueued = new AtomicBoolean();
    protected final AtomicBoolean viewportLayoutDirty = new AtomicBoolean();

    protected final Runnable messageListener = this::scheduleMessageRefresh;
    protected final Runnable connectionListener = () -> Platform.runLater(this::rebindState);
    protected final ChangeListener<Number> chatFontSizeListener =
            (obs, oldValue, newValue) -> Platform.runLater(this::handleChatFontSizeChanged);

    /**
     * При бурном трафике отдельный Platform.runLater на каждое событие быстро
     * раздувает FX-очередь. Держим не более одного запланированного прохода
     * обновления, а параллельные события лишь помечают состояние как требующее
     * повторного прохода.
     */
    protected void scheduleMessageRefresh() {
        messageRefreshDirty.set(true);
        if (!messageRefreshQueued.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(this::flushQueuedMessageRefresh);
    }

    protected void flushQueuedMessageRefresh() {
        while (messageRefreshDirty.getAndSet(false)) {
            refreshCurrentChat();
            reloadChatList();
        }
        messageRefreshQueued.set(false);
        if (messageRefreshDirty.get() && messageRefreshQueued.compareAndSet(false, true)) {
            Platform.runLater(this::flushQueuedMessageRefresh);
        }
    }

    protected abstract void refreshCurrentChat();
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
    protected abstract void startReply(MeshMessage msg);
    protected abstract void requestTraceroute(MeshMessage msg);
    protected abstract void requestNodeInfo(MeshMessage msg);
    protected abstract void sendReaction(MeshMessage msg, String emoji);
    protected abstract void confirmDeleteMessage(MeshMessage msg, HBox row);
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
     * Запоминает текущий выбранный чат для подключения, к которому привязана форма.
     */
    protected void rememberSelectedChatForBoundConnection() {
        if (boundConnectionId != null && selectedChat != null) {
            selectedChatsByConnectionId.put(boundConnectionId, ChatSelection.from(selectedChat));
        }
    }

    /**
     * Сбрасывает сохранённый выбранный чат для текущего подключения.
     */
    protected void clearSelectedChatForBoundConnection() {
        if (boundConnectionId != null) {
            selectedChatsByConnectionId.remove(boundConnectionId);
        }
    }

    /**
     * Возвращает сохранённый выбранный чат для текущего подключения.
     */
    protected ChatSelection selectedChatForBoundConnection() {
        return boundConnectionId != null ? selectedChatsByConnectionId.get(boundConnectionId) : null;
    }

    /**
     * Проверяет, соответствует ли элемент списка сохранённому ключу чата.
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
