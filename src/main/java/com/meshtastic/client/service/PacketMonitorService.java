package com.meshtastic.client.service;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сервис захвата и хранения LoRa mesh-пакетов для окна мониторинга.
 * Инкапсулирует приём пакетов из протокольного слоя, их сохранение в БД и
 * рассылку live-событий UI-слушателям.
 *
 * Публичные методы допускают вызов из произвольных потоков. Операции, которые
 * работают с БД и общим состоянием сервиса, синхронизированы на экземпляре.
 */
public final class PacketMonitorService {

    private enum PageRequestKind {
        LATEST,
        OLDER,
        NEWER
    }

    private static final Logger log = LoggerFactory.getLogger(PacketMonitorService.class);

    /**
     * Слушатель событий мониторинга.
     * Контракт: callbacks должны быть быстрыми и не блокировать поток вызова.
     * Переключение на JavaFX thread является обязанностью UI-слоя.
     */
    public interface Listener {
        default void onPacketLogged(PacketLogEntry entry) {}
        default void onCaptureStateChanged(boolean captureEnabled) {}
        default void onCleared() {}
    }

    /**
     * Серверный фильтр таблицы пакетов.
     * Контракт: пустые строки нормализуются к {@code null}, чтобы SQL-слой не
     * различал "пустой фильтр" и "фильтр не задан".
     *
     * @param direction  направление пакета или {@code null} для обоих направлений
     * @param packetType точный тип пакета или {@code null} для всех типов
     * @param searchText поисковая строка по UI-полям таблицы или {@code null}
     */
    public record PacketQuery(PacketLogEntry.Direction direction, String packetType, String searchText) {

        public PacketQuery {
            packetType = normalizeNullableText(packetType);
            searchText = normalizeNullableText(searchText);
        }

        /**
         * @return нормализованный SQL-pattern для LIKE-поиска или {@code null}, если поиск выключен
         */
        public String searchPattern() {
            return searchText == null ? null : "%" + searchText.toLowerCase(Locale.ROOT) + "%";
        }
    }

    /**
     * Курсор пагинации относительно общего порядка {@code captured_at DESC, id DESC}.
     * Контракт: курсор всегда описывает конкретную строку текущей страницы.
     *
     * @param capturedAt время захвата пакета
     * @param id         идентификатор строки в БД
     */
    public record PageCursor(long capturedAt, long id) {

        /**
         * @param entry запись текущей страницы
         * @return курсор для этой записи или {@code null}, если запись отсутствует
         */
        public static PageCursor fromEntry(PacketLogEntry entry) {
            if (entry == null) {
                return null;
            }
            return new PageCursor(entry.getCapturedAt(), entry.getId());
        }
    }

    /**
     * Одна страница таблицы пакетов.
     * Контракт:
     * - {@link #entries()} уже отсортированы как UI-таблица: новые сверху;
     * - флаги {@link #hasNewer()} и {@link #hasOlder()} описывают наличие
     *   соседних страниц в той же выборке;
     * - размер {@link #entries()} не превышает лимит, запрошенный у сервиса.
     *
     * @param entries            записи текущей страницы
     * @param hasNewer           доступны ли более новые записи в той же выборке
     * @param hasOlder           доступны ли более старые записи в той же выборке
     * @param totalMatchingCount общее число строк, подходящих под фильтр
     * @param totalStoredCount   общее число строк журнала безотносительно фильтра
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
     * @return singleton-экземпляр сервиса мониторинга
     */
    public static synchronized PacketMonitorService getInstance() {
        if (instance == null) {
            instance = new PacketMonitorService();
        }
        return instance;
    }

    /**
     * @return уже инициализированный singleton или {@code null}, если сервис ещё не создавался
     */
    public static synchronized PacketMonitorService getIfInitialized() {
        return instance;
    }

    /**
     * Закрывает singleton, если он был создан.
     * После вызова следующий {@link #getInstance()} создаст новый сервис и заново
     * инициализирует JDBC-ресурсы.
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
     * Удаляет слушателя live-событий.
     * Контракт: вызов допустим даже если слушатель не был зарегистрирован.
     *
     * @param listener слушатель, который больше не должен получать события
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Регистрирует входящий пакет для конкретного подключения.
     *
     * @param connectionId идентификатор подключения в {@link ConnectionManager}
     * @param packet       protobuf-пакет
     */
    public void recordIncoming(String connectionId, MeshProtos.MeshPacket packet) {
        recordPacket(Direction.INCOMING, packet, resolveOwnerNodeId(connectionId), resolveDeviceState(connectionId));
    }

    /**
     * Регистрирует исходящий пакет для конкретного подключения.
     *
     * @param connectionId идентификатор подключения в {@link ConnectionManager}
     * @param packet       protobuf-пакет
     */
    public void recordOutgoing(String connectionId, MeshProtos.MeshPacket packet) {
        recordPacket(Direction.OUTGOING, packet, resolveOwnerNodeId(connectionId), resolveDeviceState(connectionId));
    }

    /**
     * Внутренняя точка записи пакета в журнал.
     * Контракт:
     * - если сбор выключен, пакет тихо игнорируется
     * - уведомление слушателей происходит только после успешного insert в БД
     * - порядок live-событий соответствует порядку успешных insert
     */
    synchronized void recordPacket(Direction direction,
                                   MeshProtos.MeshPacket packet,
                                   String ownerNodeId,
                                   DeviceState deviceState) {
        if (!captureEnabled.get() || packet == null) {
            return;
        }
        if (insertStmt == null) {
            log.warn("Packet monitor DB not initialized — packet dropped");
            return;
        }

        PacketDebugFormatter.PacketDetails details =
                PacketDebugFormatter.describeMeshPacket(packet, direction, deviceState);
        PacketLogEntry entry = new PacketLogEntry(
                ownerNodeId != null ? ownerNodeId : "",
                details.capturedAtMillis(),
                direction,
                details.packetType(),
                details.fromNode(),
                details.toNode(),
                details.payloadText(),
                packet.toByteArray()
        );

        try {
            insertStmt.setString(1, entry.getOwnerNodeId());
            insertStmt.setLong(2, entry.getCapturedAt());
            insertStmt.setString(3, entry.getDirection().name());
            insertStmt.setString(4, entry.getPacketType());
            insertStmt.setString(5, entry.getFromNode());
            insertStmt.setString(6, entry.getToNode());
            insertStmt.setString(7, entry.getPayloadText());
            insertStmt.setBytes(8, entry.getPacketBytes());
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
     * Загружает все сохранённые пакеты в порядке "новые сверху".
     * Контракт: UI может напрямую использовать результат как модель таблицы.
     *
     * @return список записей в порядке {@code captured_at DESC, id DESC}
     */
    public synchronized List<PacketLogEntry> loadAll() {
        List<PacketLogEntry> entries = new ArrayList<>();
        if (dbConnection == null) {
            return entries;
        }

        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT id, owner_node_id, captured_at, direction, packet_type, from_node, to_node, payload_text, packet_bytes
                FROM lora_packet_logs
                ORDER BY captured_at DESC, id DESC
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PacketLogEntry entry = new PacketLogEntry(
                        rs.getString("owner_node_id"),
                        rs.getLong("captured_at"),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getString("packet_type"),
                        rs.getString("from_node"),
                        rs.getString("to_node"),
                        rs.getString("payload_text"),
                        rs.getBytes("packet_bytes")
                );
                entry.setId(rs.getLong("id"));
                entries.add(entry);
            }
        } catch (SQLException e) {
            log.error("Failed to load LoRa packet logs", e);
        }

        return entries;
    }

    /**
     * Загружает первую страницу таблицы в порядке "новые сверху".
     * Используется при открытии окна, смене фильтров и синхронизации с live-данными.
     *
     * @param query фильтр таблицы
     * @param limit максимум записей в памяти окна
     * @return страница выборки и сопутствующие метаданные пагинации
     */
    public synchronized PacketPage loadLatestPage(PacketQuery query, int limit) {
        return loadPage(query, limit, null, PageRequestKind.LATEST);
    }

    /**
     * Загружает фиксированный фрейм таблицы по смещению в общем порядке
     * {@code captured_at DESC, id DESC}.
     * Используется UI-слоем для пошагового перехода между страницами одинакового размера.
     *
     * @param query  фильтр таблицы
     * @param offset смещение от начала выборки; {@code 0} соответствует самой новой странице
     * @param limit  размер одного фрейма
     * @return страница и метаданные доступности соседних фреймов
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
                SELECT id, owner_node_id, captured_at, direction, packet_type, from_node, to_node, payload_text, packet_bytes
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
     * Загружает следующую страницу более старых пакетов относительно нижней записи текущей страницы.
     *
     * @param query           фильтр таблицы
     * @param oldestExclusive курсор самой старой видимой записи; сама запись в новую страницу не попадает
     * @param limit           максимум записей в памяти окна
     * @return страница более старых записей
     */
    public synchronized PacketPage loadOlderPage(PacketQuery query, PageCursor oldestExclusive, int limit) {
        return loadPage(query, limit, oldestExclusive, PageRequestKind.OLDER);
    }

    /**
     * Загружает следующую страницу более новых пакетов относительно верхней записи текущей страницы.
     *
     * @param query           фильтр таблицы
     * @param newestExclusive курсор самой новой видимой записи; сама запись в новую страницу не попадает
     * @param limit           максимум записей в памяти окна
     * @return страница более новых записей
     */
    public synchronized PacketPage loadNewerPage(PacketQuery query, PageCursor newestExclusive, int limit) {
        return loadPage(query, limit, newestExclusive, PageRequestKind.NEWER);
    }

    /**
     * Загружает доступные значения фильтра по типу пакета напрямую из БД.
     * Контракт: фильтр по типу при формировании списка не используется, чтобы
     * combo-box оставался источником выбора, а не зеркалом уже выбранного типа.
     *
     * @param query фильтр по направлению и поиску
     * @return отсортированный список типов пакетов
     */
    public synchronized List<String> loadPacketTypes(PacketQuery query) {
        List<String> packetTypes = new ArrayList<>();
        if (dbConnection == null) {
            return packetTypes;
        }

        PacketQuery typeQuery = query != null
                ? new PacketQuery(query.direction(), null, query.searchText())
                : new PacketQuery(null, null, null);

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
     * @return общее число сохранённых строк журнала без учёта UI-фильтров
     */
    public synchronized int countAllPackets() {
        if (dbConnection == null) {
            return 0;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("SELECT COUNT(*) FROM lora_packet_logs");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.error("Failed to count packet monitor rows", e);
            return 0;
        }
    }

    /**
     * Полностью очищает журнал пакетов и уведомляет слушателей.
     * Контракт: после успешной очистки локальное состояние UI считается недействительным.
     */
    public synchronized void clear() {
        if (dbConnection == null) {
            return;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("DELETE FROM lora_packet_logs")) {
            ps.executeUpdate();
            notifyCleared();
        } catch (SQLException e) {
            log.error("Failed to clear LoRa packet logs", e);
        }
    }

    /**
     * Освобождает JDBC-ресурсы сервиса.
     * Используется при завершении приложения и при сбросе singleton в тестах.
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
            try (Statement stmt = dbConnection.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS lora_packet_logs (
                            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                            owner_node_id VARCHAR(20) NOT NULL DEFAULT '',
                            captured_at   BIGINT NOT NULL,
                            direction     VARCHAR(10) NOT NULL,
                            packet_type   VARCHAR(40) NOT NULL,
                            from_node     VARCHAR(160),
                            to_node       VARCHAR(160),
                            payload_text  CLOB,
                            packet_bytes  BLOB NOT NULL
                        )
                        """);
                stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_lora_owner_ts
                        ON lora_packet_logs (owner_node_id, captured_at)
                        """);
                stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_lora_type
                        ON lora_packet_logs (packet_type)
                        """);
            }

            insertStmt = dbConnection.prepareStatement("""
                    INSERT INTO lora_packet_logs (
                        owner_node_id, captured_at, direction, packet_type,
                        from_node, to_node, payload_text, packet_bytes
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
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

    private PacketPage loadPage(PacketQuery query, int limit, PageCursor cursor, PageRequestKind requestKind) {
        int safeLimit = Math.max(1, limit);
        int totalMatchingCount = countMatching(query);
        int totalStoredCount = countAllPackets();
        List<PacketLogEntry> entries = new ArrayList<>();

        if (dbConnection == null) {
            return new PacketPage(entries, false, false, totalMatchingCount, totalStoredCount);
        }
        if ((requestKind == PageRequestKind.OLDER || requestKind == PageRequestKind.NEWER) && cursor == null) {
            return new PacketPage(entries, false, false, totalMatchingCount, totalStoredCount);
        }

        SqlQuery sqlQuery = buildPageQuery(query, cursor, requestKind, safeLimit);
        try (PreparedStatement ps = dbConnection.prepareStatement(sqlQuery.sql())) {
            bindParams(ps, sqlQuery.params());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(readEntry(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load paged LoRa packet logs", e);
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
                SELECT id, owner_node_id, captured_at, direction, packet_type, from_node, to_node, payload_text, packet_bytes
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

        if (query != null && query.direction() != null) {
            sql.append("\nAND direction = ?");
            params.add(query.direction().name());
        }
        if (includeTypeFilter && query != null && query.packetType() != null) {
            sql.append("\nAND packet_type = ?");
            params.add(query.packetType());
        }
        if (query != null && query.searchPattern() != null) {
            sql.append("""
                    
                    AND (
                        LOWER(COALESCE(packet_type, '')) LIKE ?
                        OR LOWER(COALESCE(from_node, '')) LIKE ?
                        OR LOWER(COALESCE(to_node, '')) LIKE ?
                        OR LOWER(COALESCE(CAST(payload_text AS VARCHAR), '')) LIKE ?
                        OR LOWER(CASE direction
                            WHEN 'INCOMING' THEN 'входящий'
                            WHEN 'OUTGOING' THEN 'исходящий'
                            ELSE direction
                        END) LIKE ?
                    )
                    """);
            for (int i = 0; i < 5; i++) {
                params.add(query.searchPattern());
            }
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
}
