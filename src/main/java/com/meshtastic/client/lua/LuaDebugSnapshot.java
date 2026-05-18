package com.meshtastic.client.lua;

import java.util.List;

public record LuaDebugSnapshot(long scriptId,
                               int line,
                               String reason,
                               List<LuaDebugVariable> variables) {
    public LuaDebugSnapshot {
        variables = variables == null ? List.of() : List.copyOf(variables);
    }
}
