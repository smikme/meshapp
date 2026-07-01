package com.meshtastic.client.lua.api;

import com.meshtastic.client.service.FavoriteNodeService;
import com.meshtastic.client.service.IgnoredNodeService;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Locale;

/**
 * Local node state API exposed to the Lua sandbox.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaNodeApi {

    private final LuaSandboxContext context;

    public LuaNodeApi(LuaSandboxContext context) {
        this.context = context;
    }

    /**
     * Creates the Lua table for {@code mesh.node}.
     *
     * @return node API table
     */
    public LuaTable create() {
        LuaTable node = new LuaTable();
        node.set("set_favorite_node", function(args -> updateFavoriteNode(args, true)));
        node.set("remove_favorite_node", function(args -> updateFavoriteNode(args, false)));
        node.set("set_ignored_node", function(args -> updateIgnoredNode(args, true)));
        node.set("remove_ignored_node", function(args -> updateIgnoredNode(args, false)));
        return node;
    }

    private LuaValue updateFavoriteNode(Varargs args, boolean favorite) {
        String nodeId = readTargetNodeId(args.arg1());
        String ownerNodeId = context.ownerNodeIdOrEmpty();
        if (favorite) {
            FavoriteNodeService.getInstance().addFavorite(nodeId, ownerNodeId);
        } else {
            FavoriteNodeService.getInstance().removeFavorite(nodeId, ownerNodeId);
        }
        return LuaValue.TRUE;
    }

    private LuaValue updateIgnoredNode(Varargs args, boolean ignored) {
        String nodeId = readTargetNodeId(args.arg1());
        String ownerNodeId = context.ownerNodeIdOrEmpty();
        if (ignored) {
            IgnoredNodeService.getInstance().addIgnored(nodeId, ownerNodeId);
        } else {
            IgnoredNodeService.getInstance().removeIgnored(nodeId, ownerNodeId);
        }
        return LuaValue.TRUE;
    }

    private String readTargetNodeId(LuaValue value) {
        if (value == null || value.isnil()) {
            throw new LuaError("mesh.node: target node is required");
        }
        if (value.istable()) {
            LuaTable table = value.checktable();
            String nodeId = LuaValueMapper.tableString(table, "node_id");
            if (nodeId != null && !nodeId.isBlank()) {
                return normalizeNodeId(nodeId);
            }
            int nodeNum = LuaValueMapper.tableUInt32(table, "node_num", 0);
            if (nodeNum == 0) {
                throw new LuaError("mesh.node: target node_num is required");
            }
            return nodeIdFromNum(nodeNum & 0xffff_ffffL);
        }
        if (value.isnumber()) {
            long nodeNum = (long) value.checkdouble() & 0xffff_ffffL;
            if (nodeNum == 0) {
                throw new LuaError("mesh.node: target node_num is required");
            }
            return nodeIdFromNum(nodeNum);
        }
        if (value.isstring()) {
            return normalizeNodeId(value.checkjstring());
        }
        throw new LuaError("mesh.node: unsupported target node value");
    }

    private static String normalizeNodeId(String rawNodeId) {
        if (rawNodeId == null || rawNodeId.isBlank()) {
            throw new LuaError("mesh.node: node_id is required");
        }
        String nodeId = rawNodeId.trim().toLowerCase(Locale.ROOT);
        if (!nodeId.startsWith("!") || nodeId.length() < 2 || nodeId.length() > 9) {
            throw new LuaError("mesh.node: node_id must look like !abcdef12");
        }
        try {
            Long.parseUnsignedLong(nodeId.substring(1), 16);
        } catch (NumberFormatException e) {
            throw new LuaError("mesh.node: node_id must look like !abcdef12");
        }
        return nodeId;
    }

    private static String nodeIdFromNum(long nodeNum) {
        return String.format("!%08x", nodeNum);
    }

    private VarArgFunction function(NodeFunction body) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return body.invoke(args);
            }
        };
    }

    private interface NodeFunction {
        Varargs invoke(Varargs args);
    }
}
