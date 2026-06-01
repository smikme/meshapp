package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class NodeDataTest {

    @Test
    void constructorGeneratesNodeIdFromNodeNum() {
        NodeData node = new NodeData(42);
        
        assertEquals(42, node.getNodeNum());
        assertEquals("!0000002a", node.getNodeId());
    }

    @Test
    void setGetLongName() {
        NodeData node = new NodeData(1);
        
        node.setLongName("Device Long Name");
        
        assertEquals("Device Long Name", node.getLongName());
    }

    @Test
    void setGetShortName() {
        NodeData node = new NodeData(1);
        
        node.setShortName("DLN");
        
        assertEquals("DLN", node.getShortName());
    }

    @Test
    void setGetNodeId() {
        NodeData node = new NodeData(1);
        
        node.setNodeId("!abcdef12");
        
        assertEquals("!abcdef12", node.getNodeId());
    }

    @Test
    void setGetLatitude() {
        NodeData node = new NodeData(1);
        
        node.setLatitude(55.751244);
        
        assertEquals(55.751244, node.getLatitude(), 0.000001);
    }

    @Test
    void setGetLongitude() {
        NodeData node = new NodeData(1);
        
        node.setLongitude(37.618423);
        
        assertEquals(37.618423, node.getLongitude(), 0.000001);
    }

    @Test
    void setGetAltitude() {
        NodeData node = new NodeData(1);
        
        node.setAltitude(200);
        
        assertEquals(200, node.getAltitude());
    }

    @Test
    void setGetSnr() {
        NodeData node = new NodeData(1);
        
        node.setSnr(5.5f);
        
        assertEquals(5.5f, node.getSnr(), 0.001f);
    }

    @Test
    void setGetLastHeard() {
        NodeData node = new NodeData(1);
        
        node.setLastHeard(1700000000);
        
        assertEquals(1700000000, node.getLastHeard());
    }

    @Test
    void setGetBatteryLevel() {
        NodeData node = new NodeData(1);
        
        node.setBatteryLevel(85);
        
        assertEquals(85, node.getBatteryLevel());
    }

    @Test
    void setGetVoltage() {
        NodeData node = new NodeData(1);
        
        node.setVoltage(3.7f);
        
        assertEquals(3.7f, node.getVoltage(), 0.001f);
    }

    @Test
    void setGetChannelUtilization() {
        NodeData node = new NodeData(1);
        
        node.setChannelUtilization(0.25f);
        
        assertEquals(0.25f, node.getChannelUtilization(), 0.001f);
    }

    @Test
    void setGetAirUtilTx() {
        NodeData node = new NodeData(1);
        
        node.setAirUtilTx(0.5f);
        
        assertEquals(0.5f, node.getAirUtilTx(), 0.001f);
    }

    @Test
    void setGetUptimeSeconds() {
        NodeData node = new NodeData(1);
        
        node.setUptimeSeconds(3600L);
        
        assertEquals(3600L, node.getUptimeSeconds());
    }

    @Test
    void setGetTemperature() {
        NodeData node = new NodeData(1);
        
        node.setTemperature(25.5f);
        
        assertEquals(25.5f, node.getTemperature(), 0.001f);
    }

    @Test
    void setGetRelativeHumidity() {
        NodeData node = new NodeData(1);
        
        node.setRelativeHumidity(60.0f);
        
        assertEquals(60.0f, node.getRelativeHumidity(), 0.001f);
    }

    @Test
    void setGetBarometricPressure() {
        NodeData node = new NodeData(1);
        
        node.setBarometricPressure(1013.25f);
        
        assertEquals(1013.25f, node.getBarometricPressure(), 0.001f);
    }

    @Test
    void setGetHopsAway() {
        NodeData node = new NodeData(1);
        
        node.setHopsAway(2);
        
        assertEquals(2, node.getHopsAway());
    }

    @Test
    void hasHopsAwayDefaultsToFalse() {
        NodeData node = new NodeData(1);
        
        assertFalse(node.hasHopsAway());
    }

    @Test
    void clearHopsAway() {
        NodeData node = new NodeData(1);
        
        node.setHopsAway(3);
        assertTrue(node.hasHopsAway());
        
        node.clearHopsAway();
        
        assertFalse(node.hasHopsAway());
    }

    @Test
    void isDirectNeighborReturnsTrueWhenHopsAwayIsZero() {
        NodeData node = new NodeData(1);
        
        node.setHopsAway(0);
        
        assertTrue(node.isDirectNeighbor());
    }

    @Test
    void isDirectNeighborReturnsFalseWhenHopsAwayIsNotZero() {
        NodeData node = new NodeData(1);
        
        node.setHopsAway(2);
        
        assertFalse(node.isDirectNeighbor());
    }

    @Test
    void setGetRole() {
        NodeData node = new NodeData(1);
        
        node.setRole("RADIO");
        
        assertEquals("RADIO", node.getRole());
    }

    @Test
    void setGetHwModel() {
        NodeData node = new NodeData(1);
        
        node.setHwModel("tbeam");
        
        assertEquals("tbeam", node.getHwModel());
    }

    @Test
    void formatTimeConvertsEpochSeconds() {
        // 1700000000 = Wed Nov 15 2023 14:13:20 GMT+0000
        String formatted = NodeData.formatTime(1700000000);
        
        // Проверяем формат dd.MM.yy HH:mm
        assertTrue(formatted.matches("\\d{2}\\.\\d{2}\\.\\d{2} \\d{2}:\\d{2}"));
    }

    @Test
    void translateRoleReturnsUnknownForUnknown() {
        // translateRole возвращает исходную строку если не найден перевод
        assertEquals("unknown", NodeData.translateRole("unknown"));
    }

    @Test
    void hasNameReturnsTrueWhenLongNameExists() {
        NodeData node = new NodeData(1);
        
        node.setLongName("Device");
        
        assertTrue(node.hasName());
    }

    @Test
    void hasNameReturnsFalseWhenLongNameEmpty() {
        NodeData node = new NodeData(1);
        
        assertFalse(node.hasName());
    }

    @Test
    void translateRoleReturnsNullForEmpty() {
        assertNull(NodeData.translateRole(""));
    }

    @Test
    void translateRoleReturnsNullForNull() {
        assertNull(NodeData.translateRole(null));
    }
}
