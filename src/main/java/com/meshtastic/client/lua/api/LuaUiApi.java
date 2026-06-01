package com.meshtastic.client.lua.api;

import com.meshtastic.client.lua.LuaUiBridge;
import com.meshtastic.client.lua.LuaUiNodePickRequest;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * UI-функции Lua sandbox, требующие участия пользователя.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaUiApi {

    private final LuaSandboxContext context;

    public LuaUiApi(LuaSandboxContext context) {
        this.context = context;
    }

    /**
     * Создает Lua-таблицу {@code mesh.ui}.
     *
     * @return таблица UI API
     */
    public LuaTable create() {
        LuaTable ui = new LuaTable();
        ui.set("pick_node", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaUiBridge bridge = context.uiBridge();
                if (bridge == null || !bridge.isAvailable()) {
                    throw new LuaError("No active UI context");
                }

                PickNodeOptions options = readPickNodeOptions(args.arg1());
                String requestId = bridge.nextRequestId();
                bridge.requestNodePick(new LuaUiNodePickRequest(
                        context.scriptId(),
                        requestId,
                        "mesh.ui.pick_node",
                        options.name(),
                        options.prompt(),
                        options.query(),
                        options.chatType(),
                        options.chatKey()));
                return LuaValue.valueOf(requestId);
            }
        });
        return ui;
    }

    private PickNodeOptions readPickNodeOptions(LuaValue value) {
        if (value == null || value.isnil()) {
            return new PickNodeOptions("", "", "", "", "");
        }
        if (value.isstring()) {
            return new PickNodeOptions("", "", value.checkjstring(), "", "");
        }
        LuaTable table = value.checktable();
        return new PickNodeOptions(
                optionalString(table, "name"),
                optionalString(table, "prompt"),
                optionalString(table, "query"),
                optionalString(table, "chat_type"),
                optionalString(table, "chat_key"));
    }

    private static String optionalString(LuaTable table, String key) {
        LuaValue value = table.get(key);
        return value.isnil() ? "" : value.checkjstring();
    }

    private record PickNodeOptions(String name, String prompt, String query, String chatType, String chatKey) {}
}
