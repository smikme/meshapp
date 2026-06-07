package com.meshtastic.client.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured help shown for a single configuration tree row.
 *
 * @param title display title shown at the top of the help popup
 * @param path technical path of the configuration field or section
 * @param summary short explanation of what the item controls
 * @param whenToUse guidance for when a user should change the value
 * @param defaultBehavior recommendation for users who are unsure
 * @param valueHint explanation of accepted value format or units
 * @param values possible values for enum, boolean, or documented choice fields
 * @param notes additional warnings or operational notes
 * @param technicalDetails protobuf schema text shown when it adds detail
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record ConfigHelpContent(
    String title,
    String path,
    String summary,
    String whenToUse,
    String defaultBehavior,
    String valueHint,
    List<ValueHelp> values,
    List<String> notes,
    String technicalDetails
) {
    public ConfigHelpContent {
        title = normalize(title);
        path = normalize(path);
        summary = normalize(summary);
        whenToUse = normalize(whenToUse);
        defaultBehavior = normalize(defaultBehavior);
        valueHint = normalize(valueHint);
        values = List.copyOf(values != null ? values : List.of());
        notes = List.copyOf(notes != null ? notes : List.of());
        technicalDetails = normalize(technicalDetails);
    }

    /**
     * Checks whether this help object contains any user-visible details.
     *
     * @return {@code true} when at least one help block should be displayed
     */
    public boolean hasDetails() {
        return hasText(summary) ||
            hasText(whenToUse) ||
            hasText(defaultBehavior) ||
            hasText(valueHint) ||
            !values.isEmpty() ||
            !notes.isEmpty() ||
            hasText(technicalDetails);
    }

    /**
     * Builds a plain text representation of the structured help.
     *
     * @return newline-separated help text for accessibility and tests
     */
    public String plainText() {
        List<String> parts = new ArrayList<>();
        append(parts, title);
        append(parts, path);
        append(parts, summary);
        append(parts, whenToUse);
        append(parts, defaultBehavior);
        append(parts, valueHint);
        for (ValueHelp value : values) {
            append(
                parts,
                value.value() +
                (hasText(value.title()) ? " - " + value.title() : "") +
                (hasText(value.description())
                        ? ": " + value.description()
                        : "")
            );
        }
        parts.addAll(notes.stream().filter(ConfigHelpContent::hasText).toList());
        append(parts, technicalDetails);
        return String.join("\n", parts);
    }

    private static void append(List<String> parts, String value) {
        if (hasText(value)) {
            parts.add(value);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Help text for a single selectable value.
     *
     * @param value stored value or protobuf enum name
     * @param title short human-readable label
     * @param description user-facing explanation of this value
     */
    public record ValueHelp(String value, String title, String description) {
        public ValueHelp {
            value = normalize(value);
            title = normalize(title);
            description = normalize(description);
        }
    }
}
