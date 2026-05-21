package com.meshtastic.client.lua;

import com.meshtastic.client.lua.api.LuaSandboxApi;
import com.meshtastic.client.lua.api.LuaSandboxContext;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.service.MessageDbService;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.TwoArgFunction;
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

/**
 * Изолированная сессия выполнения одного Lua-скрипта MeshApp.
 * <p>
 * Отвечает за запуск кода в песочнице LuaJ, доставку новых сообщений
 * в {@code on_message(msg)}, отладочные хуки и лимиты выполнения.
 * Разрешенные расширения {@code mesh.*} устанавливаются через отдельный
 * namespace {@link LuaSandboxApi}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
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
    private LuaSandboxApi sandboxApi;
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
            sandboxApi = new LuaSandboxApi(new LuaSandboxContext(
                    script.getId(),
                    target.connectionId(),
                    target.state(),
                    target.handler(),
                    target.meshCoreRuntime(),
                    target.ownerNodeId(),
                    scriptService,
                    this::emitOutput));
            sandboxApi.install(globals);

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
                    onMessage.call(sandboxApi.mapper().messageToTable(message, scope.chatType(), scope.chatKey()));
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

    /**
     * Область чата, которую Lua-скрипт должен просматривать для новых сообщений.
     *
     * @param chatType тип чата ({@code channel} или {@code dm})
     * @param chatKey  ключ чата
     */
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
