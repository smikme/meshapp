package com.meshtastic.client.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.WireFormat;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.model.PacketTreeNode;
import com.meshtastic.client.model.PacketLogEntry.Direction;
import javafx.scene.control.TreeItem;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;

import java.time.Instant;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Base64;
import java.util.function.Function;

/**
 * Formatting and tree-building helpers for LoRa packet debugging.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class PacketDebugFormatter {

    private static final long BROADCAST_NODE_NUM = 0xFFFF_FFFFL;
    private static final int HEX_PREVIEW_BYTES = 16;
    private static final int TEXT_PREVIEW_LIMIT = 140;
    private static final Gson EXPORT_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private PacketDebugFormatter() {}

    /**
     * Human-readable mesh-packet description for the monitor table.
 *
     * @param packetType       packet type or portnum
     * @param fromNode         sender in UI display form
     * @param toNode           recipient in UI display form
     * @param payloadText      payload represented as text
     * @param capturedAtMillis capture time in Unix epoch milliseconds
     */
    public record PacketDetails(String packetType,
                                String fromNode,
                                String toNode,
                                String payloadText,
                                long capturedAtMillis) {}

    /**
     * Prepared top-level MeshPacket endpoint labels for the UI.
     * When a node name is known, it is placed before the standard {@code nodeId};
     * otherwise only the standard {@code nodeId} is returned. Broadcast packets
     * use a localized label with {@code !ffffffff}.
     */
    public record PacketEndpoints(String fromNode, String toNode) {}

    /**
     * Metadata for a bulk JSON export using the monitor window's current filters.
 *
     * @param exportedAtMillis export creation time
     * @param routeFilter      human-readable route filter
     * @param packetTypeFilter human-readable packet-type filter
     * @param searchText       search text, or {@code null}
     * @param capturedAtFrom   lower time bound in UI form, or {@code null}
     * @param capturedAtTo     upper time bound in UI form, or {@code null}
     */
    public record PacketCollectionExportMetadata(long exportedAtMillis,
                                                 String routeFilter,
                                                 String packetTypeFilter,
                                                 String searchText,
                                                 String capturedAtFrom,
                                                 String capturedAtTo) {}

    public record PacketCollectionJsonExportState(JsonWriter writer, long packetCount) {}

    public record PacketCollectionCsvExportState(Writer writer, long packetCount) {}

    private static final String[] PACKET_COLLECTION_CSV_COLUMNS = {
            "id",
            "owner_node_id",
            "captured_at",
            "captured_at_millis",
            "captured_at_text",
            "direction",
            "direction_text",
            "packet_type",
            "transport_mechanism",
            "transport_text",
            "route_text",
            "from_node",
            "to_node",
            "payload_text",
            "packet_size_bytes",
            "mesh_packet_id",
            "mesh_packet_from_num",
            "mesh_packet_to_num",
            "mesh_packet_channel",
            "mesh_packet_rx_time",
            "mesh_packet_hop_start",
            "mesh_packet_hop_limit",
            "mesh_packet_rx_rssi",
            "mesh_packet_rx_snr",
            "decoded_portnum",
            "decoded_request_id",
            "decoded_reply_id",
            "decoded_emoji",
            "decoded_payload_text",
            "decoded_payload_base64",
            "decoded_payload_size_bytes",
            "decoded_payload_parse_error",
            "decoded_routing_error",
            "decoded_user_id",
            "decoded_user_long_name",
            "decoded_user_short_name",
            "decoded_user_role",
            "decoded_user_hw_model",
            "decoded_position_latitude",
            "decoded_position_longitude",
            "decoded_position_altitude",
            "decoded_telemetry_battery_level",
            "decoded_telemetry_voltage",
            "decoded_telemetry_temperature",
            "decoded_telemetry_humidity",
            "decoded_telemetry_pressure",
            "decoded_telemetry_packets_rx",
            "decoded_telemetry_packets_tx",
            "decoded_telemetry_channel_utilization",
            "decoded_telemetry_air_util_tx",
            "decoded_traceroute_route",
            "decoded_traceroute_route_back",
            "decoded_admin_variant",
            "decoded_admin_owner_id",
            "decoded_admin_owner_long_name",
            "decoded_admin_owner_short_name",
            "decoded_admin_firmware_version",
            "decoded_admin_role",
            "parse_error"
    };

    /**
     * Selection range in a textual preview.
 *
     * @param startChar inclusive first character index
     * @param endChar   exclusive last character index
     */
    public record TextSelectionRange(int startChar, int endChar) {}

    /**
     * Prepared HEX/ASCII packet preview with byte-to-character mappings.
     * Used to highlight HEX and ASCII columns independently when tree nodes are selected.
 *
     * @param addressText         address column
     * @param hexText             HEX column
     * @param asciiText           ASCII column
     * @param hexByteStartChars   start character index for each byte in the HEX view
     * @param hexByteEndChars     end character index for each byte in the HEX view
     * @param asciiByteStartChars start character index for each byte in the ASCII view
     * @param asciiByteEndChars   end character index for each byte in the ASCII view
     */
    public record HexPreview(String addressText,
                             String hexText,
                             String asciiText,
                             int[] hexByteStartChars,
                             int[] hexByteEndChars,
                             int[] asciiByteStartChars,
                             int[] asciiByteEndChars) {
        /**
         * @return {@code true} when the preview contains at least one packet byte
         */
        public boolean hasBytes() {
            return hexByteStartChars != null && hexByteStartChars.length > 0;
        }

        /**
         * Returns the character range to highlight in the HEX column.
 *
         * @param startByte inclusive first byte
         * @param endByte   exclusive last byte
         * @return character range, or {@code null} when the range is invalid
         */
        public TextSelectionRange selectionForHexBytes(int startByte, int endByte) {
            if (!hasBytes() || startByte < 0 || endByte <= startByte || endByte > hexByteStartChars.length) {
                return null;
            }
            return new TextSelectionRange(hexByteStartChars[startByte], hexByteEndChars[endByte - 1]);
        }

        /**
         * Returns the character range to highlight in the ASCII column.
 *
         * @param startByte inclusive first byte
         * @param endByte   exclusive last byte
         * @return character range, or {@code null} when the range is invalid
         */
        public TextSelectionRange selectionForAsciiBytes(int startByte, int endByte) {
            if (!hasBytes() || startByte < 0 || endByte <= startByte || endByte > asciiByteStartChars.length) {
                return null;
            }
            return new TextSelectionRange(asciiByteStartChars[startByte], asciiByteEndChars[endByte - 1]);
        }
    }

    private record VarintResult(int value, int nextOffset) {}

    private record FieldEnvelope(int fieldStart, int fieldEnd, int valueStart, int valueEnd) {}

    @FunctionalInterface
    private interface PayloadParser {
        Message parse() throws InvalidProtocolBufferException;
    }

    public static PacketDetails describeMeshPacket(MeshProtos.MeshPacket packet,
                                                   Direction direction,
                                                   com.meshtastic.client.model.DeviceState deviceState) {
        if (packet == null) {
            return new PacketDetails("UNKNOWN", "-", "-", "", System.currentTimeMillis());
        }

        String packetType = packet.hasDecoded()
                ? packet.getDecoded().getPortnum().name()
                : "ENCRYPTED";
        String fromNode = formatNode(packet.getFrom(), deviceState);
        String toNode = formatNode(packet.getTo(), deviceState);
        String payloadText = packet.hasDecoded()
                ? describeDecodedPayload(packet, packet.getDecoded())
                : describeEncryptedPacket(packet);
        long capturedAt = direction == Direction.INCOMING && packet.getRxTime() > 0
                ? packet.getRxTime() * 1000L
                : System.currentTimeMillis();

        return new PacketDetails(packetType, fromNode, toNode, payloadText, capturedAt);
    }

    /**
     * Builds a combined textual HEX packet view in the form
     * {@code address  hex  |ascii|}.
 *
     * @param bytes serialized packet
     * @return HEX preview text, or a no-data message
     */
    public static String formatHex(byte[] bytes) {
        HexPreview preview = formatHexPreview(bytes);
        if (!preview.hasBytes()) {
            return preview.hexText();
        }
        String[] addressLines = preview.addressText().split("\n", -1);
        String[] hexLines = preview.hexText().split("\n", -1);
        String[] asciiLines = preview.asciiText().split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hexLines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(addressLines[i]).append("  ").append(hexLines[i]).append("  |").append(asciiLines[i]).append('|');
        }
        return sb.toString();
    }

    /**
     * Builds separate address, HEX, and ASCII previews for a packet.
 *
     * @param bytes serialized packet
     * @return preview object with byte-to-character range mappings
     */
    public static HexPreview formatHexPreview(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new HexPreview("", I18n.t("packetMonitor.preview.noData"), "",
                    new int[0], new int[0], new int[0], new int[0]);
        }

        int[] hexByteStartChars = new int[bytes.length];
        int[] hexByteEndChars = new int[bytes.length];
        int[] asciiByteStartChars = new int[bytes.length];
        int[] asciiByteEndChars = new int[bytes.length];
        StringBuilder addressSb = new StringBuilder(bytes.length);
        StringBuilder hexSb = new StringBuilder(bytes.length * 4);
        StringBuilder asciiSb = new StringBuilder(bytes.length);
        for (int i = 0; i < bytes.length; i += HEX_PREVIEW_BYTES) {
            if (i > 0) {
                addressSb.append('\n');
                hexSb.append('\n');
                asciiSb.append('\n');
            }

            addressSb.append(String.format("%04X", i));
            for (int j = 0; j < HEX_PREVIEW_BYTES; j++) {
                int index = i + j;
                if (j > 0) {
                    hexSb.append(' ');
                }
                if (j == 8) {
                    hexSb.append(' ');
                }
                if (index < bytes.length) {
                    hexByteStartChars[index] = hexSb.length();
                    hexSb.append(String.format("%02X", bytes[index] & 0xFF));
                    hexByteEndChars[index] = hexSb.length();

                    asciiByteStartChars[index] = asciiSb.length();
                    int value = bytes[index] & 0xFF;
                    asciiSb.append(value >= 32 && value <= 126 ? (char) value : '.');
                    asciiByteEndChars[index] = asciiSb.length();
                } else {
                    hexSb.append("  ");
                    asciiSb.append(' ');
                }
            }
        }
        return new HexPreview(addressSb.toString(), hexSb.toString(), asciiSb.toString(),
                hexByteStartChars, hexByteEndChars, asciiByteStartChars, asciiByteEndChars);
    }

    /**
     * Exports the selected packet as human-readable text for analysis and sharing.
     * Includes packet metadata, a HEX view, and the current parse tree.
 *
     * @param entry selected journal entry
     * @return export text, or an empty string when no entry is supplied
     */
    public static String exportPacketAsText(PacketLogEntry entry) {
        return exportPacketAsText(entry, null);
    }

    /**
     * Exports the selected packet as human-readable text for analysis and sharing.
     * The From and To fields are recalculated from {@link MeshProtos.MeshPacket}
     * so the UI and export use the same {@code Name (!nodeId)} form regardless
     * of how addresses were originally stored in the database.
 *
     * @param entry       selected journal entry
     * @param deviceState device state used to resolve node names; may be {@code null}
     * @return export text, or an empty string when no entry is supplied
     */
    public static String exportPacketAsText(PacketLogEntry entry,
                                            com.meshtastic.client.model.DeviceState deviceState) {
        if (entry == null) {
            return "";
        }

        PacketEndpoints endpoints = resolvePacketEndpoints(entry, deviceState);
        StringBuilder sb = new StringBuilder(1024);
        appendExportLine(sb, I18n.t("packetMonitor.export.label.id"), Long.toString(entry.getId()));
        appendExportLine(sb, I18n.t("packetMonitor.export.label.time"), entry.getCapturedAtText());
        appendExportLine(sb, I18n.t("packetMonitor.export.label.direction"), entry.getDirectionText());
        appendExportLine(sb, I18n.t("packetMonitor.export.label.type"), entry.getPacketType());
        appendExportLine(sb, I18n.t("packetMonitor.export.label.from"), endpoints.fromNode());
        appendExportLine(sb, I18n.t("packetMonitor.export.label.to"), endpoints.toNode());
        appendExportLine(sb, I18n.t("packetMonitor.export.label.payload"), entry.getPayloadText());
        appendExportLine(sb, I18n.t("packetMonitor.export.label.size"),
                I18n.t("packetMonitor.export.sizeBytes", Integer.toString(entry.getPacketBytes().length)));
        sb.append('\n').append(I18n.t("packetMonitor.export.label.hex")).append('\n');
        sb.append(formatHex(entry.getPacketBytes()));
        sb.append('\n').append('\n').append(I18n.t("packetMonitor.export.label.hierarchy")).append('\n');
        appendTreeText(sb, buildPacketTree(entry.getPacketBytes()), 0);
        return sb.toString();
    }

    /**
     * Returns {@code from}/{@code to} labels for an already stored packet entry.
     * The method does not trust database strings first: parsing
     * {@link MeshProtos.MeshPacket} from {@code packet_bytes} has priority, and
     * stored entry values are used only as fallbacks.
 *
     * @param entry       journal entry
     * @param deviceState device state used to resolve node names; may be {@code null}
     * @return sender and recipient labels suitable for the table and text export
     */
    public static PacketEndpoints resolvePacketEndpoints(PacketLogEntry entry,
                                                         com.meshtastic.client.model.DeviceState deviceState) {
        if (entry == null) {
            return new PacketEndpoints("", "");
        }

        PacketEndpoints resolved = resolvePacketEndpoints(entry.getPacketBytes(), deviceState);
        return new PacketEndpoints(
                firstNonBlank(resolved.fromNode(), entry.getFromNode(), "-"),
                firstNonBlank(resolved.toNode(), entry.getToNode(), "-")
        );
    }

    /**
     * Parses top-level {@code from}/{@code to} fields directly from packet bytes.
 *
     * @param packetBytes serialized {@link MeshProtos.MeshPacket}
     * @param deviceState device state used to resolve node names; may be {@code null}
     * @return sender and recipient labels; both values are {@code null} if parsing fails
     */
    public static PacketEndpoints resolvePacketEndpoints(byte[] packetBytes,
                                                         com.meshtastic.client.model.DeviceState deviceState) {
        if (packetBytes == null || packetBytes.length == 0) {
            return new PacketEndpoints(null, null);
        }
        try {
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.parseFrom(packetBytes);
            return new PacketEndpoints(
                    formatNodeDisplay(packet.getFrom(), deviceState),
                    formatNodeDisplay(packet.getTo(), deviceState)
            );
        } catch (InvalidProtocolBufferException e) {
            return new PacketEndpoints(null, null);
        }
    }

    /**
     * Exports only the {@link MeshProtos.MeshPacket} content as protobuf-style JSON.
     * The format deliberately omits desktop-client fields so the result can be
     * pasted into the original Meshtastic web application.
 *
     * @param entry selected journal entry
     * @return packet JSON, or an empty string if the packet is missing or cannot be parsed
     */
    public static String exportPacketAsJson(PacketLogEntry entry) {
        if (entry == null) {
            return "";
        }
        try {
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.parseFrom(entry.getPacketBytes());
            return EXPORT_GSON.toJson(toProtoJsonObject(packet));
        } catch (InvalidProtocolBufferException e) {
            return "";
        }
    }

    /**
     * Exports packets to a hierarchical JSON file using the current UI filters.
     * Unlike {@link #exportPacketAsJson(PacketLogEntry)}, this format includes
     * export/filter metadata, desktop metadata for each journal entry, the
     * protobuf packet structure, decoded payloads when they can be parsed, and
     * the packet hierarchy tree with byte ranges.
 *
     * @param entries             entries to include in the file
     * @param metadata            export metadata; may be {@code null}
     * @param deviceStateResolver device-state resolver used to normalize node names; may be {@code null}
     * @return pretty-printed JSON
     */
    public static String exportPacketsAsJson(List<PacketLogEntry> entries,
                                             PacketCollectionExportMetadata metadata,
                                             Function<PacketLogEntry, DeviceState> deviceStateResolver) {
        try {
            StringWriter buffer = new StringWriter(4096);
            PacketCollectionJsonExportState state = beginPacketCollectionJsonExport(buffer, metadata);
            long exportedCount = 0;
            if (entries != null) {
                for (PacketLogEntry entry : entries) {
                    DeviceState deviceState = deviceStateResolver != null ? deviceStateResolver.apply(entry) : null;
                    state = writePacketCollectionJsonEntry(state, entry, deviceState);
                    exportedCount++;
                }
            }
            finishPacketCollectionJsonExport(state, exportedCount);
            return buffer.toString();
        } catch (IOException e) {
            return "";
        }
    }

    public static String exportPacketsAsCsv(List<PacketLogEntry> entries,
                                            Function<PacketLogEntry, DeviceState> deviceStateResolver) {
        try {
            StringWriter buffer = new StringWriter(4096);
            PacketCollectionCsvExportState state = beginPacketCollectionCsvExport(buffer);
            if (entries != null) {
                for (PacketLogEntry entry : entries) {
                    DeviceState deviceState = deviceStateResolver != null ? deviceStateResolver.apply(entry) : null;
                    state = writePacketCollectionCsvEntry(state, entry, deviceState);
                }
            }
            finishPacketCollectionCsvExport(state);
            return buffer.toString();
        } catch (IOException e) {
            return "";
        }
    }

    public static PacketCollectionJsonExportState beginPacketCollectionJsonExport(
            Writer writer,
            PacketCollectionExportMetadata metadata) throws IOException {
        JsonWriter jsonWriter = new JsonWriter(writer);
        jsonWriter.setIndent("  ");
        jsonWriter.beginObject();

        long exportedAtMillis = metadata != null ? metadata.exportedAtMillis() : System.currentTimeMillis();
        jsonWriter.name("exportedAt").value(Instant.ofEpochMilli(exportedAtMillis).toString());
        jsonWriter.name("exportedAtMillis").value(exportedAtMillis);
        if (metadata != null) {
            jsonWriter.name("filters");
            EXPORT_GSON.toJson(toExportFiltersJson(metadata), jsonWriter);
        }
        jsonWriter.name("packets");
        jsonWriter.beginArray();
        return new PacketCollectionJsonExportState(jsonWriter, 0);
    }

    public static PacketCollectionJsonExportState writePacketCollectionJsonEntry(
            PacketCollectionJsonExportState state,
            PacketLogEntry entry,
            DeviceState deviceState) throws IOException {
        if (state == null || state.writer() == null || entry == null) {
            return state;
        }
        EXPORT_GSON.toJson(buildHierarchicalPacketJson(entry, deviceState), state.writer());
        return new PacketCollectionJsonExportState(state.writer(), state.packetCount() + 1);
    }

    public static void finishPacketCollectionJsonExport(
            PacketCollectionJsonExportState state,
            long packetCount) throws IOException {
        if (state == null || state.writer() == null) {
            return;
        }
        JsonWriter jsonWriter = state.writer();
        jsonWriter.endArray();
        jsonWriter.name("packetCount").value(packetCount);
        jsonWriter.endObject();
        jsonWriter.flush();
    }

    public static PacketCollectionCsvExportState beginPacketCollectionCsvExport(Writer writer) throws IOException {
        if (writer == null) {
            return new PacketCollectionCsvExportState(new StringWriter(), 0);
        }
        writer.write(String.join(",", PACKET_COLLECTION_CSV_COLUMNS));
        writer.write('\n');
        return new PacketCollectionCsvExportState(writer, 0);
    }

    public static PacketCollectionCsvExportState writePacketCollectionCsvEntry(
            PacketCollectionCsvExportState state,
            PacketLogEntry entry,
            DeviceState deviceState) throws IOException {
        if (state == null || state.writer() == null || entry == null) {
            return state;
        }

        PacketEndpoints endpoints = resolvePacketEndpoints(entry, deviceState);

        String meshPacketId = "";
        String meshPacketFromNum = "";
        String meshPacketToNum = "";
        String meshPacketChannel = "";
        String meshPacketRxTime = "";
        String meshPacketHopStart = "";
        String meshPacketHopLimit = "";
        String meshPacketRxRssi = "";
        String meshPacketRxSnr = "";
        String decodedPortnum = "";
        String decodedRequestId = "";
        String decodedReplyId = "";
        String decodedEmoji = "";
        String decodedPayloadText = "";
        String decodedPayloadBase64 = "";
        String decodedPayloadSizeBytes = "";
        String decodedPayloadParseError = "";
        String decodedRoutingError = "";
        String decodedUserId = "";
        String decodedUserLongName = "";
        String decodedUserShortName = "";
        String decodedUserRole = "";
        String decodedUserHwModel = "";
        String decodedPositionLatitude = "";
        String decodedPositionLongitude = "";
        String decodedPositionAltitude = "";
        String decodedTelemetryBatteryLevel = "";
        String decodedTelemetryVoltage = "";
        String decodedTelemetryTemperature = "";
        String decodedTelemetryHumidity = "";
        String decodedTelemetryPressure = "";
        String decodedTelemetryPacketsRx = "";
        String decodedTelemetryPacketsTx = "";
        String decodedTelemetryChannelUtilization = "";
        String decodedTelemetryAirUtilTx = "";
        String decodedTracerouteRoute = "";
        String decodedTracerouteRouteBack = "";
        String decodedAdminVariant = "";
        String decodedAdminOwnerId = "";
        String decodedAdminOwnerLongName = "";
        String decodedAdminOwnerShortName = "";
        String decodedAdminFirmwareVersion = "";
        String decodedAdminRole = "";
        String parseError = "";

        try {
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.parseFrom(entry.getPacketBytes());
            meshPacketId = formatUint32(packet.getId());
            meshPacketFromNum = formatUint32(packet.getFrom());
            meshPacketToNum = formatUint32(packet.getTo());
            meshPacketChannel = Integer.toString(packet.getChannel());
            meshPacketRxTime = packet.getRxTime() > 0 ? Long.toString(packet.getRxTime()) : "";
            meshPacketHopStart = packet.getHopStart() != 0 ? Integer.toString(packet.getHopStart()) : "";
            meshPacketHopLimit = packet.getHopLimit() != 0 ? Integer.toString(packet.getHopLimit()) : "";
            meshPacketRxRssi = packet.getRxRssi() != 0 ? Integer.toString(packet.getRxRssi()) : "";
            meshPacketRxSnr = packet.getRxSnr() != 0 ? Float.toString(packet.getRxSnr()) : "";

            if (packet.hasDecoded()) {
                MeshProtos.Data data = packet.getDecoded();
                decodedPortnum = data.getPortnum().name();
                decodedRequestId = data.getRequestId() != 0 ? formatUint32(data.getRequestId()) : "";
                decodedReplyId = data.getReplyId() != 0 ? formatUint32(data.getReplyId()) : "";
                decodedEmoji = data.getEmoji() != 0 ? Integer.toString(data.getEmoji()) : "";
                decodedPayloadSizeBytes = Integer.toString(data.getPayload().size());
                decodedPayloadBase64 = Base64.getEncoder().encodeToString(data.getPayload().toByteArray());

                if (isProbablyText(data.getPayload())) {
                    decodedPayloadText = data.getPayload().toString(StandardCharsets.UTF_8);
                }

                try {
                    switch (data.getPortnum()) {
                        case TEXT_MESSAGE_APP -> decodedPayloadText = data.getPayload().toString(StandardCharsets.UTF_8);
                        case ROUTING_APP -> {
                            MeshProtos.Routing routing = MeshProtos.Routing.parseFrom(data.getPayload());
                            decodedRoutingError = routing.getErrorReason().name();
                        }
                        case NODEINFO_APP -> {
                            MeshProtos.User user = MeshProtos.User.parseFrom(data.getPayload());
                            decodedUserId = user.getId();
                            decodedUserLongName = user.getLongName();
                            decodedUserShortName = user.getShortName();
                            decodedUserRole = user.getRole().name();
                            decodedUserHwModel = user.getHwModel().name();
                        }
                        case POSITION_APP -> {
                            MeshProtos.Position position = MeshProtos.Position.parseFrom(data.getPayload());
                            decodedPositionLatitude = formatCoordinate(position.getLatitudeI());
                            decodedPositionLongitude = formatCoordinate(position.getLongitudeI());
                            decodedPositionAltitude = Integer.toString(position.getAltitude());
                        }
                        case TELEMETRY_APP -> {
                            TelemetryProtos.Telemetry telemetry = TelemetryProtos.Telemetry.parseFrom(data.getPayload());
                            if (telemetry.hasDeviceMetrics()) {
                                var metrics = telemetry.getDeviceMetrics();
                                decodedTelemetryBatteryLevel = Integer.toString(metrics.getBatteryLevel());
                                decodedTelemetryVoltage = Float.toString(metrics.getVoltage());
                            }
                            if (telemetry.hasEnvironmentMetrics()) {
                                var metrics = telemetry.getEnvironmentMetrics();
                                decodedTelemetryTemperature = Float.toString(metrics.getTemperature());
                                decodedTelemetryHumidity = Float.toString(metrics.getRelativeHumidity());
                                decodedTelemetryPressure = Float.toString(metrics.getBarometricPressure());
                            }
                            if (telemetry.hasLocalStats()) {
                                var stats = telemetry.getLocalStats();
                                decodedTelemetryPacketsRx = Integer.toString(stats.getNumPacketsRx());
                                decodedTelemetryPacketsTx = Integer.toString(stats.getNumPacketsTx());
                                decodedTelemetryChannelUtilization = Float.toString(stats.getChannelUtilization());
                                decodedTelemetryAirUtilTx = Float.toString(stats.getAirUtilTx());
                            }
                        }
                        case TRACEROUTE_APP -> {
                            MeshProtos.RouteDiscovery route = MeshProtos.RouteDiscovery.parseFrom(data.getPayload());
                            decodedTracerouteRoute = formatUint32List(route.getRouteList());
                            decodedTracerouteRouteBack = formatUint32List(route.getRouteBackList());
                        }
                        case ADMIN_APP -> {
                            AdminProtos.AdminMessage adminMessage = AdminProtos.AdminMessage.parseFrom(data.getPayload());
                            decodedAdminVariant = adminMessage.getPayloadVariantCase().name();
                            if (adminMessage.hasGetOwnerResponse()) {
                                MeshProtos.User owner = adminMessage.getGetOwnerResponse();
                                decodedAdminOwnerId = owner.getId();
                                decodedAdminOwnerLongName = owner.getLongName();
                                decodedAdminOwnerShortName = owner.getShortName();
                            }
                            if (adminMessage.hasGetDeviceMetadataResponse()) {
                                MeshProtos.DeviceMetadata metadata = adminMessage.getGetDeviceMetadataResponse();
                                decodedAdminFirmwareVersion = metadata.getFirmwareVersion();
                                decodedAdminRole = metadata.getRole().name();
                            }
                        }
                        default -> {
                        }
                    }
                } catch (InvalidProtocolBufferException e) {
                    decodedPayloadParseError = e.getMessage();
                }
            }
        } catch (InvalidProtocolBufferException e) {
            parseError = e.getMessage();
        }

        String[] values = {
                Long.toString(entry.getId()),
                entry.getOwnerNodeId(),
                Instant.ofEpochMilli(entry.getCapturedAt()).toString(),
                Long.toString(entry.getCapturedAt()),
                entry.getCapturedAtText(),
                entry.getDirection().name(),
                entry.getDirectionText(),
                entry.getPacketType(),
                entry.getTransportMechanism(),
                entry.getTransportText(),
                entry.getRouteText(),
                firstNonBlank(endpoints.fromNode(), entry.getFromNode(), "-"),
                firstNonBlank(endpoints.toNode(), entry.getToNode(), "-"),
                entry.getPayloadText(),
                Integer.toString(entry.getPacketBytes().length),
                meshPacketId,
                meshPacketFromNum,
                meshPacketToNum,
                meshPacketChannel,
                meshPacketRxTime,
                meshPacketHopStart,
                meshPacketHopLimit,
                meshPacketRxRssi,
                meshPacketRxSnr,
                decodedPortnum,
                decodedRequestId,
                decodedReplyId,
                decodedEmoji,
                decodedPayloadText,
                decodedPayloadBase64,
                decodedPayloadSizeBytes,
                decodedPayloadParseError,
                decodedRoutingError,
                decodedUserId,
                decodedUserLongName,
                decodedUserShortName,
                decodedUserRole,
                decodedUserHwModel,
                decodedPositionLatitude,
                decodedPositionLongitude,
                decodedPositionAltitude,
                decodedTelemetryBatteryLevel,
                decodedTelemetryVoltage,
                decodedTelemetryTemperature,
                decodedTelemetryHumidity,
                decodedTelemetryPressure,
                decodedTelemetryPacketsRx,
                decodedTelemetryPacketsTx,
                decodedTelemetryChannelUtilization,
                decodedTelemetryAirUtilTx,
                decodedTracerouteRoute,
                decodedTracerouteRouteBack,
                decodedAdminVariant,
                decodedAdminOwnerId,
                decodedAdminOwnerLongName,
                decodedAdminOwnerShortName,
                decodedAdminFirmwareVersion,
                decodedAdminRole,
                parseError
        };

        writeCsvRow(state.writer(), values);
        return new PacketCollectionCsvExportState(state.writer(), state.packetCount() + 1);
    }

    public static void finishPacketCollectionCsvExport(PacketCollectionCsvExportState state) throws IOException {
        if (state == null || state.writer() == null) {
            return;
        }
        state.writer().flush();
    }

    /**
     * Builds a parse tree directly from serialized packet bytes.
 *
     * @param packetBytes serialized {@link MeshProtos.MeshPacket}
     * @return tree root; contains {@code parse_error} when parsing fails
     */
    public static TreeItem<PacketTreeNode> buildPacketTree(byte[] packetBytes) {
        try {
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.parseFrom(packetBytes);
            return buildPacketTree(packet, packetBytes);
        } catch (InvalidProtocolBufferException e) {
            TreeItem<PacketTreeNode> errorRoot = new TreeItem<>(new PacketTreeNode("MeshPacket"));
            errorRoot.setExpanded(true);
            errorRoot.getChildren().add(new TreeItem<>(new PacketTreeNode("parse_error: " + e.getMessage())));
            return errorRoot;
        }
    }

    /**
     * Builds a tree for a raw packet that is not a {@link MeshProtos.MeshPacket}.
 *
     * @param rootLabel   root node label
     * @param packetBytes original packet bytes
     * @return tree with packet type and byte ranges for HEX/ASCII highlighting
     */
    public static TreeItem<PacketTreeNode> buildRawPacketTree(String rootLabel, byte[] packetBytes) {
        byte[] bytes = packetBytes != null ? packetBytes : new byte[0];
        TreeItem<PacketTreeNode> root = new TreeItem<>(
                new PacketTreeNode(rootLabel == null || rootLabel.isBlank()
                        ? I18n.t("packetMonitor.tree.rawPacket")
                        : rootLabel,
                        0, bytes.length));
        root.setExpanded(true);
        if (bytes.length == 0) {
            root.getChildren().add(new TreeItem<>(new PacketTreeNode(I18n.t("packetMonitor.tree.noData"))));
            return root;
        }
        root.getChildren().add(new TreeItem<>(
                new PacketTreeNode("type = 0x%02X".formatted(bytes[0] & 0xFF), 0, 1)));
        if (bytes.length > 1) {
            root.getChildren().add(new TreeItem<>(
                    new PacketTreeNode(I18n.t("packetMonitor.tree.payloadBytes", Integer.toString(bytes.length - 1)),
                            1, bytes.length)));
        }
        return root;
    }

    /**
     * Builds a parse tree from an already decoded protobuf packet.
 *
     * @param packet protobuf packet
     * @return tree root
     */
    public static TreeItem<PacketTreeNode> buildPacketTree(MeshProtos.MeshPacket packet) {
        return buildPacketTree(packet, packet != null ? packet.toByteArray() : new byte[0]);
    }

    /**
     * Builds a parse tree from a protobuf packet and its original bytes, keeping
     * byte ranges for later highlighting in the HEX/ASCII preview.
 *
     * @param packet      protobuf packet
     * @param packetBytes serialized packet bytes
     * @return tree root
     */
    public static TreeItem<PacketTreeNode> buildPacketTree(MeshProtos.MeshPacket packet, byte[] packetBytes) {
        TreeItem<PacketTreeNode> root = new TreeItem<>(new PacketTreeNode("MeshPacket", 0, packetBytes.length));
        root.setExpanded(true);
        appendMessageFields(root, packet, packetBytes, 0);
        return root;
    }

    private static void appendMessageFields(TreeItem<PacketTreeNode> parent,
                                            Message message,
                                            byte[] messageBytes,
                                            int absoluteOffset) {
        if (message == null || messageBytes == null) {
            return;
        }

        Map<FieldDescriptor, Integer> fieldIndexes = new HashMap<>();
        int offset = 0;
        while (offset < messageBytes.length) {
            int fieldStart = offset;
            VarintResult tagResult = readVarint32(messageBytes, offset);
            int tag = tagResult.value();
            offset = tagResult.nextOffset();

            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            int wireType = WireFormat.getTagWireType(tag);
            FieldDescriptor field = message.getDescriptorForType().findFieldByNumber(fieldNumber);

            FieldEnvelope envelope = readFieldEnvelope(messageBytes, fieldStart, offset, wireType);
            offset = envelope.fieldEnd();

            if (field == null) {
                parent.getChildren().add(new TreeItem<>(
                        new PacketTreeNode("unknown_field_" + fieldNumber,
                                absoluteOffset + envelope.fieldStart(),
                                absoluteOffset + envelope.fieldEnd())));
                continue;
            }

            if (isPackedRepeatedField(field, wireType)) {
                int startIndex = fieldIndexes.getOrDefault(field, 0);
                int consumed = appendPackedRepeatedField(parent, message, field, messageBytes, absoluteOffset,
                        envelope, startIndex);
                fieldIndexes.put(field, startIndex + consumed);
                continue;
            }

            int valueIndex = fieldIndexes.getOrDefault(field, 0);
            if (field.isRepeated()) {
                fieldIndexes.put(field, valueIndex + 1);
            }

            appendFieldNode(parent, message, field, valueIndex, messageBytes, absoluteOffset, envelope);
        }
    }

    private static void appendFieldNode(TreeItem<PacketTreeNode> parent,
                                        Message message,
                                        FieldDescriptor field,
                                        int valueIndex,
                                        byte[] messageBytes,
                                        int absoluteOffset,
                                        FieldEnvelope envelope) {
        Object value = field.isRepeated()
                ? ((List<?>) message.getField(field)).get(valueIndex)
                : message.getField(field);
        String fieldName = indexedFieldName(field, valueIndex);
        int startByte = absoluteOffset + envelope.fieldStart();
        int endByte = absoluteOffset + envelope.fieldEnd();

        if (field.getType() == FieldDescriptor.Type.MESSAGE && value instanceof Message nestedMessage) {
            TreeItem<PacketTreeNode> messageNode = new TreeItem<>(new PacketTreeNode(fieldName, startByte, endByte));
            parent.getChildren().add(messageNode);
            byte[] nestedBytes = slice(messageBytes, envelope.valueStart(), envelope.valueEnd());
            appendMessageFields(messageNode, nestedMessage, nestedBytes, absoluteOffset + envelope.valueStart());
            return;
        }

        if (field.getType() == FieldDescriptor.Type.BYTES && value instanceof ByteString bytesValue) {
            TreeItem<PacketTreeNode> bytesNode = new TreeItem<>(
                    new PacketTreeNode(fieldName + ": " + describeBytes(bytesValue), startByte, endByte));
            parent.getChildren().add(bytesNode);
            if (message instanceof MeshProtos.Data data && "payload".equals(field.getName())) {
                appendDecodedPayloadNode(bytesNode, data, slice(messageBytes, envelope.valueStart(), envelope.valueEnd()),
                        absoluteOffset + envelope.valueStart());
            }
            return;
        }

        parent.getChildren().add(new TreeItem<>(
                new PacketTreeNode(fieldName + ": " + formatScalarValue(field, value), startByte, endByte)));
    }

    private static int appendPackedRepeatedField(TreeItem<PacketTreeNode> parent,
                                                 Message message,
                                                 FieldDescriptor field,
                                                 byte[] messageBytes,
                                                 int absoluteOffset,
                                                 FieldEnvelope envelope,
                                                 int startIndex) {
        @SuppressWarnings("unchecked")
        List<Object> values = (List<Object>) message.getField(field);
        TreeItem<PacketTreeNode> packedNode = new TreeItem<>(new PacketTreeNode(
                field.getName() + " [packed]",
                absoluteOffset + envelope.fieldStart(),
                absoluteOffset + envelope.fieldEnd()));
        parent.getChildren().add(packedNode);

        int localOffset = envelope.valueStart();
        int consumed = 0;
        while (localOffset < envelope.valueEnd() && startIndex + consumed < values.size()) {
            int itemStart = localOffset;
            localOffset = skipPackedScalarValue(field, messageBytes, localOffset, envelope.valueEnd());
            Object value = values.get(startIndex + consumed);
            packedNode.getChildren().add(new TreeItem<>(new PacketTreeNode(
                    "[" + (startIndex + consumed) + "]: " + formatScalarValue(field, value),
                    absoluteOffset + itemStart,
                    absoluteOffset + localOffset)));
            consumed++;
        }
        return consumed;
    }

    private static void appendDecodedPayloadNode(TreeItem<PacketTreeNode> bytesNode,
                                                 MeshProtos.Data data,
                                                 byte[] payloadBytes,
                                                 int payloadAbsoluteOffset) {
        Message decodedPayload = tryParsePayload(data);
        if (decodedPayload != null) {
            TreeItem<PacketTreeNode> decodedNode = new TreeItem<>(
                    new PacketTreeNode("decoded_payload", payloadAbsoluteOffset,
                            payloadAbsoluteOffset + payloadBytes.length));
            appendMessageFields(decodedNode, decodedPayload, payloadBytes, payloadAbsoluteOffset);
            bytesNode.getChildren().add(decodedNode);
            return;
        }

        if (!data.getPayload().isEmpty() && isProbablyText(data.getPayload())) {
            bytesNode.getChildren().add(new TreeItem<>(new PacketTreeNode(
                    "text: " + quote(data.getPayload().toString(StandardCharsets.UTF_8)),
                    payloadAbsoluteOffset,
                    payloadAbsoluteOffset + payloadBytes.length)));
        }
    }

    private static String describeDecodedPayload(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        return switch (data.getPortnum()) {
            case TEXT_MESSAGE_APP -> describeTextPayload(data);
            case ROUTING_APP -> describeRoutingPayload(data);
            case NODEINFO_APP -> describeNodeInfoPayload(data);
            case POSITION_APP -> describePositionPayload(data);
            case TELEMETRY_APP -> describeTelemetryPayload(data);
            case TRACEROUTE_APP -> describeTraceroutePayload(data);
            case ADMIN_APP -> describeAdminPayload(data);
            default -> describeGenericPayload(packet, data);
        };
    }

    private static String describeTextPayload(MeshProtos.Data data) {
        String text = data.getPayload().toString(StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return data.getEmoji() != 0
                    ? I18n.t("packetMonitor.payload.reactionNoText")
                    : I18n.t("packetMonitor.payload.emptyText");
        }
        if (data.getEmoji() != 0) {
            return data.getReplyId() != 0
                    ? I18n.t("packetMonitor.payload.reactionToPacket", quote(text), formatUint32(data.getReplyId()))
                    : I18n.t("packetMonitor.payload.reaction", quote(text));
        }
        if (data.getReplyId() != 0) {
            return quote(text) + " (reply_id=" + formatUint32(data.getReplyId()) + ")";
        }
        return quote(text);
    }

    private static String describeRoutingPayload(MeshProtos.Data data) {
        try {
            MeshProtos.Routing routing = MeshProtos.Routing.parseFrom(data.getPayload());
            return "request_id=" + formatUint32(data.getRequestId()) + ", error=" + routing.getErrorReason();
        } catch (InvalidProtocolBufferException e) {
            return "ROUTING_APP: parse error (" + e.getMessage() + ")";
        }
    }

    private static String describeNodeInfoPayload(MeshProtos.Data data) {
        try {
            MeshProtos.User user = MeshProtos.User.parseFrom(data.getPayload());
            String name = firstNonBlank(user.getLongName(), user.getShortName(), user.getId());
            return "user=" + safeText(name) + ", role=" + user.getRole() + ", hw=" + user.getHwModel();
        } catch (InvalidProtocolBufferException e) {
            return "NODEINFO_APP: parse error (" + e.getMessage() + ")";
        }
    }

    private static String describePositionPayload(MeshProtos.Data data) {
        try {
            MeshProtos.Position position = MeshProtos.Position.parseFrom(data.getPayload());
            return "lat=" + formatCoordinate(position.getLatitudeI())
                    + ", lon=" + formatCoordinate(position.getLongitudeI())
                    + ", alt=" + position.getAltitude();
        } catch (InvalidProtocolBufferException e) {
            return "POSITION_APP: parse error (" + e.getMessage() + ")";
        }
    }

    private static String describeTelemetryPayload(MeshProtos.Data data) {
        try {
            TelemetryProtos.Telemetry telemetry = TelemetryProtos.Telemetry.parseFrom(data.getPayload());
            StringBuilder sb = new StringBuilder();
            if (telemetry.hasDeviceMetrics()) {
                var metrics = telemetry.getDeviceMetrics();
                appendSegment(sb, "battery=" + metrics.getBatteryLevel() + "%, voltage=" + metrics.getVoltage());
            }
            if (telemetry.hasEnvironmentMetrics()) {
                var metrics = telemetry.getEnvironmentMetrics();
                appendSegment(sb, "temp=" + metrics.getTemperature()
                        + ", humidity=" + metrics.getRelativeHumidity()
                        + ", pressure=" + metrics.getBarometricPressure());
            }
            if (telemetry.hasLocalStats()) {
                var stats = telemetry.getLocalStats();
                appendSegment(sb, "rx=" + stats.getNumPacketsRx()
                        + ", tx=" + stats.getNumPacketsTx()
                        + ", chUtil=" + stats.getChannelUtilization()
                        + ", airUtil=" + stats.getAirUtilTx());
            }
            if (sb.isEmpty()) {
                appendSegment(sb, "telemetry");
            }
            return sb.toString();
        } catch (InvalidProtocolBufferException e) {
            return "TELEMETRY_APP: parse error (" + e.getMessage() + ")";
        }
    }

    private static String describeTraceroutePayload(MeshProtos.Data data) {
        try {
            MeshProtos.RouteDiscovery route = MeshProtos.RouteDiscovery.parseFrom(data.getPayload());
            return "route=" + formatUint32List(route.getRouteList())
                    + ", back=" + formatUint32List(route.getRouteBackList());
        } catch (InvalidProtocolBufferException e) {
            return "TRACEROUTE_APP: parse error (" + e.getMessage() + ")";
        }
    }

    private static String describeAdminPayload(MeshProtos.Data data) {
        try {
            AdminProtos.AdminMessage adminMessage = AdminProtos.AdminMessage.parseFrom(data.getPayload());
            if (adminMessage.hasGetOwnerResponse()) {
                MeshProtos.User owner = adminMessage.getGetOwnerResponse();
                return "owner=" + safeText(firstNonBlank(owner.getLongName(), owner.getShortName(), owner.getId()));
            }
            if (adminMessage.hasGetDeviceMetadataResponse()) {
                MeshProtos.DeviceMetadata metadata = adminMessage.getGetDeviceMetadataResponse();
                return "firmware=" + safeText(metadata.getFirmwareVersion())
                        + ", role=" + metadata.getRole();
            }
            return adminMessage.getPayloadVariantCase().name();
        } catch (InvalidProtocolBufferException e) {
            return "ADMIN_APP: parse error (" + e.getMessage() + ")";
        }
    }

    private static String describeGenericPayload(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        if (data.getPayload().isEmpty()) {
            return I18n.t("packetMonitor.payload.emptyPayload");
        }
        if (isProbablyText(data.getPayload())) {
            return quote(truncate(data.getPayload().toString(StandardCharsets.UTF_8), TEXT_PREVIEW_LIMIT));
        }
        return "bytes=" + data.getPayload().size()
                + ", packet_id=" + formatUint32(packet.getId());
    }

    private static String describeEncryptedPacket(MeshProtos.MeshPacket packet) {
        return I18n.t("packetMonitor.payload.encrypted", Integer.toString(packet.getEncrypted().size()));
    }

    private static boolean isPackedRepeatedField(FieldDescriptor field, int wireType) {
        return field != null
                && field.isRepeated()
                && field.isPackable()
                && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED;
    }

    private static String indexedFieldName(FieldDescriptor field, int valueIndex) {
        if (field == null) {
            return "?";
        }
        return field.isRepeated() ? field.getName() + "[" + valueIndex + "]" : field.getName();
    }

    private static FieldEnvelope readFieldEnvelope(byte[] bytes,
                                                   int fieldStart,
                                                   int afterTagOffset,
                                                   int wireType) {
        return switch (wireType) {
            case WireFormat.WIRETYPE_VARINT -> {
                int fieldEnd = skipVarint(bytes, afterTagOffset);
                yield new FieldEnvelope(fieldStart, fieldEnd, afterTagOffset, fieldEnd);
            }
            case WireFormat.WIRETYPE_FIXED32 -> {
                int fieldEnd = afterTagOffset + 4;
                yield new FieldEnvelope(fieldStart, fieldEnd, afterTagOffset, fieldEnd);
            }
            case WireFormat.WIRETYPE_FIXED64 -> {
                int fieldEnd = afterTagOffset + 8;
                yield new FieldEnvelope(fieldStart, fieldEnd, afterTagOffset, fieldEnd);
            }
            case WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                VarintResult lengthResult = readVarint32(bytes, afterTagOffset);
                int valueStart = lengthResult.nextOffset();
                int valueEnd = valueStart + lengthResult.value();
                yield new FieldEnvelope(fieldStart, valueEnd, valueStart, valueEnd);
            }
            default -> throw new IllegalArgumentException("Unsupported wire type: " + wireType);
        };
    }

    private static VarintResult readVarint32(byte[] bytes, int offset) {
        int value = 0;
        int shift = 0;
        int cursor = offset;
        while (cursor < bytes.length) {
            int current = bytes[cursor++] & 0xFF;
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return new VarintResult(value, cursor);
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Malformed varint at offset " + offset);
    }

    private static int skipVarint(byte[] bytes, int offset) {
        return readVarint32(bytes, offset).nextOffset();
    }

    private static int skipPackedScalarValue(FieldDescriptor field, byte[] bytes, int offset, int limit) {
        if (offset >= limit) {
            return offset;
        }
        return switch (field.getType()) {
            case BOOL, ENUM, INT32, UINT32, INT64, UINT64, SINT32, SINT64 -> skipVarint(bytes, offset);
            case FIXED32, SFIXED32, FLOAT -> offset + 4;
            case FIXED64, SFIXED64, DOUBLE -> offset + 8;
            default -> throw new IllegalArgumentException("Unsupported packed type: " + field.getType());
        };
    }

    private static void appendExportLine(StringBuilder sb, String label, String value) {
        sb.append(label).append(": ").append(value != null ? value : "").append('\n');
    }

    private static void appendTreeText(StringBuilder sb, TreeItem<PacketTreeNode> item, int depth) {
        if (item == null || item.getValue() == null) {
            return;
        }
        sb.append("  ".repeat(Math.max(0, depth))).append(item.getValue().getLabel());
        if (item.getValue().hasByteRange()) {
            sb.append(" [").append(item.getValue().getStartByte())
                    .append("..").append(item.getValue().getEndByte()).append(')');
        }
        sb.append('\n');
        for (TreeItem<PacketTreeNode> child : item.getChildren()) {
            appendTreeText(sb, child, depth + 1);
        }
    }

    private static JsonObject toProtoJsonObject(Message message) {
        JsonObject json = new JsonObject();
        if (message == null) {
            return json;
        }
        for (Map.Entry<FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
            FieldDescriptor field = entry.getKey();
            json.add(field.getJsonName(), toProtoJsonElement(field, entry.getValue()));
        }
        return json;
    }

    private static JsonObject toExportFiltersJson(PacketCollectionExportMetadata metadata) {
        JsonObject filters = new JsonObject();
        addNullableProperty(filters, "route", metadata.routeFilter());
        addNullableProperty(filters, "packetType", metadata.packetTypeFilter());
        addNullableProperty(filters, "searchText", metadata.searchText());
        addNullableProperty(filters, "capturedAtFrom", metadata.capturedAtFrom());
        addNullableProperty(filters, "capturedAtTo", metadata.capturedAtTo());
        return filters;
    }

    private static JsonObject buildHierarchicalPacketJson(PacketLogEntry entry, DeviceState deviceState) {
        JsonObject packetJson = new JsonObject();
        if (entry == null) {
            return packetJson;
        }

        PacketEndpoints endpoints = resolvePacketEndpoints(entry, deviceState);
        JsonObject logEntryJson = new JsonObject();
        logEntryJson.addProperty("id", entry.getId());
        addNullableProperty(logEntryJson, "ownerNodeId", entry.getOwnerNodeId());
        logEntryJson.addProperty("capturedAt", Instant.ofEpochMilli(entry.getCapturedAt()).toString());
        logEntryJson.addProperty("capturedAtMillis", entry.getCapturedAt());
        addNullableProperty(logEntryJson, "capturedAtText", entry.getCapturedAtText());
        logEntryJson.addProperty("direction", entry.getDirection().name());
        addNullableProperty(logEntryJson, "directionText", entry.getDirectionText());
        addNullableProperty(logEntryJson, "packetType", entry.getPacketType());
        addNullableProperty(logEntryJson, "transportMechanism", entry.getTransportMechanism());
        addNullableProperty(logEntryJson, "transportText", entry.getTransportText());
        addNullableProperty(logEntryJson, "routeText", entry.getRouteText());
        addNullableProperty(logEntryJson, "from", endpoints.fromNode());
        addNullableProperty(logEntryJson, "to", endpoints.toNode());
        addNullableProperty(logEntryJson, "payloadText", entry.getPayloadText());
        logEntryJson.addProperty("packetSizeBytes", entry.getPacketBytes().length);
        packetJson.add("logEntry", logEntryJson);

        try {
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.parseFrom(entry.getPacketBytes());
            JsonObject meshPacketJson = toProtoJsonObject(packet);
            appendDecodedPayloadJson(meshPacketJson, packet);
            packetJson.add("meshPacket", meshPacketJson);
            packetJson.add("hierarchy", toTreeJson(buildPacketTree(packet, entry.getPacketBytes())));
        } catch (InvalidProtocolBufferException e) {
            packetJson.addProperty("parseError", e.getMessage());
            packetJson.add("hierarchy", toTreeJson(buildPacketTree(entry.getPacketBytes())));
        }

        return packetJson;
    }

    private static void appendDecodedPayloadJson(JsonObject meshPacketJson, MeshProtos.MeshPacket packet) {
        if (meshPacketJson == null || packet == null || !packet.hasDecoded()) {
            return;
        }
        JsonObject decodedJson = meshPacketJson.getAsJsonObject("decoded");
        if (decodedJson == null) {
            return;
        }
        JsonElement decodedPayloadJson = toDecodedPayloadJson(packet.getDecoded());
        if (decodedPayloadJson != null) {
            decodedJson.add("decodedPayload", decodedPayloadJson);
        }
    }

    private static JsonElement toDecodedPayloadJson(MeshProtos.Data data) {
        if (data == null) {
            return null;
        }
        return switch (data.getPortnum()) {
            case TEXT_MESSAGE_APP -> new JsonPrimitive(data.getPayload().toString(StandardCharsets.UTF_8));
            case ROUTING_APP -> parseDecodedPayloadJson(() -> MeshProtos.Routing.parseFrom(data.getPayload()));
            case NODEINFO_APP -> parseDecodedPayloadJson(() -> MeshProtos.User.parseFrom(data.getPayload()));
            case POSITION_APP -> parseDecodedPayloadJson(() -> MeshProtos.Position.parseFrom(data.getPayload()));
            case TELEMETRY_APP -> parseDecodedPayloadJson(() -> TelemetryProtos.Telemetry.parseFrom(data.getPayload()));
            case TRACEROUTE_APP -> parseDecodedPayloadJson(() -> MeshProtos.RouteDiscovery.parseFrom(data.getPayload()));
            case ADMIN_APP -> parseDecodedPayloadJson(() -> AdminProtos.AdminMessage.parseFrom(data.getPayload()));
            default -> {
                if (data.getPayload().isEmpty()) {
                    yield new JsonPrimitive("");
                }
                if (isProbablyText(data.getPayload())) {
                    yield new JsonPrimitive(data.getPayload().toString(StandardCharsets.UTF_8));
                }
                yield null;
            }
        };
    }

    private static JsonElement parseDecodedPayloadJson(PayloadParser parser) {
        try {
            return toProtoJsonObject(parser.parse());
        } catch (InvalidProtocolBufferException e) {
            JsonObject errorJson = new JsonObject();
            errorJson.addProperty("parseError", e.getMessage());
            return errorJson;
        }
    }

    private static JsonObject toTreeJson(TreeItem<PacketTreeNode> item) {
        JsonObject json = new JsonObject();
        if (item == null || item.getValue() == null) {
            return json;
        }
        PacketTreeNode node = item.getValue();
        json.addProperty("label", node.getLabel());
        if (node.hasByteRange()) {
            json.addProperty("startByte", node.getStartByte());
            json.addProperty("endByte", node.getEndByte());
        }
        JsonArray children = new JsonArray();
        for (TreeItem<PacketTreeNode> child : item.getChildren()) {
            children.add(toTreeJson(child));
        }
        json.add("children", children);
        return json;
    }

    private static void addNullableProperty(JsonObject json, String propertyName, String value) {
        if (json == null || propertyName == null || value == null || value.isBlank()) {
            return;
        }
        json.addProperty(propertyName, value);
    }

    private static void writeCsvRow(Writer writer, String[] values) throws IOException {
        if (writer == null || values == null) {
            return;
        }
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(escapeCsvCell(values[i]));
        }
        writer.write('\n');
    }

    private static String escapeCsvCell(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\n', ' ');
        boolean needsQuotes = normalized.contains(",")
                || normalized.contains("\"")
                || normalized.startsWith(" ")
                || normalized.endsWith(" ");
        String escaped = normalized.replace("\"", "\"\"");
        return needsQuotes ? "\"" + escaped + "\"" : escaped;
    }

    private static JsonElement toProtoJsonElement(FieldDescriptor field, Object value) {
        if (field.isRepeated()) {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            @SuppressWarnings("unchecked")
            List<Object> values = (List<Object>) value;
            for (Object item : values) {
                array.add(toSingleProtoJsonElement(field, item));
            }
            return array;
        }
        return toSingleProtoJsonElement(field, value);
    }

    private static JsonElement toSingleProtoJsonElement(FieldDescriptor field, Object value) {
        return switch (field.getType()) {
            case MESSAGE -> toProtoJsonObject((Message) value);
            case ENUM -> new JsonPrimitive(((EnumValueDescriptor) value).getName());
            case BYTES -> new JsonPrimitive(Base64.getEncoder().encodeToString(((ByteString) value).toByteArray()));
            case BOOL -> new JsonPrimitive((Boolean) value);
            case STRING -> new JsonPrimitive((String) value);
            case FLOAT -> {
                float floatValue = ((Number) value).floatValue();
                yield Float.isFinite(floatValue) ? new JsonPrimitive(floatValue)
                        : new JsonPrimitive(Float.toString(floatValue));
            }
            case DOUBLE -> {
                double doubleValue = ((Number) value).doubleValue();
                yield Double.isFinite(doubleValue) ? new JsonPrimitive(doubleValue)
                        : new JsonPrimitive(Double.toString(doubleValue));
            }
            case UINT32, FIXED32 -> new JsonPrimitive(Integer.toUnsignedLong(((Number) value).intValue()));
            case INT32, SINT32, SFIXED32 -> new JsonPrimitive(((Number) value).intValue());
            case UINT64, FIXED64 -> new JsonPrimitive(Long.toUnsignedString(((Number) value).longValue()));
            case INT64, SINT64, SFIXED64 -> new JsonPrimitive(Long.toString(((Number) value).longValue()));
            default -> throw new IllegalArgumentException("Unsupported protobuf type: " + field.getType());
        };
    }

    private static byte[] slice(byte[] source, int start, int end) {
        int length = Math.max(0, end - start);
        byte[] copy = new byte[length];
        if (length > 0) {
            System.arraycopy(source, start, copy, 0, length);
        }
        return copy;
    }

    private static Message tryParsePayload(MeshProtos.Data data) {
        try {
            return switch (data.getPortnum()) {
                case ROUTING_APP -> MeshProtos.Routing.parseFrom(data.getPayload());
                case NODEINFO_APP -> MeshProtos.User.parseFrom(data.getPayload());
                case POSITION_APP -> MeshProtos.Position.parseFrom(data.getPayload());
                case TELEMETRY_APP -> TelemetryProtos.Telemetry.parseFrom(data.getPayload());
                case TRACEROUTE_APP -> MeshProtos.RouteDiscovery.parseFrom(data.getPayload());
                case ADMIN_APP -> AdminProtos.AdminMessage.parseFrom(data.getPayload());
                default -> null;
            };
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
    }

    /**
     * Formats a node identifier for the packet monitor UI.
     * This method intentionally differs from the rest of the application:
     * here {@code nodeId} is shown as a protobuf {@code uint32}, using unsigned
     * decimal notation.
 *
     * @param nodeNum     raw node number from the protobuf packet
     * @param deviceState device state used to resolve the node name
     * @return node name with the {@code uint32} identifier in parentheses, only
     *         {@code uint32} when the name is unknown, {@code "-"} for zero, or
     *         the broadcast label for {@code 0xFFFFFFFF}
     */
    private static String formatNode(int nodeNum, com.meshtastic.client.model.DeviceState deviceState) {
        long unsignedNodeNum = Integer.toUnsignedLong(nodeNum);
        if (unsignedNodeNum == BROADCAST_NODE_NUM) {
            return I18n.t("packetMonitor.node.broadcast", Long.toString(unsignedNodeNum));
        }
        if (nodeNum == 0) {
            return "-";
        }

        String nodeId = Long.toUnsignedString(unsignedNodeNum);
        if (deviceState != null) {
            NodeData node = deviceState.getNodeDb().get(nodeNum);
            if (node != null) {
                String name = firstNonBlank(node.getLongName(), node.getShortName());
                if (name != null) {
                    return name + " (" + nodeId + ")";
                }
            }
        }
        return nodeId;
    }

    /**
     * Formats a node identifier in the standard Meshtastic form used by the
     * table and exports: {@code Name (!nodeId)}, or only {@code !nodeId} when
     * the name is unknown.
     */
    private static String formatNodeDisplay(int nodeNum, com.meshtastic.client.model.DeviceState deviceState) {
        String nodeId = String.format("!%08x", nodeNum);
        if (Integer.toUnsignedLong(nodeNum) == BROADCAST_NODE_NUM) {
            return I18n.t("packetMonitor.node.broadcast", nodeId);
        }

        String name = null;
        if (deviceState != null) {
            NodeData node = deviceState.getNodeDb().get(nodeNum);
            if (node != null) {
                name = firstNonBlank(node.getLongName(), node.getShortName());
            }
        }

        return name != null ? name + " (" + nodeId + ")" : nodeId;
    }

    private static String describeBytes(ByteString bytes) {
        if (bytes == null || bytes.isEmpty()) {
            return "bytes[0]";
        }
        byte[] array = bytes.toByteArray();
        String preview = truncate(hexInline(array), 3 * 12);
        return "bytes[" + array.length + "] " + preview;
    }

    private static String hexInline(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String formatScalarValue(FieldDescriptor field, Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return quote(text);
        }
        if (field == null) {
            return String.valueOf(value);
        }

        return switch (field.getType()) {
            case UINT32, FIXED32 -> Long.toUnsignedString(Integer.toUnsignedLong(((Number) value).intValue()));
            case UINT64, FIXED64 -> Long.toUnsignedString(((Number) value).longValue());
            case ENUM -> ((EnumValueDescriptor) value).getName();
            case BOOL, INT32, SINT32, SFIXED32, FLOAT, DOUBLE, INT64, SINT64, SFIXED64 -> String.valueOf(value);
            default -> String.valueOf(value);
        };
    }

    private static String formatUint32(int value) {
        return Long.toUnsignedString(Integer.toUnsignedLong(value));
    }

    private static String formatUint32List(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        List<String> rendered = new ArrayList<>(values.size());
        for (Integer value : values) {
            rendered.add(formatUint32(value != null ? value : 0));
        }
        return rendered.toString();
    }

    private static boolean isProbablyText(ByteString bytes) {
        String text = bytes.toString(StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return false;
        }
        int printable = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t' || !Character.isISOControl(ch)) {
                printable++;
            }
        }
        return printable >= (int) Math.ceil(text.length() * 0.9);
    }

    private static void appendSegment(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(" | ");
        }
        sb.append(value);
    }

    private static String formatCoordinate(int coordinateI) {
        return coordinateI == 0
                ? "0"
                : String.format(Locale.ROOT, "%.7f", coordinateI * 1e-7);
    }

    private static String truncate(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit - 3) + "...";
    }

    private static String quote(String text) {
        return '"' + safeText(text) + '"';
    }

    private static String safeText(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
