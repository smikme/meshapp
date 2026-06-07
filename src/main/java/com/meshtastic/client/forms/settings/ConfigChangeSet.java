package com.meshtastic.client.forms.settings;

import java.util.List;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

/**
 * Immutable set of pending configuration editor changes.
 *
 * @param ownerModified   whether owner info changed
 * @param longName        new long name
 * @param shortName       new short name
 * @param isLicensed      new licensed-operator flag
 * @param positionModified whether fixed position changed
 * @param latitude        new latitude
 * @param longitude       new longitude
 * @param altitude        new altitude
 * @param ringtoneModified whether ringtone changed
 * @param ringtone        new RTTTL ringtone
 * @param configs         changed device configs
 * @param moduleConfigs   changed module configs
 * @param channels        changed channels
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record ConfigChangeSet(
    boolean ownerModified,
    String longName,
    String shortName,
    boolean isLicensed,
    boolean positionModified,
    double latitude,
    double longitude,
    int altitude,
    boolean ringtoneModified,
    String ringtone,
    List<ConfigProtos.Config> configs,
    List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
    List<ChannelProtos.Channel> channels
) {
    /**
     * Creates an immutable change set.
     */
    public ConfigChangeSet {
        configs = List.copyOf(configs);
        moduleConfigs = List.copyOf(moduleConfigs);
        channels = List.copyOf(channels);
    }

    /**
     * Checks whether any editor value changed.
     *
     * @return {@code true} when there is at least one change
     */
    public boolean hasChanges() {
        return ownerModified ||
            positionModified ||
            ringtoneModified ||
            !configs.isEmpty() ||
            !moduleConfigs.isEmpty() ||
            !channels.isEmpty();
    }

    /**
     * Counts user-visible changed sections.
     *
     * @return total changed section count
     */
    public int totalChanges() {
        return configs.size() +
            moduleConfigs.size() +
            channels.size() +
            (ownerModified ? 1 : 0) +
            (positionModified ? 1 : 0) +
            (ringtoneModified ? 1 : 0);
    }

    /**
     * Checks whether any protobuf/admin packet section changed.
     *
     * @return {@code true} when packet config changes exist
     */
    public boolean hasPacketConfigChanges() {
        return !channels.isEmpty() || !configs.isEmpty() || !moduleConfigs.isEmpty();
    }

    /**
     * Checks whether these changes require reconnect after save.
     *
     * @return {@code true} when reconnect is required
     */
    public boolean requiresReconnect() {
        return ConfigSavePolicy.requiresReconnect(
            ownerModified,
            configs,
            moduleConfigs
        );
    }
}
