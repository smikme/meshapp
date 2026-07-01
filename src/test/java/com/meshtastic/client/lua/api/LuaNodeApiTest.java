package com.meshtastic.client.lua.api;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.service.NodeCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaNodeApiTest {

    @TempDir
    Path tempHome;

    private LuaTable node;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        node = new LuaNodeApi(new LuaSandboxContext(
                7L,
                "test",
                null,
                null,
                null,
                "!11111111",
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
                null)).create();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void nodeFlagHelpersUpdateLocalFavoriteAndIgnoredState() {
        assertTrue(node.get("set_favorite_node").call(LuaValue.valueOf("!cafebabe")).toboolean());
        assertTrue(NodeCacheService.getInstance().isFavorite("!cafebabe", "!11111111"));

        assertTrue(node.get("remove_favorite_node").call(LuaValue.valueOf("!cafebabe")).toboolean());
        assertFalse(NodeCacheService.getInstance().isFavorite("!cafebabe", "!11111111"));

        LuaTable target = new LuaTable();
        target.set("node_id", "!deadbeef");
        assertTrue(node.get("set_ignored_node").call(target).toboolean());
        assertTrue(NodeCacheService.getInstance().isIgnored("!deadbeef", "!11111111"));

        assertTrue(node.get("remove_ignored_node").call(LuaValue.valueOf(0xdeadbeefL)).toboolean());
        assertFalse(NodeCacheService.getInstance().isIgnored("!deadbeef", "!11111111"));
    }

    @Test
    void rejectsInvalidTarget() {
        assertThrows(LuaError.class, () -> node.get("set_favorite_node").call(LuaValue.valueOf("deadbeef")));
    }
}
