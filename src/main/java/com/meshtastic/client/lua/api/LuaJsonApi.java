package com.meshtastic.client.lua.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * JSON encode/decode API exposed to the Lua sandbox.
 * <p>
 * The module is installed as {@code mesh.json}. JSON objects are mapped to Lua
 * tables with string keys, JSON arrays are mapped to 1-based Lua arrays, and
 * JSON {@code null} is mapped to the userdata sentinel exposed as
 * {@code mesh.json.null}. Encoding performs conservative table-shape checks so
 * mixed object/array tables, sparse arrays, circular references, and unsupported
 * Lua values fail explicitly instead of producing surprising JSON.
 * <p>
 * JSON input size, nesting depth, and table item counts are bounded because Lua
 * scripts run inside the shared application process.
 */
public final class LuaJsonApi {

    private static final int MAX_JSON_CHARS = 1_048_576;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_TABLE_ITEMS = 50_000;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Gson PRETTY_GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final Object NULL_SENTINEL = new Object();

    private final LuaValue nullValue = LuaValue.userdataOf(NULL_SENTINEL);
    private final Set<LuaTable> forcedArrays = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Creates the Lua table for {@code mesh.json}.
     * <p>
     * The returned table exposes {@code decode}, {@code try_decode},
     * {@code encode}, {@code pretty}, {@code array}, {@code is_null}, and
     * {@code null}.
     *
     * @return JSON API table
     */
    public LuaTable create() {
        LuaTable json = new LuaTable();
        json.set("null", nullValue);
        json.set("decode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue textArg) {
                return decode(textArg.checkjstring());
            }
        });
        json.set("try_decode", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    return decode(args.arg1().checkjstring());
                } catch (LuaError error) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf(error.getMessage()) });
                }
            }
        });
        json.set("encode", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                JsonElement element = luaToJson(args.arg(1), 0, Collections.newSetFromMap(new IdentityHashMap<>()));
                boolean pretty = prettyOption(args.arg(2));
                return LuaValue.valueOf((pretty ? PRETTY_GSON : GSON).toJson(element));
            }
        });
        json.set("pretty", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                JsonElement element = luaToJson(value, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
                return LuaValue.valueOf(PRETTY_GSON.toJson(element));
            }
        });
        json.set("is_null", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                return LuaValue.valueOf(isJsonNull(value));
            }
        });
        json.set("array", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                LuaTable table = value.isnil() ? new LuaTable() : value.checktable();
                forcedArrays.add(table);
                return table;
            }
        });
        return json;
    }

    private LuaValue decode(String text) {
        if (text == null) {
            throw new LuaError("mesh.json.decode: text is required");
        }
        if (text.length() > MAX_JSON_CHARS) {
            throw new LuaError("mesh.json.decode: JSON text is too large");
        }
        try {
            JsonElement element = JsonParser.parseString(text);
            return jsonToLua(element, 0);
        } catch (JsonParseException | IllegalStateException error) {
            throw new LuaError("mesh.json.decode: invalid JSON: " + rootMessage(error));
        }
    }

    private LuaValue jsonToLua(JsonElement element, int depth) {
        checkDepth(depth, "mesh.json.decode");
        if (element == null || element.isJsonNull()) {
            return nullValue;
        }
        if (element.isJsonObject()) {
            LuaTable table = new LuaTable();
            int count = 0;
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (++count > MAX_TABLE_ITEMS) {
                    throw new LuaError("mesh.json.decode: JSON object has too many fields");
                }
                table.set(entry.getKey(), jsonToLua(entry.getValue(), depth + 1));
            }
            return table;
        }
        if (element.isJsonArray()) {
            LuaTable table = new LuaTable();
            forcedArrays.add(table);
            JsonArray array = element.getAsJsonArray();
            if (array.size() > MAX_TABLE_ITEMS) {
                throw new LuaError("mesh.json.decode: JSON array has too many items");
            }
            for (int i = 0; i < array.size(); i++) {
                table.set(i + 1, jsonToLua(array.get(i), depth + 1));
            }
            return table;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return LuaValue.valueOf(primitive.getAsBoolean());
        }
        if (primitive.isNumber()) {
            double number = primitive.getAsDouble();
            if (!Double.isFinite(number)) {
                throw new LuaError("mesh.json.decode: JSON number is out of range");
            }
            return LuaValue.valueOf(number);
        }
        return LuaValue.valueOf(primitive.getAsString());
    }

    private JsonElement luaToJson(LuaValue value, int depth, Set<LuaTable> visiting) {
        checkDepth(depth, "mesh.json.encode");
        if (value == null || value.isnil() || isJsonNull(value)) {
            return JsonNull.INSTANCE;
        }
        if (value.isboolean()) {
            return new JsonPrimitive(value.checkboolean());
        }
        if (value.isnumber()) {
            double number = value.checkdouble();
            if (!Double.isFinite(number)) {
                throw new LuaError("mesh.json.encode: numbers must be finite");
            }
            return new JsonPrimitive(number);
        }
        if (value.isstring()) {
            return new JsonPrimitive(value.checkjstring());
        }
        if (!value.istable()) {
            throw new LuaError("mesh.json.encode: unsupported value type " + value.typename());
        }
        LuaTable table = value.checktable();
        if (!visiting.add(table)) {
            throw new LuaError("mesh.json.encode: circular table reference");
        }
        try {
            TableShape shape = tableShape(table);
            if (shape.array()) {
                JsonArray array = new JsonArray();
                for (int i = 1; i <= shape.length(); i++) {
                    array.add(luaToJson(table.get(i), depth + 1, visiting));
                }
                return array;
            }
            JsonObject object = new JsonObject();
            LuaValue key = LuaValue.NIL;
            while (true) {
                Varargs next = table.next(key);
                key = next.arg1();
                if (key.isnil()) {
                    break;
                }
                if (!key.isstring()) {
                    throw new LuaError("mesh.json.encode: object keys must be strings");
                }
                object.add(key.checkjstring(), luaToJson(next.arg(2), depth + 1, visiting));
            }
            return object;
        } finally {
            visiting.remove(table);
        }
    }

    private TableShape tableShape(LuaTable table) {
        int length = table.length();
        boolean forcedArray = forcedArrays.contains(table);
        int count = 0;
        boolean hasStringKeys = false;
        boolean hasNumericKeys = false;
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            key = next.arg1();
            if (key.isnil()) {
                break;
            }
            if (++count > MAX_TABLE_ITEMS) {
                throw new LuaError("mesh.json.encode: table has too many items");
            }
            if (isPositiveIntegerKey(key)) {
                hasNumericKeys = true;
                int index = key.checkint();
                if (index < 1 || index > length) {
                    throw new LuaError("mesh.json.encode: array keys must be contiguous from 1");
                }
            } else if (key.isstring()) {
                hasStringKeys = true;
            } else {
                throw new LuaError("mesh.json.encode: table keys must be strings or positive integers");
            }
        }
        if (forcedArray) {
            if (hasStringKeys || count != length) {
                throw new LuaError("mesh.json.encode: json.array tables must contain only contiguous integer keys");
            }
            return new TableShape(true, length);
        }
        if (!hasNumericKeys) {
            return new TableShape(false, 0);
        }
        if (hasStringKeys) {
            throw new LuaError("mesh.json.encode: cannot encode mixed array/object table");
        }
        if (count != length) {
            throw new LuaError("mesh.json.encode: array keys must be contiguous from 1");
        }
        return new TableShape(length > 0, length);
    }

    private boolean prettyOption(LuaValue value) {
        if (value == null || value.isnil()) {
            return false;
        }
        if (value.isboolean()) {
            return value.checkboolean();
        }
        LuaTable table = value.checktable();
        LuaValue pretty = table.get("pretty");
        return !pretty.isnil() && pretty.checkboolean();
    }

    private boolean isJsonNull(LuaValue value) {
        return value != null && value.isuserdata() && value.touserdata() == NULL_SENTINEL;
    }

    private boolean isPositiveIntegerKey(LuaValue key) {
        return key.isint() && key.checkint() >= 1;
    }

    private void checkDepth(int depth, String source) {
        if (depth > MAX_DEPTH) {
            throw new LuaError(source + ": nesting is too deep");
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message != null && !message.isBlank()
                ? message
                : current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private record TableShape(boolean array, int length) {}
}
