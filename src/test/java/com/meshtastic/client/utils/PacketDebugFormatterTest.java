package com.meshtastic.client.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.model.PacketTreeNode;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class PacketDebugFormatterTest {

    @BeforeEach
    void setLanguage() {
        I18n.setLanguageTagForTests(I18n.LANGUAGE_RU);
    }

    @Test
    void packetTreeContainsByteRangesForNestedPayloadSelection() {
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x12345678)
                .setTo(0xFFFFFFFF)
                .setId(101)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFromUtf8("hello"))
                        .setReplyId(77)
                        .build())
                .build();

        byte[] packetBytes = packet.toByteArray();
        TreeItem<PacketTreeNode> root = PacketDebugFormatter.buildPacketTree(packetBytes);

        assertEquals(0, root.getValue().getStartByte());
        assertEquals(packetBytes.length, root.getValue().getEndByte());

        TreeItem<PacketTreeNode> payloadNode = findByPrefix(root, "payload:");
        TreeItem<PacketTreeNode> textNode = findByPrefix(root, "text:");

        assertNotNull(payloadNode);
        assertNotNull(textNode);
        assertTrue(payloadNode.getValue().hasByteRange());
        assertTrue(textNode.getValue().hasByteRange());
        assertTrue(textNode.getValue().getStartByte() >= payloadNode.getValue().getStartByte());
        assertTrue(textNode.getValue().getEndByte() <= payloadNode.getValue().getEndByte());

        PacketDebugFormatter.HexPreview preview = PacketDebugFormatter.formatHexPreview(packetBytes);
        PacketDebugFormatter.TextSelectionRange hexSelection = preview.selectionForHexBytes(
                textNode.getValue().getStartByte(),
                textNode.getValue().getEndByte()
        );
        PacketDebugFormatter.TextSelectionRange asciiSelection = preview.selectionForAsciiBytes(
                textNode.getValue().getStartByte(),
                textNode.getValue().getEndByte()
        );

        assertNotNull(hexSelection);
        assertNotNull(asciiSelection);
        assertTrue(hexSelection.endChar() > hexSelection.startChar());
        assertTrue(asciiSelection.endChar() > asciiSelection.startChar());
    }

    @Test
    void packetTreeShowsUint32FieldsAsUnsignedValues() {
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0xF66F82E0)
                .setTo(0xFFFFFFFF)
                .setId(101)
                .build();

        TreeItem<PacketTreeNode> root = PacketDebugFormatter.buildPacketTree(packet.toByteArray());

        assertNotNull(findByPrefix(root, "from: 4134503136"), dumpTree(root));
        assertNotNull(findByPrefix(root, "to: 4294967295"), dumpTree(root));
    }

    @Test
    void payloadTextShowsUint32FieldsAsUnsignedValues() {
        MeshProtos.MeshPacket textPacket = MeshProtos.MeshPacket.newBuilder()
                .setFrom(1)
                .setTo(2)
                .setId(0xFFFFFFFF)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFromUtf8("hello"))
                        .setReplyId(0xFFFFFFFF)
                        .build())
                .build();
        MeshProtos.MeshPacket routePacket = MeshProtos.MeshPacket.newBuilder()
                .setFrom(1)
                .setTo(2)
                .setId(1)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TRACEROUTE_APP)
                        .setRequestId(0xFFFFFFFF)
                        .setPayload(MeshProtos.RouteDiscovery.newBuilder()
                                .addRoute(0xF66F82E0)
                                .addRouteBack(0xFFFFFFFF)
                                .build()
                                .toByteString())
                        .build())
                .build();

        PacketDebugFormatter.PacketDetails textDetails =
                PacketDebugFormatter.describeMeshPacket(textPacket, PacketLogEntry.Direction.INCOMING, null);
        PacketDebugFormatter.PacketDetails routeDetails =
                PacketDebugFormatter.describeMeshPacket(routePacket, PacketLogEntry.Direction.INCOMING, null);

        assertEquals("\"hello\" (reply_id=4294967295)", textDetails.payloadText());
        assertEquals("route=[4134503136], back=[4294967295]", routeDetails.payloadText());
    }

    @Test
    void multilineSelectionDoesNotIncludeAddressColumn() {
        byte[] bytes = new byte[24];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (0x41 + i);
        }

        PacketDebugFormatter.HexPreview preview = PacketDebugFormatter.formatHexPreview(bytes);
        PacketDebugFormatter.TextSelectionRange hexSelection = preview.selectionForHexBytes(14, 20);
        PacketDebugFormatter.TextSelectionRange asciiSelection = preview.selectionForAsciiBytes(14, 20);

        assertNotNull(hexSelection);
        assertNotNull(asciiSelection);

        String selectedHex = preview.hexText().substring(hexSelection.startChar(), hexSelection.endChar());
        String selectedAscii = preview.asciiText().substring(asciiSelection.startChar(), asciiSelection.endChar());

        assertTrue(preview.addressText().contains("0010"));
        assertTrue(selectedHex.contains("\n"));
        assertTrue(selectedAscii.contains("\n"));
        assertTrue(!selectedHex.contains("0010"));
        assertTrue(!selectedAscii.contains("0010"));
    }

    @Test
    void packetExportTextContainsHexAndTree() {
        PacketLogEntry entry = packetLogEntry("hello mesh");

        String exported = PacketDebugFormatter.exportPacketAsText(entry);

        assertTrue(exported.contains("Тип пакета: TEXT_MESSAGE_APP"));
        assertTrue(exported.contains("От: !12345678"));
        assertTrue(exported.contains("Кому: Вещание (!ffffffff)"));
        assertTrue(exported.contains("HEX"));
        assertTrue(exported.contains("Иерархия"));
        assertTrue(exported.contains("payload:"));
    }

    @Test
    void packetEndpointsUseNodeNameAndStandardNodeId() {
        DeviceState deviceState = new DeviceState();
        NodeData fromNode = deviceState.getOrCreateNode(0x1DC26363);
        fromNode.setLongName("Тестер");

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x1DC26363)
                .setTo(0xFFFFFFFF)
                .setId(777)
                .build();

        PacketDebugFormatter.PacketEndpoints endpoints =
                PacketDebugFormatter.resolvePacketEndpoints(packet.toByteArray(), deviceState);

        assertEquals("Тестер (!1dc26363)", endpoints.fromNode());
        assertEquals("Вещание (!ffffffff)", endpoints.toNode());
    }

    @Test
    void packetExportJsonContainsMetadataAndTree() {
        PacketLogEntry entry = packetLogEntry("hello json");

        String exported = PacketDebugFormatter.exportPacketAsJson(entry);
        JsonObject root = JsonParser.parseString(exported).getAsJsonObject();

        assertEquals(0x12345678, root.get("from").getAsInt());
        assertEquals(0xFFFFFFFFL, root.get("to").getAsLong());
        assertEquals(777, root.get("id").getAsInt());
        assertTrue(root.has("decoded"));
        assertEquals("TEXT_MESSAGE_APP", root.getAsJsonObject("decoded").get("portnum").getAsString());
        assertEquals("aGVsbG8ganNvbg==", root.getAsJsonObject("decoded").get("payload").getAsString());
        assertTrue(!root.has("capturedAtText"));
        assertTrue(!root.has("tree"));
        assertTrue(!root.has("packetHexView"));
    }

    @Test
    void packetCollectionExportJsonContainsFiltersDecodedPayloadAndHierarchy() {
        PacketLogEntry entry = packetLogEntry("bulk hello");

        String exported = PacketDebugFormatter.exportPacketsAsJson(
                List.of(entry),
                new PacketDebugFormatter.PacketCollectionExportMetadata(
                        1_710_000_000_000L,
                        "Исходящие",
                        "TEXT_MESSAGE_APP",
                        "bulk",
                        "11.11.2024 09:00",
                        "11.11.2024 10:00"
                ),
                ignored -> null
        );

        JsonObject root = JsonParser.parseString(exported).getAsJsonObject();
        assertEquals(1, root.get("packetCount").getAsInt());
        assertEquals("Исходящие", root.getAsJsonObject("filters").get("route").getAsString());
        assertEquals("TEXT_MESSAGE_APP", root.getAsJsonObject("filters").get("packetType").getAsString());
        assertEquals("bulk", root.getAsJsonObject("filters").get("searchText").getAsString());

        JsonObject packet = root.getAsJsonArray("packets").get(0).getAsJsonObject();
        assertEquals("Входящий / LoRa", packet.getAsJsonObject("logEntry").get("routeText").getAsString());
        assertEquals(
                "bulk hello",
                packet.getAsJsonObject("meshPacket")
                        .getAsJsonObject("decoded")
                        .get("decodedPayload")
                        .getAsString()
        );
        assertEquals("MeshPacket", packet.getAsJsonObject("hierarchy").get("label").getAsString());
        assertTrue(packet.getAsJsonObject("hierarchy").getAsJsonArray("children").size() > 0);
    }

    @Test
    void packetCollectionExportCsvFlattensDecodedPayloadColumns() {
        PacketLogEntry textEntry = packetLogEntry("csv hello");
        PacketLogEntry positionEntry = positionPacketLogEntry();

        String exported = PacketDebugFormatter.exportPacketsAsCsv(List.of(textEntry, positionEntry), ignored -> null);
        String[] lines = exported.split("\n");

        assertEquals(3, lines.length);
        assertTrue(lines[0].contains("decoded_payload_text"));
        assertTrue(lines[0].contains("decoded_position_latitude"));
        assertTrue(!lines[0].contains("mesh_packet_json"));
        assertTrue(!lines[0].contains("hierarchy_json"));

        Map<String, String> textRow = csvRow(lines[0], lines[1]);
        Map<String, String> positionRow = csvRow(lines[0], lines[2]);

        assertEquals("TEXT_MESSAGE_APP", textRow.get("decoded_portnum"));
        assertEquals("csv hello", textRow.get("decoded_payload_text"));
        assertEquals("POSITION_APP", positionRow.get("decoded_portnum"));
        assertEquals("55.7558000", positionRow.get("decoded_position_latitude"));
        assertEquals("37.6173000", positionRow.get("decoded_position_longitude"));
        assertEquals("156", positionRow.get("decoded_position_altitude"));
    }

    private static PacketLogEntry packetLogEntry(String text) {
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x12345678)
                .setTo(0xFFFFFFFF)
                .setId(777)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFromUtf8(text))
                        .build())
                .build();

        PacketLogEntry entry = new PacketLogEntry(
                "!12345678",
                1_710_000_000_000L,
                PacketLogEntry.Direction.INCOMING,
                "TEXT_MESSAGE_APP",
                "TRANSPORT_LORA",
                "Peer (305419896)",
                "Вещание (4294967295)",
                "\"" + text + "\"",
                packet.toByteArray()
        );
        entry.setId(42);
        return entry;
    }

    private static PacketLogEntry positionPacketLogEntry() {
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x12345678)
                .setTo(0xFFFFFFFF)
                .setId(778)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.POSITION_APP)
                        .setPayload(MeshProtos.Position.newBuilder()
                                .setLatitudeI(557558000)
                                .setLongitudeI(376173000)
                                .setAltitude(156)
                                .build()
                                .toByteString())
                        .build())
                .build();

        PacketLogEntry entry = new PacketLogEntry(
                "!12345678",
                1_710_000_001_000L,
                PacketLogEntry.Direction.OUTGOING,
                "POSITION_APP",
                "TRANSPORT_LORA",
                "Peer (305419896)",
                "Вещание (4294967295)",
                "lat=55.7558000, lon=37.6173000, alt=156",
                packet.toByteArray()
        );
        entry.setId(43);
        return entry;
    }

    private static Map<String, String> csvRow(String headerLine, String rowLine) {
        List<String> headers = parseCsvLine(headerLine);
        List<String> values = parseCsvLine(rowLine);
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            row.put(headers.get(i), i < values.size() ? values.get(i) : "");
        }
        return row;
    }

    private static List<String> parseCsvLine(String line) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                values.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        values.add(cell.toString());
        return values;
    }

    private static TreeItem<PacketTreeNode> findByPrefix(TreeItem<PacketTreeNode> root, String prefix) {
        if (root == null || root.getValue() == null) {
            return null;
        }
        if (root.getValue().getLabel().startsWith(prefix)) {
            return root;
        }
        for (TreeItem<PacketTreeNode> child : root.getChildren()) {
            TreeItem<PacketTreeNode> match = findByPrefix(child, prefix);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static String dumpTree(TreeItem<PacketTreeNode> root) {
        StringBuilder sb = new StringBuilder();
        appendLabels(sb, root, 0);
        return sb.toString();
    }

    private static void appendLabels(StringBuilder sb, TreeItem<PacketTreeNode> item, int depth) {
        if (item == null || item.getValue() == null) {
            return;
        }
        sb.append("  ".repeat(Math.max(0, depth))).append(item.getValue().getLabel()).append('\n');
        for (TreeItem<PacketTreeNode> child : item.getChildren()) {
            appendLabels(sb, child, depth + 1);
        }
    }
}
