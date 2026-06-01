package com.meshtastic.client.service;

import com.meshtastic.client.lua.LuaScriptRuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates a full reset of the application's local H2 database.
 * <p>
 * Reset sequence:
 * <ol>
 *   <li>active connections are stopped so background database writes cease</li>
 *   <li>live database services release prepared statements and in-memory caches</li>
 *   <li>{@code DROP ALL OBJECTS} is executed</li>
 *   <li>services recreate their tables and JDBC resources</li>
 * </ol>
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class DatabaseResetService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseResetService.class);

    private DatabaseResetService() {}

    public static synchronized void resetAllData() throws Exception {
        log.info("Starting full application database reset");

        LuaScriptRuntimeService.getInstance().stopAll();
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
