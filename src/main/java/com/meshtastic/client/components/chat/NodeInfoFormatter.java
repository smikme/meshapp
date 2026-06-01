package com.meshtastic.client.components.chat;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.NodeData;

/**
 * Formats node details as chat system-message text.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class NodeInfoFormatter {

    private NodeInfoFormatter() {}

    /**
     * Formats node details for a system message.
     *
     * @param node node data
     * @return multiline text with icons and separators
     */
    public static String format(NodeData node) {
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.t("chat.nodeInfo.title")).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

        // Name and id.
        if (node.getLongName() != null && !node.getLongName().isEmpty()) {
            sb.append("\uD83D\uDC64 ").append(node.getLongName());
            if (node.getShortName() != null && !node.getShortName().isEmpty()) {
                sb.append(" (").append(node.getShortName()).append(")");
            }
            sb.append("\n");
        }
        sb.append("\uD83C\uDD94 ").append(node.getNodeId()).append("\n");

        // Role and hardware model.
        String role = NodeData.translateRole(node.getRole());
        if (role != null) {
            sb.append("\uD83C\uDFAD ").append(role).append("\n");
        }

        if (node.getHwModel() != null && !node.getHwModel().isEmpty()) {
            sb.append("\uD83D\uDCDF ").append(node.getHwModel()).append("\n");
        }

        // Battery.
        if (node.getBatteryLevel() > 0) {
            String battIcon = node.getBatteryLevel() > 50
                    ? "\uD83D\uDD0B" : "\uD83E\uDEAB";
            sb.append(battIcon).append(" ")
                    .append(node.getBatteryLevel()).append("%");
            if (node.getVoltage() > 0) {
                sb.append(String.format(I18n.locale(),
                        " (%.1f%s)", node.getVoltage(), I18n.t("node.unit.volt")));
            }
            sb.append("\n");
        }

        // Signal and hop count.
        if (node.getSnr() != 0) {
            sb.append("\uD83D\uDCE1 ")
                    .append(I18n.t("node.list.snr",
                            String.format(I18n.locale(), "%.1f", node.getSnr()),
                            I18n.t("node.unit.db")))
                    .append("\n");
        }
        if (node.getHopsAway() > 0) {
            sb.append(I18n.t("chat.nodeInfo.hops", node.getHopsAway())).append("\n");
        }

        // Coordinates and altitude.
        if (node.getLatitude() != 0 || node.getLongitude() != 0) {
            sb.append(String.format("\uD83D\uDCCD %.4f, %.4f\n",
                    node.getLatitude(), node.getLongitude()));
        }
        if (node.getAltitude() != 0) {
            sb.append(I18n.t("chat.nodeInfo.altitude", node.getAltitude())).append("\n");
        }

        // Public key.
        if (node.getPublicKey() != null && node.getPublicKey().length > 0) {
            StringBuilder hex = new StringBuilder();
            for (byte b : node.getPublicKey()) {
                hex.append(String.format("%02x", b));
            }
            sb.append("\uD83D\uDD10 ").append(hex).append("\n");
        }

        // Last-heard time and uptime.
        String lastHeard = NodeData.formatTime(node.getLastHeard());
        if (!lastHeard.isEmpty()) {
            sb.append("\uD83D\uDD53 ").append(lastHeard).append("\n");
        }

        if (node.getUptimeSeconds() > 0) {
            long h = node.getUptimeSeconds() / 3600;
            long m = node.getUptimeSeconds() % 3600 / 60;
            sb.append(I18n.t("chat.nodeInfo.uptime", h, m)).append("\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━");
        return sb.toString();
    }
}
