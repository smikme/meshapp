package com.meshtastic.client.protocol.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meshtastic.client.model.NodeData;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON mapping helpers for node RPC methods.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RemoteNodeJson {

    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private RemoteNodeJson() {
    }

    public static JsonObject listParams(boolean includeFavorites, boolean includeIgnored) {
        JsonObject params = new JsonObject();
        params.addProperty("includeFavorites", includeFavorites);
        params.addProperty("includeIgnored", includeIgnored);
        return params;
    }

    public static JsonObject nodeParams(NodeData node) {
        JsonObject params = new JsonObject();
        if (node != null) {
            params.addProperty("nodeId", node.getNodeId());
            params.addProperty("nodeNum", node.getNodeNum());
        }
        return params;
    }

    public static JsonObject nodeIdParams(String nodeId) {
        JsonObject params = new JsonObject();
        params.addProperty("nodeId", nodeId);
        return params;
    }

    public static JsonObject flagParams(NodeData node, boolean enabled) {
        JsonObject params = nodeParams(node);
        params.addProperty("enabled", enabled);
        return params;
    }

    public static String ownerNodeId(JsonElement result) {
        JsonObject object = isObject(result) ? result.getAsJsonObject() : new JsonObject();
        return stringField(object, "ownerNodeId");
    }

    public static List<NodeData> parseNodes(JsonElement result) {
        JsonArray items = objectArray(result, "items");
        List<NodeData> parsed = new ArrayList<>();
        for (JsonElement element : items) {
            if (isObject(element)) {
                parsed.add(parseNode(element.getAsJsonObject()));
            }
        }
        return parsed;
    }

    public static Map<String, Boolean> parseFavoriteFlags(JsonElement result) {
        return parseFlagMap(result, "favorite");
    }

    public static Map<String, Boolean> parseIgnoredFlags(JsonElement result) {
        return parseFlagMap(result, "ignored");
    }

    private static Map<String, Boolean> parseFlagMap(JsonElement result, String field) {
        JsonArray items = objectArray(result, "items");
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (JsonElement element : items) {
            if (!isObject(element)) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String nodeId = stringField(object, "nodeId");
            if (!nodeId.isBlank()) {
                flags.put(nodeId, booleanField(object, field));
            }
        }
        return flags;
    }

    private static NodeData parseNode(JsonObject object) {
        NodeData node = new NodeData(intField(object, "nodeNum"));
        node.setNodeId(firstText(stringField(object, "nodeId"), node.getNodeId()));
        node.setLongName(stringField(object, "longName"));
        node.setShortName(stringField(object, "shortName"));
        node.setLatitude(doubleField(object, "latitude"));
        node.setLongitude(doubleField(object, "longitude"));
        node.setAltitude(intField(object, "altitude"));
        node.setSnr(floatField(object, "snr"));
        node.setLastHeard(intField(object, "lastHeard"));
        node.setBatteryLevel(intField(object, "batteryLevel"));
        node.setExternallyPowered(booleanField(object, "externallyPowered"));
        node.setVoltage(floatField(object, "voltage"));
        node.setChannelUtilization(floatField(object, "channelUtilization"));
        node.setAirUtilTx(floatField(object, "airUtilTx"));
        node.setUptimeSeconds(longField(object, "uptimeSeconds"));
        node.setTemperature(floatField(object, "temperature"));
        node.setRelativeHumidity(floatField(object, "relativeHumidity"));
        node.setBarometricPressure(floatField(object, "barometricPressure"));
        if (booleanField(object, "hasHopsAway")) {
            node.setHopsAway(intField(object, "hopsAway"));
        } else {
            node.clearHopsAway();
        }
        node.setChannel(intField(object, "channel"));
        node.setRole(stringField(object, "role"));
        node.setHwModel(stringField(object, "hwModel"));
        String publicKey = stringField(object, "publicKey");
        if (!publicKey.isBlank()) {
            try {
                node.setPublicKey(DECODER.decode(publicKey));
            } catch (IllegalArgumentException ignored) {
                node.setPublicKey(null);
            }
        }
        if (object.has("unmessagable") && !object.get("unmessagable").isJsonNull()) {
            node.setUnmessagable(booleanField(object, "unmessagable"));
        }
        if (object.has("licensed") && !object.get("licensed").isJsonNull()) {
            node.setLicensed(booleanField(object, "licensed"));
        }
        return node;
    }

    public static JsonObject nodeToJson(NodeData node, boolean favorite, boolean ignored) {
        JsonObject object = new JsonObject();
        object.addProperty("nodeNum", node.getNodeNum());
        object.addProperty("nodeId", node.getNodeId());
        object.addProperty("longName", node.getLongName());
        object.addProperty("shortName", node.getShortName());
        object.addProperty("latitude", node.getLatitude());
        object.addProperty("longitude", node.getLongitude());
        object.addProperty("altitude", node.getAltitude());
        object.addProperty("snr", node.getSnr());
        object.addProperty("lastHeard", node.getLastHeard());
        object.addProperty("batteryLevel", node.getBatteryLevel());
        object.addProperty("externallyPowered", node.isExternallyPowered());
        object.addProperty("voltage", node.getVoltage());
        object.addProperty("channelUtilization", node.getChannelUtilization());
        object.addProperty("airUtilTx", node.getAirUtilTx());
        object.addProperty("uptimeSeconds", node.getUptimeSeconds());
        object.addProperty("temperature", node.getTemperature());
        object.addProperty("relativeHumidity", node.getRelativeHumidity());
        object.addProperty("barometricPressure", node.getBarometricPressure());
        object.addProperty("hasHopsAway", node.hasHopsAway());
        object.addProperty("hopsAway", node.getHopsAway());
        object.addProperty("channel", node.getChannel());
        object.addProperty("role", node.getRole());
        object.addProperty("hwModel", node.getHwModel());
        object.addProperty("publicKey", node.getPublicKey() != null ? ENCODER.encodeToString(node.getPublicKey()) : "");
        object.addProperty("unmessagable", node.getUnmessagable());
        object.addProperty("licensed", node.getLicensed());
        object.addProperty("favorite", favorite);
        object.addProperty("ignored", ignored);
        return object;
    }

    private static JsonArray objectArray(JsonElement result, String field) {
        JsonObject object = isObject(result) ? result.getAsJsonObject() : new JsonObject();
        JsonElement element = object.get(field);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static boolean isObject(JsonElement element) {
        return element != null && element.isJsonObject();
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stringField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString();
        return value == null ? "" : value.trim();
    }

    private static int intField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsInt()
                : 0;
    }

    private static long longField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsLong()
                : 0L;
    }

    private static double doubleField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsDouble()
                : 0.0;
    }

    private static float floatField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsFloat()
                : 0.0f;
    }

    private static boolean booleanField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                && element.getAsBoolean();
    }
}
