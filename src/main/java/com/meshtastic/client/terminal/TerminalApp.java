package com.meshtastic.client.terminal;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.ExtendedTerminal;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.ANSITerminal;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.logging.UiLogAppender;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocolRuntime;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.utils.AppPreferences;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.meshtastic.client.terminal.TerminalCommandParser.splitCommand;
import static com.meshtastic.client.terminal.TerminalChannelFormatter.availableChannelIndexes;
import static com.meshtastic.client.terminal.TerminalChannelFormatter.channelLabel;
import static com.meshtastic.client.terminal.TerminalDisplayFormatter.displayDirectChatLabel;
import static com.meshtastic.client.terminal.TerminalDisplayFormatter.formatTime;
import static com.meshtastic.client.terminal.TerminalDisplayFormatter.nodeSummary;
import static com.meshtastic.client.terminal.TerminalDisplayFormatter.safe;
import static com.meshtastic.client.terminal.TerminalInputLimits.textByteLength;
import static com.meshtastic.client.terminal.TerminalLayout.clamp;
import static com.meshtastic.client.terminal.TerminalLayout.initialTerminalSize;
import static com.meshtastic.client.terminal.TerminalRuntimeSupport.isMissingControllingTty;
import static com.meshtastic.client.terminal.TerminalRuntimeSupport.isWindows;
import static com.meshtastic.client.terminal.TerminalRuntimeSupport.shortError;

/**
 * Lanterna-based terminal client for connection management and chat interaction.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class TerminalApp implements AutoCloseable {

    private static final Charset TERMINAL_CHARSET = StandardCharsets.UTF_8;
    private static final int MAX_ACTIVITY_LINES = 120;
    private static final int CHAT_PAGE_SIZE = 50;
    private static final int MAX_LOADED_CHAT_MESSAGES = CHAT_PAGE_SIZE * 5;

    private final TerminalOptions options;
    private final TerminalAppUiBridge uiBridge;
    private final ConnectionManager connectionManager;
    private final List<String> activity = new CopyOnWriteArrayList<>();
    private final AtomicBoolean dirty = new AtomicBoolean(true);
    private final Runnable connectionListener = this::markDirty;
    private final Runnable messageListener = this::markDirty;
    private final TerminalRenderer renderer = new TerminalRenderer();
    private final TerminalChatSession chatSession =
            new TerminalChatSession(CHAT_PAGE_SIZE, MAX_LOADED_CHAT_MESSAGES);

    private Screen screen;
    private boolean running = true;
    private boolean commandMode;
    private boolean showHelp;
    private volatile boolean showLogbackLog;
    private String commandBuffer = "";
    private FocusPane activePane = FocusPane.CONNECTIONS;
    private int selectedConnectionIndex;
    private int selectedChannelIndex;
    private String selectedDmPeer;
    private String boundConnectionId;
    private DeviceState boundState;
    private String temporaryConnectionId;
    private List<TerminalChat> chatItems = List.of();
    private int selectedChatIndex;
    private final TerminalInputBuffer input = new TerminalInputBuffer();
    private MeshMessage replyToMessage;

    private TerminalApp(TerminalOptions options, TerminalAppUiBridge uiBridge) {
        this.options = options;
        this.uiBridge = uiBridge;
        this.connectionManager = ConnectionManager.getInstance();
    }

    public static int run(String[] args) {
        TerminalLogging.configureForTerminal();
        TerminalAppUiBridge bridge = new TerminalAppUiBridge();
        com.meshtastic.client.system.AppUi.install(bridge);
        AppPreferences.init();

        TerminalOptions parsed;
        try {
            parsed = TerminalOptions.parse(TerminalOptions.stripTerminalFlag(args));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.println(TerminalOptions.usage());
            return 2;
        }
        if (parsed.isHelp()) {
            System.out.print(TerminalOptions.usage());
            return 0;
        }

        try (TerminalApp app = new TerminalApp(parsed, bridge)) {
            app.run();
            return 0;
        } catch (Exception e) {
            System.err.println("Terminal mode failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private void run() throws IOException, InterruptedException {
        installShutdownHook();
        installLogbackListener();
        connectionManager.addListener(connectionListener);
        createTemporaryConnectionIfRequested();

        Terminal terminal = createTerminal();
        screen = new TerminalScreen(terminal);
        screen.startScreen();
        screen.setCursorPosition(null);

        try {
            if (temporaryConnectionId != null) {
                connect(temporaryConnectionId);
            }
            draw();
            eventLoop();
        } finally {
            close();
        }
    }

    private Terminal createTerminal() throws IOException {
        try {
            Terminal terminal = createUtf8Terminal();
            configureTerminal(terminal);
            return terminal;
        } catch (IOException e) {
            if (!isMissingControllingTty(e)) {
                throw e;
            }
            addActivity("No controlling TTY; using line-input terminal mode");
            FallbackAnsiTerminal terminal = new FallbackAnsiTerminal(
                    System.in,
                    System.out,
                    TERMINAL_CHARSET,
                    initialTerminalSize());
            configureTerminal(terminal);
            return terminal;
        }
    }

    private Terminal createUtf8Terminal() throws IOException {
        if (!isWindows()) {
            return new Utf8UnixTerminal(System.in, System.out, TERMINAL_CHARSET);
        }
        return new DefaultTerminalFactory(System.out, System.in, TERMINAL_CHARSET)
                .setForceTextTerminal(true)
                .setInitialTerminalSize(initialTerminalSize())
                .setInputTimeout(50)
                .setTerminalEmulatorTitle("MeshApp Terminal")
                .createTerminal();
    }

    private static void configureTerminal(Terminal terminal) throws IOException {
        if (terminal instanceof ANSITerminal ansiTerminal) {
            ansiTerminal.getInputDecoder().setTimeoutUnits(50);
        }
        if (terminal instanceof ExtendedTerminal extendedTerminal) {
            extendedTerminal.setTitle("MeshApp Terminal");
        }
    }

    private void eventLoop() throws IOException, InterruptedException {
        while (running) {
            KeyStroke key = screen.pollInput();
            if (key != null) {
                handleKey(key);
            }
            drainStatusMessages();
            rebindState();
            if (screen.doResizeIfNecessary() != null) {
                markDirty();
            }
            if (dirty.getAndSet(false)) {
                draw();
            }
            Thread.sleep(30);
        }
    }

    private void handleKey(KeyStroke key) {
        if (commandMode) {
            handleCommandKey(key);
            return;
        }

        if (key.getKeyType() == KeyType.EOF
                || (key.getKeyType() == KeyType.Character && key.isCtrlDown()
                && key.getCharacter() != null && Character.toLowerCase(key.getCharacter()) == 'c')) {
            running = false;
            return;
        }

        if (key.getKeyType() == KeyType.Tab || key.getKeyType() == KeyType.ReverseTab) {
            cycleFocus(key.getKeyType() == KeyType.ReverseTab ? -1 : 1);
            return;
        }

        if (showHelp) {
            handleHelpKey(key);
            return;
        }

        if (key.getKeyType() == KeyType.PageUp) {
            pageMessages(-1);
            activePane = FocusPane.MESSAGES;
            return;
        }
        if (key.getKeyType() == KeyType.PageDown) {
            pageMessages(1);
            activePane = FocusPane.MESSAGES;
            return;
        }

        if (activePane == FocusPane.INPUT && handleInputKey(key)) {
            return;
        }

        if (key.getKeyType() == KeyType.Escape) {
            clearReplyOrMoveToMessages();
            return;
        }

        if (handleFocusedKey(key)) {
            return;
        }

        if (key.getKeyType() != KeyType.Character || key.getCharacter() == null) {
            return;
        }

        switch (key.getCharacter()) {
            case '/' -> {
                commandMode = true;
                commandBuffer = "";
                markDirty();
            }
            case '?' -> {
                showHelp = true;
                markDirty();
            }
            case 'q', 'Q' -> running = false;
            case 'c', 'C' -> connectSelected();
            case 'd', 'D' -> disconnectSelected();
            case 'l', 'L' -> {
                showLogbackLog = !showLogbackLog;
                activePane = FocusPane.MESSAGES;
                markDirty();
            }
            case '[' -> selectRelativeChannel(-1);
            case ']' -> selectRelativeChannel(1);
            case 'r', 'R' -> startReplyToSelectedMessage();
            default -> {
                if (Character.isDigit(key.getCharacter())) {
                    selectChannelIndex(Character.digit(key.getCharacter(), 10));
                }
            }
        }
    }

    private void handleHelpKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.Escape
                || key.getKeyType() == KeyType.Enter
                || (key.getKeyType() == KeyType.Character
                && key.getCharacter() != null
                && (key.getCharacter() == '?' || key.getCharacter() == 'q' || key.getCharacter() == 'Q'))) {
            showHelp = false;
            markDirty();
        }
    }

    private boolean handleFocusedKey(KeyStroke key) {
        return switch (activePane) {
            case CONNECTIONS -> handleConnectionsKey(key);
            case CHATS -> handleChatsKey(key);
            case MESSAGES -> handleMessagesKey(key);
            case INPUT -> false;
        };
    }

    private boolean handleConnectionsKey(KeyStroke key) {
        switch (key.getKeyType()) {
            case ArrowUp -> {
                selectedConnectionIndex = Math.max(0, selectedConnectionIndex - 1);
                markDirty();
                return true;
            }
            case ArrowDown -> {
                int max = Math.max(0, connectionManager.getEntries().size() - 1);
                selectedConnectionIndex = Math.min(max, selectedConnectionIndex + 1);
                markDirty();
                return true;
            }
            case Enter -> {
                connectSelected();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean handleChatsKey(KeyStroke key) {
        switch (key.getKeyType()) {
            case ArrowUp -> {
                selectRelativeChat(-1);
                return true;
            }
            case ArrowDown -> {
                selectRelativeChat(1);
                return true;
            }
            case Enter -> {
                activePane = FocusPane.INPUT;
                markDirty();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean handleMessagesKey(KeyStroke key) {
        switch (key.getKeyType()) {
            case ArrowUp -> {
                selectRelativeMessage(-1);
                return true;
            }
            case ArrowDown -> {
                selectRelativeMessage(1);
                return true;
            }
            case Enter -> {
                startReplyToSelectedMessage();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean handleInputKey(KeyStroke key) {
        switch (key.getKeyType()) {
            case Enter -> {
                sendInputBuffer();
                return true;
            }
            case Escape -> {
                clearReplyOrMoveToMessages();
                return true;
            }
            case Backspace -> {
                backspaceInput();
                return true;
            }
            case Delete -> {
                deleteInputChar();
                return true;
            }
            case ArrowLeft -> {
                input.moveLeft();
                markDirty();
                return true;
            }
            case ArrowRight -> {
                input.moveRight();
                markDirty();
                return true;
            }
            case Home -> {
                input.home();
                markDirty();
                return true;
            }
            case End -> {
                input.end();
                markDirty();
                return true;
            }
            case Character -> {
                Character ch = key.getCharacter();
                if (ch != null && !Character.isISOControl(ch)) {
                    insertInput(Character.toString(ch));
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void handleCommandKey(KeyStroke key) {
        switch (key.getKeyType()) {
            case Escape -> {
                commandMode = false;
                commandBuffer = "";
                markDirty();
            }
            case Enter -> {
                String command = commandBuffer.trim();
                commandMode = false;
                commandBuffer = "";
                executeCommand(command);
            }
            case Backspace -> {
                if (!commandBuffer.isEmpty()) {
                    commandBuffer = commandBuffer.substring(0, commandBuffer.length() - 1);
                    markDirty();
                }
            }
            case Character -> {
                Character ch = key.getCharacter();
                if (ch != null && !Character.isISOControl(ch)) {
                    commandBuffer += ch;
                    markDirty();
                }
            }
            default -> {
            }
        }
    }

    private void executeCommand(String rawCommand) {
        if (rawCommand.isBlank()) {
            markDirty();
            return;
        }
        List<String> parts = splitCommand(rawCommand);
        String command = parts.getFirst().toLowerCase(Locale.ROOT);
        try {
            switch (command) {
                case "q", "quit", "exit" -> running = false;
                case "help", "?" -> showHelp = true;
                case "connect" -> connectCommand(parts);
                case "disconnect" -> disconnectSelected();
                case "channel", "ch" -> selectChannel(parts);
                case "dm" -> selectDm(parts);
                case "send", "s" -> sendCommand(rawCommand, command);
                case "nodes" -> addActivity("Nodes: " + nodeSummary(boundState));
                case "connections" -> addActivity("Connections: " + connectionManager.getEntries().size());
                default -> addActivity("Unknown command: " + command);
            }
        } catch (RuntimeException e) {
            addActivity("Command failed: " + e.getMessage());
        }
        markDirty();
    }

    private void connectCommand(List<String> parts) {
        if (parts.size() > 1) {
            int index = Integer.parseInt(parts.get(1)) - 1;
            List<ConnectionEntry> entries = connectionManager.getEntries();
            if (index < 0 || index >= entries.size()) {
                addActivity("Connection index out of range");
                return;
            }
            selectedConnectionIndex = index;
        }
        connectSelected();
    }

    private void connectSelected() {
        ConnectionEntry entry = selectedConnection();
        if (entry == null) {
            addActivity("No connection profiles. Start with --host, --serial or create one in GUI.");
            markDirty();
            return;
        }
        connect(entry.getId());
    }

    private void connect(String id) {
        ConnectionEntry entry = findEntry(id);
        if (entry == null) {
            addActivity("Connection not found: " + id);
            markDirty();
            return;
        }
        new Thread(() -> {
            try {
                addActivity("Connecting: " + entry.getName());
                connectionManager.connect(entry.getId());
                connectionManager.setSelectedConnectionId(entry.getId());
                addActivity("Connected: " + entry.getName());
                waitForReady(entry);
            } catch (ConnectionException e) {
                addActivity("Connect failed: " + e.getMessage());
            } catch (RuntimeException e) {
                addActivity("Connect failed: " + e.getMessage());
            } finally {
                markDirty();
            }
        }, "terminal-connect-" + entry.getId()).start();
    }

    private void waitForReady(ConnectionEntry entry) {
        var ready = connectionManager.getProtocolReadyFuture(entry.getId());
        if (ready == null) {
            return;
        }
        ready.orTimeout(30, TimeUnit.SECONDS)
                .whenComplete((state, error) -> {
                    if (error != null) {
                        addActivity("Protocol not ready: " + shortError(error));
                    } else {
                        addActivity("Protocol ready: " + entry.getEffectiveProtocol());
                    }
                    markDirty();
                });
    }

    private void disconnectSelected() {
        ConnectionEntry entry = activeConnectionEntry();
        if (entry == null) {
            entry = selectedConnection();
        }
        if (entry == null) {
            return;
        }
        connectionManager.disconnect(entry.getId());
        addActivity("Disconnected: " + entry.getName());
        markDirty();
    }

    private void selectChannel(List<String> parts) {
        if (parts.size() < 2) {
            addActivity("Usage: channel <index>");
            return;
        }
        selectChannelIndex(Integer.parseInt(parts.get(1)));
    }

    private void selectRelativeChannel(int delta) {
        List<Integer> channels = availableChannelIndexes(boundState);
        if (channels.isEmpty()) {
            selectChannelIndex(clamp(selectedChannelIndex + delta, 0, 7));
            return;
        }
        int current = channels.indexOf(selectedChannelIndex);
        if (current < 0) {
            current = 0;
        } else {
            current = Math.floorMod(current + delta, channels.size());
        }
        selectChannelIndex(channels.get(current));
    }

    private void selectChannelIndex(int channelIndex) {
        selectedChannelIndex = Math.max(0, channelIndex);
        selectedDmPeer = null;
        selectChatByKey("channel", String.valueOf(selectedChannelIndex));
        addActivity("Selected " + channelLabel(boundState, selectedChannelIndex));
        markDirty();
    }

    private void selectDm(List<String> parts) {
        if (parts.size() < 2) {
            addActivity("Usage: dm <nodeId>");
            return;
        }
        selectedDmPeer = parts.get(1);
        if (boundState != null) {
            boundState.ensureDirectMessageThread(selectedDmPeer);
        }
        selectChatByKey("dm", selectedDmPeer);
        addActivity("Selected DM " + displayDirectChatLabel(boundState, selectedDmPeer));
        markDirty();
    }

    private void sendCommand(String rawCommand, String commandName) {
        String prefix = commandName + " ";
        String text = rawCommand.length() > prefix.length()
                ? rawCommand.substring(prefix.length()).trim()
                : "";
        if (text.isBlank()) {
            addActivity("Usage: send <text>");
            return;
        }
        sendText(text, 0);
    }

    private void sendText(String text, int replyId) {
        ActiveConnection active = activeConnection();
        if (active == null || active.state() == null) {
            addActivity("No active connection");
            return;
        }
        if (active.meshCore() != null) {
            MeshMessage sent = selectedDmPeer == null
                    ? active.meshCore().sendChannelMessage(selectedChannelIndex, text, replyId)
                    : active.meshCore().sendDirectMessage(selectedDmPeer, text, replyId);
            hydrateSentReplyText(sent);
            addActivity(sent != null ? "Sent" : "Send failed");
            refreshCurrentChatFromLatest();
            markDirty();
            return;
        }
        if (active.handler() == null) {
            addActivity("Current protocol cannot send text from terminal yet");
            return;
        }
        MeshMessage sent = selectedDmPeer == null
                ? MessageService.sendChannelMessage(active.handler(), active.state(), selectedChannelIndex, text, replyId)
                : MessageService.sendDirectMessage(active.handler(), active.state(), selectedDmPeer, text, replyId);
        hydrateSentReplyText(sent);
        addActivity(sent != null ? "Sent" : "Send failed");
        refreshCurrentChatFromLatest();
        markDirty();
    }

    private void draw() throws IOException {
        refreshChatItems();
        syncLatestMessages();
        TerminalRenderResult result = renderer.draw(screen, renderState());
        selectedConnectionIndex = result.selectedConnectionIndex();
        chatSession.applyRenderResult(result);
    }

    private TerminalRenderState renderState() {
        return new TerminalRenderState(
                showHelp,
                showLogbackLog,
                commandMode,
                commandBuffer,
                activePane,
                List.copyOf(connectionManager.getEntries()),
                selectedConnectionIndex,
                isConnectedView(),
                activeConnectionEntry(),
                activeConnection(),
                boundState,
                selectedChannelIndex,
                selectedDmPeer,
                List.copyOf(chatItems),
                selectedChatIndex,
                List.copyOf(chatSession.loadedMessages()),
                chatSession.selectedMessageIndex(),
                chatSession.messageTopIndex(),
                input.text(),
                input.caret(),
                inputEnabled(),
                maxInputBytes(),
                input.byteLength(),
                replyToMessage,
                List.copyOf(activity));
    }

    private boolean inputEnabled() {
        ActiveConnection active = activeConnection();
        return active != null && active.state() != null && selectedChat() != null;
    }

    private int maxInputBytes() {
        return TerminalInputLimits.maxInputBytes(replyToMessage != null);
    }

    private void rebindState() {
        ActiveConnection active = activeConnection();
        String activeId = active != null ? active.connectionId() : null;
        DeviceState activeState = active != null ? active.state() : null;
        if (Objects.equals(activeId, boundConnectionId) && activeState == boundState) {
            return;
        }
        if (boundState != null) {
            boundState.removeMessageListener(messageListener);
        }
        boundConnectionId = activeId;
        boundState = activeState;
        if (boundState != null) {
            boundState.addMessageListener(messageListener);
        }
        resetChatStateForBoundConnection();
        activePane = boundState != null ? FocusPane.CHATS : FocusPane.CONNECTIONS;
        markDirty();
    }

    private ActiveConnection activeConnection() {
        ConnectionEntry entry = activeConnectionEntry();
        if (entry == null || !entry.isConnected()) {
            return null;
        }
        DeviceState state = connectionManager.getDeviceState(entry.getId());
        ProtocolHandler handler = connectionManager.getProtocolHandler(entry.getId());
        ProtocolRuntime<?> runtime = connectionManager.getProtocolRuntime(entry.getId());
        MeshCoreCompanionProtocolRuntime meshCore =
                runtime instanceof MeshCoreCompanionProtocolRuntime companion ? companion : null;
        return new ActiveConnection(entry.getId(), state, handler, meshCore);
    }

    private ConnectionEntry activeConnectionEntry() {
        ConnectionEntry entry = connectionManager.getSelectedConnectionEntry();
        if (entry == null || !entry.isConnected()) {
            for (ConnectionEntry candidate : connectionManager.getEntries()) {
                if (candidate.isConnected()) {
                    entry = candidate;
                    break;
                }
            }
        }
        return entry != null && entry.isConnected() ? entry : null;
    }

    private boolean isConnectedView() {
        return activeConnectionEntry() != null;
    }

    private void createTemporaryConnectionIfRequested() {
        if (!options.hasInlineConnection()) {
            return;
        }
        ConnectionEntry entry = options.toConnectionEntry();
        connectionManager.addEntry(entry);
        temporaryConnectionId = entry.getId();
        selectedConnectionIndex = Math.max(0, connectionManager.getEntries().size() - 1);
        addActivity("Temporary profile added: " + entry.getName());
    }

    private ConnectionEntry selectedConnection() {
        List<ConnectionEntry> entries = connectionManager.getEntries();
        if (entries.isEmpty()) {
            return null;
        }
        selectedConnectionIndex = clamp(selectedConnectionIndex, 0, entries.size() - 1);
        return entries.get(selectedConnectionIndex);
    }

    private void resetChatStateForBoundConnection() {
        chatItems = List.of();
        selectedChatIndex = 0;
        chatSession.reset();
        replyToMessage = null;
    }

    private void refreshChatItems() {
        if (boundState == null) {
            chatItems = List.of();
            return;
        }

        TerminalChat previous = selectedChat();
        List<TerminalChat> next = TerminalChatListBuilder.build(boundState, selectedChannelIndex, currentOwnerNodeId());
        chatItems = next;
        if (chatItems.isEmpty()) {
            chatSession.reset();
            return;
        }

        int nextSelection = indexOfChat(previous);
        if (nextSelection < 0 && chatSession.loadedChat() != null) {
            nextSelection = indexOfChat(chatSession.loadedChat());
        }
        if (nextSelection < 0) {
            nextSelection = indexOfChat(currentChatKey());
        }
        selectedChatIndex = clamp(nextSelection < 0 ? 0 : nextSelection, 0, chatItems.size() - 1);
        TerminalChat selected = selectedChat();
        if (chatSession.shouldLoad(selected, currentOwnerNodeId())) {
            chatSession.loadInitialHistory(selected, currentOwnerNodeId());
            chatSession.markRead(currentOwnerNodeId());
        }
    }

    private int indexOfChat(TerminalChat chat) {
        if (chat == null) {
            return -1;
        }
        return indexOfChat(chat.key());
    }

    private int indexOfChat(String key) {
        if (key == null) {
            return -1;
        }
        for (int i = 0; i < chatItems.size(); i++) {
            if (key.equals(chatItems.get(i).key())) {
                return i;
            }
        }
        return -1;
    }

    private TerminalChat selectedChat() {
        if (chatItems.isEmpty()) {
            return null;
        }
        selectedChatIndex = clamp(selectedChatIndex, 0, chatItems.size() - 1);
        return chatItems.get(selectedChatIndex);
    }

    private String currentChatKey() {
        return (selectedDmPeer == null ? "channel:" + selectedChannelIndex : "dm:" + selectedDmPeer);
    }

    private String currentOwnerNodeId() {
        return boundState != null && boundState.getOwnerNodeId() != null ? boundState.getOwnerNodeId() : "";
    }

    private void selectChatByKey(String dbType, String dbKey) {
        refreshChatItems();
        int index = indexOfChat(dbType + ":" + dbKey);
        if (index >= 0) {
            selectChatIndex(index);
        }
    }

    private void selectRelativeChat(int delta) {
        refreshChatItems();
        if (chatItems.isEmpty()) {
            return;
        }
        selectChatIndex(clamp(selectedChatIndex + delta, 0, chatItems.size() - 1));
    }

    private void selectChatIndex(int index) {
        if (chatItems.isEmpty()) {
            return;
        }
        chatSession.saveCurrentViewport();
        selectedChatIndex = clamp(index, 0, chatItems.size() - 1);
        TerminalChat chat = chatItems.get(selectedChatIndex);
        selectedChannelIndex = chat.channelIndex();
        selectedDmPeer = chat.peerNodeId();
        if (selectedDmPeer != null && boundState != null) {
            boundState.ensureDirectMessageThread(selectedDmPeer);
        }
        chatSession.loadInitialHistory(chat, currentOwnerNodeId());
        chatSession.markRead(currentOwnerNodeId());
        markDirty();
    }

    private void syncLatestMessages() {
        chatSession.syncLatestMessages();
    }

    private void refreshCurrentChatFromLatest() {
        chatSession.refreshFromLatest(selectedChat(), currentOwnerNodeId());
    }

    private void pageMessages(int direction) {
        chatSession.pageMessages(direction);
        markDirty();
    }

    private void selectRelativeMessage(int delta) {
        chatSession.selectRelativeMessage(delta);
        markDirty();
    }

    private void startReplyToSelectedMessage() {
        MeshMessage message = chatSession.selectedMessage();
        if (message == null) {
            return;
        }
        if (message.getPacketId() == 0) {
            addActivity("Reply unavailable: selected message has no packet id");
            markDirty();
            return;
        }
        replyToMessage = message;
        activePane = FocusPane.INPUT;
        trimInputToLimitIfNeeded();
        markDirty();
    }

    private void clearReplyOrMoveToMessages() {
        if (replyToMessage != null) {
            replyToMessage = null;
            markDirty();
            return;
        }
        if (activePane == FocusPane.INPUT) {
            activePane = FocusPane.MESSAGES;
            markDirty();
        }
    }

    private void insertInput(String text) {
        if (!inputEnabled() || text == null || text.isEmpty()) {
            return;
        }
        if (!input.insert(text, maxInputBytes())) {
            addActivity("Message is too long: " + (input.byteLength() + textByteLength(text)) + "/" + maxInputBytes());
            markDirty();
            return;
        }
        markDirty();
    }

    private void backspaceInput() {
        input.backspace();
        markDirty();
    }

    private void deleteInputChar() {
        input.delete();
        markDirty();
    }

    private void sendInputBuffer() {
        String text = input.text().trim();
        if (text.isEmpty()) {
            return;
        }
        if (!inputEnabled()) {
            addActivity("No active chat");
            markDirty();
            return;
        }
        if (textByteLength(text) > maxInputBytes()) {
            addActivity("Message is too long: " + textByteLength(text) + "/" + maxInputBytes());
            markDirty();
            return;
        }
        int replyId = replyToMessage != null ? replyToMessage.getPacketId() : 0;
        sendText(text, replyId);
        input.clear();
        replyToMessage = null;
        activePane = FocusPane.MESSAGES;
        markDirty();
    }

    private void trimInputToLimitIfNeeded() {
        input.trimToLimit(maxInputBytes());
    }

    private void hydrateSentReplyText(MeshMessage sent) {
        chatSession.hydrateSentReplyText(sent, currentOwnerNodeId());
    }

    private void cycleFocus(int direction) {
        FocusPane[] panes = focusPanes();
        int current = 0;
        for (int i = 0; i < panes.length; i++) {
            if (panes[i] == activePane) {
                current = i;
                break;
            }
        }
        activePane = panes[Math.floorMod(current + direction, panes.length)];
        markDirty();
    }

    private FocusPane[] focusPanes() {
        return isConnectedView()
                ? new FocusPane[]{FocusPane.CHATS, FocusPane.MESSAGES, FocusPane.INPUT}
                : new FocusPane[]{FocusPane.CONNECTIONS, FocusPane.MESSAGES, FocusPane.INPUT};
    }

    private void drainStatusMessages() {
        String message;
        while ((message = uiBridge.pollStatusMessage()) != null) {
            addActivity(message);
        }
    }

    private void addActivity(String line) {
        activity.add(formatTime(Instant.now()) + " " + safe(line));
        while (activity.size() > MAX_ACTIVITY_LINES) {
            activity.removeFirst();
        }
    }

    private void installLogbackListener() {
        UiLogAppender.setLiveListener(entry -> {
            if (showLogbackLog) {
                markDirty();
            }
        });
    }

    private void markDirty() {
        dirty.set(true);
    }

    private void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                closeRuntime();
            } catch (RuntimeException ignored) {
            }
        }, "terminal-shutdown"));
    }

    @Override
    public void close() throws IOException {
        closeRuntime();
        if (screen != null) {
            screen.stopScreen();
            screen = null;
        }
    }

    private void closeRuntime() {
        UiLogAppender.clearLiveListener();
        if (boundState != null) {
            boundState.removeMessageListener(messageListener);
            boundState = null;
        }
        connectionManager.removeListener(connectionListener);
        connectionManager.shutdownAll();
        if (temporaryConnectionId != null && findEntry(temporaryConnectionId) != null) {
            connectionManager.removeEntry(temporaryConnectionId);
            temporaryConnectionId = null;
        }
        com.meshtastic.client.service.MessageDbService.closeIfInitialized();
        com.meshtastic.client.service.NodeCacheService.closeIfInitialized();
        com.meshtastic.client.service.PacketMonitorService.closeIfInitialized();
        com.meshtastic.client.service.DatabaseProvider.close();
    }

    private ConnectionEntry findEntry(String id) {
        if (id == null) {
            return null;
        }
        for (ConnectionEntry entry : connectionManager.getEntries()) {
            if (id.equals(entry.getId())) {
                return entry;
            }
        }
        return null;
    }
}
