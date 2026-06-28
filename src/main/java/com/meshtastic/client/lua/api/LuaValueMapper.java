package com.meshtastic.client.lua.api;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.meshtastic.proto.ChannelProtos;

import java.util.List;
import java.util.function.Supplier;

/**
 * Maps MeshApp internal models to Lua tables exposed by the sandbox API.
 * <p>
 * Used for messages, nodes, and channels available to scripts through
 * {@code on_message(msg)} and {@code mesh.chat.*} functions.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaValueMapper {

    private static final List<String> TELEMETRY_FIELD_NAMES = List.of(
            "timestamp",
            "node_id",
            "variant",
            "battery_level",
            "externally_powered",
            "voltage",
            "channel_utilization",
            "air_util_tx",
            "device_uptime_seconds",
            "temperature",
            "relative_humidity",
            "barometric_pressure",
            "gas_resistance",
            "environment_voltage",
            "environment_current",
            "iaq",
            "distance",
            "lux",
            "white_lux",
            "ir_lux",
            "uv_lux",
            "wind_direction",
            "wind_speed",
            "weight",
            "wind_gust",
            "wind_lull",
            "radiation",
            "rainfall_1h",
            "rainfall_24h",
            "soil_moisture",
            "soil_temperature",
            "one_wire_temperature",
            "pm10_standard",
            "pm25_standard",
            "pm100_standard",
            "pm10_environmental",
            "pm25_environmental",
            "pm100_environmental",
            "particles_03um",
            "particles_05um",
            "particles_10um",
            "particles_25um",
            "particles_50um",
            "particles_100um",
            "co2",
            "co2_temperature",
            "co2_humidity",
            "form_formaldehyde",
            "form_humidity",
            "form_temperature",
            "pm40_standard",
            "particles_40um",
            "pm_temperature",
            "pm_humidity",
            "pm_voc_idx",
            "pm_nox_idx",
            "particles_tps",
            "ch1_voltage",
            "ch1_current",
            "ch2_voltage",
            "ch2_current",
            "ch3_voltage",
            "ch3_current",
            "ch4_voltage",
            "ch4_current",
            "ch5_voltage",
            "ch5_current",
            "ch6_voltage",
            "ch6_current",
            "ch7_voltage",
            "ch7_current",
            "ch8_voltage",
            "ch8_current",
            "num_packets_rx",
            "num_packets_rx_bad",
            "num_rx_dupe",
            "num_packets_tx",
            "num_tx_dropped",
            "num_tx_relay",
            "num_tx_relay_canceled",
            "local_uptime_seconds",
            "num_online_nodes",
            "num_total_nodes",
            "heap_total_bytes",
            "heap_free_bytes",
            "noise_floor",
            "health_heart_bpm",
            "health_spo2",
            "health_temperature",
            "host_uptime_seconds",
            "host_freemem_bytes",
            "host_diskfree1_bytes",
            "host_diskfree2_bytes",
            "host_diskfree3_bytes",
            "host_load1",
            "host_load5",
            "host_load15",
            "host_user_string",
            "traffic_packets_inspected",
            "traffic_position_dedup_drops",
            "traffic_nodeinfo_cache_hits",
            "traffic_rate_limit_drops",
            "traffic_unknown_packet_drops",
            "traffic_hop_exhausted_packets",
            "traffic_router_hops_preserved",
            "rx_snr",
            "rx_rssi",
            "hop_start",
            "hop_limit",
            "hops"
    );

    private final Supplier<DeviceState> stateSupplier;

    public LuaValueMapper(DeviceState state) {
        this(() -> state);
    }

    public LuaValueMapper(Supplier<DeviceState> stateSupplier) {
        this.stateSupplier = stateSupplier != null ? stateSupplier : () -> null;
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
     * Converts a chat reaction to a Lua table.
     *
     * @param reaction MeshApp reaction
     * @param chatType chat type
     * @param chatKey chat key
     * @return Lua reaction table
     */
    public LuaTable reactionToTable(MessageReaction reaction, String chatType, String chatKey) {
        LuaTable table = new LuaTable();
        table.set("db_id", LuaValue.valueOf(reaction.getDbId()));
        table.set("packet_id", LuaValue.valueOf(reaction.getPacketId()));
        table.set("target_packet_id", LuaValue.valueOf(reaction.getTargetPacketId()));
        table.set("chat_type", stringOrNil(chatType));
        table.set("chat_key", stringOrNil(chatKey));
        table.set("from", stringOrNil(reaction.getFromNodeId()));
        table.set("emoji", stringOrNil(reaction.getEmoji()));
        table.set("timestamp", LuaValue.valueOf(reaction.getTimestamp()));
        table.set("outgoing", LuaValue.valueOf(reaction.isOutgoing()));
        table.set("status", stringOrNil(reaction.getStatus() != null ? reaction.getStatus().name() : null));
        table.set("error_reason", stringOrNil(reaction.getErrorReason()));
        table.set("sender_name", stringOrNil(reaction.getSenderName()));
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
     * Converts one telemetry history row to a Lua table.
     *
     * @param entry telemetry entry
     * @return Lua telemetry table
     */
    public LuaTable telemetryToTable(TelemetryEntry entry) {
        LuaTable table = new LuaTable();
        table.set("timestamp", LuaValue.valueOf(entry.getTimestamp()));
        table.set("node_id", stringOrNil(entry.getNodeId()));
        table.set("variant", stringOrNil(entry.getTelemetryVariant()));
        table.set("battery_level", LuaValue.valueOf(entry.getBatteryLevel()));
        table.set("externally_powered", LuaValue.valueOf(entry.isExternallyPowered()));
        table.set("voltage", LuaValue.valueOf(entry.getVoltage()));
        table.set("channel_utilization", LuaValue.valueOf(entry.getChannelUtilization()));
        table.set("air_util_tx", LuaValue.valueOf(entry.getAirUtilTx()));
        table.set("device_uptime_seconds", numberOrNil(entry.getDeviceUptimeSeconds()));
        table.set("temperature", LuaValue.valueOf(entry.getTemperature()));
        table.set("relative_humidity", LuaValue.valueOf(entry.getRelativeHumidity()));
        table.set("barometric_pressure", LuaValue.valueOf(entry.getBarometricPressure()));
        table.set("gas_resistance", numberOrNil(entry.getGasResistance()));
        table.set("environment_voltage", numberOrNil(entry.getEnvironmentVoltage()));
        table.set("environment_current", numberOrNil(entry.getEnvironmentCurrent()));
        table.set("iaq", numberOrNil(entry.getIaq()));
        table.set("distance", numberOrNil(entry.getDistance()));
        table.set("lux", numberOrNil(entry.getLux()));
        table.set("white_lux", numberOrNil(entry.getWhiteLux()));
        table.set("ir_lux", numberOrNil(entry.getIrLux()));
        table.set("uv_lux", numberOrNil(entry.getUvLux()));
        table.set("wind_direction", numberOrNil(entry.getWindDirection()));
        table.set("wind_speed", numberOrNil(entry.getWindSpeed()));
        table.set("weight", numberOrNil(entry.getWeight()));
        table.set("wind_gust", numberOrNil(entry.getWindGust()));
        table.set("wind_lull", numberOrNil(entry.getWindLull()));
        table.set("radiation", numberOrNil(entry.getRadiation()));
        table.set("rainfall_1h", numberOrNil(entry.getRainfall1h()));
        table.set("rainfall_24h", numberOrNil(entry.getRainfall24h()));
        table.set("soil_moisture", numberOrNil(entry.getSoilMoisture()));
        table.set("soil_temperature", numberOrNil(entry.getSoilTemperature()));
        table.set("one_wire_temperature", oneWireTemperatureTable(entry));
        table.set("pm10_standard", numberOrNil(entry.getPm10Standard()));
        table.set("pm25_standard", numberOrNil(entry.getPm25Standard()));
        table.set("pm100_standard", numberOrNil(entry.getPm100Standard()));
        table.set("pm10_environmental", numberOrNil(entry.getPm10Environmental()));
        table.set("pm25_environmental", numberOrNil(entry.getPm25Environmental()));
        table.set("pm100_environmental", numberOrNil(entry.getPm100Environmental()));
        table.set("particles_03um", numberOrNil(entry.getParticles03um()));
        table.set("particles_05um", numberOrNil(entry.getParticles05um()));
        table.set("particles_10um", numberOrNil(entry.getParticles10um()));
        table.set("particles_25um", numberOrNil(entry.getParticles25um()));
        table.set("particles_50um", numberOrNil(entry.getParticles50um()));
        table.set("particles_100um", numberOrNil(entry.getParticles100um()));
        table.set("co2", numberOrNil(entry.getCo2()));
        table.set("co2_temperature", numberOrNil(entry.getCo2Temperature()));
        table.set("co2_humidity", numberOrNil(entry.getCo2Humidity()));
        table.set("form_formaldehyde", numberOrNil(entry.getFormFormaldehyde()));
        table.set("form_humidity", numberOrNil(entry.getFormHumidity()));
        table.set("form_temperature", numberOrNil(entry.getFormTemperature()));
        table.set("pm40_standard", numberOrNil(entry.getPm40Standard()));
        table.set("particles_40um", numberOrNil(entry.getParticles40um()));
        table.set("pm_temperature", numberOrNil(entry.getPmTemperature()));
        table.set("pm_humidity", numberOrNil(entry.getPmHumidity()));
        table.set("pm_voc_idx", numberOrNil(entry.getPmVocIdx()));
        table.set("pm_nox_idx", numberOrNil(entry.getPmNoxIdx()));
        table.set("particles_tps", numberOrNil(entry.getParticlesTps()));
        table.set("ch1_voltage", numberOrNil(entry.getCh1Voltage()));
        table.set("ch1_current", numberOrNil(entry.getCh1Current()));
        table.set("ch2_voltage", numberOrNil(entry.getCh2Voltage()));
        table.set("ch2_current", numberOrNil(entry.getCh2Current()));
        table.set("ch3_voltage", numberOrNil(entry.getCh3Voltage()));
        table.set("ch3_current", numberOrNil(entry.getCh3Current()));
        table.set("ch4_voltage", numberOrNil(entry.getCh4Voltage()));
        table.set("ch4_current", numberOrNil(entry.getCh4Current()));
        table.set("ch5_voltage", numberOrNil(entry.getCh5Voltage()));
        table.set("ch5_current", numberOrNil(entry.getCh5Current()));
        table.set("ch6_voltage", numberOrNil(entry.getCh6Voltage()));
        table.set("ch6_current", numberOrNil(entry.getCh6Current()));
        table.set("ch7_voltage", numberOrNil(entry.getCh7Voltage()));
        table.set("ch7_current", numberOrNil(entry.getCh7Current()));
        table.set("ch8_voltage", numberOrNil(entry.getCh8Voltage()));
        table.set("ch8_current", numberOrNil(entry.getCh8Current()));
        table.set("num_packets_rx", LuaValue.valueOf(entry.getNumPacketsRx()));
        table.set("num_packets_rx_bad", LuaValue.valueOf(entry.getNumPacketsRxBad()));
        table.set("num_rx_dupe", LuaValue.valueOf(entry.getNumRxDupe()));
        table.set("num_packets_tx", LuaValue.valueOf(entry.getNumPacketsTx()));
        table.set("num_tx_dropped", LuaValue.valueOf(entry.getNumTxDropped()));
        table.set("num_tx_relay", LuaValue.valueOf(entry.getNumTxRelay()));
        table.set("num_tx_relay_canceled", LuaValue.valueOf(entry.getNumTxRelayCanceled()));
        table.set("local_uptime_seconds", numberOrNil(entry.getLocalUptimeSeconds()));
        table.set("num_online_nodes", numberOrNil(entry.getNumOnlineNodes()));
        table.set("num_total_nodes", numberOrNil(entry.getNumTotalNodes()));
        table.set("heap_total_bytes", numberOrNil(entry.getHeapTotalBytes()));
        table.set("heap_free_bytes", numberOrNil(entry.getHeapFreeBytes()));
        table.set("noise_floor", numberOrNil(entry.getNoiseFloor()));
        table.set("health_heart_bpm", numberOrNil(entry.getHealthHeartBpm()));
        table.set("health_spo2", numberOrNil(entry.getHealthSpO2()));
        table.set("health_temperature", numberOrNil(entry.getHealthTemperature()));
        table.set("host_uptime_seconds", numberOrNil(entry.getHostUptimeSeconds()));
        table.set("host_freemem_bytes", numberOrNil(entry.getHostFreememBytes()));
        table.set("host_diskfree1_bytes", numberOrNil(entry.getHostDiskfree1Bytes()));
        table.set("host_diskfree2_bytes", numberOrNil(entry.getHostDiskfree2Bytes()));
        table.set("host_diskfree3_bytes", numberOrNil(entry.getHostDiskfree3Bytes()));
        table.set("host_load1", numberOrNil(entry.getHostLoad1()));
        table.set("host_load5", numberOrNil(entry.getHostLoad5()));
        table.set("host_load15", numberOrNil(entry.getHostLoad15()));
        table.set("host_user_string", stringOrNil(entry.getHostUserString()));
        table.set("traffic_packets_inspected", numberOrNil(entry.getTrafficPacketsInspected()));
        table.set("traffic_position_dedup_drops", numberOrNil(entry.getTrafficPositionDedupDrops()));
        table.set("traffic_nodeinfo_cache_hits", numberOrNil(entry.getTrafficNodeinfoCacheHits()));
        table.set("traffic_rate_limit_drops", numberOrNil(entry.getTrafficRateLimitDrops()));
        table.set("traffic_unknown_packet_drops", numberOrNil(entry.getTrafficUnknownPacketDrops()));
        table.set("traffic_hop_exhausted_packets", numberOrNil(entry.getTrafficHopExhaustedPackets()));
        table.set("traffic_router_hops_preserved", numberOrNil(entry.getTrafficRouterHopsPreserved()));
        table.set("rx_snr", LuaValue.valueOf(entry.getRxSnr()));
        table.set("rx_rssi", LuaValue.valueOf(entry.getRxRssi()));
        table.set("hop_start", LuaValue.valueOf(entry.getHopStart()));
        table.set("hop_limit", LuaValue.valueOf(entry.getHopLimit()));
        table.set("hops", entry.hasValidHopData() ? LuaValue.valueOf(entry.getHopsTraveled()) : LuaValue.NIL);
        return table;
    }

    public static List<String> telemetryFieldNames() {
        return TELEMETRY_FIELD_NAMES;
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
     * Safely reads a long field from a Lua table.
     *
     * @param table Lua table
     * @param key field name
     * @param defaultValue fallback value
     * @return field value or fallback
     */
    public static long tableLong(LuaTable table, String key, long defaultValue) {
        LuaValue value = table.get(key);
        return value.isnil() ? defaultValue : value.checklong();
    }

    /**
     * Safely reads a boolean field from a Lua table.
     *
     * @param table Lua table
     * @param key field name
     * @param defaultValue fallback value
     * @return field value or fallback
     */
    public static boolean tableBoolean(LuaTable table, String key, boolean defaultValue) {
        LuaValue value = table.get(key);
        return value.isnil() ? defaultValue : value.checkboolean();
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

    private static LuaValue numberOrNil(Number value) {
        return value == null ? LuaValue.NIL : LuaValue.valueOf(value.doubleValue());
    }

    private static LuaTable oneWireTemperatureTable(TelemetryEntry entry) {
        LuaTable table = new LuaTable();
        List<Float> values = entry.getOneWireTemperatures();
        for (int i = 0; i < values.size(); i++) {
            table.set(i + 1, LuaValue.valueOf(values.get(i)));
        }
        return table;
    }

    private ChannelProtos.Channel findMessageChannel(int channelIndex) {
        DeviceState state = stateSupplier.get();
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
