package com.meshtastic.client.lua;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class LuaScriptStoreServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchScriptsLoadsDirectoryAndScriptExportsFromGiteaShape() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/scripts", exchange -> sendJson(exchange, """
                [
                  {
                    "name": "Ping-Bot.meshapp-script.json",
                    "type": "file",
                    "download_url": "%s/raw/Ping-Bot.meshapp-script.json"
                  },
                  {
                    "name": "Automation.meshapp-script.json",
                    "type": "file",
                    "download_url": "%s/raw/Automation.meshapp-script.json"
                  },
                  {
                    "name": "README.md",
                    "type": "file",
                    "download_url": "%s/raw/README.md"
                  }
                ]
                """.formatted(baseUrl, baseUrl, baseUrl)));
        server.createContext("/raw/Ping-Bot.meshapp-script.json", exchange -> sendJson(exchange, """
                {
                  "format": "meshapp-lua-script",
                  "version": 1,
                  "scriptVersion": 5,
                  "guid": "96c3f915-87c6-435e-a6c5-c7ffe6f94d1b",
                  "icon": "🤖",
                  "name": "Ping Bot",
                  "description": "Replies to ping",
                  "codeLines": [
                    "mesh.log('ping')"
                  ],
                  "botType": "AIR_BOT",
                  "automationName": ""
                }
                """));
        server.createContext("/raw/Automation.meshapp-script.json", exchange -> sendJson(exchange, """
                {
                  "format": "meshapp-lua-script",
                  "version": 1,
                  "scriptVersion": 2,
                  "guid": "d23f4f14-20e4-47db-baf9-6f698120ff04",
                  "icon": "⚙️",
                  "name": "Automation",
                  "description": "Runs an automation command",
                  "codeLines": [
                    "mesh.log('automation')"
                  ],
                  "botType": "AUTOMATION_BOT",
                  "automationName": "@auto"
                }
                """));
        server.start();

        LuaScriptStoreService service = new LuaScriptStoreService(
                URI.create(baseUrl + "/api/scripts"),
                HttpClient.newHttpClient());

        List<LuaScriptStoreService.StoreScript> scripts = service.fetchScripts();

        assertEquals(2, scripts.size());
        LuaScriptStoreService.StoreScript automation = scripts.getFirst();
        assertEquals("d23f4f14-20e4-47db-baf9-6f698120ff04", automation.guid());
        assertEquals("⚙️", automation.icon());
        assertEquals("Automation", automation.name());
        assertEquals(2L, automation.version());
        assertEquals(LuaScript.BotType.AUTOMATION_BOT, automation.botType());

        LuaScriptStoreService.StoreScript script = scripts.get(1);
        assertEquals("96c3f915-87c6-435e-a6c5-c7ffe6f94d1b", script.guid());
        assertEquals("🤖", script.icon());
        assertEquals("Ping Bot", script.name());
        assertEquals(5L, script.version());
        assertEquals(LuaScript.BotType.AIR_BOT, script.botType());
        assertEquals("Replies to ping", script.description());
        assertEquals("mesh.log('ping')", String.join("\n", script.exportFile().codeLines()));
    }

    private static void sendJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
