package com.meshtastic.client.lua.api;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reflection mapper between protobuf messages and Lua tables.
 * <p>
 * Field names are exposed as protobuf {@code snake_case}. Enum values are
 * exposed by name. Bytes are rendered as hexadecimal strings and accepted as
 * hex, {@code hex:...}, {@code base64:...}, or plain Base64 strings.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaProtobufMapper {

    private static final Map<String, String> CONFIG_ALIASES = Map.of(
            "deviceui", "device_ui"
    );
    private static final Map<String, String> MODULE_ALIASES = Map.ofEntries(
            Map.entry("extnotif", "external_notification"),
            Map.entry("storeforward", "store_forward"),
            Map.entry("rangetest", "range_test"),
            Map.entry("cannedmsg", "canned_message"),
            Map.entry("remotehardware", "remote_hardware"),
            Map.entry("neighborinfo", "neighbor_info"),
            Map.entry("ambientlighting", "ambient_lighting"),
            Map.entry("detectionsensor", "detection_sensor"),
            Map.entry("trafficmanagement", "traffic_management")
    );

    /**
     * Converts any protobuf message to a Lua table.
     *
     * @param message protobuf message
     * @return Lua table
     */
    public LuaTable messageToTable(Message message) {
        LuaTable table = new LuaTable();
        if (message == null) {
            return table;
        }
        for (Descriptors.FieldDescriptor field : message.getDescriptorForType().getFields()) {
            Object value = message.getField(field);
            if (!field.isRepeated() && field.hasPresence() && !message.hasField(field)) {
                table.set(field.getName(), LuaValue.NIL);
                continue;
            }
            table.set(field.getName(), valueToLua(field, value));
        }
        return table;
    }

    /**
     * Converts a core config section to a named Lua table.
     *
     * @param config config section
     * @return table with {@code section} and {@code values}
     */
    public LuaTable configToTable(ConfigProtos.Config config) {
        LuaTable table = new LuaTable();
        if (config == null) {
            return table;
        }
        Descriptors.FieldDescriptor field = activePayloadField(config);
        if (field == null) {
            return table;
        }
        table.set("section", field.getName());
        table.set("values", messageToTable((Message) config.getField(field)));
        return table;
    }

    /**
     * Converts a module config section to a named Lua table.
     *
     * @param moduleConfig module config section
     * @return table with {@code section} and {@code values}
     */
    public LuaTable moduleConfigToTable(ModuleConfigProtos.ModuleConfig moduleConfig) {
        LuaTable table = new LuaTable();
        if (moduleConfig == null) {
            return table;
        }
        Descriptors.FieldDescriptor field = activePayloadField(moduleConfig);
        if (field == null) {
            return table;
        }
        table.set("section", field.getName());
        table.set("values", messageToTable((Message) moduleConfig.getField(field)));
        return table;
    }

    /**
     * Builds a core config patch, merging with an existing section unless
     * {@code replace} is true.
     */
    public ConfigProtos.Config buildConfig(String section,
                                           Map<String, Object> values,
                                           ConfigProtos.Config existing,
                                           boolean replace) {
        Descriptors.FieldDescriptor field = payloadField(
                ConfigProtos.Config.getDefaultInstance(),
                section,
                CONFIG_ALIASES);
        if (field == null) {
            throw new LuaError("Unknown config section: " + section);
        }
        Message existingSection = matchingSection(existing, field);
        if (!replace && existingSection == null) {
            throw new LuaError("Config section '" + field.getName()
                    + "' is not loaded; call mesh.admin.load_config/request_config first or pass { replace = true }");
        }
        Message.Builder sectionBuilder = existingSection != null
                ? existingSection.toBuilder()
                : ConfigProtos.Config.newBuilder().newBuilderForField(field);
        applyPatch(sectionBuilder, values);
        return ConfigProtos.Config.newBuilder()
                .setField(field, sectionBuilder.build())
                .build();
    }

    /**
     * Builds a module config patch, merging with an existing section unless
     * {@code replace} is true.
     */
    public ModuleConfigProtos.ModuleConfig buildModuleConfig(String section,
                                                             Map<String, Object> values,
                                                             ModuleConfigProtos.ModuleConfig existing,
                                                             boolean replace) {
        Descriptors.FieldDescriptor field = payloadField(
                ModuleConfigProtos.ModuleConfig.getDefaultInstance(),
                section,
                MODULE_ALIASES);
        if (field == null) {
            throw new LuaError("Unknown module config section: " + section);
        }
        Message existingSection = matchingSection(existing, field);
        if (!replace && existingSection == null) {
            throw new LuaError("Module config section '" + field.getName()
                    + "' is not loaded; call mesh.admin.load_config/request_module_config first or pass { replace = true }");
        }
        Message.Builder sectionBuilder = existingSection != null
                ? existingSection.toBuilder()
                : ModuleConfigProtos.ModuleConfig.newBuilder().newBuilderForField(field);
        applyPatch(sectionBuilder, values);
        return ModuleConfigProtos.ModuleConfig.newBuilder()
                .setField(field, sectionBuilder.build())
                .build();
    }

    /**
     * Builds a channel patch, merging with an existing channel unless
     * {@code replace} is true.
     */
    public ChannelProtos.Channel buildChannel(Map<String, Object> values,
                                              ChannelProtos.Channel existing,
                                              boolean replace) {
        if (values == null) {
            throw new LuaError("Channel patch must be a table");
        }
        Object indexValue = values.get("index");
        int index = indexValue != null ? toInt(indexValue) : existing != null ? existing.getIndex() : -1;
        if (index < 0) {
            throw new LuaError("Channel patch requires index");
        }
        if (!replace && existing == null) {
            throw new LuaError("Channel " + index
                    + " is not loaded; call mesh.admin.load_config first or pass { replace = true }");
        }
        ChannelProtos.Channel.Builder builder = existing != null
                ? existing.toBuilder()
                : ChannelProtos.Channel.newBuilder();
        applyPatch(builder, values);
        if (!values.containsKey("index")) {
            builder.setIndex(index);
        }
        return builder.build();
    }

    /**
     * Converts a Lua table to Java maps/lists suitable for protobuf patching.
     */
    public static Object luaToJava(LuaValue value) {
        if (value == null || value.isnil()) {
            return null;
        }
        if (value.isboolean()) {
            return value.checkboolean();
        }
        if (value.isnumber()) {
            return value.checkdouble();
        }
        if (value.isstring()) {
            return value.checkjstring();
        }
        if (value.istable()) {
            LuaTable table = value.checktable();
            int length = table.length();
            boolean array = length > 0 && onlyArrayKeys(table, length);
            if (array) {
                List<Object> list = new ArrayList<>();
                for (int i = 1; i <= length; i++) {
                    list.add(luaToJava(table.get(i)));
                }
                return list;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            LuaValue key = LuaValue.NIL;
            while (true) {
                Varargs next = table.next(key);
                key = next.arg1();
                if (key.isnil()) {
                    break;
                }
                if (!key.isstring()) {
                    continue;
                }
                map.put(key.checkjstring(), luaToJava(next.arg(2)));
            }
            return map;
        }
        return value.tojstring();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> luaTableToMap(LuaValue value, String description) {
        Object converted = luaToJava(value);
        if (converted instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new LuaError(description + " must be a table");
    }

    private static boolean onlyArrayKeys(LuaTable table, int length) {
        LuaValue key = LuaValue.NIL;
        int count = 0;
        while (true) {
            Varargs next = table.next(key);
            key = next.arg1();
            if (key.isnil()) {
                break;
            }
            if (!key.isint()) {
                return false;
            }
            int index = key.checkint();
            if (index < 1 || index > length) {
                return false;
            }
            count++;
        }
        return count == length;
    }

    private LuaValue valueToLua(Descriptors.FieldDescriptor field, Object value) {
        if (field.isRepeated()) {
            LuaTable table = new LuaTable();
            if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    table.set(i + 1, scalarToLua(field, list.get(i)));
                }
            }
            return table;
        }
        return scalarToLua(field, value);
    }

    private LuaValue scalarToLua(Descriptors.FieldDescriptor field, Object value) {
        if (value == null) {
            return LuaValue.NIL;
        }
        return switch (field.getType()) {
            case MESSAGE -> messageToTable((Message) value);
            case ENUM -> LuaValue.valueOf(((Descriptors.EnumValueDescriptor) value).getName());
            case BOOL -> LuaValue.valueOf((Boolean) value);
            case STRING -> LuaValue.valueOf((String) value);
            case BYTES -> LuaValue.valueOf(toHex(((ByteString) value).toByteArray()));
            case FLOAT -> LuaValue.valueOf(((Number) value).floatValue());
            case DOUBLE -> LuaValue.valueOf(((Number) value).doubleValue());
            case UINT32, FIXED32 -> LuaValue.valueOf((double) Integer.toUnsignedLong(((Number) value).intValue()));
            case UINT64, FIXED64 -> LuaValue.valueOf(((Number) value).doubleValue());
            default -> LuaValue.valueOf(((Number) value).doubleValue());
        };
    }

    @SuppressWarnings("unchecked")
    private void applyPatch(Message.Builder builder, Map<String, Object> values) {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String name = entry.getKey();
            if ("section".equals(name) || "type".equals(name)) {
                continue;
            }
            Descriptors.FieldDescriptor field = builder.getDescriptorForType().findFieldByName(name);
            if (field == null) {
                throw new LuaError("Unknown protobuf field '" + name + "' in "
                        + builder.getDescriptorForType().getFullName());
            }
            Object raw = entry.getValue();
            if (field.isRepeated()) {
                builder.clearField(field);
                if (raw == null) {
                    continue;
                }
                if (!(raw instanceof List<?> list)) {
                    throw new LuaError("Repeated field '" + name + "' must be a Lua list");
                }
                for (Object item : list) {
                    builder.addRepeatedField(field, toFieldValue(builder, field, item));
                }
                continue;
            }
            if (raw == null) {
                builder.clearField(field);
            } else {
                builder.setField(field, toFieldValue(builder, field, raw));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object toFieldValue(Message.Builder parentBuilder,
                                Descriptors.FieldDescriptor field,
                                Object raw) {
        return switch (field.getType()) {
            case MESSAGE -> {
                if (!(raw instanceof Map<?, ?> map)) {
                    throw new LuaError("Field '" + field.getName() + "' must be a table");
                }
                Message current = !field.isRepeated() && parentBuilder.hasField(field)
                        ? (Message) parentBuilder.getField(field)
                        : parentBuilder.newBuilderForField(field).build();
                Message.Builder subBuilder = current.toBuilder();
                applyPatch(subBuilder, (Map<String, Object>) map);
                yield subBuilder.build();
            }
            case ENUM -> enumValue(field, raw);
            case BOOL -> raw instanceof Boolean bool ? bool : Boolean.parseBoolean(raw.toString());
            case STRING -> raw.toString();
            case BYTES -> bytesValue(raw);
            case FLOAT -> raw instanceof Number number ? number.floatValue() : Float.parseFloat(raw.toString().trim());
            case DOUBLE -> raw instanceof Number number ? number.doubleValue() : Double.parseDouble(raw.toString().trim());
            case UINT32, FIXED32 -> toInt(raw);
            case INT32, SINT32, SFIXED32 -> raw instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(raw.toString().trim());
            case UINT64, FIXED64 -> raw instanceof Number number
                    ? number.longValue()
                    : Long.parseUnsignedLong(raw.toString().trim());
            case INT64, SINT64, SFIXED64 -> raw instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(raw.toString().trim());
            default -> raw;
        };
    }

    private Descriptors.EnumValueDescriptor enumValue(Descriptors.FieldDescriptor field, Object raw) {
        if (raw instanceof Number number) {
            Descriptors.EnumValueDescriptor value = field.getEnumType().findValueByNumber(number.intValue());
            if (value != null) {
                return value;
            }
        }
        String name = raw.toString().trim();
        Descriptors.EnumValueDescriptor value = field.getEnumType().findValueByName(name);
        if (value == null) {
            value = field.getEnumType().findValueByName(name.toUpperCase(Locale.ROOT));
        }
        if (value == null) {
            throw new LuaError("Unknown enum value '" + raw + "' for field " + field.getFullName());
        }
        return value;
    }

    private static ByteString bytesValue(Object raw) {
        if (raw instanceof ByteString byteString) {
            return byteString;
        }
        if (raw instanceof Map<?, ?> map) {
            Object hex = map.get("hex");
            if (hex != null) {
                return ByteString.copyFrom(parseHex(hex.toString()));
            }
            Object base64 = map.get("base64");
            if (base64 != null) {
                return ByteString.copyFrom(Base64.getDecoder().decode(base64.toString()));
            }
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return ByteString.EMPTY;
        }
        if (text.startsWith("hex:")) {
            return ByteString.copyFrom(parseHex(text.substring(4)));
        }
        if (text.startsWith("base64:")) {
            return ByteString.copyFrom(Base64.getDecoder().decode(text.substring(7)));
        }
        if (looksLikeHex(text)) {
            return ByteString.copyFrom(parseHex(text));
        }
        return ByteString.copyFrom(Base64.getDecoder().decode(text));
    }

    private static int toInt(Object raw) {
        if (raw instanceof Number number) {
            return (int) ((long) number.doubleValue() & 0xffff_ffffL);
        }
        String text = raw.toString().trim();
        if (text.startsWith("!")) {
            return (int) Long.parseUnsignedLong(text.substring(1), 16);
        }
        return (int) (Long.parseUnsignedLong(text) & 0xffff_ffffL);
    }

    private static Descriptors.FieldDescriptor payloadField(Message message,
                                                            String section,
                                                            Map<String, String> aliases) {
        if (section == null || section.isBlank()) {
            return null;
        }
        String normalized = normalizeSection(section);
        normalized = aliases.getOrDefault(normalized, normalized);
        for (Descriptors.FieldDescriptor field : message.getDescriptorForType().getFields()) {
            if (normalizeSection(field.getName()).equals(normalized)) {
                return field;
            }
        }
        return null;
    }

    private static String normalizeSection(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("_config")) {
            normalized = normalized.substring(0, normalized.length() - "_config".length());
        }
        if (normalized.endsWith("config")) {
            normalized = normalized.substring(0, normalized.length() - "config".length());
        }
        return normalized.replace("_", "");
    }

    private static Descriptors.FieldDescriptor activePayloadField(Message message) {
        if (message == null) {
            return null;
        }
        Descriptors.OneofDescriptor oneof = message.getDescriptorForType().getOneofs().stream()
                .filter(candidate -> "payload_variant".equals(candidate.getName()))
                .findFirst()
                .orElse(null);
        return oneof != null ? message.getOneofFieldDescriptor(oneof) : null;
    }

    private static Message matchingSection(Message existing, Descriptors.FieldDescriptor expectedField) {
        Descriptors.FieldDescriptor active = activePayloadField(existing);
        if (active == null || active.getNumber() != expectedField.getNumber()) {
            return null;
        }
        return (Message) existing.getField(active);
    }

    private static boolean looksLikeHex(String value) {
        String compact = value.replace(" ", "").replace(":", "").replace("-", "");
        return compact.length() % 2 == 0 && compact.matches("(?i)[0-9a-f]+");
    }

    private static byte[] parseHex(String value) {
        String compact = value.replace(" ", "").replace(":", "").replace("-", "");
        if (compact.length() % 2 != 0 || !compact.matches("(?i)[0-9a-f]*")) {
            throw new LuaError("Invalid hex bytes");
        }
        byte[] bytes = new byte[compact.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            sb.append(String.format("%02x", value));
        }
        return sb.toString();
    }
}
