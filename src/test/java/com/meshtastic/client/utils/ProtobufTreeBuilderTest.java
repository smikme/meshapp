package com.meshtastic.client.utils;

import com.google.protobuf.ByteString;
import com.meshtastic.client.model.ConfigTreeItem;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ConfigProtos;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtobufTreeBuilderTest {

    @Test
    void buildConfigTreeShowsAdminKeysAsSeparateSlots() {
        ConfigProtos.Config config = securityConfig("alpha", "bravo");

        TreeItem<ConfigTreeItem> root = ProtobufTreeBuilder.buildConfigTree(List.of(config));
        TreeItem<ConfigTreeItem> securitySection = root.getChildren().get(0);
        TreeItem<ConfigTreeItem> adminKeysGroup = findChildByFieldName(securitySection, "admin_key");

        assertNotNull(adminKeysGroup);
        assertTrue(adminKeysGroup.getValue().isCategory());
        assertTrue(adminKeysGroup.getChildren().size() >= 3);
        assertEquals(base64("alpha"), adminKeysGroup.getChildren().get(0).getValue().getValue());
        assertEquals(base64("bravo"), adminKeysGroup.getChildren().get(1).getValue().getValue());
        assertEquals("", adminKeysGroup.getChildren().get(2).getValue().getValue());

        ConfigProtos.Config rebuilt = ProtobufTreeBuilder.rebuildConfig(securitySection, config);
        assertEquals(config.getSecurity().getAdminKeyList(), rebuilt.getSecurity().getAdminKeyList());
    }

    @Test
    void rebuildConfigAppliesAdminKeyAdditionsAndRemovals() {
        ConfigProtos.Config config = securityConfig("alpha", "bravo");

        TreeItem<ConfigTreeItem> root = ProtobufTreeBuilder.buildConfigTree(List.of(config));
        TreeItem<ConfigTreeItem> securitySection = root.getChildren().get(0);
        TreeItem<ConfigTreeItem> adminKeysGroup = findChildByFieldName(securitySection, "admin_key");
        assertNotNull(adminKeysGroup);

        adminKeysGroup.getChildren().get(0).getValue().setValue("");
        adminKeysGroup.getChildren().get(2).getValue().setValue(base64("charlie"));

        ConfigProtos.Config rebuilt = ProtobufTreeBuilder.rebuildConfig(securitySection, config);
        assertEquals(List.of(bytes("bravo"), bytes("charlie")), rebuilt.getSecurity().getAdminKeyList());
    }

    @Test
    void applyMessageToTreeResizesAdminKeySlots() {
        ConfigProtos.Config baseConfig = securityConfig("alpha");
        ConfigProtos.Config updatedConfig = securityConfig("alpha", "bravo", "charlie");

        TreeItem<ConfigTreeItem> root = ProtobufTreeBuilder.buildConfigTree(List.of(baseConfig));
        TreeItem<ConfigTreeItem> securitySection = root.getChildren().get(0);
        TreeItem<ConfigTreeItem> adminKeysGroup = findChildByFieldName(securitySection, "admin_key");
        assertNotNull(adminKeysGroup);

        ProtobufTreeBuilder.applyMessageToTree(securitySection, updatedConfig.getSecurity());

        assertTrue(adminKeysGroup.getChildren().size() >= 4);
        assertEquals(base64("alpha"), adminKeysGroup.getChildren().get(0).getValue().getValue());
        assertEquals(base64("bravo"), adminKeysGroup.getChildren().get(1).getValue().getValue());
        assertEquals(base64("charlie"), adminKeysGroup.getChildren().get(2).getValue().getValue());
        assertEquals("", adminKeysGroup.getChildren().get(3).getValue().getValue());
    }

    @Test
    void buildConfigTreeShowsIgnoreIncomingAsSeparateSlots() {
        ConfigProtos.Config config = loraConfig(0x9E755AF0L, 0x12345678L);

        TreeItem<ConfigTreeItem> root = ProtobufTreeBuilder.buildConfigTree(List.of(config));
        TreeItem<ConfigTreeItem> loraSection = root.getChildren().get(0);
        TreeItem<ConfigTreeItem> ignoreIncomingGroup = findChildByFieldName(loraSection, "ignore_incoming");

        assertNotNull(ignoreIncomingGroup);
        assertTrue(ignoreIncomingGroup.getValue().isCategory());
        assertTrue(ignoreIncomingGroup.getChildren().size() >= 3);
        assertEquals((int) 0x9E755AF0L, ignoreIncomingGroup.getChildren().get(0).getValue().getValue());
        assertEquals(0x12345678, ignoreIncomingGroup.getChildren().get(1).getValue().getValue());
        assertNull(ignoreIncomingGroup.getChildren().get(2).getValue().getValue());
    }

    @Test
    void rebuildConfigAppliesIgnoreIncomingAdditionsAndRemovals() {
        ConfigProtos.Config config = loraConfig(0x9E755AF0L, 0x12345678L);

        TreeItem<ConfigTreeItem> root = ProtobufTreeBuilder.buildConfigTree(List.of(config));
        TreeItem<ConfigTreeItem> loraSection = root.getChildren().get(0);
        TreeItem<ConfigTreeItem> ignoreIncomingGroup = findChildByFieldName(loraSection, "ignore_incoming");
        assertNotNull(ignoreIncomingGroup);

        ignoreIncomingGroup.getChildren().get(0).getValue().setValue(null);
        ignoreIncomingGroup.getChildren().get(2).getValue().setValue((int) 0xCAFEBABEL);

        ConfigProtos.Config rebuilt = ProtobufTreeBuilder.rebuildConfig(loraSection, config);
        assertEquals(List.of(0x12345678, (int) 0xCAFEBABEL), rebuilt.getLora().getIgnoreIncomingList());
    }

    @Test
    void applyMessageToTreeResizesIgnoreIncomingSlots() {
        ConfigProtos.Config baseConfig = loraConfig(0x9E755AF0L);
        ConfigProtos.Config updatedConfig = loraConfig(0x9E755AF0L, 0x12345678L, 0xCAFEBABEL);

        TreeItem<ConfigTreeItem> root = ProtobufTreeBuilder.buildConfigTree(List.of(baseConfig));
        TreeItem<ConfigTreeItem> loraSection = root.getChildren().get(0);
        TreeItem<ConfigTreeItem> ignoreIncomingGroup = findChildByFieldName(loraSection, "ignore_incoming");
        assertNotNull(ignoreIncomingGroup);

        ProtobufTreeBuilder.applyMessageToTree(loraSection, updatedConfig.getLora());

        assertTrue(ignoreIncomingGroup.getChildren().size() >= 4);
        assertEquals((int) 0x9E755AF0L, ignoreIncomingGroup.getChildren().get(0).getValue().getValue());
        assertEquals(0x12345678, ignoreIncomingGroup.getChildren().get(1).getValue().getValue());
        assertEquals((int) 0xCAFEBABEL, ignoreIncomingGroup.getChildren().get(2).getValue().getValue());
        assertNull(ignoreIncomingGroup.getChildren().get(3).getValue().getValue());
    }

    private static TreeItem<ConfigTreeItem> findChildByFieldName(TreeItem<ConfigTreeItem> parent, String fieldName) {
        for (TreeItem<ConfigTreeItem> child : parent.getChildren()) {
            ConfigTreeItem item = child.getValue();
            if (item != null && fieldName.equals(item.getFieldName())) {
                return child;
            }
        }
        return null;
    }

    private static ConfigProtos.Config securityConfig(String... adminKeys) {
        ConfigProtos.Config.SecurityConfig.Builder security = ConfigProtos.Config.SecurityConfig.newBuilder();
        for (String key : adminKeys) {
            security.addAdminKey(bytes(key));
        }
        return ConfigProtos.Config.newBuilder()
                .setSecurity(security.build())
                .build();
    }

    private static ConfigProtos.Config loraConfig(long... ignoredNodeNums) {
        ConfigProtos.Config.LoRaConfig.Builder lora = ConfigProtos.Config.LoRaConfig.newBuilder();
        for (long ignoredNodeNum : ignoredNodeNums) {
            lora.addIgnoreIncoming((int) ignoredNodeNum);
        }
        return ConfigProtos.Config.newBuilder()
                .setLora(lora.build())
                .build();
    }

    private static ByteString bytes(String value) {
        return ByteString.copyFrom(value, StandardCharsets.UTF_8);
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
