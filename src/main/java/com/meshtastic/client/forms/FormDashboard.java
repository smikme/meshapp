package com.meshtastic.client.forms;

import com.meshtastic.client.components.TelemetryChartPanel;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.BatteryLevelEstimator;
import com.meshtastic.client.utils.SystemForm;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
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
 * Device telemetry dashboard.
 * <p>
 * Uses reusable {@link TelemetryChartPanel} for the chart and period filter.
 * The telemetry log table is shown below. Data comes from the H2 archive plus
 * live values from DeviceState.
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

    /** Full entry list, newest first, used for paged loading. */
    private List<TelemetryEntry> allEntries = Collections.emptyList();
    /** Number of rows already loaded into logData. */
    private int loadedCount;

    private DeviceState state;

    private final Runnable connectionListener = () -> Platform.runLater(this::rebindState);

    public FormDashboard() {
        init();
    }

    private void init() {
        VBox content = new VBox(0);
        content.setPadding(new Insets(10));

        // --- Telemetry chart, using the shared component ---
        chartPanel = new TelemetryChartPanel();
        chartPanel.setOnDataRefreshed(this::onChartDataRefreshed);
        VBox.setVgrow(chartPanel, Priority.ALWAYS);

        VBox chartTab = new VBox(chartPanel);
        chartTab.setPadding(new Insets(10, 0, 0, 0));
        VBox.setVgrow(chartPanel, Priority.ALWAYS);

        // --- Log counter ---
        logCountLabel = new Label(formatLogCount(0, 0));
        logCountLabel.getStyleClass().add("dashboard-log-count-label");
        logCountLabel.setPadding(new Insets(4, 0, 4, 2));

        // --- Log table ---
        logTable = createLogTable();
        VBox.setVgrow(logTable, Priority.ALWAYS);

        VBox dataTab = new VBox(4, logCountLabel, logTable);
        dataTab.setPadding(new Insets(10, 0, 0, 0));
        VBox.setVgrow(logTable, Priority.ALWAYS);

        // --- TabPane ---
        Tab tabCharts = new Tab(I18n.t("telemetry.tab.charts"), chartTab);
        tabCharts.setClosable(false);
        Tab tabData = new Tab(I18n.t("telemetry.tab.data"), dataTab);
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

    // ==================== DeviceState Binding ====================

    private void rebindState() {
        var mgr = ConnectionManager.getInstance();
        ConnectionEntry entry = mgr.getSelectedConnectionEntry();
        DeviceState newState = entry != null && entry.isConnected()
                ? mgr.getDeviceState(entry.getId())
                : null;

        this.state = newState;
        refresh();
    }

    // ==================== Data Refresh ====================

    private void refresh() {
        // No connection: do not show stale data.
        if (state == null || state.getMyNodeNum() == 0) {
            chartPanel.unbind();
            logData.clear();
            logCountLabel.setText(formatLogCount(0, 0));
            return;
        }

        // bind() checks internally; if state and nodeId are unchanged, it only refreshes data.
        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        String myNodeId = myNode != null ? myNode.getNodeId() : String.format("!%08x", state.getMyNodeNum());
        chartPanel.bind(state, myNodeId);
    }

    /** Called by TelemetryChartPanel after chart data is refreshed. */
    private void onChartDataRefreshed() {
        updateLogTable(chartPanel.getFilteredEntries());
    }

    // ==================== Log Table ====================

    @SuppressWarnings("unchecked")
    private TableView<TelemetryLogRow> createLogTable() {
        TableView<TelemetryLogRow> table = new TableView<>(logData);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(I18n.t("telemetry.table.empty")));

        TableColumn<TelemetryLogRow, String> colTime = new TableColumn<>(I18n.t("telemetry.column.time"));
        colTime.setCellValueFactory(new PropertyValueFactory<>("time"));
        colTime.setPrefWidth(150);

        TableColumn<TelemetryLogRow, String> colBattery = new TableColumn<>(I18n.t("telemetry.column.battery"));
        colBattery.setCellValueFactory(new PropertyValueFactory<>("battery"));
        colBattery.setPrefWidth(100);

        TableColumn<TelemetryLogRow, String> colVoltage = new TableColumn<>(I18n.t("telemetry.column.voltage"));
        colVoltage.setCellValueFactory(new PropertyValueFactory<>("voltage"));
        colVoltage.setPrefWidth(100);

        TableColumn<TelemetryLogRow, String> colChUtil = new TableColumn<>(I18n.t("telemetry.column.chUtil"));
        colChUtil.setCellValueFactory(new PropertyValueFactory<>("chUtil"));
        colChUtil.setPrefWidth(90);

        TableColumn<TelemetryLogRow, String> colAirUtil = new TableColumn<>(I18n.t("telemetry.column.airUtilTx"));
        colAirUtil.setCellValueFactory(new PropertyValueFactory<>("airUtil"));
        colAirUtil.setPrefWidth(90);

        TableColumn<TelemetryLogRow, String> colGoodRx = new TableColumn<>(I18n.t("telemetry.column.goodRx"));
        colGoodRx.setCellValueFactory(new PropertyValueFactory<>("goodRx"));
        colGoodRx.setPrefWidth(80);

        TableColumn<TelemetryLogRow, String> colBadRx = new TableColumn<>(I18n.t("telemetry.column.badRx"));
        colBadRx.setCellValueFactory(new PropertyValueFactory<>("badRx"));
        colBadRx.setPrefWidth(80);

        TableColumn<TelemetryLogRow, String> colDupeRx = new TableColumn<>(I18n.t("telemetry.column.dupeRx"));
        colDupeRx.setCellValueFactory(new PropertyValueFactory<>("dupeRx"));
        colDupeRx.setPrefWidth(80);

        TableColumn<TelemetryLogRow, String> colTx = new TableColumn<>(I18n.t("telemetry.column.tx"));
        colTx.setCellValueFactory(new PropertyValueFactory<>("tx"));
        colTx.setPrefWidth(60);

        TableColumn<TelemetryLogRow, String> colTxDropped = new TableColumn<>(I18n.t("telemetry.column.dropped"));
        colTxDropped.setCellValueFactory(new PropertyValueFactory<>("txDropped"));
        colTxDropped.setPrefWidth(70);

        TableColumn<TelemetryLogRow, String> colTxRelay = new TableColumn<>(I18n.t("telemetry.column.relayed"));
        colTxRelay.setCellValueFactory(new PropertyValueFactory<>("txRelay"));
        colTxRelay.setPrefWidth(70);

        TableColumn<TelemetryLogRow, String> colTxCanceled = new TableColumn<>(I18n.t("telemetry.column.canceled"));
        colTxCanceled.setCellValueFactory(new PropertyValueFactory<>("txCanceled"));
        colTxCanceled.setPrefWidth(70);

        TableColumn<TelemetryLogRow, String> colSnr = new TableColumn<>(I18n.t("telemetry.column.snr"));
        colSnr.setCellValueFactory(new PropertyValueFactory<>("snr"));
        colSnr.setPrefWidth(60);

        TableColumn<TelemetryLogRow, String> colRssi = new TableColumn<>(I18n.t("telemetry.column.rssi"));
        colRssi.setCellValueFactory(new PropertyValueFactory<>("rssi"));
        colRssi.setPrefWidth(60);

        TableColumn<TelemetryLogRow, String> colHops = new TableColumn<>(I18n.t("telemetry.column.hops"));
        colHops.setCellValueFactory(new PropertyValueFactory<>("hops"));
        colHops.setPrefWidth(60);

        TableColumn<TelemetryLogRow, String> colNode = new TableColumn<>(I18n.t("telemetry.column.node"));
        colNode.setCellValueFactory(cellData -> new ReadOnlyStringWrapper(
                cellData.getValue() != null ? cellData.getValue().getNode() : "?"));
        colNode.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null || item.isBlank() ? null : item);
            }
        });
        colNode.setPrefWidth(120);

        table.getColumns().addAll(colTime, colBattery, colVoltage, colChUtil, colAirUtil, colGoodRx, colBadRx, colDupeRx, colTx, colTxDropped, colTxRelay, colTxCanceled, colSnr, colRssi, colHops, colNode);

        // Lazy loading: load the next page when scrolling near the bottom.
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
        // Store the full list in reverse order, newest first.
        List<TelemetryEntry> reversed = new ArrayList<>(entries.size());
        for (int i = entries.size() - 1; i >= 0; i--) {
            reversed.add(entries.get(i));
        }
        allEntries = reversed;
        loadedCount = 0;
        logData.clear();
        logCountLabel.setText(formatLogCount(0, allEntries.size()));

        loadNextPage();
    }

    /** Loads the next PAGE_SIZE rows into the table. */
    private void loadNextPage() {
        if (loadedCount >= allEntries.size()) {
            logCountLabel.setText(formatLogCount(loadedCount, allEntries.size()));
            return;
        }

        int end = Math.min(loadedCount + PAGE_SIZE, allEntries.size());
        for (int i = loadedCount; i < end; i++) {
            logData.add(new TelemetryLogRow(allEntries.get(i), state));
        }
        loadedCount = end;

        logCountLabel.setText(formatLogCount(loadedCount, allEntries.size()));
    }

    private static String formatLogCount(int loaded, int total) {
        return total == 0
                ? I18n.t("telemetry.log.empty")
                : I18n.t("telemetry.log.count", loaded, total);
    }

    // ==================== Table Row Model ====================

    /**
     * One telemetry log table row.
     * JavaFX PropertyValueFactory requires public getters.
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
            if (BatteryLevelEstimator.hasBatteryPercent(bl, e.getVoltage())) {
                this.battery = BatteryLevelEstimator.effectivePercent(bl, e.getVoltage()) + "%";
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

            this.node = formatTelemetryNode(e, state);
        }

        private static String formatTelemetryNode(TelemetryEntry e, DeviceState state) {
            String nodeId = normalizeNodeId(e != null ? e.getNodeId() : null);
            String name = resolveNodeName(nodeId, state);
            if (name != null && nodeId != null) {
                return name + " (" + nodeId + ")";
            }
            if (name != null) {
                return name;
            }
            return nodeId != null ? nodeId : "?";
        }

        private static String resolveNodeName(String nodeId, DeviceState state) {
            if (state != null) {
                NodeData node = state.getNodeByNodeId(nodeId);
                String name = displayName(node);
                if (name != null) {
                    return name;
                }
                if (nodeId != null && nodeId.equals(state.getOwnerNodeId())
                        && state.getOwnerInfo() != null
                        && !state.getOwnerInfo().getLongName().isBlank()) {
                    return state.getOwnerInfo().getLongName();
                }
            }

            NodeData cached = NodeCacheService.getInstance().get(nodeId);
            String cachedName = displayName(cached);
            if (cachedName != null) {
                return cachedName;
            }
            return null;
        }

        private static String normalizeNodeId(String nodeId) {
            return nodeId == null || nodeId.isBlank() ? null : nodeId.trim();
        }

        private static String displayName(NodeData node) {
            if (node == null) {
                return null;
            }
            if (node.getLongName() != null && !node.getLongName().isEmpty()) {
                return node.getLongName();
            }
            return node.getShortName() != null && !node.getShortName().isEmpty()
                    ? node.getShortName()
                    : null;
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
