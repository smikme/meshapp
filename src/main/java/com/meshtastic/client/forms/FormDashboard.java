package com.meshtastic.client.forms;

import com.meshtastic.client.components.TelemetryChartPanel;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.SystemForm;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Дашборд телеметрии устройства.
 * <p>
 * Использует переиспользуемый компонент {@link TelemetryChartPanel} для графика и фильтра периода.
 * Таблица логов телеметрии внизу.
 * Данные загружаются из H2 (архив) + live из DeviceState.
 */
@SystemForm(name = "Статистика", description = "Телеметрия устройства")
public class FormDashboard extends Form {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm");

    private TelemetryChartPanel chartPanel;
    private Label logCountLabel;
    private TableView<TelemetryLogRow> logTable;
    private final ObservableList<TelemetryLogRow> logData = FXCollections.observableArrayList();

    private DeviceState state;

    private final Runnable connectionListener = () -> Platform.runLater(this::rebindState);

    public FormDashboard() {
        init();
    }

    private void init() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // --- График телеметрии (переиспользуемый компонент) ---
        chartPanel = new TelemetryChartPanel();
        chartPanel.setPrefHeight(320);
        chartPanel.setOnDataRefreshed(this::onChartDataRefreshed);

        // --- Счётчик логов ---
        logCountLabel = new Label("0 записей");
        logCountLabel.setStyle("-fx-opacity: 0.6; -fx-font-size: 12px;");
        logCountLabel.setPadding(new Insets(4, 0, 0, 2));

        // --- Таблица логов ---
        logTable = createLogTable();
        VBox.setVgrow(logTable, Priority.ALWAYS);

        content.getChildren().addAll(chartPanel, logCountLabel, logTable);
        getChildren().add(content);
    }

    @Override
    public void formInit() {
        ConnectionManager.getInstance().addListener(connectionListener);
        rebindState();
    }

    @Override
    public void formOpen() {
        rebindState();
    }

    @Override
    public void formRefresh() {
        refresh();
    }

    // ==================== Привязка к DeviceState ====================

    private void rebindState() {
        var mgr = ConnectionManager.getInstance();
        DeviceState newState = null;

        for (ConnectionEntry entry : mgr.getEntries()) {
            if (entry.isConnected()) {
                newState = mgr.getDeviceState(entry.getId());
                if (newState != null) { break; }
            }
        }

        this.state = newState;
        refresh();
    }

    // ==================== Обновление данных ====================

    private void refresh() {
        // Без подключения — не показываем данные
        if (state == null || state.getMyNodeNum() == 0) {
            chartPanel.unbind();
            logData.clear();
            logCountLabel.setText("0 записей");
            return;
        }

        // bind() внутри проверяет — если state и nodeId не изменились, просто обновит данные
        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        String myNodeId = myNode != null ? myNode.getNodeId() : String.format("!%08x", state.getMyNodeNum());
        chartPanel.bind(state, myNodeId);
    }

    /** Вызывается из TelemetryChartPanel после обновления данных графика */
    private void onChartDataRefreshed() {
        updateLogTable(chartPanel.getFilteredEntries());
    }

    // ==================== Таблица логов ====================

    @SuppressWarnings("unchecked")
    private TableView<TelemetryLogRow> createLogTable() {
        TableView<TelemetryLogRow> table = new TableView<>(logData);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Нет данных телеметрии"));

        TableColumn<TelemetryLogRow, String> colTime = new TableColumn<>("Время");
        colTime.setCellValueFactory(new PropertyValueFactory<>("time"));
        colTime.setPrefWidth(150);

        TableColumn<TelemetryLogRow, String> colBattery = new TableColumn<>("Батарея");
        colBattery.setCellValueFactory(new PropertyValueFactory<>("battery"));
        colBattery.setPrefWidth(100);

        TableColumn<TelemetryLogRow, String> colVoltage = new TableColumn<>("Напряжение");
        colVoltage.setCellValueFactory(new PropertyValueFactory<>("voltage"));
        colVoltage.setPrefWidth(100);

        TableColumn<TelemetryLogRow, String> colChUtil = new TableColumn<>("ChUtil");
        colChUtil.setCellValueFactory(new PropertyValueFactory<>("chUtil"));
        colChUtil.setPrefWidth(90);

        TableColumn<TelemetryLogRow, String> colAirUtil = new TableColumn<>("AirUtilTX");
        colAirUtil.setCellValueFactory(new PropertyValueFactory<>("airUtil"));
        colAirUtil.setPrefWidth(90);

        TableColumn<TelemetryLogRow, String> colNode = new TableColumn<>("Нода");
        colNode.setCellValueFactory(new PropertyValueFactory<>("node"));
        colNode.setPrefWidth(120);

        table.getColumns().addAll(colTime, colBattery, colVoltage, colChUtil, colAirUtil, colNode);
        return table;
    }

    private void updateLogTable(List<TelemetryEntry> entries) {
        logData.clear();

        // Отображаем в обратном порядке — новые сверху
        for (int i = entries.size() - 1; i >= 0; i--) {
            TelemetryEntry e = entries.get(i);
            logData.add(new TelemetryLogRow(e, state));
        }

        logCountLabel.setText(entries.size() + " записей");
    }

    // ==================== Модель строки таблицы ====================

    /**
     * Одна строка таблицы логов телеметрии.
     * JavaFX PropertyValueFactory требует публичных геттеров.
     */
    public static class TelemetryLogRow {
        private final String time;
        private final String battery;
        private final String voltage;
        private final String chUtil;
        private final String airUtil;
        private final String node;

        public TelemetryLogRow(TelemetryEntry e, DeviceState state) {
            this.time = e.getTimestamp() > 0
                    ? Instant.ofEpochSecond(e.getTimestamp())
                        .atZone(ZoneId.systemDefault())
                        .format(DATE_FMT)
                    : "—";

            int bl = e.getBatteryLevel();
            if (bl > 100) {
                this.battery = "PWD";
            } else if (bl > 0) {
                this.battery = bl + "%";
            } else {
                this.battery = "—";
            }

            this.voltage = e.getVoltage() > 0
                    ? String.format("%.2fV", e.getVoltage())
                    : "—";

            this.chUtil = String.format("%.1f%%", e.getChannelUtilization());
            this.airUtil = String.format("%.1f%%", e.getAirUtilTx());

            // Имя ноды
            String nodeName = e.getNodeId() != null ? e.getNodeId() : "?";
            if (state != null) {
                NodeData nd = state.getNodeByNodeId(e.getNodeId());
                if (nd != null && nd.getLongName() != null && !nd.getLongName().isEmpty()) {
                    nodeName = nd.getLongName();
                }
            }
            this.node = nodeName;
        }

        public String getTime()    { return time; }
        public String getBattery()  { return battery; }
        public String getVoltage()  { return voltage; }
        public String getChUtil()   { return chUtil; }
        public String getAirUtil()  { return airUtil; }
        public String getNode()     { return node; }
    }
}
