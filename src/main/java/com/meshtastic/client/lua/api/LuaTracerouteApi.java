package com.meshtastic.client.lua.api;

import com.meshtastic.client.lua.LuaTracerouteBridge;
import com.meshtastic.client.lua.LuaTracerouteRequest;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * Traceroute API exposed to the Lua sandbox.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaTracerouteApi {

    private static final int DEFAULT_TIMEOUT_SECONDS = 360;
    private static final int MAX_TIMEOUT_SECONDS = 600;

    private final LuaSandboxContext context;

    public LuaTracerouteApi(LuaSandboxContext context) {
        this.context = context;
    }

    /**
     * Creates the Lua table for {@code mesh.traceroute}.
     *
     * @return traceroute API table
     */
    public LuaTable create() {
        LuaTable traceroute = new LuaTable();
        traceroute.set("request", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaTracerouteBridge bridge = context.tracerouteBridge();
                if (bridge == null || !bridge.isTracerouteAvailable()) {
                    throw new LuaError("Traceroute is not available");
                }

                TargetNode target = readTargetNode(args.arg1());
                RequestOptions options = readOptions(args.arg(2), target);
                String requestId = bridge.nextTracerouteRequestId();
                bridge.requestTraceroute(new LuaTracerouteRequest(
                        context.scriptId(),
                        requestId,
                        "mesh.traceroute.request",
                        options.name(),
                        target.nodeNum(),
                        target.nodeId(),
                        options.targetName(),
                        options.chatType(),
                        options.chatKey(),
                        options.timeoutSeconds()));
                return LuaValue.valueOf(requestId);
            }
        });
        return traceroute;
    }

    private TargetNode readTargetNode(LuaValue value) {
        if (value == null || value.isnil()) {
            throw new LuaError("target node is required");
        }
        if (value.istable()) {
            LuaTable table = value.checktable();
            int nodeNum = LuaValueMapper.tableUInt32(table, "node_num", 0);
            String nodeId = LuaValueMapper.tableString(table, "node_id");
            if (nodeNum == 0 && nodeId != null && nodeId.startsWith("!")) {
                nodeNum = parseNodeNum(nodeId);
            }
            if (nodeId == null || nodeId.isBlank()) {
                nodeId = nodeNum != 0 ? String.format("!%08x", nodeNum) : "";
            }
            if (nodeNum == 0) {
                throw new LuaError("target node_num is required");
            }
            String name = firstNonBlank(
                    LuaValueMapper.tableString(table, "long_name"),
                    LuaValueMapper.tableString(table, "short_name"),
                    nodeId);
            return new TargetNode(nodeNum, nodeId, name);
        }
        if (value.isnumber()) {
            int nodeNum = (int) ((long) value.checkdouble() & 0xffff_ffffL);
            return new TargetNode(nodeNum, String.format("!%08x", nodeNum), String.format("!%08x", nodeNum));
        }
        if (value.isstring()) {
            String nodeId = value.checkjstring().trim();
            int nodeNum = parseNodeNum(nodeId);
            return new TargetNode(nodeNum, nodeId, nodeId);
        }
        throw new LuaError("unsupported target node value");
    }

    private RequestOptions readOptions(LuaValue value, TargetNode target) {
        if (value == null || value.isnil()) {
            return new RequestOptions("", "", "", target.displayName(), DEFAULT_TIMEOUT_SECONDS);
        }
        LuaTable table = value.checktable();
        int timeoutSeconds = LuaValueMapper.tableInt(table, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS);
        timeoutSeconds = Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, timeoutSeconds));
        return new RequestOptions(
                optionalString(table, "name"),
                optionalString(table, "chat_type"),
                optionalString(table, "chat_key"),
                firstNonBlank(optionalString(table, "target_name"), target.displayName(), target.nodeId()),
                timeoutSeconds);
    }

    private int parseNodeNum(String nodeId) {
        if (nodeId == null || !nodeId.startsWith("!") || nodeId.length() < 2 || nodeId.length() > 9) {
            throw new LuaError("node_id must look like !abcdef12");
        }
        try {
            return (int) Long.parseUnsignedLong(nodeId.substring(1), 16);
        } catch (NumberFormatException e) {
            throw new LuaError("node_id must look like !abcdef12");
        }
    }

    private static String optionalString(LuaTable table, String key) {
        LuaValue value = table.get(key);
        return value.isnil() ? "" : value.checkjstring();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record TargetNode(int nodeNum, String nodeId, String displayName) {}

    private record RequestOptions(String name,
                                  String chatType,
                                  String chatKey,
                                  String targetName,
                                  int timeoutSeconds) {}
}
