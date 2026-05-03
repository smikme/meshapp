package com.meshtastic.client.components;

import com.meshtastic.client.model.TelemetryEntry;
import javafx.scene.chart.XYChart;

import java.util.IntSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class TelemetryChartDataBuilder {

    enum ChartKind {
        BASIC,
        RX,
        RATE,
        TX,
        QUALITY,
        HOPS
    }

    static final String TITLE_BASIC = "Базовые метрики";
    static final String TITLE_RX = "Статистика эфира";
    static final String TITLE_RATE = "Скорость приема";
    static final String TITLE_TX = "Скорость передачи";
    static final String TITLE_QUALITY = "Качество соединения";
    static final String TITLE_HOPS = "Прыжки";

    static final String SERIES_BATTERY = "Battery %";
    static final String SERIES_VOLTAGE = "Voltage В";
    static final String SERIES_CH_UTIL = "ChUtil %";
    static final String SERIES_AIR_UTIL = "AirUtil %";
    static final String SERIES_GOOD_RX = "Good RX %";
    static final String SERIES_BAD_RX = "Bad RX %";
    static final String SERIES_DUPE_RX = "Dupe RX %";
    static final String SERIES_PACKETS_RECEIVED = "Packets Received";
    static final String SERIES_BAD_PACKETS = "Bad Packets";
    static final String SERIES_DUPLICATES = "Duplicates";
    static final String SERIES_PACKETS_TRANSMITTED = "Packets Transmitted";
    static final String SERIES_DROPPED = "Dropped";
    static final String SERIES_RELAYED = "Relayed";
    static final String SERIES_RELAY_CANCELED = "Relay Canceled";
    static final String SERIES_SNR = "SNR (dB)";
    static final String SERIES_RSSI = "RSSI (dBm)";
    static final String SERIES_HOPS_MAX = "Макс";
    static final String SERIES_HOPS_MIN = "Мин";
    static final String SERIES_HOPS_AVG = "Среднее";

    private static final int MAX_CHART_POINTS = 60;
    private static final long EMPTY_PERIOD_FALLBACK = 24L * 3600;
    private static final float VOLTAGE_MIN = 3.0f;
    private static final float VOLTAGE_MAX = 4.2f;

    private static final Predicate<TelemetryEntry> HAS_BATTERY =
            entry -> entry.getBatteryLevel() > 0 && entry.getBatteryLevel() <= 100;
    private static final Predicate<TelemetryEntry> HAS_VOLTAGE = entry -> entry.getVoltage() > 0;
    private static final Predicate<TelemetryEntry> HAS_RX_COUNTERS = entry -> entry.getNumPacketsRx() > 0;
    private static final Predicate<TelemetryEntry> HAS_TX_COUNTERS = entry -> entry.getNumPacketsTx() > 0;
    private static final Predicate<TelemetryEntry> HAS_QUALITY =
            entry -> entry.getRxSnr() != 0 || entry.getRxRssi() != 0;
    private static final Predicate<TelemetryEntry> HAS_HOPS = entry -> entry.getHopStart() > 0;

    private TelemetryChartDataBuilder() {
    }

    record AxisRange(long lowerBound, long upperBound, long tickUnit) {}

    record ChartPayload(String title, List<XYChart.Series<Number, Number>> series) {}

    record PreparedCharts(AxisRange axisRange, Map<ChartKind, ChartPayload> payloads) {
        ChartPayload payload(ChartKind chartKind) {
            return Optional.ofNullable(payloads.get(chartKind))
                    .orElseThrow(() -> new IllegalArgumentException("Missing chart payload: " + chartKind));
        }
    }

    private record Bucket<T>(long center, List<T> items) {}

    private record RxMetric(long timestamp, double received, double bad, double duplicate) {}

    private record TxMetric(long timestamp, double transmitted, double dropped, double relayed, double canceled) {}

    private record QualityMetric(long timestamp, double snr, double rssi) {}

    private record HopMetric(long timestamp, double average, double max, double min) {}

    @FunctionalInterface
    private interface PointFactory {
        XYChart.Data<Number, Number> create(long timestamp, double value);
    }

    static PreparedCharts build(boolean basicOnly,
                                List<TelemetryEntry> entries,
                                List<TelemetryEntry> qualityEntries,
                                long selectedPeriodSeconds) {
        AxisRange axisRange = buildAxisRange(entries, qualityEntries, selectedPeriodSeconds);

        Map<ChartKind, ChartPayload> payloads = Stream.concat(
                Stream.of(Map.entry(ChartKind.BASIC, buildBasicChart(entries))),
                basicOnly ? Stream.empty() : secondaryPayloads(entries, qualityEntries))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        return new PreparedCharts(axisRange, Map.copyOf(payloads));
    }

    private static Stream<Map.Entry<ChartKind, ChartPayload>> secondaryPayloads(List<TelemetryEntry> entries,
                                                                                List<TelemetryEntry> qualityEntries) {
        List<RxMetric> rxMetrics = aggregateIfNeeded(buildRxMetrics(entries), entries, RxMetric::timestamp,
                TelemetryChartDataBuilder::sumRxMetric);
        List<TxMetric> txMetrics = aggregateIfNeeded(buildTxMetrics(entries), entries, TxMetric::timestamp,
                TelemetryChartDataBuilder::sumTxMetric);
        return Stream.of(
                Map.entry(ChartKind.RX, buildRxChart(rxMetrics)),
                Map.entry(ChartKind.RATE, buildRateChart(rxMetrics)),
                Map.entry(ChartKind.TX, buildTxChart(txMetrics)),
                Map.entry(ChartKind.QUALITY, buildQualityChart(qualityEntries)),
                Map.entry(ChartKind.HOPS, buildHopsChart(qualityEntries))
        );
    }

    private static ChartPayload buildBasicChart(List<TelemetryEntry> entries) {
        if (isBucketed(entries)) {
            List<Bucket<TelemetryEntry>> buckets = bucketize(entries, TelemetryEntry::getTimestamp, MAX_CHART_POINTS);
            return new ChartPayload(TITLE_BASIC, List.of(
                    series(SERIES_BATTERY, averageData(buckets, HAS_BATTERY, entry -> entry.getBatteryLevel(),
                            TelemetryChartDataBuilder::dataPoint)),
                    series(SERIES_VOLTAGE, averageData(buckets, HAS_VOLTAGE, entry -> entry.getVoltage(),
                            TelemetryChartDataBuilder::createVoltageData)),
                    series(SERIES_CH_UTIL, averageData(buckets, entry -> true, entry -> entry.getChannelUtilization(),
                            TelemetryChartDataBuilder::dataPoint)),
                    series(SERIES_AIR_UTIL, averageData(buckets, entry -> true, entry -> entry.getAirUtilTx(),
                            TelemetryChartDataBuilder::dataPoint))
            ));
        }

        return new ChartPayload(TITLE_BASIC, List.of(
                series(SERIES_BATTERY, pointData(entries, HAS_BATTERY, TelemetryEntry::getTimestamp,
                        entry -> entry.getBatteryLevel(), TelemetryChartDataBuilder::dataPoint)),
                series(SERIES_VOLTAGE, pointData(entries, HAS_VOLTAGE, TelemetryEntry::getTimestamp,
                        entry -> entry.getVoltage(), TelemetryChartDataBuilder::createVoltageData)),
                series(SERIES_CH_UTIL, pointData(entries, entry -> true, TelemetryEntry::getTimestamp,
                        entry -> entry.getChannelUtilization(), TelemetryChartDataBuilder::dataPoint)),
                series(SERIES_AIR_UTIL, pointData(entries, entry -> true, TelemetryEntry::getTimestamp,
                        entry -> entry.getAirUtilTx(), TelemetryChartDataBuilder::dataPoint))
        ));
    }

    private static ChartPayload buildRxChart(List<RxMetric> metrics) {
        return new ChartPayload(TITLE_RX, List.of(
                series(SERIES_GOOD_RX, metrics.stream()
                        .filter(metric -> metric.received() > 0)
                        .map(metric -> dataPoint(metric.timestamp(),
                                (metric.received() - metric.bad() - metric.duplicate()) / metric.received() * 100.0))
                        .toList()),
                series(SERIES_BAD_RX, metrics.stream()
                        .filter(metric -> metric.received() > 0)
                        .map(metric -> dataPoint(metric.timestamp(), metric.bad() / metric.received() * 100.0))
                        .toList()),
                series(SERIES_DUPE_RX, metrics.stream()
                        .filter(metric -> metric.received() > 0)
                        .map(metric -> dataPoint(metric.timestamp(), metric.duplicate() / metric.received() * 100.0))
                        .toList())
        ));
    }

    private static ChartPayload buildRateChart(List<RxMetric> metrics) {
        return new ChartPayload(TITLE_RATE, List.of(
                series(SERIES_PACKETS_RECEIVED, pointData(metrics, metric -> true, RxMetric::timestamp,
                        RxMetric::received, TelemetryChartDataBuilder::dataPoint)),
                series(SERIES_BAD_PACKETS, pointData(metrics, metric -> true, RxMetric::timestamp,
                        RxMetric::bad, TelemetryChartDataBuilder::dataPoint)),
                series(SERIES_DUPLICATES, pointData(metrics, metric -> true, RxMetric::timestamp,
                        RxMetric::duplicate, TelemetryChartDataBuilder::dataPoint))
        ));
    }

    private static ChartPayload buildTxChart(List<TxMetric> metrics) {
        return new ChartPayload(TITLE_TX, List.of(
                series(SERIES_PACKETS_TRANSMITTED, pointData(metrics, metric -> true, TxMetric::timestamp,
                        TxMetric::transmitted, TelemetryChartDataBuilder::dataPoint)),
                series(SERIES_DROPPED, pointData(metrics, metric -> true, TxMetric::timestamp,
                        TxMetric::dropped, TelemetryChartDataBuilder::dataPoint)),
                series(SERIES_RELAYED, pointData(metrics, metric -> true, TxMetric::timestamp,
                        TxMetric::relayed, TelemetryChartDataBuilder::dataPoint)),
                series(SERIES_RELAY_CANCELED, pointData(metrics, metric -> true, TxMetric::timestamp,
                        TxMetric::canceled, TelemetryChartDataBuilder::dataPoint))
        ));
    }

    private static ChartPayload buildQualityChart(List<TelemetryEntry> qualityEntries) {
        List<QualityMetric> pointMetrics = qualityEntries.stream()
                .filter(HAS_QUALITY)
                .map(entry -> new QualityMetric(entry.getTimestamp(), entry.getRxSnr(), entry.getRxRssi()))
                .toList();
        List<QualityMetric> metrics = aggregateIfNeeded(pointMetrics, qualityEntries, QualityMetric::timestamp,
                TelemetryChartDataBuilder::averageQualityMetric);
        return new ChartPayload(TITLE_QUALITY, List.of(
                series(SERIES_SNR, pointData(metrics, metric -> true, QualityMetric::timestamp,
                        QualityMetric::snr, TelemetryChartDataBuilder::dataPoint)),
                series(SERIES_RSSI, pointData(metrics, metric -> true, QualityMetric::timestamp,
                        QualityMetric::rssi, TelemetryChartDataBuilder::dataPoint))
        ));
    }

    private static ChartPayload buildHopsChart(List<TelemetryEntry> qualityEntries) {
        List<HopMetric> metrics = buildHopMetrics(qualityEntries);
        return new ChartPayload(TITLE_HOPS, List.of(
                series(SERIES_HOPS_MAX, pointData(metrics, metric -> true, HopMetric::timestamp,
                        HopMetric::max, TelemetryChartDataBuilder::dataPoint)),
                series(SERIES_HOPS_MIN, pointData(metrics, metric -> true, HopMetric::timestamp,
                        HopMetric::min, TelemetryChartDataBuilder::dataPoint)),
                series(SERIES_HOPS_AVG, pointData(metrics, metric -> true, HopMetric::timestamp,
                        HopMetric::average, TelemetryChartDataBuilder::dataPoint))
        ));
    }

    private static List<RxMetric> buildRxMetrics(List<TelemetryEntry> entries) {
        List<TelemetryEntry> rxEntries = entries.stream()
                .filter(HAS_RX_COUNTERS)
                .toList();
        return IntStream.range(1, rxEntries.size())
                .mapToObj(index -> createRxMetric(rxEntries.get(index - 1), rxEntries.get(index)))
                .toList();
    }

    private static List<TxMetric> buildTxMetrics(List<TelemetryEntry> entries) {
        List<TelemetryEntry> txEntries = entries.stream()
                .filter(HAS_TX_COUNTERS)
                .toList();
        return IntStream.range(1, txEntries.size())
                .mapToObj(index -> createTxMetric(txEntries.get(index - 1), txEntries.get(index)))
                .toList();
    }

    private static List<HopMetric> buildHopMetrics(List<TelemetryEntry> qualityEntries) {
        if (qualityEntries.isEmpty()) {
            return List.of();
        }

        long minTs = qualityEntries.getFirst().getTimestamp();
        long maxTs = qualityEntries.getLast().getTimestamp();
        long range = maxTs - minTs;
        int hopBuckets = Math.min(30, Math.max(1, (int) (range / 900)));
        if (hopBuckets < 3) {
            hopBuckets = Math.min(3, qualityEntries.size());
        }

        return bucketize(
                qualityEntries.stream().filter(HAS_HOPS).toList(),
                TelemetryEntry::getTimestamp,
                hopBuckets,
                minTs,
                maxTs
        ).stream()
                .map(TelemetryChartDataBuilder::averageHopMetric)
                .toList();
    }

    private static AxisRange buildAxisRange(List<TelemetryEntry> entries,
                                            List<TelemetryEntry> qualityEntries,
                                            long selectedPeriodSeconds) {
        LongSummaryStatistics stats = Stream.of(entries, qualityEntries)
                .flatMap(List::stream)
                .mapToLong(TelemetryEntry::getTimestamp)
                .summaryStatistics();

        if (stats.getCount() == 0) {
            return createPlaceholderAxisRange(selectedPeriodSeconds);
        }

        long range = Math.max(stats.getMax() - stats.getMin(), 1);
        long padding = Math.max(range / 20, 60);
        long totalRange = range + 2 * padding;
        return new AxisRange(stats.getMin() - padding, stats.getMax() + padding, Math.max(totalRange / 8, 60));
    }

    private static AxisRange createPlaceholderAxisRange(long selectedPeriodSeconds) {
        long now = System.currentTimeMillis() / 1000;
        long visibleRange = selectedPeriodSeconds > 0 ? selectedPeriodSeconds : EMPTY_PERIOD_FALLBACK;
        long padding = Math.max(visibleRange / 20, 60);
        long totalRange = visibleRange + 2 * padding;
        return new AxisRange(now - visibleRange - padding, now + padding, Math.max(totalRange / 8, 60));
    }

    private static <T> List<T> aggregateIfNeeded(List<T> metrics,
                                                 List<TelemetryEntry> sourceEntries,
                                                 ToLongFunction<T> timestampExtractor,
                                                 java.util.function.BiFunction<Long, List<T>, T> aggregator) {
        if (!isBucketed(sourceEntries)) {
            return metrics;
        }
        return bucketize(metrics, timestampExtractor, MAX_CHART_POINTS,
                sourceEntries.getFirst().getTimestamp(),
                sourceEntries.getLast().getTimestamp()).stream()
                .map(bucket -> aggregator.apply(bucket.center(), bucket.items()))
                .toList();
    }

    private static <T> List<XYChart.Data<Number, Number>> pointData(List<T> entries,
                                                                    Predicate<T> filter,
                                                                    ToLongFunction<T> timestampExtractor,
                                                                    ToDoubleFunction<T> valueExtractor,
                                                                    PointFactory pointFactory) {
        return entries.stream()
                .filter(filter)
                .map(entry -> pointFactory.create(timestampExtractor.applyAsLong(entry), valueExtractor.applyAsDouble(entry)))
                .toList();
    }

    private static <T> List<XYChart.Data<Number, Number>> averageData(List<Bucket<T>> buckets,
                                                                      Predicate<T> filter,
                                                                      ToDoubleFunction<T> valueExtractor,
                                                                      PointFactory pointFactory) {
        return buckets.stream()
                .map(bucket -> bucket.items().stream()
                        .filter(filter)
                        .mapToDouble(valueExtractor)
                        .average()
                        .stream()
                        .mapToObj(value -> pointFactory.create(bucket.center(), value))
                        .findFirst())
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    private static <T> List<Bucket<T>> bucketize(List<T> entries,
                                                 ToLongFunction<T> timestampExtractor,
                                                 int bucketCount) {
        if (entries.isEmpty()) {
            return List.of();
        }
        LongSummaryStatistics stats = entries.stream()
                .mapToLong(timestampExtractor)
                .summaryStatistics();
        return bucketize(entries, timestampExtractor, bucketCount, stats.getMin(), stats.getMax());
    }

    private static <T> List<Bucket<T>> bucketize(List<T> entries,
                                                 ToLongFunction<T> timestampExtractor,
                                                 int bucketCount,
                                                 long minTs,
                                                 long maxTs) {
        if (entries.isEmpty() || bucketCount <= 0) {
            return List.of();
        }

        long bucketSize = Math.max((maxTs - minTs) / bucketCount, 1);
        Map<Integer, List<T>> grouped = entries.stream()
                .collect(Collectors.groupingBy(
                        entry -> bucketIndex(timestampExtractor.applyAsLong(entry), minTs, bucketSize, bucketCount),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Bucket<>(bucketCenter(minTs, bucketSize, entry.getKey()), entry.getValue()))
                .toList();
    }

    private static int bucketIndex(long timestamp, long minTs, long bucketSize, int bucketCount) {
        return Math.toIntExact(Math.min((timestamp - minTs) / bucketSize, bucketCount - 1L));
    }

    private static long bucketCenter(long minTs, long bucketSize, int bucketIndex) {
        return minTs + (long) bucketIndex * bucketSize + bucketSize / 2;
    }

    private static XYChart.Series<Number, Number> series(String name, List<XYChart.Data<Number, Number>> data) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(name);
        series.getData().setAll(data);
        return series;
    }

    private static XYChart.Data<Number, Number> dataPoint(long timestamp, double value) {
        return new XYChart.Data<>(timestamp, value);
    }

    private static XYChart.Data<Number, Number> createVoltageData(long timestamp, double voltage) {
        return new XYChart.Data<>(timestamp, voltageToPercent((float) voltage), voltage);
    }

    private static double voltageToPercent(float voltage) {
        double pct = (voltage - VOLTAGE_MIN) / (VOLTAGE_MAX - VOLTAGE_MIN) * 100.0;
        return Math.max(0, Math.min(100, pct));
    }

    private static RxMetric createRxMetric(TelemetryEntry previous, TelemetryEntry current) {
        int received = current.getNumPacketsRx() - previous.getNumPacketsRx();
        int bad = current.getNumPacketsRxBad() - previous.getNumPacketsRxBad();
        int duplicate = current.getNumRxDupe() - previous.getNumRxDupe();
        return received < 0
                ? new RxMetric(current.getTimestamp(), current.getNumPacketsRx(), current.getNumPacketsRxBad(), current.getNumRxDupe())
                : new RxMetric(current.getTimestamp(), received, bad, duplicate);
    }

    private static TxMetric createTxMetric(TelemetryEntry previous, TelemetryEntry current) {
        int transmitted = current.getNumPacketsTx() - previous.getNumPacketsTx();
        int dropped = current.getNumTxDropped() - previous.getNumTxDropped();
        int relayed = current.getNumTxRelay() - previous.getNumTxRelay();
        int canceled = current.getNumTxRelayCanceled() - previous.getNumTxRelayCanceled();
        return transmitted < 0
                ? new TxMetric(current.getTimestamp(), current.getNumPacketsTx(), current.getNumTxDropped(),
                current.getNumTxRelay(), current.getNumTxRelayCanceled())
                : new TxMetric(current.getTimestamp(), transmitted, dropped, relayed, canceled);
    }

    private static RxMetric sumRxMetric(long timestamp, List<RxMetric> metrics) {
        return new RxMetric(
                timestamp,
                metrics.stream().mapToDouble(RxMetric::received).sum(),
                metrics.stream().mapToDouble(RxMetric::bad).sum(),
                metrics.stream().mapToDouble(RxMetric::duplicate).sum()
        );
    }

    private static TxMetric sumTxMetric(long timestamp, List<TxMetric> metrics) {
        return new TxMetric(
                timestamp,
                metrics.stream().mapToDouble(TxMetric::transmitted).sum(),
                metrics.stream().mapToDouble(TxMetric::dropped).sum(),
                metrics.stream().mapToDouble(TxMetric::relayed).sum(),
                metrics.stream().mapToDouble(TxMetric::canceled).sum()
        );
    }

    private static QualityMetric averageQualityMetric(long timestamp, List<QualityMetric> metrics) {
        return new QualityMetric(
                timestamp,
                metrics.stream().mapToDouble(QualityMetric::snr).average().orElse(0),
                metrics.stream().mapToDouble(QualityMetric::rssi).average().orElse(0)
        );
    }

    private static HopMetric averageHopMetric(Bucket<TelemetryEntry> bucket) {
        IntSummaryStatistics stats = bucket.items().stream()
                .mapToInt(TelemetryEntry::getHopsTraveled)
                .summaryStatistics();
        return new HopMetric(bucket.center(), stats.getAverage(), stats.getMax(), stats.getMin());
    }

    private static boolean isBucketed(List<?> entries) {
        return entries.size() > MAX_CHART_POINTS;
    }
}
