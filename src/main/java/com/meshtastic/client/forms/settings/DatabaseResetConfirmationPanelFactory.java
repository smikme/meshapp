package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Builds the database-reset confirmation panel.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class DatabaseResetConfirmationPanelFactory {

    private DatabaseResetConfirmationPanelFactory() {}

    /**
     * Creates a confirmation panel that requires explicit acknowledgement before
     * running the destructive reset action.
     *
     * @param onConfirm reset action
     * @return confirmation panel
     */
    public static VBox create(Runnable onConfirm) {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(380);
        panel.setMaxWidth(380);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        Label titleLabel = new Label(I18n.t("settings.databaseReset.title"));
        titleLabel.getStyleClass().add("dialog-title");

        Label messageLabel = new Label(I18n.t("settings.databaseReset.message"));
        messageLabel.setWrapText(true);

        CheckBox acknowledgeCheckBox = new CheckBox(
            I18n.t("settings.databaseReset.acknowledge")
        );
        acknowledgeCheckBox.setWrapText(true);

        Button cancelButton = new Button(I18n.t("common.cancel"));
        cancelButton.setOnAction(e ->
            java.util.Optional
                .ofNullable(ModalPane.getInstance())
                .ifPresent(ModalPane::hide)
        );

        Button confirmButton = new Button(
            I18n.t("settings.databaseReset.confirm")
        );
        confirmButton.getStyleClass().add("accent");
        confirmButton
            .disableProperty()
            .bind(acknowledgeCheckBox.selectedProperty().not());
        confirmButton.setOnAction(e -> {
            java.util.Optional
                .ofNullable(ModalPane.getInstance())
                .ifPresent(ModalPane::hide);
            onConfirm.run();
        });

        HBox buttonRow = new HBox(10, cancelButton, confirmButton);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);
        buttonRow.setPadding(new Insets(10, 0, 0, 0));

        panel
            .getChildren()
            .addAll(
                titleLabel,
                new Separator(),
                messageLabel,
                acknowledgeCheckBox,
                buttonRow
            );
        return panel;
    }
}
