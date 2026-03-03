package com.meshtastic.client.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Единственное соединение с H2 embedded БД {@code ~/.meshapp/nodedb}.
 * <p>
 * Используется всеми сервисами ({@link MessageDbService}, {@link NodeCacheService})
 * вместо создания отдельных соединений к одному файлу.
 */
public final class DatabaseProvider {

    private static final Logger log = LoggerFactory.getLogger(DatabaseProvider.class);

    private static Connection connection;

    private DatabaseProvider() {}

    /**
     * Возвращает единственное соединение с БД. Создаёт при первом вызове.
     */
    public static synchronized Connection getConnection() {
        if (connection != null) {
            return connection;
        }
        try {
            Path dbDir = Path.of(System.getProperty("user.home"), ".meshapp");
            Files.createDirectories(dbDir);
            String dbPath = dbDir.resolve("nodedb").toString();

            connection = DriverManager.getConnection("jdbc:h2:" + dbPath + ";AUTO_SERVER=FALSE");
            log.info("Database connection established: {}", dbPath);
            DatabaseMigrator.migrate(connection);
        } catch (Exception e) {
            log.error("Failed to create database connection", e);
        }
        return connection;
    }

    /**
     * Закрывает соединение с БД. Вызывается один раз при завершении приложения,
     * после того как все сервисы закроют свои PreparedStatement.
     */
    public static synchronized void close() {
        if (connection == null) { return; }
        try {
            if (!connection.isClosed()) {
                connection.close();
                log.info("Database connection closed");
            }
        } catch (SQLException e) {
            log.error("Error closing database connection", e);
        }
        connection = null;
    }
}
