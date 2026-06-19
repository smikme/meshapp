package com.meshtastic.client.lua;

import com.meshtastic.client.components.LuaCanvasWindow;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.lua.api.LuaSandboxApi;
import com.meshtastic.client.lua.api.LuaSandboxContext;
import com.meshtastic.client.lua.api.LuaProtobufMapper;
import com.meshtastic.client.lua.api.LuaValueMapper;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.service.RemoteAdminService;
import com.meshtastic.client.service.RemoteAdminSession;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.TwoArgFunction;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.meshtastic.proto.MeshProtos;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Isolated execution session for a single MeshApp Lua script.
 * <p>
 * Runs code inside a LuaJ sandbox, delivers new messages to
 * {@code on_message(msg)}, manages debug hooks, and enforces execution limits.
 * Approved {@code mesh.*} extensions are installed through the separate
 * {@link LuaSandboxApi} namespace.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class LuaRuntimeSession implements LuaUiBridge, LuaTracerouteBridge, LuaNodeInfoBridge,
        LuaRemoteAdminBridge, LuaTimerBridge, LuaCanvasBridge, LuaFormBridge {

    private static final long RUN_TIMEOUT_MS = 3_000;
    private static final long CALLBACK_TIMEOUT_MS = 1_500;
    private static final long MAX_INSTRUCTIONS = 350_000;
    private static final int MAX_OUTPUT_CHARS = 64_000;
    private static final int MAX_MESSAGE_BATCH = 50;
    private static final int MAX_DEBUG_VARIABLES = 160;
    private static final int MAX_TABLE_PREVIEW_ITEMS = 8;
    private static final int MAX_TIMERS = 32;
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
    private final LuaProtobufMapper protobufMapper = new LuaProtobufMapper();
    private final Consumer<LuaScriptEvent> eventSink;
    private final Consumer<LuaUiNodePickRequest> uiNodePickSink;
    private final LuaFormBridge formBridge;
    private final Runnable onClosed;
    private final Set<Integer> breakpoints;
    private final LuaAutomationCommand command;
    private final boolean debugMode;
    private final Object debugLock = new Object();
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Long> lastSeenDbIds = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> pendingUiRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, PendingTraceroute> pendingTraceroutes = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, PendingNodeInfo> pendingNodeInfos = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, PendingRemoteAdmin> pendingRemoteAdmins = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, PendingTimer> pendingTimers = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Integer, RemoteAdminService> remoteAdminServices = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean canvasOpen = new AtomicBoolean(false);
    private final AtomicLong uiRequestCounter = new AtomicLong();
    private final AtomicLong tracerouteRequestCounter = new AtomicLong();
    private final AtomicLong nodeInfoRequestCounter = new AtomicLong();
    private final AtomicLong remoteAdminRequestCounter = new AtomicLong();
    private final AtomicLong timerRequestCounter = new AtomicLong();
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
                      LuaAutomationCommand command,
                      Consumer<LuaScriptEvent> eventSink,
                      Consumer<LuaUiNodePickRequest> uiNodePickSink,
                      LuaFormBridge formBridge,
                      Runnable onClosed) {
        this.script = script;
        this.target = target;
        this.scriptService = scriptService;
        this.breakpoints = breakpoints != null ? Set.copyOf(breakpoints) : Set.of();
        this.pauseOnNextLine = pauseOnStart;
        this.debugMode = pauseOnStart || !this.breakpoints.isEmpty();
        this.command = command;
        this.eventSink = eventSink;
        this.uiNodePickSink = uiNodePickSink;
        this.formBridge = formBridge;
        this.onClosed = onClosed;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "lua-script-" + script.getId());
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "lua-script-timer-" + script.getId());
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        emit(LuaScriptEvent.started(script.getId(), I18n.t("meshIde.runtime.start", script.getName())));
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

    void deliverNodeSelection(LuaUiNodeSelection selection) {
        if (selection == null || !running.get()) {
            return;
        }
        try {
            executor.execute(() -> callNodeSelected(selection));
        } catch (RejectedExecutionException ignored) {
            // Session is stopping.
        }
    }

    void deliverFormEvent(LuaFormEvent event) {
        if (event == null || !running.get()) {
            return;
        }
        try {
            executor.execute(() -> callFormEvent(event));
        } catch (RejectedExecutionException ignored) {
            // Session is stopping.
        }
    }

    @Override
    public boolean isAvailable() {
        return uiNodePickSink != null;
    }

    @Override
    public String nextRequestId() {
        return script.getId() + ":" + uiRequestCounter.incrementAndGet();
    }

    @Override
    public void requestNodePick(LuaUiNodePickRequest request) {
        if (request == null || uiNodePickSink == null) {
            throw new LuaError("No active UI context");
        }
        pendingUiRequests.add(request.requestId());
        scriptService.updateRunState(script.getId(), "RUNNING", null);
        uiNodePickSink.accept(request);
    }

    @Override
    public void showBotNotice(LuaUiBotNotice notice) {
        if (notice == null || uiNodePickSink == null) {
            throw new LuaError("No active UI context");
        }
        emit(LuaScriptEvent.uiBotNotice(script.getId(), notice));
    }

    @Override
    public boolean isTracerouteAvailable() {
        return target.state() != null && target.handler() != null;
    }

    @Override
    public String nextTracerouteRequestId() {
        return script.getId() + ":traceroute:" + tracerouteRequestCounter.incrementAndGet();
    }

    @Override
    public void requestTraceroute(LuaTracerouteRequest request) {
        if (request == null || target.state() == null || target.handler() == null) {
            throw new LuaError("Traceroute is not available");
        }
        if (request.targetNodeNum() == 0) {
            throw new LuaError("target node_num is required");
        }

        PendingTraceroute pending = new PendingTraceroute(request);
        pending.listener = (fromNodeNum, route) -> {
            if (matchesTracerouteResponse(request, fromNodeNum)) {
                completeTraceroute(request.requestId(), fromNodeNum, "ok", route, null);
            }
        };
        pendingTraceroutes.put(request.requestId(), pending);
        target.state().addTracerouteListener(pending.listener);
        pending.timeoutFuture = scheduler.schedule(
                () -> completeTraceroute(request.requestId(), 0, "timeout", null, null),
                Math.max(1, request.timeoutSeconds()),
                TimeUnit.SECONDS);
        scriptService.updateRunState(script.getId(), "RUNNING", null);
        try {
            MessageService.requestTraceroute(target.handler(), target.state(), request.targetNodeNum());
        } catch (Throwable error) {
            completeTraceroute(request.requestId(), 0, "error", null,
                    error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
        }
    }

    @Override
    public boolean isNodeInfoAvailable() {
        return target.state() != null && (target.handler() != null || target.meshCoreRuntime() != null);
    }

    @Override
    public String nextNodeInfoRequestId() {
        return script.getId() + ":nodeinfo:" + nodeInfoRequestCounter.incrementAndGet();
    }

    @Override
    public void requestNodeInfo(LuaNodeInfoRequest request) {
        if (request == null || target.state() == null) {
            throw new LuaError("NodeInfo is not available");
        }
        if (request.targetNodeNum() == 0) {
            throw new LuaError("target node_num is required");
        }
        if (target.handler() == null) {
            completeNodeInfoImmediate(request);
            return;
        }

        PendingNodeInfo pending = new PendingNodeInfo(request);
        pending.listener = nodeNum -> {
            if (nodeNum == request.targetNodeNum()) {
                completeNodeInfo(request.requestId(), "ok", target.state().getNodeDb().get(nodeNum), null);
            }
        };
        pendingNodeInfos.put(request.requestId(), pending);
        target.state().addNodeUpdateListener(pending.listener);
        pending.timeoutFuture = scheduler.schedule(
                () -> completeNodeInfo(request.requestId(), "timeout", cachedNode(request.targetNodeNum()), null),
                Math.max(1, request.timeoutSeconds()),
                TimeUnit.SECONDS);
        scriptService.updateRunState(script.getId(), "RUNNING", null);
        try {
            MessageService.requestNodeInfo(target.handler(), target.state(), request.targetNodeNum());
        } catch (Throwable error) {
            completeNodeInfo(request.requestId(), "error", cachedNode(request.targetNodeNum()),
                    error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
        }
    }

    @Override
    public boolean isRemoteAdminAvailable() {
        return target.state() != null && target.handler() != null;
    }

    @Override
    public String nextRemoteAdminRequestId() {
        return script.getId() + ":admin:" + remoteAdminRequestCounter.incrementAndGet();
    }

    @Override
    public void requestRemoteAdmin(LuaRemoteAdminRequest request) {
        if (request == null || target.state() == null || target.handler() == null) {
            throw new LuaError("Remote admin is not available");
        }
        if (request.targetNodeNum() == 0) {
            throw new LuaError("target node_num is required");
        }
        PendingRemoteAdmin pending = new PendingRemoteAdmin(request);
        pendingRemoteAdmins.put(request.requestId(), pending);
        scriptService.updateRunState(script.getId(), "RUNNING", null);
        CompletableFuture<RemoteAdminSession> future;
        try {
            future = startRemoteAdminOperation(request);
        } catch (Throwable error) {
            completeRemoteAdmin(request.requestId(), "error", error, remoteAdminSession(request.targetNodeNum()));
            return;
        }
        future.whenComplete((session, error) -> completeRemoteAdmin(
                request.requestId(),
                error == null ? "ok" : statusForError(error),
                error,
                session != null ? session : remoteAdminSession(request.targetNodeNum())));
    }

    private CompletableFuture<RemoteAdminSession> startRemoteAdminOperation(LuaRemoteAdminRequest request) {
        RemoteAdminService service = remoteAdminService(request);
        return switch (request.action()) {
            case LOAD_CONFIG -> service.loadSnapshot(progress -> callRemoteAdminProgress(request, progress));
            case REQUEST_CONFIG -> service.requestConfigSection(request.configType()).thenApply(ignored -> service.session());
            case REQUEST_MODULE_CONFIG -> service.requestModuleConfigSection(request.moduleConfigType())
                    .thenApply(ignored -> service.session());
            case SAVE_CONFIG -> saveRemoteConfig(service, request).thenApply(ignored -> service.session());
            case REFRESH_STATUS -> service.refreshConnectionStatus().thenApply(ignored -> service.session());
            case REBOOT -> service.reboot(request.delaySeconds()).thenApply(ignored -> service.session());
            case SHUTDOWN -> service.shutdown(request.delaySeconds()).thenApply(ignored -> service.session());
            case SYNC_TIME -> service.syncTime(request.epochSeconds()).thenApply(ignored -> service.session());
            case BACKUP -> service.backupPreferences(request.backupLocation()).thenApply(ignored -> service.session());
            case RESTORE -> service.restorePreferences(request.backupLocation()).thenApply(ignored -> service.session());
            case REMOVE_BACKUP -> service.removeBackupPreferences(request.backupLocation()).thenApply(ignored -> service.session());
            case RESET_NODEDB -> service.resetNodeDb(request.preserveFavorites()).thenApply(ignored -> service.session());
            case FACTORY_RESET_CONFIG -> service.factoryResetConfig().thenApply(ignored -> service.session());
            case FACTORY_RESET_DEVICE -> service.factoryResetDevice().thenApply(ignored -> service.session());
            case ENTER_DFU_MODE -> service.enterDfuMode().thenApply(ignored -> service.session());
            case SET_OWNER -> service.saveOwner(request.longName(), request.shortName(), request.licensed())
                    .thenApply(ignored -> service.session());
            case SET_FIXED_POSITION -> service.setFixedPosition(
                    request.latitude(),
                    request.longitude(),
                    request.altitude()).thenApply(ignored -> service.session());
            case REMOVE_FIXED_POSITION -> service.removeFixedPosition().thenApply(ignored -> service.session());
            case SET_RINGTONE -> service.setRingtone(request.ringtone()).thenApply(ignored -> service.session());
            case SET_CANNED_MESSAGES -> service.setCannedMessages(request.cannedMessages())
                    .thenApply(ignored -> service.session());
        };
    }

    private CompletableFuture<Void> saveRemoteConfig(RemoteAdminService service, LuaRemoteAdminRequest request) {
        List<ConfigProtos.Config> configs = buildConfigs(service.session(), request);
        List<ModuleConfigProtos.ModuleConfig> moduleConfigs = buildModuleConfigs(service.session(), request);
        List<ChannelProtos.Channel> channels = buildChannels(service.session(), request);

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        if (request.ownerSet()) {
            future = future.thenCompose(ignored -> service.saveOwner(
                    request.longName(),
                    request.shortName(),
                    request.licensed()));
        }
        if (request.removePosition()) {
            future = future.thenCompose(ignored -> service.removeFixedPosition());
        } else if (request.positionSet()) {
            future = future.thenCompose(ignored -> service.setFixedPosition(
                    request.latitude(),
                    request.longitude(),
                    request.altitude()));
        }
        if (request.ringtoneSet()) {
            future = future.thenCompose(ignored -> service.setRingtone(request.ringtone()));
        }
        if (request.cannedMessagesSet()) {
            future = future.thenCompose(ignored -> service.setCannedMessages(request.cannedMessages()));
        }
        if (!configs.isEmpty() || !moduleConfigs.isEmpty() || !channels.isEmpty()) {
            future = future.thenCompose(ignored -> service.saveConfigChanges(configs, moduleConfigs, channels));
        }
        return future;
    }

    private List<ConfigProtos.Config> buildConfigs(RemoteAdminSession session, LuaRemoteAdminRequest request) {
        if (request.configs() == null || request.configs().isEmpty()) {
            return List.of();
        }
        List<ConfigProtos.Config> configs = new ArrayList<>();
        for (LuaRemoteAdminRequest.MessagePatch patch : request.configs()) {
            configs.add(protobufMapper.buildConfig(
                    patch.section(),
                    patch.values(),
                    findConfig(session, patch.section()),
                    request.replace()));
        }
        return configs;
    }

    private List<ModuleConfigProtos.ModuleConfig> buildModuleConfigs(RemoteAdminSession session,
                                                                     LuaRemoteAdminRequest request) {
        if (request.moduleConfigs() == null || request.moduleConfigs().isEmpty()) {
            return List.of();
        }
        List<ModuleConfigProtos.ModuleConfig> moduleConfigs = new ArrayList<>();
        for (LuaRemoteAdminRequest.MessagePatch patch : request.moduleConfigs()) {
            moduleConfigs.add(protobufMapper.buildModuleConfig(
                    patch.section(),
                    patch.values(),
                    findModuleConfig(session, patch.section()),
                    request.replace()));
        }
        return moduleConfigs;
    }

    private List<ChannelProtos.Channel> buildChannels(RemoteAdminSession session, LuaRemoteAdminRequest request) {
        if (request.channels() == null || request.channels().isEmpty()) {
            return List.of();
        }
        List<ChannelProtos.Channel> channels = new ArrayList<>();
        for (LuaRemoteAdminRequest.MessagePatch patch : request.channels()) {
            Object index = patch.values().get("index");
            ChannelProtos.Channel existing = index != null
                    ? session.remoteState().getChannelStore().getChannelByIndex(intValue(index))
                    : null;
            channels.add(protobufMapper.buildChannel(patch.values(), existing, request.replace()));
        }
        return channels;
    }

    private ConfigProtos.Config findConfig(RemoteAdminSession session, String section) {
        for (ConfigProtos.Config config : snapshot(session.remoteState().getConfigs())) {
            LuaTable table = protobufMapper.configToTable(config);
            if (matchesSection(table.get("section").tojstring(), section)) {
                return config;
            }
        }
        return null;
    }

    private ModuleConfigProtos.ModuleConfig findModuleConfig(RemoteAdminSession session, String section) {
        for (ModuleConfigProtos.ModuleConfig moduleConfig : snapshot(session.remoteState().getModuleConfigs())) {
            LuaTable table = protobufMapper.moduleConfigToTable(moduleConfig);
            if (matchesSection(table.get("section").tojstring(), section)) {
                return moduleConfig;
            }
        }
        return null;
    }

    private static <T> List<T> snapshot(List<T> values) {
        synchronized (values) {
            return new ArrayList<>(values);
        }
    }

    private static boolean matchesSection(String actual, String requested) {
        if (actual == null || requested == null) {
            return false;
        }
        String a = actual.toLowerCase(Locale.ROOT).replace("_", "");
        String r = requested.toLowerCase(Locale.ROOT)
                .replace("_config", "")
                .replace("config", "")
                .replace("_", "");
        return a.equals(r);
    }

    private RemoteAdminService remoteAdminService(LuaRemoteAdminRequest request) {
        return remoteAdminServices.computeIfAbsent(request.targetNodeNum(), ignored -> {
            NodeData node = target.state().getNodeDb().get(request.targetNodeNum());
            if (node == null) {
                node = new NodeData(request.targetNodeNum());
                node.setNodeId(request.targetNodeId());
                node.setLongName(request.targetName());
            }
            return new RemoteAdminService(target.handler(), target.state(), node);
        });
    }

    private RemoteAdminSession remoteAdminSession(int nodeNum) {
        RemoteAdminService service = remoteAdminServices.get(nodeNum);
        return service != null ? service.session() : null;
    }

    private void callRemoteAdminProgress(LuaRemoteAdminRequest request, RemoteAdminService.QueryProgress progress) {
        try {
            executor.execute(() -> callAdminEvent(remoteAdminProgressToTable(request, progress), false));
        } catch (RejectedExecutionException ignored) {
            // Session is stopping.
        }
    }

    private void completeRemoteAdmin(String requestId,
                                     String status,
                                     Throwable error,
                                     RemoteAdminSession session) {
        PendingRemoteAdmin pending = pendingRemoteAdmins.get(requestId);
        if (pending == null || !pending.completed.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    callAdminEvent(remoteAdminResultToTable(pending.request, status, rootMessage(error), session), true);
                } finally {
                    pendingRemoteAdmins.remove(requestId, pending);
                    finishIfIdle();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Session is stopping.
        }
    }

    @Override
    public String nextTimerId() {
        return script.getId() + ":timer:" + timerRequestCounter.incrementAndGet();
    }

    @Override
    public void scheduleTimer(LuaTimerRequest request) {
        if (request == null) {
            throw new LuaError("Timer request is required");
        }
        if (pendingTimers.size() >= MAX_TIMERS) {
            throw new LuaError("Too many active timers; limit is " + MAX_TIMERS);
        }
        PendingTimer pending = new PendingTimer(request);
        PendingTimer existing = pendingTimers.putIfAbsent(request.timerId(), pending);
        if (existing != null) {
            throw new LuaError("Timer id already exists");
        }
        scriptService.updateRunState(script.getId(), "RUNNING", null);
        scheduleNextTimer(pending, request.immediate());
    }

    @Override
    public boolean cancelTimer(String timerId) {
        if (timerId == null || timerId.isBlank()) {
            return false;
        }
        PendingTimer pending = pendingTimers.remove(timerId);
        if (pending == null) {
            return false;
        }
        if (pending.future != null) {
            pending.future.cancel(false);
        }
        finishIfIdle();
        return true;
    }

    @Override
    public int cancelAllTimers() {
        int count = pendingTimers.size();
        cleanupTimers();
        finishIfIdle();
        return count;
    }

    private void scheduleNextTimer(PendingTimer pending, boolean immediate) {
        if (!running.get() || pending == null || !pendingTimers.containsKey(pending.request.timerId())) {
            return;
        }
        double delaySeconds = immediate ? 0.0 : nextTimerDelaySeconds(pending.request);
        double scheduledEpochSeconds = nowSeconds() + delaySeconds;
        long delayMillis = Math.max(0L, Math.round(delaySeconds * 1000.0));
        pending.future = scheduler.schedule(
                () -> fireTimer(pending.request.timerId(), scheduledEpochSeconds),
                delayMillis,
                TimeUnit.MILLISECONDS);
    }

    private double nextTimerDelaySeconds(LuaTimerRequest request) {
        if (!request.repeating() || !"wall".equals(request.align())) {
            return request.seconds();
        }
        double now = nowSeconds();
        int offsetSeconds = ZonedDateTime.now(ZoneId.systemDefault())
                .getOffset()
                .getTotalSeconds();
        double localEpoch = now + offsetSeconds;
        double interval = request.seconds();
        double nextLocalEpoch = Math.floor(localEpoch / interval + 1.0) * interval;
        double delay = nextLocalEpoch - localEpoch;
        if (delay <= 0.001) {
            delay = interval;
        }
        return delay;
    }

    private void fireTimer(String timerId, double scheduledEpochSeconds) {
        PendingTimer pending = pendingTimers.get(timerId);
        if (pending == null || !running.get()) {
            return;
        }
        if (pending.request.repeating()) {
            scheduleNextTimer(pending, false);
        }
        if (!pending.callbackQueued.compareAndSet(false, true)) {
            return;
        }
        double actualEpochSeconds = nowSeconds();
        long count = pending.count.incrementAndGet();
        try {
            executor.execute(() -> callTimer(pending, scheduledEpochSeconds, actualEpochSeconds, count));
        } catch (RejectedExecutionException ignored) {
            pending.callbackQueued.set(false);
            if (!pending.request.repeating()) {
                pendingTimers.remove(timerId, pending);
            }
            // Session is stopping.
        }
    }

    private void callTimer(PendingTimer pending,
                           double scheduledEpochSeconds,
                           double actualEpochSeconds,
                           long count) {
        if (!running.get()
                || globals == null
                || !pendingTimers.containsKey(pending.request.timerId())) {
            pending.callbackQueued.set(false);
            return;
        }
        LuaValue callback = globals.get("on_timer");
        try {
            if (callback.isfunction()) {
                debugLib.begin(CALLBACK_TIMEOUT_MS);
                callback.call(timerEventToTable(pending.request, scheduledEpochSeconds, actualEpochSeconds, count));
            } else if (pending.missingCallbackReported.compareAndSet(false, true)) {
                emit(LuaScriptEvent.warning(script.getId(), "Timer fired, but on_timer(event) is missing"));
            }
        } catch (Throwable callbackError) {
            fail(callbackError);
            finishWithoutInterruptingExecutor();
        } finally {
            pending.callbackQueued.set(false);
            if (!pending.request.repeating()) {
                pendingTimers.remove(pending.request.timerId(), pending);
            }
            finishIfIdle();
        }
    }

    @Override
    public void openCanvas(LuaCanvasOptions options) {
        try {
            LuaCanvasWindow.showWindow(script.getId(), script.getName(), script.getIcon(), options, this::handleCanvasEvent);
            canvasOpen.set(true);
            scriptService.updateRunState(script.getId(), "RUNNING", null);
        } catch (RuntimeException error) {
            throw new LuaError("mesh.canvas.open failed: " + error.getMessage());
        }
    }

    @Override
    public void closeCanvas() {
        if (!canvasOpen.getAndSet(false)) {
            return;
        }
        try {
            LuaCanvasWindow.closeWindow(script.getId());
        } catch (RuntimeException error) {
            throw new LuaError("mesh.canvas.close failed: " + error.getMessage());
        }
    }

    @Override
    public void enqueueCanvasDraw(LuaCanvasDrawCommand command) {
        if (!canvasOpen.get() || !LuaCanvasWindow.enqueueDraw(script.getId(), command)) {
            throw new LuaError("mesh.canvas: call mesh.canvas.open(...) before drawing");
        }
    }

    @Override
    public void setCanvasFrameRate(double fps) {
        if (!canvasOpen.get() || !LuaCanvasWindow.setFrameRate(script.getId(), fps)) {
            throw new LuaError("mesh.canvas: call mesh.canvas.open(...) before set_fps");
        }
    }

    @Override
    public LuaCanvasMouseState canvasMouseState() {
        return LuaCanvasWindow.mouseState(script.getId());
    }

    @Override
    public LuaCanvasKeyState canvasKeyState() {
        return LuaCanvasWindow.keyState(script.getId());
    }

    @Override
    public LuaCanvasSize canvasSize() {
        return LuaCanvasWindow.size(script.getId());
    }

    @Override
    public boolean isFormAvailable() {
        return formBridge != null && formBridge.isFormAvailable();
    }

    @Override
    public boolean isFormOpen() {
        return formBridge != null && formBridge.isFormOpen();
    }

    @Override
    public void showForm() {
        if (formBridge == null) {
            throw new LuaError("No active extension form");
        }
        formBridge.showForm();
    }

    @Override
    public void setFormTitle(String title) {
        if (formBridge == null) {
            throw new LuaError("No active extension form");
        }
        formBridge.setFormTitle(title);
    }

    @Override
    public void clearForm() {
        if (formBridge == null) {
            throw new LuaError("No active extension form");
        }
        formBridge.clearForm();
    }

    @Override
    public String addFormComponent(LuaFormComponentSpec spec) {
        if (formBridge == null) {
            throw new LuaError("No active extension form");
        }
        return formBridge.addFormComponent(spec);
    }

    @Override
    public void updateFormComponent(String id, LuaFormComponentSpec spec) {
        if (formBridge == null) {
            throw new LuaError("No active extension form");
        }
        formBridge.updateFormComponent(id, spec);
    }

    @Override
    public void removeFormComponent(String id) {
        if (formBridge == null) {
            throw new LuaError("No active extension form");
        }
        formBridge.removeFormComponent(id);
    }

    @Override
    public Object formComponentValue(String id) {
        if (formBridge == null) {
            throw new LuaError("No active extension form");
        }
        return formBridge.formComponentValue(id);
    }

    void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        resumeDebuggee(DebugCommand.CONTINUE);
        unregisterListener();
        cleanupTraceroutes();
        cleanupNodeInfos();
        cleanupRemoteAdmins();
        cleanupTimers();
        cleanupCanvas();
        Future<?> future = activeFuture;
        if (future != null) {
            future.cancel(true);
        }
        executor.shutdownNow();
        scheduler.shutdownNow();
        scriptService.updateRunState(script.getId(), "STOPPED", null);
        emit(LuaScriptEvent.stopped(script.getId(), I18n.t("meshIde.runtime.stopped")));
    }

    private boolean matchesTracerouteResponse(LuaTracerouteRequest request, int fromNodeNum) {
        if (fromNodeNum == request.targetNodeNum()) {
            return true;
        }
        return pendingTraceroutes.size() == 1;
    }

    private void completeTraceroute(String requestId,
                                    int responseFromNodeNum,
                                    String status,
                                    MeshProtos.RouteDiscovery route,
                                    String error) {
        PendingTraceroute pending = pendingTraceroutes.remove(requestId);
        if (pending == null || !pending.completed.compareAndSet(false, true)) {
            return;
        }
        if (target.state() != null && pending.listener != null) {
            target.state().removeTracerouteListener(pending.listener);
        }
        if (pending.timeoutFuture != null) {
            pending.timeoutFuture.cancel(false);
        }
        try {
            executor.execute(() -> callTraceroute(pending.request, responseFromNodeNum, status, route, error));
        } catch (RejectedExecutionException ignored) {
            // Session is stopping.
        }
    }

    private void callTraceroute(LuaTracerouteRequest request,
                                int responseFromNodeNum,
                                String status,
                                MeshProtos.RouteDiscovery route,
                                String error) {
        if (!running.get() || globals == null) {
            return;
        }
        persistTracerouteResult(request, responseFromNodeNum, status, route);
        LuaValue callback = globals.get("on_traceroute");
        try {
            if (callback.isfunction()) {
                debugLib.begin(CALLBACK_TIMEOUT_MS);
                callback.call(tracerouteEventToTable(request, responseFromNodeNum, status, route, error));
            } else {
                emit(LuaScriptEvent.warning(script.getId(), "Traceroute result received, but on_traceroute(event) is missing"));
            }
            finishIfIdle();
        } catch (Throwable callbackError) {
            fail(callbackError);
            finishWithoutInterruptingExecutor();
        }
    }

    private void persistTracerouteResult(LuaTracerouteRequest request,
                                         int responseFromNodeNum,
                                         String status,
                                         MeshProtos.RouteDiscovery route) {
        if (!"ok".equals(status) || route == null) {
            return;
        }
        messageDbService.saveTracerouteResult(
                target.ownerNodeIdOrEmpty(),
                request.chatType(),
                request.chatKey(),
                request.source(),
                request.requestId(),
                request.scriptId(),
                Integer.toUnsignedLong(request.targetNodeNum()),
                request.targetNodeId() != null ? request.targetNodeId() : nodeIdFromNum(request.targetNodeNum()),
                tracerouteTargetName(request),
                responseFromNodeNum != 0 ? Integer.toUnsignedLong(responseFromNodeNum) : 0,
                responseFromNodeNum != 0 ? nodeIdFromNum(responseFromNodeNum) : null,
                route.toByteArray(),
                null,
                System.currentTimeMillis() / 1000
        );
    }

    private static String tracerouteTargetName(LuaTracerouteRequest request) {
        if (request.targetName() != null && !request.targetName().isBlank()) {
            return request.targetName();
        }
        if (request.targetNodeId() != null && !request.targetNodeId().isBlank()) {
            return request.targetNodeId();
        }
        return nodeIdFromNum(request.targetNodeNum());
    }

    private void completeNodeInfoImmediate(LuaNodeInfoRequest request) {
        NodeData node = target.state() != null ? target.state().getNodeDb().get(request.targetNodeNum()) : null;
        String status = node != null ? "ok" : "error";
        String error = node != null ? null : "NodeInfo is not available for selected node";
        PendingNodeInfo pending = new PendingNodeInfo(request);
        pendingNodeInfos.put(request.requestId(), pending);
        scriptService.updateRunState(script.getId(), "RUNNING", null);
        try {
            executor.execute(() -> {
                PendingNodeInfo removed = pendingNodeInfos.remove(request.requestId());
                if (removed == null || !removed.completed.compareAndSet(false, true)) {
                    return;
                }
                callNodeInfo(request, status, node, error);
            });
        } catch (RejectedExecutionException ignored) {
            pendingNodeInfos.remove(request.requestId());
            // Session is stopping.
        }
    }

    private void completeNodeInfo(String requestId, String status, NodeData node, String error) {
        PendingNodeInfo pending = pendingNodeInfos.remove(requestId);
        if (pending == null || !pending.completed.compareAndSet(false, true)) {
            return;
        }
        if (target.state() != null && pending.listener != null) {
            target.state().removeNodeUpdateListener(pending.listener);
        }
        if (pending.timeoutFuture != null) {
            pending.timeoutFuture.cancel(false);
        }
        try {
            executor.execute(() -> callNodeInfo(pending.request, status, node, error));
        } catch (RejectedExecutionException ignored) {
            // Session is stopping.
        }
    }

    private NodeData cachedNode(int nodeNum) {
        return target.state() != null ? target.state().getNodeDb().get(nodeNum) : null;
    }

    private void callNodeInfo(LuaNodeInfoRequest request, String status, NodeData node, String error) {
        if (!running.get() || globals == null) {
            return;
        }
        LuaValue callback = globals.get("on_node_info");
        try {
            if (callback.isfunction()) {
                debugLib.begin(CALLBACK_TIMEOUT_MS);
                callback.call(nodeInfoEventToTable(request, status, node, error));
            } else {
                emit(LuaScriptEvent.warning(script.getId(), "NodeInfo result received, but on_node_info(event) is missing"));
            }
            finishIfIdle();
        } catch (Throwable callbackError) {
            fail(callbackError);
            finishWithoutInterruptingExecutor();
        }
    }

    private void callNodeSelected(LuaUiNodeSelection selection) {
        if (!running.get() || globals == null) {
            return;
        }
        LuaValue callback = globals.get("on_node_selected");
        try {
            pendingUiRequests.remove(selection.requestId());
            if (callback.isfunction()) {
                debugLib.begin(CALLBACK_TIMEOUT_MS);
                callback.call(nodeSelectionToTable(selection));
            } else {
                emit(LuaScriptEvent.warning(script.getId(), "Node selection received, but on_node_selected(event) is missing"));
            }
            finishIfIdle();
        } catch (Throwable error) {
            fail(error);
            finishWithoutInterruptingExecutor();
        }
    }

    private void handleCanvasEvent(LuaCanvasEvent event) {
        if (event == null) {
            return;
        }
        if ("closed".equals(event.type())) {
            canvasOpen.set(false);
        }
        if (!running.get()) {
            return;
        }
        try {
            executor.execute(() -> callCanvasEvent(event));
        } catch (RejectedExecutionException ignored) {
            // Session is stopping.
        }
    }

    private void callCanvasEvent(LuaCanvasEvent event) {
        if (!running.get() || globals == null) {
            return;
        }
        String callbackName = "frame".equals(event.type()) ? "on_canvas_frame" : "on_canvas_event";
        LuaValue callback = globals.get(callbackName);
        try {
            if (callback.isfunction()) {
                debugLib.begin(CALLBACK_TIMEOUT_MS);
                callback.call(canvasEventToTable(event));
            }
            finishIfIdle();
        } catch (Throwable error) {
            fail(error);
            finishWithoutInterruptingExecutor();
        }
    }

    private void callFormEvent(LuaFormEvent event) {
        if (!running.get() || globals == null) {
            return;
        }
        LuaValue callback = globals.get("on_form_event");
        try {
            if (callback.isfunction()) {
                debugLib.begin(CALLBACK_TIMEOUT_MS);
                callback.call(formEventToTable(event));
            }
            finishIfIdle();
        } catch (Throwable error) {
            fail(error);
            finishWithoutInterruptingExecutor();
        }
    }

    private LuaValue nodeSelectionToTable(LuaUiNodeSelection selection) {
        LuaTable table = new LuaTable();
        table.set("type", LuaValue.valueOf("ui_result"));
        table.set("source", LuaValue.valueOf(selection.source() != null ? selection.source() : "mesh.ui.pick_node"));
        table.set("name", LuaValue.valueOf(selection.name() != null ? selection.name() : ""));
        table.set("request_id", LuaValue.valueOf(selection.requestId()));
        table.set("status", LuaValue.valueOf(selection.selected() ? "selected" : "cancelled"));
        table.set("selected", LuaValue.valueOf(selection.selected()));
        table.set("cancelled", LuaValue.valueOf(!selection.selected()));
        table.set("chat_type", LuaValue.valueOf(selection.chatType() != null ? selection.chatType() : ""));
        table.set("chat_key", LuaValue.valueOf(selection.chatKey() != null ? selection.chatKey() : ""));
        table.set("node", selection.node() != null
                ? sandboxApi.mapper().nodeToTable(selection.node())
                : LuaValue.NIL);
        return table;
    }

    private LuaValue canvasEventToTable(LuaCanvasEvent event) {
        LuaTable table = new LuaTable();
        table.set("type", LuaValue.valueOf(event.type() != null ? event.type() : ""));
        table.set("source", LuaValue.valueOf("mesh.canvas"));
        table.set("x", LuaValue.valueOf(event.x()));
        table.set("y", LuaValue.valueOf(event.y()));
        table.set("screen_x", LuaValue.valueOf(event.screenX()));
        table.set("screen_y", LuaValue.valueOf(event.screenY()));
        table.set("button", LuaValue.valueOf(event.button() != null ? event.button() : ""));
        table.set("click_count", LuaValue.valueOf(event.clickCount()));
        table.set("primary", LuaValue.valueOf(event.primaryDown()));
        table.set("middle", LuaValue.valueOf(event.middleDown()));
        table.set("secondary", LuaValue.valueOf(event.secondaryDown()));
        table.set("wheel_delta_x", LuaValue.valueOf(event.wheelDeltaX()));
        table.set("wheel_delta_y", LuaValue.valueOf(event.wheelDeltaY()));
        table.set("code", LuaValue.valueOf(event.code() != null ? event.code() : ""));
        table.set("key", LuaValue.valueOf(event.key() != null ? event.key() : ""));
        table.set("text", LuaValue.valueOf(event.text() != null ? event.text() : ""));
        table.set("shift", LuaValue.valueOf(event.shiftDown()));
        table.set("ctrl", LuaValue.valueOf(event.controlDown()));
        table.set("alt", LuaValue.valueOf(event.altDown()));
        table.set("meta", LuaValue.valueOf(event.metaDown()));
        table.set("width", LuaValue.valueOf(event.width()));
        table.set("height", LuaValue.valueOf(event.height()));
        table.set("time", LuaValue.valueOf(event.timeSeconds()));
        table.set("dt", LuaValue.valueOf(event.deltaSeconds()));
        return table;
    }

    private LuaValue formEventToTable(LuaFormEvent event) {
        LuaTable table = new LuaTable();
        table.set("type", LuaValue.valueOf(event.type() != null ? event.type() : ""));
        table.set("source", LuaValue.valueOf("mesh.form"));
        table.set("component_id", LuaValue.valueOf(event.componentId() != null ? event.componentId() : ""));
        table.set("id", LuaValue.valueOf(event.componentId() != null ? event.componentId() : ""));
        table.set("value", objectToLua(event.value()));
        table.set("text", event.text() != null ? LuaValue.valueOf(event.text()) : LuaValue.NIL);
        return table;
    }

    private LuaValue timerEventToTable(LuaTimerRequest request,
                                       double scheduledEpochSeconds,
                                       double actualEpochSeconds,
                                       long count) {
        LuaTable table = new LuaTable();
        table.set("type", LuaValue.valueOf("timer"));
        table.set("source", LuaValue.valueOf(request.source() != null ? request.source() : "mesh.timer"));
        table.set("id", LuaValue.valueOf(request.timerId()));
        table.set("timer_id", LuaValue.valueOf(request.timerId()));
        table.set("name", LuaValue.valueOf(request.name() != null ? request.name() : ""));
        table.set("interval_seconds", LuaValue.valueOf(request.seconds()));
        table.set("seconds", LuaValue.valueOf(request.seconds()));
        table.set("repeating", LuaValue.valueOf(request.repeating()));
        table.set("align", LuaValue.valueOf(request.align() != null ? request.align() : "interval"));
        table.set("count", LuaValue.valueOf((double) count));
        table.set("scheduled_epoch", LuaValue.valueOf(scheduledEpochSeconds));
        table.set("actual_epoch", LuaValue.valueOf(actualEpochSeconds));
        table.set("drift_seconds", LuaValue.valueOf(actualEpochSeconds - scheduledEpochSeconds));
        table.set("time", sandboxApi.localTimeTable(actualEpochSeconds));
        return table;
    }

    private LuaValue objectToLua(Object value) {
        if (value == null) {
            return LuaValue.NIL;
        }
        if (value instanceof Boolean booleanValue) {
            return LuaValue.valueOf(booleanValue);
        }
        if (value instanceof Number number) {
            return LuaValue.valueOf(number.doubleValue());
        }
        if (value instanceof List<?> list) {
            LuaTable table = new LuaTable();
            for (int i = 0; i < list.size(); i++) {
                table.set(i + 1, objectToLua(list.get(i)));
            }
            return table;
        }
        return LuaValue.valueOf(value.toString());
    }

    private LuaValue tracerouteEventToTable(LuaTracerouteRequest request,
                                            int responseFromNodeNum,
                                            String status,
                                            MeshProtos.RouteDiscovery route,
                                            String error) {
        LuaTable table = new LuaTable();
        table.set("type", LuaValue.valueOf("traceroute_result"));
        table.set("source", LuaValue.valueOf(request.source() != null ? request.source() : "mesh.traceroute.request"));
        table.set("name", LuaValue.valueOf(request.name() != null ? request.name() : ""));
        table.set("request_id", LuaValue.valueOf(request.requestId()));
        table.set("status", LuaValue.valueOf(status != null ? status : ""));
        table.set("ok", LuaValue.valueOf("ok".equals(status)));
        table.set("timeout", LuaValue.valueOf("timeout".equals(status)));
        table.set("error", error != null ? LuaValue.valueOf(error) : LuaValue.NIL);
        table.set("target_node_num", LuaValueMapper.uint32ToLuaValue(request.targetNodeNum()));
        table.set("target_node_id", LuaValue.valueOf(request.targetNodeId() != null
                ? request.targetNodeId()
                : nodeIdFromNum(request.targetNodeNum())));
        table.set("target_name", LuaValue.valueOf(request.targetName() != null ? request.targetName() : ""));
        table.set("response_from_node_num", responseFromNodeNum != 0
                ? LuaValueMapper.uint32ToLuaValue(responseFromNodeNum)
                : LuaValue.NIL);
        table.set("response_from_node_id", responseFromNodeNum != 0
                ? LuaValue.valueOf(nodeIdFromNum(responseFromNodeNum))
                : LuaValue.NIL);
        table.set("chat_type", LuaValue.valueOf(request.chatType() != null ? request.chatType() : ""));
        table.set("chat_key", LuaValue.valueOf(request.chatKey() != null ? request.chatKey() : ""));
        table.set("route", route != null ? routeToTable(route) : LuaValue.NIL);
        return table;
    }

    private LuaValue nodeInfoEventToTable(LuaNodeInfoRequest request,
                                          String status,
                                          NodeData node,
                                          String error) {
        LuaTable table = new LuaTable();
        table.set("type", LuaValue.valueOf("nodeinfo_result"));
        table.set("source", LuaValue.valueOf(request.source() != null ? request.source() : "mesh.nodeinfo.request"));
        table.set("name", LuaValue.valueOf(request.name() != null ? request.name() : ""));
        table.set("request_id", LuaValue.valueOf(request.requestId()));
        table.set("status", LuaValue.valueOf(status != null ? status : ""));
        table.set("ok", LuaValue.valueOf("ok".equals(status)));
        table.set("timeout", LuaValue.valueOf("timeout".equals(status)));
        table.set("cached", LuaValue.valueOf(node != null && !"ok".equals(status)));
        table.set("error", error != null ? LuaValue.valueOf(error) : LuaValue.NIL);
        table.set("target_node_num", LuaValueMapper.uint32ToLuaValue(request.targetNodeNum()));
        table.set("target_node_id", LuaValue.valueOf(request.targetNodeId() != null
                ? request.targetNodeId()
                : nodeIdFromNum(request.targetNodeNum())));
        table.set("target_name", LuaValue.valueOf(request.targetName() != null ? request.targetName() : ""));
        table.set("chat_type", LuaValue.valueOf(request.chatType() != null ? request.chatType() : ""));
        table.set("chat_key", LuaValue.valueOf(request.chatKey() != null ? request.chatKey() : ""));
        table.set("node", node != null ? sandboxApi.mapper().nodeToTable(node) : LuaValue.NIL);
        return table;
    }

    private LuaValue remoteAdminProgressToTable(LuaRemoteAdminRequest request,
                                                RemoteAdminService.QueryProgress progress) {
        LuaTable table = remoteAdminBaseTable(request, "admin_progress");
        table.set("status", LuaValue.valueOf(progress.state().name().toLowerCase(Locale.ROOT)));
        table.set("ok", LuaValue.FALSE);
        table.set("timeout", LuaValue.FALSE);
        table.set("progress_key", LuaValue.valueOf(progress.key()));
        table.set("completed", LuaValue.valueOf(progress.completed()));
        table.set("total", LuaValue.valueOf(progress.total()));
        return table;
    }

    private LuaValue remoteAdminResultToTable(LuaRemoteAdminRequest request,
                                              String status,
                                              String error,
                                              RemoteAdminSession session) {
        LuaTable table = remoteAdminBaseTable(request, "admin_result");
        table.set("status", LuaValue.valueOf(status != null ? status : ""));
        table.set("ok", LuaValue.valueOf("ok".equals(status)));
        table.set("timeout", LuaValue.valueOf("timeout".equals(status)));
        table.set("error", error != null ? LuaValue.valueOf(error) : LuaValue.NIL);
        table.set("snapshot", session != null ? remoteAdminSnapshotToTable(session) : LuaValue.NIL);
        return table;
    }

    private LuaTable remoteAdminBaseTable(LuaRemoteAdminRequest request, String type) {
        LuaTable table = new LuaTable();
        table.set("type", LuaValue.valueOf(type));
        table.set("source", LuaValue.valueOf(request.source() != null ? request.source() : "mesh.admin"));
        table.set("name", LuaValue.valueOf(request.name() != null ? request.name() : ""));
        table.set("request_id", LuaValue.valueOf(request.requestId()));
        table.set("action", LuaValue.valueOf(request.action().name().toLowerCase(Locale.ROOT)));
        table.set("target_node_num", LuaValueMapper.uint32ToLuaValue(request.targetNodeNum()));
        table.set("target_node_id", LuaValue.valueOf(request.targetNodeId() != null
                ? request.targetNodeId()
                : nodeIdFromNum(request.targetNodeNum())));
        table.set("target_name", LuaValue.valueOf(request.targetName() != null ? request.targetName() : ""));
        return table;
    }

    private LuaTable remoteAdminSnapshotToTable(RemoteAdminSession session) {
        LuaTable table = new LuaTable();
        DeviceState remoteState = session.remoteState();
        table.set("target_node_num", LuaValueMapper.uint32ToLuaValue(session.targetNodeNum()));
        table.set("target_node_id", LuaValue.valueOf(nodeIdFromNum(session.targetNodeNum())));
        table.set("node", sandboxApi.mapper().nodeToTable(session.targetNode()));
        table.set("owner", remoteState.getOwnerInfo() != null
                ? protobufMapper.messageToTable(remoteState.getOwnerInfo())
                : LuaValue.NIL);
        table.set("device_metadata", remoteState.getDeviceMetadata() != null
                ? protobufMapper.messageToTable(remoteState.getDeviceMetadata())
                : LuaValue.NIL);
        table.set("ringtone", LuaValue.valueOf(remoteState.getRingtone()));
        table.set("canned_messages", LuaValue.valueOf(session.getCannedMessages()));
        table.set("canned_messages_loaded", LuaValue.valueOf(session.isCannedMessagesLoaded()));
        table.set("connection_status", session.getConnectionStatus() != null
                ? protobufMapper.messageToTable(session.getConnectionStatus())
                : LuaValue.NIL);
        table.set("configs", configsToTable(snapshot(remoteState.getConfigs())));
        table.set("module_configs", moduleConfigsToTable(snapshot(remoteState.getModuleConfigs())));
        table.set("channels", channelsToTable(snapshot(remoteState.getChannels())));
        table.set("query_statuses", queryStatusesToTable(session));
        RemoteAdminSession.QuerySummary summary = session.querySummary();
        LuaTable summaryTable = new LuaTable();
        summaryTable.set("total", LuaValue.valueOf(summary.total()));
        summaryTable.set("received", LuaValue.valueOf(summary.received()));
        summaryTable.set("failed", LuaValue.valueOf(summary.failed()));
        table.set("query_summary", summaryTable);
        return table;
    }

    private LuaTable configsToTable(List<ConfigProtos.Config> configs) {
        LuaTable table = new LuaTable();
        for (ConfigProtos.Config config : configs) {
            LuaTable item = protobufMapper.configToTable(config);
            String section = item.get("section").tojstring();
            LuaValue values = item.get("values");
            table.set(section, values);
        }
        return table;
    }

    private LuaTable moduleConfigsToTable(List<ModuleConfigProtos.ModuleConfig> moduleConfigs) {
        LuaTable table = new LuaTable();
        for (ModuleConfigProtos.ModuleConfig moduleConfig : moduleConfigs) {
            LuaTable item = protobufMapper.moduleConfigToTable(moduleConfig);
            String section = item.get("section").tojstring();
            LuaValue values = item.get("values");
            table.set(section, values);
        }
        return table;
    }

    private LuaTable channelsToTable(List<ChannelProtos.Channel> channels) {
        LuaTable table = new LuaTable();
        channels.sort(Comparator.comparingInt(ChannelProtos.Channel::getIndex));
        for (int i = 0; i < channels.size(); i++) {
            table.set(i + 1, protobufMapper.messageToTable(channels.get(i)));
        }
        return table;
    }

    private LuaTable queryStatusesToTable(RemoteAdminSession session) {
        LuaTable table = new LuaTable();
        List<RemoteAdminSession.QueryStatus> statuses = session.queryStatuses();
        for (int i = 0; i < statuses.size(); i++) {
            RemoteAdminSession.QueryStatus status = statuses.get(i);
            LuaTable item = new LuaTable();
            item.set("key", LuaValue.valueOf(status.key()));
            item.set("state", LuaValue.valueOf(status.state().name().toLowerCase(Locale.ROOT)));
            item.set("detail", status.detail() != null ? LuaValue.valueOf(status.detail()) : LuaValue.NIL);
            table.set(i + 1, item);
        }
        return table;
    }

    private void callAdminEvent(LuaValue event, boolean terminal) {
        if (!running.get() || globals == null) {
            return;
        }
        LuaValue callback = globals.get("on_admin");
        try {
            if (callback.isfunction()) {
                debugLib.begin(CALLBACK_TIMEOUT_MS);
                callback.call(event);
            } else if (terminal) {
                emit(LuaScriptEvent.warning(script.getId(), "Remote admin result received, but on_admin(event) is missing"));
            }
        } catch (Throwable callbackError) {
            fail(callbackError);
            finishWithoutInterruptingExecutor();
        }
    }

    private LuaValue routeToTable(MeshProtos.RouteDiscovery route) {
        LuaTable table = new LuaTable();
        table.set("route", intListToTable(route.getRouteList()));
        table.set("route_back", intListToTable(route.getRouteBackList()));
        table.set("route_ids", nodeIdListToTable(route.getRouteList()));
        table.set("route_back_ids", nodeIdListToTable(route.getRouteBackList()));
        table.set("snr_towards", snrListToTable(route.getSnrTowardsList()));
        table.set("snr_back", snrListToTable(route.getSnrBackList()));
        return table;
    }

    private LuaTable intListToTable(List<Integer> values) {
        LuaTable table = new LuaTable();
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                table.set(i + 1, LuaValueMapper.uint32ToLuaValue(values.get(i)));
            }
        }
        return table;
    }

    private LuaTable nodeIdListToTable(List<Integer> values) {
        LuaTable table = new LuaTable();
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                table.set(i + 1, LuaValue.valueOf(nodeIdFromNum(values.get(i))));
            }
        }
        return table;
    }

    private static String nodeIdFromNum(int nodeNum) {
        return String.format(Locale.ROOT, "!%08x", nodeNum);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return (int) ((long) number.doubleValue() & 0xffff_ffffL);
        }
        String text = value != null ? value.toString().trim() : "0";
        if (text.startsWith("!")) {
            return (int) Long.parseUnsignedLong(text.substring(1), 16);
        }
        return (int) (Long.parseUnsignedLong(text) & 0xffff_ffffL);
    }

    private static String statusForError(Throwable error) {
        String message = rootMessage(error);
        return message != null && message.toLowerCase(Locale.ROOT).contains("timed out")
                ? "timeout"
                : "error";
    }

    private static String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private LuaTable snrListToTable(List<Integer> values) {
        LuaTable table = new LuaTable();
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                table.set(i + 1, LuaValue.valueOf(values.get(i) / 4.0));
            }
        }
        return table;
    }

    private static double nowSeconds() {
        return System.currentTimeMillis() / 1000.0;
    }

    private boolean hasPendingAsyncWork() {
        return !pendingUiRequests.isEmpty()
                || !pendingTraceroutes.isEmpty()
                || !pendingNodeInfos.isEmpty()
                || !pendingRemoteAdmins.isEmpty()
                || !pendingTimers.isEmpty()
                || canvasOpen.get()
                || isFormOpen();
    }

    private void finishIfIdle() {
        if (!listenerRegistered && !hasPendingAsyncWork()) {
            scriptService.updateRunState(script.getId(), "FINISHED", null);
            finishWithoutInterruptingExecutor();
        }
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
                    this::emitOutput,
                    command,
                    this,
                    this,
                    this,
                    this,
                    this,
                    this,
                    this,
                    this::deferExecutionDeadline));
            sandboxApi.install(globals);

            debugLib.begin(RUN_TIMEOUT_MS);
            globals.load(script.getCode() != null ? script.getCode() : "", script.getName()).call();

            if (formBridge != null) {
                formBridge.showForm();
                deliverExtensionOpen();
            }

            if (command != null) {
                deliverAutomationCommand();
            }

            LuaValue onMessage = globals.get("on_message");
            if (onMessage.isfunction()) {
                keepListening = attachMessageListener();
            } else {
                if (command == null && formBridge == null) {
                    emit(LuaScriptEvent.info(script.getId(), I18n.t("meshIde.runtime.noOnMessage")));
                }
                if (!hasPendingAsyncWork()) {
                    scriptService.updateRunState(script.getId(), "FINISHED", null);
                }
            }
        } catch (Throwable error) {
            fail(error);
        } finally {
            if (!keepListening && !hasPendingAsyncWork()) {
                finishWithoutInterruptingExecutor();
            }
        }
    }

    private void deliverExtensionOpen() {
        LuaValue onExtensionOpen = globals.get("on_extension_open");
        if (!onExtensionOpen.isfunction()) {
            return;
        }
        debugLib.begin(CALLBACK_TIMEOUT_MS);
        LuaTable event = new LuaTable();
        event.set("type", LuaValue.valueOf("extension_open"));
        event.set("source", LuaValue.valueOf("mesh.extension"));
        event.set("script_id", LuaValue.valueOf(script.getId()));
        event.set("name", LuaValue.valueOf(script.getName() != null ? script.getName() : ""));
        onExtensionOpen.call(event);
    }

    private void deliverAutomationCommand() {
        LuaValue onCommand = globals.get("on_command");
        if (!onCommand.isfunction()) {
            emit(LuaScriptEvent.warning(script.getId(), I18n.t("meshIde.runtime.noOnCommand")));
            return;
        }
        debugLib.begin(CALLBACK_TIMEOUT_MS);
        onCommand.call(sandboxApi.commandToTable());
    }

    private boolean attachMessageListener() {
        if (target.state() == null) {
            emit(LuaScriptEvent.warning(script.getId(), I18n.t("meshIde.runtime.noActiveConnection")));
            scriptService.updateRunState(script.getId(), "FINISHED",
                    I18n.t("meshIde.runtime.noActiveConnectionStatus"));
            return false;
        }
        initializeMessageCursors();
        target.state().addMessageListener(deviceMessageListener);
        listenerRegistered = true;
        scriptService.updateRunState(script.getId(), "RUNNING", null);
        emit(LuaScriptEvent.info(script.getId(), I18n.t("meshIde.runtime.waitingMessages")));
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
        String reason = breakpointHit
                ? I18n.t("meshIde.runtime.debugReason.breakpoint")
                : I18n.t("meshIde.runtime.debugReason.step");
        debugSnapshot = createDebugSnapshot(line, reason);
        synchronized (debugLock) {
            paused = true;
            debugCommand = DebugCommand.NONE;
        }
        emit(LuaScriptEvent.debugPaused(script.getId(),
                I18n.t("meshIde.runtime.debugPause", Integer.toString(line), reason)));

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
        emit(LuaScriptEvent.debugResumed(script.getId(), I18n.t("meshIde.runtime.debugResumed")));
    }

    private void resumeDebuggee(DebugCommand command) {
        synchronized (debugLock) {
            debugCommand = command;
            debugLock.notifyAll();
        }
    }

    private void deferExecutionDeadline() {
        if (debugLib != null) {
            debugLib.deferDeadline();
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
                emit(LuaScriptEvent.warning(script.getId(), I18n.t("meshIde.runtime.outputLimit")));
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
        cleanupTraceroutes();
        cleanupNodeInfos();
        cleanupRemoteAdmins();
        cleanupTimers();
        cleanupCanvas();
        running.set(false);
        executor.shutdown();
        scheduler.shutdownNow();
        onClosed.run();
    }

    private void unregisterListener() {
        if (listenerRegistered && target.state() != null) {
            target.state().removeMessageListener(deviceMessageListener);
        }
        listenerRegistered = false;
    }

    private void cleanupTraceroutes() {
        pendingTraceroutes.forEach((ignored, pending) -> {
            if (target.state() != null && pending.listener != null) {
                target.state().removeTracerouteListener(pending.listener);
            }
            if (pending.timeoutFuture != null) {
                pending.timeoutFuture.cancel(false);
            }
        });
        pendingTraceroutes.clear();
    }

    private void cleanupNodeInfos() {
        pendingNodeInfos.forEach((ignored, pending) -> {
            if (target.state() != null && pending.listener != null) {
                target.state().removeNodeUpdateListener(pending.listener);
            }
            if (pending.timeoutFuture != null) {
                pending.timeoutFuture.cancel(false);
            }
        });
        pendingNodeInfos.clear();
    }

    private void cleanupRemoteAdmins() {
        pendingRemoteAdmins.clear();
        remoteAdminServices.forEach((ignored, service) -> {
            try {
                service.close();
            } catch (RuntimeException ignoredError) {
                // Session is already stopping.
            }
        });
        remoteAdminServices.clear();
    }

    private void cleanupTimers() {
        pendingTimers.forEach((ignored, pending) -> {
            if (pending.future != null) {
                pending.future.cancel(false);
            }
        });
        pendingTimers.clear();
    }

    private void cleanupCanvas() {
        if (canvasOpen.getAndSet(false)) {
            try {
                LuaCanvasWindow.closeWindow(script.getId());
            } catch (RuntimeException ignored) {
                // JavaFX may already be shutting down.
            }
        }
    }

    private void emit(LuaScriptEvent event) {
        if (eventSink != null) {
            eventSink.accept(event);
        }
    }

    /**
     * Chat scope a Lua script should watch for new messages.
     *
     * @param chatType chat type ({@code channel} or {@code dm})
     * @param chatKey  chat key
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

    private static final class PendingTraceroute {
        private final LuaTracerouteRequest request;
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private volatile BiConsumer<Integer, MeshProtos.RouteDiscovery> listener;
        private volatile ScheduledFuture<?> timeoutFuture;

        private PendingTraceroute(LuaTracerouteRequest request) {
            this.request = request;
        }
    }

    private static final class PendingNodeInfo {
        private final LuaNodeInfoRequest request;
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private volatile IntConsumer listener;
        private volatile ScheduledFuture<?> timeoutFuture;

        private PendingNodeInfo(LuaNodeInfoRequest request) {
            this.request = request;
        }
    }

    private static final class PendingRemoteAdmin {
        private final LuaRemoteAdminRequest request;
        private final AtomicBoolean completed = new AtomicBoolean(false);

        private PendingRemoteAdmin(LuaRemoteAdminRequest request) {
            this.request = request;
        }
    }

    private static final class PendingTimer {
        private final LuaTimerRequest request;
        private final AtomicBoolean callbackQueued = new AtomicBoolean(false);
        private final AtomicBoolean missingCallbackReported = new AtomicBoolean(false);
        private final AtomicLong count = new AtomicLong();
        private volatile ScheduledFuture<?> future;

        private PendingTimer(LuaTimerRequest request) {
            this.request = request;
        }
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
