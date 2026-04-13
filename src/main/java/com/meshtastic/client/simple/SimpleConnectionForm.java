package com.meshtastic.client.simple;

import com.meshtastic.client.connection.ble.BleDevice;
import com.meshtastic.client.connection.ble.BlePlatformFactory;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.service.BleDeviceDiscoveryService;
import com.meshtastic.client.service.SerialPortDiscoveryService;
import com.meshtastic.client.service.SerialPortDiscoveryService.DiscoveredPort;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
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

public class SimpleConnectionForm extends VBox {

    private final ComboBox<String> cmbType;
    private final TextField txtName;

    // TCP fields
    private final VBox tcpFields;
    private final TextField txtHost;
    private final TextField txtPort;

    // Serial fields
    private final VBox serialFields;
    private final ComboBox<String> cmbPort;
    private final TextField txtBaudRate;

    // BLE fields
    private final VBox bleFields;
    private final ComboBox<String> cmbBleDevice;
    private final Label lblBleStatus;

    private Consumer<ConnectionEntry> onSave;
    private final Consumer<List<DiscoveredPort>> discoveryListener = this::onPortsDiscovered;
    private final Consumer<List<BleDevice>> bleDiscoveryListener = this::onBleDevicesDiscovered;

    public SimpleConnectionForm() {
        setSpacing(8);
        setPadding(new Insets(20, 30, 20, 30));
        setPrefWidth(340);
        setMaxWidth(340);
        setMaxHeight(Double.MAX_VALUE);
        getStyleClass().add("modal-side-panel");

        Label title = new Label("Новое подключение");
        title.getStyleClass().add("dialog-title");

        // Тип подключения
        cmbType = new ComboBox<>();
        cmbType.getItems().addAll("TCP", "Serial / USB");
        if (BlePlatformFactory.isSupported()) {
            cmbType.getItems().add("BLE");
        }
        cmbType.getSelectionModel().selectFirst();
        cmbType.setMaxWidth(Double.MAX_VALUE);
        cmbType.setOnAction(e -> updateFieldVisibility());

        // Название
        txtName = new TextField();
        txtName.setPromptText("Например: Дом, Офис");

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
        cmbPort.setPromptText("Выберите порт...");
        cmbPort.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cmbPort, Priority.ALWAYS);

        Button btnRefresh = new Button("\u27F3");
        btnRefresh.setTooltip(new Tooltip("Обновить список портов"));
        btnRefresh.setOnAction(e -> refreshPorts());

        HBox portRow = new HBox(6, cmbPort, btnRefresh);
        portRow.setAlignment(Pos.CENTER_LEFT);

        txtBaudRate = new TextField("115200");

        serialFields = new VBox(8);
        serialFields.getChildren().addAll(
                new Label("Порт устройства"), portRow,
                new Label("Скорость (бод)"), txtBaudRate
        );
        serialFields.setVisible(false);
        serialFields.setManaged(false);

        // --- BLE fields ---
        cmbBleDevice = new ComboBox<>();
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
                new Label("Название"), txtName,
                tcpFields,
                serialFields,
                bleFields,
                buttons
        );
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
                return null;
            }
            return new ConnectionEntry(name, device.address(), device.displayName());
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
            return new ConnectionEntry(name, portName, baudRate, ConnectionType.SERIAL);
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
            return new ConnectionEntry(name, host, port);
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
        return cmbType.getSelectionModel().getSelectedIndex() == 1;
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

    private void refreshPorts() {
        List<DiscoveredPort> ports = SerialPortDiscoveryService.getInstance().scanNow();
        populatePortCombo(ports);
    }

    private void refreshBleDevices() {
        lblBleStatus.setText("Сканирование...");
        BleDeviceDiscoveryService discovery = BleDeviceDiscoveryService.getInstance();
        discovery.addListener(bleDiscoveryListener);
        discovery.startScanning();

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
        String previousSelection = cmbPort.getValue();
        cmbPort.getItems().clear();

        for (DiscoveredPort port : ports) {
            String label = port.descriptivePortName() + " (" + port.systemPortName() + ")";
            if (port.likelyMeshtastic()) {
                label += " \u2713";
            }
            cmbPort.getItems().add(label);
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
    }

    private void populateBleDeviceCombo(List<BleDevice> devices) {
        String previousSelection = cmbBleDevice.getValue();
        cmbBleDevice.getItems().clear();

        for (BleDevice device : devices) {
            String label = device.displayName() + " (" + device.rssi() + " dBm)";
            cmbBleDevice.getItems().add(label);
        }

        // Восстановить предыдущий выбор
        if (previousSelection != null) {
            for (String item : cmbBleDevice.getItems()) {
                if (item.startsWith(previousSelection.split(" \\(")[0])) {
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
        lblBleStatus.setText(count == 0
                ? "Устройства не найдены. Убедитесь, что Bluetooth включён."
                : "Найдено устройств: " + count);
    }

    /**
     * Находит BleDevice по отображаемой строке в ComboBox.
     * Формат: "DeviceName (-65 dBm)"
     */
    private BleDevice findBleDeviceByLabel(String label) {
        List<BleDevice> devices = BleDeviceDiscoveryService.getInstance().getDiscoveredDevices();
        for (BleDevice device : devices) {
            String expected = device.displayName() + " (" + device.rssi() + " dBm)";
            if (label.equals(expected)) {
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
}
