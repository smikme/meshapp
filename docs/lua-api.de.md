# MeshApp-Skript-Lua-API

**Sprache:** [Русский](lua-api.ru.md) | [English](lua-api.md) | Deutsch

MeshApp führt Benutzer-Lua-Skripte in einer LuaJ-Sandbox aus. Skripte können den Namespace `mesh`, zentrale Lua-Funktionen, die Bibliotheken `string`, `table`, `math`, `coroutine`, `bit32` sowie Funktionen wie `pairs`, `ipairs`, `pcall`, `tonumber` und `tostring` verwenden. Unsichere globale APIs sind deaktiviert: `io`, `os`, `debug`, `package`, `require`, `dofile`, `loadfile`, `luajava`, `collectgarbage`, `module`.

Ein normales Skript läuft einmal und beendet sich, sofern es nicht `on_message(msg)` deklariert oder ausstehende asynchrone Operationen hat. Wenn `on_message(msg)` deklariert ist, bleibt das Skript aktiv und empfängt neue Nachrichten. Befehlsbots verwenden `on_command(command)`, und UI-/Anfrageergebnisse kommen in separaten Callback-Funktionen an.

Ausführungslimits:

- initialer Skriptlauf: bis zu 3 Sekunden
- Callback: bis zu 1,5 Sekunden
- Ausgabe von `print` / `mesh.log`: bis zu 64 KB pro Lauf
- `mesh.sleep(seconds)` akzeptiert eine Verzögerung von `0` bis `10` Sekunden und verlängert die aktuelle Ausführungsfrist

## Lua-Kurzreferenz

Lua ist eine kleine dynamische Sprache. In MeshApp besteht Code meist aus Callback-Funktionen wie `on_message(msg)` und API-Aufrufen über `mesh.*`.

### Kommentare

```lua
-- Einzeiliger Kommentar

--[[
Mehrzeiliger Kommentar.
Nützlich, um einen Codeblock vorübergehend zu deaktivieren.
]]
```

### Variablen und Typen

Variablen benötigen keine Typdeklaration. Verwenden Sie `local`, damit eine Variable nicht global wird und nicht länger als nötig zwischen Callback-Aufrufen weiterlebt.

```lua
local text = "hello"
local count = 3
local enabled = true
local missing = nil

mesh.log(type(text))   -- string
mesh.log(type(count))  -- number
```

Wichtige Typen: `nil`, `boolean`, `number`, `string`, `table`, `function`. `nil` bedeutet, dass kein Wert vorhanden ist. In Bedingungen sind nur `false` und `nil` falsch; die Zahl `0` und ein leerer String `""` sind wahr.

```lua
if "" then
    mesh.log("Ein leerer String ist in Lua wahr")
end
```

### Strings

Strings können einfache oder doppelte Anführungszeichen verwenden. Strings werden mit dem Operator `..` verkettet.

```lua
local name = "Alpha"
local message = 'node: ' .. name

mesh.log(string.lower(message))
mesh.log(string.format("Akku %d%%", 87))
```

### Bedingungen

```lua
if msg.outgoing then
    return
elseif msg.text == "ping" then
    mesh.chat.bot_reply(msg, "pong")
else
    mesh.log("andere Nachricht")
end
```

Nützliche Operatoren: `==`, `~=`, `<`, `<=`, `>`, `>=`, `and`, `or`, `not`.

### Tabellen

Eine Tabelle ist die zentrale Datenstruktur in Lua. Sie funktioniert sowohl als Array als auch als Dictionary. Array-Indizes beginnen bei `1`.

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

### Schleifen

Verwenden Sie `ipairs` für Arrays und `pairs` für Dictionaries.

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

### Funktionen

Funktionen werden mit `function ... end` deklariert. `return` beendet die Funktion und gibt einen Wert zurück.

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

### Prüfung auf `nil`

Event-Felder können fehlen. Prüfen Sie Werte auf `nil`, bevor Sie auf verschachtelte Felder zugreifen.

```lua
function on_traceroute(event)
    if event.route and event.route.route_ids then
        mesh.log(table.concat(event.route.route_ids, " -> "))
    else
        mesh.log("Route wurde nicht empfangen")
    end
end
```

### Fehlerbehandlung

`pcall` führt eine Funktion im geschützten Modus aus: Ein Fehler stoppt nicht das gesamte Skript und wird als zweiter Wert zurückgegeben.

```lua
local ok, result = pcall(function()
    return mesh.curl.get("https://example.com/api/status")
end)

if not ok then
    mesh.log("Fehler: " .. tostring(result))
elseif result.ok then
    mesh.log(result.body)
end
```

### Wichtig zu merken

- Blöcke brauchen immer ein abschließendes `end`.
- Semikolons sind nicht nötig.
- Arrays beginnen bei Index `1`, nicht bei `0`.
- Ungleichheit wird als `~=` geschrieben, nicht als `!=`.
- String-Verkettung verwendet `..`, nicht `+`.
- `require`, Dateisystem- und System-APIs sind in der MeshApp-Sandbox deaktiviert.

## Basisfunktionen

| Funktion | Zweck |
|----------|---------|
| `print(...)` | Schreibt eine Zeile in die Skriptausgabe; Argumente werden mit Tabs verbunden |
| `mesh.log(text)` | Schreibt `text` in die Skriptausgabe |
| `mesh.now()` | Gibt Unix-Zeit in Sekunden zurück |
| `mesh.localtime([epoch_seconds])` | Gibt eine lokale Datums-/Zeit-Tabelle für den aktuellen Moment oder Zeitstempel zurück |
| `mesh.date([epoch_seconds])` | Gibt das lokale Datum nach den regionalen Systemeinstellungen formatiert zurück |
| `mesh.time([epoch_seconds])` | Gibt die lokale Zeit nach den regionalen Systemeinstellungen formatiert zurück |
| `mesh.datetime([epoch_seconds])` | Gibt lokales Datum und lokale Zeit nach den regionalen Systemeinstellungen formatiert zurück |
| `mesh.iso_date([epoch_seconds])` | Gibt einen stabilen lokalen Datumsstring zurück: `YYYY-MM-DD` |
| `mesh.iso_time([epoch_seconds])` | Gibt einen stabilen lokalen Zeitstring zurück: `HH:MM:SS` |
| `mesh.iso_datetime([epoch_seconds])` | Gibt einen stabilen lokalen Datums-/Zeitstring zurück: `YYYY-MM-DD HH:MM:SS` |
| `mesh.sleep(seconds)` | Blockierende Pause von `0` bis `10` Sekunden |
| `mesh.json.*` | Hilfsfunktionen zum Kodieren/Dekodieren von JSON |
| `mesh.timer.*` | Von MeshApp verwaltete Timer, die `on_timer(event)` aufrufen |
| `mesh.owner()` | Gibt `{ node_id, node_num, connection_id }` für den aktuellen Knoten zurück |
| `mesh.command()` | Gibt den aktuellen Befehl zurück oder außerhalb eines Befehlsstarts eine leere Tabelle |

### Zeit und Datum

Alle Zeit-Helfer verwenden die Systemzeitzone der Anwendung. Die kurzen Helfer
`mesh.date(...)`, `mesh.time(...)` und `mesh.datetime(...)` sind für
menschenlesbaren Text gedacht und folgen dem regionalen Systemformat,
einschließlich 12-/24-Stunden-Zeit und Sekunden. Verwenden Sie `mesh.iso_date(...)`,
`mesh.iso_time(...)` oder `mesh.iso_datetime(...)`, wenn ein Skript stabile Strings
für Sortierung, Speicherschlüssel, Dateinamen oder Vergleiche benötigt.

`mesh.localtime([epoch_seconds])` gibt eine Tabelle mit numerischen Feldern sowie
lokalisierten und stabilen String-Formen zurück:

| Feld | Bedeutung |
|-------|---------|
| `year`, `month`, `day` | Lokales Kalenderdatum |
| `hour`, `minute`, `second` | Lokale Uhrzeit |
| `min`, `sec` | Aliasse für `minute` und `second` |
| `weekday` | ISO-Wochentag, Montag ist `1`, Sonntag ist `7` |
| `wday` | Lua-Wochentag, Sonntag ist `1`, Samstag ist `7` |
| `yearday`, `yday` | Tag des Jahres |
| `timezone`, `zone` | ID der Systemzeitzone, zum Beispiel `Europe/Moscow` |
| `offset`, `offset_seconds` | UTC-Offset als String und Sekunden |
| `epoch` | Unix-Zeit in Sekunden |
| `date`, `time`, `datetime` | Lokalisierte Anzeigestrings |
| `iso_date`, `iso_time`, `iso_datetime` | Stabile lokale Strings |
| `iso` | ISO-Datum/-Zeit mit Offset einschließlich UTC-Offset |

```lua
local t = mesh.localtime()
mesh.log(t.datetime .. " " .. t.timezone)

local sent_at = mesh.datetime(msg.timestamp)
local key = "daily:" .. mesh.iso_date()
```

## Callbacks

| Callback | Aufrufzeitpunkt |
|----------|-------------------|
| `on_message(msg)` | Für jede neue eingehende oder ausgehende Nachricht, während das Skript läuft |
| `on_command(command)` | Wenn ein Automationsbot aus dem Chat gestartet wird |
| `on_extension_open(event)` | Wenn ein Erweiterungsskript über die linke Werkzeugleiste geöffnet wird |
| `on_form_event(event)` | Nach einer Aktion oder Wertänderung einer über `mesh.form.*` erstellten Komponente |
| `on_node_selected(event)` | Nach Auswahl oder Abbruch einer Knotenauswahl über `mesh.ui.pick_node(...)` |
| `on_traceroute(event)` | Nachdem `mesh.traceroute.request(...)` ein Ergebnis liefert |
| `on_node_info(event)` | Nachdem `mesh.nodeinfo.request(...)` ein Ergebnis liefert |
| `on_admin(event)` | Nachdem Remote-Administrationsanfragen über `mesh.admin.*` Fortschritt oder ein Ergebnis liefern |
| `on_timer(event)` | Nachdem ein von MeshApp verwalteter Timer aus `mesh.timer.*` ausgelöst wurde |
| `on_canvas_event(event)` | Nach einem Event in einem schwebenden Canvas-Fenster: Maus, Tastatur, Größenänderung, Öffnen/Schließen |
| `on_canvas_frame(event)` | Beim Canvas-Fenstertimer, wenn `fps` gesetzt ist oder `mesh.canvas.set_fps(...)` aufgerufen wurde |

## `mesh.timer`

Timer werden von MeshApp verwaltet, nicht durch eine Lua-`while true`-Schleife.
Ein Skript bleibt aktiv, solange es aktive Timer hat. Timer-Callbacks werden
seriell im Lua-Executor des Skripts zugestellt; wenn ein wiederholender Timer
erneut auslöst, während der vorherige Callback noch in der Warteschlange ist,
überspringt MeshApp den zusätzlichen Tick, statt Callbacks parallel auszuführen
oder sie in einem Schub nachzuholen.

| Funktion | Rückgabe | Zweck |
|----------|--------|---------|
| `mesh.timer.after(seconds[, options])` | `timer_id` | Ruft `on_timer(event)` einmal nach `seconds` auf |
| `mesh.timer.every(seconds[, options])` | `timer_id` | Ruft `on_timer(event)` wiederholt auf |
| `mesh.timer.cancel(timer_id)` | boolean | Bricht einen aktiven Timer ab |
| `mesh.timer.cancel_all()` | number | Bricht alle Timer ab und gibt die Anzahl zurück |

`seconds` muss zwischen `0.1` und `604800` Sekunden liegen. `options` kann enthalten:

| Option | Typ | Zweck |
|--------|------|---------|
| `name` | string | Vom Aufrufer definierter Timername, der nach `event.name` kopiert wird |
| `immediate` | boolean | Bei `every` einmal sofort vor dem ersten Intervall auslösen |
| `align` | string | Bei `every`: `interval` oder `wall`, Standard `interval` |

`align = "interval"` läuft alle N Sekunden ab dem vorherigen geplanten Tick.
`align = "wall"` richtet sich an lokalen Uhrgrenzen aus. Zum Beispiel läuft `600`
Sekunden lokal um `HH:00`, `HH:10`, `HH:20` und so weiter.

Timer-Event-Felder:

| Feld | Bedeutung |
|-------|---------|
| `type` | Immer `timer` |
| `source` | API-Quelle, zum Beispiel `mesh.timer.every` |
| `id`, `timer_id` | Timer-ID |
| `name` | Name aus den Optionen oder leerer String |
| `interval_seconds`, `seconds` | Timerintervall oder Verzögerung |
| `repeating` | Ob sich der Timer wiederholt |
| `align` | `interval` oder `wall` |
| `count` | Anzahl zugestellter Callbacks für diesen Timer |
| `scheduled_epoch`, `actual_epoch` | Geplante und tatsächliche Unix-Zeit in Sekunden |
| `drift_seconds` | `actual_epoch - scheduled_epoch` |
| `time` | Tabelle `mesh.localtime(actual_epoch)` |

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

`chat_type` akzeptiert `channel` oder `dm`. Für einen Kanal ist `chat_key` der Kanalindex als String, zum Beispiel `"0"`. Für eine Direktnachricht ist `chat_key` die Knoten-ID des Gegenübers, zum Beispiel `"!abcdef12"`.

| Funktion | Rückgabe | Zweck |
|----------|--------|---------|
| `mesh.chat.send_channel(channel, text[, reply_id])` | `message` oder `nil` | Sendet eine Funknachricht an einen Kanal |
| `mesh.chat.send_dm(node_id, text[, reply_id])` | `message` oder `nil` | Sendet eine Funk-Direktnachricht |
| `mesh.chat.reply(msg, text)` | `message` oder `nil` | Sendet eine Antwort in denselben Chat, in dem `msg` angekommen ist |
| `mesh.chat.bot_message(chat_type, chat_key, text)` | `message` | Fügt dem Verlauf eine lokale Botnachricht hinzu, ohne sie per Funk zu senden |
| `mesh.chat.bot_reply(msg, text)` | `message` | Fügt einer Nachricht eine lokale Botantwort hinzu |
| `mesh.chat.bot_notice(chat_type, chat_key, text[, options])` | `true` | Zeigt eine temporäre Bot-UI-Nachricht, ohne sie in den Verlauf zu schreiben |
| `mesh.chat.recent(chat_type, chat_key[, limit])` | Liste von `message` | Gibt aktuelle Nachrichten zurück, `limit` von 1 bis 200, Standard 20 |
| `mesh.chat.nodes()` | Liste von `node` | Gibt bekannte Knoten der aktuellen Verbindung zurück |
| `mesh.chat.channels()` | Liste von `channel` | Gibt bekannte Kanäle der aktuellen Verbindung zurück |

## `mesh.kv`

Der KV-Speicher ist pro Skript isoliert und wird in der lokalen Anwendungsdatenbank dauerhaft gespeichert.

| Funktion | Rückgabe | Zweck |
|----------|--------|---------|
| `mesh.kv.get(key)` | string oder `nil` | Liest einen Wert |
| `mesh.kv.set(key, value)` | `true` | Speichert einen Wert als String; `nil` speichert einen leeren Wert |
| `mesh.kv.delete(key)` | boolean | Löscht einen Schlüssel |
| `mesh.kv.list()` | table | Gibt alle Skriptschlüssel zurück |
| `mesh.kv.clear()` | `true` | Leert den KV-Speicher des Skripts |

## `mesh.json`

JSON-Helfer konvertieren zwischen JSON-Text und normalen Lua-Werten. Objekte werden
Tabellen mit String-Schlüsseln; Arrays werden Tabellen mit Indizes ab `1`. JSON `null`
wird durch `mesh.json.null` dargestellt, weil Lua `nil` Tabellenfelder entfernt.

| Funktion | Rückgabe | Zweck |
|----------|--------|---------|
| `mesh.json.decode(text)` | value | Parst JSON-Text oder wirft einen Fehler |
| `mesh.json.try_decode(text)` | `value, nil` oder `nil, error` | Parst JSON, ohne das Skript zu stoppen |
| `mesh.json.encode(value[, options])` | string | Kodiert einen Lua-Wert als kompaktes JSON |
| `mesh.json.pretty(value)` | string | Kodiert einen Lua-Wert als formatiertes JSON |
| `mesh.json.array(table)` | table | Markiert eine Lua-Tabelle als JSON-Array, auch ein leeres Array |
| `mesh.json.is_null(value)` | boolean | Gibt `true` für `mesh.json.null` zurück |
| `mesh.json.null` | value | Sentinel-Wert für JSON null |

Unterstützte Wertzuordnung:

| JSON | Lua |
|------|-----|
| object | Tabelle mit String-Schlüsseln |
| array | Tabelle mit Indizes `1..n` |
| string | string |
| number | number |
| boolean | boolean |
| null | `mesh.json.null` |

Beim Kodieren wird eine Tabelle mit zusammenhängenden Integer-Schlüsseln `1..n`
zu einem JSON-Array. Eine Tabelle mit String-Schlüsseln wird zu einem JSON-Objekt.
Gemischte Tabellen oder Arrays mit Lücken lösen einen Fehler aus. Eine leere Tabelle
wird als `{}` kodiert, sofern sie nicht mit `mesh.json.array({})` erstellt oder markiert
wurde. `mesh.json.encode(value, true)` oder `mesh.json.encode(value, { pretty = true })`
erzeugt formatiertes JSON.

Die Eingabe beim Parsen ist auf 1 MB begrenzt. Verschachtelung ist auf 64 Ebenen
begrenzt, und ein Objekt oder Array kann bis zu 50.000 Elemente enthalten.

```lua
local response = mesh.curl.get("https://example.com/api/status")
local data, err = mesh.json.try_decode(response.body)
if not data then
    mesh.log("Ungültiges JSON: " .. err)
    return
end

if not mesh.json.is_null(data.status) then
    mesh.log("Status: " .. data.status)
end

local body = mesh.json.encode({
    at = mesh.iso_datetime(),
    values = mesh.json.array({ 1, 2, 3 }),
    empty = mesh.json.array({})
})
```

## `mesh.curl`

HTTP(S)-Anfragen werden vom eingebauten Java-HTTP-Client ausgeführt. Zugriff auf lokale, private, Link-Local- und Multicast-Adressen ist blockiert. URLs mit Zugangsdaten sind ebenfalls blockiert.

| Funktion | Rückgabe | Zweck |
|----------|--------|---------|
| `mesh.curl.get(url[, options])` | `curl.response` | Führt eine GET-Anfrage aus |
| `mesh.curl.request(options)` | `curl.response` | Führt eine Anfrage mit Parametern aus |

`options`-Felder:

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `url` | string | HTTP(S)-Anfrage-URL. In `mesh.curl.get(url[, options])` wird sie normalerweise als erstes Argument übergeben; in `mesh.curl.request(options)` ist sie in der Tabelle erforderlich |
| `method` | string | HTTP-Methode. Standard ist `GET`; erlaubt sind `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE` |
| `body` | string oder `nil` | UTF-8-Anfragebody für Methoden mit Body; wird bei `GET` und `HEAD` nicht gesendet |
| `headers` | table<string,string> | Anfrage-Header. Headernamen werden validiert, und Service-Header wie `Host` und `Content-Length` sind blockiert |
| `timeout_ms` | number | Anfrage-Timeout in Millisekunden. Begrenzt auf `100..5000`, Standard `1500` |
| `max_bytes` | number | Maximale Bytes des Antwortbodys. Begrenzt auf `0..1048576`, Standard `262144`; beim Erreichen des Limits gilt `response.truncated = true` |

`curl.response`-Felder:

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `ok` | boolean | `true`, wenn der HTTP-Status in `200..299` liegt; sonst `false` |
| `status` | number | HTTP-Antwortstatus. Gibt `0` zurück, wenn die Anfrage ohne HTTP-Antwort endete |
| `url` | string oder `nil` | Finale URL nach Weiterleitungen; `nil` bei Ausführungsfehler |
| `body` | string | Antwortbody als UTF-8 dekodiert und durch `max_bytes` begrenzt; leerer String bei Fehler |
| `headers` | table<string,string> | Antwort-Header: Namen in Kleinbuchstaben, mehrere Werte mit `, ` verbunden; leere Tabelle bei Fehler |
| `truncated` | boolean | `true`, wenn der Antwortbody durch `max_bytes` abgeschnitten wurde |
| `error` | string oder `nil` | Fehlertext der Anfrageausführung; bei normaler HTTP-Antwort, auch mit Nicht-2xx-Status, `nil` |

## `mesh.ui`

| Funktion | Rückgabe | Zweck |
|----------|--------|---------|
| `mesh.ui.pick_node(options)` | `request_id` | Öffnet die Knotenauswahl und ruft später `on_node_selected(event)` auf |

`options`-Felder. Statt einer Tabelle kann ein String übergeben werden; er wird als `query` verwendet.

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `name` | string | Eigener Anfragename, der später als `event.name` zurückgegeben wird |
| `prompt` | string | Titel oder Hinweistext im Knotenauswahldialog |
| `query` | string | Anfangstext für Knotensuche/-filter |
| `chat_type` | string | Chat-Kontext: `channel`, `dm` oder leerer String; wird im Event zurückgegeben |
| `chat_key` | string | Chat-Schlüssel: Kanalindex als String oder Knoten-ID des Gegenübers; wird im Event zurückgegeben |

`on_node_selected(event)`-Felder:

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `type` | string | Event-Typ; gibt `ui_result` zurück |
| `source` | string | Event-API-Quelle; normalerweise `mesh.ui.pick_node` |
| `name` | string | Ursprünglicher Anfragename aus `options.name` |
| `request_id` | string | Von `mesh.ui.pick_node(...)` zurückgegebene Anfrage-ID |
| `status` | string | Auswahlergebnis: `selected` oder `cancelled` |
| `selected` | boolean | `true`, wenn der Benutzer einen Knoten ausgewählt hat |
| `cancelled` | boolean | `true`, wenn der Dialog abgebrochen wurde |
| `chat_type` | string | Chat-Kontext aus den ursprünglichen `options` |
| `chat_key` | string | Chat-Schlüssel aus den ursprünglichen `options` |
| `node` | `node` oder `nil` | Ausgewählter Knoten; `nil`, wenn der Dialog abgebrochen wurde |

## `mesh.form`

`mesh.form` ist nur für Skripte vom Typ „Extension“ verfügbar. Ein Erweiterungsskript fügt der linken Werkzeugleiste der Anwendung eine Schaltfläche hinzu und steuert einen eingebetteten MeshApp-Abschnitt, kein separates Fenster.

Form-Komponenten geben keine Lua-Objekte mit eigenen Methoden zurück. Ein Skript erstellt eine Komponente mit `mesh.form.add(...)`, erhält oder setzt ihre `id` und steuert sie anschließend über `mesh.form.set(id, ...)`, `mesh.form.value(id)` und `mesh.form.remove(id)`.

| Funktion | Rückgabe | Zweck |
|----------|--------|---------|
| `mesh.form.show([options])` | `true` | Zeigt den eingebetteten Erweiterungsabschnitt; wenn `options.text` übergeben wird, ändert sich der Titel |
| `mesh.form.set_title(title)` | `true` | Ändert den Abschnittstitel; ein leerer Titel fällt auf den Skriptnamen zurück |
| `mesh.form.clear()` | `true` | Entfernt alle erstellten Komponenten |
| `mesh.form.add(options)` | `component_id` | Fügt eine Komponente hinzu und gibt ihre ID zurück; statt einer Tabelle kann ein Typstring übergeben werden, zum Beispiel `"separator"` |
| `mesh.form.set(id, options)` | `true` | Aktualisiert Komponenteneigenschaften; Typ, ID und Parent einer bestehenden Komponente werden nicht geändert |
| `mesh.form.remove(id)` | `true` | Entfernt eine Komponente |
| `mesh.form.value(id)` | value oder `nil` | Gibt den aktuellen Komponentenwert zurück |

### Allgemeine `options`-Felder

`options.type` ist für `mesh.form.add(...)` erforderlich. Felder, die eine Komponente nicht unterstützt, werden ignoriert.

| Feld | Typ | Zweck |
|-------|------|---------|
| `type` | string | Zu erstellender Komponententyp: `label`, `button`, `text_field`, `password_field`, `text_area`, `checkbox`, `toggle_switch`, `combo_box`, `segmented_control`, `list_view`, `slider`, `progress_bar`, `ring_progress`, `line_chart`, `area_chart`, `bar_chart`, `separator`, `spacer`, `message`, `tile`, `card`, `vbox`, `hbox`, `split_pane`, `scroll_pane` |
| `id` | string | Stabile Komponenten-ID. Wenn sie fehlt, erzeugt MeshApp eine und gibt sie aus `add(...)` zurück |
| `parent` | string | ID eines Containers `card`, `vbox`, `hbox`, `split_pane` oder `scroll_pane`. Wenn sie fehlt, wird die Komponente zur Formularwurzel hinzugefügt |
| `text` | string | Beschriftung oder Titel für `label`, `button`, `checkbox`, `toggle_switch`, `message` oder `tile` |
| `prompt` | string | Platzhalter für `text_field`, `password_field` und `text_area`; bei `combo_box` dient er als Hinweis für den leeren Wert |
| `value` | string/number/boolean | Aktueller Komponentenwert. `progress_bar` und `ring_progress` verwenden `0..1`; `slider` wird auf `min..max` begrenzt; `message` und `tile` verwenden ihn als Beschreibung |
| `selected` | boolean | Alias für `value` von `checkbox` und `toggle_switch`, wenn `value` fehlt |
| `items` | array<string> | Optionen für `combo_box`, `segmented_control` und `list_view` |
| `min`, `max` | number | Bereich des `slider`; Standard `0..100`. Bei Diagrammen setzen beide Werte zusammen den Y-Achsenbereich |
| `series` | array<table> | Datenreihen für `line_chart`, `area_chart` und `bar_chart`; jede Reihe unterstützt `name`, `color` und `points` |
| `x_label`, `y_label` | string | Achsenbeschriftungen für Diagrammkomponenten |
| `x_type` | string | X-Achsenmodus des Diagramms; verwenden Sie `time`, `timestamp` oder `epoch`, um Unix-Sekunden als lokale Zeit zu formatieren |
| `chart_type` | string | Bei `type = "chart"` Auswahl von `line`, `area` oder `bar` |
| `legend` | boolean | Zeigt oder versteckt die Diagrammlegende; standardmäßig wird sie angezeigt, wenn ein Diagramm mehr als eine Reihe hat |
| `symbols` | boolean | Zeigt Punktsymbole in `line_chart` und `area_chart`; Standard `false` |
| `orientation` | string | `horizontal` oder `vertical` für `separator`, `spacer` und `split_pane` |
| `width`, `height` | number | Bevorzugte Komponentengröße in Pixeln |
| `min_width`, `min_height` | number | Minimale Komponentengröße in Pixeln |
| `max_width`, `max_height` | number | Maximale Komponentengröße in Pixeln |
| `grow` | string/boolean | Layout-Wachstum: `always`, `sometimes`, `never`, `true`/`false`. Gilt in `vbox`, `hbox` und für Kinder von `split_pane` |
| `rows` | number | Sichtbare Zeilenanzahl für `text_area` |
| `wrap` | boolean | Zeilenumbruch für `label` und `text_area` |
| `read_only` | boolean | Macht `text_field`, `password_field` und `text_area` nicht editierbar, Auswahl/Kopieren bleibt verfügbar |
| `monospace` | boolean | Aktiviert eine Monospace-Schrift für Textkomponenten; `style = "monospace"` wird ebenfalls akzeptiert |
| `disabled` | boolean | Deaktiviert oder aktiviert die Komponente |
| `visible` | boolean | Zeigt oder versteckt die Komponente; eine versteckte Komponente belegt keinen Layoutplatz |
| `style` | string | `button` unterstützt beim Erstellen `accent`; Textkomponenten unterstützen `monospace` |

`mesh.form.set(id, options)` kann unterstützte Eigenschaften wie `text`, `prompt`, `value`, `items`, `min`, `max`, Größen, `grow`, `read_only`, `wrap`, `monospace`, `disabled` und `visible` aktualisieren. `type`, `id` und `parent` sind Erstellungsfelder; entfernen und erstellen Sie eine Komponente neu, um sie zu ändern.

Diagramm-`series` ist eine Liste von Reihentabellen. Jede Reihe hat `name`, optional `color` als CSS-Farbe und `points`. Ein Punkt kann `{ x = 1700000000, y = 21.5 }`, `{ timestamp = 1700000000, value = 21.5 }`, `{ 1700000000, 21.5 }` oder eine einfache Zahl sein; dann wird der X-Wert zum Punktindex.

### Komponententypen

| `options.type` | Erzeugt | Haupteigenschaften | `mesh.form.value(id)` | Events |
|----------------|---------|-----------------|------------------------|--------|
| `label` | Textlabel mit Umbruch | `id`, `parent`, `text`, `value`, `disabled`, `visible` | Aktueller Text | Keine |
| `button` | Button | `id`, `parent`, `text`, `style="accent"`, `disabled`, `visible` | `nil` | `action` bei Klick |
| `text_field` | Einzeilige Texteingabe | `id`, `parent`, `value`, `prompt`, `read_only`, `monospace`, `disabled`, `visible` | String | `change` bei Textänderung, `action` bei Enter |
| `password_field` | Passworteingabe | `id`, `parent`, `value`, `prompt`, `read_only`, `disabled`, `visible` | String | `change` bei Textänderung, `action` bei Enter |
| `text_area` | Mehrzeilige Texteingabe | `id`, `parent`, `value`, `prompt`, `rows`, `wrap`, `read_only`, `monospace`, `disabled`, `visible` | String | `change` bei Textänderung |
| `checkbox` | Checkbox mit Text | `id`, `parent`, `text`, `value`/`selected`, `disabled`, `visible` | Boolean | `change` beim Umschalten |
| `toggle_switch` | AtlantaFX-Umschalter | `id`, `parent`, `text`, `value`/`selected`, `disabled`, `visible` | Boolean | `change` beim Umschalten |
| `combo_box` | Dropdown-Auswahl | `id`, `parent`, `items`, `value`, `prompt`, `disabled`, `visible` | Ausgewählter String oder `nil` | `change` bei Auswahl |
| `segmented_control` | Segmentierte AtlantaFX-Button-Gruppe | `id`, `parent`, `items`, `value`, `disabled`, `visible` | Ausgewählter String oder `nil` | `change` bei Auswahl |
| `list_view` | String-Liste | `id`, `parent`, `items`, `value`, `disabled`, `visible` | Ausgewählter String oder `nil` | `change` bei Auswahl |
| `slider` | Numerischer Slider | `id`, `parent`, `min`, `max`, `value`, `disabled`, `visible` | Number | `change` beim Bewegen |
| `progress_bar` | Fortschrittsanzeige | `id`, `parent`, `value`, `disabled`, `visible` | Number `0..1` | Keine |
| `ring_progress` | AtlantaFX-Ringfortschritt | `id`, `parent`, `value`, `width`, `height`, `disabled`, `visible` | Number `0..1` | Keine |
| `line_chart` | Numerisches Liniendiagramm | `id`, `parent`, `text`, `series`, `x_label`, `y_label`, `x_type`, `min`, `max`, `legend`, `symbols`, Größen | `nil` | Keine |
| `area_chart` | Numerisches Flächendiagramm | `id`, `parent`, `text`, `series`, `x_label`, `y_label`, `x_type`, `min`, `max`, `legend`, `symbols`, Größen | `nil` | Keine |
| `bar_chart` | Numerisches Balkendiagramm | `id`, `parent`, `text`, `series`, `x_label`, `y_label`, `x_type`, `min`, `max`, `legend`, Größen | `nil` | Keine |
| `separator` | Trenner | `id`, `parent`, `orientation`, `disabled`, `visible` | `nil` | Keine |
| `spacer` | Leerer AtlantaFX-Abstandshalter | `id`, `parent`, `orientation`, `value`, `grow`, `disabled`, `visible` | `nil` | Keine |
| `message` | AtlantaFX-Nachricht mit Titel und Beschreibung | `id`, `parent`, `text`, `value`, `disabled`, `visible` | Beschreibung | `action`, `close` |
| `tile` | AtlantaFX-Tile mit Titel und Beschreibung | `id`, `parent`, `text`, `value`, `disabled`, `visible` | Beschreibung | `action` |
| `card` | Kartencontainer im App-Stil | `id`, `parent`, `disabled`, `visible` | `nil` | Keine |
| `vbox` | Vertikaler Container | `id`, `parent`, `grow`, `disabled`, `visible` | `nil` | Keine |
| `hbox` | Horizontaler Container | `id`, `parent`, `grow`, `disabled`, `visible` | `nil` | Keine |
| `split_pane` | Geteilte Ansicht mit mehreren Teilbereichen | `id`, `parent`, `orientation`, `grow`, `disabled`, `visible` | `nil` | Keine |
| `scroll_pane` | Scrollbarer Container | `id`, `parent`, `grow`, `disabled`, `visible` | `nil` | Keine |

`card`, `vbox`, `hbox`, `split_pane` und `scroll_pane` sind Layoutcontainer. Um eine Komponente in einen Container zu legen, setzen Sie `parent = "container_id"` oder verwenden Sie die von `mesh.form.add(...)` zurückgegebene ID. Bei `split_pane` wird jedes Kind zu einem separaten Teilbereich; bei `scroll_pane` wird normalerweise ein einzelner `vbox`- oder `hbox`-Container hinzugefügt.

`on_extension_open(event)`-Felder:

| Feld | Typ | Zweck |
|-------|------|---------|
| `type` | string | Immer `extension_open` |
| `source` | string | Immer `mesh.extension` |
| `script_id` | number | Aktuelle Skript-ID |
| `name` | string | Aktueller Skriptname |

`on_form_event(event)`-Felder:

| Feld | Typ | Zweck |
|-------|------|---------|
| `type` | string | `action` für Buttons/Enter in einem Feld, `change` für Wertänderungen, `close`, wenn eine `message` geschlossen wird |
| `source` | string | Immer `mesh.form` |
| `component_id`, `id` | string | Komponenten-ID |
| `value` | string/number/boolean/nil | Aktueller Komponentenwert |
| `text` | string oder `nil` | Textdarstellung des Werts |

Beispiel für ein Erweiterungsformular:

```lua
function on_extension_open(event)
    mesh.form.set_title("Diagnose")
    mesh.form.clear()

    local card = mesh.form.add({ type = "card", id = "main" })
    mesh.form.add({ type = "label", id = "status", parent = card, text = "Bereit" })
    mesh.form.add({ type = "text_field", id = "node", parent = card, prompt = "Knoten-ID" })
    mesh.form.add({
        type = "combo_box",
        id = "mode",
        parent = card,
        items = { "status", "trace", "admin" },
        value = "status"
    })
    mesh.form.add({ type = "checkbox", id = "verbose", parent = card, text = "Ausführlich", value = true })
    mesh.form.add({ type = "button", id = "run", parent = card, text = "Start", style = "accent" })
end

function on_form_event(event)
    if event.id == "run" and event.type == "action" then
        local node = mesh.form.value("node")
        local mode = mesh.form.value("mode")
        local verbose = mesh.form.value("verbose")
        mesh.form.set("status", {
            text = "Start: " .. tostring(mode) .. " / " .. tostring(node) .. " / verbose=" .. tostring(verbose)
        })
    elseif event.id == "mode" and event.type == "change" then
        mesh.form.set("status", { text = "Modus: " .. tostring(event.value) })
    end
end
```

## `mesh.canvas`

`mesh.canvas` öffnet ein schwebendes, größenveränderbares, rahmenloses Fenster neben dem Hauptfenster der Anwendung. Das Fenster ist nicht modal, wird nicht zum Seitenmenü hinzugefügt und existiert nur, solange es dem Lua-Skript gehört.

| Funktion | Rückgabe | Zweck |
|----------|--------|---------|
| `mesh.canvas.open(options)` | `true` | Zeigt das Canvas-Fenster |
| `mesh.canvas.close()` | `true` | Schließt das Canvas-Fenster |
| `mesh.canvas.set_fps(fps)` | `true` | Aktiviert/ändert die Frequenz von `on_canvas_frame(event)`; `0` deaktiviert sie |
| `mesh.canvas.size()` | `{width, height}` | Gibt die aktuelle Canvas-Größe zurück |
| `mesh.canvas.mouse()` | `canvas.mouse` | Gibt den aktuellen Mauszustand zurück |
| `mesh.canvas.keys()` | `canvas.keys` | Gibt den aktuellen Tastaturzustand zurück |
| `mesh.canvas.clear([color])` | `true` | Leert die Canvas oder füllt sie mit einer Farbe |
| `mesh.canvas.set_fill(color)` | `true` | Setzt die Füllfarbe |
| `mesh.canvas.set_stroke(color)` | `true` | Setzt die Linienfarbe |
| `mesh.canvas.set_line_width(width)` | `true` | Setzt die Linienbreite |
| `mesh.canvas.set_font(size[, family[, weight]])` | `true` | Setzt die Textschrift |
| `mesh.canvas.save()` / `mesh.canvas.restore()` | `true` | Speichert und stellt den Zeichenzustand wieder her |
| `mesh.canvas.translate(x, y)` | `true` | Verschiebt das Koordinatensystem |
| `mesh.canvas.rotate(degrees)` | `true` | Dreht das Koordinatensystem |
| `mesh.canvas.scale(x[, y])` | `true` | Skaliert das Koordinatensystem |
| `mesh.canvas.fill_rect(x, y, w, h[, color])` | `true` | Zeichnet ein gefülltes Rechteck |
| `mesh.canvas.stroke_rect(x, y, w, h[, color[, line_width]])` | `true` | Zeichnet den Umriss eines Rechtecks |
| `mesh.canvas.fill_round_rect(x, y, w, h, radius[, color])` | `true` | Zeichnet ein gefülltes abgerundetes Rechteck |
| `mesh.canvas.stroke_round_rect(x, y, w, h, radius[, color[, line_width]])` | `true` | Zeichnet den Umriss eines abgerundeten Rechtecks |
| `mesh.canvas.line(x1, y1, x2, y2[, color[, line_width]])` | `true` | Zeichnet eine Linie |
| `mesh.canvas.fill_circle(x, y, radius[, color])` | `true` | Zeichnet einen gefüllten Kreis |
| `mesh.canvas.stroke_circle(x, y, radius[, color[, line_width]])` | `true` | Zeichnet den Umriss eines Kreises |
| `mesh.canvas.fill_ellipse(x, y, w, h[, color])` | `true` | Zeichnet eine gefüllte Ellipse |
| `mesh.canvas.stroke_ellipse(x, y, w, h[, color[, line_width]])` | `true` | Zeichnet den Umriss einer Ellipse |
| `mesh.canvas.fill_polygon(points[, color])` | `true` | Zeichnet ein gefülltes Polygon |
| `mesh.canvas.stroke_polygon(points[, color[, line_width]])` | `true` | Zeichnet den Umriss eines Polygons |
| `mesh.canvas.polyline(points[, color[, line_width]])` | `true` | Zeichnet eine Polylinie |
| `mesh.canvas.fill_text(text, x, y[, color])` | `true` | Zeichnet Text |
| `mesh.canvas.stroke_text(text, x, y[, color[, line_width]])` | `true` | Zeichnet Textumrisse |

`options`-Felder für `mesh.canvas.open(options)`:

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `title` | string | Titel des Canvas-Fensters. Wenn `open` statt einer Tabelle einen String erhält, wird er als `title` verwendet |
| `width` | number | Anfangsbreite der Canvas in Pixeln. Standard `640`; das Fenster begrenzt die Größe auf `260..1920` |
| `height` | number | Anfangshöhe der Canvas in Pixeln. Standard `360`; das Fenster begrenzt die Größe auf `220..1080` |
| `background` | string | Anfangs-Hintergrundfarbe im JavaFX-/CSS-Format; leerer String füllt den Hintergrund nicht |
| `resizable` | boolean | `true`, wenn die Canvas mit dem Fenster skaliert werden soll; Standard `true` |
| `fps` | number | Frequenz von `on_canvas_frame(event)`. `0` deaktiviert den Timer; das Fenster begrenzt den Wert auf `0..120` |

Standardmäßig skaliert die Canvas mit dem schwebenden Fenster (`resizable = true`); die Größe ändern Sie durch Ziehen der Fensterränder. Die Schaltfläche oben rechts schließt das Fenster nach Bestätigung. Ein Doppelklick auf die obere Ziehzone minimiert das Fenster zu einem halbtransparenten Quadrat mit dem Skriptsymbol; ein Doppelklick auf das Quadrat stellt die vorherige Größe wieder her.

Farben können als JavaFX-/CSS-String (`"#ffcc00"`, `"rgba(255,0,0,0.5)"`, `"white"`) oder als Tabelle `{r, g, b, a}` übergeben werden. `r/g/b/a`-Komponenten werden im Bereich `0..1` oder `0..255` akzeptiert.

`points` kann eine flache Liste `{x1, y1, x2, y2, ...}` oder eine Punktliste `{{x=10, y=10}, {x=40, y=20}}` sein.

`on_canvas_event(event)`- und `on_canvas_frame(event)`-Felder:

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `type` | string | Canvas-Event-Typ; Frame-Callbacks geben `frame` zurück |
| `source` | string | Event-Quelle; gibt `mesh.canvas` zurück |
| `x`, `y` | number | Mauskoordinaten innerhalb der Canvas für Maus-/Scroll-Events; `0` für andere Events |
| `screen_x`, `screen_y` | number | Bildschirm-Mauskoordinaten für Maus-/Scroll-Events; `0` für andere Events |
| `button` | string | Maustaste: `primary`, `middle`, `secondary`, `back`, `forward` oder leerer String |
| `click_count` | number | Klickanzahl in einem Maus-Event |
| `primary`, `middle`, `secondary` | boolean | Zustand der entsprechenden Maustasten zum Event-Zeitpunkt |
| `wheel_delta_x`, `wheel_delta_y` | number | Horizontales und vertikales Scroll-Delta für `scroll`; sonst `0` |
| `code` | string | Tastencode für Tastatur-Events, zum Beispiel `Left` oder `Enter` |
| `key` | string | Anzeigename der Taste für Tastatur-Events |
| `text` | string | Text/Zeichen des Tastatur-Events, falls vorhanden |
| `shift`, `ctrl`, `alt`, `meta` | boolean | Zustand der Tastaturmodifikatoren |
| `width`, `height` | number | Aktuelle Canvas-Größe in Pixeln |
| `time` | number | Unix-Zeit des Events in Sekunden |
| `dt` | number | Bei `on_canvas_frame` Sekunden seit dem vorherigen Frame; bei anderen Events `0` |

`event.type`-Werte: `opened`, `closed`, `resized`, `mouse_moved`, `mouse_pressed`, `mouse_released`, `mouse_clicked`, `mouse_dragged`, `mouse_entered`, `mouse_exited`, `scroll`, `key_pressed`, `key_released`, `key_typed`.

## `mesh.traceroute`

| Funktion | Rückgabe | Zweck |
|----------|--------|---------|
| `mesh.traceroute.request(target[, options])` | `request_id` | Startet Traceroute zu einem Knoten und ruft später `on_traceroute(event)` auf |

`target` kann ein Knoten-ID-String (`"!abcdef12"`), numerisches `node_num` oder eine Knotentabelle mit `node_num`, `node_id`, `long_name`, `short_name` sein.

`options`-Felder:

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `name` | string | Eigener Anfragename, der später als `event.name` zurückgegeben wird |
| `chat_type` | string | Chat-Kontext: `channel`, `dm` oder leerer String; wird im Event zurückgegeben |
| `chat_key` | string | Chat-Schlüssel: Kanalindex als String oder Knoten-ID des Gegenübers; wird im Event zurückgegeben |
| `target_name` | string | Anzeigename des Zielknotens; wenn er fehlt, wird er aus `target` abgeleitet |
| `timeout_seconds` | number | Warte-Timeout für das Ergebnis in Sekunden. Begrenzt auf `1..600`, Standard `360` |

`on_traceroute(event)`-Felder:

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `type` | string | Event-Typ; gibt `traceroute_result` zurück |
| `source` | string | Event-API-Quelle; normalerweise `mesh.traceroute.request` |
| `name` | string | Ursprünglicher Anfragename aus `options.name` |
| `request_id` | string | Von `mesh.traceroute.request(...)` zurückgegebene Anfrage-ID |
| `status` | string | Ergebnisstatus: `ok`, `timeout` oder `error` |
| `ok` | boolean | `true`, wenn Traceroute erfolgreich eine Route empfangen hat |
| `timeout` | boolean | `true`, wenn `timeout_seconds` abgelaufen ist |
| `error` | string oder `nil` | Sende-/Ausführungsfehlertext; `nil`, wenn kein Fehler vorliegt |
| `target_node_num` | number | Numerische Meshtastic-ID des Zielknotens (`uint32`) |
| `target_node_id` | string | Zielknoten-ID in der Form `!abcdef12` |
| `target_name` | string | Anzeigename des Zielknotens |
| `response_from_node_num` | number oder `nil` | Numerische ID des antwortenden Knotens; `nil`, wenn keine Antwort kam |
| `response_from_node_id` | string oder `nil` | Knoten-ID des antwortenden Knotens; `nil`, wenn keine Antwort kam |
| `chat_type` | string | Chat-Kontext aus den ursprünglichen `options` |
| `chat_key` | string | Chat-Schlüssel aus den ursprünglichen `options` |
| `route` | `route.discovery` oder `nil` | Routentabelle; `nil` bei Timeout/Fehler oder wenn keine Route empfangen wurde |

`route.discovery`-Felder:

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `route` | Liste von number | Hinroute als `node_num`-Werte |
| `route_back` | Liste von number | Rückroute als `node_num`-Werte |
| `route_ids` | Liste von string | Hinroute als Knoten-IDs in der Form `!abcdef12` |
| `route_back_ids` | Liste von string | Rückroute als Knoten-IDs in der Form `!abcdef12` |
| `snr_towards` | Liste von number | SNR-Werte der Hinroute in dB |
| `snr_back` | Liste von number | SNR-Werte der Rückroute in dB |

## `mesh.nodeinfo`

| Funktion | Rückgabe | Zweck |
|----------|--------|---------|
| `mesh.nodeinfo.request(target[, options])` | `request_id` | Fragt NodeInfo an und ruft später `on_node_info(event)` auf |

`target` und `options` sind wie bei `mesh.traceroute.request(...)`, aber das Standard-Timeout beträgt 60 Sekunden.

`on_node_info(event)`-Felder:

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `type` | string | Event-Typ; gibt `nodeinfo_result` zurück |
| `source` | string | Event-API-Quelle; normalerweise `mesh.nodeinfo.request` |
| `name` | string | Ursprünglicher Anfragename aus `options.name` |
| `request_id` | string | Von `mesh.nodeinfo.request(...)` zurückgegebene Anfrage-ID |
| `status` | string | Ergebnisstatus: `ok`, `timeout` oder `error` |
| `ok` | boolean | `true`, wenn NodeInfo erfolgreich empfangen wurde |
| `timeout` | boolean | `true`, wenn `timeout_seconds` abgelaufen ist |
| `cached` | boolean | `true`, wenn bei Timeout/Fehler ein lokal bekannter Knoten zurückgegeben wird |
| `error` | string oder `nil` | Anfragefehlertext; `nil`, wenn kein Fehler vorliegt |
| `target_node_num` | number | Numerische Meshtastic-ID des Zielknotens (`uint32`) |
| `target_node_id` | string | Zielknoten-ID in der Form `!abcdef12` |
| `target_name` | string | Anzeigename des Zielknotens |
| `chat_type` | string | Chat-Kontext aus den ursprünglichen `options` |
| `chat_key` | string | Chat-Schlüssel aus den ursprünglichen `options` |
| `node` | `node` oder `nil` | Knotentabelle; `nil`, wenn keine Knotendaten verfügbar sind |

## `mesh.admin`

Remote-Admin funktioniert nur für Meshtastic-Verbindungen. Der öffentliche Schlüssel des lokalen Clients muss im Admin Key des Zielknotens eingetragen sein. Alle Funktionen sind asynchron: Sie geben `request_id` zurück und rufen später `on_admin(event)` auf.

`target` kann ein Knoten-ID-String (`"!abcdef12"`), numerisches `node_num` oder eine Knotentabelle mit `node_num`, `node_id`, `long_name`, `short_name` sein.

| Funktion | Rückgabe | Zweck |
|----------|--------|---------|
| `mesh.admin.load_config(target[, options])` | `request_id` | Lädt einen Remote-Snapshot: Owner, Metadaten, Configs, Module-Configs, Kanäle, Status |
| `mesh.admin.request_config(target, type[, options])` | `request_id` | Lädt einen Core-Config-Abschnitt, zum Beispiel `POWER_CONFIG` oder `power` |
| `mesh.admin.request_module_config(target, type[, options])` | `request_id` | Lädt einen Module-Config-Abschnitt, zum Beispiel `MQTT_CONFIG` oder `mqtt` |
| `mesh.admin.save_config(target, changes, options)` | `request_id` | Speichert Änderungen an Owner/Position/Text/Config/Kanälen; erfordert `options.confirm = true` |
| `mesh.admin.refresh_status(target[, options])` | `request_id` | Lädt den Geräteverbindungsstatus neu |
| `mesh.admin.reboot(target[, delay_seconds[, options]])` | `request_id` | Fordert verzögerten Neustart an; `0` bricht einen ausstehenden Neustart ab |
| `mesh.admin.shutdown(target[, delay_seconds[, options]])` | `request_id` | Fordert verzögertes Herunterfahren an; `0` bricht ausstehendes Herunterfahren ab |
| `mesh.admin.sync_time(target[, epoch_seconds[, options]])` | `request_id` | Setzt die Zeit des Remote-Knotens; Standard ist die aktuelle App-Zeit |
| `mesh.admin.backup(target[, location[, options]])` | `request_id` | Sichert Preferences nach `FLASH` oder `SD` |
| `mesh.admin.restore(target[, location], options)` | `request_id` | Stellt Preferences wieder her; erfordert `options.confirm = true` |
| `mesh.admin.remove_backup(target[, location], options)` | `request_id` | Entfernt gespeichertes Backup; erfordert `options.confirm = true` |
| `mesh.admin.reset_nodedb(target[, preserve_favorites], options)` | `request_id` | Setzt die Remote-NodeDB zurück; erfordert `options.confirm = true` |
| `mesh.admin.factory_reset_config(target, options)` | `request_id` | Setzt die Remote-Konfiguration auf Werkseinstellungen zurück; erfordert `options.confirm = true` |
| `mesh.admin.factory_reset_device(target, options)` | `request_id` | Setzt das Remote-Gerät auf Werkseinstellungen zurück; erfordert `options.confirm = true` |
| `mesh.admin.enter_dfu_mode(target, options)` | `request_id` | Fordert DFU-Modus an; erfordert `options.confirm = true` |
| `mesh.admin.set_owner(target, owner[, options])` | `request_id` | Aktualisiert `{ long_name, short_name, licensed }` |
| `mesh.admin.set_fixed_position(target, position[, options])` | `request_id` | Setzt `{ latitude, longitude, altitude }` als manuelle Position |
| `mesh.admin.remove_fixed_position(target[, options])` | `request_id` | Löscht die manuelle feste Position |
| `mesh.admin.set_ringtone(target, text[, options])` | `request_id` | Aktualisiert den RTTTL-Klingeltontext |
| `mesh.admin.set_canned_messages(target, text[, options])` | `request_id` | Aktualisiert den Text des Canned-Message-Moduls |

Beispiel für einen `save_config`-Patch:

```lua
local target = "!abcdef12"

mesh.admin.request_config(target, "POWER_CONFIG")

function on_admin(event)
    if event.action == "request_config" and event.ok then
        mesh.admin.save_config(target, {
            owner = { long_name = "Remote-Knoten", short_name = "RMT", licensed = true },
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

Config-, Module-Config- und Kanal-Patches verwenden Protobuf-Feldnamen in `snake_case`. Enum-Werte sind Strings wie `"PRIMARY"` oder `"CLIENT"`. Repeated Fields sind Lua-Listen. Bytes werden als Hex-Strings, `hex:...`, `base64:...` oder Base64-Strings akzeptiert. Standardmäßig werden Patches mit dem Abschnitt zusammengeführt, der über `load_config` oder `request_config` geladen wurde; wenn ein Abschnitt nicht geladen wurde, schlägt `save_config` fehl. Übergeben Sie `{ replace = true, confirm = true }` nur, wenn absichtlich ein Ersatz auf Basis von Defaults gesendet wird.

Vollständige Liste lesbarer und schreibbarer Config-Felder: [lua-admin-config-reference.de.md](lua-admin-config-reference.de.md).

`on_admin(event)`-Felder:

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `type` | string | `admin_progress` oder `admin_result` |
| `source` | string | API-Quelle, zum Beispiel `mesh.admin.save_config` |
| `name` | string | Ursprünglicher Anfragename aus `options.name` |
| `request_id` | string | Von `mesh.admin.*` zurückgegebene Anfrage-ID |
| `action` | string | Aktionsname, zum Beispiel `load_config`, `save_config`, `reboot` |
| `status` | string | `ok`, `timeout`, `error` oder Fortschrittsstatus `sent`/`received`/`failed` |
| `ok` | boolean | `true` für erfolgreiche Endergebnisse |
| `timeout` | boolean | `true`, wenn das Endergebnis ein Timeout hatte |
| `error` | string oder `nil` | Fehlerdetail |
| `target_node_num` | number | Numerische Zielknoten-ID (`uint32`) |
| `target_node_id` | string | Zielknoten-ID in der Form `!abcdef12` |
| `snapshot` | table oder `nil` | Remote-Snapshot bei Endergebnissen |
| `progress_key` | string oder `nil` | Snapshot-Blockschlüssel für Fortschritts-Events |
| `completed` | number oder `nil` | Abgeschlossene Snapshot-Blöcke bei Fortschritts-Events |
| `total` | number oder `nil` | Gesamtzahl der Snapshot-Blöcke bei Fortschritts-Events |

`event.snapshot` enthält `node`, `owner`, `device_metadata`, `ringtone`, `canned_messages`, `connection_status`, `configs`, `module_configs`, `channels`, `query_statuses` und `query_summary`.

## Objektfelder

Alle folgenden Objekte werden als Lua-Tabellen zurückgegeben. Felder mit Typ `... or nil` können fehlen oder `nil` zurückgeben, wenn MeshApp die entsprechenden Daten noch nicht empfangen hat.

### `owner`

Zurückgegeben von `mesh.owner()`.

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `node_id` | string oder `nil` | Aktuelle Owner-Knoten-ID, zum Beispiel `!abcdef12` |
| `node_num` | number oder `nil` | Numerische Meshtastic-ID des aktuellen Owner-Knotens (`uint32`) |
| `connection_id` | string oder `nil` | Aktive Verbindungs-ID für den Skriptlauf-Kontext |

### `message`

Zurückgegeben von `on_message(msg)`, `mesh.chat.recent(...)` und Sende-/Botnachrichten-Funktionen.

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `db_id` | number | Lokale MeshApp-Datenbank-ID; kann bei noch nicht gespeicherten Nachrichten `0` sein |
| `packet_id` | number | Mesh-Paket-ID; wird beim Antworten als `reply_id` verwendet |
| `chat_type` | string oder `nil` | Chat-Typ: `channel` oder `dm` |
| `chat_key` | string oder `nil` | Chat-Schlüssel: Kanalindex als String oder Knoten-ID des Gegenübers |
| `from` | string oder `nil` | Sender-Knoten-ID |
| `to` | string oder `nil` | Empfänger-Knoten-ID oder Broadcast-Adresse |
| `channel` | number | Kanalindex der Nachricht |
| `channel_name` | string oder `nil` | Kanalname, wenn bekannt |
| `channel_role` | string oder `nil` | Meshtastic-Kanalrolle, zum Beispiel `PRIMARY` oder `SECONDARY` |
| `text` | string oder `nil` | Nachrichtentext |
| `reply_id` | number | `packet_id` der beantworteten Nachricht; `0`, wenn es keine Antwort ist |
| `reply_text` | string oder `nil` | Zitierter Nachrichtentext, wenn bekannt |
| `timestamp` | number | Unix-Zeit der Nachricht in Sekunden |
| `outgoing` | boolean | `true`, wenn die Nachricht vom aktuellen Knoten/Client gesendet wurde |
| `system` | boolean | `true` für System- und lokale Botnachrichten |
| `status` | string oder `nil` | Zustell-/Verarbeitungsstatus, wenn bekannt |
| `sender_name` | string oder `nil` | Anzeigename des Senders |
| `hop_start` | number | Ursprüngliches Hop-Limit des Pakets |
| `hop_limit` | number | Verbleibendes Hop-Limit des Pakets |
| `hops` | number oder `nil` | Anzahl der Hops, die das Paket zurückgelegt hat; `nil`, wenn keine Hop-Daten verfügbar sind |
| `rx_rssi` | number | RSSI des empfangenen Pakets |
| `rx_snr` | number | SNR des empfangenen Pakets in dB |

### `node`

Zurückgegeben von `mesh.chat.nodes()`, `mesh.ui.pick_node(...)`, `mesh.nodeinfo.request(...)` und als `target` für Anfragen akzeptiert.

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `node_num` | number | Numerische Meshtastic-Knoten-ID (`uint32`) |
| `node_id` | string oder `nil` | Knoten-ID in der Form `!abcdef12` |
| `long_name` | string oder `nil` | Vollständiger Benutzer-/Knotenname |
| `short_name` | string oder `nil` | Kurzer Benutzer-/Knotenname |
| `last_heard` | number | Unix-Zeit des letzten bekannten Pakets vom Knoten |
| `battery` | number | Akkustand in Prozent, wenn vom Gerät gemeldet |
| `externally_powered` | boolean | `true`, wenn der Knoten externe Stromversorgung meldet |
| `voltage` | number | Versorgungs-/Akkuspannung in Volt |
| `snr` | number | Letzter bekannter SNR des Knotens in dB |
| `latitude` | number | Breitengrad in Dezimalgrad |
| `longitude` | number | Längengrad in Dezimalgrad |
| `altitude` | number | Höhe in Metern |
| `hops_away` | number oder `nil` | Geschätzte Hop-Entfernung zum Knoten; `nil`, wenn unbekannt |
| `channel` | number | Kanalindex, der den letzten Knotendaten zugeordnet ist |
| `role` | string oder `nil` | Meshtastic-Geräterolle, wenn bekannt |
| `hw_model` | string oder `nil` | Hardwaremodell, wenn bekannt |
| `public_key` | string oder `nil` | Öffentlicher Schlüssel des Knotens als Hex-String |
| `uptime_seconds` | number | Laufzeit des Knotens in Sekunden |
| `channel_utilization` | number | Kanalauslastung in Prozent |
| `air_util_tx` | number | TX-Airtime-Auslastung in Prozent |
| `temperature` | number | Telemetrietemperatur in Grad Celsius |
| `relative_humidity` | number | Relative Luftfeuchtigkeit der Telemetrie in Prozent |
| `barometric_pressure` | number | Barometrischer Telemetriedruck |
| `unmessagable` | boolean | `true`, wenn die Anwendung den Knoten für Nachrichten als nicht verfügbar betrachtet |
| `licensed` | boolean oder `nil` | Licensed-Mode-Flag des Knotens; `nil`, wenn das Feld nicht empfangen wurde |

### `channel`

Zurückgegeben von `mesh.chat.channels()`.

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `index` | number | Kanalindex |
| `role` | string oder `nil` | Meshtastic-Kanalrolle |
| `name` | string oder `nil` | Name aus den Kanaleinstellungen |

### `command`

An `on_command(command)` übergeben und während eines Befehlsstarts von `mesh.command()` zurückgegeben.

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `type` | string | Befehlstyp; Chat-Automation gibt `chat_command` zurück |
| `source` | string | Befehlsquelle; Chat-Starts geben `chat` zurück |
| `name` | string oder `nil` | Befehlsname, meist identisch mit `handle` |
| `request_id` | string | ID dieses Befehlsaufrufs |
| `chat_type` | string oder `nil` | Chat-Typ, in dem der Befehl aufgerufen wurde: `channel` oder `dm` |
| `chat_key` | string oder `nil` | Chat-Schlüssel, in dem der Befehl aufgerufen wurde |
| `handle` | string oder `nil` | Befehls-Handle, zum Beispiel `@tracebot` |
| `text` | string oder `nil` | Vollständiger Benutzerbefehlstext |
| `arguments` | string oder `nil` | Roher Argumentstring nach dem Befehls-Handle |
| `argument_tokens` | Liste von string | Geparste Befehlsargumente; Lua-Indizierung beginnt bei `1` |

### `canvas.size`

Zurückgegeben von `mesh.canvas.size()`.

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `width` | number | Aktuelle Canvas-Breite in Pixeln |
| `height` | number | Aktuelle Canvas-Höhe in Pixeln |

### `canvas.mouse`

Zurückgegeben von `mesh.canvas.mouse()`.

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `x`, `y` | number | Letzte Mauskoordinaten innerhalb der Canvas |
| `screen_x`, `screen_y` | number | Letzte Mauskoordinaten auf dem Bildschirm |
| `over` | boolean | `true`, wenn der Mauszeiger über der Canvas ist |
| `pressed` | boolean | `true`, wenn eine primäre Maustaste gedrückt ist |
| `primary`, `middle`, `secondary` | boolean | Zustand der entsprechenden Maustasten |
| `button` | string | Taste des letzten Events: `primary`, `middle`, `secondary`, `back`, `forward` oder leerer String |
| `click_count` | number | Klickanzahl im letzten Maus-Event |
| `wheel_delta_x`, `wheel_delta_y` | number | Letztes horizontales und vertikales Scroll-Delta |
| `last_type` | string | Letzter Maus-/Scroll-Event-Typ |
| `time` | number | Unix-Zeit des letzten Maus-/Scroll-Events in Sekunden |

### `canvas.keys`

Zurückgegeben von `mesh.canvas.keys()`. Für schnelles Polling sind Tasten auch als boolesche Felder nach Code-Namen verfügbar, zum Beispiel `mesh.canvas.keys().Left`.

| Feld | Typ | Zweck / Rückgabewert |
|-------|------|--------------------------|
| `pressed` | Liste von string | Tastencodes, die aktuell gedrückt sind |
| `last_type` | string | Letzter Tastatur-Event-Typ: `key_pressed`, `key_released` oder `key_typed` |
| `last_code` | string | Letzter Tastencode, zum Beispiel `Left` oder `Enter` |
| `last_key` | string | Anzeigename der letzten Taste |
| `text` | string | Text/Zeichen des letzten Tastatur-Events, falls vorhanden |
| `shift`, `ctrl`, `alt`, `meta` | boolean | Zustand der Tastaturmodifikatoren |
| `time` | number | Unix-Zeit des letzten Tastatur-Events in Sekunden |

## Beispiele

### Lokaler Ping-/Testbot mit Verzögerung

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

### Antwort an das Mesh-Netzwerk

```lua
function on_message(msg)
    if msg.outgoing or not msg.text then
        return
    end

    if string.lower(msg.text) == "hello" then
        mesh.chat.reply(msg, "Hallo von " .. tostring(mesh.owner().node_id))
    end
end
```

### Automationsbot mit Knotenauswahl und Traceroute

```lua
function on_command(command)
    mesh.ui.pick_node({
        name = "trace_target",
        prompt = "Knoten für Traceroute auswählen",
        query = command.arguments,
        chat_type = command.chat_type,
        chat_key = command.chat_key
    })
end

function on_node_selected(event)
    if event.cancelled then
        mesh.chat.bot_message(event.chat_type, event.chat_key, "Traceroute abgebrochen")
        return
    end

    mesh.chat.bot_notice(event.chat_type, event.chat_key, "Traceroute wird gestartet...", {
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
            "Traceroute fehlgeschlagen: " .. tostring(event.status))
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

### NodeInfo über das erste Befehlsargument anfragen

```lua
function on_command(command)
    local target = command.argument_tokens[1]
    if not target then
        mesh.chat.bot_message(command.chat_type, command.chat_key, "Knoten-ID angeben")
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
            "NodeInfo ist nicht verfügbar: " .. tostring(event.status))
        return
    end

    local node = event.node
    mesh.chat.bot_message(event.chat_type, event.chat_key,
        string.format("%s (%s), SNR %.1f, Akku %d%%",
            node.long_name or node.node_id,
            node.short_name or "",
            node.snr or 0,
            node.battery or 0))
end
```

### KV-Speicher und externe HTTP-Anfrage

```lua
local runs = tonumber(mesh.kv.get("runs") or "0") + 1
mesh.kv.set("runs", tostring(runs))
mesh.log("Skriptlauf #" .. runs)

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
    mesh.log("HTTP-Fehler: " .. tostring(response.status) .. " " .. tostring(response.error))
end
```

### Eingebautes Canvas-Menü

```lua
mesh.canvas.open({
    title = "Demomenü",
    background = "#111827",
    fps = 30
})

local items = { "Start", "Einstellungen", "Beenden" }
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

    mesh.canvas.fill_text("Maus: " .. math.floor(mesh.canvas.mouse().x), size.width - 180, 32, "#9ca3af")
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
