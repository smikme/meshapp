package com.meshtastic.client.lua.api;

import com.meshtastic.client.lua.LuaRemoteAdminBridge;
import com.meshtastic.client.lua.LuaRemoteAdminRequest;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.meshtastic.proto.AdminProtos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Remote administration API exposed to the Lua sandbox.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaRemoteAdminApi {

    private final LuaSandboxContext context;

    public LuaRemoteAdminApi(LuaSandboxContext context) {
        this.context = context;
    }

    /**
     * Creates the Lua table for {@code mesh.admin}.
     *
     * @return remote-admin API table
     */
    public LuaTable create() {
        LuaTable admin = new LuaTable();
        admin.set("load_config", function((args) -> start(base(args, LuaRemoteAdminRequest.Action.LOAD_CONFIG))));
        admin.set("request_config", function(args -> {
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.REQUEST_CONFIG, 3);
            builder.configType = parseConfigType(args.checkjstring(2));
            return start(builder);
        }));
        admin.set("request_module_config", function(args -> {
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.REQUEST_MODULE_CONFIG, 3);
            builder.moduleConfigType = parseModuleConfigType(args.checkjstring(2));
            return start(builder);
        }));
        admin.set("save_config", function(args -> {
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.SAVE_CONFIG, 3);
            LuaTable changes = args.checktable(2);
            if (!builder.confirm) {
                throw new LuaError("mesh.admin.save_config requires options.confirm = true");
            }
            readSaveChanges(builder, changes);
            return start(builder);
        }));
        admin.set("refresh_status", function(args -> start(base(args, LuaRemoteAdminRequest.Action.REFRESH_STATUS))));
        admin.set("reboot", function(args -> {
            boolean optionsOnly = isTableArg(args, 2);
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.REBOOT, optionsOnly ? 2 : 3);
            builder.delaySeconds = optionsOnly ? 5 : Math.max(0, args.optint(2, 5));
            return start(builder);
        }));
        admin.set("shutdown", function(args -> {
            boolean optionsOnly = isTableArg(args, 2);
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.SHUTDOWN, optionsOnly ? 2 : 3);
            builder.delaySeconds = optionsOnly ? 5 : Math.max(0, args.optint(2, 5));
            return start(builder);
        }));
        admin.set("sync_time", function(args -> {
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.SYNC_TIME,
                    args.arg(2).istable() ? 2 : 3);
            builder.epochSeconds = args.arg(2).isnumber()
                    ? (long) args.arg(2).checkdouble()
                    : System.currentTimeMillis() / 1000;
            return start(builder);
        }));
        admin.set("backup", function(args -> {
            boolean optionsOnly = isTableArg(args, 2);
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.BACKUP, optionsOnly ? 2 : 3);
            builder.backupLocation = parseBackupLocation(optionsOnly ? "FLASH" : args.optjstring(2, "FLASH"));
            return start(builder);
        }));
        admin.set("restore", dangerousFunction(LuaRemoteAdminRequest.Action.RESTORE, args -> {
            boolean optionsOnly = isTableArg(args, 2);
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.RESTORE, optionsOnly ? 2 : 3);
            builder.backupLocation = parseBackupLocation(optionsOnly ? "FLASH" : args.optjstring(2, "FLASH"));
            return builder;
        }));
        admin.set("remove_backup", dangerousFunction(LuaRemoteAdminRequest.Action.REMOVE_BACKUP, args -> {
            boolean optionsOnly = isTableArg(args, 2);
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.REMOVE_BACKUP, optionsOnly ? 2 : 3);
            builder.backupLocation = parseBackupLocation(optionsOnly ? "FLASH" : args.optjstring(2, "FLASH"));
            return builder;
        }));
        admin.set("reset_nodedb", dangerousFunction(LuaRemoteAdminRequest.Action.RESET_NODEDB, args -> {
            boolean optionsOnly = isTableArg(args, 2);
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.RESET_NODEDB, optionsOnly ? 2 : 3);
            builder.preserveFavorites = optionsOnly || args.arg(2).isnil() || args.arg(2).toboolean();
            return builder;
        }));
        admin.set("factory_reset_config", dangerousFunction(
                LuaRemoteAdminRequest.Action.FACTORY_RESET_CONFIG,
                args -> base(args, LuaRemoteAdminRequest.Action.FACTORY_RESET_CONFIG)));
        admin.set("factory_reset_device", dangerousFunction(
                LuaRemoteAdminRequest.Action.FACTORY_RESET_DEVICE,
                args -> base(args, LuaRemoteAdminRequest.Action.FACTORY_RESET_DEVICE)));
        admin.set("enter_dfu_mode", dangerousFunction(
                LuaRemoteAdminRequest.Action.ENTER_DFU_MODE,
                args -> base(args, LuaRemoteAdminRequest.Action.ENTER_DFU_MODE)));
        admin.set("set_owner", function(args -> {
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.SET_OWNER, 3);
            readOwner(builder, args.checktable(2));
            return start(builder);
        }));
        admin.set("set_fixed_position", function(args -> {
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.SET_FIXED_POSITION, 3);
            readPosition(builder, args.checktable(2));
            return start(builder);
        }));
        admin.set("remove_fixed_position", function(args ->
                start(base(args, LuaRemoteAdminRequest.Action.REMOVE_FIXED_POSITION))));
        admin.set("set_ringtone", function(args -> {
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.SET_RINGTONE, 3);
            builder.ringtone = args.checkjstring(2);
            builder.ringtoneSet = true;
            return start(builder);
        }));
        admin.set("set_canned_messages", function(args -> {
            RequestBuilder builder = baseWithOptions(args, LuaRemoteAdminRequest.Action.SET_CANNED_MESSAGES, 3);
            builder.cannedMessages = args.checkjstring(2);
            builder.cannedMessagesSet = true;
            return start(builder);
        }));
        return admin;
    }

    private LuaValue start(RequestBuilder builder) {
        LuaRemoteAdminBridge bridge = context.remoteAdminBridge();
        if (bridge == null || !bridge.isRemoteAdminAvailable()) {
            throw new LuaError("Remote admin is not available");
        }
        String requestId = bridge.nextRemoteAdminRequestId();
        builder.requestId = requestId;
        bridge.requestRemoteAdmin(builder.build());
        return LuaValue.valueOf(requestId);
    }

    private RequestBuilder base(Varargs args, LuaRemoteAdminRequest.Action action) {
        return baseWithOptions(args, action, 2);
    }

    private RequestBuilder baseWithOptions(Varargs args, LuaRemoteAdminRequest.Action action, int optionsIndex) {
        LuaRemoteAdminBridge bridge = context.remoteAdminBridge();
        if (bridge == null || !bridge.isRemoteAdminAvailable()) {
            throw new LuaError("Remote admin is not available");
        }
        TargetNode target = readTargetNode(args.arg1());
        LuaTable options = args.arg(optionsIndex).istable() ? args.arg(optionsIndex).checktable() : null;
        RequestBuilder builder = new RequestBuilder();
        builder.action = action;
        builder.scriptId = context.scriptId();
        builder.source = "mesh.admin." + luaName(action);
        builder.name = options != null ? optionalString(options, "name") : "";
        builder.targetNodeNum = target.nodeNum();
        builder.targetNodeId = target.nodeId();
        builder.targetName = target.displayName();
        builder.confirm = options != null && booleanOption(options, "confirm", false);
        builder.replace = options != null && booleanOption(options, "replace", false);
        return builder;
    }

    private VarArgFunction function(AdminFunction body) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return body.invoke(args);
            }
        };
    }

    private VarArgFunction dangerousFunction(LuaRemoteAdminRequest.Action action, RequestFactory factory) {
        return function(args -> {
            RequestBuilder builder = factory.create(args);
            LuaTable options = args.arg(args.narg()).istable() ? args.arg(args.narg()).checktable() : null;
            if (!(builder.confirm || (options != null && booleanOption(options, "confirm", false)))) {
                throw new LuaError("mesh.admin." + luaName(action) + " requires options.confirm = true");
            }
            builder.confirm = true;
            return start(builder);
        });
    }

    private void readSaveChanges(RequestBuilder builder, LuaTable changes) {
        if (changes.get("owner").istable()) {
            readOwner(builder, changes.get("owner").checktable());
        }
        if (changes.get("position").istable()) {
            readPosition(builder, changes.get("position").checktable());
        }
        if (booleanOption(changes, "remove_position", false)) {
            builder.removePosition = true;
        }
        if (!changes.get("ringtone").isnil()) {
            builder.ringtone = changes.get("ringtone").checkjstring();
            builder.ringtoneSet = true;
        }
        if (!changes.get("canned_messages").isnil()) {
            builder.cannedMessages = changes.get("canned_messages").checkjstring();
            builder.cannedMessagesSet = true;
        }
        builder.configs = readPatches(changes.get("configs"), "configs");
        builder.moduleConfigs = readPatches(changes.get("module_configs"), "module_configs");
        builder.channels = readPatches(changes.get("channels"), "channels");
    }

    private void readOwner(RequestBuilder builder, LuaTable owner) {
        builder.longName = optionalString(owner, "long_name");
        builder.shortName = optionalString(owner, "short_name");
        builder.licensed = booleanOption(owner, "licensed", false);
        builder.ownerSet = true;
    }

    private void readPosition(RequestBuilder builder, LuaTable position) {
        builder.latitude = numberField(position, "latitude");
        builder.longitude = numberField(position, "longitude");
        builder.altitude = LuaValueMapper.tableInt(position, "altitude", 0);
        builder.positionSet = true;
    }

    @SuppressWarnings("unchecked")
    private List<LuaRemoteAdminRequest.MessagePatch> readPatches(LuaValue value, String description) {
        if (value == null || value.isnil()) {
            return List.of();
        }
        Object converted = LuaProtobufMapper.luaToJava(value);
        List<LuaRemoteAdminRequest.MessagePatch> patches = new ArrayList<>();
        if (converted instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> itemMap)) {
                    throw new LuaError(description + " entries must be tables");
                }
                Map<String, Object> values = (Map<String, Object>) itemMap;
                String section = stringValue(values.get("section"), values.get("type"));
                patches.add(new LuaRemoteAdminRequest.MessagePatch(section, values));
            }
            return patches;
        }
        if (converted instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    continue;
                }
                if (!(entry.getValue() instanceof Map<?, ?> valueMap)) {
                    throw new LuaError(description + "." + key + " must be a table");
                }
                Map<String, Object> values = (Map<String, Object>) valueMap;
                String section = stringValue(values.get("section"), values.get("type"));
                patches.add(new LuaRemoteAdminRequest.MessagePatch(
                        section != null && !section.isBlank() ? section : key,
                        values));
            }
            return patches;
        }
        throw new LuaError(description + " must be a table");
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

    private AdminProtos.AdminMessage.ConfigType parseConfigType(String value) {
        String normalized = normalizeType(value);
        for (AdminProtos.AdminMessage.ConfigType type : AdminProtos.AdminMessage.ConfigType.values()) {
            if (type == AdminProtos.AdminMessage.ConfigType.UNRECOGNIZED
                    || type == AdminProtos.AdminMessage.ConfigType.SESSIONKEY_CONFIG) {
                continue;
            }
            if (normalizeType(type.name()).equals(normalized)) {
                return type;
            }
        }
        throw new LuaError("Unknown config type: " + value);
    }

    private AdminProtos.AdminMessage.ModuleConfigType parseModuleConfigType(String value) {
        String normalized = normalizeType(value);
        for (AdminProtos.AdminMessage.ModuleConfigType type : AdminProtos.AdminMessage.ModuleConfigType.values()) {
            if (type == AdminProtos.AdminMessage.ModuleConfigType.UNRECOGNIZED) {
                continue;
            }
            if (normalizeType(type.name()).equals(normalized)) {
                return type;
            }
        }
        throw new LuaError("Unknown module config type: " + value);
    }

    private AdminProtos.AdminMessage.BackupLocation parseBackupLocation(String value) {
        try {
            return AdminProtos.AdminMessage.BackupLocation.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new LuaError("Unknown backup location: " + value);
        }
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

    private static String normalizeType(String value) {
        String normalized = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        if (normalized.endsWith("_config")) {
            normalized = normalized.substring(0, normalized.length() - "_config".length());
        }
        return normalized.replace("_", "");
    }

    private static String luaName(LuaRemoteAdminRequest.Action action) {
        return action.name().toLowerCase(Locale.ROOT);
    }

    private static String optionalString(LuaTable table, String key) {
        LuaValue value = table.get(key);
        return value.isnil() ? "" : value.checkjstring();
    }

    private static boolean isTableArg(Varargs args, int index) {
        return args.narg() >= index && args.arg(index).istable();
    }

    private static boolean booleanOption(LuaTable table, String key, boolean fallback) {
        LuaValue value = table.get(key);
        return value.isnil() ? fallback : value.toboolean();
    }

    private static double numberField(LuaTable table, String key) {
        LuaValue value = table.get(key);
        if (value.isnil()) {
            throw new LuaError(key + " is required");
        }
        return value.checkdouble();
    }

    private static String stringValue(Object primary, Object fallback) {
        Object value = primary != null ? primary : fallback;
        return value != null ? value.toString() : null;
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

    private interface AdminFunction {
        Varargs invoke(Varargs args);
    }

    private interface RequestFactory {
        RequestBuilder create(Varargs args);
    }

    private record TargetNode(int nodeNum, String nodeId, String displayName) {}

    private static final class RequestBuilder {
        private String requestId;
        private long scriptId;
        private String source;
        private String name = "";
        private int targetNodeNum;
        private String targetNodeId = "";
        private String targetName = "";
        private LuaRemoteAdminRequest.Action action;
        private AdminProtos.AdminMessage.ConfigType configType;
        private AdminProtos.AdminMessage.ModuleConfigType moduleConfigType;
        private AdminProtos.AdminMessage.BackupLocation backupLocation;
        private int delaySeconds;
        private long epochSeconds;
        private boolean preserveFavorites = true;
        private String longName = "";
        private String shortName = "";
        private boolean licensed;
        private boolean ownerSet;
        private double latitude;
        private double longitude;
        private int altitude;
        private boolean positionSet;
        private boolean removePosition;
        private String ringtone = "";
        private boolean ringtoneSet;
        private String cannedMessages = "";
        private boolean cannedMessagesSet;
        private boolean confirm;
        private boolean replace;
        private List<LuaRemoteAdminRequest.MessagePatch> configs = List.of();
        private List<LuaRemoteAdminRequest.MessagePatch> moduleConfigs = List.of();
        private List<LuaRemoteAdminRequest.MessagePatch> channels = List.of();

        private LuaRemoteAdminRequest build() {
            return new LuaRemoteAdminRequest(
                    scriptId,
                    requestId,
                    source,
                    name,
                    targetNodeNum,
                    targetNodeId,
                    targetName,
                    action,
                    configType,
                    moduleConfigType,
                    backupLocation,
                    delaySeconds,
                    epochSeconds,
                    preserveFavorites,
                    longName,
                    shortName,
                    licensed,
                    ownerSet,
                    latitude,
                    longitude,
                    altitude,
                    positionSet,
                    removePosition,
                    ringtone,
                    ringtoneSet,
                    cannedMessages,
                    cannedMessagesSet,
                    replace,
                    configs,
                    moduleConfigs,
                    channels);
        }
    }
}
