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

import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.utils.ProtobufTreeBuilder;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javafx.scene.control.TreeItem;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

/**
 * Collects pending editor changes from the configuration tree.
 * The collector converts virtual sections and protobuf-backed sections into a
 * value object consumed by the save flow.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigChangeCollector {

    private ConfigChangeCollector() {}

    /**
     * Collects all modified values from the editor tree.
     *
     * @param root                  editor root
     * @param originalConfigs       original device configs
     * @param originalModuleConfigs original module configs
     * @param modifiedChannels      modified channels
     * @param ownerInfo             current owner info
     * @param myNode                current local node
     * @return pending changes
     */
    public static ConfigChangeSet collect(
        TreeItem<ConfigTreeItem> root,
        List<ConfigProtos.Config> originalConfigs,
        List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs,
        List<ChannelProtos.Channel> modifiedChannels,
        MeshProtos.User ownerInfo,
        NodeData myNode
    ) {
        OwnerChange owner = ownerChange(root, ownerInfo, myNode);
        PositionChange position = positionChange(root);
        RingtoneChange ringtone = ringtoneChange(root);
        return new ConfigChangeSet(
            owner.modified(),
            owner.longName(),
            owner.shortName(),
            owner.isLicensed(),
            position.modified(),
            position.latitude(),
            position.longitude(),
            position.altitude(),
            ringtone.modified(),
            ringtone.value(),
            modifiedConfigs(root, originalConfigs),
            modifiedModuleConfigs(root, originalModuleConfigs),
            modifiedChannels
        );
    }

    private static OwnerChange ownerChange(
        TreeItem<ConfigTreeItem> root,
        MeshProtos.User ownerInfo,
        NodeData myNode
    ) {
        boolean defaultLicensed =
            MeshtasticConfigTreeBuilder.resolveOwnerLicensed(ownerInfo, myNode);
        return ConfigTreeItemSupport
            .findTopLevelSection(root, OWNER_INFO_CONFIG_TYPE)
            .filter(ConfigTreeItemSupport::hasModifiedFields)
            .map(section ->
                new OwnerChange(
                    true,
                    ConfigTreeItemSupport.stringValue(
                        section,
                        OWNER_LONG_NAME_FIELD
                    ),
                    ConfigTreeItemSupport.stringValue(
                        section,
                        OWNER_SHORT_NAME_FIELD
                    ),
                    ConfigTreeItemSupport.booleanValue(
                        section,
                        OWNER_IS_LICENSED_FIELD
                    )
                )
            )
            .orElse(new OwnerChange(false, null, null, defaultLicensed));
    }

    private static PositionChange positionChange(TreeItem<ConfigTreeItem> root) {
        return ConfigTreeItemSupport
            .findTopLevelSection(root, FIXED_POSITION_CONFIG_TYPE)
            .filter(ConfigTreeItemSupport::hasModifiedFields)
            .map(section ->
                new PositionChange(
                    true,
                    ConfigTreeItemSupport.doubleValue(
                        section,
                        FIXED_POSITION_LATITUDE_FIELD
                    ),
                    ConfigTreeItemSupport.doubleValue(
                        section,
                        FIXED_POSITION_LONGITUDE_FIELD
                    ),
                    ConfigTreeItemSupport.intValue(
                        section,
                        FIXED_POSITION_ALTITUDE_FIELD
                    )
                )
            )
            .orElse(PositionChange.empty());
    }

    private static RingtoneChange ringtoneChange(TreeItem<ConfigTreeItem> root) {
        return ConfigTreeItemSupport
            .findTopLevelSection(root, RINGTONE_CONFIG_TYPE)
            .filter(ConfigTreeItemSupport::hasModifiedFields)
            .map(section ->
                new RingtoneChange(
                    true,
                    ConfigTreeItemSupport.stringValue(section, RINGTONE_FIELD)
                )
            )
            .orElse(new RingtoneChange(false, ""));
    }

    private static List<ConfigProtos.Config> modifiedConfigs(
        TreeItem<ConfigTreeItem> root,
        List<ConfigProtos.Config> originalConfigs
    ) {
        return ConfigTreeItemSupport
            .findTopLevelSection(root, CONFIG_ROOT_TYPE)
            .stream()
            .flatMap(section -> section.getChildren().stream())
            .filter(ConfigTreeItemSupport::hasModifiedFields)
            .map(section -> rebuildConfig(section, originalConfigs))
            .flatMap(Optional::stream)
            .toList();
    }

    private static List<ModuleConfigProtos.ModuleConfig> modifiedModuleConfigs(
        TreeItem<ConfigTreeItem> root,
        List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs
    ) {
        return ConfigTreeItemSupport
            .findTopLevelSection(root, MODULE_CONFIG_ROOT_TYPE)
            .stream()
            .flatMap(section -> section.getChildren().stream())
            .filter(ConfigTreeItemSupport::hasModifiedFields)
            .map(section -> rebuildModuleConfig(section, originalModuleConfigs))
            .flatMap(Optional::stream)
            .toList();
    }

    private static Optional<ConfigProtos.Config> rebuildConfig(
        TreeItem<ConfigTreeItem> section,
        List<ConfigProtos.Config> originalConfigs
    ) {
        return Optional
            .ofNullable(section.getValue())
            .flatMap(sectionData ->
                ConfigProtobufSupport.findOriginalConfig(
                    originalConfigs,
                    sectionData.getConfigVariantNumber()
                )
            )
            .map(original -> ProtobufTreeBuilder.rebuildConfig(section, original))
            .filter(Objects::nonNull);
    }

    private static Optional<ModuleConfigProtos.ModuleConfig> rebuildModuleConfig(
        TreeItem<ConfigTreeItem> section,
        List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs
    ) {
        return Optional
            .ofNullable(section.getValue())
            .flatMap(sectionData ->
                ConfigProtobufSupport.findOriginalModuleConfig(
                    originalModuleConfigs,
                    sectionData.getConfigVariantNumber()
                )
            )
            .map(original ->
                ProtobufTreeBuilder.rebuildModuleConfig(section, original)
            )
            .filter(Objects::nonNull);
    }

    private record OwnerChange(
        boolean modified,
        String longName,
        String shortName,
        boolean isLicensed
    ) {}

    private record PositionChange(
        boolean modified,
        double latitude,
        double longitude,
        int altitude
    ) {
        static PositionChange empty() {
            return new PositionChange(false, 0.0, 0.0, 0);
        }
    }

    private record RingtoneChange(boolean modified, String value) {}
}
