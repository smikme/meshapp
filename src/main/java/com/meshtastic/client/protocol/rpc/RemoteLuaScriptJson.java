package com.meshtastic.client.protocol.rpc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.meshtastic.client.lua.LuaAutomationCommand;
import com.meshtastic.client.lua.LuaDebugSnapshot;
import com.meshtastic.client.lua.LuaFormComponentSpec;
import com.meshtastic.client.lua.LuaFormEvent;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptEvent;
import com.meshtastic.client.lua.LuaScriptRuntimeService;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.lua.LuaUiBotNotice;
import com.meshtastic.client.lua.LuaUiNodePickRequest;
import com.meshtastic.client.lua.LuaUiNodeSelection;
import com.meshtastic.client.model.NodeData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON mapping helpers for MeshApp IDE RPC methods.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RemoteLuaScriptJson {

    private static final Gson GSON = new Gson();

    private RemoteLuaScriptJson() {
    }

    public record FormCommand(long scriptId,
                              String command,
                              String requestId,
                              String componentId,
                              String title,
                              LuaFormComponentSpec spec,
                              LuaScript script) {
    }

    public static JsonObject scriptsToJson(List<LuaScript> scripts, LuaScriptRuntimeService runtimeService) {
        JsonArray items = new JsonArray();
        if (scripts != null) {
            for (LuaScript script : scripts) {
                items.add(scriptToJson(script, runtimeService));
            }
        }
        JsonObject object = new JsonObject();
        object.add("items", items);
        return object;
    }

    public static JsonObject scriptResult(LuaScript script, LuaScriptRuntimeService runtimeService) {
        JsonObject object = new JsonObject();
        object.add("script", scriptToJson(script, runtimeService));
        return object;
    }

    public static List<LuaScript> parseScripts(JsonElement element) {
        JsonObject object = object(element);
        JsonElement items = object.get("items");
        if (items == null || !items.isJsonArray()) {
            return List.of();
        }
        List<LuaScript> result = new ArrayList<>();
        for (JsonElement item : items.getAsJsonArray()) {
            LuaScript script = parseScript(item);
            if (script != null) {
                result.add(script);
            }
        }
        return result;
    }

    public static LuaScript parseResultScript(JsonElement element) {
        return parseScript(object(element).get("script"));
    }

    public static LuaScript parseScript(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        return new LuaScript(
                longField(object, "id"),
                stringField(object, "guid"),
                stringField(object, "icon"),
                stringField(object, "name"),
                stringField(object, "code"),
                longField(object, "version"),
                stringField(object, "description"),
                stringField(object, "author"),
                booleanField(object, "enabled"),
                stringField(object, "nodeId"),
                LuaScript.BotType.fromStorage(stringField(object, "botType")),
                stringField(object, "automationName"),
                longField(object, "createdAt"),
                longField(object, "updatedAt"),
                longField(object, "lastRunAt"),
                stringField(object, "lastStatus"),
                nullableString(object, "lastError"));
    }

    public static JsonObject scriptIdParams(long scriptId) {
        JsonObject object = new JsonObject();
        object.addProperty("scriptId", scriptId);
        return object;
    }

    public static JsonObject createParams(String name,
                                          String code,
                                          boolean enabled,
                                          String icon,
                                          String nodeId,
                                          LuaScript.BotType botType,
                                          String automationName,
                                          String description,
                                          String author) {
        JsonObject object = new JsonObject();
        addScriptSettings(object, name, enabled, icon, nodeId, botType, automationName, description, author);
        object.addProperty("code", code != null ? code : "");
        return object;
    }

    public static JsonObject saveParams(long scriptId, String name, String code, boolean enabled) {
        JsonObject object = scriptIdParams(scriptId);
        object.addProperty("name", name != null ? name : "");
        object.addProperty("code", code != null ? code : "");
        object.addProperty("enabled", enabled);
        return object;
    }

    public static JsonObject saveSettingsParams(long scriptId,
                                                String name,
                                                boolean enabled,
                                                String icon,
                                                String nodeId,
                                                LuaScript.BotType botType,
                                                String automationName,
                                                String description,
                                                String author) {
        JsonObject object = scriptIdParams(scriptId);
        addScriptSettings(object, name, enabled, icon, nodeId, botType, automationName, description, author);
        return object;
    }

    public static JsonObject importJsonParams(String json) {
        JsonObject object = new JsonObject();
        object.addProperty("json", json != null ? json : "");
        return object;
    }

    public static JsonObject importExportParams(LuaScriptService.LuaScriptExportFile exportFile) {
        JsonObject object = new JsonObject();
        object.add("exportFile", GSON.toJsonTree(exportFile));
        return object;
    }

    public static JsonObject exportJsonResult(String json) {
        JsonObject object = new JsonObject();
        object.addProperty("json", json != null ? json : "");
        return object;
    }

    public static String parseExportJson(JsonElement element) {
        return stringField(object(element), "json");
    }

    public static JsonObject importResultToJson(LuaScriptService.ScriptImportResult result,
                                                LuaScriptRuntimeService runtimeService) {
        JsonObject object = new JsonObject();
        object.add("script", scriptToJson(result != null ? result.script() : null, runtimeService));
        object.addProperty("updated", result != null && result.updated());
        return object;
    }

    public static LuaScriptService.ScriptImportResult parseImportResult(JsonElement element) {
        JsonObject object = object(element);
        return new LuaScriptService.ScriptImportResult(
                parseScript(object.get("script")),
                booleanField(object, "updated"));
    }

    public static JsonObject kvToJson(Map<String, String> values) {
        JsonObject object = new JsonObject();
        JsonObject items = new JsonObject();
        if (values != null) {
            values.forEach((key, value) -> items.addProperty(key, value != null ? value : ""));
        }
        object.add("items", items);
        return object;
    }

    public static Map<String, String> parseKv(JsonElement element) {
        JsonObject items = object(object(element).get("items"));
        Map<String, String> result = new java.util.TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
            JsonElement value = entry.getValue();
            result.put(entry.getKey(), value != null && !value.isJsonNull() ? value.getAsString() : "");
        }
        return result;
    }

    public static JsonObject kvSetParams(long scriptId, String key, String value) {
        JsonObject object = scriptIdParams(scriptId);
        object.addProperty("key", key != null ? key : "");
        object.addProperty("value", value != null ? value : "");
        return object;
    }

    public static JsonObject kvDeleteParams(long scriptId, String key) {
        JsonObject object = scriptIdParams(scriptId);
        object.addProperty("key", key != null ? key : "");
        return object;
    }

    public static JsonObject deletedResult(boolean deleted) {
        JsonObject object = new JsonObject();
        object.addProperty("deleted", deleted);
        return object;
    }

    public static boolean parseDeleted(JsonElement element) {
        return booleanField(object(element), "deleted");
    }

    public static JsonObject runningStateToJson(long scriptId, LuaScriptRuntimeService runtimeService) {
        JsonObject object = new JsonObject();
        object.addProperty("scriptId", scriptId);
        object.addProperty("running", runtimeService != null && runtimeService.isRunning(scriptId));
        object.addProperty("paused", runtimeService != null && runtimeService.isPaused(scriptId));
        return object;
    }

    public static boolean parseRunning(JsonElement element) {
        return booleanField(object(element), "running");
    }

    public static boolean parsePaused(JsonElement element) {
        return booleanField(object(element), "paused");
    }

    public static JsonObject debugParams(long scriptId, Iterable<Integer> breakpoints) {
        JsonObject object = scriptIdParams(scriptId);
        JsonArray array = new JsonArray();
        if (breakpoints != null) {
            for (Integer breakpoint : breakpoints) {
                if (breakpoint != null && breakpoint > 0) {
                    array.add(breakpoint);
                }
            }
        }
        object.add("breakpoints", array);
        return object;
    }

    public static List<Integer> parseBreakpoints(JsonElement element) {
        JsonElement breakpoints = object(element).get("breakpoints");
        if (breakpoints == null || !breakpoints.isJsonArray()) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (JsonElement item : breakpoints.getAsJsonArray()) {
            if (item != null && item.isJsonPrimitive()) {
                result.add(item.getAsInt());
            }
        }
        return result;
    }

    public static JsonObject automationCommandParams(long scriptId, LuaAutomationCommand command) {
        JsonObject object = scriptIdParams(scriptId);
        object.add("command", GSON.toJsonTree(command));
        return object;
    }

    public static LuaAutomationCommand parseAutomationCommand(JsonElement element) {
        JsonElement command = object(element).get("command");
        if (command == null || !command.isJsonObject()) {
            return null;
        }
        return GSON.fromJson(command, LuaAutomationCommand.class);
    }

    public static JsonObject eventToJson(LuaScriptEvent event) {
        JsonObject object = new JsonObject();
        if (event == null) {
            return object;
        }
        object.addProperty("type", event.type() != null ? event.type().name() : LuaScriptEvent.Type.INFO.name());
        object.addProperty("scriptId", event.scriptId());
        object.addProperty("message", event.message() != null ? event.message() : "");
        Throwable error = event.error();
        if (error != null && error.getMessage() != null) {
            object.addProperty("errorMessage", error.getMessage());
        }
        if (event.payload() instanceof LuaUiBotNotice notice) {
            object.add("payload", GSON.toJsonTree(notice));
        }
        return object;
    }

    public static LuaScriptEvent parseEvent(JsonElement element) {
        JsonObject object = object(element);
        LuaScriptEvent.Type type;
        try {
            type = LuaScriptEvent.Type.valueOf(stringField(object, "type"));
        } catch (IllegalArgumentException e) {
            type = LuaScriptEvent.Type.INFO;
        }
        String errorMessage = stringField(object, "errorMessage");
        Throwable error = errorMessage.isBlank() ? null : new IllegalStateException(errorMessage);
        Object payload = null;
        JsonElement payloadElement = object.get("payload");
        if (type == LuaScriptEvent.Type.UI_BOT_NOTICE
                && payloadElement != null
                && payloadElement.isJsonObject()) {
            payload = GSON.fromJson(payloadElement, LuaUiBotNotice.class);
        }
        return new LuaScriptEvent(type, longField(object, "scriptId"), stringField(object, "message"), error, payload);
    }

    public static JsonObject nodePickRequestToJson(LuaUiNodePickRequest request) {
        JsonObject object = new JsonObject();
        if (request != null) {
            object.addProperty("scriptId", request.scriptId());
            object.addProperty("requestId", request.requestId());
            object.addProperty("source", request.source());
            object.addProperty("name", request.name());
            object.addProperty("prompt", request.prompt());
            object.addProperty("query", request.query());
            object.addProperty("chatType", request.chatType());
            object.addProperty("chatKey", request.chatKey());
        }
        return object;
    }

    public static LuaUiNodePickRequest parseNodePickRequest(JsonElement element) {
        JsonObject object = object(element);
        return new LuaUiNodePickRequest(
                longField(object, "scriptId"),
                stringField(object, "requestId"),
                stringField(object, "source"),
                stringField(object, "name"),
                stringField(object, "prompt"),
                stringField(object, "query"),
                stringField(object, "chatType"),
                stringField(object, "chatKey"));
    }

    public static JsonObject nodeSelectionParams(long scriptId, LuaUiNodeSelection selection) {
        JsonObject object = scriptIdParams(scriptId);
        if (selection != null) {
            object.addProperty("requestId", selection.requestId());
            object.addProperty("source", selection.source());
            object.addProperty("name", selection.name());
            object.addProperty("selected", selection.selected());
            object.addProperty("chatType", selection.chatType());
            object.addProperty("chatKey", selection.chatKey());
            if (selection.node() != null) {
                object.add("node", RemoteNodeJson.nodeToJson(selection.node(), false, false));
            }
        }
        return object;
    }

    public static LuaUiNodeSelection parseNodeSelection(JsonElement element) {
        JsonObject object = object(element);
        NodeData node = null;
        JsonElement nodeElement = object.get("node");
        if (nodeElement != null && nodeElement.isJsonObject()) {
            node = RemoteNodeJson.parseNode(nodeElement.getAsJsonObject());
        }
        return new LuaUiNodeSelection(
                stringField(object, "requestId"),
                stringField(object, "source"),
                stringField(object, "name"),
                booleanField(object, "selected"),
                node,
                stringField(object, "chatType"),
                stringField(object, "chatKey"));
    }

    public static JsonObject formCommandToJson(LuaScript script,
                                               String command,
                                               String requestId,
                                               String componentId,
                                               String title,
                                               LuaFormComponentSpec spec) {
        JsonObject object = new JsonObject();
        object.addProperty("scriptId", script != null ? script.getId() : 0L);
        object.addProperty("command", command != null ? command : "");
        object.addProperty("requestId", requestId != null ? requestId : "");
        object.addProperty("componentId", componentId != null ? componentId : "");
        object.addProperty("title", title != null ? title : "");
        object.add("script", scriptToJson(script, null));
        if (spec != null) {
            object.add("spec", GSON.toJsonTree(spec));
        }
        return object;
    }

    public static FormCommand parseFormCommand(JsonElement element) {
        JsonObject object = object(element);
        LuaFormComponentSpec spec = null;
        JsonElement specElement = object.get("spec");
        if (specElement != null && specElement.isJsonObject()) {
            spec = GSON.fromJson(specElement, LuaFormComponentSpec.class);
        }
        return new FormCommand(
                longField(object, "scriptId"),
                stringField(object, "command"),
                stringField(object, "requestId"),
                stringField(object, "componentId"),
                stringField(object, "title"),
                spec,
                parseScript(object.get("script")));
    }

    public static JsonObject formEventParams(LuaFormEvent event) {
        JsonObject object = new JsonObject();
        if (event != null) {
            object.addProperty("scriptId", event.scriptId());
            object.addProperty("componentId", event.componentId());
            object.addProperty("type", event.type());
            object.add("value", GSON.toJsonTree(event.value()));
            object.addProperty("text", event.text());
        }
        return object;
    }

    public static LuaFormEvent parseFormEvent(JsonElement element) {
        JsonObject object = object(element);
        JsonElement valueElement = object.get("value");
        Object value = valueElement != null && !valueElement.isJsonNull()
                ? GSON.fromJson(valueElement, Object.class)
                : null;
        return new LuaFormEvent(
                longField(object, "scriptId"),
                stringField(object, "componentId"),
                stringField(object, "type"),
                value,
                stringField(object, "text"));
    }

    public static JsonObject formValueResultParams(long scriptId, String requestId, Object value) {
        JsonObject object = scriptIdParams(scriptId);
        object.addProperty("requestId", requestId != null ? requestId : "");
        object.add("value", GSON.toJsonTree(value));
        return object;
    }

    public static Object parseFormValue(JsonElement element) {
        JsonElement value = object(element).get("value");
        return value != null && !value.isJsonNull() ? GSON.fromJson(value, Object.class) : null;
    }

    public static JsonObject debugSnapshotResult(LuaDebugSnapshot snapshot) {
        JsonObject object = new JsonObject();
        if (snapshot != null) {
            object.add("snapshot", GSON.toJsonTree(snapshot));
        }
        return object;
    }

    public static LuaDebugSnapshot parseDebugSnapshot(JsonElement element) {
        JsonElement snapshot = object(element).get("snapshot");
        if (snapshot == null || snapshot.isJsonNull() || !snapshot.isJsonObject()) {
            return null;
        }
        try {
            return GSON.fromJson(snapshot, LuaDebugSnapshot.class);
        } catch (JsonParseException ignored) {
            return null;
        }
    }

    public static LuaScriptService.LuaScriptExportFile parseExportFile(JsonElement element) {
        JsonElement exportFile = object(element).get("exportFile");
        if (exportFile == null || !exportFile.isJsonObject()) {
            return null;
        }
        return GSON.fromJson(exportFile, LuaScriptService.LuaScriptExportFile.class);
    }

    private static JsonObject scriptToJson(LuaScript script, LuaScriptRuntimeService runtimeService) {
        JsonObject object = new JsonObject();
        if (script == null) {
            return object;
        }
        object.addProperty("id", script.getId());
        object.addProperty("guid", script.getGuid());
        object.addProperty("icon", script.getIcon());
        object.addProperty("name", script.getName());
        object.addProperty("code", script.getCode());
        object.addProperty("version", script.getVersion());
        object.addProperty("description", script.getDescription());
        object.addProperty("author", script.getAuthor());
        object.addProperty("enabled", script.isEnabled());
        object.addProperty("nodeId", script.getNodeId());
        object.addProperty("botType", script.getBotType().getStorageValue());
        object.addProperty("automationName", script.getAutomationName());
        object.addProperty("createdAt", script.getCreatedAt());
        object.addProperty("updatedAt", script.getUpdatedAt());
        object.addProperty("lastRunAt", script.getLastRunAt());
        object.addProperty("lastStatus", script.getLastStatus());
        object.addProperty("lastError", script.getLastError());
        if (runtimeService != null) {
            object.addProperty("running", runtimeService.isRunning(script.getId()));
            object.addProperty("paused", runtimeService.isPaused(script.getId()));
        }
        return object;
    }

    private static void addScriptSettings(JsonObject object,
                                          String name,
                                          boolean enabled,
                                          String icon,
                                          String nodeId,
                                          LuaScript.BotType botType,
                                          String automationName,
                                          String description,
                                          String author) {
        object.addProperty("name", name != null ? name : "");
        object.addProperty("enabled", enabled);
        object.addProperty("icon", icon != null ? icon : "");
        object.addProperty("nodeId", nodeId != null ? nodeId : "");
        object.addProperty("botType", botType != null ? botType.getStorageValue() : LuaScript.BotType.AIR_BOT.getStorageValue());
        object.addProperty("automationName", automationName != null ? automationName : "");
        object.addProperty("description", description != null ? description : "");
        object.addProperty("author", author != null ? author : "");
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    public static String stringField(JsonObject object, String field) {
        String value = nullableString(object, field);
        return value != null ? value.trim() : "";
    }

    public static String nullableString(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    public static boolean booleanField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive() && element.getAsBoolean();
    }

    public static int intField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsInt()
                : 0;
    }

    public static long longField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsLong()
                : 0L;
    }
}
