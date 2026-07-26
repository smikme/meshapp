package com.meshtastic.client.forms.settings;

import static com.meshtastic.client.forms.settings.ConfigEditorConstants.FIXED_POSITION_ALTITUDE_FIELD;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.FIXED_POSITION_CONFIG_TYPE;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.FIXED_POSITION_LATITUDE_FIELD;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.FIXED_POSITION_LONGITUDE_FIELD;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.OWNER_INFO_CONFIG_TYPE;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.OWNER_IS_LICENSED_FIELD;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.OWNER_LONG_NAME_FIELD;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.OWNER_SHORT_NAME_FIELD;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.RINGTONE_CONFIG_TYPE;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.RINGTONE_FIELD;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.utils.ProtobufTreeBuilder;
import com.meshtastic.client.utils.MeshtasticConfigCompatibility;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javafx.scene.control.TreeItem;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

/**
 * Builds the editable Meshtastic configuration tree.
 * Virtual editor sections are created here while protobuf-backed sections are
 * delegated to {@link ProtobufTreeBuilder}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshtasticConfigTreeBuilder {

    private MeshtasticConfigTreeBuilder() {}

    /**
     * Builds the hidden-root tree used by the configuration editor.
     *
     * @param state         current device state
     * @param myNode        local node data
     * @param configs       device configs
     * @param moduleConfigs module configs
     * @return hidden root tree item
     */
    public static TreeItem<ConfigTreeItem> build(
        DeviceState state,
        NodeData myNode,
        List<ConfigProtos.Config> configs,
        List<ModuleConfigProtos.ModuleConfig> moduleConfigs
    ) {
        TreeItem<ConfigTreeItem> root = section(
            I18n.t("settings.config.root"),
            null
        );
        root.setExpanded(true);

        root
            .getChildren()
            .addAll(List.of(
                ownerSection(state.getOwnerInfo(), myNode),
                fixedPositionSection(myNode),
                ringtoneSection(state)
            ));

        MeshtasticConfigCompatibility.Context compatibility =
            new MeshtasticConfigCompatibility.Context(
                state.getFirmwareCapabilities(),
                state.getRegionPresetMap(),
                MeshtasticConfigCompatibility.currentRegionValue(configs)
            );

        if (!configs.isEmpty()) {
            root.getChildren().add(
                ProtobufTreeBuilder.buildConfigTree(configs, compatibility)
            );
        }
        if (!moduleConfigs.isEmpty()) {
            root
                .getChildren()
                .add(
                    ProtobufTreeBuilder.buildModuleConfigTree(
                        moduleConfigs,
                        compatibility
                    )
                );
        }
        return root;
    }

    /**
     * Resolves editable owner long name from current owner info or node cache.
     *
     * @param ownerInfo owner info protobuf
     * @param myNode    local node data
     * @return display/edit value
     */
    public static String resolveOwnerLongName(
        MeshProtos.User ownerInfo,
        NodeData myNode
    ) {
        return firstNonBlank(
            ownerInfo == null ? null : ownerInfo.getLongName(),
            myNode == null ? null : myNode.getLongName()
        );
    }

    /**
     * Resolves editable owner short name from current owner info or node cache.
     *
     * @param ownerInfo owner info protobuf
     * @param myNode    local node data
     * @return display/edit value
     */
    public static String resolveOwnerShortName(
        MeshProtos.User ownerInfo,
        NodeData myNode
    ) {
        return firstNonBlank(
            ownerInfo == null ? null : ownerInfo.getShortName(),
            myNode == null ? null : myNode.getShortName()
        );
    }

    /**
     * Resolves licensed-operator flag from owner info or node cache.
     *
     * @param ownerInfo owner info protobuf
     * @param myNode    local node data
     * @return current licensed-operator value
     */
    public static boolean resolveOwnerLicensed(
        MeshProtos.User ownerInfo,
        NodeData myNode
    ) {
        return Optional
            .ofNullable(ownerInfo)
            .map(MeshProtos.User::getIsLicensed)
            .orElseGet(() -> myNode != null && myNode.isLicensed());
    }

    private static TreeItem<ConfigTreeItem> ownerSection(
        MeshProtos.User ownerInfo,
        NodeData myNode
    ) {
        TreeItem<ConfigTreeItem> section = section(
            I18n.t("settings.config.ownerInfo"),
            OWNER_INFO_CONFIG_TYPE
        );
        section
            .getChildren()
            .addAll(List.of(
                editableField(
                    I18n.t("settings.config.ownerLongName"),
                    OWNER_LONG_NAME_FIELD,
                    resolveOwnerLongName(ownerInfo, myNode),
                    String.class,
                    OWNER_INFO_CONFIG_TYPE
                ),
                editableField(
                    I18n.t("settings.config.ownerShortName"),
                    OWNER_SHORT_NAME_FIELD,
                    resolveOwnerShortName(ownerInfo, myNode),
                    String.class,
                    OWNER_INFO_CONFIG_TYPE
                ),
                editableField(
                    I18n.t("settings.config.licensedOperator"),
                    OWNER_IS_LICENSED_FIELD,
                    resolveOwnerLicensed(ownerInfo, myNode),
                    Boolean.class,
                    OWNER_INFO_CONFIG_TYPE
                )
            ));
        return section;
    }

    private static TreeItem<ConfigTreeItem> fixedPositionSection(NodeData myNode) {
        TreeItem<ConfigTreeItem> section = section(
            I18n.t("settings.config.fixedPosition"),
            FIXED_POSITION_CONFIG_TYPE
        );
        section
            .getChildren()
            .addAll(List.of(
                editableField(
                    I18n.t("settings.config.latitude"),
                    FIXED_POSITION_LATITUDE_FIELD,
                    myNode == null ? 0.0 : myNode.getLatitude(),
                    Double.class,
                    FIXED_POSITION_CONFIG_TYPE
                ),
                editableField(
                    I18n.t("settings.config.longitude"),
                    FIXED_POSITION_LONGITUDE_FIELD,
                    myNode == null ? 0.0 : myNode.getLongitude(),
                    Double.class,
                    FIXED_POSITION_CONFIG_TYPE
                ),
                editableField(
                    I18n.t("settings.config.altitudeMeters"),
                    FIXED_POSITION_ALTITUDE_FIELD,
                    myNode == null ? 0 : myNode.getAltitude(),
                    Integer.class,
                    FIXED_POSITION_CONFIG_TYPE
                )
            ));
        return section;
    }

    private static TreeItem<ConfigTreeItem> ringtoneSection(DeviceState state) {
        TreeItem<ConfigTreeItem> section = section(
            I18n.t("settings.config.ringtone"),
            RINGTONE_CONFIG_TYPE
        );
        section
            .getChildren()
            .add(
                editableField(
                    "RTTTL",
                    RINGTONE_FIELD,
                    state.isRingtoneLoaded() ? state.getRingtone() : "",
                    String.class,
                    RINGTONE_CONFIG_TYPE
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

    private static TreeItem<ConfigTreeItem> editableField(
        String name,
        String fieldName,
        Object value,
        Class<?> valueType,
        String configType
    ) {
        return new TreeItem<>(
            new ConfigTreeItem(
                name,
                fieldName,
                value,
                valueType,
                null,
                null,
                configType,
                0
            )
        );
    }

    private static String firstNonBlank(String first, String second) {
        return Stream
            .of(first, second)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse("");
    }
}
