package com.meshtastic.client.utils;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.meshtastic.client.model.ConfigTreeItem;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.PowerMonProtos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Преобразует машинные protobuf-значения к более понятному для пользователя виду
 * и обратно. Используется для IPv4-адресов, которые в protobuf хранятся
 * как fixed32 little-endian числа, и для bitmask-полей, которые удобнее
 * редактировать как набор флагов, а не как сырое число.
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
    private static final Set<String> HUMAN_READABLE_NODE_ID_FIELDS = Set.of(
            "meshtastic.Config.LoRaConfig.ignore_incoming"
    );
    private static final Set<String> HUMAN_READABLE_HEX_FIELDS = Set.of(
            "meshtastic.Config.PowerConfig.device_battery_ina_address"
    );
    private static final Map<String, BitmaskFieldSpec> BITMASK_FIELD_SPECS = Map.of(
            "meshtastic.Config.NetworkConfig.enabled_protocols",
            new BitmaskFieldSpec("Отключено", List.of(
                    new BitmaskOption(ConfigProtos.Config.NetworkConfig.ProtocolFlags.UDP_BROADCAST.getNumber(),
                            "UDP broadcast (локальная сеть)")
            )),
            "meshtastic.Config.PositionConfig.position_flags",
            new BitmaskFieldSpec("Не выбрано", List.of(
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.ALTITUDE.getNumber(),
                            "Высота"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.ALTITUDE_MSL.getNumber(),
                            "Высота MSL"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.GEOIDAL_SEPARATION.getNumber(),
                            "Разделение геоида"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.DOP.getNumber(),
                            "DOP"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.HVDOP.getNumber(),
                            "HDOP/VDOP"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.SATINVIEW.getNumber(),
                            "Спутники в видимости"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.SEQ_NO.getNumber(),
                            "Порядковый номер"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.TIMESTAMP.getNumber(),
                            "Временная метка"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.HEADING.getNumber(),
                            "Курс"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.SPEED.getNumber(),
                            "Скорость")
            )),
            "meshtastic.Config.PowerConfig.powermon_enables",
            new BitmaskFieldSpec("Выключено", List.of(
                    new BitmaskOption(PowerMonProtos.PowerMon.State.CPU_DeepSleep.getNumber(), "CPU Deep Sleep"),
                    new BitmaskOption(PowerMonProtos.PowerMon.State.CPU_LightSleep.getNumber(), "CPU Light Sleep"),
                    new BitmaskOption(PowerMonProtos.PowerMon.State.Vext1_On.getNumber(), "Vext1 On"),
                    new BitmaskOption(PowerMonProtos.PowerMon.State.Lora_RXOn.getNumber(), "LoRa RX On"),
                    new BitmaskOption(PowerMonProtos.PowerMon.State.Lora_TXOn.getNumber(), "LoRa TX On"),
                    new BitmaskOption(PowerMonProtos.PowerMon.State.Lora_RXActive.getNumber(), "LoRa RX Active"),
                    new BitmaskOption(PowerMonProtos.PowerMon.State.BT_On.getNumber(), "Bluetooth On"),
                    new BitmaskOption(PowerMonProtos.PowerMon.State.LED_On.getNumber(), "LED On"),
                    new BitmaskOption(PowerMonProtos.PowerMon.State.Screen_On.getNumber(), "Screen On"),
                    new BitmaskOption(PowerMonProtos.PowerMon.State.Screen_Drawing.getNumber(), "Screen Drawing"),
                    new BitmaskOption(PowerMonProtos.PowerMon.State.Wifi_On.getNumber(), "WiFi On"),
                    new BitmaskOption(PowerMonProtos.PowerMon.State.GPS_Active.getNumber(), "GPS Active")
            ))
    );

    private ConfigValueFormatter() {}

    /**
     * Набор флагов, из которых собирается целочисленное значение bitmask-поля.
     *
     * @param mask числовая маска флага
     * @param label пользовательское имя флага
     */
    public record BitmaskOption(long mask, String label) {}

    private record BitmaskFieldSpec(String zeroLabel, List<BitmaskOption> options) {}

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
        BitmaskFieldSpec bitmaskSpec = bitmaskFieldSpec(fieldDescriptor);
        if (bitmaskSpec != null && value instanceof Number number) {
            return formatBitmaskValue(bitmaskSpec, toUnsignedLong(number));
        }
        if (isHumanReadableIpv4Field(fieldDescriptor) && value instanceof Number number) {
            return formatIpv4(number.intValue());
        }
        if (isHumanReadableNodeIdField(fieldDescriptor) && value instanceof Number number) {
            return formatNodeId(number.intValue());
        }
        if (isHumanReadableHexField(fieldDescriptor) && value instanceof Number number) {
            return formatHex(number.intValue());
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
            if (isHumanReadableNodeIdField(fieldDescriptor)) {
                return parseNodeId(candidate);
            }
            if (isHumanReadableHexField(fieldDescriptor)) {
                return parseHexInt(candidate);
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
     * Проверяет, есть ли у поля набор выбираемых bitmask-флагов вместо ручного ввода числа.
     *
     * @param item узел дерева конфигурации
     * @return {@code true}, если поле нужно редактировать через выбор флагов
     */
    public static boolean hasBitmaskOptions(ConfigTreeItem item) {
        return item != null && bitmaskFieldSpec(item.getFieldDescriptor()) != null;
    }

    /**
     * Возвращает список битовых опций для поля.
     *
     * @param item узел дерева конфигурации
     * @return список доступных флагов; пустой, если поле не является bitmask
     */
    public static List<BitmaskOption> bitmaskOptions(ConfigTreeItem item) {
        BitmaskFieldSpec spec = item != null ? bitmaskFieldSpec(item.getFieldDescriptor()) : null;
        return spec != null ? spec.options() : List.of();
    }

    /**
     * Проверяет, включён ли конкретный флаг в текущем значении поля.
     *
     * @param item узел дерева конфигурации
     * @param option опция bitmask
     * @return {@code true}, если флаг выбран
     */
    public static boolean isBitmaskOptionSelected(ConfigTreeItem item, BitmaskOption option) {
        if (item == null || option == null || !(item.getValue() instanceof Number number)) {
            return false;
        }
        long mask = toUnsignedLong(number);
        return (mask & option.mask()) == option.mask();
    }

    /**
     * Собирает новое значение bitmask-поля из списка выбранных флагов.
     *
     * @param item узел дерева конфигурации
     * @param selectedOptions выбранные пользователем флаги
     * @return значение в типе, совместимом с полем дерева
     */
    public static Object buildBitmaskValue(ConfigTreeItem item, List<BitmaskOption> selectedOptions) {
        long mask = 0L;
        for (BitmaskOption option : selectedOptions) {
            if (option != null) {
                mask |= option.mask();
            }
        }

        Class<?> valueType = item != null ? item.getValueType() : null;
        if (valueType == Long.class) {
            return mask;
        }
        return (int) mask;
    }

    /**
     * Возвращает пример значения для placeholder у полей со специальным форматом ввода.
     *
     * @param item узел дерева конфигурации
     * @return пример пользовательского ввода или {@code null}, если подсказка не нужна
     */
    public static String promptText(ConfigTreeItem item) {
        if (item == null) {
            return null;
        }
        FieldDescriptor fieldDescriptor = item.getFieldDescriptor();
        if (isHumanReadableIpv4Field(fieldDescriptor)) {
            return "192.168.1.10";
        }
        if (isHumanReadableNodeIdField(fieldDescriptor)) {
            return "!1234abcd";
        }
        if (isHumanReadableHexField(fieldDescriptor)) {
            return "0x40";
        }
        return null;
    }

    /**
     * Возвращает подсказку валидации для полей со специальным форматом ввода.
     *
     * @param item узел дерева конфигурации
     * @return текст подсказки или {@code null}, если подсказка не нужна
     */
    public static String validationHint(ConfigTreeItem item) {
        if (item == null) {
            return null;
        }
        FieldDescriptor fieldDescriptor = item.getFieldDescriptor();
        if (isHumanReadableIpv4Field(fieldDescriptor)) {
            return "Введите IPv4-адрес в формате 192.168.1.10";
        }
        if (isHumanReadableNodeIdField(fieldDescriptor)) {
            return "Введите node ID в формате !1234abcd или uint32-значение";
        }
        if (isHumanReadableHexField(fieldDescriptor)) {
            return "Введите I2C-адрес в формате 0x40 или десятичное значение";
        }
        return null;
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
     * Проверяет, нужно ли отображать значение поля как node ID.
     *
     * @param fieldDescriptor protobuf-descriptor поля
     * @return {@code true}, если поле должно показываться в формате {@code !XXXXXXXX}
     */
    static boolean isHumanReadableNodeIdField(FieldDescriptor fieldDescriptor) {
        return fieldDescriptor != null
                && isUint32LikeField(fieldDescriptor)
                && HUMAN_READABLE_NODE_ID_FIELDS.contains(fieldDescriptor.getFullName());
    }

    /**
     * Проверяет, нужно ли отображать значение поля в шестнадцатеричном виде.
     *
     * @param fieldDescriptor protobuf-descriptor поля
     * @return {@code true}, если поле должно показываться в формате {@code 0x..}
     */
    static boolean isHumanReadableHexField(FieldDescriptor fieldDescriptor) {
        return fieldDescriptor != null
                && isUint32LikeField(fieldDescriptor)
                && HUMAN_READABLE_HEX_FIELDS.contains(fieldDescriptor.getFullName());
    }

    private static BitmaskFieldSpec bitmaskFieldSpec(FieldDescriptor fieldDescriptor) {
        return fieldDescriptor != null ? BITMASK_FIELD_SPECS.get(fieldDescriptor.getFullName()) : null;
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
     * Преобразует номер ноды в стандартный Meshtastic node ID.
     *
     * @param rawValue номер ноды
     * @return node ID в формате {@code !XXXXXXXX}
     */
    static String formatNodeId(int rawValue) {
        return String.format("!%08x", rawValue);
    }

    /**
     * Преобразует числовой адрес шины в hex-строку.
     *
     * @param rawValue числовой адрес
     * @return адрес в формате {@code 0x40}
     */
    static String formatHex(int rawValue) {
        return String.format("0x%02X", Integer.toUnsignedLong(rawValue));
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
     * Разбирает пользовательский node ID или uint32-значение в номер ноды.
     *
     * @param text node ID в формате {@code !XXXXXXXX}, hex или uint32
     * @return номер ноды как int
     * @throws IllegalArgumentException если значение не удалось разобрать
     */
    static int parseNodeId(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Node ID не может быть пустым");
        }

        String candidate = text.trim();
        try {
            if (candidate.startsWith("!")) {
                return (int) Long.parseUnsignedLong(candidate.substring(1), 16);
            }
            if (candidate.startsWith("0x") || candidate.startsWith("0X")) {
                return (int) Long.parseUnsignedLong(candidate.substring(2), 16);
            }
            long value = Long.parseLong(candidate);
            if (value < 0 || value > UINT32_MAX) {
                throw new IllegalArgumentException("Номер ноды вне диапазона uint32");
            }
            return (int) value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Введите node ID в формате !1234abcd или uint32-значение", e);
        }
    }

    /**
     * Разбирает hex-строку или десятичное значение в uint32-поле.
     *
     * @param text hex или decimal строка
     * @return int-значение для protobuf uint32-поля
     * @throws IllegalArgumentException если значение не удалось разобрать
     */
    static int parseHexInt(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Значение не может быть пустым");
        }

        String candidate = text.trim();
        try {
            if (candidate.startsWith("0x") || candidate.startsWith("0X")) {
                return (int) Long.parseUnsignedLong(candidate.substring(2), 16);
            }
            return Integer.parseInt(candidate);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Введите hex-значение в формате 0x40 или десятичное число", e);
        }
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

    private static String formatBitmaskValue(BitmaskFieldSpec spec, long mask) {
        if (mask == 0) {
            return spec.zeroLabel();
        }

        long remainingMask = mask;
        List<String> selectedLabels = new ArrayList<>();
        for (BitmaskOption option : spec.options()) {
            if ((mask & option.mask()) == option.mask()) {
                selectedLabels.add(option.label());
                remainingMask &= ~option.mask();
            }
        }

        if (remainingMask != 0) {
            selectedLabels.add("0x" + Long.toHexString(remainingMask).toUpperCase(Locale.ROOT));
        }

        return selectedLabels.isEmpty() ? spec.zeroLabel() : String.join(", ", selectedLabels);
    }

    private static long toUnsignedLong(Number number) {
        return number instanceof Long
                ? number.longValue()
                : Integer.toUnsignedLong(number.intValue());
    }

    private static boolean isUint32LikeField(FieldDescriptor fieldDescriptor) {
        return fieldDescriptor.getType() == FieldDescriptor.Type.UINT32
                || fieldDescriptor.getType() == FieldDescriptor.Type.INT32
                || fieldDescriptor.getType() == FieldDescriptor.Type.SINT32
                || fieldDescriptor.getType() == FieldDescriptor.Type.FIXED32
                || fieldDescriptor.getType() == FieldDescriptor.Type.SFIXED32;
    }
}
