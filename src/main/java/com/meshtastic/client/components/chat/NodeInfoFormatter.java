package com.meshtastic.client.components.chat;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.NodeData;

/**
 * Форматирование информации о ноде в текст системного сообщения чата.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class NodeInfoFormatter {

    private NodeInfoFormatter() {}

    /**
     * Форматировать информацию о ноде в текст для системного сообщения.
     *
     * @param node данные ноды
     * @return многострочный текст с иконками и разделителями
     */
    public static String format(NodeData node) {
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.t("chat.nodeInfo.title")).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

        // Имя и ID
        if (node.getLongName() != null && !node.getLongName().isEmpty()) {
            sb.append("\uD83D\uDC64 ").append(node.getLongName());
            if (node.getShortName() != null && !node.getShortName().isEmpty()) {
                sb.append(" (").append(node.getShortName()).append(")");
            }
            sb.append("\n");
        }
        sb.append("\uD83C\uDD94 ").append(node.getNodeId()).append("\n");

        // Роль и модель
        String role = NodeData.translateRole(node.getRole());
        if (role != null) {
            sb.append("\uD83C\uDFAD ").append(role).append("\n");
        }

        if (node.getHwModel() != null && !node.getHwModel().isEmpty()) {
            sb.append("\uD83D\uDCDF ").append(node.getHwModel()).append("\n");
        }

        // Батарея
        if (node.getBatteryLevel() > 0) {
            String battIcon = node.getBatteryLevel() > 50
                    ? "\uD83D\uDD0B" : "\uD83E\uDEAB";
            sb.append(battIcon).append(" ")
                    .append(node.getBatteryLevel()).append("%");
            if (node.getVoltage() > 0) {
                sb.append(String.format(" (%.1fV)", node.getVoltage()));
            }
            sb.append("\n");
        }

        // Сигнал и хопы
        if (node.getSnr() != 0) {
            sb.append(String.format("\uD83D\uDCE1 SNR: %.1f dB\n", node.getSnr()));
        }
        if (node.getHopsAway() > 0) {
            sb.append(I18n.t("chat.nodeInfo.hops", node.getHopsAway())).append("\n");
        }

        // Координаты и высота
        if (node.getLatitude() != 0 || node.getLongitude() != 0) {
            sb.append(String.format("\uD83D\uDCCD %.4f, %.4f\n",
                    node.getLatitude(), node.getLongitude()));
        }
        if (node.getAltitude() != 0) {
            sb.append(I18n.t("chat.nodeInfo.altitude", node.getAltitude())).append("\n");
        }

        // Публичный ключ
        if (node.getPublicKey() != null && node.getPublicKey().length > 0) {
            StringBuilder hex = new StringBuilder();
            for (byte b : node.getPublicKey()) {
                hex.append(String.format("%02x", b));
            }
            sb.append("\uD83D\uDD10 ").append(hex).append("\n");
        }

        // Время и аптайм
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
