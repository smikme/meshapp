package com.meshtastic.client.lua;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaCompletionEngineTest {

    private final LuaCompletionEngine engine = new LuaCompletionEngine();

    @Test
    void completesRenamedOnMessageParameterAsMessageObject() {
        LuaCompletionEngine.CompletionResult result = complete("""
                function on_message(message)
                    message.te|
                end
                """);

        assertItemsContain(result, "text");
        assertEquals("text", item(result, "text").insertText());
    }

    @Test
    void completesMessageChannelMetadataFields() {
        LuaCompletionEngine.CompletionResult result = complete("""
                function on_message(msg)
                    msg.channel_|
                end
                """);

        assertItemsContain(result, "channel_name");
        assertItemsContain(result, "channel_role");
    }

    @Test
    void completesMessageReplyFields() {
        LuaCompletionEngine.CompletionResult result = complete("""
                function on_message(msg)
                    msg.reply_|
                end
                """);

        assertItemsContain(result, "reply_id");
        assertItemsContain(result, "reply_text");
    }

    @Test
    void completesMessageHopFields() {
        LuaCompletionEngine.CompletionResult result = complete("""
                function on_message(msg)
                    msg.hop|
                end
                """);

        assertItemsContain(result, "hop_start");
        assertItemsContain(result, "hop_limit");
        assertItemsContain(result, "hops");
    }

    @Test
    void infersOwnerReturnTypeFromAssignment() {
        LuaCompletionEngine.CompletionResult result = complete("""
                local owner = mesh.owner()
                owner.node|
                """);

        assertItemsContain(result, "node_id");
        assertItemsContain(result, "node_num");
    }

    @Test
    void infersLoopItemTypeFromMeshNodesCall() {
        LuaCompletionEngine.CompletionResult result = complete("""
                for _, node in ipairs(mesh.chat.nodes()) do
                    node.|
                end
                """);

        assertItemsContain(result, "long_name");
        assertItemsContain(result, "short_name");
        assertItemsContain(result, "voltage");
        assertItemsContain(result, "public_key");
    }

    @Test
    void completesUserTableFieldsAndFunctions() {
        LuaCompletionEngine.CompletionResult result = complete("""
                local cfg = {
                    interval = 5,
                    run = function()
                    end
                }
                cfg.|
                """);

        assertItemsContain(result, "interval");
        assertItemsContain(result, "run(...)");
    }

    @Test
    void completesFieldsFromMemberAssignmentsAndDeclaredMethods() {
        LuaCompletionEngine.CompletionResult result = complete("""
                local bot = {}
                bot.interval = 5
                function bot.run()
                end
                bot.|
                """);

        assertItemsContain(result, "interval");
        assertItemsContain(result, "run(...)");
    }

    @Test
    void completesStandardLibraryMembersAndStringMethods() {
        LuaCompletionEngine.CompletionResult result = complete("""
                local value = "hello"
                value:su|
                """);

        assertItemsContain(result, "sub(s, i, j)");
        LuaCompletionEngine.CompletionResult mathResult = complete("math.flo|");
        assertItemsContain(mathResult, "floor(x)");
    }

    @Test
    void completesMeshApiThroughAliases() {
        LuaCompletionEngine.CompletionResult result = complete("""
                local chat = mesh.chat
                chat.send_|
                """);

        assertItemsContain(result, "send_channel(channel, text, reply_id)");
        assertItemsContain(result, "send_dm(node_id, text, reply_id)");
    }

    @Test
    void completesLocalBotApiOnMeshChat() {
        LuaCompletionEngine.CompletionResult result = complete("""
                local chat = mesh.chat
                chat.|
                """);

        assertItemsContain(result, "reply(msg, text)");
        assertItemsContain(result, "bot_message(chat_type, chat_key, text)");
        assertItemsContain(result, "bot_reply(msg, text)");
        assertItemsContain(result, "bot_notice(chat_type, chat_key, text, options)");
    }

    @Test
    void completesUiPickNodeAndAutomationCallbacks() {
        LuaCompletionEngine.CompletionResult uiResult = complete("""
                mesh.ui.|
                """);
        assertItemsContain(uiResult, "pick_node(options)");

        LuaCompletionEngine.CompletionResult commandResult = complete("""
                function on_command(command)
                    command.|
                end
                """);
        assertItemsContain(commandResult, "chat_type");
        assertItemsContain(commandResult, "request_id");
        assertItemsContain(commandResult, "source");
        assertItemsContain(commandResult, "argument_tokens");

        LuaCompletionEngine.CompletionResult selectionResult = complete("""
                function on_node_selected(event)
                    event.|
                end
        """);
        assertItemsContain(selectionResult, "node");
        assertItemsContain(selectionResult, "name");
        assertItemsContain(selectionResult, "selected");

        LuaCompletionEngine.CompletionResult selectedNodeResult = complete("""
                function on_node_selected(event)
                    event.node.|
                end
                """);
        assertItemsContain(selectedNodeResult, "node_id");
        assertItemsContain(selectedNodeResult, "long_name");
    }

    @Test
    void completesTracerouteApiAndCallbackEvent() {
        LuaCompletionEngine.CompletionResult apiResult = complete("""
                mesh.traceroute.|
                """);
        assertItemsContain(apiResult, "request(target, options)");

        LuaCompletionEngine.CompletionResult eventResult = complete("""
                function on_traceroute(event)
                    event.|
                end
                """);
        assertItemsContain(eventResult, "request_id");
        assertItemsContain(eventResult, "target_node_num");
        assertItemsContain(eventResult, "response_from_node_num");
        assertItemsContain(eventResult, "route");

        LuaCompletionEngine.CompletionResult routeResult = complete("""
                function on_traceroute(event)
                    event.route.|
                end
                """);
        assertItemsContain(routeResult, "snr_towards");
        assertItemsContain(routeResult, "route_ids");
        assertItemsContain(routeResult, "route_back");
    }

    @Test
    void completesNodeInfoApiAndCallbackEvent() {
        LuaCompletionEngine.CompletionResult apiResult = complete("""
                mesh.nodeinfo.|
                """);
        assertItemsContain(apiResult, "request(target, options)");

        LuaCompletionEngine.CompletionResult eventResult = complete("""
                function on_node_info(event)
                    event.|
                end
                """);
        assertItemsContain(eventResult, "request_id");
        assertItemsContain(eventResult, "target_node_num");
        assertItemsContain(eventResult, "cached");
        assertItemsContain(eventResult, "node");

        LuaCompletionEngine.CompletionResult nodeResult = complete("""
                function on_node_info(event)
                    event.node.|
                end
                """);
        assertItemsContain(nodeResult, "node_id");
        assertItemsContain(nodeResult, "voltage");
        assertItemsContain(nodeResult, "public_key");
        assertItemsContain(nodeResult, "uptime_seconds");
    }

    @Test
    void completesRemoteAdminApiAndCallbackEvent() {
        LuaCompletionEngine.CompletionResult apiResult = complete("""
                mesh.admin.|
                """);
        assertItemsContain(apiResult, "load_config(target, options)");
        assertItemsContain(apiResult, "save_config(target, changes, options)");
        assertItemsContain(apiResult, "factory_reset_device(target, options)");

        LuaCompletionEngine.CompletionResult eventResult = complete("""
                function on_admin(event)
                    event.|
                end
                """);
        assertItemsContain(eventResult, "action");
        assertItemsContain(eventResult, "target_node_num");
        assertItemsContain(eventResult, "snapshot");

        LuaCompletionEngine.CompletionResult snapshotResult = complete("""
                function on_admin(event)
                    event.snapshot.|
                end
                """);
        assertItemsContain(snapshotResult, "configs");
        assertItemsContain(snapshotResult, "module_configs");
        assertItemsContain(snapshotResult, "query_statuses");
    }

    @Test
    void completesTelemetryApiAndFields() {
        LuaCompletionEngine.CompletionResult apiResult = complete("""
                mesh.telemetry.|
                """);
        assertItemsContain(apiResult, "recent(options)");
        assertItemsContain(apiResult, "for_node(node_id, options)");
        assertItemsContain(apiResult, "query(options)");
        assertItemsContain(apiResult, "latest(node_id)");

        LuaCompletionEngine.CompletionResult fieldResult = complete("""
                local rows = mesh.telemetry.query({ node_id = "!bbbbbbbb" })
                for _, row in ipairs(rows) do
                    row.gas_|
                end
                """);
        assertItemsContain(fieldResult, "gas_resistance");

        LuaCompletionEngine.CompletionResult radiationResult = complete("""
                local latest = mesh.telemetry.latest("!bbbbbbbb")
                latest.rad|
                """);
        assertItemsContain(radiationResult, "radiation");
    }

    @Test
    void completesCurlApiAndResponseFields() {
        LuaCompletionEngine.CompletionResult curlResult = complete("""
                local curl = mesh.curl
                curl.|
                """);

        assertItemsContain(curlResult, "get(url, options)");
        assertItemsContain(curlResult, "request(options)");

        LuaCompletionEngine.CompletionResult responseResult = complete("""
                local response = mesh.curl.get("https://example.com")
                response.st|
                """);

        assertItemsContain(responseResult, "status");
    }

    @Test
    void completesLuaConstructsUserVariablesAndFunctionsAtRoot() {
        LuaCompletionEngine.CompletionResult result = complete("""
                local counter = 0
                function send_alert(text)
                end
                co|
                """);

        assertItemsContain(result, "counter");
        LuaCompletionEngine.CompletionResult snippets = engine.complete("", 0, true);
        assertTrue(displayTexts(snippets).stream().anyMatch(text -> text.startsWith("for ")));
    }

    @Test
    void replacesOnlyMemberPrefixAfterDot() {
        String code = "mesh.chat.send_";
        LuaCompletionEngine.CompletionResult result = engine.complete(code, code.length(), true);

        assertEquals("send_", code.substring(result.replaceStart(), result.replaceEnd()));
        assertItemsContain(result, "send_channel(channel, text, reply_id)");
    }

    private LuaCompletionEngine.CompletionResult complete(String markedCode) {
        int caret = markedCode.indexOf('|');
        assertTrue(caret >= 0, "Missing caret marker");
        String code = markedCode.substring(0, caret) + markedCode.substring(caret + 1);
        return engine.complete(code, caret, true);
    }

    private void assertItemsContain(LuaCompletionEngine.CompletionResult result, String displayText) {
        assertTrue(displayTexts(result).contains(displayText),
                () -> "Expected " + displayText + " in " + displayTexts(result));
    }

    private LuaCompletionEngine.CompletionItem item(LuaCompletionEngine.CompletionResult result, String displayText) {
        return result.items().stream()
                .filter(candidate -> candidate.displayText().equals(displayText))
                .findFirst()
                .orElseThrow();
    }

    private List<String> displayTexts(LuaCompletionEngine.CompletionResult result) {
        return result.items().stream().map(LuaCompletionEngine.CompletionItem::displayText).toList();
    }
}
