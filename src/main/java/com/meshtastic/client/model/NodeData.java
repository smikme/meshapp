package com.meshtastic.client.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Данные одной ноды Meshtastic-сети.
 * <p>
 * Изменяемый POJO, содержащий идентификацию (longName, shortName, nodeId),
 * позицию (lat/lon/alt), метрики устройства (батарея, напряжение, утилизация канала),
 * метрики окружения (температура, влажность, давление) и метаданные (роль, hw_model, hopsAway).
 * <p>
 * Создаётся через {@link DeviceState#getOrCreateNode(int)} или напрямую по номеру ноды.
 * Поле {@code nodeId} генерируется автоматически в формате {@code !XXXXXXXX} (hex).
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
    private float voltage;
    private float channelUtilization;
    private float airUtilTx;
    private long uptimeSeconds;
    private float temperature;
    private float relativeHumidity;
    private float barometricPressure;
    private int hopsAway;
    private String role;
    private String hwModel;
    private byte[] publicKey;

    /**
     * Создаёт ноду с указанным номером. Автоматически генерирует {@code nodeId}
     * в формате {@code !XXXXXXXX} (hex-представление nodeNum).
     *
     * @param nodeNum уникальный числовой идентификатор ноды
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
    public void setHopsAway(int hopsAway) { this.hopsAway = hopsAway; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getHwModel() { return hwModel; }
    public void setHwModel(String hwModel) { this.hwModel = hwModel; }

    public byte[] getPublicKey() { return publicKey; }
    public void setPublicKey(byte[] publicKey) { this.publicKey = publicKey; }

    /**
     * Проверяет, есть ли у ноды хотя бы одно непустое имя (longName или shortName).
     *
     * @return {@code true} если longName или shortName заполнено
     */
    public boolean hasName() {
        return (longName != null && !longName.isEmpty())
                || (shortName != null && !shortName.isEmpty());
    }

    /**
     * Форматирует Unix-время (секунды) в строку {@code dd.MM.yy HH:mm}.
     *
     * @param epochSeconds время в секундах с начала эпохи
     * @return отформатированная строка или пустая строка для значений ≤ 0
     */
    public static String formatTime(long epochSeconds) {
        if (epochSeconds <= 0) { return ""; }
        return Instant.ofEpochSecond(epochSeconds)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(TIME_FMT);
    }

    /**
     * Переводит строковый идентификатор роли ноды в русскоязычное название.
     *
     * @param role идентификатор роли (например, {@code "CLIENT"}, {@code "ROUTER"})
     * @return русскоязычное название роли, или исходная строка если перевод не найден
     */
    public static String translateRole(String role) {
        if (role == null || role.isEmpty()) { return null; }
        return switch (role) {
            case "CLIENT"         -> "Клиент";
            case "CLIENT_MUTE"    -> "Клиент (без звука)";
            case "CLIENT_HIDDEN"  -> "Клиент (скрытый)";
            case "TRACKER"        -> "Трекер";
            case "LOST_AND_FOUND" -> "Потерянное и найденное";
            case "SENSOR"         -> "Датчик";
            case "TAK"            -> "TAK";
            case "TAK_TRACKER"    -> "TAK-трекер";
            case "REPEATER"       -> "Ретранслятор";
            case "ROUTER"         -> "Маршрутизатор";
            case "ROUTER_CLIENT"  -> "Маршрутизатор-клиент";
            default               -> role;
        };
    }

    @Override
    public String toString() {
        return "NodeData{" + nodeId +
                ", longName='" + longName + '\'' +
                ", shortName='" + shortName + '\'' +
                '}';
    }
}
