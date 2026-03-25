package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.NodeData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
}
