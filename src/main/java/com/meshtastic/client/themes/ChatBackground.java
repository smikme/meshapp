package com.meshtastic.client.themes;

import org.jetbrains.annotations.PropertyKey;

/**
 * Immutable wallpaper settings shared by persisted preferences and unsaved previews.
 *
 * <p>Construction normalizes invalid values instead of rejecting old or damaged
 * preferences. Image identifiers refer to managed copies in
 * {@link BackgroundImageStore}, never arbitrary paths or remote URLs.
 * A {@link Kind#DEFAULT} configuration explicitly selects the theme background;
 * absence of a per-chat override is represented separately by {@code null} in
 * {@link AppearancePreferences#chatOverride(String, String)}.
 *
 * @param kind background renderer; {@code null} becomes {@link Kind#DEFAULT}
 * @param color primary color in {@code #RRGGBB} form; invalid values become {@code #dce8e2}
 * @param secondColor gradient end color in {@code #RRGGBB} form; invalid values become {@code #c3d9e8}
 * @param imageId managed PNG filename, retained only for a valid {@link Kind#IMAGE} configuration
 * @param dim black overlay opacity, clamped to {@code 0..0.85}; non-finite values become zero
 * @param blur Gaussian blur radius in pixels, clamped to {@code 0..30}; non-finite values become zero
 * @param repeat whether an image is tiled at its natural size instead of covering the viewport
 */
public record ChatBackground(Kind kind, String color, String secondColor, String imageId,
                             double dim, double blur, boolean repeat) {
    /** Wallpaper renderers whose enum names form part of the persisted JSON format. */
    public enum Kind {
        /** Current palette's background color. */
        DEFAULT("appearance.background.default"),
        /** A single user-selected color. */
        COLOR("appearance.background.color"),
        /** A diagonal gradient between the primary and secondary colors. */
        GRADIENT("appearance.background.gradient"),
        /** Repeating dots over the primary color. */
        DOTS("appearance.background.dots"),
        /** A repeating grid over the primary color. */
        GRID("appearance.background.grid"),
        /** An image imported into the local wallpaper store. */
        IMAGE("appearance.background.image");

        private final String displayKey;

        Kind(@PropertyKey(resourceBundle = "i18n.messages") String displayKey) {
            this.displayKey = displayKey;
        }

        /**
         * Returns the translation key for the background gallery label.
         *
         * @return key to resolve through {@link com.meshtastic.client.i18n.I18n}
         */
        public String displayKey() {
            return displayKey;
        }
    }

    /**
     * Normalizes all components before the configuration is stored or rendered.
     * An invalid image identifier changes an image background to {@link Kind#DEFAULT};
     * non-image configurations discard their image identifier.
     *
     * @param kind requested background renderer, or {@code null} for the default
     * @param color requested primary RGB color
     * @param secondColor requested gradient end RGB color
     * @param imageId requested managed PNG filename, or {@code null} for no image
     * @param dim requested dimming opacity
     * @param blur requested blur radius in pixels
     * @param repeat whether to tile an image
     */
    public ChatBackground {
        kind = kind == null ? Kind.DEFAULT : kind;
        color = validColor(color, "#dce8e2");
        secondColor = validColor(secondColor, "#c3d9e8");
        imageId = imageId != null && imageId.matches("[a-f0-9-]{36}\\.png") ? imageId : "";
        if (kind == Kind.IMAGE && imageId.isEmpty()) kind = Kind.DEFAULT;
        if (kind != Kind.IMAGE) imageId = "";
        dim = Double.isFinite(dim) ? Math.clamp(dim, 0, 0.85) : 0;
        blur = Double.isFinite(blur) ? Math.clamp(blur, 0, 30) : 0;
    }

    /**
     * Creates the theme background without dimming, blur or an image reference.
     *
     * @return normalized default configuration with the editor's initial colors
     */
    public static ChatBackground defaults() {
        return new ChatBackground(Kind.DEFAULT, null, null, null, 0, 0, false);
    }

    private static String validColor(String color, String fallback) {
        return color != null && color.matches("#[a-fA-F0-9]{6}") ? color : fallback;
    }
}
