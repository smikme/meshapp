package com.meshtastic.client.lua;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.MessageService;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.meshtastic.proto.ChannelProtos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class LuaRuntimeSession {

    private static final long RUN_TIMEOUT_MS = 3_000;
    private static final long CALLBACK_TIMEOUT_MS = 1_500;
    private static final long MAX_INSTRUCTIONS = 350_000;
    private static final int MAX_OUTPUT_CHARS = 64_000;
    private static final int MAX_MESSAGE_BATCH = 50;
    private static final int MAX_DEBUG_VARIABLES = 160;
    private static final int MAX_TABLE_PREVIEW_ITEMS = 8;
    private static final Set<String> HIDDEN_GLOBALS = Set.of(
            "_G", "_VERSION", "arg",
            "assert", "error", "getmetatable", "ipairs", "next", "pairs", "pcall", "rawequal",
            "rawget", "rawlen", "rawset", "select", "setmetatable", "tonumber", "tostring",
            "type", "xpcall",
            "bit32", "coroutine", "math", "mesh", "print", "string", "table"
    );

    private final LuaScript script;
    private final LuaScriptRuntimeService.RuntimeTarget target;
    private final LuaScriptService scriptService;
    private final MessageDbService messageDbService = MessageDbService.getInstance();
    private final Consumer<LuaScriptEvent> eventSink;
    private final Runnable onClosed;
    private final Set<Integer> breakpoints;
    private final boolean debugMode;
    private final Object debugLock = new Object();
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Long> lastSeenDbIds = new java.util.concurrent.ConcurrentHashMap<>();
    private final Runnable deviceMessageListener = this::onDeviceMessagesChanged;

    private SandboxDebugLib debugLib;
    private Globals globals;
    private Future<?> activeFuture;
    private volatile boolean listenerRegistered;
    private volatile int outputChars;
    private volatile boolean outputLimitReported;
    private volatile boolean paused;
    private volatile boolean pauseOnNextLine;
    private volatile DebugCommand debugCommand = DebugCommand.NONE;
    private volatile LuaDebugSnapshot debugSnapshot;
    private volatile int lastPausedLine = -1;

    LuaRuntimeSession(LuaScript script,
                      LuaScriptRuntimeService.RuntimeTarget target,
                      LuaScriptService scriptService,
                      Set<Integer> breakpoints,
                      boolean pauseOnStart,
                      Consumer<LuaScriptEvent> eventSink,
                      Runnable onClosed) {
        this.script = script;
        this.target = target;
        this.scriptService = scriptService;
        this.breakpoints = breakpoints != null ? Set.copyOf(breakpoints) : Set.of();
        this.pauseOnNextLine = pauseOnStart;
        this.debugMode = pauseOnStart || !this.breakpoints.isEmpty();
        this.eventSink = eventSink;
        this.onClosed = onClosed;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "lua-script-" + script.getId());
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        emit(LuaScriptEvent.started(script.getId(), "Запуск " + script.getName()));
        activeFuture = executor.submit(this::runInitialChunk);
    }

    boolean isRunning() {
        return running.get();
    }

    boolean isPaused() {
        return paused;
    }

    LuaDebugSnapshot debugSnapshot() {
        return debugSnapshot;
    }

    void debugContinue() {
        resumeDebuggee(DebugCommand.CONTINUE);
    }

    void debugStep() {
        resumeDebuggee(DebugCommand.STEP);
    }

    void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        resumeDebuggee(DebugCommand.CONTINUE);
        unregisterListener();
        Future<?> future = activeFuture;
        if (future != null) {
            future.cancel(true);
        }
        executor.shutdownNow();
        scriptService.updateRunState(script.getId(), "STOPPED", null);
        emit(LuaScriptEvent.stopped(script.getId(), "Скрипт остановлен"));
    }

    private void runInitialChunk() {
        boolean keepListening = false;
        try {
            debugLib = new SandboxDebugLib(script.getId(), this::handleDebugLine);
            globals = LuaScriptRuntimeService.createSandboxGlobals(debugLib);
            debugLib.arm(globals, debugMode);
            installApi(globals);

            debugLib.begin(RUN_TIMEOUT_MS);
            globals.load(script.getCode() != null ? script.getCode() : "", script.getName()).call();

            LuaValue onMessage = globals.get("on_message");
            if (onMessage.isfunction()) {
                keepListening = attachMessageListener();
            } else {
                emit(LuaScriptEvent.info(script.getId(), "Выполнено: on_message не объявлен, скрипт завершен"));
                scriptService.updateRunState(script.getId(), "FINISHED", null);
            }
        } catch (Throwable error) {
            fail(error);
        } finally {
            if (!keepListening) {
                finishWithoutInterruptingExecutor();
            }
        }
    }

    private boolean attachMessageListener() {
        if (target.state() == null) {
            emit(LuaScriptEvent.warning(script.getId(), "on_message объявлен, но нет активного подключения"));
            scriptService.updateRunState(script.getId(), "FINISHED", "No active connection for on_message");
            return false;
        }
        initializeMessageCursors();
        target.state().addMessageListener(deviceMessageListener);
        listenerRegistered = true;
        scriptService.updateRunState(script.getId(), "RUNNING", null);
        emit(LuaScriptEvent.info(script.getId(), "Ожидание новых сообщений"));
        return true;
    }

    private void onDeviceMessagesChanged() {
        if (!running.get()) {
            return;
        }
        try {
            executor.execute(this::deliverNewMessages);
        } catch (RejectedExecutionException ignored) {
            // Session is stopping.
        }
    }

    private void deliverNewMessages() {
        if (!running.get() || globals == null) {
            return;
        }
        LuaValue onMessage = globals.get("on_message");
        if (!onMessage.isfunction()) {
            return;
        }
        try {
            for (ChatScope scope : knownChatScopes()) {
                long lastSeen = lastSeenDbIds.getOrDefault(scope.key(), 0L);
                List<MeshMessage> messages = messageDbService.loadAfter(
                        scope.chatType(),
                        scope.chatKey(),
                        lastSeen,
                        MAX_MESSAGE_BATCH,
                        target.ownerNodeIdOrEmpty());
                for (MeshMessage message : messages) {
                    if (message.getDbId() > 0) {
                        lastSeenDbIds.put(scope.key(), message.getDbId());
                    }
                    debugLib.begin(CALLBACK_TIMEOUT_MS);
                    onMessage.call(messageToTable(message, scope.chatType(), scope.chatKey()));
                }
            }
        } catch (Throwable error) {
            fail(error);
            finishWithoutInterruptingExecutor();
        }
    }

    private void handleDebugLine(int line) {
        if (!running.get() || line <= 0) {
            return;
        }
        boolean breakpointHit = breakpoints.contains(line);
        boolean stepHit = pauseOnNextLine && line != lastPausedLine;
        if (!debugMode || (!breakpointHit && !stepHit)) {
            return;
        }

        pauseOnNextLine = false;
        lastPausedLine = line;
        String reason = breakpointHit ? "breakpoint" : "step";
        debugSnapshot = createDebugSnapshot(line, reason);
        synchronized (debugLock) {
            paused = true;
            debugCommand = DebugCommand.NONE;
        }
        emit(LuaScriptEvent.debugPaused(script.getId(), "Пауза на строке " + line + " (" + reason + ")"));

        synchronized (debugLock) {
            while (running.get() && debugCommand == DebugCommand.NONE) {
                try {
                    debugLock.wait(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running.set(false);
                    break;
                }
            }
            paused = false;
            if (!running.get()) {
                throw new LuaError("Lua script stopped");
            }
            pauseOnNextLine = debugCommand == DebugCommand.STEP;
            debugCommand = DebugCommand.NONE;
            if (debugLib != null) {
                debugLib.deferDeadline();
            }
        }
        emit(LuaScriptEvent.debugResumed(script.getId(), "Продолжение выполнения"));
    }

    private void resumeDebuggee(DebugCommand command) {
        synchronized (debugLock) {
            debugCommand = command;
            debugLock.notifyAll();
        }
    }

    private LuaDebugSnapshot createDebugSnapshot(int line, String reason) {
        List<LuaDebugVariable> variables = new ArrayList<>();
        collectLocalsAndUpvalues(variables);
        collectUserGlobals(variables);
        variables.sort(Comparator.comparingInt(LuaRuntimeSession::debugScopeRank)
                .thenComparing(LuaDebugVariable::name));
        return new LuaDebugSnapshot(script.getId(), line, reason, variables);
    }

    private void collectLocalsAndUpvalues(List<LuaDebugVariable> variables) {
        LuaValue debugTable = debugLib != null ? debugLib.debugTable() : LuaValue.NIL;
        if (!debugTable.istable()) {
            return;
        }

        int level = firstLevelWithLocals(debugTable);
        if (level <= 0) {
            level = 1;
        }
        collectLocals(debugTable, level, variables);
        collectUpvalues(debugTable, level, variables);
    }

    private int firstLevelWithLocals(LuaValue debugTable) {
        for (int level = 1; level <= 5; level++) {
            Varargs local = debugTable.get("getlocal").invoke(LuaValue.varargsOf(
                    LuaValue.valueOf(level),
                    LuaValue.ONE));
            if (!local.arg1().isnil()) {
                return level;
            }
        }
        return -1;
    }

    private void collectLocals(LuaValue debugTable, int level, List<LuaDebugVariable> variables) {
        LuaValue getLocal = debugTable.get("getlocal");
        for (int i = 1; i <= MAX_DEBUG_VARIABLES && variables.size() < MAX_DEBUG_VARIABLES; i++) {
            Varargs local = getLocal.invoke(LuaValue.varargsOf(LuaValue.valueOf(level), LuaValue.valueOf(i)));
            LuaValue nameValue = local.arg1();
            if (nameValue.isnil()) {
                break;
            }
            String name = nameValue.tojstring();
            if (name == null || name.isBlank() || name.startsWith("(")) {
                continue;
            }
            variables.add(new LuaDebugVariable("local", name, valueSummary(local.arg(2), 0)));
        }
    }

    private void collectUpvalues(LuaValue debugTable, int level, List<LuaDebugVariable> variables) {
        LuaValue getInfo = debugTable.get("getinfo");
        LuaValue getUpvalue = debugTable.get("getupvalue");
        Varargs infoArgs = LuaValue.varargsOf(LuaValue.valueOf(level), LuaValue.valueOf("f"));
        LuaValue info = getInfo.invoke(infoArgs).arg1();
        LuaValue function = info.istable() ? info.get("func") : LuaValue.NIL;
        if (!function.isfunction()) {
            return;
        }
        for (int i = 1; i <= MAX_DEBUG_VARIABLES && variables.size() < MAX_DEBUG_VARIABLES; i++) {
            Varargs upvalue = getUpvalue.invoke(LuaValue.varargsOf(function, LuaValue.valueOf(i)));
            LuaValue nameValue = upvalue.arg1();
            if (nameValue.isnil()) {
                break;
            }
            String name = nameValue.tojstring();
            if (name == null || name.isBlank() || "_ENV".equals(name)) {
                continue;
            }
            variables.add(new LuaDebugVariable("upvalue", name, valueSummary(upvalue.arg(2), 0)));
        }
    }

    private void collectUserGlobals(List<LuaDebugVariable> variables) {
        if (globals == null) {
            return;
        }
        LuaValue key = LuaValue.NIL;
        while (variables.size() < MAX_DEBUG_VARIABLES) {
            Varargs next = globals.next(key);
            key = next.arg1();
            if (key.isnil()) {
                break;
            }
            if (!key.isstring()) {
                continue;
            }
            String name = key.tojstring();
            if (name == null || name.isBlank() || HIDDEN_GLOBALS.contains(name)) {
                continue;
            }
            variables.add(new LuaDebugVariable("global", name, valueSummary(next.arg(2), 0)));
        }
    }

    private static int debugScopeRank(LuaDebugVariable variable) {
        return switch (variable.scope()) {
            case "local" -> 0;
            case "upvalue" -> 1;
            case "global" -> 2;
            default -> 3;
        };
    }

    private String valueSummary(LuaValue value, int depth) {
        if (value == null || value.isnil()) {
            return "nil";
        }
        if (value.isboolean()) {
            return String.valueOf(value.toboolean());
        }
        if (value.isnumber()) {
            return value.tojstring();
        }
        if (value.isstring()) {
            String raw = value.tojstring();
            return "\"" + truncate(raw, 120) + "\"";
        }
        if (value.istable()) {
            if (depth >= 1) {
                return "{...}";
            }
            List<String> pairs = new ArrayList<>();
            LuaValue key = LuaValue.NIL;
            for (int i = 0; i < MAX_TABLE_PREVIEW_ITEMS; i++) {
                Varargs next = value.next(key);
                key = next.arg1();
                if (key.isnil()) {
                    break;
                }
                pairs.add(valueSummary(key, depth + 1) + "=" + valueSummary(next.arg(2), depth + 1));
            }
            return "{" + String.join(", ", pairs) + (pairs.size() == MAX_TABLE_PREVIEW_ITEMS ? ", ..." : "") + "}";
        }
        if (value.isfunction()) {
            return "function";
        }
        if (value.isthread()) {
            return "thread";
        }
        if (value.isuserdata()) {
            return "userdata";
        }
        return truncate(value.tojstring(), 160);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private void initializeMessageCursors() {
        for (ChatScope scope : knownChatScopes()) {
            List<MeshMessage> latest = messageDbService.loadLast(
                    scope.chatType(),
                    scope.chatKey(),
                    1,
                    target.ownerNodeIdOrEmpty());
            long lastId = latest.isEmpty() ? 0 : latest.getLast().getDbId();
            lastSeenDbIds.put(scope.key(), lastId);
        }
    }

    private Set<ChatScope> knownChatScopes() {
        Set<ChatScope> result = new LinkedHashSet<>();
        DeviceState state = target.state();
        if (state != null) {
            result.add(new ChatScope("channel", "0"));
            for (ChannelProtos.Channel channel : state.getChannels()) {
                result.add(new ChatScope("channel", String.valueOf(channel.getIndex())));
            }
            result.addAll(state.getAllDirectMessages().keySet().stream()
                    .map(peer -> new ChatScope("dm", peer))
                    .toList());
        }
        result.addAll(messageDbService.getDistinctDmPeers(target.ownerNodeIdOrEmpty()).stream()
                .map(peer -> new ChatScope("dm", peer))
                .toList());
        return result;
    }

    private void installApi(Globals targetGlobals) {
        targetGlobals.set("print", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                emitOutput(joinArgs(args));
                return LuaValue.NONE;
            }
        });

        LuaTable mesh = new LuaTable();
        mesh.set("log", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                emitOutput(arg.tojstring());
                return LuaValue.NIL;
            }
        });
        mesh.set("now", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(System.currentTimeMillis() / 1000.0);
            }
        });
        mesh.set("owner", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return ownerTable();
            }
        });
        mesh.set("chat", chatApi());
        mesh.set("kv", kvApi());
        targetGlobals.set("mesh", mesh);
    }

    private LuaTable chatApi() {
        LuaTable chat = new LuaTable();
        chat.set("send_channel", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue channelArg, LuaValue textArg) {
                requireChatTransport();
                int channel = channelArg.checkint();
                String text = textArg.checkjstring();
                MeshMessage sent = target.meshCoreRuntime() != null
                        ? target.meshCoreRuntime().sendChannelMessage(channel, text, 0)
                        : MessageService.sendChannelMessage(target.handler(), target.state(), channel, text, 0);
                return sent != null ? messageToTable(sent, "channel", String.valueOf(channel)) : LuaValue.NIL;
            }
        });
        chat.set("send_dm", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue peerArg, LuaValue textArg) {
                requireChatTransport();
                String peerNodeId = peerArg.checkjstring();
                String text = textArg.checkjstring();
                MeshMessage sent = target.meshCoreRuntime() != null
                        ? target.meshCoreRuntime().sendDirectMessage(peerNodeId, text, 0)
                        : MessageService.sendDirectMessage(target.handler(), target.state(), peerNodeId, text, 0);
                return sent != null ? messageToTable(sent, "dm", peerNodeId) : LuaValue.NIL;
            }
        });
        chat.set("recent", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String chatType = args.checkjstring(1);
                String chatKey = args.checkjstring(2);
                int limit = Math.max(1, Math.min(200, args.optint(3, 20)));
                LuaTable table = new LuaTable();
                List<MeshMessage> messages = messageDbService.loadLast(
                        chatType,
                        chatKey,
                        limit,
                        target.ownerNodeIdOrEmpty());
                for (int i = 0; i < messages.size(); i++) {
                    table.set(i + 1, messageToTable(messages.get(i), chatType, chatKey));
                }
                return table;
            }
        });
        chat.set("nodes", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable table = new LuaTable();
                if (target.state() == null) {
                    return table;
                }
                List<NodeData> nodes = new ArrayList<>(target.state().getNodeDb().values());
                nodes.sort(java.util.Comparator.comparing(NodeData::getNodeId, java.util.Comparator.nullsLast(String::compareTo)));
                for (int i = 0; i < nodes.size(); i++) {
                    table.set(i + 1, nodeToTable(nodes.get(i)));
                }
                return table;
            }
        });
        chat.set("channels", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable table = new LuaTable();
                if (target.state() == null) {
                    return table;
                }
                List<ChannelProtos.Channel> channels = target.state().getChannels();
                for (int i = 0; i < channels.size(); i++) {
                    table.set(i + 1, channelToTable(channels.get(i)));
                }
                return table;
            }
        });
        return chat;
    }

    private LuaTable kvApi() {
        LuaTable kv = new LuaTable();
        kv.set("get", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue keyArg) {
                String value = scriptService.getKv(script.getId(), keyArg.checkjstring());
                return value != null ? LuaValue.valueOf(value) : LuaValue.NIL;
            }
        });
        kv.set("set", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue keyArg, LuaValue valueArg) {
                scriptService.setKv(script.getId(), keyArg.checkjstring(), valueArg.isnil() ? null : valueArg.tojstring());
                return LuaValue.TRUE;
            }
        });
        kv.set("delete", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue keyArg) {
                return LuaValue.valueOf(scriptService.deleteKv(script.getId(), keyArg.checkjstring()));
            }
        });
        kv.set("list", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable table = new LuaTable();
                for (Map.Entry<String, String> entry : scriptService.listKv(script.getId()).entrySet()) {
                    table.set(entry.getKey(), entry.getValue() != null ? LuaValue.valueOf(entry.getValue()) : LuaValue.NIL);
                }
                return table;
            }
        });
        kv.set("clear", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                scriptService.clearKv(script.getId());
                return LuaValue.TRUE;
            }
        });
        return kv;
    }

    private LuaTable ownerTable() {
        LuaTable table = new LuaTable();
        table.set("node_id", stringOrNil(target.ownerNodeId()));
        if (target.state() != null) {
            table.set("node_num", LuaValue.valueOf(target.state().getMyNodeNum()));
        }
        table.set("connection_id", stringOrNil(target.connectionId()));
        return table;
    }

    private LuaTable messageToTable(MeshMessage message, String chatType, String chatKey) {
        LuaTable table = new LuaTable();
        table.set("db_id", LuaValue.valueOf(message.getDbId()));
        table.set("packet_id", LuaValue.valueOf(message.getPacketId()));
        table.set("chat_type", stringOrNil(chatType));
        table.set("chat_key", stringOrNil(chatKey));
        table.set("from", stringOrNil(message.getFromNodeId()));
        table.set("to", stringOrNil(message.getToNodeId()));
        table.set("channel", LuaValue.valueOf(message.getChannelIndex()));
        table.set("text", stringOrNil(message.getText()));
        table.set("timestamp", LuaValue.valueOf(message.getTimestamp()));
        table.set("outgoing", LuaValue.valueOf(message.isOutgoing()));
        table.set("status", stringOrNil(message.getStatus() != null ? message.getStatus().name() : null));
        table.set("sender_name", stringOrNil(message.getSenderName()));
        table.set("rx_rssi", LuaValue.valueOf(message.getRxRssi()));
        table.set("rx_snr", LuaValue.valueOf(message.getRxSnr()));
        return table;
    }

    private LuaTable nodeToTable(NodeData node) {
        LuaTable table = new LuaTable();
        table.set("node_num", LuaValue.valueOf(node.getNodeNum()));
        table.set("node_id", stringOrNil(node.getNodeId()));
        table.set("long_name", stringOrNil(node.getLongName()));
        table.set("short_name", stringOrNil(node.getShortName()));
        table.set("last_heard", LuaValue.valueOf(node.getLastHeard()));
        table.set("battery", LuaValue.valueOf(node.getBatteryLevel()));
        table.set("hops_away", node.hasHopsAway() ? LuaValue.valueOf(node.getHopsAway()) : LuaValue.NIL);
        table.set("role", stringOrNil(node.getRole()));
        table.set("hw_model", stringOrNil(node.getHwModel()));
        table.set("unmessagable", LuaValue.valueOf(node.isUnmessagable()));
        return table;
    }

    private LuaTable channelToTable(ChannelProtos.Channel channel) {
        LuaTable table = new LuaTable();
        table.set("index", LuaValue.valueOf(channel.getIndex()));
        table.set("role", stringOrNil(channel.getRole().name()));
        if (channel.hasSettings()) {
            table.set("name", stringOrNil(channel.getSettings().getName()));
        }
        return table;
    }

    private void requireChatTransport() {
        if (!target.hasChatTransport()) {
            throw new LuaError("No active chat connection");
        }
    }

    private LuaValue stringOrNil(String value) {
        return value == null ? LuaValue.NIL : LuaValue.valueOf(value);
    }

    private String joinArgs(Varargs args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= args.narg(); i++) {
            if (i > 1) {
                sb.append('\t');
            }
            sb.append(args.arg(i).tojstring());
        }
        return sb.toString();
    }

    private void emitOutput(String message) {
        if (message == null) {
            message = "";
        }
        if (outputChars >= MAX_OUTPUT_CHARS) {
            if (!outputLimitReported) {
                outputLimitReported = true;
                emit(LuaScriptEvent.warning(script.getId(), "Лимит вывода скрипта исчерпан"));
            }
            return;
        }
        outputChars += message.length();
        emit(LuaScriptEvent.output(script.getId(), message));
    }

    private void fail(Throwable error) {
        if (!running.get()) {
            return;
        }
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        scriptService.updateRunState(script.getId(), "FAILED", message);
        emit(LuaScriptEvent.error(script.getId(), message, error));
    }

    private void finishWithoutInterruptingExecutor() {
        unregisterListener();
        running.set(false);
        executor.shutdown();
        onClosed.run();
    }

    private void unregisterListener() {
        if (listenerRegistered && target.state() != null) {
            target.state().removeMessageListener(deviceMessageListener);
        }
        listenerRegistered = false;
    }

    private void emit(LuaScriptEvent event) {
        if (eventSink != null) {
            eventSink.accept(event);
        }
    }

    private record ChatScope(String chatType, String chatKey) {
        String key() {
            return chatType + ":" + chatKey;
        }
    }

    private enum DebugCommand {
        NONE,
        CONTINUE,
        STEP
    }

    private static final class SandboxDebugLib extends DebugLib {

        private final long scriptId;
        private final java.util.function.IntConsumer lineHandler;
        private long deadlineNanos;
        private long instructionCount;
        private LuaValue debugTable;

        private SandboxDebugLib(long scriptId, java.util.function.IntConsumer lineHandler) {
            this.scriptId = scriptId;
            this.lineHandler = lineHandler;
        }

        @Override
        public LuaValue call(LuaValue modname, LuaValue env) {
            debugTable = super.call(modname, env);
            return debugTable;
        }

        private LuaValue debugTable() {
            return debugTable != null ? debugTable : LuaValue.NIL;
        }

        private void arm(Globals globals, boolean lineHookEnabled) {
            if (!lineHookEnabled || globals == null || globals.running == null || globals.running.state == null) {
                return;
            }
            globals.running.state.hookfunc = new TwoArgFunction() {
                @Override
                public LuaValue call(LuaValue type, LuaValue arg) {
                    if ("line".equals(type.tojstring())) {
                        lineHandler.accept(arg.toint());
                    }
                    return LuaValue.NIL;
                }
            };
            globals.running.state.hookline = true;
            globals.running.state.lastline = -1;
        }

        private void begin(long timeoutMs) {
            this.deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
            this.instructionCount = 0;
        }

        private void deferDeadline() {
            this.deadlineNanos = System.nanoTime() + RUN_TIMEOUT_MS * 1_000_000L;
        }

        @Override
        public void onInstruction(int pc, Varargs v, int top) {
            super.onInstruction(pc, v, top);
            instructionCount++;
            if (instructionCount > MAX_INSTRUCTIONS) {
                throw new LuaError("Lua script " + scriptId + " exceeded instruction limit");
            }
            if (System.nanoTime() > deadlineNanos || Thread.currentThread().isInterrupted()) {
                throw new LuaError("Lua script " + scriptId + " exceeded execution time limit");
            }
        }
    }
}
