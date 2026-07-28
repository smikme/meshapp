package com.meshtastic.client.protocol.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.utils.AppPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * JSON mapping helpers for chat RPC methods.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RemoteChatJson {

    private static final String DEFAULT_AVATAR_COLOR = "#5B8DEF";

    private RemoteChatJson() {
    }

    public static JsonObject chatMessagesParams(String chatType,
                                                String chatKey,
                                                int limit,
                                                long beforeDbId,
                                                long afterDbId) {
        JsonObject params = new JsonObject();
        params.addProperty("chatType", chatType);
        params.addProperty("chatKey", chatKey);
        params.addProperty("limit", limit);
        if (beforeDbId > 0) {
            params.addProperty("beforeDbId", beforeDbId);
        }
        if (afterDbId > 0) {
            params.addProperty("afterDbId", afterDbId);
        }
        return params;
    }

    public static JsonObject sendParams(String chatType, String chatKey, String text, int replyId) {
        return sendParams(chatType, chatKey, text, replyId, "");
    }

    public static JsonObject sendParams(String chatType,
                                        String chatKey,
                                        String text,
                                        int replyId,
                                        String clientRequestId) {
        JsonObject params = new JsonObject();
        params.addProperty("chatType", chatType);
        params.addProperty("chatKey", chatKey);
        params.addProperty("text", text);
        if (replyId != 0) {
            params.addProperty("replyId", replyId);
        }
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            params.addProperty("clientRequestId", clientRequestId.trim());
        }
        return params;
    }

    public static JsonObject reactionParams(String chatType, String chatKey, int targetPacketId, String emoji) {
        JsonObject params = new JsonObject();
        params.addProperty("chatType", chatType);
        params.addProperty("chatKey", chatKey);
        params.addProperty("targetPacketId", targetPacketId);
        params.addProperty("emoji", emoji);
        return params;
    }

    public static JsonObject retryParams(String chatType, String chatKey, long dbId, int packetId) {
        JsonObject params = new JsonObject();
        params.addProperty("chatType", chatType);
        params.addProperty("chatKey", chatKey);
        if (dbId > 0) {
            params.addProperty("dbId", dbId);
        }
        if (packetId != 0) {
            params.addProperty("packetId", packetId);
        }
        return params;
    }

    public static List<ChatItem> parseChatItems(JsonElement result) {
        return parseChatItems(result, null);
    }

    /**
     * Parses a remote chat snapshot and applies notification preferences stored
     * by this RPC client for the supplied connection scope.
     */
    public static List<ChatItem> parseChatItems(JsonElement result, String notificationOwnerId) {
        JsonArray items = objectArray(result, "items");
        List<ChatItem> parsed = new ArrayList<>();
        for (JsonElement element : items) {
            if (!isObject(element)) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            ChatItem.ChatType type = "DIRECT_MESSAGE".equals(stringField(item, "type"))
                    ? ChatItem.ChatType.DIRECT_MESSAGE
                    : ChatItem.ChatType.CHANNEL;
            int channelIndex = intField(item, "channelIndex");
            String peerNodeId = stringField(item, "peerNodeId");
            boolean muted = booleanField(item, "muted");
            if (notificationOwnerId != null && !notificationOwnerId.isBlank()) {
                String chatType = type == ChatItem.ChatType.DIRECT_MESSAGE ? "dm" : "channel";
                String chatKey = type == ChatItem.ChatType.DIRECT_MESSAGE
                        ? peerNodeId
                        : String.valueOf(channelIndex);
                muted = AppPreferences.isChatMuted(notificationOwnerId, chatType, chatKey);
            }
            parsed.add(ChatItem.remote(
                    type,
                    stringField(item, "displayName"),
                    stringField(item, "avatarText"),
                    firstText(stringField(item, "avatarColor"), DEFAULT_AVATAR_COLOR),
                    stringField(item, "lastMessageText"),
                    longField(item, "lastMessageTime"),
                    intField(item, "unreadCount"),
                    channelIndex,
                    peerNodeId,
                    muted));
        }
        return parsed;
    }

    public static List<MeshMessage> parseMessages(JsonElement result) {
        JsonArray items = objectArray(result, "items");
        List<MeshMessage> parsed = new ArrayList<>();
        for (JsonElement element : items) {
            if (isObject(element)) {
                parsed.add(parseMessageObject(element.getAsJsonObject()));
            }
        }
        return parsed;
    }

    public static MeshMessage parseResultMessage(JsonElement result) {
        if (!isObject(result)) {
            return null;
        }
        JsonElement message = result.getAsJsonObject().get("message");
        return isObject(message) ? parseMessageObject(message.getAsJsonObject()) : null;
    }

    public static String errorMessage(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            Throwable cause = current.getCause();
            if (cause == null) {
                break;
            }
            current = cause;
        }
        String message = current != null ? current.getMessage() : null;
        return message == null || message.isBlank() ? String.valueOf(error) : message;
    }

    private static MeshMessage parseMessageObject(JsonObject object) {
        MeshMessage message = new MeshMessage(
                stringField(object, "fromNodeId"),
                stringField(object, "toNodeId"),
                intField(object, "channelIndex"),
                stringField(object, "text"),
                longField(object, "timestamp"),
                booleanField(object, "outgoing"));
        message.setDbId(longField(object, "dbId"));
        message.setPacketId(intField(object, "packetId"));
        message.setReplyId(intField(object, "replyId"));
        message.setReplyText(stringField(object, "replyText"));
        message.setReplyToOutgoing(booleanField(object, "replyToOutgoing"));
        message.setHopStart(intField(object, "hopStart"));
        message.setHopLimit(intField(object, "hopLimit"));
        message.setRxRssi(intField(object, "rxRssi"));
        message.setRxSnr(floatField(object, "rxSnr"));
        message.setSenderName(stringField(object, "senderName"));
        message.setViaMqtt(booleanField(object, "viaMqtt"));
        message.setSystemMessage(booleanField(object, "systemMessage"));
        message.setErrorReason(stringField(object, "errorReason"));
        message.setReactions(parseReactions(object.get("reactions")));
        String status = stringField(object, "status");
        if (!status.isBlank()) {
            try {
                message.setStatus(MeshMessage.DeliveryStatus.valueOf(status));
            } catch (IllegalArgumentException ignored) {
                // Older or newer hosts may expose a status unknown to this client.
            }
        }
        return message;
    }

    private static List<MessageReaction> parseReactions(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<MessageReaction> reactions = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!isObject(item)) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            MessageReaction reaction = new MessageReaction(
                    intField(object, "targetPacketId"),
                    stringField(object, "fromNodeId"),
                    stringField(object, "emoji"),
                    longField(object, "timestamp"),
                    booleanField(object, "outgoing"));
            reaction.setDbId(longField(object, "dbId"));
            reaction.setPacketId(intField(object, "packetId"));
            reaction.setErrorReason(stringField(object, "errorReason"));
            reaction.setSenderName(stringField(object, "senderName"));
            String status = stringField(object, "status");
            if (!status.isBlank()) {
                try {
                    reaction.setStatus(MeshMessage.DeliveryStatus.valueOf(status));
                } catch (IllegalArgumentException ignored) {
                    // Ignore statuses unknown to this client version.
                }
            }
            reactions.add(reaction);
        }
        return reactions;
    }

    private static JsonArray objectArray(JsonElement result, String field) {
        JsonObject object = isObject(result) ? result.getAsJsonObject() : new JsonObject();
        JsonElement element = object.get(field);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static boolean isObject(JsonElement element) {
        return element != null && element.isJsonObject();
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stringField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString();
        return value == null ? "" : value.trim();
    }

    private static int intField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsInt()
                : 0;
    }

    private static long longField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsLong()
                : 0L;
    }

    private static float floatField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsFloat()
                : 0.0f;
    }

    private static boolean booleanField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                && element.getAsBoolean();
    }
}
