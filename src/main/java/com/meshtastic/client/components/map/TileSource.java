package com.meshtastic.client.components.map;

import java.net.URI;
import java.util.Objects;
import java.util.Properties;

/**
 * Describes a raster tile endpoint and the capabilities permitted by its usage policy.
 * The URL template must contain the {@code {z}}, {@code {x}}, and {@code {y}}
 * placeholders. Source identifiers are also used as local cache directory names.
 *
 * @param id                  stable filesystem-safe source identifier
 * @param urlTemplate         tile URL template containing zoom and coordinate placeholders
 * @param attribution         attribution text displayed over the map
 * @param minZoom             lowest supported zoom level
 * @param maxZoom             highest supported zoom level
 * @param bulkDownloadAllowed whether the provider explicitly permits bulk downloading
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record TileSource(
        String id,
        String urlTemplate,
        String attribution,
        int minZoom,
        int maxZoom,
        boolean bulkDownloadAllowed
) {
    /** JVM property containing the tile source identifier. */
    public static final String PROPERTY_ID = "meshapp.map.tileSource.id";
    /** JVM property containing the raster tile URL template. */
    public static final String PROPERTY_URL = "meshapp.map.tileSource.url";
    /** JVM property containing the attribution displayed on the map. */
    public static final String PROPERTY_ATTRIBUTION = "meshapp.map.tileSource.attribution";
    /** JVM property containing the minimum supported zoom level. */
    public static final String PROPERTY_MIN_ZOOM = "meshapp.map.tileSource.minZoom";
    /** JVM property containing the maximum supported zoom level. */
    public static final String PROPERTY_MAX_ZOOM = "meshapp.map.tileSource.maxZoom";
    /**
     * Public OpenStreetMap raster source. Its policy permits interactive viewport
     * requests but prohibits bulk and offline downloading.
     */
    public static final TileSource OPEN_STREET_MAP = new TileSource(
            "osm",
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "© OpenStreetMap contributors",
            TileMapView.MIN_ZOOM,
            TileMapView.MAX_ZOOM,
            false
    );

    /**
     * Validates and creates a tile-source descriptor.
     *
     * @throws NullPointerException     if an object component is {@code null}
     * @throws IllegalArgumentException if the identifier, template, or zoom range is invalid
     */
    public TileSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(urlTemplate, "urlTemplate");
        Objects.requireNonNull(attribution, "attribution");
        if (!id.matches("[a-zA-Z0-9._-]+")) {
            throw new IllegalArgumentException("Tile source id contains unsafe characters");
        }
        if (!urlTemplate.contains("{z}") || !urlTemplate.contains("{x}") || !urlTemplate.contains("{y}")) {
            throw new IllegalArgumentException("Tile URL must contain {z}, {x}, and {y}");
        }
        if (minZoom < 0 || maxZoom < minZoom) {
            throw new IllegalArgumentException("Invalid zoom range");
        }
    }

    /**
     * Resolves the URL template for one tile.
     *
     * @param zoom tile zoom level
     * @param x    horizontal tile coordinate
     * @param y    vertical tile coordinate
     * @return resolved tile URI
     * @throws IllegalArgumentException if {@code zoom} is outside this source's range
     */
    public URI tileUri(int zoom, int x, int y) {
        if (zoom < minZoom || zoom > maxZoom) {
            throw new IllegalArgumentException("Unsupported zoom: " + zoom);
        }
        return URI.create(urlTemplate
                .replace("{z}", Integer.toString(zoom))
                .replace("{x}", Integer.toString(x))
                .replace("{y}", Integer.toString(y)));
    }

    /**
     * Loads an interactive source from JVM properties. Missing properties retain
     * the policy-safe OpenStreetMap defaults. Bulk downloading is never enabled
     * from untrusted runtime configuration.
     *
     * @param properties JVM properties to inspect
     * @return configured custom source, or {@link #OPEN_STREET_MAP} when no URL is configured
     * @throws NullPointerException     if {@code properties} is {@code null}
     * @throws IllegalArgumentException if configured source values are invalid
     */
    public static TileSource configured(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String configuredUrl = properties.getProperty(PROPERTY_URL, "").trim();
        if (configuredUrl.isEmpty()) {
            return OPEN_STREET_MAP;
        }
        return new TileSource(
                properties.getProperty(PROPERTY_ID, "custom").trim(),
                configuredUrl,
                properties.getProperty(PROPERTY_ATTRIBUTION, "Map tiles: configured provider").trim(),
                intProperty(properties, PROPERTY_MIN_ZOOM, MIN_ZOOM_DEFAULT),
                intProperty(properties, PROPERTY_MAX_ZOOM, MAX_ZOOM_DEFAULT),
                false
        );
    }

    private static final int MIN_ZOOM_DEFAULT = TileMapView.MIN_ZOOM;
    private static final int MAX_ZOOM_DEFAULT = TileMapView.MAX_ZOOM;

    private static int intProperty(Properties properties, String name, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(name, Integer.toString(fallback)).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
