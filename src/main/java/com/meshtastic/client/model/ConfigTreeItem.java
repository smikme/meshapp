package com.meshtastic.client.model;

import com.google.protobuf.Descriptors;

import java.util.List;

/**
 * Data model for a configuration tree node.
 * Represents either one protobuf config field or a category section.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ConfigTreeItem {

    private final String name;              // Display name, such as Role or LoRa.
    private final String fieldName;         // Protobuf field name used for saving.
    private Object value;                   // Current value.
    private Object originalValue;           // Original value used for change tracking.
    private final Class<?> valueType;       // Value type: Boolean, Integer, Long, Float, Double, String, or Enum.
    private List<?> enumValues;             // Allowed enum values, or null for non-enum fields.
    private final boolean editable;         // true for fields, false for categories.
    private final boolean category;         // Root category such as device, position, or mqtt.
    private final String actionLabel;       // Button label for action rows.
    private final Runnable action;          // Action invoked by value-column button.
    private boolean structurallyModified;

    // Used when writing values back into protobuf.
    private final Descriptors.FieldDescriptor fieldDescriptor;   // null for categories.
    private String configType;              // "config" or "module_config".
    private int configVariantNumber;        // oneof variant number, e.g. 1=device, 2=position.

    /**
     * Constructor for a category section.
     */
    public ConfigTreeItem(String name, String configType, int configVariantNumber) {
        this(name, null, (Descriptors.FieldDescriptor) null, configType, configVariantNumber);
    }

    /**
     * Constructor for a category or nested group bound to a protobuf field.
     */
    public ConfigTreeItem(String name, String fieldName, Descriptors.FieldDescriptor fieldDescriptor,
                          String configType, int configVariantNumber) {
        this.name = name;
        this.fieldName = fieldName;
        this.value = null;
        this.originalValue = null;
        this.valueType = null;
        this.enumValues = null;
        this.editable = false;
        this.category = true;
        this.actionLabel = null;
        this.action = null;
        this.fieldDescriptor = fieldDescriptor;
        this.configType = configType;
        this.configVariantNumber = configVariantNumber;
    }

    /**
     * Constructor for an editable field.
     */
    public ConfigTreeItem(String name, String fieldName, Object value, Class<?> valueType,
                           List<?> enumValues, Descriptors.FieldDescriptor fieldDescriptor,
                           String configType, int configVariantNumber) {
        this.name = name;
        this.fieldName = fieldName;
        this.value = value;
        this.originalValue = value;
        this.valueType = valueType;
        this.enumValues = enumValues;
        this.editable = true;
        this.category = false;
        this.actionLabel = null;
        this.action = null;
        this.fieldDescriptor = fieldDescriptor;
        this.configType = configType;
        this.configVariantNumber = configVariantNumber;
    }

    /**
     * Constructor for a non-editable row that exposes a value-column action.
     */
    public ConfigTreeItem(String name, String actionLabel, Runnable action,
                          String configType, int configVariantNumber) {
        this.name = name;
        this.fieldName = null;
        this.value = null;
        this.originalValue = null;
        this.valueType = null;
        this.enumValues = null;
        this.editable = false;
        this.category = false;
        this.actionLabel = actionLabel;
        this.action = action;
        this.fieldDescriptor = null;
        this.configType = configType;
        this.configVariantNumber = configVariantNumber;
    }

    public String getName() { return name; }
    public String getFieldName() { return fieldName; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public Object getOriginalValue() { return originalValue; }
    public void resetOriginal() {
        this.originalValue = this.value;
        this.structurallyModified = false;
    }

    public void markStructurallyModified() { this.structurallyModified = true; }

    public Class<?> getValueType() { return valueType; }
    public List<?> getEnumValues() { return enumValues; }
    public void setEnumValues(List<?> enumValues) { this.enumValues = enumValues; }
    public boolean isEditable() { return editable; }
    public boolean isCategory() { return category; }
    public boolean hasAction() { return action != null; }
    public String getActionLabel() { return actionLabel; }
    public Runnable getAction() { return action; }

    public Descriptors.FieldDescriptor getFieldDescriptor() { return fieldDescriptor; }

    public String getConfigType() { return configType; }
    public void setConfigType(String configType) { this.configType = configType; }

    public int getConfigVariantNumber() { return configVariantNumber; }
    public void setConfigVariantNumber(int configVariantNumber) { this.configVariantNumber = configVariantNumber; }

    /**
     * Checks whether the value was changed by the user.
     */
    public boolean isModified() {
        if (structurallyModified) { return true; }
        if (!editable) { return false; }
        if (value == null && originalValue == null) { return false; }
        if (value == null || originalValue == null) { return true; }
        return !value.equals(originalValue);
    }

    @Override
    public String toString() {
        if (category) { return name; }
        return name + " = " + value;
    }
}
