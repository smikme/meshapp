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
 * Форматирование и построение дерева для отладки LoRa-пакетов.
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
     * Человекочитаемое описание mesh-пакета для таблицы мониторинга.
     *
     * @param packetType       тип пакета или portnum
     * @param fromNode         отправитель в UI-виде
     * @param toNode           получатель в UI-виде
     * @param payloadText      payload в текстовом представлении
     * @param capturedAtMillis время захвата в миллисекундах Unix epoch
     */
    public record PacketDetails(String packetType,
                                String fromNode,
                                String toNode,
                                String payloadText,
                                long capturedAtMillis) {}

    /**
     * Подготовленные подписи адресов верхнего уровня MeshPacket для UI.
     * Контракт:
     * - если имя ноды известно, оно добавляется перед стандартным {@code nodeId};
     * - если имя неизвестно, возвращается только стандартный {@code nodeId};
     * - для broadcast используется локализованная подпись c {@code !ffffffff}.
     */
    public record PacketEndpoints(String fromNode, String toNode) {}

    /**
     * Метаданные массового JSON-экспорта по текущим фильтрам окна мониторинга.
     *
     * @param exportedAtMillis время создания файла экспорта
     * @param routeFilter      человекочитаемое значение фильтра маршрута
     * @param packetTypeFilter человекочитаемое значение фильтра типа
     * @param searchText       поисковая строка или {@code null}
     * @param capturedAtFrom   нижняя граница времени в UI-представлении или {@code null}
     * @param capturedAtTo     верхняя граница времени в UI-представлении или {@code null}
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
     * Диапазон выделения в текстовом представлении предпросмотра.
     *
     * @param startChar индекс первого символа включительно
     * @param endChar   индекс последнего символа исключительно
     */
    public record TextSelectionRange(int startChar, int endChar) {}

    /**
     * Подготовленный HEX/ASCII предпросмотр пакета с отображением байтов в символьные позиции.
     * Используется для независимой подсветки HEX и ASCII колонок при выборе узлов дерева.
     *
     * @param addressText         адресная колонка
     * @param hexText             HEX-колонка
     * @param asciiText           ASCII-колонка
     * @param hexByteStartChars   индекс начала байта в HEX-представлении
     * @param hexByteEndChars     индекс конца байта в HEX-представлении
     * @param asciiByteStartChars индекс начала байта в ASCII-представлении
     * @param asciiByteEndChars   индекс конца байта в ASCII-представлении
     */
    public record HexPreview(String addressText,
                             String hexText,
                             String asciiText,
                             int[] hexByteStartChars,
                             int[] hexByteEndChars,
                             int[] asciiByteStartChars,
                             int[] asciiByteEndChars) {
        /**
         * @return {@code true}, если предпросмотр содержит хотя бы один байт пакета
         */
        public boolean hasBytes() {
            return hexByteStartChars != null && hexByteStartChars.length > 0;
        }

        /**
         * Возвращает диапазон символов для выделения в HEX-колонке.
         *
         * @param startByte первый байт включительно
         * @param endByte   последний байт исключительно
         * @return диапазон символов или {@code null}, если диапазон некорректен
         */
        public TextSelectionRange selectionForHexBytes(int startByte, int endByte) {
            if (!hasBytes() || startByte < 0 || endByte <= startByte || endByte > hexByteStartChars.length) {
                return null;
            }
            return new TextSelectionRange(hexByteStartChars[startByte], hexByteEndChars[endByte - 1]);
        }

        /**
         * Возвращает диапазон символов для выделения в ASCII-колонке.
         *
         * @param startByte первый байт включительно
         * @param endByte   последний байт исключительно
         * @return диапазон символов или {@code null}, если диапазон некорректен
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
     * Формирует объединённое текстовое HEX-представление пакета в виде:
     * {@code address  hex  |ascii|}.
     *
     * @param bytes сериализованный пакет
     * @return строка HEX-предпросмотра или сообщение об отсутствии данных
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
     * Строит раздельный адресный, HEX и ASCII предпросмотр пакета.
     *
     * @param bytes сериализованный пакет
     * @return объект предпросмотра с привязкой байтов к символьным диапазонам
     */
    public static HexPreview formatHexPreview(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new HexPreview("", "Нет данных", "", new int[0], new int[0], new int[0], new int[0]);
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
     * Экспортирует выбранный пакет в человекочитаемый текстовый формат для анализа и обмена.
     * Включает метаданные пакета, HEX-представление и текущее дерево разбора.
     *
     * @param entry выбранная запись из журнала
     * @return текст экспорта или пустая строка, если запись отсутствует
     */
    public static String exportPacketAsText(PacketLogEntry entry) {
        return exportPacketAsText(entry, null);
    }

    /**
     * Экспортирует выбранный пакет в человекочитаемый текстовый формат для анализа и обмена.
     * Поля {@code От} и {@code Кому} пересчитываются из {@link MeshProtos.MeshPacket},
     * чтобы UI и экспорт использовали единый формат {@code Имя (!nodeId)} независимо
     * от того, в каком виде адреса были сохранены в БД.
     *
     * @param entry       выбранная запись из журнала
     * @param deviceState состояние устройства для разрешения имён нод; может быть {@code null}
     * @return текст экспорта или пустая строка, если запись отсутствует
     */
    public static String exportPacketAsText(PacketLogEntry entry,
                                            com.meshtastic.client.model.DeviceState deviceState) {
        if (entry == null) {
            return "";
        }

        PacketEndpoints endpoints = resolvePacketEndpoints(entry, deviceState);
        StringBuilder sb = new StringBuilder(1024);
        appendExportLine(sb, "ID", Long.toString(entry.getId()));
        appendExportLine(sb, "Дата/время", entry.getCapturedAtText());
        appendExportLine(sb, "Направление", entry.getDirectionText());
        appendExportLine(sb, "Тип пакета", entry.getPacketType());
        appendExportLine(sb, "От", endpoints.fromNode());
        appendExportLine(sb, "Кому", endpoints.toNode());
        appendExportLine(sb, "Payload", entry.getPayloadText());
        appendExportLine(sb, "Размер", entry.getPacketBytes().length + " байт");
        sb.append('\n').append("HEX").append('\n');
        sb.append(formatHex(entry.getPacketBytes()));
        sb.append('\n').append('\n').append("Иерархия").append('\n');
        appendTreeText(sb, buildPacketTree(entry.getPacketBytes()), 0);
        return sb.toString();
    }

    /**
     * Возвращает подписи полей {@code from}/{@code to} для уже сохранённой записи пакета.
     * Метод не зависит от строк, лежащих в БД: приоритетом всегда является разбор
     * {@link MeshProtos.MeshPacket} из {@code packet_bytes}, а значения записи используются только как fallback.
     *
     * @param entry       запись журнала
     * @param deviceState состояние устройства для разрешения имён нод; может быть {@code null}
     * @return подписи отправителя и получателя, пригодные для таблицы и текстового экспорта
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
     * Разбирает верхнеуровневые поля {@code from}/{@code to} напрямую из байтов пакета.
     *
     * @param packetBytes сериализованный {@link MeshProtos.MeshPacket}
     * @param deviceState состояние устройства для разрешения имён нод; может быть {@code null}
     * @return подписи отправителя и получателя; при ошибке разбора оба значения {@code null}
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
     * Экспортирует только содержимое {@link MeshProtos.MeshPacket} в protobuf-style JSON.
     * Формат намеренно не содержит служебных полей desktop-клиента, чтобы результат можно было
     * вставлять в оригинальное web-приложение Meshtastic.
     *
     * @param entry выбранная запись из журнала
     * @return JSON пакета или пустая строка, если пакет отсутствует либо не разобран
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
     * Экспортирует набор пакетов в иерархический JSON-файл с учётом текущих фильтров UI.
     * В отличие от {@link #exportPacketAsJson(PacketLogEntry)}, формат включает:
     * - метаданные экспорта и выбранных фильтров;
     * - desktop-метаданные каждой записи журнала;
     * - protobuf-структуру пакета;
     * - расшифрованный {@code decodedPayload}, когда его можно разобрать;
     * - дерево иерархии пакета с byte-range.
     *
     * @param entries            записи, которые должны попасть в файл
     * @param metadata           метаданные экспорта; может быть {@code null}
     * @param deviceStateResolver resolver device-state для нормализации имён нод; может быть {@code null}
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
     * Строит дерево разбора напрямую из сериализованных байтов пакета.
     *
     * @param packetBytes сериализованный {@link MeshProtos.MeshPacket}
     * @return корневой узел дерева; при ошибке разбора содержит {@code parse_error}
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
     * Строит дерево для raw-пакета, который не является {@link MeshProtos.MeshPacket}.
     *
     * @param rootLabel подпись корневого узла
     * @param packetBytes исходные байты packet-а
     * @return дерево с типом packet-а и byte-range для подсветки HEX/ASCII
     */
    public static TreeItem<PacketTreeNode> buildRawPacketTree(String rootLabel, byte[] packetBytes) {
        byte[] bytes = packetBytes != null ? packetBytes : new byte[0];
        TreeItem<PacketTreeNode> root = new TreeItem<>(
                new PacketTreeNode(rootLabel == null || rootLabel.isBlank() ? "Raw packet" : rootLabel,
                        0, bytes.length));
        root.setExpanded(true);
        if (bytes.length == 0) {
            root.getChildren().add(new TreeItem<>(new PacketTreeNode("Нет данных")));
            return root;
        }
        root.getChildren().add(new TreeItem<>(
                new PacketTreeNode("type = 0x%02X".formatted(bytes[0] & 0xFF), 0, 1)));
        if (bytes.length > 1) {
            root.getChildren().add(new TreeItem<>(
                    new PacketTreeNode("payload (%d bytes)".formatted(bytes.length - 1), 1, bytes.length)));
        }
        return root;
    }

    /**
     * Строит дерево разбора из уже декодированного protobuf-пакета.
     *
     * @param packet protobuf-пакет
     * @return корневой узел дерева
     */
    public static TreeItem<PacketTreeNode> buildPacketTree(MeshProtos.MeshPacket packet) {
        return buildPacketTree(packet, packet != null ? packet.toByteArray() : new byte[0]);
    }

    /**
     * Строит дерево разбора из protobuf-пакета и исходных байтов, сохраняя диапазоны байтов
     * для последующей подсветки в HEX/ASCII предпросмотре.
     *
     * @param packet      protobuf-пакет
     * @param packetBytes сериализованные байты пакета
     * @return корневой узел дерева
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
            return data.getEmoji() != 0 ? "Реакция без текста" : "Пустое текстовое сообщение";
        }
        if (data.getEmoji() != 0) {
            return "Реакция " + quote(text)
                    + (data.getReplyId() != 0 ? " на packet_id=" + formatUint32(data.getReplyId()) : "");
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
            return "Пустой payload";
        }
        if (isProbablyText(data.getPayload())) {
            return quote(truncate(data.getPayload().toString(StandardCharsets.UTF_8), TEXT_PREVIEW_LIMIT));
        }
        return "bytes=" + data.getPayload().size()
                + ", packet_id=" + formatUint32(packet.getId());
    }

    private static String describeEncryptedPacket(MeshProtos.MeshPacket packet) {
        return "Encrypted payload, bytes=" + packet.getEncrypted().size();
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
     * Форматирует идентификатор узла для UI мониторинга пакетов.
     * Контракт метода намеренно отличается от остального приложения: здесь {@code nodeId}
     * показывается как protobuf {@code uint32}, то есть в unsigned decimal-виде.
     *
     * @param nodeNum     raw node number из protobuf-пакета
     * @param deviceState состояние устройства для разрешения имени узла
     * @return имя узла с {@code uint32}-идентификатором в скобках или только {@code uint32},
     *         {@code "-"} для нулевого адреса и строка broadcast для {@code 0xFFFFFFFF}
     */
    private static String formatNode(int nodeNum, com.meshtastic.client.model.DeviceState deviceState) {
        long unsignedNodeNum = Integer.toUnsignedLong(nodeNum);
        if (unsignedNodeNum == BROADCAST_NODE_NUM) {
            return "Вещание (" + unsignedNodeNum + ")";
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
     * Форматирует идентификатор узла в стандартном Meshtastic-виде для таблицы и экспорта:
     * {@code Имя (!nodeId)} или только {@code !nodeId}, если имя неизвестно.
     */
    private static String formatNodeDisplay(int nodeNum, com.meshtastic.client.model.DeviceState deviceState) {
        String nodeId = String.format("!%08x", nodeNum);
        if (Integer.toUnsignedLong(nodeNum) == BROADCAST_NODE_NUM) {
            return "Вещание (" + nodeId + ")";
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
