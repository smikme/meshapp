package com.meshtastic.client.components.map;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.i18n.I18n;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * JavaFX OSM map component with online tiles, a local cache, an external
 * offline tile directory, and custom overlays.
 * <p>
 * The component owns tile loading, zooming, panning, night mode, node markers,
 * distance measurement, area selection, and trace visualization.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class TileMapView extends StackPane {

    /** Size of one OSM tile, in pixels. */
    public static final int TILE_SIZE = 256;
    /** Minimum supported map zoom level. */
    public static final int MIN_ZOOM = 1;
    /** Maximum supported map zoom level. */
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
    static final String PROJECT_URL = "https://github.com/smikme/meshapp";
    static final String CONTACT_EMAIL = "ks@privatepractice.app";
    private static final String[] TILE_EXTENSIONS = {".png", ".jpg", ".jpeg"};
    private static final Path CACHE_BASE = Paths.get(
            System.getProperty("user.home", "."),
            ".meshapp",
            "map-tiles"
    );

    private final Pane tileLayer = new Pane();
    private final Pane areaLayer = new Pane();
    private final Pane traceLayer = new Pane();
    private final Pane markerLayer = new Pane();
    private final Pane measureLayer = new Pane();
    private final Label attributionLabel = new Label();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_2)
            .build();
    private final ExecutorService tileExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "meshapp-tile-loader");
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
    private volatile Set<TileKey> visibleTiles = Set.of();
    private final List<GeoPoint> measurePoints = new ArrayList<>();

    private List<MapMarker> markers = List.of();
    private List<TraceSegment> traceSegments = List.of();
    private TileSource tileSource = TileSource.configured(System.getProperties());
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
     * Creates the map and wires mouse handlers for panning, zooming,
     * measurements, and rectangular area selection.
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
        attributionLabel.setText(tileSource.attribution());

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
     * Sets the listener that receives the compact map status: zoom, tile count,
     * online/offline mode, and night mode.
     *
     * @param statusListener receives status text
     */
    public void setStatusListener(Consumer<String> statusListener) {
        this.statusListener = statusListener != null ? statusListener : text -> {};
        notifyStatus();
    }

    /**
     * Sets the listener that receives the geographic point under the mouse pointer.
     *
     * @param pointerListener receives geographic points
     */
    public void setPointerListener(Consumer<GeoPoint> pointerListener) {
        this.pointerListener = pointerListener != null ? pointerListener : point -> {};
    }

    /**
     * Sets the listener that receives the distance-measurement status text.
     *
     * @param measureListener receives measurement text
     */
    public void setMeasureListener(Consumer<String> measureListener) {
        this.measureListener = measureListener != null ? measureListener : text -> {};
        notifyMeasure();
    }

    /**
     * Sets the listener that receives the selected-area status text.
     *
     * @param areaSelectionListener receives area-selection text
     */
    public void setAreaSelectionListener(Consumer<String> areaSelectionListener) {
        this.areaSelectionListener = areaSelectionListener != null ? areaSelectionListener : text -> {};
        notifyAreaSelection();
    }

    /**
     * Sets the handler invoked when a node marker is clicked.
     *
     * @param markerClickListener receives the selected marker
     */
    public void setMarkerClickListener(Consumer<MapMarker> markerClickListener) {
        this.markerClickListener = markerClickListener != null ? markerClickListener : marker -> {};
    }

    /**
     * Centers the map at the given point and applies the requested zoom level.
     *
     * @param latitude  center latitude
     * @param longitude center longitude
     * @param zoom      OSM zoom level
     */
    public void setView(double latitude, double longitude, int zoom) {
        this.centerLatitude = clampLatitude(latitude);
        this.centerLongitude = normalizeLongitude(longitude);
        this.zoom = clampZoom(zoom);
        render();
    }

    /** @return latitude of the current map center */
    public double getCenterLatitude() {
        return centerLatitude;
    }

    /** @return longitude of the current map center */
    public double getCenterLongitude() {
        return centerLongitude;
    }

    /** @return current OSM zoom level */
    public int getZoom() {
        return zoom;
    }

    /** Zooms in around the center of the visible area. */
    public void zoomIn() {
        zoomAround(getWidth() / 2.0, getHeight() / 2.0, zoom + 1);
    }

    /** Zooms out around the center of the visible area. */
    public void zoomOut() {
        zoomAround(getWidth() / 2.0, getHeight() / 2.0, zoom - 1);
    }

    /**
     * Enables or disables the mode that uses only local tiles.
     *
     * @param offlineOnly {@code true} to avoid network requests to OSM
     */
    public void setOfflineOnly(boolean offlineOnly) {
        this.offlineOnly = offlineOnly;
        render();
    }

    /** @return {@code true} when the map is using local tiles only */
    public boolean isOfflineOnly() {
        return offlineOnly;
    }

    /**
     * Enables map night mode. Night mode transforms tiles only; markers,
     * traces, the ruler, and the selected area are left unchanged.
     *
     * @param nightMode {@code true} to render tiles with the night palette
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

    /** @return {@code true} when tile night mode is enabled */
    public boolean isNightMode() {
        return nightMode;
    }

    /**
     * Sets the external offline tile directory in {@code z/x/y.png|jpg|jpeg} format.
     *
     * @param externalTileRoot tile root directory, or {@code null}
     */
    public void setExternalTileRoot(Path externalTileRoot) {
        this.externalTileRoot = externalTileRoot;
        memoryCache.clear();
        nightMemoryCache.clear();
        render();
    }

    /** @return current external tile directory, or {@code null} */
    public Path getExternalTileRoot() {
        return externalTileRoot;
    }

    /**
     * Enables or disables distance-measurement mode.
     * Enabling it turns off area-selection mode.
     *
     * @param measuring {@code true} to add ruler points with map clicks
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

    /** @return {@code true} when the ruler is active */
    public boolean isMeasuring() {
        return measuring;
    }

    /** Clears all ruler points and refreshes the measurement overlay. */
    public void clearMeasure() {
        measurePoints.clear();
        updateMeasureOverlay();
        notifyMeasure();
    }

    /**
     * Enables or disables rectangular area-selection mode.
     * Enabling it turns off the ruler.
     *
     * @param areaSelectionMode {@code true} to select an area by dragging
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

    /** @return {@code true} when area-selection mode is active */
    public boolean isAreaSelectionMode() {
        return areaSelectionMode;
    }

    /** Clears the selected area and its visual overlay. */
    public void clearSelectedArea() {
        selectedArea = null;
        areaSelectionActive = false;
        updateAreaOverlay();
        notifyAreaSelection();
    }

    /**
     * Replaces the set of node markers displayed on the map.
     *
     * @param markers markers to display
     */
    public void setMarkers(List<MapMarker> markers) {
        this.markers = markers != null ? List.copyOf(markers) : List.of();
        updateMarkerOverlay();
    }

    /**
     * Replaces the set of trace segments.
     *
     * @param traceSegments connection segments between nodes
     */
    public void setTraceSegments(List<TraceSegment> traceSegments) {
        this.traceSegments = traceSegments != null ? List.copyOf(traceSegments) : List.of();
        updateTraceOverlay();
    }

    /** Hides all traces from the map. */
    public void clearTraceSegments() {
        this.traceSegments = List.of();
        updateTraceOverlay();
    }

    /**
     * Chooses a center and zoom level that fit the selected traces on the map.
     *
     * @return {@code true} if there are coordinates to fit
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
     * Chooses a center and zoom level that fit all node markers on the map.
     *
     * @return {@code true} if there are markers to fit
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

    /** @return number of tiles visible in the current viewport */
    public int visibleTileCount() {
        return visibleTileKeys().size();
    }

    /**
     * Returns the built-in cache directory dedicated to the current source.
     *
     * @return source-specific local tile cache path
     */
    public Path cacheRoot() {
        return CACHE_BASE.resolve(tileSource.id());
    }

    /**
     * Returns the source used for interactive network requests.
     *
     * @return current tile source
     */
    public TileSource getTileSource() {
        return tileSource;
    }

    /**
     * Changes the source used for interactive viewport requests. This component
     * intentionally exposes no bulk-download operation, regardless of source.
     * Existing external offline tiles remain connected and continue to take priority.
     *
     * @param tileSource new interactive tile source
     * @throws NullPointerException if {@code tileSource} is {@code null}
     */
    public void setTileSource(TileSource tileSource) {
        this.tileSource = java.util.Objects.requireNonNull(tileSource, "tileSource");
        attributionLabel.setText(tileSource.attribution());
        memoryCache.clear();
        nightMemoryCache.clear();
        render();
    }

    /**
     * Formats a distance in meters or kilometers for UI display.
     *
     * @param meters distance in meters
     * @return a string such as {@code 250 m} or {@code 1.25 km}
     */
    public static String formatDistance(double meters) {
        if (meters < 1000.0) {
            return I18n.t("map.distance.meters", String.format(I18n.locale(), "%.0f", meters));
        }
        return I18n.t("map.distance.kilometers", String.format(I18n.locale(), "%.2f", meters / 1000.0));
    }

    /**
     * Computes the distance between two coordinates with the haversine formula.
     *
     * @param a first point
     * @param b second point
     * @return distance in meters
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
     * Handles mouse-wheel scrolling as zooming around the cursor position.
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
     * Changes the zoom level while keeping the geographic point under the given
     * screen position beneath the cursor.
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
     * Redraws all map layers: tiles, markers, selection, traces, and ruler.
     */
    private void render() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            visibleTiles = Set.of();
            return;
        }

        tileLayer.getChildren().clear();
        visibleTiles = Set.copyOf(new HashSet<>(visibleTileKeys()));
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
     * Creates the visual node for a single tile. If the tile is not available
     * locally, a placeholder is shown and a background download starts in online mode.
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
            if (!offlineOnly && networkLoadNeeded(key)) {
                downloadTileIfNeeded(key);
            }
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
     * Loads a tile from memory, the external directory, or the built-in cache.
     *
     * @return tile image, or {@code null} if the tile was not found
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
     * Returns the tile image for the current visual mode.
     * Night mode uses a separate cache for transformed images.
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
     * Creates a night-mode tile by transforming the palette pixel by pixel.
     * This works the same way for online, cached, and external offline tiles.
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
     * Converts a tile pixel into the dark blue night palette, preserving road
     * and label detail through brightness inversion.
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
     * Converts a normalized color component {@code 0..1} into a {@code 0..255} byte value.
     */
    private int colorByte(double value) {
        return (int) Math.round(clamp(value, 0.0, 1.0) * 255.0);
    }

    /**
     * Schedules a background tile download if it is not already loading and is absent locally.
     */
    private void downloadTileIfNeeded(TileKey key) {
        if (!visibleTiles.contains(key) || inFlightDownloads.contains(key) || !networkLoadNeeded(key)) {
            return;
        }
        if (!inFlightDownloads.add(key)) {
            return;
        }

        tileExecutor.submit(() -> {
            try {
                if (!visibleTiles.contains(key)) {
                    return;
                }
                downloadTileFromNetwork(key);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Missing network/cache is represented by the placeholder tile.
            } finally {
                inFlightDownloads.remove(key);
                if (visibleTiles.contains(key)) {
                    Platform.runLater(this::render);
                }
            }
        });
    }

    /** Returns whether the visible tile is missing or its HTTP cache lifetime has expired. */
    private boolean networkLoadNeeded(TileKey key) {
        if (findTileInRoot(externalTileRoot, key) != null) {
            return false;
        }
        Path cached = cachePath(key);
        return !Files.isRegularFile(cached) || !TileCacheMetadata.isFresh(cached, Instant.now());
    }

    /**
     * Downloads one tile from the public OSM server and atomically stores it in the local cache.
     *
     * @return {@code true} if the tile is available locally after the method completes
     */
    private boolean downloadTileFromNetwork(TileKey key) throws IOException, InterruptedException {
        Path target = cachePath(key);
        boolean cached = Files.isRegularFile(target);
        if (cached && TileCacheMetadata.isFresh(target, Instant.now())) {
            return true;
        }

        Files.createDirectories(target.getParent());
        TileCacheMetadata previous = TileCacheMetadata.load(target).orElse(null);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(tileSource.tileUri(key.zoom(), key.x(), key.y()))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent())
                .GET();
        if (cached && previous != null) {
            previous.addValidators(requestBuilder);
        }
        HttpRequest request = requestBuilder.build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        Instant now = Instant.now();
        if (response.statusCode() == 304 && cached) {
            TileCacheMetadata.fromHeaders(response.headers(), now, previous).save(target);
            return true;
        }
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
        TileCacheMetadata.fromHeaders(response.headers(), now, previous).save(target);
        memoryCache.remove(key);
        nightMemoryCache.remove(key);
        return true;
    }

    /**
     * Builds the User-Agent used for OSM requests, including the application version.
     */
    private String userAgent() {
        String version = MeshApp.APPLICATION_VERSION != null ? MeshApp.APPLICATION_VERSION : "dev";
        return userAgent(version);
    }

    /**
     * Builds the stable, contactable identity sent to raster tile providers.
     *
     * @param version application version
     * @return HTTP User-Agent value
     */
    static String userAgent(String version) {
        return "MeshApp/" + version + " (+" + PROJECT_URL + "; contact: " + CONTACT_EMAIL + ")";
    }

    /**
     * Looks for a tile first in the external directory, then in the built-in cache.
     */
    private Path findLocalTile(TileKey key) {
        Path external = findTileInRoot(externalTileRoot, key);
        if (external != null) {
            return external;
        }
        return findTileInRoot(cacheRoot(), key);
    }

    /**
     * Finds a tile file in a directory laid out as {@code z/x/y.png|jpg|jpeg}.
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
     * Returns the path where the tile should be stored in the built-in cache.
     */
    private Path cachePath(TileKey key) {
        return cacheRoot()
                .resolve(Integer.toString(key.zoom()))
                .resolve(Integer.toString(key.x()))
                .resolve(key.y() + ".png");
    }

    /**
     * Computes the tile keys intersecting the current map viewport.
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
     * Redraws the marker layer, keeping only valid nodes near the viewport.
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
     * Redraws the current selected area and the active rectangular selection.
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
     * Redraws trace segments, SNR/hop labels, and direction arrows.
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
     * Chooses a stable offset side for a trace line so forward and reverse
     * directions do not overlap.
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
     * Adds a direction arrow to a trace segment.
     *
     * @param position fraction of the segment length where the arrow is placed
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
     * Returns the trace-line color for the forward or reverse direction.
     */
    private String traceColor(boolean reverse) {
        return reverse ? TRACE_REVERSE_COLOR : TRACE_FORWARD_COLOR;
    }

    /**
     * Builds a screen rectangle for the geographic bounds of the selected area.
     */
    private Rectangle rectangleForArea(GeoBounds area) {
        Point2D topLeft = geoToScreen(area.north(), area.west());
        Point2D bottomRight = geoToScreen(area.south(), area.east());
        return rectangleForScreenBounds(topLeft.getX(), topLeft.getY(), bottomRight.getX(), bottomRight.getY());
    }

    /**
     * Creates a JavaFX rectangle from two screen points, regardless of drag direction.
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
     * Completes area selection by storing geographic bounds and fitting the map to them.
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
     * Chooses a center and zoom level that fit the selected area in the viewport.
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
     * Creates a circular node marker with adaptive text sizing and a coordinate tooltip.
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
     * Prepares compact marker text: empty values become a dot, and long values are
     * limited to four Unicode characters.
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
     * Finds the largest font size that lets the text fit inside the marker.
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
     * Redraws the measurement line, clicked points, and distance labels between adjacent points.
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
     * Publishes the current map status text.
     */
    private void notifyStatus() {
        int tileCount = visibleTileCount();
        statusListener.accept(I18n.t("map.status.summary",
                zoom,
                tileCount,
                pluralUnit("map.status.tile", tileCount),
                I18n.t(offlineOnly ? "map.status.mode.offline" : "map.status.mode.onlineCache"),
                nightMode ? I18n.t("map.status.nightSuffix") : ""));
    }

    /**
     * Publishes the current ruler state and total route length.
     */
    private void notifyMeasure() {
        if (measurePoints.isEmpty()) {
            measureListener.accept(I18n.t(measuring ? "map.measure.clickFirst" : "map.measure.off"));
            return;
        }

        double total = 0;
        for (int i = 1; i < measurePoints.size(); i++) {
            total += distanceMeters(measurePoints.get(i - 1), measurePoints.get(i));
        }
        if (measurePoints.size() == 1) {
            measureListener.accept(I18n.t("map.measure.onePoint"));
        } else {
            measureListener.accept(I18n.t("map.measure.points",
                    measurePoints.size(),
                    pluralUnit("map.unit.point", measurePoints.size()),
                    formatDistance(total)));
        }
    }

    /**
     * Publishes the selection state and selected-area size.
     */
    private void notifyAreaSelection() {
        if (areaSelectionActive) {
            areaSelectionListener.accept(I18n.t("map.area.selecting"));
            return;
        }
        if (selectedArea == null) {
            areaSelectionListener.accept(I18n.t(areaSelectionMode ? "map.area.drag" : "map.area.none"));
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
        areaSelectionListener.accept(I18n.t("map.area.selected",
                formatDistance(widthMeters),
                formatDistance(heightMeters)));
    }

    /**
     * Converts screen coordinates inside the component to latitude and longitude.
     */
    private GeoPoint screenToGeo(double screenX, double screenY) {
        double centerX = lonToPixelX(centerLongitude, zoom);
        double centerY = latToPixelY(centerLatitude, zoom);
        double worldX = centerX + screenX - getWidth() / 2.0;
        double worldY = centerY + screenY - getHeight() / 2.0;
        return new GeoPoint(pixelYToLat(worldY, zoom), pixelXToLon(worldX, zoom));
    }

    /**
     * Converts geographic coordinates to a screen point inside the component.
     */
    private Point2D geoToScreen(double latitude, double longitude) {
        double centerX = lonToPixelX(centerLongitude, zoom);
        double centerY = latToPixelY(centerLatitude, zoom);
        double x = lonToPixelX(longitude, zoom) - centerX + getWidth() / 2.0;
        double y = latToPixelY(latitude, zoom) - centerY + getHeight() / 2.0;
        return new Point2D(x, y);
    }

    /**
     * Computes the pixel bounds of all markers at the given zoom level.
     */
    private Bounds markerBounds(int candidateZoom) {
        List<GeoPoint> points = new ArrayList<>();
        for (MapMarker marker : markers) {
            points.add(new GeoPoint(marker.latitude(), marker.longitude()));
        }
        return pointBounds(points, candidateZoom);
    }

    /**
     * Computes the pixel bounds of an arbitrary set of geographic points.
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
     * Normalizes the map center into the allowed latitude and longitude ranges.
     */
    private void normalizeCenter() {
        centerLatitude = clampLatitude(centerLatitude);
        centerLongitude = normalizeLongitude(centerLongitude);
    }

    /**
     * Validates coordinate ranges and treats {@code 0,0} as missing node coordinates.
     */
    private static boolean isValidCoordinate(double latitude, double longitude) {
        return (latitude != 0 || longitude != 0)
                && latitude >= -90
                && latitude <= 90
                && longitude >= -180
                && longitude <= 180;
    }

    /**
     * Converts longitude to a global Web Mercator pixel X coordinate.
     */
    private static double lonToPixelX(double longitude, int zoom) {
        double worldSize = worldSize(zoom);
        return (normalizeLongitude(longitude) + 180.0) / 360.0 * worldSize;
    }

    /**
     * Converts latitude to a global Web Mercator pixel Y coordinate.
     */
    private static double latToPixelY(double latitude, int zoom) {
        double lat = Math.toRadians(clampLatitude(latitude));
        double worldSize = worldSize(zoom);
        double mercator = Math.log(Math.tan(Math.PI / 4.0 + lat / 2.0));
        return (0.5 - mercator / (2.0 * Math.PI)) * worldSize;
    }

    /**
     * Converts a global Web Mercator pixel X coordinate to longitude.
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
     * Converts a global Web Mercator pixel Y coordinate to latitude.
     */
    private static double pixelYToLat(double pixelY, int zoom) {
        double worldSize = worldSize(zoom);
        double y = Math.max(0, Math.min(worldSize, pixelY));
        double n = Math.PI - 2.0 * Math.PI * y / worldSize;
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /**
     * Returns the Web Mercator world size in pixels at the given zoom level.
     */
    private static double worldSize(int zoom) {
        return TILE_SIZE * (double) (1 << zoom);
    }

    /**
     * Clamps latitude to the range supported by Web Mercator.
     */
    private static double clampLatitude(double latitude) {
        if (Double.isNaN(latitude)) {
            return 0;
        }
        return Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
    }

    /**
     * Normalizes longitude into the {@code -180..180} range.
     */
    private static double normalizeLongitude(double longitude) {
        if (Double.isNaN(longitude)) {
            return 0;
        }
        double normalized = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return normalized == -180.0 ? 180.0 : normalized;
    }

    /**
     * Clamps zoom to the supported OSM range.
     */
    private static int clampZoom(int zoom) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    /**
     * Clamps a number to the given range.
     */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Formats coordinates for the map status line.
     *
     * @param latitude  latitude
     * @param longitude longitude
     * @return string with six fractional digits
     */
    public static String formatCoordinate(double latitude, double longitude) {
        return String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude);
    }

    private static String pluralUnit(String keyPrefix, long value) {
        return I18n.t(keyPrefix + "." + I18n.pluralCategory(value));
    }

    /**
     * Geographic point in WGS84 degrees.
     *
     * @param latitude  latitude
     * @param longitude longitude
     */
    public record GeoPoint(double latitude, double longitude) {
    }

    /**
     * One visual trace segment between two nodes with coordinates.
     *
     * @param from       segment start point
     * @param to         segment end point
     * @param fromTitle  start node name
     * @param toTitle    end node name
     * @param traceTitle target trace title
     * @param signalText signal, direction, and hop-count label
     * @param snr        numeric SNR, or {@link Double#NaN} when unavailable
     * @param reverse    {@code true} for the reverse direction
     * @param traceIndex selected trace index, used to spread lines apart
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
     * Geographic bounds of a rectangular area.
     */
    private record GeoBounds(double north, double south, double west, double east) {
        /**
         * Creates bounds from any two opposite corners of a rectangle.
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
     * OSM tile key in the {@code z/x/y} scheme.
     */
    private record TileKey(int zoom, int x, int y) {
        private TileKey {
            if (zoom < MIN_ZOOM || zoom > MAX_ZOOM) {
                throw new IllegalArgumentException("Unsupported zoom: " + zoom);
            }
        }
    }

    /**
     * Pixel bounds for a set of points at a specific zoom level.
     */
    private record Bounds(double minX, double minY, double maxX, double maxY) {
        /** @return bounds width in pixels */
        double width() {
            return maxX - minX;
        }

        /** @return bounds height in pixels */
        double height() {
            return maxY - minY;
        }

        /** @return X coordinate of the bounds center */
        double centerX() {
            return minX + width() / 2.0;
        }

        /** @return Y coordinate of the bounds center */
        double centerY() {
            return minY + height() / 2.0;
        }
    }
}
