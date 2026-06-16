package com.meshtastic.client.lua;

import org.meshtastic.proto.AdminProtos;

import java.util.List;
import java.util.Map;

/**
 * Parameters for one Lua remote-admin operation.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaRemoteAdminRequest(long scriptId,
                                    String requestId,
                                    String source,
                                    String name,
                                    int targetNodeNum,
                                    String targetNodeId,
                                    String targetName,
                                    Action action,
                                    AdminProtos.AdminMessage.ConfigType configType,
                                    AdminProtos.AdminMessage.ModuleConfigType moduleConfigType,
                                    AdminProtos.AdminMessage.BackupLocation backupLocation,
                                    int delaySeconds,
                                    long epochSeconds,
                                    boolean preserveFavorites,
                                    String longName,
                                    String shortName,
                                    boolean licensed,
                                    boolean ownerSet,
                                    double latitude,
                                    double longitude,
                                    int altitude,
                                    boolean positionSet,
                                    boolean removePosition,
                                    String ringtone,
                                    boolean ringtoneSet,
                                    String cannedMessages,
                                    boolean cannedMessagesSet,
                                    boolean replace,
                                    List<MessagePatch> configs,
                                    List<MessagePatch> moduleConfigs,
                                    List<MessagePatch> channels) {

    /**
     * Remote-admin operation type.
     */
    public enum Action {
        LOAD_CONFIG,
        REQUEST_CONFIG,
        REQUEST_MODULE_CONFIG,
        SAVE_CONFIG,
        REFRESH_STATUS,
        REBOOT,
        SHUTDOWN,
        SYNC_TIME,
        BACKUP,
        RESTORE,
        REMOVE_BACKUP,
        RESET_NODEDB,
        FACTORY_RESET_CONFIG,
        FACTORY_RESET_DEVICE,
        ENTER_DFU_MODE,
        SET_OWNER,
        SET_FIXED_POSITION,
        REMOVE_FIXED_POSITION,
        SET_RINGTONE,
        SET_CANNED_MESSAGES
    }

    /**
     * Protobuf message patch parsed from a Lua table.
     *
     * @param section section or message name
     * @param values field values keyed by protobuf snake_case names
     */
    public record MessagePatch(String section, Map<String, Object> values) {}
}
