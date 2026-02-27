package com.meshtastic.client.simple;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.service.SerialPortDiscoveryService;
import com.meshtastic.client.service.SerialPortDiscoveryService.DiscoveredPort;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

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

    private Consumer<ConnectionEntry> onSave;
    private final Consumer<List<DiscoveredPort>> discoveryListener = this::onPortsDiscovered;

    public SimpleConnectionForm() {
        setSpacing(8);
        setPadding(new Insets(20, 30, 20, 30));
        setPrefWidth(340);
        setMaxWidth(340);
        setMaxHeight(Double.MAX_VALUE);
        getStyleClass().add("modal-side-panel");

        Label title = new Label("Новое подключение");
        title.setFont(Font.font("Roboto", FontWeight.BOLD, 15));

        // Тип подключения
        cmbType = new ComboBox<>();
        cmbType.getItems().addAll("TCP", "Serial (USB / Bluetooth)");
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

        if (isSerialMode()) {
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

    /** Отписка от discovery-сервиса. Вызывается при закрытии формы. */
    public void cleanup() {
        SerialPortDiscoveryService.getInstance().removeListener(discoveryListener);
    }

    private boolean isSerialMode() {
        return cmbType.getSelectionModel().getSelectedIndex() == 1;
    }

    private void updateFieldVisibility() {
        boolean serial = isSerialMode();
        tcpFields.setVisible(!serial);
        tcpFields.setManaged(!serial);
        serialFields.setVisible(serial);
        serialFields.setManaged(serial);

        if (serial) {
            refreshPorts();
        }
    }

    private void refreshPorts() {
        List<DiscoveredPort> ports = SerialPortDiscoveryService.getInstance().scanNow();
        populatePortCombo(ports);
    }

    private void onPortsDiscovered(List<DiscoveredPort> ports) {
        Platform.runLater(() -> populatePortCombo(ports));
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
