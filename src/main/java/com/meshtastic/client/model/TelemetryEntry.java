package com.meshtastic.client.model;

/**
 * Одна запись телеметрии, привязанная к моменту времени.
 * Хранит DeviceMetrics и EnvironmentMetrics для отображения на графиках.
 */
public class TelemetryEntry {

    private final long timestamp; // epoch seconds
    private final int nodeNum;

    // DeviceMetrics
    private int batteryLevel;
    private float voltage;
    private float channelUtilization;
    private float airUtilTx;

    // EnvironmentMetrics
    private float temperature;
    private float relativeHumidity;
    private float barometricPressure;

    public TelemetryEntry(long timestamp, int nodeNum) {
        this.timestamp = timestamp;
        this.nodeNum = nodeNum;
    }

    public long getTimestamp() { return timestamp; }
    public int getNodeNum() { return nodeNum; }

    public int getBatteryLevel() { return batteryLevel; }
    public void setBatteryLevel(int batteryLevel) { this.batteryLevel = batteryLevel; }

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
}
