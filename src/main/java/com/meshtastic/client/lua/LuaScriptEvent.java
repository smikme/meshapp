package com.meshtastic.client.lua;

public record LuaScriptEvent(Type type, long scriptId, String message, Throwable error) {

    public enum Type {
        INFO,
        OUTPUT,
        WARNING,
        ERROR,
        STARTED,
        STOPPED,
        DEBUG_PAUSED,
        DEBUG_RESUMED
    }

    public static LuaScriptEvent info(long scriptId, String message) {
        return new LuaScriptEvent(Type.INFO, scriptId, message, null);
    }

    public static LuaScriptEvent output(long scriptId, String message) {
        return new LuaScriptEvent(Type.OUTPUT, scriptId, message, null);
    }

    public static LuaScriptEvent warning(long scriptId, String message) {
        return new LuaScriptEvent(Type.WARNING, scriptId, message, null);
    }

    public static LuaScriptEvent error(long scriptId, String message, Throwable error) {
        return new LuaScriptEvent(Type.ERROR, scriptId, message, error);
    }

    public static LuaScriptEvent started(long scriptId, String message) {
        return new LuaScriptEvent(Type.STARTED, scriptId, message, null);
    }

    public static LuaScriptEvent stopped(long scriptId, String message) {
        return new LuaScriptEvent(Type.STOPPED, scriptId, message, null);
    }

    public static LuaScriptEvent debugPaused(long scriptId, String message) {
        return new LuaScriptEvent(Type.DEBUG_PAUSED, scriptId, message, null);
    }

    public static LuaScriptEvent debugResumed(long scriptId, String message) {
        return new LuaScriptEvent(Type.DEBUG_RESUMED, scriptId, message, null);
    }
}
