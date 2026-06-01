package com.meshtastic.client.lua;

/**
 * Lifecycle or output event produced by a Lua script.
 * <p>
 * Used by the runtime to deliver start and stop status, errors, {@code print}
 * or {@code mesh.log} output, and debug events to the UI.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaScriptEvent(Type type, long scriptId, String message, Throwable error, Object payload) {

    public LuaScriptEvent(Type type, long scriptId, String message, Throwable error) {
        this(type, scriptId, message, error, null);
    }

    /**
     * Lua runtime event type.
     */
    public enum Type {
        INFO,
        OUTPUT,
        WARNING,
        ERROR,
        STARTED,
        STOPPED,
        UI_BOT_NOTICE,
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

    public static LuaScriptEvent uiBotNotice(long scriptId, LuaUiBotNotice notice) {
        return new LuaScriptEvent(
                Type.UI_BOT_NOTICE,
                scriptId,
                notice != null ? notice.text() : "",
                null,
                notice);
    }

    public static LuaScriptEvent debugPaused(long scriptId, String message) {
        return new LuaScriptEvent(Type.DEBUG_PAUSED, scriptId, message, null);
    }

    public static LuaScriptEvent debugResumed(long scriptId, String message) {
        return new LuaScriptEvent(Type.DEBUG_RESUMED, scriptId, message, null);
    }
}
