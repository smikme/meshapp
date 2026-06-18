package com.meshtastic.client.lua.api;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.service.NodeCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaTelemetryApiTest {

    private static final String OWNER_NODE_ID = "!12345678";
    private static final String OTHER_OWNER_NODE_ID = "!87654321";

    @TempDir
    Path tempHome;

    private DeviceState state;
    private LuaTable telemetry;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        telemetry = new LuaTelemetryApi(
                new LuaSandboxContext(
                        1L,
                        "test",
                        state,
                        null,
                        null,
                        OWNER_NODE_ID,
                        LuaScriptService.getInstance(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                new LuaValueMapper(state))
                .create();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void queryFiltersByNodeVariantAndInclusiveTimeWindow() {
        persistEnvironment(100, "!bbbbbbbb", OWNER_NODE_ID, 20.0f, 0.7f, 11.0f);
        persistEnvironment(200, "!bbbbbbbb", OWNER_NODE_ID, 21.0f, 0.8f, 12.0f);
        persistEnvironment(300, "!bbbbbbbb", OWNER_NODE_ID, 22.0f, 0.9f, 13.0f);
        persistEnvironment(200, "!cccccccc", OWNER_NODE_ID, 23.0f, 1.0f, 14.0f);
        persistDevice(200, "!bbbbbbbb", OWNER_NODE_ID, 4.1f);
        persistEnvironment(200, "!bbbbbbbb", OTHER_OWNER_NODE_ID, 24.0f, 1.1f, 15.0f);

        LuaTable options = new LuaTable();
        options.set("node_id", "!bbbbbbbb");
        options.set("variant", "ENVIRONMENT_METRICS");
        options.set("since", 100);
        options.set("until", 200);
        options.set("limit", 10);

        LuaTable result = telemetry.get("query").call(options).checktable();

        assertEquals(2, result.length());
        LuaTable first = result.get(1).checktable();
        LuaTable second = result.get(2).checktable();
        assertEquals(100, first.get("timestamp").checklong());
        assertEquals(200, second.get("timestamp").checklong());
        assertEquals("!bbbbbbbb", first.get("node_id").checkjstring());
        assertEquals("ENVIRONMENT_METRICS", first.get("variant").checkjstring());
        assertEquals(20.0, first.get("temperature").checkdouble(), 0.001);
        assertEquals(0.7, first.get("gas_resistance").checkdouble(), 0.001);
        assertEquals(11.0, first.get("radiation").checkdouble(), 0.001);
        assertEquals(19.5, first.get("one_wire_temperature").checktable().get(1).checkdouble(), 0.001);
    }

    @Test
    void recentReturnsNewestFirstAndForNodeReturnsChronologicalRows() {
        persistEnvironment(100, "!bbbbbbbb", OWNER_NODE_ID, 20.0f, 0.7f, 11.0f);
        persistEnvironment(200, "!bbbbbbbb", OWNER_NODE_ID, 21.0f, 0.8f, 12.0f);
        persistEnvironment(300, "!bbbbbbbb", OWNER_NODE_ID, 22.0f, 0.9f, 13.0f);

        LuaTable recentOptions = new LuaTable();
        recentOptions.set("limit", 2);
        LuaTable recent = telemetry.get("recent")
                .invoke(LuaValue.varargsOf(new LuaValue[] {recentOptions}))
                .arg1()
                .checktable();

        assertEquals(2, recent.length());
        assertEquals(300, recent.get(1).checktable().get("timestamp").checklong());
        assertEquals(200, recent.get(2).checktable().get("timestamp").checklong());

        LuaTable forNodeOptions = new LuaTable();
        forNodeOptions.set("since", 100);
        forNodeOptions.set("until", 300);
        forNodeOptions.set("limit", 2);
        LuaTable forNode = telemetry.get("for_node")
                .invoke(LuaValue.varargsOf(LuaValue.valueOf("!bbbbbbbb"), forNodeOptions))
                .arg1()
                .checktable();

        assertEquals(2, forNode.length());
        assertEquals(100, forNode.get(1).checktable().get("timestamp").checklong());
        assertEquals(200, forNode.get(2).checktable().get("timestamp").checklong());
    }

    @Test
    void latestReturnsNewestRowForNode() {
        persistEnvironment(100, "!bbbbbbbb", OWNER_NODE_ID, 20.0f, 0.7f, 11.0f);
        persistEnvironment(200, "!bbbbbbbb", OWNER_NODE_ID, 21.0f, 0.8f, 12.0f);
        persistEnvironment(300, "!cccccccc", OWNER_NODE_ID, 22.0f, 0.9f, 13.0f);

        LuaTable latest = telemetry.get("latest")
                .invoke(LuaValue.varargsOf(new LuaValue[] {LuaValue.valueOf("!bbbbbbbb")}))
                .arg1()
                .checktable();

        assertEquals(200, latest.get("timestamp").checklong());
        assertEquals("!bbbbbbbb", latest.get("node_id").checkjstring());
    }

    @Test
    void queryAcceptsIsoDateTimeBounds() {
        persistEnvironment(1_700_000_000L, "!bbbbbbbb", OWNER_NODE_ID, 20.0f, 0.7f, 11.0f);
        persistEnvironment(1_700_000_060L, "!bbbbbbbb", OWNER_NODE_ID, 21.0f, 0.8f, 12.0f);

        LuaTable options = new LuaTable();
        options.set("node_id", "!bbbbbbbb");
        options.set("since", "2023-11-14T22:13:20Z");
        options.set("until", "2023-11-14T22:13:20Z");

        LuaTable result = telemetry.get("query").call(options).checktable();

        assertEquals(1, result.length());
        assertEquals(1_700_000_000L, result.get(1).checktable().get("timestamp").checklong());
    }

    @Test
    void fieldsExposeTelemetryTableKeys() {
        LuaTable fields = telemetry.get("fields").call().checktable();

        assertTrue(contains(fields, "timestamp"));
        assertTrue(contains(fields, "node_id"));
        assertTrue(contains(fields, "gas_resistance"));
        assertTrue(contains(fields, "radiation"));
        assertTrue(contains(fields, "one_wire_temperature"));
    }

    @Test
    void queryRejectsInvalidFilters() {
        LuaTable reversedWindow = new LuaTable();
        reversedWindow.set("since", 200);
        reversedWindow.set("until", 100);
        assertThrows(LuaError.class, () -> telemetry.get("query").call(reversedWindow));

        LuaTable invalidNode = new LuaTable();
        invalidNode.set("node_id", "not-a-node");
        assertThrows(LuaError.class, () -> telemetry.get("query").call(invalidNode));

        LuaTable invalidVariant = new LuaTable();
        invalidVariant.set("variant", "NO_SUCH_VARIANT");
        assertThrows(LuaError.class, () -> telemetry.get("query").call(invalidVariant));
    }

    private static void persistEnvironment(long timestamp,
                                           String nodeId,
                                           String ownerNodeId,
                                           float temperature,
                                           float gasResistance,
                                           float radiation) {
        TelemetryEntry entry = new TelemetryEntry(timestamp, nodeId);
        entry.setTelemetryVariant("ENVIRONMENT_METRICS");
        entry.setTemperature(temperature);
        entry.setGasResistance(gasResistance);
        entry.setIaq(42L);
        entry.setRadiation(radiation);
        entry.addOneWireTemperature(19.5f);
        NodeCacheService.getInstance().persistTelemetry(entry, ownerNodeId);
    }

    private static void persistDevice(long timestamp, String nodeId, String ownerNodeId, float voltage) {
        TelemetryEntry entry = new TelemetryEntry(timestamp, nodeId);
        entry.setTelemetryVariant("DEVICE_METRICS");
        entry.setVoltage(voltage);
        NodeCacheService.getInstance().persistTelemetry(entry, ownerNodeId);
    }

    private static boolean contains(LuaTable table, String value) {
        for (int i = 1; i <= table.length(); i++) {
            if (value.equals(table.get(i).checkjstring())) {
                return true;
            }
        }
        return false;
    }
}
