package com.meshtastic.client.lua.api;

import com.meshtastic.client.model.DeviceState;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * Root installer for the MeshApp Lua sandbox API.
 * <p>
 * Creates the {@code mesh} namespace and attaches the available extension
 * modules:
 * {@code mesh.chat}, {@code mesh.kv}, {@code mesh.json}, {@code mesh.curl},
 * {@code mesh.ui}, {@code mesh.timer}, {@code mesh.canvas}, {@code mesh.form}, {@code mesh.traceroute},
 * {@code mesh.nodeinfo}, {@code mesh.admin}, {@code mesh.telemetry}, plus the
 * core functions {@code mesh.log}, {@code mesh.now}, {@code mesh.localtime},
 * {@code mesh.date}, {@code mesh.time}, {@code mesh.datetime},
 * {@code mesh.iso_date}, {@code mesh.iso_time}, {@code mesh.iso_datetime},
 * {@code mesh.owner}, and {@code mesh.sleep}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaSandboxApi {

    private static final double MAX_SLEEP_SECONDS = 10.0;
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter ISO_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LuaSandboxContext context;
    private final LuaValueMapper mapper;

    public LuaSandboxApi(LuaSandboxContext context) {
        this.context = context;
        this.mapper = new LuaValueMapper(context::currentState);
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
        mesh.set("localtime", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return localTimeTable(zonedDateTime(args.arg(1), "mesh.localtime"));
            }
        });
        mesh.set("date", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(localizedDateTimeFormatter(FormatStyle.SHORT, null)
                        .format(zonedDateTime(args.arg(1), "mesh.date")));
            }
        });
        mesh.set("time", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(localizedDateTimeFormatter(null, FormatStyle.MEDIUM)
                        .format(zonedDateTime(args.arg(1), "mesh.time")));
            }
        });
        mesh.set("datetime", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(localizedDateTimeFormatter(FormatStyle.SHORT, FormatStyle.MEDIUM)
                        .format(zonedDateTime(args.arg(1), "mesh.datetime")));
            }
        });
        mesh.set("iso_date", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(ISO_DATE.format(zonedDateTime(args.arg(1), "mesh.iso_date")));
            }
        });
        mesh.set("iso_time", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(ISO_TIME.format(zonedDateTime(args.arg(1), "mesh.iso_time")));
            }
        });
        mesh.set("iso_datetime", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(ISO_DATE_TIME.format(zonedDateTime(args.arg(1), "mesh.iso_datetime")));
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
        mesh.set("json", new LuaJsonApi().create());
        mesh.set("curl", new LuaCurlApi().create());
        mesh.set("ui", new LuaUiApi(context).create());
        mesh.set("timer", new LuaTimerApi(context).create());
        mesh.set("canvas", new LuaCanvasApi(context).create());
        mesh.set("form", new LuaFormApi(context).create());
        mesh.set("traceroute", new LuaTracerouteApi(context).create());
        mesh.set("nodeinfo", new LuaNodeInfoApi(context).create());
        mesh.set("admin", new LuaRemoteAdminApi(context).create());
        mesh.set("telemetry", new LuaTelemetryApi(context, mapper).create());
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

    /**
     * Builds the same local time table returned by {@code mesh.localtime(...)}.
     *
     * @param epochSeconds Unix time in seconds
     * @return Lua local time table
     */
    public LuaTable localTimeTable(double epochSeconds) {
        return localTimeTable(zonedDateTime(LuaValue.valueOf(epochSeconds), "mesh.localtime"));
    }

    private LuaTable localTimeTable(ZonedDateTime dateTime) {
        LuaTable table = new LuaTable();
        int offsetSeconds = dateTime.getOffset().getTotalSeconds();
        int isoWeekday = dateTime.getDayOfWeek().getValue();
        int luaWeekday = dateTime.getDayOfWeek().getValue() % 7 + 1;

        table.set("year", LuaValue.valueOf(dateTime.getYear()));
        table.set("month", LuaValue.valueOf(dateTime.getMonthValue()));
        table.set("day", LuaValue.valueOf(dateTime.getDayOfMonth()));
        table.set("hour", LuaValue.valueOf(dateTime.getHour()));
        table.set("minute", LuaValue.valueOf(dateTime.getMinute()));
        table.set("min", LuaValue.valueOf(dateTime.getMinute()));
        table.set("second", LuaValue.valueOf(dateTime.getSecond()));
        table.set("sec", LuaValue.valueOf(dateTime.getSecond()));
        table.set("weekday", LuaValue.valueOf(isoWeekday));
        table.set("wday", LuaValue.valueOf(luaWeekday));
        table.set("yearday", LuaValue.valueOf(dateTime.getDayOfYear()));
        table.set("yday", LuaValue.valueOf(dateTime.getDayOfYear()));
        table.set("timezone", LuaValue.valueOf(dateTime.getZone().getId()));
        table.set("zone", LuaValue.valueOf(dateTime.getZone().getId()));
        table.set("offset", LuaValue.valueOf(formatOffset(offsetSeconds)));
        table.set("offset_seconds", LuaValue.valueOf(offsetSeconds));
        table.set("epoch", LuaValue.valueOf(dateTime.toInstant().toEpochMilli() / 1000.0));
        table.set("date", LuaValue.valueOf(localizedDateTimeFormatter(FormatStyle.SHORT, null).format(dateTime)));
        table.set("time", LuaValue.valueOf(localizedDateTimeFormatter(null, FormatStyle.MEDIUM).format(dateTime)));
        table.set("datetime", LuaValue.valueOf(localizedDateTimeFormatter(FormatStyle.SHORT, FormatStyle.MEDIUM)
                .format(dateTime)));
        table.set("iso_date", LuaValue.valueOf(ISO_DATE.format(dateTime)));
        table.set("iso_time", LuaValue.valueOf(ISO_TIME.format(dateTime)));
        table.set("iso_datetime", LuaValue.valueOf(ISO_DATE_TIME.format(dateTime)));
        table.set("iso", LuaValue.valueOf(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime)));
        return table;
    }

    private ZonedDateTime zonedDateTime(LuaValue epochArg, String functionName) {
        ZoneId zone = ZoneId.systemDefault();
        if (epochArg == null || epochArg.isnil()) {
            return ZonedDateTime.now(zone);
        }
        double epochSeconds = epochArg.checkdouble();
        if (!Double.isFinite(epochSeconds)) {
            throw new LuaError(functionName + ": epoch_seconds must be a finite number");
        }
        try {
            long wholeSeconds = (long) Math.floor(epochSeconds);
            long nanos = Math.round((epochSeconds - wholeSeconds) * 1_000_000_000.0);
            if (nanos == 1_000_000_000L) {
                wholeSeconds++;
                nanos = 0L;
            }
            return Instant.ofEpochSecond(wholeSeconds, nanos).atZone(zone);
        } catch (RuntimeException e) {
            throw new LuaError(functionName + ": epoch_seconds is out of range");
        }
    }

    private DateTimeFormatter localizedDateTimeFormatter(FormatStyle dateStyle, FormatStyle timeStyle) {
        DateTimeFormatter formatter;
        if (dateStyle != null && timeStyle != null) {
            formatter = DateTimeFormatter.ofLocalizedDateTime(dateStyle, timeStyle);
        } else if (dateStyle != null) {
            formatter = DateTimeFormatter.ofLocalizedDate(dateStyle);
        } else {
            formatter = DateTimeFormatter.ofLocalizedTime(timeStyle);
        }
        return formatter.withLocale(Locale.getDefault(Locale.Category.FORMAT));
    }

    private String formatOffset(int offsetSeconds) {
        String sign = offsetSeconds < 0 ? "-" : "+";
        int absSeconds = Math.abs(offsetSeconds);
        int hours = absSeconds / 3600;
        int minutes = (absSeconds % 3600) / 60;
        int seconds = absSeconds % 60;
        if (seconds == 0) {
            return String.format(Locale.ROOT, "%s%02d:%02d", sign, hours, minutes);
        }
        return String.format(Locale.ROOT, "%s%02d:%02d:%02d", sign, hours, minutes, seconds);
    }

    private LuaTable ownerTable() {
        LuaTable table = new LuaTable();
        DeviceState state = context.currentState();
        table.set("node_id", LuaValueMapper.stringOrNil(context.currentOwnerNodeId()));
        if (state != null) {
            table.set("node_num", LuaValueMapper.uint32ToLuaValue(state.getMyNodeNum()));
        }
        table.set("connection_id", LuaValueMapper.stringOrNil(context.currentConnectionId()));
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
