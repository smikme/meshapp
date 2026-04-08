package com.meshtastic.client.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.WireFormat;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.model.PacketTreeNode;
import com.meshtastic.client.model.PacketLogEntry.Direction;
import javafx.scene.control.TreeItem;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;

import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Base64;

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
        if (entry == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder(1024);
        appendExportLine(sb, "ID", Long.toString(entry.getId()));
        appendExportLine(sb, "Дата/время", entry.getCapturedAtText());
        appendExportLine(sb, "Направление", entry.getDirectionText());
        appendExportLine(sb, "Тип пакета", entry.getPacketType());
        appendExportLine(sb, "От", entry.getFromNode());
        appendExportLine(sb, "Кому", entry.getToNode());
        appendExportLine(sb, "Payload", entry.getPayloadText());
        appendExportLine(sb, "Размер", entry.getPacketBytes().length + " байт");
        sb.append('\n').append("HEX").append('\n');
        sb.append(formatHex(entry.getPacketBytes()));
        sb.append('\n').append('\n').append("Иерархия").append('\n');
        appendTreeText(sb, buildPacketTree(entry.getPacketBytes()), 0);
        return sb.toString();
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
