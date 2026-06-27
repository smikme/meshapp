package com.meshtastic.client.protocol.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.meshtastic.client.model.NodeData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class RemoteNodeJsonTest {

    @Test
    void roundTripsNodeSnapshotAndFlags() {
        NodeData node = new NodeData(0x12345678);
        node.setNodeId("!12345678");
        node.setLongName("Remote Node");
        node.setShortName("RN");
        node.setLatitude(55.75);
        node.setLongitude(37.62);
        node.setAltitude(180);
        node.setSnr(8.5f);
        node.setLastHeard(1234);
        node.setBatteryLevel(87);
        node.setExternallyPowered(true);
        node.setVoltage(4.1f);
        node.setChannelUtilization(12.5f);
        node.setAirUtilTx(1.25f);
        node.setUptimeSeconds(3600);
        node.setTemperature(24.5f);
        node.setRelativeHumidity(53.25f);
        node.setBarometricPressure(1002.5f);
        node.setHopsAway(2);
        node.setChannel(3);
        node.setRole("CLIENT");
        node.setHwModel("TBEAM");
        node.setPublicKey(new byte[] {1, 2, 3, 4});
        node.setUnmessagable(false);
        node.setLicensed(true);

        JsonObject result = new JsonObject();
        result.addProperty("ownerNodeId", "!owner");
        JsonArray items = new JsonArray();
        items.add(RemoteNodeJson.nodeToJson(node, true, false));
        result.add("items", items);

        List<NodeData> parsed = RemoteNodeJson.parseNodes(result);
        Map<String, Boolean> favorites = RemoteNodeJson.parseFavoriteFlags(result);
        Map<String, Boolean> ignored = RemoteNodeJson.parseIgnoredFlags(result);

        assertEquals("!owner", RemoteNodeJson.ownerNodeId(result));
        assertEquals(1, parsed.size());
        NodeData parsedNode = parsed.getFirst();
        assertEquals("Remote Node", parsedNode.getLongName());
        assertEquals("RN", parsedNode.getShortName());
        assertEquals(55.75, parsedNode.getLatitude());
        assertEquals(37.62, parsedNode.getLongitude());
        assertEquals(180, parsedNode.getAltitude());
        assertEquals(8.5f, parsedNode.getSnr());
        assertEquals(2, parsedNode.getHopsAway());
        assertTrue(parsedNode.hasHopsAway());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, parsedNode.getPublicKey());
        assertTrue(parsedNode.isLicensed());
        assertTrue(favorites.get("!12345678"));
        assertFalse(ignored.get("!12345678"));
    }

    @Test
    void listParamsIncludeOfflineFilterHints() {
        JsonObject params = RemoteNodeJson.listParams(true, false);

        assertTrue(params.get("includeFavorites").getAsBoolean());
        assertFalse(params.get("includeIgnored").getAsBoolean());
    }

    @Test
    void nodeIdParamsIncludeOnlyRequestedNode() {
        JsonObject params = RemoteNodeJson.nodeIdParams("!abcdef12");

        assertEquals("!abcdef12", params.get("nodeId").getAsString());
        assertFalse(params.has("nodeNum"));
    }
}
