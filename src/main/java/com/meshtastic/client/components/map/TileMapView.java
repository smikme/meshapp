package com.meshtastic.client.components.map;

import com.meshtastic.client.MeshApp;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * JavaFX-компонент OSM-карты с поддержкой онлайн-тайлов, локального кэша,
 * внешнего каталога оффлайн-тайлов и пользовательских оверлеев.
 * <p>
 * Компонент сам управляет загрузкой тайлов, масштабированием, сдвигом карты,
 * ночным режимом, маркерами нод, измерением расстояния, выделением области
 * и визуализацией трейсов.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class TileMapView extends StackPane {

    /** Размер одного OSM-тайла в пикселях. */
    public static final int TILE_SIZE = 256;
    /** Минимальный поддерживаемый масштаб карты. */
    public static final int MIN_ZOOM = 1;
    /** Максимальный поддерживаемый масштаб карты. */
    public static final int MAX_ZOOM = 19;

    private static final double MAX_LATITUDE = 85.05112878;
    private static final int MEMORY_CACHE_LIMIT = 384;
    private static final double MARKER_SIZE = 32;
    private static final double MARKER_LABEL_SIZE = 28;
    private static final double MARKER_MAX_FONT_SIZE = 13;
    private static final double MARKER_MIN_FONT_SIZE = 7;
    private static final String TRACE_FORWARD_COLOR = "#58a6ff";
    private static final String TRACE_REVERSE_COLOR = "#f0883e";
    private static final String NIGHT_MODE_CLASS = "map-night-mode";
    private static final String[] TILE_EXTENSIONS = {".png", ".jpg", ".jpeg"};
    private static final Path CACHE_ROOT = Paths.get(
            System.getProperty("user.home", "."),
            ".meshapp",
            "map-tiles",
            "osm"
    );

    private final Pane tileLayer = new Pane();
    private final Pane areaLayer = new Pane();
    private final Pane traceLayer = new Pane();
    private final Pane markerLayer = new Pane();
    private final Pane measureLayer = new Pane();
    private final Label attributionLabel = new Label("© OpenStreetMap contributors");
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ExecutorService tileExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "meshapp-osm-tile-loader");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<TileKey, Image> memoryCache = Collections.synchronizedMap(
            new LinkedHashMap<>(MEMORY_CACHE_LIMIT, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<TileKey, Image> eldest) {
                    return size() > MEMORY_CACHE_LIMIT;
                }
            }
    );
    private final Map<TileKey, Image> nightMemoryCache = Collections.synchronizedMap(
            new LinkedHashMap<>(MEMORY_CACHE_LIMIT, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<TileKey, Image> eldest) {
                    return size() > MEMORY_CACHE_LIMIT;
                }
            }
    );
    private final Set<TileKey> inFlightDownloads = ConcurrentHashMap.newKeySet();
    private final List<GeoPoint> measurePoints = new ArrayList<>();

    private List<MapMarker> markers = List.of();
    private List<TraceSegment> traceSegments = List.of();
    private Path externalTileRoot;
    private GeoBounds selectedArea;
    private boolean offlineOnly;
    private boolean nightMode;
    private boolean measuring;
    private boolean areaSelectionMode;
    private boolean areaSelectionActive;
    private double centerLatitude = 20;
    private double centerLongitude = 0;
    private int zoom = 2;
    private double pressX;
    private double pressY;
    private double dragStartCenterX;
    private double dragStartCenterY;
    private double areaStartX;
    private double areaStartY;
    private double areaEndX;
    private double areaEndY;
    private boolean dragged;
    private Consumer<String> statusListener = text -> {};
    private Consumer<GeoPoint> pointerListener = point -> {};
    private Consumer<String> measureListener = text -> {};
    private Consumer<String> areaSelectionListener = text -> {};
    private Consumer<MapMarker> markerClickListener = marker -> {};

    /**
     * Создаёт карту и настраивает обработчики мыши для сдвига, масштабирования,
     * измерений и выделения прямоугольной области.
     */
    public TileMapView() {
        getStyleClass().add("tile-map-view");
        setMinSize(0, 0);
        setFocusTraversable(true);
        setCursor(Cursor.OPEN_HAND);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        tileLayer.getStyleClass().add("map-tile-layer");
        tileLayer.setMouseTransparent(true);
        areaLayer.getStyleClass().add("map-area-layer");
        areaLayer.setMouseTransparent(true);
        traceLayer.getStyleClass().add("map-trace-layer");
        traceLayer.setMouseTransparent(true);
        markerLayer.getStyleClass().add("map-marker-layer");
        markerLayer.setPickOnBounds(false);
        measureLayer.getStyleClass().add("map-measure-layer");
        measureLayer.setMouseTransparent(true);
        attributionLabel.getStyleClass().add("map-attribution-label");
        attributionLabel.setMouseTransparent(true);

        getChildren().addAll(tileLayer, areaLayer, traceLayer, markerLayer, measureLayer, attributionLabel);
        StackPane.setAlignment(attributionLabel, Pos.BOTTOM_LEFT);

        widthProperty().addListener((obs, oldValue, newValue) -> render());
        heightProperty().addListener((obs, oldValue, newValue) -> render());

        setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            requestFocus();
            pressX = event.getX();
            pressY = event.getY();
            dragged = false;
            if (areaSelectionMode) {
                areaSelectionActive = true;
                areaStartX = event.getX();
                areaStartY = event.getY();
                areaEndX = areaStartX;
                areaEndY = areaStartY;
                updateAreaOverlay();
                notifyAreaSelection();
                setCursor(Cursor.CROSSHAIR);
                event.consume();
                return;
            }
            dragStartCenterX = lonToPixelX(centerLongitude, zoom);
            dragStartCenterY = latToPixelY(centerLatitude, zoom);
            setCursor(measuring ? Cursor.CROSSHAIR : Cursor.CLOSED_HAND);
        });

        setOnMouseDragged(event -> {
            if (!event.isPrimaryButtonDown()) {
                return;
            }
            if (areaSelectionMode && areaSelectionActive) {
                areaEndX = event.getX();
                areaEndY = event.getY();
                dragged = Math.abs(areaEndX - areaStartX) > 3 || Math.abs(areaEndY - areaStartY) > 3;
                updateAreaOverlay();
                notifyAreaSelection();
                event.consume();
                return;
            }
            double dx = event.getX() - pressX;
            double dy = event.getY() - pressY;
            if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
                dragged = true;
            }
            double centerX = dragStartCenterX - dx;
            double centerY = dragStartCenterY - dy;
            centerLongitude = pixelXToLon(centerX, zoom);
            centerLatitude = pixelYToLat(centerY, zoom);
            normalizeCenter();
            render();
        });

        setOnMouseReleased(event -> {
            if (areaSelectionMode && areaSelectionActive) {
                areaSelectionActive = false;
                areaEndX = event.getX();
                areaEndY = event.getY();
                finishAreaSelection();
                setCursor(Cursor.CROSSHAIR);
                event.consume();
                return;
            }
            setCursor(measuring ? Cursor.CROSSHAIR : Cursor.OPEN_HAND);
        });

        setOnMouseClicked(event -> {
            if (areaSelectionMode) {
                event.consume();
                return;
            }
            if (measuring && !dragged && event.getButton() == MouseButton.PRIMARY) {
                GeoPoint point = screenToGeo(event.getX(), event.getY());
                measurePoints.add(point);
                updateMeasureOverlay();
                notifyMeasure();
                event.consume();
            }
        });

        setOnMouseMoved(event -> pointerListener.accept(screenToGeo(event.getX(), event.getY())));
        addEventHandler(ScrollEvent.SCROLL, this::handleScrollZoom);
    }

    /**
     * Устанавливает получатель краткого статуса карты: масштаб, количество тайлов,
     * онлайн/оффлайн-режим и ночной режим.
     *
     * @param statusListener обработчик текста статуса
     */
    public void setStatusListener(Consumer<String> statusListener) {
        this.statusListener = statusListener != null ? statusListener : text -> {};
        notifyStatus();
    }

    /**
     * Устанавливает получатель координат точки под указателем мыши.
     *
     * @param pointerListener обработчик географической точки
     */
    public void setPointerListener(Consumer<GeoPoint> pointerListener) {
        this.pointerListener = pointerListener != null ? pointerListener : point -> {};
    }

    /**
     * Устанавливает получатель текста состояния линейки.
     *
     * @param measureListener обработчик текста измерения
     */
    public void setMeasureListener(Consumer<String> measureListener) {
        this.measureListener = measureListener != null ? measureListener : text -> {};
        notifyMeasure();
    }

    /**
     * Устанавливает получатель текста состояния выделенной области.
     *
     * @param areaSelectionListener обработчик текста по области
     */
    public void setAreaSelectionListener(Consumer<String> areaSelectionListener) {
        this.areaSelectionListener = areaSelectionListener != null ? areaSelectionListener : text -> {};
        notifyAreaSelection();
    }

    /**
     * Устанавливает обработчик клика по маркеру ноды.
     *
     * @param markerClickListener обработчик выбранного маркера
     */
    public void setMarkerClickListener(Consumer<MapMarker> markerClickListener) {
        this.markerClickListener = markerClickListener != null ? markerClickListener : marker -> {};
    }

    /**
     * Центрирует карту в указанной точке и применяет масштаб.
     *
     * @param latitude  широта центра
     * @param longitude долгота центра
     * @param zoom      масштаб OSM
     */
    public void setView(double latitude, double longitude, int zoom) {
        this.centerLatitude = clampLatitude(latitude);
        this.centerLongitude = normalizeLongitude(longitude);
        this.zoom = clampZoom(zoom);
        render();
    }

    /** @return широта текущего центра карты */
    public double getCenterLatitude() {
        return centerLatitude;
    }

    /** @return долгота текущего центра карты */
    public double getCenterLongitude() {
        return centerLongitude;
    }

    /** @return текущий масштаб OSM */
    public int getZoom() {
        return zoom;
    }

    /** Увеличивает масштаб относительно центра видимой области. */
    public void zoomIn() {
        zoomAround(getWidth() / 2.0, getHeight() / 2.0, zoom + 1);
    }

    /** Уменьшает масштаб относительно центра видимой области. */
    public void zoomOut() {
        zoomAround(getWidth() / 2.0, getHeight() / 2.0, zoom - 1);
    }

    /**
     * Включает или выключает режим использования только локальных тайлов.
     *
     * @param offlineOnly {@code true}, чтобы не обращаться к OSM по сети
     */
    public void setOfflineOnly(boolean offlineOnly) {
        this.offlineOnly = offlineOnly;
        render();
    }

    /** @return {@code true}, если карта использует только локальные тайлы */
    public boolean isOfflineOnly() {
        return offlineOnly;
    }

    /**
     * Включает ночный режим карты. Ночной режим преобразует только тайлы,
     * не затрагивая маркеры, трейсы, линейку и выделенную область.
     *
     * @param nightMode {@code true}, чтобы отображать тайлы в ночной палитре
     */
    public void setNightMode(boolean nightMode) {
        this.nightMode = nightMode;
        if (nightMode) {
            if (!getStyleClass().contains(NIGHT_MODE_CLASS)) {
                getStyleClass().add(NIGHT_MODE_CLASS);
            }
        } else {
            getStyleClass().remove(NIGHT_MODE_CLASS);
        }
        render();
    }

    /** @return {@code true}, если включён ночной режим тайлов */
    public boolean isNightMode() {
        return nightMode;
    }

    /**
     * Устанавливает внешний каталог оффлайн-тайлов формата {@code z/x/y.png|jpg|jpeg}.
     *
     * @param externalTileRoot корневой каталог тайлов или {@code null}
     */
    public void setExternalTileRoot(Path externalTileRoot) {
        this.externalTileRoot = externalTileRoot;
        memoryCache.clear();
        nightMemoryCache.clear();
        render();
    }

    /** @return текущий внешний каталог тайлов или {@code null} */
    public Path getExternalTileRoot() {
        return externalTileRoot;
    }

    /**
     * Включает или выключает режим измерения расстояния.
     * При включении режим выделения области отключается.
     *
     * @param measuring {@code true}, чтобы клики по карте добавляли точки линейки
     */
    public void setMeasuring(boolean measuring) {
        this.measuring = measuring;
        if (measuring) {
            areaSelectionMode = false;
            areaSelectionActive = false;
        }
        setCursor(measuring || areaSelectionMode ? Cursor.CROSSHAIR : Cursor.OPEN_HAND);
        notifyMeasure();
        updateAreaOverlay();
    }

    /** @return {@code true}, если активна линейка */
    public boolean isMeasuring() {
        return measuring;
    }

    /** Очищает все точки линейки и обновляет оверлей измерения. */
    public void clearMeasure() {
        measurePoints.clear();
        updateMeasureOverlay();
        notifyMeasure();
    }

    /**
     * Включает или выключает режим выделения прямоугольной области.
     * При включении линейка отключается.
     *
     * @param areaSelectionMode {@code true}, чтобы протягивание мышью выделяло область
     */
    public void setAreaSelectionMode(boolean areaSelectionMode) {
        this.areaSelectionMode = areaSelectionMode;
        if (areaSelectionMode) {
            measuring = false;
        } else {
            areaSelectionActive = false;
        }
        setCursor(areaSelectionMode ? Cursor.CROSSHAIR : Cursor.OPEN_HAND);
        updateAreaOverlay();
        notifyAreaSelection();
    }

    /** @return {@code true}, если активен режим выделения области */
    public boolean isAreaSelectionMode() {
        return areaSelectionMode;
    }

    /** Очищает выбранную область и её визуальный оверлей. */
    public void clearSelectedArea() {
        selectedArea = null;
        areaSelectionActive = false;
        updateAreaOverlay();
        notifyAreaSelection();
    }

    /**
     * Заменяет набор маркеров нод на карте.
     *
     * @param markers маркеры для отображения
     */
    public void setMarkers(List<MapMarker> markers) {
        this.markers = markers != null ? List.copyOf(markers) : List.of();
        updateMarkerOverlay();
    }

    /**
     * Заменяет набор сегментов трейсов.
     *
     * @param traceSegments сегменты соединений между нодами
     */
    public void setTraceSegments(List<TraceSegment> traceSegments) {
        this.traceSegments = traceSegments != null ? List.copyOf(traceSegments) : List.of();
        updateTraceOverlay();
    }

    /** Скрывает все трейсы с карты. */
    public void clearTraceSegments() {
        this.traceSegments = List.of();
        updateTraceOverlay();
    }

    /**
     * Подбирает центр и масштаб так, чтобы выбранные трейсы поместились на карту.
     *
     * @return {@code true}, если есть координаты для масштабирования
     */
    public boolean fitTraceSegments() {
        if (traceSegments.isEmpty()) {
            return false;
        }

        List<GeoPoint> points = new ArrayList<>();
        for (TraceSegment segment : traceSegments) {
            if (isValidCoordinate(segment.from().latitude(), segment.from().longitude())) {
                points.add(segment.from());
            }
            if (isValidCoordinate(segment.to().latitude(), segment.to().longitude())) {
                points.add(segment.to());
            }
        }
        if (points.isEmpty()) {
            return false;
        }
        if (points.size() == 1) {
            GeoPoint point = points.getFirst();
            setView(point.latitude(), point.longitude(), Math.max(13, zoom));
            return true;
        }

        double availableWidth = Math.max(280, getWidth() - 112);
        double availableHeight = Math.max(220, getHeight() - 112);
        int bestZoom = MIN_ZOOM;
        double bestCenterX = 0;
        double bestCenterY = 0;

        for (int candidateZoom = MAX_ZOOM; candidateZoom >= MIN_ZOOM; candidateZoom--) {
            Bounds bounds = pointBounds(points, candidateZoom);
            if (bounds.width() <= availableWidth && bounds.height() <= availableHeight) {
                bestZoom = candidateZoom;
                bestCenterX = bounds.centerX();
                bestCenterY = bounds.centerY();
                break;
            }
            if (candidateZoom == MIN_ZOOM) {
                bestCenterX = bounds.centerX();
                bestCenterY = bounds.centerY();
            }
        }

        zoom = bestZoom;
        centerLongitude = pixelXToLon(bestCenterX, zoom);
        centerLatitude = pixelYToLat(bestCenterY, zoom);
        normalizeCenter();
        render();
        return true;
    }

    /**
     * Подбирает центр и масштаб так, чтобы все маркеры нод поместились на карту.
     *
     * @return {@code true}, если есть маркеры для масштабирования
     */
    public boolean fitMarkers() {
        if (markers.isEmpty()) {
            return false;
        }
        if (markers.size() == 1) {
            MapMarker marker = markers.getFirst();
            setView(marker.latitude(), marker.longitude(), Math.max(13, zoom));
            return true;
        }

        double availableWidth = Math.max(280, getWidth() - 96);
        double availableHeight = Math.max(220, getHeight() - 96);
        int bestZoom = MIN_ZOOM;
        double bestCenterX = 0;
        double bestCenterY = 0;

        for (int candidateZoom = MAX_ZOOM; candidateZoom >= MIN_ZOOM; candidateZoom--) {
            Bounds bounds = markerBounds(candidateZoom);
            if (bounds.width() <= availableWidth && bounds.height() <= availableHeight) {
                bestZoom = candidateZoom;
                bestCenterX = bounds.centerX();
                bestCenterY = bounds.centerY();
                break;
            }
            if (candidateZoom == MIN_ZOOM) {
                bestCenterX = bounds.centerX();
                bestCenterY = bounds.centerY();
            }
        }

        zoom = bestZoom;
        centerLongitude = pixelXToLon(bestCenterX, zoom);
        centerLatitude = pixelYToLat(bestCenterY, zoom);
        normalizeCenter();
        render();
        return true;
    }

    /** @return количество тайлов, видимых в текущем viewport */
    public int visibleTileCount() {
        return visibleTileKeys().size();
    }

    /**
     * Возвращает количество тайлов, которые будут загружены для выбранной области.
     */
    public long downloadTileCount() {
        return downloadTilePlan().totalTiles();
    }

    /** @return {@code true}, если пользователь явно выделил область для загрузки */
    public boolean hasSelectedArea() {
        return selectedArea != null;
    }

    /** @return путь к встроенному локальному кэшу тайлов */
    public Path cacheRoot() {
        return CACHE_ROOT;
    }

    /**
     * Загружает тайлы во встроенный кэш.
     * <p>
     * Скачиваются только тайлы явно выделенной области на всех поддерживаемых масштабах.
     * План загрузки хранится как набор диапазонов, чтобы большие области не создавали
     * миллионы объектов ключей в памяти.
     *
     * @param progressConsumer обработчик прогресса загрузки
     */
    public DownloadHandle downloadSelectedAreaTiles(Consumer<DownloadProgress> progressConsumer) {
        TileDownloadPlan plan = downloadTilePlan();
        if (plan.isEmpty()) {
            progressConsumer.accept(new DownloadProgress(
                    0,
                    0,
                    0,
                    "Выделите область для загрузки тайлов",
                    DownloadState.CANCELLED
            ));
            return DownloadHandle.inactive();
        }

        DownloadHandle handle = new DownloadHandle();
        Future<?> future = tileExecutor.submit(() -> {
            AtomicLong completed = new AtomicLong();
            AtomicLong available = new AtomicLong();
            long total = plan.totalTiles();
            long lastUiUpdate = 0;
            boolean cancelled = false;

            downloadLoop:
            for (TileRange range : plan.ranges()) {
                for (int x = range.startX(); x <= range.endX(); x++) {
                    for (int y = range.startY(); y <= range.endY(); y++) {
                        if (handle.isCancelled()) {
                            cancelled = true;
                            break downloadLoop;
                        }
                        try {
                            handle.awaitIfPaused();
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            cancelled = true;
                            break downloadLoop;
                        }
                        if (handle.isCancelled()) {
                            cancelled = true;
                            break downloadLoop;
                        }

                        TileKey key = new TileKey(range.zoom(), x, y);
                        boolean ok = false;
                        try {
                            ok = hasLocalTile(key) || downloadTileFromNetwork(key);
                            if (ok) {
                                available.incrementAndGet();
                            }
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            cancelled = true;
                            break downloadLoop;
                        } catch (IOException ignored) {
                            // Partial downloads are still useful for offline use.
                        }

                        long done = completed.incrementAndGet();
                        long cached = available.get();
                        long now = System.nanoTime();
                        if (done >= total || now - lastUiUpdate >= 250_000_000L) {
                            lastUiUpdate = now;
                            Platform.runLater(() -> progressConsumer.accept(new DownloadProgress(
                                    done,
                                    total,
                                    cached,
                                    "Загружено " + done + " из " + total,
                                    DownloadState.RUNNING
                            )));
                        }
                        if (Thread.currentThread().isInterrupted()) {
                            cancelled = true;
                            break downloadLoop;
                        }
                    }
                }
            }

            long done = completed.get();
            long cached = available.get();
            DownloadState finalState = cancelled || handle.isCancelled()
                    ? DownloadState.CANCELLED
                    : DownloadState.COMPLETED;
            String message = finalState == DownloadState.CANCELLED
                    ? "Загрузка отменена: " + done + " из " + total
                    : "Загружено " + done + " из " + total;
            Platform.runLater(() -> {
                progressConsumer.accept(new DownloadProgress(done, total, cached, message, finalState));
                render();
            });
        });
        handle.attach(future);
        return handle;
    }

    /**
     * Форматирует расстояние в метрах или километрах для отображения в UI.
     *
     * @param meters расстояние в метрах
     * @return строка вида {@code 250 м} или {@code 1.25 км}
     */
    public static String formatDistance(double meters) {
        if (meters < 1000.0) {
            return String.format(Locale.ROOT, "%.0f м", meters);
        }
        return String.format(Locale.ROOT, "%.2f км", meters / 1000.0);
    }

    /**
     * Считает расстояние между двумя координатами по формуле гаверсинуса.
     *
     * @param a первая точка
     * @param b вторая точка
     * @return расстояние в метрах
     */
    public static double distanceMeters(GeoPoint a, GeoPoint b) {
        double radius = 6_371_000.0;
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double sinLat = Math.sin(dLat / 2.0);
        double sinLon = Math.sin(dLon / 2.0);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return 2.0 * radius * Math.atan2(Math.sqrt(h), Math.sqrt(1.0 - h));
    }

    /**
     * Обрабатывает прокрутку мыши как масштабирование относительно положения курсора.
     */
    private void handleScrollZoom(ScrollEvent event) {
        if (event.getDeltaY() == 0) {
            return;
        }
        int targetZoom = zoom + (event.getDeltaY() > 0 ? 1 : -1);
        zoomAround(event.getX(), event.getY(), targetZoom);
        event.consume();
    }

    /**
     * Меняет масштаб так, чтобы географическая точка под указанной экранной
     * позицией осталась под курсором после изменения масштаба.
     */
    private void zoomAround(double screenX, double screenY, int targetZoom) {
        int newZoom = clampZoom(targetZoom);
        if (newZoom == zoom || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        GeoPoint anchor = screenToGeo(screenX, screenY);
        double anchorX = lonToPixelX(anchor.longitude(), newZoom);
        double anchorY = latToPixelY(anchor.latitude(), newZoom);
        double centerX = anchorX - screenX + getWidth() / 2.0;
        double centerY = anchorY - screenY + getHeight() / 2.0;

        zoom = newZoom;
        centerLongitude = pixelXToLon(centerX, zoom);
        centerLatitude = pixelYToLat(centerY, zoom);
        normalizeCenter();
        render();
    }

    /**
     * Перерисовывает все слои карты: тайлы, маркеры, выделение, трейсы и линейку.
     */
    private void render() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        tileLayer.getChildren().clear();
        int tileCount = 1 << zoom;
        double centerX = lonToPixelX(centerLongitude, zoom);
        double centerY = latToPixelY(centerLatitude, zoom);
        double topLeftX = centerX - getWidth() / 2.0;
        double topLeftY = centerY - getHeight() / 2.0;
        int startX = (int) Math.floor(topLeftX / TILE_SIZE);
        int endX = (int) Math.floor((topLeftX + getWidth()) / TILE_SIZE);
        int startY = Math.max(0, (int) Math.floor(topLeftY / TILE_SIZE));
        int endY = Math.min(tileCount - 1, (int) Math.floor((topLeftY + getHeight()) / TILE_SIZE));

        for (int x = startX; x <= endX; x++) {
            int wrappedX = Math.floorMod(x, tileCount);
            for (int y = startY; y <= endY; y++) {
                TileKey key = new TileKey(zoom, wrappedX, y);
                Node tileNode = createTileNode(key);
                tileNode.setLayoutX(x * TILE_SIZE - topLeftX);
                tileNode.setLayoutY(y * TILE_SIZE - topLeftY);
                tileLayer.getChildren().add(tileNode);
            }
        }

        updateMarkerOverlay();
        updateAreaOverlay();
        updateTraceOverlay();
        updateMeasureOverlay();
        notifyStatus();
    }

    /**
     * Создаёт визуальный узел для одного тайла. Если тайл отсутствует локально,
     * показывает placeholder и запускает фоновую загрузку в онлайн-режиме.
     */
    private Node createTileNode(TileKey key) {
        StackPane tile = new StackPane();
        tile.getStyleClass().add("map-tile");
        tile.setMinSize(TILE_SIZE, TILE_SIZE);
        tile.setPrefSize(TILE_SIZE, TILE_SIZE);
        tile.setMaxSize(TILE_SIZE, TILE_SIZE);

        Image image = loadLocalTileImage(key);
        if (image != null) {
            ImageView imageView = new ImageView(displayTileImage(key, image));
            imageView.setFitWidth(TILE_SIZE);
            imageView.setFitHeight(TILE_SIZE);
            imageView.setSmooth(true);
            tile.getChildren().add(imageView);
            return tile;
        }

        tile.getStyleClass().add("map-tile-missing");
        Rectangle bg = new Rectangle(TILE_SIZE, TILE_SIZE);
        bg.getStyleClass().add("map-tile-missing-bg");
        Label label = new Label(key.zoom() + "/" + key.x() + "/" + key.y());
        label.getStyleClass().add("map-tile-missing-label");
        tile.getChildren().addAll(bg, label);

        if (!offlineOnly) {
            downloadTileIfNeeded(key);
        }

        return tile;
    }

    /**
     * Загружает тайл из памяти, внешнего каталога или встроенного кэша.
     *
     * @return изображение тайла или {@code null}, если тайл не найден
     */
    private Image loadLocalTileImage(TileKey key) {
        Image cached = memoryCache.get(key);
        if (cached != null) {
            return cached;
        }

        Path path = findLocalTile(key);
        if (path == null) {
            return null;
        }

        Image image = new Image(path.toUri().toString(), TILE_SIZE, TILE_SIZE, false, true, false);
        if (image.isError()) {
            return null;
        }
        memoryCache.put(key, image);
        return image;
    }

    /**
     * Возвращает изображение тайла в текущем визуальном режиме.
     * Для ночного режима используется отдельный кэш преобразованных изображений.
     */
    private Image displayTileImage(TileKey key, Image image) {
        if (!nightMode) {
            return image;
        }

        Image cached = nightMemoryCache.get(key);
        if (cached != null) {
            return cached;
        }

        Image nightImage = createNightTileImage(image);
        nightMemoryCache.put(key, nightImage);
        return nightImage;
    }

    /**
     * Создаёт ночную версию тайла попиксельным преобразованием палитры.
     * Такой подход одинаково работает для онлайн, кэшированных и внешних оффлайн-тайлов.
     */
    private Image createNightTileImage(Image source) {
        PixelReader reader = source.getPixelReader();
        if (reader == null) {
            return source;
        }

        int width = Math.max(1, (int) Math.round(source.getWidth()));
        int height = Math.max(1, (int) Math.round(source.getHeight()));
        WritableImage target = new WritableImage(width, height);
        PixelWriter writer = target.getPixelWriter();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                writer.setArgb(x, y, nightTileArgb(reader.getArgb(x, y)));
            }
        }
        return target;
    }

    /**
     * Преобразует цвет пикселя тайла в ночную сине-тёмную палитру,
     * сохраняя детали дорог и подписей через инверсию яркости.
     */
    private int nightTileArgb(int argb) {
        int alpha = (argb >>> 24) & 0xff;
        int red = (argb >>> 16) & 0xff;
        int green = (argb >>> 8) & 0xff;
        int blue = argb & 0xff;

        double luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0;
        double invertedDetail = 1.0 - luminance;
        double level = clamp(0.12 + invertedDetail * 0.72, 0.0, 1.0);

        int nightRed = colorByte(level * 0.58 + (255 - red) / 255.0 * 0.07);
        int nightGreen = colorByte(level * 0.76 + (255 - green) / 255.0 * 0.08);
        int nightBlue = colorByte(level + (255 - blue) / 255.0 * 0.08);
        return (alpha << 24) | (nightRed << 16) | (nightGreen << 8) | nightBlue;
    }

    /**
     * Переводит нормализованную компоненту цвета {@code 0..1} в байт {@code 0..255}.
     */
    private int colorByte(double value) {
        return (int) Math.round(clamp(value, 0.0, 1.0) * 255.0);
    }

    /**
     * Планирует фоновую загрузку тайла, если он ещё не загружается и отсутствует локально.
     */
    private void downloadTileIfNeeded(TileKey key) {
        if (inFlightDownloads.contains(key) || hasLocalTile(key)) {
            return;
        }
        if (!inFlightDownloads.add(key)) {
            return;
        }

        tileExecutor.submit(() -> {
            try {
                downloadTileFromNetwork(key);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Missing network/cache is represented by the placeholder tile.
            } finally {
                inFlightDownloads.remove(key);
                Platform.runLater(this::render);
            }
        });
    }

    /**
     * Загружает один тайл с публичного OSM-сервера и атомарно сохраняет его в локальный кэш.
     *
     * @return {@code true}, если тайл доступен локально после выполнения метода
     */
    private boolean downloadTileFromNetwork(TileKey key) throws IOException, InterruptedException {
        Path target = cachePath(key);
        if (Files.isRegularFile(target)) {
            return true;
        }

        Files.createDirectories(target.getParent());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://tile.openstreetmap.org/" + key.zoom() + "/" + key.x() + "/" + key.y() + ".png"))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent())
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
            return false;
        }

        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, response.body());
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        memoryCache.remove(key);
        nightMemoryCache.remove(key);
        return true;
    }

    /**
     * Формирует User-Agent для запросов к OSM с версией приложения.
     */
    private String userAgent() {
        String version = MeshApp.APPLICATION_VERSION != null ? MeshApp.APPLICATION_VERSION : "dev";
        return "MeshApp/" + version + " JavaFX OSM tile client";
    }

    /**
     * Проверяет наличие тайла во внешнем каталоге или встроенном кэше.
     */
    private boolean hasLocalTile(TileKey key) {
        return findLocalTile(key) != null;
    }

    /**
     * Ищет тайл сначала во внешнем каталоге, затем во встроенном кэше.
     */
    private Path findLocalTile(TileKey key) {
        Path external = findTileInRoot(externalTileRoot, key);
        if (external != null) {
            return external;
        }
        return findTileInRoot(CACHE_ROOT, key);
    }

    /**
     * Ищет файл тайла в каталоге формата {@code z/x/y.png|jpg|jpeg}.
     */
    private Path findTileInRoot(Path root, TileKey key) {
        if (root == null) {
            return null;
        }
        Path dir = root.resolve(Integer.toString(key.zoom())).resolve(Integer.toString(key.x()));
        for (String ext : TILE_EXTENSIONS) {
            Path file = dir.resolve(key.y() + ext);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    /**
     * Возвращает путь, по которому тайл должен храниться во встроенном кэше.
     */
    private Path cachePath(TileKey key) {
        return CACHE_ROOT
                .resolve(Integer.toString(key.zoom()))
                .resolve(Integer.toString(key.x()))
                .resolve(key.y() + ".png");
    }

    /**
     * Рассчитывает ключи тайлов, которые пересекают текущий viewport карты.
     */
    private List<TileKey> visibleTileKeys() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return List.of();
        }

        int tileCount = 1 << zoom;
        double centerX = lonToPixelX(centerLongitude, zoom);
        double centerY = latToPixelY(centerLatitude, zoom);
        double topLeftX = centerX - getWidth() / 2.0;
        double topLeftY = centerY - getHeight() / 2.0;
        int startX = (int) Math.floor(topLeftX / TILE_SIZE);
        int endX = (int) Math.floor((topLeftX + getWidth()) / TILE_SIZE);
        int startY = Math.max(0, (int) Math.floor(topLeftY / TILE_SIZE));
        int endY = Math.min(tileCount - 1, (int) Math.floor((topLeftY + getHeight()) / TILE_SIZE));

        List<TileKey> keys = new ArrayList<>();
        for (int x = startX; x <= endX; x++) {
            int wrappedX = Math.floorMod(x, tileCount);
            for (int y = startY; y <= endY; y++) {
                TileKey key = new TileKey(zoom, wrappedX, y);
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /**
     * Рассчитывает план загрузки тайлов без материализации каждого ключа.
     */
    private TileDownloadPlan downloadTilePlan() {
        if (selectedArea == null) {
            return TileDownloadPlan.empty();
        }
        return areaTilePlan(selectedArea, MIN_ZOOM, MAX_ZOOM);
    }

    /**
     * Рассчитывает диапазоны тайлов, пересекающих географическую область.
     */
    private static TileDownloadPlan areaTilePlan(GeoBounds area, int minZoom, int maxZoom) {
        if (area == null) {
            return TileDownloadPlan.empty();
        }

        List<TileRange> ranges = new ArrayList<>();
        long totalTiles = 0;
        int startZoom = clampZoom(minZoom);
        int endZoom = clampZoom(maxZoom);
        if (startZoom > endZoom) {
            return TileDownloadPlan.empty();
        }

        for (int tileZoom = startZoom; tileZoom <= endZoom; tileZoom++) {
            int tileCount = 1 << tileZoom;
            double westTile = lonToPixelX(area.west(), tileZoom) / TILE_SIZE;
            double eastTile = lonToPixelX(area.east(), tileZoom) / TILE_SIZE;
            double northTile = latToPixelY(area.north(), tileZoom) / TILE_SIZE;
            double southTile = latToPixelY(area.south(), tileZoom) / TILE_SIZE;

            int startX = clampTileIndex((int) Math.floor(Math.min(westTile, eastTile)), tileCount);
            int endX = clampTileIndex((int) Math.floor(Math.max(westTile, eastTile)), tileCount);
            int startY = clampTileIndex((int) Math.floor(Math.min(northTile, southTile)), tileCount);
            int endY = clampTileIndex((int) Math.floor(Math.max(northTile, southTile)), tileCount);

            TileRange range = new TileRange(tileZoom, startX, endX, startY, endY);
            ranges.add(range);
            totalTiles += range.count();
        }
        return new TileDownloadPlan(List.copyOf(ranges), totalTiles);
    }

    /**
     * Ограничивает индекс тайла валидным диапазоном для масштаба.
     */
    private static int clampTileIndex(int value, int tileCount) {
        return Math.max(0, Math.min(tileCount - 1, value));
    }

    /**
     * Перерисовывает слой маркеров, оставляя только валидные и близкие к viewport ноды.
     */
    private void updateMarkerOverlay() {
        markerLayer.getChildren().clear();
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        for (MapMarker marker : markers) {
            if (!isValidCoordinate(marker.latitude(), marker.longitude())) {
                continue;
            }
            Point2D screen = geoToScreen(marker.latitude(), marker.longitude());
            if (screen.getX() < -64 || screen.getX() > getWidth() + 64
                    || screen.getY() < -64 || screen.getY() > getHeight() + 64) {
                continue;
            }
            StackPane markerNode = createMarkerNode(marker);
            markerNode.setLayoutX(screen.getX() - MARKER_SIZE / 2.0);
            markerNode.setLayoutY(screen.getY() - MARKER_SIZE / 2.0);
            markerLayer.getChildren().add(markerNode);
        }
    }

    /**
     * Перерисовывает текущую выбранную область и активное прямоугольное выделение.
     */
    private void updateAreaOverlay() {
        areaLayer.getChildren().clear();
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        if (selectedArea != null) {
            Rectangle selectedRect = rectangleForArea(selectedArea);
            selectedRect.getStyleClass().add("map-selected-area");
            areaLayer.getChildren().add(selectedRect);
        }

        if (areaSelectionActive) {
            Rectangle selectionRect = rectangleForScreenBounds(areaStartX, areaStartY, areaEndX, areaEndY);
            selectionRect.getStyleClass().add("map-area-selection");
            areaLayer.getChildren().add(selectionRect);
        }
    }

    /**
     * Перерисовывает сегменты трейсов, подписи SNR/хопов и стрелки направления.
     */
    private void updateTraceOverlay() {
        traceLayer.getChildren().clear();
        if (getWidth() <= 0 || getHeight() <= 0 || traceSegments.isEmpty()) {
            return;
        }

        for (TraceSegment segment : traceSegments) {
            if (!isValidCoordinate(segment.from().latitude(), segment.from().longitude())
                    || !isValidCoordinate(segment.to().latitude(), segment.to().longitude())) {
                continue;
            }
            Point2D from = geoToScreen(segment.from().latitude(), segment.from().longitude());
            Point2D to = geoToScreen(segment.to().latitude(), segment.to().longitude());
            double dx = to.getX() - from.getX();
            double dy = to.getY() - from.getY();
            double length = Math.hypot(dx, dy);
            if (length < 1.0) {
                continue;
            }

            double canonicalDx = shouldFlipTraceNormal(segment) ? -dx : dx;
            double canonicalDy = shouldFlipTraceNormal(segment) ? -dy : dy;
            double nx = -canonicalDy / length;
            double ny = canonicalDx / length;
            double traceOffset = ((segment.traceIndex() % 5) - 2) * 18.0;
            double directionOffset = segment.reverse() ? 7.0 : -7.0;
            double offset = traceOffset + directionOffset;
            double x1 = from.getX() + nx * offset;
            double y1 = from.getY() + ny * offset;
            double x2 = to.getX() + nx * offset;
            double y2 = to.getY() + ny * offset;
            String color = traceColor(segment.reverse());

            Line line = new Line(x1, y1, x2, y2);
            line.getStyleClass().add("map-trace-line");
            if (segment.reverse()) {
                line.getStyleClass().add("map-trace-line-reverse");
            }
            line.setStyle("-fx-stroke: " + color + ";");
            traceLayer.getChildren().add(line);

            addTraceArrow(x1, y1, x2, y2, 0.42, color);
            if (length > 96.0) {
                addTraceArrow(x1, y1, x2, y2, 0.68, color);
            }

            Label signalLabel = new Label(segment.signalText());
            signalLabel.getStyleClass().add("map-trace-signal-label");
            signalLabel.setStyle("-fx-border-color: " + color + ";");
            signalLabel.applyCss();
            signalLabel.autosize();
            signalLabel.setLayoutX((x1 + x2) / 2.0 + nx * 12.0 - signalLabel.prefWidth(-1) / 2.0);
            signalLabel.setLayoutY((y1 + y2) / 2.0 + ny * 12.0 - signalLabel.prefHeight(-1) / 2.0);
            traceLayer.getChildren().add(signalLabel);
        }
    }

    /**
     * Выбирает стабильную сторону смещения для линии трейса.
     * Это позволяет прямому и обратному направлениям не накладываться друг на друга.
     */
    private boolean shouldFlipTraceNormal(TraceSegment segment) {
        int latCompare = Double.compare(segment.from().latitude(), segment.to().latitude());
        if (latCompare != 0) {
            return latCompare > 0;
        }
        int lonCompare = Double.compare(segment.from().longitude(), segment.to().longitude());
        if (lonCompare != 0) {
            return lonCompare > 0;
        }
        return segment.fromTitle().compareToIgnoreCase(segment.toTitle()) > 0;
    }

    /**
     * Добавляет стрелку направления на сегмент трейса.
     *
     * @param position доля длины сегмента, где будет расположена стрелка
     */
    private void addTraceArrow(double x1, double y1, double x2, double y2, double position, String color) {
        double x = x1 + (x2 - x1) * position;
        double y = y1 + (y2 - y1) * position;
        Polygon arrow = new Polygon(-6, -5, 7, 0, -6, 5);
        arrow.getStyleClass().add("map-trace-arrow");
        arrow.setStyle("-fx-fill: " + color + ";");
        arrow.setLayoutX(x);
        arrow.setLayoutY(y);
        arrow.setRotate(Math.toDegrees(Math.atan2(y2 - y1, x2 - x1)));
        traceLayer.getChildren().add(arrow);
    }

    /**
     * Возвращает цвет линии трейса для прямого или обратного направления.
     */
    private String traceColor(boolean reverse) {
        return reverse ? TRACE_REVERSE_COLOR : TRACE_FORWARD_COLOR;
    }

    /**
     * Строит экранный прямоугольник для географических границ выбранной области.
     */
    private Rectangle rectangleForArea(GeoBounds area) {
        Point2D topLeft = geoToScreen(area.north(), area.west());
        Point2D bottomRight = geoToScreen(area.south(), area.east());
        return rectangleForScreenBounds(topLeft.getX(), topLeft.getY(), bottomRight.getX(), bottomRight.getY());
    }

    /**
     * Создаёт JavaFX-прямоугольник по двум экранным точкам независимо от направления drag.
     */
    private Rectangle rectangleForScreenBounds(double x1, double y1, double x2, double y2) {
        double x = Math.min(x1, x2);
        double y = Math.min(y1, y2);
        double width = Math.abs(x2 - x1);
        double height = Math.abs(y2 - y1);
        Rectangle rect = new Rectangle(x, y, width, height);
        rect.setMouseTransparent(true);
        return rect;
    }

    /**
     * Завершает выделение области: сохраняет географические границы и масштабирует карту к ним.
     */
    private void finishAreaSelection() {
        double width = Math.abs(areaEndX - areaStartX);
        double height = Math.abs(areaEndY - areaStartY);
        if (width < 18 || height < 18) {
            updateAreaOverlay();
            notifyAreaSelection();
            return;
        }

        GeoPoint first = screenToGeo(areaStartX, areaStartY);
        GeoPoint second = screenToGeo(areaEndX, areaEndY);
        selectedArea = GeoBounds.from(first, second);
        fitArea(selectedArea);
        notifyAreaSelection();
    }

    /**
     * Подбирает центр и масштаб так, чтобы выбранная область поместилась в viewport.
     */
    private void fitArea(GeoBounds area) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        double availableWidth = Math.max(180, getWidth() - 80);
        double availableHeight = Math.max(140, getHeight() - 80);
        int bestZoom = MIN_ZOOM;
        double bestCenterX = 0;
        double bestCenterY = 0;

        for (int candidateZoom = MAX_ZOOM; candidateZoom >= MIN_ZOOM; candidateZoom--) {
            double westX = lonToPixelX(area.west(), candidateZoom);
            double eastX = lonToPixelX(area.east(), candidateZoom);
            double northY = latToPixelY(area.north(), candidateZoom);
            double southY = latToPixelY(area.south(), candidateZoom);
            double width = Math.abs(eastX - westX);
            double height = Math.abs(southY - northY);
            if (width <= availableWidth && height <= availableHeight) {
                bestZoom = candidateZoom;
                bestCenterX = Math.min(westX, eastX) + width / 2.0;
                bestCenterY = Math.min(northY, southY) + height / 2.0;
                break;
            }
            if (candidateZoom == MIN_ZOOM) {
                bestCenterX = Math.min(westX, eastX) + width / 2.0;
                bestCenterY = Math.min(northY, southY) + height / 2.0;
            }
        }

        zoom = bestZoom;
        centerLongitude = pixelXToLon(bestCenterX, zoom);
        centerLatitude = pixelYToLat(bestCenterY, zoom);
        normalizeCenter();
        render();
    }

    /**
     * Создаёт круглый маркер ноды с адаптивным размером текста и подсказкой координат.
     */
    private StackPane createMarkerNode(MapMarker marker) {
        String markerText = sanitizeMarkerText(marker.shortTitle());
        Label label = new Label(markerText);
        label.getStyleClass().add("map-node-marker-label");
        label.setAlignment(Pos.CENTER);
        label.setTextOverrun(OverrunStyle.CLIP);
        label.setMinSize(MARKER_LABEL_SIZE, MARKER_LABEL_SIZE);
        label.setPrefSize(MARKER_LABEL_SIZE, MARKER_LABEL_SIZE);
        label.setMaxSize(MARKER_LABEL_SIZE, MARKER_LABEL_SIZE);
        label.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, fitMarkerFontSize(markerText)));

        StackPane pin = new StackPane(label);
        pin.getStyleClass().add("map-node-marker");
        if (marker.local()) {
            pin.getStyleClass().add("map-node-marker-local");
        }
        pin.setCursor(measuring || areaSelectionMode ? Cursor.CROSSHAIR : Cursor.HAND);
        pin.setMinSize(MARKER_SIZE, MARKER_SIZE);
        pin.setPrefSize(MARKER_SIZE, MARKER_SIZE);
        pin.setMaxSize(MARKER_SIZE, MARKER_SIZE);
        pin.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || measuring || areaSelectionMode || dragged) {
                return;
            }
            markerClickListener.accept(marker);
            event.consume();
        });

        String tooltipText = marker.title()
                + "\n" + formatCoordinate(marker.latitude(), marker.longitude());
        Tooltip.install(pin, new Tooltip(tooltipText));
        return pin;
    }

    /**
     * Подготавливает короткий текст маркера: пустое значение заменяется точкой,
     * длинное значение ограничивается четырьмя Unicode-символами.
     */
    private String sanitizeMarkerText(String text) {
        if (text == null || text.isBlank()) {
            return "•";
        }
        String trimmed = text.trim();
        if (trimmed.codePointCount(0, trimmed.length()) <= 4) {
            return trimmed;
        }
        StringBuilder result = new StringBuilder();
        trimmed.codePoints().limit(4).forEach(result::appendCodePoint);
        return result.toString();
    }

    /**
     * Подбирает максимальный размер шрифта, при котором текст помещается внутри маркера.
     */
    private double fitMarkerFontSize(String text) {
        if (text == null || text.isBlank()) {
            return MARKER_MAX_FONT_SIZE;
        }
        for (double size = MARKER_MAX_FONT_SIZE; size >= MARKER_MIN_FONT_SIZE; size -= 0.5) {
            Text probe = new Text(text);
            probe.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, size));
            if (probe.getLayoutBounds().getWidth() <= MARKER_LABEL_SIZE - 2
                    && probe.getLayoutBounds().getHeight() <= MARKER_LABEL_SIZE - 2) {
                return size;
            }
        }
        return MARKER_MIN_FONT_SIZE;
    }

    /**
     * Перерисовывает линию измерения, точки кликов и подписи расстояний между соседними точками.
     */
    private void updateMeasureOverlay() {
        measureLayer.getChildren().clear();
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        if (measurePoints.size() >= 2) {
            Polyline line = new Polyline();
            line.getStyleClass().add("map-measure-line");
            for (GeoPoint point : measurePoints) {
                Point2D screen = geoToScreen(point.latitude(), point.longitude());
                line.getPoints().addAll(screen.getX(), screen.getY());
            }
            measureLayer.getChildren().add(line);
        }

        for (int i = 0; i < measurePoints.size(); i++) {
            GeoPoint point = measurePoints.get(i);
            Point2D screen = geoToScreen(point.latitude(), point.longitude());
            Circle circle = new Circle(5);
            circle.getStyleClass().add("map-measure-point");
            circle.setCenterX(screen.getX());
            circle.setCenterY(screen.getY());
            measureLayer.getChildren().add(circle);

            if (i > 0) {
                GeoPoint previous = measurePoints.get(i - 1);
                Point2D previousScreen = geoToScreen(previous.latitude(), previous.longitude());
                double distance = distanceMeters(previous, point);
                Label segment = new Label(formatDistance(distance));
                segment.getStyleClass().add("map-measure-segment-label");
                segment.setLayoutX((previousScreen.getX() + screen.getX()) / 2.0 + 6);
                segment.setLayoutY((previousScreen.getY() + screen.getY()) / 2.0 + 6);
                measureLayer.getChildren().add(segment);
            }
        }
    }

    /**
     * Отправляет наружу актуальный текст статуса карты.
     */
    private void notifyStatus() {
        statusListener.accept("z" + zoom + " · " + visibleTileCount() + " тайлов · "
                + (offlineOnly ? "оффлайн" : "онлайн/кэш")
                + (nightMode ? " · ночь" : ""));
    }

    /**
     * Отправляет наружу актуальное состояние линейки и суммарную длину маршрута.
     */
    private void notifyMeasure() {
        if (measurePoints.isEmpty()) {
            measureListener.accept(measuring ? "Кликните по карте, чтобы поставить первую точку" : "Измерение выключено");
            return;
        }

        double total = 0;
        for (int i = 1; i < measurePoints.size(); i++) {
            total += distanceMeters(measurePoints.get(i - 1), measurePoints.get(i));
        }
        if (measurePoints.size() == 1) {
            measureListener.accept("1 точка · добавьте следующую точку");
        } else {
            measureListener.accept(measurePoints.size() + " точек · " + formatDistance(total));
        }
    }

    /**
     * Отправляет наружу состояние выделения и размеры выбранной области.
     */
    private void notifyAreaSelection() {
        if (areaSelectionActive) {
            areaSelectionListener.accept("Область: выделение...");
            return;
        }
        if (selectedArea == null) {
            areaSelectionListener.accept(areaSelectionMode ? "Область: протяните прямоугольник" : "Область не выбрана");
            return;
        }

        double centerLat = (selectedArea.north() + selectedArea.south()) / 2.0;
        double centerLon = (selectedArea.west() + selectedArea.east()) / 2.0;
        double widthMeters = distanceMeters(
                new GeoPoint(centerLat, selectedArea.west()),
                new GeoPoint(centerLat, selectedArea.east())
        );
        double heightMeters = distanceMeters(
                new GeoPoint(selectedArea.north(), centerLon),
                new GeoPoint(selectedArea.south(), centerLon)
        );
        areaSelectionListener.accept("Область: "
                + formatDistance(widthMeters) + " x " + formatDistance(heightMeters));
    }

    /**
     * Переводит экранные координаты внутри компонента в широту и долготу.
     */
    private GeoPoint screenToGeo(double screenX, double screenY) {
        double centerX = lonToPixelX(centerLongitude, zoom);
        double centerY = latToPixelY(centerLatitude, zoom);
        double worldX = centerX + screenX - getWidth() / 2.0;
        double worldY = centerY + screenY - getHeight() / 2.0;
        return new GeoPoint(pixelYToLat(worldY, zoom), pixelXToLon(worldX, zoom));
    }

    /**
     * Переводит географические координаты в экранную точку внутри компонента.
     */
    private Point2D geoToScreen(double latitude, double longitude) {
        double centerX = lonToPixelX(centerLongitude, zoom);
        double centerY = latToPixelY(centerLatitude, zoom);
        double x = lonToPixelX(longitude, zoom) - centerX + getWidth() / 2.0;
        double y = latToPixelY(latitude, zoom) - centerY + getHeight() / 2.0;
        return new Point2D(x, y);
    }

    /**
     * Рассчитывает пиксельные границы всех маркеров для заданного масштаба.
     */
    private Bounds markerBounds(int candidateZoom) {
        List<GeoPoint> points = new ArrayList<>();
        for (MapMarker marker : markers) {
            points.add(new GeoPoint(marker.latitude(), marker.longitude()));
        }
        return pointBounds(points, candidateZoom);
    }

    /**
     * Рассчитывает пиксельные границы произвольного набора географических точек.
     */
    private Bounds pointBounds(List<GeoPoint> points, int candidateZoom) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (GeoPoint point : points) {
            double x = lonToPixelX(point.longitude(), candidateZoom);
            double y = latToPixelY(point.latitude(), candidateZoom);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        return new Bounds(minX, minY, maxX, maxY);
    }

    /**
     * Нормализует центр карты в допустимые диапазоны широты и долготы.
     */
    private void normalizeCenter() {
        centerLatitude = clampLatitude(centerLatitude);
        centerLongitude = normalizeLongitude(centerLongitude);
    }

    /**
     * Проверяет координаты на допустимый диапазон и отбрасывает значение {@code 0,0}
     * как отсутствие координат у ноды.
     */
    private static boolean isValidCoordinate(double latitude, double longitude) {
        return (latitude != 0 || longitude != 0)
                && latitude >= -90
                && latitude <= 90
                && longitude >= -180
                && longitude <= 180;
    }

    /**
     * Переводит долготу в глобальную пиксельную координату Web Mercator.
     */
    private static double lonToPixelX(double longitude, int zoom) {
        double worldSize = worldSize(zoom);
        return (normalizeLongitude(longitude) + 180.0) / 360.0 * worldSize;
    }

    /**
     * Переводит широту в глобальную пиксельную координату Web Mercator.
     */
    private static double latToPixelY(double latitude, int zoom) {
        double lat = Math.toRadians(clampLatitude(latitude));
        double worldSize = worldSize(zoom);
        double mercator = Math.log(Math.tan(Math.PI / 4.0 + lat / 2.0));
        return (0.5 - mercator / (2.0 * Math.PI)) * worldSize;
    }

    /**
     * Переводит глобальную пиксельную X-координату Web Mercator в долготу.
     */
    private static double pixelXToLon(double pixelX, int zoom) {
        double worldSize = worldSize(zoom);
        double x = pixelX % worldSize;
        if (x < 0) {
            x += worldSize;
        }
        return x / worldSize * 360.0 - 180.0;
    }

    /**
     * Переводит глобальную пиксельную Y-координату Web Mercator в широту.
     */
    private static double pixelYToLat(double pixelY, int zoom) {
        double worldSize = worldSize(zoom);
        double y = Math.max(0, Math.min(worldSize, pixelY));
        double n = Math.PI - 2.0 * Math.PI * y / worldSize;
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /**
     * Возвращает размер мира Web Mercator в пикселях на указанном масштабе.
     */
    private static double worldSize(int zoom) {
        return TILE_SIZE * (double) (1 << zoom);
    }

    /**
     * Ограничивает широту диапазоном, поддерживаемым Web Mercator.
     */
    private static double clampLatitude(double latitude) {
        if (Double.isNaN(latitude)) {
            return 0;
        }
        return Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
    }

    /**
     * Нормализует долготу в диапазон {@code -180..180}.
     */
    private static double normalizeLongitude(double longitude) {
        if (Double.isNaN(longitude)) {
            return 0;
        }
        double normalized = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return normalized == -180.0 ? 180.0 : normalized;
    }

    /**
     * Ограничивает масштаб поддерживаемым диапазоном OSM.
     */
    private static int clampZoom(int zoom) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    /**
     * Ограничивает число указанным диапазоном.
     */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Форматирует координату для статусной строки карты.
     *
     * @param latitude  широта
     * @param longitude долгота
     * @return строка с шестью знаками после запятой
     */
    public static String formatCoordinate(double latitude, double longitude) {
        return String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude);
    }

    /**
     * Географическая точка в градусах WGS84.
     *
     * @param latitude  широта
     * @param longitude долгота
     */
    public record GeoPoint(double latitude, double longitude) {
    }

    /**
     * Прогресс загрузки тайлов выбранной области.
     *
     * @param completed количество обработанных тайлов
     * @param total     общее количество тайлов
     * @param available количество тайлов, которые доступны локально после обработки
     * @param message   пользовательский текст прогресса
     * @param state     текущее состояние загрузки
     */
    public record DownloadProgress(long completed, long total, long available, String message, DownloadState state) {
    }

    /**
     * Состояние фоновой загрузки тайлов.
     */
    public enum DownloadState {
        RUNNING, CANCELLED, COMPLETED
    }

    /**
     * Управляет активной фоновой загрузкой тайлов.
     */
    public static final class DownloadHandle {
        private final AtomicBoolean paused = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final Object pauseLock = new Object();
        private volatile Future<?> future;

        private static DownloadHandle inactive() {
            return new DownloadHandle();
        }

        private void attach(Future<?> future) {
            this.future = future;
        }

        public void pause() {
            if (!cancelled.get()) {
                paused.set(true);
            }
        }

        public void resume() {
            paused.set(false);
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }

        public void cancel() {
            cancelled.set(true);
            resume();
            Future<?> task = future;
            if (task != null) {
                task.cancel(true);
            }
        }

        public boolean isPaused() {
            return paused.get();
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        private void awaitIfPaused() throws InterruptedException {
            synchronized (pauseLock) {
                while (paused.get() && !cancelled.get()) {
                    pauseLock.wait();
                }
            }
        }
    }

    /**
     * Один визуальный сегмент трейса между двумя нодами с координатами.
     *
     * @param from       начальная точка сегмента
     * @param to         конечная точка сегмента
     * @param fromTitle  имя начальной ноды
     * @param toTitle    имя конечной ноды
     * @param traceTitle название целевого трейса
     * @param signalText подпись сигнала, направления и количества хопов
     * @param snr        числовой SNR или {@link Double#NaN}, если данных нет
     * @param reverse    {@code true} для обратного направления
     * @param traceIndex индекс выбранного трейса для разведения линий
     */
    public record TraceSegment(
            GeoPoint from,
            GeoPoint to,
            String fromTitle,
            String toTitle,
            String traceTitle,
            String signalText,
            double snr,
            boolean reverse,
            int traceIndex
    ) {
    }

    /**
     * Географические границы прямоугольной области.
     */
    private record GeoBounds(double north, double south, double west, double east) {
        /**
         * Создаёт границы из двух произвольных углов прямоугольника.
         */
        static GeoBounds from(GeoPoint first, GeoPoint second) {
            double north = Math.max(first.latitude(), second.latitude());
            double south = Math.min(first.latitude(), second.latitude());
            double west = Math.min(first.longitude(), second.longitude());
            double east = Math.max(first.longitude(), second.longitude());
            return new GeoBounds(north, south, west, east);
        }
    }

    /**
     * План оффлайн-загрузки, представленный диапазонами тайлов вместо полного списка ключей.
     */
    private record TileDownloadPlan(List<TileRange> ranges, long totalTiles) {
        static TileDownloadPlan empty() {
            return new TileDownloadPlan(List.of(), 0);
        }

        boolean isEmpty() {
            return totalTiles <= 0 || ranges.isEmpty();
        }
    }

    /**
     * Прямоугольный диапазон тайлов одного масштаба.
     */
    private record TileRange(int zoom, int startX, int endX, int startY, int endY) {
        long count() {
            return ((long) endX - startX + 1) * ((long) endY - startY + 1);
        }
    }

    /**
     * Ключ тайла OSM в схеме {@code z/x/y}.
     */
    private record TileKey(int zoom, int x, int y) {
        private TileKey {
            if (zoom < MIN_ZOOM || zoom > MAX_ZOOM) {
                throw new IllegalArgumentException("Unsupported zoom: " + zoom);
            }
        }
    }

    /**
     * Пиксельные границы набора точек на конкретном масштабе.
     */
    private record Bounds(double minX, double minY, double maxX, double maxY) {
        /** @return ширина границ в пикселях */
        double width() {
            return maxX - minX;
        }

        /** @return высота границ в пикселях */
        double height() {
            return maxY - minY;
        }

        /** @return X-координата центра границ */
        double centerX() {
            return minX + width() / 2.0;
        }

        /** @return Y-координата центра границ */
        double centerY() {
            return minY + height() / 2.0;
        }
    }
}
