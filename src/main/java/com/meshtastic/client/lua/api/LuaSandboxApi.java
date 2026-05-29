package com.meshtastic.client.lua.api;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

/**
 * Корневой установщик разрешенного API Lua-песочницы MeshApp.
 * <p>
 * Создает namespace {@code mesh} и подключает отдельные модули расширений:
 * {@code mesh.chat}, {@code mesh.kv}, {@code mesh.curl}, {@code mesh.ui}, а также базовые
 * функции {@code mesh.log}, {@code mesh.now}, {@code mesh.owner}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaSandboxApi {

    private final LuaSandboxContext context;
    private final LuaValueMapper mapper;

    public LuaSandboxApi(LuaSandboxContext context) {
        this.context = context;
        this.mapper = new LuaValueMapper(context.state());
    }

    /**
     * Устанавливает разрешенный API в Lua globals.
     *
     * @param globals Lua globals конкретной sandbox-сессии
     */
    public void install(Globals globals) {
        globals.set("print", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                context.emitOutput(joinArgs(args));
                return LuaValue.NONE;
            }
        });

        LuaTable mesh = new LuaTable();
        mesh.set("log", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                context.emitOutput(arg.tojstring());
                return LuaValue.NIL;
            }
        });
        mesh.set("now", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(System.currentTimeMillis() / 1000.0);
            }
        });
        mesh.set("owner", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return ownerTable();
            }
        });
        mesh.set("chat", new LuaChatApi(context, mapper).create());
        mesh.set("kv", new LuaKvApi(context).create());
        mesh.set("curl", new LuaCurlApi().create());
        mesh.set("ui", new LuaUiApi(context).create());
        mesh.set("command", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return commandToTable();
            }
        });
        globals.set("mesh", mesh);
    }

    /**
     * Преобразователь моделей в Lua-таблицы, общий для callback и sandbox API.
     *
     * @return mapper текущей сессии
     */
    public LuaValueMapper mapper() {
        return mapper;
    }

    private LuaTable ownerTable() {
        LuaTable table = new LuaTable();
        table.set("node_id", LuaValueMapper.stringOrNil(context.ownerNodeId()));
        if (context.state() != null) {
            table.set("node_num", LuaValue.valueOf(context.state().getMyNodeNum()));
        }
        table.set("connection_id", LuaValueMapper.stringOrNil(context.connectionId()));
        return table;
    }

    public LuaTable commandToTable() {
        LuaTable table = new LuaTable();
        if (context.command() == null) {
            return table;
        }
        table.set("chat_type", LuaValueMapper.stringOrNil(context.command().chatType()));
        table.set("chat_key", LuaValueMapper.stringOrNil(context.command().chatKey()));
        table.set("handle", LuaValueMapper.stringOrNil(context.command().handle()));
        table.set("text", LuaValueMapper.stringOrNil(context.command().text()));
        table.set("arguments", LuaValueMapper.stringOrNil(context.command().arguments()));

        LuaTable tokens = new LuaTable();
        for (int i = 0; i < context.command().argumentTokens().size(); i++) {
            tokens.set(i + 1, LuaValue.valueOf(context.command().argumentTokens().get(i)));
        }
        table.set("argument_tokens", tokens);
        return table;
    }

    private String joinArgs(Varargs args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= args.narg(); i++) {
            if (i > 1) {
                sb.append('\t');
            }
            sb.append(args.arg(i).tojstring());
        }
        return sb.toString();
    }
}
