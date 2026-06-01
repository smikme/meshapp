package com.meshtastic.client.components.map;

/**
 * Node marker displayed over the tile map.
 *
 * @param id stable node identifier used to connect UI with the model
 * @param title full title used by tooltips
 * @param shortTitle short text shown inside the circular marker
 * @param latitude WGS84 latitude in degrees
 * @param longitude WGS84 longitude in degrees
 * @param local {@code true} when the marker represents the user's own node
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record MapMarker(
        String id,
        String title,
        String shortTitle,
        double latitude,
        double longitude,
        boolean local
) {
}
