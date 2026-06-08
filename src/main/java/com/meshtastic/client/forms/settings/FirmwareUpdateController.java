package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.FirmwareImage;
import com.meshtastic.client.service.FirmwareUpdateMode;
import com.meshtastic.client.service.FirmwareUpdateProgress;
import com.meshtastic.client.service.FirmwareUpdateRequest;
import com.meshtastic.client.service.FirmwareUpdateResult;
import com.meshtastic.client.service.FirmwareUpdateService;
import com.meshtastic.client.service.FirmwareValidationResult;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.meshtastic.proto.MeshProtos;

/**
 * Controller for the firmware settings tab.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class FirmwareUpdateController {

    private final FirmwareUpdateService service = new FirmwareUpdateService();
    private VBox panel;
    private Label connectionValueLabel;
    private Label deviceValueLabel;
    private Label firmwareValueLabel;
    private TextField fileField;
    private ComboBox<FirmwareUpdateMode> modeComboBox;
    private Label validationLabel;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button chooseFileButton;
    private Button startButton;
    private Path selectedPath;
    private FirmwareValidationResult lastValidation;

    /**
     * Builds the firmware settings tab panel and wires file selection,
     * validation, confirmation, and bootloader command actions.
     *
     * @return JavaFX panel root
     */
    public VBox createPanel() {
        panel = new VBox(10);
        panel.setPadding(new Insets(5));

        GridPane deviceGrid = createDeviceGrid();
        HBox fileRow = createFileRow();
        HBox modeRow = createModeRow();

        validationLabel = new Label(I18n.t("settings.firmware.validation.noFile"));
        validationLabel.setWrapText(true);
        validationLabel.setStyle("-fx-opacity: 0.75;");

        statusLabel = new Label("");
        statusLabel.getStyleClass().add("config-status-label");
        statusLabel.setWrapText(true);

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        startButton = new Button(I18n.t("settings.firmware.action.prepare"));
        startButton.getStyleClass().add("accent");
        startButton.setOnAction(e -> requestStart());

        HBox actionRow = new HBox(8, startButton);
        actionRow.setAlignment(Pos.CENTER_RIGHT);

        panel
            .getChildren()
            .addAll(
                deviceGrid,
                fileRow,
                modeRow,
                validationLabel,
                progressBar,
                statusLabel,
                actionRow
            );
        reload();
        return panel;
    }

    /**
     * Refreshes device summary and validation state from the current active
     * connection. Safe to call before the panel is created.
     */
    public void reload() {
        if (panel == null) {
            return;
        }
        ConnectionContext context = currentContext();
        updateDeviceSummary(context);
        updateValidation(context);
    }

    private GridPane createDeviceGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(120);
        ColumnConstraints valueColumn = new ColumnConstraints();
        valueColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, valueColumn);

        connectionValueLabel = new Label("");
        deviceValueLabel = new Label("");
        firmwareValueLabel = new Label("");
        addRow(
            grid,
            0,
            I18n.t("settings.firmware.connection"),
            connectionValueLabel
        );
        addRow(grid, 1, I18n.t("settings.firmware.device"), deviceValueLabel);
        addRow(
            grid,
            2,
            I18n.t("settings.firmware.currentFirmware"),
            firmwareValueLabel
        );
        return grid;
    }

    private HBox createFileRow() {
        fileField = new TextField();
        fileField.setEditable(false);
        fileField.setPromptText(I18n.t("settings.firmware.file.placeholder"));
        HBox.setHgrow(fileField, Priority.ALWAYS);

        chooseFileButton = new Button(I18n.t("settings.firmware.file.choose"));
        chooseFileButton.setTooltip(
            new Tooltip(I18n.t("settings.firmware.file.choose.tooltip"))
        );
        chooseFileButton.setOnAction(e -> chooseFirmwareFile());

        return new HBox(8, fileField, chooseFileButton);
    }

    private HBox createModeRow() {
        Label modeLabel = new Label(I18n.t("settings.firmware.mode.label"));
        modeComboBox = new ComboBox<>(
            FXCollections.observableArrayList(FirmwareUpdateMode.values())
        );
        modeComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(FirmwareUpdateMode mode) {
                return mode != null ? I18n.t(mode.labelKey()) : "";
            }

            @Override
            public FirmwareUpdateMode fromString(String string) {
                return FirmwareUpdateMode.AUTO;
            }
        });
        modeComboBox.getSelectionModel().select(FirmwareUpdateMode.AUTO);
        modeComboBox
            .valueProperty()
            .addListener((obs, oldMode, newMode) -> updateValidation(currentContext()));
        HBox row = new HBox(8, modeLabel, modeComboBox);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void addRow(GridPane grid, int row, String label, Label valueLabel) {
        Label nameLabel = new Label(label);
        nameLabel.setStyle("-fx-opacity: 0.7;");
        valueLabel.setWrapText(true);
        grid.add(nameLabel, 0, row);
        grid.add(valueLabel, 1, row);
    }

    private void chooseFirmwareFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("settings.firmware.file.chooserTitle"));
        chooser
            .getExtensionFilters()
            .addAll(
                new FileChooser.ExtensionFilter(
                    I18n.t("settings.firmware.file.filter.all"),
                    "*.bin",
                    "*.uf2",
                    "*.zip"
                ),
                new FileChooser.ExtensionFilter(
                    I18n.t("settings.firmware.file.filter.bin"),
                    "*.bin"
                ),
                new FileChooser.ExtensionFilter(
                    I18n.t("settings.firmware.file.filter.uf2"),
                    "*.uf2"
                ),
                new FileChooser.ExtensionFilter(
                    I18n.t("settings.firmware.file.filter.zip"),
                    "*.zip"
                )
            );
        Window owner = panel.getScene() != null
            ? panel.getScene().getWindow()
            : null;
        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }
        selectedPath = file.toPath();
        fileField.setText(file.getAbsolutePath());
        updateValidation(currentContext());
    }

    private void requestStart() {
        ConnectionContext context = currentContext();
        updateValidation(context);
        if (lastValidation == null || !lastValidation.valid()) {
            statusLabel.setText(I18n.t("settings.firmware.status.fixErrors"));
            return;
        }
        FirmwareImage image = lastValidation.image();
        FirmwareUpdateMode requestedMode = modeComboBox.getValue();
        FirmwareUpdateMode resolvedMode = service.resolveMode(
            image,
            requestedMode,
            context.entry(),
            context.state()
        );
        FirmwareUpdateRequest request = new FirmwareUpdateRequest(
            context.entry(),
            context.state(),
            context.handler(),
            image,
            requestedMode
        );
        List<String> warnings = confirmationWarnings(
            lastValidation,
            requestedMode,
            resolvedMode
        );
        ModalPane pane = ModalPane.getInstance();
        if (pane == null) {
            start(request);
            return;
        }
        pane.show(
            FirmwareUpdateConfirmationPanelFactory.create(
                image,
                resolvedMode,
                context.entry().getName(),
                warnings,
                () -> start(request)
            )
        );
    }

    private List<String> confirmationWarnings(
        FirmwareValidationResult validation,
        FirmwareUpdateMode requestedMode,
        FirmwareUpdateMode resolvedMode
    ) {
        List<String> warnings = new ArrayList<>(validation.warnings());
        if (requestedMode == FirmwareUpdateMode.AUTO) {
            warnings.add(
                I18n.t(
                    "settings.firmware.confirm.autoResolved",
                    I18n.t(resolvedMode.labelKey())
                )
            );
        }
        warnings.add(I18n.t("settings.firmware.confirm.externalLoader"));
        return warnings;
    }

    private void start(FirmwareUpdateRequest request) {
        setControlsDisabled(true);
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        service
            .start(request, this::onProgress)
            .whenComplete((result, throwable) ->
                Platform.runLater(() -> finish(result, throwable))
            );
    }

    private void onProgress(FirmwareUpdateProgress progress) {
        Platform.runLater(() -> {
            statusLabel.setText(progress.message());
            if (progress.progress() >= 0) {
                progressBar.setProgress(progress.progress());
            }
        });
    }

    private void finish(FirmwareUpdateResult result, Throwable throwable) {
        setControlsDisabled(false);
        progressBar.setVisible(result != null && result.success());
        if (throwable != null) {
            statusLabel.setText(
                I18n.t("settings.firmware.status.failed", errorDetail(throwable))
            );
            Toast.show(
                Toast.Type.ERROR,
                I18n.t("settings.firmware.toast.failed")
            );
            updateValidation(currentContext());
            return;
        }
        if (result != null && result.success()) {
            statusLabel.setText(result.message());
            progressBar.setProgress(1.0);
            Toast.show(
                Toast.Type.SUCCESS,
                I18n.t("settings.firmware.toast.started")
            );
        } else if (result != null) {
            statusLabel.setText(result.message());
            Toast.show(
                Toast.Type.ERROR,
                I18n.t("settings.firmware.toast.failed")
            );
        }
        updateValidation(currentContext());
    }

    private void updateDeviceSummary(ConnectionContext context) {
        if (context.entry() == null) {
            connectionValueLabel.setText(I18n.t("settings.firmware.noConnection"));
            deviceValueLabel.setText("-");
            firmwareValueLabel.setText("-");
            return;
        }
        connectionValueLabel.setText(context.entry().getName());
        MeshProtos.DeviceMetadata metadata = context.state() != null
            ? context.state().getDeviceMetadata()
            : null;
        deviceValueLabel.setText(
            metadata != null && metadata.getHwModel() != MeshProtos.HardwareModel.UNSET
                ? metadata.getHwModel().name()
                : I18n.t("settings.firmware.unknown")
        );
        firmwareValueLabel.setText(
            metadata != null && !metadata.getFirmwareVersion().isBlank()
                ? metadata.getFirmwareVersion()
                : I18n.t("settings.firmware.unknown")
        );
    }

    private void updateValidation(ConnectionContext context) {
        FirmwareUpdateMode mode = modeComboBox != null
            ? modeComboBox.getValue()
            : FirmwareUpdateMode.AUTO;
        lastValidation = service.validate(
            selectedPath,
            mode,
            context.entry(),
            context.state()
        );
        renderValidation(lastValidation);
        startButton.setDisable(
            service.isRunning() ||
            lastValidation == null ||
            !lastValidation.valid()
        );
    }

    private void renderValidation(FirmwareValidationResult validation) {
        if (validation == null) {
            validationLabel.setText("");
            return;
        }
        if (!validation.errors().isEmpty()) {
            validationLabel.setStyle("-fx-text-fill: #c62828;");
            validationLabel.setText(String.join("\n", validation.errors()));
            return;
        }
        List<String> lines = new ArrayList<>();
        FirmwareImage image = validation.image();
        if (image != null) {
            lines.add(
                I18n.t(
                    "settings.firmware.validation.ready",
                    image.fileName(),
                    image.displaySize(),
                    image.sha256Hex()
                )
            );
        }
        lines.addAll(validation.warnings());
        validationLabel.setStyle("-fx-opacity: 0.75;");
        validationLabel.setText(String.join("\n", lines));
    }

    private ConnectionContext currentContext() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = Optional
            .ofNullable(manager.getSelectedConnectionEntry())
            .filter(ConnectionEntry::isConnected)
            .orElse(null);
        DeviceState state = entry != null
            ? manager.getDeviceState(entry.getId())
            : null;
        ProtocolHandler handler = entry != null
            ? manager.getProtocolHandler(entry.getId())
            : null;
        return new ConnectionContext(entry, state, handler);
    }

    private void setControlsDisabled(boolean disabled) {
        chooseFileButton.setDisable(disabled);
        modeComboBox.setDisable(disabled);
        startButton.setDisable(disabled);
    }

    private String errorDetail(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null
            ? current.getMessage()
            : current.getClass().getSimpleName();
    }

    private record ConnectionContext(
        ConnectionEntry entry,
        DeviceState state,
        ProtocolHandler handler
    ) {}
}
