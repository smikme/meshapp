package com.meshtastic.client.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class LuaKvEditorWindowTest {

    @Test
    void matchesSearchByKeyOrValueCaseInsensitively() {
        assertTrue(LuaKvEditorWindow.matchesSearch("auth.token", "secret-value", " TOKEN "));
        assertTrue(LuaKvEditorWindow.matchesSearch("auth.token", "secret-value", "SECRET"));
        assertFalse(LuaKvEditorWindow.matchesSearch("auth.token", "secret-value", "missing"));
    }

    @Test
    void matchesSearchAllowsBlankQueryAndNullValues() {
        assertTrue(LuaKvEditorWindow.matchesSearch("key", "value", "   "));
        assertTrue(LuaKvEditorWindow.matchesSearch(null, "value", "val"));
        assertFalse(LuaKvEditorWindow.matchesSearch(null, null, "val"));
    }
}
