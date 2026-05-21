package com.meshtastic.client.lua;

import java.util.List;

/**
 * Снимок состояния Lua-отладки в момент остановки на строке.
 *
 * @param scriptId  идентификатор скрипта
 * @param line      текущая строка выполнения
 * @param reason    причина остановки
 * @param variables видимые локальные, upvalue и глобальные переменные
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaDebugSnapshot(long scriptId,
                               int line,
                               String reason,
                               List<LuaDebugVariable> variables) {
    public LuaDebugSnapshot {
        variables = variables == null ? List.of() : List.copyOf(variables);
    }
}
