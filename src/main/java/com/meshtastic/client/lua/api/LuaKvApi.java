package com.meshtastic.client.lua.api;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.Map;

/**
 * Реализация {@code mesh.kv} для Lua-песочницы.
 * <p>
 * Предоставляет изолированное key-value хранилище, где каждое значение
 * привязано к конкретному скрипту в БД приложения.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaKvApi {

    private final LuaSandboxContext context;

    public LuaKvApi(LuaSandboxContext context) {
        this.context = context;
    }

    /**
     * Создает Lua-таблицу {@code mesh.kv}.
     *
     * @return таблица KV API
     */
    public LuaTable create() {
        LuaTable kv = new LuaTable();
        kv.set("get", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue keyArg) {
                String value = context.scriptService().getKv(context.scriptId(), keyArg.checkjstring());
                return value != null ? LuaValue.valueOf(value) : LuaValue.NIL;
            }
        });
        kv.set("set", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue keyArg, LuaValue valueArg) {
                context.scriptService().setKv(
                        context.scriptId(),
                        keyArg.checkjstring(),
                        valueArg.isnil() ? null : valueArg.tojstring());
                return LuaValue.TRUE;
            }
        });
        kv.set("delete", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue keyArg) {
                return LuaValue.valueOf(context.scriptService().deleteKv(context.scriptId(), keyArg.checkjstring()));
            }
        });
        kv.set("list", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable table = new LuaTable();
                for (Map.Entry<String, String> entry : context.scriptService().listKv(context.scriptId()).entrySet()) {
                    table.set(entry.getKey(), entry.getValue() != null ? LuaValue.valueOf(entry.getValue()) : LuaValue.NIL);
                }
                return table;
            }
        });
        kv.set("clear", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                context.scriptService().clearKv(context.scriptId());
                return LuaValue.TRUE;
            }
        });
        return kv;
    }
}
