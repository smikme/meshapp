package com.meshtastic.client.forms.settings;

import com.meshtastic.client.model.ConfigTreeItem;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javafx.scene.control.TreeItem;

/**
 * Utility operations for configuration editor tree nodes.
 * The form uses this class for tree traversal, filtering, field lookup, and
 * modification tracking so UI code does not own recursive data-structure logic.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigTreeItemSupport {

    private ConfigTreeItemSupport() {}

    /**
     * Finds a top-level section by configuration type.
     *
     * @param root       hidden editor root
     * @param configType section config type
     * @return matching section, if present
     */
    public static Optional<TreeItem<ConfigTreeItem>> findTopLevelSection(
        TreeItem<ConfigTreeItem> root,
        String configType
    ) {
        return childrenOf(root)
            .filter(child ->
                Optional
                    .ofNullable(child.getValue())
                    .map(ConfigTreeItem::getConfigType)
                    .filter(configType::equals)
                    .isPresent()
            )
            .findFirst();
    }

    /**
     * Finds a child section by active protobuf oneof variant number.
     *
     * @param sectionRoot   parent section
     * @param variantNumber protobuf oneof field number
     * @return matching section, if present
     */
    public static Optional<TreeItem<ConfigTreeItem>> findSectionByVariant(
        TreeItem<ConfigTreeItem> sectionRoot,
        int variantNumber
    ) {
        return childrenOf(sectionRoot)
            .filter(child ->
                Optional
                    .ofNullable(child.getValue())
                    .map(ConfigTreeItem::getConfigVariantNumber)
                    .filter(number -> number == variantNumber)
                    .isPresent()
            )
            .findFirst();
    }

    /**
     * Sets a field value inside a section.
     *
     * @param section   parent section
     * @param fieldName field name
     * @param value     new value
     */
    public static void setFieldValue(
        TreeItem<ConfigTreeItem> section,
        String fieldName,
        Object value
    ) {
        findField(section, fieldName).ifPresent(item -> item.setValue(value));
    }

    /**
     * Reads a field value inside a section.
     *
     * @param section   parent section
     * @param fieldName field name
     * @return field value, if present
     */
    public static Optional<Object> fieldValue(
        TreeItem<ConfigTreeItem> section,
        String fieldName
    ) {
        return findField(section, fieldName).map(ConfigTreeItem::getValue);
    }

    /**
     * Reads a field value as text.
     *
     * @param section   parent section
     * @param fieldName field name
     * @return field text or an empty string
     */
    public static String stringValue(
        TreeItem<ConfigTreeItem> section,
        String fieldName
    ) {
        return fieldValue(section, fieldName).map(Object::toString).orElse("");
    }

    /**
     * Reads a numeric field as double.
     *
     * @param section   parent section
     * @param fieldName field name
     * @return numeric value or {@code 0}
     */
    public static double doubleValue(
        TreeItem<ConfigTreeItem> section,
        String fieldName
    ) {
        return fieldValue(section, fieldName)
            .filter(Number.class::isInstance)
            .map(Number.class::cast)
            .map(Number::doubleValue)
            .orElse(0.0);
    }

    /**
     * Reads a numeric field as int.
     *
     * @param section   parent section
     * @param fieldName field name
     * @return numeric value or {@code 0}
     */
    public static int intValue(
        TreeItem<ConfigTreeItem> section,
        String fieldName
    ) {
        return fieldValue(section, fieldName)
            .filter(Number.class::isInstance)
            .map(Number.class::cast)
            .map(Number::intValue)
            .orElse(0);
    }

    /**
     * Reads a boolean field.
     *
     * @param section   parent section
     * @param fieldName field name
     * @return boolean value or {@code false}
     */
    public static boolean booleanValue(
        TreeItem<ConfigTreeItem> section,
        String fieldName
    ) {
        return fieldValue(section, fieldName)
            .map(value ->
                value instanceof Boolean bool
                    ? bool
                    : Boolean.parseBoolean(String.valueOf(value))
            )
            .orElse(false);
    }

    /**
     * Counts editable fields under a tree node.
     *
     * @param item root node
     * @return editable field count
     */
    public static int countEditableFields(TreeItem<ConfigTreeItem> item) {
        int selfCount = Optional
            .ofNullable(item)
            .map(TreeItem::getValue)
            .filter(ConfigTreeItem::isEditable)
            .map(ignored -> 1)
            .orElse(0);
        return selfCount +
            childrenOf(item).mapToInt(ConfigTreeItemSupport::countEditableFields).sum();
    }

    /**
     * Filters a tree by case-insensitive query.
     *
     * @param root  source tree root
     * @param query search query
     * @return filtered root copy, or the original root for blank query
     */
    public static Optional<TreeItem<ConfigTreeItem>> filter(
        TreeItem<ConfigTreeItem> root,
        String query
    ) {
        if (root == null) {
            return Optional.empty();
        }
        if (query == null || query.isBlank()) {
            return Optional.of(root);
        }

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        TreeItem<ConfigTreeItem> filteredRoot = new TreeItem<>(root.getValue());
        filteredRoot.setExpanded(true);
        filteredRoot
            .getChildren()
            .setAll(
                childrenOf(root)
                    .map(child -> filterTreeItem(child, lowerQuery))
                    .flatMap(Optional::stream)
                    .toList()
            );
        return Optional.of(filteredRoot);
    }

    /**
     * Copies a tree item with all descendants.
     *
     * @param item source item
     * @return deep copy
     */
    public static TreeItem<ConfigTreeItem> copyTreeItem(
        TreeItem<ConfigTreeItem> item
    ) {
        TreeItem<ConfigTreeItem> copy = new TreeItem<>(
            Optional.ofNullable(item).map(TreeItem::getValue).orElse(null)
        );
        copy.setExpanded(Optional.ofNullable(item).map(TreeItem::isExpanded).orElse(false));
        copy
            .getChildren()
            .setAll(childrenOf(item).map(ConfigTreeItemSupport::copyTreeItem).toList());
        return copy;
    }

    /**
     * Checks whether a tree contains modified fields.
     *
     * @param item root item
     * @return {@code true} when any descendant has a changed value
     */
    public static boolean hasModifiedFields(TreeItem<ConfigTreeItem> item) {
        return Optional
                .ofNullable(item)
                .map(TreeItem::getValue)
                .map(ConfigTreeItem::isModified)
                .orElse(false) ||
            childrenOf(item).anyMatch(ConfigTreeItemSupport::hasModifiedFields);
    }

    /**
     * Resets original values for the whole tree.
     *
     * @param item root item
     */
    public static void resetModifiedFlags(TreeItem<ConfigTreeItem> item) {
        Optional
            .ofNullable(item)
            .map(TreeItem::getValue)
            .ifPresent(ConfigTreeItem::resetOriginal);
        childrenOf(item).forEach(ConfigTreeItemSupport::resetModifiedFlags);
    }

    /**
     * Finds a tree item that wraps the exact model object.
     *
     * @param root   search root
     * @param target target model object
     * @return tree item, if found
     */
    public static Optional<TreeItem<ConfigTreeItem>> findTreeItemByValue(
        TreeItem<ConfigTreeItem> root,
        ConfigTreeItem target
    ) {
        if (root == null || target == null) {
            return Optional.empty();
        }
        if (root.getValue() == target) {
            return Optional.of(root);
        }
        return childrenOf(root)
            .map(child -> findTreeItemByValue(child, target))
            .flatMap(Optional::stream)
            .findFirst();
    }

    /**
     * Finds a field inside a section by protobuf field name.
     *
     * @param section   parent section
     * @param fieldName field name
     * @return field item, if present
     */
    public static Optional<ConfigTreeItem> findField(
        TreeItem<ConfigTreeItem> section,
        String fieldName
    ) {
        return childrenOf(section)
            .map(TreeItem::getValue)
            .filter(Objects::nonNull)
            .filter(item -> fieldName.equals(item.getFieldName()))
            .findFirst();
    }

    private static Optional<TreeItem<ConfigTreeItem>> filterTreeItem(
        TreeItem<ConfigTreeItem> item,
        String lowerQuery
    ) {
        ConfigTreeItem data = item.getValue();
        if (categoryMatches(data, lowerQuery)) {
            TreeItem<ConfigTreeItem> copy = copyTreeItem(item);
            copy.setExpanded(true);
            return Optional.of(copy);
        }
        if (fieldMatches(data, lowerQuery)) {
            return Optional.of(new TreeItem<>(data));
        }

        var matchedChildren = childrenOf(item)
            .map(child -> filterTreeItem(child, lowerQuery))
            .flatMap(Optional::stream)
            .toList();
        if (matchedChildren.isEmpty()) {
            return Optional.empty();
        }

        TreeItem<ConfigTreeItem> copy = new TreeItem<>(data);
        copy.setExpanded(true);
        copy.getChildren().setAll(matchedChildren);
        return Optional.of(copy);
    }

    private static boolean categoryMatches(
        ConfigTreeItem data,
        String lowerQuery
    ) {
        return Optional
            .ofNullable(data)
            .filter(ConfigTreeItem::isCategory)
            .map(ConfigTreeItem::getName)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .filter(name -> name.contains(lowerQuery))
            .isPresent();
    }

    private static boolean fieldMatches(
        ConfigTreeItem data,
        String lowerQuery
    ) {
        return Optional
            .ofNullable(data)
            .filter(item -> !item.isCategory())
            .filter(item ->
                Optional
                    .ofNullable(item.getName())
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .filter(name -> name.contains(lowerQuery))
                    .isPresent() ||
                Optional
                    .ofNullable(item.getFieldName())
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .filter(name -> name.contains(lowerQuery))
                    .isPresent()
            )
            .isPresent();
    }

    private static java.util.stream.Stream<TreeItem<ConfigTreeItem>> childrenOf(
        TreeItem<ConfigTreeItem> item
    ) {
        return Optional
            .ofNullable(item)
            .map(TreeItem::getChildren)
            .stream()
            .flatMap(java.util.Collection::stream);
    }
}
