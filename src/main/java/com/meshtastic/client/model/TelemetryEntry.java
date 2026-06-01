package com.meshtastic.client.model;

/**
 * Single telemetry sample tied to a point in time.
 * <p>
 * Stores device metrics ({@code batteryLevel}, {@code voltage},
 * {@code channelUtilization}), environmental metrics ({@code temperature},
 * {@code humidity}, {@code pressure}), and packet statistics
 * ({@code numPacketsRx}, {@code numPacketsTx}).
 * <p>
 * Used to build telemetry charts and track mesh node health.
 * <p>
 * Example:
 * <pre>{@code
 * TelemetryEntry entry = new TelemetryEntry(System.currentTimeMillis() / 1000, "!00000001");
 * entry.setBatteryLevel(85);
 * entry.setTemperature(23.5f);
 * }</pre>
 *
 * @author Meshtastic Team
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class TelemetryEntry {

    private final long timestamp; // epoch seconds
    private final String nodeId;

    // DeviceMetrics
    private int batteryLevel;
    private boolean externallyPowered;
    private float voltage;
    private float channelUtilization;
    private float airUtilTx;

    // EnvironmentMetrics
    private float temperature;
    private float relativeHumidity;
    private float barometricPressure;

    // LocalStats (packet counters — RX)
    private int numPacketsRx;
    private int numPacketsRxBad;
    private int numRxDupe;

    // LocalStats (packet counters — TX)
    private int numPacketsTx;
    private int numTxDropped;
    private int numTxRelay;
    private int numTxRelayCanceled;

    // Connection quality (from MeshPacket)
    private float rxSnr;
    private int rxRssi;

    // Hop info (from MeshPacket)
    private int hopStart;
    private int hopLimit;

    public TelemetryEntry(long timestamp, String nodeId) {
        this.timestamp = timestamp;
        this.nodeId = nodeId;
    }

    public long getTimestamp() { return timestamp; }
    public String getNodeId() { return nodeId; }

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

    public float getTemperature() { return temperature; }
    public void setTemperature(float temperature) { this.temperature = temperature; }

    public float getRelativeHumidity() { return relativeHumidity; }
    public void setRelativeHumidity(float relativeHumidity) { this.relativeHumidity = relativeHumidity; }

    public float getBarometricPressure() { return barometricPressure; }
    public void setBarometricPressure(float barometricPressure) { this.barometricPressure = barometricPressure; }

    public int getNumPacketsRx() { return numPacketsRx; }
    public void setNumPacketsRx(int numPacketsRx) { this.numPacketsRx = numPacketsRx; }

    public int getNumPacketsRxBad() { return numPacketsRxBad; }
    public void setNumPacketsRxBad(int numPacketsRxBad) { this.numPacketsRxBad = numPacketsRxBad; }

    public int getNumRxDupe() { return numRxDupe; }
    public void setNumRxDupe(int numRxDupe) { this.numRxDupe = numRxDupe; }

    public int getNumPacketsTx() { return numPacketsTx; }
    public void setNumPacketsTx(int numPacketsTx) { this.numPacketsTx = numPacketsTx; }

    public int getNumTxDropped() { return numTxDropped; }
    public void setNumTxDropped(int numTxDropped) { this.numTxDropped = numTxDropped; }

    public int getNumTxRelay() { return numTxRelay; }
    public void setNumTxRelay(int numTxRelay) { this.numTxRelay = numTxRelay; }

    public int getNumTxRelayCanceled() { return numTxRelayCanceled; }
    public void setNumTxRelayCanceled(int numTxRelayCanceled) { this.numTxRelayCanceled = numTxRelayCanceled; }

    public float getRxSnr() { return rxSnr; }
    public void setRxSnr(float rxSnr) { this.rxSnr = rxSnr; }

    public int getRxRssi() { return rxRssi; }
    public void setRxRssi(int rxRssi) { this.rxRssi = rxRssi; }

    public int getHopStart() { return hopStart; }
    public void setHopStart(int hopStart) { this.hopStart = hopStart; }

    public int getHopLimit() { return hopLimit; }
    public void setHopLimit(int hopLimit) { this.hopLimit = hopLimit; }

    public boolean hasValidHopData() {
        return hopStart > 0 && hopLimit >= 0 && hopLimit <= hopStart;
    }

    public int getHopsTraveled() {
        return hasValidHopData() ? hopStart - hopLimit : 0;
    }
}
