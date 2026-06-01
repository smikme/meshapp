package com.meshtastic.client.model;

import com.meshtastic.client.i18n.I18n;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * Одна запись журнала LoRa-пакетов для окна мониторинга.
 * Экземпляр immutable по всем данным пакета, кроме {@link #id}, который
 * заполняется после сохранения записи в БД.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class PacketLogEntry {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS");
    private static final int PAYLOAD_PREVIEW_LIMIT = 180;

    /**
     * Направление пакета относительно локального mesh-узла и transport-канала клиента.
     */
    public enum Direction {
        INCOMING,
        OUTGOING,
        INTERNAL
    }

    private long id;
    private final String ownerNodeId;
    private final long capturedAt;
    private final Direction direction;
    private final String packetType;
    private final String transportMechanism;
    private final String fromNode;
    private final String toNode;
    private final String payloadText;
    private final byte[] packetBytes;

    /**
     * Создаёт журналируемую запись пакета.
     *
     * @param ownerNodeId идентификатор локального owner-узла, к которому относится запись
     * @param capturedAt  время захвата в миллисекундах Unix epoch
     * @param direction   направление пакета
     * @param packetType  тип пакета или portnum в UI-представлении
     * @param transportMechanism transport_mechanism в UI-представлении
     * @param fromNode    отправитель в UI-представлении
     * @param toNode      получатель в UI-представлении
     * @param payloadText payload в текстовом виде для таблицы
     * @param packetBytes сериализованный MeshPacket; массив копируется
     */
    public PacketLogEntry(String ownerNodeId,
                          long capturedAt,
                          Direction direction,
                          String packetType,
                          String transportMechanism,
                          String fromNode,
                          String toNode,
                          String payloadText,
                          byte[] packetBytes) {
        this.ownerNodeId = ownerNodeId != null ? ownerNodeId : "";
        this.capturedAt = capturedAt;
        this.direction = direction;
        this.packetType = packetType;
        this.transportMechanism = transportMechanism != null ? transportMechanism : "";
        this.fromNode = fromNode;
        this.toNode = toNode;
        this.payloadText = payloadText;
        this.packetBytes = packetBytes != null ? Arrays.copyOf(packetBytes, packetBytes.length) : new byte[0];
    }

    /**
     * @return идентификатор строки в БД; присваивается после insert
     */
    public long getId() {
        return id;
    }

    /**
     * Устанавливает идентификатор, полученный от БД.
     * Контракт: должен вызываться только сразу после успешного сохранения записи.
     *
     * @param id идентификатор строки в БД
     */
    public void setId(long id) {
        this.id = id;
    }

    public String getOwnerNodeId() {
        return ownerNodeId;
    }

    public long getCapturedAt() {
        return capturedAt;
    }

    public String getCapturedAtText() {
        return Instant.ofEpochMilli(capturedAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(TIME_FORMAT);
    }

    public Direction getDirection() {
        return direction;
    }

    public String getDirectionText() {
        return switch (direction) {
            case INCOMING -> I18n.t("packetMonitor.direction.incoming");
            case OUTGOING -> I18n.t("packetMonitor.direction.outgoing");
            case INTERNAL -> I18n.t("packetMonitor.direction.internal");
        };
    }

    public String getPacketType() {
        return packetType;
    }

    public String getTransportMechanism() {
        return transportMechanism;
    }

    public String getTransportText() {
        return formatTransportMechanism(transportMechanism);
    }

    public String getRouteText() {
        if (direction == Direction.OUTGOING && (transportMechanism == null || transportMechanism.isBlank())) {
            return I18n.t("packetMonitor.route.noAck");
        }
        return I18n.t("packetMonitor.route.pattern", getDirectionText(), getTransportText());
    }

    public String getFromNode() {
        return fromNode;
    }

    public String getToNode() {
        return toNode;
    }

    public String getPayloadText() {
        return payloadText;
    }

    /**
     * Возвращает сокращённый payload для компактных мест UI.
     * Контракт: не изменяет исходное значение {@link #payloadText}.
     *
     * @return payload целиком или усечённый preview с многоточием
     */
    public String getPayloadPreview() {
        if (payloadText == null || payloadText.isBlank()) {
            return "";
        }
        if (payloadText.length() <= PAYLOAD_PREVIEW_LIMIT) {
            return payloadText;
        }
        return payloadText.substring(0, PAYLOAD_PREVIEW_LIMIT - 3) + "...";
    }

    /**
     * @return копия сериализованных байтов пакета; вызывающий код может безопасно изменять массив
     */
    public byte[] getPacketBytes() {
        return Arrays.copyOf(packetBytes, packetBytes.length);
    }

    public static String formatTransportMechanism(String transportMechanism) {
        if (transportMechanism == null || transportMechanism.isBlank()) {
            return I18n.t("packetMonitor.transport.local");
        }
        return switch (transportMechanism) {
            case "TRANSPORT_LORA" -> "LoRa";
            case "TRANSPORT_LORA_ALT1" -> "LoRa alt 1";
            case "TRANSPORT_LORA_ALT2" -> "LoRa alt 2";
            case "TRANSPORT_LORA_ALT3" -> "LoRa alt 3";
            case "MESHCORE_COMPANION" -> "MeshCore Companion";
            case "TRANSPORT_MQTT" -> "MQTT";
            case "TRANSPORT_MULTICAST_UDP" -> "Multicast UDP";
            case "TRANSPORT_API" -> "API";
            case "TRANSPORT_INTERNAL" -> I18n.t("packetMonitor.transport.local");
            default -> transportMechanism;
        };
    }
}
