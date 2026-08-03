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
 * Persistent message store backed by H2, using the shared {@code ~/.meshapp/nodedb} database.
 * <p>
 * Channel and DM messages are saved immediately on receive/send. FormChat loads
 * messages in batches of 50, paginated by {@code id}.
 * <p>
 * Chat types ({@code chat_type}):
 * <ul>
 *   <li>{@code "channel"} - channel messages, {@code chat_key} = channelIndex</li>
 *   <li>{@code "dm"} - direct messages, {@code chat_key} = peerNodeNum</li>
 * </ul>
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MessageDbService {

    /** Latest persisted message and unread-eligible total for one chat. */
    public record ChatSummary(String chatType,
                              String chatKey,
                              MeshMessage lastMessage,
                              int unreadEligibleCount) {}

    private static final Logger log = LoggerFactory.getLogger(MessageDbService.class);
    private static final String H2_FULLTEXT_WHITESPACE = " \t\n\r\f+\"*%&/()=?'!,.;:-_#@|^~`{}[]<>\\";
    private static final int MESSAGE_SEARCH_CANDIDATE_BATCH_SIZE = 256;
    private static final int MESSAGE_SEARCH_COUNT_LIMIT = 1000;
    private static final int MESSAGE_SEARCH_COUNT_CANDIDATE_LIMIT = 16_384;
    private static final int MESSAGE_SEARCH_WORD_ALTERNATIVE_LIMIT = 128;
    private static final int MESSAGE_SEARCH_MIN_PREFIX_LENGTH = 4;
    private static final List<String> RUSSIAN_SEARCH_SUFFIXES = List.of(
            "ОСТЯМИ", "ОСТЯХ", "ОСТЯМ", "ОСТЕЙ",
            "ИЯМИ", "ИЯХ", "ИЯМ", "ИЕЙ",
            "НОГО", "НОМУ", "НЫМИ",
            "АМИ", "ЯМИ", "ЕГО", "ОГО", "ЕМУ", "ОМУ", "ЫМИ", "ИМИ",
            "НОЙ", "НАЯ", "НОЕ", "НЫЕ", "НЫЙ", "НЫМ", "НЫХ",
            "НУЮ", "ЬЮ", "ЕЙ", "ИЙ", "ЫЙ", "ОЙ", "АЯ", "ЯЯ", "ОЕ", "ЕЕ", "ЫЕ", "ИЕ",
            "ОМ", "ЕМ", "АМ", "ЯМ", "АХ", "ЯХ", "ОВ", "ЕВ", "ИЯ", "ИЕ", "ИЙ");

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
    // Database initialization.
    // ═══════════════════════════════════════════════════════════

    private void initDb() {
        try {
            closeStatements();
            dbConnection = DatabaseProvider.openServiceConnection();
            if (dbConnection == null) {
                log.error("Message DB initialization skipped because database connection is unavailable");
                return;
            }

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
                    CREATE INDEX IF NOT EXISTS idx_msg_chat_unread
                    ON messages (owner_node_id, chat_type, chat_key, outgoing, id)
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

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chat_threads (
                        owner_node_id VARCHAR(20) NOT NULL DEFAULT '',
                        chat_type     VARCHAR(10) NOT NULL,
                        chat_key      VARCHAR(20) NOT NULL,
                        PRIMARY KEY (owner_node_id, chat_type, chat_key)
                    )
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS traceroute_results (
                        id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
                        owner_node_id          VARCHAR(20) NOT NULL DEFAULT '',
                        chat_type              VARCHAR(10) NOT NULL DEFAULT '',
                        chat_key               VARCHAR(20) NOT NULL DEFAULT '',
                        source                 VARCHAR(80) NOT NULL DEFAULT '',
                        request_id             VARCHAR(120) NOT NULL DEFAULT '',
                        script_id              BIGINT DEFAULT 0,
                        target_node_num        BIGINT DEFAULT 0,
                        target_node_id         VARCHAR(20),
                        target_name            VARCHAR(120),
                        response_from_node_num BIGINT DEFAULT 0,
                        response_from_node_id  VARCHAR(20),
                        route_data             BLOB,
                        formatted_text         CLOB,
                        timestamp              BIGINT NOT NULL
                    )
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_traceroute_owner_time
                    ON traceroute_results (owner_node_id, timestamp DESC, id DESC)
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_traceroute_request
                    ON traceroute_results (owner_node_id, request_id)
                    """);
            }
            ensureMessageFullTextIndex();

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
     * Checks that the message full-text index exists when the service starts.
     * <p>
     * The normal index creation path is a database migration, but this check is
     * kept for new databases and for recovery after manual damage to H2 full-text
     * service objects.
     */
    private void ensureMessageFullTextIndex() {
        try {
            MessageFullTextIndex.ensureExists(dbConnection);
        } catch (SQLException e) {
            log.error("Failed to initialize fulltext index for messages", e);
        }
    }

    /**
     * Marks all {@code SENDING} messages as {@code FAILED} with reason {@code STALE}.
     * Called once on application startup to clean up messages left hanging from
     * the previous session, such as after a crash or power loss.
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
    // Writes.
    // ═══════════════════════════════════════════════════════════

    /**
     * Saves a message to the database and sets {@code msg.setDbId()} after insert.
     *
     * @param msg         message to save
     * @param chatType    "channel" or "dm"
     * @param chatKey     channelIndex as string, or peerNodeId
     * @param ownerNodeId owner device nodeId, for example "!9e755af0"
     */
    public synchronized void save(MeshMessage msg, String chatType, String chatKey, String ownerNodeId) {
        if (msg == null) { return; }
        if (insertStmt == null) {
            log.warn("Message DB not initialized — message dropped (chatType={}, chatKey={})", chatType, chatKey);
            return;
        }
        String normalizedOwnerNodeId = ownerNodeId != null ? ownerNodeId : "";
        if ("dm".equals(chatType)) {
            ensureChatThread(chatType, chatKey, normalizedOwnerNodeId);
        }
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

    /**
     * Persists an explicit chat thread so an empty DM chat can survive restart.
     */
    public synchronized void ensureChatThread(String chatType, String chatKey, String ownerNodeId) {
        if (dbConnection == null || chatType == null || chatType.isBlank()
                || chatKey == null || chatKey.isBlank()) {
            return;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "MERGE INTO chat_threads (owner_node_id, chat_type, chat_key) VALUES (?, ?, ?)")) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to ensure chat thread ({}, {})", chatType, chatKey, e);
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
                        WHEN ? = TRUE AND via_mqtt = TRUE AND hop_start = 0 AND ? <> 0 THEN ?
                        ELSE hop_start
                    END,
                    hop_limit = CASE
                        WHEN ? = FALSE AND ? <> 0 THEN ?
                        WHEN ? = TRUE AND via_mqtt = TRUE AND hop_limit = 0 AND ? <> 0 THEN ?
                        ELSE hop_limit
                    END,
                    sender_name = COALESCE(?, sender_name),
                    rx_rssi = CASE
                        WHEN ? = FALSE AND ? <> 0 THEN ?
                        WHEN ? = TRUE AND via_mqtt = TRUE AND rx_rssi = 0 AND ? <> 0 THEN ?
                        ELSE rx_rssi
                    END,
                    rx_snr = CASE
                        WHEN ? = FALSE AND ? <> 0 THEN ?
                        WHEN ? = TRUE AND via_mqtt = TRUE AND rx_snr = 0 AND ? <> 0 THEN ?
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
     * Saves a message reaction in a separate table so it does not affect chat
     * previews, unread counters, or the regular message history.
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
     * Saves a successful traceroute result in a separate table.
     * <p>
     * Unlike the old system-message mechanism, this row does not enter chat
     * history and is not included in message full-text search.
     *
     * @return id of the row in {@code traceroute_results}; {@code 0} if saving failed
     */
    public synchronized long saveTracerouteResult(String ownerNodeId,
                                                  String chatType,
                                                  String chatKey,
                                                  String source,
                                                  String requestId,
                                                  long scriptId,
                                                  long targetNodeNum,
                                                  String targetNodeId,
                                                  String targetName,
                                                  long responseFromNodeNum,
                                                  String responseFromNodeId,
                                                  byte[] routeData,
                                                  String formattedText,
                                                  long timestamp) {
        if (dbConnection == null) {
            return 0;
        }
        String normalizedOwnerNodeId = ownerNodeId != null ? ownerNodeId : "";
        String normalizedRequestId = requestId != null ? requestId : "";
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                INSERT INTO traceroute_results (
                    owner_node_id, chat_type, chat_key, source, request_id, script_id,
                    target_node_num, target_node_id, target_name,
                    response_from_node_num, response_from_node_id,
                    route_data, formatted_text, timestamp
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, normalizedOwnerNodeId);
            ps.setString(2, chatType != null ? chatType : "");
            ps.setString(3, chatKey != null ? chatKey : "");
            ps.setString(4, source != null ? source : "");
            ps.setString(5, normalizedRequestId);
            ps.setLong(6, scriptId);
            ps.setLong(7, targetNodeNum);
            ps.setString(8, targetNodeId);
            ps.setString(9, targetName);
            ps.setLong(10, responseFromNodeNum);
            ps.setString(11, responseFromNodeId);
            ps.setBytes(12, routeData);
            ps.setString(13, formattedText);
            ps.setLong(14, timestamp > 0 ? timestamp : System.currentTimeMillis() / 1000);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        } catch (SQLException e) {
            log.error("Failed to save traceroute result (requestId={}, target={})",
                    normalizedRequestId, targetNodeId, e);
            return 0;
        }
    }

    /**
     * Chat scope for a saved reaction.
     *
     * @param ownerNodeId owner of the local history
     * @param chatType chat type
     * @param chatKey chat key
     * @param targetPacketId packet id of the message being reacted to
     */
    public record ReactionScope(String ownerNodeId, String chatType, String chatKey, int targetPacketId) {}

    public synchronized ReactionScope findReactionScopeByPacketId(int packetId) {
        if (dbConnection == null || packetId == 0) {
            return null;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT owner_node_id, chat_type, chat_key, target_packet_id
                FROM message_reactions
                WHERE reaction_packet_id = ?
                LIMIT 1
                """)) {
            ps.setInt(1, packetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ReactionScope(
                            rs.getString("owner_node_id"),
                            rs.getString("chat_type"),
                            rs.getString("chat_key"),
                            rs.getInt("target_packet_id"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to find reaction scope for packetId {}", packetId, e);
        }
        return null;
    }

    /**
     * Updates message delivery status by packetId.
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
     * Moves an existing message into a new send attempt by updating packetId,
     * status, and error reason.
     *
     * @return {@code true} if at least one row was updated
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
     * Updates reaction delivery status by packetId.
     *
     * @return {@code true} if at least one row was found and updated
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
    // Reads: pagination.
    // ═══════════════════════════════════════════════════════════

    /**
     * Loads the last N messages for a chat in chronological order.
     *
     * @param chatType    "channel" or "dm"
     * @param chatKey     channelIndex as string, or peerNodeId
     * @param limit       maximum number of messages
     * @param ownerNodeId owner device nodeId
     * @return messages from oldest to newest
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
     * Loads N messages before the given id, used when scrolling upward.
     *
     * @param chatType    "channel" or "dm"
     * @param chatKey     channelIndex as string, or peerNodeId
     * @param beforeDbId  load messages with id &lt; beforeDbId
     * @param limit       maximum number of messages
     * @param ownerNodeId owner device nodeId
     * @return messages from oldest to newest
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
     * Loads new messages after the given id, used for real-time updates.
     *
     * @param chatType    "channel" or "dm"
     * @param chatKey     channelIndex as string, or peerNodeId
     * @param afterDbId   load messages with id > afterDbId
     * @param ownerNodeId owner device nodeId
     * @return new messages in chronological order
     */
    public List<MeshMessage> loadAfter(String chatType, String chatKey, long afterDbId, String ownerNodeId) {
        return loadAfter(chatType, chatKey, afterDbId, 0, ownerNodeId);
    }

    /**
     * Loads new messages after the given id, used for real-time updates and
     * downward pagination.
     *
     * @param chatType    "channel" or "dm"
     * @param chatKey     channelIndex as string, or peerNodeId
     * @param afterDbId   load messages with id > afterDbId
     * @param limit       maximum number of messages; {@code <= 0} means unlimited
     * @param ownerNodeId owner device nodeId
     * @return new messages in chronological order
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

    /**
     * Loads messages for several packet ids in one round trip. This replaces the
     * former chat live-update N+1 lookup path.
     */
    public Map<Integer, MeshMessage> findByPacketIds(Collection<Integer> packetIds,
                                                     String chatType,
                                                     String chatKey,
                                                     String ownerNodeId) {
        Map<Integer, MeshMessage> result = new HashMap<>();
        if (dbConnection == null || packetIds == null || packetIds.isEmpty()) {
            return result;
        }
        List<Integer> ids = packetIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id != 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return result;
        }

        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT * FROM messages "
                + "WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ? "
                + "AND packet_id IN (" + placeholders + ") ORDER BY id ASC";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            for (int i = 0; i < ids.size(); i++) {
                ps.setInt(4 + i, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MeshMessage message = readMessage(rs);
                    result.put(message.getPacketId(), message);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to batch-load messages by packet ids ({}, {}, {})",
                    ownerNodeId, chatType, chatKey, e);
        }
        return result;
    }

    /**
     * Loads one chat message by database id.
     *
     * @param chatType    "channel" or "dm"
     * @param chatKey     channelIndex as string, or peerNodeId
     * @param dbId        message id in the database
     * @param ownerNodeId owner device nodeId
     * @return message, or {@code null} if it does not belong to the requested chat
     */
    public MeshMessage findByDbId(String chatType, String chatKey, long dbId, String ownerNodeId) {
        if (dbConnection == null || dbId <= 0) { return null; }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT * FROM messages
                WHERE owner_node_id = ?
                  AND chat_type = ?
                  AND chat_key = ?
                  AND id = ?
                LIMIT 1
                """)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            ps.setLong(4, dbId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readMessage(rs) : null;
            }
        } catch (SQLException e) {
            log.error("Failed to find message by dbId {} ({}, {})", dbId, chatType, chatKey, e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Reads: search and metadata.
    // ═══════════════════════════════════════════════════════════

    /**
     * Loads recent system messages whose text starts with the given prefix.
     *
     * @param textPrefix  system-message text prefix
     * @param limit       maximum number of messages
     * @param ownerNodeId owner device nodeId
     * @return messages from newest to oldest
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
     * Loads recent saved traceroute results.
     *
     * @param limit       maximum number of rows
     * @param ownerNodeId owner device nodeId
     * @return results from newest to oldest
     */
    public List<TracerouteResultRecord> loadRecentTracerouteResults(int limit, String ownerNodeId) {
        List<TracerouteResultRecord> result = new ArrayList<>();
        if (dbConnection == null || limit <= 0) {
            return result;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT *
                FROM traceroute_results
                WHERE owner_node_id = ?
                ORDER BY timestamp DESC, id DESC
                LIMIT ?
                """)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readTracerouteResult(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load recent traceroute results (limit={})", limit, e);
        }
        return result;
    }

    /**
     * Loads saved traceroute results for a specific node.
     *
     * @param ownerNodeId   owner device nodeId
     * @param targetNodeNum numeric target node id as unsigned int
     * @param targetNodeId  target nodeId string
     * @return results from newest to oldest
     */
    public List<TracerouteResultRecord> loadTracerouteResultsForNode(String ownerNodeId,
                                                                     long targetNodeNum,
                                                                     String targetNodeId) {
        return loadTracerouteResultsForNode(
                ownerNodeId,
                targetNodeNum,
                targetNodeId,
                0,
                0,
                Integer.MAX_VALUE,
                0,
                0);
    }

    /**
     * Loads one page of saved traceroute results for a specific node.
     *
     * @param ownerNodeId            owner device nodeId
     * @param targetNodeNum          numeric target node id as unsigned int
     * @param targetNodeId           target nodeId string
     * @param startTimestampInclusive start of the time filter; {@code <= 0} disables the lower bound
     * @param endTimestampExclusive   end of the time filter; {@code <= 0} disables the upper bound
     * @param limit                  maximum number of rows
     * @param beforeTimestamp        timestamp of the last loaded row; {@code <= 0} for the first page
     * @param beforeId               id of the last loaded row, used with {@code beforeTimestamp}
     * @return page of results from newest to oldest
     */
    public List<TracerouteResultRecord> loadTracerouteResultsForNode(String ownerNodeId,
                                                                     long targetNodeNum,
                                                                     String targetNodeId,
                                                                     long startTimestampInclusive,
                                                                     long endTimestampExclusive,
                                                                     int limit,
                                                                     long beforeTimestamp,
                                                                     long beforeId) {
        List<TracerouteResultRecord> result = new ArrayList<>();
        if (dbConnection == null
                || limit <= 0
                || (targetNodeNum <= 0 && (targetNodeId == null || targetNodeId.isBlank()))) {
            return result;
        }

        String normalizedOwnerNodeId = ownerNodeId != null ? ownerNodeId : "";
        String normalizedTargetNodeId = targetNodeId != null
                ? targetNodeId.trim().toLowerCase(Locale.ROOT)
                : "";
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT *
                FROM traceroute_results
                WHERE owner_node_id = ?
                  AND (
                    (? > 0 AND (target_node_num = ? OR response_from_node_num = ?))
                    OR (? <> '' AND (
                        LOWER(target_node_id) = ?
                        OR LOWER(response_from_node_id) = ?
                        OR LOWER(chat_key) = ?
                    ))
                  )
                  AND (? <= 0 OR timestamp >= ?)
                  AND (? <= 0 OR timestamp < ?)
                  AND (? <= 0 OR timestamp < ? OR (timestamp = ? AND id < ?))
                ORDER BY timestamp DESC, id DESC
                LIMIT ?
                """)) {
            ps.setString(1, normalizedOwnerNodeId);
            ps.setLong(2, targetNodeNum);
            ps.setLong(3, targetNodeNum);
            ps.setLong(4, targetNodeNum);
            ps.setString(5, normalizedTargetNodeId);
            ps.setString(6, normalizedTargetNodeId);
            ps.setString(7, normalizedTargetNodeId);
            ps.setString(8, normalizedTargetNodeId);
            ps.setLong(9, startTimestampInclusive);
            ps.setLong(10, startTimestampInclusive);
            ps.setLong(11, endTimestampExclusive);
            ps.setLong(12, endTimestampExclusive);
            ps.setLong(13, beforeTimestamp);
            ps.setLong(14, beforeTimestamp);
            ps.setLong(15, beforeTimestamp);
            ps.setLong(16, beforeId);
            ps.setInt(17, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readTracerouteResult(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load traceroute results for node {}", targetNodeId, e);
        }
        return result;
    }

    /**
     * Loads one saved traceroute result by id inside the owner scope.
     *
     * @param id          row id in {@code traceroute_results}
     * @param ownerNodeId owner device nodeId
     * @return result row, or empty when it is not found
     */
    public Optional<TracerouteResultRecord> loadTracerouteResult(long id, String ownerNodeId) {
        if (dbConnection == null || id <= 0) {
            return Optional.empty();
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT *
                FROM traceroute_results
                WHERE id = ? AND owner_node_id = ?
                LIMIT 1
                """)) {
            ps.setLong(1, id);
            ps.setString(2, ownerNodeId != null ? ownerNodeId : "");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(readTracerouteResult(rs))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to load traceroute result {}", id, e);
            return Optional.empty();
        }
    }

    /**
     * Checks whether a specific message matches a search query.
     *
     * @param chatType    chat type
     * @param chatKey     chat key
     * @param query       search string
     * @param ownerNodeId owner device nodeId
     * @param dbId        message id in the database
     * @return {@code true} if the full-text index finds the message
     */
    public boolean messageMatchesSearch(String chatType, String chatKey, String query, String ownerNodeId, long dbId) {
        return messageMatchesSearch(chatType, chatKey, query, ownerNodeId, null, dbId);
    }

    /**
     * Checks whether a specific message matches a search query and an optional
     * author filter.
     *
     * @param chatType    chat type
     * @param chatKey     chat key
     * @param query       search string
     * @param ownerNodeId owner device nodeId
     * @param fromNodeId  author nodeId; blank value disables the filter
     * @param dbId        message id in the database
     * @return {@code true} if the full-text index finds the message
     */
    public boolean messageMatchesSearch(String chatType,
                                        String chatKey,
                                        String query,
                                        String ownerNodeId,
                                        String fromNodeId,
                                        long dbId) {
        if (dbId <= 0) {
            return false;
        }
        MessageSearchQuery searchQuery = prepareMessageSearchQuery(query);
        if (searchQuery == null || !messageExistsInChat(chatType, chatKey, ownerNodeId, fromNodeId, dbId)) {
            return false;
        }
        Long rowId = fullTextRowIdForMessage(searchQuery.indexId(), dbId);
        if (rowId == null) {
            return false;
        }
        for (List<Integer> wordIdGroup : searchQuery.wordIdGroups()) {
            if (!fullTextRowContainsAnyWord(rowId, wordIdGroup)) {
                return false;
            }
        }
        return true;
    }

    private boolean fullTextRowContainsAnyWord(long rowId, List<Integer> wordIds) {
        if (wordIds.isEmpty()) {
            return false;
        }
        String placeholders = placeholders(wordIds.size());
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT 1
                FROM FT.MAP
                WHERE ROWID = ?
                  AND WORDID IN (%s)
                LIMIT 1
                """.formatted(placeholders))) {
            int parameterIndex = 1;
            ps.setLong(parameterIndex++, rowId);
            for (Integer wordId : wordIds) {
                ps.setInt(parameterIndex++, wordId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Failed to check fulltext row words ({})", rowId, e);
            return false;
        }
    }

    /**
     * Counts search matches through the full-text index with an upper limit.
     * <p>
     * An exact {@code COUNT(*)} for a common word can scan a very large set of
     * FT rows. The UI therefore gets an exact count in normal scenarios, and a
     * capped value with {@link MessageSearchCount#limited()} for overly frequent
     * queries.
     *
     * @param chatType    chat type
     * @param chatKey     chat key
     * @param query       search string
     * @param ownerNodeId owner device nodeId
     * @return number of matches, possibly capped
     */
    public MessageSearchCount countMessageSearchMatchesLimited(String chatType,
                                                               String chatKey,
                                                               String query,
                                                               String ownerNodeId) {
        return countMessageSearchMatchesLimited(chatType, chatKey, query, ownerNodeId, null);
    }

    /**
     * Counts search matches through the full-text index with an upper limit and
     * an optional author filter.
     *
     * @param chatType    chat type
     * @param chatKey     chat key
     * @param query       search string
     * @param ownerNodeId owner device nodeId
     * @param fromNodeId  author nodeId; blank value disables the filter
     * @return number of matches, possibly capped
     */
    public MessageSearchCount countMessageSearchMatchesLimited(String chatType,
                                                               String chatKey,
                                                               String query,
                                                               String ownerNodeId,
                                                               String fromNodeId) {
        MessageSearchQuery searchQuery = prepareMessageSearchQuery(query);
        if (searchQuery == null || chatType == null || chatKey == null) {
            return new MessageSearchCount(0, false);
        }

        int count = 0;
        int scannedCandidates = 0;
        long cursorRowId = 0;
        while (count < MESSAGE_SEARCH_COUNT_LIMIT
                && scannedCandidates < MESSAGE_SEARCH_COUNT_CANDIDATE_LIMIT) {
            List<FullTextCandidate> candidates = loadFullTextCandidates(searchQuery, cursorRowId, false);
            if (candidates.isEmpty()) {
                return new MessageSearchCount(count, false);
            }

            count += countScopedCandidateMessages(candidates, chatType, chatKey, ownerNodeId, fromNodeId);
            scannedCandidates += candidates.size();
            cursorRowId = candidates.getLast().rowId();
        }
        return new MessageSearchCount(Math.min(count, MESSAGE_SEARCH_COUNT_LIMIT), true);
    }

    private int countScopedCandidateMessages(List<FullTextCandidate> candidates,
                                             String chatType,
                                             String chatKey,
                                             String ownerNodeId,
                                             String fromNodeId) {
        if (candidates.isEmpty()) {
            return 0;
        }

        String placeholders = String.join(", ", Collections.nCopies(candidates.size(), "?"));
        String sql = """
                SELECT COUNT(*)
                FROM messages m
                WHERE m.owner_node_id = ?
                  AND m.chat_type = ?
                  AND m.chat_key = ?
                  %s
                  AND m.id IN (%s)
                """.formatted(senderFilterClause(fromNodeId), placeholders);
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            int parameterIndex = bindMessageSearchScope(ps, ownerNodeId, chatType, chatKey, 1);
            parameterIndex = bindSenderFilter(ps, fromNodeId, parameterIndex);
            for (FullTextCandidate candidate : candidates) {
                ps.setLong(parameterIndex++, candidate.messageId());
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0, rs.getInt(1)) : 0;
            }
        } catch (SQLException e) {
            log.error("Failed to count scoped message search candidates ({}, {})", chatType, chatKey, e);
            return 0;
        }
    }

    private boolean messageExistsInChat(String chatType,
                                        String chatKey,
                                        String ownerNodeId,
                                        String fromNodeId,
                                        long dbId) {
        if (dbConnection == null || chatType == null || chatKey == null || dbId <= 0) {
            return false;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT 1
                FROM messages
                WHERE owner_node_id = ?
                  AND chat_type = ?
                  AND chat_key = ?
                  %s
                  AND id = ?
                LIMIT 1
                """.formatted(senderFilterClause(fromNodeId)))) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            int parameterIndex = bindSenderFilter(ps, fromNodeId, 4);
            ps.setLong(parameterIndex, dbId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Failed to check message scope ({}, {}, {})", chatType, chatKey, dbId, e);
            return false;
        }
    }

    /**
     * Finds the newest message matching the search query.
     *
     * @param chatType    chat type
     * @param chatKey     chat key
     * @param query       search string
     * @param ownerNodeId owner device nodeId
     * @return message id, or {@code 0} when there are no matches
     */
    public long findLatestMessageSearchMatch(String chatType, String chatKey, String query, String ownerNodeId) {
        return findLatestMessageSearchMatch(chatType, chatKey, query, ownerNodeId, null);
    }

    /**
     * Finds the newest message matching the search query and optional author filter.
     *
     * @param chatType    chat type
     * @param chatKey     chat key
     * @param query       search string
     * @param ownerNodeId owner device nodeId
     * @param fromNodeId  author nodeId; blank value disables the filter
     * @return message id, or {@code 0} when there are no matches
     */
    public long findLatestMessageSearchMatch(String chatType,
                                             String chatKey,
                                             String query,
                                             String ownerNodeId,
                                             String fromNodeId) {
        return findMessageSearchMatch(chatType, chatKey, query, ownerNodeId, fromNodeId, 0, null, "DESC");
    }

    /**
     * Finds the nearest previous match before the specified message.
     *
     * @param chatType    chat type
     * @param chatKey     chat key
     * @param query       search string
     * @param ownerNodeId owner device nodeId
     * @param beforeDbId  message id before which to search
     * @return message id, or {@code 0} when there is no previous match
     */
    public long findPreviousMessageSearchMatch(String chatType,
                                               String chatKey,
                                               String query,
                                               String ownerNodeId,
                                               long beforeDbId) {
        if (beforeDbId <= 0) {
            return 0;
        }
        return findPreviousMessageSearchMatch(chatType, chatKey, query, ownerNodeId, null, beforeDbId);
    }

    /**
     * Finds the nearest previous match before the specified message with an
     * optional author filter.
     *
     * @param chatType    chat type
     * @param chatKey     chat key
     * @param query       search string
     * @param ownerNodeId owner device nodeId
     * @param fromNodeId  author nodeId; blank value disables the filter
     * @param beforeDbId  message id before which to search
     * @return message id, or {@code 0} when there is no previous match
     */
    public long findPreviousMessageSearchMatch(String chatType,
                                               String chatKey,
                                               String query,
                                               String ownerNodeId,
                                               String fromNodeId,
                                               long beforeDbId) {
        if (beforeDbId <= 0) {
            return 0;
        }
        return findMessageSearchMatch(chatType, chatKey, query, ownerNodeId, fromNodeId, beforeDbId, "m.id < ?", "DESC");
    }

    /**
     * Finds the nearest next match after the specified message.
     *
     * @param chatType    chat type
     * @param chatKey     chat key
     * @param query       search string
     * @param ownerNodeId owner device nodeId
     * @param afterDbId   message id after which to search
     * @return message id, or {@code 0} when there is no next match
     */
    public long findNextMessageSearchMatch(String chatType,
                                           String chatKey,
                                           String query,
                                           String ownerNodeId,
                                           long afterDbId) {
        if (afterDbId <= 0) {
            return 0;
        }
        return findNextMessageSearchMatch(chatType, chatKey, query, ownerNodeId, null, afterDbId);
    }

    /**
     * Finds the nearest next match after the specified message with an optional
     * author filter.
     *
     * @param chatType    chat type
     * @param chatKey     chat key
     * @param query       search string
     * @param ownerNodeId owner device nodeId
     * @param fromNodeId  author nodeId; blank value disables the filter
     * @param afterDbId   message id after which to search
     * @return message id, or {@code 0} when there is no next match
     */
    public long findNextMessageSearchMatch(String chatType,
                                           String chatKey,
                                           String query,
                                           String ownerNodeId,
                                           String fromNodeId,
                                           long afterDbId) {
        if (afterDbId <= 0) {
            return 0;
        }
        return findMessageSearchMatch(chatType, chatKey, query, ownerNodeId, fromNodeId, afterDbId, "m.id > ?", "ASC");
    }

    private long findMessageSearchMatch(String chatType,
                                        String chatKey,
                                        String query,
                                        String ownerNodeId,
                                        String fromNodeId,
                                        long boundDbId,
                                        String boundClause,
                                        String sortDirection) {
        MessageSearchQuery searchQuery = prepareMessageSearchQuery(query);
        if (searchQuery == null || chatType == null || chatKey == null) {
            return 0;
        }

        boolean newestFirst = "DESC".equals(sortDirection);
        Long rowIdBound = boundDbId > 0 ? fullTextRowIdForMessage(searchQuery.indexId(), boundDbId) : null;
        long cursorRowId = rowIdBound != null
                ? rowIdBound
                : newestFirst ? Long.MAX_VALUE : 0;
        while (true) {
            List<FullTextCandidate> candidates = loadFullTextCandidates(searchQuery, cursorRowId, newestFirst);
            if (candidates.isEmpty()) {
                return 0;
            }
            long messageId = firstScopedCandidateMessageId(
                    candidates,
                    chatType,
                    chatKey,
                    ownerNodeId,
                    fromNodeId,
                    boundDbId,
                    boundClause,
                    sortDirection);
            if (messageId > 0) {
                return messageId;
            }
            cursorRowId = candidates.getLast().rowId();
        }
    }

    private Long fullTextRowIdForMessage(int indexId, long dbId) {
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT ID
                FROM FT.ROWS
                WHERE INDEXID = ?
                  AND "KEY" = ?
                LIMIT 1
                """)) {
            ps.setInt(1, indexId);
            ps.setString(2, fullTextMessageKey(dbId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("ID") : null;
            }
        } catch (SQLException e) {
            log.error("Failed to resolve fulltext row id for message {}", dbId, e);
            return null;
        }
    }

    private List<FullTextCandidate> loadFullTextCandidates(MessageSearchQuery searchQuery,
                                                           long cursorRowId,
                                                           boolean newestFirst) {
        if (searchQuery.wordIdGroups().isEmpty() || searchQuery.wordIdGroups().getFirst().isEmpty()) {
            return List.of();
        }

        List<Integer> firstWordIdGroup = searchQuery.wordIdGroups().getFirst();
        String sql = """
                SELECT DISTINCT ft_map.ROWID, ft_row."KEY"
                FROM FT.MAP ft_map
                JOIN FT.ROWS ft_row
                  ON ft_row.ID = ft_map.ROWID
                WHERE ft_map.WORDID IN (%s)
                  AND ft_map.ROWID %s ?
                  AND ft_row.INDEXID = ?
                  %s
                ORDER BY ft_map.ROWID %s
                LIMIT ?
                """.formatted(
                placeholders(firstWordIdGroup.size()),
                newestFirst ? "<" : ">",
                additionalFullTextTermClauses(searchQuery),
                newestFirst ? "DESC" : "ASC");
        List<FullTextCandidate> candidates = new ArrayList<>(MESSAGE_SEARCH_CANDIDATE_BATCH_SIZE);
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            int parameterIndex = 1;
            for (Integer wordId : firstWordIdGroup) {
                ps.setInt(parameterIndex++, wordId);
            }
            ps.setLong(parameterIndex++, cursorRowId);
            ps.setInt(parameterIndex++, searchQuery.indexId());
            for (int i = 1; i < searchQuery.wordIdGroups().size(); i++) {
                for (Integer wordId : searchQuery.wordIdGroups().get(i)) {
                    ps.setInt(parameterIndex++, wordId);
                }
            }
            ps.setInt(parameterIndex, MESSAGE_SEARCH_CANDIDATE_BATCH_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long messageId = parseFullTextMessageId(rs.getString("KEY"));
                    if (messageId > 0) {
                        candidates.add(new FullTextCandidate(rs.getLong("ROWID"), messageId));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load fulltext search candidates", e);
        }
        return candidates;
    }

    private static String additionalFullTextTermClauses(MessageSearchQuery searchQuery) {
        StringBuilder sql = new StringBuilder();
        for (int i = 1; i < searchQuery.wordIdGroups().size(); i++) {
            sql.append("""

                  AND EXISTS (
                      SELECT 1
                      FROM FT.MAP other_map
                      WHERE other_map.WORDID IN (%s)
                        AND other_map.ROWID = ft_map.ROWID
                  )
                    """.formatted(placeholders(searchQuery.wordIdGroups().get(i).size())));
        }
        return sql.toString();
    }

    private long firstScopedCandidateMessageId(List<FullTextCandidate> candidates,
                                               String chatType,
                                               String chatKey,
                                               String ownerNodeId,
                                               String fromNodeId,
                                               long boundDbId,
                                               String boundClause,
                                               String sortDirection) {
        if (candidates.isEmpty()) {
            return 0;
        }

        String placeholders = String.join(", ", Collections.nCopies(candidates.size(), "?"));
        String sql = """
                SELECT m.id
                FROM messages m
                WHERE m.owner_node_id = ?
                  AND m.chat_type = ?
                  AND m.chat_key = ?
                  %s
                  %s
                  AND m.id IN (%s)
                ORDER BY m.id %s
                LIMIT 1
                """.formatted(
                senderFilterClause(fromNodeId),
                boundClause == null ? "" : "AND " + boundClause,
                placeholders,
                sortDirection);
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            int parameterIndex = bindMessageSearchScope(ps, ownerNodeId, chatType, chatKey, 1);
            parameterIndex = bindSenderFilter(ps, fromNodeId, parameterIndex);
            if (boundClause != null) {
                ps.setLong(parameterIndex++, boundDbId);
            }
            for (FullTextCandidate candidate : candidates) {
                ps.setLong(parameterIndex++, candidate.messageId());
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("id") : 0;
            }
        } catch (SQLException e) {
            log.error("Failed to filter message search candidates ({}, {})", chatType, chatKey, e);
            return 0;
        }
    }

    private static String fullTextMessageKey(long dbId) {
        return "\"ID\"=" + dbId;
    }

    private static long parseFullTextMessageId(String key) {
        if (key == null) {
            return 0;
        }
        int delimiter = key.lastIndexOf('=');
        if (delimiter < 0 || delimiter >= key.length() - 1) {
            return 0;
        }
        try {
            return Long.parseLong(key.substring(delimiter + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private MessageSearchQuery prepareMessageSearchQuery(String query) {
        if (dbConnection == null) {
            return null;
        }
        List<String> terms = parseFullTextSearchTerms(query);
        if (terms.isEmpty()) {
            return null;
        }
        try {
            Integer indexId = MessageFullTextIndex.indexId(dbConnection);
            if (indexId == null) {
                return null;
            }
            List<List<Integer>> wordIdGroups = fullTextWordIdGroups(terms);
            return wordIdGroups.size() == terms.size() ? new MessageSearchQuery(indexId, wordIdGroups) : null;
        } catch (SQLException e) {
            log.error("Failed to resolve message fulltext index id", e);
            return null;
        }
    }

    private List<List<Integer>> fullTextWordIdGroups(List<String> terms) throws SQLException {
        List<List<Integer>> wordIdGroups = new ArrayList<>(terms.size());
        for (String term : terms) {
            List<Integer> wordIds = fullTextWordIdsForTerm(term);
            if (wordIds.isEmpty()) {
                return List.of();
            }
            wordIdGroups.add(wordIds);
        }
        return wordIdGroups;
    }

    private List<Integer> fullTextWordIdsForTerm(String term) throws SQLException {
        LinkedHashSet<Integer> wordIds = new LinkedHashSet<>();
        addExactFullTextWordId(term, wordIds);

        String prefix = searchPrefixForTerm(term);
        if (prefix.length() >= MESSAGE_SEARCH_MIN_PREFIX_LENGTH && containsCyrillic(prefix)) {
            addPrefixFullTextWordIds(prefix, wordIds);
        }
        return List.copyOf(wordIds);
    }

    private void addExactFullTextWordId(String term, Set<Integer> wordIds) throws SQLException {
        try (PreparedStatement ps = dbConnection.prepareStatement("SELECT ID FROM FT.WORDS WHERE NAME = ?")) {
            ps.setString(1, term);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    wordIds.add(rs.getInt("ID"));
                }
            }
        }
    }

    private void addPrefixFullTextWordIds(String prefix, Set<Integer> wordIds) throws SQLException {
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT ID
                FROM FT.WORDS
                WHERE NAME LIKE ?
                ORDER BY NAME
                LIMIT ?
                """)) {
            ps.setString(1, prefix + "%");
            ps.setInt(2, MESSAGE_SEARCH_WORD_ALTERNATIVE_LIMIT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    wordIds.add(rs.getInt("ID"));
                }
            }
        }
    }

    private static int bindMessageSearchScope(PreparedStatement ps,
                                              String ownerNodeId,
                                              String chatType,
                                              String chatKey,
                                              int parameterIndex) throws SQLException {
        ps.setString(parameterIndex++, ownerNodeId != null ? ownerNodeId : "");
        ps.setString(parameterIndex++, chatType);
        ps.setString(parameterIndex++, chatKey);
        return parameterIndex;
    }

    private static String senderFilterClause(String fromNodeId) {
        return hasSenderFilter(fromNodeId) ? "AND from_node_id = ?" : "";
    }

    private static int bindSenderFilter(PreparedStatement ps,
                                        String fromNodeId,
                                        int parameterIndex) throws SQLException {
        if (hasSenderFilter(fromNodeId)) {
            ps.setString(parameterIndex++, fromNodeId.trim());
        }
        return parameterIndex;
    }

    private static boolean hasSenderFilter(String fromNodeId) {
        return fromNodeId != null && !fromNodeId.isBlank();
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static String searchPrefixForTerm(String term) {
        if (term == null || term.isBlank() || !containsCyrillic(term)) {
            return term != null ? term : "";
        }
        for (String suffix : RUSSIAN_SEARCH_SUFFIXES) {
            if (term.endsWith(suffix) && term.length() - suffix.length() >= MESSAGE_SEARCH_MIN_PREFIX_LENGTH) {
                return term.substring(0, term.length() - suffix.length());
            }
        }
        char lastChar = term.charAt(term.length() - 1);
        if ((lastChar == 'Ь' || lastChar == 'Ъ' || lastChar == 'Й')
                && term.length() - 1 >= MESSAGE_SEARCH_MIN_PREFIX_LENGTH) {
            return term.substring(0, term.length() - 1);
        }
        if (isRussianVowel(lastChar) && term.length() - 1 >= MESSAGE_SEARCH_MIN_PREFIX_LENGTH) {
            return term.substring(0, term.length() - 1);
        }
        return term;
    }

    private static boolean isRussianVowel(char value) {
        return "АЕЁИОУЫЭЮЯ".indexOf(value) >= 0;
    }

    private static boolean containsCyrillic(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(value.charAt(i));
            if (block == Character.UnicodeBlock.CYRILLIC
                    || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY
                    || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_A
                    || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_B
                    || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_C) {
                return true;
            }
        }
        return false;
    }

    private static List<String> parseFullTextSearchTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        StringTokenizer tokenizer = new StringTokenizer(query, H2_FULLTEXT_WHITESPACE);
        while (tokenizer.hasMoreTokens()) {
            String term = tokenizer.nextToken().toUpperCase();
            if (!term.isBlank()) {
                terms.add(term);
            }
        }
        return List.copyOf(terms);
    }

    private record MessageSearchQuery(int indexId, List<List<Integer>> wordIdGroups) {}

    private record FullTextCandidate(long rowId, long messageId) {}

    /**
     * Result of a capped search-match count.
     *
     * @param count   number of matches found
     * @param limited {@code true} if counting stopped at the cap and the actual
     *                count may be higher
     */
    public record MessageSearchCount(int count, boolean limited) {}

    /**
     * Saved traceroute result.
     */
    public record TracerouteResultRecord(long id,
                                         String ownerNodeId,
                                         String chatType,
                                         String chatKey,
                                         String source,
                                         String requestId,
                                         long scriptId,
                                         long targetNodeNum,
                                         String targetNodeId,
                                         String targetName,
                                         long responseFromNodeNum,
                                         String responseFromNodeId,
                                         byte[] routeData,
                                         String formattedText,
                                         long timestamp) {}

    /**
     * Finds a message by packetId, used for reply_text.
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
     * Finds a message by packetId inside one owner/chat scope only.
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
     * Restores quote text for reply messages when the original already exists in
     * the same owner/chat scope. Resolved quote texts are saved to the database immediately.
     *
     * @return number of messages whose replyText was filled
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
     * Fills missing reply_text values across the given chat when the original
     * message is already saved in the same owner/chat scope.
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
     * Loads reactions for a set of message packetIds, grouped by target_packet_id.
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
     * Loads reactions after the given reaction row id, used for real-time Lua callbacks.
     *
     * @param chatType    "channel" or "dm"
     * @param chatKey     channelIndex as string, or peerNodeId
     * @param afterDbId   load reactions with id > afterDbId
     * @param limit       maximum number of reactions; {@code <= 0} means unlimited
     * @param ownerNodeId owner device nodeId
     * @return new reactions in creation order
     */
    public List<MessageReaction> loadReactionsAfter(String chatType,
                                                    String chatKey,
                                                    long afterDbId,
                                                    int limit,
                                                    String ownerNodeId) {
        List<MessageReaction> result = new ArrayList<>();
        if (dbConnection == null) { return result; }
        String sql = limit > 0
                ? "SELECT * FROM message_reactions WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ? AND id > ? ORDER BY id ASC LIMIT ?"
                : "SELECT * FROM message_reactions WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ? AND id > ? ORDER BY id ASC";
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
                    result.add(readReaction(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to loadReactionsAfter({}, {}, {}, {})", chatType, chatKey, afterDbId, limit, e);
        }
        return result;
    }

    /**
     * Returns the newest reaction row id for a chat scope.
     */
    public long latestReactionDbId(String chatType, String chatKey, String ownerNodeId) {
        if (dbConnection == null) { return 0L; }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT MAX(id) FROM message_reactions
                WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ?
                """)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0L, rs.getLong(1)) : 0L;
            }
        } catch (SQLException e) {
            log.error("Failed to get latest reaction id ({}, {}, {})", ownerNodeId, chatType, chatKey, e);
            return 0L;
        }
    }

    /**
     * Returns the list of unique DM peers (chat_key) from the database for the device.
     */
    public List<String> getDistinctDmPeers(String ownerNodeId) {
        Set<String> peers = new LinkedHashSet<>();
        if (dbConnection == null) { return new ArrayList<>(peers); }
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
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT chat_key FROM chat_threads WHERE owner_node_id = ? AND chat_type = 'dm'")) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    peers.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get explicit DM peers", e);
        }
        return new ArrayList<>(peers);
    }

    /**
     * Returns the latest message for each chat_key of the given type and device.
     * Key is chat_key; value is the latest MeshMessage.
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
     * Returns the latest message and unread-eligible total for every populated
     * chat of an owner in one indexed query.
     */
    public Map<String, ChatSummary> getChatSummaries(String ownerNodeId) {
        Map<String, ChatSummary> result = new LinkedHashMap<>();
        if (dbConnection == null) {
            return result;
        }
        String sql = """
                SELECT m.*, summary.unread_eligible_count
                FROM messages m
                INNER JOIN (
                    SELECT chat_type,
                           chat_key,
                           MAX(id) AS max_id,
                           COUNT(CASE WHEN outgoing = FALSE THEN 1 END) AS unread_eligible_count
                    FROM messages
                    WHERE owner_node_id = ?
                    GROUP BY chat_type, chat_key
                ) summary ON m.id = summary.max_id
                ORDER BY m.timestamp DESC
                """;
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MeshMessage lastMessage = readMessage(rs);
                    String chatType = rs.getString("chat_type");
                    String chatKey = rs.getString("chat_key");
                    ChatSummary summary = new ChatSummary(
                            chatType,
                            chatKey,
                            lastMessage,
                            Math.max(0, rs.getInt("unread_eligible_count")));
                    result.put(chatType + "\u0000" + chatKey, summary);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load chat summaries for {}", ownerNodeId, e);
        }
        return result;
    }

    /** Stable map key used by {@link #getChatSummaries(String)}. */
    public static String chatSummaryKey(String chatType, String chatKey) {
        return chatType + "\u0000" + chatKey;
    }

    /**
     * Returns the number of messages in a chat for the given device.
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
     * Returns the number of messages that can count as unread: any non-outgoing
     * messages, including system messages.
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
    // Deletion.
    // ═══════════════════════════════════════════════════════════

    /**
     * Deletes all messages and the read counter for the given chat and device.
     *
     * @param chatType    "channel" or "dm"
     * @param chatKey     channelIndex as string, or peerNodeId
     * @param ownerNodeId owner device nodeId
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
     * Removes an explicit empty-chat marker. "Clear history" keeps this marker,
     * while "delete local chat" removes it.
     */
    public synchronized void deleteChatThread(String chatType, String chatKey, String ownerNodeId) {
        if (dbConnection == null) { return; }
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "DELETE FROM chat_threads WHERE owner_node_id = ? AND chat_type = ? AND chat_key = ?")) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, chatType);
            ps.setString(3, chatKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete chat thread ({}, {})", chatType, chatKey, e);
        }
    }

    /**
     * Deletes one message by its database id.
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
    // Read-message tracking.
    // ═══════════════════════════════════════════════════════════

    /**
     * Saves the read-message count for a chat and device.
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
     * Loads all read-message counters for the device.
     * @return map with "ch:KEY" / "dm:KEY" keys and readCount values
     */
    public Map<String, Integer> loadAllReadCounts(String ownerNodeId) {
        Map<String, Integer> result = new HashMap<>();
        if (dbConnection == null) { return result; }
        List<String[]> normalizations = new ArrayList<>();
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT counts.chat_type,
                       counts.chat_key,
                       counts.read_count,
                       COALESCE(summary.total_count, 0) AS total_count,
                       COALESCE(summary.eligible_count, 0) AS eligible_count
                FROM chat_read_counts counts
                LEFT JOIN (
                    SELECT chat_type,
                           chat_key,
                           COUNT(*) AS total_count,
                           COUNT(CASE WHEN outgoing = FALSE THEN 1 END) AS eligible_count
                    FROM messages
                    WHERE owner_node_id = ?
                    GROUP BY chat_type, chat_key
                ) summary
                  ON counts.chat_type = summary.chat_type
                 AND counts.chat_key = summary.chat_key
                WHERE counts.owner_node_id = ?
                """)) {
            ps.setString(1, ownerNodeId != null ? ownerNodeId : "");
            ps.setString(2, ownerNodeId != null ? ownerNodeId : "");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("chat_type");
                    String key = rs.getString("chat_key");
                    int count = rs.getInt("read_count");
                    int totalMessages = rs.getInt("total_count");
                    int eligibleCount = rs.getInt("eligible_count");
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
     * Counts total unread messages across all chats for the owner.
     *
     * @param ownerNodeId owner device nodeId
     * @return total unread incoming messages
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
    // JSON to H2 migration.
    // ═══════════════════════════════════════════════════════════

    /**
     * Imports messages from JSON files in the MessageHistoryService format into H2.
     * Called once on first startup if {@code ~/.meshapp/history/} exists.
     */
    public void migrateFromJsonHistory() {
        Path historyDir = Path.of(System.getProperty("user.home"), ".meshapp", "history");
        if (!Files.isDirectory(historyDir)) { return; }

        // Check whether data already exists, using the legacy check without ownerNodeId.
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
                        // A DM file contains a hex nodeNum; create NodeData to derive nodeId.
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

            // Rename history to history.bak.
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

    /** Structure of JSON files produced by MessageHistoryService. */
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
    // Utilities.
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

    private static TracerouteResultRecord readTracerouteResult(ResultSet rs) throws SQLException {
        return new TracerouteResultRecord(
                rs.getLong("id"),
                rs.getString("owner_node_id"),
                rs.getString("chat_type"),
                rs.getString("chat_key"),
                rs.getString("source"),
                rs.getString("request_id"),
                rs.getLong("script_id"),
                rs.getLong("target_node_num"),
                rs.getString("target_node_id"),
                rs.getString("target_name"),
                rs.getLong("response_from_node_num"),
                rs.getString("response_from_node_id"),
                rs.getBytes("route_data"),
                rs.getString("formatted_text"),
                rs.getLong("timestamp")
        );
    }

    /**
     * Closes the database connection.
     */
    public void close() {
        closeStatements();
        closeConnection();
        dbConnection = null;
    }

    public synchronized void prepareForDatabaseReset() {
        closeStatements();
        closeConnection();
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

    private void closeConnection() {
        if (dbConnection == null) {
            return;
        }
        try {
            if (!dbConnection.isClosed()) {
                dbConnection.close();
            }
        } catch (SQLException e) {
            log.error("Error closing message DB connection", e);
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
