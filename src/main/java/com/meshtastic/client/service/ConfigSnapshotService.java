package com.meshtastic.client.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Чтение/запись snapshot-файлов конфигурации MeshApp (.mcf/.mtp).
 */
public final class ConfigSnapshotService {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String FILE_FORMAT = "meshapp-config";
    private static final int FILE_VERSION = 1;

    private static final Set<String> TEMPLATE_EXACT_FIELDS = Set.of(
            "long_name",
            "short_name",
            "macaddr",
            "public_key",
            "private_key",
            "admin_key",
            "psk",
            "session_passkey",
            "wifi_enabled",
            "wifi_ssid",
            "wifi_psk",
            "ntp_server",
            "fixed_position",
            "fixed_pin"
    );

    public enum SnapshotKind {
        CONFIG("config", "mcf"),
        TEMPLATE("template", "mtp");

        private final String id;
        private final String extension;

        SnapshotKind(String id, String extension) {
            this.id = id;
            this.extension = extension;
        }

        public String id() { return id; }
        public String extension() { return extension; }

        public static SnapshotKind fromId(String id) {
            for (SnapshotKind kind : values()) {
                if (kind.id.equalsIgnoreCase(id)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Неизвестный тип snapshot: " + id);
        }
    }

    public record OwnerInfo(String longName, String shortName, boolean isLicensed) {}
    public record FixedPosition(double latitude, double longitude, int altitude) {}

    public record ConfigSnapshot(
            String format,
            int version,
            SnapshotKind kind,
            String exportedAt,
            OwnerInfo ownerInfo,
            FixedPosition fixedPosition,
            String ringtone,
            List<JsonObject> configs,
            List<JsonObject> moduleConfigs,
            List<JsonObject> channels
    ) {}

    private ConfigSnapshotService() {}

    public static ConfigSnapshot createSnapshot(SnapshotKind kind,
                                                OwnerInfo ownerInfo,
                                                FixedPosition fixedPosition,
                                                String ringtone,
                                                List<ConfigProtos.Config> configs,
                                                List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
                                                List<ChannelProtos.Channel> channels) {
        ConfigSnapshot snapshot = new ConfigSnapshot(
                FILE_FORMAT,
                FILE_VERSION,
                kind,
                Instant.now().toString(),
                ownerInfo,
                fixedPosition,
                ringtone,
                toJsonObjects(configs),
                toJsonObjects(moduleConfigs),
                toJsonObjects(channels)
        );
        return kind == SnapshotKind.TEMPLATE ? sanitizeTemplate(snapshot) : snapshot;
    }

    public static void writeSnapshot(Path path, ConfigSnapshot snapshot) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", snapshot.format());
        root.addProperty("version", snapshot.version());
        root.addProperty("kind", snapshot.kind().id());
        root.addProperty("exportedAt", snapshot.exportedAt());

        if (snapshot.ownerInfo() != null) {
            JsonObject owner = new JsonObject();
            owner.addProperty("longName", snapshot.ownerInfo().longName());
            owner.addProperty("shortName", snapshot.ownerInfo().shortName());
            owner.addProperty("isLicensed", snapshot.ownerInfo().isLicensed());
            root.add("ownerInfo", owner);
        }

        if (snapshot.fixedPosition() != null) {
            JsonObject fixed = new JsonObject();
            fixed.addProperty("latitude", snapshot.fixedPosition().latitude());
            fixed.addProperty("longitude", snapshot.fixedPosition().longitude());
            fixed.addProperty("altitude", snapshot.fixedPosition().altitude());
            root.add("fixedPosition", fixed);
        }

        if (snapshot.ringtone() != null) {
            root.addProperty("ringtone", snapshot.ringtone());
        }

        root.add("configs", toJsonArray(snapshot.configs()));
        root.add("moduleConfigs", toJsonArray(snapshot.moduleConfigs()));
        root.add("channels", toJsonArray(snapshot.channels()));

        Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    public static ConfigSnapshot readSnapshot(Path path) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new IOException("Некорректный формат файла конфигурации");
        }

        JsonObject root = parsed.getAsJsonObject();
        String format = getRequiredString(root, "format");
        if (!FILE_FORMAT.equals(format)) {
            throw new IOException("Неподдерживаемый формат файла: " + format);
        }

        int version = root.has("version") ? root.get("version").getAsInt() : 0;
        if (version != FILE_VERSION) {
            throw new IOException("Неподдерживаемая версия файла: " + version);
        }

        SnapshotKind kind = SnapshotKind.fromId(getRequiredString(root, "kind"));
        String exportedAt = root.has("exportedAt") ? root.get("exportedAt").getAsString() : "";

        OwnerInfo ownerInfo = null;
        if (root.has("ownerInfo") && root.get("ownerInfo").isJsonObject()) {
            JsonObject owner = root.getAsJsonObject("ownerInfo");
            ownerInfo = new OwnerInfo(
                    owner.has("longName") ? owner.get("longName").getAsString() : "",
                    owner.has("shortName") ? owner.get("shortName").getAsString() : "",
                    owner.has("isLicensed") && owner.get("isLicensed").getAsBoolean()
            );
        }

        FixedPosition fixedPosition = null;
        if (root.has("fixedPosition") && root.get("fixedPosition").isJsonObject()) {
            JsonObject fixed = root.getAsJsonObject("fixedPosition");
            fixedPosition = new FixedPosition(
                    fixed.has("latitude") ? fixed.get("latitude").getAsDouble() : 0,
                    fixed.has("longitude") ? fixed.get("longitude").getAsDouble() : 0,
                    fixed.has("altitude") ? fixed.get("altitude").getAsInt() : 0
            );
        }

        return new ConfigSnapshot(
                format,
                version,
                kind,
                exportedAt,
                ownerInfo,
                fixedPosition,
                root.has("ringtone") && !root.get("ringtone").isJsonNull()
                        ? root.get("ringtone").getAsString()
                        : null,
                readObjectArray(root, "configs"),
                readObjectArray(root, "moduleConfigs"),
                readObjectArray(root, "channels")
        );
    }

    public static <T extends Message> T mergeJsonIntoMessage(T baseMessage, JsonObject patch) {
        if (patch == null) {
            return baseMessage;
        }
        @SuppressWarnings("unchecked")
        T merged = (T) mergeIntoBuilder(baseMessage.toBuilder(), patch).build();
        return merged;
    }

    public static String detectActiveVariantField(JsonObject protoJson) {
        for (Map.Entry<String, JsonElement> entry : protoJson.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static ConfigSnapshot sanitizeTemplate(ConfigSnapshot source) {
        return new ConfigSnapshot(
                source.format(),
                source.version(),
                SnapshotKind.TEMPLATE,
                source.exportedAt(),
                null,
                null,
                null,
                sanitizeObjectList(source.configs()),
                sanitizeObjectList(source.moduleConfigs()),
                sanitizeObjectList(source.channels())
        );
    }

    private static List<JsonObject> sanitizeObjectList(List<JsonObject> source) {
        List<JsonObject> result = new ArrayList<>(source.size());
        for (JsonObject item : source) {
            JsonObject copy = item.deepCopy();
            scrubTemplateJson(copy);
            if (!copy.entrySet().isEmpty()) {
                result.add(copy);
            }
        }
        return result;
    }

    private static void scrubTemplateJson(JsonObject object) {
        Iterator<Map.Entry<String, JsonElement>> iterator = object.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonElement> entry = iterator.next();
            String fieldName = entry.getKey().toLowerCase(Locale.ROOT);
            JsonElement value = entry.getValue();

            if (shouldRemoveTemplateField(fieldName)) {
                iterator.remove();
                continue;
            }

            if (value.isJsonObject()) {
                JsonObject child = value.getAsJsonObject();
                scrubTemplateJson(child);
                if (child.entrySet().isEmpty()) {
                    iterator.remove();
                }
            } else if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                scrubTemplateArray(array);
                if (array.isEmpty()) {
                    iterator.remove();
                }
            }
        }
    }

    private static void scrubTemplateArray(JsonArray array) {
        for (int i = array.size() - 1; i >= 0; i--) {
            JsonElement element = array.get(i);
            if (element.isJsonObject()) {
                JsonObject child = element.getAsJsonObject();
                scrubTemplateJson(child);
                if (child.entrySet().isEmpty()) {
                    array.remove(i);
                }
            } else if (element.isJsonArray()) {
                JsonArray child = element.getAsJsonArray();
                scrubTemplateArray(child);
                if (child.isEmpty()) {
                    array.remove(i);
                }
            }
        }
    }

    private static boolean shouldRemoveTemplateField(String fieldName) {
        return TEMPLATE_EXACT_FIELDS.contains(fieldName)
                || fieldName.endsWith("_key")
                || fieldName.contains("passkey")
                || fieldName.contains("secret")
                || fieldName.contains("token");
    }

    private static List<JsonObject> toJsonObjects(List<? extends Message> messages) {
        List<JsonObject> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            result.add(toJsonObject(message));
        }
        return result;
    }

    private static JsonArray toJsonArray(List<JsonObject> objects) {
        JsonArray array = new JsonArray();
        for (JsonObject object : objects) {
            array.add(object.deepCopy());
        }
        return array;
    }

    private static List<JsonObject> readObjectArray(JsonObject root, String fieldName) {
        List<JsonObject> result = new ArrayList<>();
        if (!root.has(fieldName) || !root.get(fieldName).isJsonArray()) {
            return result;
        }

        for (JsonElement element : root.getAsJsonArray(fieldName)) {
            if (element.isJsonObject()) {
                result.add(element.getAsJsonObject());
            }
        }
        return result;
    }

    private static String getRequiredString(JsonObject root, String fieldName) throws IOException {
        if (!root.has(fieldName) || !root.get(fieldName).isJsonPrimitive()) {
            throw new IOException("В файле отсутствует поле '" + fieldName + "'");
        }
        return root.get(fieldName).getAsString();
    }

    private static JsonObject toJsonObject(Message message) {
        JsonObject json = new JsonObject();
        for (FieldDescriptor fd : message.getDescriptorForType().getFields()) {
            if (shouldSkipField(message, fd)) {
                continue;
            }
            if (fd.getType() == FieldDescriptor.Type.MESSAGE && !fd.isRepeated() && !message.hasField(fd)) {
                continue;
            }
            json.add(fd.getName(), toJsonElement(fd, message.getField(fd)));
        }
        return json;
    }

    private static boolean shouldSkipField(Message message, FieldDescriptor fd) {
        if (fd.getContainingOneof() == null) {
            return false;
        }
        FieldDescriptor active = message.getOneofFieldDescriptor(fd.getContainingOneof());
        return active == null || !active.equals(fd);
    }

    private static JsonElement toJsonElement(FieldDescriptor fd, Object value) {
        if (fd.isRepeated()) {
            JsonArray array = new JsonArray();
            @SuppressWarnings("unchecked")
            List<Object> values = (List<Object>) value;
            for (Object item : values) {
                array.add(toSingleJsonElement(fd, item));
            }
            return array;
        }
        return toSingleJsonElement(fd, value);
    }

    private static JsonElement toSingleJsonElement(FieldDescriptor fd, Object value) {
        return switch (fd.getType()) {
            case MESSAGE -> toJsonObject((Message) value);
            case ENUM -> new com.google.gson.JsonPrimitive(((com.google.protobuf.Descriptors.EnumValueDescriptor) value).getName());
            case BYTES -> new com.google.gson.JsonPrimitive(
                    Base64.getEncoder().encodeToString(((ByteString) value).toByteArray()));
            case BOOL -> new com.google.gson.JsonPrimitive((Boolean) value);
            case STRING -> new com.google.gson.JsonPrimitive((String) value);
            case FLOAT -> new com.google.gson.JsonPrimitive((Float) value);
            case DOUBLE -> new com.google.gson.JsonPrimitive((Double) value);
            case UINT32, INT32, SINT32, FIXED32, SFIXED32 -> new com.google.gson.JsonPrimitive(((Number) value).intValue());
            case UINT64, INT64, SINT64, FIXED64, SFIXED64 -> new com.google.gson.JsonPrimitive(((Number) value).longValue());
            default -> throw new IllegalArgumentException("Неподдерживаемый protobuf тип: " + fd.getType());
        };
    }

    private static Message.Builder mergeIntoBuilder(Message.Builder builder, JsonObject patch) {
        for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
            FieldDescriptor fd = builder.getDescriptorForType().findFieldByName(entry.getKey());
            if (fd == null || entry.getValue() == null || entry.getValue().isJsonNull()) {
                continue;
            }

            if (fd.isRepeated()) {
                if (!entry.getValue().isJsonArray()) {
                    continue;
                }
                builder.clearField(fd);
                JsonArray array = entry.getValue().getAsJsonArray();
                for (JsonElement element : array) {
                    Object converted = fromJsonElement(builder, fd, element);
                    if (converted != null) {
                        builder.addRepeatedField(fd, converted);
                    }
                }
                continue;
            }

            Object converted = fromJsonElement(builder, fd, entry.getValue());
            if (converted != null) {
                builder.setField(fd, converted);
            }
        }
        return builder;
    }

    private static Object fromJsonElement(Message.Builder parentBuilder, FieldDescriptor fd, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }

        return switch (fd.getType()) {
            case MESSAGE -> {
                if (!element.isJsonObject()) {
                    yield null;
                }
                Message current = parentBuilder.hasField(fd)
                        ? (Message) parentBuilder.getField(fd)
                        : parentBuilder.newBuilderForField(fd).build();
                Message.Builder childBuilder = current.toBuilder();
                yield mergeIntoBuilder(childBuilder, element.getAsJsonObject()).build();
            }
            case ENUM -> {
                if (!element.isJsonPrimitive()) {
                    yield null;
                }
                String enumName = element.getAsString();
                var byName = fd.getEnumType().findValueByName(enumName);
                if (byName != null) {
                    yield byName;
                }
                if (element.getAsJsonPrimitive().isNumber()) {
                    yield fd.getEnumType().findValueByNumber(element.getAsInt());
                }
                yield null;
            }
            case BYTES -> {
                if (!element.isJsonPrimitive()) {
                    yield null;
                }
                String base64 = element.getAsString().trim();
                yield base64.isEmpty() ? ByteString.EMPTY : ByteString.copyFrom(Base64.getDecoder().decode(base64));
            }
            case BOOL -> element.getAsBoolean();
            case STRING -> element.getAsString();
            case FLOAT -> element.getAsFloat();
            case DOUBLE -> element.getAsDouble();
            case UINT32, INT32, SINT32, FIXED32, SFIXED32 -> element.getAsInt();
            case UINT64, INT64, SINT64, FIXED64, SFIXED64 -> element.getAsLong();
            default -> null;
        };
    }
}
