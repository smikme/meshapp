package com.meshtastic.client.utils;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.meshtastic.client.model.ConfigTreeItem;

import java.util.Set;

/**
 * Преобразует машинные protobuf-значения к более понятному для пользователя виду
 * и обратно. Сейчас используется для IPv4-адресов, которые в protobuf хранятся
 * как fixed32 little-endian числа.
 */
public final class ConfigValueFormatter {

    private static final long UINT32_MAX = 0xFFFF_FFFFL;
    private static final Set<String> HUMAN_READABLE_IPV4_FIELDS = Set.of(
            "meshtastic.Config.NetworkConfig.IpV4Config.ip",
            "meshtastic.Config.NetworkConfig.IpV4Config.gateway",
            "meshtastic.Config.NetworkConfig.IpV4Config.subnet",
            "meshtastic.Config.NetworkConfig.IpV4Config.dns",
            "meshtastic.NetworkConnectionStatus.ip_address"
    );

    private ConfigValueFormatter() {}

    /**
     * Форматирует значение поля дерева для отображения в UI.
     *
     * @param item узел дерева конфигурации
     * @return строка для показа пользователю
     */
    public static String formatValue(ConfigTreeItem item) {
        if (item == null) {
            return "";
        }
        return formatValue(item.getFieldDescriptor(), item.getValue());
    }

    /**
     * Форматирует исходное protobuf-значение по его descriptor.
     * Для известных IPv4-полей возвращает dotted-decimal строку.
     *
     * @param fieldDescriptor protobuf-descriptor поля
     * @param value исходное значение поля
     * @return строка для отображения в редакторе
     */
    public static String formatValue(FieldDescriptor fieldDescriptor, Object value) {
        if (value == null) {
            return "";
        }
        if (isHumanReadableIpv4Field(fieldDescriptor) && value instanceof Number number) {
            return formatIpv4(number.intValue());
        }
        return value.toString();
    }

    /**
     * Разбирает текст, введённый пользователем в редакторе, в тип значения дерева.
     *
     * @param item узел дерева конфигурации
     * @param text текст, введённый пользователем
     * @return значение, готовое к сохранению в дереве
     */
    public static Object parseTextValue(ConfigTreeItem item, String text) {
        if (item == null) {
            return text;
        }
        return parseTextValue(item.getFieldDescriptor(), item.getValueType(), text);
    }

    /**
     * Разбирает пользовательский текст с учётом protobuf-descriptor и ожидаемого Java-типа.
     * Для известных IPv4-полей поддерживает как dotted-decimal ввод, так и legacy uint32.
     *
     * @param fieldDescriptor protobuf-descriptor поля
     * @param valueType ожидаемый Java-тип значения
     * @param text текст, введённый пользователем
     * @return разобранное значение поля
     */
    public static Object parseTextValue(FieldDescriptor fieldDescriptor, Class<?> valueType, String text) {
        if (valueType == null || valueType == String.class) {
            return text;
        }

        String candidate = text != null ? text.trim() : "";
        if (valueType == Integer.class) {
            if (isHumanReadableIpv4Field(fieldDescriptor)) {
                return parseIpv4(candidate);
            }
            return Integer.parseInt(candidate);
        }
        if (valueType == Long.class) {
            return Long.parseLong(candidate);
        }
        if (valueType == Float.class) {
            return Float.parseFloat(candidate);
        }
        if (valueType == Double.class) {
            return Double.parseDouble(candidate);
        }
        return text;
    }

    /**
     * Возвращает пример значения для placeholder у полей со специальным форматом ввода.
     *
     * @param item узел дерева конфигурации
     * @return пример пользовательского ввода или {@code null}, если подсказка не нужна
     */
    public static String promptText(ConfigTreeItem item) {
        return item != null && isHumanReadableIpv4Field(item.getFieldDescriptor())
                ? "192.168.1.10"
                : null;
    }

    /**
     * Возвращает подсказку валидации для полей со специальным форматом ввода.
     *
     * @param item узел дерева конфигурации
     * @return текст подсказки или {@code null}, если подсказка не нужна
     */
    public static String validationHint(ConfigTreeItem item) {
        return item != null && isHumanReadableIpv4Field(item.getFieldDescriptor())
                ? "Введите IPv4-адрес в формате 192.168.1.10"
                : null;
    }

    /**
     * Проверяет, нужно ли отображать значение поля как IPv4-адрес.
     *
     * @param fieldDescriptor protobuf-descriptor поля
     * @return {@code true}, если поле должно показываться в dotted-decimal виде
     */
    static boolean isHumanReadableIpv4Field(FieldDescriptor fieldDescriptor) {
        return fieldDescriptor != null
                && fieldDescriptor.getType() == FieldDescriptor.Type.FIXED32
                && HUMAN_READABLE_IPV4_FIELDS.contains(fieldDescriptor.getFullName());
    }

    /**
     * Преобразует little-endian fixed32 IPv4 в dotted-decimal строку.
     *
     * @param rawValue исходное protobuf-значение IPv4
     * @return IPv4-адрес в формате {@code a.b.c.d}
     */
    static String formatIpv4(int rawValue) {
        long unsigned = Integer.toUnsignedLong(rawValue);
        long octet1 = unsigned & 0xFF;
        long octet2 = (unsigned >>> 8) & 0xFF;
        long octet3 = (unsigned >>> 16) & 0xFF;
        long octet4 = (unsigned >>> 24) & 0xFF;
        return "%d.%d.%d.%d".formatted(octet1, octet2, octet3, octet4);
    }

    /**
     * Преобразует dotted-decimal IPv4 в protobuf fixed32 little-endian значение.
     *
     * @param text IPv4-адрес в пользовательском формате
     * @return значение для protobuf fixed32 поля
     * @throws IllegalArgumentException если адрес имеет неверный формат
     */
    static int parseIpv4(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("IPv4-адрес не может быть пустым");
        }

        if (!text.contains(".")) {
            return parseLegacyIpv4Number(text);
        }

        String[] parts = text.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("IPv4-адрес должен содержать 4 октета");
        }

        long value = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                throw new IllegalArgumentException("IPv4-адрес содержит пустой октет");
            }

            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("IPv4-адрес содержит нечисловой октет", e);
            }

            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException("Каждый октет IPv4-адреса должен быть в диапазоне 0-255");
            }
            value |= ((long) octet) << (8 * i);
        }

        return (int) value;
    }

    /**
     * Поддерживает старый способ ввода IPv4 как uint32, чтобы импорт и ручное редактирование
     * уже сохранённых значений оставались обратно совместимыми.
     *
     * @param text строковое представление uint32
     * @return значение для protobuf fixed32 поля
     * @throws IllegalArgumentException если число не удалось разобрать
     */
    private static int parseLegacyIpv4Number(String text) {
        try {
            if (text.startsWith("-")) {
                return Integer.parseInt(text);
            }

            long unsigned = Long.parseLong(text);
            if (unsigned < 0 || unsigned > UINT32_MAX) {
                throw new IllegalArgumentException("Числовое представление IPv4 вне диапазона uint32");
            }
            return (int) unsigned;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Введите IPv4-адрес в формате 192.168.1.10 или старое uint32-значение",
                    e
            );
        }
    }
}
