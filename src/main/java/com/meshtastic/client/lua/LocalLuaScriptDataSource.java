package com.meshtastic.client.lua;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Local Lua script data source backed by the application database and runtime.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LocalLuaScriptDataSource implements LuaScriptDataSource {

    private final LuaScriptService scriptService;
    private final LuaScriptRuntimeService runtimeService;

    public LocalLuaScriptDataSource() {
        this(LuaScriptService.getInstance(), LuaScriptRuntimeService.getInstance());
    }

    LocalLuaScriptDataSource(LuaScriptService scriptService, LuaScriptRuntimeService runtimeService) {
        this.scriptService = scriptService;
        this.runtimeService = runtimeService;
    }

    @Override
    public List<LuaScript> listScripts() {
        return scriptService.listScripts();
    }

    @Override
    public Optional<LuaScript> findScript(long scriptId) {
        return scriptService.findScript(scriptId);
    }

    @Override
    public LuaScript createScript() {
        return scriptService.createScript();
    }

    @Override
    public LuaScript createDraftScript() {
        return scriptService.createDraftScript();
    }

    @Override
    public LuaScript createScript(String name,
                                  String code,
                                  boolean enabled,
                                  String icon,
                                  String nodeId,
                                  LuaScript.BotType botType,
                                  String automationName,
                                  String description,
                                  String author) {
        return scriptService.createScript(name, code, enabled, icon, nodeId, botType, automationName, description, author);
    }

    @Override
    public LuaScript saveScript(long scriptId, String name, String code, boolean enabled) {
        return scriptService.saveScript(scriptId, name, code, enabled);
    }

    @Override
    public LuaScript saveScriptSettings(long scriptId,
                                        String name,
                                        boolean enabled,
                                        String icon,
                                        String nodeId,
                                        LuaScript.BotType botType,
                                        String automationName,
                                        String description,
                                        String author) {
        return scriptService.saveScriptSettings(
                scriptId, name, enabled, icon, nodeId, botType, automationName, description, author);
    }

    @Override
    public void deleteScript(long scriptId) {
        scriptService.deleteScript(scriptId);
    }

    @Override
    public LuaScriptService.ScriptImportResult importScriptJson(String json) {
        return scriptService.importScriptJson(json);
    }

    @Override
    public LuaScriptService.ScriptImportResult importScriptExport(LuaScriptService.LuaScriptExportFile exportFile) {
        return scriptService.importScriptExport(exportFile);
    }

    @Override
    public String exportScriptJson(long scriptId) {
        return scriptService.exportScriptJson(scriptId);
    }

    @Override
    public boolean isRunning(long scriptId) {
        return runtimeService.isRunning(scriptId);
    }

    @Override
    public boolean isPaused(long scriptId) {
        return runtimeService.isPaused(scriptId);
    }

    @Override
    public void runScript(LuaScript script, Consumer<LuaScriptEvent> sink) {
        if (script != null && script.getBotType() == LuaScript.BotType.EXTENSION) {
            LuaExtensionManager.getInstance().runExtension(script.getId(), sink);
            return;
        }
        runtimeService.runScript(script, sink);
    }

    @Override
    public void runAutomationCommand(LuaScript script,
                                     LuaAutomationCommand command,
                                     Consumer<LuaScriptEvent> sink,
                                     Consumer<LuaUiNodePickRequest> uiNodePickSink) {
        runtimeService.runAutomationCommand(script, command, sink, uiNodePickSink);
    }

    @Override
    public void deliverNodeSelection(long scriptId, LuaUiNodeSelection selection) {
        runtimeService.deliverNodeSelection(scriptId, selection);
    }

    @Override
    public void debugScript(LuaScript script, Set<Integer> breakpoints, Consumer<LuaScriptEvent> sink) {
        if (script != null && script.getBotType() == LuaScript.BotType.EXTENSION) {
            LuaExtensionManager.getInstance().debugExtension(script.getId(), breakpoints, sink);
            return;
        }
        runtimeService.debugScript(script, breakpoints, sink);
    }

    @Override
    public void stopScript(long scriptId, Consumer<LuaScriptEvent> sink) {
        runtimeService.stopScript(scriptId, sink);
    }

    @Override
    public void debugContinue(long scriptId) {
        runtimeService.debugContinue(scriptId);
    }

    @Override
    public void debugStep(long scriptId) {
        runtimeService.debugStep(scriptId);
    }

    @Override
    public Optional<LuaDebugSnapshot> debugSnapshot(long scriptId) {
        return runtimeService.debugSnapshot(scriptId);
    }

    @Override
    public Map<String, String> listKv(long scriptId) {
        return scriptService.listKv(scriptId);
    }

    @Override
    public void setKv(long scriptId, String key, String value) {
        scriptService.setKv(scriptId, key, value);
    }

    @Override
    public boolean deleteKv(long scriptId, String key) {
        return scriptService.deleteKv(scriptId, key);
    }

    @Override
    public void clearKv(long scriptId) {
        scriptService.clearKv(scriptId);
    }
}
