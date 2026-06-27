package com.meshtastic.client.protocol.rpc;

import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptEvent;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.lua.LuaAutomationCommand;
import com.meshtastic.client.lua.LuaFormComponentSpec;
import com.meshtastic.client.lua.LuaFormEvent;
import com.meshtastic.client.lua.LuaUiBotNotice;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class RemoteLuaScriptJsonTest {

    @Test
    void roundTripsScriptsAndImportResult() {
        LuaScript script = new LuaScript(
                42L,
                "guid-1",
                "🤖",
                "Remote Bot",
                "print('ok')",
                3L,
                "Description",
                "Author",
                true,
                "!12345678",
                LuaScript.BotType.AIR_BOT,
                "",
                100L,
                200L,
                300L,
                "OK",
                null);

        List<LuaScript> parsedScripts = RemoteLuaScriptJson.parseScripts(
                RemoteLuaScriptJson.scriptsToJson(List.of(script), null));
        LuaScript parsed = parsedScripts.getFirst();

        assertEquals(42L, parsed.getId());
        assertEquals("guid-1", parsed.getGuid());
        assertEquals("Remote Bot", parsed.getName());
        assertEquals("print('ok')", parsed.getCode());
        assertEquals(3L, parsed.getVersion());
        assertEquals("Description", parsed.getDescription());
        assertEquals("Author", parsed.getAuthor());
        assertTrue(parsed.isEnabled());
        assertEquals("!12345678", parsed.getNodeId());
        assertEquals(LuaScript.BotType.AIR_BOT, parsed.getBotType());
        assertEquals("OK", parsed.getLastStatus());

        LuaScriptService.ScriptImportResult importResult =
                new LuaScriptService.ScriptImportResult(script, true);
        LuaScriptService.ScriptImportResult parsedImport =
                RemoteLuaScriptJson.parseImportResult(RemoteLuaScriptJson.importResultToJson(importResult, null));

        assertTrue(parsedImport.updated());
        assertEquals(42L, parsedImport.script().getId());
    }

    @Test
    void roundTripsKvRuntimeEventAndState() {
        Map<String, String> kv = RemoteLuaScriptJson.parseKv(
                RemoteLuaScriptJson.kvToJson(Map.of("alpha", "one", "beta", "")));

        assertEquals("one", kv.get("alpha"));
        assertEquals("", kv.get("beta"));
        assertTrue(RemoteLuaScriptJson.parseDeleted(RemoteLuaScriptJson.deletedResult(true)));
        assertFalse(RemoteLuaScriptJson.parseDeleted(RemoteLuaScriptJson.deletedResult(false)));

        LuaScriptEvent event = RemoteLuaScriptJson.parseEvent(
                RemoteLuaScriptJson.eventToJson(LuaScriptEvent.warning(42L, "warn")));

        assertEquals(LuaScriptEvent.Type.WARNING, event.type());
        assertEquals(42L, event.scriptId());
        assertEquals("warn", event.message());
    }

    @Test
    void roundTripsAutomationAndExtensionRpcPayloads() {
        LuaAutomationCommand command = new LuaAutomationCommand(
                "channel",
                "0",
                "@auto",
                "@auto alpha",
                "alpha",
                List.of("alpha"),
                "request-1");

        LuaAutomationCommand parsedCommand = RemoteLuaScriptJson.parseAutomationCommand(
                RemoteLuaScriptJson.automationCommandParams(42L, command));

        assertEquals("@auto", parsedCommand.handle());
        assertEquals("alpha", parsedCommand.arguments());
        assertEquals("request-1", parsedCommand.requestId());

        LuaUiBotNotice notice = new LuaUiBotNotice(
                42L,
                "mesh.chat.bot_notice",
                "progress",
                "channel",
                "0",
                "Working");
        LuaScriptEvent parsedNoticeEvent = RemoteLuaScriptJson.parseEvent(
                RemoteLuaScriptJson.eventToJson(LuaScriptEvent.uiBotNotice(42L, notice)));

        assertEquals(LuaScriptEvent.Type.UI_BOT_NOTICE, parsedNoticeEvent.type());
        LuaUiBotNotice parsedNotice = (LuaUiBotNotice) parsedNoticeEvent.payload();
        assertEquals("progress", parsedNotice.name());
        assertEquals("Working", parsedNotice.text());

        LuaScript script = new LuaScript(
                42L,
                "extension",
                "",
                true,
                "",
                LuaScript.BotType.EXTENSION,
                "",
                0L,
                0L,
                0L,
                "NEW",
                null);
        LuaFormComponentSpec spec = new LuaFormComponentSpec(
                "run",
                "button",
                "root",
                "Run",
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        RemoteLuaScriptJson.FormCommand formCommand = RemoteLuaScriptJson.parseFormCommand(
                RemoteLuaScriptJson.formCommandToJson(script, "add", "", "run", "", spec));

        assertEquals(42L, formCommand.scriptId());
        assertEquals("add", formCommand.command());
        assertEquals("run", formCommand.spec().id());
        assertEquals("button", formCommand.spec().type());

        LuaFormEvent formEvent = RemoteLuaScriptJson.parseFormEvent(
                RemoteLuaScriptJson.formEventParams(new LuaFormEvent(42L, "run", "action", true, "Run")));
        assertEquals("run", formEvent.componentId());
        assertEquals("action", formEvent.type());
        assertEquals("Run", formEvent.text());
    }
}
