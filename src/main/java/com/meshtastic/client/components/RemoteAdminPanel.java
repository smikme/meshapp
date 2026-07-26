package com.meshtastic.client.components;

import static com.meshtastic.client.forms.settings.ConfigEditorConstants.CONFIG_ROOT_TYPE;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.MODULE_CONFIG_ROOT_TYPE;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.meshtastic.client.forms.settings.ConfigChangeCollector;
import com.meshtastic.client.forms.settings.ConfigChangeSet;
import com.meshtastic.client.forms.settings.ConfigCompatibilityValidator;
import com.meshtastic.client.forms.settings.ConfigHelpPopupController;
import com.meshtastic.client.forms.settings.ConfigPanelFactory;
import com.meshtastic.client.forms.settings.ConfigProtobufSupport;
import com.meshtastic.client.forms.settings.ConfigSnapshotEditor;
import com.meshtastic.client.forms.settings.ConfigTreeItemSupport;
import com.meshtastic.client.forms.settings.MeshtasticConfigTreeBuilder;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import com.meshtastic.client.service.LocalRemoteAdminBackend;
import com.meshtastic.client.service.RemoteAdminBackend;
import com.meshtastic.client.service.RemoteAdminService;
import com.meshtastic.client.service.RemoteAdminSession;
import com.meshtastic.client.service.RpcRemoteAdminBackend;
import com.meshtastic.client.utils.ProtobufTreeBuilder;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.UnicodeTextUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Window;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ConnStatusProtos;
import org.meshtastic.proto.ModuleConfigProtos;

/**
 * Modal window content for editing a remote node through Meshtastic AdminMessage.
 */
public final class RemoteAdminPanel extends VBox implements AutoCloseable {

    private static final double WINDOW_WIDTH = 860;
    private static final double WINDOW_HEIGHT = 760;
    private static final double MIN_WINDOW_WIDTH = 680;
    private static final double MIN_WINDOW_HEIGHT = 520;

    private final RemoteAdminBackend remoteAdminBackend;
    private final ConfigHelpPopupController configHelpPopupController = new ConfigHelpPopupController();
    private final String targetDisplayName;
    private final TextField searchField = new TextField();
    private final Label statusLabel = new Label();
    private final Label queryStatusLabel = new Label();
    private final Label commandStatusLabel = new Label();
    private final ProgressBar loadProgressBar = new ProgressBar(0);
    private final List<Button> commandButtons = new ArrayList<>();
    private final TreeTableView<ConfigTreeItem> configTree;
    private final Button refreshButton;
    private final Button saveButton;
    private final Button closeButton;

    private TreeItem<ConfigTreeItem> fullConfigRoot;
    private RemoteAdminSession loadedSession;
    private List<ConfigProtos.Config> originalConfigs = new ArrayList<>();
    private List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs = new ArrayList<>();
    private List<ChannelProtos.Channel> originalChannels = new ArrayList<>();
    private List<ChannelProtos.Channel> workingChannels = new ArrayList<>();
    private ModalPane modalPane;
    private boolean adminKeyConfirmed;

    /**
     * Creates a side panel for administering one remote Meshtastic node.
     * The constructor builds the section catalog locally. Network requests are
     * sent only after the user explicitly requests a section or command.
     *
     * @param localState state of the locally connected node
     * @param targetNode remote node selected in the node detail view
     * @param handler protocol handler used for ADMIN_APP packets
     */
    public RemoteAdminPanel(DeviceState localState, NodeData targetNode, ProtocolHandler handler) {
        this(targetNode, new LocalRemoteAdminBackend(handler, localState, targetNode));
    }

    private RemoteAdminPanel(NodeData targetNode, RemoteAdminBackend remoteAdminBackend) {
        setPrefSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setMinSize(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT);
        setMaxHeight(Double.MAX_VALUE);
        getStyleClass().addAll("packet-monitor-root", "modal-side-panel");

        this.remoteAdminBackend = remoteAdminBackend;
        this.configTree = ConfigPanelFactory.createConfigTree(
                configHelpPopupController,
                this::syncRepeatedEditorSlots);

        targetDisplayName = resolveDisplayName(targetNode);
        Label title = new Label(I18n.t("remoteAdmin.title", targetDisplayName));
        title.getStyleClass().add("form-title");
        Label nodeId = new Label(targetNode.getNodeId() != null ? targetNode.getNodeId() : "");
        nodeId.setStyle("-fx-opacity: 0.65;");

        VBox headerText = new VBox(2, title, nodeId);
        HBox.setHgrow(headerText, Priority.ALWAYS);

        refreshButton = toolbarButton(
                I18n.t("remoteAdmin.action.refresh"),
                "/icons/refresh.svg",
                this::showSectionCatalog);
        saveButton = toolbarButton(
                I18n.t("remoteAdmin.action.save"),
                "/icons/save-radio.svg",
                this::saveChanges);
        closeButton = toolbarButton(
                I18n.t("common.close"),
                "/icons/close.svg",
                this::closeAndHide);

        ToolBar toolbar = new ToolBar(refreshButton, saveButton, closeButton);
        toolbar.getStyleClass().add("config-toolbar");

        HBox header = new HBox(10, headerText, toolbar);
        header.setAlignment(Pos.CENTER_LEFT);

        TabPane tabs = new TabPane(
                contentTab(I18n.t("remoteAdmin.tab.config"), createConfigTabContent()),
                contentTab(I18n.t("remoteAdmin.tab.commands"), createCommandsTabContent()));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        setPadding(new Insets(12));
        setSpacing(10);
        getChildren().addAll(header, tabs);

        showSectionCatalog();
    }

    private VBox createConfigTabContent() {
        searchField.setPromptText(I18n.t("settings.config.search.placeholder"));
        searchField.textProperty().addListener((obs, oldText, newText) -> filterConfigTree(newText));

        statusLabel.getStyleClass().add("config-status-label");
        statusLabel.setWrapText(true);
        queryStatusLabel.getStyleClass().add("config-status-label");
        queryStatusLabel.setWrapText(true);
        loadProgressBar.setMaxWidth(Double.MAX_VALUE);
        loadProgressBar.setVisible(false);
        loadProgressBar.setManaged(false);

        Label prerequisite = new Label(I18n.t("remoteAdmin.prerequisite"));
        prerequisite.setWrapText(true);
        prerequisite.setStyle("-fx-opacity: 0.75;");

        VBox.setVgrow(configTree, Priority.ALWAYS);
        VBox content = new VBox(10, prerequisite, searchField, loadProgressBar, statusLabel, queryStatusLabel, configTree);
        content.setPadding(new Insets(10, 0, 0, 0));
        VBox.setVgrow(content, Priority.ALWAYS);
        return content;
    }

    private ScrollPane createCommandsTabContent() {
        commandStatusLabel.getStyleClass().add("config-status-label");
        commandStatusLabel.setWrapText(true);
        commandStatusLabel.setText(I18n.t("remoteAdmin.status.commandsLoadFirst"));

        VBox content = new VBox(12,
                commandStatusLabel,
                commandGroup(I18n.t("remoteAdmin.section.quickCommands"),
                        delayedCommandButton(
                                I18n.t("remoteAdmin.command.reboot"),
                                "/icons/restart-radio.svg",
                                () -> remoteAdminBackend.reboot(5),
                                I18n.t("remoteAdmin.command.cancelReboot"),
                                "/icons/ide-stop.svg",
                                () -> remoteAdminBackend.reboot(0)),
                        delayedCommandButton(
                                I18n.t("remoteAdmin.command.shutdown"),
                                "/icons/shutdown-radio.svg",
                                () -> remoteAdminBackend.shutdown(5),
                                I18n.t("remoteAdmin.command.cancelShutdown"),
                                "/icons/ide-stop.svg",
                                () -> remoteAdminBackend.shutdown(0)),
                        commandButton(I18n.t("remoteAdmin.command.syncTime"), "/icons/sync-time.svg",
                                () -> runCommand(I18n.t("remoteAdmin.command.syncTime"),
                                        () -> remoteAdminBackend.syncTime(System.currentTimeMillis() / 1000))),
                        commandButton(I18n.t("remoteAdmin.command.refreshStatus"), "/icons/refresh.svg",
                                this::refreshConnectionStatus)),
                commandGroup(I18n.t("remoteAdmin.section.maintenance"),
                        commandButton(I18n.t("remoteAdmin.command.backupFlash"), "/icons/save-config.svg",
                                () -> runCommand(I18n.t("remoteAdmin.command.backupFlash"),
                                        () -> remoteAdminBackend.backupPreferences(
                                                AdminProtos.AdminMessage.BackupLocation.FLASH))),
                        commandButton(I18n.t("remoteAdmin.command.backupSd"), "/icons/save-config.svg",
                                () -> runCommand(I18n.t("remoteAdmin.command.backupSd"),
                                        () -> remoteAdminBackend.backupPreferences(
                                                AdminProtos.AdminMessage.BackupLocation.SD))),
                        commandButton(I18n.t("remoteAdmin.command.restoreFlash"), "/icons/load-config.svg",
                                () -> confirmAndRun(I18n.t("remoteAdmin.command.restoreFlash"),
                                        () -> remoteAdminBackend.restorePreferences(
                                                AdminProtos.AdminMessage.BackupLocation.FLASH), true)),
                        commandButton(I18n.t("remoteAdmin.command.restoreSd"), "/icons/load-config.svg",
                                () -> confirmAndRun(I18n.t("remoteAdmin.command.restoreSd"),
                                        () -> remoteAdminBackend.restorePreferences(
                                                AdminProtos.AdminMessage.BackupLocation.SD), true)),
                        commandButton(I18n.t("remoteAdmin.command.removeBackupFlash"), "/icons/clear.svg",
                                () -> confirmAndRun(I18n.t("remoteAdmin.command.removeBackupFlash"),
                                        () -> remoteAdminBackend.removeBackupPreferences(
                                                AdminProtos.AdminMessage.BackupLocation.FLASH), true)),
                        commandButton(I18n.t("remoteAdmin.command.removeBackupSd"), "/icons/clear.svg",
                                () -> confirmAndRun(I18n.t("remoteAdmin.command.removeBackupSd"),
                                        () -> remoteAdminBackend.removeBackupPreferences(
                                                AdminProtos.AdminMessage.BackupLocation.SD), true))),
                commandGroup(I18n.t("remoteAdmin.section.danger"),
                        commandButton(I18n.t("remoteAdmin.command.resetNodeDb"), "/icons/database.svg",
                                () -> confirmAndRun(I18n.t("remoteAdmin.command.resetNodeDb"),
                                        () -> remoteAdminBackend.resetNodeDb(true), true)),
                        commandButton(I18n.t("remoteAdmin.command.factoryResetConfig"), "/icons/toast_warning.svg",
                                () -> confirmAndRun(I18n.t("remoteAdmin.command.factoryResetConfig"),
                                        remoteAdminBackend::factoryResetConfig, true)),
                        commandButton(I18n.t("remoteAdmin.command.factoryResetDevice"), "/icons/toast_error.svg",
                                () -> confirmAndRun(I18n.t("remoteAdmin.command.factoryResetDevice"),
                                        remoteAdminBackend::factoryResetDevice, true)),
                        commandButton(I18n.t("remoteAdmin.command.enterDfuMode"), "/icons/ide-bug.svg",
                                () -> confirmAndRun(I18n.t("remoteAdmin.command.enterDfuMode"),
                                        remoteAdminBackend::enterDfuMode, true))));
        content.setPadding(new Insets(10, 0, 0, 0));

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return scrollPane;
    }

    private static Tab contentTab(String title, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    /**
     * Opens the remote admin modal window for a node.
     *
     * @param localState state of the locally connected node
     * @param node remote node selected by the user
     * @param handler protocol handler used for radio traffic
     */
    public static void showForNode(DeviceState localState, NodeData node, ProtocolHandler handler) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showForNode(localState, node, handler));
            return;
        }
        if (localState == null || node == null || handler == null) {
            return;
        }
        ModalPane modal = ModalPane.getInstance();
        if (modal == null) {
            return;
        }
        RemoteAdminPanel panel = new RemoteAdminPanel(localState, node, handler);
        panel.modalPane = modal;
        modal.show(panel, false, false);
        modal.setOnHidden(panel::close);
    }

    public static void showForRemoteNode(RemoteRpcState rpcState, NodeData node) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showForRemoteNode(rpcState, node));
            return;
        }
        if (rpcState == null || rpcState.client() == null || !rpcState.client().isOpen() || node == null) {
            return;
        }
        ModalPane modal = ModalPane.getInstance();
        if (modal == null) {
            return;
        }
        RemoteAdminPanel panel = new RemoteAdminPanel(node, new RpcRemoteAdminBackend(rpcState, node));
        panel.modalPane = modal;
        modal.show(panel, false, false);
        modal.setOnHidden(panel::close);
    }

    /**
     * Releases the remote admin backend owned by this panel.
     */
    @Override
    public void close() {
        remoteAdminBackend.close();
    }

    private void closeAndHide() {
        ModalPane pane = modalPane != null ? modalPane : ModalPane.getInstance();
        if (pane != null) {
            pane.hide();
        } else {
            close();
        }
    }

    private boolean confirmAdminKeyConfigured() {
        Window owner = getScene() != null ? getScene().getWindow() : null;
        return confirmAdminKeyConfigured(targetDisplayName, owner);
    }

    private static boolean confirmAdminKeyConfigured(String targetDisplayName, Window owner) {
        ButtonType cancelButton = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType loadButton = new ButtonType(I18n.t("remoteAdmin.action.confirmAccess"),
                ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.t("remoteAdmin.access.title"));
        alert.setHeaderText(I18n.t("remoteAdmin.access.header"));
        alert.setContentText(I18n.t("remoteAdmin.access.message", targetDisplayName));
        alert.getButtonTypes().setAll(cancelButton, loadButton);
        if (owner != null) {
            alert.initOwner(owner);
        }
        return alert.showAndWait().orElse(cancelButton) == loadButton;
    }

    private boolean ensureAdminKeyConfirmed(Label targetStatusLabel) {
        if (adminKeyConfirmed) {
            return true;
        }
        if (confirmAdminKeyConfigured()) {
            adminKeyConfirmed = true;
            return true;
        }
        targetStatusLabel.setText(I18n.t("remoteAdmin.status.confirmCancelled"));
        return false;
    }

    private void showSectionCatalog() {
        loadedSession = remoteAdminBackend.session();
        rebuildTree(loadedSession, false);
        statusLabel.setText(I18n.t("remoteAdmin.status.sectionsReady"));
        queryStatusLabel.setText(I18n.t("remoteAdmin.status.noSectionsRequested"));
        updateCommandState(loadedSession);
        finishProgress(true);
        setBusy(false);
    }

    private void rebuildTree(RemoteAdminSession session) {
        rebuildTree(session, true);
    }

    private void rebuildTree(RemoteAdminSession session, boolean updateStatus) {
        DeviceState remoteState = session.remoteState();
        synchronized (remoteState.getConfigs()) {
            originalConfigs = new ArrayList<>(remoteState.getConfigs());
        }
        synchronized (remoteState.getModuleConfigs()) {
            originalModuleConfigs = new ArrayList<>(remoteState.getModuleConfigs());
        }
        synchronized (remoteState.getChannels()) {
            originalChannels = new ArrayList<>(remoteState.getChannels());
        }
        workingChannels = new ArrayList<>(originalChannels);
        fullConfigRoot = MeshtasticConfigTreeBuilder.build(
                remoteState,
                session.targetNode(),
                originalConfigs,
                originalModuleConfigs);
        addMissingSectionPlaceholders();
        configTree.setRoot(fullConfigRoot);
        searchField.clear();

        if (!updateStatus) {
            return;
        }
        int loadedSections = originalConfigs.size() + originalModuleConfigs.size();
        int totalFields = ConfigTreeItemSupport.countEditableFields(fullConfigRoot);
        RemoteAdminSession.QuerySummary summary = session.querySummary();
        statusLabel.setText(I18n.t("remoteAdmin.status.loadedDetailed",
                summary.received(),
                summary.total(),
                summary.failed(),
                loadedSections,
                totalFields));
        updateQueryStatusLabel(session);
    }

    private void saveChanges() {
        if (loadedSession == null || fullConfigRoot == null) {
            statusLabel.setText(I18n.t("remoteAdmin.status.loadFirst"));
            return;
        }
        ConfigChangeSet changes = ConfigChangeCollector.collect(
                fullConfigRoot,
                originalConfigs,
                originalModuleConfigs,
                collectModifiedChannels(),
                loadedSession.remoteState().getOwnerInfo(),
                loadedSession.targetNode());
        if (!changes.hasChanges()) {
            statusLabel.setText(I18n.t("settings.config.status.noChanges"));
            return;
        }
        Optional<String> compatibilityError =
            ConfigCompatibilityValidator.validate(
                loadedSession.remoteState(),
                changes,
                originalConfigs
            );
        if (compatibilityError.isPresent()) {
            statusLabel.setText(compatibilityError.get());
            return;
        }
        if (!ensureAdminKeyConfirmed(statusLabel)) {
            return;
        }

        setBusy(true);
        statusLabel.setText(I18n.t("remoteAdmin.status.sending"));
        CompletableFuture<Void> saveFuture = CompletableFuture.completedFuture(null);
        if (changes.ownerModified()) {
            saveFuture = saveFuture.thenCompose(ignored -> remoteAdminBackend.saveOwner(
                    changes.longName(),
                    changes.shortName(),
                    changes.isLicensed()));
        }
        if (changes.positionModified()) {
            if (changes.latitude() == 0 && changes.longitude() == 0 && changes.altitude() == 0) {
                saveFuture = saveFuture.thenCompose(ignored -> remoteAdminBackend.removeFixedPosition());
            } else {
                saveFuture = saveFuture.thenCompose(ignored -> remoteAdminBackend.setFixedPosition(
                        changes.latitude(),
                        changes.longitude(),
                        changes.altitude()));
            }
        }
        if (changes.ringtoneModified()) {
            saveFuture = saveFuture.thenCompose(ignored -> remoteAdminBackend.setRingtone(changes.ringtone()));
        }
        if (changes.hasPacketConfigChanges()) {
            saveFuture = saveFuture.thenCompose(ignored -> remoteAdminBackend.saveConfigChanges(
                    changes.configs(),
                    changes.moduleConfigs(),
                    changes.channels()));
        }

        saveFuture.whenComplete((ignored, error) -> Platform.runLater(() -> {
            setBusy(false);
            if (error != null) {
                statusLabel.setText(I18n.t("settings.config.status.saveError", errorDetail(error)));
                return;
            }
            ConfigTreeItemSupport.resetModifiedFlags(fullConfigRoot);
            originalConfigs = mergeSavedConfigs(originalConfigs, changes.configs());
            originalModuleConfigs = mergeSavedModuleConfigs(originalModuleConfigs, changes.moduleConfigs());
            originalChannels = ConfigSnapshotEditor.workingChannelsSnapshot(originalChannels, workingChannels);
            statusLabel.setText(I18n.t("remoteAdmin.status.sent", changes.totalChanges()));
            configTree.refresh();
        }));
    }

    private List<ChannelProtos.Channel> collectModifiedChannels() {
        return ConfigSnapshotEditor.collectModifiedChannels(originalChannels, workingChannels);
    }

    private void addMissingSectionPlaceholders() {
        TreeItem<ConfigTreeItem> configRoot = ensureTopLevelSection(
                CONFIG_ROOT_TYPE,
                I18n.t("settings.config.section.root.device"));
        TreeItem<ConfigTreeItem> moduleRoot = ensureTopLevelSection(
                MODULE_CONFIG_ROOT_TYPE,
                I18n.t("settings.config.section.root.module"));

        Set<Integer> loadedConfigVariants = loadedConfigVariants();
        for (AdminProtos.AdminMessage.ConfigType type : RemoteAdminService.editableConfigTypes()) {
            int variantNumber = RemoteAdminService.configVariantNumber(type);
            if (loadedConfigVariants.contains(variantNumber)) {
                continue;
            }
            ConfigProtobufSupport.configFieldByVariantNumber(variantNumber)
                    .ifPresent(field -> configRoot.getChildren().add(missingConfigSection(type, field)));
        }

        Set<Integer> loadedModuleVariants = loadedModuleVariants();
        for (AdminProtos.AdminMessage.ModuleConfigType type :
                RemoteAdminService.editableModuleConfigTypes(
                    loadedSession.remoteState().getFirmwareCapabilities()
                )) {
            int variantNumber = RemoteAdminService.moduleConfigVariantNumber(type);
            if (loadedModuleVariants.contains(variantNumber)) {
                continue;
            }
            ConfigProtobufSupport.moduleConfigFieldByVariantNumber(variantNumber)
                    .ifPresent(field -> moduleRoot.getChildren().add(missingModuleSection(type, field)));
        }
    }

    private TreeItem<ConfigTreeItem> ensureTopLevelSection(String configType, String displayName) {
        return ConfigTreeItemSupport.findTopLevelSection(fullConfigRoot, configType)
                .orElseGet(() -> {
                    TreeItem<ConfigTreeItem> root = new TreeItem<>(
                            new ConfigTreeItem(displayName, configType, 0));
                    root.setExpanded(true);
                    fullConfigRoot.getChildren().add(root);
                    return root;
                });
    }

    private Set<Integer> loadedConfigVariants() {
        Set<Integer> variants = new HashSet<>();
        for (ConfigProtos.Config config : originalConfigs) {
            variants.add(ConfigProtobufSupport.activeOneofFieldNumber(config));
        }
        return variants;
    }

    private Set<Integer> loadedModuleVariants() {
        Set<Integer> variants = new HashSet<>();
        for (ModuleConfigProtos.ModuleConfig moduleConfig : originalModuleConfigs) {
            variants.add(ConfigProtobufSupport.activeModuleOneofFieldNumber(moduleConfig));
        }
        return variants;
    }

    private TreeItem<ConfigTreeItem> missingConfigSection(AdminProtos.AdminMessage.ConfigType type,
                                                          FieldDescriptor field) {
        String displayName = ProtobufTreeBuilder.sectionDisplayName(field.getName());
        return missingSection(
                displayName,
                field,
                CONFIG_ROOT_TYPE,
                field.getNumber(),
                () -> requestMissingConfigSection(type, displayName));
    }

    private TreeItem<ConfigTreeItem> missingModuleSection(AdminProtos.AdminMessage.ModuleConfigType type,
                                                          FieldDescriptor field) {
        String displayName = ProtobufTreeBuilder.sectionDisplayName(field.getName());
        return missingSection(
                displayName,
                field,
                MODULE_CONFIG_ROOT_TYPE,
                field.getNumber(),
                () -> requestMissingModuleSection(type, displayName));
    }

    private static TreeItem<ConfigTreeItem> missingSection(String displayName,
                                                           FieldDescriptor field,
                                                           String configType,
                                                           int variantNumber,
                                                           Runnable requestAction) {
        TreeItem<ConfigTreeItem> section = new TreeItem<>(
                new ConfigTreeItem(displayName, field.getName(), field, configType, variantNumber));
        section.setExpanded(true);
        section.getChildren().add(new TreeItem<>(new ConfigTreeItem(
                I18n.t("remoteAdmin.status.sectionMissing"),
                I18n.t("remoteAdmin.action.requestSection"),
                requestAction,
                configType,
                variantNumber)));
        return section;
    }

    private void requestMissingConfigSection(AdminProtos.AdminMessage.ConfigType type, String displayName) {
        requestMissingSection(displayName, () -> remoteAdminBackend.requestConfigSection(type));
    }

    private void requestMissingModuleSection(AdminProtos.AdminMessage.ModuleConfigType type, String displayName) {
        requestMissingSection(displayName, () -> remoteAdminBackend.requestModuleConfigSection(type));
    }

    private void requestMissingSection(String displayName, Supplier<CompletableFuture<Void>> request) {
        if (loadedSession == null) {
            statusLabel.setText(I18n.t("remoteAdmin.status.loadFirst"));
            return;
        }
        if (!ensureAdminKeyConfirmed(statusLabel)) {
            return;
        }
        setBusy(true);
        startProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        statusLabel.setText(I18n.t("remoteAdmin.status.sectionRequesting", displayName));
        request.get().whenComplete((ignored, error) -> Platform.runLater(() -> {
            finishProgress(error == null);
            setBusy(false);
            if (error != null) {
                statusLabel.setText(I18n.t(
                        "remoteAdmin.status.sectionError",
                        displayName,
                        errorDetail(error)));
                updateQueryStatusLabel(loadedSession);
                return;
            }
            rebuildTree(loadedSession);
            statusLabel.setText(I18n.t("remoteAdmin.status.sectionLoaded", displayName));
            configTree.refresh();
        }));
    }

    private static List<ConfigProtos.Config> mergeSavedConfigs(List<ConfigProtos.Config> originals,
                                                               List<ConfigProtos.Config> saved) {
        List<ConfigProtos.Config> merged = new ArrayList<>(originals);
        for (ConfigProtos.Config config : saved) {
            int variant = ConfigProtobufSupport.activeOneofFieldNumber(config);
            boolean replaced = false;
            for (int i = 0; i < merged.size(); i++) {
                if (ConfigProtobufSupport.activeOneofFieldNumber(merged.get(i)) == variant) {
                    merged.set(i, config);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                merged.add(config);
            }
        }
        return merged;
    }

    private static List<ModuleConfigProtos.ModuleConfig> mergeSavedModuleConfigs(
            List<ModuleConfigProtos.ModuleConfig> originals,
            List<ModuleConfigProtos.ModuleConfig> saved) {
        List<ModuleConfigProtos.ModuleConfig> merged = new ArrayList<>(originals);
        for (ModuleConfigProtos.ModuleConfig moduleConfig : saved) {
            int variant = ConfigProtobufSupport.activeModuleOneofFieldNumber(moduleConfig);
            boolean replaced = false;
            for (int i = 0; i < merged.size(); i++) {
                if (ConfigProtobufSupport.activeModuleOneofFieldNumber(merged.get(i)) == variant) {
                    merged.set(i, moduleConfig);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                merged.add(moduleConfig);
            }
        }
        return merged;
    }

    private void filterConfigTree(String query) {
        ConfigTreeItemSupport.filter(fullConfigRoot, query).ifPresent(configTree::setRoot);
    }

    private void syncRepeatedEditorSlots(ConfigTreeItem editedItem) {
        if (fullConfigRoot != null
                && loadedSession != null
                && ProtobufTreeBuilder.adjustLoRaPresetAfterRegionEdit(
                    fullConfigRoot,
                    editedItem,
                    loadedSession.remoteState().getFirmwareCapabilities(),
                    loadedSession.remoteState().getRegionPresetMap()
                )) {
            configTree.refresh();
            return;
        }
        if (editedItem == null
                || editedItem.getFieldDescriptor() == null
                || !editedItem.getFieldDescriptor().isRepeated()
                || fullConfigRoot == null) {
            return;
        }
        ConfigTreeItemSupport.findTreeItemByValue(fullConfigRoot, editedItem)
                .filter(item -> item.getParent() != null)
                .ifPresent(item -> {
                    ProtobufTreeBuilder.adjustRepeatedGroupAfterEdit(item.getParent());
                    configTree.refresh();
                });
    }

    private void setBusy(boolean busy) {
        refreshButton.setDisable(busy);
        saveButton.setDisable(busy || fullConfigRoot == null);
        configTree.setDisable(busy);
        boolean commandDisabled = busy || loadedSession == null;
        for (Button button : commandButtons) {
            button.setDisable(commandDisabled);
        }
    }

    private static Button toolbarButton(String tooltip, String iconPath, Runnable action) {
        SVGPath icon = SvgIconLoader.load(iconPath, 18);
        Button button = new Button();
        button.getStyleClass().add("config-toolbar-button");
        button.setMinSize(34, 34);
        button.setPrefSize(34, 34);
        button.setMaxSize(34, 34);
        if (icon != null) {
            button.setGraphic(icon);
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            button.setText(tooltip);
        }
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(e -> action.run());
        return button;
    }

    private Button commandButton(String text, String iconPath, Runnable action) {
        Button button = new Button();
        button.getStyleClass().addAll("config-toolbar-button", "remote-admin-command-button");
        button.setMinHeight(34);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMnemonicParsing(false);
        configureCommandButton(button, text, iconPath);
        button.setOnAction(e -> action.run());
        commandButtons.add(button);
        return button;
    }

    private Button delayedCommandButton(String actionText,
                                        String actionIconPath,
                                        Supplier<CompletableFuture<Void>> action,
                                        String cancelText,
                                        String cancelIconPath,
                                        Supplier<CompletableFuture<Void>> cancelAction) {
        Button button = commandButton(actionText, actionIconPath, () -> { });
        final boolean[] cancelMode = {false};
        final long[] commandSequence = {0};
        button.setOnAction(e -> {
            if (!cancelMode[0]) {
                if (!confirmCommand(actionText, false)) {
                    return;
                }
                long sequence = ++commandSequence[0];
                cancelMode[0] = true;
                configureCommandButton(button, cancelText, cancelIconPath);
                runDelayedCommand(actionText, action, error -> {
                    if (error != null && cancelMode[0]) {
                        cancelMode[0] = false;
                        configureCommandButton(button, actionText, actionIconPath);
                    }
                }, () -> commandSequence[0] == sequence);
                return;
            }

            long sequence = ++commandSequence[0];
            cancelMode[0] = false;
            configureCommandButton(button, actionText, actionIconPath);
            runDelayedCommand(cancelText, cancelAction, error -> {
                if (error != null && !cancelMode[0]) {
                    cancelMode[0] = true;
                    configureCommandButton(button, cancelText, cancelIconPath);
                }
            }, () -> commandSequence[0] == sequence);
        });
        return button;
    }

    private static void configureCommandButton(Button button, String text, String iconPath) {
        SVGPath icon = SvgIconLoader.load(iconPath, 16);
        button.setText(text);
        if (icon != null) {
            button.setGraphic(icon);
            button.setContentDisplay(ContentDisplay.LEFT);
            button.setGraphicTextGap(8);
        } else {
            button.setGraphic(null);
            button.setContentDisplay(ContentDisplay.TEXT_ONLY);
        }
        button.setTooltip(new Tooltip(text));
    }

    private static VBox commandGroup(String title, Button... buttons) {
        Label titleLabel = new Label(title);
        ToolBar toolbar = new ToolBar(buttons);
        toolbar.getStyleClass().add("config-toolbar");
        toolbar.setPrefWidth(WINDOW_WIDTH - 40);
        return new VBox(8, titleLabel, toolbar, new Separator());
    }

    private void refreshConnectionStatus() {
        if (loadedSession == null) {
            commandStatusLabel.setText(I18n.t("remoteAdmin.status.commandsLoadFirst"));
            return;
        }
        if (!ensureAdminKeyConfirmed(commandStatusLabel)) {
            return;
        }
        setBusy(true);
        commandStatusLabel.setText(I18n.t(
                "remoteAdmin.status.commandSending",
                I18n.t("remoteAdmin.command.refreshStatus")));
        remoteAdminBackend.refreshConnectionStatus().whenComplete((adminMessage, error) ->
                Platform.runLater(() -> {
                    setBusy(false);
                    if (error != null) {
                        commandStatusLabel.setText(I18n.t(
                                "remoteAdmin.status.commandError",
                                I18n.t("remoteAdmin.command.refreshStatus"),
                                errorDetail(error)));
                        return;
                    }
                    updateCommandState(loadedSession);
                    updateQueryStatusLabel(loadedSession);
                }));
    }

    private void runCommand(String commandName, Supplier<CompletableFuture<Void>> action) {
        if (loadedSession == null) {
            commandStatusLabel.setText(I18n.t("remoteAdmin.status.commandsLoadFirst"));
            return;
        }
        if (!ensureAdminKeyConfirmed(commandStatusLabel)) {
            return;
        }
        setBusy(true);
        commandStatusLabel.setText(I18n.t("remoteAdmin.status.commandSending", commandName));
        action.get().whenComplete((ignored, error) -> Platform.runLater(() -> {
            setBusy(false);
            if (error != null) {
                commandStatusLabel.setText(I18n.t(
                        "remoteAdmin.status.commandError",
                        commandName,
                        errorDetail(error)));
                return;
            }
            commandStatusLabel.setText(I18n.t("remoteAdmin.status.commandSent", commandName));
        }));
    }

    private void runDelayedCommand(String commandName,
                                   Supplier<CompletableFuture<Void>> action,
                                   Consumer<Throwable> onComplete,
                                   BooleanSupplier statusOwner) {
        if (loadedSession == null) {
            if (statusOwner.getAsBoolean()) {
                commandStatusLabel.setText(I18n.t("remoteAdmin.status.commandsLoadFirst"));
            }
            onComplete.accept(new IllegalStateException(I18n.t("remoteAdmin.status.commandsLoadFirst")));
            return;
        }
        if (!ensureAdminKeyConfirmed(commandStatusLabel)) {
            onComplete.accept(new IllegalStateException(I18n.t("remoteAdmin.status.confirmCancelled")));
            return;
        }

        if (statusOwner.getAsBoolean()) {
            commandStatusLabel.setText(I18n.t("remoteAdmin.status.commandSending", commandName));
        }
        CompletableFuture<Void> commandFuture;
        try {
            commandFuture = action.get();
        } catch (RuntimeException error) {
            if (statusOwner.getAsBoolean()) {
                commandStatusLabel.setText(I18n.t(
                        "remoteAdmin.status.commandError",
                        commandName,
                        errorDetail(error)));
            }
            onComplete.accept(error);
            return;
        }

        commandFuture.whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (statusOwner.getAsBoolean()) {
                if (error != null) {
                    commandStatusLabel.setText(I18n.t(
                            "remoteAdmin.status.commandError",
                            commandName,
                            errorDetail(error)));
                } else {
                    commandStatusLabel.setText(I18n.t("remoteAdmin.status.commandSent", commandName));
                }
            }
            onComplete.accept(error);
        }));
    }

    private void confirmAndRun(String commandName,
                               Supplier<CompletableFuture<Void>> action,
                               boolean dangerous) {
        if (!confirmCommand(commandName, dangerous)) {
            return;
        }
        runCommand(commandName, action);
    }

    private boolean confirmCommand(String commandName, boolean dangerous) {
        ButtonType cancelButton = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType sendButton = new ButtonType(I18n.t("remoteAdmin.action.sendCommand"),
                ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.t("remoteAdmin.confirm.title"));
        alert.setHeaderText(I18n.t("remoteAdmin.confirm.header", commandName));
        alert.setContentText(I18n.t(
                dangerous ? "remoteAdmin.confirm.dangerMessage" : "remoteAdmin.confirm.message",
                commandName,
                targetDisplayName));
        alert.getButtonTypes().setAll(cancelButton, sendButton);
        initAlertOwner(alert);
        return alert.showAndWait().orElse(cancelButton) == sendButton;
    }

    private void initAlertOwner(Alert alert) {
        if (alert == null) {
            return;
        }
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
    }

    private void updateCommandState(RemoteAdminSession session) {
        commandStatusLabel.setText(I18n.t(
                "remoteAdmin.status.connectionStatus",
                describeConnectionStatus(session.getConnectionStatus())));
    }

    private void startProgress(double progress) {
        loadProgressBar.setProgress(progress);
        loadProgressBar.setVisible(true);
        loadProgressBar.setManaged(true);
    }

    private void finishProgress(boolean completed) {
        loadProgressBar.setProgress(completed ? 1 : 0);
        loadProgressBar.setVisible(false);
        loadProgressBar.setManaged(false);
    }

    private void updateQueryStatusLabel(RemoteAdminSession session) {
        List<String> failed = session.queryStatuses().stream()
                .filter(status -> status.state() != RemoteAdminSession.QueryState.RECEIVED)
                .map(RemoteAdminSession.QueryStatus::key)
                .map(RemoteAdminPanel::displayQueryKey)
                .limit(10)
                .toList();
        if (failed.isEmpty()) {
            queryStatusLabel.setText(I18n.t("remoteAdmin.status.noMissingSections"));
            return;
        }
        queryStatusLabel.setText(I18n.t("remoteAdmin.status.missingSections", String.join(", ", failed)));
    }

    private static String displayQueryKey(String key) {
        if (key == null) {
            return "";
        }
        String configPrefix = "get_config/";
        if (key.startsWith(configPrefix)) {
            return configDisplayName(key.substring(configPrefix.length()));
        }
        String modulePrefix = "get_module_config/";
        if (key.startsWith(modulePrefix)) {
            return moduleConfigDisplayName(key.substring(modulePrefix.length()));
        }
        return key.replace("get_config/", "config ")
                .replace("get_module_config/", "module ")
                .replace("get_channel/", "channel ")
                .replace('_', ' ')
                .toLowerCase(Locale.ROOT);
    }

    private static String configDisplayName(String enumName) {
        try {
            AdminProtos.AdminMessage.ConfigType type =
                    AdminProtos.AdminMessage.ConfigType.valueOf(enumName);
            return ConfigProtobufSupport.configFieldByVariantNumber(
                            RemoteAdminService.configVariantNumber(type))
                    .map(FieldDescriptor::getName)
                    .map(ProtobufTreeBuilder::sectionDisplayName)
                    .orElse(enumName);
        } catch (IllegalArgumentException e) {
            return enumName;
        }
    }

    private static String moduleConfigDisplayName(String enumName) {
        try {
            AdminProtos.AdminMessage.ModuleConfigType type =
                    AdminProtos.AdminMessage.ModuleConfigType.valueOf(enumName);
            return ConfigProtobufSupport.moduleConfigFieldByVariantNumber(
                            RemoteAdminService.moduleConfigVariantNumber(type))
                    .map(FieldDescriptor::getName)
                    .map(ProtobufTreeBuilder::sectionDisplayName)
                    .orElse(enumName);
        } catch (IllegalArgumentException e) {
            return enumName;
        }
    }

    private static String describeConnectionStatus(ConnStatusProtos.DeviceConnectionStatus status) {
        if (status == null) {
            return I18n.t("remoteAdmin.connection.unknown");
        }
        List<String> parts = new ArrayList<>();
        if (status.hasWifi()) {
            ConnStatusProtos.WifiConnectionStatus wifi = status.getWifi();
            String ssid = wifi.getSsid().isBlank() ? "" : " " + wifi.getSsid();
            parts.add("Wi-Fi " + connectedText(wifi.getStatus().getIsConnected()) + ssid);
        }
        if (status.hasEthernet()) {
            parts.add("Ethernet " + connectedText(status.getEthernet().getStatus().getIsConnected()));
        }
        if (status.hasBluetooth()) {
            parts.add("Bluetooth " + connectedText(status.getBluetooth().getIsConnected()));
        }
        if (status.hasSerial()) {
            parts.add("Serial " + connectedText(status.getSerial().getIsConnected()));
        }
        return parts.isEmpty() ? I18n.t("remoteAdmin.connection.noData") : String.join("; ", parts);
    }

    private static String connectedText(boolean connected) {
        return connected
                ? I18n.t("remoteAdmin.connection.connected")
                : I18n.t("remoteAdmin.connection.disconnected");
    }

    private static String resolveDisplayName(NodeData node) {
        String raw = node.getLongName() != null && !node.getLongName().isBlank()
                ? node.getLongName()
                : node.getNodeId() != null ? node.getNodeId() : "?";
        return UnicodeTextUtils.sanitizeForJavaFxDisplay(raw);
    }

    private static String errorDetail(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }
}
