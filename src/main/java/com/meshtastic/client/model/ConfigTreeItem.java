package com.meshtastic.client.model;

import com.google.protobuf.Descriptors;

import java.util.List;

/**
 * Модель данных для узла дерева конфигурации.
 * Представляет одно поле protobuf-конфига или категорию (секцию).
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ConfigTreeItem {

    private final String name;              // Отображаемое имя ("Роль", "LoRa" и т.д.)
    private final String fieldName;         // Protobuf field name (для сохранения)
    private Object value;                   // Текущее значение
    private Object originalValue;           // Исходное значение (для отслеживания изменений)
    private final Class<?> valueType;       // Тип значения (Boolean, Integer, Long, Float, Double, String, Enum)
    private final List<?> enumValues;       // Для enum — список допустимых значений (null для не-enum)
    private final boolean editable;         // true для полей, false для категорий
    private final boolean category;         // Корневая категория (device, position, mqtt...)

    // Для обратной записи в protobuf:
    private final Descriptors.FieldDescriptor fieldDescriptor;   // null для категорий
    private String configType;              // "config" или "module_config"
    private int configVariantNumber;        // Номер oneof варианта (1=device, 2=position, ...)

    /**
     * Конструктор для категории (секции).
     */
    public ConfigTreeItem(String name, String configType, int configVariantNumber) {
        this(name, null, null, configType, configVariantNumber);
    }

    /**
     * Конструктор для категории (секции/вложенной группы) с привязкой к protobuf-полю.
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
        this.fieldDescriptor = fieldDescriptor;
        this.configType = configType;
        this.configVariantNumber = configVariantNumber;
    }

    /**
     * Конструктор для редактируемого поля.
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
        this.fieldDescriptor = fieldDescriptor;
        this.configType = configType;
        this.configVariantNumber = configVariantNumber;
    }

    public String getName() { return name; }
    public String getFieldName() { return fieldName; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public Object getOriginalValue() { return originalValue; }
    public void resetOriginal() { this.originalValue = this.value; }

    public Class<?> getValueType() { return valueType; }
    public List<?> getEnumValues() { return enumValues; }
    public boolean isEditable() { return editable; }
    public boolean isCategory() { return category; }

    public Descriptors.FieldDescriptor getFieldDescriptor() { return fieldDescriptor; }

    public String getConfigType() { return configType; }
    public void setConfigType(String configType) { this.configType = configType; }

    public int getConfigVariantNumber() { return configVariantNumber; }
    public void setConfigVariantNumber(int configVariantNumber) { this.configVariantNumber = configVariantNumber; }

    /**
     * Проверяет, было ли значение изменено пользователем.
     */
    public boolean isModified() {
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
