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

    // ═══════════════════════════════════════════════════════════
    // Database initialization.
    // ═══════════════════════════════════════════════════════════

    private void initDb() {
        try {
            closeStatements();
            dbConnection = DatabaseProvider.getConnection();

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
                    CREATE TABLE IF NOT EXISTS telemetry_history (
                        id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
                        ts                  BIGINT NOT NULL,
                        node_id             VARCHAR(20) NOT NULL,
                        owner_node_id       VARCHAR(20) NOT NULL DEFAULT '',
                        battery_level       INT,
                        externally_powered  BOOLEAN DEFAULT FALSE,
                        voltage             REAL,
                        channel_utilization REAL,
                        air_util_tx         REAL,
                        temperature         REAL,
                        relative_humidity   REAL,
                        barometric_pressure REAL,
                        num_packets_rx      INT DEFAULT 0,
                        num_packets_rx_bad  INT DEFAULT 0,
                        num_rx_dupe         INT DEFAULT 0,
                        num_packets_tx      INT DEFAULT 0,
                        num_tx_dropped      INT DEFAULT 0,
                        num_tx_relay        INT DEFAULT 0,
                        num_tx_relay_canceled INT DEFAULT 0,
                        rx_snr              REAL DEFAULT 0,
                        rx_rssi             INT DEFAULT 0,
                        hop_start           INT DEFAULT 0,
                        hop_limit           INT DEFAULT 0
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
            }

            mergeStmt = dbConnection.prepareStatement("""
                MERGE INTO nodes (node_id, node_num, long_name, short_name, role, hw_model,
                                  latitude, longitude, altitude, snr, last_heard,
                                  battery_level, externally_powered, voltage, hops_away, channel, public_key, unmessagable)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);

            insertTelemetryStmt = dbConnection.prepareStatement("""
                INSERT INTO telemetry_history (ts, node_id, battery_level, externally_powered, voltage,
                    channel_utilization, air_util_tx, temperature, relative_humidity, barometric_pressure,
                    num_packets_rx, num_packets_rx_bad, num_rx_dupe,
                    num_packets_tx, num_tx_dropped, num_tx_relay, num_tx_relay_canceled,
                    rx_snr, rx_rssi, hop_start, hop_limit, owner_node_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            stmt.execute("DELETE FROM nodes");
            log.info("Кэш нод полностью очищен");
        } catch (SQLException e) {
            log.error("Ошибка очистки кэша нод", e);
        }
    }

    /** Deletes a specific node and its telemetry from cache and database. */
    public synchronized void deleteNode(String nodeId) {
        if (nodeId == null) { return; }
        cache.remove(nodeId);
        missingNodeIds.add(nodeId);
        if (dbConnection == null) { return; }
        try (PreparedStatement ps1 = dbConnection.prepareStatement("DELETE FROM telemetry_history WHERE node_id = ?");
             PreparedStatement ps2 = dbConnection.prepareStatement("DELETE FROM nodes WHERE node_id = ?")) {
            ps1.setString(1, nodeId);
            ps1.executeUpdate();
            ps2.setString(1, nodeId);
            ps2.executeUpdate();
            log.info("Нода {} удалена из кэша", nodeId);
        } catch (SQLException e) {
            log.error("Ошибка удаления ноды {} из кэша", nodeId, e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Favorite nodes.
    // ═══════════════════════════════════════════════════════════

    /**
     * Sets the favorite flag for a node.
     * If the node is absent from the database, no row is created.
     */
    public synchronized void setFavorite(String nodeId, boolean favorite) {
        if (dbConnection == null || nodeId == null) { return; }
        ensureNodeRowExists(nodeId);
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
     */
    public boolean isFavorite(String nodeId) {
        if (dbConnection == null || nodeId == null) { return false; }
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
     */
    public List<NodeData> loadFavoriteNodes() {
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
     */
    public synchronized void setIgnored(String nodeId, boolean ignored) {
        if (dbConnection == null || nodeId == null) { return; }
        ensureNodeRowExists(nodeId);
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
     */
    public boolean isIgnored(String nodeId) {
        if (dbConnection == null || nodeId == null) { return false; }
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
     */
    public List<NodeData> loadIgnoredNodes() {
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
        if (insertTelemetryStmt == null || entry == null) { return; }
        try {
            insertTelemetryStmt.setLong(1, entry.getTimestamp());
            insertTelemetryStmt.setString(2, entry.getNodeId());
            insertTelemetryStmt.setInt(3, entry.getBatteryLevel());
            insertTelemetryStmt.setBoolean(4, entry.isExternallyPowered());
            insertTelemetryStmt.setFloat(5, entry.getVoltage());
            insertTelemetryStmt.setFloat(6, entry.getChannelUtilization());
            insertTelemetryStmt.setFloat(7, entry.getAirUtilTx());
            insertTelemetryStmt.setFloat(8, entry.getTemperature());
            insertTelemetryStmt.setFloat(9, entry.getRelativeHumidity());
            insertTelemetryStmt.setFloat(10, entry.getBarometricPressure());
            insertTelemetryStmt.setInt(11, entry.getNumPacketsRx());
            insertTelemetryStmt.setInt(12, entry.getNumPacketsRxBad());
            insertTelemetryStmt.setInt(13, entry.getNumRxDupe());
            insertTelemetryStmt.setInt(14, entry.getNumPacketsTx());
            insertTelemetryStmt.setInt(15, entry.getNumTxDropped());
            insertTelemetryStmt.setInt(16, entry.getNumTxRelay());
            insertTelemetryStmt.setInt(17, entry.getNumTxRelayCanceled());
            insertTelemetryStmt.setFloat(18, entry.getRxSnr());
            insertTelemetryStmt.setInt(19, entry.getRxRssi());
            insertTelemetryStmt.setInt(20, entry.getHopStart());
            insertTelemetryStmt.setInt(21, entry.getHopLimit());
            insertTelemetryStmt.setString(22, ownerNodeId != null ? ownerNodeId : "");
            insertTelemetryStmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to persist telemetry entry", e);
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
              AND (battery_level <> 0 OR channel_utilization <> 0 OR air_util_tx <> 0 OR voltage <> 0 OR num_packets_rx <> 0 OR num_packets_tx <> 0 OR rx_snr <> 0 OR hop_start <> 0)
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

    private static TelemetryEntry readTelemetryRow(ResultSet rs) throws SQLException {
        TelemetryEntry e = new TelemetryEntry(rs.getLong("ts"), rs.getString("node_id"));
        applyBatteryLevel(rs.getInt("battery_level"), e);
        e.setExternallyPowered(e.isExternallyPowered() || rs.getBoolean("externally_powered"));
        e.setVoltage(rs.getFloat("voltage"));
        e.setChannelUtilization(rs.getFloat("channel_utilization"));
        e.setAirUtilTx(rs.getFloat("air_util_tx"));
        e.setTemperature(rs.getFloat("temperature"));
        e.setRelativeHumidity(rs.getFloat("relative_humidity"));
        e.setBarometricPressure(rs.getFloat("barometric_pressure"));
        e.setNumPacketsRx(rs.getInt("num_packets_rx"));
        e.setNumPacketsRxBad(rs.getInt("num_packets_rx_bad"));
        e.setNumRxDupe(rs.getInt("num_rx_dupe"));
        e.setNumPacketsTx(rs.getInt("num_packets_tx"));
        e.setNumTxDropped(rs.getInt("num_tx_dropped"));
        e.setNumTxRelay(rs.getInt("num_tx_relay"));
        e.setNumTxRelayCanceled(rs.getInt("num_tx_relay_canceled"));
        e.setRxSnr(rs.getFloat("rx_snr"));
        e.setRxRssi(rs.getInt("rx_rssi"));
        e.setHopStart(rs.getInt("hop_start"));
        e.setHopLimit(rs.getInt("hop_limit"));
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
        } catch (SQLException e) {
            log.error("Error closing node cache DB statements", e);
        } finally {
            mergeStmt = null;
            insertTelemetryStmt = null;
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

    private static int parseNodeNum(String nodeId) {
        if (nodeId == null || nodeId.length() < 2 || nodeId.charAt(0) != '!') {
            throw new IllegalArgumentException("Invalid nodeId: " + nodeId);
        }
        return (int) Long.parseUnsignedLong(nodeId.substring(1), 16);
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
