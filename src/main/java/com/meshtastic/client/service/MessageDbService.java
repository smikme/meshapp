package com.meshtastic.client.service;

import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
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
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MessageDbService {

    private static final Logger log = LoggerFactory.getLogger(MessageDbService.class);

    private static MessageDbService instance;

    private Connection dbConnection;
    private PreparedStatement insertStmt;
    private PreparedStatement updateStatusStmt;
    private PreparedStatement insertReactionStmt;
    private PreparedStatement updateReactionStatusStmt;

    private MessageDbService() {
        initDb();
    }

    public static synchronized MessageDbService getInstance() {
        if (instance == null) {
            instance = new MessageDbService();
        }
        return instance;
    }

    public static synchronized MessageDbService getIfInitialized() {
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
            closeStatements();
            dbConnection = DatabaseProvider.getConnection();

            try (Statement stmt = dbConnection.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id             BIGINT AUTO_INCREMENT PRIMARY KEY,
                        chat_type      VARCHAR(10) NOT NULL,
                        chat_key       VARCHAR(20) NOT NULL,
                        from_node_id   VARCHAR(20) NOT NULL,
                        to_node_id     VARCHAR(20) NOT NULL,
                        channel_idx    INT NOT NULL,
                        text           CLOB,
                        timestamp      BIGINT NOT NULL,
                        outgoing       BOOLEAN NOT NULL,
                        packet_id      INT DEFAULT 0,
                        status         VARCHAR(20),
                        error_reason   VARCHAR(100),
                        reply_id       INT DEFAULT 0,
                        reply_text     CLOB,
                        hop_start      INT DEFAULT 0,
                        hop_limit      INT DEFAULT 0,
                        sender_name    VARCHAR(100),
                        via_mqtt       BOOLEAN DEFAULT FALSE,
                        system_msg     BOOLEAN DEFAULT FALSE,
                        rx_rssi        INT DEFAULT 0,
                        rx_snr         REAL DEFAULT 0,
                        owner_node_id  VARCHAR(20) NOT NULL DEFAULT ''
                    )
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_msg_chat ON messages (owner_node_id, chat_type, chat_key, id)
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_msg_packet ON messages (packet_id)
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_msg_chat_packet
                    ON messages (owner_node_id, chat_type, chat_key, packet_id, id)
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS message_reactions (
                        id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
                        owner_node_id      VARCHAR(20) NOT NULL DEFAULT '',
                        chat_type          VARCHAR(10) NOT NULL,
                        chat_key           VARCHAR(20) NOT NULL,
                        target_packet_id   INT NOT NULL,
                        reaction_packet_id INT DEFAULT 0,
                        from_node_id       VARCHAR(20) NOT NULL,
                        emoji              VARCHAR(16) NOT NULL,
                        timestamp          BIGINT NOT NULL,
                        outgoing           BOOLEAN NOT NULL,
                        status             VARCHAR(20),
                        error_reason       VARCHAR(100),
                        sender_name        VARCHAR(100)
                    )
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_reaction_chat_target
                    ON message_reactions (owner_node_id, chat_type, chat_key, target_packet_id, id)
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_reaction_packet ON message_reactions (reaction_packet_id)
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chat_read_counts (
                        owner_node_id VARCHAR(20) NOT NULL DEFAULT '',
                        chat_type     VARCHAR(10) NOT NULL,
                        chat_key      VARCHAR(20) NOT NULL,
                        read_count    INT NOT NULL DEFAULT 0,
                        PRIMARY KEY (owner_node_id, chat_type, chat_key)
                    )
                    """);
            }

            insertStmt = dbConnection.prepareStatement("""
                INSERT INTO messages (chat_type, chat_key, from_node_id, to_node_id, channel_idx,
                    text, timestamp, outgoing, packet_id, status, error_reason,
                    reply_id, reply_text, hop_start, hop_limit, sender_name, system_msg,
                    rx_rssi, rx_snr, via_mqtt, owner_node_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);

            updateStatusStmt = dbConnection.prepareStatement("""
                UPDATE messages SET status = ?, error_reason = ? WHERE packet_id = ? AND packet_id <> 0
                """);

            insertReactionStmt = dbConnection.prepareStatement("""
                INSERT INTO message_reactions (
                    owner_node_id, chat_type, chat_key, target_packet_id, reaction_packet_id,
                    from_node_id, emoji, timestamp, outgoing, status, error_reason, sender_name
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);

            updateReactionStatusStmt = dbConnection.prepareStatement("""
                UPDATE message_reactions
                SET status = ?, error_reason = ?
                WHERE reaction_packet_id = ? AND reaction_packet_id <> 0
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
        if (dbConnection == null) { return; }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "UPDATE messages SET status = ?, error_reason = ? WHERE status = 'SENDING'")) {
            ps.setString(1, MeshMessage.DeliveryStatus.FAILED.name());
            ps.setString(2, "STALE");
            int updatedMessages = ps.executeUpdate();

            int updatedReactions = 0;
            try (PreparedStatement reactionPs = dbConnection.prepareStatement(
                    "UPDATE message_reactions SET status = ?, error_reason = ? WHERE status = 'SENDING'")) {
                reactionPs.setString(1, MeshMessage.DeliveryStatus.FAILED.name());
                reactionPs.setString(2, "STALE");
                updatedReactions = reactionPs.executeUpdate();
            }

            if (updatedMessages > 0 || updatedReactions > 0) {
                log.info("Marked {} stale messages and {} stale reactions as FAILED on startup",
                        updatedMessages, updatedReactions);
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
     * @param msg         сообщение для сохранения
     * @param chatType    "channel" или "dm"
     * @param chatKey     channelIndex (как строка) или peerNodeId
     * @param ownerNodeId nodeId устройства-владельца (например, "!9e755af0")
     */
    public synchronized void save(MeshMessage msg, String chatType, String chatKey, String ownerNodeId) {
        if (msg == null) { return; }
        if (insertStmt == null) {
            log.warn("Message DB not initialized — message dropped (chatType={}, chatKey={})", chatType, chatKey);
            return;
        }
        String normalizedOwnerNodeId = ownerNodeId != null ? ownerNodeId : "";
        try {
            Long existingDbId = findExistingMessageDbId(
                    msg.getPacketId(),
                    chatType,
                    chatKey,
                    normalizedOwnerNodeId,
                    msg.getChannelIndex(),
                    msg.isOutgoing(),
                    msg.getFromNodeId(),
                    msg.getToNodeId());
            if (existingDbId != null) {
                msg.setDbId(existingDbId);
                updateExistingMessageMetadata(existingDbId, msg);
                return;
            }

            insertStmt.setString(1, chatType);
            insertStmt.setString(2, chatKey);
            insertStmt.setString(3, msg.getFromNodeId());
            insertStmt.setString(4, msg.getToNodeId());
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
            insertStmt.setInt(18, msg.getRxRssi());
            insertStmt.setFloat(19, msg.getRxSnr());
            insertStmt.setBoolean(20, msg.isViaMqtt());
            insertStmt.setString(21, normalizedOwnerNodeId);
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

    private Long findExistingMessageDbId(int packetId,
                                         String chatType,
                                         String chatKey,
                                         String ownerNodeId,
                                         int channelIndex,
                                         boolean outgoing,
                                         String fromNodeId,
                                         String toNodeId) throws SQLException {
        if (dbConnection == null || packetId == 0) {
            return null;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT id FROM messages
                WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ?
                  AND packet_id = ? AND packet_id <> 0
                  AND channel_idx = ? AND outgoing = ?
                  AND ((from_node_id = ?) OR (from_node_id IS NULL AND ? IS NULL))
                  AND ((to_node_id = ?) OR (to_node_id IS NULL AND ? IS NULL))
                ORDER BY id ASC LIMIT 1
                """)) {
            ps.setString(1, ownerNodeId);
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            ps.setInt(4, packetId);
            ps.setInt(5, channelIndex);
            ps.setBoolean(6, outgoing);
            ps.setString(7, fromNodeId);
            ps.setString(8, fromNodeId);
            ps.setString(9, toNodeId);
            ps.setString(10, toNodeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("id") : null;
            }
        }
    }

    private void updateExistingMessageMetadata(long dbId, MeshMessage msg) throws SQLException {
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                UPDATE messages
                SET status = COALESCE(?, status),
                    error_reason = COALESCE(?, error_reason),
                    reply_id = CASE WHEN ? <> 0 THEN ? ELSE reply_id END,
                    reply_text = CASE
                        WHEN ? IS NOT NULL AND LENGTH(?) > 0
                             AND (reply_text IS NULL OR LENGTH(reply_text) = 0)
                        THEN ?
                        ELSE reply_text
                    END,
                    hop_start = CASE
                        WHEN ? = FALSE AND ? <> 0 THEN ?
                        WHEN ? = TRUE AND hop_start = 0 AND ? <> 0 THEN ?
                        ELSE hop_start
                    END,
                    hop_limit = CASE
                        WHEN ? = FALSE AND ? <> 0 THEN ?
                        WHEN ? = TRUE AND hop_limit = 0 AND ? <> 0 THEN ?
                        ELSE hop_limit
                    END,
                    sender_name = COALESCE(?, sender_name),
                    rx_rssi = CASE
                        WHEN ? = FALSE AND ? <> 0 THEN ?
                        WHEN ? = TRUE AND rx_rssi = 0 AND ? <> 0 THEN ?
                        ELSE rx_rssi
                    END,
                    rx_snr = CASE
                        WHEN ? = FALSE AND ? <> 0 THEN ?
                        WHEN ? = TRUE AND rx_snr = 0 AND ? <> 0 THEN ?
                        ELSE rx_snr
                    END,
                    via_mqtt = CASE WHEN ? THEN via_mqtt ELSE FALSE END
                WHERE id = ?
                """)) {
            ps.setString(1, msg.getStatus() != null ? msg.getStatus().name() : null);
            ps.setString(2, msg.getErrorReason());
            ps.setInt(3, msg.getReplyId());
            ps.setInt(4, msg.getReplyId());
            ps.setString(5, msg.getReplyText());
            ps.setString(6, msg.getReplyText());
            ps.setString(7, msg.getReplyText());
            ps.setBoolean(8, msg.isViaMqtt());
            ps.setInt(9, msg.getHopStart());
            ps.setInt(10, msg.getHopStart());
            ps.setBoolean(11, msg.isViaMqtt());
            ps.setInt(12, msg.getHopStart());
            ps.setInt(13, msg.getHopStart());
            ps.setBoolean(14, msg.isViaMqtt());
            ps.setInt(15, msg.getHopLimit());
            ps.setInt(16, msg.getHopLimit());
            ps.setBoolean(17, msg.isViaMqtt());
            ps.setInt(18, msg.getHopLimit());
            ps.setInt(19, msg.getHopLimit());
            ps.setString(20, msg.getSenderName());
            ps.setBoolean(21, msg.isViaMqtt());
            ps.setInt(22, msg.getRxRssi());
            ps.setInt(23, msg.getRxRssi());
            ps.setBoolean(24, msg.isViaMqtt());
            ps.setInt(25, msg.getRxRssi());
            ps.setInt(26, msg.getRxRssi());
            ps.setBoolean(27, msg.isViaMqtt());
            ps.setFloat(28, msg.getRxSnr());
            ps.setFloat(29, msg.getRxSnr());
            ps.setBoolean(30, msg.isViaMqtt());
            ps.setFloat(31, msg.getRxSnr());
            ps.setFloat(32, msg.getRxSnr());
            ps.setBoolean(33, msg.isViaMqtt());
            ps.setLong(34, dbId);
            ps.executeUpdate();
        }
    }

    /**
     * Сохраняет реакцию на сообщение в отдельной таблице, чтобы она не влияла
     * на preview чатов, unread-счётчики и обычную историю сообщений.
     */
    public synchronized boolean saveReaction(MessageReaction reaction,
                                             String chatType,
                                             String chatKey,
                                             String ownerNodeId) {
        if (reaction == null) { return false; }
        if (insertReactionStmt == null) {
            log.warn("Reaction DB not initialized — reaction dropped (chatType={}, chatKey={})", chatType, chatKey);
            return false;
        }
        try {
            insertReactionStmt.setString(1, ownerNodeId != null ? ownerNodeId : "");
            insertReactionStmt.setString(2, chatType);
            insertReactionStmt.setString(3, chatKey);
            insertReactionStmt.setInt(4, reaction.getTargetPacketId());
            insertReactionStmt.setInt(5, reaction.getPacketId());
            insertReactionStmt.setString(6, reaction.getFromNodeId());
            insertReactionStmt.setString(7, reaction.getEmoji());
            insertReactionStmt.setLong(8, reaction.getTimestamp());
            insertReactionStmt.setBoolean(9, reaction.isOutgoing());
            insertReactionStmt.setString(10, reaction.getStatus() != null ? reaction.getStatus().name() : null);
            insertReactionStmt.setString(11, reaction.getErrorReason());
            insertReactionStmt.setString(12, reaction.getSenderName());
            insertReactionStmt.executeUpdate();

            try (ResultSet keys = insertReactionStmt.getGeneratedKeys()) {
                if (keys.next()) {
                    reaction.setDbId(keys.getLong(1));
                }
            }
            return true;
        } catch (SQLException e) {
            log.error("Failed to save reaction to DB", e);
            return false;
        }
    }

    /**
     * Обновляет статус доставки сообщения по packetId.
     */
    public synchronized void updateStatus(int packetId, MeshMessage.DeliveryStatus status, String errorReason) {
        if (packetId == 0) { return; }
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

    /**
     * Переводит существующее сообщение в новую попытку отправки:
     * обновляет packetId, статус и причину ошибки.
     *
     * @return {@code true}, если обновлена хотя бы одна запись
     */
    public synchronized boolean updateMessageForRetry(long dbId,
                                                      int previousPacketId,
                                                      int newPacketId,
                                                      MeshMessage.DeliveryStatus status,
                                                      String errorReason) {
        if (dbConnection == null || newPacketId == 0) {
            return false;
        }

        boolean lookupByDbId = dbId > 0;
        if (!lookupByDbId && previousPacketId == 0) {
            return false;
        }

        String sql = lookupByDbId
                ? "UPDATE messages SET packet_id = ?, status = ?, error_reason = ? WHERE id = ?"
                : "UPDATE messages SET packet_id = ?, status = ?, error_reason = ? WHERE packet_id = ? AND packet_id <> 0";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setInt(1, newPacketId);
            ps.setString(2, status != null ? status.name() : null);
            ps.setString(3, errorReason);
            if (lookupByDbId) {
                ps.setLong(4, dbId);
            } else {
                ps.setInt(4, previousPacketId);
            }
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to update message retry state (dbId={}, previousPacketId={}, newPacketId={})",
                    dbId, previousPacketId, newPacketId, e);
            return false;
        }
    }

    /**
     * Обновляет статус доставки реакции по packetId.
     *
     * @return {@code true}, если найдена и обновлена хотя бы одна запись
     */
    public synchronized boolean updateReactionStatus(int packetId,
                                                     MeshMessage.DeliveryStatus status,
                                                     String errorReason) {
        if (packetId == 0) { return false; }
        if (updateReactionStatusStmt == null) {
            log.warn("Reaction DB not initialized — status update dropped (packetId={})", packetId);
            return false;
        }
        try {
            updateReactionStatusStmt.setString(1, status != null ? status.name() : null);
            updateReactionStatusStmt.setString(2, errorReason);
            updateReactionStatusStmt.setInt(3, packetId);
            return updateReactionStatusStmt.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to update reaction status for packetId {}", packetId, e);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Чтение — пагинация
    // ═══════════════════════════════════════════════════════════

    /**
     * Загружает последние N сообщений чата (в хронологическом порядке).
     *
     * @param chatType    "channel" или "dm"
     * @param chatKey     channelIndex (как строка) или peerNodeId
     * @param limit       максимальное количество
     * @param ownerNodeId nodeId устройства-владельца
     * @return список сообщений (старые → новые)
     */
    public List<MeshMessage> loadLast(String chatType, String chatKey, int limit, String ownerNodeId) {
        List<MeshMessage> result = new ArrayList<>();
        if (dbConnection == null) { return result; }
        String sql = """
            SELECT * FROM (
                SELECT * FROM messages WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ?
                ORDER BY id DESC LIMIT ?
            ) ORDER BY id ASC
            """;
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            ps.setInt(4, limit);
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
     *
     * @param chatType    "channel" или "dm"
     * @param chatKey     channelIndex (как строка) или peerNodeId
     * @param beforeDbId  загружать сообщения с id &lt; beforeDbId
     * @param limit       максимальное количество
     * @param ownerNodeId nodeId устройства-владельца
     * @return список сообщений (старые → новые)
     */
    public List<MeshMessage> loadBefore(String chatType, String chatKey, long beforeDbId, int limit, String ownerNodeId) {
        List<MeshMessage> result = new ArrayList<>();
        if (dbConnection == null) { return result; }
        String sql = """
            SELECT * FROM (
                SELECT * FROM messages WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ? AND id < ?
                ORDER BY id DESC LIMIT ?
            ) ORDER BY id ASC
            """;
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            ps.setLong(4, beforeDbId);
            ps.setInt(5, limit);
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
     * @param chatType    "channel" или "dm"
     * @param chatKey     channelIndex (как строка) или peerNodeId
     * @param afterDbId   загружать сообщения с id > afterDbId
     * @param ownerNodeId nodeId устройства-владельца
     * @return список новых сообщений (хронологический порядок)
     */
    public List<MeshMessage> loadAfter(String chatType, String chatKey, long afterDbId, String ownerNodeId) {
        return loadAfter(chatType, chatKey, afterDbId, 0, ownerNodeId);
    }

    /**
     * Загружает новые сообщения ПОСЛЕ указанного id (для real-time обновления и
     * постраничной навигации вниз).
     *
     * @param chatType    "channel" или "dm"
     * @param chatKey     channelIndex (как строка) или peerNodeId
     * @param afterDbId   загружать сообщения с id > afterDbId
     * @param limit       максимальное количество; {@code <= 0} означает без лимита
     * @param ownerNodeId nodeId устройства-владельца
     * @return список новых сообщений (хронологический порядок)
     */
    public List<MeshMessage> loadAfter(String chatType,
                                       String chatKey,
                                       long afterDbId,
                                       int limit,
                                       String ownerNodeId) {
        List<MeshMessage> result = new ArrayList<>();
        if (dbConnection == null) { return result; }
        String sql = limit > 0
                ? "SELECT * FROM messages WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ? AND id > ? ORDER BY id ASC LIMIT ?"
                : "SELECT * FROM messages WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ? AND id > ? ORDER BY id ASC";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            ps.setLong(4, afterDbId);
            if (limit > 0) {
                ps.setInt(5, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readMessage(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to loadAfter({}, {}, {}, {})", chatType, chatKey, afterDbId, limit, e);
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    //  Чтение — поиск и метаданные
    // ═══════════════════════════════════════════════════════════

    /**
     * Загружает последние системные сообщения, начинающиеся с указанного префикса.
     *
     * @param textPrefix  префикс текста системного сообщения
     * @param limit       максимальное количество
     * @param ownerNodeId nodeId устройства-владельца
     * @return список сообщений (новые → старые)
     */
    public List<MeshMessage> loadRecentSystemMessagesByPrefix(String textPrefix, int limit, String ownerNodeId) {
        List<MeshMessage> result = new ArrayList<>();
        if (dbConnection == null || textPrefix == null || textPrefix.isBlank() || limit <= 0) {
            return result;
        }
        String sql = """
            SELECT * FROM messages
            WHERE owner_node_id = ?
              AND system_msg = TRUE
              AND CAST(text AS VARCHAR) LIKE ?
            ORDER BY id DESC
            LIMIT ?
            """;
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, textPrefix + "%");
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readMessage(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load recent system messages by prefix (limit={})", limit, e);
        }
        return result;
    }

    /**
     * Находит сообщение по packetId (для reply_text).
     */
    public MeshMessage findByPacketId(int packetId) {
        if (dbConnection == null || packetId == 0) { return null; }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT * FROM messages WHERE packet_id = ? LIMIT 1")) {
            ps.setInt(1, packetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { return readMessage(rs); }
            }
        } catch (SQLException e) {
            log.error("Failed to find message by packetId {}", packetId, e);
        }
        return null;
    }

    /**
     * Находит сообщение по packetId только внутри конкретного owner/chat scope.
     */
    public MeshMessage findByPacketId(int packetId, String chatType, String chatKey, String ownerNodeId) {
        if (dbConnection == null || packetId == 0) { return null; }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT * FROM messages
                WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ?
                  AND packet_id = ? AND packet_id <> 0
                ORDER BY id ASC LIMIT 1
                """)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            ps.setInt(4, packetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { return readMessage(rs); }
            }
        } catch (SQLException e) {
            log.error("Failed to find message by packetId {} in scope ({}, {}, {})",
                    packetId, ownerNodeId, chatType, chatKey, e);
        }
        return null;
    }

    /**
     * Восстанавливает текст цитаты для reply-сообщений, если оригинал уже есть
     * в том же owner/chat scope. Найденные quote-тексты сразу сохраняются в БД.
     *
     * @return количество сообщений, для которых удалось заполнить replyText
     */
    public synchronized int hydrateReplyTexts(List<MeshMessage> messages,
                                              String chatType,
                                              String chatKey,
                                              String ownerNodeId) {
        if (messages == null || messages.isEmpty() || dbConnection == null) {
            return 0;
        }

        int updated = 0;
        for (MeshMessage message : messages) {
            if (!needsReplyText(message)) {
                continue;
            }

            MeshMessage original = findByPacketId(message.getReplyId(), chatType, chatKey, ownerNodeId);
            if (original == null || original.getText() == null || original.getText().isEmpty()) {
                continue;
            }

            message.setReplyText(original.getText());
            if (message.getDbId() > 0) {
                updateReplyText(message.getDbId(), original.getText());
            }
            updated++;
        }
        return updated;
    }

    /**
     * Заполняет отсутствующие reply_text во всём указанном чате, когда оригинал
     * сообщения уже сохранён в этом же owner/chat scope.
     */
    public synchronized int backfillMissingReplyTexts(String chatType, String chatKey, String ownerNodeId) {
        if (dbConnection == null) {
            return 0;
        }

        List<ReplyBackfillTarget> targets = new ArrayList<>();
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT id, reply_id FROM messages
                WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ?
                  AND reply_id <> 0
                  AND (reply_text IS NULL OR LENGTH(reply_text) = 0)
                ORDER BY id ASC
                """)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    targets.add(new ReplyBackfillTarget(rs.getLong("id"), rs.getInt("reply_id")));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list missing reply_text rows for ({}, {}, {})",
                    ownerNodeId, chatType, chatKey, e);
            return 0;
        }

        int updated = 0;
        for (ReplyBackfillTarget target : targets) {
            MeshMessage original = findByPacketId(target.replyId(), chatType, chatKey, ownerNodeId);
            if (original == null || original.getText() == null || original.getText().isEmpty()) {
                continue;
            }
            updateReplyText(target.dbId(), original.getText());
            updated++;
        }
        return updated;
    }

    private static boolean needsReplyText(MeshMessage message) {
        return message != null
                && message.getReplyId() != 0
                && (message.getReplyText() == null || message.getReplyText().isEmpty());
    }

    private void updateReplyText(long dbId, String replyText) {
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "UPDATE messages SET reply_text = ? WHERE id = ? AND (reply_text IS NULL OR LENGTH(reply_text) = 0)")) {
            ps.setString(1, replyText);
            ps.setLong(2, dbId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update reply_text for message id={}", dbId, e);
        }
    }

    private record ReplyBackfillTarget(long dbId, int replyId) {}

    /**
     * Загружает реакции для набора packetId сообщений, сгруппированные по target_packet_id.
     */
    public Map<Integer, List<MessageReaction>> loadReactionsByTargetPacketIds(String chatType,
                                                                              String chatKey,
                                                                              String ownerNodeId,
                                                                              Collection<Integer> targetPacketIds) {
        Map<Integer, List<MessageReaction>> result = new HashMap<>();
        if (dbConnection == null || targetPacketIds == null || targetPacketIds.isEmpty()) {
            return result;
        }

        List<Integer> ids = targetPacketIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id != 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) { return result; }

        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        String sql = """
            SELECT * FROM message_reactions
            WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ?
              AND target_packet_id IN (%s)
            ORDER BY target_packet_id ASC, id ASC
            """.formatted(placeholders);
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            for (int i = 0; i < ids.size(); i++) {
                ps.setInt(4 + i, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MessageReaction reaction = readReaction(rs);
                    result.computeIfAbsent(reaction.getTargetPacketId(), ignored -> new ArrayList<>())
                            .add(reaction);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load reactions ({}, {}, {})", chatType, chatKey, ids.size(), e);
        }
        return result;
    }

    /**
     * Возвращает список уникальных DM-пиров (chat_key) из БД для данного устройства.
     */
    public List<String> getDistinctDmPeers(String ownerNodeId) {
        List<String> peers = new ArrayList<>();
        if (dbConnection == null) { return peers; }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT DISTINCT chat_key FROM messages WHERE owner_node_id = ? AND chat_type = 'dm'")) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    peers.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get distinct DM peers", e);
        }
        return peers;
    }

    /**
     * Возвращает последнее сообщение для каждого chat_key данного типа и устройства.
     * Ключ — chat_key, значение — последнее MeshMessage.
     */
    public Map<String, MeshMessage> getLastMessagePerChat(String chatType, String ownerNodeId) {
        Map<String, MeshMessage> result = new LinkedHashMap<>();
        if (dbConnection == null) { return result; }
        String sql = """
            SELECT m.* FROM messages m
            INNER JOIN (
                SELECT chat_key, MAX(id) AS max_id
                FROM messages WHERE owner_node_id = ? AND chat_type = ?
                GROUP BY chat_key
            ) sub ON m.id = sub.max_id
            ORDER BY m.timestamp DESC
            """;
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MeshMessage msg = readMessage(rs);
                    result.put(rs.getString("chat_key"), msg);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get last messages per chat ({})", chatType, e);
        }
        return result;
    }

    /**
     * Возвращает количество сообщений в чате для данного устройства.
     */
    public int getMessageCount(String chatType, String chatKey, String ownerNodeId) {
        if (dbConnection == null) { return 0; }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT COUNT(*) FROM messages WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ?")) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { return rs.getInt(1); }
            }
        } catch (SQLException e) {
            log.error("Failed to count messages ({}, {})", chatType, chatKey, e);
        }
        return 0;
    }

    /**
     * Возвращает количество сообщений, которые могут считаться непрочитанными:
     * любые не-исходящие сообщения, включая системные.
     */
    public int getUnreadEligibleMessageCount(String chatType, String chatKey, String ownerNodeId) {
        if (dbConnection == null) { return 0; }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT COUNT(*) FROM messages
                WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ?
                  AND outgoing = FALSE
                """)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { return rs.getInt(1); }
            }
        } catch (SQLException e) {
            log.error("Failed to count unread-eligible messages ({}, {})", chatType, chatKey, e);
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════════
    //  Удаление
    // ═══════════════════════════════════════════════════════════

    /**
     * Удаляет все сообщения и счётчик прочитанных для указанного чата и устройства.
     *
     * @param chatType    "channel" или "dm"
     * @param chatKey     channelIndex (как строка) или peerNodeId
     * @param ownerNodeId nodeId устройства-владельца
     */
    public synchronized void deleteChat(String chatType, String chatKey, String ownerNodeId) {
        if (dbConnection == null) { return; }
        try (PreparedStatement ps1 = dbConnection.prepareStatement(
                     "DELETE FROM messages WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ?");
             PreparedStatement psReactions = dbConnection.prepareStatement(
                     "DELETE FROM message_reactions WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ?");
             PreparedStatement ps2 = dbConnection.prepareStatement(
                     "DELETE FROM chat_read_counts WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ?")) {
            ps1.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps1.setString(2, chatType);
            ps1.setString(3, chatKey);
            ps1.executeUpdate();

            psReactions.setString(1, ownerNodeId != null ? ownerNodeId : "");
            psReactions.setString(2, chatType);
            psReactions.setString(3, chatKey);
            psReactions.executeUpdate();

            ps2.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps2.setString(2, chatType);
            ps2.setString(3, chatKey);
            ps2.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete chat ({}, {})", chatType, chatKey, e);
        }
    }

    /**
     * Удаляет одно сообщение по его id в БД.
     */
    public synchronized void deleteMessage(long dbId) {
        if (dbConnection == null || dbId <= 0) { return; }
        String ownerNodeId = null;
        String chatType = null;
        String chatKey = null;
        int packetId = 0;

        try (PreparedStatement lookup = dbConnection.prepareStatement(
                "SELECT owner_node_id, chat_type, chat_key, packet_id FROM messages WHERE id = ?")) {
            lookup.setLong(1, dbId);
            try (ResultSet rs = lookup.executeQuery()) {
                if (rs.next()) {
                    ownerNodeId = rs.getString("owner_node_id");
                    chatType = rs.getString("chat_type");
                    chatKey = rs.getString("chat_key");
                    packetId = rs.getInt("packet_id");
                }
            }
        } catch (SQLException e) {
            log.error("Failed to look up message id={}", dbId, e);
        }

        try (PreparedStatement ps = dbConnection.prepareStatement("DELETE FROM messages WHERE id = ?")) {
            ps.setLong(1, dbId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete message id={}", dbId, e);
        }

        if (packetId != 0 && ownerNodeId != null && chatType != null && chatKey != null) {
            try (PreparedStatement ps = dbConnection.prepareStatement("""
                    DELETE FROM message_reactions
                    WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ? AND target_packet_id = ?
                    """)) {
                ps.setString(1, ownerNodeId);
                ps.setString(2, chatType);
                ps.setString(3, chatKey);
                ps.setInt(4, packetId);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to delete reactions for message id={}", dbId, e);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Трекинг прочитанных сообщений
    // ═══════════════════════════════════════════════════════════

    /**
     * Сохранить количество прочитанных сообщений для чата и устройства.
     */
    public void saveReadCount(String chatType, String chatKey, int readCount, String ownerNodeId) {
        if (dbConnection == null) { return; }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "MERGE INTO chat_read_counts (owner_node_id, chat_type, chat_key, read_count) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            ps.setInt(4, readCount);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save read count ({}, {})", chatType, chatKey, e);
        }
    }

    /**
     * Загрузить все счётчики прочитанных сообщений для данного устройства.
     * @return Map с ключами "ch:KEY" / "dm:KEY" → readCount
     */
    public Map<String, Integer> loadAllReadCounts(String ownerNodeId) {
        Map<String, Integer> result = new HashMap<>();
        if (dbConnection == null) { return result; }
        List<String[]> normalizations = new ArrayList<>();
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT chat_type, chat_key, read_count FROM chat_read_counts WHERE owner_node_id = ?")) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("chat_type");
                    String key = rs.getString("chat_key");
                    int count = rs.getInt("read_count");
                    int totalMessages = getMessageCount(type, key, ownerNodeId);
                    int eligibleCount = getUnreadEligibleMessageCount(type, key, ownerNodeId);
                    if (totalMessages > 0 && count > eligibleCount) {
                        count = eligibleCount;
                        normalizations.add(new String[]{type, key, String.valueOf(count)});
                    }
                    String mapKey = ("channel".equals(type) ? "ch:" : "dm:") + key;
                    result.put(mapKey, count);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load read counts", e);
        }
        for (String[] normalization : normalizations) {
            saveReadCount(normalization[0], normalization[1], Integer.parseInt(normalization[2]), ownerNodeId);
        }
        return result;
    }

    /**
     * Считает суммарное количество непрочитанных сообщений по всем чатам владельца.
     *
     * @param ownerNodeId nodeId устройства-владельца
     * @return сумма непрочитанных входящих сообщений
     */
    public int getTotalUnreadCount(String ownerNodeId) {
        if (dbConnection == null) { return 0; }

        String normalizedOwnerNodeId = ownerNodeId != null ? ownerNodeId : "";
        Map<String, Integer> unreadEligibleCounts = new HashMap<>();
        Map<String, Integer> readCounts = new HashMap<>();

        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT chat_type, chat_key, COUNT(*) AS unread_count
                FROM messages
                WHERE owner_node_id = ? AND outgoing = FALSE
                GROUP BY chat_type, chat_key
                """)) {
            ps.setString(1, normalizedOwnerNodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    unreadEligibleCounts.put(
                            readCountKey(rs.getString("chat_type"), rs.getString("chat_key")),
                            rs.getInt("unread_count"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to count total unread messages", e);
            return 0;
        }

        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT chat_type, chat_key, read_count FROM chat_read_counts WHERE owner_node_id = ?")) {
            ps.setString(1, normalizedOwnerNodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    readCounts.put(
                            readCountKey(rs.getString("chat_type"), rs.getString("chat_key")),
                            rs.getInt("read_count"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load read counts for total unread calculation", e);
            return 0;
        }

        int totalUnread = 0;
        for (Map.Entry<String, Integer> entry : unreadEligibleCounts.entrySet()) {
            int read = readCounts.getOrDefault(entry.getKey(), 0);
            totalUnread += Math.max(0, entry.getValue() - read);
        }
        return totalUnread;
    }

    private static String readCountKey(String chatType, String chatKey) {
        return (chatType != null ? chatType : "") + "\u0000" + (chatKey != null ? chatKey : "");
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
        if (!Files.isDirectory(historyDir)) { return; }

        // Проверяем, есть ли уже данные (legacy check без ownerNodeId)
        if (getMessageCount("channel", "0", "") > 0 || !getDistinctDmPeers("").isEmpty()) {
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
                        if (stored == null || stored.isEmpty()) { continue; }

                        String chatType;
                        String chatKey;
                        if (name.startsWith("channel_")) {
                            chatType = "channel";
                            chatKey = name.replace("channel_", "").replace(".json", "");
                        } else if (name.startsWith("dm_")) {
                            chatType = "dm";
                            // dm файл содержит hex nodeNum — создаём NodeData для получения nodeId
                            int dmNodeNum = (int) Long.parseLong(name.replace("dm_", "").replace(".json", ""), 16);
                            chatKey = new NodeData(dmNodeNum).getNodeId();
                        } else {
                            continue;
                        }

                        for (JsonStoredMessage s : stored) {
                            String fromNodeId = new NodeData(s.fromNum).getNodeId();
                            String toNodeId = new NodeData(s.toNum).getNodeId();
                            MeshMessage msg = new MeshMessage(fromNodeId, toNodeId, s.channelIndex,
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
                            insertStmt.setString(2, chatKey);
                            insertStmt.setString(3, msg.getFromNodeId());
                            insertStmt.setString(4, msg.getToNodeId());
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
                            insertStmt.setInt(18, 0);
                            insertStmt.setFloat(19, 0);
                            insertStmt.setBoolean(20, false);
                            insertStmt.setString(21, "");
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
            try { dbConnection.rollback(); dbConnection.setAutoCommit(true); } catch (SQLException ex) {
                log.debug("Rollback failed during JSON migration recovery", ex);
            }
        }
    }

    /** Структура JSON-файлов из MessageHistoryService */
    @SuppressWarnings("unused")
    private static final class JsonStoredMessage {
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
                rs.getString("from_node_id"),
                rs.getString("to_node_id"),
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
        msg.setRxRssi(rs.getInt("rx_rssi"));
        msg.setRxSnr(rs.getFloat("rx_snr"));
        msg.setViaMqtt(rs.getBoolean("via_mqtt"));
        return msg;
    }

    /**
     * Закрывает соединение с БД.
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
    }

    private void closeStatements() {
        try {
            if (insertStmt != null) { insertStmt.close(); }
            if (updateStatusStmt != null) { updateStatusStmt.close(); }
            if (insertReactionStmt != null) { insertReactionStmt.close(); }
            if (updateReactionStatusStmt != null) { updateReactionStatusStmt.close(); }
        } catch (SQLException e) {
            log.error("Error closing message DB statements", e);
        } finally {
            insertStmt = null;
            updateStatusStmt = null;
            insertReactionStmt = null;
            updateReactionStatusStmt = null;
        }
    }

    private static MessageReaction readReaction(ResultSet rs) throws SQLException {
        MessageReaction reaction = new MessageReaction(
                rs.getInt("target_packet_id"),
                rs.getString("from_node_id"),
                rs.getString("emoji"),
                rs.getLong("timestamp"),
                rs.getBoolean("outgoing")
        );
        reaction.setDbId(rs.getLong("id"));
        reaction.setPacketId(rs.getInt("reaction_packet_id"));

        String statusStr = rs.getString("status");
        if (statusStr != null) {
            try {
                reaction.setStatus(MeshMessage.DeliveryStatus.valueOf(statusStr));
            } catch (IllegalArgumentException ignored) {}
        }

        reaction.setErrorReason(rs.getString("error_reason"));
        reaction.setSenderName(rs.getString("sender_name"));
        return reaction;
    }
}
