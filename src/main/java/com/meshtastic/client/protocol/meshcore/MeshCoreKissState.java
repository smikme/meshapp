package com.meshtastic.client.protocol.meshcore;

/**
 * Runtime-состояние, собранное из MeshCore KISS {@code SetHardware} responses.
 */
public class MeshCoreKissState {

    private volatile boolean ready;
    private volatile String deviceName;
    private volatile String identityHex;
    private volatile String ownerId;
    private volatile Integer firmwareVersion;
    private volatile Integer txPowerDbm;
    private volatile Integer batteryMillivolts;
    private volatile RadioParameters radioParameters;
    private volatile Stats stats;
    private volatile Integer lastRxRssiDbm;
    private volatile Float lastRxSnrDb;
    private volatile Boolean lastTxSuccess;
    private volatile String lastError;

    /**
     * Проверяет, получил ли runtime хотя бы один валидный response от устройства.
     *
     * @return {@code true}, если handshake считается успешным
     */
    public boolean isReady() {
        return ready;
    }

    void setReady(boolean ready) {
        this.ready = ready;
    }

    /**
     * Возвращает имя MeshCore-устройства.
     *
     * @return имя устройства или {@code null}, если оно ещё не получено
     */
    public String getDeviceName() {
        return deviceName;
    }

    void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    /**
     * Возвращает identity public key в HEX.
     *
     * @return HEX-строка identity или {@code null}
     */
    public String getIdentityHex() {
        return identityHex;
    }

    void setIdentityHex(String identityHex) {
        this.identityHex = identityHex;
        this.ownerId = MeshCoreCompanionFrames.nodeIdFromPublicKeyHex(identityHex);
    }

    /**
     * Возвращает стабильный owner id для сервисов верхнего уровня.
     *
     * @return короткий MeshCore node id вида {@code mc:<12 hex>} или {@code null}
     */
    public String getOwnerId() {
        return ownerId;
    }

    /**
     * Возвращает числовую версию firmware, полученную из KISS metadata.
     *
     * @return версия firmware или {@code null}
     */
    public Integer getFirmwareVersion() {
        return firmwareVersion;
    }

    void setFirmwareVersion(Integer firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }

    /**
     * Возвращает текущую мощность передачи.
     *
     * @return TX power в dBm или {@code null}
     */
    public Integer getTxPowerDbm() {
        return txPowerDbm;
    }

    void setTxPowerDbm(Integer txPowerDbm) {
        this.txPowerDbm = txPowerDbm;
    }

    /**
     * Возвращает напряжение батареи.
     *
     * @return напряжение в millivolts или {@code null}
     */
    public Integer getBatteryMillivolts() {
        return batteryMillivolts;
    }

    void setBatteryMillivolts(Integer batteryMillivolts) {
        this.batteryMillivolts = batteryMillivolts;
    }

    /**
     * Возвращает параметры radio, полученные через MeshCore KISS.
     *
     * @return параметры radio или {@code null}
     */
    public RadioParameters getRadioParameters() {
        return radioParameters;
    }

    void setRadioParameters(RadioParameters radioParameters) {
        this.radioParameters = radioParameters;
    }

    /**
     * Возвращает счётчики packets.
     *
     * @return statistics или {@code null}
     */
    public Stats getStats() {
        return stats;
    }

    void setStats(Stats stats) {
        this.stats = stats;
    }

    /**
     * Возвращает RSSI последнего принятого packet-а.
     *
     * @return RSSI в dBm или {@code null}
     */
    public Integer getLastRxRssiDbm() {
        return lastRxRssiDbm;
    }

    /**
     * Возвращает SNR последнего принятого packet-а.
     *
     * @return SNR в dB или {@code null}
     */
    public Float getLastRxSnrDb() {
        return lastRxSnrDb;
    }

    void setLastRxMeta(float snrDb, int rssiDbm) {
        this.lastRxSnrDb = snrDb;
        this.lastRxRssiDbm = rssiDbm;
    }

    /**
     * Возвращает результат последней передачи, если устройство его сообщило.
     *
     * @return {@code true}/{@code false} или {@code null}, если статуса ещё нет
     */
    public Boolean getLastTxSuccess() {
        return lastTxSuccess;
    }

    void setLastTxSuccess(Boolean lastTxSuccess) {
        this.lastTxSuccess = lastTxSuccess;
    }

    /**
     * Возвращает последнюю ошибку MeshCore KISS.
     *
     * @return текст ошибки или {@code null}
     */
    public String getLastError() {
        return lastError;
    }

    void setLastError(String lastError) {
        this.lastError = lastError;
    }

    /**
     * Параметры LoRa radio, возвращаемые MeshCore KISS modem.
     *
     * @param frequencyHz рабочая частота в Hz
     * @param bandwidthHz bandwidth в Hz
     * @param spreadingFactor LoRa spreading factor
     * @param codingRate LoRa coding rate
     */
    public record RadioParameters(long frequencyHz, long bandwidthHz, int spreadingFactor, int codingRate) {
    }

    /**
     * Счётчики packet statistics MeshCore KISS modem.
     *
     * @param receivedPackets количество принятых packets
     * @param transmittedPackets количество отправленных packets
     * @param receiveErrors количество ошибок приёма
     */
    public record Stats(long receivedPackets, long transmittedPackets, long receiveErrors) {
    }
}
