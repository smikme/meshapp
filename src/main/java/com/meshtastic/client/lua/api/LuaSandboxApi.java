package com.meshtastic.client.lua.api;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

/**
 * Root installer for the MeshApp Lua sandbox API.
 * <p>
 * Creates the {@code mesh} namespace and attaches the available extension
 * modules:
 * {@code mesh.chat}, {@code mesh.kv}, {@code mesh.curl}, {@code mesh.ui},
 * {@code mesh.canvas}, {@code mesh.traceroute}, {@code mesh.nodeinfo}, plus the
 * core functions {@code mesh.log}, {@code mesh.now}, {@code mesh.owner}, and
 * {@code mesh.sleep}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaSandboxApi {

    private static final double MAX_SLEEP_SECONDS = 10.0;

    private final LuaSandboxContext context;
    private final LuaValueMapper mapper;

    public LuaSandboxApi(LuaSandboxContext context) {
        this.context = context;
        this.mapper = new LuaValueMapper(context.state());
    }

    /**
     * Installs the allowed API into the Lua globals table.
     *
     * @param globals Lua globals for the current sandbox session
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
        mesh.set("sleep", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue secondsArg) {
                double seconds = secondsArg.checkdouble();
                if (!Double.isFinite(seconds) || seconds < 0.0 || seconds > MAX_SLEEP_SECONDS) {
                    throw new LuaError("mesh.sleep: seconds must be between 0 and " + MAX_SLEEP_SECONDS);
                }
                try {
                    Thread.sleep(Math.round(seconds * 1000.0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LuaError("Lua script stopped");
                }
                context.deferExecutionDeadline();
                return LuaValue.TRUE;
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
        mesh.set("canvas", new LuaCanvasApi(context).create());
        mesh.set("traceroute", new LuaTracerouteApi(context).create());
        mesh.set("nodeinfo", new LuaNodeInfoApi(context).create());
        mesh.set("command", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return commandToTable();
            }
        });
        globals.set("mesh", mesh);
    }

    /**
     * Returns the model-to-table mapper shared by callbacks and sandbox APIs.
     *
     * @return mapper for the current session
     */
    public LuaValueMapper mapper() {
        return mapper;
    }

    private LuaTable ownerTable() {
        LuaTable table = new LuaTable();
        table.set("node_id", LuaValueMapper.stringOrNil(context.ownerNodeId()));
        if (context.state() != null) {
            table.set("node_num", LuaValueMapper.uint32ToLuaValue(context.state().getMyNodeNum()));
        }
        table.set("connection_id", LuaValueMapper.stringOrNil(context.connectionId()));
        return table;
    }

    public LuaTable commandToTable() {
        LuaTable table = new LuaTable();
        if (context.command() == null) {
            return table;
        }
        table.set("type", LuaValue.valueOf("chat_command"));
        table.set("source", LuaValue.valueOf("chat"));
        table.set("name", LuaValueMapper.stringOrNil(context.command().handle()));
        table.set("request_id", LuaValue.valueOf(commandRequestId()));
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

    private String commandRequestId() {
        String requestId = context.command() != null ? context.command().requestId() : null;
        return requestId != null && !requestId.isBlank()
                ? requestId
                : context.scriptId() + ":command";
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
