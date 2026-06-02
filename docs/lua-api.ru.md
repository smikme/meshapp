# Lua API для скриптов MeshApp

**Язык:** Русский | [English](lua-api.md)

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
| `on_canvas_event(event)` | После события плавающего Canvas-окна: мышь, клавиатура, resize, open/close |
| `on_canvas_frame(event)` | По таймеру Canvas-окна, если задан `fps` или вызван `mesh.canvas.set_fps(...)` |

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

Поля `options`:

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `url` | string | HTTP(S)-URL запроса. В `mesh.curl.get(url[, options])` обычно передаётся первым аргументом, в `mesh.curl.request(options)` обязателен в таблице |
| `method` | string | HTTP-метод. По умолчанию `GET`; разрешены `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE` |
| `body` | string или `nil` | Тело запроса в UTF-8 для методов с телом; для `GET` и `HEAD` не отправляется |
| `headers` | table<string,string> | Заголовки запроса. Имена проверяются, служебные заголовки вроде `Host` и `Content-Length` запрещены |
| `timeout_ms` | number | Таймаут запроса в миллисекундах. Ограничен диапазоном `100..5000`, по умолчанию `1500` |
| `max_bytes` | number | Максимум байт тела ответа. Ограничен `0..1048576`, по умолчанию `262144`; при обрезке ответа `response.truncated = true` |

Поля `curl.response`:

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `ok` | boolean | `true`, если HTTP-статус находится в диапазоне `200..299`; иначе `false` |
| `status` | number | HTTP-статус ответа. Возвращает `0`, если запрос завершился без HTTP-ответа |
| `url` | string или `nil` | Итоговый URL после редиректов; `nil` при ошибке выполнения запроса |
| `body` | string | Тело ответа, декодированное как UTF-8 и ограниченное `max_bytes`; при ошибке пустая строка |
| `headers` | table<string,string> | Заголовки ответа: имена в нижнем регистре, несколько значений объединены через `, `; при ошибке пустая таблица |
| `truncated` | boolean | `true`, если тело ответа было обрезано по `max_bytes` |
| `error` | string или `nil` | Текст ошибки выполнения запроса; для обычного HTTP-ответа, включая не-2xx статус, возвращает `nil` |

## `mesh.ui`

| Функция | Возврат | Назначение |
|---------|---------|------------|
| `mesh.ui.pick_node(options)` | `request_id` | Открывает выбор ноды и позже вызывает `on_node_selected(event)` |

Поля `options`. Вместо таблицы можно передать строку, она будет использована как `query`.

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `name` | string | Произвольное имя запроса, которое вернётся в `event.name` |
| `prompt` | string | Текст заголовка или подсказки в окне выбора ноды |
| `query` | string | Начальная строка поиска/фильтра нод |
| `chat_type` | string | Контекст чата: `channel`, `dm` или пустая строка; возвращается в событии |
| `chat_key` | string | Ключ чата: индекс канала строкой или node ID собеседника; возвращается в событии |

Поля `on_node_selected(event)`:

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `type` | string | Тип события; возвращает `ui_result` |
| `source` | string | API-источник события; обычно `mesh.ui.pick_node` |
| `name` | string | Имя исходного запроса из `options.name` |
| `request_id` | string | ID запроса, который вернул `mesh.ui.pick_node(...)` |
| `status` | string | Результат выбора: `selected` или `cancelled` |
| `selected` | boolean | `true`, если пользователь выбрал ноду |
| `cancelled` | boolean | `true`, если выбор отменён |
| `chat_type` | string | Контекст чата из исходных `options` |
| `chat_key` | string | Ключ чата из исходных `options` |
| `node` | `node` или `nil` | Выбранная нода; `nil`, если выбор отменён |

## `mesh.canvas`

`mesh.canvas` открывает плавающее изменяемое окно без системной рамки рядом с основным окном приложения. Окно не модальное, не добавляется в боковое меню и существует только пока его держит Lua-скрипт.

| Функция | Возврат | Назначение |
|---------|---------|------------|
| `mesh.canvas.open(options)` | `true` | Показывает Canvas-окно |
| `mesh.canvas.close()` | `true` | Закрывает Canvas-окно |
| `mesh.canvas.set_fps(fps)` | `true` | Включает/меняет частоту `on_canvas_frame(event)`, `0` выключает |
| `mesh.canvas.size()` | `{width, height}` | Возвращает текущий размер холста |
| `mesh.canvas.mouse()` | `canvas.mouse` | Возвращает текущее состояние мыши |
| `mesh.canvas.keys()` | `canvas.keys` | Возвращает текущее состояние клавиатуры |
| `mesh.canvas.clear([color])` | `true` | Очищает холст или заливает цветом |
| `mesh.canvas.set_fill(color)` | `true` | Устанавливает цвет заливки |
| `mesh.canvas.set_stroke(color)` | `true` | Устанавливает цвет линии |
| `mesh.canvas.set_line_width(width)` | `true` | Устанавливает толщину линии |
| `mesh.canvas.set_font(size[, family[, weight]])` | `true` | Устанавливает шрифт текста |
| `mesh.canvas.save()` / `mesh.canvas.restore()` | `true` | Сохраняет и восстанавливает состояние рисования |
| `mesh.canvas.translate(x, y)` | `true` | Смещает систему координат |
| `mesh.canvas.rotate(degrees)` | `true` | Поворачивает систему координат |
| `mesh.canvas.scale(x[, y])` | `true` | Масштабирует систему координат |
| `mesh.canvas.fill_rect(x, y, w, h[, color])` | `true` | Рисует залитый прямоугольник |
| `mesh.canvas.stroke_rect(x, y, w, h[, color[, line_width]])` | `true` | Рисует контур прямоугольника |
| `mesh.canvas.fill_round_rect(x, y, w, h, radius[, color])` | `true` | Рисует залитый скруглённый прямоугольник |
| `mesh.canvas.stroke_round_rect(x, y, w, h, radius[, color[, line_width]])` | `true` | Рисует контур скруглённого прямоугольника |
| `mesh.canvas.line(x1, y1, x2, y2[, color[, line_width]])` | `true` | Рисует линию |
| `mesh.canvas.fill_circle(x, y, radius[, color])` | `true` | Рисует залитый круг |
| `mesh.canvas.stroke_circle(x, y, radius[, color[, line_width]])` | `true` | Рисует контур круга |
| `mesh.canvas.fill_ellipse(x, y, w, h[, color])` | `true` | Рисует залитый эллипс |
| `mesh.canvas.stroke_ellipse(x, y, w, h[, color[, line_width]])` | `true` | Рисует контур эллипса |
| `mesh.canvas.fill_polygon(points[, color])` | `true` | Рисует залитый многоугольник |
| `mesh.canvas.stroke_polygon(points[, color[, line_width]])` | `true` | Рисует контур многоугольника |
| `mesh.canvas.polyline(points[, color[, line_width]])` | `true` | Рисует ломаную линию |
| `mesh.canvas.fill_text(text, x, y[, color])` | `true` | Рисует текст |
| `mesh.canvas.stroke_text(text, x, y[, color[, line_width]])` | `true` | Рисует контур текста |

Поля `options` для `mesh.canvas.open(options)`:

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `title` | string | Заголовок Canvas-окна. Если в `open` передана строка вместо таблицы, она используется как `title` |
| `width` | number | Начальная ширина холста в пикселях. По умолчанию `640`; окно ограничивает размер диапазоном `260..1920` |
| `height` | number | Начальная высота холста в пикселях. По умолчанию `360`; окно ограничивает размер диапазоном `220..1080` |
| `background` | string | Начальный цвет фона в формате JavaFX/CSS; пустая строка не заливает фон |
| `resizable` | boolean | `true`, если холст должен масштабироваться вместе с окном; по умолчанию `true` |
| `fps` | number | Частота вызова `on_canvas_frame(event)`. `0` выключает таймер; окно ограничивает значение диапазоном `0..120` |

По умолчанию Canvas масштабируется вместе с плавающим окном (`resizable = true`); размер меняется перетаскиванием краёв окна. Кнопка в правом верхнем углу закрывает окно после подтверждения. Двойной клик по верхней зоне переноса сворачивает окно в полупрозрачный квадрат с иконкой скрипта; двойной клик по квадрату восстанавливает прежний размер.

Цвет можно передать строкой JavaFX/CSS (`"#ffcc00"`, `"rgba(255,0,0,0.5)"`, `"white"`) или таблицей `{r, g, b, a}`. Компоненты `r/g/b/a` принимаются в диапазоне `0..1` или `0..255`.

`points` можно передать как плоский список `{x1, y1, x2, y2, ...}` или как список точек `{{x=10, y=10}, {x=40, y=20}}`.

Поля `on_canvas_event(event)` и `on_canvas_frame(event)`:

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `type` | string | Тип события Canvas; для кадрового callback возвращает `frame` |
| `source` | string | Источник события; возвращает `mesh.canvas` |
| `x`, `y` | number | Координаты мыши внутри холста для mouse/scroll-событий; для остальных событий `0` |
| `screen_x`, `screen_y` | number | Экранные координаты мыши для mouse/scroll-событий; для остальных событий `0` |
| `button` | string | Кнопка мыши: `primary`, `middle`, `secondary`, `back`, `forward` или пустая строка |
| `click_count` | number | Количество кликов в mouse-событии |
| `primary`, `middle`, `secondary` | boolean | Состояние соответствующих кнопок мыши в момент события |
| `wheel_delta_x`, `wheel_delta_y` | number | Горизонтальная и вертикальная прокрутка для `scroll`; иначе `0` |
| `code` | string | Код клавиши для keyboard-событий, например `Left` или `Enter` |
| `key` | string | Отображаемое имя клавиши для keyboard-событий |
| `text` | string | Текст/символ keyboard-события, когда он есть |
| `shift`, `ctrl`, `alt`, `meta` | boolean | Состояние модификаторов клавиатуры |
| `width`, `height` | number | Текущий размер холста в пикселях |
| `time` | number | Unix time события в секундах |
| `dt` | number | Для `on_canvas_frame` — секунд с прошлого кадра; для остальных событий `0` |

Значения `event.type`: `opened`, `closed`, `resized`, `mouse_moved`, `mouse_pressed`, `mouse_released`, `mouse_clicked`, `mouse_dragged`, `mouse_entered`, `mouse_exited`, `scroll`, `key_pressed`, `key_released`, `key_typed`.

## `mesh.traceroute`

| Функция | Возврат | Назначение |
|---------|---------|------------|
| `mesh.traceroute.request(target[, options])` | `request_id` | Запускает traceroute до ноды и позже вызывает `on_traceroute(event)` |

`target` может быть node ID строкой (`"!abcdef12"`), числовым `node_num` или таблицей ноды с полями `node_num`, `node_id`, `long_name`, `short_name`.

Поля `options`:

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `name` | string | Произвольное имя запроса, которое вернётся в `event.name` |
| `chat_type` | string | Контекст чата: `channel`, `dm` или пустая строка; возвращается в событии |
| `chat_key` | string | Ключ чата: индекс канала строкой или node ID собеседника; возвращается в событии |
| `target_name` | string | Отображаемое имя целевой ноды; если не задано, берётся из `target` |
| `timeout_seconds` | number | Таймаут ожидания результата в секундах. Ограничен диапазоном `1..600`, по умолчанию `360` |

Поля `on_traceroute(event)`:

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `type` | string | Тип события; возвращает `traceroute_result` |
| `source` | string | API-источник события; обычно `mesh.traceroute.request` |
| `name` | string | Имя исходного запроса из `options.name` |
| `request_id` | string | ID запроса, который вернул `mesh.traceroute.request(...)` |
| `status` | string | Статус результата: `ok`, `timeout` или `error` |
| `ok` | boolean | `true`, если traceroute успешно получил маршрут |
| `timeout` | boolean | `true`, если истёк `timeout_seconds` |
| `error` | string или `nil` | Текст ошибки отправки/выполнения запроса; `nil` без ошибки |
| `target_node_num` | number | Числовой Meshtastic ID целевой ноды (`uint32`) |
| `target_node_id` | string | Node ID целевой ноды в виде `!abcdef12` |
| `target_name` | string | Отображаемое имя целевой ноды |
| `response_from_node_num` | number или `nil` | Числовой ID ноды, от которой пришёл ответ; `nil`, если ответа не было |
| `response_from_node_id` | string или `nil` | Node ID ноды, от которой пришёл ответ; `nil`, если ответа не было |
| `chat_type` | string | Контекст чата из исходных `options` |
| `chat_key` | string | Ключ чата из исходных `options` |
| `route` | `route.discovery` или `nil` | Таблица маршрута; `nil` при timeout/error или если маршрут не получен |

Поля `route.discovery`:

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `route` | список number | Прямой маршрут как список `node_num` |
| `route_back` | список number | Обратный маршрут как список `node_num` |
| `route_ids` | список string | Прямой маршрут как список node ID вида `!abcdef12` |
| `route_back_ids` | список string | Обратный маршрут как список node ID вида `!abcdef12` |
| `snr_towards` | список number | SNR по прямому маршруту в dB |
| `snr_back` | список number | SNR по обратному маршруту в dB |

## `mesh.nodeinfo`

| Функция | Возврат | Назначение |
|---------|---------|------------|
| `mesh.nodeinfo.request(target[, options])` | `request_id` | Запрашивает NodeInfo и позже вызывает `on_node_info(event)` |

`target` и `options` такие же, как у `mesh.traceroute.request(...)`, но таймаут по умолчанию 60 секунд.

Поля `on_node_info(event)`:

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `type` | string | Тип события; возвращает `nodeinfo_result` |
| `source` | string | API-источник события; обычно `mesh.nodeinfo.request` |
| `name` | string | Имя исходного запроса из `options.name` |
| `request_id` | string | ID запроса, который вернул `mesh.nodeinfo.request(...)` |
| `status` | string | Статус результата: `ok`, `timeout` или `error` |
| `ok` | boolean | `true`, если NodeInfo успешно получен |
| `timeout` | boolean | `true`, если истёк `timeout_seconds` |
| `cached` | boolean | `true`, если при timeout/error возвращена локально известная нода |
| `error` | string или `nil` | Текст ошибки запроса; `nil` без ошибки |
| `target_node_num` | number | Числовой Meshtastic ID целевой ноды (`uint32`) |
| `target_node_id` | string | Node ID целевой ноды в виде `!abcdef12` |
| `target_name` | string | Отображаемое имя целевой ноды |
| `chat_type` | string | Контекст чата из исходных `options` |
| `chat_key` | string | Ключ чата из исходных `options` |
| `node` | `node` или `nil` | Таблица ноды; `nil`, если данных о ноде нет |

## Поля объектов

Все объекты ниже возвращаются как Lua-таблицы. Поля с типом `... или nil` могут отсутствовать или возвращать `nil`, если MeshApp ещё не получил соответствующие данные.

### `owner`

Возвращается из `mesh.owner()`.

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `node_id` | string или `nil` | Node ID текущей собственной ноды, например `!abcdef12` |
| `node_num` | number или `nil` | Числовой Meshtastic ID текущей собственной ноды (`uint32`) |
| `connection_id` | string или `nil` | ID активного подключения, в контексте которого запущен скрипт |

### `message`

Возвращается в `on_message(msg)`, `mesh.chat.recent(...)` и функциях отправки/бот-сообщений.

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `db_id` | number | Локальный ID сообщения в базе MeshApp; может быть `0` для ещё не сохранённых сообщений |
| `packet_id` | number | ID mesh-пакета; используется как `reply_id` при ответе |
| `chat_type` | string или `nil` | Тип чата: `channel` или `dm` |
| `chat_key` | string или `nil` | Ключ чата: индекс канала строкой или node ID собеседника |
| `from` | string или `nil` | Node ID отправителя |
| `to` | string или `nil` | Node ID получателя или broadcast-адрес |
| `channel` | number | Индекс канала сообщения |
| `channel_name` | string или `nil` | Имя канала, если оно известно |
| `channel_role` | string или `nil` | Роль канала из Meshtastic, например `PRIMARY` или `SECONDARY` |
| `text` | string или `nil` | Текст сообщения |
| `reply_id` | number | `packet_id` сообщения, на которое был ответ; `0`, если это не ответ |
| `reply_text` | string или `nil` | Текст цитируемого сообщения, если известен |
| `timestamp` | number | Unix time сообщения в секундах |
| `outgoing` | boolean | `true`, если сообщение отправлено текущей нодой/клиентом |
| `system` | boolean | `true` для системных и локальных bot-сообщений |
| `status` | string или `nil` | Статус доставки/обработки сообщения, если известен |
| `sender_name` | string или `nil` | Отображаемое имя отправителя |
| `hop_start` | number | Исходный hop limit пакета |
| `hop_limit` | number | Оставшийся hop limit пакета |
| `hops` | number или `nil` | Сколько hop прошёл пакет; `nil`, если данных hop нет |
| `rx_rssi` | number | RSSI принятого пакета |
| `rx_snr` | number | SNR принятого пакета в dB |

### `node`

Возвращается из `mesh.chat.nodes()`, `mesh.ui.pick_node(...)`, `mesh.nodeinfo.request(...)` и может передаваться как `target` для запросов.

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `node_num` | number | Числовой Meshtastic ID ноды (`uint32`) |
| `node_id` | string или `nil` | Node ID в виде `!abcdef12` |
| `long_name` | string или `nil` | Полное имя пользователя/ноды |
| `short_name` | string или `nil` | Короткое имя пользователя/ноды |
| `last_heard` | number | Unix time последнего известного пакета от ноды |
| `battery` | number | Уровень батареи в процентах, если он передан устройством |
| `externally_powered` | boolean | `true`, если нода сообщает внешнее питание |
| `voltage` | number | Напряжение питания/батареи в вольтах |
| `snr` | number | Последний известный SNR связи с нодой в dB |
| `latitude` | number | Широта в десятичных градусах |
| `longitude` | number | Долгота в десятичных градусах |
| `altitude` | number | Высота в метрах |
| `hops_away` | number или `nil` | Оценка количества hop до ноды; `nil`, если неизвестно |
| `channel` | number | Индекс канала, связанный с последними данными ноды |
| `role` | string или `nil` | Роль устройства Meshtastic, если известна |
| `hw_model` | string или `nil` | Модель аппаратной платформы, если известна |
| `public_key` | string или `nil` | Публичный ключ ноды в hex-строке |
| `uptime_seconds` | number | Uptime ноды в секундах |
| `channel_utilization` | number | Channel utilization в процентах |
| `air_util_tx` | number | Air utilization TX в процентах |
| `temperature` | number | Температура из telemetry в градусах Celsius |
| `relative_humidity` | number | Относительная влажность из telemetry в процентах |
| `barometric_pressure` | number | Барометрическое давление из telemetry |
| `unmessagable` | boolean | `true`, если приложение считает ноду недоступной для сообщений |
| `licensed` | boolean или `nil` | Признак licensed-режима ноды; `nil`, если поле не получено |

### `channel`

Возвращается из `mesh.chat.channels()`.

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `index` | number | Индекс канала |
| `role` | string или `nil` | Роль канала из Meshtastic |
| `name` | string или `nil` | Имя канала из настроек канала |

### `command`

Передаётся в `on_command(command)` и возвращается из `mesh.command()` во время командного запуска.

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `type` | string | Тип команды; для chat automation возвращает `chat_command` |
| `source` | string | Источник команды; для запуска из чата возвращает `chat` |
| `name` | string или `nil` | Имя команды, обычно совпадает с `handle` |
| `request_id` | string | ID конкретного запуска команды |
| `chat_type` | string или `nil` | Тип чата, где вызвана команда: `channel` или `dm` |
| `chat_key` | string или `nil` | Ключ чата, где вызвана команда |
| `handle` | string или `nil` | Командный handle, например `@tracebot` |
| `text` | string или `nil` | Полный текст пользовательской команды |
| `arguments` | string или `nil` | Строка аргументов после command handle |
| `argument_tokens` | список string | Аргументы, разбитые парсером команды; индексация Lua начинается с `1` |

### `canvas.size`

Возвращается из `mesh.canvas.size()`.

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `width` | number | Текущая ширина холста в пикселях |
| `height` | number | Текущая высота холста в пикселях |

### `canvas.mouse`

Возвращается из `mesh.canvas.mouse()`.

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `x`, `y` | number | Последние координаты мыши внутри холста |
| `screen_x`, `screen_y` | number | Последние экранные координаты мыши |
| `over` | boolean | `true`, если курсор находится над холстом |
| `pressed` | boolean | `true`, если нажата любая основная кнопка мыши |
| `primary`, `middle`, `secondary` | boolean | Состояние соответствующих кнопок мыши |
| `button` | string | Последняя кнопка события: `primary`, `middle`, `secondary`, `back`, `forward` или пустая строка |
| `click_count` | number | Количество кликов в последнем mouse-событии |
| `wheel_delta_x`, `wheel_delta_y` | number | Последняя горизонтальная и вертикальная прокрутка |
| `last_type` | string | Последний тип mouse/scroll-события |
| `time` | number | Unix time последнего mouse/scroll-события в секундах |

### `canvas.keys`

Возвращается из `mesh.canvas.keys()`. Для быстрого опроса клавиши также доступны как булевы поля по имени кода, например `mesh.canvas.keys().Left`.

| Поле | Тип | Назначение / возвращаемое значение |
|------|-----|------------------------------------|
| `pressed` | список string | Список кодов клавиш, которые сейчас нажаты |
| `last_type` | string | Последний тип keyboard-события: `key_pressed`, `key_released` или `key_typed` |
| `last_code` | string | Код последней клавиши, например `Left` или `Enter` |
| `last_key` | string | Отображаемое имя последней клавиши |
| `text` | string | Текст/символ последнего keyboard-события, когда он есть |
| `shift`, `ctrl`, `alt`, `meta` | boolean | Состояние модификаторов клавиатуры |
| `time` | number | Unix time последнего keyboard-события в секундах |

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

### Встроенное меню на Canvas

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
