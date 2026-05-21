package com.meshtastic.client.lua.api;

import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
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

/**
 * Реализация {@code mesh.chat} для Lua-песочницы.
 * <p>
 * Предоставляет скриптам доступ только к разрешенным чат-функциям приложения:
 * отправке сообщений, ответам, чтению последних сообщений, списку нод и каналов.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaChatApi {

    private final LuaSandboxContext context;
    private final LuaValueMapper mapper;
    private final MessageDbService messageDbService = MessageDbService.getInstance();

    public LuaChatApi(LuaSandboxContext context, LuaValueMapper mapper) {
        this.context = context;
        this.mapper = mapper;
    }

    /**
     * Создает Lua-таблицу {@code mesh.chat}.
     *
     * @return таблица чат API
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
                if (context.state() == null) {
                    return table;
                }
                List<NodeData> nodes = new ArrayList<>(context.state().getNodeDb().values());
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
                if (context.state() == null) {
                    return table;
                }
                List<ChannelProtos.Channel> channels = context.state().getChannels();
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
        return context.meshCoreRuntime() != null
                ? context.meshCoreRuntime().sendChannelMessage(channel, text, replyId)
                : MessageService.sendChannelMessage(context.handler(), context.state(), channel, text, replyId);
    }

    private MeshMessage sendDirectMessage(String peerNodeId, String text, int replyId) {
        requireChatTransport();
        return context.meshCoreRuntime() != null
                ? context.meshCoreRuntime().sendDirectMessage(peerNodeId, text, replyId)
                : MessageService.sendDirectMessage(context.handler(), context.state(), peerNodeId, text, replyId);
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

        String owner = context.ownerNodeId();
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
}
