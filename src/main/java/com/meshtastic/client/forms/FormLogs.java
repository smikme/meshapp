package com.meshtastic.client.forms;

import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.logging.UiLogAppender;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.LogEntry;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.SystemForm;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@SystemForm(name = "Логирование", description = "Просмотр логов приложения", tags = {"logs", "logging"})
public class FormLogs extends Form {

    private static final Logger log = LoggerFactory.getLogger(FormLogs.class);
    private static final String ICON_PAUSE = "/icons/pause.svg";
    private static final String ICON_PLAY = "/icons/play.svg";
    private static final int MAX_VISIBLE_LOG_ENTRIES = 5_000;
    private static final DateTimeFormatter EXPORT_FILE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final ObservableList<LogEntry> logData = FXCollections.observableArrayList();
    private final Object logViewStateLock = new Object();
    private boolean logViewUpdatesPaused;
    private boolean logBufferChangedWhilePaused;
    private boolean rebuildingLogView;

    private TableView<LogEntry> logTable;
    private Button btnPause;
    private Tooltip btnPauseTooltip;

    public FormLogs() {
        init();
    }

    @SuppressWarnings("unchecked")
    private void init() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Заголовок + панель действий
        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Логирование");
        title.getStyleClass().add("form-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToolBar actionToolbar = new ToolBar();
        actionToolbar.getStyleClass().add("logs-toolbar");

        btnPause = createToolbarButton(
                "Пауза логов",
                "Остановить обновление лога на экране",
                ICON_PAUSE,
                this::toggleLogViewUpdates
        );
        btnPauseTooltip = btnPause.getTooltip();
        updatePauseButtonState();

        Button btnSave = createToolbarButton(
                "Сохранить в файл",
                "Экспортировать текущие логи в файл",
                "/icons/save-config.svg",
                this::saveLogsToFile
        );

        Button btnCopy = createToolbarButton(
                "Копировать",
                "Скопировать текущие логи в буфер обмена",
                "/icons/copy.svg",
                this::copyLogsToClipboard
        );

        Button btnClear = createToolbarButton(
                "Очистить",
                "Удалить логи из таблицы и внутреннего буфера",
                "/icons/clear.svg",
                () -> {
                    UiLogAppender.clearBuffer();
                    logData.clear();
                });

        var noLogsBinding = Bindings.isEmpty(logData);
        btnSave.disableProperty().bind(noLogsBinding);
        btnCopy.disableProperty().bind(noLogsBinding);
        btnClear.disableProperty().bind(noLogsBinding);

        actionToolbar.getItems().addAll(
                btnPause,
                new Separator(Orientation.VERTICAL),
                btnSave,
                btnCopy,
                btnClear
        );

        titleRow.getChildren().addAll(title, spacer, actionToolbar);

        // Таблица логов
        logTable = new TableView<>(logData);
        logTable.setFixedCellSize(26);

        TableColumn<LogEntry, String> colTime = new TableColumn<>("Дата");
        colTime.setCellValueFactory(new PropertyValueFactory<>("time"));
        colTime.setMinWidth(90);
        colTime.setMaxWidth(110);
        colTime.setSortable(false);

        TableColumn<LogEntry, String> colLevel = new TableColumn<>("Тип");
        colLevel.setCellValueFactory(new PropertyValueFactory<>("level"));
        colLevel.setMinWidth(30);
        colLevel.setMaxWidth(50);
        colLevel.setStyle("-fx-alignment: CENTER;");
        colLevel.setSortable(false);
        colLevel.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String level, boolean empty) {
                super.updateItem(level, empty);
                if (empty || level == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                } else {
                    String emoji = levelEmoji(level);
                    ImageView iv = EmojiImageCache.createImageView(emoji, 16);
                    if (iv != null) {
                        setText(null);
                        setGraphic(iv);
                    } else {
                        setGraphic(null);
                        setText(emoji);
                    }
                    setTooltip(new javafx.scene.control.Tooltip(level));
                }
            }
        });

        TableColumn<LogEntry, String> colMessage = new TableColumn<>("Сообщение");
        colMessage.setCellValueFactory(new PropertyValueFactory<>("message"));
        colMessage.setSortable(false);

        logTable.getColumns().addAll(colTime, colLevel, colMessage);
        logTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // Раскраска строк по уровню
        logTable.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(LogEntry item, boolean empty) {
                super.updateItem(item, empty);
                setStyle("");
                if (item != null && !empty) {
                    switch (item.getLevel()) {
                        case "ERROR" -> setStyle("-fx-text-fill: #E53935;");
                        case "WARN"  -> setStyle("-fx-text-fill: #FB8C00;");
                        default -> {}
                    }
                }
            }
        });

        VBox.setVgrow(logTable, Priority.ALWAYS);
        content.getChildren().addAll(titleRow, logTable);
        getChildren().add(content);
    }

    @Override
    public void formInit() {
        // Live-подписка включается только пока форма открыта.
    }

    @Override
    public void formOpen() {
        // Обновить из буфера при каждом открытии
        reloadVisibleLogsFromBuffer();
        installLiveLogListener();
    }

    @Override
    public void formClose() {
        UiLogAppender.clearLiveListener();
    }

    private void installLiveLogListener() {
        UiLogAppender.setLiveListener(this::handleLiveLogEntry);
    }

    private void handleLiveLogEntry(LogEntry entry) {
        synchronized (logViewStateLock) {
            if (logViewUpdatesPaused || rebuildingLogView) {
                logBufferChangedWhilePaused = true;
                return;
            }
        }
        Platform.runLater(() -> appendLogEntry(entry));
    }

    private void appendLogEntry(LogEntry entry) {
        logData.add(entry);
        trimVisibleLogEntries(logData, MAX_VISIBLE_LOG_ENTRIES);
        scrollToBottom();
    }

    private void reloadVisibleLogsFromBuffer() {
        List<LogEntry> bufferSnapshot = UiLogAppender.getBuffer();
        trimVisibleLogEntries(bufferSnapshot, MAX_VISIBLE_LOG_ENTRIES);
        logData.setAll(bufferSnapshot);
        scrollToBottom();
    }

    static void trimVisibleLogEntries(List<?> entries, int maxEntries) {
        if (entries == null || maxEntries <= 0) {
            return;
        }
        int overflow = entries.size() - maxEntries;
        if (overflow > 0) {
            entries.subList(0, overflow).clear();
        }
    }

    private void copyLogsToClipboard() {
        String text = formatLogs();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void scrollToBottom() {
        if (!logData.isEmpty()) {
            logTable.scrollTo(logData.size() - 1);
        }
    }

    private void toggleLogViewUpdates() {
        if (logViewUpdatesPaused) {
            resumeLogViewUpdates();
        } else {
            pauseLogViewUpdates();
        }
        updatePauseButtonState();
        Toast.show(
                Toast.Type.INFO,
                logViewUpdatesPaused
                        ? "Обновление лога на экране приостановлено"
                        : "Обновление лога на экране возобновлено"
        );
    }

    private void pauseLogViewUpdates() {
        synchronized (logViewStateLock) {
            logViewUpdatesPaused = true;
            logBufferChangedWhilePaused = false;
        }
    }

    private void resumeLogViewUpdates() {
        synchronized (logViewStateLock) {
            rebuildingLogView = true;
        }

        int remainingPasses = 3;
        try {
            while (remainingPasses-- > 0) {
                synchronized (logViewStateLock) {
                    logBufferChangedWhilePaused = false;
                }
                reloadVisibleLogsFromBuffer();

                synchronized (logViewStateLock) {
                    if (!logBufferChangedWhilePaused) {
                        logViewUpdatesPaused = false;
                        rebuildingLogView = false;
                        return;
                    }
                }
            }
        } finally {
            synchronized (logViewStateLock) {
                logViewUpdatesPaused = false;
                rebuildingLogView = false;
                logBufferChangedWhilePaused = false;
            }
        }
    }

    private void updatePauseButtonState() {
        if (btnPause != null) {
            String title = logViewUpdatesPaused ? "Продолжить логи" : "Пауза логов";
            String description = logViewUpdatesPaused
                    ? "Перестроить лог из памяти и снова обновлять экран"
                    : "Остановить обновление лога на экране, не останавливая сбор записей";
            setToolbarButtonGraphic(btnPause, logViewUpdatesPaused ? ICON_PLAY : ICON_PAUSE, title);
            btnPause.setAccessibleText(title);
            if (btnPauseTooltip != null) {
                btnPauseTooltip.setText(title + "\n" + description);
            }
        }
    }

    private Button createToolbarButton(String title, String description, String iconPath, Runnable action) {
        Button button = new Button();
        button.getStyleClass().add("logs-toolbar-button");
        button.setMinSize(34, 34);
        button.setPrefSize(34, 34);
        button.setMaxSize(34, 34);
        button.setFocusTraversable(false);
        button.setAccessibleText(title);
        setToolbarButtonGraphic(button, iconPath, title);
        button.setTooltip(new Tooltip(title + "\n" + description));
        button.setOnAction(e -> action.run());
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

    private void saveLogsToFile() {
        if (logData.isEmpty()) {
            Toast.show(Toast.Type.WARNING, "Нет логов для сохранения");
            return;
        }

        try {
            FileChooser chooser = createLogFileChooser();
            File selectedFile = chooser.showSaveDialog(getCurrentWindow());
            if (selectedFile == null) {
                return;
            }

            File outputFile = ensureLogExtension(selectedFile);
            Files.writeString(outputFile.toPath(), formatLogs(), StandardCharsets.UTF_8);
            Toast.show(Toast.Type.SUCCESS, "Лог сохранён: " + outputFile.getName());
        } catch (Exception e) {
            log.error("Log export failed", e);
            ModalPane.showError(
                    "Ошибка сохранения лога",
                    e.getMessage() != null ? e.getMessage() : "Не удалось сохранить файл"
            );
        }
    }

    private FileChooser createLogFileChooser() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Сохранить лог");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Log files (*.log)", "*.log"
        ));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Text files (*.txt)", "*.txt"
        ));
        chooser.setInitialFileName("meshapp-log-" + EXPORT_FILE_TIME.format(LocalDateTime.now()) + ".log");
        return chooser;
    }

    private File ensureLogExtension(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".log") || name.endsWith(".txt")) {
            return file;
        }
        return new File(file.getParentFile(), file.getName() + ".log");
    }

    private Window getCurrentWindow() {
        return getScene() != null ? getScene().getWindow() : null;
    }

    private String formatLogs() {
        return formatLogEntries(logData);
    }

    static String formatLogEntries(Iterable<LogEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (LogEntry entry : entries) {
            sb.append('[').append(entry.getTime()).append("] ")
                    .append(entry.getLevel()).append(": ")
                    .append(entry.getFullMessage()).append('\n');
        }
        return sb.toString();
    }

    private static String levelEmoji(String level) {
        return switch (level) {
            case "ERROR" -> "\uD83D\uDD34";  // 🔴
            case "WARN"  -> "\uD83D\uDFE0";  // 🟠
            case "INFO"  -> "\uD83D\uDFE2";  // 🟢
            case "DEBUG" -> "\uD83D\uDD35";  // 🔵
            case "TRACE" -> "\u26AA";         // ⚪
            default      -> "\u2753";         // ❓
        };
    }
}
