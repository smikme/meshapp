package com.meshtastic.client.terminal;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.Screen.RefreshType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.ExtendedTerminal;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.ANSITerminal;
import com.googlecode.lanterna.terminal.ansi.UnixTerminal;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.logging.UiLogAppender;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.LogEntry;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocolRuntime;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.utils.AppPreferences;
import org.meshtastic.proto.ChannelProtos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lanterna-based terminal client.
 */
public final class TerminalApp implements AutoCloseable {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final TerminalSize DEFAULT_TERMINAL_SIZE = new TerminalSize(100, 30);
    private static final Charset TERMINAL_CHARSET = StandardCharsets.UTF_8;
    private static final int MAX_ACTIVITY_LINES = 120;

    private final TerminalOptions options;
    private final TerminalAppUiBridge uiBridge;
    private final ConnectionManager connectionManager;
    private final List<String> activity = new CopyOnWriteArrayList<>();
    private final AtomicBoolean dirty = new AtomicBoolean(true);
    private final Runnable connectionListener = this::markDirty;
    private final Runnable messageListener = this::markDirty;

    private Screen screen;
    private boolean running = true;
    private boolean commandMode;
    private boolean showHelp;
    private volatile boolean showLogbackLog;
    private String commandBuffer = "";
    private int selectedConnectionIndex;
    private int selectedChannelIndex;
    private String selectedDmPeer;
    private String boundConnectionId;
    private DeviceState boundState;
    private String temporaryConnectionId;

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
                || key.getKeyType() == KeyType.Escape
                || (key.getKeyType() == KeyType.Character && key.isCtrlDown()
                && key.getCharacter() != null && Character.toLowerCase(key.getCharacter()) == 'c')) {
            running = false;
            return;
        }
        if (key.getKeyType() == KeyType.ArrowUp) {
            if (isConnectedView()) {
                selectRelativeChannel(-1);
                return;
            }
            selectedConnectionIndex = Math.max(0, selectedConnectionIndex - 1);
            markDirty();
            return;
        }
        if (key.getKeyType() == KeyType.ArrowDown) {
            if (isConnectedView()) {
                selectRelativeChannel(1);
                return;
            }
            int max = Math.max(0, connectionManager.getEntries().size() - 1);
            selectedConnectionIndex = Math.min(max, selectedConnectionIndex + 1);
            markDirty();
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
                showHelp = !showHelp;
                markDirty();
            }
            case 'q', 'Q' -> running = false;
            case 'c', 'C' -> connectSelected();
            case 'd', 'D' -> disconnectSelected();
            case 'l', 'L' -> {
                showLogbackLog = !showLogbackLog;
                markDirty();
            }
            case '[' -> selectRelativeChannel(-1);
            case ']' -> selectRelativeChannel(1);
            case 'r', 'R' -> markDirty();
            default -> {
                if (Character.isDigit(key.getCharacter())) {
                    selectChannelIndex(Character.digit(key.getCharacter(), 10));
                }
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
                case "nodes" -> addActivity("Nodes: " + nodeSummary());
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
        List<Integer> channels = availableChannelIndexes();
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
        addActivity("Selected " + channelLabel(selectedChannelIndex));
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
        addActivity("Selected DM " + selectedDmPeer);
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
        sendText(text);
    }

    private void sendText(String text) {
        ActiveConnection active = activeConnection();
        if (active == null || active.state() == null) {
            addActivity("No active connection");
            return;
        }
        if (active.meshCore() != null) {
            MeshMessage sent = selectedDmPeer == null
                    ? active.meshCore().sendChannelMessage(selectedChannelIndex, text, 0)
                    : active.meshCore().sendDirectMessage(selectedDmPeer, text, 0);
            addActivity(sent != null ? "Sent" : "Send failed");
            markDirty();
            return;
        }
        if (active.handler() == null) {
            addActivity("Current protocol cannot send text from terminal yet");
            return;
        }
        MeshMessage sent = selectedDmPeer == null
                ? MessageService.sendChannelMessage(active.handler(), active.state(), selectedChannelIndex, text, 0)
                : MessageService.sendDirectMessage(active.handler(), active.state(), selectedDmPeer, text, 0);
        addActivity(sent != null ? "Sent" : "Send failed");
        markDirty();
    }

    private void draw() throws IOException {
        TerminalSize size = screen.doResizeIfNecessary();
        if (size == null) {
            size = screen.getTerminalSize();
        }
        screen.clear();
        TextGraphics g = screen.newTextGraphics();
        drawFrame(g, size);
        if (showHelp) {
            drawHelp(g, size);
        } else {
            drawLeftPane(g, size);
            drawDetail(g, size);
        }
        drawCommandLine(g, size);
        screen.refresh(RefreshType.DELTA);
    }

    private void drawFrame(TextGraphics g, TerminalSize size) {
        int width = size.getColumns();
        g.setBackgroundColor(TextColor.ANSI.BLUE);
        g.setForegroundColor(TextColor.ANSI.WHITE);
        putString(g, 0, 0, padRight(" MeshApp Terminal", width), SGR.BOLD);
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.WHITE);
        int leftWidth = leftPaneWidth(size);
        for (int y = 1; y < size.getRows() - 1; y++) {
            putChar(g, leftWidth, y, '|');
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        putString(g, 1, size.getRows() - 2,
                fit("q quit | c connect | d disconnect | [ ] channel | 0-9 channel | l logs | / command | ? help",
                        Math.max(0, width - 2)));
    }

    private void drawLeftPane(TextGraphics g, TerminalSize size) {
        if (isConnectedView()) {
            drawChannels(g, size);
        } else {
            drawConnections(g, size);
        }
    }

    private void drawConnections(TextGraphics g, TerminalSize size) {
        int leftWidth = leftPaneWidth(size);
        List<ConnectionEntry> entries = connectionManager.getEntries();
        selectedConnectionIndex = clamp(selectedConnectionIndex, 0, Math.max(0, entries.size() - 1));

        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, 1, 2, "Connections", SGR.BOLD);
        if (entries.isEmpty()) {
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            putString(g, 1, 4, fit("No profiles", leftWidth - 2));
            return;
        }

        int y = 4;
        for (int i = 0; i < entries.size() && y < size.getRows() - 10; i++) {
            ConnectionEntry entry = entries.get(i);
            boolean selected = i == selectedConnectionIndex;
            if (selected) {
                g.setBackgroundColor(TextColor.ANSI.WHITE);
                g.setForegroundColor(TextColor.ANSI.BLACK);
            } else if (entry.isConnected()) {
                g.setForegroundColor(TextColor.ANSI.GREEN);
            } else if (entry.isReconnecting()) {
                g.setForegroundColor(TextColor.ANSI.YELLOW);
            } else {
                g.setForegroundColor(TextColor.ANSI.WHITE);
            }
            putString(g, 1, y++, padRight((i + 1) + ". " + safe(entry.getName()), leftWidth - 2));
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            putString(g, 3, y++, fit(connectionSummary(entry), leftWidth - 4));
        }

        y = Math.max(y + 1, size.getRows() - 8);
        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, 1, y++, "Chat");
        g.setForegroundColor(TextColor.ANSI.WHITE);
        String chat = selectedDmPeer == null ? channelLabel(selectedChannelIndex) : "dm " + selectedDmPeer;
        putString(g, 1, y++, fit(chat, leftWidth - 2));

        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, 1, y++, "Nodes");
        g.setForegroundColor(TextColor.ANSI.WHITE);
        for (String line : nodeLines(Math.max(0, size.getRows() - y - 3))) {
            putString(g, 1, y++, fit(line, leftWidth - 2));
        }
    }

    private void drawChannels(TextGraphics g, TerminalSize size) {
        int leftWidth = leftPaneWidth(size);
        ConnectionEntry active = activeConnectionEntry();
        int y = 2;

        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, 1, y++, "Channels", SGR.BOLD);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        if (active != null) {
            putString(g, 1, y++, fit("on " + safe(active.getName()), leftWidth - 2));
        }
        y++;

        List<Integer> channels = visibleChannelIndexes();
        for (int i = 0; i < channels.size() && y < size.getRows() - 8; i++) {
            int channelIndex = channels.get(i);
            boolean selected = selectedDmPeer == null && channelIndex == selectedChannelIndex;
            if (selected) {
                g.setBackgroundColor(TextColor.ANSI.WHITE);
                g.setForegroundColor(TextColor.ANSI.BLACK);
            } else {
                g.setForegroundColor(TextColor.ANSI.WHITE);
            }
            putString(g, 1, y++, padRight(channelMenuLabel(i, channelIndex), leftWidth - 2));
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            putString(g, 3, y++, fit(channelDescription(channelIndex), leftWidth - 4));
        }

        y = Math.max(y + 1, size.getRows() - 8);
        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, 1, y++, "Chat");
        g.setForegroundColor(TextColor.ANSI.WHITE);
        String chat = selectedDmPeer == null ? channelLabel(selectedChannelIndex) : "dm " + selectedDmPeer;
        putString(g, 1, y++, fit(chat, leftWidth - 2));

        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, 1, y++, "Nodes");
        g.setForegroundColor(TextColor.ANSI.WHITE);
        for (String line : nodeLines(Math.max(0, size.getRows() - y - 3))) {
            putString(g, 1, y++, fit(line, leftWidth - 2));
        }
    }

    private void drawDetail(TextGraphics g, TerminalSize size) {
        int leftWidth = leftPaneWidth(size);
        int x = leftWidth + 2;
        int width = Math.max(10, size.getColumns() - x - 1);
        int y = 2;

        if (showLogbackLog) {
            drawLogbackDetail(g, size, x, width, y);
            return;
        }

        ActiveConnection active = activeConnection();
        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, x, y++, "Live messages", SGR.BOLD);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        putString(g, x, y++, fit(detailSubtitle(active), width));

        List<String> messageLines = messageLines(Math.max(0, size.getRows() - y - 2), width);
        g.setForegroundColor(TextColor.ANSI.WHITE);
        for (String line : messageLines) {
            putString(g, x, y++, fit(line, width));
        }
    }

    private void drawLogbackDetail(TextGraphics g, TerminalSize size, int x, int width, int y) {
        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, x, y++, "Logback", SGR.BOLD);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        putString(g, x, y++, fit("events=" + UiLogAppender.getBuffer().size(), width));

        List<String> recent = logbackLines(Math.max(0, size.getRows() - y - 2), width);
        for (String line : recent) {
            putString(g, x, y++, fit(line, width));
        }
    }

    private void drawHelp(TextGraphics g, TerminalSize size) {
        int x = 2;
        int y = 2;
        int width = Math.max(10, size.getColumns() - 4);
        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, x, y++, "Help", SGR.BOLD);
        g.setForegroundColor(TextColor.ANSI.WHITE);
        for (String line : List.of(
                "Keys:",
                "  Up/Down       select connection or channel",
                "  c             connect selected connection",
                "  d             disconnect active/selected connection",
                "  l             toggle logback log",
                "  [ / ]         previous / next channel",
                "  0-9           select channel by index",
                "  /             enter command mode",
                "  ?             toggle this help",
                "  q / Ctrl-C    quit",
                "",
                "Commands:",
                "  connect [n]          connect selected or numbered profile",
                "  disconnect           disconnect selected profile",
                "  channel <index>      select channel chat",
                "  dm <nodeId>          select direct message peer",
                "  send <text>          send to selected chat",
                "  nodes                append node summary to status buffer",
                "  quit                 exit terminal mode")) {
            if (y < size.getRows() - 3) {
                putString(g, x, y++, fit(line, width));
            }
        }
    }

    private void drawCommandLine(TextGraphics g, TerminalSize size) {
        int row = size.getRows() - 1;
        int width = size.getColumns();
        g.setBackgroundColor(TextColor.ANSI.BLACK);
        g.setForegroundColor(commandMode ? TextColor.ANSI.YELLOW : TextColor.ANSI.WHITE);
        String prompt = commandMode ? ":" + commandBuffer : "Press / for command";
        putString(g, 0, row, padRight(prompt, width));
        if (commandMode) {
            int cursor = Math.min(width - 1, TerminalText.displayWidth(":" + commandBuffer));
            screen.setCursorPosition(new TerminalPosition(cursor, row));
        } else {
            screen.setCursorPosition(null);
        }
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
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

    private List<String> messageLines(int maxLines, int width) {
        if (boundState == null || maxLines <= 0) {
            return List.of();
        }
        List<MeshMessage> messages = selectedDmPeer == null
                ? boundState.getMessages(selectedChannelIndex)
                : boundState.getDirectMessages(selectedDmPeer);
        List<String> lines = new ArrayList<>();
        for (MeshMessage msg : tail(messages, maxLines)) {
            String direction = msg.isOutgoing() ? "me" : displaySender(msg);
            String status = msg.getStatus() != null && msg.isOutgoing() ? " [" + msg.getStatus() + "]" : "";
            String prefix = TIME_FMT.format(Instant.ofEpochSecond(Math.max(0, msg.getTimestamp())))
                    + " " + direction + status + ": ";
            lines.addAll(wrap(prefix + safe(msg.getText()), width));
        }
        return tail(lines, maxLines);
    }

    private List<String> nodeLines(int maxLines) {
        if (boundState == null || maxLines <= 0) {
            return List.of();
        }
        return boundState.getNodeDb().values().stream()
                .sorted(Comparator.comparingInt(NodeData::getLastHeard).reversed())
                .limit(maxLines)
                .map(node -> displayNode(node) + " " + safe(node.getNodeId()))
                .toList();
    }

    private String nodeSummary() {
        if (boundState == null) {
            return "no active state";
        }
        return boundState.getNodeDb().size() + " nodes";
    }

    private List<Integer> availableChannelIndexes() {
        if (boundState == null || boundState.getChannels() == null || boundState.getChannels().isEmpty()) {
            return List.of();
        }
        return boundState.getChannels().stream()
                .mapToInt(ChannelProtos.Channel::getIndex)
                .filter(index -> index >= 0)
                .distinct()
                .sorted()
                .boxed()
                .toList();
    }

    private List<Integer> visibleChannelIndexes() {
        List<Integer> channels = new ArrayList<>(availableChannelIndexes());
        if (channels.isEmpty()) {
            channels.add(selectedChannelIndex);
        } else if (!channels.contains(selectedChannelIndex)) {
            channels.add(selectedChannelIndex);
            channels.sort(Integer::compareTo);
        }
        return channels;
    }

    private String channelMenuLabel(int menuIndex, int channelIndex) {
        return channelIndex + ". " + channelLabel(channelIndex);
    }

    private String channelDescription(int channelIndex) {
        if (boundState == null) {
            return "waiting for device state";
        }
        String name = channelName(channelIndex);
        return name == null ? "index " + channelIndex : "index " + channelIndex + " | " + name;
    }

    private String channelLabel(int channelIndex) {
        String name = channelName(channelIndex);
        return name == null ? "channel " + channelIndex : "channel " + channelIndex + " " + name;
    }

    private String channelName(int channelIndex) {
        if (boundState == null || boundState.getChannels() == null) {
            return null;
        }
        for (ChannelProtos.Channel channel : boundState.getChannels()) {
            if (channel.getIndex() == channelIndex) {
                String name = channel.getSettings().getName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
                return channelIndex == 0 ? "Primary" : null;
            }
        }
        return null;
    }

    private String displaySender(MeshMessage msg) {
        if (msg.getSenderName() != null && !msg.getSenderName().isBlank()) {
            return msg.getSenderName();
        }
        NodeData node = boundState != null ? boundState.getNodeByNodeId(msg.getFromNodeId()) : null;
        return node != null ? displayNode(node) : safe(msg.getFromNodeId());
    }

    private static String displayNode(NodeData node) {
        if (node == null) {
            return "?";
        }
        if (node.getLongName() != null && !node.getLongName().isBlank()) {
            return node.getLongName();
        }
        if (node.getShortName() != null && !node.getShortName().isBlank()) {
            return node.getShortName();
        }
        return node.getNodeId() != null ? node.getNodeId() : String.format("!%08x", node.getNodeNum());
    }

    private String detailSubtitle(ActiveConnection active) {
        if (active == null) {
            return "No active connection";
        }
        String chat = selectedDmPeer == null ? channelLabel(selectedChannelIndex) : "dm " + selectedDmPeer;
        return "connection=" + active.connectionId() + " chat=" + chat;
    }

    private static String connectionSummary(ConnectionEntry entry) {
        String status = entry.isConnected() ? "connected" : entry.isReconnecting() ? "reconnecting" : "idle";
        return status + " | " + entry.getEffectiveProtocol() + " | " + switch (entry.getEffectiveType()) {
            case TCP -> "tcp " + safe(entry.getHost()) + ":" + entry.getPort();
            case SERIAL -> "serial " + safe(entry.getPortName()) + " @" + entry.getBaudRate();
            case BLE -> "ble " + safe(entry.getBleAddress());
        };
    }

    private void drainStatusMessages() {
        String message;
        while ((message = uiBridge.pollStatusMessage()) != null) {
            addActivity(message);
        }
    }

    private void addActivity(String line) {
        activity.add(TIME_FMT.format(Instant.now()) + " " + safe(line));
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

    private List<String> logbackLines(int maxLines, int width) {
        if (maxLines <= 0) {
            return List.of();
        }
        List<LogEntry> entries = UiLogAppender.getBuffer();
        if (entries.isEmpty()) {
            return List.of("No logback events");
        }

        int from = Math.max(0, entries.size() - Math.max(20, maxLines));
        List<String> lines = new ArrayList<>();
        for (LogEntry entry : entries.subList(from, entries.size())) {
            lines.addAll(logbackEntryLines(entry, width));
        }
        return tail(lines, maxLines);
    }

    private static List<String> logbackEntryLines(LogEntry entry, int width) {
        if (entry == null) {
            return List.of();
        }
        String prefix = "[" + safe(entry.getTime()) + "] " + safe(entry.getLevel()) + ": ";
        String fullMessage = entry.getFullMessage();
        if (fullMessage == null || fullMessage.isBlank()) {
            fullMessage = entry.getMessage();
        }
        String normalized = fullMessage != null ? fullMessage.replace("\r\n", "\n").replace('\r', '\n') : "";
        String[] rawLines = normalized.isEmpty() ? new String[]{""} : normalized.split("\n", -1);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < rawLines.length; i++) {
            String line = (i == 0 ? prefix : "    ") + rawLines[i];
            lines.addAll(wrap(line, width));
        }
        return lines;
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

    private static List<String> splitCommand(String command) {
        List<String> result = new ArrayList<>();
        for (String part : command.trim().split("\\s+")) {
            if (!part.isBlank()) {
                result.add(part);
            }
        }
        return result;
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

    private static List<String> wrap(String text, int width) {
        return TerminalText.wrap(text, width);
    }

    private static <T> List<T> tail(List<T> list, int maxItems) {
        if (list == null || list.isEmpty() || maxItems <= 0) {
            return List.of();
        }
        int from = Math.max(0, list.size() - maxItems);
        return new ArrayList<>(list.subList(from, list.size()));
    }

    private static String shortError(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private static boolean isMissingControllingTty(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("/dev/tty") || message.contains("Device not configured"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static TerminalSize initialTerminalSize() {
        int columns = readTerminalSizeEnv("COLUMNS", DEFAULT_TERMINAL_SIZE.getColumns());
        int rows = readTerminalSizeEnv("LINES", DEFAULT_TERMINAL_SIZE.getRows());
        return new TerminalSize(Math.max(40, columns), Math.max(12, rows));
    }

    private static int readTerminalSizeEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int leftPaneWidth(TerminalSize size) {
        return clamp(size.getColumns() / 3, 28, 44);
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String fit(String value, int width) {
        return TerminalText.fit(value, width);
    }

    private static String padRight(String value, int width) {
        return TerminalText.padRight(value, width);
    }

    private static String safe(String value) {
        return TerminalText.render(value);
    }

    private static void putString(TextGraphics g, int x, int y, String value, SGR modifier, SGR... modifiers) {
        EnumSet<SGR> previousModifiers = EnumSet.copyOf(g.getActiveModifiers());
        g.clearModifiers();
        g.enableModifiers(modifier);
        if (modifiers != null && modifiers.length > 0) {
            g.enableModifiers(modifiers);
        }
        putString(g, x, y, value);
        g.setModifiers(previousModifiers);
    }

    private static void putString(TextGraphics g, int x, int y, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        TerminalSize size = g.getSize();
        if (y < 0 || y >= size.getRows() || x >= size.getColumns()) {
            return;
        }

        String rendered = TerminalText.render(value);
        int column = x;
        for (int i = 0; i < rendered.length(); ) {
            int end = TerminalText.nextClusterEnd(rendered, i);
            String cluster = rendered.substring(i, end);
            int width = TerminalText.displayWidth(cluster);
            if (column + width <= 0) {
                column += width;
                i = end;
                continue;
            }
            if (column < 0 || column + width > size.getColumns()) {
                break;
            }
            for (TextCharacter character : TextCharacter.fromString(
                    cluster,
                    g.getForegroundColor(),
                    g.getBackgroundColor(),
                    EnumSet.copyOf(g.getActiveModifiers()))) {
                g.setCharacter(column, y, character);
                column += character.isDoubleWidth() ? 2 : 1;
            }
            i = end;
        }
    }

    private static void putChar(TextGraphics g, int x, int y, char ch) {
        g.setCharacter(x, y, ch);
    }

    private static final class Utf8UnixTerminal extends UnixTerminal {

        private Utf8UnixTerminal(InputStream input, OutputStream output, Charset charset) throws IOException {
            super(input, output, charset);
        }

        @Override
        public void putString(String string) throws IOException {
            if (string != null && !string.isEmpty()) {
                writeToTerminal(string.getBytes(getCharset()));
            }
        }
    }

    private static final class FallbackAnsiTerminal extends ANSITerminal {
        private final TerminalSize terminalSize;

        private FallbackAnsiTerminal(InputStream input,
                                     OutputStream output,
                                     Charset charset,
                                     TerminalSize terminalSize) {
            super(input, output, charset);
            this.terminalSize = terminalSize;
        }

        @Override
        protected TerminalSize findTerminalSize() {
            return terminalSize;
        }

        @Override
        public void putString(String string) throws IOException {
            if (string != null && !string.isEmpty()) {
                writeToTerminal(string.getBytes(getCharset()));
            }
        }
    }

    private record ActiveConnection(String connectionId,
                                    DeviceState state,
                                    ProtocolHandler handler,
                                    MeshCoreCompanionProtocolRuntime meshCore) {
    }
}
