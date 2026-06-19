package com.meshtastic.client.lua;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Context-aware Lua completion engine for MeshApp IDE.
 * <p>
 * Knows the allowed {@code mesh.*} sandbox API, standard Lua libraries, fields
 * of application message/node/channel objects, and performs lightweight static
 * analysis of variables, functions, tables, and aliases directly from the editor
 * text.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaCompletionEngine {

    private static final int MAX_COMPLETIONS = 32;
    private static final Pattern ON_MESSAGE_PATTERN =
            Pattern.compile("\\bfunction\\s+on_message\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern FUNCTION_PATTERN =
            Pattern.compile("\\b(?:local\\s+)?function\\s+([A-Za-z_][A-Za-z0-9_:.]*)\\s*\\(([^)]*)\\)");
    private static final Pattern FUNCTION_ASSIGN_PATTERN =
            Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_.]*)\\s*=\\s*function\\s*\\(([^)]*)\\)");
    private static final Pattern LOCAL_ASSIGN_PATTERN =
            Pattern.compile("\\blocal\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*)*)\\s*=\\s*([^\\n;]+)");
    private static final Pattern ASSIGN_PATTERN =
            Pattern.compile("(?m)(?<![.\\w])([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^\\n;]+)");
    private static final Pattern IPAIRS_FOR_PATTERN =
            Pattern.compile("\\bfor\\s+(?:[A-Za-z_][A-Za-z0-9_]*\\s*,\\s*)?([A-Za-z_][A-Za-z0-9_]*)\\s+in\\s+ipairs\\s*\\(([^)]*\\)[^)]*|[^)]*)\\)");
    private static final Pattern TABLE_ASSIGN_PATTERN =
            Pattern.compile("\\blocal\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\{([\\s\\S]*?)\\}", Pattern.MULTILINE);
    private static final Pattern TABLE_FIELD_PATTERN =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(function\\s*\\(|[^,}\\n]+)");
    private static final Pattern TABLE_MEMBER_ASSIGN_PATTERN =
            Pattern.compile("(?m)(?<![\\w.])([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(function\\s*\\(|[^\\n;]+)");

    private final Map<String, TypeDef> types;
    private final List<CompletionItem> rootItems;

    public LuaCompletionEngine() {
        TypeDef mesh = new TypeDef();
        mesh.member("log(text)", "log(", "function", null);
        mesh.member("now()", "now()", "function", "number");
        mesh.member("localtime(epoch_seconds)", "localtime(", "function", "time");
        mesh.member("date(epoch_seconds)", "date(", "function", "string");
        mesh.member("time(epoch_seconds)", "time(", "function", "string");
        mesh.member("datetime(epoch_seconds)", "datetime(", "function", "string");
        mesh.member("iso_date(epoch_seconds)", "iso_date(", "function", "string");
        mesh.member("iso_time(epoch_seconds)", "iso_time(", "function", "string");
        mesh.member("iso_datetime(epoch_seconds)", "iso_datetime(", "function", "string");
        mesh.member("sleep(seconds)", "sleep(", "function", "boolean");
        mesh.member("owner()", "owner()", "function", "owner");
        mesh.member("chat", "chat.", "object", "mesh.chat");
        mesh.member("kv", "kv.", "object", "mesh.kv");
        mesh.member("json", "json.", "object", "mesh.json");
        mesh.member("curl", "curl.", "object", "mesh.curl");
        mesh.member("ui", "ui.", "object", "mesh.ui");
        mesh.member("timer", "timer.", "object", "mesh.timer");
        mesh.member("canvas", "canvas.", "object", "mesh.canvas");
        mesh.member("form", "form.", "object", "mesh.form");
        mesh.member("traceroute", "traceroute.", "object", "mesh.traceroute");
        mesh.member("nodeinfo", "nodeinfo.", "object", "mesh.nodeinfo");
        mesh.member("admin", "admin.", "object", "mesh.admin");
        mesh.member("telemetry", "telemetry.", "object", "mesh.telemetry");
        mesh.member("command()", "command()", "function", "command");

        TypeDef chat = new TypeDef();
        chat.member("send_channel(channel, text, reply_id)", "send_channel(", "function", "message");
        chat.member("send_dm(node_id, text, reply_id)", "send_dm(", "function", "message");
        chat.member("reply(msg, text)", "reply(", "function", "message");
        chat.member("bot_message(chat_type, chat_key, text)", "bot_message(", "function", "message");
        chat.member("bot_reply(msg, text)", "bot_reply(", "function", "message");
        chat.member("bot_notice(chat_type, chat_key, text, options)", "bot_notice(", "function", "boolean");
        chat.member("recent(chat_type, chat_key, limit)", "recent(", "function", "list:message");
        chat.member("nodes()", "nodes()", "function", "list:node");
        chat.member("channels()", "channels()", "function", "list:channel");

        TypeDef kv = new TypeDef();
        kv.member("get(key)", "get(", "function", "string");
        kv.member("set(key, value)", "set(", "function", "boolean");
        kv.member("delete(key)", "delete(", "function", "boolean");
        kv.member("list()", "list()", "function", "table");
        kv.member("clear()", "clear()", "function", "boolean");

        TypeDef json = new TypeDef();
        json.member("decode(text)", "decode(", "function", "table");
        json.member("try_decode(text)", "try_decode(", "function", "table");
        json.member("encode(value, options)", "encode(", "function", "string");
        json.member("pretty(value)", "pretty(", "function", "string");
        json.member("array(table)", "array(", "function", "table");
        json.member("is_null(value)", "is_null(", "function", "boolean");
        json.member("null", "null", "field", null);

        TypeDef curl = new TypeDef();
        curl.member("get(url, options)", "get(", "function", "curl.response");
        curl.member("request(options)", "request(", "function", "curl.response");

        TypeDef curlResponse = new TypeDef();
        for (String field : List.of("ok", "status", "url", "body", "headers", "error", "truncated")) {
            curlResponse.member(field, field, "field", null);
        }

        TypeDef ui = new TypeDef();
        ui.member("pick_node(options)", "pick_node(", "function", "string");

        TypeDef timer = new TypeDef();
        timer.member("after(seconds, options)", "after(", "function", "string");
        timer.member("every(seconds, options)", "every(", "function", "string");
        timer.member("cancel(timer_id)", "cancel(", "function", "boolean");
        timer.member("cancel_all()", "cancel_all()", "function", "number");

        TypeDef form = new TypeDef();
        for (String member : List.of("show(options)", "set_title(title)", "clear()", "add(options)",
                "set(id, options)", "remove(id)", "value(id)")) {
            form.member(member, member.substring(0, member.indexOf('(') + 1), "function", null);
        }

        TypeDef canvas = new TypeDef();
        canvas.member("open(options)", "open(", "function", "boolean");
        canvas.member("close()", "close()", "function", "boolean");
        canvas.member("set_fps(fps)", "set_fps(", "function", "boolean");
        canvas.member("size()", "size()", "function", "canvas.size");
        canvas.member("mouse()", "mouse()", "function", "canvas.mouse");
        canvas.member("keys()", "keys()", "function", "canvas.keys");
        for (String member : List.of("clear(color)", "set_fill(color)", "set_stroke(color)",
                "set_line_width(width)", "set_font(size, family, weight)", "save()", "restore()",
                "translate(x, y)", "rotate(degrees)", "scale(x, y)",
                "fill_rect(x, y, w, h, color)", "stroke_rect(x, y, w, h, color, line_width)",
                "fill_round_rect(x, y, w, h, radius, color)",
                "stroke_round_rect(x, y, w, h, radius, color, line_width)",
                "line(x1, y1, x2, y2, color, line_width)",
                "fill_circle(x, y, radius, color)", "stroke_circle(x, y, radius, color, line_width)",
                "fill_ellipse(x, y, w, h, color)", "stroke_ellipse(x, y, w, h, color, line_width)",
                "fill_polygon(points, color)", "stroke_polygon(points, color, line_width)",
                "polyline(points, color, line_width)", "fill_text(text, x, y, color)",
                "stroke_text(text, x, y, color, line_width)", "text(text, x, y, color)")) {
            canvas.member(member, member.substring(0, member.indexOf('(') + 1), "function", "boolean");
        }

        TypeDef traceroute = new TypeDef();
        traceroute.member("request(target, options)", "request(", "function", "string");

        TypeDef nodeinfo = new TypeDef();
        nodeinfo.member("request(target, options)", "request(", "function", "string");

        TypeDef admin = new TypeDef();
        for (String member : List.of(
                "load_config(target, options)",
                "request_config(target, type, options)",
                "request_module_config(target, type, options)",
                "save_config(target, changes, options)",
                "refresh_status(target, options)",
                "reboot(target, delay_seconds, options)",
                "shutdown(target, delay_seconds, options)",
                "sync_time(target, epoch_seconds, options)",
                "backup(target, location, options)",
                "restore(target, location, options)",
                "remove_backup(target, location, options)",
                "reset_nodedb(target, preserve_favorites, options)",
                "factory_reset_config(target, options)",
                "factory_reset_device(target, options)",
                "enter_dfu_mode(target, options)",
                "set_owner(target, owner, options)",
                "set_fixed_position(target, position, options)",
                "remove_fixed_position(target, options)",
                "set_ringtone(target, text, options)",
                "set_canned_messages(target, text, options)")) {
            admin.member(member, member.substring(0, member.indexOf('(') + 1), "function", "string");
        }

        TypeDef telemetry = new TypeDef();
        telemetry.member("recent(options)", "recent(", "function", "list:telemetry");
        telemetry.member("for_node(node_id, options)", "for_node(", "function", "list:telemetry");
        telemetry.member("query(options)", "query(", "function", "list:telemetry");
        telemetry.member("latest(node_id)", "latest(", "function", "telemetry");
        telemetry.member("fields()", "fields()", "function", "list:string");

        TypeDef command = new TypeDef();
        for (String field : List.of("type", "source", "name", "request_id",
                "chat_type", "chat_key", "handle", "text", "arguments", "argument_tokens")) {
            command.member(field, field, "field", null);
        }

        TypeDef nodeSelection = new TypeDef();
        for (String field : List.of("type", "source", "name", "request_id",
                "status", "selected", "cancelled", "chat_type", "chat_key")) {
            nodeSelection.member(field, field, "field", null);
        }
        nodeSelection.member("node", "node", "field", "node");

        TypeDef tracerouteEvent = new TypeDef();
        for (String field : List.of("type", "source", "name", "request_id", "status", "ok", "timeout",
                "error", "target_node_num", "target_node_id", "target_name",
                "response_from_node_num", "response_from_node_id", "chat_type", "chat_key")) {
            tracerouteEvent.member(field, field, "field", null);
        }
        tracerouteEvent.member("route", "route", "field", "route.discovery");

        TypeDef nodeInfoEvent = new TypeDef();
        for (String field : List.of("type", "source", "name", "request_id", "status", "ok", "timeout",
                "cached", "error", "target_node_num", "target_node_id", "target_name", "chat_type", "chat_key")) {
            nodeInfoEvent.member(field, field, "field", null);
        }
        nodeInfoEvent.member("node", "node", "field", "node");

        TypeDef adminEvent = new TypeDef();
        for (String field : List.of("type", "source", "name", "request_id", "action", "status", "ok", "timeout",
                "error", "target_node_num", "target_node_id", "target_name", "progress_key", "completed", "total")) {
            adminEvent.member(field, field, "field", null);
        }
        adminEvent.member("snapshot", "snapshot", "field", "admin.snapshot");

        TypeDef adminSnapshot = new TypeDef();
        for (String field : List.of("target_node_num", "target_node_id", "owner", "device_metadata", "ringtone",
                "canned_messages", "canned_messages_loaded", "connection_status", "configs", "module_configs",
                "channels", "query_statuses", "query_summary")) {
            adminSnapshot.member(field, field, "field", null);
        }
        adminSnapshot.member("node", "node", "field", "node");

        TypeDef timerEvent = new TypeDef();
        for (String field : List.of("type", "source", "id", "timer_id", "name", "interval_seconds", "seconds",
                "repeating", "align", "count", "scheduled_epoch", "actual_epoch", "drift_seconds")) {
            timerEvent.member(field, field, "field", null);
        }
        timerEvent.member("time", "time", "field", "time");

        TypeDef canvasEvent = new TypeDef();
        for (String field : List.of("type", "source", "x", "y", "screen_x", "screen_y", "button",
                "click_count", "primary", "middle", "secondary", "wheel_delta_x", "wheel_delta_y",
                "code", "key", "text", "shift", "ctrl", "alt", "meta", "width", "height", "time", "dt")) {
            canvasEvent.member(field, field, "field", null);
        }

        TypeDef canvasMouse = new TypeDef();
        for (String field : List.of("x", "y", "screen_x", "screen_y", "over", "pressed", "primary",
                "middle", "secondary", "button", "click_count", "wheel_delta_x", "wheel_delta_y",
                "last_type", "time")) {
            canvasMouse.member(field, field, "field", null);
        }

        TypeDef canvasKeys = new TypeDef();
        for (String field : List.of("pressed", "last_type", "last_code", "last_key", "text",
                "shift", "ctrl", "alt", "meta", "time")) {
            canvasKeys.member(field, field, "field", null);
        }

        TypeDef canvasSize = new TypeDef();
        canvasSize.member("width", "width", "field", null);
        canvasSize.member("height", "height", "field", null);

        TypeDef routeDiscovery = new TypeDef();
        for (String field : List.of("route", "route_back", "route_ids", "route_back_ids", "snr_towards", "snr_back")) {
            routeDiscovery.member(field, field, "field", null);
        }

        TypeDef message = new TypeDef();
        for (String field : List.of("db_id", "packet_id", "chat_type", "chat_key", "from", "to", "channel",
                "channel_name", "channel_role", "text", "reply_id", "reply_text", "timestamp", "outgoing", "system",
                "status", "sender_name", "hop_start", "hop_limit", "hops", "rx_rssi", "rx_snr")) {
            message.member(field, field, "field", null);
        }

        TypeDef owner = new TypeDef();
        owner.member("node_id", "node_id", "field", null);
        owner.member("node_num", "node_num", "field", null);
        owner.member("connection_id", "connection_id", "field", null);

        TypeDef time = new TypeDef();
        for (String field : List.of("year", "month", "day", "hour", "minute", "min", "second", "sec",
                "weekday", "wday", "yearday", "yday", "timezone", "zone", "offset", "offset_seconds",
                "epoch", "date", "time", "datetime", "iso_date", "iso_time", "iso_datetime", "iso")) {
            time.member(field, field, "field", null);
        }

        TypeDef node = new TypeDef();
        for (String field : List.of("node_num", "node_id", "long_name", "short_name", "last_heard",
                "battery", "externally_powered", "voltage", "snr", "latitude", "longitude", "altitude",
                "hops_away", "channel", "role", "hw_model", "public_key", "uptime_seconds",
                "channel_utilization", "air_util_tx", "temperature", "relative_humidity",
                "barometric_pressure", "unmessagable", "licensed")) {
            node.member(field, field, "field", null);
        }

        TypeDef telemetryEntry = new TypeDef();
        for (String field : List.of("timestamp", "node_id", "variant", "battery_level", "externally_powered",
                "voltage", "channel_utilization", "air_util_tx", "device_uptime_seconds",
                "temperature", "relative_humidity", "barometric_pressure", "gas_resistance",
                "environment_voltage", "environment_current", "iaq", "distance", "lux", "white_lux",
                "ir_lux", "uv_lux", "wind_direction", "wind_speed", "weight", "wind_gust", "wind_lull",
                "radiation", "rainfall_1h", "rainfall_24h", "soil_moisture", "soil_temperature",
                "one_wire_temperature", "pm25_standard", "co2", "ch1_voltage", "ch1_current",
                "num_packets_rx", "num_packets_tx", "local_uptime_seconds", "health_heart_bpm",
                "health_spo2", "health_temperature", "host_uptime_seconds", "host_freemem_bytes",
                "host_user_string", "traffic_packets_inspected", "rx_snr", "rx_rssi",
                "hop_start", "hop_limit", "hops")) {
            telemetryEntry.member(field, field, "field", null);
        }

        TypeDef channel = new TypeDef();
        channel.member("index", "index", "field", null);
        channel.member("role", "role", "field", null);
        channel.member("name", "name", "field", null);

        TypeDef string = new TypeDef();
        for (String member : List.of("byte(s, i)", "char(...)", "find(s, pattern)", "format(format, ...)",
                "gmatch(s, pattern)", "gsub(s, pattern, repl)", "len(s)", "lower(s)", "match(s, pattern)",
                "rep(s, n)", "reverse(s)", "sub(s, i, j)", "upper(s)")) {
            string.member(member, member.substring(0, member.indexOf('(') + 1), "function", null);
        }

        TypeDef table = new TypeDef();
        for (String member : List.of("concat(table, sep)", "insert(table, value)", "remove(table, pos)", "sort(table, comp)")) {
            table.member(member, member.substring(0, member.indexOf('(') + 1), "function", null);
        }

        TypeDef math = new TypeDef();
        for (String member : List.of("abs(x)", "acos(x)", "asin(x)", "atan(x)", "ceil(x)", "cos(x)", "deg(x)",
                "exp(x)", "floor(x)", "fmod(x, y)", "huge", "log(x)", "max(...)", "min(...)", "modf(x)",
                "pi", "pow(x, y)", "rad(x)", "random(m, n)", "randomseed(x)", "sin(x)", "sqrt(x)", "tan(x)")) {
            int paren = member.indexOf('(');
            math.member(member, paren >= 0 ? member.substring(0, paren + 1) : member,
                    paren >= 0 ? "function" : "field", null);
        }

        TypeDef coroutine = new TypeDef();
        for (String member : List.of("create(f)", "resume(co, ...)", "running()", "status(co)", "wrap(f)", "yield(...)")) {
            coroutine.member(member, member.substring(0, member.indexOf('(') + 1), "function", null);
        }

        TypeDef bit32 = new TypeDef();
        for (String member : List.of("arshift(x, disp)", "band(...)", "bnot(x)", "bor(...)", "btest(...)",
                "bxor(...)", "extract(n, field, width)", "lrotate(x, disp)", "lshift(x, disp)",
                "replace(n, v, field, width)", "rrotate(x, disp)", "rshift(x, disp)")) {
            bit32.member(member, member.substring(0, member.indexOf('(') + 1), "function", null);
        }

        Map<String, TypeDef> defs = new LinkedHashMap<>();
        defs.put("mesh", mesh);
        defs.put("mesh.chat", chat);
        defs.put("mesh.kv", kv);
        defs.put("mesh.json", json);
        defs.put("mesh.curl", curl);
        defs.put("mesh.ui", ui);
        defs.put("mesh.timer", timer);
        defs.put("mesh.canvas", canvas);
        defs.put("mesh.form", form);
        defs.put("mesh.traceroute", traceroute);
        defs.put("mesh.nodeinfo", nodeinfo);
        defs.put("mesh.admin", admin);
        defs.put("mesh.telemetry", telemetry);
        defs.put("curl.response", curlResponse);
        defs.put("command", command);
        defs.put("node.selection", nodeSelection);
        defs.put("traceroute.event", tracerouteEvent);
        defs.put("nodeinfo.event", nodeInfoEvent);
        defs.put("admin.event", adminEvent);
        defs.put("admin.snapshot", adminSnapshot);
        defs.put("timer.event", timerEvent);
        defs.put("canvas.event", canvasEvent);
        defs.put("canvas.mouse", canvasMouse);
        defs.put("canvas.keys", canvasKeys);
        defs.put("canvas.size", canvasSize);
        defs.put("route.discovery", routeDiscovery);
        defs.put("message", message);
        defs.put("owner", owner);
        defs.put("time", time);
        defs.put("node", node);
        defs.put("telemetry", telemetryEntry);
        defs.put("channel", channel);
        defs.put("string", string);
        defs.put("table", table);
        defs.put("math", math);
        defs.put("coroutine", coroutine);
        defs.put("bit32", bit32);
        this.types = Map.copyOf(defs);

        this.rootItems = List.of(
                new CompletionItem("function name(args)", "function name(args)\n    \nend", "snippet"),
                new CompletionItem("if condition then", "if condition then\n    \nend", "snippet"),
                new CompletionItem("for k, v in pairs(table) do", "for k, v in pairs(table) do\n    \nend", "snippet"),
                new CompletionItem("for i, v in ipairs(list) do", "for i, v in ipairs(list) do\n    \nend", "snippet"),
                new CompletionItem("while condition do", "while condition do\n    \nend", "snippet"),
                new CompletionItem("repeat until condition", "repeat\n    \nuntil condition", "snippet"),
                new CompletionItem("on_message(msg)", "function on_message(msg)\n    \nend", "snippet"),
                new CompletionItem("on_command(command)", "function on_command(command)\n    \nend", "snippet"),
                new CompletionItem("on_node_selected(event)", "function on_node_selected(event)\n    \nend", "snippet"),
                new CompletionItem("on_traceroute(event)", "function on_traceroute(event)\n    \nend", "snippet"),
                new CompletionItem("on_node_info(event)", "function on_node_info(event)\n    \nend", "snippet"),
                new CompletionItem("on_admin(event)", "function on_admin(event)\n    \nend", "snippet"),
                new CompletionItem("on_timer(event)", "function on_timer(event)\n    \nend", "snippet"),
                new CompletionItem("on_canvas_event(event)", "function on_canvas_event(event)\n    \nend", "snippet"),
                new CompletionItem("on_canvas_frame(event)", "function on_canvas_frame(event)\n    \nend", "snippet"),
                new CompletionItem("on_form_event(event)", "function on_form_event(event)\n    \nend", "snippet"),
                new CompletionItem("mesh", "mesh", "object"),
                new CompletionItem("string", "string", "object"),
                new CompletionItem("table", "table", "object"),
                new CompletionItem("math", "math", "object"),
                new CompletionItem("coroutine", "coroutine", "object"),
                new CompletionItem("bit32", "bit32", "object"),
                new CompletionItem("assert(value)", "assert(", "function"),
                new CompletionItem("error(message)", "error(", "function"),
                new CompletionItem("getmetatable(table)", "getmetatable(", "function"),
                new CompletionItem("next(table, index)", "next(", "function"),
                new CompletionItem("print(...)", "print(", "function"),
                new CompletionItem("pairs(table)", "pairs(", "function"),
                new CompletionItem("ipairs(list)", "ipairs(", "function"),
                new CompletionItem("rawequal(a, b)", "rawequal(", "function"),
                new CompletionItem("rawget(table, index)", "rawget(", "function"),
                new CompletionItem("rawlen(value)", "rawlen(", "function"),
                new CompletionItem("rawset(table, index, value)", "rawset(", "function"),
                new CompletionItem("select(index, ...)", "select(", "function"),
                new CompletionItem("setmetatable(table, metatable)", "setmetatable(", "function"),
                new CompletionItem("tostring(value)", "tostring(", "function"),
                new CompletionItem("tonumber(value)", "tonumber(", "function"),
                new CompletionItem("type(value)", "type(", "function"),
                new CompletionItem("pcall(fn)", "pcall(", "function"),
                new CompletionItem("xpcall(fn, err)", "xpcall(", "function"),
                new CompletionItem("local", "local ", "keyword"),
                new CompletionItem("return", "return ", "keyword"),
                new CompletionItem("break", "break", "keyword"),
                new CompletionItem("nil", "nil", "literal"),
                new CompletionItem("true", "true", "literal"),
                new CompletionItem("false", "false", "literal")
        );
    }

    public CompletionResult complete(String code, int caret, boolean forced) {
        String safeCode = code != null ? code : "";
        int safeCaret = Math.max(0, Math.min(caret, safeCode.length()));
        Prefix prefix = prefixAt(safeCode, safeCaret);
        if (!forced && prefix.text().length() < 2 && !prefix.text().contains(".") && !prefix.text().contains(":")) {
            return CompletionResult.empty(safeCaret);
        }

        Analysis analysis = analyze(safeCode, safeCaret);
        List<CompletionItem> candidates;
        int replaceStart;
        int separator = Math.max(prefix.text().lastIndexOf('.'), prefix.text().lastIndexOf(':'));
        String memberPrefix;
        if (separator >= 0) {
            String objectExpression = prefix.text().substring(0, separator);
            memberPrefix = prefix.text().substring(separator + 1);
            replaceStart = prefix.start() + separator + 1;
            String type = resolveExpressionType(objectExpression, analysis).orElse(null);
            candidates = type != null ? membersForType(type, analysis) : List.of();
        } else {
            memberPrefix = prefix.text();
            replaceStart = prefix.start();
            candidates = rootCandidates(analysis);
        }

        String query = memberPrefix.toLowerCase(Locale.ROOT);
        List<CompletionItem> items = candidates.stream()
                .filter(item -> item.lookupText().toLowerCase(Locale.ROOT).startsWith(query)
                        || item.displayText().toLowerCase(Locale.ROOT).startsWith(query))
                .sorted(Comparator.comparingInt(CompletionItem::rank).thenComparing(CompletionItem::displayText))
                .limit(MAX_COMPLETIONS)
                .toList();
        if (items.isEmpty()) {
            return CompletionResult.empty(safeCaret);
        }
        return new CompletionResult(replaceStart, safeCaret, items);
    }

    private List<CompletionItem> rootCandidates(Analysis analysis) {
        List<CompletionItem> items = new ArrayList<>(rootItems);
        analysis.symbols().forEach((name, type) -> items.add(new CompletionItem(name, name, "variable")));
        analysis.functions().forEach(name -> items.add(new CompletionItem(name + "(...)", name + "(", "function")));
        return deduplicate(items);
    }

    private List<CompletionItem> membersForType(String type, Analysis analysis) {
        String normalized = elementType(type);
        if (normalized.startsWith("table:")) {
            return analysis.tableFields().getOrDefault(normalized.substring("table:".length()), List.of());
        }
        TypeDef def = types.get(normalized);
        return def != null ? def.members() : List.of();
    }

    private List<CompletionItem> deduplicate(List<CompletionItem> items) {
        Map<String, CompletionItem> result = new LinkedHashMap<>();
        for (CompletionItem item : items) {
            result.putIfAbsent(item.insertText(), item);
        }
        return List.copyOf(result.values());
    }

    private Analysis analyze(String code, int caret) {
        String beforeCaret = stripCommentsAndStrings(code.substring(0, Math.max(0, Math.min(caret, code.length()))));
        Map<String, String> symbols = new LinkedHashMap<>();
        Set<String> functions = new LinkedHashSet<>();
        Map<String, List<CompletionItem>> tableFields = new LinkedHashMap<>();

        symbols.put("mesh", "mesh");
        Matcher onMessageMatcher = ON_MESSAGE_PATTERN.matcher(beforeCaret);
        if (onMessageMatcher.find()) {
            symbols.put(onMessageMatcher.group(1), "message");
        }

        Matcher functionMatcher = FUNCTION_PATTERN.matcher(beforeCaret);
        while (functionMatcher.find()) {
            String name = functionMatcher.group(1);
            registerFunction(name, symbols, functions, tableFields);
            for (String param : splitNames(functionMatcher.group(2))) {
                symbols.putIfAbsent(param, "unknown");
            }
            if ("on_message".equals(name)) {
                List<String> params = splitNames(functionMatcher.group(2));
                if (!params.isEmpty()) {
                    symbols.put(params.getFirst(), "message");
                }
            } else if ("on_command".equals(name)) {
                List<String> params = splitNames(functionMatcher.group(2));
                if (!params.isEmpty()) {
                    symbols.put(params.getFirst(), "command");
                }
            } else if ("on_node_selected".equals(name)) {
                List<String> params = splitNames(functionMatcher.group(2));
                if (!params.isEmpty()) {
                    symbols.put(params.getFirst(), "node.selection");
                }
            } else if ("on_traceroute".equals(name)) {
                List<String> params = splitNames(functionMatcher.group(2));
                if (!params.isEmpty()) {
                    symbols.put(params.getFirst(), "traceroute.event");
                }
            } else if ("on_node_info".equals(name)) {
                List<String> params = splitNames(functionMatcher.group(2));
                if (!params.isEmpty()) {
                    symbols.put(params.getFirst(), "nodeinfo.event");
                }
            } else if ("on_admin".equals(name)) {
                List<String> params = splitNames(functionMatcher.group(2));
                if (!params.isEmpty()) {
                    symbols.put(params.getFirst(), "admin.event");
                }
            } else if ("on_timer".equals(name)) {
                List<String> params = splitNames(functionMatcher.group(2));
                if (!params.isEmpty()) {
                    symbols.put(params.getFirst(), "timer.event");
                }
            } else if ("on_canvas_event".equals(name) || "on_canvas_frame".equals(name)) {
                List<String> params = splitNames(functionMatcher.group(2));
                if (!params.isEmpty()) {
                    symbols.put(params.getFirst(), "canvas.event");
                }
            }
        }

        Matcher functionAssignMatcher = FUNCTION_ASSIGN_PATTERN.matcher(beforeCaret);
        while (functionAssignMatcher.find()) {
            registerFunction(functionAssignMatcher.group(1), symbols, functions, tableFields);
        }

        Matcher tableMatcher = TABLE_ASSIGN_PATTERN.matcher(beforeCaret);
        while (tableMatcher.find()) {
            String tableName = tableMatcher.group(1);
            List<CompletionItem> fields = tableFields(tableMatcher.group(2));
            for (CompletionItem field : fields) {
                addTableField(tableName, field, symbols, tableFields);
            }
        }

        Matcher localAssignMatcher = LOCAL_ASSIGN_PATTERN.matcher(beforeCaret);
        while (localAssignMatcher.find()) {
            List<String> names = splitNames(localAssignMatcher.group(1));
            List<String> values = splitExpressions(localAssignMatcher.group(2));
            for (int i = 0; i < names.size(); i++) {
                String value = i < values.size() ? values.get(i) : "";
                putInferredSymbol(symbols, names.get(i), inferExpressionType(value, symbols).orElse("unknown"));
            }
        }

        Matcher assignMatcher = ASSIGN_PATTERN.matcher(beforeCaret);
        while (assignMatcher.find()) {
            String name = assignMatcher.group(1);
            if ("local".equals(name) || "function".equals(name)) {
                continue;
            }
            putInferredSymbol(symbols, name,
                    inferExpressionType(assignMatcher.group(2), symbols).orElse(symbols.getOrDefault(name, "unknown")));
        }

        Matcher tableMemberMatcher = TABLE_MEMBER_ASSIGN_PATTERN.matcher(beforeCaret);
        while (tableMemberMatcher.find()) {
            String tableName = tableMemberMatcher.group(1);
            String member = tableMemberMatcher.group(2);
            boolean function = tableMemberMatcher.group(3).trim().startsWith("function");
            addTableField(tableName, new CompletionItem(function ? member + "(...)" : member,
                    function ? member + "(" : member,
                    function ? "function" : "field"), symbols, tableFields);
        }

        Matcher ipairsMatcher = IPAIRS_FOR_PATTERN.matcher(beforeCaret);
        while (ipairsMatcher.find()) {
            String variable = ipairsMatcher.group(1);
            String expression = ipairsMatcher.group(2);
            inferExpressionType(expression, symbols)
                    .map(this::elementType)
                    .ifPresent(type -> symbols.put(variable, type));
        }

        return new Analysis(Map.copyOf(symbols), Set.copyOf(functions), Map.copyOf(tableFields));
    }

    private void putInferredSymbol(Map<String, String> symbols, String name, String inferredType) {
        String currentType = symbols.get(name);
        if (currentType != null && currentType.startsWith("table:") && "table".equals(inferredType)) {
            return;
        }
        symbols.put(name, inferredType);
    }

    private void registerFunction(String name,
                                  Map<String, String> symbols,
                                  Set<String> functions,
                                  Map<String, List<CompletionItem>> tableFields) {
        int memberSeparator = Math.max(name.lastIndexOf('.'), name.lastIndexOf(':'));
        if (memberSeparator < 0) {
            functions.add(name);
            return;
        }
        String object = name.substring(0, memberSeparator);
        String member = name.substring(memberSeparator + 1);
        addTableField(object, new CompletionItem(member + "(...)", member + "(", "function"), symbols, tableFields);
    }

    private void addTableField(String tableName,
                               CompletionItem item,
                               Map<String, String> symbols,
                               Map<String, List<CompletionItem>> tableFields) {
        if (!tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return;
        }
        List<CompletionItem> fields = tableFields.computeIfAbsent(tableName, ignored -> new ArrayList<>());
        if (fields.stream().noneMatch(existing -> existing.insertText().equals(item.insertText()))) {
            fields.add(item);
        }
        symbols.putIfAbsent(tableName, "table:" + tableName);
    }

    private List<CompletionItem> tableFields(String tableBody) {
        List<CompletionItem> result = new ArrayList<>();
        Matcher matcher = TABLE_FIELD_PATTERN.matcher(tableBody);
        while (matcher.find()) {
            String name = matcher.group(1);
            boolean function = matcher.group(2).trim().startsWith("function");
            result.add(new CompletionItem(function ? name + "(...)" : name, function ? name + "(" : name,
                    function ? "function" : "field"));
        }
        return deduplicate(result);
    }

    private Optional<String> resolveExpressionType(String expression, Analysis analysis) {
        String trimmed = expression != null ? expression.trim() : "";
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        if (types.containsKey(trimmed)) {
            return Optional.of(trimmed);
        }
        Optional<String> direct = inferExpressionType(trimmed, analysis.symbols());
        if (direct.isPresent() && !"unknown".equals(direct.get())) {
            return direct;
        }
        String[] parts = trimmed.split("[.:]");
        if (parts.length == 0) {
            return Optional.empty();
        }
        String type = analysis.symbols().get(parts[0]);
        if (type == null && types.containsKey(parts[0])) {
            type = parts[0];
        }
        for (int i = 1; i < parts.length && type != null; i++) {
            TypeDef def = types.get(elementType(type));
            if (def == null) {
                return Optional.empty();
            }
            type = def.returnType(parts[i]).orElse(null);
        }
        return Optional.ofNullable(type);
    }

    private Optional<String> inferExpressionType(String expression, Map<String, String> symbols) {
        String value = expression != null ? expression.trim() : "";
        value = value.replaceAll("--.*$", "").trim();
        if (value.startsWith("{")) {
            return Optional.of("table");
        }
        if (value.startsWith("\"") || value.startsWith("'")) {
            return Optional.of("string");
        }
        if (value.matches("-?\\d+(?:\\.\\d+)?")) {
            return Optional.of("number");
        }
        if ("true".equals(value) || "false".equals(value)) {
            return Optional.of("boolean");
        }
        if (value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return Optional.ofNullable(symbols.getOrDefault(value, value));
        }
        if (value.startsWith("mesh.chat.recent")) {
            return Optional.of("list:message");
        }
        if (value.startsWith("mesh.chat.nodes")) {
            return Optional.of("list:node");
        }
        if (value.startsWith("mesh.chat.channels")) {
            return Optional.of("list:channel");
        }
        if (value.startsWith("mesh.telemetry.recent")
                || value.startsWith("mesh.telemetry.for_node")
                || value.startsWith("mesh.telemetry.query")) {
            return Optional.of("list:telemetry");
        }
        if (value.startsWith("mesh.telemetry.latest")) {
            return Optional.of("telemetry");
        }
        if (value.startsWith("mesh.chat.send_channel")
                || value.startsWith("mesh.chat.send_dm")
                || value.startsWith("mesh.chat.bot_message")
                || value.startsWith("mesh.chat.bot_reply")) {
            return Optional.of("message");
        }
        if (value.startsWith("mesh.owner")) {
            return Optional.of("owner");
        }
        if (value.startsWith("mesh.localtime")) {
            return Optional.of("time");
        }
        if (value.startsWith("mesh.command")) {
            return Optional.of("command");
        }
        if (value.startsWith("mesh.kv.list")) {
            return Optional.of("table");
        }
        if (value.startsWith("mesh.curl.get") || value.startsWith("mesh.curl.request")) {
            return Optional.of("curl.response");
        }
        if (value.startsWith("mesh.canvas.size")) {
            return Optional.of("canvas.size");
        }
        if (value.startsWith("mesh.canvas.mouse")) {
            return Optional.of("canvas.mouse");
        }
        if (value.startsWith("mesh.canvas.keys")) {
            return Optional.of("canvas.keys");
        }
        if (value.startsWith("mesh.chat")) {
            return Optional.of("mesh.chat");
        }
        if (value.startsWith("mesh.kv")) {
            return Optional.of("mesh.kv");
        }
        if (value.startsWith("mesh.json")) {
            return Optional.of("mesh.json");
        }
        if (value.startsWith("mesh.curl")) {
            return Optional.of("mesh.curl");
        }
        if (value.startsWith("mesh.ui")) {
            return Optional.of("mesh.ui");
        }
        if (value.startsWith("mesh.timer")) {
            return Optional.of("mesh.timer");
        }
        if (value.startsWith("mesh.canvas")) {
            return Optional.of("mesh.canvas");
        }
        if (value.startsWith("mesh.form")) {
            return Optional.of("mesh.form");
        }
        if (value.startsWith("mesh.traceroute")) {
            return Optional.of("mesh.traceroute");
        }
        if (value.startsWith("mesh.nodeinfo")) {
            return Optional.of("mesh.nodeinfo");
        }
        if (value.startsWith("mesh.admin")) {
            return Optional.of("mesh.admin");
        }
        if (value.startsWith("mesh")) {
            return Optional.of("mesh");
        }
        return Optional.empty();
    }

    private String elementType(String type) {
        return type != null && type.startsWith("list:") ? type.substring("list:".length()) : type;
    }

    private Prefix prefixAt(String code, int caret) {
        int start = caret;
        while (start > 0) {
            char ch = code.charAt(start - 1);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '.' || ch == ':') {
                start--;
            } else {
                break;
            }
        }
        return new Prefix(start, code.substring(start, caret));
    }

    private List<String> splitNames(String names) {
        if (names == null || names.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : names.split(",")) {
            String name = part.trim();
            if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                result.add(name);
            }
        }
        return result;
    }

    private List<String> splitExpressions(String values) {
        if (values == null || values.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < values.length(); i++) {
            char ch = values.charAt(i);
            if (ch == '(' || ch == '{' || ch == '[') {
                depth++;
            } else if (ch == ')' || ch == '}' || ch == ']') {
                depth = Math.max(0, depth - 1);
            } else if (ch == ',' && depth == 0) {
                result.add(values.substring(start, i).trim());
                start = i + 1;
            }
        }
        result.add(values.substring(start).trim());
        return result;
    }

    private String stripCommentsAndStrings(String code) {
        String withoutBlockComments = code.replaceAll("--\\[\\[[\\s\\S]*?]]", " ");
        String withoutLineComments = withoutBlockComments.replaceAll("--[^\\n]*", " ");
        return withoutLineComments
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"")
                .replaceAll("'(?:\\\\.|[^'\\\\])*'", "''");
    }

    public record CompletionResult(int replaceStart, int replaceEnd, List<CompletionItem> items) {
        static CompletionResult empty(int caret) {
            return new CompletionResult(caret, caret, List.of());
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    public record CompletionItem(String displayText, String insertText, String kind) {
        String lookupText() {
            int paren = displayText.indexOf('(');
            return paren > 0 ? displayText.substring(0, paren) : displayText;
        }

        int rank() {
            return switch (kind) {
                case "variable", "field" -> 0;
                case "keyword", "snippet" -> 1;
                case "function" -> 2;
                case "object" -> 3;
                case "literal" -> 4;
                default -> 4;
            };
        }
    }

    private record Prefix(int start, String text) {}

    private record Analysis(Map<String, String> symbols,
                            Set<String> functions,
                            Map<String, List<CompletionItem>> tableFields) {}

    private static final class TypeDef {
        private final List<CompletionItem> members = new ArrayList<>();
        private final Map<String, String> returnTypes = new LinkedHashMap<>();

        void member(String display, String insert, String kind, String returnType) {
            members.add(new CompletionItem(display, insert, kind));
            returnTypes.put(memberName(display), returnType);
        }

        List<CompletionItem> members() {
            return List.copyOf(members);
        }

        Optional<String> returnType(String memberName) {
            return Optional.ofNullable(returnTypes.get(memberName));
        }

        private String memberName(String display) {
            int paren = display.indexOf('(');
            return paren >= 0 ? display.substring(0, paren) : display;
        }
    }
}
