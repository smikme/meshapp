package com.meshtastic.client.utils;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves user-facing help text for configuration tree rows.
 * <p>
 * The resolver is the public entry point used by UI code. It delegates
 * structured help assembly to {@link ConfigHelpRepository} and keeps protobuf
 * schema comments available as a fallback for fields and enum values.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigDescriptionResolver {

    private static final List<String> PROTO_RESOURCES = List.of(
        "/meshtastic/config.proto",
        "/meshtastic/module_config.proto",
        "/meshtastic/device_ui.proto",
        "/meshtastic/atak.proto"
    );
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
        "^\\s*package\\s+([A-Za-z_][\\w.]*)\\s*;"
    );
    private static final Pattern MESSAGE_PATTERN = Pattern.compile(
        "^\\s*message\\s+([A-Za-z_][\\w]*)\\b.*\\{\\s*$"
    );
    private static final Pattern ENUM_PATTERN = Pattern.compile(
        "^\\s*enum\\s+([A-Za-z_][\\w]*)\\b.*\\{\\s*$"
    );
    private static final Pattern FIELD_PATTERN = Pattern.compile(
        "^\\s*(?:repeated\\s+)?[A-Za-z_][\\w.]*\\s+([A-Za-z_][\\w]*)\\s*=\\s*\\d+\\b.*;"
    );
    private static final Pattern ENUM_VALUE_PATTERN = Pattern.compile(
        "^\\s*([A-Z][A-Z0-9_]*)\\s*=\\s*\\d+\\b.*;"
    );

    private static volatile Map<String, String> protoDescriptions;
    private static volatile Map<String, String> protoValueDescriptions;

    /**
     * Returns a plain text version of the help content for accessibility text,
     * tests, and other non-rich UI surfaces.
     *
     * @param item configuration tree item to describe; may be {@code null}
     * @return newline-separated help text, or an empty string when no item is supplied
     */
    public static String descriptionFor(ConfigTreeItem item) {
        return helpFor(item).plainText();
    }

    /**
     * Returns structured help content for a configuration tree item.
     *
     * @param item configuration tree item to describe; may be {@code null}
     * @return normalized help content suitable for a rich popup
     */
    public static ConfigHelpContent helpFor(ConfigTreeItem item) {
        return ConfigHelpRepository.getInstance().helpFor(item);
    }

    static String localizedProtoDescription(FieldDescriptor fieldDescriptor) {
        if (fieldDescriptor == null) {
            return "";
        }
        String localized = I18n.tOrNull(
            "settings.config.description.protobuf." +
            fieldDescriptor.getFullName()
        );
        return hasText(localized) ? localized : "";
    }

    static String protoDescription(String fullName) {
        if (!hasText(fullName)) {
            return "";
        }
        return protoDescriptions().getOrDefault(fullName, "");
    }

    static String protoValueDescription(String fullName) {
        if (!hasText(fullName)) {
            return "";
        }
        return protoValueDescriptions().getOrDefault(fullName, "");
    }

    static Map<String, String> protoDescriptionsForTests() {
        return protoDescriptions();
    }

    static Map<String, String> protoValueDescriptionsForTests() {
        return protoValueDescriptions();
    }

    private static Map<String, String> protoDescriptions() {
        Map<String, String> current = protoDescriptions;
        if (current == null) {
            current = loadProtoDescriptions();
            protoDescriptions = current;
        }
        return current;
    }

    private static Map<String, String> protoValueDescriptions() {
        Map<String, String> current = protoValueDescriptions;
        if (current == null) {
            loadProtoDocumentationIfNeeded();
            current = protoValueDescriptions;
        }
        return current != null ? current : Map.of();
    }

    private static void loadProtoDocumentationIfNeeded() {
        if (protoDescriptions == null || protoValueDescriptions == null) {
            Map<String, String> fieldDescriptions = new HashMap<>();
            Map<String, String> valueDescriptions = new HashMap<>();
            loadProtoDescriptions(fieldDescriptions, valueDescriptions);
            protoDescriptions = Map.copyOf(fieldDescriptions);
            protoValueDescriptions = Map.copyOf(valueDescriptions);
        }
    }

    private static Map<String, String> loadProtoDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        Map<String, String> valueDescriptions = new HashMap<>();
        loadProtoDescriptions(descriptions, valueDescriptions);
        return Map.copyOf(descriptions);
    }

    private static void loadProtoDescriptions(
        Map<String, String> descriptions,
        Map<String, String> valueDescriptions
    ) {
        for (String resource : PROTO_RESOURCES) {
            try (InputStream input =
                    ConfigDescriptionResolver.class.getResourceAsStream(
                        resource
                    )) {
                if (input == null) {
                    continue;
                }
                parseProto(input, descriptions, valueDescriptions);
            } catch (IOException ignored) {
                // Missing help text should not break configuration editing.
            }
        }
    }

    private static void parseProto(
        InputStream input,
        Map<String, String> descriptions,
        Map<String, String> valueDescriptions
    ) throws IOException {
        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8)
            )
        ) {
            String packageName = "";
            List<ProtoScope> scopeStack = new ArrayList<>();
            List<String> pendingComment = new ArrayList<>();
            int depth = 0;
            boolean inBlockComment = false;
            String line;
            while ((line = reader.readLine()) != null) {
                CommentStripResult stripped = stripComments(
                    line,
                    inBlockComment,
                    pendingComment
                );
                inBlockComment = stripped.inBlockComment();
                String code = stripped.code().trim();

                if (!code.isEmpty()) {
                    Matcher packageMatcher = PACKAGE_PATTERN.matcher(code);
                    if (packageMatcher.matches()) {
                        packageName = packageMatcher.group(1);
                        pendingComment.clear();
                    } else {
                        Matcher messageMatcher = MESSAGE_PATTERN.matcher(code);
                        if (messageMatcher.matches()) {
                            scopeStack.add(
                                new ProtoScope(
                                    ScopeType.MESSAGE,
                                    messageMatcher.group(1),
                                    depth + countChar(code, '{')
                                )
                            );
                            pendingComment.clear();
                        } else {
                            Matcher enumMatcher = ENUM_PATTERN.matcher(code);
                            if (enumMatcher.matches()) {
                                scopeStack.add(
                                    new ProtoScope(
                                        ScopeType.ENUM,
                                        enumMatcher.group(1),
                                        depth + countChar(code, '{')
                                    )
                                );
                                pendingComment.clear();
                            } else {
                                Matcher enumValueMatcher =
                                    ENUM_VALUE_PATTERN.matcher(code);
                                if (
                                    enumValueMatcher.matches() &&
                                    insideEnum(scopeStack)
                                ) {
                                    String comment = normalizedComment(
                                        pendingComment
                                    );
                                    if (isUsefulProtoComment(comment)) {
                                        valueDescriptions.put(
                                            fullName(
                                                packageName,
                                                scopeStack,
                                                enumValueMatcher.group(1)
                                            ),
                                            comment
                                        );
                                    }
                                    pendingComment.clear();
                                } else {
                                    Matcher fieldMatcher =
                                        FIELD_PATTERN.matcher(code);
                                    if (
                                        fieldMatcher.matches() &&
                                        insideMessage(scopeStack) &&
                                        !insideEnum(scopeStack)
                                    ) {
                                        String comment = normalizedComment(
                                            pendingComment
                                        );
                                        if (isUsefulProtoComment(comment)) {
                                            descriptions.put(
                                                fullName(
                                                    packageName,
                                                    scopeStack,
                                                    fieldMatcher.group(1)
                                                ),
                                                comment
                                            );
                                        }
                                        pendingComment.clear();
                                    } else if (
                                        !code.equals("{") &&
                                        !code.equals("}")
                                    ) {
                                        pendingComment.clear();
                                    }
                                }
                            }
                        }
                    }
                }

                depth += countChar(code, '{') - countChar(code, '}');
                while (
                    !scopeStack.isEmpty() &&
                    scopeStack.getLast().depth() > depth
                ) {
                    scopeStack.removeLast();
                }
            }
        }
    }

    private static CommentStripResult stripComments(
        String line,
        boolean inBlockComment,
        List<String> pendingComment
    ) {
        String remaining = line;
        StringBuilder code = new StringBuilder();
        boolean insideBlock = inBlockComment;

        while (!remaining.isEmpty()) {
            if (insideBlock) {
                int end = remaining.indexOf("*/");
                String commentPart = end >= 0
                    ? remaining.substring(0, end)
                    : remaining;
                appendCommentLine(pendingComment, commentPart);
                if (end < 0) {
                    return new CommentStripResult(code.toString(), true);
                }
                remaining = remaining.substring(end + 2);
                insideBlock = false;
                continue;
            }

            int blockStart = remaining.indexOf("/*");
            int lineStart = remaining.indexOf("//");
            if (lineStart >= 0 && (blockStart < 0 || lineStart < blockStart)) {
                code.append(remaining, 0, lineStart);
                appendCommentLine(
                    pendingComment,
                    remaining.substring(lineStart + 2)
                );
                return new CommentStripResult(code.toString(), false);
            }
            if (blockStart >= 0) {
                code.append(remaining, 0, blockStart);
                remaining = remaining.substring(blockStart + 2);
                insideBlock = true;
                continue;
            }
            code.append(remaining);
            break;
        }

        return new CommentStripResult(code.toString(), insideBlock);
    }

    private static void appendCommentLine(
        List<String> commentLines,
        String rawLine
    ) {
        String cleaned = rawLine
            .replaceFirst("^\\s*\\*\\s?", "")
            .trim();
        if (!cleaned.isEmpty()) {
            commentLines.add(cleaned);
        }
    }

    private static String normalizedComment(List<String> commentLines) {
        if (commentLines.isEmpty()) {
            return "";
        }
        return String.join(" ", commentLines)
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static boolean isUsefulProtoComment(String comment) {
        if (!hasText(comment)) {
            return false;
        }
        String normalized = comment.trim().toLowerCase(Locale.ROOT);
        return !normalized.equals("todo: replace") &&
            !normalized.equals("payload variant");
    }

    private static String fullName(
        String packageName,
        List<ProtoScope> scopeStack,
        String leafName
    ) {
        StringBuilder fullName = new StringBuilder();
        if (hasText(packageName)) {
            fullName.append(packageName).append('.');
        }
        for (ProtoScope scope : scopeStack) {
            fullName.append(scope.name()).append('.');
        }
        fullName.append(leafName);
        return fullName.toString();
    }

    private static boolean insideMessage(List<ProtoScope> scopeStack) {
        return scopeStack.stream().anyMatch(scope ->
            scope.type() == ScopeType.MESSAGE
        );
    }

    private static boolean insideEnum(List<ProtoScope> scopeStack) {
        return !scopeStack.isEmpty() &&
            scopeStack.getLast().type() == ScopeType.ENUM;
    }

    private static int countChar(String value, char target) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private enum ScopeType {
        MESSAGE,
        ENUM,
    }

    private record ProtoScope(ScopeType type, String name, int depth) {}

    private record CommentStripResult(String code, boolean inBlockComment) {}

    private ConfigDescriptionResolver() {}
}
