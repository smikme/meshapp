# Lua API для скриптов MeshApp

MeshApp выполняет пользовательские Lua-скрипты в sandbox-среде LuaJ. Скриптам доступен namespace `mesh`, базовые функции Lua, библиотеки `string`, `table`, `math`, `coroutine`, `bit32` и функции вроде `pairs`, `ipairs`, `pcall`, `tonumber`, `tostring`. Небезопасные глобальные API отключены: `io`, `os`, `debug`, `package`, `require`, `dofile`, `loadfile`, `luajava`, `collectgarbage`, `module`.

Обычный скрипт запускается один раз и завершается, если не объявлен `on_message(msg)` или нет ожидающих асинхронных операций. Если объявлен `on_message(msg)`, скрипт остаётся активным и получает новые сообщения. Для командных ботов используется `on_command(command)`, а результаты UI/запросов приходят в отдельные callback-функции.

Ограничения выполнения:

- первичный запуск скрипта — до 3 секунд
- callback — до 1.5 секунд
- вывод `print` / `mesh.log` — до 64 КБ на запуск
- `mesh.sleep(seconds)` принимает задержку от `0` до `10` секунд и продлевает deadline текущего выполнения

## Краткая справка по Lua

Lua — небольшой динамический язык. В MeshApp код обычно состоит из функций-callback вроде `on_message(msg)` и вызовов API `mesh.*`.

### Комментарии

```lua
-- Однострочный комментарий

--[[
Многострочный комментарий.
Удобен для временного отключения блока кода.
]]
```

### Переменные и типы

Переменные не требуют объявления типа. Используйте `local`, чтобы переменная не стала глобальной и не жила между callback-вызовами дольше, чем нужно.

```lua
local text = "hello"
local count = 3
local enabled = true
local missing = nil

mesh.log(type(text))   -- string
mesh.log(type(count))  -- number
```

Основные типы: `nil`, `boolean`, `number`, `string`, `table`, `function`. Значение `nil` означает отсутствие значения. В условиях ложными считаются только `false` и `nil`; числа `0` и пустая строка `""` считаются истинными.

```lua
if "" then
    mesh.log("Пустая строка в Lua считается true")
end
```

### Строки

Строки можно писать в одинарных или двойных кавычках. Склейка строк выполняется оператором `..`.

```lua
local name = "Alpha"
local message = 'node: ' .. name

mesh.log(string.lower(message))
mesh.log(string.format("battery %d%%", 87))
```

### Условия

```lua
if msg.outgoing then
    return
elseif msg.text == "ping" then
    mesh.chat.bot_reply(msg, "pong")
else
    mesh.log("другое сообщение")
end
```

Полезные операторы: `==`, `~=`, `<`, `<=`, `>`, `>=`, `and`, `or`, `not`.

### Таблицы

Таблица — главная структура данных Lua. Она работает и как массив, и как словарь. Индексы массивов начинаются с `1`.

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

### Циклы

Для массивов используйте `ipairs`, для словарей — `pairs`.

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

### Функции

Функции объявляются через `function ... end`. `return` завершает функцию и возвращает значение.

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

### Проверка `nil`

Поля событий могут отсутствовать. Перед обращением к вложенным полям проверяйте значение на `nil`.

```lua
function on_traceroute(event)
    if event.route and event.route.route_ids then
        mesh.log(table.concat(event.route.route_ids, " -> "))
    else
        mesh.log("маршрут не получен")
    end
end
```

### Обработка ошибок

`pcall` запускает функцию защищённо: ошибка не прерывает весь скрипт, а возвращается вторым значением.

```lua
local ok, result = pcall(function()
    return mesh.curl.get("https://example.com/api/status")
end)

if not ok then
    mesh.log("ошибка: " .. tostring(result))
elseif result.ok then
    mesh.log(result.body)
end
```

### Что важно помнить

- В конце блоков всегда нужен `end`.
- Не нужны точки с запятой.
- Массивы начинаются с индекса `1`, не с `0`.
- Неравенство записывается как `~=`, а не `!=`.
- Для объединения строк используется `..`, а не `+`.
- `require`, файловая система и системные API отключены sandbox-ограничениями MeshApp.

## Базовые функции

| Функция | Назначение |
|---------|------------|
| `print(...)` | Пишет строку в вывод скрипта; аргументы объединяются табуляцией |
| `mesh.log(text)` | Пишет `text` в вывод скрипта |
| `mesh.now()` | Возвращает Unix time в секундах |
| `mesh.sleep(seconds)` | Блокирующая пауза от `0` до `10` секунд |
| `mesh.owner()` | Возвращает таблицу `{ node_id, node_num, connection_id }` текущего узла |
| `mesh.command()` | Возвращает текущую команду или пустую таблицу вне командного запуска |

## Callback-функции

| Callback | Когда вызывается |
|----------|------------------|
| `on_message(msg)` | Для каждого нового входящего или исходящего сообщения, пока скрипт запущен |
| `on_command(command)` | При запуске automation-бота из чата |
| `on_node_selected(event)` | После выбора или отмены выбора ноды через `mesh.ui.pick_node(...)` |
| `on_traceroute(event)` | После результата `mesh.traceroute.request(...)` |
| `on_node_info(event)` | После результата `mesh.nodeinfo.request(...)` |

## `mesh.chat`

`chat_type` принимает значения `channel` или `dm`. Для канала `chat_key` — строковый индекс канала, например `"0"`. Для личного диалога `chat_key` — node ID собеседника, например `"!abcdef12"`.

| Функция | Возврат | Назначение |
|---------|---------|------------|
| `mesh.chat.send_channel(channel, text[, reply_id])` | `message` или `nil` | Отправляет сообщение в канал по радио |
| `mesh.chat.send_dm(node_id, text[, reply_id])` | `message` или `nil` | Отправляет личное сообщение по радио |
| `mesh.chat.reply(msg, text)` | `message` или `nil` | Отправляет ответ в тот же чат, где пришло `msg` |
| `mesh.chat.bot_message(chat_type, chat_key, text)` | `message` | Добавляет локальное сообщение бота в историю, не отправляя его по радио |
| `mesh.chat.bot_reply(msg, text)` | `message` | Добавляет локальный ответ бота к сообщению |
| `mesh.chat.bot_notice(chat_type, chat_key, text[, options])` | `true` | Показывает временное UI-сообщение бота без записи в историю |
| `mesh.chat.recent(chat_type, chat_key[, limit])` | список `message` | Возвращает последние сообщения, `limit` от 1 до 200, по умолчанию 20 |
| `mesh.chat.nodes()` | список `node` | Возвращает известные ноды текущего подключения |
| `mesh.chat.channels()` | список `channel` | Возвращает известные каналы текущего подключения |

## `mesh.kv`

KV-хранилище изолировано по скрипту и сохраняется в локальной БД приложения.

| Функция | Возврат | Назначение |
|---------|---------|------------|
| `mesh.kv.get(key)` | строка или `nil` | Читает значение |
| `mesh.kv.set(key, value)` | `true` | Сохраняет значение как строку; `nil` записывает пустое значение |
| `mesh.kv.delete(key)` | boolean | Удаляет ключ |
| `mesh.kv.list()` | table | Возвращает все ключи скрипта |
| `mesh.kv.clear()` | `true` | Очищает KV-хранилище скрипта |

## `mesh.curl`

HTTP(S)-запросы выполняются встроенным Java HTTP-клиентом. Доступ к локальным, приватным, link-local и multicast-адресам запрещён. URL с credentials также запрещены.

| Функция | Возврат | Назначение |
|---------|---------|------------|
| `mesh.curl.get(url[, options])` | `curl.response` | Выполняет GET-запрос |
| `mesh.curl.request(options)` | `curl.response` | Выполняет запрос с параметрами |

Поля `options`: `url`, `method`, `body`, `headers`, `timeout_ms`, `max_bytes`. Разрешены методы `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`. `timeout_ms` ограничен диапазоном 100..5000, `max_bytes` — до 1 МБ.

Поля ответа: `ok`, `status`, `url`, `body`, `headers`, `truncated`, `error`.

## `mesh.ui`

| Функция | Возврат | Назначение |
|---------|---------|------------|
| `mesh.ui.pick_node(options)` | `request_id` | Открывает выбор ноды и позже вызывает `on_node_selected(event)` |

Поля `options`: `name`, `prompt`, `query`, `chat_type`, `chat_key`. Вместо таблицы можно передать строку, она будет использована как `query`.

Поля `on_node_selected(event)`: `type`, `source`, `name`, `request_id`, `status`, `selected`, `cancelled`, `chat_type`, `chat_key`, `node`.

## `mesh.traceroute`

| Функция | Возврат | Назначение |
|---------|---------|------------|
| `mesh.traceroute.request(target[, options])` | `request_id` | Запускает traceroute до ноды и позже вызывает `on_traceroute(event)` |

`target` может быть node ID строкой (`"!abcdef12"`), числовым `node_num` или таблицей ноды с полями `node_num`, `node_id`, `long_name`, `short_name`.

Поля `options`: `name`, `chat_type`, `chat_key`, `target_name`, `timeout_seconds`. Таймаут ограничен диапазоном 1..600 секунд, по умолчанию 360.

Поля `on_traceroute(event)`: `type`, `source`, `name`, `request_id`, `status`, `ok`, `timeout`, `error`, `target_node_num`, `target_node_id`, `target_name`, `response_from_node_num`, `response_from_node_id`, `chat_type`, `chat_key`, `route`.

Если `event.route` есть, в нём доступны поля `route`, `route_back`, `route_ids`, `route_back_ids`, `snr_towards`, `snr_back`.

## `mesh.nodeinfo`

| Функция | Возврат | Назначение |
|---------|---------|------------|
| `mesh.nodeinfo.request(target[, options])` | `request_id` | Запрашивает NodeInfo и позже вызывает `on_node_info(event)` |

`target` и `options` такие же, как у `mesh.traceroute.request(...)`, но таймаут по умолчанию 60 секунд.

Поля `on_node_info(event)`: `type`, `source`, `name`, `request_id`, `status`, `ok`, `timeout`, `cached`, `error`, `target_node_num`, `target_node_id`, `target_name`, `chat_type`, `chat_key`, `node`.

## Поля объектов

`message`: `db_id`, `packet_id`, `chat_type`, `chat_key`, `from`, `to`, `channel`, `channel_name`, `channel_role`, `text`, `reply_id`, `reply_text`, `timestamp`, `outgoing`, `system`, `status`, `sender_name`, `hop_start`, `hop_limit`, `hops`, `rx_rssi`, `rx_snr`.

`node`: `node_num`, `node_id`, `long_name`, `short_name`, `last_heard`, `battery`, `externally_powered`, `voltage`, `snr`, `latitude`, `longitude`, `altitude`, `hops_away`, `channel`, `role`, `hw_model`, `public_key`, `uptime_seconds`, `channel_utilization`, `air_util_tx`, `temperature`, `relative_humidity`, `barometric_pressure`, `unmessagable`, `licensed`.

`channel`: `index`, `role`, `name`.

`command`: `type`, `source`, `name`, `request_id`, `chat_type`, `chat_key`, `handle`, `text`, `arguments`, `argument_tokens`.

## Примеры

### Локальный ping/test-бот с задержкой

```lua
math.randomseed(math.floor(mesh.now() * 1000))

function on_message(msg)
    if msg.outgoing or msg.system or not msg.text then
        return
    end

    local text = string.lower(msg.text)
    if text == "ping" or text == "test" or text == "пинг" then
        mesh.sleep(math.random(1, 5))
        mesh.chat.bot_reply(msg, "pong")
    end
end
```

### Ответ в mesh-сеть

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

### Automation-бот с выбором ноды и traceroute

```lua
function on_command(command)
    mesh.ui.pick_node({
        name = "trace_target",
        prompt = "Выберите ноду для traceroute",
        query = command.arguments,
        chat_type = command.chat_type,
        chat_key = command.chat_key
    })
end

function on_node_selected(event)
    if event.cancelled then
        mesh.chat.bot_message(event.chat_type, event.chat_key, "Traceroute отменён")
        return
    end

    mesh.chat.bot_notice(event.chat_type, event.chat_key, "Запускаю traceroute...", {
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
            "Traceroute не выполнен: " .. tostring(event.status))
        return
    end

    local hops = event.target_node_id
    if event.route and event.route.route_ids then
        local route = table.concat(event.route.route_ids, " -> ")
        if route ~= "" then
            hops = route
        end
    end

    mesh.chat.bot_message(event.chat_type, event.chat_key, "Маршрут: " .. hops)
end
```

### Запрос NodeInfo по первому аргументу команды

```lua
function on_command(command)
    local target = command.argument_tokens[1]
    if not target then
        mesh.chat.bot_message(command.chat_type, command.chat_key, "Укажите node ID")
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
            "NodeInfo недоступен: " .. tostring(event.status))
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

### KV-хранилище и внешний HTTP-запрос

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
