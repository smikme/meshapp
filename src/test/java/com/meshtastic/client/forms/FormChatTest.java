package com.meshtastic.client.forms;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.MessageChangeEvent;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageDbService;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.ChannelProtos;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class FormChatTest {

    @TempDir
    Path tempHome;

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
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
    void shouldIgnoreScrollEventsWhileFormIsHidden() {
        onFxThread(() -> {
            FormChat form = new FormChat();
            ChatItem channel = channel(0);
            MeshMessage loaded = incoming("loaded");
            loaded.setDbId(77);

            form.selectedChat = channel;
            form.loadedChatScrollCacheKey = form.chatScrollCacheKey(channel);
            form.loadedMessages.add(loaded);
            form.newestLoadedDbId = loaded.getDbId();
            form.latestKnownDbId = loaded.getDbId();

            FormChatBase.ChatScrollState saved = new FormChatBase.ChatScrollState(77, 18.5, false);
            form.savedChatScrollStates.put(form.chatScrollCacheKey(channel), saved);
            form.formVisible = false;

            form.messageScrollPane.setVvalue(1.0);

            FormChatBase.ChatScrollState actual = form.savedChatScrollStates.get(form.chatScrollCacheKey(channel));
            assertEquals(saved.anchorDbId(), actual.anchorDbId());
            assertEquals(saved.anchorOffset(), actual.anchorOffset());
            assertEquals(saved.atBottom(), actual.atBottom());
            return null;
        });
    }

    @Test
    void formCloseInvalidatesQueuedScrollOperations() {
        onFxThread(() -> {
            FormChat form = new FormChat();
            form.formVisible = true;
            long generation = form.scrollOperationGeneration;

            form.formClose();

            assertFalse(form.formVisible);
            assertEquals(generation + 1, form.scrollOperationGeneration);
            return null;
        });
    }

    @Test
    void formOpenReopensRestoredSelectionWhenDetailPaneIsPlaceholder() {
        onFxThread(() -> {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry entry = new ConnectionEntry("test", "127.0.0.1", 4403);
            manager.addEntry(entry);
            entry.setConnected(true);
            manager.setSelectedConnectionId(entry.getId());

            DeviceState state = new DeviceState();
            try {
                state.setMyNodeNum(0x12345678);
                state.addChannel(channelProto(0));
                deviceStates(manager).put(entry.getId(), state);

                FormChat form = new FormChat();
                form.state = state;
                form.boundConnectionId = entry.getId();
                form.selectedChat = null;
                form.selectedChatsByConnectionId.put(entry.getId(), FormChatBase.ChatSelection.from(channel(0)));
                form.detailPane.getChildren().setAll(form.placeholderBox);

                form.formOpen();

                assertNotNull(form.selectedChat);
                assertTrue(form.isChatDetailOpenFor(form.selectedChat));
                assertTrue(form.detailPane.getChildren().contains(form.messageArea));
            } finally {
                state.shutdown();
            }
            return null;
        });
        waitForFxEvents();
        waitForFxEvents();
    }

    @Test
    void reloadChatListClearsSelectionWhenRestoredChatNoLongerExists() {
        onFxThread(() -> {
            DeviceState state = new DeviceState();
            try {
                state.setMyNodeNum(0x12345678);
                state.addChannel(channelProto(0));

                FormChat form = new FormChat();
                form.state = state;
                form.boundConnectionId = "connection-1";
                form.selectedChat = channel(1);
                form.selectedChatsByConnectionId.put(
                        "connection-1",
                        FormChatBase.ChatSelection.from(channel(1)));
                form.detailPane.getChildren().setAll(form.messageArea);

                form.reloadChatList();

                assertEquals(null, form.selectedChat);
                assertTrue(form.chatListView.getSelectionModel().isEmpty());
                assertFalse(form.selectedChatsByConnectionId.containsKey("connection-1"));
                assertTrue(form.detailPane.getChildren().contains(form.placeholderBox));
                assertFalse(form.detailPane.getChildren().contains(form.messageArea));
            } finally {
                state.shutdown();
            }
            return null;
        });
        waitForFxEvents();
        waitForFxEvents();
    }

    @Test
    void openDirectChatKeepsEmptyThreadAfterReload() {
        onFxThread(() -> {
            DeviceState state = new DeviceState();
            try {
                state.setMyNodeNum(0x12345678);
                state.addChannel(channelProto(0));
                NodeData peer = new NodeData(0x00000002);
                peer.setLongName("Peer Two");
                peer.setShortName("P2");

                FormChat form = new FormChat();
                form.state = state;
                form.boundConnectionId = "connection-1";

                form.openDirectChat(peer.getNodeId(), peer);
                form.reloadChatList();

                assertTrue(state.getAllDirectMessages().containsKey(peer.getNodeId()));
                assertNotNull(form.selectedChat);
                assertEquals(ChatItem.ChatType.DIRECT_MESSAGE, form.selectedChat.getType());
                assertEquals(peer.getNodeId(), form.selectedChat.getPeerNodeId());
                assertTrue(form.chatListView.getItems().stream()
                        .anyMatch(item -> item.getType() == ChatItem.ChatType.DIRECT_MESSAGE
                                && peer.getNodeId().equals(item.getPeerNodeId())));
            } finally {
                state.shutdown();
            }
            return null;
        });
        waitForFxEvents();
        waitForFxEvents();
    }

    @Test
    void hiddenIncomingMessageDoesNotTouchViewportState() {
        onFxThread(() -> {
            MessageDbService db = MessageDbService.getInstance();
            DeviceState state = new DeviceState();
            try {
                state.setMyNodeNum(0x12345678);
                FormChat form = new FormChat();
                ChatItem channel = channel(0);
                MeshMessage first = incoming("first");
                first.setPacketId(101);
                db.save(first, "channel", "0", "!12345678");

                form.state = state;
                form.selectedChat = channel;
                form.loadedChatScrollCacheKey = form.chatScrollCacheKey(channel);
                form.appendLoadedMessageRow(first);
                form.recalcLoadedBounds();
                form.latestKnownDbId = first.getDbId();
                form.formVisible = false;
                form.messageScrollPane.setVvalue(0.42);

                FormChatBase.ChatScrollState saved =
                        new FormChatBase.ChatScrollState(first.getDbId(), 18.5, false);
                form.savedChatScrollStates.put(form.chatScrollCacheKey(channel), saved);

                MeshMessage second = incoming("second");
                second.setPacketId(102);
                db.save(second, "channel", "0", "!12345678");

                form.refreshCurrentChat();

                assertEquals(2, form.loadedMessages.size());
                assertFalse(form.viewportLayoutQueued.get());
                assertEquals(0.42, form.messageScrollPane.getVvalue());
                FormChatBase.ChatScrollState actual =
                        form.savedChatScrollStates.get(form.chatScrollCacheKey(channel));
                assertEquals(saved.anchorDbId(), actual.anchorDbId());
                assertEquals(saved.anchorOffset(), actual.anchorOffset());
                assertEquals(saved.atBottom(), actual.atBottom());
            } finally {
                state.shutdown();
            }
            return null;
        });
    }

    @Test
    void shouldCoalesceMessageChangeEventsWhenFxQueueBacksUp() {
        onFxThread(() -> {
            FormChat form = new FormChat();

            for (int i = 0; i < FormChatBase.MAX_PENDING_MESSAGE_CHANGE_EVENTS + 10; i++) {
                form.scheduleMessageChangeRefresh(MessageChangeEvent.unknown());
            }

            assertTrue(form.pendingMessageChangesOverflowed.get());
            assertTrue(form.pendingMessageChanges.size() <= FormChatBase.MAX_PENDING_MESSAGE_CHANGE_EVENTS);
            return null;
        });
        waitForFxEvents();
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
    void liveTailMetricUsesBottomSlopWithoutForcingLayout() {
        assertTrue(FormChatMessages.isScrolledToBottomFromMetrics(1000, 400, 1.0));
        assertTrue(FormChatMessages.isScrolledToBottomFromMetrics(1000, 400, 0.97));
        assertFalse(FormChatMessages.isScrolledToBottomFromMetrics(1000, 400, 0.95));
        assertTrue(FormChatMessages.isScrolledToBottomFromMetrics(0, 0, 0));
        assertTrue(FormChatMessages.isScrolledToBottomFromMetrics(300, 400, 0));
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
        return ChatItem.fromChannel(channelProto(index), (MeshMessage) null, 0, false);
    }

    private static ChannelProtos.Channel channelProto(int index) {
        return ChannelProtos.Channel.newBuilder()
                .setIndex(index)
                .setRole(index == 0
                        ? ChannelProtos.Channel.Role.PRIMARY
                        : ChannelProtos.Channel.Role.SECONDARY)
                .setSettings(ChannelProtos.ChannelSettings.newBuilder().setName("Ch " + index))
                .build();
    }

    private static ChatItem directMessage(String nodeId) {
        return ChatItem.fromDirectMessage(nodeId, null, (MeshMessage) null, 0, false);
    }

    private static void waitForFxEvents() {
        onFxThread(() -> null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, DeviceState> deviceStates(ConnectionManager manager) {
        return (Map<String, DeviceState>) readField(manager, "deviceStates");
    }

    private static Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read field " + fieldName, e);
        }
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
