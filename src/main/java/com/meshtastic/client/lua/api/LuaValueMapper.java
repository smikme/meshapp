package com.meshtastic.client.lua.api;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.meshtastic.proto.ChannelProtos;

/**
 * Maps MeshApp internal models to Lua tables exposed by the sandbox API.
 * <p>
 * Used for messages, nodes, and channels available to scripts through
 * {@code on_message(msg)} and {@code mesh.chat.*} functions.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaValueMapper {

    private final DeviceState state;

    public LuaValueMapper(DeviceState state) {
        this.state = state;
    }

    /**
     * Converts a chat message to a Lua table.
     *
     * @param message MeshApp message
     * @param chatType chat type
     * @param chatKey chat key
     * @return Lua message table
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
        table.set("system", LuaValue.valueOf(message.isSystemMessage()));
        table.set("status", stringOrNil(message.getStatus() != null ? message.getStatus().name() : null));
        table.set("sender_name", stringOrNil(message.getSenderName()));
        table.set("rx_rssi", LuaValue.valueOf(message.getRxRssi()));
        table.set("rx_snr", LuaValue.valueOf(message.getRxSnr()));
        return table;
    }

    /**
     * Converts a node to a Lua table.
     *
     * @param node MeshApp node
     * @return Lua node table
     */
    public LuaTable nodeToTable(NodeData node) {
        LuaTable table = new LuaTable();
        table.set("node_num", uint32ToLuaValue(node.getNodeNum()));
        table.set("node_id", stringOrNil(node.getNodeId()));
        table.set("long_name", stringOrNil(node.getLongName()));
        table.set("short_name", stringOrNil(node.getShortName()));
        table.set("last_heard", LuaValue.valueOf(node.getLastHeard()));
        table.set("battery", LuaValue.valueOf(node.getBatteryLevel()));
        table.set("externally_powered", LuaValue.valueOf(node.isExternallyPowered()));
        table.set("voltage", LuaValue.valueOf(node.getVoltage()));
        table.set("snr", LuaValue.valueOf(node.getSnr()));
        table.set("latitude", LuaValue.valueOf(node.getLatitude()));
        table.set("longitude", LuaValue.valueOf(node.getLongitude()));
        table.set("altitude", LuaValue.valueOf(node.getAltitude()));
        table.set("hops_away", node.hasHopsAway() ? LuaValue.valueOf(node.getHopsAway()) : LuaValue.NIL);
        table.set("channel", LuaValue.valueOf(node.getChannel()));
        table.set("role", stringOrNil(node.getRole()));
        table.set("hw_model", stringOrNil(node.getHwModel()));
        table.set("public_key", stringOrNil(hex(node.getPublicKey())));
        table.set("uptime_seconds", LuaValue.valueOf((double) node.getUptimeSeconds()));
        table.set("channel_utilization", LuaValue.valueOf(node.getChannelUtilization()));
        table.set("air_util_tx", LuaValue.valueOf(node.getAirUtilTx()));
        table.set("temperature", LuaValue.valueOf(node.getTemperature()));
        table.set("relative_humidity", LuaValue.valueOf(node.getRelativeHumidity()));
        table.set("barometric_pressure", LuaValue.valueOf(node.getBarometricPressure()));
        table.set("unmessagable", LuaValue.valueOf(node.isUnmessagable()));
        table.set("licensed", node.getLicensed() != null ? LuaValue.valueOf(node.isLicensed()) : LuaValue.NIL);
        return table;
    }

    /**
     * Converts a channel to a Lua table.
     *
     * @param channel Meshtastic channel
     * @return Lua channel table
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
     * Safely reads an integer field from a Lua table.
     *
     * @param table Lua table
     * @param key field name
     * @param defaultValue fallback value
     * @return field value or fallback
     */
    public static int tableInt(LuaTable table, String key, int defaultValue) {
        LuaValue value = table.get(key);
        return value.isnil() ? defaultValue : value.checkint();
    }

    /**
     * Safely reads a uint32 field from a Lua table into a Java int while preserving bits.
     * <p>
     * Meshtastic stores {@code node_num} as unsigned 32-bit, while protobuf and
     * Java store it in a signed {@code int}. The Lua API presents those values as
     * positive numbers in the {@code 0..4294967295} range; on return to Java they
     * must be folded back into the same 32 bits.
     *
     * @param table Lua table
     * @param key field name
     * @param defaultValue fallback value
     * @return signed int containing the original uint32 bits
     */
    public static int tableUInt32(LuaTable table, String key, int defaultValue) {
        LuaValue value = table.get(key);
        if (value.isnil()) {
            return defaultValue;
        }
        if (value.isnumber()) {
            double number = value.checkdouble();
            if (number < 0) {
                number += 4_294_967_296.0;
            }
            return (int) ((long) number & 0xffff_ffffL);
        }
        return value.checkint();
    }

    /**
     * Safely reads a string field from a Lua table.
     *
     * @param table Lua table
     * @param key field name
     * @return string value, or {@code null}
     */
    public static String tableString(LuaTable table, String key) {
        LuaValue value = table.get(key);
        return value.isnil() ? null : value.checkjstring();
    }

    static LuaValue stringOrNil(String value) {
        return value == null ? LuaValue.NIL : LuaValue.valueOf(value);
    }

    public static LuaValue uint32ToLuaValue(int value) {
        return LuaValue.valueOf((double) Integer.toUnsignedLong(value));
    }

    private ChannelProtos.Channel findMessageChannel(int channelIndex) {
        return state != null ? state.getChannelStore().getChannelByIndex(channelIndex) : null;
    }

    private String channelName(ChannelProtos.Channel channel) {
        return channel != null && channel.hasSettings() ? channel.getSettings().getName() : null;
    }

    private static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            sb.append(String.format("%02x", value));
        }
        return sb.toString();
    }
}
