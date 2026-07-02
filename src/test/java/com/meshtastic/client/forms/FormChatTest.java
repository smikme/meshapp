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
import com.meshtastic.client.system.DrawerManager;
import com.meshtastic.client.system.DrawerPane;
import com.meshtastic.client.system.MainForm;
import com.meshtastic.client.system.RootPane;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
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
    void responsiveChatListExpandsAfterCompactMode() {
        onFxThread(() -> {
            FormChat form = new FormChat();
            form.selectedChat = channel(0);
            form.resize(650, 800);

            invokeNoArg(form, "updateResponsiveChatLayout");

            VBox chatListPane = readField(form, "chatListPane");
            ListView<?> chatListView = readField(form, "chatListView");
            SplitPane splitPane = readField(form, "chatSplitPane");

            assertEquals(64.0, chatListPane.getPrefWidth(), 0.01);
            assertEquals(64.0, chatListPane.getMaxWidth(), 0.01);
            assertEquals(64.0, chatListView.getPrefWidth(), 0.01);
            assertTrue(chatListView.getPseudoClassStates().stream()
                    .anyMatch(pseudoClass -> "compact".equals(pseudoClass.getPseudoClassName())));
            assertEquals(64.0 / 650.0, splitPane.getDividers().getFirst().getPosition(), 0.02);

            form.resize(820, 800);
            invokeNoArg(form, "updateResponsiveChatLayout");

            assertEquals(314.0, chatListPane.getPrefWidth(), 0.01);
            assertEquals(314.0, chatListPane.getMinWidth(), 0.01);
            assertEquals(314.0, chatListPane.getMaxWidth(), 0.01);
            assertFalse(chatListView.getPseudoClassStates().stream()
                    .anyMatch(pseudoClass -> "compact".equals(pseudoClass.getPseudoClassName())));
            assertEquals(314.0 / 820.0, splitPane.getDividers().getFirst().getPosition(), 0.02);
            return null;
        });
    }

    @Test
    void responsiveChatListCompactsWhenNoChatIsSelected() {
        onFxThread(() -> {
            FormChat form = new FormChat();
            form.selectedChat = null;
            form.resize(650, 800);

            invokeNoArg(form, "updateResponsiveChatLayout");

            VBox chatListPane = readField(form, "chatListPane");
            ListView<?> chatListView = readField(form, "chatListView");
            SplitPane splitPane = readField(form, "chatSplitPane");

            assertTrue(splitPane.getItems().contains(chatListPane));
            assertEquals(64.0, chatListPane.getPrefWidth(), 0.01);
            assertEquals(64.0, chatListPane.getMinWidth(), 0.01);
            assertEquals(64.0, chatListPane.getMaxWidth(), 0.01);
            assertTrue(chatListView.getPseudoClassStates().stream()
                    .anyMatch(pseudoClass -> "compact".equals(pseudoClass.getPseudoClassName())));
            assertEquals(64.0 / 650.0, splitPane.getDividers().getFirst().getPosition(), 0.02);
            return null;
        });
    }

    @Test
    void responsiveChatListUsesExpandedWidthWhenNoChatIsSelected() {
        onFxThread(() -> {
            FormChat form = new FormChat();
            form.selectedChat = null;
            form.resize(820, 800);

            invokeNoArg(form, "updateResponsiveChatLayout");

            VBox chatListPane = readField(form, "chatListPane");
            ListView<?> chatListView = readField(form, "chatListView");
            SplitPane splitPane = readField(form, "chatSplitPane");

            assertTrue(splitPane.getItems().contains(chatListPane));
            assertEquals(314.0, chatListPane.getPrefWidth(), 0.01);
            assertEquals(314.0, chatListPane.getMinWidth(), 0.01);
            assertEquals(314.0, chatListPane.getMaxWidth(), 0.01);
            assertFalse(chatListView.getPseudoClassStates().stream()
                    .anyMatch(pseudoClass -> "compact".equals(pseudoClass.getPseudoClassName())));
            assertEquals(314.0 / 820.0, splitPane.getDividers().getFirst().getPosition(), 0.02);
            return null;
        });
    }

    @Test
    void responsiveChatListDoesNotRestoreNarrowSavedWidthForOpenChat() {
        onFxThread(() -> {
            AppPreferences.setChatDividerPos(0.24);

            FormChat form = new FormChat();
            form.selectedChat = channel(0);
            form.resize(820, 800);

            invokeNoArg(form, "updateResponsiveChatLayout");

            VBox chatListPane = readField(form, "chatListPane");
            SplitPane splitPane = readField(form, "chatSplitPane");

            assertEquals(314.0, chatListPane.getPrefWidth(), 0.01);
            assertEquals(314.0, chatListPane.getMinWidth(), 0.01);
            assertEquals(314.0 / 820.0, splitPane.getDividers().getFirst().getPosition(), 0.02);
            return null;
        });
    }

    @Test
    void responsiveChatListUsesSceneWidthWhenFormWasCompressed() {
        onFxThread(() -> {
            FormChat form = new FormChat();
            form.selectedChat = channel(0);
            new Scene(new StackPane(form), 876, 800);
            form.resize(489, 800);

            invokeNoArg(form, "updateResponsiveChatLayout");

            VBox chatListPane = readField(form, "chatListPane");
            SplitPane splitPane = readField(form, "chatSplitPane");

            assertTrue(splitPane.getItems().contains(chatListPane));
            assertEquals(314.0, chatListPane.getPrefWidth(), 0.01);
            assertEquals(314.0, chatListPane.getMinWidth(), 0.01);
            Boolean hidden = readField(form, "chatListHiddenMode");
            assertFalse(hidden);
            return null;
        });
    }

    @Test
    void responsiveChatListUsesWindowWidthWhenFormAndSceneWereCompressed() {
        onFxThread(() -> {
            AppPreferences.setChatDividerPos(0.4);
            AppPreferences.setChatListWidth(480.0);

            Stage stage = new Stage();
            try {
                FormChat form = new FormChat();
                form.selectedChat = channel(0);
                new Scene(new StackPane(form), 650, 800);
                stage.setScene(form.getScene());
                stage.setWidth(1383);
                form.resize(650, 800);

                invokeNoArg(form, "updateResponsiveChatLayout");

                VBox chatListPane = readField(form, "chatListPane");
                SplitPane splitPane = readField(form, "chatSplitPane");

                assertTrue(splitPane.getItems().contains(chatListPane));
                assertEquals(480.0, chatListPane.getPrefWidth(), 0.01);
                assertEquals(314.0, chatListPane.getMinWidth(), 0.01);
                assertTrue(chatListPane.getMaxWidth() > 480.0);
                assertTrue(splitPane.getDividers().getFirst().getPosition() < 0.4);
            } finally {
                stage.close();
            }
            return null;
        });
    }

    @Test
    void responsiveChatListKeepsPixelWidthWhenWindowExpandsQuickly() {
        onFxThread(() -> {
            AppPreferences.setChatDividerPos(0.35);
            AppPreferences.setChatListWidth(314.0);

            FormChat form = new FormChat();
            form.selectedChat = channel(0);
            form.resize(820, 800);
            invokeNoArg(form, "updateResponsiveChatLayout");

            SplitPane splitPane = readField(form, "chatSplitPane");
            VBox chatListPane = readField(form, "chatListPane");
            assertEquals(314.0 / 820.0, splitPane.getDividers().getFirst().getPosition(), 0.02);

            splitPane.setDividerPositions(0.75);
            form.resize(1383, 800);
            invokeNoArg(form, "updateResponsiveChatLayout");

            assertEquals(314.0, chatListPane.getPrefWidth(), 0.01);
            assertEquals(314.0, AppPreferences.getChatListWidth(0.0), 0.01);
            assertEquals(314.0 / 1383.0, splitPane.getDividers().getFirst().getPosition(), 0.02);
            return null;
        });
    }

    @Test
    void responsiveChatListRestoresMissingListPaneWhenModeSaysVisible() {
        onFxThread(() -> {
            FormChat form = new FormChat();
            form.selectedChat = channel(0);
            form.resize(820, 800);

            VBox chatListPane = readField(form, "chatListPane");
            VBox detailPane = readField(form, "detailPane");
            SplitPane splitPane = readField(form, "chatSplitPane");
            splitPane.getItems().setAll(detailPane);

            invokeNoArg(form, "updateResponsiveChatLayout");

            assertTrue(splitPane.getItems().contains(chatListPane));
            assertEquals(314.0, chatListPane.getPrefWidth(), 0.01);
            assertEquals(314.0, chatListPane.getMinWidth(), 0.01);
            Boolean hidden = readField(form, "chatListHiddenMode");
            assertFalse(hidden);
            return null;
        });
    }

    @Test
    void mainFormStretchesChatFormBeforeResponsiveDecision() {
        onFxThread(() -> {
            AppPreferences.setChatDividerPos(0.4);
            AppPreferences.setChatListWidth(360.0);

            MainForm mainForm = new MainForm();
            FormChat form = new FormChat();
            form.selectedChat = channel(0);
            mainForm.setForm(form);

            StackPane root = new StackPane(mainForm);
            new Scene(root, 900, 800);
            root.resize(900, 800);
            root.applyCss();
            root.layout();

            invokeNoArg(form, "updateResponsiveChatLayout");

            VBox chatListPane = readField(form, "chatListPane");
            SplitPane splitPane = readField(form, "chatSplitPane");

            assertTrue(mainForm.getWidth() >= 890.0);
            assertTrue(form.getWidth() >= 890.0);
            assertTrue(splitPane.getItems().contains(chatListPane));
            assertEquals(360.0, chatListPane.getPrefWidth(), 0.01);
            assertTrue(chatListPane.getMaxWidth() > 360.0);
            assertEquals(0.4, splitPane.getDividers().getFirst().getPosition(), 0.02);
            return null;
        });
    }

    @Test
    void rootPaneStretchesChatFormBeforeResponsiveDecision() {
        onFxThread(() -> {
            AppPreferences.setChatDividerPos(0.4);
            AppPreferences.setChatListWidth(500.0);

            RootPane rootPane = new RootPane();
            FormChat form = new FormChat();
            form.selectedChat = channel(0);
            rootPane.getMainForm().setForm(form);

            new Scene(rootPane, 1383, 900);
            rootPane.resize(1383, 900);
            rootPane.applyCss();
            rootPane.layout();

            invokeNoArg(form, "updateResponsiveChatLayout");

            VBox chatListPane = readField(form, "chatListPane");
            SplitPane splitPane = readField(form, "chatSplitPane");

            assertTrue(rootPane.getMainForm().getWidth() >= 1300.0);
            assertTrue(form.getWidth() >= 1300.0);
            assertTrue(splitPane.getItems().contains(chatListPane));
            assertEquals(500.0, chatListPane.getPrefWidth(), 0.01);
            assertTrue(chatListPane.getMaxWidth() > 500.0);
            assertTrue(splitPane.getDividers().getFirst().getPosition() < 0.4);
            return null;
        });
    }

    @Test
    void responsiveChatListRestoresSavedSplitterOnceDialogHasRoom() {
        onFxThread(() -> {
            AppPreferences.setChatDividerPos(0.4);
            AppPreferences.setChatListWidth(360.0);

            FormChat form = new FormChat();
            form.selectedChat = channel(0);
            form.resize(900, 800);

            invokeNoArg(form, "updateResponsiveChatLayout");

            SplitPane splitPane = readField(form, "chatSplitPane");

            assertEquals(0.4, splitPane.getDividers().getFirst().getPosition(), 0.02);
            return null;
        });
    }

    @Test
    void responsiveChatListRestoresSavedSplitterWhenNoChatIsSelected() {
        onFxThread(() -> {
            AppPreferences.setChatDividerPos(0.4);
            AppPreferences.setChatListWidth(360.0);

            FormChat form = new FormChat();
            form.selectedChat = null;
            form.resize(900, 800);

            invokeNoArg(form, "updateResponsiveChatLayout");

            VBox chatListPane = readField(form, "chatListPane");
            SplitPane splitPane = readField(form, "chatSplitPane");

            assertTrue(splitPane.getItems().contains(chatListPane));
            assertEquals(360.0, chatListPane.getPrefWidth(), 0.01);
            assertTrue(chatListPane.getMaxWidth() > 360.0);
            assertEquals(0.4, splitPane.getDividers().getFirst().getPosition(), 0.02);
            return null;
        });
    }

    @Test
    void responsiveChatListSavesSplitterOnceDialogHasRoom() {
        onFxThread(() -> {
            AppPreferences.setChatDividerPos(0.4);
            AppPreferences.setChatListWidth(360.0);

            FormChat form = new FormChat();
            form.selectedChat = channel(0);
            form.resize(820, 800);

            invokeNoArg(form, "updateResponsiveChatLayout");
            invokeNoArg(form, "ensureChatDividerListener");

            SplitPane splitPane = readField(form, "chatSplitPane");
            splitPane.setDividerPositions(0.25);
            invokeNoArg(form, "saveUserChatListWidth");

            assertEquals(360.0, AppPreferences.getChatListWidth(0.0), 0.001);

            form.resize(900, 800);
            invokeNoArg(form, "updateResponsiveChatLayout");
            splitPane.setDividerPositions(0.45);
            invokeNoArg(form, "saveUserChatListWidth");

            assertEquals(405.0, AppPreferences.getChatListWidth(0.0), 0.001);
            assertEquals(0.45, AppPreferences.getChatDividerPos(), 0.001);
            return null;
        });
    }

    @Test
    void drawerChatUnreadDotClearsWhenChatNavigationItemIsSelected() {
        onFxThread(() -> {
            DrawerPane drawerPane = new DrawerPane();
            drawerPane.setChatUnreadDot(true);

            Circle dot = readField(drawerPane, "chatBadgeDot");
            assertTrue(dot.isVisible());

            drawerPane.setSelectedItemClass(FormChat.class);

            assertFalse(dot.isVisible());

            drawerPane.setChatUnreadDot(true);

            assertFalse(dot.isVisible());
            return null;
        });
    }

    @Test
    void hiddenChatFormReloadDoesNotClearDrawerUnreadDot() {
        onFxThread(() -> {
            DrawerPane drawerPane = new DrawerPane();
            DrawerManager.setDrawerPane(drawerPane);
            drawerPane.setChatUnreadDot(true);

            DeviceState state = new DeviceState();
            try {
                state.setMyNodeNum(0x12345678);
                state.addChannel(channelProto(0));

                FormChat form = new FormChat();
                form.state = state;
                form.formVisible = false;

                form.reloadChatList();

                Circle dot = readField(drawerPane, "chatBadgeDot");
                assertTrue(dot.isVisible());
            } finally {
                state.shutdown();
            }
            return null;
        });
    }

    @Test
    void hiddenChatFormReloadDoesNotShowDrawerUnreadDotForExistingUnread() {
        onFxThread(() -> {
            DrawerPane drawerPane = new DrawerPane();
            DrawerManager.setDrawerPane(drawerPane);
            drawerPane.setChatUnreadDot(false);

            MessageDbService db = MessageDbService.getInstance();
            DeviceState state = new DeviceState();
            try {
                state.setMyNodeNum(0x12345678);
                state.addChannel(channelProto(0));
                db.save(incoming("already unread"), "channel", "0", "!12345678");

                FormChat form = new FormChat();
                form.state = state;
                form.formVisible = false;

                form.reloadChatList();

                Circle dot = readField(drawerPane, "chatBadgeDot");
                assertFalse(dot.isVisible());
            } finally {
                state.shutdown();
            }
            return null;
        });
    }

    @Test
    void copyLoadedMessageMetadataAcceptsRetryPacketIdChangeWhenDbIdMatches() {
        MeshMessage loaded = outgoing("retry me");
        loaded.setDbId(88);
        loaded.setPacketId(100);
        loaded.setStatus(MeshMessage.DeliveryStatus.FAILED);
        loaded.setErrorReason("TIMEOUT");

        MeshMessage updated = outgoing("retry me");
        updated.setDbId(88);
        updated.setPacketId(200);
        updated.setStatus(MeshMessage.DeliveryStatus.SENDING);

        assertTrue(FormChatMessages.copyLoadedMessageMetadata(loaded, updated));
        assertEquals(200, loaded.getPacketId());
        assertEquals(MeshMessage.DeliveryStatus.SENDING, loaded.getStatus());
        assertEquals(null, loaded.getErrorReason());
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
    void reloadChatListRestoresPersistedSelectionWhenNoChatIsSelected() {
        onFxThread(() -> {
            String connectionId = "connection-persisted-selection";
            AppPreferences.saveSelectedChat(connectionId, "channel:0");

            DeviceState state = new DeviceState();
            try {
                state.setMyNodeNum(0x12345678);
                state.addChannel(channelProto(0));

                FormChat form = new FormChat();
                form.state = state;
                form.boundConnectionId = connectionId;
                form.selectedChat = null;
                form.detailPane.getChildren().setAll(form.placeholderBox);

                form.reloadChatList();
                form.reopenSelectedChatIfPossible();

                assertNotNull(form.selectedChat);
                assertEquals(ChatItem.ChatType.CHANNEL, form.selectedChat.getType());
                assertEquals(0, form.selectedChat.getChannelIndex());
                assertTrue(form.isChatDetailOpenFor(form.selectedChat));
                assertEquals("channel:0", AppPreferences.loadSelectedChat(connectionId));
            } finally {
                state.shutdown();
            }
            return null;
        });
        waitForFxEvents();
        waitForFxEvents();
    }

    @Test
    void closeChatKeepsLastSelectionForNextFormOpen() {
        onFxThread(() -> {
            String connectionId = "connection-close-keeps-selection";
            AppPreferences.removeSelectedChat(connectionId);

            FormChat form = new FormChat();
            form.boundConnectionId = connectionId;
            form.selectedChat = channel(0);
            form.rememberSelectedChatForBoundConnection();

            form.closeChat();

            assertEquals(null, form.selectedChat);
            assertTrue(form.selectedChatsByConnectionId.containsKey(connectionId));
            assertEquals("channel:0", AppPreferences.loadSelectedChat(connectionId));
            assertTrue(form.detailPane.getChildren().contains(form.placeholderBox));
            return null;
        });
    }

    @Test
    void reloadChatListClearsSelectionWhenRestoredChatNoLongerExists() {
        onFxThread(() -> {
            String connectionId = "connection-missing-selection";
            AppPreferences.saveSelectedChat(connectionId, "channel:1");

            DeviceState state = new DeviceState();
            try {
                state.setMyNodeNum(0x12345678);
                state.addChannel(channelProto(0));

                FormChat form = new FormChat();
                form.state = state;
                form.boundConnectionId = connectionId;
                form.selectedChat = channel(1);
                form.selectedChatsByConnectionId.put(
                        connectionId,
                        FormChatBase.ChatSelection.from(channel(1)));
                form.detailPane.getChildren().setAll(form.messageArea);

                form.reloadChatList();

                assertEquals(null, form.selectedChat);
                assertTrue(form.chatListView.getSelectionModel().isEmpty());
                assertFalse(form.selectedChatsByConnectionId.containsKey(connectionId));
                assertEquals(null, AppPreferences.loadSelectedChat(connectionId));
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
    void reloadChatListRestoresSavedEmptyDirectChat() {
        onFxThread(() -> {
            DeviceState state = new DeviceState();
            try {
                state.setMyNodeNum(0x12345678);
                state.addChannel(channelProto(0));
                String connectionId = "connection-saved-empty-dm";
                String peerNodeId = "!00000002";
                AppPreferences.saveSelectedChat(connectionId, "dm:" + peerNodeId);

                FormChat form = new FormChat();
                form.state = state;
                form.boundConnectionId = connectionId;

                form.reloadChatList();

                assertTrue(form.chatListView.getItems().stream()
                        .anyMatch(item -> item.getType() == ChatItem.ChatType.DIRECT_MESSAGE
                                && peerNodeId.equals(item.getPeerNodeId())));
                assertEquals(List.of(peerNodeId),
                        MessageDbService.getInstance().getDistinctDmPeers(state.getOwnerNodeId()));
            } finally {
                state.shutdown();
            }
            return null;
        });
        waitForFxEvents();
        waitForFxEvents();
    }

    @Test
    void clearDirectChatHistoryKeepsChatInListAfterRestart() {
        onFxThread(() -> {
            MessageDbService db = MessageDbService.getInstance();
            DeviceState state = new DeviceState();
            String peerNodeId = "!00000002";
            String ownerNodeId;
            try {
                state.setMyNodeNum(0x12345678);
                state.addChannel(channelProto(0));
                ownerNodeId = state.getOwnerNodeId();
                MeshMessage message = incoming("dm history");
                message.setPacketId(7001);
                db.save(message, "dm", peerNodeId, ownerNodeId);

                FormChat form = new FormChat();
                form.state = state;
                form.boundConnectionId = "connection-clear-dm-history";
                form.reloadChatList();
                ChatItem dm = form.chatListView.getItems().stream()
                        .filter(item -> item.getType() == ChatItem.ChatType.DIRECT_MESSAGE)
                        .filter(item -> peerNodeId.equals(item.getPeerNodeId()))
                        .findFirst()
                        .orElseThrow();
                assertFalse(state.getAllDirectMessages().containsKey(peerNodeId));

                form.openChat(dm);
                invokeOneArg(form, "clearChatHistory", ChatItem.class, dm);

                assertTrue(db.loadLast("dm", peerNodeId, 10, ownerNodeId).isEmpty());
                assertTrue(state.getAllDirectMessages().containsKey(peerNodeId));
                assertNotNull(form.selectedChat);
                assertEquals(peerNodeId, form.selectedChat.getPeerNodeId());
                assertTrue(form.chatListView.getItems().stream()
                        .anyMatch(item -> item.getType() == ChatItem.ChatType.DIRECT_MESSAGE
                                && peerNodeId.equals(item.getPeerNodeId())));
            } finally {
                state.shutdown();
            }

            TestEnvironmentSupport.resetSingletons();

            DeviceState restartedState = new DeviceState();
            try {
                restartedState.setMyNodeNum(0x12345678);
                restartedState.addChannel(channelProto(0));
                FormChat restartedForm = new FormChat();
                restartedForm.state = restartedState;
                restartedForm.boundConnectionId = "connection-clear-dm-history";

                restartedForm.reloadChatList();

                assertTrue(MessageDbService.getInstance().loadLast("dm", peerNodeId, 10, ownerNodeId).isEmpty());
                assertFalse(restartedState.getAllDirectMessages().containsKey(peerNodeId));
                assertTrue(restartedForm.chatListView.getItems().stream()
                        .anyMatch(item -> item.getType() == ChatItem.ChatType.DIRECT_MESSAGE
                                && peerNodeId.equals(item.getPeerNodeId())));
            } finally {
                restartedState.shutdown();
            }
            return null;
        });
        waitForFxEvents();
        waitForFxEvents();
    }

    @Test
    void deleteDirectChatRemovesPersistentThreadAndSavedSelection() {
        onFxThread(() -> {
            MessageDbService db = MessageDbService.getInstance();
            DeviceState state = new DeviceState();
            try {
                state.setMyNodeNum(0x12345678);
                state.addChannel(channelProto(0));
                String connectionId = "connection-delete-dm-thread";
                String peerNodeId = "!00000002";
                state.ensureDirectMessageThread(peerNodeId);

                FormChat form = new FormChat();
                form.state = state;
                form.boundConnectionId = connectionId;
                form.reloadChatList();
                ChatItem dm = form.chatListView.getItems().stream()
                        .filter(item -> item.getType() == ChatItem.ChatType.DIRECT_MESSAGE)
                        .filter(item -> peerNodeId.equals(item.getPeerNodeId()))
                        .findFirst()
                        .orElseThrow();

                form.openChat(dm);
                assertEquals("dm:" + peerNodeId, AppPreferences.loadSelectedChat(connectionId));

                invokeOneArg(form, "deleteChat", ChatItem.class, dm);

                assertTrue(db.getDistinctDmPeers(state.getOwnerNodeId()).isEmpty());
                assertFalse(state.getAllDirectMessages().containsKey(peerNodeId));
                assertTrue(form.chatListView.getItems().stream()
                        .noneMatch(item -> item.getType() == ChatItem.ChatType.DIRECT_MESSAGE
                                && peerNodeId.equals(item.getPeerNodeId())));
                assertEquals(null, AppPreferences.loadSelectedChat(connectionId));
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
        updated.setReplyToOutgoing(true);

        assertTrue(FormChat.copyLoadedMessageMetadata(loaded, updated));
        assertFalse(loaded.isViaMqtt());
        assertTrue(loaded.isReplyToOutgoing());
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

    @SuppressWarnings("unchecked")
    private static <T> T readField(Object target, String fieldName) {
        Field field = findField(target.getClass(), fieldName);
        try {
            return (T) field.get(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to read field " + fieldName, e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new AssertionError("Failed to find field " + fieldName);
    }

    private static void invokeNoArg(Object target, String methodName) {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                java.lang.reflect.Method method = current.getDeclaredMethod(methodName);
                method.setAccessible(true);
                method.invoke(target);
                return;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Failed to invoke method " + methodName, e);
            }
        }
        throw new AssertionError("Failed to find method " + methodName);
    }

    private static void invokeOneArg(Object target, String methodName, Class<?> argType, Object arg) {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                java.lang.reflect.Method method = current.getDeclaredMethod(methodName, argType);
                method.setAccessible(true);
                method.invoke(target, arg);
                return;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Failed to invoke method " + methodName, e);
            }
        }
        throw new AssertionError("Failed to find method " + methodName);
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
