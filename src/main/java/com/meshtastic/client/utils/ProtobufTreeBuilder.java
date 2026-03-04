package com.meshtastic.client.utils;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.meshtastic.client.model.ConfigTreeItem;
import javafx.scene.control.TreeItem;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Утилита для построения дерева ConfigTreeItem из protobuf Config/ModuleConfig.
 * Использует protobuf reflection для автоматического обхода полей.
 */
public final class ProtobufTreeBuilder {

    private static final Logger log = LoggerFactory.getLogger(ProtobufTreeBuilder.class);

    /** Русские названия секций конфига */
    private static final Map<String, String> SECTION_NAMES = Map.ofEntries(
            Map.entry("device", "Устройство"),
            Map.entry("position", "Позиция"),
            Map.entry("power", "Питание"),
            Map.entry("network", "Сеть"),
            Map.entry("display", "Дисплей"),
            Map.entry("lora", "LoRa"),
            Map.entry("bluetooth", "Bluetooth"),
            Map.entry("security", "Безопасность"),
            Map.entry("sessionkey", "Ключ сессии"),
            Map.entry("device_ui", "UI устройства"),
            Map.entry("mqtt", "MQTT"),
            Map.entry("serial", "Серийный порт"),
            Map.entry("external_notification", "Внешние уведомления"),
            Map.entry("store_forward", "Store & Forward"),
            Map.entry("range_test", "Тест дальности"),
            Map.entry("telemetry", "Телеметрия"),
            Map.entry("canned_message", "Готовые сообщения"),
            Map.entry("audio", "Аудио"),
            Map.entry("remote_hardware", "Удалённое оборудование"),
            Map.entry("neighbor_info", "Информация о соседях"),
            Map.entry("ambient_lighting", "Подсветка"),
            Map.entry("detection_sensor", "Датчик обнаружения"),
            Map.entry("paxcounter", "Счётчик PAX"),
            Map.entry("statusmessage", "Статус"),
            Map.entry("traffic_management", "Управление трафиком")
    );

    private ProtobufTreeBuilder() {}

    /**
     * Строит дерево из списка Config (устройство).
     */
    public static TreeItem<ConfigTreeItem> buildConfigTree(List<ConfigProtos.Config> configs) {
        ConfigTreeItem rootData = new ConfigTreeItem("Конфигурация устройства", "config", 0);
        TreeItem<ConfigTreeItem> root = new TreeItem<>(rootData);
        root.setExpanded(true);

        for (ConfigProtos.Config config : configs) {
            FieldDescriptor oneofField = getActiveOneofField(config, "payload_variant");
            if (oneofField == null) { continue; }

            Message sectionMsg = (Message) config.getField(oneofField);
            String sectionName = oneofField.getName();
            int variantNumber = oneofField.getNumber();

            String displayName = SECTION_NAMES.getOrDefault(sectionName, humanize(sectionName));
            ConfigTreeItem sectionData = new ConfigTreeItem(displayName, "config", variantNumber);
            TreeItem<ConfigTreeItem> sectionItem = new TreeItem<>(sectionData);

            addFieldsToTree(sectionItem, sectionMsg, "config", variantNumber);

            if (!sectionItem.getChildren().isEmpty()) {
                root.getChildren().add(sectionItem);
            }
        }

        return root;
    }

    /**
     * Строит дерево из списка ModuleConfig (модули).
     */
    public static TreeItem<ConfigTreeItem> buildModuleConfigTree(List<ModuleConfigProtos.ModuleConfig> moduleConfigs) {
        ConfigTreeItem rootData = new ConfigTreeItem("Конфигурация модулей", "module_config", 0);
        TreeItem<ConfigTreeItem> root = new TreeItem<>(rootData);
        root.setExpanded(true);

        for (ModuleConfigProtos.ModuleConfig mc : moduleConfigs) {
            FieldDescriptor oneofField = getActiveOneofField(mc, "payload_variant");
            if (oneofField == null) { continue; }

            Message sectionMsg = (Message) mc.getField(oneofField);
            String sectionName = oneofField.getName();
            int variantNumber = oneofField.getNumber();

            String displayName = SECTION_NAMES.getOrDefault(sectionName, humanize(sectionName));
            ConfigTreeItem sectionData = new ConfigTreeItem(displayName, "module_config", variantNumber);
            TreeItem<ConfigTreeItem> sectionItem = new TreeItem<>(sectionData);

            addFieldsToTree(sectionItem, sectionMsg, "module_config", variantNumber);

            if (!sectionItem.getChildren().isEmpty()) {
                root.getChildren().add(sectionItem);
            }
        }

        return root;
    }

    /**
     * Рекурсивно добавляет поля protobuf Message в дерево.
     */
    private static void addFieldsToTree(TreeItem<ConfigTreeItem> parent, Message message,
                                          String configType, int variantNumber) {
        for (FieldDescriptor fd : message.getDescriptorForType().getFields()) {
            // Пропускаем repeated/map поля и bytes
            if (fd.isRepeated()) { continue; }
            if (fd.getType() == FieldDescriptor.Type.BYTES) { continue; }

            String fieldName = fd.getName();
            String displayName = humanize(fieldName);
            Object value = message.getField(fd);

            if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
                // Вложенная группа — рекурсия
                Message subMsg = (Message) value;
                ConfigTreeItem groupData = new ConfigTreeItem(displayName, configType, variantNumber);
                TreeItem<ConfigTreeItem> groupItem = new TreeItem<>(groupData);
                addFieldsToTree(groupItem, subMsg, configType, variantNumber);
                if (!groupItem.getChildren().isEmpty()) {
                    parent.getChildren().add(groupItem);
                }
            } else if (fd.getType() == FieldDescriptor.Type.ENUM) {
                EnumValueDescriptor enumVal = (EnumValueDescriptor) value;
                List<EnumValueDescriptor> enumValues = new ArrayList<>(fd.getEnumType().getValues());
                ConfigTreeItem item = new ConfigTreeItem(
                        displayName, fieldName, enumVal, EnumValueDescriptor.class,
                        enumValues, fd, configType, variantNumber);
                parent.getChildren().add(new TreeItem<>(item));
            } else if (fd.getType() == FieldDescriptor.Type.BOOL) {
                ConfigTreeItem item = new ConfigTreeItem(
                        displayName, fieldName, value, Boolean.class,
                        null, fd, configType, variantNumber);
                parent.getChildren().add(new TreeItem<>(item));
            } else if (fd.getType() == FieldDescriptor.Type.STRING) {
                ConfigTreeItem item = new ConfigTreeItem(
                        displayName, fieldName, value, String.class,
                        null, fd, configType, variantNumber);
                parent.getChildren().add(new TreeItem<>(item));
            } else if (fd.getType() == FieldDescriptor.Type.FLOAT) {
                ConfigTreeItem item = new ConfigTreeItem(
                        displayName, fieldName, value, Float.class,
                        null, fd, configType, variantNumber);
                parent.getChildren().add(new TreeItem<>(item));
            } else if (fd.getType() == FieldDescriptor.Type.DOUBLE) {
                ConfigTreeItem item = new ConfigTreeItem(
                        displayName, fieldName, value, Double.class,
                        null, fd, configType, variantNumber);
                parent.getChildren().add(new TreeItem<>(item));
            } else if (fd.getType() == FieldDescriptor.Type.UINT32
                    || fd.getType() == FieldDescriptor.Type.INT32
                    || fd.getType() == FieldDescriptor.Type.SINT32
                    || fd.getType() == FieldDescriptor.Type.FIXED32
                    || fd.getType() == FieldDescriptor.Type.SFIXED32) {
                ConfigTreeItem item = new ConfigTreeItem(
                        displayName, fieldName, value, Integer.class,
                        null, fd, configType, variantNumber);
                parent.getChildren().add(new TreeItem<>(item));
            } else if (fd.getType() == FieldDescriptor.Type.UINT64
                    || fd.getType() == FieldDescriptor.Type.INT64
                    || fd.getType() == FieldDescriptor.Type.SINT64
                    || fd.getType() == FieldDescriptor.Type.FIXED64
                    || fd.getType() == FieldDescriptor.Type.SFIXED64) {
                ConfigTreeItem item = new ConfigTreeItem(
                        displayName, fieldName, value, Long.class,
                        null, fd, configType, variantNumber);
                parent.getChildren().add(new TreeItem<>(item));
            }
        }
    }

    /**
     * Находит активное поле oneof.
     */
    private static FieldDescriptor getActiveOneofField(Message msg, String oneofName) {
        Descriptors.OneofDescriptor oneof = msg.getDescriptorForType().getOneofs().stream()
                .filter(o -> o.getName().equals(oneofName))
                .findFirst()
                .orElse(null);
        if (oneof == null) { return null; }
        return msg.getOneofFieldDescriptor(oneof);
    }

    /**
     * Преобразует protobuf field name (snake_case) в человекочитаемое имя.
     * Например: "node_info_broadcast_secs" -> "Node info broadcast secs"
     */
    public static String humanize(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) { return fieldName; }
        String result = fieldName.replace('_', ' ');
        return result.substring(0, 1).toUpperCase(Locale.ROOT) + result.substring(1);
    }

    /**
     * Собирает protobuf Config из дерева для отправки на устройство.
     * Берёт все дочерние поля секции и создаёт новый Config с обновлёнными значениями.
     */
    public static ConfigProtos.Config rebuildConfig(TreeItem<ConfigTreeItem> sectionItem,
                                                      ConfigProtos.Config originalConfig) {
        FieldDescriptor oneofField = getActiveOneofField(originalConfig, "payload_variant");
        if (oneofField == null) { return null; }

        Message originalSection = (Message) originalConfig.getField(oneofField);
        Message.Builder sectionBuilder = originalSection.toBuilder();

        applyTreeValues(sectionItem, sectionBuilder);

        return ConfigProtos.Config.newBuilder()
                .setField(oneofField, sectionBuilder.build())
                .build();
    }

    /**
     * Собирает protobuf ModuleConfig из дерева для отправки на устройство.
     */
    public static ModuleConfigProtos.ModuleConfig rebuildModuleConfig(
            TreeItem<ConfigTreeItem> sectionItem,
            ModuleConfigProtos.ModuleConfig originalModuleConfig) {
        FieldDescriptor oneofField = getActiveOneofField(originalModuleConfig, "payload_variant");
        if (oneofField == null) { return null; }

        Message originalSection = (Message) originalModuleConfig.getField(oneofField);
        Message.Builder sectionBuilder = originalSection.toBuilder();

        applyTreeValues(sectionItem, sectionBuilder);

        return ModuleConfigProtos.ModuleConfig.newBuilder()
                .setField(oneofField, sectionBuilder.build())
                .build();
    }

    /**
     * Рекурсивно применяет значения из дерева в protobuf Builder.
     */
    private static void applyTreeValues(TreeItem<ConfigTreeItem> treeItem, Message.Builder builder) {
        for (TreeItem<ConfigTreeItem> child : treeItem.getChildren()) {
            ConfigTreeItem item = child.getValue();
            if (item.isCategory()) {
                // Вложенная группа (sub-message) — не поддерживается для записи пока
                continue;
            }
            if (item.getFieldDescriptor() == null || item.getValue() == null) { continue; }

            FieldDescriptor fd = item.getFieldDescriptor();
            // Ищем соответствующий FieldDescriptor в текущем builder (может отличаться от оригинала)
            FieldDescriptor builderFd = builder.getDescriptorForType().findFieldByName(fd.getName());
            if (builderFd == null) { continue; }

            Object value = item.getValue();
            try {
                if (builderFd.getType() == FieldDescriptor.Type.ENUM) {
                    if (value instanceof EnumValueDescriptor evd) {
                        builder.setField(builderFd, evd);
                    }
                } else if (builderFd.getType() == FieldDescriptor.Type.BOOL) {
                    builder.setField(builderFd, value);
                } else if (builderFd.getType() == FieldDescriptor.Type.STRING) {
                    builder.setField(builderFd, value.toString());
                } else if (builderFd.getType() == FieldDescriptor.Type.FLOAT) {
                    builder.setField(builderFd, ((Number) value).floatValue());
                } else if (builderFd.getType() == FieldDescriptor.Type.DOUBLE) {
                    builder.setField(builderFd, ((Number) value).doubleValue());
                } else if (builderFd.getType() == FieldDescriptor.Type.UINT32
                        || builderFd.getType() == FieldDescriptor.Type.INT32
                        || builderFd.getType() == FieldDescriptor.Type.SINT32
                        || builderFd.getType() == FieldDescriptor.Type.FIXED32
                        || builderFd.getType() == FieldDescriptor.Type.SFIXED32) {
                    builder.setField(builderFd, ((Number) value).intValue());
                } else if (builderFd.getType() == FieldDescriptor.Type.UINT64
                        || builderFd.getType() == FieldDescriptor.Type.INT64
                        || builderFd.getType() == FieldDescriptor.Type.SINT64
                        || builderFd.getType() == FieldDescriptor.Type.FIXED64
                        || builderFd.getType() == FieldDescriptor.Type.SFIXED64) {
                    builder.setField(builderFd, ((Number) value).longValue());
                }
            } catch (Exception e) { //NOPMD - field type mismatch is expected and safely skipped
                log.trace("Skipping field '{}': {}", builderFd.getName(), e.getMessage());
            }
        }
    }
}
