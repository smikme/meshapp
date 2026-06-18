package com.meshtastic.client.lua.api;

import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.service.NodeCacheService;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.meshtastic.proto.TelemetryProtos;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Read-only telemetry history API exposed to the Lua sandbox.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaTelemetryApi {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final Pattern MESHTASTIC_NODE_ID = Pattern.compile("![0-9a-f]{8}");
    private static final Pattern MESHCORE_NODE_ID = Pattern.compile("mc:[0-9a-f]{8}");

    private final LuaSandboxContext context;
    private final LuaValueMapper mapper;
    private final NodeCacheService nodeCacheService;

    public LuaTelemetryApi(LuaSandboxContext context, LuaValueMapper mapper) {
        this(context, mapper, NodeCacheService.getInstance());
    }

    LuaTelemetryApi(LuaSandboxContext context, LuaValueMapper mapper, NodeCacheService nodeCacheService) {
        this.context = context;
        this.mapper = mapper;
        this.nodeCacheService = nodeCacheService;
    }

    /**
     * Creates the Lua table for {@code mesh.telemetry}.
     *
     * @return telemetry API table
     */
    public LuaTable create() {
        LuaTable telemetry = new LuaTable();
        telemetry.set("recent", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                QueryOptions options = readOptions(args.arg1());
                return telemetryList(nodeCacheService.loadTelemetry(
                        options.nodeId(),
                        options.variant(),
                        options.sinceEpoch(),
                        options.untilEpoch(),
                        options.limit(),
                        true,
                        context.ownerNodeIdOrEmpty()));
            }
        });
        telemetry.set("for_node", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String nodeId = normalizeNodeId(args.arg1());
                QueryOptions options = readOptions(args.arg(2)).withNodeId(nodeId);
                return telemetryList(nodeCacheService.loadTelemetry(
                        options.nodeId(),
                        options.variant(),
                        options.sinceEpoch(),
                        options.untilEpoch(),
                        options.limit(),
                        false,
                        context.ownerNodeIdOrEmpty()));
            }
        });
        telemetry.set("query", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                QueryOptions options = readOptions(arg);
                return telemetryList(nodeCacheService.loadTelemetry(
                        options.nodeId(),
                        options.variant(),
                        options.sinceEpoch(),
                        options.untilEpoch(),
                        options.limit(),
                        false,
                        context.ownerNodeIdOrEmpty()));
            }
        });
        telemetry.set("latest", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                QueryOptions options = latestOptions(args.arg1());
                List<TelemetryEntry> entries = nodeCacheService.loadTelemetry(
                        options.nodeId(),
                        options.variant(),
                        options.sinceEpoch(),
                        options.untilEpoch(),
                        1,
                        true,
                        context.ownerNodeIdOrEmpty());
                return entries.isEmpty() ? LuaValue.NIL : mapper.telemetryToTable(entries.getFirst());
            }
        });
        telemetry.set("fields", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable table = new LuaTable();
                List<String> fields = LuaValueMapper.telemetryFieldNames();
                for (int i = 0; i < fields.size(); i++) {
                    table.set(i + 1, LuaValue.valueOf(fields.get(i)));
                }
                return table;
            }
        });
        return telemetry;
    }

    private QueryOptions latestOptions(LuaValue value) {
        if (value == null || value.isnil()) {
            return QueryOptions.empty();
        }
        if (value.istable()) {
            return readOptions(value).withLimit(1);
        }
        return QueryOptions.empty().withNodeId(normalizeNodeId(value)).withLimit(1);
    }

    private QueryOptions readOptions(LuaValue value) {
        if (value == null || value.isnil()) {
            return QueryOptions.empty();
        }
        LuaTable table = value.checktable();
        QueryOptions options = new QueryOptions(
                normalizeOptionalNodeId(optionalString(table, "node_id")),
                normalizeVariant(optionalString(table, "variant")),
                optionalEpoch(table, "since"),
                optionalEpoch(table, "until"),
                normalizeLimit(table));
        if (options.sinceEpoch() > 0 && options.untilEpoch() > 0 && options.sinceEpoch() > options.untilEpoch()) {
            throw new LuaError("mesh.telemetry: since must be <= until");
        }
        return options;
    }

    private LuaTable telemetryList(List<TelemetryEntry> entries) {
        LuaTable table = new LuaTable();
        for (int i = 0; i < entries.size(); i++) {
            table.set(i + 1, mapper.telemetryToTable(entries.get(i)));
        }
        return table;
    }

    private String normalizeNodeId(LuaValue value) {
        if (value == null || value.isnil()) {
            throw new LuaError("mesh.telemetry: node_id is required");
        }
        return normalizeOptionalNodeId(value.checkjstring());
    }

    private static String normalizeOptionalNodeId(String rawNodeId) {
        if (rawNodeId == null || rawNodeId.isBlank()) {
            return null;
        }
        String nodeId = rawNodeId.trim().toLowerCase(Locale.ROOT);
        if (MESHTASTIC_NODE_ID.matcher(nodeId).matches() || MESHCORE_NODE_ID.matcher(nodeId).matches()) {
            return nodeId;
        }
        throw new LuaError("mesh.telemetry: node_id must look like !abcdef12 or mc:abcdef12");
    }

    private static String normalizeVariant(String rawVariant) {
        if (rawVariant == null || rawVariant.isBlank()) {
            return null;
        }
        String variant = rawVariant.trim().toUpperCase(Locale.ROOT);
        try {
            TelemetryProtos.Telemetry.VariantCase.valueOf(variant);
            return variant;
        } catch (IllegalArgumentException e) {
            throw new LuaError("mesh.telemetry: unknown variant " + rawVariant);
        }
    }

    private static long optionalEpoch(LuaTable table, String key) {
        LuaValue value = table.get(key);
        if (value.isnil()) {
            return 0;
        }
        if (value.isnumber()) {
            double number = value.checkdouble();
            if (!Double.isFinite(number) || number < 0) {
                throw new LuaError("mesh.telemetry: " + key + " must be a non-negative epoch seconds value");
            }
            return (long) number;
        }
        String raw = value.checkjstring().trim();
        if (raw.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            // Fall through to ISO-8601 parsing.
        }
        try {
            return Instant.parse(raw).getEpochSecond();
        } catch (DateTimeParseException e) {
            throw new LuaError("mesh.telemetry: " + key + " must be epoch seconds or ISO-8601 UTC time");
        }
    }

    private static int normalizeLimit(LuaTable table) {
        LuaValue value = table.get("limit");
        if (value.isnil()) {
            return DEFAULT_LIMIT;
        }
        int limit = value.checkint();
        if (limit < 1) {
            throw new LuaError("mesh.telemetry: limit must be >= 1");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String optionalString(LuaTable table, String key) {
        LuaValue value = table.get(key);
        return value.isnil() ? null : value.checkjstring();
    }

    private record QueryOptions(String nodeId, String variant, long sinceEpoch, long untilEpoch, int limit) {
        static QueryOptions empty() {
            return new QueryOptions(null, null, 0, 0, DEFAULT_LIMIT);
        }

        QueryOptions withNodeId(String nodeId) {
            return new QueryOptions(nodeId, variant, sinceEpoch, untilEpoch, limit);
        }

        QueryOptions withLimit(int limit) {
            return new QueryOptions(nodeId, variant, sinceEpoch, untilEpoch, limit);
        }
    }
}
