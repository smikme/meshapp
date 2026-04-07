package com.meshtastic.client.forms;

import com.meshtastic.client.model.MeshMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormChatTest {

    @Test
    void shouldMarkNewMessagesReadImmediatelyWhenVisibleAndAtTail() {
        assertTrue(FormChat.shouldMarkNewMessagesReadImmediately(
                true,
                true,
                List.of(incoming("hello"), outgoing("sent-by-me"))));
    }

    @Test
    void shouldNotMarkNewMessagesReadImmediatelyWhenUserIsNotAtTail() {
        assertFalse(FormChat.shouldMarkNewMessagesReadImmediately(
                true,
                false,
                List.of(incoming("hello"))));
    }

    @Test
    void shouldNotMarkNewMessagesReadImmediatelyForOutgoingOnlyBatch() {
        assertFalse(FormChat.shouldMarkNewMessagesReadImmediately(
                true,
                true,
                List.of(outgoing("sent-by-me"))));
    }

    @Test
    void shouldDetectDatabaseRewindWhenLatestDbMessageHasSmallerIdThanLoadedViewport() {
        MeshMessage loaded = incoming("old");
        loaded.setDbId(120);

        MeshMessage newestPersisted = incoming("new-after-reset");
        newestPersisted.setDbId(3);

        assertTrue(FormChat.hasDatabaseRewind(
                120,
                List.of(newestPersisted),
                List.of(loaded)));
    }

    @Test
    void shouldIgnoreDatabaseRewindCheckWhenLatestDbMessageAlreadyExistsInViewport() {
        MeshMessage loaded = incoming("existing");
        loaded.setDbId(3);

        MeshMessage newestPersisted = incoming("existing");
        newestPersisted.setDbId(3);

        assertFalse(FormChat.hasDatabaseRewind(
                120,
                List.of(newestPersisted),
                List.of(loaded)));
    }

    private static MeshMessage incoming(String text) {
        return new MeshMessage("!00000002", "!ffffffff", 0, text, 10, false);
    }

    private static MeshMessage outgoing(String text) {
        return new MeshMessage("!00000001", "!ffffffff", 0, text, 10, true);
    }
}
