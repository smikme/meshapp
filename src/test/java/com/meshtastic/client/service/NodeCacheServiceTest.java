package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class NodeCacheServiceTest {

    @TempDir
    Path tempHome;

    private NodeCacheService service;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        service = NodeCacheService.getInstance();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void setIgnoredCreatesPlaceholderRowForUnknownNode() {
        service.setIgnored("!cafebabe", true);

        assertTrue(service.isIgnored("!cafebabe"));

        NodeData cached = service.get("!cafebabe");
        assertNotNull(cached);
        assertEquals("!cafebabe", cached.getNodeId());
        assertEquals(0xCAFEBABE, cached.getNodeNum());
    }

    @Test
    void favoriteAndIgnoredFlagsAreScopedByOwnerNode() {
        service.setFavorite("!cafebabe", "!11111111", true);
        service.setIgnored("!cafebabe", "!22222222", true);

        assertTrue(service.isFavorite("!cafebabe", "!11111111"));
        assertFalse(service.isFavorite("!cafebabe", "!22222222"));
        assertFalse(service.isFavorite("!cafebabe", "!33333333"));

        assertFalse(service.isIgnored("!cafebabe", "!11111111"));
        assertTrue(service.isIgnored("!cafebabe", "!22222222"));
        assertFalse(service.isIgnored("!cafebabe", "!33333333"));

        assertEquals(1, service.loadFavoriteNodes("!11111111").size());
        assertEquals(0, service.loadFavoriteNodes("!22222222").size());
        assertEquals(0, service.loadIgnoredNodes("!11111111").size());
        assertEquals(1, service.loadIgnoredNodes("!22222222").size());
    }

    @Test
    void publicKeySurvivesNodeCacheRestart() {
        NodeData node = new NodeData(0xA0065360);
        node.setNodeId("!a0065360");
        node.setLongName("COVOX");
        node.setPublicKey(new byte[] {1, 2, 3, 4});
        node.setUnmessagable(true);
        service.update(node);

        TestEnvironmentSupport.resetSingletons();
        service = NodeCacheService.getInstance();

        NodeData restored = service.get("!a0065360");
        assertNotNull(restored);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, restored.getPublicKey());
        assertTrue(restored.isUnmessagable());
    }

    @Test
    void enrichFromCacheCopiesPublicKeyEvenWhenNameAlreadyPresent() {
        NodeData cached = new NodeData(0xA0065360);
        cached.setNodeId("!a0065360");
        cached.setLongName("COVOX");
        cached.setPublicKey(new byte[] {5, 6, 7, 8});
        cached.setUnmessagable(true);
        service.update(cached);

        NodeData liveNode = new NodeData(0xA0065360);
        liveNode.setNodeId("!a0065360");
        liveNode.setLongName("COVOX");

        service.enrichFromCache(liveNode);

        assertArrayEquals(new byte[] {5, 6, 7, 8}, liveNode.getPublicKey());
        assertTrue(liveNode.isUnmessagable());
    }

    @Test
    void firmware28ReplayTelemetryIsDeduplicatedByPacketIdentity() {
        TelemetryEntry first =
                new TelemetryEntry(1_700_000_000L, "!bbbbbbbb");
        first.setPacketId(0xF1234567L);
        first.setTelemetryVariant("DEVICE_METRICS");
        first.setBatteryLevel(80);

        TelemetryEntry replay =
                new TelemetryEntry(1_700_000_000L, "!bbbbbbbb");
        replay.setPacketId(0xF1234567L);
        replay.setTelemetryVariant("DEVICE_METRICS");
        replay.setBatteryLevel(80);

        assertTrue(service.persistTelemetry(first, "!aaaaaaaa", true));
        assertFalse(service.persistTelemetry(replay, "!aaaaaaaa", true));
        assertEquals(1, service.countTelemetryEntries("!aaaaaaaa"));
        assertEquals(
                0xF1234567L,
                service.loadTelemetryHistory(10, "!aaaaaaaa")
                        .getFirst()
                        .getPacketId());
    }

    @Test
    void legacyTelemetryKeepsExistingDuplicateBehavior() {
        TelemetryEntry entry =
                new TelemetryEntry(1_700_000_000L, "!bbbbbbbb");
        entry.setPacketId(77);
        entry.setTelemetryVariant("DEVICE_METRICS");

        assertTrue(service.persistTelemetry(entry, "!aaaaaaaa", false));
        assertTrue(service.persistTelemetry(entry, "!aaaaaaaa", false));
        assertEquals(2, service.countTelemetryEntries("!aaaaaaaa"));
    }

    @Test
    void enrichFromCacheMemoizesDbHitForSubsequentLookups() throws Exception {
        NodeData persisted = new NodeData(0xA0065360);
        persisted.setNodeId("!a0065360");
        persisted.setLongName("COVOX");
        persisted.setShortName("CVX");
        service.update(persisted);

        TestEnvironmentSupport.resetSingletons();
        service = NodeCacheService.getInstance();

        NodeData firstLiveNode = new NodeData(0xA0065360);
        firstLiveNode.setNodeId("!a0065360");
        service.enrichFromCache(firstLiveNode);

        assertEquals("COVOX", firstLiveNode.getLongName());
        assertTrue(readCache(service).containsKey("!a0065360"));

        try (PreparedStatement ps = DatabaseProvider.getConnection()
                .prepareStatement("DELETE FROM nodes WHERE node_id = ?")) {
            ps.setString(1, "!a0065360");
            ps.executeUpdate();
        }

        NodeData secondLiveNode = new NodeData(0xA0065360);
        secondLiveNode.setNodeId("!a0065360");
        service.enrichFromCache(secondLiveNode);

        assertEquals("COVOX", secondLiveNode.getLongName());
        assertEquals("CVX", secondLiveNode.getShortName());
    }

    @Test
    void updateClearsMemoizedMissAndAllowsLaterEnrich() throws Exception {
        NodeData missingLiveNode = new NodeData(0xDEADBEEF);
        missingLiveNode.setNodeId("!deadbeef");

        service.enrichFromCache(missingLiveNode);

        assertNull(missingLiveNode.getLongName());
        assertTrue(readMissingNodeIds(service).contains("!deadbeef"));

        NodeData fresh = new NodeData(0xDEADBEEF);
        fresh.setNodeId("!deadbeef");
        fresh.setLongName("Recovered");
        fresh.setShortName("RCVD");
        service.update(fresh);

        assertFalse(readMissingNodeIds(service).contains("!deadbeef"));

        NodeData reloadedLiveNode = new NodeData(0xDEADBEEF);
        reloadedLiveNode.setNodeId("!deadbeef");
        service.enrichFromCache(reloadedLiveNode);

        assertEquals("Recovered", reloadedLiveNode.getLongName());
        assertEquals("RCVD", reloadedLiveNode.getShortName());
    }

    @Test
    void deleteNodeRemovesTelemetryOnlyForOwnerScope() {
        TelemetryEntry ownerAEntry = new TelemetryEntry(1_700_000_000L, "!bbbbbbbb");
        ownerAEntry.setVoltage(4.0f);
        TelemetryEntry ownerBEntry = new TelemetryEntry(1_700_000_060L, "!bbbbbbbb");
        ownerBEntry.setVoltage(4.1f);
        TelemetryEntry otherNodeOwnerAEntry = new TelemetryEntry(1_700_000_120L, "!cccccccc");
        otherNodeOwnerAEntry.setVoltage(4.2f);

        service.persistTelemetry(ownerAEntry, "!aaaaaaaa");
        service.persistTelemetry(ownerBEntry, "!dddddddd");
        service.persistTelemetry(otherNodeOwnerAEntry, "!aaaaaaaa");

        service.deleteNode("!bbbbbbbb", "!aaaaaaaa");

        assertEquals(0, service.loadTelemetryForNode("!bbbbbbbb", 0, Long.MAX_VALUE, "!aaaaaaaa").size());
        assertEquals(1, service.loadTelemetryForNode("!bbbbbbbb", 0, Long.MAX_VALUE, "!dddddddd").size());
        assertEquals(1, service.loadTelemetryForNode("!cccccccc", 0, Long.MAX_VALUE, "!aaaaaaaa").size());
    }

    @Test
    void extendedTelemetryFieldsSurvivePersistenceRoundTrip() {
        TelemetryEntry entry = new TelemetryEntry(1_700_000_000L, "!bbbbbbbb");
        entry.setTelemetryVariant("ENVIRONMENT_METRICS");
        entry.setBatteryLevel(87);
        entry.setExternallyPowered(true);
        entry.setVoltage(4.01f);
        entry.setChannelUtilization(12.5f);
        entry.setAirUtilTx(1.25f);
        entry.setDeviceUptimeSeconds(123_456L);
        entry.setTemperature(22.5f);
        entry.setRelativeHumidity(44.5f);
        entry.setBarometricPressure(1001.25f);
        entry.setGasResistance(0.75f);
        entry.setEnvironmentVoltage(3.3f);
        entry.setEnvironmentCurrent(0.12f);
        entry.setIaq(42L);
        entry.setDistance(123.4f);
        entry.setLux(55.5f);
        entry.setWhiteLux(56.5f);
        entry.setIrLux(57.5f);
        entry.setUvLux(0.8f);
        entry.setWindDirection(270L);
        entry.setWindSpeed(5.5f);
        entry.setWeight(10.25f);
        entry.setWindGust(8.5f);
        entry.setWindLull(2.5f);
        entry.setRadiation(12.75f);
        entry.setRainfall1h(1.5f);
        entry.setRainfall24h(6.5f);
        entry.setSoilMoisture(64L);
        entry.setSoilTemperature(12.25f);
        entry.addOneWireTemperature(20.1f);
        entry.addOneWireTemperature(21.2f);
        entry.setPm10Standard(1L);
        entry.setPm25Standard(2L);
        entry.setPm100Standard(3L);
        entry.setPm10Environmental(4L);
        entry.setPm25Environmental(5L);
        entry.setPm100Environmental(6L);
        entry.setParticles03um(7L);
        entry.setParticles05um(8L);
        entry.setParticles10um(9L);
        entry.setParticles25um(10L);
        entry.setParticles50um(11L);
        entry.setParticles100um(12L);
        entry.setCo2(420L);
        entry.setCo2Temperature(23.5f);
        entry.setCo2Humidity(45.5f);
        entry.setFormFormaldehyde(0.5f);
        entry.setFormHumidity(46.5f);
        entry.setFormTemperature(24.5f);
        entry.setPm40Standard(13L);
        entry.setParticles40um(14L);
        entry.setPmTemperature(25.5f);
        entry.setPmHumidity(47.5f);
        entry.setPmVocIdx(99.5f);
        entry.setPmNoxIdx(12.5f);
        entry.setParticlesTps(0.42f);
        entry.setCh1Voltage(3.1f);
        entry.setCh1Current(0.1f);
        entry.setCh2Voltage(3.2f);
        entry.setCh2Current(0.2f);
        entry.setCh3Voltage(3.3f);
        entry.setCh3Current(0.3f);
        entry.setCh4Voltage(3.4f);
        entry.setCh4Current(0.4f);
        entry.setCh5Voltage(3.5f);
        entry.setCh5Current(0.5f);
        entry.setCh6Voltage(3.6f);
        entry.setCh6Current(0.6f);
        entry.setCh7Voltage(3.7f);
        entry.setCh7Current(0.7f);
        entry.setCh8Voltage(3.8f);
        entry.setCh8Current(0.8f);
        entry.setNumPacketsRx(101);
        entry.setNumPacketsRxBad(2);
        entry.setNumRxDupe(3);
        entry.setNumPacketsTx(102);
        entry.setNumTxDropped(4);
        entry.setNumTxRelay(5);
        entry.setNumTxRelayCanceled(6);
        entry.setLocalUptimeSeconds(654_321L);
        entry.setNumOnlineNodes(7L);
        entry.setNumTotalNodes(8L);
        entry.setHeapTotalBytes(9_000L);
        entry.setHeapFreeBytes(4_000L);
        entry.setNoiseFloor(-110);
        entry.setHealthHeartBpm(72L);
        entry.setHealthSpO2(98L);
        entry.setHealthTemperature(36.6f);
        entry.setHostUptimeSeconds(100_000L);
        entry.setHostFreememBytes(200_000L);
        entry.setHostDiskfree1Bytes(300_000L);
        entry.setHostDiskfree2Bytes(400_000L);
        entry.setHostDiskfree3Bytes(500_000L);
        entry.setHostLoad1(10L);
        entry.setHostLoad5(20L);
        entry.setHostLoad15(30L);
        entry.setHostUserString("host status");
        entry.setTrafficPacketsInspected(11L);
        entry.setTrafficPositionDedupDrops(12L);
        entry.setTrafficNodeinfoCacheHits(13L);
        entry.setTrafficRateLimitDrops(14L);
        entry.setTrafficUnknownPacketDrops(15L);
        entry.setTrafficHopExhaustedPackets(16L);
        entry.setTrafficRouterHopsPreserved(17L);
        entry.setRxSnr(6.25f);
        entry.setRxRssi(-85);
        entry.setHopStart(5);
        entry.setHopLimit(2);

        service.persistTelemetry(entry, "!aaaaaaaa");

        List<TelemetryEntry> restoredEntries =
                service.loadTelemetryForNode("!bbbbbbbb", 0, Long.MAX_VALUE, "!aaaaaaaa");
        assertEquals(1, restoredEntries.size());
        TelemetryEntry restored = restoredEntries.get(0);

        assertEquals("ENVIRONMENT_METRICS", restored.getTelemetryVariant());
        assertEquals(87, restored.getBatteryLevel());
        assertTrue(restored.isExternallyPowered());
        assertEquals(4.01f, restored.getVoltage(), 0.001f);
        assertEquals(12.5f, restored.getChannelUtilization(), 0.001f);
        assertEquals(1.25f, restored.getAirUtilTx(), 0.001f);
        assertEquals(123_456L, restored.getDeviceUptimeSeconds());
        assertEquals(22.5f, restored.getTemperature(), 0.001f);
        assertEquals(44.5f, restored.getRelativeHumidity(), 0.001f);
        assertEquals(1001.25f, restored.getBarometricPressure(), 0.001f);
        assertFloatEquals(0.75f, restored.getGasResistance());
        assertFloatEquals(3.3f, restored.getEnvironmentVoltage());
        assertFloatEquals(0.12f, restored.getEnvironmentCurrent());
        assertEquals(42L, restored.getIaq());
        assertFloatEquals(123.4f, restored.getDistance());
        assertFloatEquals(55.5f, restored.getLux());
        assertFloatEquals(56.5f, restored.getWhiteLux());
        assertFloatEquals(57.5f, restored.getIrLux());
        assertFloatEquals(0.8f, restored.getUvLux());
        assertEquals(270L, restored.getWindDirection());
        assertFloatEquals(5.5f, restored.getWindSpeed());
        assertFloatEquals(10.25f, restored.getWeight());
        assertFloatEquals(8.5f, restored.getWindGust());
        assertFloatEquals(2.5f, restored.getWindLull());
        assertFloatEquals(12.75f, restored.getRadiation());
        assertFloatEquals(1.5f, restored.getRainfall1h());
        assertFloatEquals(6.5f, restored.getRainfall24h());
        assertEquals(64L, restored.getSoilMoisture());
        assertFloatEquals(12.25f, restored.getSoilTemperature());
        assertEquals(List.of(20.1f, 21.2f), restored.getOneWireTemperatures());
        assertEquals(1L, restored.getPm10Standard());
        assertEquals(2L, restored.getPm25Standard());
        assertEquals(3L, restored.getPm100Standard());
        assertEquals(4L, restored.getPm10Environmental());
        assertEquals(5L, restored.getPm25Environmental());
        assertEquals(6L, restored.getPm100Environmental());
        assertEquals(7L, restored.getParticles03um());
        assertEquals(8L, restored.getParticles05um());
        assertEquals(9L, restored.getParticles10um());
        assertEquals(10L, restored.getParticles25um());
        assertEquals(11L, restored.getParticles50um());
        assertEquals(12L, restored.getParticles100um());
        assertEquals(420L, restored.getCo2());
        assertFloatEquals(23.5f, restored.getCo2Temperature());
        assertFloatEquals(45.5f, restored.getCo2Humidity());
        assertFloatEquals(0.5f, restored.getFormFormaldehyde());
        assertFloatEquals(46.5f, restored.getFormHumidity());
        assertFloatEquals(24.5f, restored.getFormTemperature());
        assertEquals(13L, restored.getPm40Standard());
        assertEquals(14L, restored.getParticles40um());
        assertFloatEquals(25.5f, restored.getPmTemperature());
        assertFloatEquals(47.5f, restored.getPmHumidity());
        assertFloatEquals(99.5f, restored.getPmVocIdx());
        assertFloatEquals(12.5f, restored.getPmNoxIdx());
        assertFloatEquals(0.42f, restored.getParticlesTps());
        assertFloatEquals(3.1f, restored.getCh1Voltage());
        assertFloatEquals(0.1f, restored.getCh1Current());
        assertFloatEquals(3.2f, restored.getCh2Voltage());
        assertFloatEquals(0.2f, restored.getCh2Current());
        assertFloatEquals(3.3f, restored.getCh3Voltage());
        assertFloatEquals(0.3f, restored.getCh3Current());
        assertFloatEquals(3.4f, restored.getCh4Voltage());
        assertFloatEquals(0.4f, restored.getCh4Current());
        assertFloatEquals(3.5f, restored.getCh5Voltage());
        assertFloatEquals(0.5f, restored.getCh5Current());
        assertFloatEquals(3.6f, restored.getCh6Voltage());
        assertFloatEquals(0.6f, restored.getCh6Current());
        assertFloatEquals(3.7f, restored.getCh7Voltage());
        assertFloatEquals(0.7f, restored.getCh7Current());
        assertFloatEquals(3.8f, restored.getCh8Voltage());
        assertFloatEquals(0.8f, restored.getCh8Current());
        assertEquals(101, restored.getNumPacketsRx());
        assertEquals(2, restored.getNumPacketsRxBad());
        assertEquals(3, restored.getNumRxDupe());
        assertEquals(102, restored.getNumPacketsTx());
        assertEquals(4, restored.getNumTxDropped());
        assertEquals(5, restored.getNumTxRelay());
        assertEquals(6, restored.getNumTxRelayCanceled());
        assertEquals(654_321L, restored.getLocalUptimeSeconds());
        assertEquals(7L, restored.getNumOnlineNodes());
        assertEquals(8L, restored.getNumTotalNodes());
        assertEquals(9_000L, restored.getHeapTotalBytes());
        assertEquals(4_000L, restored.getHeapFreeBytes());
        assertEquals(-110, restored.getNoiseFloor());
        assertEquals(72L, restored.getHealthHeartBpm());
        assertEquals(98L, restored.getHealthSpO2());
        assertFloatEquals(36.6f, restored.getHealthTemperature());
        assertEquals(100_000L, restored.getHostUptimeSeconds());
        assertEquals(200_000L, restored.getHostFreememBytes());
        assertEquals(300_000L, restored.getHostDiskfree1Bytes());
        assertEquals(400_000L, restored.getHostDiskfree2Bytes());
        assertEquals(500_000L, restored.getHostDiskfree3Bytes());
        assertEquals(10L, restored.getHostLoad1());
        assertEquals(20L, restored.getHostLoad5());
        assertEquals(30L, restored.getHostLoad15());
        assertEquals("host status", restored.getHostUserString());
        assertEquals(11L, restored.getTrafficPacketsInspected());
        assertEquals(12L, restored.getTrafficPositionDedupDrops());
        assertEquals(13L, restored.getTrafficNodeinfoCacheHits());
        assertEquals(14L, restored.getTrafficRateLimitDrops());
        assertEquals(15L, restored.getTrafficUnknownPacketDrops());
        assertEquals(16L, restored.getTrafficHopExhaustedPackets());
        assertEquals(17L, restored.getTrafficRouterHopsPreserved());
        assertEquals(6.25f, restored.getRxSnr(), 0.001f);
        assertEquals(-85, restored.getRxRssi());
        assertEquals(3, restored.getHopsTraveled());
    }

    @Test
    void missingNodeIdNegativeCacheIsBounded() throws Exception {
        Set<String> missingNodeIds = readMissingNodeIds(service);
        int maxMissingNodeIds = maxMissingNodeIdCount();

        for (int i = 0; i < maxMissingNodeIds + 3; i++) {
            missingNodeIds.add(String.format("!%08x", i));
        }

        assertEquals(maxMissingNodeIds, missingNodeIds.size());
        assertFalse(missingNodeIds.contains("!00000000"));
        assertFalse(missingNodeIds.contains("!00000001"));
        assertFalse(missingNodeIds.contains("!00000002"));
        assertTrue(missingNodeIds.contains(String.format("!%08x", maxMissingNodeIds + 2)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, NodeData> readCache(NodeCacheService service) throws Exception {
        Field field = NodeCacheService.class.getDeclaredField("cache");
        field.setAccessible(true);
        return (Map<String, NodeData>) field.get(service);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> readMissingNodeIds(NodeCacheService service) throws Exception {
        Field field = NodeCacheService.class.getDeclaredField("missingNodeIds");
        field.setAccessible(true);
        return (Set<String>) field.get(service);
    }

    private static int maxMissingNodeIdCount() throws Exception {
        Field field = NodeCacheService.class.getDeclaredField("MAX_MISSING_NODE_IDS");
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void assertFloatEquals(float expected, Float actual) {
        assertNotNull(actual);
        assertEquals(expected, actual, 0.001f);
    }
}
