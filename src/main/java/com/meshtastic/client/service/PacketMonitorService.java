package com.meshtastic.client.service;

import com.google.protobuf.InvalidProtocolBufferException;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.model.PacketLogEntry.Direction;
import com.meshtastic.client.utils.PacketDebugFormatter;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Captures and stores LoRa mesh packets for the packet monitor window.
 * The service owns the handoff from protocol runtimes, durable storage in the
 * local database, and live event delivery to UI listeners.
 *
 * Public methods may be called from arbitrary threads. Operations that touch
 * the database or shared service state are synchronized on the service instance.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class PacketMonitorService {

    private static final long BROADCAST_NODE_NUM = 0xFFFF_FFFFL;
    private static final Pattern STANDARD_NODE_ID_PATTERN = Pattern.compile("^!?[0-9a-fA-F]{8}$");
    public static final String TRANSPORT_MECHANISM_UNSPECIFIED = "__UNSPECIFIED__";

    private enum PageRequestKind {
        LATEST,
        OLDER,
        NEWER
    }

    private static final Logger log = LoggerFactory.getLogger(PacketMonitorService.class);

    /**
     * Receives packet monitor events.
     * Implementations should return quickly and must not block the calling
     * thread. Moving work onto the JavaFX application thread is the UI layer's
     * responsibility.
     */
    public interface Listener {
        default void onPacketLogged(PacketLogEntry entry) {}
        default void onCaptureStateChanged(boolean captureEnabled) {}
        default void onCleared() {}
    }

    @FunctionalInterface
    public interface PacketBatchConsumer {
        void accept(List<PacketLogEntry> batch) throws IOException;
    }

    /**
     * Server-side filter for the packet table.
     * Blank strings are normalized to {@code null} so the SQL layer treats an
     * empty filter and an omitted filter identically.
 *
     * @param direction            packet direction, or {@code null} for both directions
     * @param packetType           exact packet type, or {@code null} for all types
     * @param transportMechanism   exact transport_mechanism, {@link #TRANSPORT_MECHANISM_UNSPECIFIED}
     *                             for rows without a stored mechanism, or {@code null} for all mechanisms
     * @param searchText           free-text search across the table's UI fields, or {@code null}
     * @param capturedAtFromMillis inclusive lower bound for capture time, in epoch millis, or {@code null}
     * @param capturedAtToMillis   inclusive upper bound for capture time, in epoch millis, or {@code null}
     */
    public record PacketQuery(PacketLogEntry.Direction direction,
                              String packetType,
                              String transportMechanism,
                              String searchText,
                              Long capturedAtFromMillis,
                              Long capturedAtToMillis) {

        public PacketQuery {
            packetType = normalizeNullableText(packetType);
            transportMechanism = normalizeNullableText(transportMechanism);
            searchText = normalizeNullableText(searchText);
        }

        /**
         * @return normalized SQL pattern for LIKE search, or {@code null} when search is disabled
         */
        public String searchPattern() {
            return searchText == null ? null : "%" + searchText.toLowerCase(Locale.ROOT) + "%";
        }

        /**
         * Builds additional SQL patterns for node-address searches.
         * This preserves compatibility between the UI form {@code !1dc26363}
         * and older {@code from_node}/{@code to_node} values stored as names
         * with decimal IDs, or as decimal IDs alone.
         */
        public List<String> nodeAddressSearchPatterns() {
            return resolveNodeAddressSearchPatterns(searchText);
        }
    }

    /**
     * Pagination cursor in the global {@code captured_at DESC, id DESC} order.
     * A cursor always points to one concrete row from the current page.
 *
     * @param capturedAt packet capture time
     * @param id         database row identifier
     */
    public record PageCursor(long capturedAt, long id) {

        /**
         * @param entry row from the current page
         * @return cursor for that row, or {@code null} when no row is supplied
         */
        public static PageCursor fromEntry(PacketLogEntry entry) {
            if (entry == null) {
                return null;
            }
            return new PageCursor(entry.getCapturedAt(), entry.getId());
        }
    }

    /**
     * One page of packet-table data.
     * The entries are already sorted as the UI displays them, newest first.
     * {@link #hasNewer()} and {@link #hasOlder()} describe neighboring pages
     * within the same filtered result set, and {@link #entries()} never exceeds
     * the limit requested from the service.
 *
     * @param entries            rows on the current page
     * @param hasNewer           whether newer rows are available in the same result set
     * @param hasOlder           whether older rows are available in the same result set
     * @param totalMatchingCount total number of rows that match the filter
     * @param totalStoredCount   total number of stored rows, regardless of filters
     */
    public record PacketPage(List<PacketLogEntry> entries,
                             boolean hasNewer,
                             boolean hasOlder,
                             int totalMatchingCount,
                             int totalStoredCount) {
    }

    private record SqlQuery(String sql, List<Object> params) {}

    private static PacketMonitorService instance;

    private final AtomicBoolean captureEnabled = new AtomicBoolean(false);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private Connection dbConnection;
    private PreparedStatement insertStmt;

    private PacketMonitorService() {
        initDb();
    }

    /**
     * @return singleton packet monitor service instance
     */
    public static synchronized PacketMonitorService getInstance() {
        if (instance == null) {
            instance = new PacketMonitorService();
        }
        return instance;
    }

    /**
     * @return initialized singleton, or {@code null} if the service has not been created yet
     */
    public static synchronized PacketMonitorService getIfInitialized() {
        return instance;
    }

    /**
     * Closes the singleton if it has been created.
     * The next {@link #getInstance()} call will create a fresh service and
     * initialize its JDBC resources again.
     */
    public static synchronized void closeIfInitialized() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    public boolean isCaptureEnabled() {
        return captureEnabled.get();
    }

    public void startCapture() {
        if (captureEnabled.compareAndSet(false, true)) {
            notifyCaptureStateChanged(true);
        }
    }

    public void stopCapture() {
        if (captureEnabled.compareAndSet(true, false)) {
            notifyCaptureStateChanged(false);
        }
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a live-event listener.
     * The call is valid even when the listener was not registered.
 *
     * @param listener listener that should stop receiving events
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Records an incoming packet for a specific connection.
 *
     * @param connectionId connection identifier in {@link ConnectionManager}
     * @param packet       protobuf packet
     */
    public void recordIncoming(String connectionId, MeshProtos.MeshPacket packet) {
        recordPacket(Direction.INCOMING, packet, resolveOwnerNodeId(connectionId), resolveDeviceState(connectionId));
    }

    /**
     * Records an outgoing packet for a specific connection.
 *
     * @param connectionId connection identifier in {@link ConnectionManager}
     * @param packet       protobuf packet
     */
    public void recordOutgoing(String connectionId, MeshProtos.MeshPacket packet) {
        recordPacket(Direction.OUTGOING, packet, resolveOwnerNodeId(connectionId), resolveDeviceState(connectionId));
    }

    /**
     * Records an incoming raw protocol packet that is not a Meshtastic protobuf.
     * <p>
     * This is used by the MeshCore Companion Protocol. The monitor stores the
     * raw bytes, a HEX/ASCII preview, and a human-readable packet type without
     * attempting to convert the payload into a {@link MeshProtos.MeshPacket}.
 *
     * @param connectionId connection identifier in {@link ConnectionManager}
     * @param packetType   raw packet type used by the UI filter
     * @param payloadText  short payload description
     * @param packetBytes  original packet bytes
     */
    public void recordRawIncoming(String connectionId, String packetType, String payloadText, byte[] packetBytes) {
        recordRawPacket(Direction.INCOMING, connectionId, packetType, payloadText, packetBytes);
    }

    /**
     * Records an outgoing raw protocol packet that is not a Meshtastic protobuf.
 *
     * @param connectionId connection identifier in {@link ConnectionManager}
     * @param packetType   raw packet type used by the UI filter
     * @param payloadText  short payload description
     * @param packetBytes  original packet bytes
     */
    public void recordRawOutgoing(String connectionId, String packetType, String payloadText, byte[] packetBytes) {
        recordRawPacket(Direction.OUTGOING, connectionId, packetType, payloadText, packetBytes);
    }

    /**
     * Internal journal entry point for protobuf packets.
     * If capture is disabled, the packet is ignored silently. Listeners are
     * notified only after a successful database insert, and live events follow
     * the same order as successful inserts.
     */
    synchronized void recordPacket(Direction direction,
                                   MeshProtos.MeshPacket packet,
                                   String ownerNodeId,
                                   DeviceState deviceState) {
        if (!captureEnabled.get() || packet == null) {
            return;
        }
        if (!shouldRecordPacket(direction, packet)) {
            return;
        }
        if (insertStmt == null) {
            log.warn("Packet monitor DB not initialized — packet dropped");
            return;
        }

        Direction loggedDirection = resolveLoggedDirection(direction, packet, ownerNodeId, deviceState);
        String transportMechanism = resolveStoredTransportMechanism(packet, loggedDirection);
        PacketDebugFormatter.PacketDetails details =
                PacketDebugFormatter.describeMeshPacket(packet, direction, deviceState);
        PacketLogEntry entry = new PacketLogEntry(
                ownerNodeId != null ? ownerNodeId : "",
                details.capturedAtMillis(),
                loggedDirection,
                details.packetType(),
                transportMechanism,
                details.fromNode(),
                details.toNode(),
                details.payloadText(),
                packet.toByteArray()
        );

        persistEntry(entry);
    }

    private synchronized void recordRawPacket(Direction direction,
                                              String connectionId,
                                              String packetType,
                                              String payloadText,
                                              byte[] packetBytes) {
        if (!captureEnabled.get() || packetBytes == null || packetBytes.length == 0) {
            return;
        }
        if (insertStmt == null) {
            log.warn("Packet monitor DB not initialized — raw packet dropped");
            return;
        }

        String ownerNodeId = resolveOwnerNodeId(connectionId);
        PacketLogEntry entry = new PacketLogEntry(
                ownerNodeId,
                System.currentTimeMillis(),
                direction,
                packetType != null && !packetType.isBlank() ? packetType : "RAW",
                "MESHCORE_COMPANION",
                direction == Direction.OUTGOING ? ownerNodeId : "MeshCore",
                direction == Direction.OUTGOING ? "MeshCore" : ownerNodeId,
                payloadText,
                packetBytes
        );
        persistEntry(entry);
    }

    private void persistEntry(PacketLogEntry entry) {
        if (entry == null || insertStmt == null) {
            return;
        }
        try {
            insertStmt.setString(1, entry.getOwnerNodeId());
            insertStmt.setLong(2, entry.getCapturedAt());
            insertStmt.setString(3, entry.getDirection().name());
            insertStmt.setString(4, entry.getPacketType());
            insertStmt.setString(5, entry.getTransportMechanism());
            insertStmt.setString(6, entry.getFromNode());
            insertStmt.setString(7, entry.getToNode());
            insertStmt.setString(8, entry.getPayloadText());
            insertStmt.setBytes(9, entry.getPacketBytes());
            insertStmt.executeUpdate();

            try (ResultSet keys = insertStmt.getGeneratedKeys()) {
                if (keys.next()) {
                    entry.setId(keys.getLong(1));
                }
            }
            notifyPacketLogged(entry);
        } catch (SQLException e) {
            log.error("Failed to persist LoRa packet log", e);
        }
    }

    /**
     * Loads all stored packets in newest-first order.
     * The UI can use the returned list directly as a table model.
 *
     * @return entries ordered by {@code captured_at DESC, id DESC}
     */
    public synchronized List<PacketLogEntry> loadAll() {
        return loadAll(null);
    }

    /**
     * Loads all stored packets that match the filter, in newest-first order.
 *
     * @param query table filter; {@code null} means no additional conditions
     * @return entries ordered by {@code captured_at DESC, id DESC}
     */
    public synchronized List<PacketLogEntry> loadAll(PacketQuery query) {
        List<PacketLogEntry> entries = new ArrayList<>();
        if (dbConnection == null) {
            return entries;
        }

        SqlQuery sqlQuery = buildFilteredQuery("""
                SELECT id, owner_node_id, captured_at, direction, packet_type, transport_mechanism,
                       from_node, to_node, payload_text, packet_bytes
                FROM lora_packet_logs
                WHERE 1 = 1
                """, query, null, null, true, false);
        String sql = sqlQuery.sql() + "\nORDER BY captured_at DESC, id DESC";

        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            bindParams(ps, sqlQuery.params());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PacketLogEntry entry = new PacketLogEntry(
                            rs.getString("owner_node_id"),
                            rs.getLong("captured_at"),
                            Direction.valueOf(rs.getString("direction")),
                            rs.getString("packet_type"),
                            rs.getString("transport_mechanism"),
                            rs.getString("from_node"),
                            rs.getString("to_node"),
                            rs.getString("payload_text"),
                            rs.getBytes("packet_bytes")
                    );
                    entry.setId(rs.getLong("id"));
                    entries.add(entry);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load LoRa packet logs", e);
        }

        return entries;
    }

    /**
     * Streams all packets that match the filter in fixed-size batches.
     * This is intended for operations such as export, where loading the entire
     * result set into memory would be unnecessary or unsafe.
 *
     * @param query     table filter; {@code null} means no additional conditions
     * @param batchSize maximum number of rows in one batch
     * @param consumer  callback that receives batches in {@code captured_at DESC, id DESC} order
     * @return actual number of processed entries
     * @throws IOException if the consumer fails or database rows cannot be read
     */
    public long forEachMatchingBatch(PacketQuery query,
                                     int batchSize,
                                     PacketBatchConsumer consumer) throws IOException {
        if (consumer == null) {
            return 0;
        }

        int safeBatchSize = Math.max(1, batchSize);
        PageCursor cursor = null;
        PageRequestKind requestKind = PageRequestKind.LATEST;
        long processed = 0;

        while (true) {
            List<PacketLogEntry> batch = loadExportBatch(query, cursor, requestKind, safeBatchSize);

            if (batch.isEmpty()) {
                return processed;
            }

            consumer.accept(Collections.unmodifiableList(batch));
            processed += batch.size();

            if (batch.size() < safeBatchSize) {
                return processed;
            }

            cursor = PageCursor.fromEntry(batch.getLast());
            requestKind = PageRequestKind.OLDER;
        }
    }

    /**
     * @return total number of rows that match the supplied filter
     */
    public synchronized int countMatchingPackets(PacketQuery query) {
        return countMatching(query);
    }

    /**
     * Loads the first table page in newest-first order.
     * Used when the window opens, filters change, or live data is reconciled.
 *
     * @param query table filter
     * @param limit maximum number of rows kept in the window
     * @return result page with pagination metadata
     */
    public synchronized PacketPage loadLatestPage(PacketQuery query, int limit) {
        return loadPage(query, limit, null, PageRequestKind.LATEST);
    }

    /**
     * Loads a fixed table frame by offset in the global
     * {@code captured_at DESC, id DESC}.
     * The UI uses this for step-by-step navigation between equal-sized pages.
 *
     * @param query  table filter
     * @param offset offset from the start of the result set; {@code 0} is the newest page
     * @param limit  frame size
     * @return page and metadata describing the availability of neighboring frames
     */
    public synchronized PacketPage loadPageFrame(PacketQuery query, int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, limit);
        int totalMatchingCount = countMatching(query);
        int totalStoredCount = countAllPackets();
        List<PacketLogEntry> entries = new ArrayList<>();

        if (dbConnection == null) {
            return new PacketPage(entries, false, false, totalMatchingCount, totalStoredCount);
        }

        SqlQuery sqlQuery = buildFilteredQuery("""
                SELECT id, owner_node_id, captured_at, direction, packet_type, transport_mechanism,
                       from_node, to_node, payload_text, packet_bytes
                FROM lora_packet_logs
                WHERE 1 = 1
                """, query, null, null, true, false);
        String sql = sqlQuery.sql() + "\nORDER BY captured_at DESC, id DESC\nLIMIT ? OFFSET ?";
        List<Object> params = new ArrayList<>(sqlQuery.params());
        params.add(safeLimit);
        params.add(safeOffset);

        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(readEntry(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load packet monitor page frame", e);
            return new PacketPage(List.of(), safeOffset > 0, false, totalMatchingCount, totalStoredCount);
        }

        boolean hasNewer = safeOffset > 0;
        boolean hasOlder = safeOffset + entries.size() < totalMatchingCount;
        return new PacketPage(entries, hasNewer, hasOlder, totalMatchingCount, totalStoredCount);
    }

    /**
     * Loads the next page of older packets relative to the bottom row of the current page.
 *
     * @param query           table filter
     * @param oldestExclusive cursor for the oldest visible row; that row is excluded from the new page
     * @param limit           maximum number of rows kept in the window
     * @return page of older entries
     */
    public synchronized PacketPage loadOlderPage(PacketQuery query, PageCursor oldestExclusive, int limit) {
        return loadPage(query, limit, oldestExclusive, PageRequestKind.OLDER);
    }

    /**
     * Loads the next page of newer packets relative to the top row of the current page.
 *
     * @param query           table filter
     * @param newestExclusive cursor for the newest visible row; that row is excluded from the new page
     * @param limit           maximum number of rows kept in the window
     * @return page of newer entries
     */
    public synchronized PacketPage loadNewerPage(PacketQuery query, PageCursor newestExclusive, int limit) {
        return loadPage(query, limit, newestExclusive, PageRequestKind.NEWER);
    }

    /**
     * Loads available packet-type filter values directly from the database.
     * The packet-type filter itself is intentionally ignored while building the
     * list so the combo box remains a source of choices rather than a mirror of
     * the currently selected type.
 *
     * @param query direction and search filter
     * @return sorted packet type list
     */
    public synchronized List<String> loadPacketTypes(PacketQuery query) {
        List<String> packetTypes = new ArrayList<>();
        if (dbConnection == null) {
            return packetTypes;
        }

        PacketQuery typeQuery = query != null
                ? new PacketQuery(
                        query.direction(),
                        null,
                        query.transportMechanism(),
                        query.searchText(),
                        query.capturedAtFromMillis(),
                        query.capturedAtToMillis())
                : new PacketQuery(null, null, null, null, null, null);

        SqlQuery sqlQuery = buildFilteredQuery("""
                SELECT DISTINCT packet_type
                FROM lora_packet_logs
                WHERE 1 = 1
                """, typeQuery, null, null, false, false);
        String sql = sqlQuery.sql() + "\nORDER BY packet_type";

        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            bindParams(ps, sqlQuery.params());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String packetType = rs.getString("packet_type");
                    if (packetType != null && !packetType.isBlank()) {
                        packetTypes.add(packetType);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load packet monitor packet types", e);
        }

        return packetTypes;
    }

    /**
     * @return total number of stored log rows, ignoring UI filters
     */
    public synchronized int countAllPackets() {
        if (dbConnection == null) {
            return 0;
        }
        SqlQuery sqlQuery = buildFilteredQuery("""
                SELECT COUNT(*)
                FROM lora_packet_logs
                WHERE 1 = 1
                """, null, null, null, true, false);
        try (PreparedStatement ps = dbConnection.prepareStatement(sqlQuery.sql())) {
            bindParams(ps, sqlQuery.params());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("Failed to count packet monitor rows", e);
            return 0;
        }
    }

    /**
     * Clears the packet journal completely and notifies listeners.
     * After a successful clear, local UI state should be treated as stale.
     */
    public synchronized void clear() {
        if (dbConnection == null) {
            return;
        }
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.executeUpdate("DELETE FROM lora_packet_logs");
            notifyCleared();
        } catch (SQLException e) {
            log.error("Failed to clear monitor logs", e);
        }
    }

    /**
     * Releases the service's JDBC resources.
     * Used during application shutdown and when tests reset the singleton.
     */
    public void close() {
        closeStatements();
        dbConnection = null;
    }

    public synchronized void prepareForDatabaseReset() {
        closeStatements();
        dbConnection = null;
    }

    public synchronized void reinitializeAfterDatabaseReset() {
        initDb();
        notifyCleared();
    }

    private void closeStatements() {
        try {
            if (insertStmt != null) {
                insertStmt.close();
            }
        } catch (SQLException e) {
            log.error("Failed to close packet monitor statements", e);
        } finally {
            insertStmt = null;
        }
    }

    private void initDb() {
        try {
            closeStatements();
            dbConnection = DatabaseProvider.getConnection();
            if (dbConnection == null) {
                log.error("Packet monitor DB initialization skipped because database connection is unavailable");
                return;
            }
            try (Statement stmt = dbConnection.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS lora_packet_logs (
                            id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
                            owner_node_id       VARCHAR(20) NOT NULL DEFAULT '',
                            captured_at         BIGINT NOT NULL,
                            direction           VARCHAR(10) NOT NULL,
                            packet_type         VARCHAR(40) NOT NULL,
                            transport_mechanism VARCHAR(40),
                            from_node           VARCHAR(160),
                            to_node             VARCHAR(160),
                            payload_text        CLOB,
                            packet_bytes        BLOB NOT NULL
                        )
                        """);
                stmt.execute("""
                        ALTER TABLE lora_packet_logs
                        ADD COLUMN IF NOT EXISTS transport_mechanism VARCHAR(40)
                        """);
                stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_lora_owner_ts
                        ON lora_packet_logs (owner_node_id, captured_at)
                        """);
                stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_lora_type
                        ON lora_packet_logs (packet_type)
                        """);
                stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_lora_transport
                        ON lora_packet_logs (transport_mechanism)
                        """);
            }

            insertStmt = dbConnection.prepareStatement("""
                    INSERT INTO lora_packet_logs (
                        owner_node_id, captured_at, direction, packet_type,
                        transport_mechanism, from_node, to_node, payload_text, packet_bytes
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            normalizeLegacyDirections();
            normalizeLegacyTransportMechanisms();
        } catch (Exception e) {
            log.error("Failed to initialize packet monitor DB", e);
        }
    }

    private void notifyPacketLogged(PacketLogEntry entry) {
        for (Listener listener : listeners) {
            try {
                listener.onPacketLogged(entry);
            } catch (Exception e) {
                log.error("Packet monitor listener failed onPacketLogged", e);
            }
        }
    }

    private void notifyCaptureStateChanged(boolean enabled) {
        for (Listener listener : listeners) {
            try {
                listener.onCaptureStateChanged(enabled);
            } catch (Exception e) {
                log.error("Packet monitor listener failed onCaptureStateChanged", e);
            }
        }
    }

    private void notifyCleared() {
        for (Listener listener : listeners) {
            try {
                listener.onCleared();
            } catch (Exception e) {
                log.error("Packet monitor listener failed onCleared", e);
            }
        }
    }

    private static String resolveOwnerNodeId(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return "";
        }
        String ownerNodeId = ConnectionManager.getInstance().getOwnerNodeId(connectionId);
        return ownerNodeId != null ? ownerNodeId : "";
    }

    private static DeviceState resolveDeviceState(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return null;
        }
        return ConnectionManager.getInstance().getDeviceState(connectionId);
    }

    private void normalizeLegacyDirections() {
        if (dbConnection == null) {
            return;
        }

        int updatesQueued = 0;
        try (PreparedStatement select = dbConnection.prepareStatement("""
                SELECT id, owner_node_id, packet_bytes
                FROM lora_packet_logs
                WHERE direction = ?
                """);
             PreparedStatement update = dbConnection.prepareStatement("""
                     UPDATE lora_packet_logs
                     SET direction = ?
                     WHERE id = ?
                     """)) {
            select.setString(1, Direction.INCOMING.name());
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    MeshProtos.MeshPacket packet = parsePacketBytes(rs.getLong("id"), rs.getBytes("packet_bytes"));
                    if (packet == null) {
                        continue;
                    }
                    Direction normalized = resolveLoggedDirection(
                            Direction.INCOMING,
                            packet,
                            rs.getString("owner_node_id"),
                            null
                    );
                    if (normalized == Direction.INCOMING) {
                        continue;
                    }
                    update.setString(1, normalized.name());
                    update.setLong(2, rs.getLong("id"));
                    update.addBatch();
                    updatesQueued++;
                }
            }
            if (updatesQueued > 0) {
                update.executeBatch();
                log.info("Normalized {} legacy LoRa packet directions for self-origin packets", updatesQueued);
            }
        } catch (SQLException e) {
            log.error("Failed to normalize legacy LoRa packet directions", e);
        }
    }

    private void normalizeLegacyTransportMechanisms() {
        if (dbConnection == null) {
            return;
        }

        int updatesQueued = 0;
        try (PreparedStatement select = dbConnection.prepareStatement("""
                SELECT id, direction, packet_bytes
                FROM lora_packet_logs
                WHERE transport_mechanism IS NULL OR TRIM(transport_mechanism) = ''
                """);
             PreparedStatement update = dbConnection.prepareStatement("""
                     UPDATE lora_packet_logs
                     SET transport_mechanism = ?
                     WHERE id = ?
                     """)) {
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    MeshProtos.MeshPacket packet = parsePacketBytes(rs.getLong("id"), rs.getBytes("packet_bytes"));
                    if (packet == null) {
                        continue;
                    }
                    Direction storedDirection = Direction.valueOf(rs.getString("direction"));
                    String transportMechanism = resolveStoredTransportMechanism(packet, storedDirection);
                    if (transportMechanism == null || transportMechanism.isBlank()) {
                        continue;
                    }
                    update.setString(1, transportMechanism);
                    update.setLong(2, rs.getLong("id"));
                    update.addBatch();
                    updatesQueued++;
                }
            }
            if (updatesQueued > 0) {
                update.executeBatch();
                log.info("Backfilled {} legacy LoRa packet transport mechanisms", updatesQueued);
            }
        } catch (SQLException e) {
            log.error("Failed to backfill legacy LoRa packet transport mechanisms", e);
        }
    }

    private static MeshProtos.MeshPacket parsePacketBytes(long id, byte[] packetBytes) {
        if (packetBytes == null || packetBytes.length == 0) {
            return null;
        }
        try {
            return MeshProtos.MeshPacket.parseFrom(packetBytes);
        } catch (InvalidProtocolBufferException e) {
            log.debug("Skipping packet direction normalization for row {}: invalid MeshPacket bytes", id, e);
            return null;
        }
    }

    private static Direction resolveLoggedDirection(Direction transportDirection,
                                                    MeshProtos.MeshPacket packet,
                                                    String ownerNodeId,
                                                    DeviceState deviceState) {
        if (transportDirection != Direction.INCOMING || packet == null) {
            return transportDirection;
        }

        Integer localNodeNum = resolveLocalNodeNum(ownerNodeId, deviceState);
        if (localNodeNum == null) {
            return transportDirection;
        }

        long packetFrom = Integer.toUnsignedLong(packet.getFrom());
        long localNode = Integer.toUnsignedLong(localNodeNum);
        if (packetFrom != localNode) {
            return transportDirection;
        }

        if (arrivedOverRadio(packet)) {
            // In the mesh monitor, a self-origin LoRa packet should be shown as outgoing:
            // the desktop received it as FromRadio, but the node itself put it on the air.
            return Direction.OUTGOING;
        }

        return Direction.INTERNAL;
    }

    private static boolean arrivedOverRadio(MeshProtos.MeshPacket packet) {
        return switch (packet.getTransportMechanism()) {
            case TRANSPORT_LORA,
                 TRANSPORT_LORA_ALT1,
                 TRANSPORT_LORA_ALT2,
                 TRANSPORT_LORA_ALT3 -> true;
            default -> false;
        };
    }

    private static boolean shouldRecordPacket(Direction direction, MeshProtos.MeshPacket packet) {
        if (direction == Direction.OUTGOING) {
            MeshProtos.MeshPacket.TransportMechanism mechanism = packet.getTransportMechanism();
            return mechanism == null
                    || mechanism == MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_INTERNAL
                    || mechanism == MeshProtos.MeshPacket.TransportMechanism.UNRECOGNIZED
                    || arrivedOverRadio(packet);
        }
        return arrivedOverRadio(packet);
    }

    private static String resolveStoredTransportMechanism(MeshProtos.MeshPacket packet, Direction direction) {
        if (packet == null) {
            return "";
        }
        MeshProtos.MeshPacket.TransportMechanism mechanism = packet.getTransportMechanism();
        if (mechanism == null
                || mechanism == MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_INTERNAL
                || mechanism == MeshProtos.MeshPacket.TransportMechanism.UNRECOGNIZED) {
            if (direction == Direction.OUTGOING) {
                return MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA.name();
            }
            return "";
        }
        return mechanism.name();
    }

    private static Integer resolveLocalNodeNum(String ownerNodeId, DeviceState deviceState) {
        if (deviceState != null && deviceState.getMyNodeNum() != 0) {
            return deviceState.getMyNodeNum();
        }
        return parseStandardNodeId(ownerNodeId);
    }

    private PacketPage loadPage(PacketQuery query, int limit, PageCursor cursor, PageRequestKind requestKind) {
        int safeLimit = Math.max(1, limit);
        int totalMatchingCount = countMatching(query);
        int totalStoredCount = countAllPackets();
        List<PacketLogEntry> entries;

        if (dbConnection == null) {
            return new PacketPage(List.of(), false, false, totalMatchingCount, totalStoredCount);
        }
        if ((requestKind == PageRequestKind.OLDER || requestKind == PageRequestKind.NEWER) && cursor == null) {
            return new PacketPage(List.of(), false, false, totalMatchingCount, totalStoredCount);
        }

        try {
            entries = loadExportBatch(query, cursor, requestKind, safeLimit);
        } catch (IOException e) {
            return new PacketPage(List.of(), false, false, totalMatchingCount, totalStoredCount);
        }

        if (requestKind == PageRequestKind.NEWER) {
            Collections.reverse(entries);
        }

        boolean hasNewer = !entries.isEmpty()
                && existsNewerThan(query, PageCursor.fromEntry(entries.getFirst()));
        boolean hasOlder = !entries.isEmpty()
                && existsOlderThan(query, PageCursor.fromEntry(entries.getLast()));

        return new PacketPage(entries, hasNewer, hasOlder, totalMatchingCount, totalStoredCount);
    }

    private synchronized List<PacketLogEntry> loadExportBatch(PacketQuery query,
                                                              PageCursor cursor,
                                                              PageRequestKind requestKind,
                                                              int limit) throws IOException {
        List<PacketLogEntry> entries = new ArrayList<>(Math.max(1, limit));
        if (dbConnection == null) {
            return entries;
        }
        if ((requestKind == PageRequestKind.OLDER || requestKind == PageRequestKind.NEWER) && cursor == null) {
            return entries;
        }

        SqlQuery sqlQuery = buildPageQuery(query, cursor, requestKind, Math.max(1, limit));
        try (PreparedStatement ps = dbConnection.prepareStatement(sqlQuery.sql())) {
            bindParams(ps, sqlQuery.params());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(readEntry(rs));
                }
            }
            return entries;
        } catch (SQLException e) {
            log.error("Failed to load paged LoRa packet logs", e);
            throw new IOException("Failed to load paged LoRa packet logs", e);
        }
    }

    private int countMatching(PacketQuery query) {
        if (dbConnection == null) {
            return 0;
        }

        SqlQuery sqlQuery = buildFilteredQuery("""
                SELECT COUNT(*)
                FROM lora_packet_logs
                WHERE 1 = 1
                """, query, null, null, true, false);

        try (PreparedStatement ps = dbConnection.prepareStatement(sqlQuery.sql())) {
            bindParams(ps, sqlQuery.params());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("Failed to count filtered LoRa packet logs", e);
            return 0;
        }
    }

    private boolean existsOlderThan(PacketQuery query, PageCursor cursor) {
        return existsRelativeTo(query, cursor, true);
    }

    private boolean existsNewerThan(PacketQuery query, PageCursor cursor) {
        return existsRelativeTo(query, cursor, false);
    }

    private boolean existsRelativeTo(PacketQuery query, PageCursor cursor, boolean older) {
        if (dbConnection == null || cursor == null) {
            return false;
        }

        SqlQuery sqlQuery = buildFilteredQuery("""
                SELECT 1
                FROM lora_packet_logs
                WHERE 1 = 1
                """, query, cursor, older ? PageRequestKind.OLDER : PageRequestKind.NEWER, true, false);
        String sql = sqlQuery.sql() + "\nLIMIT 1";

        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            bindParams(ps, sqlQuery.params());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Failed to probe neighbouring packet monitor page", e);
            return false;
        }
    }

    private SqlQuery buildPageQuery(PacketQuery query, PageCursor cursor, PageRequestKind requestKind, int limit) {
        SqlQuery sqlQuery = buildFilteredQuery("""
                SELECT id, owner_node_id, captured_at, direction, packet_type, transport_mechanism,
                       from_node, to_node, payload_text, packet_bytes
                FROM lora_packet_logs
                WHERE 1 = 1
                """, query, cursor, requestKind, true, true);

        String orderBy = switch (requestKind) {
            case NEWER -> "\nORDER BY captured_at ASC, id ASC";
            case LATEST, OLDER -> "\nORDER BY captured_at DESC, id DESC";
        };

        List<Object> params = new ArrayList<>(sqlQuery.params());
        params.add(limit);
        return new SqlQuery(sqlQuery.sql() + orderBy + "\nLIMIT ?", params);
    }

    private SqlQuery buildFilteredQuery(String baseSql,
                                        PacketQuery query,
                                        PageCursor cursor,
                                        PageRequestKind requestKind,
                                        boolean includeTypeFilter,
                                        boolean includeLimitPlaceholder) {
        StringBuilder sql = new StringBuilder(baseSql);
        List<Object> params = new ArrayList<>();

        sql.append("""
                
                AND transport_mechanism IN (
                    'TRANSPORT_LORA',
                    'TRANSPORT_LORA_ALT1',
                    'TRANSPORT_LORA_ALT2',
                    'TRANSPORT_LORA_ALT3',
                    'MESHCORE_COMPANION'
                )
                """);

        if (query != null && query.direction() != null) {
            sql.append("\nAND direction = ?");
            params.add(query.direction().name());
        }
        if (includeTypeFilter && query != null && query.packetType() != null) {
            sql.append("\nAND packet_type = ?");
            params.add(query.packetType());
        }
        if (query != null && query.transportMechanism() != null) {
            if (TRANSPORT_MECHANISM_UNSPECIFIED.equals(query.transportMechanism())) {
                sql.append("\nAND (transport_mechanism IS NULL OR TRIM(transport_mechanism) = '')");
            } else {
                sql.append("\nAND transport_mechanism = ?");
                params.add(query.transportMechanism());
            }
        }
        if (query != null && query.searchPattern() != null) {
            List<String> nodeAddressPatterns = query.nodeAddressSearchPatterns();
            sql.append("""
                    
                    AND (
                        LOWER(COALESCE(packet_type, '')) LIKE ?
                        OR LOWER(COALESCE(transport_mechanism, '')) LIKE ?
                        OR LOWER(CASE
                            WHEN transport_mechanism IS NULL OR TRIM(transport_mechanism) = '' THEN 'локальный local'
                            WHEN transport_mechanism = 'TRANSPORT_LORA' THEN 'lora'
                            WHEN transport_mechanism = 'TRANSPORT_LORA_ALT1' THEN 'lora alt 1'
                            WHEN transport_mechanism = 'TRANSPORT_LORA_ALT2' THEN 'lora alt 2'
                            WHEN transport_mechanism = 'TRANSPORT_LORA_ALT3' THEN 'lora alt 3'
                            WHEN transport_mechanism = 'MESHCORE_COMPANION' THEN 'meshcore companion'
                            WHEN transport_mechanism = 'TRANSPORT_MQTT' THEN 'mqtt'
                            WHEN transport_mechanism = 'TRANSPORT_MULTICAST_UDP' THEN 'multicast udp'
                            WHEN transport_mechanism = 'TRANSPORT_API' THEN 'api'
                            WHEN transport_mechanism = 'TRANSPORT_INTERNAL' THEN 'локальный local'
                            ELSE transport_mechanism
                        END) LIKE ?
                        OR LOWER(CONCAT(
                            CASE direction
                                WHEN 'INCOMING' THEN 'входящий incoming'
                                WHEN 'OUTGOING' THEN 'исходящий outgoing'
                                WHEN 'INTERNAL' THEN 'внутренний internal'
                                ELSE direction
                            END,
                            ' / ',
                            CASE
                                WHEN direction = 'OUTGOING'
                                     AND (transport_mechanism IS NULL OR TRIM(transport_mechanism) = '')
                                    THEN 'без подтверждения lora no lora acknowledgment'
                                WHEN transport_mechanism IS NULL OR TRIM(transport_mechanism) = '' THEN 'локальный local'
                                WHEN transport_mechanism = 'TRANSPORT_LORA' THEN 'lora'
                                WHEN transport_mechanism = 'TRANSPORT_LORA_ALT1' THEN 'lora alt 1'
                                WHEN transport_mechanism = 'TRANSPORT_LORA_ALT2' THEN 'lora alt 2'
                                WHEN transport_mechanism = 'TRANSPORT_LORA_ALT3' THEN 'lora alt 3'
                                WHEN transport_mechanism = 'TRANSPORT_MQTT' THEN 'mqtt'
                                WHEN transport_mechanism = 'TRANSPORT_MULTICAST_UDP' THEN 'multicast udp'
                                WHEN transport_mechanism = 'TRANSPORT_API' THEN 'api'
                                WHEN transport_mechanism = 'TRANSPORT_INTERNAL' THEN 'локальный local'
                                ELSE LOWER(COALESCE(transport_mechanism, ''))
                            END
                        )) LIKE ?
                        OR LOWER(COALESCE(from_node, '')) LIKE ?
                        OR LOWER(COALESCE(to_node, '')) LIKE ?
                        OR LOWER(COALESCE(CAST(payload_text AS VARCHAR), '')) LIKE ?
                        OR LOWER(CASE direction
                            WHEN 'INCOMING' THEN 'входящий incoming'
                            WHEN 'OUTGOING' THEN 'исходящий outgoing'
                            WHEN 'INTERNAL' THEN 'внутренний internal'
                            ELSE direction
                        END) LIKE ?
                    )
                    """);
            for (int i = 0; i < 8; i++) {
                params.add(query.searchPattern());
            }

            if (!nodeAddressPatterns.isEmpty()) {
                int insertionPoint = sql.lastIndexOf(")");
                StringBuilder addressConditions = new StringBuilder();
                for (String pattern : nodeAddressPatterns) {
                    addressConditions.append("\nOR LOWER(COALESCE(from_node, '')) LIKE ?");
                    addressConditions.append("\nOR LOWER(COALESCE(to_node, '')) LIKE ?");
                    params.add(pattern);
                    params.add(pattern);
                }
                sql.insert(insertionPoint, addressConditions);
            }
        }
        if (query != null && query.capturedAtFromMillis() != null) {
            sql.append("\nAND captured_at >= ?");
            params.add(query.capturedAtFromMillis());
        }
        if (query != null && query.capturedAtToMillis() != null) {
            sql.append("\nAND captured_at <= ?");
            params.add(query.capturedAtToMillis());
        }

        if (cursor != null && requestKind == PageRequestKind.OLDER) {
            sql.append("\nAND (captured_at < ? OR (captured_at = ? AND id < ?))");
            params.add(cursor.capturedAt());
            params.add(cursor.capturedAt());
            params.add(cursor.id());
        } else if (cursor != null && requestKind == PageRequestKind.NEWER) {
            sql.append("\nAND (captured_at > ? OR (captured_at = ? AND id > ?))");
            params.add(cursor.capturedAt());
            params.add(cursor.capturedAt());
            params.add(cursor.id());
        }

        if (!includeLimitPlaceholder) {
            return new SqlQuery(sql.toString(), params);
        }
        return new SqlQuery(sql.toString(), params);
    }

    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof String stringValue) {
                ps.setString(i + 1, stringValue);
            } else if (value instanceof Integer intValue) {
                ps.setInt(i + 1, intValue);
            } else if (value instanceof Long longValue) {
                ps.setLong(i + 1, longValue);
            } else {
                ps.setObject(i + 1, value);
            }
        }
    }

    private static PacketLogEntry readEntry(ResultSet rs) throws SQLException {
        PacketLogEntry entry = new PacketLogEntry(
                rs.getString("owner_node_id"),
                rs.getLong("captured_at"),
                Direction.valueOf(rs.getString("direction")),
                rs.getString("packet_type"),
                rs.getString("transport_mechanism"),
                rs.getString("from_node"),
                rs.getString("to_node"),
                rs.getString("payload_text"),
                rs.getBytes("packet_bytes")
        );
        entry.setId(rs.getLong("id"));
        return entry;
    }

    private static String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> resolveNodeAddressSearchPatterns(String searchText) {
        String normalized = normalizeNullableText(searchText);
        if (normalized == null) {
            return List.of();
        }

        String lowered = normalized.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> patterns = new LinkedHashSet<>();

        Integer parsedNodeNum = parseStandardNodeId(lowered);
        if (parsedNodeNum != null) {
            long unsignedNodeNum = Integer.toUnsignedLong(parsedNodeNum);
            patterns.add("%" + String.format("!%08x", parsedNodeNum) + "%");
            patterns.add("%" + Long.toUnsignedString(unsignedNodeNum) + "%");
            patterns.add("%" + parsedNodeNum + "%");
            if (unsignedNodeNum == BROADCAST_NODE_NUM) {
                patterns.add("%вещание%");
                patterns.add("%broadcast%");
            }
        }

        return List.copyOf(patterns);
    }

    private static Integer parseStandardNodeId(String searchText) {
        if (searchText == null || !STANDARD_NODE_ID_PATTERN.matcher(searchText).matches()) {
            return null;
        }

        String hex = searchText.charAt(0) == '!' ? searchText.substring(1) : searchText;
        try {
            long unsignedValue = Long.parseUnsignedLong(hex, 16);
            return (int) unsignedValue;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
