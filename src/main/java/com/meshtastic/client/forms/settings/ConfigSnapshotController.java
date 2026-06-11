package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.ConfigSnapshotService;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javafx.scene.control.TreeItem;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates exporting and importing configuration snapshots.
 * The controller owns file naming, file chooser setup, snapshot I/O, and
 * user-facing success/error reporting.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigSnapshotController {

    private static final Logger log = LoggerFactory.getLogger(
        ConfigSnapshotController.class
    );

    private final Host host;

    /**
     * Creates a controller bound to a settings form host.
     *
     * @param host host callbacks
     */
    public ConfigSnapshotController(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Exports the current editor content to a snapshot file.
     *
     * @param kind snapshot kind
     */
    public void exportSnapshot(ConfigSnapshotService.SnapshotKind kind) {
        if (!ensureEditorAvailableForSnapshotOperation()) {
            return;
        }

        TreeItem<ConfigTreeItem> root = host.currentEditorRoot();
        if (root == null) {
            return;
        }

        try {
            ConfigSnapshotService.ConfigSnapshot snapshot =
                ConfigSnapshotEditor.createSnapshot(
                    kind,
                    root,
                    host.state(),
                    host.originalConfigs(),
                    host.originalModuleConfigs(),
                    host.originalChannels(),
                    host.workingChannels()
                );

            FileChooser chooser = createSnapshotFileChooser(kind, true);
            File target = chooser.showSaveDialog(host.currentWindow());
            if (target == null) {
                return;
            }

            File outputFile = ensureSnapshotExtension(target, kind);
            ConfigSnapshotService.writeSnapshot(outputFile.toPath(), snapshot);
            Toast.show(
                Toast.Type.SUCCESS,
                switch (kind) {
                    case CONFIG -> I18n.t(
                        "settings.snapshot.saved.config",
                        outputFile.getName()
                    );
                    case TEMPLATE -> I18n.t(
                        "settings.snapshot.saved.template",
                        outputFile.getName()
                    );
                }
            );
        } catch (Exception e) {
            log.error("Snapshot export failed", e);
            ModalPane.showError(
                kind == ConfigSnapshotService.SnapshotKind.CONFIG
                    ? I18n.t("settings.snapshot.saveConfig.error.title")
                    : I18n.t("settings.snapshot.saveTemplate.error.title"),
                e.getMessage() != null
                    ? e.getMessage()
                    : I18n.t("settings.snapshot.save.errorFallback")
            );
        }
    }

    /**
     * Imports a snapshot file into the current editor.
     *
     * @param kind expected snapshot kind
     */
    public void importSnapshot(ConfigSnapshotService.SnapshotKind kind) {
        if (!ensureEditorAvailableForSnapshotOperation()) {
            return;
        }

        FileChooser chooser = createSnapshotFileChooser(kind, false);
        File source = chooser.showOpenDialog(host.currentWindow());
        if (source == null) {
            return;
        }

        Runnable importAction = () -> importSnapshot(source, kind);
        if (host.hasPendingEditorChanges()) {
            ModalPane.showConfirm(
                kind == ConfigSnapshotService.SnapshotKind.CONFIG
                    ? I18n.t("settings.snapshot.loadConfig.confirmTitle")
                    : I18n.t("settings.snapshot.loadTemplate.confirmTitle"),
                I18n.t("settings.snapshot.unsavedConfirm"),
                confirmed -> {
                    if (confirmed) {
                        importAction.run();
                    }
                }
            );
        } else {
            importAction.run();
        }
    }

    private boolean ensureEditorAvailableForSnapshotOperation() {
        if (host.currentEditorRoot() == null) {
            host.reloadConfigTree();
        }

        ConnectionEntry activeEntry = host.findActiveConnectionEntry();
        if (host.isConfigExchangeInProgress(activeEntry)) {
            host.watchConfigExchangeCompletion(activeEntry);
            Toast.show(
                Toast.Type.WARNING,
                I18n.t("settings.status.waitDeviceConfigRead")
            );
            return false;
        }

        if (host.currentEditorRoot() == null) {
            Toast.show(
                Toast.Type.WARNING,
                I18n.t("settings.status.loadRadioConfigFirst")
            );
            return false;
        }
        return true;
    }

    private void importSnapshot(
        File source,
        ConfigSnapshotService.SnapshotKind expectedKind
    ) {
        try {
            host.reloadConfigTree();
            if (host.currentEditorRoot() == null) {
                throw new IllegalStateException(
                    I18n.t("settings.snapshot.editorNotLoaded")
                );
            }

            ConfigSnapshotService.ConfigSnapshot snapshot =
                ConfigSnapshotService.readSnapshot(source.toPath());
            if (snapshot.kind() != expectedKind) {
                throw new IllegalArgumentException(
                    I18n.t(
                        "settings.snapshot.wrongType",
                        expectedKind.extension()
                    )
                );
            }

            host.applySnapshotToEditor(snapshot);
            host.refreshConfigTreeView();
            host.setSaveConfigButtonDisabled(false);
            String fileKind = snapshotKindLabel(expectedKind);
            host.setStatus(
                I18n.t("settings.snapshot.loadedStatus", fileKind, source.getName())
            );
            Toast.show(
                Toast.Type.SUCCESS,
                I18n.t("settings.snapshot.loadedToast", fileKind, source.getName())
            );
        } catch (Exception e) {
            log.error("Snapshot import failed", e);
            ModalPane.showError(
                expectedKind == ConfigSnapshotService.SnapshotKind.CONFIG
                    ? I18n.t("settings.snapshot.loadConfig.error.title")
                    : I18n.t("settings.snapshot.loadTemplate.error.title"),
                e.getMessage() != null
                    ? e.getMessage()
                    : I18n.t("settings.snapshot.load.errorFallback")
            );
        }
    }

    private FileChooser createSnapshotFileChooser(
        ConfigSnapshotService.SnapshotKind kind,
        boolean saveMode
    ) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(
            switch (kind) {
                case CONFIG -> saveMode
                    ? I18n.t("settings.snapshot.fileChooser.saveConfig")
                    : I18n.t("settings.snapshot.fileChooser.loadConfig");
                case TEMPLATE -> saveMode
                    ? I18n.t("settings.snapshot.fileChooser.saveTemplate")
                    : I18n.t("settings.snapshot.fileChooser.loadTemplate");
            }
        );
        chooser
            .getExtensionFilters()
            .add(
                new FileChooser.ExtensionFilter(
                    kind == ConfigSnapshotService.SnapshotKind.CONFIG
                        ? I18n.t(
                            "settings.snapshot.fileFilter.config",
                            kind.extension()
                        )
                        : I18n.t(
                            "settings.snapshot.fileFilter.template",
                            kind.extension()
                        ),
                    "*." + kind.extension()
                )
            );
        if (saveMode) {
            chooser.setInitialFileName(buildSuggestedSnapshotName(kind));
        }
        return chooser;
    }

    private String buildSuggestedSnapshotName(
        ConfigSnapshotService.SnapshotKind kind
    ) {
        String baseName = "mesh-config";
        DeviceState state = host.state();
        if (state != null) {
            NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
            if (myNode != null) {
                if (
                    myNode.getLongName() != null &&
                    !myNode.getLongName().isBlank()
                ) {
                    baseName = myNode.getLongName().trim();
                } else if (
                    myNode.getNodeId() != null && !myNode.getNodeId().isBlank()
                ) {
                    baseName = myNode.getNodeId().trim();
                }
            }
        }
        baseName = baseName
            .replaceAll("[\\\\/:*?\"<>|]+", "_")
            .replaceAll("\\s+", "_");
        if (baseName.isBlank()) {
            baseName = "mesh-config";
        }
        if (kind == ConfigSnapshotService.SnapshotKind.TEMPLATE) {
            baseName += "-template";
        }
        return baseName;
    }

    private File ensureSnapshotExtension(
        File file,
        ConfigSnapshotService.SnapshotKind kind
    ) {
        String expectedSuffix = "." + kind.extension();
        String fileName = file.getName();
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        String duplicateSuffix = expectedSuffix + expectedSuffix;

        if (lowerName.endsWith(duplicateSuffix)) {
            fileName = fileName.substring(
                0,
                fileName.length() - expectedSuffix.length()
            );
            lowerName = fileName.toLowerCase(Locale.ROOT);
        }

        if (lowerName.endsWith(expectedSuffix)) {
            File parent = file.getParentFile();
            return parent != null
                ? new File(parent, fileName)
                : new File(fileName);
        }
        File parent = file.getParentFile();
        return parent != null
            ? new File(parent, fileName + expectedSuffix)
            : new File(fileName + expectedSuffix);
    }

    private String snapshotKindLabel(ConfigSnapshotService.SnapshotKind kind) {
        return kind == ConfigSnapshotService.SnapshotKind.CONFIG
            ? I18n.t("settings.snapshot.kind.config")
            : I18n.t("settings.snapshot.kind.template");
    }

    /**
     * Host callbacks implemented by the settings form.
     */
    public interface Host {
        DeviceState state();
        TreeItem<ConfigTreeItem> currentEditorRoot();
        List<ConfigProtos.Config> originalConfigs();
        List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs();
        List<ChannelProtos.Channel> originalChannels();
        List<ChannelProtos.Channel> workingChannels();
        ConnectionEntry findActiveConnectionEntry();
        boolean isConfigExchangeInProgress(ConnectionEntry entry);
        void watchConfigExchangeCompletion(ConnectionEntry entry);
        void reloadConfigTree();
        Window currentWindow();
        boolean hasPendingEditorChanges();
        void applySnapshotToEditor(
            ConfigSnapshotService.ConfigSnapshot snapshot
        );
        void refreshConfigTreeView();
        void setSaveConfigButtonDisabled(boolean disabled);
        void setStatus(String status);
    }
}
