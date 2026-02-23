package com.meshtastic.client.model;

/**
 * Сообщение Meshtastic-чата (канальное или личное).
 * <p>
 * Содержит текст, адресацию (отправитель, получатель, канал),
 * статус доставки и метаданные маршрутизации (hop start/limit).
 * Исходящие сообщения создаются через {@link com.meshtastic.client.service.MessageService},
 * входящие — через {@link com.meshtastic.client.service.MessageListenerService}.
 * <p>
 * Поле {@code status} объявлено как {@code volatile} для безопасного
 * обновления из потока TCP-reader с последующим чтением из UI-потока.
 */
public class MeshMessage {

    /**
     * Статус доставки сообщения.
     * <ul>
     *   <li>{@code SENDING} — отправлено на радио, ожидает ACK</li>
     *   <li>{@code DELIVERED} — получено подтверждение (ACK) от получателя</li>
     *   <li>{@code FAILED} — получен NAK или таймаут доставки</li>
     * </ul>
     */
    public enum DeliveryStatus { SENDING, DELIVERED, FAILED }

    private final int fromNum;
    private final int toNum;
    private final int channelIndex;
    private final String text;
    private final long timestamp;
    private final boolean outgoing;

    private volatile DeliveryStatus status;
    private int packetId;
    private String errorReason;
    private int replyId;
    private String replyText;
    private int hopStart;
    private int hopLimit;
    private String senderName;
    private boolean systemMessage;
    private long dbId;

    /**
     * Создаёт сообщение с основными полями.
     *
     * @param fromNum      номер ноды-отправителя
     * @param toNum        номер ноды-получателя ({@code 0xFFFFFFFF} для broadcast)
     * @param channelIndex индекс канала (0 для primary)
     * @param text         текст сообщения (UTF-8)
     * @param timestamp    время в секундах с начала эпохи (epoch seconds)
     * @param outgoing     {@code true} если сообщение отправлено с этого устройства
     */
    public MeshMessage(int fromNum, int toNum, int channelIndex, String text, long timestamp, boolean outgoing) {
        this.fromNum = fromNum;
        this.toNum = toNum;
        this.channelIndex = channelIndex;
        this.text = text;
        this.timestamp = timestamp;
        this.outgoing = outgoing;
    }

    public int getFromNum() { return fromNum; }
    public int getToNum() { return toNum; }
    public int getChannelIndex() { return channelIndex; }
    public String getText() { return text; }
    public long getTimestamp() { return timestamp; }
    public boolean isOutgoing() { return outgoing; }

    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }

    public int getPacketId() { return packetId; }
    public void setPacketId(int packetId) { this.packetId = packetId; }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }

    public int getReplyId() { return replyId; }
    public void setReplyId(int replyId) { this.replyId = replyId; }

    public String getReplyText() { return replyText; }
    public void setReplyText(String replyText) { this.replyText = replyText; }

    public int getHopStart() { return hopStart; }
    public void setHopStart(int hopStart) { this.hopStart = hopStart; }

    public int getHopLimit() { return hopLimit; }
    public void setHopLimit(int hopLimit) { this.hopLimit = hopLimit; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public boolean isSystemMessage() { return systemMessage; }
    public void setSystemMessage(boolean systemMessage) { this.systemMessage = systemMessage; }

    public long getDbId() { return dbId; }
    public void setDbId(long dbId) { this.dbId = dbId; }

    /**
     * Вычисляет количество хопов, которое прошло сообщение.
     * Рассчитывается как {@code hopStart - hopLimit}. Если {@code hopStart}
     * не задан (0), возвращает 0.
     *
     * @return количество хопов или 0 если данные недоступны
     */
    public int getHopsTraveled() {
        return hopStart > 0 ? hopStart - hopLimit : 0;
    }

}
