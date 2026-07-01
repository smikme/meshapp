# MeshApp Script Lua API

**Language:** [Русский](lua-api.ru.md) | English | [Deutsch](lua-api.de.md)

MeshApp runs user Lua scripts in a LuaJ sandbox. Scripts can use the `mesh` namespace, core Lua functions, the `string`, `table`, `math`, `coroutine`, `bit32` libraries and functions such as `pairs`, `ipairs`, `pcall`, `tonumber` and `tostring`. Unsafe global APIs are disabled: `io`, `os`, `debug`, `package`, `require`, `dofile`, `loadfile`, `luajava`, `collectgarbage`, `module`.

A regular script runs once and exits unless it declares `on_message(msg)`, `on_reaction(reaction)`, or has pending asynchronous operations. If `on_message(msg)` or `on_reaction(reaction)` is declared, the script stays active and receives new chat events. Command bots use `on_command(command)`, and UI/request results arrive in separate callback functions.

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
| `mesh.localtime([epoch_seconds])` | Returns a local date/time table for the current moment or timestamp |
| `mesh.date([epoch_seconds])` | Returns the local date formatted by the system regional settings |
| `mesh.time([epoch_seconds])` | Returns the local time formatted by the system regional settings |
| `mesh.datetime([epoch_seconds])` | Returns the local date and time formatted by the system regional settings |
| `mesh.iso_date([epoch_seconds])` | Returns a stable local date string: `YYYY-MM-DD` |
| `mesh.iso_time([epoch_seconds])` | Returns a stable local time string: `HH:MM:SS` |
| `mesh.iso_datetime([epoch_seconds])` | Returns a stable local date/time string: `YYYY-MM-DD HH:MM:SS` |
| `mesh.sleep(seconds)` | Blocking pause from `0` to `10` seconds |
| `mesh.json.*` | JSON encode/decode helpers |
| `mesh.timer.*` | Host-managed timers that call `on_timer(event)` |
| `mesh.owner()` | Returns `{ node_id, node_num, connection_id }` for the current node |
| `mesh.command()` | Returns the current command or an empty table outside command launch |

### Time and Dates

All time helpers use the application's system time zone. The short helpers
`mesh.date(...)`, `mesh.time(...)`, and `mesh.datetime(...)` are meant for
human-facing text and follow the system regional format, including 12/24-hour
time and seconds. Use `mesh.iso_date(...)`, `mesh.iso_time(...)`, or
`mesh.iso_datetime(...)` when a script needs stable strings for sorting,
storage keys, file names, or comparisons.

`mesh.localtime([epoch_seconds])` returns a table with numeric fields and both
localized and stable string forms:

| Field | Meaning |
|-------|---------|
| `year`, `month`, `day` | Local calendar date |
| `hour`, `minute`, `second` | Local clock time |
| `min`, `sec` | Aliases for `minute` and `second` |
| `weekday` | ISO weekday, Monday is `1`, Sunday is `7` |
| `wday` | Lua-style weekday, Sunday is `1`, Saturday is `7` |
| `yearday`, `yday` | Day of year |
| `timezone`, `zone` | System time zone id, for example `Europe/Moscow` |
| `offset`, `offset_seconds` | UTC offset as a string and seconds |
| `epoch` | Unix time in seconds |
| `date`, `time`, `datetime` | Localized display strings |
| `iso_date`, `iso_time`, `iso_datetime` | Stable local strings |
| `iso` | ISO offset date/time, including the UTC offset |

```lua
local t = mesh.localtime()
mesh.log(t.datetime .. " " .. t.timezone)

local sent_at = mesh.datetime(msg.timestamp)
local key = "daily:" .. mesh.iso_date()
```

## Callbacks

| Callback | When it is called |
|----------|-------------------|
| `on_message(msg)` | For every new incoming or outgoing message while the script is running |
| `on_reaction(reaction)` | For every new incoming or outgoing message reaction while the script is running |
| `on_command(command)` | When an automation bot is started from chat |
| `on_extension_open(event)` | When an extension script is opened from the left toolbar |
| `on_form_event(event)` | After an action or value change from a component created through `mesh.form.*` |
| `on_node_selected(event)` | After selecting or cancelling node selection through `mesh.ui.pick_node(...)` |
| `on_traceroute(event)` | After `mesh.traceroute.request(...)` produces a result |
| `on_node_info(event)` | After `mesh.nodeinfo.request(...)` produces a result |
| `on_admin(event)` | After `mesh.admin.*` remote administration requests produce progress or a result |
| `on_timer(event)` | After a host-managed timer from `mesh.timer.*` fires |
| `on_canvas_event(event)` | After an event in a floating Canvas window: mouse, keyboard, resize, open/close |
| `on_canvas_frame(event)` | On the Canvas window timer, if `fps` is set or `mesh.canvas.set_fps(...)` was called |

## `mesh.timer`

Timers are managed by MeshApp, not by a Lua `while true` loop. A script stays
running while it has active timers. Timer callbacks are delivered serially on
the script's Lua executor; if a repeating timer fires again while the previous
callback is still queued, MeshApp skips the extra tick instead of running
callbacks in parallel or catching up in a burst.

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.timer.after(seconds[, options])` | `timer_id` | Calls `on_timer(event)` once after `seconds` |
| `mesh.timer.every(seconds[, options])` | `timer_id` | Calls `on_timer(event)` repeatedly |
| `mesh.timer.cancel(timer_id)` | boolean | Cancels one active timer |
| `mesh.timer.cancel_all()` | number | Cancels all timers and returns the cancelled count |

`seconds` must be from `0.1` to `604800` seconds. `options` can contain:

| Option | Type | Purpose |
|--------|------|---------|
| `name` | string | Caller-defined timer name copied to `event.name` |
| `immediate` | boolean | For `every`, fire once immediately before the first interval |
| `align` | string | For `every`: `interval` or `wall`, default `interval` |

`align = "interval"` runs every N seconds from the previous scheduled tick.
`align = "wall"` aligns to local wall-clock boundaries. For example, `600`
seconds runs at local `HH:00`, `HH:10`, `HH:20`, and so on.

Timer event fields:

| Field | Meaning |
|-------|---------|
| `type` | Always `timer` |
| `source` | API source, such as `mesh.timer.every` |
| `id`, `timer_id` | Timer id |
| `name` | Name from options or an empty string |
| `interval_seconds`, `seconds` | Timer interval or delay |
| `repeating` | Whether the timer repeats |
| `align` | `interval` or `wall` |
| `count` | Delivered callback count for this timer |
| `scheduled_epoch`, `actual_epoch` | Planned and actual Unix time in seconds |
| `drift_seconds` | `actual_epoch - scheduled_epoch` |
| `time` | `mesh.localtime(actual_epoch)` table |

```lua
mesh.timer.every(600, {
    name = "ten-minute-job",
    align = "wall"
})

function on_timer(event)
    if event.name == "ten-minute-job" then
        mesh.log("tick at " .. event.time.iso_datetime)
    end
end
```

## `mesh.chat`

`chat_type` accepts `channel` or `dm`. For a channel, `chat_key` is a string channel index, for example `"0"`. For a direct message, `chat_key` is the peer node ID, for example `"!abcdef12"`.

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.chat.send_channel(channel, text[, reply_id])` | `message` or `nil` | Sends a radio message to a channel |
| `mesh.chat.send_dm(node_id, text[, reply_id])` | `message` or `nil` | Sends a radio direct message |
| `mesh.chat.reply(msg, text)` | `message` or `nil` | Sends a reply to the same chat where `msg` arrived |
| `mesh.chat.react(msg, emoji)` | `true` or `nil` | Sends a Meshtastic reaction to `msg` |
| `mesh.chat.bot_message(chat_type, chat_key, text)` | `message` | Adds a local bot message to history without sending it over radio |
| `mesh.chat.bot_reply(msg, text)` | `message` | Adds a local bot reply to a message |
| `mesh.chat.bot_notice(chat_type, chat_key, text[, options])` | `true` | Shows a temporary bot UI message without writing it to history |
| `mesh.chat.recent(chat_type, chat_key[, limit])` | list of `message` | Returns recent messages, `limit` from 1 to 200, default 20 |
| `mesh.chat.nodes()` | list of `node` | Returns known nodes for the current connection |
| `mesh.chat.channels()` | list of `channel` | Returns known channels for the current connection |

`mesh.chat.react(msg, emoji)` requires a message table with `packet_id` and a
supported `chat_type` (`channel` or `dm`). Reactions are sent through the active
Meshtastic connection and are not available for MeshCore connections.

## `mesh.kv`

KV storage is isolated per script and persisted in the local application database.

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.kv.get(key)` | string or `nil` | Reads a value |
| `mesh.kv.set(key, value)` | `true` | Stores a value as a string; `nil` stores an empty value |
| `mesh.kv.delete(key)` | boolean | Deletes a key |
| `mesh.kv.list()` | table | Returns all script keys |
| `mesh.kv.clear()` | `true` | Clears the script KV storage |

## `mesh.json`

JSON helpers convert between JSON text and normal Lua values. Objects become
tables with string keys; arrays become tables indexed from `1`. JSON `null` is
represented by `mesh.json.null`, because Lua `nil` removes table fields.

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.json.decode(text)` | value | Parses JSON text or raises an error |
| `mesh.json.try_decode(text)` | `value, nil` or `nil, error` | Parses JSON without stopping the script |
| `mesh.json.encode(value[, options])` | string | Encodes a Lua value to compact JSON |
| `mesh.json.pretty(value)` | string | Encodes a Lua value to formatted JSON |
| `mesh.json.array(table)` | table | Marks a Lua table as a JSON array, including an empty array |
| `mesh.json.is_null(value)` | boolean | Returns `true` for `mesh.json.null` |
| `mesh.json.null` | value | JSON null sentinel |

Supported value mapping:

| JSON | Lua |
|------|-----|
| object | table with string keys |
| array | table with indexes `1..n` |
| string | string |
| number | number |
| boolean | boolean |
| null | `mesh.json.null` |

When encoding, a table with contiguous integer keys `1..n` becomes a JSON
array. A table with string keys becomes a JSON object. Mixed tables or arrays
with holes raise an error. An empty table is encoded as `{}` unless it was
created or marked with `mesh.json.array({})`. `mesh.json.encode(value, true)`
or `mesh.json.encode(value, { pretty = true })` produces formatted JSON.

Parsing input is limited to 1 MB. Nesting is limited to 64 levels, and one
object or array can contain up to 50,000 items.

```lua
local response = mesh.curl.get("https://example.com/api/status")
local data, err = mesh.json.try_decode(response.body)
if not data then
    mesh.log("bad JSON: " .. err)
    return
end

if not mesh.json.is_null(data.status) then
    mesh.log("status: " .. data.status)
end

local body = mesh.json.encode({
    at = mesh.iso_datetime(),
    values = mesh.json.array({ 1, 2, 3 }),
    empty = mesh.json.array({})
})
```

## `mesh.curl`

HTTP(S) requests are executed by the built-in Java HTTP client. Access to local, private, link-local and multicast addresses is blocked. URLs with credentials are also blocked.

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.curl.get(url[, options])` | `curl.response` | Performs a GET request |
| `mesh.curl.request(options)` | `curl.response` | Performs a request with parameters |

`options` fields:

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `url` | string | HTTP(S) request URL. In `mesh.curl.get(url[, options])` it is usually passed as the first argument; in `mesh.curl.request(options)` it is required in the table |
| `method` | string | HTTP method. Defaults to `GET`; allowed values are `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE` |
| `body` | string or `nil` | UTF-8 request body for methods with a body; not sent for `GET` and `HEAD` |
| `headers` | table<string,string> | Request headers. Header names are validated, and service headers such as `Host` and `Content-Length` are blocked |
| `timeout_ms` | number | Request timeout in milliseconds. Limited to `100..5000`, default `1500` |
| `max_bytes` | number | Maximum response body bytes. Limited to `0..1048576`, default `262144`; when the limit is reached, `response.truncated = true` |

`curl.response` fields:

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `ok` | boolean | `true` when the HTTP status is in `200..299`; otherwise `false` |
| `status` | number | HTTP response status. Returns `0` if the request ended without an HTTP response |
| `url` | string or `nil` | Final URL after redirects; `nil` on request execution error |
| `body` | string | Response body decoded as UTF-8 and limited by `max_bytes`; empty string on error |
| `headers` | table<string,string> | Response headers: lower-case names, multiple values joined with `, `; empty table on error |
| `truncated` | boolean | `true` if the response body was cut by `max_bytes` |
| `error` | string or `nil` | Request execution error text; for a normal HTTP response, including non-2xx status, returns `nil` |

## `mesh.ui`

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.ui.pick_node(options)` | `request_id` | Opens node selection and later calls `on_node_selected(event)` |

`options` fields. A string can be passed instead of a table; it will be used as `query`.

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `name` | string | Custom request name returned later as `event.name` |
| `prompt` | string | Title or hint text in the node picker |
| `query` | string | Initial node search/filter text |
| `chat_type` | string | Chat context: `channel`, `dm`, or empty string; returned in the event |
| `chat_key` | string | Chat key: channel index as a string or peer node ID; returned in the event |

`on_node_selected(event)` fields:

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `type` | string | Event type; returns `ui_result` |
| `source` | string | Event API source; usually `mesh.ui.pick_node` |
| `name` | string | Original request name from `options.name` |
| `request_id` | string | Request ID returned by `mesh.ui.pick_node(...)` |
| `status` | string | Selection result: `selected` or `cancelled` |
| `selected` | boolean | `true` when the user selected a node |
| `cancelled` | boolean | `true` when the picker was cancelled |
| `chat_type` | string | Chat context from the original `options` |
| `chat_key` | string | Chat key from the original `options` |
| `node` | `node` or `nil` | Selected node; `nil` if the picker was cancelled |

## `mesh.form`

`mesh.form` is available only to scripts of type “Extension”. An extension script adds a button to the left application toolbar and controls an embedded MeshApp section, not a separate window.

Form components do not return Lua objects with their own methods. A script creates a component with `mesh.form.add(...)`, receives or assigns its `id`, and then controls it through `mesh.form.set(id, ...)`, `mesh.form.value(id)`, and `mesh.form.remove(id)`.

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.form.show([options])` | `true` | Shows the embedded extension section; if `options.text` is passed, changes the title |
| `mesh.form.set_title(title)` | `true` | Changes the section title; an empty title falls back to the script name |
| `mesh.form.clear()` | `true` | Removes all created components |
| `mesh.form.add(options)` | `component_id` | Adds a component and returns its id; a string type can be passed instead of a table, for example `"separator"` |
| `mesh.form.set(id, options)` | `true` | Updates component properties; an existing component's type, id, and parent are not changed |
| `mesh.form.remove(id)` | `true` | Removes a component |
| `mesh.form.value(id)` | value or `nil` | Returns the current component value |

### Common `options` Fields

`options.type` is required for `mesh.form.add(...)`. Fields unsupported by a component are ignored.

| Field | Type | Purpose |
|-------|------|---------|
| `type` | string | Component type to create: `label`, `button`, `text_field`, `password_field`, `text_area`, `checkbox`, `toggle_switch`, `combo_box`, `segmented_control`, `list_view`, `slider`, `progress_bar`, `ring_progress`, `line_chart`, `area_chart`, `bar_chart`, `separator`, `spacer`, `message`, `tile`, `card`, `vbox`, `hbox`, `split_pane`, `scroll_pane` |
| `id` | string | Stable component id. If omitted, MeshApp creates one and returns it from `add(...)` |
| `parent` | string | Id of a `card`, `vbox`, `hbox`, `split_pane`, or `scroll_pane` container. If omitted, the component is added to the form root |
| `text` | string | Label or title for `label`, `button`, `checkbox`, `toggle_switch`, `message`, or `tile` |
| `prompt` | string | Placeholder for `text_field`, `password_field`, and `text_area`; for `combo_box`, it is used as the empty value prompt |
| `value` | string/number/boolean | Current component value. `progress_bar` and `ring_progress` use `0..1`; `slider` is clamped to `min..max`; `message` and `tile` use it as the description |
| `selected` | boolean | Alias for `checkbox` and `toggle_switch` `value` when `value` is omitted |
| `items` | `array<string>` | Options for `combo_box`, `segmented_control`, and `list_view` |
| `min`, `max` | number | `slider` range; defaults to `0..100`. For charts, sets the Y-axis range when both values are present |
| `series` | `array<table>` | Data series for `line_chart`, `area_chart`, and `bar_chart`; each series supports `name`, `color`, and `points` |
| `x_label`, `y_label` | string | Axis labels for chart components |
| `x_type` | string | Chart X-axis mode; use `time`, `timestamp`, or `epoch` to format Unix seconds as local time |
| `chart_type` | string | For `type = "chart"`, selects `line`, `area`, or `bar` |
| `legend` | boolean | Shows or hides a chart legend; by default legends are shown when a chart has more than one series |
| `symbols` | boolean | Shows point symbols on `line_chart` and `area_chart`; defaults to `false` |
| `orientation` | string | `horizontal` or `vertical` for `separator`, `spacer`, and `split_pane` |
| `width`, `height` | number | Preferred component size in pixels |
| `min_width`, `min_height` | number | Minimum component size in pixels |
| `max_width`, `max_height` | number | Maximum component size in pixels |
| `grow` | string/boolean | Layout growth: `always`, `sometimes`, `never`, `true`/`false`. Applies in `vbox`, `hbox`, and to `split_pane` children |
| `rows` | number | Visible row count for `text_area` |
| `wrap` | boolean | Text wrapping for `label` and `text_area` |
| `read_only` | boolean | Makes `text_field`, `password_field`, and `text_area` non-editable while keeping selection/copy available |
| `monospace` | boolean | Enables a monospace font for text components; `style = "monospace"` is also accepted |
| `disabled` | boolean | Disables or enables the component |
| `visible` | boolean | Shows or hides the component; a hidden component does not take layout space |
| `style` | string | `button` supports `accent` when created; text components support `monospace` |

`mesh.form.set(id, options)` can update supported properties such as `text`, `prompt`, `value`, `items`, `min`, `max`, sizes, `grow`, `read_only`, `wrap`, `monospace`, `disabled`, and `visible`. `type`, `id`, and `parent` are creation-time fields; remove and recreate a component to change them.

Chart `series` is a list of series tables. Each series has `name`, optional CSS `color`, and `points`. A point can be `{ x = 1700000000, y = 21.5 }`, `{ timestamp = 1700000000, value = 21.5 }`, `{ 1700000000, 21.5 }`, or a plain number where the X value becomes the point index.

### Component Types

| `options.type` | Creates | Main properties | `mesh.form.value(id)` | Events |
|----------------|---------|-----------------|------------------------|--------|
| `label` | Wrapped text label | `id`, `parent`, `text`, `value`, `disabled`, `visible` | Current text | None |
| `button` | Button | `id`, `parent`, `text`, `style="accent"`, `disabled`, `visible` | `nil` | `action` on click |
| `text_field` | Single-line text input | `id`, `parent`, `value`, `prompt`, `read_only`, `monospace`, `disabled`, `visible` | String | `change` when text changes, `action` on Enter |
| `password_field` | Password input | `id`, `parent`, `value`, `prompt`, `read_only`, `disabled`, `visible` | String | `change` when text changes, `action` on Enter |
| `text_area` | Multi-line text input | `id`, `parent`, `value`, `prompt`, `rows`, `wrap`, `read_only`, `monospace`, `disabled`, `visible` | String | `change` when text changes |
| `checkbox` | Checkbox with text | `id`, `parent`, `text`, `value`/`selected`, `disabled`, `visible` | Boolean | `change` when toggled |
| `toggle_switch` | AtlantaFX toggle switch | `id`, `parent`, `text`, `value`/`selected`, `disabled`, `visible` | Boolean | `change` when toggled |
| `combo_box` | Drop-down selection | `id`, `parent`, `items`, `value`, `prompt`, `disabled`, `visible` | Selected string or `nil` | `change` when selected |
| `segmented_control` | AtlantaFX segmented button group | `id`, `parent`, `items`, `value`, `disabled`, `visible` | Selected string or `nil` | `change` when selected |
| `list_view` | String list | `id`, `parent`, `items`, `value`, `disabled`, `visible` | Selected string or `nil` | `change` when selected |
| `slider` | Numeric slider | `id`, `parent`, `min`, `max`, `value`, `disabled`, `visible` | Number | `change` when moved |
| `progress_bar` | Progress indicator | `id`, `parent`, `value`, `disabled`, `visible` | Number `0..1` | None |
| `ring_progress` | AtlantaFX ring progress indicator | `id`, `parent`, `value`, `width`, `height`, `disabled`, `visible` | Number `0..1` | None |
| `line_chart` | Numeric line chart | `id`, `parent`, `text`, `series`, `x_label`, `y_label`, `x_type`, `min`, `max`, `legend`, `symbols`, sizes | `nil` | None |
| `area_chart` | Numeric area chart | `id`, `parent`, `text`, `series`, `x_label`, `y_label`, `x_type`, `min`, `max`, `legend`, `symbols`, sizes | `nil` | None |
| `bar_chart` | Numeric bar chart | `id`, `parent`, `text`, `series`, `x_label`, `y_label`, `x_type`, `min`, `max`, `legend`, sizes | `nil` | None |
| `separator` | Separator | `id`, `parent`, `orientation`, `disabled`, `visible` | `nil` | None |
| `spacer` | AtlantaFX empty spacer | `id`, `parent`, `orientation`, `value`, `grow`, `disabled`, `visible` | `nil` | None |
| `message` | AtlantaFX message with title and description | `id`, `parent`, `text`, `value`, `disabled`, `visible` | Description | `action`, `close` |
| `tile` | AtlantaFX tile with title and description | `id`, `parent`, `text`, `value`, `disabled`, `visible` | Description | `action` |
| `card` | App-styled card container | `id`, `parent`, `disabled`, `visible` | `nil` | None |
| `vbox` | Vertical container | `id`, `parent`, `grow`, `disabled`, `visible` | `nil` | None |
| `hbox` | Horizontal container | `id`, `parent`, `grow`, `disabled`, `visible` | `nil` | None |
| `split_pane` | Split view with multiple child panes | `id`, `parent`, `orientation`, `grow`, `disabled`, `visible` | `nil` | None |
| `scroll_pane` | Scrollable container | `id`, `parent`, `grow`, `disabled`, `visible` | `nil` | None |

`card`, `vbox`, `hbox`, `split_pane`, and `scroll_pane` are layout containers. To place a component inside a container, set `parent = "container_id"` or use the id returned by `mesh.form.add(...)`. For `split_pane`, every child becomes a separate split-view area; for `scroll_pane`, usually add one child `vbox` or `hbox` container.

`on_extension_open(event)` fields:

| Field | Type | Purpose |
|-------|------|---------|
| `type` | string | Always `extension_open` |
| `source` | string | Always `mesh.extension` |
| `script_id` | number | Current script id |
| `name` | string | Current script name |

`on_form_event(event)` fields:

| Field | Type | Purpose |
|-------|------|---------|
| `type` | string | `action` for buttons/Enter in a field, `change` for value changes, `close` when a `message` is closed |
| `source` | string | Always `mesh.form` |
| `component_id`, `id` | string | Component id |
| `value` | string/number/boolean/nil | Current component value |
| `text` | string or `nil` | Text representation of the value |

Extension form example:

```lua
function on_extension_open(event)
    mesh.form.set_title("Diagnostics")
    mesh.form.clear()

    local card = mesh.form.add({ type = "card", id = "main" })
    mesh.form.add({ type = "label", id = "status", parent = card, text = "Ready" })
    mesh.form.add({ type = "text_field", id = "node", parent = card, prompt = "Node ID" })
    mesh.form.add({
        type = "combo_box",
        id = "mode",
        parent = card,
        items = { "status", "trace", "admin" },
        value = "status"
    })
    mesh.form.add({ type = "checkbox", id = "verbose", parent = card, text = "Verbose", value = true })
    mesh.form.add({ type = "button", id = "run", parent = card, text = "Run", style = "accent" })
end

function on_form_event(event)
    if event.id == "run" and event.type == "action" then
        local node = mesh.form.value("node")
        local mode = mesh.form.value("mode")
        local verbose = mesh.form.value("verbose")
        mesh.form.set("status", {
            text = "Run: " .. tostring(mode) .. " / " .. tostring(node) .. " / verbose=" .. tostring(verbose)
        })
    elseif event.id == "mode" and event.type == "change" then
        mesh.form.set("status", { text = "Mode: " .. tostring(event.value) })
    end
end
```

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

`options` fields for `mesh.canvas.open(options)`:

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `title` | string | Canvas window title. If `open` receives a string instead of a table, it is used as `title` |
| `width` | number | Initial canvas width in pixels. Defaults to `640`; the window clamps size to `260..1920` |
| `height` | number | Initial canvas height in pixels. Defaults to `360`; the window clamps size to `220..1080` |
| `background` | string | Initial background color in JavaFX/CSS format; empty string does not fill the background |
| `resizable` | boolean | `true` if the canvas should resize with the window; default `true` |
| `fps` | number | `on_canvas_frame(event)` frequency. `0` disables the timer; the window clamps the value to `0..120` |

By default, Canvas scales with the floating window (`resizable = true`); resize it by dragging the window edges. The button in the top-right corner closes the window after confirmation. Double-clicking the top drag zone minimizes the window to a translucent square with the script icon; double-clicking the square restores the previous size.

Color can be passed as a JavaFX/CSS string (`"#ffcc00"`, `"rgba(255,0,0,0.5)"`, `"white"`) or as a table `{r, g, b, a}`. `r/g/b/a` components are accepted in the `0..1` or `0..255` range.

`points` can be a flat list `{x1, y1, x2, y2, ...}` or a list of points `{{x=10, y=10}, {x=40, y=20}}`.

`on_canvas_event(event)` and `on_canvas_frame(event)` fields:

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `type` | string | Canvas event type; frame callbacks return `frame` |
| `source` | string | Event source; returns `mesh.canvas` |
| `x`, `y` | number | Mouse coordinates inside the canvas for mouse/scroll events; `0` for other events |
| `screen_x`, `screen_y` | number | Screen mouse coordinates for mouse/scroll events; `0` for other events |
| `button` | string | Mouse button: `primary`, `middle`, `secondary`, `back`, `forward`, or empty string |
| `click_count` | number | Click count in a mouse event |
| `primary`, `middle`, `secondary` | boolean | State of the corresponding mouse buttons at event time |
| `wheel_delta_x`, `wheel_delta_y` | number | Horizontal and vertical scroll delta for `scroll`; otherwise `0` |
| `code` | string | Key code for keyboard events, for example `Left` or `Enter` |
| `key` | string | Display name of the key for keyboard events |
| `text` | string | Keyboard event text/character when present |
| `shift`, `ctrl`, `alt`, `meta` | boolean | Keyboard modifier state |
| `width`, `height` | number | Current canvas size in pixels |
| `time` | number | Event Unix time in seconds |
| `dt` | number | For `on_canvas_frame`, seconds since the previous frame; for other events `0` |

`event.type` values: `opened`, `closed`, `resized`, `mouse_moved`, `mouse_pressed`, `mouse_released`, `mouse_clicked`, `mouse_dragged`, `mouse_entered`, `mouse_exited`, `scroll`, `key_pressed`, `key_released`, `key_typed`.

## `mesh.traceroute`

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.traceroute.request(target[, options])` | `request_id` | Starts traceroute to a node and later calls `on_traceroute(event)` |

`target` can be a node ID string (`"!abcdef12"`), numeric `node_num` or a node table with `node_num`, `node_id`, `long_name`, `short_name`.

`options` fields:

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `name` | string | Custom request name returned later as `event.name` |
| `chat_type` | string | Chat context: `channel`, `dm`, or empty string; returned in the event |
| `chat_key` | string | Chat key: channel index as a string or peer node ID; returned in the event |
| `target_name` | string | Display name of the target node; if omitted, it is derived from `target` |
| `timeout_seconds` | number | Result wait timeout in seconds. Limited to `1..600`, default `360` |

`on_traceroute(event)` fields:

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `type` | string | Event type; returns `traceroute_result` |
| `source` | string | Event API source; usually `mesh.traceroute.request` |
| `name` | string | Original request name from `options.name` |
| `request_id` | string | Request ID returned by `mesh.traceroute.request(...)` |
| `status` | string | Result status: `ok`, `timeout`, or `error` |
| `ok` | boolean | `true` when traceroute successfully received a route |
| `timeout` | boolean | `true` when `timeout_seconds` expired |
| `error` | string or `nil` | Send/execution error text; `nil` when there is no error |
| `target_node_num` | number | Numeric Meshtastic ID of the target node (`uint32`) |
| `target_node_id` | string | Target node ID in `!abcdef12` form |
| `target_name` | string | Display name of the target node |
| `response_from_node_num` | number or `nil` | Numeric ID of the node that responded; `nil` when there was no response |
| `response_from_node_id` | string or `nil` | Node ID of the node that responded; `nil` when there was no response |
| `chat_type` | string | Chat context from the original `options` |
| `chat_key` | string | Chat key from the original `options` |
| `route` | `route.discovery` or `nil` | Route table; `nil` on timeout/error or when no route was received |

`route.discovery` fields:

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `route` | list of number | Forward route as `node_num` values |
| `route_back` | list of number | Reverse route as `node_num` values |
| `route_ids` | list of string | Forward route as node IDs in `!abcdef12` form |
| `route_back_ids` | list of string | Reverse route as node IDs in `!abcdef12` form |
| `snr_towards` | list of number | Forward-route SNR values in dB |
| `snr_back` | list of number | Reverse-route SNR values in dB |

## `mesh.node`

`mesh.node` manages local MeshApp node flags. The functions are synchronous and use `FavoriteNodeService` and `IgnoredNodeService`: they update the local flag for the Lua session owner node and send the corresponding admin command through the bot connection when available. These functions return `true` and do not call `on_admin(event)`.

`target` can be a node ID string (`"!abcdef12"`), numeric `node_num`, or a node table with `node_num`, `node_id`.

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.node.set_favorite_node(target)` | boolean | Adds the target node to MeshApp favorite nodes and syncs the flag to the device |
| `mesh.node.remove_favorite_node(target)` | boolean | Removes the target node from MeshApp favorite nodes and syncs the flag to the device |
| `mesh.node.set_ignored_node(target)` | boolean | Adds the target node to MeshApp ignored nodes and syncs the flag to the device |
| `mesh.node.remove_ignored_node(target)` | boolean | Removes the target node from MeshApp ignored nodes and syncs the flag to the device |

## `mesh.nodeinfo`

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.nodeinfo.request(target[, options])` | `request_id` | Requests NodeInfo and later calls `on_node_info(event)` |

`target` and `options` are the same as for `mesh.traceroute.request(...)`, but default timeout is 60 seconds.

`on_node_info(event)` fields:

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `type` | string | Event type; returns `nodeinfo_result` |
| `source` | string | Event API source; usually `mesh.nodeinfo.request` |
| `name` | string | Original request name from `options.name` |
| `request_id` | string | Request ID returned by `mesh.nodeinfo.request(...)` |
| `status` | string | Result status: `ok`, `timeout`, or `error` |
| `ok` | boolean | `true` when NodeInfo was received successfully |
| `timeout` | boolean | `true` when `timeout_seconds` expired |
| `cached` | boolean | `true` if a locally known node is returned on timeout/error |
| `error` | string or `nil` | Request error text; `nil` when there is no error |
| `target_node_num` | number | Numeric Meshtastic ID of the target node (`uint32`) |
| `target_node_id` | string | Target node ID in `!abcdef12` form |
| `target_name` | string | Display name of the target node |
| `chat_type` | string | Chat context from the original `options` |
| `chat_key` | string | Chat key from the original `options` |
| `node` | `node` or `nil` | Node table; `nil` when no node data is available |

## `mesh.admin`

Remote admin works only for Meshtastic connections. The local client's public key must be listed in the target node's Admin Key. All functions are asynchronous: they return `request_id` and later call `on_admin(event)`.

`target` can be a node ID string (`"!abcdef12"`), numeric `node_num`, or a node table with `node_num`, `node_id`, `long_name`, `short_name`.

| Function | Return | Purpose |
|----------|--------|---------|
| `mesh.admin.load_config(target[, options])` | `request_id` | Loads a remote snapshot: owner, metadata, configs, module configs, channels, status |
| `mesh.admin.request_config(target, type[, options])` | `request_id` | Loads one core config section, for example `POWER_CONFIG` or `power` |
| `mesh.admin.request_module_config(target, type[, options])` | `request_id` | Loads one module config section, for example `MQTT_CONFIG` or `mqtt` |
| `mesh.admin.save_config(target, changes, options)` | `request_id` | Saves owner/position/text/config/channel changes; requires `options.confirm = true` |
| `mesh.admin.refresh_status(target[, options])` | `request_id` | Reloads device connection status |
| `mesh.admin.reboot(target[, delay_seconds[, options]])` | `request_id` | Requests delayed reboot; `0` cancels pending reboot |
| `mesh.admin.shutdown(target[, delay_seconds[, options]])` | `request_id` | Requests delayed shutdown; `0` cancels pending shutdown |
| `mesh.admin.sync_time(target[, epoch_seconds[, options]])` | `request_id` | Sets remote node time; defaults to current app time |
| `mesh.admin.backup(target[, location[, options]])` | `request_id` | Backs up preferences to `FLASH` or `SD` |
| `mesh.admin.restore(target[, location], options)` | `request_id` | Restores preferences; requires `options.confirm = true` |
| `mesh.admin.remove_backup(target[, location], options)` | `request_id` | Removes stored backup; requires `options.confirm = true` |
| `mesh.admin.reset_nodedb(target[, preserve_favorites], options)` | `request_id` | Resets remote NodeDB; requires `options.confirm = true` |
| `mesh.admin.factory_reset_config(target, options)` | `request_id` | Factory-resets remote config; requires `options.confirm = true` |
| `mesh.admin.factory_reset_device(target, options)` | `request_id` | Factory-resets the remote device; requires `options.confirm = true` |
| `mesh.admin.enter_dfu_mode(target, options)` | `request_id` | Requests DFU mode; requires `options.confirm = true` |
| `mesh.admin.set_owner(target, owner[, options])` | `request_id` | Updates `{ long_name, short_name, licensed }` |
| `mesh.admin.set_fixed_position(target, position[, options])` | `request_id` | Sets `{ latitude, longitude, altitude }` as a manual position |
| `mesh.admin.remove_fixed_position(target[, options])` | `request_id` | Clears manual fixed position |
| `mesh.admin.set_ringtone(target, text[, options])` | `request_id` | Updates RTTTL ringtone text |
| `mesh.admin.set_canned_messages(target, text[, options])` | `request_id` | Updates canned message module text |

`save_config` patch example:

```lua
local target = "!abcdef12"

mesh.admin.request_config(target, "POWER_CONFIG")

function on_admin(event)
    if event.action == "request_config" and event.ok then
        mesh.admin.save_config(target, {
            owner = { long_name = "Remote Node", short_name = "RMT", licensed = true },
            configs = {
                power = { ls_secs = 300, min_wake_secs = 10 }
            },
            module_configs = {
                mqtt = { enabled = true, address = "mqtt.example.com" }
            },
            channels = {
                { index = 0, role = "PRIMARY", settings = { name = "LongFast" } }
            }
        }, { confirm = true })
    end
end
```

Config, module config and channel patches use protobuf `snake_case` field names. Enum values are strings such as `"PRIMARY"` or `"CLIENT"`. Repeated fields are Lua lists. Bytes are accepted as hex strings, `hex:...`, `base64:...`, or Base64 strings. By default patches are merged with the section loaded by `load_config` or `request_config`; if a section was not loaded, `save_config` fails. Pass `{ replace = true, confirm = true }` only when intentionally sending a replacement built from defaults.

Full list of readable and writable config fields: [lua-admin-config-reference.md](lua-admin-config-reference.md).

`on_admin(event)` fields:

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `type` | string | `admin_progress` or `admin_result` |
| `source` | string | API source, for example `mesh.admin.save_config` |
| `name` | string | Original request name from `options.name` |
| `request_id` | string | Request ID returned by `mesh.admin.*` |
| `action` | string | Action name, for example `load_config`, `save_config`, `reboot` |
| `status` | string | `ok`, `timeout`, `error`, or progress state `sent`/`received`/`failed` |
| `ok` | boolean | `true` for successful terminal results |
| `timeout` | boolean | `true` when the terminal result timed out |
| `error` | string or `nil` | Failure detail |
| `target_node_num` | number | Numeric target node ID (`uint32`) |
| `target_node_id` | string | Target node ID in `!abcdef12` form |
| `snapshot` | table or `nil` | Remote snapshot on terminal results |
| `progress_key` | string or `nil` | Snapshot block key for progress events |
| `completed` | number or `nil` | Completed snapshot blocks for progress events |
| `total` | number or `nil` | Total snapshot blocks for progress events |

`event.snapshot` includes `node`, `owner`, `device_metadata`, `ringtone`, `canned_messages`, `connection_status`, `configs`, `module_configs`, `channels`, `query_statuses`, and `query_summary`.

## Object Fields

All objects below are returned as Lua tables. Fields typed as `... or nil` may be absent or return `nil` when MeshApp has not received the corresponding data yet.

### `owner`

Returned by `mesh.owner()`.

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `node_id` | string or `nil` | Current owner node ID, for example `!abcdef12` |
| `node_num` | number or `nil` | Numeric Meshtastic ID of the current owner node (`uint32`) |
| `connection_id` | string or `nil` | Active connection ID for the script run context |

### `message`

Returned by `on_message(msg)`, `mesh.chat.recent(...)`, and send/bot-message functions.

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `db_id` | number | Local MeshApp database ID; may be `0` for messages not saved yet |
| `packet_id` | number | Mesh packet ID; used as `reply_id` when replying |
| `chat_type` | string or `nil` | Chat type: `channel` or `dm` |
| `chat_key` | string or `nil` | Chat key: channel index as a string or peer node ID |
| `from` | string or `nil` | Sender node ID |
| `to` | string or `nil` | Recipient node ID or broadcast address |
| `channel` | number | Message channel index |
| `channel_name` | string or `nil` | Channel name when known |
| `channel_role` | string or `nil` | Meshtastic channel role, for example `PRIMARY` or `SECONDARY` |
| `text` | string or `nil` | Message text |
| `reply_id` | number | `packet_id` of the message being replied to; `0` when not a reply |
| `reply_text` | string or `nil` | Quoted message text when known |
| `timestamp` | number | Message Unix time in seconds |
| `outgoing` | boolean | `true` when the message was sent by the current node/client |
| `system` | boolean | `true` for system and local bot messages |
| `status` | string or `nil` | Delivery/processing status when known |
| `sender_name` | string or `nil` | Sender display name |
| `hop_start` | number | Original packet hop limit |
| `hop_limit` | number | Remaining packet hop limit |
| `hops` | number or `nil` | Number of hops the packet travelled; `nil` when hop data is unavailable |
| `rx_rssi` | number | Received packet RSSI |
| `rx_snr` | number | Received packet SNR in dB |

### `reaction`

Returned by `on_reaction(reaction)`.

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `db_id` | number | Local MeshApp database ID |
| `packet_id` | number | Mesh packet ID of the reaction packet |
| `target_packet_id` | number | `packet_id` of the message being reacted to |
| `chat_type` | string or `nil` | Chat type: `channel` or `dm` |
| `chat_key` | string or `nil` | Chat key: channel index as a string or peer node ID |
| `from` | string or `nil` | Sender node ID |
| `emoji` | string or `nil` | Reaction emoji |
| `timestamp` | number | Reaction Unix time in seconds |
| `outgoing` | boolean | `true` when the reaction was sent by the current node/client |
| `status` | string or `nil` | Delivery status such as `SENDING`, `DELIVERED`, `CONFIRMED`, or `FAILED` |
| `error_reason` | string or `nil` | Failure reason when known |
| `sender_name` | string or `nil` | Sender display name |

### `node`

Returned by `mesh.chat.nodes()`, `mesh.ui.pick_node(...)`, `mesh.nodeinfo.request(...)`, and accepted as a `target` for requests.

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `node_num` | number | Numeric Meshtastic node ID (`uint32`) |
| `node_id` | string or `nil` | Node ID in `!abcdef12` form |
| `long_name` | string or `nil` | Full user/node name |
| `short_name` | string or `nil` | Short user/node name |
| `last_heard` | number | Unix time of the last known packet from the node |
| `battery` | number | Battery level percentage when reported by the device |
| `externally_powered` | boolean | `true` when the node reports external power |
| `voltage` | number | Supply/battery voltage in volts |
| `snr` | number | Last known SNR for the node in dB |
| `latitude` | number | Latitude in decimal degrees |
| `longitude` | number | Longitude in decimal degrees |
| `altitude` | number | Altitude in meters |
| `hops_away` | number or `nil` | Estimated hop distance to the node; `nil` when unknown |
| `channel` | number | Channel index associated with the latest node data |
| `role` | string or `nil` | Meshtastic device role when known |
| `hw_model` | string or `nil` | Hardware model when known |
| `public_key` | string or `nil` | Node public key as a hex string |
| `uptime_seconds` | number | Node uptime in seconds |
| `channel_utilization` | number | Channel utilization percentage |
| `air_util_tx` | number | Air utilization TX percentage |
| `temperature` | number | Telemetry temperature in degrees Celsius |
| `relative_humidity` | number | Telemetry relative humidity percentage |
| `barometric_pressure` | number | Telemetry barometric pressure |
| `unmessagable` | boolean | `true` when the application considers the node unavailable for messages |
| `licensed` | boolean or `nil` | Node licensed-mode flag; `nil` when the field was not received |

### `channel`

Returned by `mesh.chat.channels()`.

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `index` | number | Channel index |
| `role` | string or `nil` | Meshtastic channel role |
| `name` | string or `nil` | Channel settings name |

### `command`

Passed to `on_command(command)` and returned by `mesh.command()` during a command launch.

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `type` | string | Command type; chat automation returns `chat_command` |
| `source` | string | Command source; chat launches return `chat` |
| `name` | string or `nil` | Command name, usually the same as `handle` |
| `request_id` | string | ID of this command invocation |
| `chat_type` | string or `nil` | Chat type where the command was invoked: `channel` or `dm` |
| `chat_key` | string or `nil` | Chat key where the command was invoked |
| `handle` | string or `nil` | Command handle, for example `@tracebot` |
| `text` | string or `nil` | Full user command text |
| `arguments` | string or `nil` | Raw argument string after the command handle |
| `argument_tokens` | list of string | Parsed command arguments; Lua indexing starts at `1` |

### `canvas.size`

Returned by `mesh.canvas.size()`.

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `width` | number | Current canvas width in pixels |
| `height` | number | Current canvas height in pixels |

### `canvas.mouse`

Returned by `mesh.canvas.mouse()`.

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `x`, `y` | number | Last mouse coordinates inside the canvas |
| `screen_x`, `screen_y` | number | Last screen mouse coordinates |
| `over` | boolean | `true` when the pointer is over the canvas |
| `pressed` | boolean | `true` when any primary mouse button is down |
| `primary`, `middle`, `secondary` | boolean | State of the corresponding mouse buttons |
| `button` | string | Last event button: `primary`, `middle`, `secondary`, `back`, `forward`, or empty string |
| `click_count` | number | Click count in the last mouse event |
| `wheel_delta_x`, `wheel_delta_y` | number | Last horizontal and vertical scroll delta |
| `last_type` | string | Last mouse/scroll event type |
| `time` | number | Unix time of the last mouse/scroll event in seconds |

### `canvas.keys`

Returned by `mesh.canvas.keys()`. For quick polling, keys are also available as boolean fields by code name, for example `mesh.canvas.keys().Left`.

| Field | Type | Purpose / returned value |
|-------|------|--------------------------|
| `pressed` | list of string | Key codes that are currently pressed |
| `last_type` | string | Last keyboard event type: `key_pressed`, `key_released`, or `key_typed` |
| `last_code` | string | Last key code, for example `Left` or `Enter` |
| `last_key` | string | Display name of the last key |
| `text` | string | Last keyboard event text/character when present |
| `shift`, `ctrl`, `alt`, `meta` | boolean | Keyboard modifier state |
| `time` | number | Unix time of the last keyboard event in seconds |

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

### React to messages and log reactions

```lua
function on_message(msg)
    if msg.outgoing or msg.system or not msg.text then
        return
    end

    if string.lower(msg.text) == "ok" then
        mesh.chat.react(msg, "👍")
    end
end

function on_reaction(reaction)
    mesh.log("reaction " .. tostring(reaction.emoji)
        .. " to packet " .. tostring(reaction.target_packet_id))
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
