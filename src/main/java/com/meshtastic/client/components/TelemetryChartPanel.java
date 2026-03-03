package com.meshtastic.client.components;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.service.NodeCacheService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Переиспользуемый компонент: AreaChart телеметрии + панель фильтра по периоду.
 * <p>
 * Используется в FormDashboard (телеметрия локальной ноды) и в FormNodes (телеметрия выбранной ноды).
 * Жизненный цикл: {@link #bind(DeviceState, int)} / {@link #unbind()}.
 */
public class TelemetryChartPanel extends VBox {

    private static final DateTimeFormatter AXIS_FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    // Периоды фильтрации (секунды)
    private static final long PERIOD_1H  = 3600;
    private static final long PERIOD_6H  = 6L * 3600;
    private static final long PERIOD_12H = 12L * 3600;
    private static final long PERIOD_24H = 24L * 3600;
    private static final long PERIOD_48H = 48L * 3600;
    private static final long PERIOD_1W  = 7L * 86400;
    private static final long PERIOD_MAX = 0; // без ограничений

    /** Максимальное количество точек на графике для плавного отображения */
    private static final int MAX_CHART_POINTS = 100;

    /** Диапазон LiPo аккумулятора для нормализации напряжения в % (0–100) */
    private static final float VOLTAGE_MIN = 3.0f;
    private static final float VOLTAGE_MAX = 4.2f;

    private final AreaChart<Number, Number> chart;
    private ToggleGroup periodGroup;
    private long selectedPeriodSeconds = PERIOD_6H;

    private DeviceState state;
    private String nodeId;
    private final Runnable telemetryListener = () -> Platform.runLater(this::refresh);

    /** Callback, вызываемый после обновления данных (для синхронизации таблицы логов) */
    private Runnable onDataRefreshed;

    /** Последние отфильтрованные записи (для использования в таблице логов) */
    private List<TelemetryEntry> filteredEntries = Collections.emptyList();

    public TelemetryChartPanel() {
        setSpacing(6);

        // --- График ---
        chart = createChart();
        chart.setPrefHeight(250);
        chart.setMinHeight(150);
        VBox.setVgrow(chart, Priority.ALWAYS);

        // --- Фильтр периода ---
        HBox periodBar = createPeriodBar();

        getChildren().addAll(chart, periodBar);
    }

    // ==================== Публичный API ====================

    /**
     * Устанавливает callback, вызываемый после каждого обновления данных графика.
     * Используется FormDashboard для синхронизации таблицы логов.
     */
    public void setOnDataRefreshed(Runnable callback) {
        this.onDataRefreshed = callback;
    }

    /**
     * Привязать компонент к состоянию устройства и идентификатору ноды.
     * Подписывается на обновления телеметрии и загружает данные.
     * Если уже привязан к тому же state и nodeId — просто обновляет данные без переподписки.
     */
    public void bind(DeviceState state, String nodeId) {
        if (this.state == state && Objects.equals(this.nodeId, nodeId)) {
            // Уже привязан к тем же данным — просто обновить
            refresh();
            return;
        }

        unbind();
        this.state = state;
        this.nodeId = nodeId;

        if (this.state != null) {
            this.state.addTelemetryListener(telemetryListener);
        }
        refresh();
    }

    /**
     * Отвязать от текущего DeviceState и очистить график.
     */
    public void unbind() {
        if (this.state != null) {
            this.state.removeTelemetryListener(telemetryListener);
        }
        this.state = null;
        this.nodeId = null;
        this.filteredEntries = Collections.emptyList();
        chart.getData().clear();
        chart.setTitle(null);
        ((NumberAxis) chart.getXAxis()).setAutoRanging(true);
    }

    /**
     * Возвращает последние отфильтрованные записи телеметрии.
     * Используется FormDashboard для таблицы логов.
     */
    public List<TelemetryEntry> getFilteredEntries() {
        return filteredEntries;
    }

    // ==================== Обновление данных ====================

    private void refresh() {
        if (state == null || nodeId == null) {
            chart.getData().clear();
            chart.setTitle("Нет подключения");
            ((NumberAxis) chart.getXAxis()).setAutoRanging(true);
            filteredEntries = Collections.emptyList();
            return;
        }

        // Вся фильтрация (нода, период, нулевые артефакты, будущие даты) — в SQL
        long now = System.currentTimeMillis() / 1000;
        long sinceEpoch = selectedPeriodSeconds > 0 ? now - selectedPeriodSeconds : 0;
        long maxTs = now + 300; // допуск 5 мин на рассинхронизацию часов

        filteredEntries = NodeCacheService.getInstance()
                .loadTelemetryForNode(nodeId, sinceEpoch, maxTs);

        updateChart(filteredEntries);

        if (onDataRefreshed != null) {
            onDataRefreshed.run();
        }
    }

    // ==================== График ====================

    private AreaChart<Number, Number> createChart() {
        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("");
        xAxis.setForceZeroInRange(false);
        xAxis.setAutoRanging(true);
        xAxis.setTickLabelFormatter(new NumberAxis.DefaultFormatter(xAxis) {
            @Override
            public String toString(Number object) {
                long epoch = object.longValue();
                if (epoch <= 0) { return ""; }
                return Instant.ofEpochSecond(epoch)
                        .atZone(ZoneId.systemDefault())
                        .format(AXIS_FMT);
            }
        });

        NumberAxis yAxis = new NumberAxis(0, 100, 25);
        yAxis.setLabel("");
        yAxis.setAutoRanging(false);

        AreaChart<Number, Number> areaChart = new AreaChart<>(xAxis, yAxis);
        areaChart.setAnimated(false);
        areaChart.setCreateSymbols(false);
        areaChart.setLegendVisible(true);
        areaChart.setTitle(null);
        return areaChart;
    }

    /**
     * Конвертирует напряжение (В) в процент шкалы 0–100 для отображения на общем графике.
     * LiPo: 3.0V = 0%, 4.2V = 100%.
     */
    private static double voltageToPercent(float voltage) {
        double pct = (voltage - VOLTAGE_MIN) / (VOLTAGE_MAX - VOLTAGE_MIN) * 100.0;
        return Math.max(0, Math.min(100, pct));
    }

    private void updateChart(List<TelemetryEntry> entries) {
        chart.getData().clear();

        NumberAxis xAxis = (NumberAxis) chart.getXAxis();

        if (entries.isEmpty()) {
            chart.setTitle("Нет данных");
            xAxis.setAutoRanging(true);
            return;
        }

        chart.setTitle(entries.size() + " записей");

        XYChart.Series<Number, Number> batterySeries = new XYChart.Series<>();
        batterySeries.setName("Battery %");
        XYChart.Series<Number, Number> voltageSeries = new XYChart.Series<>();
        voltageSeries.setName("Voltage В");
        XYChart.Series<Number, Number> chUtilSeries = new XYChart.Series<>();
        chUtilSeries.setName("ChUtil %");
        XYChart.Series<Number, Number> airUtilSeries = new XYChart.Series<>();
        airUtilSeries.setName("AirUtil %");

        long minTs = entries.getFirst().getTimestamp();
        long maxTs = entries.getLast().getTimestamp();

        if (entries.size() <= MAX_CHART_POINTS) {
            // Мало данных — показываем raw точки
            for (TelemetryEntry e : entries) {
                long ts = e.getTimestamp();
                if (e.getBatteryLevel() > 0 && e.getBatteryLevel() <= 100) {
                    batterySeries.getData().add(new XYChart.Data<>(ts, e.getBatteryLevel()));
                }
                if (e.getVoltage() > 0) {
                    voltageSeries.getData().add(new XYChart.Data<>(ts, voltageToPercent(e.getVoltage())));
                }
                chUtilSeries.getData().add(new XYChart.Data<>(ts, e.getChannelUtilization()));
                airUtilSeries.getData().add(new XYChart.Data<>(ts, e.getAirUtilTx()));
            }
        } else {
            // Много данных — агрегируем во временные бакеты (средние значения)
            long dataRange = maxTs - minTs;
            int bucketCount = MAX_CHART_POINTS;
            long bucketSize = Math.max(dataRange / bucketCount, 1);

            int idx = 0;
            for (int b = 0; b < bucketCount && idx < entries.size(); b++) {
                long bucketStart = minTs + (long) b * bucketSize;
                long bucketEnd = bucketStart + bucketSize;

                double sumBattery = 0;
                double sumVoltage = 0;
                double sumChUtil = 0;
                double sumAirUtil = 0;
                int countBattery = 0;
                int countVoltage = 0;
                int count = 0;

                while (idx < entries.size() && entries.get(idx).getTimestamp() < bucketEnd) {
                    TelemetryEntry e = entries.get(idx);
                    if (e.getBatteryLevel() > 0 && e.getBatteryLevel() <= 100) {
                        sumBattery += e.getBatteryLevel();
                        countBattery++;
                    }
                    if (e.getVoltage() > 0) {
                        sumVoltage += voltageToPercent(e.getVoltage());
                        countVoltage++;
                    }
                    sumChUtil += e.getChannelUtilization();
                    sumAirUtil += e.getAirUtilTx();
                    count++;
                    idx++;
                }

                if (count > 0) {
                    long bucketCenter = bucketStart + bucketSize / 2;
                    if (countBattery > 0) {
                        batterySeries.getData().add(new XYChart.Data<>(bucketCenter, sumBattery / countBattery));
                    }
                    if (countVoltage > 0) {
                        voltageSeries.getData().add(new XYChart.Data<>(bucketCenter, sumVoltage / countVoltage));
                    }
                    chUtilSeries.getData().add(new XYChart.Data<>(bucketCenter, sumChUtil / count));
                    airUtilSeries.getData().add(new XYChart.Data<>(bucketCenter, sumAirUtil / count));
                }
            }
        }

        // Фиксируем границы оси X по реальным данным
        xAxis.setAutoRanging(false);
        long range = maxTs - minTs;
        long padding = Math.max(range / 20, 60);
        xAxis.setLowerBound(minTs - padding);
        xAxis.setUpperBound(maxTs + padding);
        xAxis.setTickUnit(Math.max((range + 2 * padding) / 8, 60));

        // Порядок: Battery, Voltage, ChUtil, AirUtil
        if (!batterySeries.getData().isEmpty()) {
            chart.getData().add(batterySeries);
        }
        if (!voltageSeries.getData().isEmpty()) {
            chart.getData().add(voltageSeries);
        }
        chart.getData().add(chUtilSeries);
        chart.getData().add(airUtilSeries);
    }

    // ==================== Панель фильтра периода ====================

    private HBox createPeriodBar() {
        periodGroup = new ToggleGroup();

        ToggleButton btn1h  = periodButton("1ч",  PERIOD_1H);
        ToggleButton btn6h  = periodButton("6ч",  PERIOD_6H);
        ToggleButton btn12h = periodButton("12ч", PERIOD_12H);
        ToggleButton btn24h = periodButton("24ч", PERIOD_24H);
        ToggleButton btn48h = periodButton("48ч", PERIOD_48H);
        ToggleButton btn1w  = periodButton("1нед", PERIOD_1W);
        ToggleButton btnMax = periodButton("Всё",  PERIOD_MAX);

        btn6h.setSelected(true);

        HBox bar = new HBox(0, btn1h, btn6h, btn12h, btn24h, btn48h, btn1w, btnMax);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-border-color: -color-border-default; -fx-border-radius: 6; -fx-background-radius: 6;");
        return bar;
    }

    private ToggleButton periodButton(String text, long periodSeconds) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(periodGroup);
        btn.setUserData(periodSeconds);
        btn.setPadding(new Insets(6, 16, 6, 16));
        btn.setStyle("-fx-background-radius: 0; -fx-font-size: 12px;");
        btn.setOnAction(e -> {
            selectedPeriodSeconds = periodSeconds;
            refresh();
        });
        return btn;
    }
}
