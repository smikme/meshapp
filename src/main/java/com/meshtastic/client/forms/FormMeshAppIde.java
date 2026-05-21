package com.meshtastic.client.forms;

import com.meshtastic.client.components.LuaDevWindow;
import com.meshtastic.client.components.LuaScriptSettingsForm;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptEvent;
import com.meshtastic.client.lua.LuaScriptRuntimeService;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.SystemForm;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Главная форма MeshApp IDE со списком пользовательских Lua-скриптов.
 * <p>
 * Форма отображает параметры скриптов, их состояние выполнения и действия
 * управления. Отдельное окно редактора открывается только по явному действию
 * редактирования выбранного скрипта.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@SystemForm(name = "MeshApp IDE", description = "Скрипты и автоматизация", tags = {"lua", "scripts", "automation"})
public class FormMeshAppIde extends Form {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final LuaScriptService scriptService = LuaScriptService.getInstance();
    private final LuaScriptRuntimeService runtimeService = LuaScriptRuntimeService.getInstance();

    private VBox cardsBox;
    private Label emptyLabel;

    public FormMeshAppIde() {
        init();
    }

    private void init() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("MeshApp IDE");
        title.getStyleClass().add("form-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refreshButton = new Button("Обновить");
        refreshButton.setOnAction(event -> rebuildCards());

        Button createButton = new Button("Новый скрипт");
        createButton.setOnAction(event -> createScript());

        titleRow.getChildren().addAll(title, spacer, refreshButton, createButton);

        emptyLabel = new Label("Скриптов пока нет");
        emptyLabel.setStyle("-fx-opacity: 0.65;");

        cardsBox = new VBox(10);

        ScrollPane scrollPane = new ScrollPane(cardsBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        content.getChildren().addAll(titleRow, scrollPane);
        getChildren().add(content);
    }

    @Override
    public void formOpen() {
        rebuildCards();
    }

    @Override
    public void formRefresh() {
        rebuildCards();
    }

    private void rebuildCards() {
        List<LuaScript> scripts = scriptService.listScripts();
        cardsBox.getChildren().clear();
        if (scripts.isEmpty()) {
            VBox emptyCard = new VBox(emptyLabel);
            emptyCard.setPadding(new Insets(15));
            emptyCard.getStyleClass().add("connection-card");
            cardsBox.getChildren().add(emptyCard);
            return;
        }
        for (LuaScript script : scripts) {
            cardsBox.getChildren().add(createScriptCard(script));
        }
    }

    private VBox createScriptCard(LuaScript script) {
        boolean running = runtimeService.isRunning(script.getId());
        VBox card = new VBox(8);
        card.setPadding(new Insets(15));
        card.getStyleClass().add("connection-card");
        if (running) {
            card.setStyle("-fx-border-color: #1EA97C; -fx-border-width: 0 0 0 4; -fx-background-radius: 20; -fx-border-radius: 20;");
        } else if (!script.isEnabled()) {
            card.setStyle("-fx-border-color: #9CA3AF; -fx-border-width: 0 0 0 4; -fx-background-radius: 20; -fx-border-radius: 20;");
        }

        HBox topRow = new HBox(10);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label indicator = new Label("\u25CF");
        indicator.setStyle("-fx-text-fill: " + indicatorColor(script, running) + "; -fx-font-weight: bold;");

        Label name = new Label(script.getName());
        name.getStyleClass().add("connection-card-name");

        Label status = new Label(statusText(script, running));
        status.setStyle("-fx-text-fill: " + indicatorColor(script, running) + "; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button runButton = new Button(running ? "Остановить" : "Запустить");
        runButton.setOnAction(event -> {
            if (running) {
                stopScript(script);
            } else {
                runScript(script);
            }
        });

        Button enabledButton = new Button(script.isAutostart() ? "Автозапуск выкл" : "Автозапуск вкл");
        enabledButton.setOnAction(event -> toggleAutostart(script));

        Button ideButton = new Button("IDE");
        ideButton.setOnAction(event -> LuaDevWindow.showWindow(script.getId()));

        Button editButton = new Button("Редактировать");
        editButton.setOnAction(event -> showSettingsDialog(script));

        Button deleteButton = new Button("Удалить");
        deleteButton.setOnAction(event -> deleteScript(script));

        topRow.getChildren().addAll(indicator, name, status, spacer, runButton, enabledButton, ideButton, editButton, deleteButton);

        Label params = new Label(scriptSummary(script));
        params.setWrapText(true);
        params.setStyle("-fx-opacity: 0.68;");

        card.getChildren().addAll(topRow, params);
        if (script.getLastError() != null && !script.getLastError().isBlank()) {
            Label error = new Label("Ошибка: " + truncate(script.getLastError(), 240));
            error.setWrapText(true);
            error.setStyle("-fx-text-fill: #EF4444;");
            card.getChildren().add(error);
        }
        return card;
    }

    private void createScript() {
        try {
            LuaScript script = scriptService.createScript();
            rebuildCards();
            Toast.show(Toast.Type.SUCCESS, "Создан скрипт: " + script.getName());
        } catch (Exception e) {
            Toast.show(Toast.Type.ERROR, "Не удалось создать скрипт: " + e.getMessage());
        }
    }

    private void toggleAutostart(LuaScript script) {
        try {
            scriptService.saveScript(script.getId(), script.getName(), script.getCode(), !script.isAutostart());
            rebuildCards();
        } catch (Exception e) {
            Toast.show(Toast.Type.ERROR, "Не удалось изменить состояние: " + e.getMessage());
        }
    }

    private void showSettingsDialog(LuaScript script) {
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            return;
        }
        LuaScriptSettingsForm form = new LuaScriptSettingsForm(script);
        form.setOnSave(draft -> {
            try {
                LuaScript saved = scriptService.saveScriptSettings(
                        script.getId(),
                        draft.name(),
                        draft.autostart(),
                        draft.nodeId(),
                        draft.botType(),
                        draft.automationName());
                modalPane.hide();
                rebuildCards();
                Toast.show(Toast.Type.SUCCESS, "Сохранено: " + saved.getName());
            } catch (Exception e) {
                Toast.show(Toast.Type.ERROR, "Ошибка сохранения: " + e.getMessage());
            }
        });
        modalPane.show(form);
    }

    private void runScript(LuaScript script) {
        runtimeService.runScript(script, this::handleRuntimeEvent);
        rebuildCards();
        Toast.show(Toast.Type.INFO, "Запуск: " + script.getName());
    }

    private void stopScript(LuaScript script) {
        runtimeService.stopScript(script.getId(), this::handleRuntimeEvent);
        rebuildCards();
        Toast.show(Toast.Type.INFO, "Остановлен: " + script.getName());
    }

    private void deleteScript(LuaScript script) {
        ModalPane.showConfirm(
                "Подтверждение",
                "Удалить скрипт \"" + script.getName() + "\"?",
                confirmed -> {
                    if (!confirmed) {
                        return;
                    }
                    runtimeService.stopScript(script.getId(), this::handleRuntimeEvent);
                    scriptService.deleteScript(script.getId());
                    rebuildCards();
                    Toast.show(Toast.Type.SUCCESS, "Удален скрипт: " + script.getName());
                });
    }

    private void handleRuntimeEvent(LuaScriptEvent event) {
        Platform.runLater(() -> {
            rebuildCards();
            if (event.type() == LuaScriptEvent.Type.ERROR) {
                Toast.show(Toast.Type.ERROR, event.message());
            }
        });
    }

    private String scriptSummary(LuaScript script) {
        return "ID: " + script.getId()
                + " · " + (script.isAutostart() ? "автозапуск" : "без автозапуска")
                + " · " + script.getBotType().getDisplayName()
                + automationSummary(script)
                + " · " + nodeSummary(script)
                + " · строк: " + lineCount(script.getCode())
                + " · изменен: " + formatTime(script.getUpdatedAt())
                + " · запуск: " + formatLastRun(script.getLastRunAt());
    }

    private String statusText(LuaScript script, boolean running) {
        if (running) {
            return "RUNNING";
        }
        String status = script.getLastStatus();
        return status == null || status.isBlank() ? "NEW" : status.toUpperCase(Locale.ROOT);
    }

    private String indicatorColor(LuaScript script, boolean running) {
        if (running) {
            return "#1EA97C";
        }
        if (!script.isAutostart()) {
            return "#9CA3AF";
        }
        String status = script.getLastStatus();
        if ("ERROR".equalsIgnoreCase(status)) {
            return "#EF4444";
        }
        return "#60A5FA";
    }

    private String automationSummary(LuaScript script) {
        if (script.getBotType() != LuaScript.BotType.AUTOMATION_BOT) {
            return "";
        }
        String automationName = script.getAutomationName();
        return automationName == null || automationName.isBlank() ? " · автоматизация не задана" : " · " + automationName;
    }

    private String nodeSummary(LuaScript script) {
        String nodeId = script.getNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            return "нода: не выбрана";
        }
        ConnectionManager manager = ConnectionManager.getInstance();
        for (ConnectionEntry entry : manager.getEntries()) {
            String entryNodeId = firstNonBlank(manager.getOwnerNodeId(entry.getId()), entry.getNodeId());
            if (nodeId.equalsIgnoreCase(entryNodeId)) {
                return "нода: " + entry.getName() + " (" + nodeId + ")";
            }
        }
        return "нода: " + nodeId;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : second != null ? second.trim() : "";
    }

    private String formatLastRun(long epochSeconds) {
        return epochSeconds > 0 ? formatTime(epochSeconds) : "никогда";
    }

    private String formatTime(long epochSeconds) {
        return epochSeconds > 0 ? TIME_FORMAT.format(Instant.ofEpochSecond(epochSeconds)) : "-";
    }

    private int lineCount(String code) {
        if (code == null || code.isEmpty()) {
            return 0;
        }
        return code.split("\\R", -1).length;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)) + "...";
    }
}
