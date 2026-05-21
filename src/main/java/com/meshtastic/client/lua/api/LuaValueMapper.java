package com.meshtastic.client.lua.api;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.meshtastic.proto.ChannelProtos;

/**
 * Преобразует внутренние модели MeshApp в Lua-таблицы sandbox API.
 * <p>
 * Используется для объектов сообщений, нод и каналов, которые доступны скриптам
 * через {@code on_message(msg)} и функции {@code mesh.chat.*}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaValueMapper {

    private final DeviceState state;

    public LuaValueMapper(DeviceState state) {
        this.state = state;
    }

    /**
     * Преобразует сообщение чата в Lua-таблицу.
     *
     * @param message  сообщение MeshApp
     * @param chatType тип чата
     * @param chatKey  ключ чата
     * @return Lua-таблица сообщения
     */
    public LuaTable messageToTable(MeshMessage message, String chatType, String chatKey) {
        LuaTable table = new LuaTable();
        table.set("db_id", LuaValue.valueOf(message.getDbId()));
        table.set("packet_id", LuaValue.valueOf(message.getPacketId()));
        table.set("chat_type", stringOrNil(chatType));
        table.set("chat_key", stringOrNil(chatKey));
        table.set("from", stringOrNil(message.getFromNodeId()));
        table.set("to", stringOrNil(message.getToNodeId()));
        table.set("channel", LuaValue.valueOf(message.getChannelIndex()));
        ChannelProtos.Channel channel = findMessageChannel(message.getChannelIndex());
        table.set("channel_name", stringOrNil(channelName(channel)));
        table.set("channel_role", stringOrNil(channel != null ? channel.getRole().name() : null));
        table.set("text", stringOrNil(message.getText()));
        table.set("reply_id", LuaValue.valueOf(message.getReplyId()));
        table.set("reply_text", stringOrNil(message.getReplyText()));
        table.set("hop_start", LuaValue.valueOf(message.getHopStart()));
        table.set("hop_limit", LuaValue.valueOf(message.getHopLimit()));
        table.set("hops", message.hasValidHopData() ? LuaValue.valueOf(message.getHopsTraveled()) : LuaValue.NIL);
        table.set("timestamp", LuaValue.valueOf(message.getTimestamp()));
        table.set("outgoing", LuaValue.valueOf(message.isOutgoing()));
        table.set("status", stringOrNil(message.getStatus() != null ? message.getStatus().name() : null));
        table.set("sender_name", stringOrNil(message.getSenderName()));
        table.set("rx_rssi", LuaValue.valueOf(message.getRxRssi()));
        table.set("rx_snr", LuaValue.valueOf(message.getRxSnr()));
        return table;
    }

    /**
     * Преобразует ноду в Lua-таблицу.
     *
     * @param node нода MeshApp
     * @return Lua-таблица ноды
     */
    public LuaTable nodeToTable(NodeData node) {
        LuaTable table = new LuaTable();
        table.set("node_num", LuaValue.valueOf(node.getNodeNum()));
        table.set("node_id", stringOrNil(node.getNodeId()));
        table.set("long_name", stringOrNil(node.getLongName()));
        table.set("short_name", stringOrNil(node.getShortName()));
        table.set("last_heard", LuaValue.valueOf(node.getLastHeard()));
        table.set("battery", LuaValue.valueOf(node.getBatteryLevel()));
        table.set("hops_away", node.hasHopsAway() ? LuaValue.valueOf(node.getHopsAway()) : LuaValue.NIL);
        table.set("role", stringOrNil(node.getRole()));
        table.set("hw_model", stringOrNil(node.getHwModel()));
        table.set("unmessagable", LuaValue.valueOf(node.isUnmessagable()));
        return table;
    }

    /**
     * Преобразует канал в Lua-таблицу.
     *
     * @param channel канал Meshtastic
     * @return Lua-таблица канала
     */
    public LuaTable channelToTable(ChannelProtos.Channel channel) {
        LuaTable table = new LuaTable();
        table.set("index", LuaValue.valueOf(channel.getIndex()));
        table.set("role", stringOrNil(channel.getRole().name()));
        if (channel.hasSettings()) {
            table.set("name", stringOrNil(channel.getSettings().getName()));
        }
        return table;
    }

    /**
     * Безопасно читает целое поле из Lua-таблицы.
     *
     * @param table        Lua-таблица
     * @param key          имя поля
     * @param defaultValue значение по умолчанию
     * @return значение поля или default
     */
    public static int tableInt(LuaTable table, String key, int defaultValue) {
        LuaValue value = table.get(key);
        return value.isnil() ? defaultValue : value.checkint();
    }

    /**
     * Безопасно читает строковое поле из Lua-таблицы.
     *
     * @param table Lua-таблица
     * @param key   имя поля
     * @return строка или {@code null}
     */
    public static String tableString(LuaTable table, String key) {
        LuaValue value = table.get(key);
        return value.isnil() ? null : value.checkjstring();
    }

    static LuaValue stringOrNil(String value) {
        return value == null ? LuaValue.NIL : LuaValue.valueOf(value);
    }

    private ChannelProtos.Channel findMessageChannel(int channelIndex) {
        return state != null ? state.getChannelStore().getChannelByIndex(channelIndex) : null;
    }

    private String channelName(ChannelProtos.Channel channel) {
        return channel != null && channel.hasSettings() ? channel.getSettings().getName() : null;
    }
}
