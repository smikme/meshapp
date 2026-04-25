package com.meshtastic.client.forms;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.MeshMessage;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ChannelProtos;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormChatTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void shouldNotSaveScrollStateWhenLoadedWindowBelongsToPreviousChat() {
        onFxThread(() -> {
            FormChat form = new FormChat();
            ChatItem channel = channel(0);
            ChatItem directMessage = directMessage("!00000002");

            MeshMessage staleDirectMessage = incoming("stale dm row");
            staleDirectMessage.setDbId(42);

            form.selectedChat = channel;
            form.loadedChatScrollCacheKey = form.chatScrollCacheKey(directMessage);
            form.loadedMessages.add(staleDirectMessage);
            form.newestLoadedDbId = staleDirectMessage.getDbId();
            form.latestKnownDbId = staleDirectMessage.getDbId();

            form.saveCurrentChatScrollState();

            assertFalse(form.savedChatScrollStates.containsKey(form.chatScrollCacheKey(channel)));
            return null;
        });
    }

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

    @Test
    void copyLoadedMessageMetadataRefreshesMqttBadgeAndLoraMetrics() {
        MeshMessage loaded = incoming("existing");
        loaded.setPacketId(42);
        loaded.setViaMqtt(true);

        MeshMessage updated = incoming("existing");
        updated.setPacketId(42);
        updated.setViaMqtt(false);
        updated.setHopStart(5);
        updated.setHopLimit(2);
        updated.setRxRssi(-84);
        updated.setRxSnr(6.0f);

        assertTrue(FormChat.copyLoadedMessageMetadata(loaded, updated));
        assertFalse(loaded.isViaMqtt());
        assertEquals(5, loaded.getHopStart());
        assertEquals(2, loaded.getHopLimit());
        assertEquals(-84, loaded.getRxRssi());
        assertEquals(6.0f, loaded.getRxSnr());
    }

    private static MeshMessage incoming(String text) {
        return new MeshMessage("!00000002", "!ffffffff", 0, text, 10, false);
    }

    private static MeshMessage outgoing(String text) {
        return new MeshMessage("!00000001", "!ffffffff", 0, text, 10, true);
    }

    private static ChatItem channel(int index) {
        ChannelProtos.Channel channel = ChannelProtos.Channel.newBuilder()
                .setIndex(index)
                .setRole(index == 0
                        ? ChannelProtos.Channel.Role.PRIMARY
                        : ChannelProtos.Channel.Role.SECONDARY)
                .setSettings(ChannelProtos.ChannelSettings.newBuilder().setName("Ch " + index))
                .build();
        return ChatItem.fromChannel(channel, (MeshMessage) null, 0, false);
    }

    private static ChatItem directMessage(String nodeId) {
        return ChatItem.fromDirectMessage(nodeId, null, (MeshMessage) null, 0, false);
    }

    private static <T> T onFxThread(FxSupplier<T> supplier) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });

        await(latch);
        if (failure.get() != null) {
            throw new AssertionError("JavaFX task failed", failure.get());
        }
        return result.get();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for JavaFX task");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for JavaFX task", e);
        }
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }
}
