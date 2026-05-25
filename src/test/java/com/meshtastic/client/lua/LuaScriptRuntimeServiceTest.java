package com.meshtastic.client.lua;

import com.meshtastic.client.TestEnvironmentSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LuaScriptRuntimeServiceTest {

    @TempDir
    Path tempHome;

    private LuaScriptService scriptService;
    private LuaScriptRuntimeService runtimeService;
    private List<LuaScriptEvent> events;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        scriptService = LuaScriptService.getInstance();
        runtimeService = LuaScriptRuntimeService.getInstance();
        events = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (runtimeService != null) {
            runtimeService.stopAll();
        }
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void kvStorageIsIsolatedPerScriptAndDeletedWithScript() {
        LuaScript first = scriptService.createScript("first", "");
        LuaScript second = scriptService.createScript("second", "");

        scriptService.setKv(first.getId(), "shared", "one");
        scriptService.setKv(second.getId(), "shared", "two");

        assertEquals("one", scriptService.getKv(first.getId(), "shared"));
        assertEquals("two", scriptService.getKv(second.getId(), "shared"));

        scriptService.deleteScript(first.getId());

        assertNull(scriptService.getKv(first.getId(), "shared"));
        assertEquals("two", scriptService.getKv(second.getId(), "shared"));
    }

    @Test
    void scriptSettingsArePersisted() {
        LuaScript script = scriptService.createScript("settings", "mesh.log('ok')");

        LuaScript saved = scriptService.saveScriptSettings(
                script.getId(),
                "settings-renamed",
                false,
                "!abcdef12",
                LuaScript.BotType.AUTOMATION_BOT,
                "@bot_1");

        assertEquals("settings-renamed", saved.getName());
        assertFalse(saved.isAutostart());
        assertEquals("!abcdef12", saved.getNodeId());
        assertEquals(LuaScript.BotType.AUTOMATION_BOT, saved.getBotType());
        assertEquals("@bot_1", saved.getAutomationName());

        LuaScript reloaded = scriptService.findScript(script.getId()).orElseThrow();
        assertEquals("settings-renamed", reloaded.getName());
        assertFalse(reloaded.isAutostart());
        assertEquals("!abcdef12", reloaded.getNodeId());
        assertEquals(LuaScript.BotType.AUTOMATION_BOT, reloaded.getBotType());
        assertEquals("@bot_1", reloaded.getAutomationName());
    }

    @Test
    void draftScriptIsNotPersistedUntilCreatedWithSettings() {
        LuaScript draft = scriptService.createDraftScript();

        assertEquals(0L, draft.getId());
        assertTrue(scriptService.listScripts().isEmpty());

        LuaScript created = scriptService.createScript(
                draft.getName(),
                draft.getCode(),
                false,
                "!abcdef12",
                LuaScript.BotType.AUTOMATION_BOT,
                "@bot_1");

        assertEquals(1, scriptService.listScripts().size());
        assertEquals(draft.getName(), created.getName());
        assertEquals(draft.getCode(), created.getCode());
        assertFalse(created.isAutostart());
        assertEquals("!abcdef12", created.getNodeId());
        assertEquals(LuaScript.BotType.AUTOMATION_BOT, created.getBotType());
        assertEquals("@bot_1", created.getAutomationName());
    }

    @Test
    void sandboxDoesNotExposeUnsafeLibraries() {
        LuaScript script = scriptService.createScript("sandbox", String.join("\n",
                "mesh.kv.set('debug', tostring(debug))",
                "mesh.kv.set('os', tostring(os))",
                "mesh.kv.set('io', tostring(io))",
                "mesh.kv.set('package', tostring(package))",
                "mesh.kv.set('require', tostring(require))",
                "mesh.kv.set('luajava', tostring(luajava))"));

        runtimeService.runScript(script, events::add);

        awaitCondition(() -> "nil".equals(scriptService.getKv(script.getId(), "debug")),
                "Lua sandbox script did not finish");

        assertEquals("nil", scriptService.getKv(script.getId(), "debug"));
        assertEquals("nil", scriptService.getKv(script.getId(), "os"));
        assertEquals("nil", scriptService.getKv(script.getId(), "io"));
        assertEquals("nil", scriptService.getKv(script.getId(), "package"));
        assertEquals("nil", scriptService.getKv(script.getId(), "require"));
        assertEquals("nil", scriptService.getKv(script.getId(), "luajava"));
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void debugScriptRunsToBreakpointCapturesVariablesAndContinues() {
        LuaScript script = scriptService.createScript("debug", String.join("\n",
                "global_count = 7",
                "local count = 1",
                "count = count + 1",
                "mesh.kv.set('count', tostring(count))"));

        runtimeService.debugScript(script, Set.of(3), events::add);

        awaitCondition(() -> runtimeService.isPaused(script.getId())
                        && runtimeService.debugSnapshot(script.getId())
                        .map(snapshot -> snapshot.line() == 3)
                        .orElse(false),
                "Lua debugger did not pause on breakpoint");
        LuaDebugSnapshot pause = runtimeService.debugSnapshot(script.getId()).orElseThrow();
        assertEquals(3, pause.line());
        assertTrue(pause.variables().stream().anyMatch(variable ->
                "local".equals(variable.scope())
                        && "count".equals(variable.name())
                        && "1".equals(variable.value())));
        assertTrue(pause.variables().stream().anyMatch(variable ->
                "global".equals(variable.scope())
                        && "global_count".equals(variable.name())
                        && "7".equals(variable.value())));

        runtimeService.debugContinue(script.getId());

        awaitCondition(() -> !runtimeService.isRunning(script.getId()), "Lua debugger did not finish");

        assertEquals("2", scriptService.getKv(script.getId(), "count"));
        assertEquals(1, events.stream()
                .filter(event -> event.type() == LuaScriptEvent.Type.DEBUG_PAUSED)
                .count());
        assertTrue(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.DEBUG_RESUMED));
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void debugScriptWithoutBreakpointsPausesOnStartForManualStepping() {
        LuaScript script = scriptService.createScript("debug-step", String.join("\n",
                "local count = 1",
                "count = count + 1"));

        runtimeService.debugScript(script, Set.of(), events::add);

        awaitCondition(() -> runtimeService.isPaused(script.getId()), "Lua debugger did not pause on start");
        LuaDebugSnapshot pause = runtimeService.debugSnapshot(script.getId()).orElseThrow();
        assertEquals(1, pause.line());

        runtimeService.debugContinue(script.getId());

        awaitCondition(() -> !runtimeService.isRunning(script.getId()), "Lua debugger did not finish");
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    private static void awaitCondition(BooleanSupplier condition, String timeoutMessage) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for condition");
            }
        }
        fail(timeoutMessage);
    }
}
