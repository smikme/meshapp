package com.meshtastic.client.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.service.DatabaseMigrator;
import com.meshtastic.client.service.DatabaseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves localized configuration help from the local H2 database.
 * <p>
 * Bundled JSON files are parsed only during import. Each help request performs
 * a database lookup for the specific section, field, or type article it needs,
 * avoiding a process-wide in-memory copy of the help documentation.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigHelpRepository {

    private static final Logger log = LoggerFactory.getLogger(ConfigHelpRepository.class);
    private static final ConfigHelpRepository INSTANCE =
        new ConfigHelpRepository();
    private static final Gson GSON = new Gson();
    private static final String FALLBACK_LANGUAGE = I18n.LANGUAGE_EN;
    private static final List<String> HELP_FILES = List.of("common.json");

    private final Object importLock = new Object();
    private volatile boolean bundledDocumentsVerified;

    /**
     * Returns the shared repository instance.
     *
     * @return singleton repository used by configuration UI code
     */
    public static ConfigHelpRepository getInstance() {
        return INSTANCE;
    }

    /**
     * Marks bundled help documents as needing another version check.
     * <p>
     * Used after a full database reset. This flag does not contain help text.
     */
    public void invalidateLoadedState() {
        bundledDocumentsVerified = false;
    }

    /**
     * Builds localized help content for a configuration tree item.
     *
     * @param item configuration tree item to describe; may be {@code null}
     * @return normalized help content with JSON, type, and protobuf fallback data
     */
    public ConfigHelpContent helpFor(ConfigTreeItem item) {
        return helpFor(item, activeLanguage());
    }

    ConfigHelpContent helpFor(ConfigTreeItem item, String language) {
        ensureBundledDocumentsLoaded();
        if (item == null) {
            return new ConfigHelpContent(
                "",
                "",
                "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                ""
            );
        }

        List<String> languageCandidates = languageCandidates(language);
        FieldDescriptor fieldDescriptor = item.getFieldDescriptor();
        boolean category = item.isCategory();

        HelpEntry entry = findEntry(
            item,
            fieldDescriptor,
            category,
            languageCandidates
        );
        HelpEntry typeEntry = typeEntry(
            item,
            fieldDescriptor,
            languageCandidates
        );

        String title = item.getName();
        String path = pathFor(item, fieldDescriptor);
        String technicalDetails = fieldDescriptor != null
            ? ConfigDescriptionResolver.protoDescription(
                fieldDescriptor.getFullName()
            )
            : "";

        String summary = firstText(
            entry != null ? entry.summary() : "",
            category
                ? firstText(
                    typeEntry != null ? typeEntry.summary() : "",
                    I18n.t("settings.config.help.categoryFallback", title)
                )
                : firstText(
                    ConfigDescriptionResolver.localizedProtoDescription(
                        fieldDescriptor
                    ),
                    technicalDetails,
                    typeEntry != null ? typeEntry.summary() : "",
                    I18n.t(
                        "settings.config.help.fieldFallback",
                        title,
                        item.getFieldName() != null
                            ? item.getFieldName()
                            : title
                    )
                )
        );

        String whenToUse = firstText(
            entry != null ? entry.whenToUse() : "",
            typeEntry != null ? typeEntry.whenToUse() : ""
        );
        String defaultBehavior = firstText(
            entry != null ? entry.defaultBehavior() : "",
            typeEntry != null ? typeEntry.defaultBehavior() : ""
        );
        String valueHint = firstText(
            entry != null ? entry.valueHint() : "",
            valueHintFor(item, fieldDescriptor, typeEntry)
        );

        List<ConfigHelpContent.ValueHelp> values = valuesFor(
            fieldDescriptor,
            entry,
            typeEntry,
            entry == null
        );
        List<String> notes = notesFor(item, fieldDescriptor, entry, typeEntry);

        return new ConfigHelpContent(
            title,
            path,
            summary,
            whenToUse,
            defaultBehavior,
            valueHint,
            values,
            notes,
            entry == null && shouldShowTechnicalDetails(summary, technicalDetails)
                ? technicalDetails
                : ""
        );
    }

    /**
     * Searches localized help articles in the database.
     *
     * @param query user search text
     * @param language preferred UI language
     * @param limit maximum number of rows to return
     * @return matching articles, ordered by language fallback and article key
     */
    public List<HelpSearchResult> search(
        String query,
        String language,
        int limit
    ) {
        ensureBundledDocumentsLoaded();
        if (!hasText(query) || limit <= 0) {
            return List.of();
        }

        List<HelpSearchResult> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        for (String candidate : languageCandidates(language)) {
            int remaining = limit - results.size();
            if (remaining <= 0) {
                break;
            }
            results.addAll(searchLanguage(candidate, pattern, remaining, seen));
        }
        return List.copyOf(results);
    }

    boolean hasArticleForTests(
        String language,
        String articleType,
        String articleKey
    ) {
        ensureBundledDocumentsLoaded();
        return findArticle(
            articleType,
            articleKey,
            List.of(normalizeLanguageTag(language))
        ) != null;
    }

    HelpEntry articleForTests(
        String language,
        String articleType,
        String articleKey
    ) {
        ensureBundledDocumentsLoaded();
        return findArticle(
            articleType,
            articleKey,
            List.of(normalizeLanguageTag(language))
        );
    }

    int documentVersionForTests(String language, String documentId) {
        ensureBundledDocumentsLoaded();
        Connection connection = DatabaseProvider.getConnection();
        if (connection == null) {
            return -1;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT version
                FROM config_help_documents
                WHERE language_tag = ? AND document_id = ?
                """)) {
            ps.setString(1, normalizeLanguageTag(language));
            ps.setString(2, documentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("version") : -1;
            }
        } catch (SQLException e) {
            log.error("Failed to read help document version", e);
            return -1;
        }
    }

    void resetLoadedStateForTests() {
        invalidateLoadedState();
    }

    private HelpEntry findEntry(
        ConfigTreeItem item,
        FieldDescriptor fieldDescriptor,
        boolean category,
        List<String> languageCandidates
    ) {
        if (category) {
            String sectionId = sectionId(item);
            HelpEntry sectionEntry = findArticle(
                "section",
                sectionId,
                languageCandidates
            );
            if (sectionEntry != null) {
                return sectionEntry;
            }
        }

        List<String> ids = new ArrayList<>();
        if (fieldDescriptor != null) {
            ids.add(fieldDescriptor.getFullName());
        }
        if (hasText(item.getConfigType()) && hasText(item.getFieldName())) {
            ids.add(item.getConfigType() + "." + item.getFieldName());
            String normalizedFieldId = normalizedFieldId(
                item.getConfigType(),
                item.getFieldName()
            );
            if (hasText(normalizedFieldId)) {
                ids.add(normalizedFieldId);
            }
        }
        if (hasText(item.getFieldName())) {
            ids.add(item.getFieldName());
        }

        for (String id : ids) {
            HelpEntry entry = findArticle("field", id, languageCandidates);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    private static String normalizedFieldId(
        String configType,
        String fieldName
    ) {
        if (
            "meshcore_channels".equals(configType) &&
            fieldName.matches("channel_\\d+")
        ) {
            return "meshcore_channels.channel";
        }
        return "";
    }

    private HelpEntry typeEntry(
        ConfigTreeItem item,
        FieldDescriptor fieldDescriptor,
        List<String> languageCandidates
    ) {
        String typeKey = typeKey(item, fieldDescriptor);
        return findArticle("type", typeKey, languageCandidates);
    }

    private List<ConfigHelpContent.ValueHelp> valuesFor(
        FieldDescriptor fieldDescriptor,
        HelpEntry entry,
        HelpEntry typeEntry,
        boolean allowProtoValueDescriptions
    ) {
        if (
            fieldDescriptor != null &&
            fieldDescriptor.getType() == FieldDescriptor.Type.ENUM
        ) {
            List<ConfigHelpContent.ValueHelp> values = new ArrayList<>();
            for (EnumValueDescriptor enumValue : fieldDescriptor
                .getEnumType()
                .getValues()) {
                HelpValue custom = entry != null
                    ? entry.values().get(enumValue.getName())
                    : null;
                String technical = allowProtoValueDescriptions
                    ? ConfigDescriptionResolver.protoValueDescription(
                        enumValue.getFullName()
                    )
                    : "";
                values.add(
                    new ConfigHelpContent.ValueHelp(
                        enumValue.getName(),
                        firstText(
                            custom != null ? custom.title() : "",
                            humanizeEnumValue(enumValue.getName())
                        ),
                        firstText(
                            custom != null ? custom.description() : "",
                            technical,
                            I18n.t(
                                "settings.config.help.enumValueFallback",
                                enumValue.getName()
                            )
                        )
                    )
                );
            }
            return values;
        }

        if (
            fieldDescriptor != null &&
            fieldDescriptor.getType() == FieldDescriptor.Type.BOOL
        ) {
            List<ConfigHelpContent.ValueHelp> values = new ArrayList<>();
            Map<String, HelpValue> customValues = entry != null
                ? entry.values()
                : Map.of();
            Map<String, HelpValue> typeValues = typeEntry != null
                ? typeEntry.values()
                : Map.of();
            values.add(valueFor("true", customValues, typeValues));
            values.add(valueFor("false", customValues, typeValues));
            return values;
        }

        if (entry != null && !entry.values().isEmpty()) {
            return entry
                .values()
                .entrySet()
                .stream()
                .map(e ->
                    new ConfigHelpContent.ValueHelp(
                        e.getKey(),
                        e.getValue().title(),
                        e.getValue().description()
                    )
                )
                .toList();
        }
        return List.of();
    }

    private static ConfigHelpContent.ValueHelp valueFor(
        String value,
        Map<String, HelpValue> customValues,
        Map<String, HelpValue> typeValues
    ) {
        HelpValue custom = customValues.get(value);
        HelpValue type = typeValues.get(value);
        return new ConfigHelpContent.ValueHelp(
            value,
            firstText(
                custom != null ? custom.title() : "",
                type != null ? type.title() : ""
            ),
            firstText(
                custom != null ? custom.description() : "",
                type != null ? type.description() : ""
            )
        );
    }

    private List<String> notesFor(
        ConfigTreeItem item,
        FieldDescriptor fieldDescriptor,
        HelpEntry entry,
        HelpEntry typeEntry
    ) {
        List<String> notes = new ArrayList<>();
        if (entry != null) {
            notes.addAll(entry.notes());
        }
        if (typeEntry != null) {
            notes.addAll(typeEntry.notes());
        }
        if (fieldDescriptor != null && fieldDescriptor.isRepeated()) {
            notes.add(I18n.t("settings.config.help.note.repeated"));
        }
        if (
            fieldDescriptor != null &&
            fieldDescriptor.getOptions().getDeprecated()
        ) {
            notes.add(I18n.t("settings.config.help.note.deprecated"));
        }
        if (item.isCategory()) {
            notes.add(I18n.t("settings.config.help.note.category"));
        }
        return notes
            .stream()
            .filter(ConfigHelpRepository::hasText)
            .distinct()
            .toList();
    }

    private String valueHintFor(
        ConfigTreeItem item,
        FieldDescriptor fieldDescriptor,
        HelpEntry typeEntry
    ) {
        if (fieldDescriptor == null) {
            return typeEntry != null ? typeEntry.valueHint() : "";
        }
        String typeHint = typeEntry != null ? typeEntry.valueHint() : "";
        String inferredUnit = inferredUnit(item, fieldDescriptor);
        if (!hasText(inferredUnit)) {
            return typeHint;
        }
        return hasText(typeHint) ? typeHint + " " + inferredUnit : inferredUnit;
    }

    private static String inferredUnit(
        ConfigTreeItem item,
        FieldDescriptor fieldDescriptor
    ) {
        String fieldName = fieldDescriptor.getName().toLowerCase(Locale.ROOT);
        if (fieldName.endsWith("_secs") || fieldName.contains("_secs_")) {
            return I18n.t("settings.config.help.unit.seconds");
        }
        if (fieldName.endsWith("_ms") || fieldName.contains("_ms_")) {
            return I18n.t("settings.config.help.unit.milliseconds");
        }
        if (fieldName.contains("distance")) {
            return I18n.t("settings.config.help.unit.meters");
        }
        if (fieldName.contains("altitude")) {
            return I18n.t("settings.config.help.unit.meters");
        }
        if (fieldName.contains("frequency")) {
            return I18n.t("settings.config.help.unit.frequency");
        }
        if (fieldName.contains("tx_power")) {
            return I18n.t("settings.config.help.unit.dbm");
        }
        if (fieldName.contains("gpio") || fieldName.endsWith("_pin")) {
            return I18n.t("settings.config.help.unit.gpio");
        }
        if (
            item.getValueType() == Integer.class ||
            item.getValueType() == Long.class ||
            item.getValueType() == Float.class ||
            item.getValueType() == Double.class
        ) {
            return I18n.t("settings.config.help.unit.number");
        }
        return "";
    }

    private HelpEntry findArticle(
        String articleType,
        String articleKey,
        List<String> languageCandidates
    ) {
        if (!hasText(articleKey)) {
            return null;
        }
        for (String language : languageCandidates) {
            for (String documentId : documentIds()) {
                HelpEntry entry = findArticle(
                    normalizeLanguageTag(language),
                    documentId,
                    articleType,
                    articleKey
                );
                if (entry != null) {
                    return entry;
                }
            }
        }
        return null;
    }

    private HelpEntry findArticle(
        String language,
        String documentId,
        String articleType,
        String articleKey
    ) {
        Connection connection = DatabaseProvider.getConnection();
        if (connection == null) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT content_json
                FROM config_help_articles
                WHERE language_tag = ?
                  AND document_id = ?
                  AND article_type = ?
                  AND article_key = ?
                """)) {
            ps.setString(1, language);
            ps.setString(2, documentId);
            ps.setString(3, articleType);
            ps.setString(4, articleKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return readHelpEntry(
                    JsonParser.parseString(rs.getString("content_json"))
                        .getAsJsonObject()
                );
            }
        } catch (Exception e) {
            log.error(
                "Failed to load config help article {}:{}:{}:{}",
                language,
                documentId,
                articleType,
                articleKey,
                e
            );
            return null;
        }
    }

    private List<HelpSearchResult> searchLanguage(
        String language,
        String pattern,
        int limit,
        Set<String> seen
    ) {
        Connection connection = DatabaseProvider.getConnection();
        if (connection == null) {
            return List.of();
        }
        List<HelpSearchResult> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT language_tag, article_type, article_key, content_json
                FROM config_help_articles
                WHERE language_tag = ?
                  AND LOWER(CAST(search_text AS VARCHAR)) LIKE ?
                ORDER BY article_type, article_key
                LIMIT ?
                """)) {
            ps.setString(1, normalizeLanguageTag(language));
            ps.setString(2, pattern);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("article_type") +
                        ":" +
                        rs.getString("article_key");
                    if (!seen.add(key)) {
                        continue;
                    }
                    HelpEntry entry = readHelpEntry(
                        JsonParser.parseString(rs.getString("content_json"))
                            .getAsJsonObject()
                    );
                    results.add(
                        new HelpSearchResult(
                            rs.getString("language_tag"),
                            rs.getString("article_type"),
                            rs.getString("article_key"),
                            entry.summary()
                        )
                    );
                }
            }
        } catch (Exception e) {
            log.error("Failed to search config help articles", e);
        }
        return results;
    }

    private void ensureBundledDocumentsLoaded() {
        if (bundledDocumentsVerified) {
            return;
        }
        synchronized (importLock) {
            if (bundledDocumentsVerified) {
                return;
            }
            Connection connection = DatabaseProvider.getConnection();
            if (connection == null) {
                return;
            }
            try {
                DatabaseMigrator.createConfigHelpTables(connection);
                for (String language : supportedHelpLanguages()) {
                    for (String fileName : HELP_FILES) {
                        importBundledDocumentIfNeeded(
                            connection,
                            language,
                            fileName
                        );
                    }
                }
                bundledDocumentsVerified = true;
            } catch (SQLException e) {
                log.error("Failed to initialize config help tables", e);
            }
        }
    }

    private void importBundledDocumentIfNeeded(
        Connection connection,
        String language,
        String fileName
    ) {
        String resource = "/help/config/" + language + "/" + fileName;
        try (InputStream input =
                ConfigHelpRepository.class.getResourceAsStream(resource)) {
            if (input == null) {
                return;
            }
            byte[] bytes = input.readAllBytes();
            JsonObject root = JsonParser
                .parseReader(
                    new InputStreamReader(
                        new ByteArrayInputStream(bytes),
                        StandardCharsets.UTF_8
                    )
                )
                .getAsJsonObject();
            HelpDocument document = new HelpDocument(
                normalizeLanguageTag(language),
                documentIdFor(fileName),
                documentVersion(root),
                checksum(bytes),
                root
            );
            if (!documentNeedsImport(connection, document)) {
                return;
            }
            importDocument(connection, document);
        } catch (Exception e) {
            log.error("Failed to import config help resource {}", resource, e);
        }
    }

    private boolean documentNeedsImport(
        Connection connection,
        HelpDocument document
    ) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT version, checksum
                FROM config_help_documents
                WHERE language_tag = ? AND document_id = ?
                """)) {
            ps.setString(1, document.language());
            ps.setString(2, document.documentId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return true;
                }
                return rs.getInt("version") != document.version() ||
                    !document.checksum().equals(rs.getString("checksum"));
            }
        }
    }

    private void importDocument(
        Connection connection,
        HelpDocument document
    ) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM config_help_articles
                    WHERE language_tag = ? AND document_id = ?
                    """)) {
                delete.setString(1, document.language());
                delete.setString(2, document.documentId());
                delete.executeUpdate();
            }

            try (PreparedStatement upsert = connection.prepareStatement("""
                    MERGE INTO config_help_documents (
                        language_tag, document_id, version, checksum, loaded_at
                    )
                    KEY (language_tag, document_id)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                upsert.setString(1, document.language());
                upsert.setString(2, document.documentId());
                upsert.setInt(3, document.version());
                upsert.setString(4, document.checksum());
                upsert.setLong(5, System.currentTimeMillis());
                upsert.executeUpdate();
            }

            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO config_help_articles (
                        language_tag, document_id, article_type, article_key,
                        content_json, search_text, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insertEntryMap(insert, document, "section", "sections");
                insertEntryMap(insert, document, "type", "types");
                insertEntryMap(insert, document, "field", "fields");
                insert.executeBatch();
            }

            connection.commit();
            log.info(
                "Imported config help document {}:{} v{}",
                document.language(),
                document.documentId(),
                document.version()
            );
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void insertEntryMap(
        PreparedStatement insert,
        HelpDocument document,
        String articleType,
        String rootKey
    ) throws SQLException {
        JsonObject object = document.root().getAsJsonObject(rootKey);
        if (object == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject article = entry.getValue().getAsJsonObject();
            insert.setString(1, document.language());
            insert.setString(2, document.documentId());
            insert.setString(3, articleType);
            insert.setString(4, entry.getKey());
            insert.setString(5, GSON.toJson(article));
            insert.setString(6, searchText(entry.getKey(), article));
            insert.setLong(7, now);
            insert.addBatch();
        }
    }

    private String sectionId(ConfigTreeItem item) {
        if (hasText(item.getFieldName())) {
            return item.getFieldName();
        }
        return hasText(item.getConfigType()) ? item.getConfigType() : "";
    }

    private String pathFor(
        ConfigTreeItem item,
        FieldDescriptor fieldDescriptor
    ) {
        if (fieldDescriptor != null) {
            String fullName = fieldDescriptor.getFullName();
            return fullName.startsWith("meshtastic.")
                ? fullName.substring("meshtastic.".length())
                : fullName;
        }
        if (hasText(item.getConfigType()) && hasText(item.getFieldName())) {
            return item.getConfigType() + "." + item.getFieldName();
        }
        return hasText(item.getFieldName())
            ? item.getFieldName()
            : item.getConfigType();
    }

    private String typeKey(
        ConfigTreeItem item,
        FieldDescriptor fieldDescriptor
    ) {
        if (item.isCategory()) {
            return "category";
        }
        if (fieldDescriptor == null) {
            Class<?> valueType = item.getValueType();
            if (valueType == Boolean.class) {
                return "boolean";
            }
            if (
                valueType == Integer.class ||
                valueType == Long.class ||
                valueType == Float.class ||
                valueType == Double.class
            ) {
                return "number";
            }
            return "string";
        }
        if (fieldDescriptor.isRepeated()) {
            return "repeated";
        }
        return switch (fieldDescriptor.getType()) {
            case BOOL -> "boolean";
            case ENUM -> "enum";
            case STRING -> "string";
            case BYTES -> "bytes";
            case MESSAGE -> "category";
            case FLOAT,
                DOUBLE,
                UINT32,
                INT32,
                SINT32,
                FIXED32,
                SFIXED32,
                UINT64,
                INT64,
                SINT64,
                FIXED64,
                SFIXED64 -> "number";
            default -> "string";
        };
    }

    private static HelpEntry readHelpEntry(JsonObject object) {
        return new HelpEntry(
            stringValue(object, "summary"),
            stringValue(object, "whenToUse"),
            stringValue(object, "defaultBehavior"),
            stringValue(object, "valueHint"),
            readValues(object.getAsJsonObject("values")),
            readStringList(object.get("notes"))
        );
    }

    private static Map<String, HelpValue> readValues(JsonObject object) {
        if (object == null) {
            return Map.of();
        }
        Map<String, HelpValue> values = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isJsonObject()) {
                JsonObject valueObject = entry.getValue().getAsJsonObject();
                values.put(
                    entry.getKey(),
                    new HelpValue(
                        stringValue(valueObject, "title"),
                        stringValue(valueObject, "description")
                    )
                );
            }
        }
        return Map.copyOf(values);
    }

    private static List<String> readStringList(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item != null && item.isJsonPrimitive()) {
                values.add(item.getAsString());
            }
        }
        return List.copyOf(values);
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive()
            ? value.getAsString().trim()
            : "";
    }

    private String activeLanguage() {
        return normalizeLanguageTag(I18n.locale().toLanguageTag());
    }

    private static List<String> languageCandidates(String language) {
        List<String> candidates = new ArrayList<>();
        addLanguageCandidate(candidates, normalizeLanguageTag(language));
        String baseLanguage = Locale
            .forLanguageTag(normalizeLanguageTag(language))
            .getLanguage();
        addLanguageCandidate(candidates, baseLanguage);
        addLanguageCandidate(candidates, FALLBACK_LANGUAGE);
        return List.copyOf(candidates);
    }

    private static List<String> supportedHelpLanguages() {
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        for (I18n.LanguageOption option : I18n.supportedLanguages()) {
            if (!I18n.LANGUAGE_SYSTEM.equals(option.tag())) {
                languages.add(normalizeLanguageTag(option.tag()));
            }
        }
        languages.add(FALLBACK_LANGUAGE);
        return List.copyOf(languages);
    }

    private static List<String> documentIds() {
        return HELP_FILES.stream().map(ConfigHelpRepository::documentIdFor).toList();
    }

    private static String documentIdFor(String fileName) {
        String baseName = fileName.endsWith(".json")
            ? fileName.substring(0, fileName.length() - ".json".length())
            : fileName;
        return "config/" + baseName;
    }

    private static void addLanguageCandidate(
        List<String> candidates,
        String language
    ) {
        if (hasText(language) && !candidates.contains(language)) {
            candidates.add(language);
        }
    }

    private static String normalizeLanguageTag(String language) {
        if (!hasText(language)) {
            return FALLBACK_LANGUAGE;
        }
        Locale locale = Locale.forLanguageTag(language.trim().replace('_', '-'));
        String localeLanguage = locale.getLanguage();
        if (!hasText(localeLanguage)) {
            return FALLBACK_LANGUAGE;
        }
        return locale.toLanguageTag();
    }

    private static int documentVersion(JsonObject root) {
        JsonElement version = root.get("version");
        return version != null && version.isJsonPrimitive()
            ? Math.max(1, version.getAsInt())
            : 1;
    }

    private static String checksum(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static String searchText(String articleKey, JsonObject article) {
        List<String> parts = new ArrayList<>();
        append(parts, articleKey);
        append(parts, stringValue(article, "summary"));
        append(parts, stringValue(article, "whenToUse"));
        append(parts, stringValue(article, "defaultBehavior"));
        append(parts, stringValue(article, "valueHint"));
        parts.addAll(readStringList(article.get("notes")));

        JsonObject values = article.getAsJsonObject("values");
        if (values != null) {
            for (Map.Entry<String, JsonElement> value : values.entrySet()) {
                append(parts, value.getKey());
                if (value.getValue() != null && value.getValue().isJsonObject()) {
                    JsonObject valueObject = value.getValue().getAsJsonObject();
                    append(parts, stringValue(valueObject, "title"));
                    append(parts, stringValue(valueObject, "description"));
                }
            }
        }
        return String.join("\n", parts)
            .toLowerCase(Locale.ROOT)
            .trim();
    }

    private static void append(List<String> parts, String value) {
        if (hasText(value)) {
            parts.add(value.trim());
        }
    }

    private static boolean shouldShowTechnicalDetails(
        String summary,
        String technicalDetails
    ) {
        return hasText(technicalDetails) && !summary.equals(technicalDetails);
    }

    private static String humanizeEnumValue(String enumName) {
        return ProtobufTreeBuilder.humanize(
            enumName.toLowerCase(Locale.ROOT)
        );
    }

    private static String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Search result for a single help article.
     *
     * @param languageTag language of the matched article
     * @param articleType article collection, such as {@code field}
     * @param articleKey stable article key
     * @param summary short article summary
     */
    public record HelpSearchResult(
        String languageTag,
        String articleType,
        String articleKey,
        String summary
    ) {}

    private record HelpDocument(
        String language,
        String documentId,
        int version,
        String checksum,
        JsonObject root
    ) {}

    record HelpEntry(
        String summary,
        String whenToUse,
        String defaultBehavior,
        String valueHint,
        Map<String, HelpValue> values,
        List<String> notes
    ) {
        HelpEntry {
            summary = summary != null ? summary.trim() : "";
            whenToUse = whenToUse != null ? whenToUse.trim() : "";
            defaultBehavior = defaultBehavior != null
                ? defaultBehavior.trim()
                : "";
            valueHint = valueHint != null ? valueHint.trim() : "";
            values = Map.copyOf(values != null ? values : Map.of());
            notes = List.copyOf(notes != null ? notes : List.of());
        }
    }

    record HelpValue(String title, String description) {
        HelpValue {
            title = title != null ? title.trim() : "";
            description = description != null ? description.trim() : "";
        }
    }

    private ConfigHelpRepository() {}
}
