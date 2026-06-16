package com.meshtastic.client.components;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.service.DatabaseProvider;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Startup dialog shown while an automatic H2 database recovery is running.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class DatabaseRecoveryDialog {

    private DatabaseRecoveryDialog() {}

    /**
     * Shows a modal startup dialog and runs database recovery on a background
     * virtual thread.
     * <p>
     * The dialog is intended for the first database open during application
     * startup, before the main window is shown. It blocks the JavaFX application
     * thread with {@link Stage#showAndWait()} while the worker thread performs
     * the H2 recovery task and posts progress updates back to the UI thread.
     * When called outside the JavaFX application thread, the task is executed
     * directly without showing UI; this keeps service tests and non-UI callers
     * independent from JavaFX.
     *
     * @param owner owner window for modality, usually the primary stage
     * @param dbFile database file that failed to open
     * @param recoveryTask recovery task supplied by {@link DatabaseProvider}
     * @throws Exception when the recovery task fails before startup can continue
     */
    public static void run(Window owner, Path dbFile, DatabaseProvider.RecoveryTask recoveryTask) throws Exception {
        if (!Platform.isFxApplicationThread()) {
            recoveryTask.run((step, path) -> {});
            return;
        }

        Stage dialog = createDialog(owner, dbFile);
        Scene scene = dialog.getScene();
        Label statusLabel = (Label) scene.lookup("#databaseRecoveryStatus");
        Label pathLabel = (Label) scene.lookup("#databaseRecoveryPath");
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = Thread.ofVirtual().name("meshapp-db-recovery").unstarted(() -> {
            try {
                recoveryTask.run((step, path) -> Platform.runLater(() ->
                        updateStatus(statusLabel, pathLabel, step, path)));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                Platform.runLater(dialog::close);
            }
        });

        dialog.setOnShown(e -> worker.start());
        dialog.showAndWait();
        ThemeManager.unregisterScene(scene);

        Throwable throwable = failure.get();
        if (throwable instanceof Exception exception) {
            throw exception;
        }
        if (throwable != null) {
            throw new RuntimeException("Database recovery failed", throwable);
        }
    }

    private static Stage createDialog(Window owner, Path dbFile) {
        Label titleLabel = new Label(I18n.t("databaseRecovery.header"));
        titleLabel.getStyleClass().add("dialog-title");

        Label messageLabel = new Label(I18n.t("databaseRecovery.message"));
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(Double.MAX_VALUE);

        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setMinSize(36, 36);
        indicator.setPrefSize(36, 36);
        indicator.setMaxSize(36, 36);

        Label statusLabel = new Label(I18n.t("databaseRecovery.status.detected"));
        statusLabel.setId("databaseRecoveryStatus");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.getStyleClass().add("database-recovery-status");
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        HBox progressRow = new HBox(12, indicator, statusLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        Label pathLabel = new Label(I18n.t("databaseRecovery.path", dbFile));
        pathLabel.setId("databaseRecoveryPath");
        pathLabel.setWrapText(true);
        pathLabel.setMaxWidth(Double.MAX_VALUE);
        pathLabel.getStyleClass().add("database-recovery-path");

        VBox root = new VBox(12, titleLabel, messageLabel, progressRow, pathLabel);
        root.getStyleClass().add("database-recovery-dialog");
        root.setPadding(new Insets(22, 24, 22, 24));
        root.setPrefWidth(480);
        root.setMinWidth(420);

        Scene scene = new Scene(root);
        ThemeManager.applyTheme(scene, AppPreferences.isDarkMode());

        Stage dialog = new Stage();
        dialog.setTitle(I18n.t("databaseRecovery.title"));
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setScene(scene);
        dialog.setResizable(false);
        dialog.setOnCloseRequest(e -> e.consume());
        return dialog;
    }

    private static void updateStatus(Label statusLabel,
                                     Label pathLabel,
                                     DatabaseProvider.RecoveryStep step,
                                     Path path) {
        if (statusLabel != null) {
            statusLabel.setText(I18n.t(statusKey(step)));
        }
        if (pathLabel != null && path != null) {
            pathLabel.setText(I18n.t("databaseRecovery.path", path));
        }
    }

    private static String statusKey(DatabaseProvider.RecoveryStep step) {
        return switch (step) {
            case DETECTED -> "databaseRecovery.status.detected";
            case MOVING_CORRUPT_DATABASE -> "databaseRecovery.status.move";
            case EXPORTING_SQL -> "databaseRecovery.status.export";
            case IMPORTING_SQL -> "databaseRecovery.status.import";
            case CREATING_FRESH_DATABASE -> "databaseRecovery.status.fresh";
            case COMPLETE -> "databaseRecovery.status.complete";
        };
    }
}
