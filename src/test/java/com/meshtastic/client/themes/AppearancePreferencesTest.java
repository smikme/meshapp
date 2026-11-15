package com.meshtastic.client.themes;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.utils.AppPreferences;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AppearancePreferencesTest {
    @TempDir Path home;

    @BeforeEach void setUp() { TestEnvironmentSupport.setUserHome(home); }

    @Test void existingDayNightPreferenceIsPreservedWithoutMigration() {
        AppPreferences.setDarkMode(true);
        assertEquals(ThemePalette.STANDARD, AppearancePreferences.palette());
        assertEquals(ChatBackground.defaults(), AppearancePreferences.globalBackground());
        assertTrue(AppPreferences.isDarkMode());
        AppearancePreferences.setPalette(ThemePalette.GREEN);
        AppPreferences.init();
        assertEquals(ThemePalette.GREEN, AppearancePreferences.palette());
        assertTrue(AppPreferences.isDarkMode());
    }

    @Test void longMeshCoreIdsOwnersAndChatTypesRemainIndependentAfterReload() throws Exception {
        String owner = "mc:" + "a".repeat(64), peer = "mc:" + "b".repeat(64);
        ChatBackground global = new ChatBackground(ChatBackground.Kind.GRADIENT, "#abcdef", "#012345", "", .2, 3, false);
        ChatBackground own = new ChatBackground(ChatBackground.Kind.DOTS, "#112233", null, "", .4, 5, false);
        AppearancePreferences.setGlobalBackground(global);
        AppearancePreferences.setChatBackground(owner, "dm:" + peer, own);
        AppPreferences.getState().flush();
        AppPreferences.init();
        assertEquals(own, AppearancePreferences.backgroundFor(owner, "dm:" + peer));
        assertEquals(global, AppearancePreferences.backgroundFor(owner + "x", "dm:" + peer));
        assertEquals(global, AppearancePreferences.backgroundFor(owner, "channel:0"));
        assertEquals(global, AppearancePreferences.backgroundFor(owner, "dm:" + peer + "x"));
        AppearancePreferences.setGlobalBackground(ChatBackground.defaults());
        assertEquals(own, AppearancePreferences.backgroundFor(owner, "dm:" + peer));
        AppearancePreferences.setChatBackground(owner, "dm:" + peer, null);
        assertNull(AppearancePreferences.chatOverride(owner, "dm:" + peer));
        assertEquals(ChatBackground.defaults(), AppearancePreferences.backgroundFor(owner, "dm:" + peer));
    }

    @Test void explicitDefaultIsDifferentFromInheritingAndAmbiguousKeysDoNotCollide() {
        ChatBackground color = new ChatBackground(ChatBackground.Kind.COLOR, "#123456", null, "", 0, 0, false);
        AppearancePreferences.setGlobalBackground(color);
        AppearancePreferences.setChatBackground("ab", "c", ChatBackground.defaults());
        assertEquals(ChatBackground.defaults(), AppearancePreferences.backgroundFor("ab", "c"));
        assertEquals(color, AppearancePreferences.backgroundFor("a", "bc"));
    }

    @Test void invalidOrFutureSettingsFallBackAndUnsafeImagePathsAreRejected() {
        var prefs = AppPreferences.getState().node("appearance");
        prefs.put("palette", "FUTURE_PALETTE");
        prefs.put("background", "{broken");
        assertEquals(ThemePalette.STANDARD, AppearancePreferences.palette());
        assertEquals(ChatBackground.defaults(), AppearancePreferences.globalBackground());
        prefs.put("background", "{\"kind\":\"IMAGE\",\"imageId\":\"../../photo.png\",\"color\":\"bad\",\"dim\":99,\"blur\":-3}");
        ChatBackground safe = AppearancePreferences.globalBackground();
        assertEquals(ChatBackground.Kind.DEFAULT, safe.kind());
        assertEquals("", safe.imageId());
        assertEquals("#dce8e2", safe.color());
        assertEquals(.85, safe.dim());
        assertEquals(0, safe.blur());
    }
}
