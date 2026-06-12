package com.meshtastic.client.forms.settings;

import static com.meshtastic.client.forms.settings.ConfigEditorConstants.CONFIG_ROOT_TYPE;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.FIXED_POSITION_ALTITUDE_FIELD;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.FIXED_POSITION_CONFIG_TYPE;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.FIXED_POSITION_LATITUDE_FIELD;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.FIXED_POSITION_LONGITUDE_FIELD;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.MODULE_CONFIG_ROOT_TYPE;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.OWNER_INFO_CONFIG_TYPE;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.OWNER_IS_LICENSED_FIELD;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.OWNER_LONG_NAME_FIELD;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.OWNER_SHORT_NAME_FIELD;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.RINGTONE_CONFIG_TYPE;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.RINGTONE_FIELD;

import com.google.gson.JsonObject;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.service.ConfigSnapshotService;
import com.meshtastic.client.utils.ProtobufTreeBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import javafx.scene.control.TreeItem;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

/**
 * Snapshot import/export operations for the configuration tree editor.
 * This class owns tree extraction, tree patch application, protobuf rebuilds,
 * and channel comparison/merge logic.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigSnapshotEditor {

    private ConfigSnapshotEditor() {}

    /**
     * Creates a snapshot from the current editor state.
     *
     * @param kind                  snapshot kind
     * @param root                  editor root
     * @param state                 current device state
     * @param originalConfigs       original device configs
     * @param originalModuleConfigs original module configs
     * @param originalChannels      original channels
     * @param workingChannels       current working channels
     * @return snapshot value ready to write
     */
    public static ConfigSnapshotService.ConfigSnapshot createSnapshot(
        ConfigSnapshotService.SnapshotKind kind,
        TreeItem<ConfigTreeItem> root,
        DeviceState state,
        List<ConfigProtos.Config> originalConfigs,
        List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs,
        List<ChannelProtos.Channel> originalChannels,
        List<ChannelProtos.Channel> workingChannels
    ) {
        return ConfigSnapshotService.createSnapshot(
            kind,
            extractOwnerInfo(root),
            extractFixedPosition(root),
            extractRingtone(root, state),
            collectCurrentConfigMessages(root, originalConfigs),
            collectCurrentModuleConfigMessages(root, originalModuleConfigs),
            workingChannelsSnapshot(originalChannels, workingChannels)
        );
    }

    /**
     * Applies a snapshot to the editor tree and returns updated channel state.
     *
     * @param snapshot              imported snapshot
     * @param root                  editor root
     * @param originalConfigs       original device configs
     * @param originalModuleConfigs original module configs
     * @param originalChannels      original channels
     * @param workingChannels       current working channels
     * @return apply result
     */
    public static ApplyResult applySnapshot(
        ConfigSnapshotService.ConfigSnapshot snapshot,
        TreeItem<ConfigTreeItem> root,
        List<ConfigProtos.Config> originalConfigs,
        List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs,
        List<ChannelProtos.Channel> originalChannels,
        List<ChannelProtos.Channel> workingChannels
    ) {
        if (root == null) {
            return new ApplyResult(workingChannelsSnapshot(originalChannels, workingChannels));
        }

        applyOwnerInfo(snapshot.ownerInfo(), root);
        applyFixedPosition(snapshot.fixedPosition(), root);
        applyRingtone(snapshot.ringtone(), root);
        applyConfigSnapshot(snapshot.configs(), root, originalConfigs);
        applyModuleConfigSnapshot(
            snapshot.moduleConfigs(),
            root,
            originalModuleConfigs
        );
        return new ApplyResult(
            applyChannelSnapshot(
                snapshot.channels(),
                originalChannels,
                workingChannels
            )
        );
    }

    /**
     * Checks whether editor values or channels differ from loaded originals.
     *
     * @param root             editor root
     * @param originalChannels original channels
     * @param workingChannels  current working channels
     * @return {@code true} when there are unsaved editor changes
     */
    public static boolean hasPendingEditorChanges(
        TreeItem<ConfigTreeItem> root,
        List<ChannelProtos.Channel> originalChannels,
        List<ChannelProtos.Channel> workingChannels
    ) {
        return (
            (root != null && ConfigTreeItemSupport.hasModifiedFields(root)) ||
            !collectModifiedChannels(originalChannels, workingChannels).isEmpty()
        );
    }

    /**
     * Finds channels that differ from the original channel list.
     *
     * @param originalChannels original channels
     * @param workingChannels  current working channels
     * @return changed channels sorted by index
     */
    public static List<ChannelProtos.Channel> collectModifiedChannels(
        List<ChannelProtos.Channel> originalChannels,
        List<ChannelProtos.Channel> workingChannels
    ) {
        List<ChannelProtos.Channel> originals = safeChannels(originalChannels);
        List<ChannelProtos.Channel> targets = workingChannelsSnapshot(
            originals,
            workingChannels
        );
        return java.util.stream.Stream
            .concat(originals.stream(), targets.stream())
            .map(ChannelProtos.Channel::getIndex)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
            .stream()
            .map(index -> modifiedChannelAt(index, originals, targets))
            .flatMap(Optional::stream)
            .sorted(Comparator.comparingInt(ChannelProtos.Channel::getIndex))
            .toList();
    }

    /**
     * Returns the effective channel list used by snapshot export/save.
     *
     * @param originalChannels original channels
     * @param workingChannels  current working channels
     * @return mutable effective channel snapshot
     */
    public static List<ChannelProtos.Channel> workingChannelsSnapshot(
        List<ChannelProtos.Channel> originalChannels,
        List<ChannelProtos.Channel> workingChannels
    ) {
        List<ChannelProtos.Channel> source = safeChannels(workingChannels)
            .isEmpty()
            ? safeChannels(originalChannels)
            : safeChannels(workingChannels);
        return new ArrayList<>(source);
    }

    private static ConfigSnapshotService.OwnerInfo extractOwnerInfo(
        TreeItem<ConfigTreeItem> root
    ) {
        return ConfigTreeItemSupport
            .findTopLevelSection(root, OWNER_INFO_CONFIG_TYPE)
            .map(ownerSection ->
                new ConfigSnapshotService.OwnerInfo(
                    ConfigTreeItemSupport.stringValue(
                        ownerSection,
                        OWNER_LONG_NAME_FIELD
                    ),
                    ConfigTreeItemSupport.stringValue(
                        ownerSection,
                        OWNER_SHORT_NAME_FIELD
                    ),
                    ConfigTreeItemSupport.booleanValue(
                        ownerSection,
                        OWNER_IS_LICENSED_FIELD
                    )
                )
            )
            .orElse(null);
    }

    private static ConfigSnapshotService.FixedPosition extractFixedPosition(
        TreeItem<ConfigTreeItem> root
    ) {
        return ConfigTreeItemSupport
            .findTopLevelSection(root, FIXED_POSITION_CONFIG_TYPE)
            .map(positionSection ->
                new ConfigSnapshotService.FixedPosition(
                    ConfigTreeItemSupport.doubleValue(
                        positionSection,
                        FIXED_POSITION_LATITUDE_FIELD
                    ),
                    ConfigTreeItemSupport.doubleValue(
                        positionSection,
                        FIXED_POSITION_LONGITUDE_FIELD
                    ),
                    ConfigTreeItemSupport.intValue(
                        positionSection,
                        FIXED_POSITION_ALTITUDE_FIELD
                    )
                )
            )
            .orElse(null);
    }

    private static String extractRingtone(
        TreeItem<ConfigTreeItem> root,
        DeviceState state
    ) {
        return ConfigTreeItemSupport
            .findTopLevelSection(root, RINGTONE_CONFIG_TYPE)
            .filter(section ->
                state == null ||
                state.isRingtoneLoaded() ||
                ConfigTreeItemSupport.hasModifiedFields(section)
            )
            .map(section ->
                ConfigTreeItemSupport.stringValue(section, RINGTONE_FIELD)
            )
            .orElse(null);
    }

    private static List<ConfigProtos.Config> collectCurrentConfigMessages(
        TreeItem<ConfigTreeItem> root,
        List<ConfigProtos.Config> originalConfigs
    ) {
        return ConfigTreeItemSupport
            .findTopLevelSection(root, CONFIG_ROOT_TYPE)
            .stream()
            .flatMap(section -> section.getChildren().stream())
            .map(section -> rebuildCurrentConfig(section, originalConfigs))
            .flatMap(Optional::stream)
            .toList();
    }

    private static List<ModuleConfigProtos.ModuleConfig> collectCurrentModuleConfigMessages(
        TreeItem<ConfigTreeItem> root,
        List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs
    ) {
        return ConfigTreeItemSupport
            .findTopLevelSection(root, MODULE_CONFIG_ROOT_TYPE)
            .stream()
            .flatMap(section -> section.getChildren().stream())
            .map(section ->
                rebuildCurrentModuleConfig(section, originalModuleConfigs)
            )
            .flatMap(Optional::stream)
            .toList();
    }

    private static Optional<ConfigProtos.Config> rebuildCurrentConfig(
        TreeItem<ConfigTreeItem> section,
        List<ConfigProtos.Config> originalConfigs
    ) {
        return Optional
            .ofNullable(section.getValue())
            .map(ConfigTreeItem::getConfigVariantNumber)
            .flatMap(variant ->
                ConfigProtobufSupport.findOrCreateConfig(
                    originalConfigs,
                    variant
                )
            )
            .map(original -> ProtobufTreeBuilder.rebuildConfig(section, original));
    }

    private static Optional<ModuleConfigProtos.ModuleConfig> rebuildCurrentModuleConfig(
        TreeItem<ConfigTreeItem> section,
        List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs
    ) {
        return Optional
            .ofNullable(section.getValue())
            .map(ConfigTreeItem::getConfigVariantNumber)
            .flatMap(variant ->
                ConfigProtobufSupport.findOrCreateModuleConfig(
                    originalModuleConfigs,
                    variant
                )
            )
            .map(original ->
                ProtobufTreeBuilder.rebuildModuleConfig(section, original)
            );
    }

    private static void applyOwnerInfo(
        ConfigSnapshotService.OwnerInfo ownerInfo,
        TreeItem<ConfigTreeItem> root
    ) {
        if (ownerInfo == null) {
            return;
        }
        ConfigTreeItemSupport
            .findTopLevelSection(root, OWNER_INFO_CONFIG_TYPE)
            .ifPresent(ownerSection -> {
                ConfigTreeItemSupport.setFieldValue(
                    ownerSection,
                    OWNER_LONG_NAME_FIELD,
                    ownerInfo.longName()
                );
                ConfigTreeItemSupport.setFieldValue(
                    ownerSection,
                    OWNER_SHORT_NAME_FIELD,
                    ownerInfo.shortName()
                );
                ConfigTreeItemSupport.setFieldValue(
                    ownerSection,
                    OWNER_IS_LICENSED_FIELD,
                    ownerInfo.isLicensed()
                );
            });
    }

    private static void applyFixedPosition(
        ConfigSnapshotService.FixedPosition fixedPosition,
        TreeItem<ConfigTreeItem> root
    ) {
        if (fixedPosition == null) {
            return;
        }
        ConfigTreeItemSupport
            .findTopLevelSection(root, FIXED_POSITION_CONFIG_TYPE)
            .ifPresent(positionSection -> {
                ConfigTreeItemSupport.setFieldValue(
                    positionSection,
                    FIXED_POSITION_LATITUDE_FIELD,
                    fixedPosition.latitude()
                );
                ConfigTreeItemSupport.setFieldValue(
                    positionSection,
                    FIXED_POSITION_LONGITUDE_FIELD,
                    fixedPosition.longitude()
                );
                ConfigTreeItemSupport.setFieldValue(
                    positionSection,
                    FIXED_POSITION_ALTITUDE_FIELD,
                    fixedPosition.altitude()
                );
            });
    }

    private static void applyRingtone(
        String ringtone,
        TreeItem<ConfigTreeItem> root
    ) {
        if (ringtone == null) {
            return;
        }
        ConfigTreeItemSupport
            .findTopLevelSection(root, RINGTONE_CONFIG_TYPE)
            .ifPresent(section ->
                ConfigTreeItemSupport.setFieldValue(
                    section,
                    RINGTONE_FIELD,
                    ringtone
                )
            );
    }

    private static void applyConfigSnapshot(
        List<JsonObject> configs,
        TreeItem<ConfigTreeItem> root,
        List<ConfigProtos.Config> originalConfigs
    ) {
        ConfigTreeItemSupport
            .findTopLevelSection(root, CONFIG_ROOT_TYPE)
            .ifPresent(configRoot ->
                configs.forEach(configJson ->
                    applyConfigPatch(configRoot, configJson, originalConfigs)
                )
            );
    }

    private static void applyModuleConfigSnapshot(
        List<JsonObject> moduleConfigs,
        TreeItem<ConfigTreeItem> root,
        List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs
    ) {
        ConfigTreeItemSupport
            .findTopLevelSection(root, MODULE_CONFIG_ROOT_TYPE)
            .ifPresent(moduleRoot ->
                moduleConfigs.forEach(moduleJson ->
                    applyModuleConfigPatch(
                        moduleRoot,
                        moduleJson,
                        originalModuleConfigs
                    )
                )
            );
    }

    private static void applyConfigPatch(
        TreeItem<ConfigTreeItem> configRoot,
        JsonObject configJson,
        List<ConfigProtos.Config> originalConfigs
    ) {
        resolveConfigVariantNumber(configJson).ifPresent(variantNumber -> {
            ConfigProtos.Config baseConfig = ConfigProtobufSupport
                .findOriginalConfig(originalConfigs, variantNumber)
                .orElse(ConfigProtos.Config.getDefaultInstance());
            ConfigProtos.Config mergedConfig =
                ConfigSnapshotService.mergeJsonIntoMessage(
                    baseConfig,
                    configJson
                );
            ConfigTreeItemSupport
                .findSectionByVariant(configRoot, variantNumber)
                .flatMap(section ->
                    ConfigProtobufSupport
                        .activeConfigPayload(mergedConfig)
                        .map(payload -> new ConfigPatchTarget(section, payload))
                )
                .ifPresent(target ->
                    ProtobufTreeBuilder.applyMessageToTree(
                        target.section(),
                        target.payload()
                    )
                );
        });
    }

    private static void applyModuleConfigPatch(
        TreeItem<ConfigTreeItem> moduleRoot,
        JsonObject moduleJson,
        List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs
    ) {
        resolveModuleVariantNumber(moduleJson).ifPresent(variantNumber -> {
            ModuleConfigProtos.ModuleConfig baseConfig = ConfigProtobufSupport
                .findOriginalModuleConfig(
                    originalModuleConfigs,
                    variantNumber
                )
                .orElse(ModuleConfigProtos.ModuleConfig.getDefaultInstance());
            ModuleConfigProtos.ModuleConfig mergedConfig =
                ConfigSnapshotService.mergeJsonIntoMessage(
                    baseConfig,
                    moduleJson
                );
            ConfigTreeItemSupport
                .findSectionByVariant(moduleRoot, variantNumber)
                .flatMap(section ->
                    ConfigProtobufSupport
                        .activeModulePayload(mergedConfig)
                        .map(payload -> new ConfigPatchTarget(section, payload))
                )
                .ifPresent(target ->
                    ProtobufTreeBuilder.applyMessageToTree(
                        target.section(),
                        target.payload()
                    )
                );
        });
    }

    private static Optional<Integer> resolveConfigVariantNumber(
        JsonObject configJson
    ) {
        return Optional
            .ofNullable(ConfigSnapshotService.detectActiveVariantField(configJson))
            .map(ConfigProtos.Config.getDescriptor()::findFieldByName)
            .map(ConfigSnapshotEditor::resolveVariantNumber)
            .filter(variantNumber -> variantNumber >= 0);
    }

    private static Optional<Integer> resolveModuleVariantNumber(
        JsonObject moduleJson
    ) {
        return Optional
            .ofNullable(ConfigSnapshotService.detectActiveVariantField(moduleJson))
            .map(ModuleConfigProtos.ModuleConfig.getDescriptor()::findFieldByName)
            .map(ConfigSnapshotEditor::resolveVariantNumber)
            .filter(variantNumber -> variantNumber >= 0);
    }

    private static int resolveVariantNumber(FieldDescriptor fieldDescriptor) {
        return fieldDescriptor != null ? fieldDescriptor.getNumber() : -1;
    }

    private static List<ChannelProtos.Channel> applyChannelSnapshot(
        List<JsonObject> channelPatches,
        List<ChannelProtos.Channel> originalChannels,
        List<ChannelProtos.Channel> workingChannels
    ) {
        if (channelPatches == null || channelPatches.isEmpty()) {
            return workingChannelsSnapshot(originalChannels, workingChannels);
        }

        return channelPatches
            .stream()
            .filter(channelJson -> channelJson.has("index"))
            .map(channelJson -> mergeChannelPatch(channelJson, originalChannels))
            .sorted(Comparator.comparingInt(ChannelProtos.Channel::getIndex))
            .toList();
    }

    private static ChannelProtos.Channel mergeChannelPatch(
        JsonObject channelJson,
        List<ChannelProtos.Channel> originalChannels
    ) {
        int channelIndex = channelJson.get("index").getAsInt();
        ChannelProtos.Channel baseChannel = ConfigProtobufSupport
            .findChannelByIndex(originalChannels, channelIndex)
            .orElseGet(() ->
                ConfigProtobufSupport.disabledChannel(channelIndex)
            );
        return ConfigSnapshotService.mergeJsonIntoMessage(
            baseChannel,
            channelJson
        );
    }

    private static Optional<ChannelProtos.Channel> modifiedChannelAt(
        int index,
        List<ChannelProtos.Channel> originalChannels,
        List<ChannelProtos.Channel> targetChannels
    ) {
        ChannelProtos.Channel originalNormalized = ConfigProtobufSupport
            .findChannelByIndex(originalChannels, index)
            .orElseGet(() -> ConfigProtobufSupport.disabledChannel(index));
        ChannelProtos.Channel targetNormalized = ConfigProtobufSupport
            .findChannelByIndex(targetChannels, index)
            .orElseGet(() -> ConfigProtobufSupport.disabledChannel(index));
        return originalNormalized.equals(targetNormalized)
            ? Optional.empty()
            : Optional.of(targetNormalized);
    }

    private static List<ChannelProtos.Channel> safeChannels(
        List<ChannelProtos.Channel> channels
    ) {
        return Optional.ofNullable(channels).orElse(List.of());
    }

    /**
     * Result of applying snapshot data to the editor.
     *
     * @param workingChannels updated working channels
     */
    public record ApplyResult(List<ChannelProtos.Channel> workingChannels) {
        public ApplyResult {
            workingChannels = List.copyOf(workingChannels);
        }
    }

    private record ConfigPatchTarget(
        TreeItem<ConfigTreeItem> section,
        Message payload
    ) {}
}
