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
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
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
    void tracerouteRequestDeliversResultToAutomationCallback() {
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
    void scriptVersionIncrementsOnlyForPersistedScriptModifications() {
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
        assertEquals(3L, settingsChanged.getVersion());

        scriptService.updateRunState(script.getId(), "DONE", null);
        scriptService.setKv(script.getId(), "runtime", "value");

        LuaScript afterRuntimeChanges = scriptService.findScript(script.getId()).orElseThrow();
        assertEquals(3L, afterRuntimeChanges.getVersion());
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
    }
}
