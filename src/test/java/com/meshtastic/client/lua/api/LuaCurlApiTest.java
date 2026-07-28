package com.meshtastic.client.lua.api;

import com.meshtastic.client.utils.AppPreferences;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class LuaCurlApiTest {

    private boolean originalSecurityRestrictionsDisabled;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        originalSecurityRestrictionsDisabled =
                AppPreferences.isLuaCurlSecurityRestrictionsDisabled();
        AppPreferences.setLuaCurlSecurityRestrictionsDisabled(false);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        AppPreferences.setLuaCurlSecurityRestrictionsDisabled(
                originalSecurityRestrictionsDisabled);
    }

    @Test
    void blocksLocalAddressesByDefault() {
        LuaTable curl = new LuaCurlApi().create();

        LuaError error = assertThrows(LuaError.class, () ->
                curl.get("get").call(LuaValue.valueOf("http://127.0.0.1/")));

        assertTrue(error.getMessage().contains("local and private addresses"));
    }

    @Test
    void allowsLocalAddressesWhenSecurityRestrictionsAreDisabled() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/status", exchange -> sendText(exchange, "ok"));
        server.start();
        AppPreferences.setLuaCurlSecurityRestrictionsDisabled(true);

        LuaTable curl = new LuaCurlApi().create();
        LuaTable response = curl
                .get("get")
                .call(LuaValue.valueOf("http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/status"))
                .checktable();

        assertTrue(response.get("ok").toboolean());
        assertEquals(200, response.get("status").toint());
        assertEquals("ok", response.get("body").tojstring());
    }

    private static void sendText(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
