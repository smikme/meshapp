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
 * Локальный персистентный кэш нод.
 * <p>
 * Хранит накопленную информацию о нодах между сессиями приложения
 * в H2 embedded БД {@code ~/.meshapp/nodedb}. Используется как fallback
 * когда в {@link com.meshtastic.client.model.DeviceState#getNodeDb()} нет данных.
 * <p>
 * Обновляется при получении данных через config exchange и live-пакеты
 * (NODEINFO_APP, POSITION_APP, TELEMETRY_APP). Запись в БД происходит
 * немедленно (MERGE INTO), без debounce.
 */
public class NodeCacheService {

    private static final Logger log = LoggerFactory.getLogger(NodeCacheService.class);

    private static NodeCacheService instance;

    private final ConcurrentHashMap<Integer, NodeData> cache = new ConcurrentHashMap<>();
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

    public static synchronized void closeIfInitialized() {
        if (instance != null) {
            instance.close();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Инициализация БД
    // ═══════════════════════════════════════════════════════════

    private void initDb() {
        try {
            dbConnection = DatabaseProvider.getConnection();

            try (Statement stmt = dbConnection.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS nodes (
                        node_num      INT PRIMARY KEY,
                        long_name     VARCHAR(100),
                        short_name    VARCHAR(10),
                        node_id       VARCHAR(20),
                        role          VARCHAR(30),
                        hw_model      VARCHAR(50),
                        latitude      DOUBLE,
                        longitude     DOUBLE,
                        altitude      INT,
                        snr           REAL,
                        last_heard    INT,
                        battery_level INT,
                        voltage       REAL,
                        hops_away     INT
                    )
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS telemetry_history (
                        id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
                        ts                  BIGINT NOT NULL,
                        node_num            INT NOT NULL,
                        battery_level       INT,
                        voltage             REAL,
                        channel_utilization REAL,
                        air_util_tx         REAL,
                        temperature         REAL,
                        relative_humidity   REAL,
                        barometric_pressure REAL
                    )
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_telemetry_ts ON telemetry_history (ts)
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_telemetry_node_ts ON telemetry_history (node_num, ts)
                    """);
            }

            mergeStmt = dbConnection.prepareStatement("""
                MERGE INTO nodes (node_num, long_name, short_name, node_id, role, hw_model,
                                  latitude, longitude, altitude, snr, last_heard,
                                  battery_level, voltage, hops_away)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);

            insertTelemetryStmt = dbConnection.prepareStatement("""
                INSERT INTO telemetry_history (ts, node_num, battery_level, voltage,
                    channel_utilization, air_util_tx, temperature, relative_humidity, barometric_pressure)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);

            log.info("Node cache DB initialized");
        } catch (Exception e) {
            log.error("Failed to initialize node cache DB", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Чтение
    // ═══════════════════════════════════════════════════════════

    /**
     * Возвращает ноду из in-memory кэша или H2. Ленивая загрузка из БД,
     * если в памяти нет — результат кэшируется.
     */
    public NodeData get(int nodeNum) {
        NodeData node = cache.get(nodeNum);
        if (node == null) {
            node = loadFromDb(nodeNum);
            if (node != null && node.hasName()) {
                cache.put(nodeNum, node);
            }
        }
        return node;
    }

    /**
     * Загружает одну ноду из БД по nodeNum.
     */
    private NodeData loadFromDb(int nodeNum) {
        if (dbConnection == null) return null;
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT * FROM nodes WHERE node_num = ?")) {
            ps.setInt(1, nodeNum);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readNode(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to load node {} from DB", nodeNum, e);
        }
        return null;
    }

    /**
     * Возвращает все ноды из кэша.
     */
    public Collection<NodeData> getAll() {
        return cache.values();
    }

    /**
     * Возвращает количество нод в кэше.
     */
    public int size() {
        return cache.size();
    }

    /**
     * Возвращает страницу нод из БД, отсортированных по {@code last_heard DESC}.
     * Используется для пагинации на вкладке «Кэш».
     *
     * @param offset смещение (количество пропущенных строк)
     * @param limit  максимальное количество строк на странице
     * @return список нод для указанной страницы (может быть пустым)
     */
    public List<NodeData> loadPage(int offset, int limit) {
        List<NodeData> page = new ArrayList<>();
        if (dbConnection == null) return page;
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
     * Возвращает общее количество нод в БД.
     *
     * @return количество записей в таблице {@code nodes}
     */
    public int countNodesInDb() {
        if (dbConnection == null) return 0;
        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM nodes")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("Failed to count nodes in DB", e);
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════════
    //  Запись (merge)
    // ═══════════════════════════════════════════════════════════

    /**
     * Обновляет ноду в кэше и БД. Применяет merge — новые непустые поля
     * перезаписывают старые, нулевые/пустые поля не затирают имеющиеся данные.
     *
     * @param nodeNum номер ноды
     * @param fresh   свежие данные для merge
     */
    public void update(int nodeNum, NodeData fresh) {
        if (fresh == null) return;
        cache.compute(nodeNum, (key, existing) -> {
            if (existing == null) {
                existing = loadFromDb(nodeNum);
            }
            if (existing == null) {
                existing = new NodeData(nodeNum);
            }
            merge(existing, fresh);
            return existing.hasName() ? existing : null;
        });
        persistNode(nodeNum);
    }

    /**
     * Массовое обновление нод (после config exchange).
     * Для каждой ноды выполняется merge с кэшем, затем batch-запись в БД.
     *
     * @param nodes карта нод из {@code DeviceState.getNodeDb()}
     */
    public void updateAll(Map<Integer, NodeData> nodes) {
        for (Map.Entry<Integer, NodeData> entry : nodes.entrySet()) {
            int nodeNum = entry.getKey();
            NodeData fresh = entry.getValue();
            cache.compute(nodeNum, (key, existing) -> {
                if (existing == null) {
                    existing = loadFromDb(nodeNum);
                }
                if (existing == null) {
                    existing = new NodeData(nodeNum);
                }
                merge(existing, fresh);
                return existing.hasName() ? existing : null;
            });
        }
        persistAll(nodes.keySet());
    }

    /**
     * Обогащает bare-ноду (без имени) данными из кэша/H2.
     * Заполняет только identity-поля (longName, shortName, role, hwModel),
     * если они отсутствуют у ноды. Телеметрию и позицию не трогает —
     * у ноды уже есть свежие данные от устройства.
     *
     * @param node нода из DeviceState для обогащения
     */
    public void enrichFromCache(NodeData node) {
        if (node == null || node.hasName()) return;

        NodeData cached = cache.get(node.getNodeNum());
        if (cached == null) {
            cached = loadFromDb(node.getNodeNum());
            if (cached != null) {
                log.debug("enrichFromCache: loaded !{} from H2, hasName={}", Integer.toHexString(node.getNodeNum()), cached.hasName());
            } else {
                log.debug("enrichFromCache: !{} not found in H2", Integer.toHexString(node.getNodeNum()));
            }
        }
        if (cached == null || !cached.hasName()) return;

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
    }

    /**
     * Полностью очищает кэш: удаляет все записи из H2 и in-memory кэша.
     */
    public synchronized void clearAll() {
        cache.clear();
        if (dbConnection == null) return;
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute("DELETE FROM nodes");
            log.info("Кэш нод полностью очищен");
        } catch (SQLException e) {
            log.error("Ошибка очистки кэша нод", e);
        }
    }

    /** Удалить конкретную ноду и её телеметрию из кэша и БД. */
    public synchronized void deleteNode(int nodeNum) {
        cache.remove(nodeNum);
        if (dbConnection == null) return;
        try (PreparedStatement ps1 = dbConnection.prepareStatement("DELETE FROM telemetry_history WHERE node_num = ?");
             PreparedStatement ps2 = dbConnection.prepareStatement("DELETE FROM nodes WHERE node_num = ?")) {
            ps1.setInt(1, nodeNum);
            ps1.executeUpdate();
            ps2.setInt(1, nodeNum);
            ps2.executeUpdate();
            log.info("Нода !{} удалена из кэша", Integer.toHexString(nodeNum));
        } catch (SQLException e) {
            log.error("Ошибка удаления ноды !{} из кэша", Integer.toHexString(nodeNum), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Телеметрия — персистентная история
    // ═══════════════════════════════════════════════════════════

    /**
     * Сохраняет одну запись телеметрии в H2.
     */
    public synchronized void persistTelemetry(TelemetryEntry entry) {
        if (insertTelemetryStmt == null || entry == null) return;
        try {
            insertTelemetryStmt.setLong(1, entry.getTimestamp());
            insertTelemetryStmt.setInt(2, entry.getNodeNum());
            insertTelemetryStmt.setInt(3, entry.getBatteryLevel());
            insertTelemetryStmt.setFloat(4, entry.getVoltage());
            insertTelemetryStmt.setFloat(5, entry.getChannelUtilization());
            insertTelemetryStmt.setFloat(6, entry.getAirUtilTx());
            insertTelemetryStmt.setFloat(7, entry.getTemperature());
            insertTelemetryStmt.setFloat(8, entry.getRelativeHumidity());
            insertTelemetryStmt.setFloat(9, entry.getBarometricPressure());
            insertTelemetryStmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to persist telemetry entry", e);
        }
    }

    /**
     * Загружает последние {@code limit} записей телеметрии из H2,
     * отсортированных по времени (старые → новые).
     *
     * @param limit максимальное количество записей
     * @return список записей (может быть пустым)
     */
    public List<TelemetryEntry> loadTelemetryHistory(int limit) {
        List<TelemetryEntry> result = new ArrayList<>();
        if (dbConnection == null) return result;
        // Подзапрос берёт последние N записей (DESC), внешний сортирует ASC
        String sql = "SELECT * FROM (SELECT * FROM telemetry_history ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setInt(1, limit);
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
     * Загружает записи телеметрии за указанный период (от {@code sinceEpoch} до текущего момента),
     * отсортированные по времени (старые → новые).
     *
     * @param sinceEpoch метка времени начала периода (epoch seconds), 0 = без ограничений
     * @return список записей (может быть пустым)
     */
    public List<TelemetryEntry> loadTelemetrySince(long sinceEpoch) {
        List<TelemetryEntry> result = new ArrayList<>();
        if (dbConnection == null) return result;
        String sql = sinceEpoch > 0
                ? "SELECT * FROM telemetry_history WHERE ts >= ? ORDER BY ts ASC"
                : "SELECT * FROM telemetry_history ORDER BY ts ASC";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            if (sinceEpoch > 0) ps.setLong(1, sinceEpoch);
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
     * Загружает записи телеметрии для конкретной ноды за указанный период.
     * Фильтрация по nodeNum, периоду, нулевым артефактам и будущим датам выполняется в SQL.
     *
     * @param nodeNum      номер ноды
     * @param sinceEpoch   метка времени начала периода (epoch seconds), 0 = без ограничений
     * @param maxFutureTs  максимально допустимая метка времени (для фильтрации будущих дат)
     * @return список записей, отсортированных по времени ASC (может быть пустым)
     */
    public List<TelemetryEntry> loadTelemetryForNode(int nodeNum, long sinceEpoch, long maxFutureTs) {
        List<TelemetryEntry> result = new ArrayList<>();
        if (dbConnection == null) return result;

        String sql = """
            SELECT * FROM telemetry_history
            WHERE node_num = ?
              AND ts <= ?
              AND (battery_level <> 0 OR channel_utilization <> 0 OR air_util_tx <> 0 OR voltage <> 0)
            """ + (sinceEpoch > 0 ? "  AND ts >= ?\n" : "") + """
            ORDER BY ts ASC
            """;

        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setInt(1, nodeNum);
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
            log.error("Failed to load telemetry for node {} since {}", nodeNum, sinceEpoch, e);
        }
        return result;
    }

    /**
     * Возвращает общее количество записей телеметрии в БД.
     */
    public int countTelemetryEntries() {
        if (dbConnection == null) return 0;
        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM telemetry_history")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("Failed to count telemetry entries", e);
        }
        return 0;
    }

    private static TelemetryEntry readTelemetryRow(ResultSet rs) throws SQLException {
        TelemetryEntry e = new TelemetryEntry(rs.getLong("ts"), rs.getInt("node_num"));
        e.setBatteryLevel(rs.getInt("battery_level"));
        e.setVoltage(rs.getFloat("voltage"));
        e.setChannelUtilization(rs.getFloat("channel_utilization"));
        e.setAirUtilTx(rs.getFloat("air_util_tx"));
        e.setTemperature(rs.getFloat("temperature"));
        e.setRelativeHumidity(rs.getFloat("relative_humidity"));
        e.setBarometricPressure(rs.getFloat("barometric_pressure"));
        return e;
    }

    /**
     * Удаляет записи телеметрии старше указанного количества дней.
     *
     * @param days количество дней (записи старше этого срока удаляются)
     * @return количество удалённых записей
     */
    public int pruneTelemetryHistory(int days) {
        if (dbConnection == null) return 0;
        long cutoff = System.currentTimeMillis() / 1000 - (long) days * 86400;
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "DELETE FROM telemetry_history WHERE ts < ?")) {
            ps.setLong(1, cutoff);
            int deleted = ps.executeUpdate();
            if (deleted > 0) log.info("Pruned {} old telemetry entries (older than {} days)", deleted, days);
            return deleted;
        } catch (SQLException e) {
            log.error("Failed to prune telemetry history", e);
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Импорт из OneMesh
    // ═══════════════════════════════════════════════════════════

    /** URL для загрузки кэша нод из интернета. */
    private static final String ONEMESH_URL =
            "https://map.onemesh.ru/cache/nodes_optimized_v2.json";

    /**
     * Загружает и импортирует ноды из OneMesh JSON-файла в H2 кэш.
     * <p>
     * Формат файла: массив массивов, каждый внутренний массив — нода.
     * Маппинг позиций:
     * <ul>
     *   <li>[1] — nodeNum (unsigned int как строка)</li>
     *   <li>[2] — longName</li>
     *   <li>[3] — shortName</li>
     *   <li>[4] — hwModel (числовой код Meshtastic HardwareModel)</li>
     *   <li>[5] — role (числовой код Meshtastic Config.DeviceConfig.Role)</li>
     *   <li>[12] — latitude (raw int, * 1e-7 для градусов)</li>
     *   <li>[13] — longitude (raw int, * 1e-7 для градусов)</li>
     *   <li>[16] — altitude (метры)</li>
     *   <li>[17] — lastHeard (ISO 8601 timestamp)</li>
     *   <li>[18] — batteryLevel</li>
     *   <li>[19] — voltage (строка)</li>
     * </ul>
     *
     * @return количество импортированных нод
     * @throws Exception при ошибках загрузки или парсинга
     */
    public int importFromOneMesh() throws Exception {
        log.info("Начало импорта нод из OneMesh: {}", ONEMESH_URL);

        JsonArray rows;
        try (var is = URI.create(ONEMESH_URL).toURL().openStream();
             var reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            rows = JsonParser.parseReader(reader).getAsJsonArray();
        }

        int imported = 0;
        if (dbConnection == null) return 0;

        try {
            dbConnection.setAutoCommit(false);
            for (JsonElement elem : rows) {
                try {
                    JsonArray row = elem.getAsJsonArray();
                    NodeData node = parseOneMeshRow(row);
                    if (node == null || !node.hasName()) continue;

                    cache.put(node.getNodeNum(), node);
                    bindNode(mergeStmt, node);
                    mergeStmt.addBatch();
                    imported++;

                    // Промежуточный flush каждые 1000 записей
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
            try { dbConnection.rollback(); dbConnection.setAutoCommit(true); } catch (SQLException ex) { /* ignored */ }
            throw e;
        }
        return imported;
    }

    /**
     * Парсит одну строку (массив) из OneMesh JSON в {@link NodeData}.
     *
     * @param row JSON-массив одной ноды
     * @return {@link NodeData} или {@code null} если строка невалидна
     */
    private static NodeData parseOneMeshRow(JsonArray row) {
        if (row.size() < 20) return null;

        // [1] nodeNum — unsigned int как строка
        long nodeNumLong = Long.parseUnsignedLong(row.get(1).getAsString());
        int nodeNum = (int) nodeNumLong;
        if (nodeNum == 0) return null;

        NodeData node = new NodeData(nodeNum);

        // [2] longName
        if (!row.get(2).isJsonNull()) {
            node.setLongName(row.get(2).getAsString());
        }

        // [3] shortName
        if (!row.get(3).isJsonNull()) {
            node.setShortName(row.get(3).getAsString());
        }

        // [4] hwModel — числовой код → имя enum Protobuf
        if (!row.get(4).isJsonNull()) {
            int hwCode = row.get(4).getAsInt();
            MeshProtos.HardwareModel hw = MeshProtos.HardwareModel.forNumber(hwCode);
            node.setHwModel(hw != null ? hw.name() : String.valueOf(hwCode));
        }

        // [5] role — числовой код → имя enum Protobuf
        if (!row.get(5).isJsonNull()) {
            int roleCode = row.get(5).getAsInt();
            ConfigProtos.Config.DeviceConfig.Role role =
                    ConfigProtos.Config.DeviceConfig.Role.forNumber(roleCode);
            node.setRole(role != null ? role.name() : String.valueOf(roleCode));
        }

        // [12] latitude (raw int * 1e-7)
        if (!row.get(12).isJsonNull()) {
            double lat = row.get(12).getAsLong() * 1e-7;
            if (lat != 0) node.setLatitude(lat);
        }

        // [13] longitude (raw int * 1e-7)
        if (!row.get(13).isJsonNull()) {
            double lon = row.get(13).getAsLong() * 1e-7;
            if (lon != 0) node.setLongitude(lon);
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
            node.setBatteryLevel(row.get(18).getAsInt());
        }

        // [19] voltage (строка → float)
        if (!row.get(19).isJsonNull()) {
            try {
                node.setVoltage(Float.parseFloat(row.get(19).getAsString()));
            } catch (NumberFormatException ignored) { }
        }

        return node;
    }

    // ═══════════════════════════════════════════════════════════
    //  Merge-логика
    // ═══════════════════════════════════════════════════════════

    /**
     * Переносит непустые поля из {@code src} в {@code dst}.
     * Нулевые/пустые/дефолтные поля в src не затирают имеющиеся в dst.
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
        if (src.getLatitude() != 0) dst.setLatitude(src.getLatitude());
        if (src.getLongitude() != 0) dst.setLongitude(src.getLongitude());
        if (src.getAltitude() != 0) dst.setAltitude(src.getAltitude());
        if (src.getSnr() != 0) dst.setSnr(src.getSnr());
        if (src.getLastHeard() != 0) dst.setLastHeard(src.getLastHeard());
        if (src.getBatteryLevel() != 0) dst.setBatteryLevel(src.getBatteryLevel());
        if (src.getVoltage() != 0) dst.setVoltage(src.getVoltage());
        if (src.getChannelUtilization() != 0) dst.setChannelUtilization(src.getChannelUtilization());
        if (src.getAirUtilTx() != 0) dst.setAirUtilTx(src.getAirUtilTx());
        if (src.getUptimeSeconds() != 0) dst.setUptimeSeconds(src.getUptimeSeconds());
        if (src.getTemperature() != 0) dst.setTemperature(src.getTemperature());
        if (src.getRelativeHumidity() != 0) dst.setRelativeHumidity(src.getRelativeHumidity());
        if (src.getBarometricPressure() != 0) dst.setBarometricPressure(src.getBarometricPressure());
        if (src.getHopsAway() != 0) dst.setHopsAway(src.getHopsAway());
    }

    // ═══════════════════════════════════════════════════════════
    //  Персистентность (H2)
    // ═══════════════════════════════════════════════════════════

    /**
     * No-op: данные читаются из БД по запросу через {@link #loadPage} и {@link #countNodesInDb}.
     * In-memory кэш заполняется лениво при вызовах {@link #update}.
     */
    public void load() {
        // No-op: данные читаются из H2 напрямую
    }

    /**
     * No-op: записи в H2 происходят немедленно. Метод сохранён для совместимости API.
     */
    public void save() {
        // No-op: H2 writes are immediate
    }

    /**
     * Корректно закрывает соединение с БД. Вызывается при завершении приложения.
     */
    public void close() {
        try {
            if (mergeStmt != null) mergeStmt.close();
            if (insertTelemetryStmt != null) insertTelemetryStmt.close();
        } catch (SQLException e) {
            log.error("Error closing node cache DB statements", e);
        }
    }

    /**
     * Сохраняет одну ноду в БД (MERGE INTO).
     * Ноды без имён (longName и shortName оба пусты) не сохраняются.
     */
    private synchronized void persistNode(int nodeNum) {
        NodeData node = cache.get(nodeNum);
        if (node == null || mergeStmt == null || !node.hasName()) return;
        try {
            bindNode(mergeStmt, node);
            mergeStmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to persist node {} to DB", nodeNum, e);
        }
    }

    /**
     * Сохраняет набор нод в БД в одной транзакции (batch MERGE).
     * Ноды без имён пропускаются.
     */
    private synchronized void persistAll(Set<Integer> nodeNums) {
        if (mergeStmt == null) return;
        try {
            dbConnection.setAutoCommit(false);
            for (int nodeNum : nodeNums) {
                NodeData node = cache.get(nodeNum);
                if (node != null && node.hasName()) {
                    bindNode(mergeStmt, node);
                    mergeStmt.addBatch();
                }
            }
            mergeStmt.executeBatch();
            dbConnection.commit();
            dbConnection.setAutoCommit(true);
            log.debug("Persisted {} nodes to DB in batch", nodeNums.size());
        } catch (SQLException e) {
            log.error("Failed to batch-persist nodes to DB", e);
            try { dbConnection.rollback(); dbConnection.setAutoCommit(true); } catch (SQLException ex) { /* ignored */ }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Утилиты JDBC
    // ═══════════════════════════════════════════════════════════

    /**
     * Привязывает поля NodeData к параметрам PreparedStatement.
     */
    private static void bindNode(PreparedStatement ps, NodeData n) throws SQLException {
        ps.setInt(1, n.getNodeNum());
        ps.setString(2, n.getLongName());
        ps.setString(3, n.getShortName());
        ps.setString(4, n.getNodeId());
        ps.setString(5, n.getRole());
        ps.setString(6, n.getHwModel());
        ps.setDouble(7, n.getLatitude());
        ps.setDouble(8, n.getLongitude());
        ps.setInt(9, n.getAltitude());
        ps.setFloat(10, n.getSnr());
        ps.setInt(11, n.getLastHeard());
        ps.setInt(12, n.getBatteryLevel());
        ps.setFloat(13, n.getVoltage());
        ps.setInt(14, n.getHopsAway());
    }

    /**
     * Читает NodeData из текущей строки ResultSet.
     */
    private static NodeData readNode(ResultSet rs) throws SQLException {
        NodeData node = new NodeData(rs.getInt("node_num"));
        node.setLongName(rs.getString("long_name"));
        node.setShortName(rs.getString("short_name"));
        String nodeId = rs.getString("node_id");
        if (nodeId != null) node.setNodeId(nodeId);
        node.setRole(rs.getString("role"));
        node.setHwModel(rs.getString("hw_model"));
        node.setLatitude(rs.getDouble("latitude"));
        node.setLongitude(rs.getDouble("longitude"));
        node.setAltitude(rs.getInt("altitude"));
        node.setSnr(rs.getFloat("snr"));
        node.setLastHeard(rs.getInt("last_heard"));
        node.setBatteryLevel(rs.getInt("battery_level"));
        node.setVoltage(rs.getFloat("voltage"));
        node.setHopsAway(rs.getInt("hops_away"));
        return node;
    }
}
