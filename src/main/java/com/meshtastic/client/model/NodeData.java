package com.meshtastic.client.model;

import com.meshtastic.client.i18n.I18n;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Mutable data object for one Meshtastic node.
 * <p>
 * Contains identity, position, device metrics, environment metrics, and metadata
 * such as role, hardware model, and hop distance. Instances are usually created
 * through {@link DeviceState#getOrCreateNode(int)}; {@code nodeId} is generated
 * automatically in the {@code !XXXXXXXX} form.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class NodeData {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm");

    private final int nodeNum;
    private String longName;
    private String shortName;
    private String nodeId;
    private double latitude;
    private double longitude;
    private int altitude;
    private float snr;
    private int lastHeard;
    private int batteryLevel;
    private boolean externallyPowered;
    private float voltage;
    private float channelUtilization;
    private float airUtilTx;
    private long uptimeSeconds;
    private float temperature;
    private float relativeHumidity;
    private float barometricPressure;
    private int hopsAway;
    private boolean hasHopsAway;
    private int channel;
    private String role;
    private String hwModel;
    private byte[] publicKey;
    private Boolean unmessagable;
    private Boolean licensed;

    /**
     * Creates a node with the given numeric id and generated {@code nodeId}.
     *
     * @param nodeNum unique numeric node id
     */
    public NodeData(int nodeNum) {
        this.nodeNum = nodeNum;
        this.nodeId = String.format("!%08x", nodeNum);
    }

    public int getNodeNum() { return nodeNum; }

    public String getLongName() { return longName; }
    public void setLongName(String longName) { this.longName = longName; }

    public String getShortName() { return shortName; }
    public void setShortName(String shortName) { this.shortName = shortName; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public int getAltitude() { return altitude; }
    public void setAltitude(int altitude) { this.altitude = altitude; }

    public float getSnr() { return snr; }
    public void setSnr(float snr) { this.snr = snr; }

    public int getLastHeard() { return lastHeard; }
    public void setLastHeard(int lastHeard) { this.lastHeard = lastHeard; }

    public int getBatteryLevel() { return batteryLevel; }
    public void setBatteryLevel(int batteryLevel) { this.batteryLevel = batteryLevel; }

    public boolean isExternallyPowered() { return externallyPowered; }
    public void setExternallyPowered(boolean externallyPowered) { this.externallyPowered = externallyPowered; }

    public float getVoltage() { return voltage; }
    public void setVoltage(float voltage) { this.voltage = voltage; }

    public float getChannelUtilization() { return channelUtilization; }
    public void setChannelUtilization(float channelUtilization) { this.channelUtilization = channelUtilization; }

    public float getAirUtilTx() { return airUtilTx; }
    public void setAirUtilTx(float airUtilTx) { this.airUtilTx = airUtilTx; }

    public long getUptimeSeconds() { return uptimeSeconds; }
    public void setUptimeSeconds(long uptimeSeconds) { this.uptimeSeconds = uptimeSeconds; }

    public float getTemperature() { return temperature; }
    public void setTemperature(float temperature) { this.temperature = temperature; }

    public float getRelativeHumidity() { return relativeHumidity; }
    public void setRelativeHumidity(float relativeHumidity) { this.relativeHumidity = relativeHumidity; }

    public float getBarometricPressure() { return barometricPressure; }
    public void setBarometricPressure(float barometricPressure) { this.barometricPressure = barometricPressure; }

    public int getHopsAway() { return hopsAway; }
    public void setHopsAway(int hopsAway) {
        this.hopsAway = hopsAway;
        this.hasHopsAway = true;
    }
    public boolean hasHopsAway() { return hasHopsAway; }
    public void clearHopsAway() {
        this.hopsAway = 0;
        this.hasHopsAway = false;
    }
    public boolean isDirectNeighbor() { return hasHopsAway && hopsAway == 0; }

    public int getChannel() { return channel; }
    public void setChannel(int channel) { this.channel = channel; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getHwModel() { return hwModel; }
    public void setHwModel(String hwModel) { this.hwModel = hwModel; }

    public byte[] getPublicKey() { return publicKey; }
    public void setPublicKey(byte[] publicKey) { this.publicKey = publicKey; }

    public Boolean getUnmessagable() { return unmessagable; }
    public void setUnmessagable(Boolean unmessagable) { this.unmessagable = unmessagable; }
    public boolean isUnmessagable() { return Boolean.TRUE.equals(unmessagable); }

    public Boolean getLicensed() { return licensed; }
    public void setLicensed(Boolean licensed) { this.licensed = licensed; }
    public boolean isLicensed() { return Boolean.TRUE.equals(licensed); }

    /**
     * Returns whether the node has at least one non-empty name.
     *
     * @return {@code true} when longName or shortName is set
     */
    public boolean hasName() {
        return (longName != null && !longName.isEmpty())
                || (shortName != null && !shortName.isEmpty());
    }

    /**
     * Formats Unix time in seconds as {@code dd.MM.yy HH:mm}.
     *
     * @param epochSeconds seconds since Unix epoch
     * @return formatted string, or empty string for non-positive values
     */
    public static String formatTime(long epochSeconds) {
        if (epochSeconds <= 0) { return ""; }
        return Instant.ofEpochSecond(epochSeconds)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(TIME_FMT);
    }

    /**
     * Converts a role identifier into the localized role name shown by the UI.
     *
     * @param role role identifier, for example {@code "CLIENT"} or {@code "ROUTER"}
     * @return localized role name, or the original value when no mapping exists
     */
    public static String translateRole(String role) {
        if (role == null || role.isEmpty()) { return null; }
        String key = "node.role." + role;
        String translated = I18n.t(key);
        return translated.equals("!" + key + "!") ? role : translated;
    }

    @Override
    public String toString() {
        return "NodeData{" + nodeId +
                ", longName='" + longName + '\'' +
                ", shortName='" + shortName + '\'' +
                '}';
    }
}
