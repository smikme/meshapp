package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.service.FirmwareImage;
import com.meshtastic.client.service.FirmwareUpdateMode;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Builds the firmware update risk confirmation panel.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class FirmwareUpdateConfirmationPanelFactory {

    private FirmwareUpdateConfirmationPanelFactory() {}

    /**
     * Creates a confirmation panel that requires an explicit acknowledgement
     * checkbox before sending the bootloader command.
     *
     * @param image selected firmware image
     * @param mode resolved bootloader mode
     * @param connectionName active connection display name
     * @param warnings warning lines shown before the acknowledgement checkbox
     * @param onConfirm action to run after acknowledgement and confirmation
     * @return confirmation panel root
     */
    public static VBox create(
        FirmwareImage image,
        FirmwareUpdateMode mode,
        String connectionName,
        List<String> warnings,
        Runnable onConfirm
    ) {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(430);
        panel.setMaxWidth(430);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        Label titleLabel = new Label(I18n.t("settings.firmware.confirm.title"));
        titleLabel.getStyleClass().add("dialog-title");

        Label summaryLabel = new Label(
            I18n.t(
                "settings.firmware.confirm.summary",
                connectionName != null ? connectionName : "",
                image != null ? image.fileName() : "",
                image != null ? image.displaySize() : "",
                I18n.t(mode.labelKey())
            )
        );
        summaryLabel.setWrapText(true);

        Label riskLabel = new Label(I18n.t("settings.firmware.confirm.risk"));
        riskLabel.setWrapText(true);

        VBox warningBox = new VBox(4);
        for (String warning : warnings != null ? warnings : List.<String>of()) {
            Label warningLabel = new Label("- " + warning);
            warningLabel.setWrapText(true);
            warningBox.getChildren().add(warningLabel);
        }

        CheckBox acknowledgeCheckBox = new CheckBox(
            I18n.t("settings.firmware.confirm.acknowledge")
        );
        acknowledgeCheckBox.setWrapText(true);

        Button cancelButton = new Button(I18n.t("common.cancel"));
        cancelButton.setOnAction(e ->
            java.util.Optional
                .ofNullable(ModalPane.getInstance())
                .ifPresent(ModalPane::hide)
        );

        Button confirmButton = new Button(
            I18n.t("settings.firmware.confirm.action")
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
                summaryLabel,
                riskLabel,
                warningBox,
                acknowledgeCheckBox,
                buttonRow
            );
        return panel;
    }
}
