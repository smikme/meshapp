package com.meshtastic.client.lua;

import com.meshtastic.client.forms.FormMeshAppIde;
import com.meshtastic.client.forms.LuaExtensionForm;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.system.AllForms;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.system.FormManager;
import javafx.application.Platform;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Coordinates dynamic Lua extension navigation and embedded form sessions.
 */
public final class LuaExtensionManager {

    private static final String NAVIGATION_KEY_PREFIX = "lua-extension:";

    private static LuaExtensionManager instance;

    private final LuaScriptService scriptService = LuaScriptService.getInstance();
    private final LuaScriptRuntimeService runtimeService = LuaScriptRuntimeService.getInstance();
    private final Map<Long, LuaExtensionForm> forms = new ConcurrentHashMap<>();

    private LuaExtensionManager() {}

    public static synchronized LuaExtensionManager getInstance() {
        if (instance == null) {
            instance = new LuaExtensionManager();
        }
        return instance;
    }

    public static String navigationKey(long scriptId) {
        return NAVIGATION_KEY_PREFIX + scriptId;
    }

    public static List<LuaScript> enabledExtensionScripts() {
        return LuaScriptService.getInstance().listScripts().stream()
                .filter(LuaScript::isEnabled)
                .filter(script -> script.getBotType() == LuaScript.BotType.EXTENSION)
                .toList();
    }

    public void openExtension(long scriptId) {
        runExtension(scriptId, null, true);
    }

    public void runExtension(long scriptId, Consumer<LuaScriptEvent> eventSink) {
        runExtension(scriptId, eventSink, false);
    }

    private void runExtension(long scriptId, Consumer<LuaScriptEvent> eventSink, boolean requireEnabled) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> runExtension(scriptId, eventSink, requireEnabled));
            return;
        }

        LuaExtensionForm form = prepareExtensionForm(scriptId, requireEnabled);
        if (form == null) {
            return;
        }

        if (!runtimeService.isRunning(scriptId)) {
            LuaScript script = scriptService.findScript(scriptId).orElse(null);
            runtimeService.runExtension(script, form, event -> handleRuntimeEvent(event, eventSink));
        }
    }

    public void debugExtension(long scriptId,
                               Set<Integer> breakpoints,
                               Consumer<LuaScriptEvent> eventSink) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> debugExtension(scriptId, breakpoints, eventSink));
            return;
        }

        LuaExtensionForm form = prepareExtensionForm(scriptId, false);
        if (form == null) {
            return;
        }

        LuaScript script = scriptService.findScript(scriptId).orElse(null);
        runtimeService.debugExtension(script, form, breakpoints, event -> handleRuntimeEvent(event, eventSink));
    }

    private LuaExtensionForm prepareExtensionForm(long scriptId, boolean requireEnabled) {
        Optional<LuaScript> scriptOptional = scriptService.findScript(scriptId);
        if (scriptOptional.isEmpty()
                || (requireEnabled && !scriptOptional.get().isEnabled())
                || scriptOptional.get().getBotType() != LuaScript.BotType.EXTENSION) {
            Toast.show(Toast.Type.WARNING, I18n.t("meshIde.extension.unavailable"));
            reconcileEnabledExtensions();
            return null;
        }

        LuaScript script = scriptOptional.get();
        LuaExtensionForm form = forms.computeIfAbsent(scriptId, ignored -> new LuaExtensionForm(
                script,
                event -> runtimeService.deliverFormEvent(scriptId, event)));
        form.updateScript(script);
        FormManager.showDynamicForm(form, navigationKey(scriptId));
        return form;
    }

    public void reconcileEnabledExtensions() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::reconcileEnabledExtensions);
            return;
        }

        Map<Long, LuaScript> enabledScripts = enabledExtensionScripts().stream()
                .collect(Collectors.toMap(LuaScript::getId, script -> script));
        for (Map.Entry<Long, LuaExtensionForm> entry : forms.entrySet()) {
            LuaScript script = enabledScripts.get(entry.getKey());
            if (script != null) {
                entry.getValue().updateScript(script);
            }
        }

        for (Map.Entry<Long, LuaExtensionForm> entry : List.copyOf(forms.entrySet())) {
            if (enabledScripts.containsKey(entry.getKey())) {
                continue;
            }
            LuaExtensionForm form = forms.remove(entry.getKey());
            if (form == null) {
                continue;
            }
            boolean current = FormManager.getCurrentForm() == form;
            runtimeService.stopScript(entry.getKey(), null);
            form.dispose();
            if (current) {
                Form ide = AllForms.getForm(FormMeshAppIde.class);
                FormManager.showForm(ide);
            }
        }
    }

    private void handleRuntimeEvent(LuaScriptEvent event) {
        handleRuntimeEvent(event, null);
    }

    private void handleRuntimeEvent(LuaScriptEvent event, Consumer<LuaScriptEvent> eventSink) {
        if (event == null) {
            return;
        }
        if (eventSink != null) {
            eventSink.accept(event);
        }
        Platform.runLater(() -> {
            if (event.type() == LuaScriptEvent.Type.ERROR) {
                Toast.show(Toast.Type.ERROR, event.message());
            }
        });
    }
}
