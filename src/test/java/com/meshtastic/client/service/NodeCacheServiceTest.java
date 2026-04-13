package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.NodeData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
