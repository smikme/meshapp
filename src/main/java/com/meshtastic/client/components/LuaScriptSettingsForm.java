package com.meshtastic.client.components;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.ConnectionManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Side form for editing MeshApp IDE Lua script settings.
 * <p>
 * The form edits only script metadata: name, autostart, bot type, automation
 * name, and node binding for on-air bots. Source code stays in the separate IDE window.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaScriptSettingsForm extends VBox {

    private static final double NODE_COMBO_HEIGHT = 34.0;
    private static final int NODE_COMBO_VISIBLE_ROWS = 6;

    private final LuaScript script;
    private final TextField iconField = new TextField();
    private final TextField nameField = new TextField();
    private final TextField authorField = new TextField();
    private final Label guidLabel = new Label("GUID");
    private final TextField guidField = new TextField();
    private final TextField versionField = new TextField();
    private final TextArea descriptionArea = new TextArea();
    private final CheckBox autostartCheck = new CheckBox(I18n.t("meshIde.settings.autostart"));
    private final Label nodeLabel = new Label(I18n.t("meshIde.settings.node"));
    private final ComboBox<NodeChoice> nodeCombo = new ComboBox<>();
    private final ComboBox<LuaScript.BotType> botTypeCombo = new ComboBox<>();
    private final Label automationNameLabel = new Label(I18n.t("meshIde.settings.automationName"));
    private final TextField automationNameField = new TextField();
    private final Label statusLabel = new Label();
    private final ConnectionManager connectionManager = ConnectionManager.getInstance();
    private final Runnable connectionListener = () -> Platform.runLater(this::refreshNodeChoices);

    private Consumer<Draft> onSave;
    private boolean disposed;
    private boolean updatingNodeChoices;
    private boolean userSelectedNode;

    public LuaScriptSettingsForm(LuaScript script) {
        this.script = script;
        configureLayout();
        connectionManager.addListener(connectionListener);
        populateFields();
    }

    public void setOnSave(Consumer<Draft> onSave) {
        this.onSave = onSave;
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        connectionManager.removeListener(connectionListener);
    }

    private void configureLayout() {
        setSpacing(10);
        setPadding(new Insets(20, 30, 20, 30));
        setPrefWidth(390);
        setMaxWidth(390);
        setMaxHeight(Double.MAX_VALUE);
        getStyleClass().add("modal-side-panel");

        Label title = new Label(I18n.t(isNewScript()
                ? "meshIde.settings.title.new"
                : "meshIde.settings.title.edit"));
        title.getStyleClass().add("dialog-title");

        iconField.setPromptText(LuaScript.DEFAULT_ICON);
        iconField.setMaxWidth(Double.MAX_VALUE);
        iconField.setTextFormatter(new TextFormatter<>(this::filterIconChange));

        nameField.setPromptText(I18n.t("meshIde.settings.namePrompt"));
        nameField.setMaxWidth(Double.MAX_VALUE);

        authorField.setPromptText(I18n.t("meshIde.settings.authorPrompt"));
        authorField.setMaxWidth(Double.MAX_VALUE);

        guidField.setEditable(false);
        guidField.setFocusTraversable(true);
        guidField.setMaxWidth(Double.MAX_VALUE);

        versionField.setEditable(false);
        versionField.setFocusTraversable(true);
        versionField.setMaxWidth(Double.MAX_VALUE);

        descriptionArea.setPromptText(I18n.t("meshIde.settings.descriptionPrompt"));
        descriptionArea.setWrapText(true);
        descriptionArea.setPrefRowCount(7);
        descriptionArea.setMinHeight(120);
        descriptionArea.setMaxWidth(Double.MAX_VALUE);

        nodeCombo.setMaxWidth(Double.MAX_VALUE);
        nodeCombo.setMinHeight(NODE_COMBO_HEIGHT);
        nodeCombo.setPrefHeight(NODE_COMBO_HEIGHT);
        nodeCombo.setMaxHeight(NODE_COMBO_HEIGHT);
        nodeCombo.setVisibleRowCount(NODE_COMBO_VISIBLE_ROWS);
        nodeCombo.setPromptText(I18n.t("meshIde.settings.nodePrompt"));
        nodeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(NodeChoice choice) {
                return choice != null ? choice.displayText() : "";
            }

            @Override
            public NodeChoice fromString(String value) {
                return null;
            }
        });
        nodeCombo.setCellFactory(listView -> new NodeChoiceCell());
        nodeCombo.setButtonCell(new NodeChoiceCell());
        nodeCombo.valueProperty().addListener((obs, oldChoice, newChoice) -> {
            if (!updatingNodeChoices) {
                userSelectedNode = true;
            }
        });

        botTypeCombo.getItems().setAll(LuaScript.BotType.values());
        botTypeCombo.setMaxWidth(Double.MAX_VALUE);
        botTypeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(LuaScript.BotType type) {
                return type != null ? type.getDisplayName() : "";
            }

            @Override
            public LuaScript.BotType fromString(String value) {
                return LuaScript.BotType.fromStorage(value);
            }
        });
        botTypeCombo.valueProperty().addListener((obs, oldType, newType) -> updateAutomationVisibility());

        automationNameField.setPromptText(I18n.t("meshIde.settings.automationNamePrompt"));
        automationNameField.setMaxWidth(Double.MAX_VALUE);

        statusLabel.getStyleClass().add("muted-small-label");
        statusLabel.setWrapText(true);

        Button cancelButton = new Button(I18n.t("common.cancel"));
        cancelButton.setOnAction(event -> closeModal());

        Button saveButton = new Button(I18n.t("common.save"));
        saveButton.getStyleClass().add("accent");
        saveButton.setOnAction(event -> save());

        HBox actions = new HBox(10, cancelButton, saveButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(10, 0, 0, 0));

        getChildren().addAll(
                title,
                new Separator(),
                new Label(I18n.t("meshIde.column.icon")),
                iconField,
                new Label(I18n.t("meshIde.column.scriptName")),
                nameField,
                new Label(I18n.t("meshIde.column.author")),
                authorField,
                guidLabel,
                guidField,
                new Label(I18n.t("meshIde.column.version")),
                versionField,
                new Label(I18n.t("meshIde.column.description")),
                descriptionArea,
                autostartCheck,
                nodeLabel,
                nodeCombo,
                new Label(I18n.t("meshIde.column.type")),
                botTypeCombo,
                automationNameLabel,
                automationNameField,
                statusLabel,
                actions
        );
    }

    private void populateFields() {
        iconField.setText(script.getIcon());
        nameField.setText(script.getName() != null ? script.getName() : "");
        authorField.setText(script.getAuthor());
        String guid = script.getGuid();
        boolean hasGuid = guid != null && !guid.isBlank();
        guidField.setText(hasGuid ? guid : "");
        guidLabel.setVisible(hasGuid);
        guidLabel.setManaged(hasGuid);
        guidField.setVisible(hasGuid);
        guidField.setManaged(hasGuid);
        versionField.setText(String.valueOf(script.getVersion()));
        descriptionArea.setText(script.getDescription());
        autostartCheck.setSelected(script.isAutostart());

        refreshNodeChoices();

        botTypeCombo.getSelectionModel().select(script.getBotType());
        automationNameField.setText(script.getAutomationName() != null ? script.getAutomationName() : "");
        updateAutomationVisibility();
        Platform.runLater(nameField::requestFocus);
    }

    private void updateAutomationVisibility() {
        LuaScript.BotType type = botTypeCombo.getValue() != null
                ? botTypeCombo.getValue()
                : LuaScript.BotType.AIR_BOT;
        boolean automation = type.requiresAutomationName();
        boolean nodeBound = type.requiresNodeBinding();
        autostartCheck.setText(I18n.t(type == LuaScript.BotType.EXTENSION
                ? "meshIde.settings.extensionEnabled"
                : "meshIde.settings.autostart"));
        automationNameLabel.setVisible(automation);
        automationNameLabel.setManaged(automation);
        automationNameField.setVisible(automation);
        automationNameField.setManaged(automation);
        nodeLabel.setVisible(nodeBound);
        nodeLabel.setManaged(nodeBound);
        nodeCombo.setVisible(nodeBound);
        nodeCombo.setManaged(nodeBound);
    }

    private void save() {
        statusLabel.setText("");
        Draft draft = buildDraft();
        if (draft == null) {
            return;
        }
        if (onSave != null) {
            onSave.accept(draft);
        }
    }

    private void refreshNodeChoices() {
        if (disposed) {
            return;
        }
        String preferredNodeId = preferredNodeId(selectedNodeId());
        List<NodeChoice> choices = loadNodeChoices(preferredNodeId);

        updatingNodeChoices = true;
        try {
            nodeCombo.getItems().setAll(choices);
            selectNodeChoice(choices, preferredNodeId);
        } finally {
            updatingNodeChoices = false;
        }
    }

    private String preferredNodeId(String selectedNodeId) {
        if (userSelectedNode) {
            String selected = normalizeNodeId(selectedNodeId);
            if (!selected.isBlank()) {
                return selected;
            }
        }
        String savedNodeId = normalizeNodeId(script.getNodeId());
        if (!savedNodeId.isBlank()) {
            return savedNodeId;
        }
        String activeNodeId = activeConnectionNodeId();
        if (!activeNodeId.isBlank()) {
            return activeNodeId;
        }
        return normalizeNodeId(selectedNodeId);
    }

    private void selectNodeChoice(List<NodeChoice> choices, String nodeId) {
        String normalizedNodeId = normalizeNodeId(nodeId);
        if (!normalizedNodeId.isBlank()) {
            choices.stream()
                    .filter(choice -> sameNode(choice.nodeId(), normalizedNodeId))
                    .findFirst()
                    .ifPresentOrElse(nodeCombo.getSelectionModel()::select, () -> selectFallbackNode(choices));
            return;
        }
        selectFallbackNode(choices);
    }

    private void selectFallbackNode(List<NodeChoice> choices) {
        if (!choices.isEmpty()) {
            nodeCombo.getSelectionModel().selectFirst();
        } else {
            nodeCombo.getSelectionModel().clearSelection();
        }
    }

    private String selectedNodeId() {
        NodeChoice selected = nodeCombo.getValue();
        return selected != null ? selected.nodeId() : "";
    }

    private String activeConnectionNodeId() {
        ConnectionEntry entry = connectionManager.getSelectedConnectionEntry();
        return entry != null ? resolveConnectionNodeId(entry) : "";
    }

    private Draft buildDraft() {
        String name = nameField.getText() != null ? nameField.getText().trim() : "";
        if (name.isEmpty()) {
            statusLabel.setText(I18n.t("meshIde.settings.validation.nameRequired"));
            return null;
        }
        String icon;
        try {
            icon = LuaScript.requireValidIcon(iconField.getText());
        } catch (IllegalArgumentException e) {
            statusLabel.setText(iconValidationMessage());
            return null;
        }

        LuaScript.BotType botType = botTypeCombo.getValue() != null
                ? botTypeCombo.getValue()
                : LuaScript.BotType.AIR_BOT;
        NodeChoice nodeChoice = nodeCombo.getValue();
        String nodeId = "";
        if (botType.requiresNodeBinding()) {
            if (nodeChoice == null || isBlank(nodeChoice.nodeId())) {
                statusLabel.setText(I18n.t("meshIde.settings.validation.nodeRequired"));
                return null;
            }
            nodeId = nodeChoice.nodeId();
        }
        String automationName = automationNameField.getText() != null
                ? automationNameField.getText().trim()
                : "";
        if (botType.requiresAutomationName()
                && !automationName.matches("@[\\p{L}\\p{N}_]+")) {
            statusLabel.setText(I18n.t("meshIde.settings.validation.automationNameFormat"));
            return null;
        }

        String description = descriptionArea.getText() != null ? descriptionArea.getText() : "";
        String author = LuaScript.normalizeAuthor(authorField.getText());
        return new Draft(name, autostartCheck.isSelected(), icon, nodeId, botType, automationName, description,
                author);
    }

    private TextFormatter.Change filterIconChange(TextFormatter.Change change) {
        String nextText = change.getControlNewText();
        if (nextText == null || nextText.isEmpty() || LuaScript.isEmojiIcon(nextText)) {
            if (iconValidationMessage().equals(statusLabel.getText())) {
                statusLabel.setText("");
            }
            return change;
        }
        statusLabel.setText(iconValidationMessage());
        return null;
    }

    private List<NodeChoice> loadNodeChoices(String preferredNodeId) {
        Map<String, NodeChoice> choices = new LinkedHashMap<>();

        ConnectionEntry selectedEntry = connectionManager.getSelectedConnectionEntry();
        addNodeChoice(choices, selectedEntry);
        for (ConnectionEntry entry : connectionManager.getEntries()) {
            addNodeChoice(choices, entry);
        }

        String savedNodeId = normalizeNodeId(script.getNodeId());
        if (!savedNodeId.isBlank()) {
            choices.putIfAbsent(savedNodeId,
                    new NodeChoice(savedNodeId, I18n.t("meshIde.settings.savedNode", savedNodeId), false));
        }

        String preferred = normalizeNodeId(preferredNodeId);
        if (!preferred.isBlank()) {
            choices.putIfAbsent(preferred,
                    new NodeChoice(preferred, I18n.t("meshIde.settings.nodeWithId", preferred), false));
        }
        return List.copyOf(choices.values());
    }

    private void addNodeChoice(Map<String, NodeChoice> choices, ConnectionEntry entry) {
        if (entry == null) {
            return;
        }
        String nodeId = resolveConnectionNodeId(entry);
        if (nodeId.isBlank()) {
            return;
        }
        choices.putIfAbsent(nodeId, new NodeChoice(nodeId, resolveNodeName(entry, nodeId), entry.isConnected()));
    }

    private String resolveConnectionNodeId(ConnectionEntry entry) {
        return normalizeNodeId(firstNonBlank(connectionManager.getOwnerNodeId(entry.getId()), entry.getNodeId()));
    }

    private String resolveNodeName(ConnectionEntry entry, String nodeId) {
        DeviceState state = connectionManager.getDeviceState(entry.getId());
        NodeData node = state != null ? state.getNodeDb().get(state.getMyNodeNum()) : null;
        String nodeName = node != null ? firstNonBlank(node.getLongName(), node.getShortName()) : "";
        if (nodeName.isBlank()) {
            nodeName = firstNonBlank(entry.getName(), I18n.t("meshIde.settings.nodeFallback"));
        }
        return nodeName + " (" + nodeId + ")";
    }

    private static String iconValidationMessage() {
        return I18n.t("meshIde.settings.validation.iconEmoji");
    }

    private static void closeModal() {
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane != null) {
            modalPane.hide();
        }
    }

    private boolean isNewScript() {
        return script.getId() <= 0;
    }

    private static boolean sameNode(String left, String right) {
        return normalizeNodeId(left).equalsIgnoreCase(normalizeNodeId(right));
    }

    private static String normalizeNodeId(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first.trim() : !isBlank(second) ? second.trim() : "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record Draft(String name,
                        boolean autostart,
                        String icon,
                        String nodeId,
                        LuaScript.BotType botType,
                        String automationName,
                        String description,
                        String author) {}

    private record NodeChoice(String nodeId, String displayName, boolean connected) {
        private String displayText() {
            return displayName + (connected ? " · " + I18n.t("meshIde.settings.connectedSuffix") : "");
        }

        @Override
        public String toString() {
            return displayText();
        }
    }

    private static final class NodeChoiceCell extends ListCell<NodeChoice> {
        private NodeChoiceCell() {
            setMinHeight(NODE_COMBO_HEIGHT);
            setPrefHeight(NODE_COMBO_HEIGHT);
            setMaxHeight(NODE_COMBO_HEIGHT);
        }

        @Override
        protected void updateItem(NodeChoice item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.displayText());
        }
    }
}
