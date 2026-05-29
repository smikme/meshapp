package com.meshtastic.client.lua;

import com.meshtastic.client.TestEnvironmentSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class LuaScriptImportExportTest {

    @TempDir
    Path tempHome;

    private LuaScriptService scriptService;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        scriptService = LuaScriptService.getInstance();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void exportAndImportCreatesScriptWithOriginalGuidAndProperties() throws Exception {
        String code = "mesh.log('hello')\nmesh.log('second line')";
        String description = """
                Назначение:
                Проверяет перенос описания между установками.

                Детали:
                Описание может занимать несколько строк и оставаться частью JSON-экспорта.
                """;
        LuaScript script = scriptService.createScript(
                "portable",
                code,
                false,
                "🚀",
                "!abcdef12",
                LuaScript.BotType.AIR_BOT,
                "",
                description);
        Path exportFile = tempHome.resolve("portable.json");

        scriptService.exportScript(script.getId(), exportFile);

        String json = Files.readString(exportFile);
        assertTrue(json.contains("\"format\": \"meshapp-lua-script\""));
        assertTrue(json.contains("\"scriptVersion\": 1"));
        assertTrue(json.contains("\"guid\": \"" + script.getGuid() + "\""));
        assertTrue(json.contains("\"description\":"));
        assertTrue(json.contains("\"codeLines\": ["));
        assertTrue(json.contains("\"mesh.log('hello')\""));
        assertTrue(json.contains("\"mesh.log('second line')\""));
        assertFalse(json.contains("\"code\":"));

        scriptService.deleteScript(script.getId());
        LuaScriptService.ScriptImportResult result = scriptService.importScript(exportFile);

        assertFalse(result.updated());
        LuaScript imported = result.script();
        assertEquals(script.getGuid(), imported.getGuid());
        assertEquals("portable", imported.getName());
        assertEquals(code, imported.getCode());
        assertEquals("🚀", imported.getIcon());
        assertEquals(1L, imported.getVersion());
        assertEquals(description, imported.getDescription());
        assertFalse(imported.isAutostart());
        assertEquals("!abcdef12", imported.getNodeId());
        assertEquals(LuaScript.BotType.AIR_BOT, imported.getBotType());
    }

    @Test
    void importWithExistingGuidUpdatesScriptInsteadOfCreatingDuplicate() throws Exception {
        LuaScript script = scriptService.createScript(
                "same-guid",
                "mesh.log('old')",
                true,
                "🤖",
                "!abcdef12",
                LuaScript.BotType.AIR_BOT,
                "");
        Path exportFile = tempHome.resolve("same-guid.json");
        scriptService.exportScript(script.getId(), exportFile);

        Files.writeString(exportFile, """
                {
                  "format": "meshapp-lua-script",
                  "version": 1,
                  "scriptVersion": 7,
                  "guid": "%s",
                  "icon": "🛰️",
                  "name": "same-guid-updated",
                  "description": "Updated description\\nwith multiple lines",
                  "codeLines": [
                    "mesh.log('new')"
                  ],
                  "autostart": false,
                  "nodeId": "!00000001",
                  "botType": "AIR_BOT",
                  "automationName": ""
                }
                """.formatted(script.getGuid()));

        LuaScriptService.ScriptImportResult result = scriptService.importScript(exportFile);

        assertTrue(result.updated());
        LuaScript updated = result.script();
        assertEquals(script.getId(), updated.getId());
        assertEquals(script.getGuid(), updated.getGuid());
        assertEquals("same-guid-updated", updated.getName());
        assertEquals("mesh.log('new')", updated.getCode());
        assertEquals("🛰️", updated.getIcon());
        assertEquals(2L, updated.getVersion());
        assertEquals("Updated description\nwith multiple lines", updated.getDescription());
        assertFalse(updated.isAutostart());
        assertEquals("!00000001", updated.getNodeId());
        assertEquals(1, scriptService.listScripts().size());
    }

    @Test
    void importReadsLegacyCodeStringField() throws Exception {
        Path source = tempHome.resolve("legacy-code.json");
        Files.writeString(source, """
                {
                  "format": "meshapp-lua-script",
                  "version": 1,
                  "guid": "123e4567-e89b-12d3-a456-426614174000",
                  "icon": "🤖",
                  "name": "legacy",
                  "code": "mesh.log('legacy')\\nmesh.log('code')",
                  "autostart": true,
                  "nodeId": "!abcdef12",
                  "botType": "AIR_BOT",
                  "automationName": ""
                }
                """);

        LuaScriptService.ScriptImportResult result = scriptService.importScript(source);

        assertFalse(result.updated());
        assertEquals("mesh.log('legacy')\nmesh.log('code')", result.script().getCode());
    }
}
