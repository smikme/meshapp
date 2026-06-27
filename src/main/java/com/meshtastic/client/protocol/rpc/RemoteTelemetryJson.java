package com.meshtastic.client.protocol.rpc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.meshtastic.client.model.TelemetryEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON mapping helpers for telemetry RPC methods.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RemoteTelemetryJson {

    private static final Gson GSON = new Gson();

    private RemoteTelemetryJson() {
    }

    public static JsonObject dashboardParams(String nodeId, long sinceEpoch, long maxFutureTs) {
        JsonObject params = new JsonObject();
        if (nodeId != null && !nodeId.isBlank()) {
            params.addProperty("nodeId", nodeId);
        }
        if (sinceEpoch > 0) {
            params.addProperty("sinceEpoch", sinceEpoch);
        }
        if (maxFutureTs > 0) {
            params.addProperty("maxFutureTs", maxFutureTs);
        }
        return params;
    }

    public static JsonObject dashboardResult(String ownerNodeId,
                                             String nodeId,
                                             List<TelemetryEntry> entries,
                                             List<TelemetryEntry> qualityEntries) {
        JsonObject result = new JsonObject();
        result.addProperty("ownerNodeId", ownerNodeId != null ? ownerNodeId : "");
        result.addProperty("nodeId", nodeId != null ? nodeId : "");
        result.add("entries", entriesToJson(entries));
        result.add("qualityEntries", entriesToJson(qualityEntries));
        return result;
    }

    public static String ownerNodeId(JsonElement result) {
        return stringField(object(result), "ownerNodeId");
    }

    public static String nodeId(JsonElement result) {
        return stringField(object(result), "nodeId");
    }

    public static List<TelemetryEntry> parseEntries(JsonElement result) {
        return parseEntryArray(object(result).get("entries"));
    }

    public static List<TelemetryEntry> parseQualityEntries(JsonElement result) {
        return parseEntryArray(object(result).get("qualityEntries"));
    }

    private static JsonArray entriesToJson(List<TelemetryEntry> entries) {
        JsonArray array = new JsonArray();
        if (entries == null) {
            return array;
        }
        for (TelemetryEntry entry : entries) {
            if (entry != null) {
                array.add(GSON.toJsonTree(entry));
            }
        }
        return array;
    }

    private static List<TelemetryEntry> parseEntryArray(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<TelemetryEntry> entries = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!isObject(item)) {
                continue;
            }
            try {
                TelemetryEntry entry = GSON.fromJson(item, TelemetryEntry.class);
                if (entry != null) {
                    entries.add(entry);
                }
            } catch (JsonParseException ignored) {
                // Ignore malformed telemetry rows from a newer or older host.
            }
        }
        return entries;
    }

    private static JsonObject object(JsonElement element) {
        return isObject(element) ? element.getAsJsonObject() : new JsonObject();
    }

    private static boolean isObject(JsonElement element) {
        return element != null && element.isJsonObject();
    }

    private static String stringField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString();
        return value == null ? "" : value.trim();
    }
}
