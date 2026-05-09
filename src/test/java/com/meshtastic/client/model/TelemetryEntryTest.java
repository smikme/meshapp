package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class TelemetryEntryTest {

    private static TelemetryEntry createEntry() {
        return new TelemetryEntry(1_700_000_000L, "!00000001");
    }

    @Test
    void getTimestamp() {
        TelemetryEntry entry = new TelemetryEntry(1_700_000_000L, "!00000001");
        
        assertEquals(1_700_000_000L, entry.getTimestamp());
    }

    @Test
    void getNodeId() {
        TelemetryEntry entry = new TelemetryEntry(1_700_000_000L, "!00000001");
        
        assertEquals("!00000001", entry.getNodeId());
    }

    @Test
    void getBatteryLevelDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0, entry.getBatteryLevel());
    }

    @Test
    void setGetBatteryLevel() {
        TelemetryEntry entry = createEntry();
        
        entry.setBatteryLevel(85);
        
        assertEquals(85, entry.getBatteryLevel());
    }

    @Test
    void getVoltageDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0.0f, entry.getVoltage(), 0.001f);
    }

    @Test
    void setGetVoltage() {
        TelemetryEntry entry = createEntry();
        
        entry.setVoltage(3.7f);
        
        assertEquals(3.7f, entry.getVoltage(), 0.001f);
    }

    @Test
    void getChannelUtilizationDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0.0f, entry.getChannelUtilization(), 0.001f);
    }

    @Test
    void setGetChannelUtilization() {
        TelemetryEntry entry = createEntry();
        
        entry.setChannelUtilization(0.25f);
        
        assertEquals(0.25f, entry.getChannelUtilization(), 0.001f);
    }

    @Test
    void getAirUtilTxDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0.0f, entry.getAirUtilTx(), 0.001f);
    }

    @Test
    void setGetAirUtilTx() {
        TelemetryEntry entry = createEntry();
        
        entry.setAirUtilTx(0.5f);
        
        assertEquals(0.5f, entry.getAirUtilTx(), 0.001f);
    }

    @Test
    void getTemperatureDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0.0f, entry.getTemperature(), 0.001f);
    }

    @Test
    void setGetTemperature() {
        TelemetryEntry entry = createEntry();
        
        entry.setTemperature(25.5f);
        
        assertEquals(25.5f, entry.getTemperature(), 0.001f);
    }

    @Test
    void getRelativeHumidityDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0.0f, entry.getRelativeHumidity(), 0.001f);
    }

    @Test
    void setGetRelativeHumidity() {
        TelemetryEntry entry = createEntry();
        
        entry.setRelativeHumidity(60.0f);
        
        assertEquals(60.0f, entry.getRelativeHumidity(), 0.001f);
    }

    @Test
    void getBarometricPressureDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0.0f, entry.getBarometricPressure(), 0.001f);
    }

    @Test
    void setGetBarometricPressure() {
        TelemetryEntry entry = createEntry();
        
        entry.setBarometricPressure(1013.25f);
        
        assertEquals(1013.25f, entry.getBarometricPressure(), 0.001f);
    }

    @Test
    void getNumPacketsRxDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0, entry.getNumPacketsRx());
    }

    @Test
    void setGetNumPacketsRx() {
        TelemetryEntry entry = createEntry();
        
        entry.setNumPacketsRx(100);
        
        assertEquals(100, entry.getNumPacketsRx());
    }

    @Test
    void getNumPacketsRxBadDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0, entry.getNumPacketsRxBad());
    }

    @Test
    void setGetNumPacketsRxBad() {
        TelemetryEntry entry = createEntry();
        
        entry.setNumPacketsRxBad(5);
        
        assertEquals(5, entry.getNumPacketsRxBad());
    }

    @Test
    void getNumRxDupeDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0, entry.getNumRxDupe());
    }

    @Test
    void setGetNumRxDupe() {
        TelemetryEntry entry = createEntry();
        
        entry.setNumRxDupe(3);
        
        assertEquals(3, entry.getNumRxDupe());
    }

    @Test
    void getNumPacketsTxDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0, entry.getNumPacketsTx());
    }

    @Test
    void setGetNumPacketsTx() {
        TelemetryEntry entry = createEntry();
        
        entry.setNumPacketsTx(50);
        
        assertEquals(50, entry.getNumPacketsTx());
    }

    @Test
    void getNumTxDroppedDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0, entry.getNumTxDropped());
    }

    @Test
    void setGetNumTxDropped() {
        TelemetryEntry entry = createEntry();
        
        entry.setNumTxDropped(2);
        
        assertEquals(2, entry.getNumTxDropped());
    }

    @Test
    void getNumTxRelayDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0, entry.getNumTxRelay());
    }

    @Test
    void setGetNumTxRelay() {
        TelemetryEntry entry = createEntry();
        
        entry.setNumTxRelay(10);
        
        assertEquals(10, entry.getNumTxRelay());
    }

    @Test
    void getNumTxRelayCanceledDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0, entry.getNumTxRelayCanceled());
    }

    @Test
    void setGetNumTxRelayCanceled() {
        TelemetryEntry entry = createEntry();
        
        entry.setNumTxRelayCanceled(1);
        
        assertEquals(1, entry.getNumTxRelayCanceled());
    }

    @Test
    void getRxSnrDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0.0f, entry.getRxSnr(), 0.001f);
    }

    @Test
    void setGetRxSnr() {
        TelemetryEntry entry = createEntry();
        
        entry.setRxSnr(6.5f);
        
        assertEquals(6.5f, entry.getRxSnr(), 0.001f);
    }

    @Test
    void getRxRssiDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0, entry.getRxRssi());
    }

    @Test
    void setGetRxRssi() {
        TelemetryEntry entry = createEntry();
        
        entry.setRxRssi(-80);
        
        assertEquals(-80, entry.getRxRssi());
    }

    @Test
    void getHopStartDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0, entry.getHopStart());
    }

    @Test
    void setGetHopStart() {
        TelemetryEntry entry = createEntry();
        
        entry.setHopStart(3);
        
        assertEquals(3, entry.getHopStart());
    }

    @Test
    void getHopLimitDefaultsToZero() {
        TelemetryEntry entry = createEntry();
        
        assertEquals(0, entry.getHopLimit());
    }

    @Test
    void setGetHopLimit() {
        TelemetryEntry entry = createEntry();
        
        entry.setHopLimit(1);
        
        assertEquals(1, entry.getHopLimit());
    }

    @Test
    void getHopsTraveledCalculatesCorrectly() {
        TelemetryEntry entry = createEntry();
        
        entry.setHopStart(5);
        entry.setHopLimit(2);
        
        assertEquals(3, entry.getHopsTraveled());
    }

    @Test
    void getHopsTraveledReturnsZeroWhenHopStartNotSet() {
        TelemetryEntry entry = createEntry();
        
        entry.setHopLimit(2);
        
        assertEquals(0, entry.getHopsTraveled());
    }

    @Test
    void getHopsTraveledReturnsZeroWhenHopLimitExceedsHopStart() {
        TelemetryEntry entry = createEntry();

        entry.setHopStart(4);
        entry.setHopLimit(6);

        assertFalse(entry.hasValidHopData());
        assertEquals(0, entry.getHopsTraveled());
    }
}
