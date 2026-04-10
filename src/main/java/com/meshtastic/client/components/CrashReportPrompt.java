package com.meshtastic.client.components;

import com.meshtastic.client.modal.ModalPane;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Window;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Crash-report панели, встроенные в общую стилистику приложения через ModalPane.
 */
public final class CrashReportPrompt {

    private CrashReportPrompt() {}

    public static void show(Window owner, Consumer<Decision> callback) {
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            callback.accept(new Decision(false, ""));
            return;
        }

        AtomicBoolean handled = new AtomicBoolean(false);
        AtomicReference<Decision> result = new AtomicReference<>(new Decision(false, ""));

        Label title = new Label("Отчёт о сбое");
        title.setFont(Font.font("Roboto", FontWeight.BOLD, 15));

        Label lead = new Label("Прошлый запуск приложения завершился ошибкой. Мы сохранили технический лог, чтобы можно было отправить его разработчикам.");
        lead.setWrapText(true);

        Label commentLabel = new Label("Комментарий к случившемуся:");
        TextArea commentArea = new TextArea();
        commentArea.setPromptText("Опишите, что происходило перед сбоем");
        commentArea.setWrapText(true);
        commentArea.setPrefRowCount(5);

        Label privacy = new Label("Никакая конфиденциальная информация не передаётся: отправляется только технический лог приложения и ваш необязательный комментарий.");
        privacy.setWrapText(true);
        privacy.setStyle("-fx-opacity: 0.8;");

        Button cancelButton = new Button("Отмена");
        Button sendButton = new Button("Отправить лог");
        sendButton.getStyleClass().add("accent");

        HBox buttonRow = new HBox(10, cancelButton, sendButton);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);
        buttonRow.setPadding(new Insets(10, 0, 0, 0));

        VBox panel = new VBox(12,
                title,
                new Separator(),
                lead,
                commentLabel,
                commentArea,
                privacy,
                buttonRow
        );
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(420);
        panel.setMaxWidth(420);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        cancelButton.setOnAction(event -> {
            result.set(new Decision(false, commentArea.getText()));
            modalPane.hide();
        });
        sendButton.setOnAction(event -> {
            result.set(new Decision(true, commentArea.getText()));
            modalPane.hide();
        });

        modalPane.show(panel, false, false);
        modalPane.setOnHidden(() -> {
            if (handled.compareAndSet(false, true)) {
                callback.accept(result.get());
            }
        });

        Platform.runLater(commentArea::requestFocus);
    }

    public static ProgressDialog showProgress(Window owner) {
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            return ProgressDialog.noop();
        }

        Label title = new Label("Отправка лога");
        title.setFont(Font.font("Roboto", FontWeight.BOLD, 15));

        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setMaxSize(56, 56);

        Label message = new Label("Отправляем архив лога разработчикам.");
        message.setWrapText(true);

        Label secondary = new Label("Можно дождаться завершения или отменить отправку и продолжить запуск приложения.");
        secondary.setWrapText(true);
        secondary.setStyle("-fx-opacity: 0.8;");

        Button cancelButton = new Button("Отмена");

        HBox progressRow = new HBox(14, indicator, new VBox(8, message, secondary));
        progressRow.setAlignment(Pos.CENTER_LEFT);

        HBox buttonRow = new HBox(cancelButton);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);
        buttonRow.setPadding(new Insets(10, 0, 0, 0));

        VBox panel = new VBox(12,
                title,
                new Separator(),
                progressRow,
                buttonRow
        );
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(380);
        panel.setMaxWidth(380);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        ProgressDialog progressDialog = new ProgressDialog(modalPane);
        cancelButton.setOnAction(event -> modalPane.hide());

        modalPane.show(panel, false, false);
        modalPane.setOnHidden(progressDialog::handleHidden);
        return progressDialog;
    }

    public record Decision(boolean sendReport, String comment) {}

    public static final class ProgressDialog {

        private final ModalPane modalPane;
        private final AtomicBoolean closedProgrammatically = new AtomicBoolean(false);
        private final AtomicBoolean handled = new AtomicBoolean(false);
        private volatile Runnable onCancel;
        private volatile Runnable onClosed;

        private ProgressDialog(ModalPane modalPane) {
            this.modalPane = modalPane;
        }

        private static ProgressDialog noop() {
            return new ProgressDialog(null);
        }

        public void setOnCancel(Runnable onCancel) {
            this.onCancel = onCancel;
        }

        public void close() {
            close(null);
        }

        public void close(Runnable onClosed) {
            this.onClosed = onClosed;
            closedProgrammatically.set(true);
            if (modalPane != null) {
                modalPane.hide();
            } else if (onClosed != null) {
                onClosed.run();
            }
        }

        private void handleHidden() {
            if (!handled.compareAndSet(false, true)) {
                return;
            }

            if (closedProgrammatically.get()) {
                Runnable closedAction = onClosed;
                if (closedAction != null) {
                    closedAction.run();
                }
                return;
            }

            Runnable cancelAction = onCancel;
            if (cancelAction != null) {
                cancelAction.run();
            }
        }
    }
}
