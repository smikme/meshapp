package com.meshtastic.client.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Координирует полный сброс локальной H2 БД приложения.
 * <p>
 * Сценарий reset-а:
 * <ol>
 *   <li>останавливаются активные подключения, чтобы прекратить фоновые записи в БД</li>
 *   <li>живые DB-сервисы освобождают PreparedStatement-ы и in-memory кэши</li>
 *   <li>выполняется {@code DROP ALL OBJECTS}</li>
 *   <li>сервисы заново инициализируют таблицы и JDBC-ресурсы</li>
 * </ol>
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class DatabaseResetService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseResetService.class);

    private DatabaseResetService() {}

    public static synchronized void resetAllData() throws Exception {
        log.info("Starting full application database reset");

        ConnectionManager.getInstance().shutdownAll();

        MessageDbService messageDbService = MessageDbService.getIfInitialized();
        NodeCacheService nodeCacheService = NodeCacheService.getIfInitialized();
        PacketMonitorService packetMonitorService = PacketMonitorService.getIfInitialized();

        if (messageDbService != null) {
            messageDbService.prepareForDatabaseReset();
        }
        if (nodeCacheService != null) {
            nodeCacheService.prepareForDatabaseReset();
        }
        if (packetMonitorService != null) {
            packetMonitorService.prepareForDatabaseReset();
        }

        DatabaseProvider.resetDatabase();

        if (messageDbService != null) {
            messageDbService.reinitializeAfterDatabaseReset();
        } else {
            MessageDbService.getInstance();
        }
        if (nodeCacheService != null) {
            nodeCacheService.reinitializeAfterDatabaseReset();
        } else {
            NodeCacheService.getInstance();
        }
        if (packetMonitorService != null) {
            packetMonitorService.reinitializeAfterDatabaseReset();
        } else {
            PacketMonitorService.getInstance();
        }

        log.info("Full application database reset complete");
    }
}
