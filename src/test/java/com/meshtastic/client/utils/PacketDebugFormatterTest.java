package com.meshtastic.client.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.model.PacketTreeNode;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketDebugFormatterTest {

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
        assertTrue(exported.contains("HEX"));
        assertTrue(exported.contains("Иерархия"));
        assertTrue(exported.contains("payload:"));
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
                "Peer (305419896)",
                "Вещание (4294967295)",
                "\"" + text + "\"",
                packet.toByteArray()
        );
        entry.setId(42);
        return entry;
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
