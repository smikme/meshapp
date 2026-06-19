package com.meshtastic.client.lua.api;

import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.MessageChangeEvent;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.lua.LuaUiBotNotice;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.MessageService;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.meshtastic.proto.ChannelProtos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Implementation of {@code mesh.chat} for the Lua sandbox.
 * <p>
 * Gives scripts access only to approved chat operations: sending messages,
 * replying, reading recent messages, and listing nodes and channels.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaChatApi {

    private static final String SYSTEM_BOT_NODE_ID = "!00000000";
    private static final String BROADCAST_NODE_ID = "!ffffffff";
    private static final int MAX_BOT_MESSAGE_LENGTH = 4096;

    private final LuaSandboxContext context;
    private final LuaValueMapper mapper;
    private final MessageDbService messageDbService = MessageDbService.getInstance();

    public LuaChatApi(LuaSandboxContext context, LuaValueMapper mapper) {
        this.context = context;
        this.mapper = mapper;
    }

    /**
     * Creates the Lua table for {@code mesh.chat}.
     *
     * @return chat API table
     */
    public LuaTable create() {
        LuaTable chat = new LuaTable();
        chat.set("send_channel", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int channel = args.checkint(1);
                String text = args.checkjstring(2);
                int replyId = args.optint(3, 0);
                MeshMessage sent = sendChannelMessage(channel, text, replyId);
                return sent != null ? mapper.messageToTable(sent, "channel", String.valueOf(channel)) : LuaValue.NIL;
            }
        });
        chat.set("send_dm", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String peerNodeId = args.checkjstring(1);
                String text = args.checkjstring(2);
                int replyId = args.optint(3, 0);
                MeshMessage sent = sendDirectMessage(peerNodeId, text, replyId);
                return sent != null ? mapper.messageToTable(sent, "dm", peerNodeId) : LuaValue.NIL;
            }
        });
        chat.set("reply", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaTable message = args.checktable(1);
                String text = args.checkjstring(2);
                int replyId = LuaValueMapper.tableInt(message, "packet_id", 0);
                if (replyId == 0) {
                    throw new LuaError("Cannot reply: msg.packet_id is missing");
                }

                String chatType = LuaValueMapper.tableString(message, "chat_type");
                if ("channel".equals(chatType)) {
                    int channel = messageChannelIndex(message);
                    MeshMessage sent = sendChannelMessage(channel, text, replyId);
                    return sent != null ? mapper.messageToTable(sent, "channel", String.valueOf(channel)) : LuaValue.NIL;
                }
                if ("dm".equals(chatType)) {
                    String peerNodeId = messageDirectPeer(message);
                    MeshMessage sent = sendDirectMessage(peerNodeId, text, replyId);
                    return sent != null ? mapper.messageToTable(sent, "dm", peerNodeId) : LuaValue.NIL;
                }

                throw new LuaError("Cannot reply: unsupported msg.chat_type " + chatType);
            }
        });
        chat.set("bot_message", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String chatType = normalizeChatType(args.checkjstring(1));
                String chatKey = args.checkjstring(2);
                String text = args.checkjstring(3);
                return createBotMessage(chatType, chatKey, text, 0, null);
            }
        });
        chat.set("bot_reply", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaTable message = args.checktable(1);
                String text = args.checkjstring(2);
                String chatType = normalizeChatType(LuaValueMapper.tableString(message, "chat_type"));
                String chatKey;
                if ("channel".equals(chatType)) {
                    chatKey = String.valueOf(messageChannelIndex(message));
                } else if ("dm".equals(chatType)) {
                    chatKey = messageDirectPeer(message);
                } else {
                    throw new LuaError("Cannot bot_reply: unsupported msg.chat_type " + chatType);
                }
                int replyId = LuaValueMapper.tableInt(message, "packet_id", 0);
                String replyText = LuaValueMapper.tableString(message, "text");
                return createBotMessage(chatType, chatKey, text, replyId, replyText);
            }
        });
        chat.set("bot_notice", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                if (context.uiBridge() == null || !context.uiBridge().isAvailable()) {
                    throw new LuaError("No active UI context");
                }
                String chatType = normalizeChatType(args.checkjstring(1));
                String chatKey = args.checkjstring(2);
                String text = normalizeBotText(args.checkjstring(3));
                LuaTable options = args.arg(4).istable() ? args.arg(4).checktable() : null;
                String name = options != null ? optionalString(options, "name") : "";
                ChatScope scope = normalizeChatScope(chatType, chatKey);
                context.uiBridge().showBotNotice(new LuaUiBotNotice(
                        context.scriptId(),
                        "mesh.chat.bot_notice",
                        name,
                        scope.chatType(),
                        scope.chatKey(),
                        text));
                return LuaValue.TRUE;
            }
        });
        chat.set("recent", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String chatType = args.checkjstring(1);
                String chatKey = args.checkjstring(2);
                int limit = Math.max(1, Math.min(200, args.optint(3, 20)));
                LuaTable table = new LuaTable();
                List<MeshMessage> messages = messageDbService.loadLast(
                        chatType,
                        chatKey,
                        limit,
                        context.ownerNodeIdOrEmpty());
                for (int i = 0; i < messages.size(); i++) {
                    table.set(i + 1, mapper.messageToTable(messages.get(i), chatType, chatKey));
                }
                return table;
            }
        });
        chat.set("nodes", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable table = new LuaTable();
                DeviceState state = context.currentState();
                if (state == null) {
                    return table;
                }
                List<NodeData> nodes = new ArrayList<>(state.getNodeDb().values());
                nodes.sort(Comparator.comparing(NodeData::getNodeId, Comparator.nullsLast(String::compareTo)));
                for (int i = 0; i < nodes.size(); i++) {
                    table.set(i + 1, mapper.nodeToTable(nodes.get(i)));
                }
                return table;
            }
        });
        chat.set("channels", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable table = new LuaTable();
                DeviceState state = context.currentState();
                if (state == null) {
                    return table;
                }
                List<ChannelProtos.Channel> channels = state.getChannels();
                for (int i = 0; i < channels.size(); i++) {
                    table.set(i + 1, mapper.channelToTable(channels.get(i)));
                }
                return table;
            }
        });
        return chat;
    }

    private MeshMessage sendChannelMessage(int channel, String text, int replyId) {
        requireChatTransport();
        LuaSandboxContext.ConnectionSnapshot target = context.currentTarget();
        return target.meshCoreRuntime() != null
                ? target.meshCoreRuntime().sendChannelMessage(channel, text, replyId)
                : MessageService.sendChannelMessage(target.handler(), target.state(), channel, text, replyId);
    }

    private MeshMessage sendDirectMessage(String peerNodeId, String text, int replyId) {
        requireChatTransport();
        LuaSandboxContext.ConnectionSnapshot target = context.currentTarget();
        return target.meshCoreRuntime() != null
                ? target.meshCoreRuntime().sendDirectMessage(peerNodeId, text, replyId)
                : MessageService.sendDirectMessage(target.handler(), target.state(), peerNodeId, text, replyId);
    }

    private LuaTable createBotMessage(String chatType, String rawChatKey, String rawText, int replyId, String replyText) {
        DeviceState state = requireChatContext();
        ChatScope scope = normalizeChatScope(chatType, rawChatKey);
        String text = normalizeBotText(rawText);
        String ownerNodeId = ownerNodeIdForMessages();
        MeshMessage message = new MeshMessage(
                SYSTEM_BOT_NODE_ID,
                "dm".equals(scope.chatType()) ? scope.chatKey() : BROADCAST_NODE_ID,
                scope.channelIndex(),
                text,
                System.currentTimeMillis() / 1000,
                false);
        message.setSystemMessage(true);
        if (replyId > 0) {
            message.setReplyId(replyId);
            message.setReplyText(replyText);
        }

        messageDbService.save(message, scope.chatType(), scope.chatKey(), ownerNodeId);
        state.fireMessageChange(MessageChangeEvent.newMessage(
                scope.chatType(),
                scope.chatKey(),
                ownerNodeId,
                message));
        return mapper.messageToTable(message, scope.chatType(), scope.chatKey());
    }

    private ChatScope normalizeChatScope(String chatType, String rawChatKey) {
        String chatKey = rawChatKey != null ? rawChatKey.trim() : "";
        if (chatKey.isEmpty()) {
            throw new LuaError("chat_key must not be empty");
        }

        if ("channel".equals(chatType)) {
            int channel;
            try {
                channel = Integer.parseInt(chatKey);
            } catch (NumberFormatException e) {
                throw new LuaError("channel chat_key must be a channel index");
            }
            if (channel < 0) {
                throw new LuaError("channel chat_key must be >= 0");
            }
            return new ChatScope("channel", String.valueOf(channel), channel);
        }
        if ("dm".equals(chatType)) {
            return new ChatScope("dm", chatKey, 0);
        }
        throw new LuaError("Unsupported chat_type: " + chatType);
    }

    private String normalizeChatType(String chatType) {
        return chatType != null ? chatType.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String normalizeBotText(String rawText) {
        String text = rawText != null ? rawText : "";
        if (text.isBlank()) {
            throw new LuaError("bot message text must not be empty");
        }
        if (text.length() > MAX_BOT_MESSAGE_LENGTH) {
            throw new LuaError("bot message text is too long");
        }
        return text;
    }

    private static String optionalString(LuaTable table, String key) {
        LuaValue value = table.get(key);
        return value.isnil() ? "" : value.checkjstring();
    }

    private DeviceState requireChatContext() {
        DeviceState state = context.currentState();
        if (state == null || ownerNodeIdForMessages().isBlank()) {
            throw new LuaError("No active chat context");
        }
        return state;
    }

    private String ownerNodeIdForMessages() {
        if (context.currentOwnerNodeId() != null && !context.currentOwnerNodeId().isBlank()) {
            return context.currentOwnerNodeId();
        }
        DeviceState state = context.currentState();
        return state != null && state.getOwnerNodeId() != null
                ? state.getOwnerNodeId()
                : "";
    }

    private int messageChannelIndex(LuaTable message) {
        LuaValue channel = message.get("channel");
        if (!channel.isnil()) {
            return channel.checkint();
        }
        String chatKey = LuaValueMapper.tableString(message, "chat_key");
        if (chatKey != null && !chatKey.isBlank()) {
            try {
                return Integer.parseInt(chatKey);
            } catch (NumberFormatException ignored) {
                // Fall through to a Lua-facing error.
            }
        }
        throw new LuaError("Cannot reply: channel index is missing");
    }

    private String messageDirectPeer(LuaTable message) {
        String chatKey = LuaValueMapper.tableString(message, "chat_key");
        if (chatKey != null && !chatKey.isBlank()) {
            return chatKey;
        }

        String owner = ownerNodeIdForMessages();
        String from = LuaValueMapper.tableString(message, "from");
        String to = LuaValueMapper.tableString(message, "to");
        if (from != null && !from.isBlank() && !from.equalsIgnoreCase(owner)) {
            return from;
        }
        if (to != null && !to.isBlank() && !to.equalsIgnoreCase(owner)) {
            return to;
        }
        throw new LuaError("Cannot reply: DM peer is missing");
    }

    private void requireChatTransport() {
        if (!context.hasChatTransport()) {
            throw new LuaError("No active chat connection");
        }
    }

    private record ChatScope(String chatType, String chatKey, int channelIndex) {}
}
