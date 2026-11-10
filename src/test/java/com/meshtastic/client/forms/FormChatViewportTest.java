package com.meshtastic.client.forms;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import com.meshtastic.client.rpc.RpcClient;
import com.meshtastic.client.rpc.RpcTransport;
import com.meshtastic.client.rpc.RpcTransportListener;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.ChatHistoryLoader;
import com.meshtastic.client.service.DatabaseProvider;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.ChannelProtos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormChatViewportTest {
    @TempDir Path home;
    private ControlledForm form;
    private Stage stage;
    private final List<MeshMessage> messages = new ArrayList<>();
    private ProtocolHandler sendHandler;
    private DeviceState sendState;

    @BeforeEach
    void setUp() throws Exception {
        TestEnvironmentSupport.setUserHome(home);
        TestEnvironmentSupport.resetSingletons();
        TestEnvironmentSupport.ensureJavaFxStarted();
        fx(() -> {
            form = new ControlledForm();
            form.formVisible = true;
            stage = new Stage();
            stage.setScene(new Scene(form, 1100, 800));
            stage.show();
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        fx(form::cancelHistoryLoading);
        drain();
        fx(() -> {
            form.formClose();
            stage.close();
        });
        drain();
        if (sendHandler != null) sendHandler.shutdown();
        if (sendState != null) sendState.shutdown();
        fx(TestEnvironmentSupport::resetSingletons);
    }

    @Test
    void sendingAfterRepeatedChatSwitchesShowsTheMessageAndChatListPreview() throws Exception {
        var transport = configureSending();
        for (int i = 1; i <= 180; i++) {
            MessageDbService.getInstance().save(message(i), "channel", "0", "!12345678");
        }
        fx(() -> { form.reloadChatList(); form.openChat(channel(0)); });
        drain();
        for (int i = 0; i < 8; i++) {
            fx(() -> {
                form.loadOlderMessages();
                form.openChat(channel(1));
                form.openChat(channel(0));
            });
        }
        drain();
        var input = input();
        fx(() -> {
            input.setText("sent after switching");
            ((javafx.scene.control.Button) form.lookup(".chat-send-btn")).fire();
            assertEquals("", input.getText());
        });
        drain();
        assertTrue(transport.sent.await(5, TimeUnit.SECONDS), "the fake transport must receive the message");
        fx(() -> {
            var outgoing = form.loadedMessages.stream().filter(MeshMessage::isOutgoing)
                    .filter(message -> "sent after switching".equals(message.getText())).findFirst().orElseThrow();
            assertTrue(outgoing.getDbId() > 0);
            assertTrue(form.loadedMessageRows.containsKey(outgoing.getDbId()), () -> "sent message must be visible: v="
                    + form.messageScrollPane.getVvalue() + ", height=" + form.messageContainer.getHeight()
                    + ", top=" + form.messageContainer.messageTop(outgoing.getDbId())
                    + ", rendered=" + form.loadedMessageRows.keySet());
            assertEquals("sent after switching", form.chatItems.stream()
                    .filter(item -> item.getChannelIndex() == 0).findFirst().orElseThrow().getLastMessageText());
            assertTrue(form.lookupAll(".chat-preview-text-node").stream()
                    .filter(javafx.scene.text.Text.class::isInstance).map(javafx.scene.text.Text.class::cast)
                    .anyMatch(text -> text.getText().contains("sent after switching")), "preview must be rendered");
        });
    }

    @Test
    void scrollDownButtonHidesAfterReachingTheTailAndReturnsWhenScrollingUp() throws Exception {
        seed(120);
        fx(() -> form.openChat(channel(0)));
        drain();
        fx(() -> form.messageScrollPane.setVvalue(.55));
        drain();
        fx(() -> {
            assertTrue(form.scrollDownBtn.isVisible());
            assertFalse(form.isAtLiveTail());
            form.scrollDownBtn.fire();
        });
        drain();
        fx(() -> {
            assertTrue(form.isAtLiveTail());
            assertFalse(form.scrollDownBtn.isVisible(), "hide the button after the virtual viewport reaches the bottom");
            assertFalse(form.scrollDownBadge.isVisible());
            form.messageScrollPane.setVvalue(.55);
        });
        drain();
        fx(() -> {
            assertFalse(form.isAtLiveTail());
            assertTrue(form.scrollDownBtn.isVisible(), "the button must return when leaving the tail");
        });
    }

    @Test
    void scrollDownButtonWaitsForTheLatestPageBeforeHiding() throws Exception {
        seed(240);
        fx(() -> form.openChat(channel(0)));
        drain();
        fx(() -> form.ensureMessageLoaded(messages.get(80).getDbId()));
        drain();
        fx(() -> {
            assertFalse(form.allNewerHistoryLoaded);
            assertTrue(form.scrollDownBtn.isVisible());
            form.hold = true;
            form.scrollDownBtn.fire();
            assertTrue(form.isHistoryLoading());
            assertTrue(form.scrollDownBtn.isVisible(), "do not hide while the latest page is still loading");
        });
        pulse();
        fx(() -> assertTrue(form.scrollDownBtn.isVisible()));
        completeHeld(form.held.size() - 1);
        drain();
        fx(() -> {
            assertTrue(form.isAtLiveTail());
            assertFalse(form.scrollDownBtn.isVisible());
            assertFalse(form.scrollDownBadge.isVisible());
        });
    }

    @Test
    void sendingAtTheLiveTailKeepsExistingBubblesAndDoesNotReloadHistory() throws Exception {
        configureSending();
        for (int i = 1; i <= 3; i++) {
            MessageDbService.getInstance().save(message(i), "channel", "0", "!12345678");
        }
        fx(() -> { form.reloadChatList(); form.openChat(channel(0)); });
        drain();
        var previousRows = new java.util.HashMap<Long, com.meshtastic.client.components.chat.MessageBubbleFactory.RenderedMessageRow>();
        var detachments = new java.util.concurrent.atomic.AtomicInteger();
        var input = input();
        fx(() -> {
            previousRows.putAll(form.loadedRenderedMessageRows);
            assertEquals(3, previousRows.size());
            previousRows.values().forEach(rendered -> rendered.row().parentProperty().addListener((_, _, parent) -> {
                if (parent == null) detachments.incrementAndGet();
            }));
            form.hold = true; // A redundant async reload would leave the chat blank here.
            input.setText("append without blinking");
            ((javafx.scene.control.Button) form.lookup(".chat-send-btn")).fire();
            assertFalse(form.isHistoryLoading(), "sending at the live tail must not reload the history window");
            assertEquals(4, form.loadedMessages.size());
        });
        drain();
        fx(() -> {
            assertEquals(0, detachments.get(), "existing bubbles must stay attached throughout sending");
            previousRows.forEach((id, rendered) -> org.junit.jupiter.api.Assertions.assertSame(
                    rendered, form.loadedRenderedMessageRows.get(id), "bubble content must not be rebuilt"));
            assertEquals(1, form.loadedMessages.stream().filter(message -> "append without blinking".equals(message.getText())).count());
            assertTrue(form.loadedMessageRows.containsKey(form.loadedMessages.getLast().getDbId()));
            assertTrue(form.isAtLiveTail());
        });
    }

    @Test
    void sendingFromOldHistoryKeepsTheViewportUntilLatestHistoryIsReadyAndCanRetry() throws Exception {
        configureSending();
        for (int i = 1; i <= 240; i++) {
            MessageDbService.getInstance().save(message(i), "channel", "0", "!12345678");
        }
        fx(() -> { form.reloadChatList(); form.openChat(channel(0)); });
        drain();
        fx(() -> form.ensureMessageLoaded(80));
        drain();
        var previousRows = new java.util.HashMap<Long, com.meshtastic.client.components.chat.MessageBubbleFactory.RenderedMessageRow>();
        var input = input();
        fx(() -> {
            assertFalse(form.allNewerHistoryLoaded);
            previousRows.putAll(form.loadedRenderedMessageRows);
            assertFalse(previousRows.isEmpty());
            form.hold = true;
            input.setText("sent from old history");
            ((javafx.scene.control.Button) form.lookup(".chat-send-btn")).fire();
            assertTrue(form.isHistoryLoading());
            assertEquals(previousRows, form.loadedRenderedMessageRows,
                    "keep the previous viewport while the latest page is loading");
            form.held.getLast().future().completeExceptionally(new IllegalStateException("test read failure"));
        });
        drain();
        fx(() -> {
            assertEquals(previousRows, form.loadedRenderedMessageRows, "a failed load must not blank the chat");
            form.hold = false;
            form.jumpToLatestMessages();
        });
        drain();
        fx(() -> {
            assertTrue(form.allNewerHistoryLoaded);
            assertEquals("sent from old history", form.loadedMessages.getLast().getText());
            assertTrue(form.loadedMessageRows.containsKey(form.loadedMessages.getLast().getDbId()));
            assertTrue(form.isAtLiveTail());
        });
    }

    @Test
    void failedLocalPersistenceKeepsTheDraftAndDoesNotTransmit() throws Exception {
        var transport = configureSending();
        fx(() -> { form.reloadChatList(); form.openChat(channel(0)); });
        drain();
        DatabaseProvider.getConnection().close();
        var input = input();
        fx(() -> {
            input.setText("keep my unsent message");
            ((javafx.scene.control.Button) form.lookup(".chat-send-btn")).fire();
            assertEquals("keep my unsent message", input.getText());
            assertTrue(form.loadedMessages.isEmpty());
        });
        assertEquals(1, transport.sent.getCount());
    }

    private RecordingTransport configureSending() throws Exception {
        var transport = new RecordingTransport();
        sendHandler = new ProtocolHandler(transport);
        sendState = new DeviceState();
        sendState.setMyNodeNum(0x12345678);
        var self = new com.meshtastic.client.model.NodeData(0x12345678);
        self.setNodeId("!12345678");
        sendState.getNodeDb().put(self.getNodeNum(), self);
        sendState.addChannel(ChannelProtos.Channel.newBuilder().setIndex(0)
                .setRole(ChannelProtos.Channel.Role.PRIMARY)
                .setSettings(ChannelProtos.ChannelSettings.newBuilder().setName("Test 0")).build());
        fx(() -> { form.state = sendState; form.protocolHandler = sendHandler; });
        return transport;
    }

    private com.meshtastic.client.components.chat.EmojiTextField input() throws Exception {
        var field = com.meshtastic.client.components.chat.ChatInputBar.class.getDeclaredField("messageInput");
        field.setAccessible(true);
        return (com.meshtastic.client.components.chat.EmojiTextField) field.get(form.chatInputBar);
    }

    private static class RecordingTransport implements com.meshtastic.client.connection.MeshtasticConnection {
        final java.util.concurrent.CountDownLatch sent = new java.util.concurrent.CountDownLatch(1);
        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public boolean isConnected() { return true; }
        @Override public void sendBytes(byte[] data) { sent.countDown(); }
        @Override public void setDataListener(java.util.function.Consumer<byte[]> listener) {}
        @Override public void setConnectionListener(com.meshtastic.client.connection.ConnectionListener listener) {}
    }

    @Test
    void restoresSavedWindowWithoutRenderingAndDiscardingLatestPage() throws Exception {
        seed(240);
        fx(() -> {
            var saved = new FormChatBase.ChatScrollState(messages.get(80).getDbId(), 23, false);
            form.savedChatScrollStates.put(form.chatScrollCacheKey(channel(0)), saved);
            form.openChat(channel(0));
        });
        drain();
        fx(() -> {
            assertEquals(23, anchorOffset(messages.get(80).getDbId()), 1.0);
            assertEquals(FormChatBase.PAGE_SIZE, form.loadedMessages.size());
            assertTrue(form.loadedMessageRows.size() < FormChatBase.PAGE_SIZE);
            assertEquals(messages.getLast().getDbId(), form.latestKnownDbId);
            assertFalse(form.allNewerHistoryLoaded);
        });
    }

    @Test
    void bottomOfHistoricalWindowIsNotMistakenForTheLiveTailWhenReturningToChat() throws Exception {
        seed(240);
        fx(() -> {
            form.savedChatScrollStates.put(form.chatScrollCacheKey(channel(0)),
                    new FormChatBase.ChatScrollState(messages.get(80).getDbId(), 23, false));
            form.openChat(channel(0));
        });
        drain();
        FormChatBase.ChatScrollState[] anchor = new FormChatBase.ChatScrollState[1];
        fx(() -> { form.formVisible = false; form.messageScrollPane.setVvalue(1); });
        drain();
        fx(() -> {
            form.saveCurrentChatScrollState();
            anchor[0] = form.getSavedScrollState(channel(0));
            assertFalse(anchor[0].atBottom(), "only the real live tail may use the bottom sentinel");
            form.formVisible = true;
            form.openChat(channel(1));
            form.openChat(channel(0));
        });
        drain();
        fx(() -> assertEquals(anchor[0].anchorOffset(), anchorOffset(anchor[0].anchorDbId()), 1));
    }

    @Test
    void missingSavedAnchorFallsBackToLatestHistory() throws Exception {
        seed(70);
        fx(() -> {
            form.savedChatScrollStates.put(form.chatScrollCacheKey(channel(0)),
                    new FormChatBase.ChatScrollState(9999999, 20, false));
            form.openChat(channel(0));
        });
        drain();
        fx(() -> {
            assertEquals(messages.getLast().getDbId(), form.newestLoadedDbId);
            assertTrue(form.allNewerHistoryLoaded);
        });
    }

    @Test
    void paginationAndIncomingMessagePreserveViewportAndEntireHistoryRemainsReachable() throws Exception {
        seed(240);
        fx(() -> form.openChat(channel(0)));
        drain();
        fx(() -> form.messageScrollPane.setVvalue(0.4));
        FormChatBase.ChatScrollState[] anchor = new FormChatBase.ChatScrollState[1];
        fx(() -> {
            anchor[0] = form.captureViewportAnchor();
            form.loadOlderMessages();
        });
        drain();
        fx(() -> {
            assertEquals(anchor[0].anchorOffset(), anchorOffset(anchor[0].anchorDbId()), 1.0);
        });
        fx(() -> {
            MeshMessage incoming = message(241);
            MessageDbService.getInstance().save(incoming, "channel", "0", "");
            form.refreshCurrentChat();
        });
        drain();
        fx(() -> {
            assertEquals(anchor[0].anchorOffset(), anchorOffset(anchor[0].anchorDbId()), 1.0);
            assertEquals(1, form.getUnreadCount(form.selectedChat));
        });
        for (int i = 0; i < 4; i++) {
            fx(form::loadOlderMessages);
            drain();
        }
        fx(() -> {
            assertTrue(form.allHistoryLoaded);
            assertEquals(messages.getFirst().getDbId(), form.oldestLoadedDbId);
            assertTrue(form.loadedMessages.size() <= FormChatBase.MAX_LOADED_MESSAGES);
        });
        for (int i = 0; i < 4; i++) {
            fx(form::loadNewerMessages);
            drain();
        }
        fx(() -> {
            assertTrue(form.allNewerHistoryLoaded);
            assertEquals(241, form.loadedMessages.getLast().getPacketId());
            assertTrue(form.loadedMessages.size() <= FormChatBase.MAX_LOADED_MESSAGES);
        });
    }

    @Test
    void switchingKeepsSharedControlsAttachedAndOldCallbacksCannotScrollNewChat() throws Exception {
        seed(80);
        fx(() -> form.openChat(channel(0)));
        drain();
        fx(() -> {
            int[] detachments = {0};
            form.messageArea.parentProperty().addListener((_, _, parent) -> {
                if (parent == null) detachments[0]++;
            });
            form.messageScrollPane.setVvalue(.4);
            form.saveCurrentChatScrollState();
            var saved = form.getSavedScrollState(channel(0));
            form.scrollToBottom(); // queued callback belongs to the chat being left
            form.openChat(channel(1));
            form.openChat(channel(0));
            assertEquals(0, detachments[0]);
            assertEquals(saved, form.getSavedScrollState(channel(0)));
        });
        drain();
        fx(() -> {
            var saved = form.getSavedScrollState(channel(0));
            assertEquals(saved.anchorOffset(), anchorOffset(saved.anchorDbId()), 1.0);
            assertFalse(form.isScrollStateSyncSuspended());
        });
    }

    @Test
    void pendingRemoteHistoryStillArrivesWhenChatFormIsHidden() throws Exception {
        DelayedTransport transport = new DelayedTransport();
        try (RpcClient client = new RpcClient(transport)) {
            fx(() -> {
                form.remoteRpcState = new RemoteRpcState(client, new JsonObject());
                form.openChat(channel(0));
                form.formClose();
                transport.reply(0, "loaded while hidden", 30);
            });
            drain();
            fx(() -> {
                assertEquals("loaded while hidden", form.loadedMessages.getFirst().getText());
                assertFalse(form.formVisible);
                assertFalse(form.isScrollStateSyncSuspended());
            });
        }
    }

    @Test
    void remoteIncomingMessageDuringInitialLoadIsMergedAfterThePage() throws Exception {
        DelayedTransport transport = new DelayedTransport();
        try (RpcClient client = new RpcClient(transport)) {
            fx(() -> {
                form.remoteRpcState = new RemoteRpcState(client, new JsonObject());
                form.openChat(channel(0));
                MeshMessage incoming = message(31);
                incoming.setDbId(31);
                form.processMessageChangeEvents(List.of(com.meshtastic.client.model.MessageChangeEvent.newMessage(
                        "channel", "0", form.currentOwnerNodeId(), incoming)));
                transport.reply(0, "history", 30);
            });
            drain();
            fx(() -> {
                assertEquals(List.of(30L, 31L), form.loadedMessages.stream().map(MeshMessage::getDbId).toList());
                assertEquals(31, form.latestKnownDbId);
            });
        }
    }

    @Test
    void delayedRpcFromFirstVisitCannotOverwriteSecondVisitToSameChat() throws Exception {
        DelayedTransport transport = new DelayedTransport();
        try (RpcClient client = new RpcClient(transport)) {
            fx(() -> {
                form.remoteRpcState = new RemoteRpcState(client, new JsonObject());
                form.openChat(channel(0));
                form.openChat(channel(1));
                form.openChat(channel(0));
                assertEquals(3, transport.requests.size());
                transport.reply(2, "current visit", 30);
            });
            drain();
            fx(() -> {
                assertEquals("current visit", form.loadedMessages.getFirst().getText());
                assertFalse(form.isScrollStateSyncSuspended(), "old pending RPCs must not freeze scrolling");
                transport.reply(0, "stale first visit", 10);
                transport.reply(1, "another chat", 20);
            });
            drain();
            fx(() -> {
                assertEquals("current visit", form.loadedMessages.getFirst().getText());
                assertEquals(1, form.loadedMessages.size());
                assertFalse(form.isScrollStateSyncSuspended());
            });
        }
    }

    @Test
    void pendingPageUsesPositionAtApplicationAndDoesNotBlockNewScrollInput() throws Exception {
        seed(240);
        fx(() -> form.openChat(channel(0)));
        drain();
        fx(() -> {
            form.hold = true;
            form.loadOlderMessages();
            form.loadOlderMessages();
            assertEquals(1, form.held.size(), "same cursor must share one request");
            form.messageScrollPane.setVvalue(.65);
        });
        pulse();
        FormChatBase.ChatScrollState[] anchor = new FormChatBase.ChatScrollState[1];
        fx(() -> anchor[0] = form.captureViewportAnchor());
        fx(() -> {
            MeshMessage incoming = message(241);
            MessageDbService.getInstance().save(incoming, "channel", "0", "");
            form.refreshCurrentChat();
        });
        completeHeld(0);
        drain();
        fx(() -> assertEquals(anchor[0].anchorOffset(), anchorOffset(anchor[0].anchorDbId()), 1));
    }

    @Test
    void prefetchWaitsAfterDirectionChangeAndAppliesWithoutASecondQuery() throws Exception {
        seed(240);
        fx(() -> form.openChat(channel(0)));
        drain();
        long[] first = new long[1];
        fx(() -> {
            first[0] = form.oldestLoadedDbId;
            form.hold = true;
            double extent = form.messageContainer.getHeight() - form.messageScrollPane.getViewportBounds().getHeight();
            double near = 2.4 * form.messageScrollPane.getViewportBounds().getHeight() / extent;
            form.messageScrollPane.setVvalue(near);
            form.prefetchAdjacentHistory(near);
            form.prefetchAdjacentHistory(near);
            assertEquals(1, form.held.size());
            form.messageScrollPane.setVvalue(.95);
        });
        completeHeld(0);
        drain();
        fx(() -> {
            assertEquals(first[0], form.oldestLoadedDbId, "prefetch must not trim the user's new position");
            form.messageScrollPane.setVvalue(.1);
            assertEquals(1, form.held.size(), "prefetched page must be reused");
            assertTrue(form.oldestLoadedDbId < first[0]);
        });
        drain();
    }

    @Test
    void oldLocalPageCannotOverwriteASecondVisitEvenWhenCancellationIsIgnored() throws Exception {
        seed(80);
        fx(() -> {
            form.hold = true;
            form.openChat(channel(0));
            form.openChat(channel(1));
            form.openChat(channel(0));
            assertEquals(3, form.held.size());
        });
        completeHeld(2);
        drain();
        completeHeld(0);
        completeHeld(1);
        drain();
        fx(() -> {
            assertEquals(50, form.loadedMessages.size());
            assertTrue(form.loadedMessages.stream().allMatch(message -> message.getChannelIndex() == 0));
            assertEquals(messages.getLast().getDbId(), form.newestLoadedDbId);
        });
    }

    @Test
    void failedPageCanBeRetriedAndDoesNotMarkTheBeginningOfHistory() throws Exception {
        seed(120);
        fx(() -> form.openChat(channel(0)));
        drain();
        fx(() -> {
            form.hold = true;
            form.loadOlderMessages();
            form.held.getFirst().future.completeExceptionally(new java.sql.SQLException("test read failure"));
        });
        drain();
        fx(() -> {
            assertFalse(form.allHistoryLoaded);
            assertEquals(50, form.loadedMessages.size());
            form.loadOlderMessages();
            assertEquals(2, form.held.size());
        });
        completeHeld(1);
        drain();
        fx(() -> assertEquals(100, form.loadedMessages.size()));
    }

    @Test
    void stalePageAfterDeletionIsReloadedWithoutResurrectingTheMessage() throws Exception {
        seed(150);
        fx(() -> form.openChat(channel(0)));
        drain();
        fx(() -> { form.hold = true; form.loadOlderMessages(); });
        ChatHistoryLoader.Page oldPage = MessageDbService.getInstance().loadHistoryPage(form.held.getFirst().request);
        long deleted = oldPage.messages().getFirst().getDbId();
        MessageDbService.getInstance().deleteMessage(deleted);
        fx(() -> form.held.getFirst().future.complete(oldPage));
        pulse();
        fx(() -> assertEquals(2, form.held.size()));
        completeHeld(1);
        drain();
        fx(() -> assertTrue(form.loadedMessages.stream().noneMatch(message -> message.getDbId() == deleted)));
    }

    @Test
    void navigationOutsideWindowAndCellRecyclingKeepSelectionAndHighlight() throws Exception {
        seed(240);
        fx(() -> form.openChat(channel(0)));
        drain();
        long selected = messages.getLast().getDbId();
        fx(() -> {
            form.selectedMessageDbIds.add(selected);
            form.ensureMessageLoaded(messages.get(10).getDbId());
            form.scrollToMessage(messages.get(10).getDbId(), 0);
        });
        drain();
        fx(() -> {
            assertTrue(form.messageContainer.containsMessage(messages.get(10).getDbId()));
            assertEquals(-12, anchorOffset(messages.get(10).getDbId()), 1);
            assertTrue(form.selectedMessageDbIds.contains(selected));
            form.scrollToMessage(selected, 0);
        });
        drain();
        fx(() -> {
            assertTrue(form.loadedMessageRows.get(selected).getStyleClass().contains("chat-message-row-selected"));
            assertTrue(form.loadedMessageRows.size() < 30);
        });
    }

    @Test
    void searchFindsUnloadedHistoryAndKeepsHighlightAfterVirtualization() throws Exception {
        MeshMessage target = new MeshMessage("!00000002", "!ffffffff", 0, "unique_history_search_target", 1, false);
        target.setPacketId(999);
        MessageDbService.getInstance().save(target, "channel", "0", "");
        seed(240);
        fx(() -> form.openChat(channel(0)));
        drain();
        fx(() -> {
            form.detailPane.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.F, false, true, false, false));
            var field = (javafx.scene.control.TextField) form.lookup(".chat-message-search-field");
            field.setText("unique_history_search_target");
            field.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ENTER, false, false, false, false));
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean[] found = {false};
        while (!found[0] && System.nanoTime() < deadline) {
            pulse();
            fx(() -> found[0] = form.loadedMessageRows.containsKey(target.getDbId()));
            if (!found[0]) Thread.sleep(10);
        }
        assertTrue(found[0], "search must load its result from outside the current window");
        drain();
        fx(() -> assertTrue(form.loadedMessageRows.get(target.getDbId()).getStyleClass().contains("chat-message-search-hit")));
    }

    @Test
    void paginationAndFontRefreshPreserveTheCurrentDraft() throws Exception {
        seed(100);
        fx(() -> form.openChat(channel(0)));
        drain();
        var field = com.meshtastic.client.components.chat.ChatInputBar.class.getDeclaredField("messageInput");
        field.setAccessible(true);
        var input = (com.meshtastic.client.components.chat.EmojiTextField) field.get(form.chatInputBar);
        fx(() -> { input.setText("Unsent draft 🚀"); form.loadOlderMessages(); });
        drain();
        fx(() -> { form.refreshLoadedMessageRows(true); assertEquals("Unsent draft 🚀", input.getText()); });
        drain();
        fx(() -> assertEquals("Unsent draft 🚀", input.getText()));
    }

    @Test
    void deliveryStatusUpdatesWhileMessageCellIsOffscreen() throws Exception {
        MeshMessage outgoing = new MeshMessage("!owner", "!ffffffff", 0, "pending", 1, true);
        outgoing.setPacketId(10001);
        outgoing.setStatus(MeshMessage.DeliveryStatus.SENDING);
        MessageDbService.getInstance().save(outgoing, "channel", "0", "");
        seed(48);
        fx(() -> form.openChat(channel(0)));
        drain();
        fx(() -> {
            assertFalse(form.loadedMessageRows.containsKey(outgoing.getDbId()));
            MessageDbService.getInstance().updateStatusById(outgoing.getDbId(), "", MeshMessage.DeliveryStatus.DELIVERED, null);
            form.refreshCurrentChat();
            MeshMessage model = form.loadedMessages.stream().filter(message -> message.getDbId() == outgoing.getDbId()).findFirst().orElseThrow();
            assertEquals(MeshMessage.DeliveryStatus.DELIVERED, model.getStatus());
            form.scrollToMessage(outgoing.getDbId(), 0);
        });
        drain();
        fx(() -> assertTrue(form.loadedMessageRows.containsKey(outgoing.getDbId())));
    }

    private void completeHeld(int index) throws Exception {
        var held = form.held.get(index);
        var page = MessageDbService.getInstance().loadHistoryPage(held.request);
        fx(() -> held.future.complete(page));
    }

    private void pulse() throws Exception {
        for (int i = 0; i < 12; i++) fx(() -> { form.applyCss(); form.layout(); });
    }

    private void seed(int count) {
        for (int i = 1; i <= count; i++) {
            MeshMessage message = message(i);
            MessageDbService.getInstance().save(message, "channel", "0", "");
            messages.add(message);
        }
    }

    private static MeshMessage message(int number) {
        var message = new MeshMessage("!00000002", "!ffffffff", 0,
                "Message " + number + " 🚀 https://example.test/\n" + "Wrapped text. ".repeat(number % 5),
                1700000000L + number, false);
        message.setPacketId(number);
        return message;
    }

    private double anchorOffset(long id) {
        return form.messageScrollPane.getVvalue() * Math.max(0,
                form.messageContainer.getHeight() - form.messageScrollPane.getViewportBounds().getHeight())
                - form.messageContainer.messageTop(id);
    }

    private static ChatItem channel(int index) {
        return ChatItem.fromChannel(ChannelProtos.Channel.newBuilder().setIndex(index)
                .setRole(ChannelProtos.Channel.Role.PRIMARY)
                .setSettings(ChannelProtos.ChannelSettings.newBuilder().setName("Test " + index))
                .build(), (MeshMessage) null, 0, false);
    }

    private void drain() throws Exception {
        for (int i = 0; i < 16; i++) {
            fx(() -> { form.applyCss(); form.layout(); });
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean[] loading = {true};
        while (loading[0] && System.nanoTime() < deadline) {
            fx(() -> { form.applyCss(); form.layout(); loading[0] = form.isHistoryLoading(); });
            if (loading[0]) Thread.sleep(10);
        }
        assertFalse(loading[0], "history reader did not complete");
        for (int i = 0; i < 6; i++) fx(() -> { form.applyCss(); form.layout(); });
    }

    private static void fx(Runnable task) throws Exception {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { task.run(); result.complete(null); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        result.get(30, TimeUnit.SECONDS);
    }

    private record Held(ChatHistoryLoader.Request request, CompletableFuture<ChatHistoryLoader.Page> future) {}

    private static class ControlledForm extends FormChat {
        boolean hold;
        final List<Held> held = new ArrayList<>();
        @Override protected CompletableFuture<ChatHistoryLoader.Page> fetchHistoryPage(ChatHistoryLoader.Request request) {
            if (!hold) return super.fetchHistoryPage(request);
            var future = new CompletableFuture<ChatHistoryLoader.Page>() {
                @Override public boolean cancel(boolean interrupt) { return false; }
            };
            held.add(new Held(request, future));
            return future;
        }
    }

    private static class DelayedTransport implements RpcTransport {
        final List<JsonObject> requests = new ArrayList<>();
        private RpcTransportListener listener;
        @Override public void setListener(RpcTransportListener listener) { this.listener = listener; }
        @Override public void send(String message) { requests.add(JsonParser.parseString(message).getAsJsonObject()); }
        @Override public boolean isOpen() { return true; }
        @Override public void close() {}

        void reply(int requestIndex, String text, long dbId) {
            JsonObject message = new JsonObject();
            message.addProperty("text", text);
            message.addProperty("dbId", dbId);
            message.addProperty("fromNodeId", "!00000002");
            message.addProperty("toNodeId", "!ffffffff");
            JsonArray items = new JsonArray();
            items.add(message);
            JsonObject result = new JsonObject();
            result.add("items", items);
            JsonObject response = new JsonObject();
            response.addProperty("type", "rpc_response");
            response.add("requestId", requests.get(requestIndex).get("requestId"));
            response.addProperty("ok", true);
            response.add("result", result);
            listener.onMessage(response.toString());
        }
    }
}
