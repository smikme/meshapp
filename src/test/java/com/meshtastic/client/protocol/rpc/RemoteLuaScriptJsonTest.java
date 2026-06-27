package com.meshtastic.client.protocol.rpc;

import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptEvent;
import com.meshtastic.client.lua.LuaScriptService;
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
}
