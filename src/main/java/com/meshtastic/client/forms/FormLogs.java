package com.meshtastic.client.forms;

import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.logging.UiLogAppender;
import com.meshtastic.client.model.LogEntry;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.SystemForm;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

@SystemForm(name = "Логирование", description = "Просмотр логов приложения", tags = {"logs", "logging"})
public class FormLogs extends Form {

    private final ObservableList<LogEntry> logData = FXCollections.observableArrayList();
    private TableView<LogEntry> logTable;

    public FormLogs() {
        init();
    }

    @SuppressWarnings("unchecked")
    private void init() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Заголовок + кнопка очистки
        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Логирование");
        title.setFont(Font.font("Roboto", FontWeight.BOLD, 16));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnCopy = new Button("Копировать");
        btnCopy.setOnAction(e -> copyLogsToClipboard());

        Button btnClear = new Button("Очистить");
        btnClear.setOnAction(e -> {
            UiLogAppender.clearBuffer();
            logData.clear();
        });

        titleRow.getChildren().addAll(title, spacer, btnCopy, btnClear);

        // Таблица логов
        logTable = new TableView<>(logData);
        logTable.setFixedCellSize(26);

        TableColumn<LogEntry, String> colTime = new TableColumn<>("Дата");
        colTime.setCellValueFactory(new PropertyValueFactory<>("time"));
        colTime.setPrefWidth(100);
        colTime.setSortable(false);

        TableColumn<LogEntry, String> colLevel = new TableColumn<>("Тип");
        colLevel.setCellValueFactory(new PropertyValueFactory<>("level"));
        colLevel.setPrefWidth(40);
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
        // Подписка на новые события в реальном времени
        UiLogAppender.setLiveListener(entry ->
                Platform.runLater(() -> {
                    logData.add(entry);
                    scrollToBottom();
                })
        );
    }

    @Override
    public void formOpen() {
        // Обновить из буфера при каждом открытии
        logData.setAll(UiLogAppender.getBuffer());
        scrollToBottom();
    }

    private void copyLogsToClipboard() {
        StringBuilder sb = new StringBuilder();
        for (LogEntry entry : logData) {
            sb.append('[').append(entry.getTime()).append("] ")
              .append(entry.getLevel()).append(": ")
              .append(entry.getMessage()).append('\n');
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void scrollToBottom() {
        if (!logData.isEmpty()) {
            logTable.scrollTo(logData.size() - 1);
        }
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
