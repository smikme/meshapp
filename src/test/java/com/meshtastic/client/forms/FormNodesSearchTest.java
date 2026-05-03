package com.meshtastic.client.forms;

import com.meshtastic.client.model.NodeData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class FormNodesSearchTest {

    @Test
    void matchesSearchQueryByLegacyHexNodeIdEvenIfUserIdChanged() {
        NodeData node = new NodeData(0x55667788);
        node.setNodeId("alice-custom-id");
        node.setLongName("Alice");

        assertTrue(FormNodes.matchesSearchQuery(node, "!55667788"));
        assertTrue(FormNodes.matchesSearchQuery(node, "55667788"));
    }

    @Test
    void matchesSearchQueryForUnnamedNodeByNumericId() {
        NodeData node = new NodeData(0x01020304);

        assertTrue(FormNodes.matchesSearchQuery(node, "!01020304"));
        assertTrue(FormNodes.matchesSearchQuery(node, "16909060"));
        assertFalse(FormNodes.matchesSearchQuery(node, "mesh-owner"));
    }
}
