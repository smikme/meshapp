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

        assertItemsContain(result, "send_channel(channel, text)");
        assertItemsContain(result, "send_dm(node_id, text)");
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
        assertItemsContain(result, "send_channel(channel, text)");
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
