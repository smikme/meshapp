package com.meshtastic.client.service;

import com.meshtastic.client.model.MeshMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Персистентное хранилище сообщений в H2 (общая БД {@code ~/.meshapp/nodedb}).
 * <p>
 * Все сообщения каналов и DM сохраняются немедленно при получении/отправке.
 * FormChat загружает сообщения порциями по 50 (пагинация по {@code id}).
 * <p>
 * Типы чатов ({@code chat_type}):
 * <ul>
 *   <li>{@code "channel"} — канальные сообщения, {@code chat_key} = channelIndex</li>
 *   <li>{@code "dm"} — личные сообщения, {@code chat_key} = peerNodeNum</li>
 * </ul>
 */
public class MessageDbService {

    private static final Logger log = LoggerFactory.getLogger(MessageDbService.class);

    private static MessageDbService instance;

    private Connection dbConnection;
    private PreparedStatement insertStmt;
    private PreparedStatement updateStatusStmt;

    private MessageDbService() {
        initDb();
    }

    public static synchronized MessageDbService getInstance() {
        if (instance == null) {
            instance = new MessageDbService();
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
                    CREATE TABLE IF NOT EXISTS messages (
                        id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                        chat_type    VARCHAR(10) NOT NULL,
                        chat_key     INT NOT NULL,
                        from_num     INT NOT NULL,
                        to_num       INT NOT NULL,
                        channel_idx  INT NOT NULL,
                        text         CLOB,
                        timestamp    BIGINT NOT NULL,
                        outgoing     BOOLEAN NOT NULL,
                        packet_id    INT DEFAULT 0,
                        status       VARCHAR(20),
                        error_reason VARCHAR(100),
                        reply_id     INT DEFAULT 0,
                        reply_text   CLOB,
                        hop_start    INT DEFAULT 0,
                        hop_limit    INT DEFAULT 0,
                        sender_name  VARCHAR(100),
                        system_msg   BOOLEAN DEFAULT FALSE
                    )
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_msg_chat ON messages (chat_type, chat_key, id)
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_msg_packet ON messages (packet_id)
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chat_read_counts (
                        chat_type  VARCHAR(10) NOT NULL,
                        chat_key   INT NOT NULL,
                        read_count INT NOT NULL DEFAULT 0,
                        PRIMARY KEY (chat_type, chat_key)
                    )
                    """);
            }

            insertStmt = dbConnection.prepareStatement("""
                INSERT INTO messages (chat_type, chat_key, from_num, to_num, channel_idx,
                    text, timestamp, outgoing, packet_id, status, error_reason,
                    reply_id, reply_text, hop_start, hop_limit, sender_name, system_msg)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);

            updateStatusStmt = dbConnection.prepareStatement("""
                UPDATE messages SET status = ?, error_reason = ? WHERE packet_id = ? AND packet_id <> 0
                """);

            log.info("Message DB initialized (table 'messages' in nodedb)");
        } catch (Exception e) {
            log.error("Failed to initialize message DB", e);
        }
    }

    /**
     * Помечает все сообщения со статусом {@code SENDING} как {@code FAILED}
     * с причиной {@code STALE}. Вызывается один раз при старте приложения,
     * чтобы очистить «зависшие» сообщения от предыдущей сессии
     * (аварийное завершение, потеря питания и т.д.).
     */
    public synchronized void markStaleSendingAsFailed() {
        if (dbConnection == null) return;
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "UPDATE messages SET status = ?, error_reason = ? WHERE status = 'SENDING'")) {
            ps.setString(1, MeshMessage.DeliveryStatus.FAILED.name());
            ps.setString(2, "STALE");
            int updated = ps.executeUpdate();
            if (updated > 0) {
                log.info("Marked {} stale SENDING messages as FAILED on startup", updated);
            }
        } catch (SQLException e) {
            log.error("Failed to mark stale SENDING messages as FAILED", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Запись
    // ═══════════════════════════════════════════════════════════

    /**
     * Сохраняет сообщение в БД. Устанавливает {@code msg.setDbId()} после вставки.
     *
     * @param msg      сообщение для сохранения
     * @param chatType "channel" или "dm"
     * @param chatKey  channelIndex или peerNodeNum
     */
    public synchronized void save(MeshMessage msg, String chatType, int chatKey) {
        if (msg == null) return;
        if (insertStmt == null) {
            log.warn("Message DB not initialized — message dropped (chatType={}, chatKey={})", chatType, chatKey);
            return;
        }
        try {
            insertStmt.setString(1, chatType);
            insertStmt.setInt(2, chatKey);
            insertStmt.setInt(3, msg.getFromNum());
            insertStmt.setInt(4, msg.getToNum());
            insertStmt.setInt(5, msg.getChannelIndex());
            insertStmt.setString(6, msg.getText());
            insertStmt.setLong(7, msg.getTimestamp());
            insertStmt.setBoolean(8, msg.isOutgoing());
            insertStmt.setInt(9, msg.getPacketId());
            insertStmt.setString(10, msg.getStatus() != null ? msg.getStatus().name() : null);
            insertStmt.setString(11, msg.getErrorReason());
            insertStmt.setInt(12, msg.getReplyId());
            insertStmt.setString(13, msg.getReplyText());
            insertStmt.setInt(14, msg.getHopStart());
            insertStmt.setInt(15, msg.getHopLimit());
            insertStmt.setString(16, msg.getSenderName());
            insertStmt.setBoolean(17, msg.isSystemMessage());
            insertStmt.executeUpdate();

            try (ResultSet keys = insertStmt.getGeneratedKeys()) {
                if (keys.next()) {
                    msg.setDbId(keys.getLong(1));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to save message to DB", e);
        }
    }

    /**
     * Обновляет статус доставки сообщения по packetId.
     */
    public synchronized void updateStatus(int packetId, MeshMessage.DeliveryStatus status, String errorReason) {
        if (packetId == 0) return;
        if (updateStatusStmt == null) {
            log.warn("Message DB not initialized — status update dropped (packetId={})", packetId);
            return;
        }
        try {
            updateStatusStmt.setString(1, status != null ? status.name() : null);
            updateStatusStmt.setString(2, errorReason);
            updateStatusStmt.setInt(3, packetId);
            updateStatusStmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update message status for packetId {}", packetId, e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Чтение — пагинация
    // ═══════════════════════════════════════════════════════════

    /**
     * Загружает последние N сообщений чата (в хронологическом порядке).
     *
     * @param chatType "channel" или "dm"
     * @param chatKey  channelIndex или peerNodeNum
     * @param limit    максимальное количество
     * @return список сообщений (старые → новые)
     */
    public List<MeshMessage> loadLast(String chatType, int chatKey, int limit) {
        List<MeshMessage> result = new ArrayList<>();
        if (dbConnection == null) return result;
        String sql = """
            SELECT * FROM (
                SELECT * FROM messages WHERE chat_type = ? AND chat_key = ?
                ORDER BY id DESC LIMIT ?
            ) ORDER BY id ASC
            """;
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, chatType);
            ps.setInt(2, chatKey);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readMessage(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to loadLast({}, {}, {})", chatType, chatKey, limit, e);
        }
        return result;
    }

    /**
     * Загружает N сообщений ДО указанного id (для скролла вверх).
     * Возвращает в хронологическом порядке (старые → новые).
     *
     * @param chatType   "channel" или "dm"
     * @param chatKey    channelIndex или peerNodeNum
     * @param beforeDbId загружать сообщения с id &lt; beforeDbId
     * @param limit      максимальное количество
     * @return список сообщений (старые → новые)
     */
    public List<MeshMessage> loadBefore(String chatType, int chatKey, long beforeDbId, int limit) {
        List<MeshMessage> result = new ArrayList<>();
        if (dbConnection == null) return result;
        String sql = """
            SELECT * FROM (
                SELECT * FROM messages WHERE chat_type = ? AND chat_key = ? AND id < ?
                ORDER BY id DESC LIMIT ?
            ) ORDER BY id ASC
            """;
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, chatType);
            ps.setInt(2, chatKey);
            ps.setLong(3, beforeDbId);
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readMessage(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to loadBefore({}, {}, {}, {})", chatType, chatKey, beforeDbId, limit, e);
        }
        return result;
    }

    /**
     * Загружает новые сообщения ПОСЛЕ указанного id (для real-time обновления).
     *
     * @param chatType  "channel" или "dm"
     * @param chatKey   channelIndex или peerNodeNum
     * @param afterDbId загружать сообщения с id > afterDbId
     * @return список новых сообщений (хронологический порядок)
     */
    public List<MeshMessage> loadAfter(String chatType, int chatKey, long afterDbId) {
        List<MeshMessage> result = new ArrayList<>();
        if (dbConnection == null) return result;
        String sql = "SELECT * FROM messages WHERE chat_type = ? AND chat_key = ? AND id > ? ORDER BY id ASC";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, chatType);
            ps.setInt(2, chatKey);
            ps.setLong(3, afterDbId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readMessage(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to loadAfter({}, {}, {})", chatType, chatKey, afterDbId, e);
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    //  Чтение — поиск и метаданные
    // ═══════════════════════════════════════════════════════════

    /**
     * Находит сообщение по packetId (для reply_text).
     */
    public MeshMessage findByPacketId(int packetId) {
        if (dbConnection == null || packetId == 0) return null;
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT * FROM messages WHERE packet_id = ? LIMIT 1")) {
            ps.setInt(1, packetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readMessage(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to find message by packetId {}", packetId, e);
        }
        return null;
    }

    /**
     * Возвращает список уникальных DM-пиров (chat_key) из БД.
     */
    public List<Integer> getDistinctDmPeers() {
        List<Integer> peers = new ArrayList<>();
        if (dbConnection == null) return peers;
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT DISTINCT chat_key FROM messages WHERE chat_type = 'dm'")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    peers.add(rs.getInt(1));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get distinct DM peers", e);
        }
        return peers;
    }

    /**
     * Возвращает последнее сообщение для каждого chat_key данного типа.
     * Ключ — chat_key, значение — последнее MeshMessage.
     */
    public Map<Integer, MeshMessage> getLastMessagePerChat(String chatType) {
        Map<Integer, MeshMessage> result = new LinkedHashMap<>();
        if (dbConnection == null) return result;
        // Подзапрос: максимальный id для каждого chat_key
        String sql = """
            SELECT m.* FROM messages m
            INNER JOIN (
                SELECT chat_key, MAX(id) AS max_id
                FROM messages WHERE chat_type = ?
                GROUP BY chat_key
            ) sub ON m.id = sub.max_id
            ORDER BY m.timestamp DESC
            """;
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, chatType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MeshMessage msg = readMessage(rs);
                    result.put(rs.getInt("chat_key"), msg);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get last messages per chat ({})", chatType, e);
        }
        return result;
    }

    /**
     * Возвращает количество сообщений в чате.
     */
    public int getMessageCount(String chatType, int chatKey) {
        if (dbConnection == null) return 0;
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT COUNT(*) FROM messages WHERE chat_type = ? AND chat_key = ?")) {
            ps.setString(1, chatType);
            ps.setInt(2, chatKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("Failed to count messages ({}, {})", chatType, chatKey, e);
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════════
    //  Удаление
    // ═══════════════════════════════════════════════════════════

    /**
     * Удаляет все сообщения и счётчик прочитанных для указанного чата.
     *
     * @param chatType "channel" или "dm"
     * @param chatKey  channelIndex или peerNodeNum
     */
    public synchronized void deleteChat(String chatType, int chatKey) {
        if (dbConnection == null) return;
        try (PreparedStatement ps1 = dbConnection.prepareStatement(
                     "DELETE FROM messages WHERE chat_type = ? AND chat_key = ?");
             PreparedStatement ps2 = dbConnection.prepareStatement(
                     "DELETE FROM chat_read_counts WHERE chat_type = ? AND chat_key = ?")) {
            ps1.setString(1, chatType);
            ps1.setInt(2, chatKey);
            ps1.executeUpdate();

            ps2.setString(1, chatType);
            ps2.setInt(2, chatKey);
            ps2.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete chat ({}, {})", chatType, chatKey, e);
        }
    }

    /**
     * Удаляет одно сообщение по его id в БД.
     */
    public synchronized void deleteMessage(long dbId) {
        if (dbConnection == null || dbId <= 0) return;
        try (PreparedStatement ps = dbConnection.prepareStatement("DELETE FROM messages WHERE id = ?")) {
            ps.setLong(1, dbId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete message id={}", dbId, e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Трекинг прочитанных сообщений
    // ═══════════════════════════════════════════════════════════

    /**
     * Сохранить количество прочитанных сообщений для чата.
     */
    public void saveReadCount(String chatType, int chatKey, int readCount) {
        if (dbConnection == null) return;
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "MERGE INTO chat_read_counts (chat_type, chat_key, read_count) VALUES (?, ?, ?)")) {
            ps.setString(1, chatType);
            ps.setInt(2, chatKey);
            ps.setInt(3, readCount);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save read count ({}, {})", chatType, chatKey, e);
        }
    }

    /**
     * Загрузить все счётчики прочитанных сообщений.
     * @return Map с ключами "ch:KEY" / "dm:KEY" → readCount
     */
    public Map<String, Integer> loadAllReadCounts() {
        Map<String, Integer> result = new HashMap<>();
        if (dbConnection == null) return result;
        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT chat_type, chat_key, read_count FROM chat_read_counts")) {
            while (rs.next()) {
                String type = rs.getString("chat_type");
                int key = rs.getInt("chat_key");
                int count = rs.getInt("read_count");
                String mapKey = ("channel".equals(type) ? "ch:" : "dm:") + key;
                result.put(mapKey, count);
            }
        } catch (SQLException e) {
            log.error("Failed to load read counts", e);
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    //  Миграция JSON → H2
    // ═══════════════════════════════════════════════════════════

    /**
     * Импортирует сообщения из JSON-файлов (MessageHistoryService формат) в H2.
     * Вызывается один раз при первом запуске, если {@code ~/.meshapp/history/} существует.
     */
    public void migrateFromJsonHistory() {
        Path historyDir = Path.of(System.getProperty("user.home"), ".meshapp", "history");
        if (!Files.isDirectory(historyDir)) return;

        // Проверяем, есть ли уже данные
        if (getMessageCount("channel", 0) > 0 || !getDistinctDmPeers().isEmpty()) {
            log.info("Messages already exist in DB, skipping JSON migration");
            return;
        }

        log.info("Migrating message history from JSON to H2...");
        try {
            var gson = new com.google.gson.Gson();
            var msgListType = new com.google.gson.reflect.TypeToken<List<JsonStoredMessage>>() {}.getType();
            int total = 0;

            dbConnection.setAutoCommit(false);

            try (var files = Files.list(historyDir)) {
                for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    String name = file.getFileName().toString();
                    try (var reader = Files.newBufferedReader(file)) {
                        List<JsonStoredMessage> stored = gson.fromJson(reader, msgListType);
                        if (stored == null || stored.isEmpty()) continue;

                        String chatType;
                        int chatKey;
                        if (name.startsWith("channel_")) {
                            chatType = "channel";
                            chatKey = Integer.parseInt(name.replace("channel_", "").replace(".json", ""));
                        } else if (name.startsWith("dm_")) {
                            chatType = "dm";
                            chatKey = (int) Long.parseLong(name.replace("dm_", "").replace(".json", ""), 16);
                        } else {
                            continue;
                        }

                        for (JsonStoredMessage s : stored) {
                            MeshMessage msg = new MeshMessage(s.fromNum, s.toNum, s.channelIndex,
                                    s.text, s.timestamp, s.outgoing);
                            msg.setPacketId(s.packetId);
                            if (s.status != null) {
                                if (s.status == MeshMessage.DeliveryStatus.SENDING) {
                                    msg.setStatus(MeshMessage.DeliveryStatus.FAILED);
                                    msg.setErrorReason("STALE");
                                } else {
                                    msg.setStatus(s.status);
                                    msg.setErrorReason(s.errorReason);
                                }
                            }
                            msg.setReplyId(s.replyId);
                            msg.setReplyText(s.replyText);
                            msg.setHopStart(s.hopStart);
                            msg.setHopLimit(s.hopLimit);
                            msg.setSenderName(s.senderName);

                            insertStmt.setString(1, chatType);
                            insertStmt.setInt(2, chatKey);
                            insertStmt.setInt(3, msg.getFromNum());
                            insertStmt.setInt(4, msg.getToNum());
                            insertStmt.setInt(5, msg.getChannelIndex());
                            insertStmt.setString(6, msg.getText());
                            insertStmt.setLong(7, msg.getTimestamp());
                            insertStmt.setBoolean(8, msg.isOutgoing());
                            insertStmt.setInt(9, msg.getPacketId());
                            insertStmt.setString(10, msg.getStatus() != null ? msg.getStatus().name() : null);
                            insertStmt.setString(11, msg.getErrorReason());
                            insertStmt.setInt(12, msg.getReplyId());
                            insertStmt.setString(13, msg.getReplyText());
                            insertStmt.setInt(14, msg.getHopStart());
                            insertStmt.setInt(15, msg.getHopLimit());
                            insertStmt.setString(16, msg.getSenderName());
                            insertStmt.setBoolean(17, msg.isSystemMessage());
                            insertStmt.addBatch();
                            total++;

                            if (total % 500 == 0) {
                                insertStmt.executeBatch();
                            }
                        }

                        log.info("Migrated {} messages from {}", stored.size(), name);
                    } catch (Exception e) {
                        log.warn("Failed to migrate file: {}", name, e);
                    }
                }
            }

            insertStmt.executeBatch();
            dbConnection.commit();
            dbConnection.setAutoCommit(true);

            // Переименовать history → history.bak
            Path backup = historyDir.resolveSibling("history.bak");
            try {
                Files.move(historyDir, backup);
                log.info("Renamed history/ → history.bak/");
            } catch (Exception e) {
                log.warn("Failed to rename history dir", e);
            }

            log.info("JSON → H2 migration complete: {} messages imported", total);
        } catch (Exception e) {
            log.error("Failed to migrate JSON history to H2", e);
            try { dbConnection.rollback(); dbConnection.setAutoCommit(true); } catch (SQLException ex) { /* ignored */ }
        }
    }

    /** Структура JSON-файлов из MessageHistoryService */
    @SuppressWarnings("unused")
    private static class JsonStoredMessage {
        int fromNum;
        int toNum;
        int channelIndex;
        String text;
        long timestamp;
        boolean outgoing;
        int packetId;
        MeshMessage.DeliveryStatus status;
        String errorReason;
        int replyId;
        String replyText;
        int hopStart;
        int hopLimit;
        String senderName;
    }

    // ═══════════════════════════════════════════════════════════
    //  Утилиты
    // ═══════════════════════════════════════════════════════════

    private static MeshMessage readMessage(ResultSet rs) throws SQLException {
        MeshMessage msg = new MeshMessage(
                rs.getInt("from_num"),
                rs.getInt("to_num"),
                rs.getInt("channel_idx"),
                rs.getString("text"),
                rs.getLong("timestamp"),
                rs.getBoolean("outgoing")
        );
        msg.setDbId(rs.getLong("id"));
        msg.setPacketId(rs.getInt("packet_id"));

        String statusStr = rs.getString("status");
        if (statusStr != null) {
            try {
                msg.setStatus(MeshMessage.DeliveryStatus.valueOf(statusStr));
            } catch (IllegalArgumentException ignored) {}
        }

        msg.setErrorReason(rs.getString("error_reason"));
        msg.setReplyId(rs.getInt("reply_id"));
        msg.setReplyText(rs.getString("reply_text"));
        msg.setHopStart(rs.getInt("hop_start"));
        msg.setHopLimit(rs.getInt("hop_limit"));
        msg.setSenderName(rs.getString("sender_name"));
        msg.setSystemMessage(rs.getBoolean("system_msg"));
        return msg;
    }

    /**
     * Закрывает соединение с БД.
     */
    public void close() {
        try {
            if (insertStmt != null) insertStmt.close();
            if (updateStatusStmt != null) updateStatusStmt.close();
        } catch (SQLException e) {
            log.error("Error closing message DB statements", e);
        }
    }
}
