package com.meshtastic.client.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Wrapper around H2's built-in full-text index for the message table.
 * <p>
 * Encapsulates creation of the {@code FT} schema, registration of the
 * {@code FT_INIT} alias, indexing of {@code messages.text}, and lookup of the
 * internal index id. Database migrations use it directly, and
 * {@link MessageDbService} uses it as a runtime guard.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class MessageFullTextIndex {

    private static final String SCHEMA = "PUBLIC";
    private static final String TABLE = "MESSAGES";
    private static final String COLUMNS = "TEXT";

    private MessageFullTextIndex() {}

    /**
     * Initializes H2 full-text support and creates the message index if it is
     * not already registered in {@code FT.INDEXES}.
     *
     * @param connection active H2 database connection
     * @throws SQLException if H2 cannot create the required full-text objects
     */
    static void ensureExists(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE ALIAS IF NOT EXISTS FT_INIT
                    FOR "org.h2.fulltext.FullText.init"
                    """);
            stmt.execute("CALL FT_INIT()");
        }
        if (indexId(connection) == null) {
            create(connection);
        }
    }

    /**
     * Returns the internal full-text index id for {@code PUBLIC.MESSAGES}.
     *
     * @param connection active H2 database connection
     * @return index id, or {@code null} when the index has not been created yet
     * @throws SQLException if the {@code FT.INDEXES} metadata table cannot be read
     */
    static Integer indexId(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT ID FROM FT.INDEXES
                WHERE SCHEMA = ? AND "TABLE" = ?
                LIMIT 1
                """)) {
            ps.setString(1, SCHEMA);
            ps.setString(2, TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("ID") : null;
            }
        }
    }

    /**
     * Creates the H2 full-text index for {@code messages.text}.
     *
     * @param connection active H2 database connection
     * @throws SQLException if H2 rejects index creation
     */
    private static void create(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("CALL FT_CREATE_INDEX(?, ?, ?)")) {
            ps.setString(1, SCHEMA);
            ps.setString(2, TABLE);
            ps.setString(3, COLUMNS);
            ps.execute();
        }
    }
}
