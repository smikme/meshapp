package com.meshtastic.client.lua.api;

import com.meshtastic.client.lua.LuaTimerBridge;
import com.meshtastic.client.lua.LuaTimerRequest;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.Locale;

/**
 * Host-managed timer API exposed to the Lua sandbox.
 * <p>
 * The module is installed as {@code mesh.timer}. Timers are scheduled by the
 * Java runtime and delivered back to Lua through {@code on_timer(event)}, which
 * lets scripts react to time without blocking the single Lua executor in a sleep
 * loop. Repeating timers can run at fixed intervals or align to local
 * wall-clock boundaries.
 */
public final class LuaTimerApi {

    private static final double MIN_SECONDS = 0.1;
    private static final double MAX_SECONDS = 7 * 24 * 60 * 60;

    private final LuaSandboxContext context;

    /**
     * Creates a timer API bound to one sandbox execution context.
     *
     * @param context sandbox context that provides the timer bridge
     */
    public LuaTimerApi(LuaSandboxContext context) {
        this.context = context;
    }

    /**
     * Creates the Lua table for {@code mesh.timer}.
     * <p>
     * The returned table exposes {@code after}, {@code every}, {@code cancel},
     * and {@code cancel_all}.
     *
     * @return timer API table
     */
    public LuaTable create() {
        LuaTable timer = new LuaTable();
        timer.set("after", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                TimerOptions options = readOptions(args.arg(2), false);
                return schedule(
                        "mesh.timer.after",
                        args.arg(1),
                        false,
                        "interval",
                        false,
                        options);
            }
        });
        timer.set("every", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                TimerOptions options = readOptions(args.arg(2), true);
                return schedule(
                        "mesh.timer.every",
                        args.arg(1),
                        true,
                        options.align(),
                        options.immediate(),
                        options);
            }
        });
        timer.set("cancel", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue timerIdArg) {
                String timerId = timerIdArg.checkjstring();
                return LuaValue.valueOf(bridge().cancelTimer(timerId));
            }
        });
        timer.set("cancel_all", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(bridge().cancelAllTimers());
            }
        });
        return timer;
    }

    private LuaValue schedule(String source,
                              LuaValue secondsArg,
                              boolean repeating,
                              String align,
                              boolean immediate,
                              TimerOptions options) {
        double seconds = secondsArg.checkdouble();
        if (!Double.isFinite(seconds) || seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            throw new LuaError(source + ": seconds must be between " + MIN_SECONDS + " and " + MAX_SECONDS);
        }
        LuaTimerBridge bridge = bridge();
        String timerId = bridge.nextTimerId();
        bridge.scheduleTimer(new LuaTimerRequest(
                context.scriptId(),
                timerId,
                source,
                options.name(),
                seconds,
                repeating,
                align,
                immediate));
        return LuaValue.valueOf(timerId);
    }

    private LuaTimerBridge bridge() {
        LuaTimerBridge bridge = context.timerBridge();
        if (bridge == null) {
            throw new LuaError("Timers are not available");
        }
        return bridge;
    }

    private TimerOptions readOptions(LuaValue value, boolean repeating) {
        if (value == null || value.isnil()) {
            return new TimerOptions("", "interval", false);
        }
        LuaTable table = value.checktable();
        String align = optionalString(table, "align", "interval").toLowerCase(Locale.ROOT);
        if (!repeating) {
            align = "interval";
        } else if (!"interval".equals(align) && !"wall".equals(align)) {
            throw new LuaError("mesh.timer.every: align must be 'interval' or 'wall'");
        }
        return new TimerOptions(
                optionalString(table, "name", ""),
                align,
                optionalBoolean(table, "immediate", false));
    }

    private static String optionalString(LuaTable table, String key, String defaultValue) {
        LuaValue value = table.get(key);
        return value.isnil() ? defaultValue : value.checkjstring();
    }

    private static boolean optionalBoolean(LuaTable table, String key, boolean defaultValue) {
        LuaValue value = table.get(key);
        return value.isnil() ? defaultValue : value.checkboolean();
    }

    private record TimerOptions(String name, String align, boolean immediate) {}
}
