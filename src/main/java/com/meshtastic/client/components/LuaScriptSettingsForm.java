package com.meshtastic.client.components;

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
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Боковая форма редактирования параметров Lua-скрипта MeshApp IDE.
 * <p>
 * Форма изменяет только метаданные сценария: имя, автозапуск, тип бота,
 * имя автоматизации и, для эфирных ботов, привязку к ноде. Исходный код
 * остается в отдельном IDE-окне.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaScriptSettingsForm extends VBox {

    private static final double NODE_COMBO_HEIGHT = 34.0;
    private static final int NODE_COMBO_VISIBLE_ROWS = 6;

    private final LuaScript script;
    private final TextField nameField = new TextField();
    private final CheckBox autostartCheck = new CheckBox("Автозапуск");
    private final Label nodeLabel = new Label("Нода исполнения");
    private final ComboBox<NodeChoice> nodeCombo = new ComboBox<>();
    private final ComboBox<LuaScript.BotType> botTypeCombo = new ComboBox<>();
    private final Label automationNameLabel = new Label("Имя автоматизации");
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

        Label title = new Label(isNewScript() ? "Новый скрипт" : "Редактировать скрипт");
        title.getStyleClass().add("dialog-title");

        nameField.setPromptText("Имя скрипта");
        nameField.setMaxWidth(Double.MAX_VALUE);

        nodeCombo.setMaxWidth(Double.MAX_VALUE);
        nodeCombo.setMinHeight(NODE_COMBO_HEIGHT);
        nodeCombo.setPrefHeight(NODE_COMBO_HEIGHT);
        nodeCombo.setMaxHeight(NODE_COMBO_HEIGHT);
        nodeCombo.setVisibleRowCount(NODE_COMBO_VISIBLE_ROWS);
        nodeCombo.setPromptText("Выберите ноду");
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

        automationNameField.setPromptText("@имя_бота");
        automationNameField.setMaxWidth(Double.MAX_VALUE);

        statusLabel.getStyleClass().add("muted-small-label");
        statusLabel.setWrapText(true);

        Button cancelButton = new Button("Отмена");
        cancelButton.setOnAction(event -> closeModal());

        Button saveButton = new Button("Сохранить");
        saveButton.getStyleClass().add("accent");
        saveButton.setOnAction(event -> save());

        HBox actions = new HBox(10, cancelButton, saveButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(10, 0, 0, 0));

        getChildren().addAll(
                title,
                new Separator(),
                new Label("Имя скрипта"),
                nameField,
                autostartCheck,
                nodeLabel,
                nodeCombo,
                new Label("Тип"),
                botTypeCombo,
                automationNameLabel,
                automationNameField,
                statusLabel,
                actions
        );
    }

    private void populateFields() {
        nameField.setText(script.getName() != null ? script.getName() : "");
        autostartCheck.setSelected(script.isAutostart());

        refreshNodeChoices();

        botTypeCombo.getSelectionModel().select(script.getBotType());
        automationNameField.setText(script.getAutomationName() != null ? script.getAutomationName() : "");
        updateAutomationVisibility();
        Platform.runLater(nameField::requestFocus);
    }

    private void updateAutomationVisibility() {
        boolean automation = botTypeCombo.getValue() == LuaScript.BotType.AUTOMATION_BOT;
        automationNameLabel.setVisible(automation);
        automationNameLabel.setManaged(automation);
        automationNameField.setVisible(automation);
        automationNameField.setManaged(automation);
        nodeLabel.setVisible(!automation);
        nodeLabel.setManaged(!automation);
        nodeCombo.setVisible(!automation);
        nodeCombo.setManaged(!automation);
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
            statusLabel.setText("Введите имя скрипта");
            return null;
        }

        LuaScript.BotType botType = botTypeCombo.getValue() != null
                ? botTypeCombo.getValue()
                : LuaScript.BotType.AIR_BOT;
        NodeChoice nodeChoice = nodeCombo.getValue();
        String nodeId = "";
        if (botType != LuaScript.BotType.AUTOMATION_BOT) {
            if (nodeChoice == null || isBlank(nodeChoice.nodeId())) {
                statusLabel.setText("Выберите ноду исполнения");
                return null;
            }
            nodeId = nodeChoice.nodeId();
        }
        String automationName = automationNameField.getText() != null
                ? automationNameField.getText().trim()
                : "";
        if (botType == LuaScript.BotType.AUTOMATION_BOT
                && !automationName.matches("@[\\p{L}\\p{N}_]+")) {
            statusLabel.setText("Имя автоматизации должно быть в формате @имя_бота");
            return null;
        }

        return new Draft(name, autostartCheck.isSelected(), nodeId, botType, automationName);
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
            choices.putIfAbsent(savedNodeId, new NodeChoice(savedNodeId, "Сохраненная нода (" + savedNodeId + ")", false));
        }

        String preferred = normalizeNodeId(preferredNodeId);
        if (!preferred.isBlank()) {
            choices.putIfAbsent(preferred, new NodeChoice(preferred, "Нода (" + preferred + ")", false));
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
            nodeName = firstNonBlank(entry.getName(), "Нода");
        }
        return nodeName + " (" + nodeId + ")";
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
                        String nodeId,
                        LuaScript.BotType botType,
                        String automationName) {}

    private record NodeChoice(String nodeId, String displayName, boolean connected) {
        private String displayText() {
            return displayName + (connected ? " · подключена" : "");
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
