package com.meshtastic.client.utils;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies localized configuration help lookup, fallback behavior, and coverage
 * for the configuration rows shown in the UI.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class ConfigDescriptionResolverTest {

    private String previousLanguage;

    @BeforeEach
    void setUp() {
        previousLanguage = I18n.getLanguageTag();
    }

    @AfterEach
    void tearDown() {
        I18n.setLanguageTagForTests(previousLanguage);
    }

    @Test
    void loadsFieldDescriptionsFromProtoResources() {
        I18n.setLanguageTagForTests(I18n.LANGUAGE_EN);
        FieldDescriptor roleField = ConfigProtos.Config.DeviceConfig
            .getDescriptor()
            .findFieldByName("role");
        ConfigTreeItem roleItem = new ConfigTreeItem(
            "Role",
            "role",
            null,
            FieldDescriptor.class,
            null,
            roleField,
            "config",
            1
        );

        String description = ConfigDescriptionResolver.descriptionFor(roleItem);

        assertTrue(description.contains("Config.DeviceConfig.role"));
        assertTrue(description.contains("Normal node"));
        assertTrue(description.contains("CLIENT - Normal node"));
        assertFalse(description.contains("role of node"));
    }

    @Test
    void usesLocalizedVirtualDescriptions() {
        ConfigTreeItem longName = new ConfigTreeItem(
            "Long name",
            "long_name",
            "Alpha",
            String.class,
            null,
            null,
            "owner_info",
            0
        );

        I18n.setLanguageTagForTests(I18n.LANGUAGE_RU);
        assertTrue(
            ConfigDescriptionResolver
                .descriptionFor(longName)
                .contains("Полное имя устройства")
        );

        I18n.setLanguageTagForTests(I18n.LANGUAGE_EN);
        assertTrue(
            ConfigDescriptionResolver
                .descriptionFor(longName)
                .contains("Full device name")
        );
    }

    @Test
    void protobufSectionsKeepDescriptorsAndLocalizedDescriptions() {
        I18n.setLanguageTagForTests(I18n.LANGUAGE_EN);
        ConfigProtos.Config deviceConfig = ConfigProtos.Config.newBuilder()
            .setDevice(ConfigProtos.Config.DeviceConfig.newBuilder().build())
            .build();

        TreeItem<ConfigTreeItem> root = ProtobufTreeBuilder.buildConfigTree(
            List.of(deviceConfig)
        );
        ConfigTreeItem deviceSection = root.getChildren().getFirst().getValue();

        assertEquals("device", deviceSection.getFieldName());
        assertNotNull(deviceSection.getFieldDescriptor());
        assertTrue(
            ConfigDescriptionResolver
                .descriptionFor(deviceSection)
                .contains("Core device behavior on the mesh")
        );
    }

    @Test
    void loadsHelpDocumentsOutsideI18nBundles() {
        ConfigHelpRepository.HelpBundle englishBundle =
            ConfigHelpRepository.getInstance().bundleForTests(I18n.LANGUAGE_EN);
        ConfigHelpRepository.HelpBundle russianBundle =
            ConfigHelpRepository.getInstance().bundleForTests(I18n.LANGUAGE_RU);

        assertTrue(englishBundle.sections().containsKey("lora"));
        assertTrue(russianBundle.sections().containsKey("lora"));
        assertTrue(
            englishBundle
                .fields()
                .get("meshtastic.Config.DeviceConfig.role")
                .values()
                .containsKey("CLIENT_BASE")
        );
        assertTrue(
            russianBundle
                .fields()
                .get("meshtastic.Config.DeviceConfig.role")
                .summary()
                .contains("Роль определяет")
        );
    }

    @Test
    void helpDocumentsUseLanguageFallbackChain() {
        ConfigTreeItem longName = new ConfigTreeItem(
            "Long name",
            "long_name",
            "Alpha",
            String.class,
            null,
            null,
            "owner_info",
            0
        );
        ConfigHelpRepository repository = ConfigHelpRepository.getInstance();

        assertTrue(
            repository
                .helpFor(longName, "ru-RU")
                .summary()
                .contains("Полное имя устройства")
        );
        assertTrue(
            repository
                .helpFor(longName, "en-US")
                .summary()
                .contains("Full device name")
        );
        assertTrue(
            repository
                .helpFor(longName, "de")
                .summary()
                .contains("Full device name")
        );
    }

    @Test
    void enumValuesComeFromLocalizedHelpForCoveredEnum() {
        I18n.setLanguageTagForTests(I18n.LANGUAGE_EN);
        FieldDescriptor rebroadcastModeField =
            ConfigProtos.Config.DeviceConfig
                .getDescriptor()
                .findFieldByName("rebroadcast_mode");
        ConfigTreeItem rebroadcastMode = new ConfigTreeItem(
            "Rebroadcast mode",
            "rebroadcast_mode",
            null,
            FieldDescriptor.class,
            null,
            rebroadcastModeField,
            "config",
            1
        );

        ConfigHelpContent help = ConfigDescriptionResolver.helpFor(
            rebroadcastMode
        );

        assertTrue(help.valueHint().contains("Choose one option"));
        assertTrue(help.values().stream().anyMatch(value ->
            value.value().equals("ALL")
        ));
        assertTrue(help.plainText().contains("Rebroadcast mode"));
        assertTrue(help.plainText().contains("network reach"));
        assertTrue(help.plainText().contains("Restrictive modes"));
        assertTrue(help.technicalDetails().isBlank());
    }

    @Test
    void russianCoveredFieldsDoNotShowEnglishProtoFallback() {
        I18n.setLanguageTagForTests(I18n.LANGUAGE_RU);
        FieldDescriptor rebroadcastModeField =
            ConfigProtos.Config.DeviceConfig
                .getDescriptor()
                .findFieldByName("rebroadcast_mode");
        ConfigTreeItem rebroadcastMode = new ConfigTreeItem(
            "Rebroadcast mode",
            "rebroadcast_mode",
            null,
            FieldDescriptor.class,
            null,
            rebroadcastModeField,
            "config",
            1
        );

        ConfigHelpContent help = ConfigDescriptionResolver.helpFor(
            rebroadcastMode
        );

        assertTrue(help.summary().contains("ретранслировать"));
        assertTrue(help.technicalDetails().isBlank());
        assertFalse(help.plainText().contains("Sets the role of node"));
    }

    @Test
    void booleanHelpExplainsTrueAndFalseValues() {
        I18n.setLanguageTagForTests(I18n.LANGUAGE_EN);
        FieldDescriptor heartbeatField = ConfigProtos.Config.DeviceConfig
            .getDescriptor()
            .findFieldByName("led_heartbeat_disabled");
        ConfigTreeItem heartbeat = new ConfigTreeItem(
            "Led heartbeat disabled",
            "led_heartbeat_disabled",
            false,
            Boolean.class,
            null,
            heartbeatField,
            "config",
            1
        );

        ConfigHelpContent help = ConfigDescriptionResolver.helpFor(heartbeat);

        assertTrue(help.values().stream().anyMatch(value ->
            value.value().equals("true") &&
            value.title().equals("Enabled") &&
            value.description().contains("feature is active")
        ));
        assertTrue(help.values().stream().anyMatch(value ->
            value.value().equals("false") &&
            value.title().equals("Disabled") &&
            value.description().contains("not used")
        ));
    }

    @Test
    void helpDocumentsCoverEveryFilteredConfigurationField() {
        Set<String> required = requiredHelpFieldIds();
        ConfigHelpRepository repository = ConfigHelpRepository.getInstance();

        assertHelpBundleCovers(
            required,
            repository.bundleForTests(I18n.LANGUAGE_RU),
            I18n.LANGUAGE_RU
        );
        assertHelpBundleCovers(
            required,
            repository.bundleForTests(I18n.LANGUAGE_EN),
            I18n.LANGUAGE_EN
        );
    }

    private static void assertHelpBundleCovers(
        Set<String> required,
        ConfigHelpRepository.HelpBundle bundle,
        String language
    ) {
        List<String> missing = required
            .stream()
            .filter(id -> !bundle.fields().containsKey(id))
            .toList();

        assertTrue(
            missing.isEmpty(),
            "Missing " + language + " config help entries: " + missing
        );
    }

    /**
     * Help coverage filter protocol.
     * <p>
     * The help files must cover the same items the UI can show: scalar,
     * enum, bytes, repeated scalar/bytes fields, nested message groups with
     * visible children, virtual owner/fixed-position/ringtone fields, and the
     * read-only MeshCore rows. Repeated message fields and non-visible protobuf
     * service fields are intentionally excluded.
     */
    private static Set<String> requiredHelpFieldIds() {
        Set<String> ids = new LinkedHashSet<>();
        addVirtualFieldIds(ids);
        collectRootConfigFields(ConfigProtos.Config.getDescriptor(), ids);
        collectRootConfigFields(
            ModuleConfigProtos.ModuleConfig.getDescriptor(),
            ids
        );
        return ids;
    }

    private static void addVirtualFieldIds(Set<String> ids) {
        ids.addAll(
            List.of(
                "owner_info.long_name",
                "owner_info.short_name",
                "owner_info.is_licensed",
                "fixed_position.latitude",
                "fixed_position.longitude",
                "fixed_position.altitude",
                "ringtone.ringtone",
                "meshcore_device.device_name",
                "meshcore_device.owner_id",
                "meshcore_device.public_key",
                "meshcore_device.model",
                "meshcore_device.firmware_version",
                "meshcore_device.firmware_build",
                "meshcore_device.protocol_version",
                "meshcore_device.ble_pin",
                "meshcore_radio.tx_power",
                "meshcore_radio.max_tx_power",
                "meshcore_radio.frequency",
                "meshcore_radio.bandwidth",
                "meshcore_radio.spreading_factor",
                "meshcore_radio.coding_rate",
                "meshcore_limits.max_contacts",
                "meshcore_limits.contact_count",
                "meshcore_limits.max_channels",
                "meshcore_limits.battery_mv",
                "meshcore_limits.storage_used",
                "meshcore_limits.storage_total",
                "meshcore_limits.last_error",
                "meshcore_channels.channel"
            )
        );
    }

    private static void collectRootConfigFields(
        com.google.protobuf.Descriptors.Descriptor descriptor,
        Set<String> ids
    ) {
        for (FieldDescriptor field : descriptor.getFields()) {
            if (field.getType() == FieldDescriptor.Type.MESSAGE) {
                collectVisibleFields(field.getMessageType(), ids);
            }
        }
    }

    private static boolean collectVisibleFields(
        com.google.protobuf.Descriptors.Descriptor descriptor,
        Set<String> ids
    ) {
        boolean hasVisibleChildren = false;
        for (FieldDescriptor field : descriptor.getFields()) {
            boolean visible = isVisibleField(field, ids);
            hasVisibleChildren = hasVisibleChildren || visible;
        }
        return hasVisibleChildren;
    }

    private static boolean isVisibleField(
        FieldDescriptor field,
        Set<String> ids
    ) {
        if (field.isRepeated()) {
            if (field.getType() == FieldDescriptor.Type.MESSAGE) {
                return false;
            }
            ids.add(field.getFullName());
            return true;
        }

        if (field.getType() == FieldDescriptor.Type.MESSAGE) {
            boolean hasVisibleChildren = collectVisibleFields(
                field.getMessageType(),
                ids
            );
            if (hasVisibleChildren) {
                ids.add(field.getFullName());
            }
            return hasVisibleChildren;
        }

        ids.add(field.getFullName());
        return true;
    }
}
