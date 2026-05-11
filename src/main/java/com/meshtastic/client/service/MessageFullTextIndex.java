package com.meshtastic.client.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Обертка над штатным полнотекстовым индексом H2 для таблицы сообщений.
 * <p>
 * Инкапсулирует создание схемы {@code FT}, регистрацию алиаса {@code FT_INIT},
 * создание индекса по {@code messages.text} и получение внутреннего id индекса.
 * Используется миграциями БД и {@link MessageDbService} как runtime-страховка.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class MessageFullTextIndex {

    private static final String SCHEMA = "PUBLIC";
    private static final String TABLE = "MESSAGES";
    private static final String COLUMNS = "TEXT";

    private MessageFullTextIndex() {}

    /**
     * Инициализирует H2 full-text подсистему и создает индекс сообщений,
     * если он еще не зарегистрирован в {@code FT.INDEXES}.
     *
     * @param connection активное соединение с H2 БД
     * @throws SQLException если H2 не смог создать full-text объекты
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
     * Возвращает внутренний id full-text индекса для {@code PUBLIC.MESSAGES}.
     *
     * @param connection активное соединение с H2 БД
     * @return id индекса или {@code null}, если индекс еще не создан
     * @throws SQLException если не удалось прочитать служебную таблицу {@code FT.INDEXES}
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
     * Создает H2 full-text индекс по колонке {@code messages.text}.
     *
     * @param connection активное соединение с H2 БД
     * @throws SQLException если H2 отклонил создание индекса
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
