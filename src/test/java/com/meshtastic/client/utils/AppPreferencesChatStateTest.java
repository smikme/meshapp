package com.meshtastic.client.utils;

import com.meshtastic.client.TestEnvironmentSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

class AppPreferencesChatStateTest {
    private static final String OWNER = "mc:" + "a".repeat(64);
    private static final String PEER = "mc:" + "b".repeat(64);

    @TempDir
    Path home;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(home);
    }

    @AfterEach
    void tearDown() throws Exception {
        AppPreferences.getState().removeNode();
        TestEnvironmentSupport.setUserHome(home);
    }

    @Test
    void fullMeshCoreIdsRoundTripAndRemoveScrollStateAfterReinitialization() throws Exception {
        String chat = AppPreferences.composeChatPreferenceId("dm", PEER);
        assertEquals(138, (OWNER + "|" + chat).length());

        AppPreferences.saveChatScrollState(OWNER, chat, 123, 18.5, false);
        AppPreferences.getState().flush();
        AppPreferences.init();

        assertScrollState(OWNER, chat, 123, 18.5, false);
        String[] keys = AppPreferences.getState().node("chatScroll").keys();
        assertEquals(1, keys.length);
        assertTrue(keys[0].length() <= Preferences.MAX_KEY_LENGTH);

        AppPreferences.removeChatScrollState(OWNER, chat);
        assertNull(AppPreferences.loadChatScrollState(OWNER, chat));
        assertEquals(0, AppPreferences.getState().node("chatScroll").keys().length);
    }

    @Test
    void ownersAndPeersWithIdenticalPrefixesHaveIndependentSettings() throws Exception {
        String[] owners = {OWNER, "mc:" + "a".repeat(63) + "c"};
        String[] peers = {PEER, "mc:" + "b".repeat(63) + "d"};
        for (int owner = 0; owner < owners.length; owner++) {
            for (int peer = 0; peer < peers.length; peer++) {
                int id = 1 + owner * 2 + peer;
                String chat = AppPreferences.composeChatPreferenceId("dm", peers[peer]);
                AppPreferences.saveChatScrollState(owners[owner], chat, id, id + 0.5, false);
                AppPreferences.setChatMuted(owners[owner], "dm", peers[peer], owner == peer);
            }
        }
        AppPreferences.getState().flush();
        AppPreferences.init();

        for (int owner = 0; owner < owners.length; owner++) {
            for (int peer = 0; peer < peers.length; peer++) {
                int id = 1 + owner * 2 + peer;
                String chat = AppPreferences.composeChatPreferenceId("dm", peers[peer]);
                assertScrollState(owners[owner], chat, id, id + 0.5, false);
                assertEquals(owner == peer, AppPreferences.isChatMuted(owners[owner], chat));
            }
        }
        assertEquals(4, AppPreferences.getState().node("chatScroll").keys().length);
        assertEquals(4, AppPreferences.getState().node("chatNotifications").keys().length);

        AppPreferences.removeChatScrollState(OWNER, "dm:" + PEER);
        AppPreferences.setChatMuted(OWNER, "dm", PEER, false);
        assertNull(AppPreferences.loadChatScrollState(OWNER, "dm:" + PEER));
        assertFalse(AppPreferences.isChatMuted(OWNER, "dm", PEER));
        assertScrollState(owners[1], "dm:" + peers[1], 4, 4.5, false);
        assertTrue(AppPreferences.isChatMuted(owners[1], "dm", peers[1]));
    }

    @Test
    void existingShortKeysRemainReadableWritableAndRemovable() {
        String owner = "!12345678";
        String chat = "dm:!87654321";
        String legacyKey = owner + "|" + chat;
        Preferences scroll = AppPreferences.getState().node("chatScroll");
        Preferences notifications = AppPreferences.getState().node("chatNotifications");
        scroll.put(legacyKey, "42|12.5|0");
        notifications.putBoolean(legacyKey, true);

        assertScrollState(owner, chat, 42, 12.5, false);
        assertTrue(AppPreferences.isChatMuted(owner, chat));

        AppPreferences.saveChatScrollState(owner, chat, 43, 0, true);
        AppPreferences.setChatMuted(owner, chat, false);
        assertEquals("43|0.0|1", scroll.get(legacyKey, null));
        assertFalse(notifications.getBoolean(legacyKey, true));

        AppPreferences.removeChatScrollState(owner, chat);
        assertNull(scroll.get(legacyKey, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {80, 81})
    void scrollAndMuteSettingsWorkAtThePreferenceKeyLengthBoundary(int length) throws Exception {
        String owner = "!12345678";
        String chat = "dm:" + "b".repeat(length - owner.length() - 1 - 3);
        String rawKey = owner + "|" + chat;
        assertEquals(length, rawKey.length());

        AppPreferences.saveChatScrollState(owner, chat, 7, 0, true);
        AppPreferences.setChatMuted(owner, chat, true);
        AppPreferences.getState().flush();
        AppPreferences.init();

        assertScrollState(owner, chat, 7, 0, true);
        assertTrue(AppPreferences.isChatMuted(owner, chat));
        for (String node : new String[]{"chatScroll", "chatNotifications"}) {
            String[] keys = AppPreferences.getState().node(node).keys();
            assertEquals(1, keys.length);
            assertTrue(keys[0].length() <= Preferences.MAX_KEY_LENGTH);
            if (length == Preferences.MAX_KEY_LENGTH) assertEquals(rawKey, keys[0]);
        }

        AppPreferences.removeChatScrollState(owner, chat);
        AppPreferences.setChatMuted(owner, chat, false);
        assertNull(AppPreferences.loadChatScrollState(owner, chat));
        assertFalse(AppPreferences.isChatMuted(owner, chat));
    }

    private static void assertScrollState(String owner, String chat, long id, double offset, boolean bottom) {
        AppPreferences.ChatScrollState state = AppPreferences.loadChatScrollState(owner, chat);
        assertNotNull(state);
        assertEquals(id, state.getAnchorDbId());
        assertEquals(offset, state.getAnchorOffset());
        assertEquals(bottom, state.isAtBottom());
    }
}
