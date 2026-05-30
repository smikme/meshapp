package com.meshtastic.client.lua.api;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.MessageChangeEvent;
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
}
