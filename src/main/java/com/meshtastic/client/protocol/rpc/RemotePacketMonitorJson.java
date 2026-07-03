package com.meshtastic.client.protocol.rpc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.service.PacketMonitorService;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON mapping helpers for LoRa packet monitor RPC methods.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RemotePacketMonitorJson {

    private static final Gson GSON = new Gson();

    private RemotePacketMonitorJson() {
    }

    public static JsonObject pageParams(String request,
                                        PacketMonitorService.PacketQuery query,
                                        PacketMonitorService.PageCursor cursor,
                                        int limit) {
        JsonObject params = new JsonObject();
        params.addProperty("request", request);
        params.add("query", queryToJson(query));
        if (cursor != null) {
            params.add("cursor", cursorToJson(cursor));
        }
        params.addProperty("limit", limit);
        return params;
    }

    public static JsonObject queryParams(PacketMonitorService.PacketQuery query) {
        JsonObject params = new JsonObject();
        params.add("query", queryToJson(query));
        return params;
    }

    public static JsonObject pageToJson(PacketMonitorService.PacketPage page) {
        JsonObject object = new JsonObject();
        object.add("entries", entriesToJson(page != null ? page.entries() : List.of()));
        object.addProperty("hasNewer", page != null && page.hasNewer());
        object.addProperty("hasOlder", page != null && page.hasOlder());
        object.addProperty("totalMatchingCount", page != null ? page.totalMatchingCount() : 0);
        object.addProperty("totalStoredCount", page != null ? page.totalStoredCount() : 0);
        return object;
    }

    public static PacketMonitorService.PacketPage parsePage(JsonElement element) {
        JsonObject object = object(element);
        return new PacketMonitorService.PacketPage(
                parseEntries(object.get("entries")),
                booleanField(object, "hasNewer"),
                booleanField(object, "hasOlder"),
                intField(object, "totalMatchingCount"),
                intField(object, "totalStoredCount"));
    }

    public static PacketMonitorService.PacketQuery parseQuery(JsonElement element) {
        JsonObject object = object(element);
        PacketLogEntry.Direction direction = null;
        String directionText = stringField(object, "direction");
        if (!directionText.isBlank()) {
            try {
                direction = PacketLogEntry.Direction.valueOf(directionText);
            } catch (IllegalArgumentException ignored) {
                direction = null;
            }
        }
        return new PacketMonitorService.PacketQuery(
                direction,
                blankToNull(stringField(object, "packetType")),
                blankToNull(stringField(object, "transportMechanism")),
                blankToNull(stringField(object, "searchText")),
                nullableLong(object, "capturedAtFromMillis"),
                nullableLong(object, "capturedAtToMillis"));
    }

    public static PacketMonitorService.PageCursor parseCursor(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        return new PacketMonitorService.PageCursor(
                longField(object, "capturedAt"),
                longField(object, "id"));
    }

    public static JsonObject countsToJson(int matching, int total) {
        JsonObject object = new JsonObject();
        object.addProperty("matching", matching);
        object.addProperty("total", total);
        return object;
    }

    public static int matchingCount(JsonElement element) {
        return intField(object(element), "matching");
    }

    public static int totalCount(JsonElement element) {
        return intField(object(element), "total");
    }

    public static JsonObject typesToJson(List<String> packetTypes) {
        JsonObject object = new JsonObject();
        JsonArray items = new JsonArray();
        if (packetTypes != null) {
            packetTypes.forEach(items::add);
        }
        object.add("items", items);
        return object;
    }

    public static List<String> parseTypes(JsonElement element) {
        JsonElement items = object(element).get("items");
        if (items == null || !items.isJsonArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonElement item : items.getAsJsonArray()) {
            if (item != null && item.isJsonPrimitive()) {
                String value = item.getAsString();
                if (value != null && !value.isBlank()) {
                    result.add(value.trim());
                }
            }
        }
        return result;
    }

    public static JsonObject captureStateToJson(boolean enabled) {
        JsonObject object = new JsonObject();
        object.addProperty("enabled", enabled);
        return object;
    }

    public static boolean captureEnabled(JsonElement element) {
        return booleanField(object(element), "enabled");
    }

    public static JsonObject entryEvent(PacketLogEntry entry) {
        JsonObject object = new JsonObject();
        object.add("entry", GSON.toJsonTree(entry));
        return object;
    }

    public static PacketLogEntry parseEventEntry(JsonElement element) {
        JsonObject object = object(element);
        JsonElement entry = object.get("entry");
        return parseEntry(entry);
    }

    private static JsonObject queryToJson(PacketMonitorService.PacketQuery query) {
        JsonObject object = new JsonObject();
        if (query == null) {
            return object;
        }
        if (query.direction() != null) {
            object.addProperty("direction", query.direction().name());
        }
        addNullable(object, "packetType", query.packetType());
        addNullable(object, "transportMechanism", query.transportMechanism());
        addNullable(object, "searchText", query.searchText());
        addNullable(object, "capturedAtFromMillis", query.capturedAtFromMillis());
        addNullable(object, "capturedAtToMillis", query.capturedAtToMillis());
        return object;
    }

    private static JsonObject cursorToJson(PacketMonitorService.PageCursor cursor) {
        JsonObject object = new JsonObject();
        object.addProperty("capturedAt", cursor.capturedAt());
        object.addProperty("id", cursor.id());
        return object;
    }

    private static JsonArray entriesToJson(List<PacketLogEntry> entries) {
        JsonArray array = new JsonArray();
        if (entries == null) {
            return array;
        }
        for (PacketLogEntry entry : entries) {
            if (entry != null) {
                array.add(GSON.toJsonTree(entry));
            }
        }
        return array;
    }

    private static List<PacketLogEntry> parseEntries(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<PacketLogEntry> entries = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            PacketLogEntry entry = parseEntry(item);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static PacketLogEntry parseEntry(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        try {
            return GSON.fromJson(element, PacketLogEntry.class);
        } catch (JsonParseException ignored) {
            return null;
        }
    }

    private static void addNullable(JsonObject object, String field, String value) {
        if (value != null && !value.isBlank()) {
            object.addProperty(field, value);
        }
    }

    private static void addNullable(JsonObject object, String field, Long value) {
        if (value != null) {
            object.addProperty(field, value);
        }
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String stringField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString();
        return value == null ? "" : value.trim();
    }

    private static Long nullableLong(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsLong()
                : null;
    }

    private static long longField(JsonObject object, String field) {
        Long value = nullableLong(object, field);
        return value != null ? value : 0L;
    }

    private static int intField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsInt()
                : 0;
    }

    private static boolean booleanField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                && element.getAsBoolean();
    }
}
