package com.meshtastic.client.lua;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meshtastic.client.forms.LuaExtensionForm;
import com.meshtastic.client.protocol.rpc.RemoteChatJson;
import com.meshtastic.client.protocol.rpc.RemoteLuaScriptJson;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import com.meshtastic.client.rpc.RpcEventListener;
import com.meshtastic.client.system.FormManager;
import javafx.application.Platform;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * RPC Lua script data source backed by a remote MeshApp host.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RemoteLuaScriptDataSource implements LuaScriptDataSource {

    private static final Duration REMOTE_RPC_TIMEOUT = Duration.ofSeconds(15);

    private final RemoteRpcState rpcState;
    private final RpcEventListener eventListener = this::handleRemoteEvent;
    private final ConcurrentMap<Long, Consumer<LuaScriptEvent>> sinksByScriptId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Consumer<LuaUiNodePickRequest>> nodePickSinksByScriptId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, LuaExtensionForm> remoteExtensionForms = new ConcurrentHashMap<>();

    public RemoteLuaScriptDataSource(RemoteRpcState rpcState) {
        this.rpcState = rpcState;
        this.rpcState.client().addEventListener(eventListener);
    }

    @Override
    public List<LuaScript> listScripts() {
        return RemoteLuaScriptJson.parseScripts(call("lua.list", new JsonObject()));
    }

    @Override
    public Optional<LuaScript> findScript(long scriptId) {
        return Optional.ofNullable(RemoteLuaScriptJson.parseResultScript(
                call("lua.get", RemoteLuaScriptJson.scriptIdParams(scriptId))));
    }

    @Override
    public LuaScript createScript() {
        return RemoteLuaScriptJson.parseResultScript(call("lua.createDefault", new JsonObject()));
    }

    @Override
    public LuaScript createDraftScript() {
        return RemoteLuaScriptJson.parseResultScript(call("lua.draft", new JsonObject()));
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
        return RemoteLuaScriptJson.parseResultScript(call("lua.create",
                RemoteLuaScriptJson.createParams(name, code, enabled, icon, nodeId, botType,
                        automationName, description, author)));
    }

    @Override
    public LuaScript saveScript(long scriptId, String name, String code, boolean enabled) {
        return RemoteLuaScriptJson.parseResultScript(call("lua.save",
                RemoteLuaScriptJson.saveParams(scriptId, name, code, enabled)));
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
        return RemoteLuaScriptJson.parseResultScript(call("lua.saveSettings",
                RemoteLuaScriptJson.saveSettingsParams(
                        scriptId, name, enabled, icon, nodeId, botType, automationName, description, author)));
    }

    @Override
    public void deleteScript(long scriptId) {
        call("lua.delete", RemoteLuaScriptJson.scriptIdParams(scriptId));
        sinksByScriptId.remove(scriptId);
    }

    @Override
    public LuaScriptService.ScriptImportResult importScriptJson(String json) {
        return RemoteLuaScriptJson.parseImportResult(
                call("lua.importJson", RemoteLuaScriptJson.importJsonParams(json)));
    }

    @Override
    public LuaScriptService.ScriptImportResult importScriptExport(LuaScriptService.LuaScriptExportFile exportFile) {
        return RemoteLuaScriptJson.parseImportResult(
                call("lua.importExport", RemoteLuaScriptJson.importExportParams(exportFile)));
    }

    @Override
    public String exportScriptJson(long scriptId) {
        return RemoteLuaScriptJson.parseExportJson(call("lua.export", RemoteLuaScriptJson.scriptIdParams(scriptId)));
    }

    @Override
    public boolean isRunning(long scriptId) {
        return RemoteLuaScriptJson.parseRunning(call("lua.runningState", RemoteLuaScriptJson.scriptIdParams(scriptId)));
    }

    @Override
    public boolean isPaused(long scriptId) {
        return RemoteLuaScriptJson.parsePaused(call("lua.runningState", RemoteLuaScriptJson.scriptIdParams(scriptId)));
    }

    @Override
    public void runScript(LuaScript script, Consumer<LuaScriptEvent> sink) {
        if (script == null) {
            return;
        }
        rememberSink(script.getId(), sink);
        call("lua.run", RemoteLuaScriptJson.scriptIdParams(script.getId()));
    }

    @Override
    public void runAutomationCommand(LuaScript script,
                                     LuaAutomationCommand command,
                                     Consumer<LuaScriptEvent> sink,
                                     Consumer<LuaUiNodePickRequest> uiNodePickSink) {
        if (script == null || command == null) {
            return;
        }
        rememberSink(script.getId(), sink);
        if (uiNodePickSink != null) {
            nodePickSinksByScriptId.put(script.getId(), uiNodePickSink);
        }
        call("lua.automation.run", RemoteLuaScriptJson.automationCommandParams(script.getId(), command));
    }

    @Override
    public void deliverNodeSelection(long scriptId, LuaUiNodeSelection selection) {
        call("lua.ui.nodeSelection", RemoteLuaScriptJson.nodeSelectionParams(scriptId, selection));
    }

    @Override
    public void debugScript(LuaScript script, Set<Integer> breakpoints, Consumer<LuaScriptEvent> sink) {
        if (script == null) {
            return;
        }
        rememberSink(script.getId(), sink);
        call("lua.debug", RemoteLuaScriptJson.debugParams(script.getId(), breakpoints));
    }

    @Override
    public void stopScript(long scriptId, Consumer<LuaScriptEvent> sink) {
        rememberSink(scriptId, sink);
        call("lua.stop", RemoteLuaScriptJson.scriptIdParams(scriptId));
    }

    @Override
    public void debugContinue(long scriptId) {
        call("lua.debugContinue", RemoteLuaScriptJson.scriptIdParams(scriptId));
    }

    @Override
    public void debugStep(long scriptId) {
        call("lua.debugStep", RemoteLuaScriptJson.scriptIdParams(scriptId));
    }

    @Override
    public Optional<LuaDebugSnapshot> debugSnapshot(long scriptId) {
        return Optional.ofNullable(RemoteLuaScriptJson.parseDebugSnapshot(
                call("lua.debugSnapshot", RemoteLuaScriptJson.scriptIdParams(scriptId))));
    }

    @Override
    public Map<String, String> listKv(long scriptId) {
        return RemoteLuaScriptJson.parseKv(call("lua.kv.list", RemoteLuaScriptJson.scriptIdParams(scriptId)));
    }

    @Override
    public void setKv(long scriptId, String key, String value) {
        call("lua.kv.set", RemoteLuaScriptJson.kvSetParams(scriptId, key, value));
    }

    @Override
    public boolean deleteKv(long scriptId, String key) {
        return RemoteLuaScriptJson.parseDeleted(call("lua.kv.delete", RemoteLuaScriptJson.kvDeleteParams(scriptId, key)));
    }

    @Override
    public void clearKv(long scriptId) {
        call("lua.kv.clear", RemoteLuaScriptJson.scriptIdParams(scriptId));
    }

    @Override
    public void close() {
        rpcState.client().removeEventListener(eventListener);
        sinksByScriptId.clear();
        nodePickSinksByScriptId.clear();
        remoteExtensionForms.values().forEach(LuaExtensionForm::dispose);
        remoteExtensionForms.clear();
    }

    private void rememberSink(long scriptId, Consumer<LuaScriptEvent> sink) {
        if (sink != null && scriptId > 0) {
            sinksByScriptId.put(scriptId, sink);
        }
    }

    private JsonElement call(String method, JsonObject params) {
        try {
            return rpcState.client().call(method, params, REMOTE_RPC_TIMEOUT).join();
        } catch (CompletionException e) {
            throw new IllegalStateException(RemoteChatJson.errorMessage(e), e);
        }
    }

    private void handleRemoteEvent(String event, JsonElement payload) {
        if ("lua.form.command".equals(event)) {
            handleRemoteFormCommand(payload);
            return;
        }
        if ("lua.ui.nodePick.request".equals(event)) {
            handleRemoteNodePickRequest(payload);
            return;
        }
        if (!"lua.runtime.event".equals(event)) {
            return;
        }
        LuaScriptEvent scriptEvent = RemoteLuaScriptJson.parseEvent(payload);
        Consumer<LuaScriptEvent> sink = sinksByScriptId.get(scriptEvent.scriptId());
        if (sink != null) {
            sink.accept(scriptEvent);
        }
        if (scriptEvent.type() == LuaScriptEvent.Type.STOPPED) {
            if (sink != null) {
                sinksByScriptId.remove(scriptEvent.scriptId(), sink);
            } else {
                sinksByScriptId.remove(scriptEvent.scriptId());
            }
            nodePickSinksByScriptId.remove(scriptEvent.scriptId());
            closeRemoteExtensionForm(scriptEvent.scriptId());
        }
    }

    private void handleRemoteNodePickRequest(JsonElement payload) {
        LuaUiNodePickRequest request = RemoteLuaScriptJson.parseNodePickRequest(payload);
        Consumer<LuaUiNodePickRequest> sink = nodePickSinksByScriptId.get(request.scriptId());
        if (sink != null) {
            sink.accept(request);
        }
    }

    private void handleRemoteFormCommand(JsonElement payload) {
        RemoteLuaScriptJson.FormCommand command = RemoteLuaScriptJson.parseFormCommand(payload);
        Platform.runLater(() -> applyRemoteFormCommand(command));
    }

    private void applyRemoteFormCommand(RemoteLuaScriptJson.FormCommand command) {
        if (command == null || command.scriptId() <= 0) {
            return;
        }
        if ("close".equals(command.command())) {
            closeRemoteExtensionForm(command.scriptId());
            return;
        }
        LuaExtensionForm form = remoteExtensionForms.computeIfAbsent(command.scriptId(), ignored ->
                new LuaExtensionForm(command.script(), this::sendRemoteFormEvent));
        if (command.script() != null) {
            form.updateScript(command.script());
        }
        switch (command.command()) {
            case "show" -> FormManager.showDynamicForm(form, LuaExtensionManager.navigationKey(command.scriptId()));
            case "title" -> form.setFormTitle(command.title());
            case "clear" -> form.clearForm();
            case "add" -> form.addFormComponent(command.spec());
            case "update" -> form.updateFormComponent(command.componentId(), command.spec());
            case "remove" -> form.removeFormComponent(command.componentId());
            case "value" -> sendRemoteFormValue(command.scriptId(), command.requestId(),
                    form.formComponentValue(command.componentId()));
            default -> {
            }
        }
    }

    private void sendRemoteFormEvent(LuaFormEvent event) {
        callAsync("lua.form.event", RemoteLuaScriptJson.formEventParams(event));
    }

    private void sendRemoteFormValue(long scriptId, String requestId, Object value) {
        callAsync("lua.form.valueResult", RemoteLuaScriptJson.formValueResultParams(scriptId, requestId, value));
    }

    private void closeRemoteExtensionForm(long scriptId) {
        LuaExtensionForm form = remoteExtensionForms.remove(scriptId);
        if (form != null) {
            form.dispose();
        }
    }

    private void callAsync(String method, JsonObject params) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                call(method, params);
            } catch (RuntimeException ignored) {
                // The runtime may already be stopped or the RPC connection may have closed.
            }
        });
    }
}
