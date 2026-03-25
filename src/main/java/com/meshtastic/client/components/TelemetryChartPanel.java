package com.meshtastic.client.components;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.service.NodeCacheService;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;

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

    /** Период-заглушка для пустого режима, когда пользователь выбрал "Всё" */
    private static final long EMPTY_PERIOD_FALLBACK = PERIOD_24H;

    /** Диапазон LiPo аккумулятора для нормализации напряжения в % (0–100) */
    private static final float VOLTAGE_MIN = 3.0f;
    private static final float VOLTAGE_MAX = 4.2f;

    private static final String TITLE_BASIC = "Базовые метрики";
    private static final String TITLE_RX = "Статистика эфира";
    private static final String TITLE_RATE = "Скорость приема";
    private static final String TITLE_TX = "Скорость передачи";
    private static final String TITLE_QUALITY = "Качество соединения";
    private static final String TITLE_HOPS = "Прыжки";

    private final boolean basicOnly;
    private final AreaChart<Number, Number> chart;
    private final AreaChart<Number, Number> rxChart;
    private final AreaChart<Number, Number> rateChart;
    private final AreaChart<Number, Number> txChart;
    private final AreaChart<Number, Number> qualityChart;
    private final AreaChart<Number, Number> hopsChart;
    private ToggleGroup periodGroup;
    private long selectedPeriodSeconds = PERIOD_6H;

    private DeviceState state;
    private String nodeId;
    private final Runnable telemetryListener = () -> Platform.runLater(this::refresh);

    /** Callback, вызываемый после обновления данных (для синхронизации таблицы логов) */
    private Runnable onDataRefreshed;

    /** Последние отфильтрованные записи (для использования в таблице логов) */
    private List<TelemetryEntry> filteredEntries = Collections.emptyList();

    private record AxisRange(long lowerBound, long upperBound, long tickUnit) {}

    public TelemetryChartPanel() {
        this(false);
    }

    public TelemetryChartPanel(boolean basicOnly) {
        this.basicOnly = basicOnly;
        setSpacing(6);

        // --- Графики ---
        chart = createChart();
        chart.setPrefHeight(200);
        chart.setMinHeight(120);

        if (basicOnly) {
            rxChart = null;
            rateChart = null;
            txChart = null;
            qualityChart = null;
            hopsChart = null;

            StackPane chartWrap = wrapWithOverlay(chart);
            VBox.setVgrow(chartWrap, Priority.ALWAYS);

            HBox periodBar = createPeriodBar();
            getChildren().addAll(chartWrap, periodBar);
        } else {
            String gryCSS = getClass().getResource("/css/chart-green-red-yellow.css").toExternalForm();
            String txCSS = getClass().getResource("/css/chart-tx.css").toExternalForm();

            rxChart = createChart();
            rxChart.setPrefHeight(200);
            rxChart.setMinHeight(120);
            rxChart.getStylesheets().add(gryCSS);

            rateChart = createAutoRangeChart();
            rateChart.setPrefHeight(200);
            rateChart.setMinHeight(120);
            rateChart.getStylesheets().add(gryCSS);

            txChart = createAutoRangeChart();
            txChart.setPrefHeight(200);
            txChart.setMinHeight(120);
            txChart.getStylesheets().add(txCSS);

            String qualityCSS = getClass().getResource("/css/chart-quality.css").toExternalForm();
            qualityChart = createAutoRangeChart();
            qualityChart.setPrefHeight(200);
            qualityChart.setMinHeight(120);
            qualityChart.getStylesheets().add(qualityCSS);

            String hopsCSS = getClass().getResource("/css/chart-hops.css").toExternalForm();
            hopsChart = createAutoRangeChart();
            hopsChart.setPrefHeight(200);
            hopsChart.setMinHeight(120);
            hopsChart.getStylesheets().add(hopsCSS);

            StackPane chartWrap = wrapWithOverlay(chart);
            HBox.setHgrow(chartWrap, Priority.ALWAYS);
            StackPane rxWrap = wrapWithOverlay(rxChart);
            HBox.setHgrow(rxWrap, Priority.ALWAYS);
            StackPane rateWrap = wrapWithOverlay(rateChart);
            HBox.setHgrow(rateWrap, Priority.ALWAYS);
            StackPane txWrap = wrapWithOverlay(txChart);
            HBox.setHgrow(txWrap, Priority.ALWAYS);
            StackPane qualityWrap = wrapWithOverlay(qualityChart);
            HBox.setHgrow(qualityWrap, Priority.ALWAYS);
            StackPane hopsWrap = wrapWithOverlay(hopsChart);
            HBox.setHgrow(hopsWrap, Priority.ALWAYS);

            HBox topRow = new HBox(6, chartWrap, rxWrap);
            VBox.setVgrow(topRow, Priority.ALWAYS);

            HBox middleRow = new HBox(6, rateWrap, txWrap);
            VBox.setVgrow(middleRow, Priority.ALWAYS);

            HBox bottomRow = new HBox(6, qualityWrap, hopsWrap);
            VBox.setVgrow(bottomRow, Priority.ALWAYS);

            HBox periodBar = createPeriodBar();
            getChildren().addAll(topRow, middleRow, bottomRow, periodBar);
        }

        updateChart(Collections.emptyList(), Collections.emptyList());
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
        updateChart(Collections.emptyList(), Collections.emptyList());
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
            filteredEntries = Collections.emptyList();
            updateChart(filteredEntries, Collections.emptyList());
            return;
        }

        // Вся фильтрация (нода, период, нулевые артефакты, будущие даты) — в SQL
        long now = System.currentTimeMillis() / 1000;
        long sinceEpoch = selectedPeriodSeconds > 0 ? now - selectedPeriodSeconds : 0;
        long maxTs = now + 300; // допуск 5 мин на рассинхронизацию часов

        String ownerNodeId = state.getMyNodeNum() != 0
                ? String.format("!%08x", state.getMyNodeNum()) : "";

        filteredEntries = NodeCacheService.getInstance()
                .loadTelemetryForNode(nodeId, sinceEpoch, maxTs, ownerNodeId);

        List<TelemetryEntry> qualityEntries = basicOnly
                ? Collections.emptyList()
                : NodeCacheService.getInstance().loadTelemetryQuality(sinceEpoch, maxTs, ownerNodeId);

        updateChart(filteredEntries, qualityEntries);

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

    private AreaChart<Number, Number> createAutoRangeChart() {
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

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("");
        yAxis.setAutoRanging(true);
        yAxis.setForceZeroInRange(true);

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

    private static XYChart.Series<Number, Number> createSeries(String name) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(name);
        return series;
    }

    @SafeVarargs
    private static void setChartSeries(AreaChart<Number, Number> areaChart, String title,
                                       XYChart.Series<Number, Number>... series) {
        areaChart.setTitle(title);
        areaChart.getData().setAll(series);
    }

    private void setXAxisRange(NumberAxis axis, AxisRange range) {
        axis.setAutoRanging(false);
        axis.setLowerBound(range.lowerBound());
        axis.setUpperBound(range.upperBound());
        axis.setTickUnit(range.tickUnit());
    }

    private AxisRange createPlaceholderAxisRange() {
        long now = System.currentTimeMillis() / 1000;
        long visibleRange = selectedPeriodSeconds > 0 ? selectedPeriodSeconds : EMPTY_PERIOD_FALLBACK;
        long padding = Math.max(visibleRange / 20, 60);
        long totalRange = visibleRange + 2 * padding;
        return new AxisRange(now - visibleRange - padding, now + padding, Math.max(totalRange / 8, 60));
    }

    private AxisRange buildAxisRange(List<TelemetryEntry> entries, List<TelemetryEntry> qualityEntries) {
        long minTs = Long.MAX_VALUE;
        long maxTs = Long.MIN_VALUE;

        if (!entries.isEmpty()) {
            minTs = Math.min(minTs, entries.getFirst().getTimestamp());
            maxTs = Math.max(maxTs, entries.getLast().getTimestamp());
        }
        if (!qualityEntries.isEmpty()) {
            minTs = Math.min(minTs, qualityEntries.getFirst().getTimestamp());
            maxTs = Math.max(maxTs, qualityEntries.getLast().getTimestamp());
        }

        if (minTs == Long.MAX_VALUE || maxTs == Long.MIN_VALUE) {
            return createPlaceholderAxisRange();
        }

        long range = Math.max(maxTs - minTs, 1);
        long padding = Math.max(range / 20, 60);
        long totalRange = range + 2 * padding;
        return new AxisRange(minTs - padding, maxTs + padding, Math.max(totalRange / 8, 60));
    }

    private void updateChart(List<TelemetryEntry> entries, List<TelemetryEntry> qualityEntries) {
        chart.getData().clear();
        NumberAxis xAxis = (NumberAxis) chart.getXAxis();

        NumberAxis rxXAxis = null, rateXAxis = null, txXAxis = null, qualityXAxis = null, hopsXAxis = null;
        if (!basicOnly) {
            rxChart.getData().clear();
            rateChart.getData().clear();
            txChart.getData().clear();
            qualityChart.getData().clear();
            hopsChart.getData().clear();
            rxXAxis = (NumberAxis) rxChart.getXAxis();
            rateXAxis = (NumberAxis) rateChart.getXAxis();
            txXAxis = (NumberAxis) txChart.getXAxis();
            qualityXAxis = (NumberAxis) qualityChart.getXAxis();
            hopsXAxis = (NumberAxis) hopsChart.getXAxis();
        }

        XYChart.Series<Number, Number> batterySeries = createSeries("Battery %");
        XYChart.Series<Number, Number> voltageSeries = createSeries("Voltage В");
        XYChart.Series<Number, Number> chUtilSeries = createSeries("ChUtil %");
        XYChart.Series<Number, Number> airUtilSeries = createSeries("AirUtil %");
        XYChart.Series<Number, Number> goodRxSeries = null, badRxSeries = null, dupeRxSeries = null;
        XYChart.Series<Number, Number> pktRxSeries = null, pktBadSeries = null, pktDupeSeries = null;
        XYChart.Series<Number, Number> txSeries = null, txDroppedSeries = null, txRelaySeries = null, txRelayCanceledSeries = null;
        XYChart.Series<Number, Number> snrSeries = null, rssiSeries = null;
        XYChart.Series<Number, Number> avgHopsSeries = null, maxHopsSeries = null, minHopsSeries = null;

        if (!basicOnly) {
            goodRxSeries = createSeries("Good RX %");
            badRxSeries = createSeries("Bad RX %");
            dupeRxSeries = createSeries("Dupe RX %");

            pktRxSeries = createSeries("Packets Received");
            pktBadSeries = createSeries("Bad Packets");
            pktDupeSeries = createSeries("Duplicates");

            txSeries = createSeries("Packets Transmitted");
            txDroppedSeries = createSeries("Dropped");
            txRelaySeries = createSeries("Relayed");
            txRelayCanceledSeries = createSeries("Relay Canceled");

            snrSeries = createSeries("SNR (dB)");
            rssiSeries = createSeries("RSSI (dBm)");

            avgHopsSeries = createSeries("Среднее");
            maxHopsSeries = createSeries("Макс");
            minHopsSeries = createSeries("Мин");
        }

        // Предыдущие значения кумулятивных счётчиков для вычисления дельты
        int prevPktRx = -1, prevBadRx = -1, prevDupeRx = -1;
        int prevTx = -1, prevTxDropped = -1, prevTxRelay = -1, prevTxCanceled = -1;
        long minTs = entries.isEmpty() ? 0 : entries.getFirst().getTimestamp();
        long maxTs = entries.isEmpty() ? 0 : entries.getLast().getTimestamp();

        if (entries.size() <= MAX_CHART_POINTS) {
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
                if (!basicOnly && e.getNumPacketsRx() > 0) {
                    // Дельта (пакетов за интервал), а не кумулятивный счётчик
                    if (prevPktRx >= 0) {
                        int dRx = e.getNumPacketsRx() - prevPktRx;
                        int dBad = e.getNumPacketsRxBad() - prevBadRx;
                        int dDupe = e.getNumRxDupe() - prevDupeRx;
                        if (dRx < 0) { dRx = e.getNumPacketsRx(); dBad = e.getNumPacketsRxBad(); dDupe = e.getNumRxDupe(); }
                        pktRxSeries.getData().add(new XYChart.Data<>(ts, dRx));
                        pktBadSeries.getData().add(new XYChart.Data<>(ts, dBad));
                        pktDupeSeries.getData().add(new XYChart.Data<>(ts, dDupe));
                        // Проценты из дельт (реальное качество за интервал)
                        if (dRx > 0) {
                            goodRxSeries.getData().add(new XYChart.Data<>(ts, (dRx - dBad - dDupe) / (double) dRx * 100.0));
                            badRxSeries.getData().add(new XYChart.Data<>(ts, dBad / (double) dRx * 100.0));
                            dupeRxSeries.getData().add(new XYChart.Data<>(ts, dDupe / (double) dRx * 100.0));
                        }
                    }
                    prevPktRx = e.getNumPacketsRx();
                    prevBadRx = e.getNumPacketsRxBad();
                    prevDupeRx = e.getNumRxDupe();
                }
                if (!basicOnly && e.getNumPacketsTx() > 0) {
                    if (prevTx >= 0) {
                        int dTx = e.getNumPacketsTx() - prevTx;
                        int dDr = e.getNumTxDropped() - prevTxDropped;
                        int dRl = e.getNumTxRelay() - prevTxRelay;
                        int dCn = e.getNumTxRelayCanceled() - prevTxCanceled;
                        if (dTx < 0) { dTx = e.getNumPacketsTx(); dDr = e.getNumTxDropped(); dRl = e.getNumTxRelay(); dCn = e.getNumTxRelayCanceled(); }
                        txSeries.getData().add(new XYChart.Data<>(ts, dTx));
                        txDroppedSeries.getData().add(new XYChart.Data<>(ts, dDr));
                        txRelaySeries.getData().add(new XYChart.Data<>(ts, dRl));
                        txRelayCanceledSeries.getData().add(new XYChart.Data<>(ts, dCn));
                    }
                    prevTx = e.getNumPacketsTx();
                    prevTxDropped = e.getNumTxDropped();
                    prevTxRelay = e.getNumTxRelay();
                    prevTxCanceled = e.getNumTxRelayCanceled();
                }
            }
        } else {
            long dataRange = maxTs - minTs;
            int bucketCount = MAX_CHART_POINTS;
            long bucketSize = Math.max(dataRange / bucketCount, 1);

            int idx = 0;
            for (int b = 0; b < bucketCount && idx < entries.size(); b++) {
                long bucketStart = minTs + (long) b * bucketSize;
                long bucketEnd = bucketStart + bucketSize;

                double sumBattery = 0, sumVoltage = 0, sumChUtil = 0, sumAirUtil = 0;
                double sumDeltaRx = 0, sumDeltaBadRx = 0, sumDeltaDupeRx = 0;
                double sumDeltaTx = 0, sumDeltaTxDr = 0, sumDeltaTxRl = 0, sumDeltaTxCn = 0;
                int countBattery = 0, countVoltage = 0, count = 0;
                int countDeltaRx = 0, countDeltaTx = 0;

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
                    if (!basicOnly && e.getNumPacketsRx() > 0) {
                        if (prevPktRx >= 0) {
                            int dRx = e.getNumPacketsRx() - prevPktRx;
                            int dBad = e.getNumPacketsRxBad() - prevBadRx;
                            int dDupe = e.getNumRxDupe() - prevDupeRx;
                            if (dRx < 0) { dRx = e.getNumPacketsRx(); dBad = e.getNumPacketsRxBad(); dDupe = e.getNumRxDupe(); }
                            sumDeltaRx += dRx;
                            sumDeltaBadRx += dBad;
                            sumDeltaDupeRx += dDupe;
                            countDeltaRx++;
                        }
                        prevPktRx = e.getNumPacketsRx();
                        prevBadRx = e.getNumPacketsRxBad();
                        prevDupeRx = e.getNumRxDupe();
                    }
                    if (!basicOnly && e.getNumPacketsTx() > 0) {
                        if (prevTx >= 0) {
                            int dTx = e.getNumPacketsTx() - prevTx;
                            int dDr = e.getNumTxDropped() - prevTxDropped;
                            int dRl = e.getNumTxRelay() - prevTxRelay;
                            int dCn = e.getNumTxRelayCanceled() - prevTxCanceled;
                            if (dTx < 0) { dTx = e.getNumPacketsTx(); dDr = e.getNumTxDropped(); dRl = e.getNumTxRelay(); dCn = e.getNumTxRelayCanceled(); }
                            sumDeltaTx += dTx;
                            sumDeltaTxDr += dDr;
                            sumDeltaTxRl += dRl;
                            sumDeltaTxCn += dCn;
                            countDeltaTx++;
                        }
                        prevTx = e.getNumPacketsTx();
                        prevTxDropped = e.getNumTxDropped();
                        prevTxRelay = e.getNumTxRelay();
                        prevTxCanceled = e.getNumTxRelayCanceled();
                    }
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
                    if (countDeltaRx > 0 && sumDeltaRx > 0) {
                        goodRxSeries.getData().add(new XYChart.Data<>(bucketCenter, (sumDeltaRx - sumDeltaBadRx - sumDeltaDupeRx) / sumDeltaRx * 100.0));
                        badRxSeries.getData().add(new XYChart.Data<>(bucketCenter, sumDeltaBadRx / sumDeltaRx * 100.0));
                        dupeRxSeries.getData().add(new XYChart.Data<>(bucketCenter, sumDeltaDupeRx / sumDeltaRx * 100.0));
                    }
                    if (countDeltaRx > 0) {
                        pktRxSeries.getData().add(new XYChart.Data<>(bucketCenter, sumDeltaRx));
                        pktBadSeries.getData().add(new XYChart.Data<>(bucketCenter, sumDeltaBadRx));
                        pktDupeSeries.getData().add(new XYChart.Data<>(bucketCenter, sumDeltaDupeRx));
                    }
                    if (countDeltaTx > 0) {
                        txSeries.getData().add(new XYChart.Data<>(bucketCenter, sumDeltaTx));
                        txDroppedSeries.getData().add(new XYChart.Data<>(bucketCenter, sumDeltaTxDr));
                        txRelaySeries.getData().add(new XYChart.Data<>(bucketCenter, sumDeltaTxRl));
                        txRelayCanceledSeries.getData().add(new XYChart.Data<>(bucketCenter, sumDeltaTxCn));
                    }
                }
            }
        }

        // Заполняем серии quality/hops из данных по всем нодам
        if (!basicOnly && !qualityEntries.isEmpty()) {
            long qMinTs = qualityEntries.getFirst().getTimestamp();
            long qMaxTs = qualityEntries.getLast().getTimestamp();
            long qRange = qMaxTs - qMinTs;

            // SNR/RSSI — per-point если мало данных
            if (qualityEntries.size() <= MAX_CHART_POINTS) {
                for (TelemetryEntry e : qualityEntries) {
                    long ts = e.getTimestamp();
                    if (e.getRxSnr() != 0 || e.getRxRssi() != 0) {
                        snrSeries.getData().add(new XYChart.Data<>(ts, e.getRxSnr()));
                        rssiSeries.getData().add(new XYChart.Data<>(ts, e.getRxRssi()));
                    }
                }
            } else {
                long qBucketSize = Math.max(qRange / MAX_CHART_POINTS, 1);
                int qi = 0;
                for (int b = 0; b < MAX_CHART_POINTS && qi < qualityEntries.size(); b++) {
                    long bStart = qMinTs + (long) b * qBucketSize;
                    long bEnd = bStart + qBucketSize;
                    double sumSnr = 0, sumRssi = 0;
                    int cntQ = 0;
                    while (qi < qualityEntries.size() && qualityEntries.get(qi).getTimestamp() < bEnd) {
                        TelemetryEntry e = qualityEntries.get(qi);
                        if (e.getRxSnr() != 0 || e.getRxRssi() != 0) {
                            sumSnr += e.getRxSnr();
                            sumRssi += e.getRxRssi();
                            cntQ++;
                        }
                        qi++;
                    }
                    if (cntQ > 0) {
                        long bCenter = bStart + qBucketSize / 2;
                        snrSeries.getData().add(new XYChart.Data<>(bCenter, sumSnr / cntQ));
                        rssiSeries.getData().add(new XYChart.Data<>(bCenter, sumRssi / cntQ));
                    }
                }
            }

            // Hops — ВСЕГДА бакетируем, чтобы avg/max/min отличались
            // (каждая запись — одна нода с одним hop count; агрегация даёт разные значения)
            int hopBuckets = Math.min(30, Math.max(1, (int)(qRange / 900))); // ~15мин на бакет
            if (hopBuckets < 3) hopBuckets = Math.min(3, qualityEntries.size());
            long hopBucketSize = Math.max(qRange / hopBuckets, 1);

            int hi = 0;
            for (int b = 0; b < hopBuckets && hi < qualityEntries.size(); b++) {
                long bStart = qMinTs + (long) b * hopBucketSize;
                long bEnd = bStart + hopBucketSize;
                double sumHops = 0;
                int cntH = 0;
                int bMaxHops = Integer.MIN_VALUE, bMinHops = Integer.MAX_VALUE;

                while (hi < qualityEntries.size() && qualityEntries.get(hi).getTimestamp() < bEnd) {
                    TelemetryEntry e = qualityEntries.get(hi);
                    if (e.getHopStart() > 0) {
                        int hops = e.getHopsTraveled();
                        sumHops += hops;
                        bMaxHops = Math.max(bMaxHops, hops);
                        bMinHops = Math.min(bMinHops, hops);
                        cntH++;
                    }
                    hi++;
                }

                if (cntH > 0) {
                    long bCenter = bStart + hopBucketSize / 2;
                    avgHopsSeries.getData().add(new XYChart.Data<>(bCenter, sumHops / cntH));
                    maxHopsSeries.getData().add(new XYChart.Data<>(bCenter, bMaxHops));
                    minHopsSeries.getData().add(new XYChart.Data<>(bCenter, bMinHops));
                }
            }
        }

        AxisRange axisRange = buildAxisRange(entries, qualityEntries);

        setXAxisRange(xAxis, axisRange);
        setChartSeries(chart, TITLE_BASIC, batterySeries, voltageSeries, chUtilSeries, airUtilSeries);

        if (!basicOnly) {
            setXAxisRange(rxXAxis, axisRange);
            setXAxisRange(rateXAxis, axisRange);
            setXAxisRange(txXAxis, axisRange);
            setXAxisRange(qualityXAxis, axisRange);
            setXAxisRange(hopsXAxis, axisRange);

            setChartSeries(rxChart, TITLE_RX, goodRxSeries, badRxSeries, dupeRxSeries);
            setChartSeries(rateChart, TITLE_RATE, pktRxSeries, pktBadSeries, pktDupeSeries);
            setChartSeries(txChart, TITLE_TX, txSeries, txDroppedSeries, txRelaySeries, txRelayCanceledSeries);
            setChartSeries(qualityChart, TITLE_QUALITY, snrSeries, rssiSeries);
            setChartSeries(hopsChart, TITLE_HOPS, maxHopsSeries, minHopsSeries, avgHopsSeries);
        }
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

    // ==================== Интерактивный курсор ====================

    /**
     * Оборачивает AreaChart в StackPane и накладывает вертикальный курсор + label.
     */
    private StackPane wrapWithOverlay(AreaChart<Number, Number> areaChart) {
        Line crosshair = new Line();
        crosshair.setStyle("-fx-stroke: white; -fx-opacity: 0.6; -fx-stroke-width: 1;");
        crosshair.setMouseTransparent(true);
        crosshair.setVisible(false);
        crosshair.setManaged(false);

        Label valueLabel = new Label();
        valueLabel.setStyle("-fx-background-color: rgba(0,0,0,0.85); -fx-text-fill: white; "
                + "-fx-padding: 6; -fx-font-size: 11px; -fx-font-family: monospace; -fx-background-radius: 4;");
        valueLabel.setMouseTransparent(true);
        valueLabel.setVisible(false);
        valueLabel.setManaged(false);

        StackPane wrapper = new StackPane(areaChart, crosshair, valueLabel);
        StackPane.setAlignment(crosshair, Pos.TOP_LEFT);
        StackPane.setAlignment(valueLabel, Pos.TOP_LEFT);

        installCrosshair(areaChart, crosshair, valueLabel, wrapper);
        return wrapper;
    }

    /**
     * Устанавливает обработчики мыши на plot-area графика.
     * Используем Platform.runLater чтобы chart.lookup() работал после добавления в Scene Graph.
     */
    private void installCrosshair(AreaChart<Number, Number> areaChart,
                                  Line crosshair, Label valueLabel, StackPane wrapper) {
        areaChart.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) return;
            Platform.runLater(() -> {
                Node plotBg = areaChart.lookup(".chart-plot-background");
                if (!(plotBg instanceof Region plotArea)) return;

                Node chartContent = areaChart.lookup(".chart-content");
                if (chartContent == null) return;

                // Event filter перехватывает клики ДО дочерних элементов (area fill, line),
                // поэтому работает и на заполненной области графика
                chartContent.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
                    Point2D local = plotArea.sceneToLocal(event.getSceneX(), event.getSceneY());
                    if (plotArea.contains(local)) {
                        showCrosshair(local.getX(), areaChart, crosshair, valueLabel, wrapper, plotArea);
                    }
                });

                chartContent.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
                    Point2D local = plotArea.sceneToLocal(event.getSceneX(), event.getSceneY());
                    if (!plotArea.contains(local)) {
                        crosshair.setVisible(false);
                        valueLabel.setVisible(false);
                    }
                });
            });
        });
    }

    private void showCrosshair(double localX, AreaChart<Number, Number> areaChart,
                               Line crosshair, Label valueLabel, StackPane wrapper, Region plotArea) {
        boolean hasData = areaChart.getData().stream().anyMatch(series -> !series.getData().isEmpty());
        if (!hasData) {
            crosshair.setVisible(false);
            valueLabel.setVisible(false);
            return;
        }

        NumberAxis xAxis = (NumberAxis) areaChart.getXAxis();
        long epoch = xAxis.getValueForDisplay(localX).longValue();

        // Форматируем время
        String timeStr = Instant.ofEpochSecond(epoch)
                .atZone(ZoneId.systemDefault())
                .format(AXIS_FMT);

        StringBuilder sb = new StringBuilder(timeStr).append("\n");

        // Значения всех серий в ближайшей точке
        for (XYChart.Series<Number, Number> series : areaChart.getData()) {
            XYChart.Data<Number, Number> nearest = findNearest(series, epoch);
            if (nearest != null) {
                double val = nearest.getYValue().doubleValue();
                sb.append(series.getName()).append(": ").append(String.format("%.1f", val)).append("\n");
            }
        }

        valueLabel.setText(sb.toString().strip());

        // Позиция курсора: конвертируем из координат plotArea в координаты wrapper
        Bounds plotBounds = plotArea.localToScene(plotArea.getBoundsInLocal());
        Bounds wrapperBounds = wrapper.sceneToLocal(plotBounds);

        double xInWrapper = wrapperBounds.getMinX() + localX;
        double plotTop = wrapperBounds.getMinY();
        double plotHeight = wrapperBounds.getHeight();

        crosshair.setStartX(xInWrapper);
        crosshair.setStartY(plotTop);
        crosshair.setEndX(xInWrapper);
        crosshair.setEndY(plotTop + plotHeight);
        crosshair.setVisible(true);

        // Label позиция — справа от курсора, но если близко к правому краю — слева
        double labelX = xInWrapper + 8;
        double wrapperWidth = wrapper.getWidth();
        valueLabel.autosize();
        if (labelX + valueLabel.prefWidth(-1) > wrapperWidth - 10) {
            labelX = xInWrapper - valueLabel.prefWidth(-1) - 8;
        }
        valueLabel.setTranslateX(labelX);
        valueLabel.setTranslateY(plotTop + 10);
        valueLabel.setVisible(true);
    }

    /**
     * Находит ближайшую по оси X точку в серии.
     */
    private static XYChart.Data<Number, Number> findNearest(XYChart.Series<Number, Number> series, long targetEpoch) {
        if (series.getData().isEmpty()) return null;
        XYChart.Data<Number, Number> best = null;
        long bestDist = Long.MAX_VALUE;
        for (XYChart.Data<Number, Number> d : series.getData()) {
            long dist = Math.abs(d.getXValue().longValue() - targetEpoch);
            if (dist < bestDist) {
                bestDist = dist;
                best = d;
            }
        }
        return best;
    }
}
