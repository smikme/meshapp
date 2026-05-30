package com.meshtastic.client.simple;

import com.meshtastic.client.connection.ble.BleDevice;
import com.meshtastic.client.connection.ble.BlePlatformFactory;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.model.SerialModemLineMode;
import com.meshtastic.client.service.BleDeviceDiscoveryService;
import com.meshtastic.client.service.SerialPortDiscoveryService;
import com.meshtastic.client.service.SerialPortDiscoveryService.DiscoveredPort;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class SimpleConnectionForm extends VBox {

    private final ConnectionEntry editingEntry;
    private final ComboBox<String> cmbType;
    private final ComboBox<String> cmbProtocol;
    private final TextField txtName;
    private final CheckBox chkAutoconnect;

    // TCP fields
    private final VBox tcpFields;
    private final TextField txtHost;
    private final TextField txtPort;

    // Serial fields
    private final VBox serialFields;
    private final ComboBox<String> cmbPort;
    private final Label lblSerialStatus;
    private final TextField txtBaudRate;
    private final ComboBox<String> cmbSerialModemLines;

    // BLE fields
    private final VBox bleFields;
    private final ComboBox<String> cmbBleDevice;
    private final Label lblBleStatus;

    private Consumer<ConnectionEntry> onSave;
    private String savedPortName;
    private String savedBleDeviceLabel;
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

        Label title = new Label(editingEntry == null ? "Новое подключение" : "Редактирование подключения");
        title.getStyleClass().add("dialog-title");

        // Тип подключения
        cmbType = new ComboBox<>();
        cmbType.getItems().addAll("TCP", "Serial / USB");
        if (BlePlatformFactory.isSupported()
                || (editingEntry != null && editingEntry.getEffectiveType() == ConnectionType.BLE)) {
            cmbType.getItems().add("BLE");
        }
        cmbType.getSelectionModel().selectFirst();
        cmbType.setMaxWidth(Double.MAX_VALUE);

        // Протокол
        cmbProtocol = new ComboBox<>();
        cmbProtocol.setMaxWidth(Double.MAX_VALUE);
        cmbProtocol.setOnAction(e -> {
            if (isBleMode()) {
                refreshBleDevices();
            }
        });
        updateProtocolOptions();
        cmbType.setOnAction(e -> {
            updateProtocolOptions();
            updateFieldVisibility();
        });

        // Название
        txtName = new TextField();
        txtName.setPromptText("Например: Дом, Офис");

        chkAutoconnect = new CheckBox("Автоподключение");
        chkAutoconnect.setTooltip(new Tooltip("Подключаться автоматически при запуске приложения"));

        // --- TCP fields ---
        txtHost = new TextField();
        txtHost.setPromptText("192.168.1.1");

        txtPort = new TextField("4403");

        tcpFields = new VBox(8);
        tcpFields.getChildren().addAll(
                new Label("Хост"), txtHost,
                new Label("Порт"), txtPort
        );

        // --- Serial fields ---
        cmbPort = new ComboBox<>();
        cmbPort.setEditable(true);
        cmbPort.setPromptText("Выберите порт...");
        cmbPort.setMaxWidth(Double.MAX_VALUE);
        cmbPort.setOnAction(e -> updateSerialAccessStatus());
        HBox.setHgrow(cmbPort, Priority.ALWAYS);

        Button btnRefresh = new Button("\u27F3");
        btnRefresh.setTooltip(new Tooltip("Обновить список портов"));
        btnRefresh.setOnAction(e -> refreshPorts());

        HBox portRow = new HBox(6, cmbPort, btnRefresh);
        portRow.setAlignment(Pos.CENTER_LEFT);

        lblSerialStatus = new Label();
        lblSerialStatus.getStyleClass().add("text-muted");
        lblSerialStatus.setWrapText(true);
        lblSerialStatus.setVisible(false);
        lblSerialStatus.setManaged(false);

        txtBaudRate = new TextField("115200");

        cmbSerialModemLines = new ComboBox<>();
        cmbSerialModemLines.getItems().addAll(
                labelForSerialModemLineMode(SerialModemLineMode.AUTO),
                labelForSerialModemLineMode(SerialModemLineMode.DTR_OFF_RTS_OFF),
                labelForSerialModemLineMode(SerialModemLineMode.DTR_OFF_RTS_ON),
                labelForSerialModemLineMode(SerialModemLineMode.DTR_ON_RTS_OFF),
                labelForSerialModemLineMode(SerialModemLineMode.DTR_ON_RTS_ON)
        );
        cmbSerialModemLines.setValue(labelForSerialModemLineMode(SerialModemLineMode.AUTO));
        cmbSerialModemLines.setMaxWidth(Double.MAX_VALUE);

        serialFields = new VBox(8);
        serialFields.getChildren().addAll(
                new Label("Порт устройства"), portRow,
                lblSerialStatus,
                new Label("Скорость (бод)"), txtBaudRate,
                new Label("Линии DTR/RTS"), cmbSerialModemLines
        );
        serialFields.setVisible(false);
        serialFields.setManaged(false);

        // --- BLE fields ---
        cmbBleDevice = new ComboBox<>();
        cmbBleDevice.setEditable(true);
        cmbBleDevice.setPromptText("Поиск устройств...");
        cmbBleDevice.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cmbBleDevice, Priority.ALWAYS);

        Button btnBleScan = new Button("\u27F3");
        btnBleScan.setTooltip(new Tooltip("Сканировать BLE устройства"));
        btnBleScan.setOnAction(e -> refreshBleDevices());

        HBox bleDeviceRow = new HBox(6, cmbBleDevice, btnBleScan);
        bleDeviceRow.setAlignment(Pos.CENTER_LEFT);

        lblBleStatus = new Label();
        lblBleStatus.getStyleClass().add("text-muted");

        bleFields = new VBox(8);
        bleFields.getChildren().addAll(
                new Label("BLE устройство"), bleDeviceRow,
                lblBleStatus
        );
        bleFields.setVisible(false);
        bleFields.setManaged(false);

        // Buttons
        Button btnSave = new Button("Сохранить");
        btnSave.getStyleClass().add("accent");
        btnSave.setOnAction(e -> doSave());

        Button btnCancel = new Button("Отмена");
        btnCancel.setOnAction(e -> doCancel());

        HBox buttons = new HBox(10, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        getChildren().addAll(
                title, new Separator(),
                new Label("Тип подключения"), cmbType,
                new Label("Протокол"), cmbProtocol,
                new Label("Название"), txtName,
                chkAutoconnect,
                tcpFields,
                serialFields,
                bleFields,
                buttons
        );

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

    public ConnectionEntry getConnectionEntry() {
        String name = txtName.getText().trim();
        if (name.isEmpty()) {
            return null;
        }

        if (isBleMode()) {
            String selectedDevice = cmbBleDevice.getValue();
            if (selectedDevice == null || selectedDevice.isEmpty()) {
                return null;
            }
            BleDevice device = findBleDeviceByLabel(selectedDevice);
            if (device == null) {
                String address = extractBleAddress(selectedDevice);
                if (address == null || address.isBlank()) {
                    return null;
                }
                ConnectionEntry entry = new ConnectionEntry(name, address, extractBleDeviceName(selectedDevice));
                entry.setProtocol(selectedProtocolType());
                entry.setAutoconnect(chkAutoconnect.isSelected());
                return withEditingMetadata(entry);
            }
            ConnectionEntry entry = new ConnectionEntry(name, device.address(), device.displayName());
            entry.setProtocol(selectedProtocolForDevice(device));
            entry.setAutoconnect(chkAutoconnect.isSelected());
            return withEditingMetadata(entry);
        } else if (isSerialMode()) {
            String selectedPort = cmbPort.getValue();
            if (selectedPort == null || selectedPort.isEmpty()) {
                return null;
            }
            String portName = extractSystemPortName(selectedPort);
            int baudRate;
            try {
                baudRate = Integer.parseInt(txtBaudRate.getText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
            ConnectionEntry entry = new ConnectionEntry(name, portName, baudRate, ConnectionType.SERIAL);
            entry.setProtocol(selectedProtocolType());
            entry.setSerialModemLineMode(selectedSerialModemLineMode());
            entry.setAutoconnect(chkAutoconnect.isSelected());
            return withEditingMetadata(entry);
        } else {
            String host = txtHost.getText().trim();
            if (host.isEmpty()) {
                return null;
            }
            int port;
            try {
                port = Integer.parseInt(txtPort.getText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
            ConnectionEntry entry = new ConnectionEntry(name, host, port);
            entry.setProtocol(selectedProtocolType());
            entry.setAutoconnect(chkAutoconnect.isSelected());
            return withEditingMetadata(entry);
        }
    }

    public void formOpen() {
        txtName.requestFocus();
        SerialPortDiscoveryService.getInstance().addListener(discoveryListener);
        refreshPorts();
    }

    /** Отписка от discovery-сервисов. Вызывается при закрытии формы. */
    public void cleanup() {
        SerialPortDiscoveryService.getInstance().removeListener(discoveryListener);
        BleDeviceDiscoveryService.getInstance().removeListener(bleDiscoveryListener);
        BleDeviceDiscoveryService.getInstance().stopScanning();
    }

    private boolean isSerialMode() {
        return "Serial / USB".equals(cmbType.getSelectionModel().getSelectedItem());
    }

    private boolean isBleMode() {
        String selected = cmbType.getSelectionModel().getSelectedItem();
        return "BLE".equals(selected);
    }

    private void updateFieldVisibility() {
        boolean serial = isSerialMode();
        boolean ble = isBleMode();
        boolean tcp = !serial && !ble;

        tcpFields.setVisible(tcp);
        tcpFields.setManaged(tcp);
        serialFields.setVisible(serial);
        serialFields.setManaged(serial);
        bleFields.setVisible(ble);
        bleFields.setManaged(ble);

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
                txtPort.setText(String.valueOf(entry.getPort() > 0 ? entry.getPort() : 4403));
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
        if (type == ConnectionType.BLE && !cmbType.getItems().contains("BLE")) {
            cmbType.getItems().add("BLE");
        }
        cmbType.setValue(switch (type) {
            case SERIAL -> "Serial / USB";
            case BLE -> "BLE";
            case TCP -> "TCP";
        });
    }

    private void updateProtocolOptions() {
        ProtocolType previous = selectedProtocolType();
        cmbProtocol.getItems().clear();
        if (isBleMode()) {
            cmbProtocol.getItems().addAll("Meshtastic", "MeshCore Companion");
        } else {
            cmbProtocol.getItems().addAll("Meshtastic", "MeshCore KISS", "MeshCore Companion");
        }
        String previousLabel = labelForProtocol(previous);
        if (cmbProtocol.getItems().contains(previousLabel)) {
            cmbProtocol.setValue(previousLabel);
        } else {
            cmbProtocol.setValue("Meshtastic");
        }
    }

    private void refreshPorts() {
        List<DiscoveredPort> ports = SerialPortDiscoveryService.getInstance().scanNow();
        populatePortCombo(ports);
    }

    private void refreshBleDevices() {
        lblBleStatus.setText("Сканирование...");
        BleDeviceDiscoveryService discovery = BleDeviceDiscoveryService.getInstance();
        discovery.setScanProfile(BleProtocolProfile.forProtocol(selectedProtocolType()));
        discovery.addListener(bleDiscoveryListener);
        discovery.startScanning();
        if (!discovery.isScanning()) {
            String errorMessage = discovery.getLastErrorMessage();
            lblBleStatus.setText(errorMessage == null || errorMessage.isBlank()
                    ? "BLE сканирование не запущено."
                    : errorMessage);
            return;
        }

        // Показать уже найденные устройства
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

        // Восстановить предыдущий выбор
        if (previousSelection != null) {
            String prevSysName = extractSystemPortName(previousSelection);
            for (String item : cmbPort.getItems()) {
                if (item.contains(prevSysName)) {
                    cmbPort.setValue(item);
                    break;
                }
            }
        }

        // Автовыбор первого вероятного Meshtastic порта
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
                ? "Нет доступа к выбранному serial-порту."
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

        // Восстановить предыдущий выбор
        if (previousSelection != null) {
            for (String item : cmbBleDevice.getItems()) {
                if (sameBleSelection(previousSelection, item)) {
                    cmbBleDevice.setValue(item);
                    break;
                }
            }
        }

        // Автовыбор первого устройства
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
                    ? "Сканирование..."
                    : "Устройства не найдены. Убедитесь, что Bluetooth включён.");
        } else {
            lblBleStatus.setText("Найдено устройств: " + count);
        }
    }

    /**
     * Находит BleDevice по отображаемой строке в ComboBox.
     * Формат: "DeviceName (-65 dBm)"
     */
    private BleDevice findBleDeviceByLabel(String label) {
        List<BleDevice> devices = BleDeviceDiscoveryService.getInstance().getDiscoveredDevices();
        String selectedAddress = extractBleAddress(label);
        for (BleDevice device : devices) {
            if (selectedAddress != null && selectedAddress.equalsIgnoreCase(device.address())) {
                return device;
            }
        }
        // Fallback: по началу строки (RSSI мог измениться)
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
     * Извлекает системное имя порта из форматированной строки.
     * Формат: "CP210x USB to UART Bridge (cu.usbserial-1234) ✓"
     * Результат: "cu.usbserial-1234"
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
        return switch (value) {
            case "Meshtastic" -> ProtocolType.MESHTASTIC;
            case "MeshCore KISS" -> ProtocolType.MESHCORE_KISS;
            case "MeshCore Companion" -> ProtocolType.MESHCORE_COMPANION;
            default -> ProtocolType.MESHTASTIC;
        };
    }

    private SerialModemLineMode selectedSerialModemLineMode() {
        String value = cmbSerialModemLines == null ? null : cmbSerialModemLines.getValue();
        if (value == null || value.isBlank()) {
            return SerialModemLineMode.AUTO;
        }
        return switch (value) {
            case "DTR off, RTS off" -> SerialModemLineMode.DTR_OFF_RTS_OFF;
            case "DTR off, RTS on" -> SerialModemLineMode.DTR_OFF_RTS_ON;
            case "DTR on, RTS off" -> SerialModemLineMode.DTR_ON_RTS_OFF;
            case "DTR on, RTS on" -> SerialModemLineMode.DTR_ON_RTS_ON;
            default -> SerialModemLineMode.AUTO;
        };
    }

    private static String labelForProtocol(ProtocolType protocolType) {
        if (protocolType == null) {
            return "Meshtastic";
        }
        return switch (protocolType) {
            case MESHTASTIC -> "Meshtastic";
            case MESHCORE_KISS -> "MeshCore KISS";
            case MESHCORE_COMPANION -> "MeshCore Companion";
        };
    }

    private static String labelForSerialModemLineMode(SerialModemLineMode mode) {
        if (mode == null) {
            return "Auto";
        }
        return switch (mode) {
            case AUTO -> "Auto";
            case DTR_OFF_RTS_OFF -> "DTR off, RTS off";
            case DTR_OFF_RTS_ON -> "DTR off, RTS on";
            case DTR_ON_RTS_OFF -> "DTR on, RTS off";
            case DTR_ON_RTS_ON -> "DTR on, RTS on";
        };
    }
}
