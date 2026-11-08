package com.meshtastic.client.protocol.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Primitive JSON field decoding shared by RPC payload mappers. */
final class RpcJsonFields {
    private RpcJsonFields() {}

    static boolean booleanField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive() && element.getAsBoolean();
    }

    static int intField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsInt()
                : 0;
    }

    static long longField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsLong()
                : 0L;
    }
}
