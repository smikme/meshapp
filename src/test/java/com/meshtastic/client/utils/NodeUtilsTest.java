package com.meshtastic.client.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeUtilsTest {

    @Test
    void avatarFontSizeTreatsSingleEmojiAsOneGlyph() {
        assertEquals(NodeUtils.avatarFontSize(1, 40), NodeUtils.avatarFontSize("😀", 40));
    }

    @Test
    void avatarFontSizeKeepsSimpleFourCharacterAvatarAtBaseSize() {
        assertEquals(NodeUtils.avatarFontSize(4, 40), NodeUtils.avatarFontSize("ABCD", 40));
    }

    @Test
    void avatarFontSizeShrinksWideFourCharacterAvatar() {
        assertTrue(NodeUtils.avatarFontSize("#GAM", 40) < NodeUtils.avatarFontSize(4, 40));
    }

    @Test
    void avatarFontSizeFallsBackAfterSanitize() {
        assertEquals(NodeUtils.avatarFontSize(1, 40), NodeUtils.avatarFontSize("A\uD83D", 40));
    }

    @Test
    void chatAvatarFontSizeFallsBackAfterDisplaySanitize() {
        assertEquals(NodeUtils.chatAvatarFontSize(1, 40), NodeUtils.chatAvatarFontSize("📡", 40));
    }
}
