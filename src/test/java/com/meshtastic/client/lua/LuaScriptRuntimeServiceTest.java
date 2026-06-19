package com.meshtastic.client.lua;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.MessageListenerService;
import com.google.protobuf.ByteString;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import org.meshtastic.proto.TelemetryProtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LuaScriptRuntimeServiceTest {

    @TempDir
    Path tempHome;

    private LuaScriptService scriptService;
    private LuaScriptRuntimeService runtimeService;
    private List<LuaScriptEvent> events;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        scriptService = LuaScriptService.getInstance();
        runtimeService = LuaScriptRuntimeService.getInstance();
        events = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (runtimeService != null) {
            runtimeService.stopAll();
        }
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void kvStorageIsIsolatedPerScriptAndDeletedWithScript() {
        LuaScript first = scriptService.createScript("first", "");
        LuaScript second = scriptService.createScript("second", "");

        scriptService.setKv(first.getId(), "shared", "one");
        scriptService.setKv(second.getId(), "shared", "two");

        assertEquals("one", scriptService.getKv(first.getId(), "shared"));
        assertEquals("two", scriptService.getKv(second.getId(), "shared"));

        scriptService.deleteScript(first.getId());

        assertNull(scriptService.getKv(first.getId(), "shared"));
        assertEquals("two", scriptService.getKv(second.getId(), "shared"));
    }

    @Test
    void kvStorageListsUpdatesAndDeletesEntriesForEditor() {
        LuaScript script = scriptService.createScript("kv-editor", "");

        scriptService.setKv(script.getId(), "beta", "two");
        scriptService.setKv(script.getId(), "alpha", "one");

        Map<String, String> initialKv = scriptService.listKv(script.getId());
        assertEquals(List.of("alpha", "beta"), new ArrayList<>(initialKv.keySet()));
        assertEquals("one", initialKv.get("alpha"));
        assertEquals("two", initialKv.get("beta"));

        scriptService.setKv(script.getId(), "alpha", "updated");
        assertEquals("updated", scriptService.getKv(script.getId(), "alpha"));

        assertTrue(scriptService.deleteKv(script.getId(), "alpha"));
        assertFalse(scriptService.deleteKv(script.getId(), "missing"));
        assertEquals(Map.of("beta", "two"), scriptService.listKv(script.getId()));
    }

    @Test
    void createdScriptsReceiveStableUniqueGuids() {
        LuaScript first = scriptService.createScript("guid-one", "");
        LuaScript second = scriptService.createScript("guid-two", "");

        assertValidGuid(first.getGuid());
        assertValidGuid(second.getGuid());
        assertNotEquals(first.getGuid(), second.getGuid());
        assertEquals(LuaScript.DEFAULT_ICON, first.getIcon());

        LuaScript saved = scriptService.saveScript(first.getId(), "guid-one-renamed", "mesh.log('ok')", true);
        assertEquals(first.getGuid(), saved.getGuid());
        assertEquals(first.getIcon(), saved.getIcon());
        assertEquals(first.getGuid(), scriptService.findScript(first.getId()).orElseThrow().getGuid());
    }

    @Test
    void scriptIconIsPersistedWithSettings() {
        LuaScript script = scriptService.createScript("icon", "");

        LuaScript saved = scriptService.saveScriptSettings(
                script.getId(),
                "icon",
                true,
                "🛰️",
                "!abcdef12",
                LuaScript.BotType.AIR_BOT,
                "");

        assertEquals("🛰️", saved.getIcon());
        assertEquals("🛰️", scriptService.findScript(script.getId()).orElseThrow().getIcon());
    }

    @Test
    void scriptIconRejectsPlainText() {
        LuaScript script = scriptService.createScript("plain-text-icon", "");

        assertThrows(IllegalArgumentException.class, () -> scriptService.saveScriptSettings(
                script.getId(),
                "plain-text-icon",
                true,
                "bot",
                "!abcdef12",
                LuaScript.BotType.AIR_BOT,
                ""));

        assertThrows(IllegalArgumentException.class, () -> scriptService.createScript(
                "bad-icon",
                "",
                true,
                "script",
                "!abcdef12",
                LuaScript.BotType.AIR_BOT,
                ""));
    }

    @Test
    void autostartScriptsForNodeRunsOnlyMatchingEnabledScripts() {
        LuaScript matching = scriptService.createScript(
                "matching-autostart",
                "mesh.kv.set('started_node', mesh.owner().node_id)",
                true,
                "!ABCDEF12",
                LuaScript.BotType.AIR_BOT,
                "");
        LuaScript otherNode = scriptService.createScript(
                "other-node-autostart",
                "mesh.kv.set('started_node', 'wrong-node')",
                true,
                "!00000000",
                LuaScript.BotType.AIR_BOT,
                "");
        LuaScript disabled = scriptService.createScript(
                "disabled-autostart",
                "mesh.kv.set('started_node', 'disabled')",
                false,
                "!ABCDEF12",
                LuaScript.BotType.AIR_BOT,
                "");

        runtimeService.autostartScriptsForNode(" !abcdef12 ", events::add);

        awaitCondition(() -> "!abcdef12".equals(scriptService.getKv(matching.getId(), "started_node")),
                "Matching autostart script did not run");

        assertNull(scriptService.getKv(otherNode.getId(), "started_node"));
        assertNull(scriptService.getKv(disabled.getId(), "started_node"));
        assertTrue(events.stream().anyMatch(event ->
                event.type() == LuaScriptEvent.Type.STARTED && event.scriptId() == matching.getId()));
        assertFalse(events.stream().anyMatch(event ->
                event.type() == LuaScriptEvent.Type.STARTED && event.scriptId() == otherNode.getId()));
        assertFalse(events.stream().anyMatch(event ->
                event.type() == LuaScriptEvent.Type.STARTED && event.scriptId() == disabled.getId()));
    }

    @Test
    void autostartScriptsForNodeIgnoresAutomationBots() {
        LuaScript automation = scriptService.createScript(
                "automation-autostart",
                "mesh.kv.set('started_node', mesh.owner().node_id)",
                true,
                "!ABCDEF12",
                LuaScript.BotType.AUTOMATION_BOT,
                "@bot_1");

        runtimeService.autostartScriptsForNode("!abcdef12", events::add);

        assertEquals("", automation.getNodeId());
        assertNull(scriptService.getKv(automation.getId(), "started_node"));
        assertFalse(events.stream().anyMatch(event ->
                event.type() == LuaScriptEvent.Type.STARTED && event.scriptId() == automation.getId()));
    }

    @Test
    void autostartScriptsForNodeIgnoresExtensions() {
        LuaScript extension = scriptService.createScript(
                "extension-autostart",
                "mesh.kv.set('started_node', 'extension')",
                true,
                "",
                LuaScript.BotType.EXTENSION,
                "");

        runtimeService.autostartScriptsForNode("!abcdef12", events::add);

        assertEquals("", extension.getNodeId());
        assertNull(scriptService.getKv(extension.getId(), "started_node"));
        assertFalse(events.stream().anyMatch(event ->
                event.type() == LuaScriptEvent.Type.STARTED && event.scriptId() == extension.getId()));
    }

    @Test
    void automationCommandDeliversCommandContextAndFinishes() {
        LuaScript automation = scriptService.createScript(
                "automation-command",
                """
                function on_command(command)
                    mesh.kv.set('command_type', command.type)
                    mesh.kv.set('command_source', command.source)
                    mesh.kv.set('command_name', command.name)
                    mesh.kv.set('command_request_id', command.request_id)
                    mesh.kv.set('chat_type', command.chat_type)
                    mesh.kv.set('chat_key', command.chat_key)
                    mesh.kv.set('handle', command.handle)
                    mesh.kv.set('arguments', command.arguments)
                    mesh.kv.set('first_arg', command.argument_tokens[1] or '')
                end
                """,
                true,
                "",
                LuaScript.BotType.AUTOMATION_BOT,
                "@auto");

        runtimeService.runAutomationCommand(
                automation,
                new LuaAutomationCommand("channel", "0", "@auto", "@auto Alpha", "Alpha", List.of("Alpha"), "cmd-123"),
                events::add,
                null);

        awaitCondition(() -> !runtimeService.isRunning(automation.getId()), "Automation command did not finish");

        assertEquals("chat_command", scriptService.getKv(automation.getId(), "command_type"));
        assertEquals("chat", scriptService.getKv(automation.getId(), "command_source"));
        assertEquals("@auto", scriptService.getKv(automation.getId(), "command_name"));
        assertEquals("cmd-123", scriptService.getKv(automation.getId(), "command_request_id"));
        assertEquals("channel", scriptService.getKv(automation.getId(), "chat_type"));
        assertEquals("0", scriptService.getKv(automation.getId(), "chat_key"));
        assertEquals("@auto", scriptService.getKv(automation.getId(), "handle"));
        assertEquals("Alpha", scriptService.getKv(automation.getId(), "arguments"));
        assertEquals("Alpha", scriptService.getKv(automation.getId(), "first_arg"));
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void extensionRuntimeControlsEmbeddedFormAndReceivesEvents() {
        LuaScript extension = scriptService.createScript(
                "extension-form",
                """
                function on_extension_open(event)
                    mesh.kv.set('opened', event.type)
                    mesh.form.set_title('Extension Title')
                    local card = mesh.form.add({ type = 'card', id = 'main' })
                    mesh.form.add({ type = 'button', id = 'run', parent = card, text = 'Run' })
                end

                function on_form_event(event)
                    mesh.kv.set('form_event', event.type .. ':' .. event.id .. ':' .. tostring(event.value))
                    mesh.form.set('run', { text = 'Done' })
                end
                """,
                true,
                "",
                LuaScript.BotType.EXTENSION,
                "");
        FakeFormBridge formBridge = new FakeFormBridge();

        runtimeService.runExtension(extension, formBridge, events::add);

        awaitCondition(() -> formBridge.components.containsKey("run"),
                "Extension did not create form controls");

        assertEquals("extension_open", scriptService.getKv(extension.getId(), "opened"));
        assertEquals("Extension Title", formBridge.title);
        assertTrue(runtimeService.isRunning(extension.getId()));

        runtimeService.deliverFormEvent(extension.getId(),
                new LuaFormEvent(extension.getId(), "run", "action", "clicked", "Run"));

        awaitCondition(() -> "action:run:clicked".equals(scriptService.getKv(extension.getId(), "form_event")),
                "Extension did not receive form event");

        assertEquals("Done", formBridge.components.get("run").text());
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void extensionFormAcceptsExtendedAtlantaFxComponentOptions() {
        LuaScript extension = scriptService.createScript(
                "extension-form-components",
                """
                function on_extension_open(event)
                    mesh.form.clear()
                    local shell = mesh.form.add({
                        type = 'split_pane',
                        id = 'shell',
                        orientation = 'horizontal',
                        grow = 'always'
                    })
                    mesh.form.add({
                        type = 'list_view',
                        id = 'channels',
                        parent = shell,
                        items = { '#mesh', '#ops' },
                        value = '#ops',
                        width = 180,
                        min_width = 120,
                        grow = 'never'
                    })
                    mesh.form.add({
                        type = 'text_area',
                        id = 'log',
                        parent = shell,
                        value = '[12:00] <node> hello',
                        read_only = true,
                        monospace = true,
                        rows = 12,
                        wrap = false,
                        grow = 'always'
                    })
                    mesh.form.add({ type = 'toggle_switch', id = 'online', text = 'Online', selected = true })
                    mesh.form.add({
                        type = 'segmented_control',
                        id = 'mode',
                        items = { 'IRC', 'DM' },
                        value = 'IRC'
                    })
                    mesh.form.add({ type = 'ring_progress', id = 'sync', value = 0.5, width = 48, height = 48 })
                end
                """,
                true,
                "",
                LuaScript.BotType.EXTENSION,
                "");
        FakeFormBridge formBridge = new FakeFormBridge();

        runtimeService.runExtension(extension, formBridge, events::add);

        awaitCondition(() -> formBridge.components.containsKey("sync"),
                "Extension did not create extended form controls");

        LuaFormComponentSpec shell = formBridge.components.get("shell").spec();
        assertEquals("split_pane", shell.type());
        assertEquals("horizontal", shell.orientation());
        assertEquals("always", shell.grow());

        LuaFormComponentSpec channels = formBridge.components.get("channels").spec();
        assertEquals("list_view", channels.type());
        assertEquals("shell", channels.parentId());
        assertEquals(List.of("#mesh", "#ops"), channels.items());
        assertEquals("#ops", channels.value());
        assertEquals(180.0, channels.width());
        assertEquals(120.0, channels.minWidth());
        assertEquals("never", channels.grow());

        LuaFormComponentSpec log = formBridge.components.get("log").spec();
        assertEquals(Boolean.TRUE, log.readOnly());
        assertEquals(Boolean.TRUE, log.monospace());
        assertEquals(Boolean.FALSE, log.wrap());
        assertEquals(12, log.rows());

        assertEquals(Boolean.TRUE, formBridge.components.get("online").value());
        assertEquals("IRC", formBridge.components.get("mode").value());
        assertEquals(0.5, formBridge.components.get("sync").value());
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void sleepAllowsCallbacksToResumeAfterDelay() {
        LuaScript automation = scriptService.createScript(
                "automation-sleep",
                """
                function on_command(command)
                    mesh.sleep(1.6)
                    mesh.kv.set('slept', 'done')
                end
                """,
                true,
                "",
                LuaScript.BotType.AUTOMATION_BOT,
                "@sleep");

        runtimeService.runAutomationCommand(
                automation,
                new LuaAutomationCommand("channel", "0", "@sleep", "@sleep", "", List.of(), "cmd-sleep"),
                events::add,
                null);

        awaitCondition(() -> !runtimeService.isRunning(automation.getId()), "Sleep automation did not finish");

        assertEquals("done", scriptService.getKv(automation.getId(), "slept"));
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void timerAfterKeepsScriptAliveUntilCallback() {
        LuaScript script = scriptService.createScript(
                "timer-after",
                """
                function on_timer(event)
                    mesh.kv.set('timer_type', event.type)
                    mesh.kv.set('timer_source', event.source)
                    mesh.kv.set('timer_name', event.name)
                    mesh.kv.set('timer_count', tostring(event.count))
                    mesh.kv.set('timer_repeating', tostring(event.repeating))
                    mesh.kv.set('timer_time', event.time.iso_datetime)
                    mesh.kv.set('timer_done', 'yes')
                end

                mesh.timer.after(0.1, { name = 'once' })
                """);

        runtimeService.runScript(script, events::add);

        awaitCondition(() -> "yes".equals(scriptService.getKv(script.getId(), "timer_done")),
                "One-shot timer did not fire");
        awaitCondition(() -> !runtimeService.isRunning(script.getId()), "One-shot timer script did not finish");

        assertEquals("timer", scriptService.getKv(script.getId(), "timer_type"));
        assertEquals("mesh.timer.after", scriptService.getKv(script.getId(), "timer_source"));
        assertEquals("once", scriptService.getKv(script.getId(), "timer_name"));
        assertEquals("1", scriptService.getKv(script.getId(), "timer_count"));
        assertEquals("false", scriptService.getKv(script.getId(), "timer_repeating"));
        assertNotNull(scriptService.getKv(script.getId(), "timer_time"));
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void timerEveryRepeatsAndCanCancelItself() {
        LuaScript script = scriptService.createScript(
                "timer-every",
                """
                function on_timer(event)
                    mesh.kv.set('timer_id', event.id)
                    mesh.kv.set('timer_name', event.name)
                    mesh.kv.set('timer_count', tostring(event.count))
                    mesh.kv.set('timer_align', event.align)
                    mesh.kv.set('timer_repeating', tostring(event.repeating))
                    mesh.kv.set('timer_has_time', tostring(event.time.year ~= nil))
                    if event.count >= 2 then
                        mesh.kv.set('timer_cancelled', tostring(mesh.timer.cancel(event.id)))
                        mesh.kv.set('timer_done', 'yes')
                    end
                end

                mesh.timer.every(0.1, { name = 'repeat', immediate = true })
                """);

        runtimeService.runScript(script, events::add);

        awaitCondition(() -> "yes".equals(scriptService.getKv(script.getId(), "timer_done")),
                "Repeating timer did not cancel itself");
        awaitCondition(() -> !runtimeService.isRunning(script.getId()), "Repeating timer script did not finish");

        assertTrue(scriptService.getKv(script.getId(), "timer_id").contains(":timer:"));
        assertEquals("repeat", scriptService.getKv(script.getId(), "timer_name"));
        assertEquals("2", scriptService.getKv(script.getId(), "timer_count"));
        assertEquals("interval", scriptService.getKv(script.getId(), "timer_align"));
        assertEquals("true", scriptService.getKv(script.getId(), "timer_repeating"));
        assertEquals("true", scriptService.getKv(script.getId(), "timer_has_time"));
        assertEquals("true", scriptService.getKv(script.getId(), "timer_cancelled"));
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void jsonApiParsesEncodesNullsAndArrays() {
        LuaScript script = scriptService.createScript(
                "json-api",
                """
                local data = mesh.json.decode('{"name":"alpha","items":[1,true,null],"empty":[]}')
                mesh.kv.set('name', data.name)
                mesh.kv.set('item1', tostring(data.items[1]))
                mesh.kv.set('item2', tostring(data.items[2]))
                mesh.kv.set('item3_null', tostring(mesh.json.is_null(data.items[3])))
                mesh.kv.set('decoded_empty_array', mesh.json.encode(data.empty))

                local payload = {
                    status = mesh.json.null,
                    empty_object = {},
                    empty_array = mesh.json.array({}),
                    values = mesh.json.array({ "a", "b" })
                }
                local encoded = mesh.json.encode(payload)
                local roundtrip = mesh.json.decode(encoded)
                mesh.kv.set('roundtrip_null', tostring(mesh.json.is_null(roundtrip.status)))
                mesh.kv.set('roundtrip_empty_object', mesh.json.encode(roundtrip.empty_object))
                mesh.kv.set('roundtrip_empty_array', mesh.json.encode(roundtrip.empty_array))
                mesh.kv.set('roundtrip_value2', roundtrip.values[2])
                mesh.kv.set('pretty_has_newline', tostring(string.find(mesh.json.pretty(payload), "\\n") ~= nil))

                local invalid, parse_error = mesh.json.try_decode('{bad')
                mesh.kv.set('try_invalid_nil', tostring(invalid == nil))
                mesh.kv.set('try_error_present', tostring(parse_error ~= nil and #parse_error > 0))

                local mixed_ok = pcall(function()
                    return mesh.json.encode({ [1] = "array", key = "object" })
                end)
                mesh.kv.set('mixed_ok', tostring(mixed_ok))
                mesh.kv.set('done', 'yes')
                """);

        runtimeService.runScript(script, events::add);

        awaitCondition(() -> "yes".equals(scriptService.getKv(script.getId(), "done")),
                "JSON API script did not finish");

        assertEquals("alpha", scriptService.getKv(script.getId(), "name"));
        assertEquals("1", scriptService.getKv(script.getId(), "item1"));
        assertEquals("true", scriptService.getKv(script.getId(), "item2"));
        assertEquals("true", scriptService.getKv(script.getId(), "item3_null"));
        assertEquals("[]", scriptService.getKv(script.getId(), "decoded_empty_array"));
        assertEquals("true", scriptService.getKv(script.getId(), "roundtrip_null"));
        assertEquals("{}", scriptService.getKv(script.getId(), "roundtrip_empty_object"));
        assertEquals("[]", scriptService.getKv(script.getId(), "roundtrip_empty_array"));
        assertEquals("b", scriptService.getKv(script.getId(), "roundtrip_value2"));
        assertEquals("true", scriptService.getKv(script.getId(), "pretty_has_newline"));
        assertEquals("true", scriptService.getKv(script.getId(), "try_invalid_nil"));
        assertEquals("true", scriptService.getKv(script.getId(), "try_error_present"));
        assertEquals("false", scriptService.getKv(script.getId(), "mixed_ok"));
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void timeHelpersUseLocalTimezoneAndRegionalFormats() {
        Locale previousLocale = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone previousTimeZone = TimeZone.getDefault();
        ZoneId zone = ZoneId.of("Europe/Moscow");
        ZonedDateTime localDateTime = LocalDateTime.of(2026, 6, 19, 14, 5, 9).atZone(zone);
        long epoch = localDateTime.toEpochSecond();
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            TimeZone.setDefault(TimeZone.getTimeZone(zone));

            LuaScript script = scriptService.createScript(
                    "time-api",
                    """
                    local epoch = %d
                    local t = mesh.localtime(epoch)
                    mesh.kv.set('iso_date', mesh.iso_date(epoch))
                    mesh.kv.set('iso_time', mesh.iso_time(epoch))
                    mesh.kv.set('iso_datetime', mesh.iso_datetime(epoch))
                    mesh.kv.set('date', mesh.date(epoch))
                    mesh.kv.set('time', mesh.time(epoch))
                    mesh.kv.set('datetime', mesh.datetime(epoch))
                    mesh.kv.set('year', tostring(t.year))
                    mesh.kv.set('month', tostring(t.month))
                    mesh.kv.set('day', tostring(t.day))
                    mesh.kv.set('hour', tostring(t.hour))
                    mesh.kv.set('minute', tostring(t.minute))
                    mesh.kv.set('second', tostring(t.second))
                    mesh.kv.set('weekday', tostring(t.weekday))
                    mesh.kv.set('wday', tostring(t.wday))
                    mesh.kv.set('yearday', tostring(t.yearday))
                    mesh.kv.set('timezone', t.timezone)
                    mesh.kv.set('offset', t.offset)
                    mesh.kv.set('offset_seconds', tostring(t.offset_seconds))
                    mesh.kv.set('local_date', t.date)
                    mesh.kv.set('local_time', t.time)
                    mesh.kv.set('local_datetime', t.datetime)
                    mesh.kv.set('local_iso', t.iso_datetime)
                    """.formatted(epoch));

            runtimeService.runScript(script, events::add);

            awaitCondition(() -> "2026-06-19".equals(scriptService.getKv(script.getId(), "iso_date")),
                    "Lua time API script did not finish");

            String expectedDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
                    .withLocale(Locale.US)
                    .format(localDateTime);
            String expectedTime = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
                    .withLocale(Locale.US)
                    .format(localDateTime);
            String expectedDateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM)
                    .withLocale(Locale.US)
                    .format(localDateTime);

            assertEquals("2026-06-19", scriptService.getKv(script.getId(), "iso_date"));
            assertEquals("14:05:09", scriptService.getKv(script.getId(), "iso_time"));
            assertEquals("2026-06-19 14:05:09", scriptService.getKv(script.getId(), "iso_datetime"));
            assertEquals(expectedDate, scriptService.getKv(script.getId(), "date"));
            assertEquals(expectedTime, scriptService.getKv(script.getId(), "time"));
            assertEquals(expectedDateTime, scriptService.getKv(script.getId(), "datetime"));
            assertEquals("2026", scriptService.getKv(script.getId(), "year"));
            assertEquals("6", scriptService.getKv(script.getId(), "month"));
            assertEquals("19", scriptService.getKv(script.getId(), "day"));
            assertEquals("14", scriptService.getKv(script.getId(), "hour"));
            assertEquals("5", scriptService.getKv(script.getId(), "minute"));
            assertEquals("9", scriptService.getKv(script.getId(), "second"));
            assertEquals("5", scriptService.getKv(script.getId(), "weekday"));
            assertEquals("6", scriptService.getKv(script.getId(), "wday"));
            assertEquals("170", scriptService.getKv(script.getId(), "yearday"));
            assertEquals("Europe/Moscow", scriptService.getKv(script.getId(), "timezone"));
            assertEquals("+03:00", scriptService.getKv(script.getId(), "offset"));
            assertEquals("10800", scriptService.getKv(script.getId(), "offset_seconds"));
            assertEquals(expectedDate, scriptService.getKv(script.getId(), "local_date"));
            assertEquals(expectedTime, scriptService.getKv(script.getId(), "local_time"));
            assertEquals(expectedDateTime, scriptService.getKv(script.getId(), "local_datetime"));
            assertEquals("2026-06-19 14:05:09", scriptService.getKv(script.getId(), "local_iso"));
            assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previousLocale);
            TimeZone.setDefault(previousTimeZone);
        }
    }

    @Test
    void uiPickNodeReturnsSelectionToAutomationCallback() {
        LuaScript automation = scriptService.createScript(
                "automation-pick-node",
                """
                function on_command(command)
                    local request_id = mesh.ui.pick_node({
                        name = 'pick_target',
                        prompt = 'Pick',
                        query = command.arguments,
                        chat_type = command.chat_type,
                        chat_key = command.chat_key
                    })
                    mesh.kv.set('request_id', request_id)
                end

                function on_node_selected(event)
                    mesh.kv.set('selection_type', event.type)
                    mesh.kv.set('selection_source', event.source)
                    mesh.kv.set('selection_name', event.name)
                    mesh.kv.set('selection_request_id', event.request_id)
                    mesh.kv.set('selection_status', event.status)
                    mesh.kv.set('selection_chat', event.chat_type .. ':' .. event.chat_key)
                    if event.node ~= nil then
                        mesh.kv.set('selection_node', event.node.node_id)
                    end
                end
                """,
                true,
                "",
                LuaScript.BotType.AUTOMATION_BOT,
                "@pick");
        List<LuaUiNodePickRequest> requests = new CopyOnWriteArrayList<>();

        runtimeService.runAutomationCommand(
                automation,
                new LuaAutomationCommand("dm", "!abcdef01", "@pick", "@pick Alpha", "Alpha", List.of("Alpha")),
                events::add,
                requests::add);

        awaitCondition(() -> !requests.isEmpty(), "Lua UI node pick request was not emitted");

        LuaUiNodePickRequest request = requests.getFirst();
        assertEquals(automation.getId(), request.scriptId());
        assertEquals("mesh.ui.pick_node", request.source());
        assertEquals("pick_target", request.name());
        assertEquals("Pick", request.prompt());
        assertEquals("Alpha", request.query());
        assertEquals("dm", request.chatType());
        assertEquals("!abcdef01", request.chatKey());

        NodeData node = new NodeData(0x0000BEEF);
        node.setLongName("Alpha");
        runtimeService.deliverNodeSelection(automation.getId(), LuaUiNodeSelection.selected(request, node));

        awaitCondition(() -> "!0000beef".equals(scriptService.getKv(automation.getId(), "selection_node")),
                "Lua node selection callback did not run");

        assertEquals("selected", scriptService.getKv(automation.getId(), "selection_status"));
        assertEquals("ui_result", scriptService.getKv(automation.getId(), "selection_type"));
        assertEquals("mesh.ui.pick_node", scriptService.getKv(automation.getId(), "selection_source"));
        assertEquals("pick_target", scriptService.getKv(automation.getId(), "selection_name"));
        assertEquals(request.requestId(), scriptService.getKv(automation.getId(), "selection_request_id"));
        assertEquals("dm:!abcdef01", scriptService.getKv(automation.getId(), "selection_chat"));
        assertFalse(runtimeService.isRunning(automation.getId()));
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void botNoticeEmitsTransientUiEventWithoutMessageStorage() {
        LuaScript automation = scriptService.createScript(
                "automation-bot-notice",
                """
                function on_command(command)
                    mesh.chat.bot_notice(command.chat_type, command.chat_key, 'Working', {
                        name = 'tracebot_progress'
                    })
                end
                """,
                true,
                "",
                LuaScript.BotType.AUTOMATION_BOT,
                "@notice");

        runtimeService.runAutomationCommand(
                automation,
                new LuaAutomationCommand("channel", "0", "@notice", "@notice", "", List.of(), "notice-1"),
                events::add,
                request -> fail("Node picker must not be requested"));

        awaitCondition(() -> events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.UI_BOT_NOTICE),
                "Lua bot notice was not emitted");

        LuaUiBotNotice notice = events.stream()
                .filter(event -> event.type() == LuaScriptEvent.Type.UI_BOT_NOTICE)
                .map(event -> (LuaUiBotNotice) event.payload())
                .findFirst()
                .orElseThrow();
        assertEquals("mesh.chat.bot_notice", notice.source());
        assertEquals("tracebot_progress", notice.name());
        assertEquals("channel", notice.chatType());
        assertEquals("0", notice.chatKey());
        assertEquals("Working", notice.text());
        assertTrue(MessageDbService.getInstance()
                .loadLast("channel", "0", 10, "")
                .isEmpty());
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void tracerouteRequestDeliversResultToAutomationCallback() throws Exception {
        LuaScript automation = scriptService.createScript(
                "automation-traceroute",
                """
                function on_command(command)
                    mesh.traceroute.request({
                        node_num = 48879,
                        node_id = '!0000beef',
                        long_name = 'Alpha'
                    }, {
                        name = 'trace_request',
                        chat_type = command.chat_type,
                        chat_key = command.chat_key,
                        timeout_seconds = 5
                    })
                end

                function on_traceroute(event)
                    mesh.kv.set('trace_type', event.type)
                    mesh.kv.set('trace_source', event.source)
                    mesh.kv.set('trace_name', event.name)
                    mesh.kv.set('trace_request_id', event.request_id)
                    mesh.kv.set('trace_status', event.status)
                    mesh.kv.set('trace_ok', tostring(event.ok))
                    mesh.kv.set('trace_target', tostring(event.target_node_num))
                    mesh.kv.set('trace_response_from', tostring(event.response_from_node_num or ''))
                    mesh.kv.set('trace_chat', event.chat_type .. ':' .. event.chat_key)
                    if event.route ~= nil then
                        mesh.kv.set('route_first', tostring(event.route.route[1] or ''))
                        mesh.kv.set('route_first_id', event.route.route_ids[1] or '')
                        mesh.kv.set('snr_first', string.format('%.1f', event.route.snr_towards[1] or 0))
                    end
                end
                """,
                true,
                "",
                LuaScript.BotType.AUTOMATION_BOT,
                "@tracebot");
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        FakeTransportConnection transport = new FakeTransportConnection();
        ProtocolHandler handler = new ProtocolHandler(transport);
        AtomicBoolean closed = new AtomicBoolean(false);
        LuaRuntimeSession session = new LuaRuntimeSession(
                automation,
                new LuaScriptRuntimeService.RuntimeTarget("test", state, handler, null, "!12345678"),
                scriptService,
                Set.of(),
                false,
                new LuaAutomationCommand("channel", "0", "@tracebot", "@tracebot Alpha", "Alpha", List.of("Alpha"), "trace-command"),
                events::add,
                request -> fail("Node picker must not be requested"),
                null,
                () -> closed.set(true));

        try {
            session.start();
            awaitCondition(() -> transport.writeCount() > 0, "Traceroute packet was not sent");

            state.fireTracerouteListeners(
                    (int) 0xF0000002L,
                    MeshProtos.RouteDiscovery.newBuilder()
                            .addRoute((int) 0xF0000003L)
                            .addSnrTowards(12)
                            .build());

            awaitCondition(closed::get, "Traceroute automation did not finish");

            assertEquals("traceroute_result", scriptService.getKv(automation.getId(), "trace_type"));
            assertEquals("mesh.traceroute.request", scriptService.getKv(automation.getId(), "trace_source"));
            assertEquals("trace_request", scriptService.getKv(automation.getId(), "trace_name"));
            assertTrue(scriptService.getKv(automation.getId(), "trace_request_id").contains(":traceroute:"));
            assertEquals("ok", scriptService.getKv(automation.getId(), "trace_status"));
            assertEquals("true", scriptService.getKv(automation.getId(), "trace_ok"));
            assertEquals("48879", scriptService.getKv(automation.getId(), "trace_target"));
            assertFalse(scriptService.getKv(automation.getId(), "trace_response_from").startsWith("-"));
            assertEquals("channel:0", scriptService.getKv(automation.getId(), "trace_chat"));
            assertEquals("!f0000003", scriptService.getKv(automation.getId(), "route_first_id"));
            assertEquals("3.0", scriptService.getKv(automation.getId(), "snr_first"));
            List<MessageDbService.TracerouteResultRecord> savedTraces = MessageDbService.getInstance()
                    .loadRecentTracerouteResults(10, "!12345678");
            assertEquals(1, savedTraces.size());
            MessageDbService.TracerouteResultRecord savedTrace = savedTraces.get(0);
            assertEquals("channel", savedTrace.chatType());
            assertEquals("0", savedTrace.chatKey());
            assertEquals("mesh.traceroute.request", savedTrace.source());
            assertEquals(automation.getId(), savedTrace.scriptId());
            assertEquals(48879L, savedTrace.targetNodeNum());
            assertEquals("!0000beef", savedTrace.targetNodeId());
            assertEquals("Alpha", savedTrace.targetName());
            assertEquals(Integer.toUnsignedLong((int) 0xF0000002L), savedTrace.responseFromNodeNum());
            assertEquals("!f0000002", savedTrace.responseFromNodeId());
            assertNotNull(savedTrace.routeData());
            MeshProtos.RouteDiscovery savedRoute = MeshProtos.RouteDiscovery.parseFrom(savedTrace.routeData());
            assertEquals(1, savedRoute.getRouteCount());
            assertEquals((int) 0xF0000003L, savedRoute.getRoute(0));
            assertEquals(12, savedRoute.getSnrTowards(0));
            assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
        } finally {
            session.stop();
            handler.shutdown();
        }
    }

    @Test
    void nodeInfoRequestDeliversUpdatedNodeToAutomationCallback() {
        LuaScript automation = scriptService.createScript(
                "automation-nodeinfo",
                """
                function on_command(command)
                    mesh.nodeinfo.request({
                        node_num = 48879,
                        node_id = '!0000beef',
                        long_name = 'Alpha'
                    }, {
                        name = 'nodeinfo_request',
                        chat_type = command.chat_type,
                        chat_key = command.chat_key,
                        timeout_seconds = 5
                    })
                end

                function on_node_info(event)
                    mesh.kv.set('info_type', event.type)
                    mesh.kv.set('info_source', event.source)
                    mesh.kv.set('info_name', event.name)
                    mesh.kv.set('info_request_id', event.request_id)
                    mesh.kv.set('info_status', event.status)
                    mesh.kv.set('info_ok', tostring(event.ok))
                    mesh.kv.set('info_cached', tostring(event.cached))
                    mesh.kv.set('info_target', tostring(event.target_node_num))
                    mesh.kv.set('info_chat', event.chat_type .. ':' .. event.chat_key)
                    if event.node ~= nil then
                        mesh.kv.set('info_node_id', event.node.node_id)
                        mesh.kv.set('info_node_num', tostring(event.node.node_num))
                        mesh.kv.set('info_long_name', event.node.long_name or '')
                        mesh.kv.set('info_short_name', event.node.short_name or '')
                        mesh.kv.set('info_voltage', string.format('%.1f', event.node.voltage or 0))
                        mesh.kv.set('info_snr', string.format('%.1f', event.node.snr or 0))
                        mesh.kv.set('info_latitude', string.format('%.2f', event.node.latitude or 0))
                        mesh.kv.set('info_public_key', event.node.public_key or '')
                        mesh.kv.set('info_uptime', tostring(event.node.uptime_seconds or ''))
                        mesh.kv.set('info_licensed', tostring(event.node.licensed))
                    end
                end
                """,
                true,
                "",
                LuaScript.BotType.AUTOMATION_BOT,
                "@infobot");
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        FakeTransportConnection transport = new FakeTransportConnection();
        ProtocolHandler handler = new ProtocolHandler(transport);
        AtomicBoolean closed = new AtomicBoolean(false);
        LuaRuntimeSession session = new LuaRuntimeSession(
                automation,
                new LuaScriptRuntimeService.RuntimeTarget("test", state, handler, null, "!12345678"),
                scriptService,
                Set.of(),
                false,
                new LuaAutomationCommand("channel", "0", "@infobot", "@infobot Alpha", "Alpha", List.of("Alpha"), "info-command"),
                events::add,
                request -> fail("Node picker must not be requested"),
                null,
                () -> closed.set(true));

        try {
            session.start();
            awaitCondition(() -> transport.writeCount() > 0, "NodeInfo packet was not sent");

            new MessageListenerService(state).onNodeInfo(MeshProtos.NodeInfo.newBuilder()
                    .setNum(0x0000BEEF)
                    .setUser(MeshProtos.User.newBuilder()
                            .setId("!0000beef")
                            .setLongName("Alpha Updated")
                            .setShortName("ALP")
                            .setPublicKey(com.google.protobuf.ByteString.copyFrom(new byte[] { 0x01, 0x23, (byte) 0xff }))
                            .setIsLicensed(true)
                            .build())
                    .setPosition(MeshProtos.Position.newBuilder()
                            .setLatitudeI(557_500_000)
                            .setLongitudeI(376_100_000)
                            .setAltitude(180)
                            .build())
                    .setSnr(7.5f)
                    .setDeviceMetrics(TelemetryProtos.DeviceMetrics.newBuilder()
                            .setVoltage(4.5f)
                            .setUptimeSeconds(3660)
                            .build())
                    .build());

            awaitCondition(closed::get, "NodeInfo automation did not finish");

            assertEquals("nodeinfo_result", scriptService.getKv(automation.getId(), "info_type"));
            assertEquals("mesh.nodeinfo.request", scriptService.getKv(automation.getId(), "info_source"));
            assertEquals("nodeinfo_request", scriptService.getKv(automation.getId(), "info_name"));
            assertTrue(scriptService.getKv(automation.getId(), "info_request_id").contains(":nodeinfo:"));
            assertEquals("ok", scriptService.getKv(automation.getId(), "info_status"));
            assertEquals("true", scriptService.getKv(automation.getId(), "info_ok"));
            assertEquals("false", scriptService.getKv(automation.getId(), "info_cached"));
            assertEquals("48879", scriptService.getKv(automation.getId(), "info_target"));
            assertEquals("channel:0", scriptService.getKv(automation.getId(), "info_chat"));
            assertEquals("!0000beef", scriptService.getKv(automation.getId(), "info_node_id"));
            assertEquals("48879", scriptService.getKv(automation.getId(), "info_node_num"));
            assertEquals("Alpha Updated", scriptService.getKv(automation.getId(), "info_long_name"));
            assertEquals("ALP", scriptService.getKv(automation.getId(), "info_short_name"));
            assertEquals("4.5", scriptService.getKv(automation.getId(), "info_voltage"));
            assertEquals("7.5", scriptService.getKv(automation.getId(), "info_snr"));
            assertEquals("55.75", scriptService.getKv(automation.getId(), "info_latitude"));
            assertEquals("0123ff", scriptService.getKv(automation.getId(), "info_public_key"));
            assertEquals("3660", scriptService.getKv(automation.getId(), "info_uptime"));
            assertEquals("true", scriptService.getKv(automation.getId(), "info_licensed"));
            assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
        } finally {
            session.stop();
            handler.shutdown();
        }
    }

    @Test
    void nodeInfoTimeoutDeliversCachedNodeToAutomationCallback() {
        LuaScript automation = scriptService.createScript(
                "automation-nodeinfo-timeout-cache",
                """
                function on_command(command)
                    mesh.nodeinfo.request({
                        node_num = 48879,
                        node_id = '!0000beef',
                        long_name = 'Alpha Cached'
                    }, {
                        name = 'nodeinfo_request',
                        chat_type = command.chat_type,
                        chat_key = command.chat_key,
                        timeout_seconds = 1
                    })
                end

                function on_node_info(event)
                    mesh.kv.set('info_status', event.status)
                    mesh.kv.set('info_ok', tostring(event.ok))
                    mesh.kv.set('info_timeout', tostring(event.timeout))
                    mesh.kv.set('info_cached', tostring(event.cached))
                    if event.node ~= nil then
                        mesh.kv.set('info_node_id', event.node.node_id)
                        mesh.kv.set('info_long_name', event.node.long_name or '')
                    end
                end
                """,
                true,
                "",
                LuaScript.BotType.AUTOMATION_BOT,
                "@infobot");
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        NodeData cached = state.getOrCreateNode(0x0000BEEF);
        cached.setLongName("Alpha Cached");
        FakeTransportConnection transport = new FakeTransportConnection();
        ProtocolHandler handler = new ProtocolHandler(transport);
        AtomicBoolean closed = new AtomicBoolean(false);
        LuaRuntimeSession session = new LuaRuntimeSession(
                automation,
                new LuaScriptRuntimeService.RuntimeTarget("test", state, handler, null, "!12345678"),
                scriptService,
                Set.of(),
                false,
                new LuaAutomationCommand("channel", "0", "@infobot", "@infobot Alpha", "Alpha", List.of("Alpha"), "info-command"),
                events::add,
                request -> fail("Node picker must not be requested"),
                null,
                () -> closed.set(true));

        try {
            session.start();
            awaitCondition(() -> transport.writeCount() > 0, "NodeInfo packet was not sent");
            awaitCondition(closed::get, "NodeInfo timeout automation did not finish");

            assertEquals("timeout", scriptService.getKv(automation.getId(), "info_status"));
            assertEquals("false", scriptService.getKv(automation.getId(), "info_ok"));
            assertEquals("true", scriptService.getKv(automation.getId(), "info_timeout"));
            assertEquals("true", scriptService.getKv(automation.getId(), "info_cached"));
            assertEquals("!0000beef", scriptService.getKv(automation.getId(), "info_node_id"));
            assertEquals("Alpha Cached", scriptService.getKv(automation.getId(), "info_long_name"));
            assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
        } finally {
            session.stop();
            handler.shutdown();
        }
    }

    @Test
    void remoteAdminRequestConfigDeliversSnapshotToLuaCallback() throws Exception {
        LuaScript automation = scriptService.createScript(
                "automation-remote-admin-request",
                """
                local target = { node_num = 572662306, node_id = '!22222222', long_name = 'Remote' }

                function on_command(command)
                    mesh.admin.request_config(target, 'POWER_CONFIG', { name = 'power_request' })
                end

                function on_admin(event)
                    mesh.kv.set('admin_type', event.type)
                    mesh.kv.set('admin_source', event.source)
                    mesh.kv.set('admin_name', event.name)
                    mesh.kv.set('admin_action', event.action)
                    mesh.kv.set('admin_status', event.status)
                    mesh.kv.set('admin_ok', tostring(event.ok))
                    mesh.kv.set('admin_target', tostring(event.target_node_num))
                    mesh.kv.set('power_ls', tostring(event.snapshot.configs.power.ls_secs or ''))
                    mesh.kv.set('query_state', event.snapshot.query_statuses[1].state)
                end
                """,
                true,
                "",
                LuaScript.BotType.AUTOMATION_BOT,
                "@adminbot");
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x11111111);
        FakeTransportConnection transport = new FakeTransportConnection();
        ProtocolHandler handler = new ProtocolHandler(transport);
        AtomicBoolean closed = new AtomicBoolean(false);
        LuaRuntimeSession session = new LuaRuntimeSession(
                automation,
                new LuaScriptRuntimeService.RuntimeTarget("test", state, handler, null, "!11111111"),
                scriptService,
                Set.of(),
                false,
                new LuaAutomationCommand("channel", "0", "@adminbot", "@adminbot", "", List.of(), "admin-command"),
                events::add,
                request -> fail("Node picker must not be requested"),
                null,
                () -> closed.set(true));

        try {
            session.start();
            MeshProtos.ToRadio sent = transport.awaitToRadio();
            AdminProtos.AdminMessage request =
                    AdminProtos.AdminMessage.parseFrom(sent.getPacket().getDecoded().getPayload());
            assertTrue(request.hasGetConfigRequest());
            assertEquals(AdminProtos.AdminMessage.ConfigType.POWER_CONFIG, request.getGetConfigRequest());
            assertEquals(0x22222222, sent.getPacket().getTo());
            assertTrue(sent.getPacket().getPkiEncrypted());

            assertTrue(state.completePendingPacketAck(sent.getPacket().getId(), MeshProtos.Routing.Error.NONE));
            transport.emitFromRadio(MeshProtos.FromRadio.newBuilder()
                    .setPacket(remoteAdminResponse(
                            0x22222222,
                            0x11111111,
                            sent.getPacket().getId(),
                            AdminProtos.AdminMessage.newBuilder()
                                    .setSessionPasskey(ByteString.copyFromUtf8("remote-passkey"))
                                    .setGetConfigResponse(ConfigProtos.Config.newBuilder()
                                            .setPower(ConfigProtos.Config.PowerConfig.newBuilder()
                                                    .setLsSecs(300)))
                                    .build()))
                    .build());

            awaitCondition(closed::get, "Remote admin request automation did not finish");

            assertEquals("admin_result", scriptService.getKv(automation.getId(), "admin_type"));
            assertEquals("mesh.admin.request_config", scriptService.getKv(automation.getId(), "admin_source"));
            assertEquals("power_request", scriptService.getKv(automation.getId(), "admin_name"));
            assertEquals("request_config", scriptService.getKv(automation.getId(), "admin_action"));
            assertEquals("ok", scriptService.getKv(automation.getId(), "admin_status"));
            assertEquals("true", scriptService.getKv(automation.getId(), "admin_ok"));
            assertEquals("572662306", scriptService.getKv(automation.getId(), "admin_target"));
            assertEquals("300", scriptService.getKv(automation.getId(), "power_ls"));
            assertEquals("received", scriptService.getKv(automation.getId(), "query_state"));
            assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
        } finally {
            session.stop();
            handler.shutdown();
            state.shutdown();
        }
    }

    @Test
    void remoteAdminSaveConfigMergesLoadedSectionBeforeSending() throws Exception {
        LuaScript automation = scriptService.createScript(
                "automation-remote-admin-save",
                """
                local target = { node_num = 572662306, node_id = '!22222222', long_name = 'Remote' }

                function on_command(command)
                    mesh.admin.request_config(target, 'POWER_CONFIG')
                end

                function on_admin(event)
                    mesh.kv.set('last_action', event.action or '')
                    mesh.kv.set('last_status', event.status or '')
                    if event.action == 'request_config' then
                        mesh.admin.save_config(target, {
                            configs = {
                                power = {
                                    ls_secs = 600
                                }
                            }
                        }, { confirm = true })
                    elseif event.action == 'save_config' then
                        mesh.kv.set('save_status', event.status)
                        mesh.kv.set('save_ok', tostring(event.ok))
                    end
                end
                """,
                true,
                "",
                LuaScript.BotType.AUTOMATION_BOT,
                "@adminbot");
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x11111111);
        FakeTransportConnection transport = new FakeTransportConnection();
        ProtocolHandler handler = new ProtocolHandler(transport);
        AtomicBoolean closed = new AtomicBoolean(false);
        LuaRuntimeSession session = new LuaRuntimeSession(
                automation,
                new LuaScriptRuntimeService.RuntimeTarget("test", state, handler, null, "!11111111"),
                scriptService,
                Set.of(),
                false,
                new LuaAutomationCommand("channel", "0", "@adminbot", "@adminbot", "", List.of(), "admin-command"),
                events::add,
                request -> fail("Node picker must not be requested"),
                null,
                () -> closed.set(true));

        try {
            session.start();
            MeshProtos.ToRadio requestConfig = transport.awaitToRadio();
            assertTrue(state.completePendingPacketAck(requestConfig.getPacket().getId(), MeshProtos.Routing.Error.NONE));
            transport.emitFromRadio(MeshProtos.FromRadio.newBuilder()
                    .setPacket(remoteAdminResponse(
                            0x22222222,
                            0x11111111,
                            requestConfig.getPacket().getId(),
                            AdminProtos.AdminMessage.newBuilder()
                                    .setSessionPasskey(ByteString.copyFromUtf8("remote-passkey"))
                                    .setGetConfigResponse(ConfigProtos.Config.newBuilder()
                                            .setPower(ConfigProtos.Config.PowerConfig.newBuilder()
                                                    .setLsSecs(300)
                                                    .setMinWakeSecs(30)))
                                    .build()))
                    .build());

            MeshProtos.ToRadio begin = transport.awaitToRadio();
            assertTrue(AdminProtos.AdminMessage.parseFrom(begin.getPacket().getDecoded().getPayload())
                    .hasBeginEditSettings());
            assertTrue(state.completePendingPacketAck(begin.getPacket().getId(), MeshProtos.Routing.Error.NONE));

            MeshProtos.ToRadio setConfig = transport.awaitToRadio();
            AdminProtos.AdminMessage setConfigAdmin =
                    AdminProtos.AdminMessage.parseFrom(setConfig.getPacket().getDecoded().getPayload());
            assertTrue(setConfigAdmin.hasSetConfig());
            assertEquals(600, setConfigAdmin.getSetConfig().getPower().getLsSecs());
            assertEquals(30, setConfigAdmin.getSetConfig().getPower().getMinWakeSecs());
            assertEquals("remote-passkey", setConfigAdmin.getSessionPasskey().toStringUtf8());
            assertEquals(Portnums.PortNum.ADMIN_APP, setConfig.getPacket().getDecoded().getPortnum());
            assertTrue(state.completePendingPacketAck(setConfig.getPacket().getId(), MeshProtos.Routing.Error.NONE));

            MeshProtos.ToRadio commit = transport.awaitToRadio();
            assertTrue(AdminProtos.AdminMessage.parseFrom(commit.getPacket().getDecoded().getPayload())
                    .hasCommitEditSettings());
            assertTrue(state.completePendingPacketAck(commit.getPacket().getId(), MeshProtos.Routing.Error.NONE));

            awaitCondition(closed::get, "Remote admin save automation did not finish");

            assertEquals("save_config", scriptService.getKv(automation.getId(), "last_action"));
            assertEquals("ok", scriptService.getKv(automation.getId(), "last_status"));
            assertEquals("ok", scriptService.getKv(automation.getId(), "save_status"));
            assertEquals("true", scriptService.getKv(automation.getId(), "save_ok"));
            assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
        } finally {
            session.stop();
            handler.shutdown();
            state.shutdown();
        }
    }

    @Test
    void scriptSettingsArePersisted() {
        LuaScript script = scriptService.createScript("settings", "mesh.log('ok')");
        String description = """
                Назначение:
                Проверяет сохранение длинного описания.

                Параметры:
                Несколько строк должны храниться без потери переносов.
                """;

        LuaScript saved = scriptService.saveScriptSettings(
                script.getId(),
                "settings-renamed",
                false,
                LuaScript.DEFAULT_ICON,
                "!abcdef12",
                LuaScript.BotType.AUTOMATION_BOT,
                "@bot_1",
                description);

        assertEquals("settings-renamed", saved.getName());
        assertFalse(saved.isAutostart());
        assertEquals("", saved.getNodeId());
        assertEquals(LuaScript.BotType.AUTOMATION_BOT, saved.getBotType());
        assertEquals("@bot_1", saved.getAutomationName());
        assertEquals(description, saved.getDescription());

        LuaScript reloaded = scriptService.findScript(script.getId()).orElseThrow();
        assertEquals("settings-renamed", reloaded.getName());
        assertFalse(reloaded.isAutostart());
        assertEquals("", reloaded.getNodeId());
        assertEquals(LuaScript.BotType.AUTOMATION_BOT, reloaded.getBotType());
        assertEquals("@bot_1", reloaded.getAutomationName());
        assertEquals(description, reloaded.getDescription());
    }

    @Test
    void scriptVersionIncrementsOnlyForCodeChanges() {
        LuaScript script = scriptService.createScript("versioned", "mesh.log('one')");

        assertEquals(1L, script.getVersion());

        LuaScript unchanged = scriptService.saveScript(
                script.getId(),
                "versioned",
                "mesh.log('one')",
                true);
        assertEquals(1L, unchanged.getVersion());

        LuaScript codeChanged = scriptService.saveScript(
                script.getId(),
                "versioned",
                "mesh.log('two')",
                true);
        assertEquals(2L, codeChanged.getVersion());

        LuaScript settingsChanged = scriptService.saveScriptSettings(
                script.getId(),
                "versioned",
                false,
                LuaScript.DEFAULT_ICON,
                "!abcdef12",
                LuaScript.BotType.AIR_BOT,
                "",
                "Line one\nLine two");
        assertEquals(2L, settingsChanged.getVersion());

        scriptService.updateRunState(script.getId(), "DONE", null);
        scriptService.setKv(script.getId(), "runtime", "value");

        LuaScript afterRuntimeChanges = scriptService.findScript(script.getId()).orElseThrow();
        assertEquals(2L, afterRuntimeChanges.getVersion());
    }

    @Test
    void draftScriptIsNotPersistedUntilCreatedWithSettings() {
        LuaScript draft = scriptService.createDraftScript();

        assertEquals(0L, draft.getId());
        assertTrue(scriptService.listScripts().isEmpty());

        LuaScript created = scriptService.createScript(
                draft.getName(),
                draft.getCode(),
                false,
                "!abcdef12",
                LuaScript.BotType.AUTOMATION_BOT,
                "@bot_1");

        assertEquals(1, scriptService.listScripts().size());
        assertEquals(draft.getName(), created.getName());
        assertEquals(draft.getCode(), created.getCode());
        assertFalse(created.isAutostart());
        assertEquals("", created.getNodeId());
        assertEquals(LuaScript.BotType.AUTOMATION_BOT, created.getBotType());
        assertEquals("@bot_1", created.getAutomationName());
    }

    @Test
    void sandboxDoesNotExposeUnsafeLibraries() {
        LuaScript script = scriptService.createScript("sandbox", String.join("\n",
                "mesh.kv.set('debug', tostring(debug))",
                "mesh.kv.set('os', tostring(os))",
                "mesh.kv.set('io', tostring(io))",
                "mesh.kv.set('package', tostring(package))",
                "mesh.kv.set('require', tostring(require))",
                "mesh.kv.set('luajava', tostring(luajava))"));

        runtimeService.runScript(script, events::add);

        awaitCondition(() -> "nil".equals(scriptService.getKv(script.getId(), "luajava")),
                "Lua sandbox script did not finish");

        assertEquals("nil", scriptService.getKv(script.getId(), "debug"));
        assertEquals("nil", scriptService.getKv(script.getId(), "os"));
        assertEquals("nil", scriptService.getKv(script.getId(), "io"));
        assertEquals("nil", scriptService.getKv(script.getId(), "package"));
        assertEquals("nil", scriptService.getKv(script.getId(), "require"));
        assertEquals("nil", scriptService.getKv(script.getId(), "luajava"));
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void debugScriptRunsToBreakpointCapturesVariablesAndContinues() {
        LuaScript script = scriptService.createScript("debug", String.join("\n",
                "global_count = 7",
                "local count = 1",
                "count = count + 1",
                "mesh.kv.set('count', tostring(count))"));

        runtimeService.debugScript(script, Set.of(3), events::add);

        awaitCondition(() -> runtimeService.isPaused(script.getId())
                        && runtimeService.debugSnapshot(script.getId())
                        .map(snapshot -> snapshot.line() == 3)
                        .orElse(false),
                "Lua debugger did not pause on breakpoint");
        LuaDebugSnapshot pause = runtimeService.debugSnapshot(script.getId()).orElseThrow();
        assertEquals(3, pause.line());
        assertTrue(pause.variables().stream().anyMatch(variable ->
                "local".equals(variable.scope())
                        && "count".equals(variable.name())
                        && "1".equals(variable.value())));
        assertTrue(pause.variables().stream().anyMatch(variable ->
                "global".equals(variable.scope())
                        && "global_count".equals(variable.name())
                        && "7".equals(variable.value())));

        runtimeService.debugContinue(script.getId());

        awaitCondition(() -> !runtimeService.isRunning(script.getId()), "Lua debugger did not finish");

        assertEquals("2", scriptService.getKv(script.getId(), "count"));
        assertEquals(1, events.stream()
                .filter(event -> event.type() == LuaScriptEvent.Type.DEBUG_PAUSED)
                .count());
        assertTrue(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.DEBUG_RESUMED));
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void debugScriptWithoutBreakpointsPausesOnStartForManualStepping() {
        LuaScript script = scriptService.createScript("debug-step", String.join("\n",
                "local count = 1",
                "count = count + 1"));

        runtimeService.debugScript(script, Set.of(), events::add);

        awaitCondition(() -> runtimeService.isPaused(script.getId()), "Lua debugger did not pause on start");
        LuaDebugSnapshot pause = runtimeService.debugSnapshot(script.getId()).orElseThrow();
        assertEquals(1, pause.line());

        runtimeService.debugContinue(script.getId());

        awaitCondition(() -> !runtimeService.isRunning(script.getId()), "Lua debugger did not finish");
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    @Test
    void debugExtensionRunsWithEmbeddedFormContext() {
        LuaScript extension = scriptService.createScript(
                "debug-extension",
                String.join("\n",
                        "function on_extension_open(event)",
                        "    mesh.form.set_title('Debug Extension')",
                        "    local count = 1",
                        "    count = count + 1",
                        "    mesh.kv.set('count', tostring(count))",
                        "    mesh.form.add({ type = 'label', id = 'status', text = tostring(count) })",
                        "end"),
                true,
                "",
                LuaScript.BotType.EXTENSION,
                "");
        FakeFormBridge formBridge = new FakeFormBridge();

        runtimeService.debugExtension(extension, formBridge, Set.of(4), events::add);

        awaitCondition(() -> runtimeService.isPaused(extension.getId())
                        && runtimeService.debugSnapshot(extension.getId())
                        .map(snapshot -> snapshot.line() == 4)
                        .orElse(false),
                "Extension debugger did not pause inside on_extension_open");

        LuaDebugSnapshot pause = runtimeService.debugSnapshot(extension.getId()).orElseThrow();
        assertTrue(pause.variables().stream().anyMatch(variable ->
                "local".equals(variable.scope())
                        && "count".equals(variable.name())
                        && "1".equals(variable.value())));

        runtimeService.debugContinue(extension.getId());

        awaitCondition(() -> "2".equals(scriptService.getKv(extension.getId(), "count")),
                "Extension debugger did not continue after breakpoint");

        assertEquals("Debug Extension", formBridge.title);
        assertEquals("2", formBridge.components.get("status").text());
        assertTrue(runtimeService.isRunning(extension.getId()));
        assertFalse(events.stream().anyMatch(event -> event.type() == LuaScriptEvent.Type.ERROR));
    }

    private static void awaitCondition(BooleanSupplier condition, String timeoutMessage) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for condition");
            }
        }
        fail(timeoutMessage);
    }

    private static void assertValidGuid(String guid) {
        assertNotNull(guid);
        assertEquals(guid, UUID.fromString(guid).toString());
    }

    private static MeshProtos.MeshPacket remoteAdminResponse(int from,
                                                             int to,
                                                             int requestId,
                                                             AdminProtos.AdminMessage adminMessage) {
        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.ADMIN_APP)
                .setPayload(adminMessage.toByteString())
                .setRequestId(requestId)
                .build();
        return MeshProtos.MeshPacket.newBuilder()
                .setFrom(from)
                .setTo(to)
                .setDecoded(data)
                .build();
    }

    private static MeshProtos.ToRadio parseToRadio(byte[] frame) throws Exception {
        int payloadLength = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        byte[] payload = new byte[payloadLength];
        System.arraycopy(frame, 4, payload, 0, payloadLength);
        return MeshProtos.ToRadio.parseFrom(payload);
    }

    private static final class FakeFormBridge implements LuaFormBridge {
        private final Map<String, FakeComponent> components = new ConcurrentHashMap<>();
        private volatile String title = "";
        private volatile boolean open = true;

        @Override
        public boolean isFormAvailable() {
            return open;
        }

        @Override
        public boolean isFormOpen() {
            return open;
        }

        @Override
        public void showForm() {
            open = true;
        }

        @Override
        public void setFormTitle(String title) {
            this.title = title;
        }

        @Override
        public void clearForm() {
            components.clear();
        }

        @Override
        public String addFormComponent(LuaFormComponentSpec spec) {
            String id = spec.id() != null && !spec.id().isBlank()
                    ? spec.id()
                    : "component_" + (components.size() + 1);
            components.put(id, new FakeComponent(
                    spec.type(),
                    spec.text(),
                    spec.value(),
                    spec));
            return id;
        }

        @Override
        public void updateFormComponent(String id, LuaFormComponentSpec spec) {
            FakeComponent existing = components.get(id);
            components.put(id, new FakeComponent(
                    spec.type() != null ? spec.type() : existing != null ? existing.type() : "",
                    spec.text() != null ? spec.text() : existing != null ? existing.text() : "",
                    spec.value() != null ? spec.value() : existing != null ? existing.value() : null,
                    spec));
        }

        @Override
        public void removeFormComponent(String id) {
            components.remove(id);
        }

        @Override
        public Object formComponentValue(String id) {
            FakeComponent component = components.get(id);
            return component != null ? component.value() : null;
        }
    }

    private record FakeComponent(String type, String text, Object value, LuaFormComponentSpec spec) {}

    private static final class FakeTransportConnection implements TransportConnection {
        private final BlockingQueue<byte[]> writes = new LinkedBlockingQueue<>();
        private volatile Consumer<byte[]> dataListener;
        private volatile ConnectionListener connectionListener;
        private volatile boolean connected = true;

        @Override
        public void connect() throws ConnectionException {
            connected = true;
            if (connectionListener != null) {
                connectionListener.onConnected();
            }
        }

        @Override
        public void disconnect() {
            connected = false;
            if (connectionListener != null) {
                connectionListener.onDisconnected();
            }
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void sendBytes(byte[] data) {
            writes.offer(data);
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            dataListener = listener;
        }

        @Override
        public void setConnectionListener(ConnectionListener listener) {
            connectionListener = listener;
        }

        int writeCount() {
            return writes.size();
        }

        MeshProtos.ToRadio awaitToRadio() throws Exception {
            byte[] frame = writes.poll(1, TimeUnit.SECONDS);
            if (frame == null) {
                throw new AssertionError("Timed out waiting for outbound frame");
            }
            return parseToRadio(frame);
        }

        void emitFromRadio(MeshProtos.FromRadio fromRadio) {
            Consumer<byte[]> listener = dataListener;
            if (listener == null) {
                throw new AssertionError("No data listener registered");
            }
            listener.accept(fromRadio.toByteArray());
        }
    }
}
