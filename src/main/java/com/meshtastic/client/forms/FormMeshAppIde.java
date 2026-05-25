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
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.SystemForm;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

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

        ToolBar actionToolbar = new ToolBar();
        actionToolbar.getStyleClass().add("ide-toolbar");

        Button refreshButton = createToolbarButton(
                "Обновить",
                "Перестроить список скриптов",
                "/icons/refresh.svg",
                this::rebuildCards);

        Button createButton = createToolbarButton(
                "Новый скрипт",
                "Создать новый Lua-скрипт",
                "/icons/add.svg",
                this::createScript);

        actionToolbar.getItems().addAll(
                refreshButton,
                new Separator(Orientation.VERTICAL),
                createButton
        );

        titleRow.getChildren().addAll(title, spacer, actionToolbar);

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

        ToolBar actionToolbar = createScriptActionToolbar(script, running);

        topRow.getChildren().addAll(indicator, name, status, spacer, actionToolbar);

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

    private ToolBar createScriptActionToolbar(LuaScript script, boolean running) {
        ToolBar actionToolbar = new ToolBar();
        actionToolbar.getStyleClass().add("ide-toolbar");

        Button runButton = createToolbarButton(
                running ? "Остановить" : "Запустить",
                running ? "Остановить выполнение скрипта" : "Запустить скрипт",
                running ? "/icons/ide-stop.svg" : "/icons/ide-terminal-run.svg",
                () -> {
                    if (running) {
                        stopScript(script);
                    } else {
                        runScript(script);
                    }
                });

        Button autostartButton = createToolbarButton(
                script.isAutostart() ? "Отключить автозапуск" : "Включить автозапуск",
                script.isAutostart()
                        ? "Не запускать скрипт автоматически"
                        : "Запускать скрипт автоматически",
                "/icons/autoplay.svg",
                () -> toggleAutostart(script));

        Button ideButton = createToolbarButton(
                "Открыть IDE",
                "Открыть редактор и отладчик скрипта",
                "/icons/ide-file-code.svg",
                () -> LuaDevWindow.showWindow(script.getId()));

        Button editButton = createToolbarButton(
                "Настройки",
                "Изменить параметры скрипта",
                "/drawer/icon/setting.svg",
                () -> showSettingsDialog(script));

        Button deleteButton = createToolbarButton(
                "Удалить",
                "Удалить скрипт",
                "/drawer/icon/delete-node.svg",
                () -> deleteScript(script));

        actionToolbar.getItems().addAll(
                runButton,
                autostartButton,
                new Separator(Orientation.VERTICAL),
                ideButton,
                editButton,
                new Separator(Orientation.VERTICAL),
                deleteButton
        );
        return actionToolbar;
    }

    private Button createToolbarButton(String title, String description, String iconPath, Runnable action) {
        Button button = new Button();
        button.getStyleClass().add("ide-toolbar-button");
        button.setMinSize(34, 34);
        button.setPrefSize(34, 34);
        button.setMaxSize(34, 34);
        button.setFocusTraversable(false);
        button.setAccessibleText(title);
        setToolbarButtonGraphic(button, iconPath, title);
        button.setTooltip(new Tooltip(title + "\n" + description));
        button.setOnAction(event -> action.run());
        return button;
    }

    private void setToolbarButtonGraphic(Button button, String iconPath, String fallbackText) {
        SVGPath icon = SvgIconLoader.load(iconPath, 18);
        if (icon != null) {
            button.setGraphic(icon);
            button.setText(null);
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            button.setGraphic(null);
            button.setText(fallbackText);
            button.setContentDisplay(ContentDisplay.TEXT_ONLY);
        }
    }

    private void createScript() {
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            return;
        }
        LuaScript draftScript = scriptService.createDraftScript();
        LuaScriptSettingsForm form = new LuaScriptSettingsForm(draftScript);
        form.setOnSave(draft -> {
            try {
                LuaScript created = scriptService.createScript(
                        draft.name(),
                        draftScript.getCode(),
                        draft.autostart(),
                        draft.nodeId(),
                        draft.botType(),
                        draft.automationName());
                modalPane.hide();
                rebuildCards();
                Toast.show(Toast.Type.SUCCESS, "Создан скрипт: " + created.getName());
            } catch (Exception e) {
                Toast.show(Toast.Type.ERROR, "Не удалось создать скрипт: " + e.getMessage());
            }
        });
        modalPane.show(form);
        modalPane.setOnHidden(form::dispose);
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
        modalPane.setOnHidden(form::dispose);
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
