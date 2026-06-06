package com.meshtastic.client.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads localized configuration help documents and combines them with protobuf metadata.
 * <p>
 * Help files are searched by language tag, then by base language, then by the
 * fallback language. This lets new UI languages provide help text by adding
 * resources under {@code /help/config/<language>/} without changing Java code.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigHelpRepository {

    private static final ConfigHelpRepository INSTANCE =
        new ConfigHelpRepository();
    private static final String FALLBACK_LANGUAGE = I18n.LANGUAGE_EN;
    private static final List<String> HELP_FILES = List.of("common.json");

    private final Map<String, HelpBundle> bundles = new HashMap<>();

    /**
     * Returns the shared repository instance.
     *
     * @return singleton repository used by configuration UI code
     */
    public static ConfigHelpRepository getInstance() {
        return INSTANCE;
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

        List<HelpBundle> languageBundles = bundlesFor(language);
        FieldDescriptor fieldDescriptor = item.getFieldDescriptor();
        boolean category = item.isCategory();

        HelpEntry entry = findEntry(
            item,
            fieldDescriptor,
            category,
            languageBundles
        );
        HelpEntry typeEntry = typeEntry(
            item,
            fieldDescriptor,
            languageBundles
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

    HelpBundle bundleForTests(String language) {
        return bundle(language);
    }

    private HelpEntry findEntry(
        ConfigTreeItem item,
        FieldDescriptor fieldDescriptor,
        boolean category,
        List<HelpBundle> languageBundles
    ) {
        if (category) {
            String sectionId = sectionId(item);
            HelpEntry sectionEntry = findSection(sectionId, languageBundles);
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
            HelpEntry entry = findField(id, languageBundles);
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
        List<HelpBundle> languageBundles
    ) {
        String typeKey = typeKey(item, fieldDescriptor);
        return findType(typeKey, languageBundles);
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

    private HelpEntry findSection(
        String id,
        List<HelpBundle> languageBundles
    ) {
        return firstEntry(languageBundles, bundle -> bundle.sections().get(id));
    }

    private HelpEntry findField(
        String id,
        List<HelpBundle> languageBundles
    ) {
        return firstEntry(languageBundles, bundle -> bundle.fields().get(id));
    }

    private HelpEntry findType(
        String id,
        List<HelpBundle> languageBundles
    ) {
        return firstEntry(languageBundles, bundle -> bundle.types().get(id));
    }

    private static HelpEntry firstEntry(
        List<HelpBundle> languageBundles,
        java.util.function.Function<HelpBundle, HelpEntry> selector
    ) {
        for (HelpBundle bundle : languageBundles) {
            HelpEntry entry = selector.apply(bundle);
            if (entry != null) {
                return entry;
            }
        }
        return null;
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

    private HelpBundle bundle(String language) {
        String normalized = normalizeLanguageTag(language);
        synchronized (bundles) {
            return bundles.computeIfAbsent(normalized, this::loadBundle);
        }
    }

    private List<HelpBundle> bundlesFor(String language) {
        return languageCandidates(language)
            .stream()
            .map(this::bundle)
            .toList();
    }

    private HelpBundle loadBundle(String language) {
        Map<String, HelpEntry> sections = new HashMap<>();
        Map<String, HelpEntry> fields = new HashMap<>();
        Map<String, HelpEntry> types = new HashMap<>();
        for (String fileName : HELP_FILES) {
            String resource = "/help/config/" + language + "/" + fileName;
            try (
                InputStream input =
                    ConfigHelpRepository.class.getResourceAsStream(resource)
            ) {
                if (input == null) {
                    continue;
                }
                JsonObject root = JsonParser
                    .parseReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8)
                    )
                    .getAsJsonObject();
                readEntryMap(root.getAsJsonObject("sections"), sections);
                readEntryMap(root.getAsJsonObject("fields"), fields);
                readEntryMap(root.getAsJsonObject("types"), types);
            } catch (Exception ignored) {
                // Invalid optional help files should not block configuration editing.
            }
        }
        return new HelpBundle(
            Map.copyOf(sections),
            Map.copyOf(fields),
            Map.copyOf(types)
        );
    }

    private static void readEntryMap(
        JsonObject object,
        Map<String, HelpEntry> target
    ) {
        if (object == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isJsonObject()) {
                target.put(
                    entry.getKey(),
                    readHelpEntry(entry.getValue().getAsJsonObject())
                );
            }
        }
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

    record HelpBundle(
        Map<String, HelpEntry> sections,
        Map<String, HelpEntry> fields,
        Map<String, HelpEntry> types
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
