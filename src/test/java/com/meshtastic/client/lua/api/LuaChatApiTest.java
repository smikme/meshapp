package com.meshtastic.client.lua.api;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.MessageChangeEvent;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.MessageDbService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaChatApiTest {

    private static final String OWNER_NODE_ID = "!12345678";

    @TempDir
    Path tempHome;

    private DeviceState state;
    private LuaTable chat;
    private List<MessageChangeEvent> messageChanges;
    private AtomicInteger broadMessageListenerCalls;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        messageChanges = new CopyOnWriteArrayList<>();
        broadMessageListenerCalls = new AtomicInteger();
        state.addMessageChangeListener(messageChanges::add);
        state.addMessageListener(broadMessageListenerCalls::incrementAndGet);
        chat = new LuaChatApi(
                new LuaSandboxContext(
                        1L,
                        "test",
                        state,
                        null,
                        null,
                        OWNER_NODE_ID,
                        LuaScriptService.getInstance(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                new LuaValueMapper(state))
                .create();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void botMessageSavesLocalSystemMessageAndPublishesMessageChange() {
        LuaTable result = botMessage("channel", "2", "Built-in bot answer");

        assertEquals("Built-in bot answer", result.get("text").checkjstring());
        assertEquals("channel", result.get("chat_type").checkjstring());
        assertEquals("2", result.get("chat_key").checkjstring());
        assertTrue(result.get("system").checkboolean());

        List<MeshMessage> saved = MessageDbService.getInstance()
                .loadLast("channel", "2", 10, OWNER_NODE_ID);
        assertEquals(1, saved.size());
        MeshMessage message = saved.get(0);
        assertEquals("Built-in bot answer", message.getText());
        assertEquals("!00000000", message.getFromNodeId());
        assertEquals("!ffffffff", message.getToNodeId());
        assertEquals(2, message.getChannelIndex());
        assertTrue(message.isSystemMessage());

        assertEquals(1, messageChanges.size());
        MessageChangeEvent event = messageChanges.get(0);
        assertEquals(MessageChangeEvent.Kind.NEW_MESSAGE, event.kind());
        assertEquals("channel", event.chatType());
        assertEquals("2", event.chatKey());
        assertEquals(OWNER_NODE_ID, event.ownerNodeId());
        assertTrue(event.message().isSystemMessage());
        assertEquals(0, broadMessageListenerCalls.get());
    }

    @Test
    void nodesUsesLiveTargetAfterContextStartedWithoutState() {
        AtomicReference<DeviceState> liveState = new AtomicReference<>();
        LuaTable liveChat = new LuaChatApi(
                new LuaSandboxContext(
                        1L,
                        null,
                        null,
                        null,
                        null,
                        "",
                        LuaScriptService.getInstance(),
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
                        () -> new LuaSandboxContext.ConnectionSnapshot(
                                "live",
                                liveState.get(),
                                null,
                                null,
                                OWNER_NODE_ID)),
                new LuaValueMapper(liveState::get))
                .create();

        assertEquals(0, liveChat.get("nodes").call().checktable().length());

        DeviceState connectedState = new DeviceState();
        NodeData node = connectedState.getOrCreateNode(0x10203040);
        node.setNodeId("!10203040");
        node.setLongName("Live Node");
        liveState.set(connectedState);

        LuaTable nodes = liveChat.get("nodes").call().checktable();

        assertEquals(1, nodes.length());
        assertEquals("!10203040", nodes.get(1).checktable().get("node_id").checkjstring());
        assertEquals("Live Node", nodes.get(1).checktable().get("long_name").checkjstring());
    }

    @Test
    void botReplyTargetsSourceChatAndKeepsReplyMetadata() {
        LuaTable source = new LuaTable();
        source.set("chat_type", "dm");
        source.set("chat_key", "!abcdef01");
        source.set("packet_id", 77);
        source.set("text", "Incoming question");
        source.set("from", "!abcdef01");
        source.set("to", OWNER_NODE_ID);

        LuaTable result = chat.get("bot_reply")
                .invoke(LuaValue.varargsOf(source, LuaValue.valueOf("Bot answer")))
                .arg1()
                .checktable();

        assertEquals("dm", result.get("chat_type").checkjstring());
        assertEquals("!abcdef01", result.get("chat_key").checkjstring());
        assertEquals(77, result.get("reply_id").checkint());
        assertEquals("Incoming question", result.get("reply_text").checkjstring());

        List<MeshMessage> saved = MessageDbService.getInstance()
                .loadLast("dm", "!abcdef01", 10, OWNER_NODE_ID);
        assertEquals(1, saved.size());
        MeshMessage message = saved.get(0);
        assertEquals("Bot answer", message.getText());
        assertEquals("!00000000", message.getFromNodeId());
        assertEquals("!abcdef01", message.getToNodeId());
        assertEquals(77, message.getReplyId());
        assertEquals("Incoming question", message.getReplyText());
        assertTrue(message.isSystemMessage());
    }

    @Test
    void reactSendsMessageReactionAndPublishesReactionChange() {
        FakeTransportConnection transport = new FakeTransportConnection();
        ProtocolHandler handler = new ProtocolHandler(transport);
        try {
            LuaTable liveChat = new LuaChatApi(
                    new LuaSandboxContext(
                            1L,
                            "test",
                            state,
                            handler,
                            null,
                            OWNER_NODE_ID,
                            LuaScriptService.getInstance(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    new LuaValueMapper(state))
                    .create();

            MeshMessage target = new MeshMessage("!00000002", "!ffffffff", 0, "hello", 1234, false);
            target.setPacketId(42);
            MessageDbService.getInstance().save(target, "channel", "0", OWNER_NODE_ID);

            LuaValue result = liveChat.get("react")
                    .invoke(LuaValue.varargsOf(
                            new LuaValue[] {
                                    new LuaValueMapper(state).messageToTable(target, "channel", "0"),
                                    LuaValue.valueOf("👍")
                            }))
                    .arg1();

            assertTrue(result.checkboolean());
            List<MessageReaction> reactions = MessageDbService.getInstance()
                    .loadReactionsByTargetPacketIds("channel", "0", OWNER_NODE_ID, List.of(42))
                    .get(42);
            assertEquals(1, reactions.size());
            assertEquals("👍", reactions.getFirst().getEmoji());
            assertEquals(42, reactions.getFirst().getTargetPacketId());
            assertTrue(reactions.getFirst().isOutgoing());

            assertTrue(messageChanges.stream().anyMatch(event ->
                    event.kind() == MessageChangeEvent.Kind.REACTION_CHANGED
                            && "channel".equals(event.chatType())
                            && "0".equals(event.chatKey())
                            && event.targetPacketId() == 42));
            assertEquals(1, broadMessageListenerCalls.get());
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void botMessageRejectsInvalidScopeAndText() {
        assertThrows(LuaError.class, () -> botMessage("unknown", "0", "text"));
        assertThrows(LuaError.class, () -> botMessage("channel", "abc", "text"));
        assertThrows(LuaError.class, () -> botMessage("channel", "0", " "));

        assertTrue(MessageDbService.getInstance()
                .loadLast("channel", "0", 10, OWNER_NODE_ID)
                .isEmpty());
    }

    private LuaTable botMessage(String chatType, String chatKey, String text) {
        return chat.get("bot_message")
                .invoke(LuaValue.varargsOf(new LuaValue[]{
                        LuaValue.valueOf(chatType),
                        LuaValue.valueOf(chatKey),
                        LuaValue.valueOf(text)
                }))
                .arg1()
                .checktable();
    }

    private static final class FakeTransportConnection implements TransportConnection {
        private volatile Consumer<byte[]> dataListener;
        private volatile ConnectionListener connectionListener;
        private volatile boolean connected = true;

        @Override
        public void connect() throws ConnectionException {
            connected = true;
            if (connectionListener != null) {
                connectionListener.onConnected();
            }
        }

        @Override
        public void disconnect() {
            connected = false;
            if (connectionListener != null) {
                connectionListener.onDisconnected();
            }
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void sendBytes(byte[] data) {
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            dataListener = listener;
        }

        @Override
        public void setConnectionListener(ConnectionListener listener) {
            connectionListener = listener;
        }
    }
}
