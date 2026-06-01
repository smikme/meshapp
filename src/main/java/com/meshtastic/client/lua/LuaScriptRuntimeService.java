package com.meshtastic.client.lua;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocolRuntime;
import com.meshtastic.client.service.ConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.CoroutineLib;
import org.luaj.vm2.lib.MathLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.luaj.vm2.LoadState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Управляет жизненным циклом Lua-скриптов MeshApp.
 * <p>
 * Сервис создает песочницы выполнения, выбирает активное подключение приложения,
 * запускает обычный режим и режим отладки, останавливает активные сессии и
 * предоставляет состояние выполнения для UI MeshApp IDE.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaScriptRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(LuaScriptRuntimeService.class);

    private static LuaScriptRuntimeService instance;

    private final Map<Long, LuaRuntimeSession> sessions = new ConcurrentHashMap<>();

    private LuaScriptRuntimeService() {}

    public static synchronized LuaScriptRuntimeService getInstance() {
        if (instance == null) {
            instance = new LuaScriptRuntimeService();
        }
        return instance;
    }

    public void runScript(LuaScript script, Consumer<LuaScriptEvent> sink) {
        if (script == null) {
            return;
        }
        stopScript(script.getId(), sink);
        RuntimeTarget target = resolveTarget(script);
        LuaRuntimeSession session = new LuaRuntimeSession(
                script,
                target,
                LuaScriptService.getInstance(),
                Set.of(),
                false,
                null,
                event -> {
                    if (sink != null) {
                        sink.accept(event);
                    }
                },
                null,
                () -> sessions.remove(script.getId())
        );
        sessions.put(script.getId(), session);
        session.start();
    }

    public void runAutomationCommand(LuaScript script,
                                     LuaAutomationCommand command,
                                     Consumer<LuaScriptEvent> sink,
                                     Consumer<LuaUiNodePickRequest> uiNodePickSink) {
        if (script == null || command == null) {
            return;
        }
        stopScript(script.getId(), sink);
        RuntimeTarget target = resolveTarget(script);
        LuaRuntimeSession session = new LuaRuntimeSession(
                script,
                target,
                LuaScriptService.getInstance(),
                Set.of(),
                false,
                command,
                event -> {
                    if (sink != null) {
                        sink.accept(event);
                    }
                },
                uiNodePickSink,
                () -> sessions.remove(script.getId())
        );
        sessions.put(script.getId(), session);
        session.start();
    }

    /**
     * Запускает все эфирные Lua-скрипты с включенным автозапуском,
     * привязанные к указанной ноде.
     * <p>
     * Метод вызывается после готовности протокольного runtime-а подключения, чтобы
     * скрипт получал уже заполненное состояние ноды и корректный transport target.
     *
     * @param nodeId идентификатор локальной ноды активного подключения
     * @param sink получатель событий запуска; может быть {@code null}
     */
    public void autostartScriptsForNode(String nodeId, Consumer<LuaScriptEvent> sink) {
        String normalizedNodeId = normalizeNodeId(nodeId);
        if (normalizedNodeId.isBlank()) {
            return;
        }
        List<LuaScript> scripts = LuaScriptService.getInstance().listScripts().stream()
                .filter(LuaScript::isAutostart)
                .filter(script -> script.getBotType() != LuaScript.BotType.AUTOMATION_BOT)
                .filter(script -> normalizedNodeId.equals(normalizeNodeId(script.getNodeId())))
                .toList();
        for (LuaScript script : scripts) {
            log.info("Autostarting Lua script '{}' for node {}", script.getName(), normalizedNodeId);
            runScript(script, sink);
        }
    }

    public void debugScript(LuaScript script, Set<Integer> breakpoints, Consumer<LuaScriptEvent> sink) {
        if (script == null) {
            return;
        }
        stopScript(script.getId(), sink);
        RuntimeTarget target = resolveTarget(script);
        Set<Integer> breakpointSet = breakpoints != null ? Set.copyOf(breakpoints) : Set.of();
        LuaRuntimeSession session = new LuaRuntimeSession(
                script,
                target,
                LuaScriptService.getInstance(),
                breakpointSet,
                breakpointSet.isEmpty(),
                null,
                event -> {
                    if (sink != null) {
                        sink.accept(event);
                    }
                },
                null,
                () -> sessions.remove(script.getId())
        );
        sessions.put(script.getId(), session);
        session.start();
    }

    public void deliverNodeSelection(long scriptId, LuaUiNodeSelection selection) {
        LuaRuntimeSession session = sessions.get(scriptId);
        if (session != null) {
            session.deliverNodeSelection(selection);
        }
    }

    public void stopScript(long scriptId, Consumer<LuaScriptEvent> sink) {
        LuaRuntimeSession session = sessions.remove(scriptId);
        if (session != null) {
            session.stop();
        }
    }

    public void stopAll() {
        List<LuaRuntimeSession> activeSessions = new ArrayList<>(sessions.values());
        sessions.clear();
        for (LuaRuntimeSession session : activeSessions) {
            session.stop();
        }
    }

    public boolean isRunning(long scriptId) {
        LuaRuntimeSession session = sessions.get(scriptId);
        return session != null && session.isRunning();
    }

    public boolean isPaused(long scriptId) {
        LuaRuntimeSession session = sessions.get(scriptId);
        return session != null && session.isPaused();
    }

    public void debugContinue(long scriptId) {
        LuaRuntimeSession session = sessions.get(scriptId);
        if (session != null) {
            session.debugContinue();
        }
    }

    public void debugStep(long scriptId) {
        LuaRuntimeSession session = sessions.get(scriptId);
        if (session != null) {
            session.debugStep();
        }
    }

    public Optional<LuaDebugSnapshot> debugSnapshot(long scriptId) {
        LuaRuntimeSession session = sessions.get(scriptId);
        return session != null ? Optional.ofNullable(session.debugSnapshot()) : Optional.empty();
    }

    public String checkSyntax(String code, String scriptName) {
        try {
            Globals globals = createCompileOnlyGlobals();
            globals.load(code != null ? code : "", scriptName != null ? scriptName : "lua-script");
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    static RuntimeTarget resolveSelectedTarget() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = manager.getSelectedConnectionEntry();
        if (entry == null || !entry.isConnected()) {
            return RuntimeTarget.empty("");
        }
        return resolveTarget(manager, entry);
    }

    private static RuntimeTarget resolveTarget(LuaScript script) {
        String nodeId = normalizeNodeId(script != null ? script.getNodeId() : null);
        if (nodeId.isBlank()) {
            return resolveSelectedTarget();
        }
        ConnectionManager manager = ConnectionManager.getInstance();
        for (ConnectionEntry entry : manager.getActiveConnectionEntries()) {
            String ownerNodeId = normalizeNodeId(manager.getOwnerNodeId(entry.getId()));
            if (nodeId.equals(ownerNodeId)) {
                return resolveTarget(manager, entry);
            }
        }
        return RuntimeTarget.empty(nodeId);
    }

    private static RuntimeTarget resolveTarget(ConnectionManager manager, ConnectionEntry entry) {
        DeviceState state = manager.getDeviceState(entry.getId());
        ProtocolHandler handler = manager.getProtocolHandler(entry.getId());
        ProtocolRuntime<?> runtime = manager.getProtocolRuntime(entry.getId());
        MeshCoreCompanionProtocolRuntime meshCoreRuntime =
                runtime instanceof MeshCoreCompanionProtocolRuntime companionRuntime
                        ? companionRuntime
                        : null;
        String ownerNodeId = manager.getOwnerNodeId(entry.getId());
        if ((ownerNodeId == null || ownerNodeId.isBlank()) && state != null) {
            ownerNodeId = state.getOwnerNodeId();
        }
        return new RuntimeTarget(entry.getId(), state, handler, meshCoreRuntime, ownerNodeId);
    }

    private static String normalizeNodeId(String nodeId) {
        return nodeId == null ? "" : nodeId.trim().toLowerCase();
    }

    static Globals createSandboxGlobals(DebugLib debugLib) {
        Globals globals = new Globals();
        globals.load(new BaseLib());
        globals.load(new PackageLib());
        globals.load(new Bit32Lib());
        globals.load(new TableLib());
        globals.load(new StringLib());
        globals.load(new CoroutineLib());
        globals.load(new MathLib());
        if (debugLib != null) {
            globals.load(debugLib);
        }
        LoadState.install(globals);
        LuaC.install(globals);
        removeUnsafeGlobals(globals);
        return globals;
    }

    private static Globals createCompileOnlyGlobals() {
        Globals globals = JsePlatform.standardGlobals();
        removeUnsafeGlobals(globals);
        return globals;
    }

    private static void removeUnsafeGlobals(Globals globals) {
        globals.set("dofile", LuaValue.NIL);
        globals.set("loadfile", LuaValue.NIL);
        globals.set("require", LuaValue.NIL);
        globals.set("module", LuaValue.NIL);
        globals.set("collectgarbage", LuaValue.NIL);
        globals.set("io", LuaValue.NIL);
        globals.set("os", LuaValue.NIL);
        globals.set("package", LuaValue.NIL);
        globals.set("debug", LuaValue.NIL);
        globals.set("luajava", LuaValue.NIL);
    }

    record RuntimeTarget(String connectionId,
                         DeviceState state,
                         ProtocolHandler handler,
                         MeshCoreCompanionProtocolRuntime meshCoreRuntime,
                         String ownerNodeId) {
        static RuntimeTarget empty(String ownerNodeId) {
            return new RuntimeTarget(null, null, null, null, ownerNodeId != null ? ownerNodeId : "");
        }

        boolean hasChatTransport() {
            return state != null && (handler != null || meshCoreRuntime != null);
        }

        String ownerNodeIdOrEmpty() {
            return ownerNodeId != null ? ownerNodeId : "";
        }
    }
}
