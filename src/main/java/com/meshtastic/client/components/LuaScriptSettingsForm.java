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
 * Форма изменяет только метаданные сценария: имя, автозапуск, привязку к ноде,
 * тип бота и имя автоматизации. Исходный код остается в отдельном IDE-окне.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaScriptSettingsForm extends VBox {

    private final LuaScript script;
    private final TextField nameField = new TextField();
    private final CheckBox autostartCheck = new CheckBox("Автозапуск");
    private final ComboBox<NodeChoice> nodeCombo = new ComboBox<>();
    private final ComboBox<LuaScript.BotType> botTypeCombo = new ComboBox<>();
    private final Label automationNameLabel = new Label("Имя автоматизации");
    private final TextField automationNameField = new TextField();
    private final Label statusLabel = new Label();

    private Consumer<Draft> onSave;

    public LuaScriptSettingsForm(LuaScript script) {
        this.script = script;
        configureLayout();
        populateFields();
    }

    public void setOnSave(Consumer<Draft> onSave) {
        this.onSave = onSave;
    }

    private void configureLayout() {
        setSpacing(10);
        setPadding(new Insets(20, 30, 20, 30));
        setPrefWidth(390);
        setMaxWidth(390);
        setMaxHeight(Double.MAX_VALUE);
        getStyleClass().add("modal-side-panel");

        Label title = new Label("Редактировать скрипт");
        title.getStyleClass().add("dialog-title");

        nameField.setPromptText("Имя скрипта");
        nameField.setMaxWidth(Double.MAX_VALUE);

        nodeCombo.setMaxWidth(Double.MAX_VALUE);
        nodeCombo.setPromptText("Выберите ноду");

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
                new Label("Нода исполнения"),
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

        List<NodeChoice> choices = loadNodeChoices(script);
        nodeCombo.getItems().setAll(choices);
        choices.stream()
                .filter(choice -> sameNode(choice.nodeId(), script.getNodeId()))
                .findFirst()
                .ifPresentOrElse(nodeCombo.getSelectionModel()::select, () -> {
                    if (!choices.isEmpty()) {
                        nodeCombo.getSelectionModel().selectFirst();
                    }
                });

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

    private Draft buildDraft() {
        String name = nameField.getText() != null ? nameField.getText().trim() : "";
        if (name.isEmpty()) {
            statusLabel.setText("Введите имя скрипта");
            return null;
        }

        NodeChoice nodeChoice = nodeCombo.getValue();
        if (nodeChoice == null || isBlank(nodeChoice.nodeId())) {
            statusLabel.setText("Выберите ноду исполнения");
            return null;
        }

        LuaScript.BotType botType = botTypeCombo.getValue() != null
                ? botTypeCombo.getValue()
                : LuaScript.BotType.AIR_BOT;
        String automationName = automationNameField.getText() != null
                ? automationNameField.getText().trim()
                : "";
        if (botType == LuaScript.BotType.AUTOMATION_BOT
                && !automationName.matches("@[\\p{L}\\p{N}_]+")) {
            statusLabel.setText("Имя автоматизации должно быть в формате @имя_бота");
            return null;
        }

        return new Draft(name, autostartCheck.isSelected(), nodeChoice.nodeId(), botType, automationName);
    }

    private static List<NodeChoice> loadNodeChoices(LuaScript script) {
        ConnectionManager manager = ConnectionManager.getInstance();
        Map<String, NodeChoice> choices = new LinkedHashMap<>();
        for (ConnectionEntry entry : manager.getEntries()) {
            String nodeId = normalizeNodeId(firstNonBlank(manager.getOwnerNodeId(entry.getId()), entry.getNodeId()));
            if (nodeId.isBlank()) {
                continue;
            }
            choices.putIfAbsent(nodeId, new NodeChoice(nodeId, resolveNodeName(manager, entry, nodeId), entry.isConnected()));
        }

        String savedNodeId = normalizeNodeId(script.getNodeId());
        if (!savedNodeId.isBlank()) {
            choices.putIfAbsent(savedNodeId, new NodeChoice(savedNodeId, "Сохраненная нода", false));
        }
        return List.copyOf(choices.values());
    }

    private static String resolveNodeName(ConnectionManager manager, ConnectionEntry entry, String nodeId) {
        DeviceState state = manager.getDeviceState(entry.getId());
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
        @Override
        public String toString() {
            return displayName + (connected ? " · подключена" : "");
        }
    }
}
