package com.meshtastic.client.protocol.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.utils.AppPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class RemoteChatJsonTest {

    @Test
    void parseChatItemsRestoresServerUnreadCount() {
        JsonObject item = new JsonObject();
        item.addProperty("type", "CHANNEL");
        item.addProperty("displayName", "Primary");
        item.addProperty("avatarText", "#Pr");
        item.addProperty("avatarColor", "#5B8DEF");
        item.addProperty("lastMessageText", "hello");
        item.addProperty("lastMessageTime", 1234L);
        item.addProperty("unreadCount", 4);
        item.addProperty("channelIndex", 0);
        item.addProperty("peerNodeId", "");
        item.addProperty("muted", false);
        JsonArray items = new JsonArray();
        items.add(item);
        JsonObject result = new JsonObject();
        result.add("items", items);

        List<ChatItem> parsed = RemoteChatJson.parseChatItems(result);

        assertEquals(1, parsed.size());
        assertEquals(4, parsed.getFirst().getUnreadCount());
    }

    @Test
    void parseChatItemsUsesRpcClientMutePreference(@TempDir Path tempHome) {
        TestEnvironmentSupport.setUserHome(tempHome);
        String ownerId = AppPreferences.remoteChatOwnerId("rpc-1");
        AppPreferences.setChatMuted(ownerId, "channel", "0", true);

        JsonObject item = new JsonObject();
        item.addProperty("type", "CHANNEL");
        item.addProperty("channelIndex", 0);
        item.addProperty("muted", false);
        JsonArray items = new JsonArray();
        items.add(item);
        JsonObject result = new JsonObject();
        result.add("items", items);

        List<ChatItem> parsed = RemoteChatJson.parseChatItems(result, ownerId);

        assertTrue(parsed.getFirst().isMuted());
        assertFalse(RemoteChatJson.parseChatItems(result,
                AppPreferences.remoteChatOwnerId("rpc-2")).getFirst().isMuted());
    }

    @Test
    void parseMessagesRestoresReplyTextAndReactions() {
        JsonObject message = new JsonObject();
        message.addProperty("fromNodeId", "!11111111");
        message.addProperty("toNodeId", "!ffffffff");
        message.addProperty("channelIndex", 0);
        message.addProperty("text", "answer");
        message.addProperty("timestamp", 1234L);
        message.addProperty("outgoing", false);
        message.addProperty("packetId", 42);
        message.addProperty("replyId", 7);
        message.addProperty("replyText", "quoted");
        message.addProperty("replyToOutgoing", true);
        message.addProperty("senderName", "Alice");
        message.addProperty("dbId", 10L);

        JsonObject reaction = new JsonObject();
        reaction.addProperty("targetPacketId", 42);
        reaction.addProperty("fromNodeId", "!22222222");
        reaction.addProperty("emoji", "👍");
        reaction.addProperty("timestamp", 1235L);
        reaction.addProperty("outgoing", true);
        reaction.addProperty("dbId", 11L);
        reaction.addProperty("packetId", 43);
        reaction.addProperty("status", MeshMessage.DeliveryStatus.DELIVERED.name());
        reaction.addProperty("senderName", "Bob");
        JsonArray reactions = new JsonArray();
        reactions.add(reaction);
        message.add("reactions", reactions);

        JsonArray items = new JsonArray();
        items.add(message);
        JsonObject result = new JsonObject();
        result.add("items", items);

        List<MeshMessage> parsed = RemoteChatJson.parseMessages(result);

        assertEquals(1, parsed.size());
        MeshMessage parsedMessage = parsed.getFirst();
        assertEquals("quoted", parsedMessage.getReplyText());
        assertTrue(parsedMessage.isReplyToOutgoing());
        assertEquals(1, parsedMessage.getReactions().size());
        assertEquals("👍", parsedMessage.getReactions().getFirst().getEmoji());
        assertEquals("Bob", parsedMessage.getReactions().getFirst().getSenderName());
        assertEquals(MeshMessage.DeliveryStatus.DELIVERED, parsedMessage.getReactions().getFirst().getStatus());
    }

    @Test
    void sendParamsKeepsNegativeReplyPacketId() {
        JsonObject params = RemoteChatJson.sendParams("channel", "0", "reply", -123);

        assertEquals(-123, params.get("replyId").getAsInt());
    }

    @Test
    void sendParamsIncludesClientRequestIdWhenPresent() {
        JsonObject params = RemoteChatJson.sendParams("channel", "0", "hello", 0, " request-1 ");

        assertEquals("request-1", params.get("clientRequestId").getAsString());
    }

    @Test
    void retryParamsIncludeStableDbIdAndCurrentPacketId() {
        JsonObject params = RemoteChatJson.retryParams("channel", "0", 55L, 123);

        assertEquals("channel", params.get("chatType").getAsString());
        assertEquals("0", params.get("chatKey").getAsString());
        assertEquals(55L, params.get("dbId").getAsLong());
        assertEquals(123, params.get("packetId").getAsInt());
    }

    @Test
    void reactionParamsIncludeTargetPacketAndEmoji() {
        JsonObject params = RemoteChatJson.reactionParams("dm", "!12345678", -123, "👍");

        assertEquals("dm", params.get("chatType").getAsString());
        assertEquals("!12345678", params.get("chatKey").getAsString());
        assertEquals(-123, params.get("targetPacketId").getAsInt());
        assertEquals("👍", params.get("emoji").getAsString());
    }
}
