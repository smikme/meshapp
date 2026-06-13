package com.meshtastic.client.forms.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.utils.ProtobufTreeBuilder;
import java.util.List;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

class ConfigChangeCollectorTest {

    @Test
    void collectBuildsStoreForwardModuleConfigWhenOriginalIsMissing() {
        ModuleConfigProtos.ModuleConfig template =
            ModuleConfigProtos.ModuleConfig.newBuilder()
                .setStoreForward(
                    ModuleConfigProtos.ModuleConfig.StoreForwardConfig.newBuilder()
                        .setEnabled(false)
                        .build()
                )
                .build();
        TreeItem<ConfigTreeItem> root = new TreeItem<>(
            new ConfigTreeItem("Root", "root", 0)
        );
        TreeItem<ConfigTreeItem> moduleRoot =
            ProtobufTreeBuilder.buildModuleConfigTree(List.of(template));
        root.getChildren().add(moduleRoot);

        ConfigTreeItem enabled = findField(root, "enabled");
        assertNotNull(enabled);
        enabled.setValue(true);

        ConfigChangeSet changes = ConfigChangeCollector.collect(
            root,
            List.of(),
            List.of(),
            List.of(),
            MeshProtos.User.getDefaultInstance(),
            null
        );

        assertEquals(1, changes.moduleConfigs().size());
        ModuleConfigProtos.ModuleConfig saved = changes
            .moduleConfigs()
            .getFirst();
        assertTrue(saved.hasStoreForward());
        assertTrue(saved.getStoreForward().getEnabled());
    }

    private static ConfigTreeItem findField(
        TreeItem<ConfigTreeItem> root,
        String fieldName
    ) {
        if (root == null) {
            return null;
        }
        ConfigTreeItem item = root.getValue();
        if (item != null && fieldName.equals(item.getFieldName())) {
            return item;
        }
        for (TreeItem<ConfigTreeItem> child : root.getChildren()) {
            ConfigTreeItem found = findField(child, fieldName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
