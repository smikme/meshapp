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
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Дашборд телеметрии устройства.
 * <p>
 * Использует переиспользуемый компонент {@link TelemetryChartPanel} для графика и фильтра периода.
 * Таблица логов телеметрии внизу.
 * Данные загружаются из H2 (архив) + live из DeviceState.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@SystemForm(name = "Статистика", description = "Телеметрия устройства")
public class FormDashboard extends Form {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm");
    private static final int PAGE_SIZE = 100;

    private TelemetryChartPanel chartPanel;
    private Label logCountLabel;
    private TableView<TelemetryLogRow> logTable;
    private final ObservableList<TelemetryLogRow> logData = FXCollections.observableArrayList();

    /** Полный список записей (новые первыми) для постраничной подгрузки */
    private List<TelemetryEntry> allEntries = Collections.emptyList();
    /** Сколько строк уже загружено в logData */
    private int loadedCount;

    private DeviceState state;

    private final Runnable connectionListener = () -> Platform.runLater(this::rebindState);

    public FormDashboard() {
        init();
    }

    private void init() {
        VBox content = new VBox(0);
        content.setPadding(new Insets(10));

        // --- График телеметрии (переиспользуемый компонент) ---
        chartPanel = new TelemetryChartPanel();
        chartPanel.setOnDataRefreshed(this::onChartDataRefreshed);
        VBox.setVgrow(chartPanel, Priority.ALWAYS);

        VBox chartTab = new VBox(chartPanel);
        chartTab.setPadding(new Insets(10, 0, 0, 0));
        VBox.setVgrow(chartPanel, Priority.ALWAYS);

        // --- Счётчик логов ---
        logCountLabel = new Label("0 записей");
        logCountLabel.getStyleClass().add("dashboard-log-count-label");
        logCountLabel.setPadding(new Insets(4, 0, 4, 2));

        // --- Таблица логов ---
        logTable = createLogTable();
        VBox.setVgrow(logTable, Priority.ALWAYS);

        VBox dataTab = new VBox(4, logCountLabel, logTable);
        dataTab.setPadding(new Insets(10, 0, 0, 0));
        VBox.setVgrow(logTable, Priority.ALWAYS);

        // --- TabPane ---
        Tab tabCharts = new Tab("Графики", chartTab);
        tabCharts.setClosable(false);
        Tab tabData = new Tab("Данные", dataTab);
        tabData.setClosable(false);

        TabPane tabPane = new TabPane(tabCharts, tabData);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        content.getChildren().add(tabPane);
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
        ConnectionEntry entry = mgr.getSelectedConnectionEntry();
        DeviceState newState = entry != null && entry.isConnected()
                ? mgr.getDeviceState(entry.getId())
                : null;

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

        TableColumn<TelemetryLogRow, String> colGoodRx = new TableColumn<>("Good RX");
        colGoodRx.setCellValueFactory(new PropertyValueFactory<>("goodRx"));
        colGoodRx.setPrefWidth(80);

        TableColumn<TelemetryLogRow, String> colBadRx = new TableColumn<>("Bad RX");
        colBadRx.setCellValueFactory(new PropertyValueFactory<>("badRx"));
        colBadRx.setPrefWidth(80);

        TableColumn<TelemetryLogRow, String> colDupeRx = new TableColumn<>("Dupe RX");
        colDupeRx.setCellValueFactory(new PropertyValueFactory<>("dupeRx"));
        colDupeRx.setPrefWidth(80);

        TableColumn<TelemetryLogRow, String> colTx = new TableColumn<>("TX");
        colTx.setCellValueFactory(new PropertyValueFactory<>("tx"));
        colTx.setPrefWidth(60);

        TableColumn<TelemetryLogRow, String> colTxDropped = new TableColumn<>("Dropped");
        colTxDropped.setCellValueFactory(new PropertyValueFactory<>("txDropped"));
        colTxDropped.setPrefWidth(70);

        TableColumn<TelemetryLogRow, String> colTxRelay = new TableColumn<>("Relayed");
        colTxRelay.setCellValueFactory(new PropertyValueFactory<>("txRelay"));
        colTxRelay.setPrefWidth(70);

        TableColumn<TelemetryLogRow, String> colTxCanceled = new TableColumn<>("Canceled");
        colTxCanceled.setCellValueFactory(new PropertyValueFactory<>("txCanceled"));
        colTxCanceled.setPrefWidth(70);

        TableColumn<TelemetryLogRow, String> colSnr = new TableColumn<>("SNR");
        colSnr.setCellValueFactory(new PropertyValueFactory<>("snr"));
        colSnr.setPrefWidth(60);

        TableColumn<TelemetryLogRow, String> colRssi = new TableColumn<>("RSSI");
        colRssi.setCellValueFactory(new PropertyValueFactory<>("rssi"));
        colRssi.setPrefWidth(60);

        TableColumn<TelemetryLogRow, String> colHops = new TableColumn<>("Hops");
        colHops.setCellValueFactory(new PropertyValueFactory<>("hops"));
        colHops.setPrefWidth(60);

        TableColumn<TelemetryLogRow, String> colNode = new TableColumn<>("Нода");
        colNode.setCellValueFactory(new PropertyValueFactory<>("node"));
        colNode.setPrefWidth(120);

        table.getColumns().addAll(colTime, colBattery, colVoltage, colChUtil, colAirUtil, colGoodRx, colBadRx, colDupeRx, colTx, colTxDropped, colTxRelay, colTxCanceled, colSnr, colRssi, colHops, colNode);

        // Lazy loading: подгружать следующую страницу при прокрутке до конца
        table.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin == null) return;
            for (Node node : table.lookupAll(".scroll-bar")) {
                if (node instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL) {
                    sb.valueProperty().addListener((v, oldVal, newVal) -> {
                        if (newVal.doubleValue() >= 0.95 && loadedCount < allEntries.size()) {
                            loadNextPage();
                        }
                    });
                    break;
                }
            }
        });

        return table;
    }

    private void updateLogTable(List<TelemetryEntry> entries) {
        // Сохраняем полный список в обратном порядке (новые сверху)
        List<TelemetryEntry> reversed = new ArrayList<>(entries.size());
        for (int i = entries.size() - 1; i >= 0; i--) {
            reversed.add(entries.get(i));
        }
        allEntries = reversed;
        loadedCount = 0;
        logData.clear();

        loadNextPage();
    }

    /** Подгружает следующие PAGE_SIZE строк в таблицу */
    private void loadNextPage() {
        if (loadedCount >= allEntries.size()) return;

        int end = Math.min(loadedCount + PAGE_SIZE, allEntries.size());
        for (int i = loadedCount; i < end; i++) {
            logData.add(new TelemetryLogRow(allEntries.get(i), state));
        }
        loadedCount = end;

        logCountLabel.setText(loadedCount + " / " + allEntries.size() + " записей");
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
        private final String goodRx;
        private final String badRx;
        private final String dupeRx;
        private final String tx;
        private final String txDropped;
        private final String txRelay;
        private final String txCanceled;
        private final String snr;
        private final String rssi;
        private final String hops;
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

            if (e.getNumPacketsRx() > 0) {
                double total = e.getNumPacketsRx();
                this.goodRx = String.format("%.1f%%", (total - e.getNumPacketsRxBad() - e.getNumRxDupe()) / total * 100.0);
                this.badRx = String.format("%.1f%%", e.getNumPacketsRxBad() / total * 100.0);
                this.dupeRx = String.format("%.1f%%", e.getNumRxDupe() / total * 100.0);
            } else {
                this.goodRx = "—";
                this.badRx = "—";
                this.dupeRx = "—";
            }

            this.tx = e.getNumPacketsTx() > 0 ? String.valueOf(e.getNumPacketsTx()) : "—";
            this.txDropped = e.getNumPacketsTx() > 0 ? String.valueOf(e.getNumTxDropped()) : "—";
            this.txRelay = e.getNumPacketsTx() > 0 ? String.valueOf(e.getNumTxRelay()) : "—";
            this.txCanceled = e.getNumPacketsTx() > 0 ? String.valueOf(e.getNumTxRelayCanceled()) : "—";

            this.snr = (e.getRxSnr() != 0) ? String.format("%.1f", e.getRxSnr()) : "—";
            this.rssi = (e.getRxRssi() != 0) ? String.valueOf(e.getRxRssi()) : "—";
            this.hops = e.hasValidHopData() ? String.valueOf(e.getHopsTraveled()) : "—";

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
        public String getGoodRx()   { return goodRx; }
        public String getBadRx()    { return badRx; }
        public String getDupeRx()   { return dupeRx; }
        public String getTx()       { return tx; }
        public String getTxDropped() { return txDropped; }
        public String getTxRelay()  { return txRelay; }
        public String getTxCanceled() { return txCanceled; }
        public String getSnr()      { return snr; }
        public String getRssi()     { return rssi; }
        public String getHops()     { return hops; }
        public String getNode()     { return node; }
    }
}
