package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionState;
import java.util.List;
import java.util.Optional;
import javafx.scene.control.TreeItem;
import org.meshtastic.proto.ChannelProtos;

/**
 * Builds the read-only settings tree for MeshCore Companion Protocol.
 * MeshCore Companion does not expose Meshtastic Admin protobuf configuration,
 * so this tree contains available metadata, radio parameters, storage, and
 * channel summaries.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshCoreSettingsTreeBuilder {

    private MeshCoreSettingsTreeBuilder() {}

    /**
     * Builds a read-only tree for MeshCore state.
     *
     * @param meshCoreState MeshCore runtime state
     * @param channels      known channel list
     * @return hidden-root settings tree
     */
    public static TreeItem<ConfigTreeItem> build(
        MeshCoreCompanionState meshCoreState,
        List<ChannelProtos.Channel> channels
    ) {
        TreeItem<ConfigTreeItem> root = section(
            I18n.t("settings.config.root"),
            null
        );
        root.setExpanded(true);
        root
            .getChildren()
            .addAll(List.of(
                deviceSection(meshCoreState),
                radioSection(meshCoreState),
                limitsSection(meshCoreState),
                channelsSection(channels)
            ));
        return root;
    }

    private static TreeItem<ConfigTreeItem> deviceSection(
        MeshCoreCompanionState state
    ) {
        TreeItem<ConfigTreeItem> section = section(
            I18n.t("settings.meshCore.section.device"),
            "meshcore_device"
        );
        addValue(section, I18n.t("settings.meshCore.field.name"), "device_name", valueOrDash(state.getDeviceName()));
        addValue(section, I18n.t("settings.meshCore.field.ownerId"), "owner_id", valueOrDash(state.getOwnerId()));
        addValue(section, I18n.t("settings.meshCore.field.publicKey"), "public_key", valueOrDash(state.getPublicKeyHex()));
        addValue(section, I18n.t("settings.meshCore.field.model"), "model", valueOrDash(state.getModel()));
        addValue(section, I18n.t("settings.meshCore.field.firmware"), "firmware_version", valueOrDash(state.getFirmwareVersion()));
        addValue(section, I18n.t("settings.meshCore.field.build"), "firmware_build", valueOrDash(state.getFirmwareBuild()));
        addValue(section, I18n.t("settings.meshCore.field.protocolVersion"), "protocol_version", valueOrDash(state.getFirmwareProtocolVersion()));
        addValue(section, I18n.t("settings.meshCore.field.blePin"), "ble_pin", valueOrDash(state.getBlePin()));
        return section;
    }

    private static TreeItem<ConfigTreeItem> radioSection(
        MeshCoreCompanionState state
    ) {
        TreeItem<ConfigTreeItem> section = section(
            I18n.t("settings.meshCore.section.radio"),
            "meshcore_radio"
        );
        addValue(section, I18n.t("settings.meshCore.field.txPower"), "tx_power", valueOrDash(state.getTxPowerDbm()));
        addValue(section, I18n.t("settings.meshCore.field.maxTxPower"), "max_tx_power", valueOrDash(state.getMaxTxPowerDbm()));
        addValue(section, I18n.t("settings.meshCore.field.frequency"), "frequency", valueOrDash(state.getRadioFrequencyKhz()));
        addValue(section, I18n.t("settings.meshCore.field.bandwidth"), "bandwidth", valueOrDash(state.getRadioBandwidthKhz()));
        addValue(section, I18n.t("settings.meshCore.field.spreadingFactor"), "spreading_factor", valueOrDash(state.getRadioSpreadingFactor()));
        addValue(section, I18n.t("settings.meshCore.field.codingRate"), "coding_rate", valueOrDash(state.getRadioCodingRate()));
        return section;
    }

    private static TreeItem<ConfigTreeItem> limitsSection(
        MeshCoreCompanionState state
    ) {
        TreeItem<ConfigTreeItem> section = section(
            I18n.t("settings.meshCore.section.data"),
            "meshcore_limits"
        );
        addValue(section, I18n.t("settings.meshCore.field.maxContacts"), "max_contacts", valueOrDash(state.getMaxContacts()));
        addValue(section, I18n.t("settings.meshCore.field.contactCount"), "contact_count", valueOrDash(state.getContactCount()));
        addValue(section, I18n.t("settings.meshCore.field.maxChannels"), "max_channels", valueOrDash(state.getMaxChannels()));
        addValue(section, I18n.t("settings.meshCore.field.battery"), "battery_mv", valueOrDash(state.getBatteryMillivolts()));
        addValue(section, I18n.t("settings.meshCore.field.storageUsed"), "storage_used", valueOrDash(state.getUsedStorageKb()));
        addValue(section, I18n.t("settings.meshCore.field.storageTotal"), "storage_total", valueOrDash(state.getTotalStorageKb()));
        addValue(section, I18n.t("settings.meshCore.field.lastError"), "last_error", valueOrDash(state.getLastError()));
        return section;
    }

    private static TreeItem<ConfigTreeItem> channelsSection(
        List<ChannelProtos.Channel> channels
    ) {
        TreeItem<ConfigTreeItem> section = section(
            I18n.t("settings.meshCore.section.channels"),
            "meshcore_channels"
        );
        Optional
            .ofNullable(channels)
            .stream()
            .flatMap(List::stream)
            .forEach(channel ->
                addValue(
                    section,
                    I18n.t("settings.meshCore.channel", channel.getIndex()),
                    "channel_" + channel.getIndex(),
                    channelLabel(channel)
                )
            );
        return section;
    }

    private static TreeItem<ConfigTreeItem> section(
        String name,
        String configType
    ) {
        return new TreeItem<>(new ConfigTreeItem(name, configType, 0));
    }

    private static void addValue(
        TreeItem<ConfigTreeItem> section,
        String name,
        String fieldName,
        Object value
    ) {
        section
            .getChildren()
            .add(
                new TreeItem<>(
                    new ConfigTreeItem(
                        name,
                        fieldName,
                        value,
                        String.class,
                        null,
                        null,
                        section.getValue().getConfigType(),
                        0
                    )
                )
            );
    }

    private static String channelLabel(ChannelProtos.Channel channel) {
        String fallback = I18n.t(
            "settings.meshCore.channelFallback",
            channel.getIndex()
        );
        String name = Optional
            .of(channel)
            .filter(ChannelProtos.Channel::hasSettings)
            .map(ChannelProtos.Channel::getSettings)
            .map(ChannelProtos.ChannelSettings::getName)
            .filter(value -> !value.isBlank())
            .orElse(fallback);
        return name + " (" + channel.getRole().name() + ")";
    }

    private static String valueOrDash(Object value) {
        return Optional
            .ofNullable(value)
            .map(String::valueOf)
            .filter(text -> !text.isBlank())
            .orElse("\u2014");
    }
}
