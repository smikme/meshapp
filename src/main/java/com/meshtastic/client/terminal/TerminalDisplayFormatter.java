package com.meshtastic.client.terminal;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.LogEntry;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.utils.NodeUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Formatting helpers for the terminal UI.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class TerminalDisplayFormatter {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private TerminalDisplayFormatter() {
    }

    static String formatTime(Instant instant) {
        return TIME_FMT.format(instant);
    }

    static List<String> messageBlockLines(DeviceState state, MeshMessage msg, int width) {
        if (msg == null) {
            return List.of("");
        }
        List<String> lines = new ArrayList<>();
        String direction = msg.isOutgoing() ? "me" : displayIncomingSenderName(state, msg);
        String status = msg.getStatus() != null && msg.isOutgoing() ? " [" + msg.getStatus() + "]" : "";
        String hops = messageHopsLabel(msg);
        String prefix = TIME_FMT.format(Instant.ofEpochSecond(Math.max(0, msg.getTimestamp())))
                + " " + direction + status + hops + ": ";
        lines.addAll(TerminalText.wrap(prefix + safe(msg.getText()), width));
        if (msg.getReplyId() != 0 && msg.getReplyText() != null && !msg.getReplyText().isBlank()) {
            lines.addAll(TerminalText.wrap("  > " + previewText(msg.getReplyText(), 120), width));
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    /**
     * Formats the terminal routing indicator for a message.
     * <p>
     * The value matches the GUI semantics: valid hop data is rendered as the
     * traveled hop count ({@code hopStart - hopLimit}); unknown or inconsistent
     * hop data is omitted.
     *
     * @param msg message to inspect, may be {@code null}
     * @return compact terminal label with the rabbit marker and hop count, or an empty string
     */
    static String messageHopsLabel(MeshMessage msg) {
        return msg != null && msg.hasValidHopData()
                ? " [\uD83D\uDC07" + msg.getHopsTraveled() + "]"
                : "";
    }

    /**
     * Resolves the sender label for an incoming terminal message using GUI-like
     * priority: long node name, cached sender name, short node name, then node id.
     *
     * @param state current device state, may be {@code null}
     * @param msg incoming message, may be {@code null}
     * @return sender label suitable for terminal display
     */
    static String displayIncomingSenderName(DeviceState state, MeshMessage msg) {
        if (msg == null) {
            return "";
        }
        NodeData senderNode = NodeUtils.resolveNode(state, msg.getFromNodeId());
        return firstNonBlank(
                senderNode != null ? senderNode.getLongName() : null,
                msg.getSenderName(),
                senderNode != null ? senderNode.getShortName() : null,
                msg.getFromNodeId()
        );
    }

    /**
     * Resolves a direct-chat peer label without exposing the node id when a long
     * or short node name is available.
     *
     * @param state current device state, may be {@code null}
     * @param peerNodeId peer node id
     * @return display label for the direct-chat list and status line
     */
    static String displayDirectChatLabel(DeviceState state, String peerNodeId) {
        NodeData node = NodeUtils.resolveNode(state, peerNodeId);
        return firstNonBlank(
                node != null ? node.getLongName() : null,
                node != null ? node.getShortName() : null,
                node != null ? node.getNodeId() : null,
                peerNodeId
        );
    }

    /**
     * Checks whether a node has a human-readable long or short name.
     *
     * @param state current device state, may be {@code null}
     * @param nodeId node id to resolve
     * @return {@code true} when the terminal can hide the raw node id
     */
    static boolean hasNodeDisplayName(DeviceState state, String nodeId) {
        NodeData node = NodeUtils.resolveNode(state, nodeId);
        return node != null
                && ((node.getLongName() != null && !node.getLongName().isBlank())
                || (node.getShortName() != null && !node.getShortName().isBlank()));
    }

    static String nodeLabel(NodeData node) {
        if (node == null) {
            return "?";
        }
        if (node.getLongName() != null && !node.getLongName().isBlank()) {
            return node.getLongName();
        }
        if (node.getShortName() != null && !node.getShortName().isBlank()) {
            return node.getShortName();
        }
        return node.getNodeId() != null ? node.getNodeId() : String.format("!%08x", node.getNodeNum());
    }

    static String nodeSummary(DeviceState state) {
        if (state == null) {
            return "no active state";
        }
        return state.getNodeDb().size() + " nodes";
    }

    static String connectionSummary(ConnectionEntry entry) {
        String status = entry.isConnected() ? "connected" : entry.isReconnecting() ? "reconnecting" : "idle";
        return status + " | " + entry.getEffectiveProtocol() + " | " + switch (entry.getEffectiveType()) {
            case TCP -> "tcp " + safe(entry.getHost()) + ":" + entry.getPort();
            case SERIAL -> "serial " + safe(entry.getPortName()) + " @" + entry.getBaudRate();
            case BLE -> "ble " + safe(entry.getBleAddress());
            case REMOTE_RPC -> "rpc " + safe(entry.getHost()) + ":" + entry.getPort();
        };
    }

    static List<String> logbackEntryLines(LogEntry entry, int width) {
        if (entry == null) {
            return List.of();
        }
        String prefix = "[" + safe(entry.getTime()) + "] " + safe(entry.getLevel()) + ": ";
        String fullMessage = entry.getFullMessage();
        if (fullMessage == null || fullMessage.isBlank()) {
            fullMessage = entry.getMessage();
        }
        String normalized = fullMessage != null ? fullMessage.replace("\r\n", "\n").replace('\r', '\n') : "";
        String[] rawLines = normalized.isEmpty() ? new String[]{""} : normalized.split("\n", -1);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < rawLines.length; i++) {
            String line = (i == 0 ? prefix : "    ") + rawLines[i];
            lines.addAll(TerminalText.wrap(line, width));
        }
        return lines;
    }

    static long lastMessageTime(MeshMessage message) {
        return message != null ? message.getTimestamp() : 0;
    }

    static String previewText(String value, int maxChars) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        if (text.length() <= maxChars) {
            return safe(text);
        }
        return safe(text.substring(0, Math.max(0, maxChars - 3)) + "...");
    }

    static String safe(String value) {
        return TerminalText.render(value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
