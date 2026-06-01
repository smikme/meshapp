package com.meshtastic.client.utils;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.PowerMonProtos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts raw protobuf values to user-friendly text and back.
 * This covers IPv4 addresses stored in protobuf as little-endian fixed32 values
 * and bitmask fields that are easier to edit as a set of flags than as a raw
 * number.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
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
            new BitmaskFieldSpec("settings.config.bitmask.disabled", List.of(
                    new BitmaskOption(ConfigProtos.Config.NetworkConfig.ProtocolFlags.UDP_BROADCAST.getNumber(),
                            "settings.config.bitmask.udpBroadcastLocal")
            )),
            "meshtastic.Config.PositionConfig.position_flags",
            new BitmaskFieldSpec("settings.config.bitmask.notSelected", List.of(
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.ALTITUDE.getNumber(),
                            "settings.config.bitmask.altitude"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.ALTITUDE_MSL.getNumber(),
                            "settings.config.bitmask.altitudeMsl"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.GEOIDAL_SEPARATION.getNumber(),
                            "settings.config.bitmask.geoidalSeparation"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.DOP.getNumber(),
                            "DOP"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.HVDOP.getNumber(),
                            "HDOP/VDOP"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.SATINVIEW.getNumber(),
                            "settings.config.bitmask.satellitesInView"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.SEQ_NO.getNumber(),
                            "settings.config.bitmask.sequenceNumber"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.TIMESTAMP.getNumber(),
                            "settings.config.bitmask.timestamp"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.HEADING.getNumber(),
                            "settings.config.bitmask.heading"),
                    new BitmaskOption(ConfigProtos.Config.PositionConfig.PositionFlags.SPEED.getNumber(),
                            "settings.config.bitmask.speed")
            )),
            "meshtastic.Config.PowerConfig.powermon_enables",
            new BitmaskFieldSpec("settings.config.bitmask.off", List.of(
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
     * One flag option used to compose an integer bitmask field value.
 *
     * @param mask     numeric flag mask
     * @param labelKey user-facing label key
     */
    public record BitmaskOption(long mask, String labelKey) {
        public String label() {
            String translated = I18n.t(labelKey);
            return translated.startsWith("!") && translated.endsWith("!") ? labelKey : translated;
        }
    }

    private record BitmaskFieldSpec(String zeroLabelKey, List<BitmaskOption> options) {
        String zeroLabel() {
            return I18n.t(zeroLabelKey);
        }
    }

    /**
     * Formats a tree field value for display in the UI.
 *
     * @param item configuration tree node
     * @return text shown to the user
     */
    public static String formatValue(ConfigTreeItem item) {
        if (item == null) {
            return "";
        }
        return formatValue(item.getFieldDescriptor(), item.getValue());
    }

    /**
     * Formats a raw protobuf value according to its descriptor.
     * Known IPv4 fields are returned as dotted-decimal strings.
 *
     * @param fieldDescriptor protobuf field descriptor
     * @param value           raw field value
     * @return text displayed in the editor
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
     * Parses text entered by the user into the value type expected by the tree.
 *
     * @param item configuration tree node
     * @param text text entered by the user
     * @return value ready to be stored in the tree
     */
    public static Object parseTextValue(ConfigTreeItem item, String text) {
        if (item == null) {
            return text;
        }
        return parseTextValue(item.getFieldDescriptor(), item.getValueType(), text);
    }

    /**
     * Parses user text using the protobuf descriptor and expected Java type.
     * Known IPv4 fields accept both dotted-decimal input and legacy uint32 input.
 *
     * @param fieldDescriptor protobuf field descriptor
     * @param valueType       expected Java value type
     * @param text            text entered by the user
     * @return parsed field value
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
     * Checks whether the field should be edited as selectable bitmask flags.
 *
     * @param item configuration tree node
     * @return {@code true} when the field should be edited through flag selection
     */
    public static boolean hasBitmaskOptions(ConfigTreeItem item) {
        return item != null && bitmaskFieldSpec(item.getFieldDescriptor()) != null;
    }

    /**
     * Returns the bit options available for a field.
 *
     * @param item configuration tree node
     * @return available flags, or an empty list when the field is not a bitmask
     */
    public static List<BitmaskOption> bitmaskOptions(ConfigTreeItem item) {
        BitmaskFieldSpec spec = item != null ? bitmaskFieldSpec(item.getFieldDescriptor()) : null;
        return spec != null ? spec.options() : List.of();
    }

    /**
     * Checks whether a specific flag is enabled in the current field value.
 *
     * @param item   configuration tree node
     * @param option bitmask option
     * @return {@code true} when the flag is selected
     */
    public static boolean isBitmaskOptionSelected(ConfigTreeItem item, BitmaskOption option) {
        if (item == null || option == null || !(item.getValue() instanceof Number number)) {
            return false;
        }
        long mask = toUnsignedLong(number);
        return (mask & option.mask()) == option.mask();
    }

    /**
     * Builds a new bitmask field value from the selected flags.
 *
     * @param item            configuration tree node
     * @param selectedOptions flags selected by the user
     * @return value with a type compatible with the tree field
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
     * Returns an example placeholder for fields with special input formats.
 *
     * @param item configuration tree node
     * @return user-input example, or {@code null} when no hint is needed
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
     * Returns a validation hint for fields with special input formats.
 *
     * @param item configuration tree node
     * @return hint text, or {@code null} when no hint is needed
     */
    public static String validationHint(ConfigTreeItem item) {
        if (item == null) {
            return null;
        }
        FieldDescriptor fieldDescriptor = item.getFieldDescriptor();
        if (isHumanReadableIpv4Field(fieldDescriptor)) {
            return I18n.t("settings.config.validation.ipv4Hint");
        }
        if (isHumanReadableNodeIdField(fieldDescriptor)) {
            return I18n.t("settings.config.validation.nodeIdHint");
        }
        if (isHumanReadableHexField(fieldDescriptor)) {
            return I18n.t("settings.config.validation.i2cHint");
        }
        return null;
    }

    /**
     * Checks whether the field should be displayed as an IPv4 address.
 *
     * @param fieldDescriptor protobuf field descriptor
     * @return {@code true} when the field should be shown as dotted-decimal text
     */
    static boolean isHumanReadableIpv4Field(FieldDescriptor fieldDescriptor) {
        return fieldDescriptor != null
                && fieldDescriptor.getType() == FieldDescriptor.Type.FIXED32
                && HUMAN_READABLE_IPV4_FIELDS.contains(fieldDescriptor.getFullName());
    }

    /**
     * Checks whether the field should be displayed as a node ID.
 *
     * @param fieldDescriptor protobuf field descriptor
     * @return {@code true} when the field should be shown as {@code !XXXXXXXX}
     */
    static boolean isHumanReadableNodeIdField(FieldDescriptor fieldDescriptor) {
        return fieldDescriptor != null
                && isUint32LikeField(fieldDescriptor)
                && HUMAN_READABLE_NODE_ID_FIELDS.contains(fieldDescriptor.getFullName());
    }

    /**
     * Checks whether the field should be displayed in hexadecimal form.
 *
     * @param fieldDescriptor protobuf field descriptor
     * @return {@code true} when the field should be shown as {@code 0x..}
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
     * Converts a little-endian fixed32 IPv4 value to dotted-decimal text.
 *
     * @param rawValue raw protobuf IPv4 value
     * @return IPv4 address in {@code a.b.c.d} form
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
     * Converts a node number to the standard Meshtastic node ID.
 *
     * @param rawValue node number
     * @return node ID in {@code !XXXXXXXX} form
     */
    static String formatNodeId(int rawValue) {
        return String.format("!%08x", rawValue);
    }

    /**
     * Converts a numeric bus address to a hex string.
 *
     * @param rawValue numeric address
     * @return address in {@code 0x40} form
     */
    static String formatHex(int rawValue) {
        return String.format("0x%02X", Integer.toUnsignedLong(rawValue));
    }

    /**
     * Converts dotted-decimal IPv4 text to a protobuf little-endian fixed32 value.
 *
     * @param text IPv4 address in user-facing format
     * @return value for the protobuf fixed32 field
     * @throws IllegalArgumentException when the address format is invalid
     */
    static int parseIpv4(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(I18n.t("settings.config.validation.ipv4Empty"));
        }

        if (!text.contains(".")) {
            return parseLegacyIpv4Number(text);
        }

        String[] parts = text.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException(I18n.t("settings.config.validation.ipv4OctetCount"));
        }

        long value = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                throw new IllegalArgumentException(I18n.t("settings.config.validation.ipv4EmptyOctet"));
            }

            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(I18n.t("settings.config.validation.ipv4NonNumericOctet"), e);
            }

            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException(I18n.t("settings.config.validation.ipv4OctetRange"));
            }
            value |= ((long) octet) << (8 * i);
        }

        return (int) value;
    }

    /**
     * Parses a user-entered node ID or uint32 value into a node number.
 *
     * @param text node ID in {@code !XXXXXXXX} form, hex, or uint32
     * @return node number as int
     * @throws IllegalArgumentException when the value cannot be parsed
     */
    static int parseNodeId(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(I18n.t("settings.config.validation.nodeIdEmpty"));
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
                throw new IllegalArgumentException(I18n.t("settings.config.validation.nodeIdRange"));
            }
            return (int) value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(I18n.t("settings.config.validation.nodeIdInvalid"), e);
        }
    }

    /**
     * Parses a hex string or decimal value into a uint32 field.
 *
     * @param text hex or decimal string
     * @return int value for the protobuf uint32 field
     * @throws IllegalArgumentException when the value cannot be parsed
     */
    static int parseHexInt(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(I18n.t("settings.config.validation.emptyValue"));
        }

        String candidate = text.trim();
        try {
            if (candidate.startsWith("0x") || candidate.startsWith("0X")) {
                return (int) Long.parseUnsignedLong(candidate.substring(2), 16);
            }
            return Integer.parseInt(candidate);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(I18n.t("settings.config.validation.hexInvalid"), e);
        }
    }

    /**
     * Supports the legacy IPv4-as-uint32 input form so imports and manual edits
     * of already saved values remain backward compatible.
 *
     * @param text uint32 text representation
     * @return value for the protobuf fixed32 field
     * @throws IllegalArgumentException when the number cannot be parsed
     */
    private static int parseLegacyIpv4Number(String text) {
        try {
            if (text.startsWith("-")) {
                return Integer.parseInt(text);
            }

            long unsigned = Long.parseLong(text);
            if (unsigned < 0 || unsigned > UINT32_MAX) {
                throw new IllegalArgumentException(I18n.t("settings.config.validation.ipv4NumberRange"));
            }
            return (int) unsigned;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    I18n.t("settings.config.validation.ipv4LegacyInvalid"),
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
