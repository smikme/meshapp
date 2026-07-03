package com.meshtastic.client.protocol.rpc;

import com.google.gson.JsonObject;
import com.meshtastic.client.model.TelemetryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class RemoteTelemetryJsonTest {

    @Test
    void roundTripsDashboardTelemetrySnapshot() {
        TelemetryEntry entry = new TelemetryEntry(1_700_000_000L, "!12345678");
        entry.setTelemetryVariant("DEVICE_METRICS");
        entry.setBatteryLevel(87);
        entry.setVoltage(4.1f);
        entry.setChannelUtilization(12.5f);
        entry.setAirUtilTx(1.25f);
        entry.setTemperature(24.5f);
        entry.setRelativeHumidity(53.25f);
        entry.setBarometricPressure(1002.5f);
        entry.setRadiation(0.12f);
        entry.setNumPacketsRx(100);
        entry.setNumPacketsRxBad(2);
        entry.setNumRxDupe(3);
        entry.setNumPacketsTx(50);
        entry.setNumTxDropped(1);
        entry.setNumTxRelay(5);
        entry.setNumTxRelayCanceled(1);
        entry.setRxSnr(8.5f);
        entry.setRxRssi(-76);
        entry.setHopStart(5);
        entry.setHopLimit(3);
        entry.setOneWireTemperatures(List.of(21.5f, 22.0f));

        JsonObject result = RemoteTelemetryJson.dashboardResult(
                "!owner",
                "!12345678",
                List.of(entry),
                List.of(entry));

        List<TelemetryEntry> entries = RemoteTelemetryJson.parseEntries(result);
        List<TelemetryEntry> qualityEntries = RemoteTelemetryJson.parseQualityEntries(result);

        assertEquals("!owner", RemoteTelemetryJson.ownerNodeId(result));
        assertEquals("!12345678", RemoteTelemetryJson.nodeId(result));
        assertEquals(1, entries.size());
        assertEquals(1, qualityEntries.size());

        TelemetryEntry parsed = entries.getFirst();
        assertEquals(1_700_000_000L, parsed.getTimestamp());
        assertEquals("!12345678", parsed.getNodeId());
        assertEquals("DEVICE_METRICS", parsed.getTelemetryVariant());
        assertEquals(87, parsed.getBatteryLevel());
        assertEquals(4.1f, parsed.getVoltage());
        assertEquals(12.5f, parsed.getChannelUtilization());
        assertEquals(1.25f, parsed.getAirUtilTx());
        assertEquals(24.5f, parsed.getTemperature());
        assertEquals(53.25f, parsed.getRelativeHumidity());
        assertEquals(1002.5f, parsed.getBarometricPressure());
        assertEquals(0.12f, parsed.getRadiation());
        assertEquals(100, parsed.getNumPacketsRx());
        assertEquals(2, parsed.getNumPacketsRxBad());
        assertEquals(3, parsed.getNumRxDupe());
        assertEquals(50, parsed.getNumPacketsTx());
        assertEquals(1, parsed.getNumTxDropped());
        assertEquals(5, parsed.getNumTxRelay());
        assertEquals(1, parsed.getNumTxRelayCanceled());
        assertEquals(8.5f, parsed.getRxSnr());
        assertEquals(-76, parsed.getRxRssi());
        assertEquals(5, parsed.getHopStart());
        assertEquals(3, parsed.getHopLimit());
        assertFalse(parsed.getOneWireTemperatures().isEmpty());
    }

    @Test
    void dashboardParamsOmitBlankNodeAndZeroBounds() {
        JsonObject params = RemoteTelemetryJson.dashboardParams("", 0, 0);

        assertFalse(params.has("nodeId"));
        assertFalse(params.has("sinceEpoch"));
        assertFalse(params.has("maxFutureTs"));
    }
}
