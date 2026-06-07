package com.meshtastic.client.utils;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import javafx.scene.control.TreeItem;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds editable {@link ConfigTreeItem} trees from protobuf Config and
 * ModuleConfig messages using protobuf reflection.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ProtobufTreeBuilder {

    private static final Logger log = LoggerFactory.getLogger(ProtobufTreeBuilder.class);
    private static final int MIN_VISIBLE_REPEATED_BYTES_SLOTS = 3;
    private static final int MIN_VISIBLE_REPEATED_SCALAR_SLOTS = 1;

    private static final Map<String, String> SECTION_NAME_KEYS = Map.ofEntries(
            Map.entry("device", "settings.config.section.device"),
            Map.entry("position", "settings.config.section.position"),
            Map.entry("power", "settings.config.section.power"),
            Map.entry("network", "settings.config.section.network"),
            Map.entry("display", "settings.config.section.display"),
            Map.entry("lora", "settings.config.section.lora"),
            Map.entry("bluetooth", "settings.config.section.bluetooth"),
            Map.entry("security", "settings.config.section.security"),
            Map.entry("sessionkey", "settings.config.section.sessionkey"),
            Map.entry("device_ui", "settings.config.section.device_ui"),
            Map.entry("mqtt", "settings.config.section.mqtt"),
            Map.entry("serial", "settings.config.section.serial"),
            Map.entry("external_notification", "settings.config.section.external_notification"),
            Map.entry("store_forward", "settings.config.section.store_forward"),
            Map.entry("range_test", "settings.config.section.range_test"),
            Map.entry("telemetry", "settings.config.section.telemetry"),
            Map.entry("canned_message", "settings.config.section.canned_message"),
            Map.entry("audio", "settings.config.section.audio"),
            Map.entry("remote_hardware", "settings.config.section.remote_hardware"),
            Map.entry("neighbor_info", "settings.config.section.neighbor_info"),
            Map.entry("ambient_lighting", "settings.config.section.ambient_lighting"),
            Map.entry("detection_sensor", "settings.config.section.detection_sensor"),
            Map.entry("paxcounter", "settings.config.section.paxcounter"),
            Map.entry("statusmessage", "settings.config.section.statusmessage"),
            Map.entry("traffic_management", "settings.config.section.traffic_management")
    );

    private ProtobufTreeBuilder() {}

    /**
     * Builds a device configuration tree from {@code Config} messages.
     */
    public static TreeItem<ConfigTreeItem> buildConfigTree(List<ConfigProtos.Config> configs) {
        ConfigTreeItem rootData = new ConfigTreeItem(I18n.t("settings.config.section.root.device"), "config", 0);
        TreeItem<ConfigTreeItem> root = new TreeItem<>(rootData);
        root.setExpanded(true);

        for (ConfigProtos.Config config : configs) {
            FieldDescriptor oneofField = getActiveOneofField(config, "payload_variant");
            if (oneofField == null) { continue; }

            Message sectionMsg = (Message) config.getField(oneofField);
            String sectionName = oneofField.getName();
            int variantNumber = oneofField.getNumber();

            String displayName = sectionDisplayName(sectionName);
            ConfigTreeItem sectionData = new ConfigTreeItem(
                    displayName, sectionName, oneofField, "config", variantNumber);
            TreeItem<ConfigTreeItem> sectionItem = new TreeItem<>(sectionData);

            addFieldsToTree(sectionItem, sectionMsg, "config", variantNumber);

            if (!sectionItem.getChildren().isEmpty()) {
                root.getChildren().add(sectionItem);
            }
        }

        return root;
    }

    /**
     * Builds a module configuration tree from {@code ModuleConfig} messages.
     */
    public static TreeItem<ConfigTreeItem> buildModuleConfigTree(List<ModuleConfigProtos.ModuleConfig> moduleConfigs) {
        ConfigTreeItem rootData = new ConfigTreeItem(I18n.t("settings.config.section.root.module"), "module_config", 0);
        TreeItem<ConfigTreeItem> root = new TreeItem<>(rootData);
        root.setExpanded(true);

        for (ModuleConfigProtos.ModuleConfig mc : moduleConfigs) {
            FieldDescriptor oneofField = getActiveOneofField(mc, "payload_variant");
            if (oneofField == null) { continue; }

            Message sectionMsg = (Message) mc.getField(oneofField);
            String sectionName = oneofField.getName();
            int variantNumber = oneofField.getNumber();

            String displayName = sectionDisplayName(sectionName);
            ConfigTreeItem sectionData = new ConfigTreeItem(
                    displayName, sectionName, oneofField, "module_config", variantNumber);
            TreeItem<ConfigTreeItem> sectionItem = new TreeItem<>(sectionData);

            addFieldsToTree(sectionItem, sectionMsg, "module_config", variantNumber);

            if (!sectionItem.getChildren().isEmpty()) {
                root.getChildren().add(sectionItem);
            }
        }

        return root;
    }

    /**
     * Recursively adds protobuf message fields to the configuration tree.
     */
    private static void addFieldsToTree(TreeItem<ConfigTreeItem> parent, Message message,
                                          String configType, int variantNumber) {
        for (FieldDescriptor fd : message.getDescriptorForType().getFields()) {
            String fieldName = fd.getName();
            String displayName = humanize(fieldName);
            Object value = message.getField(fd);

            if (fd.isRepeated()) {
                if (fd.getType() == FieldDescriptor.Type.BYTES) {
                    parent.getChildren().add(buildRepeatedBytesGroup(fd, value, configType, variantNumber, displayName));
                } else if (isSupportedRepeatedScalar(fd)) {
                    parent.getChildren().add(buildRepeatedScalarGroup(fd, value, configType, variantNumber, displayName));
                }
                continue;
            }

            if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
                // Nested message group: descend into it.
                Message subMsg = (Message) value;
                ConfigTreeItem groupData = new ConfigTreeItem(
                        displayName, fieldName, fd, configType, variantNumber);
                TreeItem<ConfigTreeItem> groupItem = new TreeItem<>(groupData);
                addFieldsToTree(groupItem, subMsg, configType, variantNumber);
                if (!groupItem.getChildren().isEmpty()) {
                    parent.getChildren().add(groupItem);
                }
            } else {
                ConfigTreeItem item = createValueItem(displayName, fieldName, value, fd, configType, variantNumber);
                if (item != null) {
                    parent.getChildren().add(new TreeItem<>(item));
                }
            }
        }
    }

    private static TreeItem<ConfigTreeItem> buildRepeatedBytesGroup(FieldDescriptor fd,
                                                                    Object value,
                                                                    String configType,
                                                                    int variantNumber,
                                                                    String displayName) {
        ConfigTreeItem groupData = new ConfigTreeItem(displayName, fd.getName(), fd, configType, variantNumber);
        TreeItem<ConfigTreeItem> groupItem = new TreeItem<>(groupData);
        groupItem.setExpanded(true);
        syncRepeatedBytesGroup(groupItem, fd, value, configType, variantNumber, displayName);
        return groupItem;
    }

    private static TreeItem<ConfigTreeItem> buildRepeatedScalarGroup(FieldDescriptor fd,
                                                                     Object value,
                                                                     String configType,
                                                                     int variantNumber,
                                                                     String displayName) {
        ConfigTreeItem groupData = new ConfigTreeItem(displayName, fd.getName(), fd, configType, variantNumber);
        TreeItem<ConfigTreeItem> groupItem = new TreeItem<>(groupData);
        groupItem.setExpanded(true);
        syncRepeatedScalarGroup(groupItem, fd, value, configType, variantNumber, displayName);
        return groupItem;
    }

    /**
     * Keeps a repeated field group editable after a value changes.
     * The group retains the minimum number of visible slots and always exposes
     * one empty slot for adding the next value without rebuilding the whole tree.
     */
    public static void adjustRepeatedGroupAfterEdit(TreeItem<ConfigTreeItem> groupItem) {
        if (groupItem == null || groupItem.getValue() == null) {
            return;
        }

        ConfigTreeItem groupData = groupItem.getValue();
        FieldDescriptor fd = groupData.getFieldDescriptor();
        if (fd == null || !fd.isRepeated()) {
            return;
        }

        int minVisibleSlots;
        if (fd.getType() == FieldDescriptor.Type.BYTES) {
            minVisibleSlots = MIN_VISIBLE_REPEATED_BYTES_SLOTS;
        } else if (isSupportedRepeatedScalar(fd)) {
            minVisibleSlots = MIN_VISIBLE_REPEATED_SCALAR_SLOTS;
        } else {
            return;
        }

        trimTrailingEmptyRepeatedSlots(groupItem, minVisibleSlots);
        if (!hasEmptyRepeatedSlot(groupItem)) {
            appendEmptyRepeatedSlot(
                    groupItem,
                    fd,
                    groupData.getConfigType(),
                    groupData.getConfigVariantNumber(),
                    groupData.getName());
        }
    }

    private static void syncRepeatedBytesGroup(TreeItem<ConfigTreeItem> groupItem,
                                               FieldDescriptor fd,
                                               Object value,
                                               String configType,
                                               int variantNumber,
                                               String displayName) {
        List<String> base64Values = toBase64Values(value);
        int slotCount = Math.max(base64Values.size() + 1, MIN_VISIBLE_REPEATED_BYTES_SLOTS);
        groupItem.getChildren().clear();
        for (int i = 0; i < slotCount; i++) {
            String slotValue = i < base64Values.size() ? base64Values.get(i) : "";
            String slotName = displayName + " " + (i + 1);
            ConfigTreeItem item = new ConfigTreeItem(
                    slotName, fd.getName(), slotValue, String.class,
                    null, fd, configType, variantNumber);
            groupItem.getChildren().add(new TreeItem<>(item));
        }
    }

    private static List<String> toBase64Values(Object value) {
        if (!(value instanceof List<?>)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<ByteString> bytesList = (List<ByteString>) value;
        return bytesList.stream()
                .map(bs -> Base64.getEncoder().encodeToString(bs.toByteArray()))
                .collect(Collectors.toList());
    }

    private static void trimTrailingEmptyRepeatedSlots(TreeItem<ConfigTreeItem> groupItem, int minVisibleSlots) {
        while (groupItem.getChildren().size() > minVisibleSlots && hasMultipleTrailingEmptySlots(groupItem)) {
            groupItem.getChildren().remove(groupItem.getChildren().size() - 1);
        }
    }

    private static boolean hasMultipleTrailingEmptySlots(TreeItem<ConfigTreeItem> groupItem) {
        int size = groupItem.getChildren().size();
        return size >= 2
                && isEmptyRepeatedSlot(groupItem.getChildren().get(size - 1))
                && isEmptyRepeatedSlot(groupItem.getChildren().get(size - 2));
    }

    private static boolean hasEmptyRepeatedSlot(TreeItem<ConfigTreeItem> groupItem) {
        for (TreeItem<ConfigTreeItem> child : groupItem.getChildren()) {
            if (isEmptyRepeatedSlot(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEmptyRepeatedSlot(TreeItem<ConfigTreeItem> item) {
        if (item == null || item.getValue() == null) {
            return true;
        }
        Object value = item.getValue().getValue();
        return value == null || (value instanceof String text && text.trim().isEmpty());
    }

    private static void appendEmptyRepeatedSlot(TreeItem<ConfigTreeItem> groupItem,
                                                FieldDescriptor fd,
                                                String configType,
                                                int variantNumber,
                                                String displayName) {
        String slotName = displayName + " " + (groupItem.getChildren().size() + 1);
        ConfigTreeItem item = createValueItem(slotName, fd.getName(), null, fd, configType, variantNumber);
        if (item != null) {
            groupItem.getChildren().add(new TreeItem<>(item));
        }
    }

    private static void syncRepeatedScalarGroup(TreeItem<ConfigTreeItem> groupItem,
                                                FieldDescriptor fd,
                                                Object value,
                                                String configType,
                                                int variantNumber,
                                                String displayName) {
        List<?> values = value instanceof List<?> list ? list : List.of();
        int slotCount = Math.max(values.size() + 1, MIN_VISIBLE_REPEATED_SCALAR_SLOTS);
        groupItem.getChildren().clear();
        for (int i = 0; i < slotCount; i++) {
            Object slotValue = i < values.size() ? toTreeValue(fd, values.get(i)) : null;
            String slotName = displayName + " " + (i + 1);
            ConfigTreeItem item = createValueItem(slotName, fd.getName(), slotValue, fd, configType, variantNumber);
            if (item != null) {
                groupItem.getChildren().add(new TreeItem<>(item));
            }
        }
    }

    /**
     * Finds the active field of a named {@code oneof}.
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
     * Converts a protobuf {@code snake_case} field name into a readable label.
     * Example: {@code "node_info_broadcast_secs"} becomes {@code "Node info broadcast secs"}.
     */
    public static String humanize(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) { return fieldName; }
        String result = fieldName.replace('_', ' ');
        return result.substring(0, 1).toUpperCase(Locale.ROOT) + result.substring(1);
    }

    private static String sectionDisplayName(String sectionName) {
        String key = SECTION_NAME_KEYS.get(sectionName);
        return key != null ? I18n.t(key) : humanize(sectionName);
    }

    /**
     * Rebuilds a protobuf {@code Config} from the edited tree before sending it
     * to the device.
     */
    public static ConfigProtos.Config rebuildConfig(TreeItem<ConfigTreeItem> sectionItem,
                                                      ConfigProtos.Config originalConfig) {
        FieldDescriptor oneofField = getActiveOneofField(originalConfig, "payload_variant");
        if (oneofField == null) { return null; }

        Message originalSection = (Message) originalConfig.getField(oneofField);
        Message.Builder sectionBuilder = originalSection.toBuilder();

        applyTreeValues(sectionItem, sectionBuilder);

        ConfigProtos.Config rebuilt = ConfigProtos.Config.newBuilder()
                .setField(oneofField, sectionBuilder.build())
                .build();
        if (rebuilt.getPayloadVariantCase() == ConfigProtos.Config.PayloadVariantCase.LORA) {
            log.debug("rebuildConfig LORA ignore_incoming: original {} -> rebuilt {}",
                    ConfigDebugFormatter.describeIgnoreIncoming(originalConfig),
                    ConfigDebugFormatter.describeIgnoreIncoming(rebuilt));
        }
        return rebuilt;
    }

    /**
     * Rebuilds a protobuf {@code ModuleConfig} from the edited tree before sending it.
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
     * Recursively applies tree values to a protobuf builder.
     */
    private static void applyTreeValues(TreeItem<ConfigTreeItem> treeItem, Message.Builder builder) {
        for (TreeItem<ConfigTreeItem> child : treeItem.getChildren()) {
            ConfigTreeItem item = child.getValue();
            if (item.isCategory()) {
                FieldDescriptor groupFd = item.getFieldDescriptor();
                if (groupFd != null && groupFd.isRepeated() && groupFd.getType() == FieldDescriptor.Type.BYTES) {
                    applyRepeatedBytesValues(child, builder, groupFd);
                    continue;
                }
                if (groupFd != null && groupFd.isRepeated() && isSupportedRepeatedScalar(groupFd)) {
                    applyRepeatedScalarValues(child, builder, groupFd);
                    continue;
                }
                if (groupFd == null || groupFd.isRepeated() || groupFd.getType() != FieldDescriptor.Type.MESSAGE) {
                    continue;
                }
                FieldDescriptor builderFd = builder.getDescriptorForType().findFieldByName(groupFd.getName());
                if (builderFd == null || builderFd.getType() != FieldDescriptor.Type.MESSAGE) {
                    continue;
                }

                Message currentSubMessage = builder.hasField(builderFd)
                        ? (Message) builder.getField(builderFd)
                        : builder.newBuilderForField(builderFd).build();
                Message.Builder subBuilder = currentSubMessage.toBuilder();
                applyTreeValues(child, subBuilder);
                builder.setField(builderFd, subBuilder.build());
                continue;
            }
            if (item.getFieldDescriptor() == null || item.getValue() == null) { continue; }

            FieldDescriptor fd = item.getFieldDescriptor();
            // Resolve the field descriptor on the current builder; it may differ from the original one.
            FieldDescriptor builderFd = builder.getDescriptorForType().findFieldByName(fd.getName());
            if (builderFd == null) { continue; }

            Object value = item.getValue();
            log.debug("applyTreeValues: field='{}' value={} (type={})", builderFd.getName(), value, builderFd.getType());
            try {
                Object builderValue = toBuilderValue(builderFd, value);
                if (builderValue != null) {
                    builder.setField(builderFd, builderValue);
                }
            } catch (Exception e) { //NOPMD - field type mismatch is expected and safely skipped
                log.trace("Skipping field '{}': {}", builderFd.getName(), e.getMessage());
            }
        }
    }

    private static void applyRepeatedBytesValues(TreeItem<ConfigTreeItem> groupItem,
                                                 Message.Builder builder,
                                                 FieldDescriptor fieldDescriptor) {
        FieldDescriptor builderFd = builder.getDescriptorForType().findFieldByName(fieldDescriptor.getName());
        if (builderFd == null || !builderFd.isRepeated() || builderFd.getType() != FieldDescriptor.Type.BYTES) {
            return;
        }

        builder.clearField(builderFd);
        for (TreeItem<ConfigTreeItem> child : groupItem.getChildren()) {
            ConfigTreeItem valueItem = child.getValue();
            if (valueItem == null || valueItem.getValue() == null) {
                continue;
            }
            String base64Str = valueItem.getValue().toString().trim();
            if (base64Str.isEmpty()) {
                continue;
            }
            try {
                builder.addRepeatedField(builderFd, ByteString.copyFrom(Base64.getDecoder().decode(base64Str)));
            } catch (IllegalArgumentException e) {
                log.trace("Skipping invalid repeated bytes field '{}': {}", builderFd.getName(), e.getMessage());
            }
        }
    }

    private static void applyRepeatedScalarValues(TreeItem<ConfigTreeItem> groupItem,
                                                  Message.Builder builder,
                                                  FieldDescriptor fieldDescriptor) {
        FieldDescriptor builderFd = builder.getDescriptorForType().findFieldByName(fieldDescriptor.getName());
        if (builderFd == null || !builderFd.isRepeated() || !isSupportedRepeatedScalar(builderFd)) {
            return;
        }

        boolean debugIgnoreIncoming = isIgnoreIncomingField(builderFd);
        if (debugIgnoreIncoming) {
            log.debug("applyRepeatedScalarValues ignore_incoming tree slots: {}",
                    describeRepeatedScalarSlots(groupItem));
        }

        builder.clearField(builderFd);
        int slotIndex = 0;
        for (TreeItem<ConfigTreeItem> child : groupItem.getChildren()) {
            slotIndex++;
            ConfigTreeItem valueItem = child.getValue();
            if (valueItem == null || valueItem.getValue() == null) {
                if (debugIgnoreIncoming) {
                    log.debug("applyRepeatedScalarValues ignore_incoming slot {} skipped: empty", slotIndex);
                }
                continue;
            }
            try {
                Object builderValue = toBuilderValue(builderFd, valueItem.getValue());
                if (builderValue != null) {
                    builder.addRepeatedField(builderFd, builderValue);
                    if (debugIgnoreIncoming) {
                        log.debug("applyRepeatedScalarValues ignore_incoming slot {} raw={} -> {}",
                                slotIndex,
                                valueItem.getValue(),
                                ConfigDebugFormatter.formatObjectNodeNum(builderValue));
                    }
                } else if (debugIgnoreIncoming) {
                    log.debug("applyRepeatedScalarValues ignore_incoming slot {} skipped: builderValue=null raw={}",
                            slotIndex, valueItem.getValue());
                }
            } catch (Exception e) { //NOPMD - invalid repeated entry should not break whole save
                if (debugIgnoreIncoming) {
                    log.warn("Skipping invalid repeated scalar field '{}' slot {} raw={}: {}",
                            builderFd.getName(), slotIndex,
                            valueItem.getValue(), e.getMessage());
                } else {
                    log.trace("Skipping invalid repeated scalar field '{}': {}", builderFd.getName(), e.getMessage());
                }
            }
        }

        if (debugIgnoreIncoming) {
            @SuppressWarnings("unchecked")
            List<Object> builtValues = (List<Object>) builder.getField(builderFd);
            log.debug("applyRepeatedScalarValues ignore_incoming result: {}",
                    ConfigDebugFormatter.describeNodeNumObjects(builtValues));
        }
    }

    private static boolean isIgnoreIncomingField(FieldDescriptor fieldDescriptor) {
        return fieldDescriptor != null
                && "meshtastic.Config.LoRaConfig.ignore_incoming".equals(fieldDescriptor.getFullName());
    }

    private static String describeRepeatedScalarSlots(TreeItem<ConfigTreeItem> groupItem) {
        if (groupItem == null || groupItem.getChildren().isEmpty()) {
            return "count=0 []";
        }
        List<String> slots = new ArrayList<>();
        int index = 0;
        for (TreeItem<ConfigTreeItem> child : groupItem.getChildren()) {
            index++;
            ConfigTreeItem valueItem = child.getValue();
            Object value = valueItem != null ? valueItem.getValue() : null;
            slots.add(index + "=" + (value == null ? "<empty>" : ConfigDebugFormatter.formatObjectNodeNum(value)));
        }
        return "count=" + groupItem.getChildren().size() + " [" + String.join(", ", slots) + "]";
    }

    /**
     * Applies protobuf message values to an existing tree.
     * Used when importing a configuration file into the current editor state.
     */
    public static void applyMessageToTree(TreeItem<ConfigTreeItem> treeItem, Message message) {
        if (treeItem == null || message == null) {
            return;
        }

        for (TreeItem<ConfigTreeItem> child : treeItem.getChildren()) {
            ConfigTreeItem item = child.getValue();
            if (item == null) {
                continue;
            }

            if (item.isCategory()) {
                FieldDescriptor fd = item.getFieldDescriptor();
                if (fd != null && fd.isRepeated() && fd.getType() == FieldDescriptor.Type.BYTES) {
                    FieldDescriptor messageFd = message.getDescriptorForType().findFieldByName(fd.getName());
                    if (messageFd == null || !messageFd.isRepeated() || messageFd.getType() != FieldDescriptor.Type.BYTES) {
                        continue;
                    }
                    syncRepeatedBytesGroup(child, messageFd, message.getField(messageFd),
                            item.getConfigType(), item.getConfigVariantNumber(), item.getName());
                    continue;
                }
                if (fd != null && fd.isRepeated() && isSupportedRepeatedScalar(fd)) {
                    FieldDescriptor messageFd = message.getDescriptorForType().findFieldByName(fd.getName());
                    if (messageFd == null || !messageFd.isRepeated() || !isSupportedRepeatedScalar(messageFd)) {
                        continue;
                    }
                    syncRepeatedScalarGroup(child, messageFd, message.getField(messageFd),
                            item.getConfigType(), item.getConfigVariantNumber(), item.getName());
                    continue;
                }
                if (fd == null || fd.isRepeated() || fd.getType() != FieldDescriptor.Type.MESSAGE) {
                    continue;
                }
                FieldDescriptor messageFd = message.getDescriptorForType().findFieldByName(fd.getName());
                if (messageFd == null || messageFd.getType() != FieldDescriptor.Type.MESSAGE || !message.hasField(messageFd)) {
                    continue;
                }
                applyMessageToTree(child, (Message) message.getField(messageFd));
                continue;
            }

            String fieldName = item.getFieldName();
            if (fieldName == null || fieldName.isEmpty()) {
                continue;
            }
            FieldDescriptor messageFd = message.getDescriptorForType().findFieldByName(fieldName);
            if (messageFd == null) {
                continue;
            }
            item.setValue(toTreeValue(messageFd, message.getField(messageFd)));
        }
    }

    private static Object toTreeValue(FieldDescriptor fd, Object value) {
        if (value == null) {
            return null;
        }
        if (fd.getType() == FieldDescriptor.Type.BYTES) {
            if (fd.isRepeated()) {
                @SuppressWarnings("unchecked")
                List<ByteString> bytesList = (List<ByteString>) value;
                return bytesList.stream()
                        .map(bs -> Base64.getEncoder().encodeToString(bs.toByteArray()))
                        .collect(Collectors.joining(", "));
            }
            ByteString bs = (ByteString) value;
            return Base64.getEncoder().encodeToString(bs.toByteArray());
        }
        return value;
    }

    private static ConfigTreeItem createValueItem(String displayName,
                                                  String fieldName,
                                                  Object value,
                                                  FieldDescriptor fd,
                                                  String configType,
                                                  int variantNumber) {
        if (fd.getType() == FieldDescriptor.Type.ENUM) {
            EnumValueDescriptor enumVal = value instanceof EnumValueDescriptor evd ? evd : null;
            List<EnumValueDescriptor> enumValues = new ArrayList<>(fd.getEnumType().getValues());
            return new ConfigTreeItem(
                    displayName, fieldName, enumVal, EnumValueDescriptor.class,
                    enumValues, fd, configType, variantNumber);
        }
        if (fd.getType() == FieldDescriptor.Type.BOOL) {
            return new ConfigTreeItem(
                    displayName, fieldName, value, Boolean.class,
                    null, fd, configType, variantNumber);
        }
        if (fd.getType() == FieldDescriptor.Type.STRING) {
            return new ConfigTreeItem(
                    displayName, fieldName, value, String.class,
                    null, fd, configType, variantNumber);
        }
        if (fd.getType() == FieldDescriptor.Type.FLOAT) {
            return new ConfigTreeItem(
                    displayName, fieldName, value, Float.class,
                    null, fd, configType, variantNumber);
        }
        if (fd.getType() == FieldDescriptor.Type.DOUBLE) {
            return new ConfigTreeItem(
                    displayName, fieldName, value, Double.class,
                    null, fd, configType, variantNumber);
        }
        if (fd.getType() == FieldDescriptor.Type.UINT32
                || fd.getType() == FieldDescriptor.Type.INT32
                || fd.getType() == FieldDescriptor.Type.SINT32
                || fd.getType() == FieldDescriptor.Type.FIXED32
                || fd.getType() == FieldDescriptor.Type.SFIXED32) {
            return new ConfigTreeItem(
                    displayName, fieldName, value, Integer.class,
                    null, fd, configType, variantNumber);
        }
        if (fd.getType() == FieldDescriptor.Type.UINT64
                || fd.getType() == FieldDescriptor.Type.INT64
                || fd.getType() == FieldDescriptor.Type.SINT64
                || fd.getType() == FieldDescriptor.Type.FIXED64
                || fd.getType() == FieldDescriptor.Type.SFIXED64) {
            return new ConfigTreeItem(
                    displayName, fieldName, value, Long.class,
                    null, fd, configType, variantNumber);
        }
        if (fd.getType() == FieldDescriptor.Type.BYTES) {
            ByteString bs = value instanceof ByteString byteString ? byteString : ByteString.EMPTY;
            String base64Value = Base64.getEncoder().encodeToString(bs.toByteArray());
            return new ConfigTreeItem(
                    displayName, fieldName, base64Value, String.class,
                    null, fd, configType, variantNumber);
        }
        return null;
    }

    private static Object toBuilderValue(FieldDescriptor builderFd, Object value) {
        if (builderFd.getType() == FieldDescriptor.Type.ENUM) {
            if (value instanceof EnumValueDescriptor evd) {
                return evd;
            }
            return null;
        }
        if (builderFd.getType() == FieldDescriptor.Type.BOOL) {
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
        }
        if (builderFd.getType() == FieldDescriptor.Type.STRING) {
            return value.toString();
        }
        if (builderFd.getType() == FieldDescriptor.Type.FLOAT) {
            return value instanceof Number number
                    ? number.floatValue()
                    : Float.parseFloat(value.toString().trim());
        }
        if (builderFd.getType() == FieldDescriptor.Type.DOUBLE) {
            return value instanceof Number number
                    ? number.doubleValue()
                    : Double.parseDouble(value.toString().trim());
        }
        if (builderFd.getType() == FieldDescriptor.Type.UINT32
                || builderFd.getType() == FieldDescriptor.Type.INT32
                || builderFd.getType() == FieldDescriptor.Type.SINT32
                || builderFd.getType() == FieldDescriptor.Type.FIXED32
                || builderFd.getType() == FieldDescriptor.Type.SFIXED32) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            Object parsed = ConfigValueFormatter.parseTextValue(builderFd, Integer.class, value.toString());
            return parsed instanceof Number number ? number.intValue() : null;
        }
        if (builderFd.getType() == FieldDescriptor.Type.UINT64
                || builderFd.getType() == FieldDescriptor.Type.INT64
                || builderFd.getType() == FieldDescriptor.Type.SINT64
                || builderFd.getType() == FieldDescriptor.Type.FIXED64
                || builderFd.getType() == FieldDescriptor.Type.SFIXED64) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            Object parsed = ConfigValueFormatter.parseTextValue(builderFd, Long.class, value.toString());
            return parsed instanceof Number number ? number.longValue() : null;
        }
        if (builderFd.getType() == FieldDescriptor.Type.BYTES) {
            String base64Str = value.toString().trim();
            if (base64Str.isEmpty()) {
                return ByteString.EMPTY;
            }
            return ByteString.copyFrom(Base64.getDecoder().decode(base64Str));
        }
        return null;
    }

    private static boolean isSupportedRepeatedScalar(FieldDescriptor fd) {
        return fd.getType() != FieldDescriptor.Type.MESSAGE && fd.getType() != FieldDescriptor.Type.BYTES;
    }
}
