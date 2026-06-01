# MeshApp Script Lua API

**Language:** [Русский](lua-api.ru.md) | English

MeshApp runs user Lua scripts in a LuaJ sandbox. Scripts can use the `mesh` namespace, core Lua functions, the `string`, `table`, `math`, `coroutine`, `bit32` libraries and functions such as `pairs`, `ipairs`, `pcall`, `tonumber` and `tostring`. Unsafe global APIs are disabled: `io`, `os`, `debug`, `package`, `require`, `dofile`, `loadfile`, `luajava`, `collectgarbage`, `module`.

A regular script runs once and exits unless it declares `on_message(msg)` or has pending asynchronous operations. If `on_message(msg)` is declared, the script stays active and receives new messages. Command bots use `on_command(command)`, and UI/request results arrive in separate callback functions.

Execution limits:

- initial script run: up to 3 seconds
- callback: up to 1.5 seconds
- `print` / `mesh.log` output: up to 64 KB per run
- `mesh.sleep(seconds)` accepts a delay from `0` to `10` seconds and extends the current execution deadline

## Lua Quick Reference

Lua is a small dynamic language. In MeshApp, code usually consists of callback functions such as `on_message(msg)` and `mesh.*` API calls.

### Comments

```lua
-- Single-line comment

--[[
Multiline comment.
Useful for temporarily disabling a code block.
]]
```

### Variables and Types

Variables do not require type declarations. Use `local` so a variable does not become global and does not live between callback calls longer than needed.

```lua
local text = "hello"
local count = 3
local enabled = true
local missing = nil

mesh.log(type(text))   -- string
mesh.log(type(count))  -- number
```

Main types: `nil`, `boolean`, `number`, `string`, `table`, `function`. `nil` means that the value is absent. In conditions, only `false` and `nil` are false; number `0` and an empty string `""` are true.

```lua
if "" then
    mesh.log("An empty string is true in Lua")
end
```

### Strings

Strings can use single or double quotes. Strings are concatenated with the `..` operator.

```lua
local name = "Alpha"
local message = 'node: ' .. name

mesh.log(string.lower(message))
mesh.log(string.format("battery %d%%", 87))
```

### Conditions

```lua
if msg.outgoing then
    return
elseif msg.text == "ping" then
    mesh.chat.bot_reply(msg, "pong")
else
    mesh.log("another message")
end
```

Useful operators: `==`, `~=`, `<`, `<=`, `>`, `>=`, `and`, `or`, `not`.

### Tables

A table is Lua's main data structure. It works as both an array and a dictionary. Array indexes start at `1`.

```lua
local route = { "node-a", "node-b", "node-c" }
mesh.log(route[1])       -- node-a
mesh.log(#route)         -- 3

local node = {
    node_id = "!abcdef12",
    long_name = "Alpha"
}
mesh.log(node.node_id)
mesh.log(node["long_name"])
```

### Loops

Use `ipairs` for arrays and `pairs` for dictionaries.

```lua
for i, node in ipairs(mesh.chat.nodes()) do
    mesh.log(i .. ": " .. tostring(node.node_id))
end

local values = mesh.kv.list()
for key, value in pairs(values) do
    mesh.log(key .. " = " .. tostring(value))
end

for i = 1, 3 do
    mesh.log("step " .. i)
end
```

### Functions

Functions are declared with `function ... end`. `return` exits the function and returns a value.

```lua
local function normalize(text)
    if not text then
        return ""
    end
    return string.lower(text)
end

function on_message(msg)
    local text = normalize(msg.text)
    if text == "ping" then
        mesh.chat.bot_reply(msg, "pong")
    end
end
```

### Checking for `nil`

Event fields may be absent. Check values for `nil` before accessing nested fields.

```lua
function on_traceroute(event)
    if event.route and event.route.route_ids then
        mesh.log(table.concat(event.route.route_ids, " -> "))
    else
        mesh.log("route was not received")
    end
end
```

### Error Handling

`pcall` runs a function in protected mode: an error does not stop the whole script and is returned as the second value.

```lua
local ok, result = pcall(function()
    return mesh.curl.get("https://example.com/api/status")
end)

if not ok then
    mesh.log("error: " .. tostring(result))
elseif result.ok then
    mesh.log(result.body)
end
```

### Things to Remember

- Blocks always need a closing `end`.
- Semicolons are not needed.
- Arrays start at index `1`, not `0`.
- Inequality is written as `~=`, not `!=`.
- String concatenation uses `..`, not `+`.
- `require`, the file system and system APIs are disabled by the MeshApp sandbox.

## Base Functions

| Function | Purpose |
|----------|---------|
| `print(...)` | Writes a line to script output; arguments are joined with tabs |
| `mesh.log(text)` | Writes `text` to script output |
| `mesh.now()` | Returns Unix time in seconds |
| `mesh.sleep(seconds)` | Blocking pause from `0` to `10` seconds |
| `mesh.owner()` | Returns `{ node_id, node_num, connection_id }` for the current node |
| `mesh.command()` | Returns the current command or an empty table outside command launch |

## Callbacks

| Callback | When it is called |
|----------|-------------------|
| `on_message(msg)` | For every new incoming or outgoing message while the script is running |
| `on_command(command)` | When an automation bot is started from chat |
| `on_node_selected(event)` | After selecting or cancelling node selection through `mesh.ui.pick_node(...)` |
| `on_traceroute(event)` | After `mesh.traceroute.request(...)` produces a result |
| `on_node_info(event)` | After `mesh.nodeinfo.request(...)` produces a result |
| `on_canvas_event(event)` | After an event in a floating Canvas window: mouse, keyboard, resize, open/close |
| `on_canvas_frame(event)` | On the Canvas window timer, if `fps` is set or `mesh.canvas.set_fps(...)` was called |

## `mesh.chat`

`chat_type` accepts `channel` or `dm`. For a channel, `chat_key` is a string channel index, for example `"0"`. For a direct message, `chat_key` is the peer node ID, for example `"!abcdef12"`.

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.chat.send_channel(channel, text[, reply_id])` | `message` or `nil` | Sends a radio message to a channel |
| `mesh.chat.send_dm(node_id, text[, reply_id])` | `message` or `nil` | Sends a radio direct message |
| `mesh.chat.reply(msg, text)` | `message` or `nil` | Sends a reply to the same chat where `msg` arrived |
| `mesh.chat.bot_message(chat_type, chat_key, text)` | `message` | Adds a local bot message to history without sending it over radio |
| `mesh.chat.bot_reply(msg, text)` | `message` | Adds a local bot reply to a message |
| `mesh.chat.bot_notice(chat_type, chat_key, text[, options])` | `true` | Shows a temporary bot UI message without writing it to history |
| `mesh.chat.recent(chat_type, chat_key[, limit])` | list of `message` | Returns recent messages, `limit` from 1 to 200, default 20 |
| `mesh.chat.nodes()` | list of `node` | Returns known nodes for the current connection |
| `mesh.chat.channels()` | list of `channel` | Returns known channels for the current connection |

## `mesh.kv`

KV storage is isolated per script and persisted in the local application database.

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.kv.get(key)` | string or `nil` | Reads a value |
| `mesh.kv.set(key, value)` | `true` | Stores a value as a string; `nil` stores an empty value |
| `mesh.kv.delete(key)` | boolean | Deletes a key |
| `mesh.kv.list()` | table | Returns all script keys |
| `mesh.kv.clear()` | `true` | Clears the script KV storage |

## `mesh.curl`

HTTP(S) requests are executed by the built-in Java HTTP client. Access to local, private, link-local and multicast addresses is blocked. URLs with credentials are also blocked.

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.curl.get(url[, options])` | `curl.response` | Performs a GET request |
| `mesh.curl.request(options)` | `curl.response` | Performs a request with parameters |

`options` fields: `url`, `method`, `body`, `headers`, `timeout_ms`, `max_bytes`. Allowed methods: `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`. `timeout_ms` is limited to 100..5000, `max_bytes` is limited to 1 MB.

Response fields: `ok`, `status`, `url`, `body`, `headers`, `truncated`, `error`.

## `mesh.ui`

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.ui.pick_node(options)` | `request_id` | Opens node selection and later calls `on_node_selected(event)` |

`options` fields: `name`, `prompt`, `query`, `chat_type`, `chat_key`. A string can be passed instead of a table; it will be used as `query`.

`on_node_selected(event)` fields: `type`, `source`, `name`, `request_id`, `status`, `selected`, `cancelled`, `chat_type`, `chat_key`, `node`.

## `mesh.canvas`

`mesh.canvas` opens a floating resizable borderless window next to the main application window. The window is not modal, is not added to the side menu and exists only while the Lua script owns it.

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.canvas.open(options)` | `true` | Shows the Canvas window |
| `mesh.canvas.close()` | `true` | Closes the Canvas window |
| `mesh.canvas.set_fps(fps)` | `true` | Enables/changes the `on_canvas_frame(event)` frequency; `0` disables it |
| `mesh.canvas.size()` | `{width, height}` | Returns the current canvas size |
| `mesh.canvas.mouse()` | `canvas.mouse` | Returns the current mouse state |
| `mesh.canvas.keys()` | `canvas.keys` | Returns the current keyboard state |
| `mesh.canvas.clear([color])` | `true` | Clears the canvas or fills it with a color |
| `mesh.canvas.set_fill(color)` | `true` | Sets fill color |
| `mesh.canvas.set_stroke(color)` | `true` | Sets stroke color |
| `mesh.canvas.set_line_width(width)` | `true` | Sets line width |
| `mesh.canvas.set_font(size[, family[, weight]])` | `true` | Sets text font |
| `mesh.canvas.save()` / `mesh.canvas.restore()` | `true` | Saves and restores drawing state |
| `mesh.canvas.translate(x, y)` | `true` | Translates the coordinate system |
| `mesh.canvas.rotate(degrees)` | `true` | Rotates the coordinate system |
| `mesh.canvas.scale(x[, y])` | `true` | Scales the coordinate system |
| `mesh.canvas.fill_rect(x, y, w, h[, color])` | `true` | Draws a filled rectangle |
| `mesh.canvas.stroke_rect(x, y, w, h[, color[, line_width]])` | `true` | Draws a rectangle outline |
| `mesh.canvas.fill_round_rect(x, y, w, h, radius[, color])` | `true` | Draws a filled rounded rectangle |
| `mesh.canvas.stroke_round_rect(x, y, w, h, radius[, color[, line_width]])` | `true` | Draws a rounded rectangle outline |
| `mesh.canvas.line(x1, y1, x2, y2[, color[, line_width]])` | `true` | Draws a line |
| `mesh.canvas.fill_circle(x, y, radius[, color])` | `true` | Draws a filled circle |
| `mesh.canvas.stroke_circle(x, y, radius[, color[, line_width]])` | `true` | Draws a circle outline |
| `mesh.canvas.fill_ellipse(x, y, w, h[, color])` | `true` | Draws a filled ellipse |
| `mesh.canvas.stroke_ellipse(x, y, w, h[, color[, line_width]])` | `true` | Draws an ellipse outline |
| `mesh.canvas.fill_polygon(points[, color])` | `true` | Draws a filled polygon |
| `mesh.canvas.stroke_polygon(points[, color[, line_width]])` | `true` | Draws a polygon outline |
| `mesh.canvas.polyline(points[, color[, line_width]])` | `true` | Draws a polyline |
| `mesh.canvas.fill_text(text, x, y[, color])` | `true` | Draws text |
| `mesh.canvas.stroke_text(text, x, y[, color[, line_width]])` | `true` | Draws text outline |

`options` fields: `title`, `width`, `height`, `background`, `resizable`, `fps`. By default, Canvas scales with the floating window (`resizable = true`); resize it by dragging the window edges. The button in the top-right corner closes the window after confirmation. Double-clicking the top drag zone minimizes the window to a translucent square with the script icon; double-clicking the square restores the previous size.

Color can be passed as a JavaFX/CSS string (`"#ffcc00"`, `"rgba(255,0,0,0.5)"`, `"white"`) or as a table `{r, g, b, a}`. `r/g/b/a` components are accepted in the `0..1` or `0..255` range.

`points` can be a flat list `{x1, y1, x2, y2, ...}` or a list of points `{{x=10, y=10}, {x=40, y=20}}`.

`on_canvas_event(event)` fields: `type`, `source`, `x`, `y`, `screen_x`, `screen_y`, `button`, `click_count`, `primary`, `middle`, `secondary`, `wheel_delta_x`, `wheel_delta_y`, `code`, `key`, `text`, `shift`, `ctrl`, `alt`, `meta`, `width`, `height`, `time`, `dt`.

`event.type` values: `opened`, `closed`, `resized`, `mouse_moved`, `mouse_pressed`, `mouse_released`, `mouse_clicked`, `mouse_dragged`, `mouse_entered`, `mouse_exited`, `scroll`, `key_pressed`, `key_released`, `key_typed`.

## `mesh.traceroute`

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.traceroute.request(target[, options])` | `request_id` | Starts traceroute to a node and later calls `on_traceroute(event)` |

`target` can be a node ID string (`"!abcdef12"`), numeric `node_num` or a node table with `node_num`, `node_id`, `long_name`, `short_name`.

`options` fields: `name`, `chat_type`, `chat_key`, `target_name`, `timeout_seconds`. Timeout is limited to 1..600 seconds, default 360.

`on_traceroute(event)` fields: `type`, `source`, `name`, `request_id`, `status`, `ok`, `timeout`, `error`, `target_node_num`, `target_node_id`, `target_name`, `response_from_node_num`, `response_from_node_id`, `chat_type`, `chat_key`, `route`.

If `event.route` exists, it exposes `route`, `route_back`, `route_ids`, `route_back_ids`, `snr_towards`, `snr_back`.

## `mesh.nodeinfo`

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.nodeinfo.request(target[, options])` | `request_id` | Requests NodeInfo and later calls `on_node_info(event)` |

`target` and `options` are the same as for `mesh.traceroute.request(...)`, but default timeout is 60 seconds.

`on_node_info(event)` fields: `type`, `source`, `name`, `request_id`, `status`, `ok`, `timeout`, `cached`, `error`, `target_node_num`, `target_node_id`, `target_name`, `chat_type`, `chat_key`, `node`.

## Object Fields

`message`: `db_id`, `packet_id`, `chat_type`, `chat_key`, `from`, `to`, `channel`, `channel_name`, `channel_role`, `text`, `reply_id`, `reply_text`, `timestamp`, `outgoing`, `system`, `status`, `sender_name`, `hop_start`, `hop_limit`, `hops`, `rx_rssi`, `rx_snr`.

`node`: `node_num`, `node_id`, `long_name`, `short_name`, `last_heard`, `battery`, `externally_powered`, `voltage`, `snr`, `latitude`, `longitude`, `altitude`, `hops_away`, `channel`, `role`, `hw_model`, `public_key`, `uptime_seconds`, `channel_utilization`, `air_util_tx`, `temperature`, `relative_humidity`, `barometric_pressure`, `unmessagable`, `licensed`.

`channel`: `index`, `role`, `name`.

`command`: `type`, `source`, `name`, `request_id`, `chat_type`, `chat_key`, `handle`, `text`, `arguments`, `argument_tokens`.

`canvas.mouse`: `x`, `y`, `screen_x`, `screen_y`, `over`, `pressed`, `primary`, `middle`, `secondary`, `button`, `click_count`, `wheel_delta_x`, `wheel_delta_y`, `last_type`, `time`.

`canvas.keys`: `pressed`, `last_type`, `last_code`, `last_key`, `text`, `shift`, `ctrl`, `alt`, `meta`, `time`. For quick polling, keys are also available as boolean fields by code name, for example `mesh.canvas.keys().Left`.

## Examples

### Local ping/test bot with delay

```lua
math.randomseed(math.floor(mesh.now() * 1000))

function on_message(msg)
    if msg.outgoing or msg.system or not msg.text then
        return
    end

    local text = string.lower(msg.text)
    if text == "ping" or text == "test" then
        mesh.sleep(math.random(1, 5))
        mesh.chat.bot_reply(msg, "pong")
    end
end
```

### Reply to the mesh network

```lua
function on_message(msg)
    if msg.outgoing or not msg.text then
        return
    end

    if string.lower(msg.text) == "hello" then
        mesh.chat.reply(msg, "hello from " .. tostring(mesh.owner().node_id))
    end
end
```

### Automation bot with node selection and traceroute

```lua
function on_command(command)
    mesh.ui.pick_node({
        name = "trace_target",
        prompt = "Select a node for traceroute",
        query = command.arguments,
        chat_type = command.chat_type,
        chat_key = command.chat_key
    })
end

function on_node_selected(event)
    if event.cancelled then
        mesh.chat.bot_message(event.chat_type, event.chat_key, "Traceroute cancelled")
        return
    end

    mesh.chat.bot_notice(event.chat_type, event.chat_key, "Starting traceroute...", {
        name = "trace_progress"
    })

    mesh.traceroute.request(event.node, {
        name = "trace_request",
        chat_type = event.chat_type,
        chat_key = event.chat_key,
        timeout_seconds = 120
    })
end

function on_traceroute(event)
    if not event.ok then
        mesh.chat.bot_message(event.chat_type, event.chat_key,
            "Traceroute failed: " .. tostring(event.status))
        return
    end

    local hops = event.target_node_id
    if event.route and event.route.route_ids then
        local route = table.concat(event.route.route_ids, " -> ")
        if route ~= "" then
            hops = route
        end
    end

    mesh.chat.bot_message(event.chat_type, event.chat_key, "Route: " .. hops)
end
```

### Request NodeInfo by the first command argument

```lua
function on_command(command)
    local target = command.argument_tokens[1]
    if not target then
        mesh.chat.bot_message(command.chat_type, command.chat_key, "Specify node ID")
        return
    end

    mesh.nodeinfo.request(target, {
        name = "nodeinfo_request",
        chat_type = command.chat_type,
        chat_key = command.chat_key,
        timeout_seconds = 60
    })
end

function on_node_info(event)
    if not event.ok or not event.node then
        mesh.chat.bot_message(event.chat_type, event.chat_key,
            "NodeInfo is unavailable: " .. tostring(event.status))
        return
    end

    local node = event.node
    mesh.chat.bot_message(event.chat_type, event.chat_key,
        string.format("%s (%s), SNR %.1f, battery %d%%",
            node.long_name or node.node_id,
            node.short_name or "",
            node.snr or 0,
            node.battery or 0))
end
```

### KV storage and external HTTP request

```lua
local runs = tonumber(mesh.kv.get("runs") or "0") + 1
mesh.kv.set("runs", tostring(runs))
mesh.log("script run #" .. runs)

local response = mesh.curl.get("https://example.com/api/status", {
    timeout_ms = 1500,
    max_bytes = 4096,
    headers = {
        ["Accept"] = "application/json"
    }
})

if response.ok then
    mesh.log(response.body)
else
    mesh.log("HTTP error: " .. tostring(response.status) .. " " .. tostring(response.error))
end
```

### Built-in Canvas menu

```lua
mesh.canvas.open({
    title = "Demo menu",
    background = "#111827",
    fps = 30
})

local items = { "Start", "Settings", "Exit" }
local selected = 1

local function draw()
    local size = mesh.canvas.size()
    mesh.canvas.clear("#111827")
    mesh.canvas.set_font(28, "Roboto", "BOLD")
    mesh.canvas.fill_text("MeshApp Lua", 40, 60, "#e5e7eb")

    mesh.canvas.set_font(20, "Roboto")
    for i, label in ipairs(items) do
        local y = 105 + i * 48
        local bg = i == selected and "#2563eb" or "#1f2937"
        mesh.canvas.fill_round_rect(40, y - 30, 240, 38, 8, bg)
        mesh.canvas.fill_text(label, 62, y - 5, "#f9fafb")
    end

    mesh.canvas.fill_text("mouse: " .. math.floor(mesh.canvas.mouse().x), size.width - 180, 32, "#9ca3af")
end

function on_canvas_event(event)
    if event.type == "mouse_clicked" then
        for i = 1, #items do
            local y = 105 + i * 48
            if event.x >= 40 and event.x <= 280 and event.y >= y - 30 and event.y <= y + 8 then
                selected = i
            end
        end
    end
    draw()
end

function on_canvas_frame(event)
    draw()
end
```
