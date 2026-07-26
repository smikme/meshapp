package com.meshtastic.client.utils;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.service.DatabaseProvider;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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

    @TempDir
    Path tempHome;

    private String previousLanguage;
    private String previousUserHome;

    @BeforeEach
    void setUp() {
        previousLanguage = I18n.getLanguageTag();
        previousUserHome = System.getProperty("user.home");
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
        if (previousUserHome != null) {
            System.setProperty("user.home", previousUserHome);
        }
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
        ConfigHelpRepository repository = ConfigHelpRepository.getInstance();

        assertTrue(
            repository.hasArticleForTests(
                I18n.LANGUAGE_EN,
                "section",
                "lora"
            )
        );
        assertTrue(
            repository.hasArticleForTests(
                I18n.LANGUAGE_RU,
                "section",
                "lora"
            )
        );
        assertTrue(
            repository.hasArticleForTests(
                I18n.LANGUAGE_DE,
                "section",
                "lora"
            )
        );
        assertTrue(
            repository
                .articleForTests(
                    I18n.LANGUAGE_EN,
                    "field",
                    "meshtastic.Config.DeviceConfig.role"
                )
                .values()
                .containsKey("CLIENT_BASE")
        );
        assertTrue(
            repository
                .articleForTests(
                    I18n.LANGUAGE_RU,
                    "field",
                    "meshtastic.Config.DeviceConfig.role"
                )
                .summary()
                .contains("Роль определяет")
        );
        assertTrue(
            repository
                .articleForTests(
                    I18n.LANGUAGE_DE,
                    "field",
                    "meshtastic.Config.DeviceConfig.role"
                )
                .summary()
                .contains("Die Rolle bestimmt")
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
                .contains("Vollständiger Gerätename")
        );
        assertTrue(
            repository
                .helpFor(longName, "de-DE")
                .summary()
                .contains("Vollständiger Gerätename")
        );
        assertTrue(
            repository
                .helpFor(longName, "it")
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

        assertHelpDatabaseCovers(
            required,
            repository,
            I18n.LANGUAGE_RU
        );
        assertHelpDatabaseCovers(
            required,
            repository,
            I18n.LANGUAGE_EN
        );
        assertHelpDatabaseCovers(
            required,
            repository,
            I18n.LANGUAGE_DE
        );
    }

    @Test
    void importsHelpDocumentsIntoDatabaseWithVersion() throws Exception {
        ConfigHelpRepository repository = ConfigHelpRepository.getInstance();

        assertEquals(
            2,
            repository.documentVersionForTests(
                I18n.LANGUAGE_EN,
                "config/common"
            )
        );
        assertEquals(
            2,
            repository.documentVersionForTests(
                I18n.LANGUAGE_DE,
                "config/common"
            )
        );

        Connection connection = DatabaseProvider.getConnection();
        assertEquals(
            297,
            countRows(
                connection,
                "config_help_articles",
                "language_tag = 'en' AND article_type = 'field'"
            )
        );
    }

    @Test
    void reimportsHelpWhenDatabaseVersionDiffers() throws Exception {
        ConfigHelpRepository repository = ConfigHelpRepository.getInstance();
        assertTrue(
            repository.hasArticleForTests(
                I18n.LANGUAGE_EN,
                "field",
                "owner_info.long_name"
            )
        );

        Connection connection = DatabaseProvider.getConnection();
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                DELETE FROM config_help_articles
                WHERE language_tag = 'en'
                  AND document_id = 'config/common'
                  AND article_type = 'field'
                  AND article_key = 'owner_info.long_name'
                """);
            stmt.executeUpdate("""
                UPDATE config_help_documents
                SET version = 0
                WHERE language_tag = 'en'
                  AND document_id = 'config/common'
                """);
        }

        repository.resetLoadedStateForTests();

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

        assertTrue(
            repository
                .helpFor(longName, I18n.LANGUAGE_EN)
                .summary()
                .contains("Full device name")
        );
        assertEquals(
            2,
            repository.documentVersionForTests(
                I18n.LANGUAGE_EN,
                "config/common"
            )
        );
        assertTrue(
            repository.hasArticleForTests(
                I18n.LANGUAGE_EN,
                "field",
                "owner_info.long_name"
            )
        );
    }

    @Test
    void searchesHelpArticlesInDatabase() {
        List<ConfigHelpRepository.HelpSearchResult> results =
            ConfigHelpRepository.getInstance().search("buzzer", "en", 10);

        assertTrue(results.stream().anyMatch(result ->
            result.articleType().equals("field") &&
            result.articleKey().equals("meshtastic.Config.DeviceConfig.buzzer_mode")
        ));
    }

    private static void assertHelpDatabaseCovers(
        Set<String> required,
        ConfigHelpRepository repository,
        String language
    ) {
        List<String> missing = required
            .stream()
            .filter(id -> !repository.hasArticleForTests(language, "field", id))
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

    private static int countRows(
        Connection connection,
        String tableName,
        String where
    ) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + tableName + " WHERE " + where);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
