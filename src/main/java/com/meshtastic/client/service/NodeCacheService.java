package com.meshtastic.client.service;

import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local persistent node cache.
 * <p>
 * Stores accumulated node information between application sessions in the
 * embedded H2 database {@code ~/.meshapp/nodedb}. Used as a fallback when
 * {@link com.meshtastic.client.model.DeviceState#getNodeDb()} has no data.
 * <p>
 * The key is {@code node_id}, a string such as {@code !9e755af0}, which is the
 * stable node identifier from Meshtastic {@code User.id}.
 * <p>
 * Updated from config exchange data and live packets (NODEINFO_APP,
 * POSITION_APP, TELEMETRY_APP). Database writes happen immediately through
 * MERGE INTO, without debounce.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class NodeCacheService {

    private static final Logger log = LoggerFactory.getLogger(NodeCacheService.class);
    private static final int MAX_MISSING_NODE_IDS = 10_000;

    private static NodeCacheService instance;

    private final ConcurrentHashMap<String, NodeData> cache = new ConcurrentHashMap<>();
    private final Set<String> missingNodeIds = newBoundedMissingNodeSet();
    private Connection dbConnection;
    private PreparedStatement mergeStmt;
    private PreparedStatement insertTelemetryStmt;
    private PreparedStatement insertOneWireTelemetryStmt;

    private static final String[] TELEMETRY_HISTORY_COLUMNS = {
            "ts",
            "node_id",
            "owner_node_id",
            "packet_id",
            "telemetry_variant",
            "battery_level",
            "externally_powered",
            "voltage",
            "channel_utilization",
            "air_util_tx",
            "device_uptime_seconds",
            "temperature",
            "relative_humidity",
            "barometric_pressure",
            "gas_resistance",
            "environment_voltage",
            "environment_current",
            "iaq",
            "distance",
            "lux",
            "white_lux",
            "ir_lux",
            "uv_lux",
            "wind_direction",
            "wind_speed",
            "weight",
            "wind_gust",
            "wind_lull",
            "radiation",
            "rainfall_1h",
            "rainfall_24h",
            "soil_moisture",
            "soil_temperature",
            "pm10_standard",
            "pm25_standard",
            "pm100_standard",
            "pm10_environmental",
            "pm25_environmental",
            "pm100_environmental",
            "particles_03um",
            "particles_05um",
            "particles_10um",
            "particles_25um",
            "particles_50um",
            "particles_100um",
            "co2",
            "co2_temperature",
            "co2_humidity",
            "form_formaldehyde",
            "form_humidity",
            "form_temperature",
            "pm40_standard",
            "particles_40um",
            "pm_temperature",
            "pm_humidity",
            "pm_voc_idx",
            "pm_nox_idx",
            "particles_tps",
            "ch1_voltage",
            "ch1_current",
            "ch2_voltage",
            "ch2_current",
            "ch3_voltage",
            "ch3_current",
            "ch4_voltage",
            "ch4_current",
            "ch5_voltage",
            "ch5_current",
            "ch6_voltage",
            "ch6_current",
            "ch7_voltage",
            "ch7_current",
            "ch8_voltage",
            "ch8_current",
            "num_packets_rx",
            "num_packets_rx_bad",
            "num_rx_dupe",
            "num_packets_tx",
            "num_tx_dropped",
            "num_tx_relay",
            "num_tx_relay_canceled",
            "local_uptime_seconds",
            "num_online_nodes",
            "num_total_nodes",
            "heap_total_bytes",
            "heap_free_bytes",
            "noise_floor",
            "health_heart_bpm",
            "health_spo2",
            "health_temperature",
            "host_uptime_seconds",
            "host_freemem_bytes",
            "host_diskfree1_bytes",
            "host_diskfree2_bytes",
            "host_diskfree3_bytes",
            "host_load1",
            "host_load5",
            "host_load15",
            "host_user_string",
            "traffic_packets_inspected",
            "traffic_position_dedup_drops",
            "traffic_nodeinfo_cache_hits",
            "traffic_rate_limit_drops",
            "traffic_unknown_packet_drops",
            "traffic_hop_exhausted_packets",
            "traffic_router_hops_preserved",
            "rx_snr",
            "rx_rssi",
            "hop_start",
            "hop_limit"
    };

    private static final String[][] EXTENDED_TELEMETRY_COLUMNS = {
            {"packet_id", "packet_id BIGINT DEFAULT 0"},
            {"telemetry_variant", "telemetry_variant VARCHAR(40)"},
            {"device_uptime_seconds", "device_uptime_seconds BIGINT"},
            {"gas_resistance", "gas_resistance REAL"},
            {"environment_voltage", "environment_voltage REAL"},
            {"environment_current", "environment_current REAL"},
            {"iaq", "iaq BIGINT"},
            {"distance", "distance REAL"},
            {"lux", "lux REAL"},
            {"white_lux", "white_lux REAL"},
            {"ir_lux", "ir_lux REAL"},
            {"uv_lux", "uv_lux REAL"},
            {"wind_direction", "wind_direction BIGINT"},
            {"wind_speed", "wind_speed REAL"},
            {"weight", "weight REAL"},
            {"wind_gust", "wind_gust REAL"},
            {"wind_lull", "wind_lull REAL"},
            {"radiation", "radiation REAL"},
            {"rainfall_1h", "rainfall_1h REAL"},
            {"rainfall_24h", "rainfall_24h REAL"},
            {"soil_moisture", "soil_moisture BIGINT"},
            {"soil_temperature", "soil_temperature REAL"},
            {"pm10_standard", "pm10_standard BIGINT"},
            {"pm25_standard", "pm25_standard BIGINT"},
            {"pm100_standard", "pm100_standard BIGINT"},
            {"pm10_environmental", "pm10_environmental BIGINT"},
            {"pm25_environmental", "pm25_environmental BIGINT"},
            {"pm100_environmental", "pm100_environmental BIGINT"},
            {"particles_03um", "particles_03um BIGINT"},
            {"particles_05um", "particles_05um BIGINT"},
            {"particles_10um", "particles_10um BIGINT"},
            {"particles_25um", "particles_25um BIGINT"},
            {"particles_50um", "particles_50um BIGINT"},
            {"particles_100um", "particles_100um BIGINT"},
            {"co2", "co2 BIGINT"},
            {"co2_temperature", "co2_temperature REAL"},
            {"co2_humidity", "co2_humidity REAL"},
            {"form_formaldehyde", "form_formaldehyde REAL"},
            {"form_humidity", "form_humidity REAL"},
            {"form_temperature", "form_temperature REAL"},
            {"pm40_standard", "pm40_standard BIGINT"},
            {"particles_40um", "particles_40um BIGINT"},
            {"pm_temperature", "pm_temperature REAL"},
            {"pm_humidity", "pm_humidity REAL"},
            {"pm_voc_idx", "pm_voc_idx REAL"},
            {"pm_nox_idx", "pm_nox_idx REAL"},
            {"particles_tps", "particles_tps REAL"},
            {"ch1_voltage", "ch1_voltage REAL"},
            {"ch1_current", "ch1_current REAL"},
            {"ch2_voltage", "ch2_voltage REAL"},
            {"ch2_current", "ch2_current REAL"},
            {"ch3_voltage", "ch3_voltage REAL"},
            {"ch3_current", "ch3_current REAL"},
            {"ch4_voltage", "ch4_voltage REAL"},
            {"ch4_current", "ch4_current REAL"},
            {"ch5_voltage", "ch5_voltage REAL"},
            {"ch5_current", "ch5_current REAL"},
            {"ch6_voltage", "ch6_voltage REAL"},
            {"ch6_current", "ch6_current REAL"},
            {"ch7_voltage", "ch7_voltage REAL"},
            {"ch7_current", "ch7_current REAL"},
            {"ch8_voltage", "ch8_voltage REAL"},
            {"ch8_current", "ch8_current REAL"},
            {"local_uptime_seconds", "local_uptime_seconds BIGINT"},
            {"num_online_nodes", "num_online_nodes BIGINT"},
            {"num_total_nodes", "num_total_nodes BIGINT"},
            {"heap_total_bytes", "heap_total_bytes BIGINT"},
            {"heap_free_bytes", "heap_free_bytes BIGINT"},
            {"noise_floor", "noise_floor INT"},
            {"health_heart_bpm", "health_heart_bpm BIGINT"},
            {"health_spo2", "health_spo2 BIGINT"},
            {"health_temperature", "health_temperature REAL"},
            {"host_uptime_seconds", "host_uptime_seconds BIGINT"},
            {"host_freemem_bytes", "host_freemem_bytes BIGINT"},
            {"host_diskfree1_bytes", "host_diskfree1_bytes BIGINT"},
            {"host_diskfree2_bytes", "host_diskfree2_bytes BIGINT"},
            {"host_diskfree3_bytes", "host_diskfree3_bytes BIGINT"},
            {"host_load1", "host_load1 BIGINT"},
            {"host_load5", "host_load5 BIGINT"},
            {"host_load15", "host_load15 BIGINT"},
            {"host_user_string", "host_user_string VARCHAR(512)"},
            {"traffic_packets_inspected", "traffic_packets_inspected BIGINT"},
            {"traffic_position_dedup_drops", "traffic_position_dedup_drops BIGINT"},
            {"traffic_nodeinfo_cache_hits", "traffic_nodeinfo_cache_hits BIGINT"},
            {"traffic_rate_limit_drops", "traffic_rate_limit_drops BIGINT"},
            {"traffic_unknown_packet_drops", "traffic_unknown_packet_drops BIGINT"},
            {"traffic_hop_exhausted_packets", "traffic_hop_exhausted_packets BIGINT"},
            {"traffic_router_hops_preserved", "traffic_router_hops_preserved BIGINT"}
    };

    private NodeCacheService() {
        initDb();
    }

    public static synchronized NodeCacheService getInstance() {
        if (instance == null) {
            instance = new NodeCacheService();
        }
        return instance;
    }

    public static synchronized NodeCacheService getIfInitialized() {
        return instance;
    }

    public static synchronized void closeIfInitialized() {
        if (instance != null) {
            instance.close();
        }
    }

    private static Set<String> newBoundedMissingNodeSet() {
        int capacity = (int) (MAX_MISSING_NODE_IDS / 0.75f) + 1;
        return Collections.synchronizedSet(Collections.newSetFromMap(
                new LinkedHashMap<String, Boolean>(capacity, 0.75f) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                        return size() > MAX_MISSING_NODE_IDS;
                    }
                }));
    }

    private static String telemetryInsertSql() {
        String placeholders = String.join(", ", Collections.nCopies(TELEMETRY_HISTORY_COLUMNS.length, "?"));
        return "INSERT INTO telemetry_history ("
                + String.join(", ", TELEMETRY_HISTORY_COLUMNS)
                + ") VALUES ("
                + placeholders
                + ")";
    }

    private static void addExtendedTelemetryColumns(Statement stmt) {
        for (String[] column : EXTENDED_TELEMETRY_COLUMNS) {
            try {
                stmt.execute("ALTER TABLE telemetry_history ADD COLUMN IF NOT EXISTS " + column[1]);
            } catch (SQLException ignored) {
                // Older H2 states can already contain a subset of these columns.
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Database initialization.
    // ═══════════════════════════════════════════════════════════

    private void initDb() {
        try {
            closeStatements();
            dbConnection = DatabaseProvider.getConnection();
            if (dbConnection == null) {
                log.error("Node cache DB initialization skipped because database connection is unavailable");
                return;
            }

            try (Statement stmt = dbConnection.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS nodes (
                        node_id       VARCHAR(20) PRIMARY KEY,
                        node_num      INT,
                        long_name     VARCHAR(100),
                        short_name    VARCHAR(10),
                        role          VARCHAR(30),
                        hw_model      VARCHAR(50),
                        latitude      DOUBLE,
                        longitude     DOUBLE,
                        altitude      INT,
                        snr           REAL,
                        last_heard    INT,
                        battery_level INT,
                        externally_powered BOOLEAN DEFAULT FALSE,
                        voltage       REAL,
                        hops_away     INT,
                        channel       INT DEFAULT 0,
                        public_key    VARBINARY,
                        unmessagable  BOOLEAN,
                        favorite      BOOLEAN DEFAULT FALSE,
                        ignored       BOOLEAN DEFAULT FALSE
                    )
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS node_flags (
                        owner_node_id VARCHAR(20) NOT NULL DEFAULT '',
                        node_id       VARCHAR(20) NOT NULL,
                        favorite      BOOLEAN DEFAULT FALSE,
                        ignored       BOOLEAN DEFAULT FALSE,
                        PRIMARY KEY (owner_node_id, node_id)
                    )
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS telemetry_history (
                        id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
                        ts                  BIGINT NOT NULL,
                        node_id             VARCHAR(20) NOT NULL,
                        owner_node_id       VARCHAR(20) NOT NULL DEFAULT '',
                        packet_id           BIGINT DEFAULT 0,
                        telemetry_variant   VARCHAR(40),
                        battery_level       INT,
                        externally_powered  BOOLEAN DEFAULT FALSE,
                        voltage             REAL,
                        channel_utilization REAL,
                        air_util_tx         REAL,
                        device_uptime_seconds BIGINT,
                        temperature         REAL,
                        relative_humidity   REAL,
                        barometric_pressure REAL,
                        gas_resistance      REAL,
                        environment_voltage REAL,
                        environment_current REAL,
                        iaq                 BIGINT,
                        distance            REAL,
                        lux                 REAL,
                        white_lux           REAL,
                        ir_lux              REAL,
                        uv_lux              REAL,
                        wind_direction      BIGINT,
                        wind_speed          REAL,
                        weight              REAL,
                        wind_gust           REAL,
                        wind_lull           REAL,
                        radiation           REAL,
                        rainfall_1h         REAL,
                        rainfall_24h        REAL,
                        soil_moisture       BIGINT,
                        soil_temperature    REAL,
                        pm10_standard       BIGINT,
                        pm25_standard       BIGINT,
                        pm100_standard      BIGINT,
                        pm10_environmental  BIGINT,
                        pm25_environmental  BIGINT,
                        pm100_environmental BIGINT,
                        particles_03um      BIGINT,
                        particles_05um      BIGINT,
                        particles_10um      BIGINT,
                        particles_25um      BIGINT,
                        particles_50um      BIGINT,
                        particles_100um     BIGINT,
                        co2                 BIGINT,
                        co2_temperature     REAL,
                        co2_humidity        REAL,
                        form_formaldehyde   REAL,
                        form_humidity       REAL,
                        form_temperature    REAL,
                        pm40_standard       BIGINT,
                        particles_40um      BIGINT,
                        pm_temperature      REAL,
                        pm_humidity         REAL,
                        pm_voc_idx          REAL,
                        pm_nox_idx          REAL,
                        particles_tps       REAL,
                        ch1_voltage         REAL,
                        ch1_current         REAL,
                        ch2_voltage         REAL,
                        ch2_current         REAL,
                        ch3_voltage         REAL,
                        ch3_current         REAL,
                        ch4_voltage         REAL,
                        ch4_current         REAL,
                        ch5_voltage         REAL,
                        ch5_current         REAL,
                        ch6_voltage         REAL,
                        ch6_current         REAL,
                        ch7_voltage         REAL,
                        ch7_current         REAL,
                        ch8_voltage         REAL,
                        ch8_current         REAL,
                        num_packets_rx      INT DEFAULT 0,
                        num_packets_rx_bad  INT DEFAULT 0,
                        num_rx_dupe         INT DEFAULT 0,
                        num_packets_tx      INT DEFAULT 0,
                        num_tx_dropped      INT DEFAULT 0,
                        num_tx_relay        INT DEFAULT 0,
                        num_tx_relay_canceled INT DEFAULT 0,
                        local_uptime_seconds BIGINT,
                        num_online_nodes    BIGINT,
                        num_total_nodes     BIGINT,
                        heap_total_bytes    BIGINT,
                        heap_free_bytes     BIGINT,
                        noise_floor         INT,
                        health_heart_bpm    BIGINT,
                        health_spo2         BIGINT,
                        health_temperature  REAL,
                        host_uptime_seconds BIGINT,
                        host_freemem_bytes  BIGINT,
                        host_diskfree1_bytes BIGINT,
                        host_diskfree2_bytes BIGINT,
                        host_diskfree3_bytes BIGINT,
                        host_load1          BIGINT,
                        host_load5          BIGINT,
                        host_load15         BIGINT,
                        host_user_string    VARCHAR(512),
                        traffic_packets_inspected BIGINT,
                        traffic_position_dedup_drops BIGINT,
                        traffic_nodeinfo_cache_hits BIGINT,
                        traffic_rate_limit_drops BIGINT,
                        traffic_unknown_packet_drops BIGINT,
                        traffic_hop_exhausted_packets BIGINT,
                        traffic_router_hops_preserved BIGINT,
                        rx_snr              REAL DEFAULT 0,
                        rx_rssi             INT DEFAULT 0,
                        hop_start           INT DEFAULT 0,
                        hop_limit           INT DEFAULT 0
                    )
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS telemetry_one_wire_temperature (
                        telemetry_id BIGINT NOT NULL,
                        sensor_index INT NOT NULL,
                        temperature  REAL,
                        PRIMARY KEY (telemetry_id, sensor_index),
                        CONSTRAINT fk_telemetry_one_wire
                            FOREIGN KEY (telemetry_id) REFERENCES telemetry_history(id)
                            ON DELETE CASCADE
                    )
                    """);

                // Migration for existing DBs: add packet stats columns
                for (String col : new String[]{"num_packets_rx", "num_packets_rx_bad", "num_rx_dupe",
                        "num_packets_tx", "num_tx_dropped", "num_tx_relay", "num_tx_relay_canceled"}) {
                    try {
                        stmt.execute("ALTER TABLE telemetry_history ADD COLUMN " + col + " INT DEFAULT 0");
                    } catch (SQLException ignored) {
                        // Column already exists
                    }
                }
                // Migration: connection quality columns
                try { stmt.execute("ALTER TABLE telemetry_history ADD COLUMN rx_snr REAL DEFAULT 0"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE telemetry_history ADD COLUMN rx_rssi INT DEFAULT 0"); } catch (SQLException ignored) {}
                // Migration: separate power-source flag from battery percentage
                try { stmt.execute("ALTER TABLE telemetry_history ADD COLUMN externally_powered BOOLEAN DEFAULT FALSE"); } catch (SQLException ignored) {}
                // Migration: hop columns
                try { stmt.execute("ALTER TABLE telemetry_history ADD COLUMN hop_start INT DEFAULT 0"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE telemetry_history ADD COLUMN hop_limit INT DEFAULT 0"); } catch (SQLException ignored) {}
                addExtendedTelemetryColumns(stmt);
                // Migration: channel column in nodes table
                try { stmt.execute("ALTER TABLE nodes ADD COLUMN channel INT DEFAULT 0"); } catch (SQLException ignored) {}
                // Migration: public_key column in nodes table
                try { stmt.execute("ALTER TABLE nodes ADD COLUMN public_key VARBINARY"); } catch (SQLException ignored) {}
                // Migration: is_unmessagable flag in nodes table
                try { stmt.execute("ALTER TABLE nodes ADD COLUMN unmessagable BOOLEAN"); } catch (SQLException ignored) {}
                // Migration: ignored column (v5 migration may not have run on fresh-install DBs)
                try { stmt.execute("ALTER TABLE nodes ADD COLUMN IF NOT EXISTS ignored BOOLEAN DEFAULT FALSE"); } catch (SQLException ignored) {}
                // Migration: separate power-source flag from battery percentage
                try { stmt.execute("ALTER TABLE nodes ADD COLUMN IF NOT EXISTS externally_powered BOOLEAN DEFAULT FALSE"); } catch (SQLException ignored) {}

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_telemetry_ts ON telemetry_history (ts)
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_telemetry_node_ts ON telemetry_history (owner_node_id, node_id, ts)
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_telemetry_one_wire_telemetry
                    ON telemetry_one_wire_temperature (telemetry_id)
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_node_flags_node ON node_flags (node_id)
                    """);
            }

            mergeStmt = dbConnection.prepareStatement("""
                MERGE INTO nodes (node_id, node_num, long_name, short_name, role, hw_model,
                                  latitude, longitude, altitude, snr, last_heard,
                                  battery_level, externally_powered, voltage, hops_away, channel, public_key, unmessagable)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);

            insertTelemetryStmt = dbConnection.prepareStatement(telemetryInsertSql(), Statement.RETURN_GENERATED_KEYS);
            insertOneWireTelemetryStmt = dbConnection.prepareStatement("""
                INSERT INTO telemetry_one_wire_temperature (telemetry_id, sensor_index, temperature)
                VALUES (?, ?, ?)
                """);

            log.info("Node cache DB initialized");
        } catch (Exception e) {
            log.error("Failed to initialize node cache DB", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Reads.
    // ═══════════════════════════════════════════════════════════

    /**
     * Returns a node by node_id from the in-memory cache or H2.
     * If the node is not in memory, it is loaded lazily from the database and cached.
     */
    public NodeData get(String nodeId) {
        return getCachedOrLoad(nodeId, false);
    }

    /**
     * Convenience lookup by node_num, converted to node_id.
     * Used where only the numeric identifier is available.
     */
    public NodeData getByNum(int nodeNum) {
        return get(String.format("!%08x", nodeNum));
    }

    /**
     * Loads one node from the database by node_id.
     */
    private NodeData loadFromDb(String nodeId) {
        if (dbConnection == null || nodeId == null) { return null; }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT * FROM nodes WHERE node_id = ?")) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { return readNode(rs); }
            }
        } catch (SQLException e) {
            log.error("Failed to load node {} from DB", nodeId, e);
        }
        return null;
    }

    /**
     * Returns all nodes from the cache.
     */
    public Collection<NodeData> getAll() {
        return cache.values();
    }

    /**
     * Returns the number of nodes in the cache.
     */
    public int size() {
        return cache.size();
    }

    /**
     * Returns one page of nodes from the database, sorted by {@code last_heard DESC}.
     * Used for pagination on the Cache tab.
     *
     * @param offset number of rows to skip
     * @param limit  maximum number of rows on the page
     * @return nodes for the requested page, possibly empty
     */
    public List<NodeData> loadPage(int offset, int limit) {
        List<NodeData> page = new ArrayList<>();
        if (dbConnection == null) { return page; }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT * FROM nodes ORDER BY last_heard DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    page.add(readNode(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load page (offset={}, limit={}) from DB", offset, limit, e);
        }
        return page;
    }

    /**
     * Returns the total number of nodes in the database.
     *
     * @return number of rows in the {@code nodes} table
     */
    public int countNodesInDb() {
        if (dbConnection == null) { return 0; }
        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM nodes")) {
            if (rs.next()) { return rs.getInt(1); }
        } catch (SQLException e) {
            log.error("Failed to count nodes in DB", e);
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════════
    // Writes (merge).
    // ═══════════════════════════════════════════════════════════

    /**
     * Updates a node in the cache and database. Merge semantics apply: new
     * non-empty fields overwrite old ones, while zero/empty fields do not erase
     * existing data.
     *
     * @param fresh fresh data to merge; nodeId is taken from {@code fresh}
     */
    public void update(NodeData fresh) {
        if (fresh == null) { return; }
        String nodeId = fresh.getNodeId();
        if (nodeId == null || nodeId.isEmpty()) { return; }
        missingNodeIds.remove(nodeId);
        cache.compute(nodeId, (key, existing) -> {
            if (existing == null) {
                existing = loadFromDb(nodeId);
            }
            if (existing == null) {
                existing = new NodeData(fresh.getNodeNum());
                existing.setNodeId(nodeId);
            }
            merge(existing, fresh);
            return existing;
        });
        persistNode(nodeId);
    }

    /**
     * Bulk node update, usually after config exchange.
     * Each node is merged with the cache, then written to the database in a batch.
     *
     * @param nodes node map from {@code DeviceState.getNodeDb()}
     */
    public void updateAll(Map<Integer, NodeData> nodes) {
        Set<String> persistIds = new HashSet<>();
        for (NodeData fresh : nodes.values()) {
            String nodeId = fresh.getNodeId();
            if (nodeId == null || nodeId.isEmpty()) { continue; }
            missingNodeIds.remove(nodeId);
            cache.compute(nodeId, (key, existing) -> {
                if (existing == null) {
                    existing = loadFromDb(nodeId);
                }
                if (existing == null) {
                    existing = new NodeData(fresh.getNodeNum());
                    existing.setNodeId(nodeId);
                }
                if (!fresh.hasHopsAway()) {
                    existing.clearHopsAway();
                }
                merge(existing, fresh);
                return existing;
            });
            persistIds.add(nodeId);
        }
        persistAll(persistIds);
    }

    /**
     * Enriches a bare node, such as one without a name, from cache/H2 data.
     * Only missing identity fields are filled: longName, shortName, role, hwModel,
     * and publicKey. Telemetry and position are left untouched because the node
     * already has fresh data from the device.
     *
     * @param node DeviceState node to enrich
     */
    public void enrichFromCache(NodeData node) {
        if (node == null) { return; }
        boolean needsIdentity = !node.hasName()
                || node.getRole() == null
                || node.getHwModel() == null
                || node.getPublicKey() == null
                || node.getPublicKey().length == 0
                || node.getUnmessagable() == null;
        if (!needsIdentity) { return; }

        String nodeId = node.getNodeId();
        NodeData cached = getCachedOrLoad(nodeId, true);
        if (cached == null) { return; }

        if ((node.getLongName() == null || node.getLongName().isEmpty())
                && cached.getLongName() != null && !cached.getLongName().isEmpty()) {
            node.setLongName(cached.getLongName());
        }
        if ((node.getShortName() == null || node.getShortName().isEmpty())
                && cached.getShortName() != null && !cached.getShortName().isEmpty()) {
            node.setShortName(cached.getShortName());
        }
        if (node.getRole() == null && cached.getRole() != null) {
            node.setRole(cached.getRole());
        }
        if (node.getHwModel() == null && cached.getHwModel() != null) {
            node.setHwModel(cached.getHwModel());
        }
        if ((node.getPublicKey() == null || node.getPublicKey().length == 0)
                && cached.getPublicKey() != null && cached.getPublicKey().length > 0) {
            node.setPublicKey(cached.getPublicKey().clone());
        }
        if (node.getUnmessagable() == null && cached.getUnmessagable() != null) {
            node.setUnmessagable(cached.getUnmessagable());
        }
    }

    /**
     * Fully clears the cache: removes all rows from H2 and the in-memory cache.
     */
    public synchronized void clearAll() {
        cache.clear();
        missingNodeIds.clear();
        if (dbConnection == null) { return; }
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute("DELETE FROM node_flags");
            stmt.execute("DELETE FROM nodes");
            log.info("Кэш нод полностью очищен");
        } catch (SQLException e) {
            log.error("Ошибка очистки кэша нод", e);
        }
    }

    /**
     * Deletes a specific node from the shared node cache and removes telemetry
     * for that node only within the current owner scope.
     */
    public synchronized void deleteNode(String nodeId, String ownerNodeId) {
        if (nodeId == null) { return; }
        cache.remove(nodeId);
        missingNodeIds.add(nodeId);
        if (dbConnection == null) { return; }
        try (PreparedStatement ps1 = dbConnection.prepareStatement(
                "DELETE FROM telemetry_history WHERE owner_node_id = ? AND node_id = ?");
             PreparedStatement psFlags = dbConnection.prepareStatement(
                "DELETE FROM node_flags WHERE node_id = ?");
             PreparedStatement ps2 = dbConnection.prepareStatement("DELETE FROM nodes WHERE node_id = ?")) {
            ps1.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps1.setString(2, nodeId);
            ps1.executeUpdate();
            psFlags.setString(1, nodeId);
            psFlags.executeUpdate();
            ps2.setString(1, nodeId);
            ps2.executeUpdate();
            log.info("Нода {} удалена из кэша для owner {}", nodeId, ownerNodeId);
        } catch (SQLException e) {
            log.error("Ошибка удаления ноды {} из кэша", nodeId, e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Favorite nodes.
    // ═══════════════════════════════════════════════════════════

    /**
     * Sets the favorite flag for a node.
     * Legacy overload for callers that do not know the owner device yet.
     */
    public synchronized void setFavorite(String nodeId, boolean favorite) {
        setFavorite(nodeId, "", favorite);
    }

    /**
     * Sets the favorite flag for a node within one owner device scope.
     */
    public synchronized void setFavorite(String nodeId, String ownerNodeId, boolean favorite) {
        if (dbConnection == null || nodeId == null) { return; }
        ensureNodeRowExists(nodeId);
        String owner = ownerScope(ownerNodeId);
        if (!owner.isBlank()) {
            setFlags(nodeId, owner, favorite, isIgnored(nodeId, owner));
            return;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "UPDATE nodes SET favorite = ? WHERE node_id = ?")) {
            ps.setBoolean(1, favorite);
            ps.setString(2, nodeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to set favorite for node {}", nodeId, e);
        }
    }

    /**
     * Checks whether a node is marked as favorite.
     * Legacy overload for callers that do not know the owner device yet.
     */
    public boolean isFavorite(String nodeId) {
        return isFavorite(nodeId, "");
    }

    /**
     * Checks whether a node is marked as favorite within one owner device scope.
     */
    public boolean isFavorite(String nodeId, String ownerNodeId) {
        if (dbConnection == null || nodeId == null) { return false; }
        String owner = ownerScope(ownerNodeId);
        if (!owner.isBlank()) {
            return isScopedFlagSet(owner, nodeId, "favorite");
        }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT favorite FROM nodes WHERE node_id = ?")) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { return rs.getBoolean("favorite"); }
            }
        } catch (SQLException e) {
            log.error("Failed to check favorite for node {}", nodeId, e);
        }
        return false;
    }

    /**
     * Loads all favorite nodes from the database.
     * Legacy overload for callers that do not know the owner device yet.
     */
    public List<NodeData> loadFavoriteNodes() {
        return loadFavoriteNodes("");
    }

    /**
     * Loads all favorite nodes for one owner device.
     */
    public List<NodeData> loadFavoriteNodes(String ownerNodeId) {
        String owner = ownerScope(ownerNodeId);
        if (!owner.isBlank()) {
            return loadScopedFlaggedNodes(owner, true);
        }
        List<NodeData> favorites = new ArrayList<>();
        if (dbConnection == null) { return favorites; }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT * FROM nodes WHERE favorite = TRUE")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    favorites.add(readNode(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load favorite nodes from DB", e);
        }
        return favorites;
    }

    // ═══════════════════════════════════════════════════════════
    // Ignored nodes.
    // ═══════════════════════════════════════════════════════════

    /**
     * Sets the ignored flag for a node.
     * Legacy overload for callers that do not know the owner device yet.
     */
    public synchronized void setIgnored(String nodeId, boolean ignored) {
        setIgnored(nodeId, "", ignored);
    }

    /**
     * Sets the ignored flag for a node within one owner device scope.
     */
    public synchronized void setIgnored(String nodeId, String ownerNodeId, boolean ignored) {
        if (dbConnection == null || nodeId == null) { return; }
        ensureNodeRowExists(nodeId);
        String owner = ownerScope(ownerNodeId);
        if (!owner.isBlank()) {
            setFlags(nodeId, owner, isFavorite(nodeId, owner), ignored);
            return;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "UPDATE nodes SET ignored = ? WHERE node_id = ?")) {
            ps.setBoolean(1, ignored);
            ps.setString(2, nodeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to set ignored for node {}", nodeId, e);
        }
    }

    /**
     * Checks whether a node is ignored.
     * Legacy overload for callers that do not know the owner device yet.
     */
    public boolean isIgnored(String nodeId) {
        return isIgnored(nodeId, "");
    }

    /**
     * Checks whether a node is ignored within one owner device scope.
     */
    public boolean isIgnored(String nodeId, String ownerNodeId) {
        if (dbConnection == null || nodeId == null) { return false; }
        String owner = ownerScope(ownerNodeId);
        if (!owner.isBlank()) {
            return isScopedFlagSet(owner, nodeId, "ignored");
        }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT ignored FROM nodes WHERE node_id = ?")) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { return rs.getBoolean("ignored"); }
            }
        } catch (SQLException e) {
            log.error("Failed to check ignored for node {}", nodeId, e);
        }
        return false;
    }

    /**
     * Loads all ignored nodes from the database.
     * Legacy overload for callers that do not know the owner device yet.
     */
    public List<NodeData> loadIgnoredNodes() {
        return loadIgnoredNodes("");
    }

    /**
     * Loads all ignored nodes for one owner device.
     */
    public List<NodeData> loadIgnoredNodes(String ownerNodeId) {
        String owner = ownerScope(ownerNodeId);
        if (!owner.isBlank()) {
            return loadScopedFlaggedNodes(owner, false);
        }
        List<NodeData> ignored = new ArrayList<>();
        if (dbConnection == null) { return ignored; }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT * FROM nodes WHERE ignored = TRUE")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ignored.add(readNode(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load ignored nodes from DB", e);
        }
        return ignored;
    }

    /**
     * Writes both device-scoped node flags at once. Used while syncing NodeInfo
     * from the device before the full node row is necessarily persisted.
     */
    public synchronized void setFlags(String nodeId,
                                      String ownerNodeId,
                                      boolean favorite,
                                      boolean ignored) {
        String owner = ownerScope(ownerNodeId);
        if (dbConnection == null || owner.isBlank() || nodeId == null) { return; }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                MERGE INTO node_flags (owner_node_id, node_id, favorite, ignored)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setString(1, owner);
            ps.setString(2, nodeId);
            ps.setBoolean(3, favorite);
            ps.setBoolean(4, ignored);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to set scoped flags for owner {} node {}", owner, nodeId, e);
        }
    }

    private boolean isScopedFlagSet(String ownerNodeId, String nodeId, String columnName) {
        if (!"favorite".equals(columnName) && !"ignored".equals(columnName)) {
            return false;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT " + columnName + " FROM node_flags WHERE owner_node_id = ? AND node_id = ?")) {
            ps.setString(1, ownerNodeId);
            ps.setString(2, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { return rs.getBoolean(columnName); }
            }
        } catch (SQLException e) {
            log.error("Failed to check scoped {} flag for owner {} node {}", columnName, ownerNodeId, nodeId, e);
        }
        return false;
    }

    private List<NodeData> loadScopedFlaggedNodes(String ownerNodeId, boolean favorite) {
        List<NodeData> nodes = new ArrayList<>();
        if (dbConnection == null) { return nodes; }
        String columnName = favorite ? "favorite" : "ignored";
        String sql = """
                SELECT n.*
                FROM nodes n
                JOIN node_flags f ON f.node_id = n.node_id
                WHERE f.owner_node_id = ? AND f.%s = TRUE
                """.formatted(columnName);
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    nodes.add(readNode(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load scoped {} nodes for owner {}", columnName, ownerNodeId, e);
        }
        return nodes;
    }

    /**
     * Creates a placeholder node row when a flag changes before the node has
     * been written to cache/H2 by a full update().
     */
    private void ensureNodeRowExists(String nodeId) {
        if (mergeStmt == null || get(nodeId) != null) { return; }

        int nodeNum;
        try {
            nodeNum = parseNodeNum(nodeId);
        } catch (IllegalArgumentException e) {
            log.warn("Skipping placeholder node creation for invalid nodeId {}", nodeId, e);
            return;
        }

        cache.computeIfAbsent(nodeId, key -> {
            NodeData placeholder = new NodeData(nodeNum);
            placeholder.setNodeId(key);
            return placeholder;
        });
        missingNodeIds.remove(nodeId);
        persistNode(nodeId);
    }

    // ═══════════════════════════════════════════════════════════
    // Telemetry: persistent history.
    // ═══════════════════════════════════════════════════════════

    /**
     * Saves one telemetry entry to H2.
     */
    public synchronized void persistTelemetry(TelemetryEntry entry, String ownerNodeId) {
        persistTelemetry(entry, ownerNodeId, false);
    }

    /**
     * Saves one telemetry entry and optionally suppresses firmware 2.8 replay duplicates.
     *
     * @return {@code true} when the entry was stored
     */
    public synchronized boolean persistTelemetry(
            TelemetryEntry entry,
            String ownerNodeId,
            boolean deduplicateByPacketId) {
        if (insertTelemetryStmt == null || entry == null) { return false; }
        try {
            if (deduplicateByPacketId
                    && entry.getPacketId() != 0
                    && telemetryPacketExists(entry, ownerNodeId)) {
                return false;
            }
            bindTelemetry(insertTelemetryStmt, entry, ownerNodeId);
            insertTelemetryStmt.executeUpdate();
            long telemetryId = generatedTelemetryId(insertTelemetryStmt);
            if (telemetryId > 0) {
                persistOneWireTemperatures(telemetryId, entry.getOneWireTemperatures());
            }
            return true;
        } catch (SQLException e) {
            log.error("Failed to persist telemetry entry", e);
            return false;
        }
    }

    private boolean telemetryPacketExists(
            TelemetryEntry entry,
            String ownerNodeId) throws SQLException {
        String sql = """
                SELECT 1
                FROM telemetry_history
                WHERE owner_node_id = ?
                  AND node_id = ?
                  AND packet_id = ?
                  AND COALESCE(telemetry_variant, '') = COALESCE(?, '')
                LIMIT 1
                """;
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, entry.getNodeId());
            ps.setLong(3, entry.getPacketId());
            ps.setString(4, entry.getTelemetryVariant());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Loads the last {@code limit} telemetry entries from H2, sorted by time
     * from oldest to newest.
     *
     * @param limit maximum number of entries
     * @return entries, possibly empty
     */
    public List<TelemetryEntry> loadTelemetryHistory(int limit, String ownerNodeId) {
        List<TelemetryEntry> result = new ArrayList<>();
        if (dbConnection == null) { return result; }
            // Inner query takes the latest N rows (DESC); the outer query sorts them ASC.
        String sql = "SELECT * FROM (SELECT * FROM telemetry_history WHERE owner_node_id = ? ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readTelemetryRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load telemetry history", e);
        }
        return result;
    }

    /**
     * Loads telemetry entries for the given period, from {@code sinceEpoch} to now,
     * sorted by time from oldest to newest.
     *
     * @param sinceEpoch period start timestamp in epoch seconds; 0 means no lower bound
     * @return entries, possibly empty
     */
    public List<TelemetryEntry> loadTelemetrySince(long sinceEpoch, String ownerNodeId) {
        List<TelemetryEntry> result = new ArrayList<>();
        if (dbConnection == null) { return result; }
        String sql = sinceEpoch > 0
                ? "SELECT * FROM telemetry_history WHERE owner_node_id = ? AND ts >= ? ORDER BY ts ASC"
                : "SELECT * FROM telemetry_history WHERE owner_node_id = ? ORDER BY ts ASC";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            if (sinceEpoch > 0) { ps.setLong(2, sinceEpoch); }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readTelemetryRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load telemetry since {}", sinceEpoch, e);
        }
        return result;
    }

    /**
     * Loads telemetry entries for a specific node over the given period.
     * Filtering by nodeId, period, zero-value artifacts, and future timestamps is
     * performed in SQL.
     *
     * @param nodeId       node identifier, for example {@code !9e755af0}
     * @param sinceEpoch   period start timestamp in epoch seconds; 0 means no lower bound
     * @param maxFutureTs  maximum accepted timestamp for filtering future dates
     * @return entries sorted by time ascending, possibly empty
     */
    public List<TelemetryEntry> loadTelemetryForNode(String nodeId, long sinceEpoch, long maxFutureTs, String ownerNodeId) {
        List<TelemetryEntry> result = new ArrayList<>();
        if (dbConnection == null || nodeId == null) { return result; }

        String sql = """
            SELECT * FROM telemetry_history
            WHERE owner_node_id = ?
              AND node_id = ?
              AND ts <= ?
              AND (telemetry_variant IS NOT NULL OR battery_level <> 0 OR channel_utilization <> 0 OR air_util_tx <> 0 OR voltage <> 0 OR num_packets_rx <> 0 OR num_packets_tx <> 0 OR rx_snr <> 0 OR hop_start <> 0)
            """ + (sinceEpoch > 0 ? "  AND ts >= ?\n" : "") + """
            ORDER BY ts ASC
            """;

        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, nodeId);
            ps.setLong(3, maxFutureTs);
            if (sinceEpoch > 0) {
                ps.setLong(4, sinceEpoch);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readTelemetryRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load telemetry for node {} since {}", nodeId, sinceEpoch, e);
        }
        return result;
    }

    /**
     * Loads telemetry entries with optional node, variant, and time-window filters.
     *
     * @param nodeId       optional node identifier
     * @param variant      optional telemetry variant name
     * @param sinceEpoch   inclusive lower timestamp bound; 0 means no lower bound
     * @param untilEpoch   inclusive upper timestamp bound; 0 means no upper bound
     * @param limit        maximum number of rows
     * @param newestFirst  {@code true} for {@code ts DESC}, {@code false} for {@code ts ASC}
     * @param ownerNodeId  owner-device scope
     * @return matching telemetry entries
     */
    public List<TelemetryEntry> loadTelemetry(String nodeId,
                                              String variant,
                                              long sinceEpoch,
                                              long untilEpoch,
                                              int limit,
                                              boolean newestFirst,
                                              String ownerNodeId) {
        List<TelemetryEntry> result = new ArrayList<>();
        if (dbConnection == null) { return result; }

        StringBuilder sql = new StringBuilder("""
                SELECT * FROM telemetry_history
                WHERE owner_node_id = ?
                """);
        List<SqlBinder> binders = new ArrayList<>();
        binders.add((ps, index) -> ps.setString(index, ownerNodeId != null ? ownerNodeId : ""));

        if (nodeId != null && !nodeId.isBlank()) {
            sql.append("  AND node_id = ?\n");
            binders.add((ps, index) -> ps.setString(index, nodeId));
        }
        if (variant != null && !variant.isBlank()) {
            sql.append("  AND telemetry_variant = ?\n");
            binders.add((ps, index) -> ps.setString(index, variant));
        }
        if (sinceEpoch > 0) {
            sql.append("  AND ts >= ?\n");
            binders.add((ps, index) -> ps.setLong(index, sinceEpoch));
        }
        if (untilEpoch > 0) {
            sql.append("  AND ts <= ?\n");
            binders.add((ps, index) -> ps.setLong(index, untilEpoch));
        }

        sql.append("ORDER BY ts ").append(newestFirst ? "DESC" : "ASC").append(", id ");
        sql.append(newestFirst ? "DESC" : "ASC").append("\nLIMIT ?");
        int safeLimit = Math.max(1, limit);
        binders.add((ps, index) -> ps.setInt(index, safeLimit));

        try (PreparedStatement ps = dbConnection.prepareStatement(sql.toString())) {
            for (int i = 0; i < binders.size(); i++) {
                binders.get(i).bind(ps, i + 1);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readTelemetryRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load telemetry query node={}, variant={}, since={}, until={}",
                    nodeId, variant, sinceEpoch, untilEpoch, e);
        }
        return result;
    }

    /**
     * Loads telemetry entries for all nodes that contain link-quality data (SNR/RSSI/hops).
     */
    public List<TelemetryEntry> loadTelemetryQuality(long sinceEpoch, long maxFutureTs, String ownerNodeId) {
        List<TelemetryEntry> result = new ArrayList<>();
        if (dbConnection == null) { return result; }

        String sql = """
            SELECT * FROM telemetry_history
            WHERE owner_node_id = ?
              AND ts <= ?
              AND (rx_snr <> 0 OR hop_start <> 0)
            """ + (sinceEpoch > 0 ? "  AND ts >= ?\n" : "") + """
            ORDER BY ts ASC
            """;

        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setLong(2, maxFutureTs);
            if (sinceEpoch > 0) {
                ps.setLong(3, sinceEpoch);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readTelemetryRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load quality telemetry since {}", sinceEpoch, e);
        }
        return result;
    }

    /**
     * Returns the total number of telemetry entries in the database.
     */
    public int countTelemetryEntries(String ownerNodeId) {
        if (dbConnection == null) { return 0; }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT COUNT(*) FROM telemetry_history WHERE owner_node_id = ?")) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { return rs.getInt(1); }
            }
        } catch (SQLException e) {
            log.error("Failed to count telemetry entries", e);
        }
        return 0;
    }

    private TelemetryEntry readTelemetryRow(ResultSet rs) throws SQLException {
        TelemetryEntry e = new TelemetryEntry(rs.getLong("ts"), rs.getString("node_id"));
        long telemetryId = rs.getLong("id");
        e.setPacketId(rs.getLong("packet_id"));
        e.setTelemetryVariant(rs.getString("telemetry_variant"));
        applyBatteryLevel(rs.getInt("battery_level"), e);
        e.setExternallyPowered(e.isExternallyPowered() || rs.getBoolean("externally_powered"));
        e.setVoltage(rs.getFloat("voltage"));
        e.setChannelUtilization(rs.getFloat("channel_utilization"));
        e.setAirUtilTx(rs.getFloat("air_util_tx"));
        e.setDeviceUptimeSeconds(nullableLong(rs, "device_uptime_seconds"));
        e.setTemperature(rs.getFloat("temperature"));
        e.setRelativeHumidity(rs.getFloat("relative_humidity"));
        e.setBarometricPressure(rs.getFloat("barometric_pressure"));
        e.setGasResistance(nullableFloat(rs, "gas_resistance"));
        e.setEnvironmentVoltage(nullableFloat(rs, "environment_voltage"));
        e.setEnvironmentCurrent(nullableFloat(rs, "environment_current"));
        e.setIaq(nullableLong(rs, "iaq"));
        e.setDistance(nullableFloat(rs, "distance"));
        e.setLux(nullableFloat(rs, "lux"));
        e.setWhiteLux(nullableFloat(rs, "white_lux"));
        e.setIrLux(nullableFloat(rs, "ir_lux"));
        e.setUvLux(nullableFloat(rs, "uv_lux"));
        e.setWindDirection(nullableLong(rs, "wind_direction"));
        e.setWindSpeed(nullableFloat(rs, "wind_speed"));
        e.setWeight(nullableFloat(rs, "weight"));
        e.setWindGust(nullableFloat(rs, "wind_gust"));
        e.setWindLull(nullableFloat(rs, "wind_lull"));
        e.setRadiation(nullableFloat(rs, "radiation"));
        e.setRainfall1h(nullableFloat(rs, "rainfall_1h"));
        e.setRainfall24h(nullableFloat(rs, "rainfall_24h"));
        e.setSoilMoisture(nullableLong(rs, "soil_moisture"));
        e.setSoilTemperature(nullableFloat(rs, "soil_temperature"));
        e.setPm10Standard(nullableLong(rs, "pm10_standard"));
        e.setPm25Standard(nullableLong(rs, "pm25_standard"));
        e.setPm100Standard(nullableLong(rs, "pm100_standard"));
        e.setPm10Environmental(nullableLong(rs, "pm10_environmental"));
        e.setPm25Environmental(nullableLong(rs, "pm25_environmental"));
        e.setPm100Environmental(nullableLong(rs, "pm100_environmental"));
        e.setParticles03um(nullableLong(rs, "particles_03um"));
        e.setParticles05um(nullableLong(rs, "particles_05um"));
        e.setParticles10um(nullableLong(rs, "particles_10um"));
        e.setParticles25um(nullableLong(rs, "particles_25um"));
        e.setParticles50um(nullableLong(rs, "particles_50um"));
        e.setParticles100um(nullableLong(rs, "particles_100um"));
        e.setCo2(nullableLong(rs, "co2"));
        e.setCo2Temperature(nullableFloat(rs, "co2_temperature"));
        e.setCo2Humidity(nullableFloat(rs, "co2_humidity"));
        e.setFormFormaldehyde(nullableFloat(rs, "form_formaldehyde"));
        e.setFormHumidity(nullableFloat(rs, "form_humidity"));
        e.setFormTemperature(nullableFloat(rs, "form_temperature"));
        e.setPm40Standard(nullableLong(rs, "pm40_standard"));
        e.setParticles40um(nullableLong(rs, "particles_40um"));
        e.setPmTemperature(nullableFloat(rs, "pm_temperature"));
        e.setPmHumidity(nullableFloat(rs, "pm_humidity"));
        e.setPmVocIdx(nullableFloat(rs, "pm_voc_idx"));
        e.setPmNoxIdx(nullableFloat(rs, "pm_nox_idx"));
        e.setParticlesTps(nullableFloat(rs, "particles_tps"));
        e.setCh1Voltage(nullableFloat(rs, "ch1_voltage"));
        e.setCh1Current(nullableFloat(rs, "ch1_current"));
        e.setCh2Voltage(nullableFloat(rs, "ch2_voltage"));
        e.setCh2Current(nullableFloat(rs, "ch2_current"));
        e.setCh3Voltage(nullableFloat(rs, "ch3_voltage"));
        e.setCh3Current(nullableFloat(rs, "ch3_current"));
        e.setCh4Voltage(nullableFloat(rs, "ch4_voltage"));
        e.setCh4Current(nullableFloat(rs, "ch4_current"));
        e.setCh5Voltage(nullableFloat(rs, "ch5_voltage"));
        e.setCh5Current(nullableFloat(rs, "ch5_current"));
        e.setCh6Voltage(nullableFloat(rs, "ch6_voltage"));
        e.setCh6Current(nullableFloat(rs, "ch6_current"));
        e.setCh7Voltage(nullableFloat(rs, "ch7_voltage"));
        e.setCh7Current(nullableFloat(rs, "ch7_current"));
        e.setCh8Voltage(nullableFloat(rs, "ch8_voltage"));
        e.setCh8Current(nullableFloat(rs, "ch8_current"));
        e.setNumPacketsRx(rs.getInt("num_packets_rx"));
        e.setNumPacketsRxBad(rs.getInt("num_packets_rx_bad"));
        e.setNumRxDupe(rs.getInt("num_rx_dupe"));
        e.setNumPacketsTx(rs.getInt("num_packets_tx"));
        e.setNumTxDropped(rs.getInt("num_tx_dropped"));
        e.setNumTxRelay(rs.getInt("num_tx_relay"));
        e.setNumTxRelayCanceled(rs.getInt("num_tx_relay_canceled"));
        e.setLocalUptimeSeconds(nullableLong(rs, "local_uptime_seconds"));
        e.setNumOnlineNodes(nullableLong(rs, "num_online_nodes"));
        e.setNumTotalNodes(nullableLong(rs, "num_total_nodes"));
        e.setHeapTotalBytes(nullableLong(rs, "heap_total_bytes"));
        e.setHeapFreeBytes(nullableLong(rs, "heap_free_bytes"));
        e.setNoiseFloor(nullableInteger(rs, "noise_floor"));
        e.setHealthHeartBpm(nullableLong(rs, "health_heart_bpm"));
        e.setHealthSpO2(nullableLong(rs, "health_spo2"));
        e.setHealthTemperature(nullableFloat(rs, "health_temperature"));
        e.setHostUptimeSeconds(nullableLong(rs, "host_uptime_seconds"));
        e.setHostFreememBytes(nullableLong(rs, "host_freemem_bytes"));
        e.setHostDiskfree1Bytes(nullableLong(rs, "host_diskfree1_bytes"));
        e.setHostDiskfree2Bytes(nullableLong(rs, "host_diskfree2_bytes"));
        e.setHostDiskfree3Bytes(nullableLong(rs, "host_diskfree3_bytes"));
        e.setHostLoad1(nullableLong(rs, "host_load1"));
        e.setHostLoad5(nullableLong(rs, "host_load5"));
        e.setHostLoad15(nullableLong(rs, "host_load15"));
        e.setHostUserString(rs.getString("host_user_string"));
        e.setTrafficPacketsInspected(nullableLong(rs, "traffic_packets_inspected"));
        e.setTrafficPositionDedupDrops(nullableLong(rs, "traffic_position_dedup_drops"));
        e.setTrafficNodeinfoCacheHits(nullableLong(rs, "traffic_nodeinfo_cache_hits"));
        e.setTrafficRateLimitDrops(nullableLong(rs, "traffic_rate_limit_drops"));
        e.setTrafficUnknownPacketDrops(nullableLong(rs, "traffic_unknown_packet_drops"));
        e.setTrafficHopExhaustedPackets(nullableLong(rs, "traffic_hop_exhausted_packets"));
        e.setTrafficRouterHopsPreserved(nullableLong(rs, "traffic_router_hops_preserved"));
        e.setRxSnr(rs.getFloat("rx_snr"));
        e.setRxRssi(rs.getInt("rx_rssi"));
        e.setHopStart(rs.getInt("hop_start"));
        e.setHopLimit(rs.getInt("hop_limit"));
        loadOneWireTemperatures(telemetryId, e);
        return e;
    }

    /**
     * Deletes telemetry entries older than the given number of days.
     *
     * @param days number of days; older entries are removed
     * @return number of deleted rows
     */
    public int pruneTelemetryHistory(int days, String ownerNodeId) {
        if (dbConnection == null) { return 0; }
        long cutoff = System.currentTimeMillis() / 1000 - (long) days * 86400;
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "DELETE FROM telemetry_history WHERE owner_node_id = ? AND ts < ?")) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setLong(2, cutoff);
            int deleted = ps.executeUpdate();
            if (deleted > 0) { log.info("Pruned {} old telemetry entries (older than {} days)", deleted, days); }
            return deleted;
        } catch (SQLException e) {
            log.error("Failed to prune telemetry history", e);
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Import from OneMesh.
    // ═══════════════════════════════════════════════════════════

    /** URL used to download the node cache from the internet. */
    private static final String ONEMESH_URL =
            "https://map.onemesh.ru/cache/nodes_optimized_v2.json";

    /**
     * Downloads and imports nodes from the OneMesh JSON file into the H2 cache.
     * <p>
     * File format: array of arrays, where each inner array is one node.
     * Position mapping:
     * <ul>
     *   <li>[1] - nodeNum, unsigned int as string</li>
     *   <li>[2] - longName</li>
     *   <li>[3] - shortName</li>
     *   <li>[4] - hwModel, numeric Meshtastic HardwareModel code</li>
     *   <li>[5] - role, numeric Meshtastic Config.DeviceConfig.Role code</li>
     *   <li>[12] - latitude, raw int multiplied by 1e-7 for degrees</li>
     *   <li>[13] - longitude, raw int multiplied by 1e-7 for degrees</li>
     *   <li>[16] - altitude in meters</li>
     *   <li>[17] - lastHeard, ISO 8601 timestamp</li>
     *   <li>[18] - batteryLevel</li>
     *   <li>[19] - voltage as string</li>
     * </ul>
     *
     * @return number of imported nodes
     * @throws Exception on download or parsing errors
     */
    public int importFromOneMesh() throws Exception {
        log.info("Начало импорта нод из OneMesh: {}", ONEMESH_URL);

        JsonArray rows;
        try (var is = URI.create(ONEMESH_URL).toURL().openStream();
             var reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            rows = JsonParser.parseReader(reader).getAsJsonArray();
        }

        int imported = 0;
        if (dbConnection == null) { return 0; }

        try {
            dbConnection.setAutoCommit(false);
            for (JsonElement elem : rows) {
                try {
                    JsonArray row = elem.getAsJsonArray();
                    NodeData node = parseOneMeshRow(row);
                    if (node == null || !node.hasName()) { continue; }

                    cache.put(node.getNodeId(), node);
                    missingNodeIds.remove(node.getNodeId());
                    bindNode(mergeStmt, node);
                    mergeStmt.addBatch();
                    imported++;

                // Flush in batches every 1000 rows.
                    if (imported % 1000 == 0) {
                        mergeStmt.executeBatch();
                    }
                } catch (Exception e) {
                    log.debug("Пропуск строки при импорте OneMesh: {}", e.getMessage());
                }
            }
            mergeStmt.executeBatch();
            dbConnection.commit();
            dbConnection.setAutoCommit(true);
            log.info("Импорт из OneMesh завершён: {} нод", imported);
        } catch (SQLException e) {
            log.error("Ошибка при batch-импорте из OneMesh", e);
            try { dbConnection.rollback(); dbConnection.setAutoCommit(true); } catch (SQLException ex) {
                log.debug("Rollback failed during OneMesh import recovery", ex);
            }
            throw e;
        }
        return imported;
    }

    /**
     * Parses one row array from OneMesh JSON into {@link NodeData}.
     *
     * @param row JSON array for one node
     * @return {@link NodeData}, or {@code null} when the row is invalid
     */
    private static NodeData parseOneMeshRow(JsonArray row) {
        if (row.size() < 20) { return null; }


            // [1] nodeNum, unsigned int as string.
        long nodeNumLong = Long.parseUnsignedLong(row.get(1).getAsString());
        int nodeNum = (int) nodeNumLong;
        if (nodeNum == 0) { return null; }

        NodeData node = new NodeData(nodeNum);

        // [2] longName
        if (!row.get(2).isJsonNull()) {
            node.setLongName(row.get(2).getAsString());
        }

        // [3] shortName
        if (!row.get(3).isJsonNull()) {
            node.setShortName(row.get(3).getAsString());
        }

            // [4] hwModel, numeric code to protobuf enum name.
        if (!row.get(4).isJsonNull()) {
            int hwCode = row.get(4).getAsInt();
            MeshProtos.HardwareModel hw = MeshProtos.HardwareModel.forNumber(hwCode);
            node.setHwModel(hw != null ? hw.name() : String.valueOf(hwCode));
        }

            // [5] role, numeric code to protobuf enum name.
        if (!row.get(5).isJsonNull()) {
            int roleCode = row.get(5).getAsInt();
            ConfigProtos.Config.DeviceConfig.Role role =
                    ConfigProtos.Config.DeviceConfig.Role.forNumber(roleCode);
            node.setRole(role != null ? role.name() : String.valueOf(roleCode));
        }

        // [12] latitude (raw int * 1e-7)
        if (!row.get(12).isJsonNull()) {
            double lat = row.get(12).getAsLong() * 1e-7;
            if (lat != 0) { node.setLatitude(lat); }
        }

        // [13] longitude (raw int * 1e-7)
        if (!row.get(13).isJsonNull()) {
            double lon = row.get(13).getAsLong() * 1e-7;
            if (lon != 0) { node.setLongitude(lon); }
        }

        // [16] altitude
        if (!row.get(16).isJsonNull()) {
            node.setAltitude(row.get(16).getAsInt());
        }

        // [17] lastHeard — ISO 8601 → epoch seconds
        if (!row.get(17).isJsonNull()) {
            try {
                String ts = row.get(17).getAsString();
                int epoch = (int) Instant.parse(ts).getEpochSecond();
                node.setLastHeard(epoch);
            } catch (Exception ignored) { }
        }

        // [18] batteryLevel
        if (!row.get(18).isJsonNull()) {
            applyBatteryLevel(row.get(18).getAsInt(), node);
        }

            // [19] voltage, string to float.
        if (!row.get(19).isJsonNull()) {
            try {
                node.setVoltage(Float.parseFloat(row.get(19).getAsString()));
            } catch (NumberFormatException ignored) { }
        }

        return node;
    }

    // ═══════════════════════════════════════════════════════════
    // Merge logic.
    // ═══════════════════════════════════════════════════════════

    /**
     * Copies non-empty fields from {@code src} to {@code dst}.
     * Zero, empty, and default fields in src do not overwrite existing values in dst.
     */
    private static void merge(NodeData dst, NodeData src) {
        if (src.getLongName() != null && !src.getLongName().isEmpty()) {
            dst.setLongName(src.getLongName());
        }
        if (src.getShortName() != null && !src.getShortName().isEmpty()) {
            dst.setShortName(src.getShortName());
        }
        if (src.getNodeId() != null && !src.getNodeId().isEmpty()) {
            dst.setNodeId(src.getNodeId());
        }
        if (src.getRole() != null && !src.getRole().isEmpty()) {
            dst.setRole(src.getRole());
        }
        if (src.getHwModel() != null && !src.getHwModel().isEmpty()) {
            dst.setHwModel(src.getHwModel());
        }
        if (src.getLatitude() != 0) { dst.setLatitude(src.getLatitude()); }

        if (src.getLongitude() != 0) { dst.setLongitude(src.getLongitude()); }

        if (src.getAltitude() != 0) { dst.setAltitude(src.getAltitude()); }

        if (src.getSnr() != 0) { dst.setSnr(src.getSnr()); }

        if (src.getLastHeard() != 0) { dst.setLastHeard(src.getLastHeard()); }

        if (src.getBatteryLevel() != 0) {
            dst.setBatteryLevel(src.getBatteryLevel());
            dst.setExternallyPowered(false);
        }
        if (src.isExternallyPowered()) {
            dst.setExternallyPowered(true);
        }

        if (src.getVoltage() != 0) { dst.setVoltage(src.getVoltage()); }

        if (src.getChannelUtilization() != 0) { dst.setChannelUtilization(src.getChannelUtilization()); }

        if (src.getAirUtilTx() != 0) { dst.setAirUtilTx(src.getAirUtilTx()); }

        if (src.getUptimeSeconds() != 0) { dst.setUptimeSeconds(src.getUptimeSeconds()); }

        if (src.getTemperature() != 0) { dst.setTemperature(src.getTemperature()); }

        if (src.getRelativeHumidity() != 0) { dst.setRelativeHumidity(src.getRelativeHumidity()); }

        if (src.getBarometricPressure() != 0) { dst.setBarometricPressure(src.getBarometricPressure()); }

        if (src.hasHopsAway()) { dst.setHopsAway(src.getHopsAway()); }

        if (src.getChannel() != 0) { dst.setChannel(src.getChannel()); }

        if (src.getPublicKey() != null && src.getPublicKey().length > 0) {
            dst.setPublicKey(src.getPublicKey().clone());
        }
        if (src.getUnmessagable() != null) {
            dst.setUnmessagable(src.getUnmessagable());
        }

    }

    private NodeData getCachedOrLoad(String nodeId, boolean logLoadResult) {
        if (nodeId == null || nodeId.isEmpty()) { return null; }

        NodeData cached = cache.get(nodeId);
        if (cached != null) {
            return cached;
        }
        if (missingNodeIds.contains(nodeId)) {
            return null;
        }

        NodeData loaded = loadFromDb(nodeId);
        if (loaded == null) {
            missingNodeIds.add(nodeId);
            if (logLoadResult) {
                log.debug("enrichFromCache: {} not found in H2", nodeId);
            }
            return null;
        }

        NodeData existing = cache.putIfAbsent(nodeId, loaded);
        NodeData resolved = existing != null ? existing : loaded;
        missingNodeIds.remove(nodeId);
        if (logLoadResult) {
            log.debug("enrichFromCache: loaded {} from H2, hasName={}", nodeId, resolved.hasName());
        }
        return resolved;
    }

    // ═══════════════════════════════════════════════════════════
    // Persistence (H2).
    // ═══════════════════════════════════════════════════════════

    /**
     * No-op: data is read from the database on demand through {@link #loadPage}
     * and {@link #countNodesInDb}. The in-memory cache is filled lazily by
     * {@link #update}.
     */
    public void load() {
        // No-op: data is read directly from H2.
    }

    /**
     * No-op: H2 writes happen immediately. Kept for API compatibility.
     */
    public void save() {
        // No-op: H2 writes are immediate
    }

    /**
     * Closes the database connection cleanly. Called during application shutdown.
     */
    public void close() {
        cache.clear();
        missingNodeIds.clear();
        closeStatements();
        dbConnection = null;
    }

    public synchronized void prepareForDatabaseReset() {
        cache.clear();
        missingNodeIds.clear();
        closeStatements();
        dbConnection = null;
    }

    public synchronized void reinitializeAfterDatabaseReset() {
        cache.clear();
        missingNodeIds.clear();
        initDb();
    }

    private void closeStatements() {
        try {
            if (mergeStmt != null) { mergeStmt.close(); }
            if (insertTelemetryStmt != null) { insertTelemetryStmt.close(); }
            if (insertOneWireTelemetryStmt != null) { insertOneWireTelemetryStmt.close(); }
        } catch (SQLException e) {
            log.error("Error closing node cache DB statements", e);
        } finally {
            mergeStmt = null;
            insertTelemetryStmt = null;
            insertOneWireTelemetryStmt = null;
        }
    }

    /**
     * Saves one node to the database using MERGE INTO.
     */
    private synchronized void persistNode(String nodeId) {
        NodeData node = cache.get(nodeId);
        if (node == null || mergeStmt == null) { return; }
        try {
            bindNode(mergeStmt, node);
            mergeStmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to persist node {}", nodeId, e);
        }
    }

    /**
     * Saves a set of nodes to the database in one transaction using batch MERGE.
     */
    private synchronized void persistAll(Set<String> nodeIds) {
        if (mergeStmt == null) { return; }
        try {
            dbConnection.setAutoCommit(false);
            for (String nodeId : nodeIds) {
                NodeData node = cache.get(nodeId);
                if (node != null) {
                    bindNode(mergeStmt, node);
                    mergeStmt.addBatch();
                }
            }
            mergeStmt.executeBatch();
            dbConnection.commit();
            dbConnection.setAutoCommit(true);
            log.debug("Persisted {} nodes to DB in batch", nodeIds.size());
        } catch (SQLException e) {
            log.error("Failed to batch-persist nodes to DB", e);
            try { dbConnection.rollback(); dbConnection.setAutoCommit(true); } catch (SQLException ex) {
                log.debug("Rollback failed during batch-persist recovery", ex);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // JDBC utilities.
    // ═══════════════════════════════════════════════════════════

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps, int index) throws SQLException;
    }

    /**
     * Binds NodeData fields to PreparedStatement parameters.
     * Order: node_id, node_num, long_name, short_name, role, hw_model, ...
     */
    private static void bindNode(PreparedStatement ps, NodeData n) throws SQLException {
        ps.setString(1, n.getNodeId());
        ps.setInt(2, n.getNodeNum());
        ps.setString(3, n.getLongName());
        ps.setString(4, n.getShortName());
        ps.setString(5, n.getRole());
        ps.setString(6, n.getHwModel());
        ps.setDouble(7, n.getLatitude());
        ps.setDouble(8, n.getLongitude());
        ps.setInt(9, n.getAltitude());
        ps.setFloat(10, n.getSnr());
        ps.setInt(11, n.getLastHeard());
        ps.setInt(12, n.getBatteryLevel());
        ps.setBoolean(13, n.isExternallyPowered());
        ps.setFloat(14, n.getVoltage());
        if (n.hasHopsAway()) {
            ps.setInt(15, n.getHopsAway());
        } else {
            ps.setNull(15, Types.INTEGER);
        }
        ps.setInt(16, n.getChannel());
        byte[] publicKey = n.getPublicKey();
        if (publicKey != null && publicKey.length > 0) {
            ps.setBytes(17, publicKey);
        } else {
            ps.setNull(17, Types.VARBINARY);
        }
        if (n.getUnmessagable() != null) {
            ps.setBoolean(18, n.getUnmessagable());
        } else {
            ps.setNull(18, Types.BOOLEAN);
        }
    }

    private static void bindTelemetry(PreparedStatement ps, TelemetryEntry entry, String ownerNodeId) throws SQLException {
        int i = 1;
        ps.setLong(i++, entry.getTimestamp());
        ps.setString(i++, entry.getNodeId());
        ps.setString(i++, ownerNodeId != null ? ownerNodeId : "");
        ps.setLong(i++, entry.getPacketId());
        setNullableString(ps, i++, entry.getTelemetryVariant(), 40);
        ps.setInt(i++, entry.getBatteryLevel());
        ps.setBoolean(i++, entry.isExternallyPowered());
        ps.setFloat(i++, entry.getVoltage());
        ps.setFloat(i++, entry.getChannelUtilization());
        ps.setFloat(i++, entry.getAirUtilTx());
        setNullableLong(ps, i++, entry.getDeviceUptimeSeconds());
        ps.setFloat(i++, entry.getTemperature());
        ps.setFloat(i++, entry.getRelativeHumidity());
        ps.setFloat(i++, entry.getBarometricPressure());
        setNullableFloat(ps, i++, entry.getGasResistance());
        setNullableFloat(ps, i++, entry.getEnvironmentVoltage());
        setNullableFloat(ps, i++, entry.getEnvironmentCurrent());
        setNullableLong(ps, i++, entry.getIaq());
        setNullableFloat(ps, i++, entry.getDistance());
        setNullableFloat(ps, i++, entry.getLux());
        setNullableFloat(ps, i++, entry.getWhiteLux());
        setNullableFloat(ps, i++, entry.getIrLux());
        setNullableFloat(ps, i++, entry.getUvLux());
        setNullableLong(ps, i++, entry.getWindDirection());
        setNullableFloat(ps, i++, entry.getWindSpeed());
        setNullableFloat(ps, i++, entry.getWeight());
        setNullableFloat(ps, i++, entry.getWindGust());
        setNullableFloat(ps, i++, entry.getWindLull());
        setNullableFloat(ps, i++, entry.getRadiation());
        setNullableFloat(ps, i++, entry.getRainfall1h());
        setNullableFloat(ps, i++, entry.getRainfall24h());
        setNullableLong(ps, i++, entry.getSoilMoisture());
        setNullableFloat(ps, i++, entry.getSoilTemperature());
        setNullableLong(ps, i++, entry.getPm10Standard());
        setNullableLong(ps, i++, entry.getPm25Standard());
        setNullableLong(ps, i++, entry.getPm100Standard());
        setNullableLong(ps, i++, entry.getPm10Environmental());
        setNullableLong(ps, i++, entry.getPm25Environmental());
        setNullableLong(ps, i++, entry.getPm100Environmental());
        setNullableLong(ps, i++, entry.getParticles03um());
        setNullableLong(ps, i++, entry.getParticles05um());
        setNullableLong(ps, i++, entry.getParticles10um());
        setNullableLong(ps, i++, entry.getParticles25um());
        setNullableLong(ps, i++, entry.getParticles50um());
        setNullableLong(ps, i++, entry.getParticles100um());
        setNullableLong(ps, i++, entry.getCo2());
        setNullableFloat(ps, i++, entry.getCo2Temperature());
        setNullableFloat(ps, i++, entry.getCo2Humidity());
        setNullableFloat(ps, i++, entry.getFormFormaldehyde());
        setNullableFloat(ps, i++, entry.getFormHumidity());
        setNullableFloat(ps, i++, entry.getFormTemperature());
        setNullableLong(ps, i++, entry.getPm40Standard());
        setNullableLong(ps, i++, entry.getParticles40um());
        setNullableFloat(ps, i++, entry.getPmTemperature());
        setNullableFloat(ps, i++, entry.getPmHumidity());
        setNullableFloat(ps, i++, entry.getPmVocIdx());
        setNullableFloat(ps, i++, entry.getPmNoxIdx());
        setNullableFloat(ps, i++, entry.getParticlesTps());
        setNullableFloat(ps, i++, entry.getCh1Voltage());
        setNullableFloat(ps, i++, entry.getCh1Current());
        setNullableFloat(ps, i++, entry.getCh2Voltage());
        setNullableFloat(ps, i++, entry.getCh2Current());
        setNullableFloat(ps, i++, entry.getCh3Voltage());
        setNullableFloat(ps, i++, entry.getCh3Current());
        setNullableFloat(ps, i++, entry.getCh4Voltage());
        setNullableFloat(ps, i++, entry.getCh4Current());
        setNullableFloat(ps, i++, entry.getCh5Voltage());
        setNullableFloat(ps, i++, entry.getCh5Current());
        setNullableFloat(ps, i++, entry.getCh6Voltage());
        setNullableFloat(ps, i++, entry.getCh6Current());
        setNullableFloat(ps, i++, entry.getCh7Voltage());
        setNullableFloat(ps, i++, entry.getCh7Current());
        setNullableFloat(ps, i++, entry.getCh8Voltage());
        setNullableFloat(ps, i++, entry.getCh8Current());
        ps.setInt(i++, entry.getNumPacketsRx());
        ps.setInt(i++, entry.getNumPacketsRxBad());
        ps.setInt(i++, entry.getNumRxDupe());
        ps.setInt(i++, entry.getNumPacketsTx());
        ps.setInt(i++, entry.getNumTxDropped());
        ps.setInt(i++, entry.getNumTxRelay());
        ps.setInt(i++, entry.getNumTxRelayCanceled());
        setNullableLong(ps, i++, entry.getLocalUptimeSeconds());
        setNullableLong(ps, i++, entry.getNumOnlineNodes());
        setNullableLong(ps, i++, entry.getNumTotalNodes());
        setNullableLong(ps, i++, entry.getHeapTotalBytes());
        setNullableLong(ps, i++, entry.getHeapFreeBytes());
        setNullableInteger(ps, i++, entry.getNoiseFloor());
        setNullableLong(ps, i++, entry.getHealthHeartBpm());
        setNullableLong(ps, i++, entry.getHealthSpO2());
        setNullableFloat(ps, i++, entry.getHealthTemperature());
        setNullableLong(ps, i++, entry.getHostUptimeSeconds());
        setNullableLong(ps, i++, entry.getHostFreememBytes());
        setNullableLong(ps, i++, entry.getHostDiskfree1Bytes());
        setNullableLong(ps, i++, entry.getHostDiskfree2Bytes());
        setNullableLong(ps, i++, entry.getHostDiskfree3Bytes());
        setNullableLong(ps, i++, entry.getHostLoad1());
        setNullableLong(ps, i++, entry.getHostLoad5());
        setNullableLong(ps, i++, entry.getHostLoad15());
        setNullableString(ps, i++, entry.getHostUserString(), 512);
        setNullableLong(ps, i++, entry.getTrafficPacketsInspected());
        setNullableLong(ps, i++, entry.getTrafficPositionDedupDrops());
        setNullableLong(ps, i++, entry.getTrafficNodeinfoCacheHits());
        setNullableLong(ps, i++, entry.getTrafficRateLimitDrops());
        setNullableLong(ps, i++, entry.getTrafficUnknownPacketDrops());
        setNullableLong(ps, i++, entry.getTrafficHopExhaustedPackets());
        setNullableLong(ps, i++, entry.getTrafficRouterHopsPreserved());
        ps.setFloat(i++, entry.getRxSnr());
        ps.setInt(i++, entry.getRxRssi());
        ps.setInt(i++, entry.getHopStart());
        ps.setInt(i++, entry.getHopLimit());
        if (i != TELEMETRY_HISTORY_COLUMNS.length + 1) {
            throw new SQLException("Telemetry bind count mismatch: expected "
                    + TELEMETRY_HISTORY_COLUMNS.length + ", bound " + (i - 1));
        }
    }

    private static long generatedTelemetryId(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.getGeneratedKeys()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private void persistOneWireTemperatures(long telemetryId, List<Float> temperatures) throws SQLException {
        if (insertOneWireTelemetryStmt == null || temperatures == null || temperatures.isEmpty()) {
            return;
        }
        int sensorIndex = 0;
        for (Float temperature : temperatures) {
            if (temperature == null) {
                continue;
            }
            insertOneWireTelemetryStmt.setLong(1, telemetryId);
            insertOneWireTelemetryStmt.setInt(2, sensorIndex++);
            insertOneWireTelemetryStmt.setFloat(3, temperature);
            insertOneWireTelemetryStmt.addBatch();
        }
        insertOneWireTelemetryStmt.executeBatch();
    }

    private void loadOneWireTemperatures(long telemetryId, TelemetryEntry entry) throws SQLException {
        if (dbConnection == null || telemetryId <= 0 || entry == null) {
            return;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT temperature
                FROM telemetry_one_wire_temperature
                WHERE telemetry_id = ?
                ORDER BY sensor_index ASC
                """)) {
            ps.setLong(1, telemetryId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entry.addOneWireTemperature(rs.getFloat("temperature"));
                }
            }
        }
    }

    private static void setNullableFloat(PreparedStatement ps, int index, Float value) throws SQLException {
        if (value != null) {
            ps.setFloat(index, value);
        } else {
            ps.setNull(index, Types.REAL);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value != null) {
            ps.setLong(index, value);
        } else {
            ps.setNull(index, Types.BIGINT);
        }
    }

    private static void setNullableInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value != null) {
            ps.setInt(index, value);
        } else {
            ps.setNull(index, Types.INTEGER);
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value, int maxLength) throws SQLException {
        if (value != null) {
            ps.setString(index, value.length() <= maxLength ? value : value.substring(0, maxLength));
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }

    private static Float nullableFloat(ResultSet rs, String columnName) throws SQLException {
        float value = rs.getFloat(columnName);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String columnName) throws SQLException {
        long value = rs.getLong(columnName);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, String columnName) throws SQLException {
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }

    private static int parseNodeNum(String nodeId) {
        if (nodeId == null || nodeId.length() < 2 || nodeId.charAt(0) != '!') {
            throw new IllegalArgumentException("Invalid nodeId: " + nodeId);
        }
        return (int) Long.parseUnsignedLong(nodeId.substring(1), 16);
    }

    private static String ownerScope(String ownerNodeId) {
        return ownerNodeId != null ? ownerNodeId.toLowerCase(Locale.ROOT) : "";
    }

    /**
     * Reads NodeData from the current ResultSet row.
     */
    private static NodeData readNode(ResultSet rs) throws SQLException {
        NodeData node = new NodeData(rs.getInt("node_num"));
        String nodeId = rs.getString("node_id");
        if (nodeId != null) { node.setNodeId(nodeId); }
        node.setLongName(rs.getString("long_name"));
        node.setShortName(rs.getString("short_name"));
        node.setRole(rs.getString("role"));
        node.setHwModel(rs.getString("hw_model"));
        node.setLatitude(rs.getDouble("latitude"));
        node.setLongitude(rs.getDouble("longitude"));
        node.setAltitude(rs.getInt("altitude"));
        node.setSnr(rs.getFloat("snr"));
        node.setLastHeard(rs.getInt("last_heard"));
        applyBatteryLevel(rs.getInt("battery_level"), node);
        node.setExternallyPowered(node.isExternallyPowered() || rs.getBoolean("externally_powered"));
        node.setVoltage(rs.getFloat("voltage"));
        int hopsAway = rs.getInt("hops_away");
        if (rs.wasNull()) {
            node.clearHopsAway();
        } else {
            node.setHopsAway(hopsAway);
        }
        node.setChannel(rs.getInt("channel"));
        byte[] publicKey = rs.getBytes("public_key");
        if (publicKey != null && publicKey.length > 0) {
            node.setPublicKey(publicKey);
        }
        boolean unmessagable = rs.getBoolean("unmessagable");
        if (!rs.wasNull()) {
            node.setUnmessagable(unmessagable);
        }
        return node;
    }

    private static void applyBatteryLevel(int rawBatteryLevel, NodeData node) {
        if (rawBatteryLevel > 100) {
            node.setExternallyPowered(true);
        } else {
            node.setBatteryLevel(rawBatteryLevel);
        }
    }

    private static void applyBatteryLevel(int rawBatteryLevel, TelemetryEntry entry) {
        if (rawBatteryLevel > 100) {
            entry.setExternallyPowered(true);
        } else {
            entry.setBatteryLevel(rawBatteryLevel);
        }
    }
}
