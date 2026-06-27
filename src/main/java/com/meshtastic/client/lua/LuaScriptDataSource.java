package com.meshtastic.client.lua;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Data source used by MeshApp IDE forms for local or RPC-hosted Lua scripts.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface LuaScriptDataSource extends AutoCloseable {

    List<LuaScript> listScripts();

    Optional<LuaScript> findScript(long scriptId);

    LuaScript createScript();

    LuaScript createDraftScript();

    LuaScript createScript(String name,
                           String code,
                           boolean enabled,
                           String icon,
                           String nodeId,
                           LuaScript.BotType botType,
                           String automationName,
                           String description,
                           String author);

    LuaScript saveScript(long scriptId, String name, String code, boolean enabled);

    LuaScript saveScriptSettings(long scriptId,
                                 String name,
                                 boolean enabled,
                                 String icon,
                                 String nodeId,
                                 LuaScript.BotType botType,
                                 String automationName,
                                 String description,
                                 String author);

    void deleteScript(long scriptId);

    LuaScriptService.ScriptImportResult importScriptJson(String json);

    LuaScriptService.ScriptImportResult importScriptExport(LuaScriptService.LuaScriptExportFile exportFile);

    String exportScriptJson(long scriptId);

    boolean isRunning(long scriptId);

    boolean isPaused(long scriptId);

    void runScript(LuaScript script, Consumer<LuaScriptEvent> sink);

    void runAutomationCommand(LuaScript script,
                              LuaAutomationCommand command,
                              Consumer<LuaScriptEvent> sink,
                              Consumer<LuaUiNodePickRequest> uiNodePickSink);

    void deliverNodeSelection(long scriptId, LuaUiNodeSelection selection);

    void debugScript(LuaScript script, Set<Integer> breakpoints, Consumer<LuaScriptEvent> sink);

    void stopScript(long scriptId, Consumer<LuaScriptEvent> sink);

    void debugContinue(long scriptId);

    void debugStep(long scriptId);

    Optional<LuaDebugSnapshot> debugSnapshot(long scriptId);

    Map<String, String> listKv(long scriptId);

    void setKv(long scriptId, String key, String value);

    boolean deleteKv(long scriptId, String key);

    void clearKv(long scriptId);

    @Override
    default void close() {
        // no-op
    }
}
