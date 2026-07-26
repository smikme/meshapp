package com.meshtastic.client.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single telemetry sample tied to a point in time.
 * <p>
 * Stores Meshtastic telemetry variants as typed fields so they can be queried,
 * sorted, and charted directly from the local H2 database.
 *
 * @author Meshtastic Team
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class TelemetryEntry {

    private final long timestamp; // epoch seconds
    private final String nodeId;
    private long packetId;
    private String telemetryVariant;

    // DeviceMetrics
    private int batteryLevel;
    private boolean externallyPowered;
    private float voltage;
    private float channelUtilization;
    private float airUtilTx;
    private Long deviceUptimeSeconds;

    // EnvironmentMetrics
    private float temperature;
    private float relativeHumidity;
    private float barometricPressure;
    private Float gasResistance;
    private Float environmentVoltage;
    private Float environmentCurrent;
    private Long iaq;
    private Float distance;
    private Float lux;
    private Float whiteLux;
    private Float irLux;
    private Float uvLux;
    private Long windDirection;
    private Float windSpeed;
    private Float weight;
    private Float windGust;
    private Float windLull;
    private Float radiation;
    private Float rainfall1h;
    private Float rainfall24h;
    private Long soilMoisture;
    private Float soilTemperature;
    private final List<Float> oneWireTemperatures = new ArrayList<>();

    // AirQualityMetrics
    private Long pm10Standard;
    private Long pm25Standard;
    private Long pm100Standard;
    private Long pm10Environmental;
    private Long pm25Environmental;
    private Long pm100Environmental;
    private Long particles03um;
    private Long particles05um;
    private Long particles10um;
    private Long particles25um;
    private Long particles50um;
    private Long particles100um;
    private Long co2;
    private Float co2Temperature;
    private Float co2Humidity;
    private Float formFormaldehyde;
    private Float formHumidity;
    private Float formTemperature;
    private Long pm40Standard;
    private Long particles40um;
    private Float pmTemperature;
    private Float pmHumidity;
    private Float pmVocIdx;
    private Float pmNoxIdx;
    private Float particlesTps;

    // PowerMetrics
    private Float ch1Voltage;
    private Float ch1Current;
    private Float ch2Voltage;
    private Float ch2Current;
    private Float ch3Voltage;
    private Float ch3Current;
    private Float ch4Voltage;
    private Float ch4Current;
    private Float ch5Voltage;
    private Float ch5Current;
    private Float ch6Voltage;
    private Float ch6Current;
    private Float ch7Voltage;
    private Float ch7Current;
    private Float ch8Voltage;
    private Float ch8Current;

    // LocalStats (packet counters - RX)
    private int numPacketsRx;
    private int numPacketsRxBad;
    private int numRxDupe;

    // LocalStats (packet counters - TX)
    private int numPacketsTx;
    private int numTxDropped;
    private int numTxRelay;
    private int numTxRelayCanceled;

    // LocalStats (other counters)
    private Long localUptimeSeconds;
    private Long numOnlineNodes;
    private Long numTotalNodes;
    private Long heapTotalBytes;
    private Long heapFreeBytes;
    private Integer noiseFloor;

    // HealthMetrics
    private Long healthHeartBpm;
    private Long healthSpO2;
    private Float healthTemperature;

    // HostMetrics
    private Long hostUptimeSeconds;
    private Long hostFreememBytes;
    private Long hostDiskfree1Bytes;
    private Long hostDiskfree2Bytes;
    private Long hostDiskfree3Bytes;
    private Long hostLoad1;
    private Long hostLoad5;
    private Long hostLoad15;
    private String hostUserString;

    // TrafficManagementStats
    private Long trafficPacketsInspected;
    private Long trafficPositionDedupDrops;
    private Long trafficNodeinfoCacheHits;
    private Long trafficRateLimitDrops;
    private Long trafficUnknownPacketDrops;
    private Long trafficHopExhaustedPackets;
    private Long trafficRouterHopsPreserved;

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
    public long getPacketId() { return packetId; }
    public void setPacketId(long packetId) { this.packetId = packetId; }

    public String getTelemetryVariant() { return telemetryVariant; }
    public void setTelemetryVariant(String telemetryVariant) { this.telemetryVariant = telemetryVariant; }

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

    public Long getDeviceUptimeSeconds() { return deviceUptimeSeconds; }
    public void setDeviceUptimeSeconds(Long deviceUptimeSeconds) { this.deviceUptimeSeconds = deviceUptimeSeconds; }

    public float getTemperature() { return temperature; }
    public void setTemperature(float temperature) { this.temperature = temperature; }

    public float getRelativeHumidity() { return relativeHumidity; }
    public void setRelativeHumidity(float relativeHumidity) { this.relativeHumidity = relativeHumidity; }

    public float getBarometricPressure() { return barometricPressure; }
    public void setBarometricPressure(float barometricPressure) { this.barometricPressure = barometricPressure; }

    public Float getGasResistance() { return gasResistance; }
    public void setGasResistance(Float gasResistance) { this.gasResistance = gasResistance; }

    public Float getEnvironmentVoltage() { return environmentVoltage; }
    public void setEnvironmentVoltage(Float environmentVoltage) { this.environmentVoltage = environmentVoltage; }

    public Float getEnvironmentCurrent() { return environmentCurrent; }
    public void setEnvironmentCurrent(Float environmentCurrent) { this.environmentCurrent = environmentCurrent; }

    public Long getIaq() { return iaq; }
    public void setIaq(Long iaq) { this.iaq = iaq; }

    public Float getDistance() { return distance; }
    public void setDistance(Float distance) { this.distance = distance; }

    public Float getLux() { return lux; }
    public void setLux(Float lux) { this.lux = lux; }

    public Float getWhiteLux() { return whiteLux; }
    public void setWhiteLux(Float whiteLux) { this.whiteLux = whiteLux; }

    public Float getIrLux() { return irLux; }
    public void setIrLux(Float irLux) { this.irLux = irLux; }

    public Float getUvLux() { return uvLux; }
    public void setUvLux(Float uvLux) { this.uvLux = uvLux; }

    public Long getWindDirection() { return windDirection; }
    public void setWindDirection(Long windDirection) { this.windDirection = windDirection; }

    public Float getWindSpeed() { return windSpeed; }
    public void setWindSpeed(Float windSpeed) { this.windSpeed = windSpeed; }

    public Float getWeight() { return weight; }
    public void setWeight(Float weight) { this.weight = weight; }

    public Float getWindGust() { return windGust; }
    public void setWindGust(Float windGust) { this.windGust = windGust; }

    public Float getWindLull() { return windLull; }
    public void setWindLull(Float windLull) { this.windLull = windLull; }

    public Float getRadiation() { return radiation; }
    public void setRadiation(Float radiation) { this.radiation = radiation; }

    public Float getRainfall1h() { return rainfall1h; }
    public void setRainfall1h(Float rainfall1h) { this.rainfall1h = rainfall1h; }

    public Float getRainfall24h() { return rainfall24h; }
    public void setRainfall24h(Float rainfall24h) { this.rainfall24h = rainfall24h; }

    public Long getSoilMoisture() { return soilMoisture; }
    public void setSoilMoisture(Long soilMoisture) { this.soilMoisture = soilMoisture; }

    public Float getSoilTemperature() { return soilTemperature; }
    public void setSoilTemperature(Float soilTemperature) { this.soilTemperature = soilTemperature; }

    public List<Float> getOneWireTemperatures() { return Collections.unmodifiableList(oneWireTemperatures); }

    public void setOneWireTemperatures(List<Float> values) {
        oneWireTemperatures.clear();
        if (values == null) { return; }
        for (Float value : values) {
            if (value != null) {
                oneWireTemperatures.add(value);
            }
        }
    }

    public void addOneWireTemperature(float temperature) {
        oneWireTemperatures.add(temperature);
    }

    public Long getPm10Standard() { return pm10Standard; }
    public void setPm10Standard(Long pm10Standard) { this.pm10Standard = pm10Standard; }

    public Long getPm25Standard() { return pm25Standard; }
    public void setPm25Standard(Long pm25Standard) { this.pm25Standard = pm25Standard; }

    public Long getPm100Standard() { return pm100Standard; }
    public void setPm100Standard(Long pm100Standard) { this.pm100Standard = pm100Standard; }

    public Long getPm10Environmental() { return pm10Environmental; }
    public void setPm10Environmental(Long pm10Environmental) { this.pm10Environmental = pm10Environmental; }

    public Long getPm25Environmental() { return pm25Environmental; }
    public void setPm25Environmental(Long pm25Environmental) { this.pm25Environmental = pm25Environmental; }

    public Long getPm100Environmental() { return pm100Environmental; }
    public void setPm100Environmental(Long pm100Environmental) { this.pm100Environmental = pm100Environmental; }

    public Long getParticles03um() { return particles03um; }
    public void setParticles03um(Long particles03um) { this.particles03um = particles03um; }

    public Long getParticles05um() { return particles05um; }
    public void setParticles05um(Long particles05um) { this.particles05um = particles05um; }

    public Long getParticles10um() { return particles10um; }
    public void setParticles10um(Long particles10um) { this.particles10um = particles10um; }

    public Long getParticles25um() { return particles25um; }
    public void setParticles25um(Long particles25um) { this.particles25um = particles25um; }

    public Long getParticles50um() { return particles50um; }
    public void setParticles50um(Long particles50um) { this.particles50um = particles50um; }

    public Long getParticles100um() { return particles100um; }
    public void setParticles100um(Long particles100um) { this.particles100um = particles100um; }

    public Long getCo2() { return co2; }
    public void setCo2(Long co2) { this.co2 = co2; }

    public Float getCo2Temperature() { return co2Temperature; }
    public void setCo2Temperature(Float co2Temperature) { this.co2Temperature = co2Temperature; }

    public Float getCo2Humidity() { return co2Humidity; }
    public void setCo2Humidity(Float co2Humidity) { this.co2Humidity = co2Humidity; }

    public Float getFormFormaldehyde() { return formFormaldehyde; }
    public void setFormFormaldehyde(Float formFormaldehyde) { this.formFormaldehyde = formFormaldehyde; }

    public Float getFormHumidity() { return formHumidity; }
    public void setFormHumidity(Float formHumidity) { this.formHumidity = formHumidity; }

    public Float getFormTemperature() { return formTemperature; }
    public void setFormTemperature(Float formTemperature) { this.formTemperature = formTemperature; }

    public Long getPm40Standard() { return pm40Standard; }
    public void setPm40Standard(Long pm40Standard) { this.pm40Standard = pm40Standard; }

    public Long getParticles40um() { return particles40um; }
    public void setParticles40um(Long particles40um) { this.particles40um = particles40um; }

    public Float getPmTemperature() { return pmTemperature; }
    public void setPmTemperature(Float pmTemperature) { this.pmTemperature = pmTemperature; }

    public Float getPmHumidity() { return pmHumidity; }
    public void setPmHumidity(Float pmHumidity) { this.pmHumidity = pmHumidity; }

    public Float getPmVocIdx() { return pmVocIdx; }
    public void setPmVocIdx(Float pmVocIdx) { this.pmVocIdx = pmVocIdx; }

    public Float getPmNoxIdx() { return pmNoxIdx; }
    public void setPmNoxIdx(Float pmNoxIdx) { this.pmNoxIdx = pmNoxIdx; }

    public Float getParticlesTps() { return particlesTps; }
    public void setParticlesTps(Float particlesTps) { this.particlesTps = particlesTps; }

    public Float getCh1Voltage() { return ch1Voltage; }
    public void setCh1Voltage(Float ch1Voltage) { this.ch1Voltage = ch1Voltage; }

    public Float getCh1Current() { return ch1Current; }
    public void setCh1Current(Float ch1Current) { this.ch1Current = ch1Current; }

    public Float getCh2Voltage() { return ch2Voltage; }
    public void setCh2Voltage(Float ch2Voltage) { this.ch2Voltage = ch2Voltage; }

    public Float getCh2Current() { return ch2Current; }
    public void setCh2Current(Float ch2Current) { this.ch2Current = ch2Current; }

    public Float getCh3Voltage() { return ch3Voltage; }
    public void setCh3Voltage(Float ch3Voltage) { this.ch3Voltage = ch3Voltage; }

    public Float getCh3Current() { return ch3Current; }
    public void setCh3Current(Float ch3Current) { this.ch3Current = ch3Current; }

    public Float getCh4Voltage() { return ch4Voltage; }
    public void setCh4Voltage(Float ch4Voltage) { this.ch4Voltage = ch4Voltage; }

    public Float getCh4Current() { return ch4Current; }
    public void setCh4Current(Float ch4Current) { this.ch4Current = ch4Current; }

    public Float getCh5Voltage() { return ch5Voltage; }
    public void setCh5Voltage(Float ch5Voltage) { this.ch5Voltage = ch5Voltage; }

    public Float getCh5Current() { return ch5Current; }
    public void setCh5Current(Float ch5Current) { this.ch5Current = ch5Current; }

    public Float getCh6Voltage() { return ch6Voltage; }
    public void setCh6Voltage(Float ch6Voltage) { this.ch6Voltage = ch6Voltage; }

    public Float getCh6Current() { return ch6Current; }
    public void setCh6Current(Float ch6Current) { this.ch6Current = ch6Current; }

    public Float getCh7Voltage() { return ch7Voltage; }
    public void setCh7Voltage(Float ch7Voltage) { this.ch7Voltage = ch7Voltage; }

    public Float getCh7Current() { return ch7Current; }
    public void setCh7Current(Float ch7Current) { this.ch7Current = ch7Current; }

    public Float getCh8Voltage() { return ch8Voltage; }
    public void setCh8Voltage(Float ch8Voltage) { this.ch8Voltage = ch8Voltage; }

    public Float getCh8Current() { return ch8Current; }
    public void setCh8Current(Float ch8Current) { this.ch8Current = ch8Current; }

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

    public Long getLocalUptimeSeconds() { return localUptimeSeconds; }
    public void setLocalUptimeSeconds(Long localUptimeSeconds) { this.localUptimeSeconds = localUptimeSeconds; }

    public Long getNumOnlineNodes() { return numOnlineNodes; }
    public void setNumOnlineNodes(Long numOnlineNodes) { this.numOnlineNodes = numOnlineNodes; }

    public Long getNumTotalNodes() { return numTotalNodes; }
    public void setNumTotalNodes(Long numTotalNodes) { this.numTotalNodes = numTotalNodes; }

    public Long getHeapTotalBytes() { return heapTotalBytes; }
    public void setHeapTotalBytes(Long heapTotalBytes) { this.heapTotalBytes = heapTotalBytes; }

    public Long getHeapFreeBytes() { return heapFreeBytes; }
    public void setHeapFreeBytes(Long heapFreeBytes) { this.heapFreeBytes = heapFreeBytes; }

    public Integer getNoiseFloor() { return noiseFloor; }
    public void setNoiseFloor(Integer noiseFloor) { this.noiseFloor = noiseFloor; }

    public Long getHealthHeartBpm() { return healthHeartBpm; }
    public void setHealthHeartBpm(Long healthHeartBpm) { this.healthHeartBpm = healthHeartBpm; }

    public Long getHealthSpO2() { return healthSpO2; }
    public void setHealthSpO2(Long healthSpO2) { this.healthSpO2 = healthSpO2; }

    public Float getHealthTemperature() { return healthTemperature; }
    public void setHealthTemperature(Float healthTemperature) { this.healthTemperature = healthTemperature; }

    public Long getHostUptimeSeconds() { return hostUptimeSeconds; }
    public void setHostUptimeSeconds(Long hostUptimeSeconds) { this.hostUptimeSeconds = hostUptimeSeconds; }

    public Long getHostFreememBytes() { return hostFreememBytes; }
    public void setHostFreememBytes(Long hostFreememBytes) { this.hostFreememBytes = hostFreememBytes; }

    public Long getHostDiskfree1Bytes() { return hostDiskfree1Bytes; }
    public void setHostDiskfree1Bytes(Long hostDiskfree1Bytes) { this.hostDiskfree1Bytes = hostDiskfree1Bytes; }

    public Long getHostDiskfree2Bytes() { return hostDiskfree2Bytes; }
    public void setHostDiskfree2Bytes(Long hostDiskfree2Bytes) { this.hostDiskfree2Bytes = hostDiskfree2Bytes; }

    public Long getHostDiskfree3Bytes() { return hostDiskfree3Bytes; }
    public void setHostDiskfree3Bytes(Long hostDiskfree3Bytes) { this.hostDiskfree3Bytes = hostDiskfree3Bytes; }

    public Long getHostLoad1() { return hostLoad1; }
    public void setHostLoad1(Long hostLoad1) { this.hostLoad1 = hostLoad1; }

    public Long getHostLoad5() { return hostLoad5; }
    public void setHostLoad5(Long hostLoad5) { this.hostLoad5 = hostLoad5; }

    public Long getHostLoad15() { return hostLoad15; }
    public void setHostLoad15(Long hostLoad15) { this.hostLoad15 = hostLoad15; }

    public String getHostUserString() { return hostUserString; }
    public void setHostUserString(String hostUserString) { this.hostUserString = hostUserString; }

    public Long getTrafficPacketsInspected() { return trafficPacketsInspected; }
    public void setTrafficPacketsInspected(Long trafficPacketsInspected) { this.trafficPacketsInspected = trafficPacketsInspected; }

    public Long getTrafficPositionDedupDrops() { return trafficPositionDedupDrops; }
    public void setTrafficPositionDedupDrops(Long trafficPositionDedupDrops) { this.trafficPositionDedupDrops = trafficPositionDedupDrops; }

    public Long getTrafficNodeinfoCacheHits() { return trafficNodeinfoCacheHits; }
    public void setTrafficNodeinfoCacheHits(Long trafficNodeinfoCacheHits) { this.trafficNodeinfoCacheHits = trafficNodeinfoCacheHits; }

    public Long getTrafficRateLimitDrops() { return trafficRateLimitDrops; }
    public void setTrafficRateLimitDrops(Long trafficRateLimitDrops) { this.trafficRateLimitDrops = trafficRateLimitDrops; }

    public Long getTrafficUnknownPacketDrops() { return trafficUnknownPacketDrops; }
    public void setTrafficUnknownPacketDrops(Long trafficUnknownPacketDrops) { this.trafficUnknownPacketDrops = trafficUnknownPacketDrops; }

    public Long getTrafficHopExhaustedPackets() { return trafficHopExhaustedPackets; }
    public void setTrafficHopExhaustedPackets(Long trafficHopExhaustedPackets) { this.trafficHopExhaustedPackets = trafficHopExhaustedPackets; }

    public Long getTrafficRouterHopsPreserved() { return trafficRouterHopsPreserved; }
    public void setTrafficRouterHopsPreserved(Long trafficRouterHopsPreserved) { this.trafficRouterHopsPreserved = trafficRouterHopsPreserved; }

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
