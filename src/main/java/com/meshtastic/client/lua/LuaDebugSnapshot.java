package com.meshtastic.client.lua;

import java.util.List;

/**
 * Snapshot of Lua debug state while execution is paused on a line.
 *
 * @param scriptId  script identifier
 * @param line      current execution line
 * @param reason    pause reason
 * @param variables visible locals, upvalues, and globals
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
