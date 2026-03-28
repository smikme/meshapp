package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.NodeData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
