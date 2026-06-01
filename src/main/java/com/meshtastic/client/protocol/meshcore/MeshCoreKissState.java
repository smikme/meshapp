package com.meshtastic.client.protocol.meshcore;

/**
 * Runtime state assembled from MeshCore KISS {@code SetHardware} responses.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
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
     * Reports whether the runtime has received at least one valid device response.
 *
     * @return {@code true} once the handshake is considered successful
     */
    public boolean isReady() {
        return ready;
    }

    void setReady(boolean ready) {
        this.ready = ready;
    }

    /**
     * Returns the MeshCore device name.
 *
     * @return device name, or {@code null} before it is received
     */
    public String getDeviceName() {
        return deviceName;
    }

    void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    /**
     * Returns the identity public key as HEX.
 *
     * @return identity HEX string, or {@code null}
     */
    public String getIdentityHex() {
        return identityHex;
    }

    void setIdentityHex(String identityHex) {
        this.identityHex = identityHex;
        this.ownerId = MeshCoreCompanionFrames.nodeIdFromPublicKeyHex(identityHex);
    }

    /**
     * Returns the stable owner id used by higher-level services.
 *
     * @return short MeshCore node id in the {@code mc:<12 hex>} form, or {@code null}
     */
    public String getOwnerId() {
        return ownerId;
    }

    /**
     * Returns the numeric firmware version from KISS metadata.
 *
     * @return firmware version, or {@code null}
     */
    public Integer getFirmwareVersion() {
        return firmwareVersion;
    }

    void setFirmwareVersion(Integer firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }

    /**
     * Returns the current transmit power.
 *
     * @return TX power in dBm, or {@code null}
     */
    public Integer getTxPowerDbm() {
        return txPowerDbm;
    }

    void setTxPowerDbm(Integer txPowerDbm) {
        this.txPowerDbm = txPowerDbm;
    }

    /**
     * Returns the battery voltage.
 *
     * @return voltage in millivolts, or {@code null}
     */
    public Integer getBatteryMillivolts() {
        return batteryMillivolts;
    }

    void setBatteryMillivolts(Integer batteryMillivolts) {
        this.batteryMillivolts = batteryMillivolts;
    }

    /**
     * Returns radio parameters received through MeshCore KISS.
 *
     * @return radio parameters, or {@code null}
     */
    public RadioParameters getRadioParameters() {
        return radioParameters;
    }

    void setRadioParameters(RadioParameters radioParameters) {
        this.radioParameters = radioParameters;
    }

    /**
     * Returns packet counters.
 *
     * @return statistics, or {@code null}
     */
    public Stats getStats() {
        return stats;
    }

    void setStats(Stats stats) {
        this.stats = stats;
    }

    /**
     * Returns RSSI for the last received packet.
 *
     * @return RSSI in dBm, or {@code null}
     */
    public Integer getLastRxRssiDbm() {
        return lastRxRssiDbm;
    }

    /**
     * Returns SNR for the last received packet.
 *
     * @return SNR in dB, or {@code null}
     */
    public Float getLastRxSnrDb() {
        return lastRxSnrDb;
    }

    void setLastRxMeta(float snrDb, int rssiDbm) {
        this.lastRxSnrDb = snrDb;
        this.lastRxRssiDbm = rssiDbm;
    }

    /**
     * Returns the last transmit result when the device reported one.
 *
     * @return {@code true}/{@code false}, or {@code null} when no status is available yet
     */
    public Boolean getLastTxSuccess() {
        return lastTxSuccess;
    }

    void setLastTxSuccess(Boolean lastTxSuccess) {
        this.lastTxSuccess = lastTxSuccess;
    }

    /**
     * Returns the latest MeshCore KISS error.
 *
     * @return error text, or {@code null}
     */
    public String getLastError() {
        return lastError;
    }

    void setLastError(String lastError) {
        this.lastError = lastError;
    }

    /**
     * LoRa radio parameters returned by the MeshCore KISS modem.
 *
     * @param frequencyHz operating frequency in Hz
     * @param bandwidthHz bandwidth in Hz
     * @param spreadingFactor LoRa spreading factor
     * @param codingRate LoRa coding rate
     */
    public record RadioParameters(long frequencyHz, long bandwidthHz, int spreadingFactor, int codingRate) {
    }

    /**
     * Packet-statistics counters from the MeshCore KISS modem.
 *
     * @param receivedPackets received packet count
     * @param transmittedPackets transmitted packet count
     * @param receiveErrors receive error count
     */
    public record Stats(long receivedPackets, long transmittedPackets, long receiveErrors) {
    }
}
