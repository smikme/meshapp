package com.meshtastic.client.simple;

import com.meshtastic.client.connection.ble.BleDevice;
import com.meshtastic.client.connection.ble.BlePlatformFactory;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.model.SerialModemLineMode;
import com.meshtastic.client.rpc.RpcAccessKey;
import com.meshtastic.client.service.BleDeviceDiscoveryService;
import com.meshtastic.client.service.SerialPortDiscoveryService;
import com.meshtastic.client.service.SerialPortDiscoveryService.DiscoveredPort;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Side-panel form for creating and editing saved connection profiles.
 * <p>
 * The form owns only profile validation and object construction. Opening,
 * storing, and connecting profiles is handled by the parent connections screen.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class SimpleConnectionForm extends VBox {

    private static final String FIELD_ERROR_STYLE_CLASS = "connection-field-error";
    private static final int DEFAULT_TCP_PORT = 4403;
    private static final int DEFAULT_REMOTE_RPC_PORT = AppPreferences.DEFAULT_REMOTE_RPC_SERVER_PORT;
    private static final Pattern BLE_MAC_ADDRESS_PATTERN =
            Pattern.compile("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$");
    private static final Pattern BLE_UUID_PATTERN =
            Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final List<ConnectionType> BASE_CONNECTION_TYPES = List.of(
            ConnectionType.TCP,
            ConnectionType.SERIAL,
            ConnectionType.REMOTE_RPC);
    private static final List<ProtocolType> STREAM_PROTOCOLS = List.of(
            ProtocolType.MESHTASTIC,
            ProtocolType.MESHCORE_KISS,
            ProtocolType.MESHCORE_COMPANION);
    private static final List<ProtocolType> BLE_PROTOCOLS = List.of(
            ProtocolType.MESHTASTIC,
            ProtocolType.MESHCORE_COMPANION);
    private static final List<SerialModemLineMode> SERIAL_MODE_OPTIONS = List.of(
            SerialModemLineMode.AUTO,
            SerialModemLineMode.DTR_OFF_RTS_OFF,
            SerialModemLineMode.DTR_OFF_RTS_ON,
            SerialModemLineMode.DTR_ON_RTS_OFF,
            SerialModemLineMode.DTR_ON_RTS_ON);

    private final ConnectionEntry editingEntry;
    private final ComboBox<String> cmbType;
    private final ComboBox<String> cmbProtocol;
    private final TextField txtName;
    private final ValidationField nameValidation;
    private final CheckBox chkAutoconnect;

    // TCP fields
    private final VBox tcpFields;
    private final TextField txtHost;
    private final ValidationField hostValidation;
    private final TextField txtPort;
    private final ValidationField tcpPortValidation;
    private final VBox remoteRpcFields;
    private final TextField txtRemoteAccessKey;
    private final ValidationField remoteAccessKeyValidation;

    // Serial fields
    private final VBox serialFields;
    private final ComboBox<String> cmbPort;
    private final ValidationField serialPortValidation;
    private final Label lblSerialStatus;
    private final TextField txtBaudRate;
    private final ValidationField baudRateValidation;
    private final ComboBox<String> cmbSerialModemLines;

    // BLE fields
    private final VBox bleFields;
    private final ComboBox<String> cmbBleDevice;
    private final ValidationField bleDeviceValidation;
    private final Label lblBleStatus;

    private Consumer<ConnectionEntry> onSave;
    private String savedPortName;
    private String savedBleDeviceLabel;
    private ConnectionType lastConnectionType = ConnectionType.TCP;
    private List<DiscoveredPort> lastDiscoveredPorts = List.of();
    private final Consumer<List<DiscoveredPort>> discoveryListener = this::onPortsDiscovered;
    private final Consumer<List<BleDevice>> bleDiscoveryListener = this::onBleDevicesDiscovered;

    public SimpleConnectionForm() {
        this(null);
    }

    public SimpleConnectionForm(ConnectionEntry editingEntry) {
        this.editingEntry = editingEntry;

        setSpacing(8);
        setPadding(new Insets(20, 30, 20, 30));
        setPrefWidth(340);
        setMaxWidth(340);
        setMaxHeight(Double.MAX_VALUE);
        getStyleClass().add("modal-side-panel");

        Label title = new Label(I18n.t(editingEntry == null
                ? "connection.form.createTitle"
                : "connection.form.editTitle"));
        title.getStyleClass().add("dialog-title");

        // Connection type.
        cmbType = new ComboBox<>();
        cmbType.getItems().addAll(BASE_CONNECTION_TYPES.stream()
                .map(SimpleConnectionForm::labelForConnectionType)
                .toList());
        if (BlePlatformFactory.isSupported()
                || (editingEntry != null && editingEntry.getEffectiveType() == ConnectionType.BLE)) {
            cmbType.getItems().add(labelForConnectionType(ConnectionType.BLE));
        }
        cmbType.getSelectionModel().selectFirst();
        cmbType.setMaxWidth(Double.MAX_VALUE);

        // Protocol.
        cmbProtocol = new ComboBox<>();
        cmbProtocol.setMaxWidth(Double.MAX_VALUE);
        cmbProtocol.setOnAction(e -> {
            if (isBleMode()) {
                refreshBleDevices();
            }
        });
        updateProtocolOptions();
        cmbType.setOnAction(e -> {
            ConnectionType nextType = selectedConnectionType();
            applyDefaultPortForTypeChange(lastConnectionType, nextType);
            lastConnectionType = nextType;
            updateProtocolOptions();
            updateFieldVisibility();
        });

        // Display name.
        txtName = new TextField();
        txtName.setPromptText(I18n.t("connection.form.namePrompt"));
        nameValidation = createValidationField(txtName);

        chkAutoconnect = new CheckBox(I18n.t("connection.form.autoconnect"));
        chkAutoconnect.setTooltip(new Tooltip(I18n.t("connection.form.autoconnect.tooltip")));

        // --- TCP fields ---
        txtHost = new TextField();
        txtHost.setPromptText("192.168.1.1");
        hostValidation = createValidationField(txtHost);

        txtPort = new TextField("4403");
        tcpPortValidation = createValidationField(txtPort);

        tcpFields = new VBox(8);
        tcpFields.getChildren().addAll(
                new Label(I18n.t("connection.form.host")), txtHost, hostValidation.label(),
                new Label(I18n.t("connection.form.port")), txtPort, tcpPortValidation.label()
        );

        txtRemoteAccessKey = new TextField();
        txtRemoteAccessKey.setPromptText("mra1_...");
        remoteAccessKeyValidation = createValidationField(txtRemoteAccessKey);
        remoteRpcFields = new VBox(8);
        remoteRpcFields.getChildren().addAll(
                new Label(I18n.t("connection.form.remoteAccessKey")),
                txtRemoteAccessKey,
                remoteAccessKeyValidation.label()
        );
        remoteRpcFields.setVisible(false);
        remoteRpcFields.setManaged(false);

        // --- Serial fields ---
        cmbPort = new ComboBox<>();
        cmbPort.setEditable(true);
        cmbPort.setPromptText(I18n.t("connection.form.portPrompt"));
        cmbPort.setMaxWidth(Double.MAX_VALUE);
        cmbPort.setOnAction(e -> updateSerialAccessStatus());
        HBox.setHgrow(cmbPort, Priority.ALWAYS);

        Button btnRefresh = new Button("\u27F3");
        btnRefresh.setTooltip(new Tooltip(I18n.t("connection.form.refreshPorts")));
        btnRefresh.setOnAction(e -> refreshPorts());

        HBox portRow = new HBox(6, cmbPort, btnRefresh);
        portRow.setAlignment(Pos.CENTER_LEFT);

        serialPortValidation = createValidationField(cmbPort);

        lblSerialStatus = new Label();
        lblSerialStatus.getStyleClass().add("text-muted");
        lblSerialStatus.setWrapText(true);
        lblSerialStatus.setVisible(false);
        lblSerialStatus.setManaged(false);

        txtBaudRate = new TextField("115200");
        baudRateValidation = createValidationField(txtBaudRate);

        cmbSerialModemLines = new ComboBox<>();
        cmbSerialModemLines.getItems().addAll(SERIAL_MODE_OPTIONS.stream()
                .map(SimpleConnectionForm::labelForSerialModemLineMode)
                .toList());
        cmbSerialModemLines.setValue(labelForSerialModemLineMode(SerialModemLineMode.AUTO));
        cmbSerialModemLines.setMaxWidth(Double.MAX_VALUE);

        serialFields = new VBox(8);
        serialFields.getChildren().addAll(
                new Label(I18n.t("connection.form.devicePort")), portRow,
                serialPortValidation.label(),
                lblSerialStatus,
                new Label(I18n.t("connection.form.baudRate")), txtBaudRate, baudRateValidation.label(),
                new Label(I18n.t("connection.form.serialLines")), cmbSerialModemLines
        );
        serialFields.setVisible(false);
        serialFields.setManaged(false);

        // --- BLE fields ---
        cmbBleDevice = new ComboBox<>();
        cmbBleDevice.setEditable(true);
        cmbBleDevice.setPromptText(I18n.t("connection.form.bleDevicePrompt"));
        cmbBleDevice.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cmbBleDevice, Priority.ALWAYS);

        Button btnBleScan = new Button("\u27F3");
        btnBleScan.setTooltip(new Tooltip(I18n.t("connection.form.scanBleDevices")));
        btnBleScan.setOnAction(e -> refreshBleDevices());

        HBox bleDeviceRow = new HBox(6, cmbBleDevice, btnBleScan);
        bleDeviceRow.setAlignment(Pos.CENTER_LEFT);

        bleDeviceValidation = createValidationField(cmbBleDevice);

        lblBleStatus = new Label();
        lblBleStatus.getStyleClass().add("text-muted");

        bleFields = new VBox(8);
        bleFields.getChildren().addAll(
                new Label(I18n.t("connection.form.bleDevice")), bleDeviceRow,
                bleDeviceValidation.label(),
                lblBleStatus
        );
        bleFields.setVisible(false);
        bleFields.setManaged(false);

        // Buttons
        Button btnSave = new Button(I18n.t("common.save"));
        btnSave.getStyleClass().add("accent");
        btnSave.setOnAction(e -> doSave());

        Button btnCancel = new Button(I18n.t("common.cancel"));
        btnCancel.setOnAction(e -> doCancel());

        HBox buttons = new HBox(10, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        getChildren().addAll(
                title, new Separator(),
                new Label(I18n.t("connection.form.connectionType")), cmbType,
                new Label(I18n.t("connection.form.protocol")), cmbProtocol,
                new Label(I18n.t("connection.form.connectionName")), txtName, nameValidation.label(),
                chkAutoconnect,
                tcpFields,
                remoteRpcFields,
                serialFields,
                bleFields,
                buttons
        );

        setupValidationClearing();
        populateFromEntry(editingEntry);
    }

    public void setOnSave(Consumer<ConnectionEntry> onSave) {
        this.onSave = onSave;
    }

    private void doSave() {
        ConnectionEntry entry = getConnectionEntry();
        if (entry != null && onSave != null) {
            onSave.accept(entry);
        }
    }

    private void doCancel() {
        cleanup();
        var modalPane = com.meshtastic.client.modal.ModalPane.getInstance();
        if (modalPane != null) {
            modalPane.hide();
        }
    }

    /**
     * Builds a validated {@link ConnectionEntry} from the current form state.
     * <p>
     * On validation failure this method updates the visible field errors,
     * focuses the first invalid control, and returns {@code null} to preserve
     * the existing save-flow contract used by {@link #doSave()}.
     *
     * @return a fully configured connection profile, or {@code null} when the form is invalid
     */
    public ConnectionEntry getConnectionEntry() {
        clearValidationErrors();

        var validation = new ValidationState();
        var name = requiredText(
                validation,
                txtName,
                nameValidation,
                "connection.form.validation.nameRequired");
        var entry = switch (selectedConnectionType()) {
            case TCP -> buildTcpConnectionEntry(validation, name);
            case SERIAL -> buildSerialConnectionEntry(validation, name);
            case BLE -> buildBleConnectionEntry(validation, name);
            case REMOTE_RPC -> buildRemoteRpcConnectionEntry(validation, name);
        };

        if (validation.hasErrors()) {
            validation.focusFirstInvalid();
            return null;
        }
        return entry;
    }

    /**
     * Reads and validates the TCP-specific fields.
     */
    private ConnectionEntry buildTcpConnectionEntry(ValidationState validation, String name) {
        var host = requiredText(
                validation,
                txtHost,
                hostValidation,
                "connection.form.validation.hostRequired");
        var port = boundedInt(
                validation,
                txtPort,
                tcpPortValidation,
                "connection.form.validation.tcpPortRequired",
                "connection.form.validation.tcpPortInvalid",
                "connection.form.validation.tcpPortRange",
                1,
                65_535);

        return validation.hasErrors()
                ? null
                : configureCommonFields(new ConnectionEntry(name, host, port));
    }

    /**
     * Reads and validates direct MeshApp RPC fields.
     */
    private ConnectionEntry buildRemoteRpcConnectionEntry(ValidationState validation, String name) {
        var host = requiredText(
                validation,
                txtHost,
                hostValidation,
                "connection.form.validation.remoteHostRequired");
        var port = boundedInt(
                validation,
                txtPort,
                tcpPortValidation,
                "connection.form.validation.remotePortRequired",
                "connection.form.validation.remotePortInvalid",
                "connection.form.validation.remotePortRange",
                1,
                65_535);
        var accessKey = requiredText(
                validation,
                txtRemoteAccessKey,
                remoteAccessKeyValidation,
                "connection.form.validation.remoteAccessKeyRequired");

        if (!accessKey.isBlank()) {
            try {
                RpcAccessKey.parse(accessKey);
            } catch (IllegalArgumentException e) {
                validation.reject(remoteAccessKeyValidation,
                        I18n.t("connection.form.validation.remoteAccessKeyInvalid"));
            }
        }

        return validation.hasErrors()
                ? null
                : configureCommonFields(
                        ConnectionEntry.remoteRpc(name, host, port, accessKey),
                        ProtocolType.REMOTE_RPC);
    }

    /**
     * Reads and validates the Serial/USB-specific fields.
     */
    private ConnectionEntry buildSerialConnectionEntry(ValidationState validation, String name) {
        var selectedPort = requiredComboText(
                validation,
                cmbPort,
                serialPortValidation,
                "connection.form.validation.serialPortRequired");
        var portName = selectedPort.isBlank()
                ? ""
                : extractSystemPortName(selectedPort).trim();
        if (!selectedPort.isBlank() && portName.isBlank()) {
            validation.reject(serialPortValidation, I18n.t("connection.form.validation.serialPortRequired"));
        }

        var baudRate = boundedInt(
                validation,
                txtBaudRate,
                baudRateValidation,
                "connection.form.validation.baudRateRequired",
                "connection.form.validation.baudRateInvalid",
                "connection.form.validation.baudRateRange",
                1,
                Integer.MAX_VALUE);

        if (validation.hasErrors()) {
            return null;
        }

        var entry = configureCommonFields(new ConnectionEntry(name, portName, baudRate, ConnectionType.SERIAL));
        entry.setSerialModemLineMode(selectedSerialModemLineMode());
        return entry;
    }

    /**
     * Reads and validates the BLE-specific fields.
     */
    private ConnectionEntry buildBleConnectionEntry(ValidationState validation, String name) {
        var selectedDevice = requiredComboText(
                validation,
                cmbBleDevice,
                bleDeviceValidation,
                "connection.form.validation.bleDeviceRequired");
        if (selectedDevice.isBlank()) {
            return null;
        }

        var discoveredDevice = findBleDeviceByLabel(selectedDevice);
        if (discoveredDevice != null) {
            return configureCommonFields(
                    new ConnectionEntry(name, discoveredDevice.address(), discoveredDevice.displayName()),
                    selectedProtocolForDevice(discoveredDevice));
        }

        var address = extractBleAddress(selectedDevice);
        if (!isBleAddressCandidate(address)) {
            validation.reject(bleDeviceValidation, I18n.t("connection.form.validation.bleDeviceInvalid"));
            return null;
        }

        return configureCommonFields(new ConnectionEntry(name, address, extractBleDeviceName(selectedDevice)));
    }

    /**
     * Applies form-level options that are common to every transport type.
     */
    private ConnectionEntry configureCommonFields(ConnectionEntry entry) {
        return configureCommonFields(entry, selectedProtocolType());
    }

    /**
     * Applies common profile options while allowing BLE discovery to choose a protocol.
     */
    private ConnectionEntry configureCommonFields(ConnectionEntry entry, ProtocolType protocolType) {
        entry.setProtocol(protocolType);
        entry.setAutoconnect(chkAutoconnect.isSelected());
        return withEditingMetadata(entry);
    }

    /**
     * Creates the field binding used for visual validation feedback.
     */
    private static ValidationField createValidationField(Control control) {
        var label = new Label();
        label.getStyleClass().add("connection-validation-error");
        label.setWrapText(true);
        label.setVisible(false);
        label.setManaged(false);
        return new ValidationField(control, label);
    }

    private void setupValidationClearing() {
        clearOnTextChange(txtName, nameValidation);
        clearOnTextChange(txtHost, hostValidation);
        clearOnTextChange(txtPort, tcpPortValidation);
        clearOnTextChange(txtRemoteAccessKey, remoteAccessKeyValidation);
        clearOnTextChange(txtBaudRate, baudRateValidation);
        clearOnComboChange(cmbPort, serialPortValidation);
        clearOnComboChange(cmbBleDevice, bleDeviceValidation);
        cmbType.valueProperty().addListener((obs, oldValue, newValue) -> clearValidationErrors());
    }

    private static void clearOnTextChange(TextInputControl control, ValidationField validationField) {
        control.textProperty().addListener((obs, oldValue, newValue) -> validationField.clear());
    }

    private static void clearOnComboChange(ComboBox<String> comboBox, ValidationField validationField) {
        comboBox.valueProperty().addListener((obs, oldValue, newValue) -> validationField.clear());
        if (comboBox.getEditor() != null) {
            comboBox.getEditor().textProperty().addListener((obs, oldValue, newValue) ->
                    validationField.clear());
        }
    }

    private void clearValidationErrors() {
        validationFields().forEach(ValidationField::clear);
    }

    /**
     * Returns every field that can display a validation error.
     */
    private List<ValidationField> validationFields() {
        return List.of(
                nameValidation,
                hostValidation,
                tcpPortValidation,
                remoteAccessKeyValidation,
                serialPortValidation,
                baudRateValidation,
                bleDeviceValidation);
    }

    /**
     * Reads text from a text input and records a validation error when it is blank.
     *
     * @return trimmed text; the value may be blank when validation has rejected the field
     */
    private static String requiredText(
            ValidationState validation,
            TextInputControl control,
            ValidationField validationField,
            String messageKey) {
        var value = control.getText().trim();
        if (value.isBlank()) {
            validation.reject(validationField, I18n.t(messageKey));
        }
        return value;
    }

    /**
     * Reads text from an editable combo box or its selected value and requires it to be non-blank.
     *
     * @return trimmed combo-box text; the value may be blank when validation has rejected the field
     */
    private static String requiredComboText(
            ValidationState validation,
            ComboBox<String> comboBox,
            ValidationField validationField,
            String messageKey) {
        var value = comboText(comboBox);
        if (value.isBlank()) {
            validation.reject(validationField, I18n.t(messageKey));
        }
        return value;
    }

    /**
     * Parses an integer field and checks an inclusive range.
     *
     * @return parsed integer, or {@code 0} when validation has rejected the field
     */
    private static int boundedInt(
            ValidationState validation,
            TextInputControl control,
            ValidationField validationField,
            String requiredMessageKey,
            String invalidMessageKey,
            String rangeMessageKey,
            int min,
            int max) {
        var value = control.getText().trim();
        if (value.isBlank()) {
            validation.reject(validationField, I18n.t(requiredMessageKey));
            return 0;
        }

        try {
            var parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                validation.reject(validationField, I18n.t(rangeMessageKey));
                return 0;
            }
            return parsed;
        } catch (NumberFormatException e) {
            validation.reject(validationField, I18n.t(invalidMessageKey));
            return 0;
        }
    }

    /**
     * Gets the effective text of a combo box, using the editor for editable boxes.
     */
    private static String comboText(ComboBox<String> comboBox) {
        if (comboBox.isEditable() && comboBox.getEditor() != null) {
            return comboBox.getEditor().getText().trim();
        }
        var value = comboBox.getValue();
        return value == null ? "" : value.trim();
    }

    /**
     * Checks BLE address formats accepted by MeshApp profiles.
     */
    private static boolean isBleAddressCandidate(String address) {
        return address != null
                && (BLE_MAC_ADDRESS_PATTERN.matcher(address).matches()
                || BLE_UUID_PATTERN.matcher(address).matches());
    }

    /**
     * Pair of a form control and the validation label displayed directly below it.
     */
    private record ValidationField(Control control, Label label) {

        private void show(String message) {
            if (!control.getStyleClass().contains(FIELD_ERROR_STYLE_CLASS)) {
                control.getStyleClass().add(FIELD_ERROR_STYLE_CLASS);
            }
            label.setText(message);
            label.setVisible(true);
            label.setManaged(true);
        }

        private void clear() {
            control.getStyleClass().remove(FIELD_ERROR_STYLE_CLASS);
            label.setText("");
            label.setVisible(false);
            label.setManaged(false);
        }
    }

    /**
     * Tracks validation errors for one save attempt and focuses the first invalid control.
     */
    private static final class ValidationState {
        private Control firstInvalid;

        private void reject(ValidationField field, String message) {
            field.show(message);
            firstInvalid = firstInvalid == null ? field.control() : firstInvalid;
        }

        private boolean hasErrors() {
            return firstInvalid != null;
        }

        private void focusFirstInvalid() {
            firstInvalid.requestFocus();
            if (firstInvalid instanceof TextInputControl textInputControl) {
                textInputControl.selectAll();
                return;
            }
            if (firstInvalid instanceof ComboBox<?> comboBox
                    && comboBox.isEditable()
                    && comboBox.getEditor() != null) {
                comboBox.getEditor().selectAll();
            }
        }
    }

    public void formOpen() {
        txtName.requestFocus();
        SerialPortDiscoveryService.getInstance().addListener(discoveryListener);
        refreshPorts();
    }

    /** Unsubscribes from discovery services when the form closes. */
    public void cleanup() {
        SerialPortDiscoveryService.getInstance().removeListener(discoveryListener);
        BleDeviceDiscoveryService.getInstance().removeListener(bleDiscoveryListener);
        BleDeviceDiscoveryService.getInstance().stopScanning();
    }

    private boolean isSerialMode() {
        return selectedConnectionType() == ConnectionType.SERIAL;
    }

    private boolean isBleMode() {
        return selectedConnectionType() == ConnectionType.BLE;
    }

    private boolean isRemoteRpcMode() {
        return selectedConnectionType() == ConnectionType.REMOTE_RPC;
    }

    private void updateFieldVisibility() {
        boolean serial = isSerialMode();
        boolean ble = isBleMode();
        boolean remoteRpc = isRemoteRpcMode();
        boolean tcp = !serial && !ble;

        tcpFields.setVisible(tcp);
        tcpFields.setManaged(tcp);
        remoteRpcFields.setVisible(remoteRpc);
        remoteRpcFields.setManaged(remoteRpc);
        serialFields.setVisible(serial);
        serialFields.setManaged(serial);
        bleFields.setVisible(ble);
        bleFields.setManaged(ble);

        cmbProtocol.setDisable(remoteRpc);
        if (remoteRpc) {
            cmbProtocol.setValue(labelForProtocol(ProtocolType.REMOTE_RPC));
        }

        if (serial) {
            refreshPorts();
        }
        if (ble) {
            refreshBleDevices();
        }
    }

    private void populateFromEntry(ConnectionEntry entry) {
        if (entry == null) {
            updateFieldVisibility();
            return;
        }

        txtName.setText(valueOrEmpty(entry.getName()));
        chkAutoconnect.setSelected(entry.isAutoconnect());
        selectConnectionType(entry.getEffectiveType());
        updateProtocolOptions();
        String protocolLabel = labelForProtocol(entry.getEffectiveProtocol());
        if (cmbProtocol.getItems().contains(protocolLabel)) {
            cmbProtocol.setValue(protocolLabel);
        }

        switch (entry.getEffectiveType()) {
            case TCP -> {
                txtHost.setText(valueOrEmpty(entry.getHost()));
                txtPort.setText(String.valueOf(entry.getPort() > 0 ? entry.getPort() : DEFAULT_TCP_PORT));
            }
            case REMOTE_RPC -> {
                txtHost.setText(valueOrEmpty(entry.getHost()));
                txtPort.setText(String.valueOf(entry.getPort() > 0
                        ? entry.getPort()
                        : DEFAULT_REMOTE_RPC_PORT));
                txtRemoteAccessKey.setText(valueOrEmpty(entry.getRemoteAccessKey()));
            }
            case SERIAL -> {
                savedPortName = entry.getPortName();
                if (savedPortName != null && !savedPortName.isBlank()) {
                    cmbPort.setValue(savedPortName);
                }
                txtBaudRate.setText(String.valueOf(entry.getBaudRate() > 0 ? entry.getBaudRate() : 115200));
                cmbSerialModemLines.setValue(labelForSerialModemLineMode(
                        entry.getEffectiveSerialModemLineMode()));
            }
            case BLE -> {
                savedBleDeviceLabel = savedBleDeviceLabel(entry);
                if (savedBleDeviceLabel != null) {
                    cmbBleDevice.getItems().add(savedBleDeviceLabel);
                    cmbBleDevice.setValue(savedBleDeviceLabel);
                }
            }
        }

        updateFieldVisibility();
    }

    private void selectConnectionType(ConnectionType type) {
        String label = labelForConnectionType(type);
        if (type == ConnectionType.BLE && !cmbType.getItems().contains(label)) {
            cmbType.getItems().add(label);
        }
        cmbType.setValue(label);
        lastConnectionType = type != null ? type : ConnectionType.TCP;
    }

    private void applyDefaultPortForTypeChange(ConnectionType previousType, ConnectionType nextType) {
        if (previousType == nextType || txtPort == null) {
            return;
        }
        String currentPort = txtPort.getText() == null ? "" : txtPort.getText().trim();
        if (nextType == ConnectionType.REMOTE_RPC && Integer.toString(DEFAULT_TCP_PORT).equals(currentPort)) {
            txtPort.setText(Integer.toString(DEFAULT_REMOTE_RPC_PORT));
            return;
        }
        if ((nextType == ConnectionType.TCP || nextType == ConnectionType.SERIAL)
                && Integer.toString(DEFAULT_REMOTE_RPC_PORT).equals(currentPort)) {
            txtPort.setText(Integer.toString(DEFAULT_TCP_PORT));
        }
    }

    private void updateProtocolOptions() {
        ProtocolType previous = selectedProtocolType();
        cmbProtocol.getItems().clear();
        List<ProtocolType> protocolOptions = isRemoteRpcMode()
                ? List.of(ProtocolType.REMOTE_RPC)
                : isBleMode() ? BLE_PROTOCOLS : STREAM_PROTOCOLS;
        cmbProtocol.getItems().addAll(protocolOptions.stream()
                .map(SimpleConnectionForm::labelForProtocol)
                .toList());
        String previousLabel = labelForProtocol(previous);
        if (cmbProtocol.getItems().contains(previousLabel)) {
            cmbProtocol.setValue(previousLabel);
        } else {
            cmbProtocol.setValue(labelForProtocol(isRemoteRpcMode()
                    ? ProtocolType.REMOTE_RPC
                    : ProtocolType.MESHTASTIC));
        }
    }

    private void refreshPorts() {
        List<DiscoveredPort> ports = SerialPortDiscoveryService.getInstance().scanNow();
        populatePortCombo(ports);
    }

    private void refreshBleDevices() {
        lblBleStatus.setText(I18n.t("connection.ble.scanning"));
        BleDeviceDiscoveryService discovery = BleDeviceDiscoveryService.getInstance();
        discovery.setScanProfile(BleProtocolProfile.forProtocol(selectedProtocolType()));
        discovery.addListener(bleDiscoveryListener);
        discovery.startScanning();
        if (!discovery.isScanning()) {
            String errorMessage = discovery.getLastErrorMessage();
            lblBleStatus.setText(errorMessage == null || errorMessage.isBlank()
                    ? I18n.t("connection.ble.scanNotStarted")
                    : errorMessage);
            return;
        }

        // Show devices that were already discovered.
        List<BleDevice> devices = discovery.getDiscoveredDevices();
        if (!devices.isEmpty()) {
            populateBleDeviceCombo(devices);
        }
    }

    private void onPortsDiscovered(List<DiscoveredPort> ports) {
        Platform.runLater(() -> populatePortCombo(ports));
    }

    private void onBleDevicesDiscovered(List<BleDevice> devices) {
        Platform.runLater(() -> populateBleDeviceCombo(devices));
    }

    private void populatePortCombo(List<DiscoveredPort> ports) {
        lastDiscoveredPorts = List.copyOf(ports);
        String previousSelection = cmbPort.getValue();
        cmbPort.getItems().clear();

        boolean savedPortDiscovered = false;
        for (DiscoveredPort port : ports) {
            String label = port.descriptivePortName() + " (" + port.systemPortName() + ")";
            if (!port.accessible()) {
                label += " !";
            } else if (port.likelyMeshtastic()) {
                label += " \u2713";
            }
            if (savedPortName != null && savedPortName.equals(port.systemPortName())) {
                savedPortDiscovered = true;
            }
            cmbPort.getItems().add(label);
        }
        if (savedPortName != null && !savedPortName.isBlank() && !savedPortDiscovered) {
            cmbPort.getItems().add(savedPortName);
        }

        // Restore the previous selection.
        if (previousSelection != null) {
            String prevSysName = extractSystemPortName(previousSelection);
            for (String item : cmbPort.getItems()) {
                if (item.contains(prevSysName)) {
                    cmbPort.setValue(item);
                    break;
                }
            }
        }

        // Auto-select the first likely Meshtastic port.
        if (cmbPort.getValue() == null && !ports.isEmpty()) {
            for (int i = 0; i < ports.size(); i++) {
                if (ports.get(i).likelyMeshtastic()) {
                    cmbPort.getSelectionModel().select(i);
                    break;
                }
            }
        }
        updateSerialAccessStatus();
    }

    private void updateSerialAccessStatus() {
        if (!isSerialMode()) {
            clearSerialAccessStatus();
            return;
        }
        DiscoveredPort selectedPort = findSelectedDiscoveredPort();
        if (selectedPort != null && !selectedPort.accessible()) {
            showSerialAccessStatus(selectedPort.accessWarning());
            return;
        }
        clearSerialAccessStatus();
    }

    private DiscoveredPort findSelectedDiscoveredPort() {
        String selected = cmbPort.getValue();
        if (selected == null || selected.isBlank()) {
            return null;
        }
        String selectedSystemName = extractSystemPortName(selected);
        for (DiscoveredPort port : lastDiscoveredPorts) {
            if (port.systemPortName().equals(selectedSystemName)) {
                return port;
            }
        }
        return null;
    }

    private void showSerialAccessStatus(String message) {
        lblSerialStatus.setText(message == null || message.isBlank()
                ? I18n.t("connection.serial.noAccessSelected")
                : message);
        lblSerialStatus.setStyle("-fx-text-fill: #B45309;");
        lblSerialStatus.setVisible(true);
        lblSerialStatus.setManaged(true);
    }

    private void clearSerialAccessStatus() {
        lblSerialStatus.setText("");
        lblSerialStatus.setVisible(false);
        lblSerialStatus.setManaged(false);
    }

    private void populateBleDeviceCombo(List<BleDevice> devices) {
        String previousSelection = cmbBleDevice.getValue();
        cmbBleDevice.getItems().clear();

        if (savedBleDeviceLabel != null) {
            cmbBleDevice.getItems().add(savedBleDeviceLabel);
        }

        for (BleDevice device : devices) {
            String label = bleDeviceLabel(device);
            if (sameBleAddress(savedBleDeviceLabel, device.address())) {
                continue;
            }
            cmbBleDevice.getItems().add(label);
        }

        // Restore the previous selection.
        if (previousSelection != null) {
            for (String item : cmbBleDevice.getItems()) {
                if (sameBleSelection(previousSelection, item)) {
                    cmbBleDevice.setValue(item);
                    break;
                }
            }
        }

        // Auto-select the first device.
        if (cmbBleDevice.getValue() == null && !devices.isEmpty()) {
            cmbBleDevice.getSelectionModel().selectFirst();
        }

        int count = devices.size();
        BleDeviceDiscoveryService discovery = BleDeviceDiscoveryService.getInstance();
        String errorMessage = discovery.getLastErrorMessage();
        if (!discovery.isScanning() && errorMessage != null && !errorMessage.isBlank()) {
            lblBleStatus.setText(errorMessage);
        } else if (count == 0) {
            lblBleStatus.setText(discovery.isScanning()
                    ? I18n.t("connection.ble.scanning")
                    : I18n.t("connection.ble.noDevices"));
        } else {
            lblBleStatus.setText(I18n.t("connection.ble.devicesFound", count));
        }
    }

    /**
     * Finds a {@link BleDevice} by the display string shown in the combo box.
     * Format: {@code "DeviceName (-65 dBm)"}.
     */
    private BleDevice findBleDeviceByLabel(String label) {
        List<BleDevice> devices = BleDeviceDiscoveryService.getInstance().getDiscoveredDevices();
        String selectedAddress = extractBleAddress(label);
        for (BleDevice device : devices) {
            if (selectedAddress != null && selectedAddress.equalsIgnoreCase(device.address())) {
                return device;
            }
        }
        // Fallback to the label prefix because RSSI may have changed.
        for (BleDevice device : devices) {
            if (label.startsWith(device.displayName())) {
                return device;
            }
        }
        return null;
    }

    private ConnectionEntry withEditingMetadata(ConnectionEntry entry) {
        if (editingEntry != null) {
            entry.setId(editingEntry.getId());
            entry.setNodeId(editingEntry.getNodeId());
        }
        return entry;
    }

    /**
     * Extracts the system port name from a formatted display string.
     * Format: {@code "CP210x USB to UART Bridge (cu.usbserial-1234) OK"}.
     * Result: {@code "cu.usbserial-1234"}.
     */
    private String extractSystemPortName(String formatted) {
        int start = formatted.lastIndexOf('(');
        int end = formatted.lastIndexOf(')');
        if (start >= 0 && end > start) {
            return formatted.substring(start + 1, end);
        }
        return formatted;
    }

    private static String savedBleDeviceLabel(ConnectionEntry entry) {
        if (entry.getBleAddress() == null || entry.getBleAddress().isBlank()) {
            return null;
        }
        String name = entry.getBleDeviceName() != null && !entry.getBleDeviceName().isBlank()
                ? entry.getBleDeviceName()
                : entry.getBleAddress();
        return name + " (" + entry.getBleAddress() + ")";
    }

    private static String bleDeviceLabel(BleDevice device) {
        return device.displayName() + " (" + device.address() + ", " + device.rssi() + " dBm)";
    }

    private static boolean sameBleSelection(String left, String right) {
        String leftAddress = extractBleAddress(left);
        String rightAddress = extractBleAddress(right);
        if (leftAddress != null && rightAddress != null) {
            return leftAddress.equalsIgnoreCase(rightAddress);
        }
        return left != null && left.equals(right);
    }

    private static boolean sameBleAddress(String label, String address) {
        String labelAddress = extractBleAddress(label);
        return labelAddress != null && address != null && labelAddress.equalsIgnoreCase(address);
    }

    private static String extractBleAddress(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        int start = label.lastIndexOf('(');
        int end = label.lastIndexOf(')');
        if (start >= 0 && end > start) {
            String address = label.substring(start + 1, end);
            int comma = address.indexOf(',');
            if (comma >= 0) {
                address = address.substring(0, comma);
            }
            return address.trim();
        }
        return label.trim();
    }

    private static String extractBleDeviceName(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        int start = label.lastIndexOf(" (");
        if (start > 0) {
            return label.substring(0, start).trim();
        }
        return label.trim();
    }

    private static String valueOrEmpty(String value) {
        return value != null ? value : "";
    }

    private ProtocolType selectedProtocolForDevice(BleDevice device) {
        return selectedProtocolType();
    }

    private ProtocolType selectedProtocolType() {
        String value = cmbProtocol == null ? null : cmbProtocol.getValue();
        if (value == null || value.isBlank()) {
            return ProtocolType.MESHTASTIC;
        }
        for (ProtocolType protocolType : ProtocolType.values()) {
            if (labelForProtocol(protocolType).equals(value)) {
                return protocolType;
            }
        }
        return ProtocolType.MESHTASTIC;
    }

    private SerialModemLineMode selectedSerialModemLineMode() {
        String value = cmbSerialModemLines == null ? null : cmbSerialModemLines.getValue();
        if (value == null || value.isBlank()) {
            return SerialModemLineMode.AUTO;
        }
        for (SerialModemLineMode mode : SerialModemLineMode.values()) {
            if (labelForSerialModemLineMode(mode).equals(value)) {
                return mode;
            }
        }
        return SerialModemLineMode.AUTO;
    }

    private ConnectionType selectedConnectionType() {
        String value = cmbType == null ? null : cmbType.getValue();
        if (value == null || value.isBlank()) {
            return ConnectionType.TCP;
        }
        for (ConnectionType type : ConnectionType.values()) {
            if (labelForConnectionType(type).equals(value)) {
                return type;
            }
        }
        return ConnectionType.TCP;
    }

    private static String labelForConnectionType(ConnectionType type) {
        if (type == null) {
            return I18n.t("connection.type.tcp");
        }
        return switch (type) {
            case TCP -> I18n.t("connection.type.tcp");
            case SERIAL -> I18n.t("connection.type.serial");
            case BLE -> I18n.t("connection.type.ble");
            case REMOTE_RPC -> I18n.t("connection.type.remoteRpc");
        };
    }

    private static String labelForProtocol(ProtocolType protocolType) {
        if (protocolType == null) {
            return I18n.t("connection.protocol.meshtastic");
        }
        return switch (protocolType) {
            case MESHTASTIC -> I18n.t("connection.protocol.meshtastic");
            case MESHCORE_KISS -> I18n.t("connection.protocol.meshcoreKiss");
            case MESHCORE_COMPANION -> I18n.t("connection.protocol.meshcoreCompanion");
            case REMOTE_RPC -> I18n.t("connection.protocol.remoteRpc");
        };
    }

    private static String labelForSerialModemLineMode(SerialModemLineMode mode) {
        if (mode == null) {
            return I18n.t("connection.serialLine.auto");
        }
        return switch (mode) {
            case AUTO -> I18n.t("connection.serialLine.auto");
            case DTR_OFF_RTS_OFF -> I18n.t("connection.serialLine.dtrOffRtsOff");
            case DTR_OFF_RTS_ON -> I18n.t("connection.serialLine.dtrOffRtsOn");
            case DTR_ON_RTS_OFF -> I18n.t("connection.serialLine.dtrOnRtsOff");
            case DTR_ON_RTS_ON -> I18n.t("connection.serialLine.dtrOnRtsOn");
        };
    }
}
