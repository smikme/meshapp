package com.meshtastic.client.themes;

import org.jetbrains.annotations.PropertyKey;

/**
 * Built-in application palettes, each with a light and a dark CSS variant.
 *
 * <p>Enum names are persisted by {@link AppearancePreferences}; keep them stable
 * when changing display names. Day/night mode is stored separately in
 * {@link com.meshtastic.client.utils.AppPreferences}.
 */
public enum ThemePalette {
    /** Default AtlantaFX Cupertino colors. */
    STANDARD("appearance.palette.standard"),
    /** Blue surfaces, accents and outgoing message bubbles. */
    BLUE("appearance.palette.blue"),
    /** Green surfaces, accents and outgoing message bubbles. */
    GREEN("appearance.palette.green");

    private final String displayKey;

    ThemePalette(@PropertyKey(resourceBundle = "i18n.messages") String displayKey) {
        this.displayKey = displayKey;
    }

    /**
     * Returns the CSS class used to select this palette on a scene or preview root.
     *
     * @return palette selector without a leading dot, for example {@code palette-blue}
     */
    public String styleClass() {
        return "palette-" + name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Returns the translation key for the palette's user-facing name.
     *
     * @return key to resolve through {@link com.meshtastic.client.i18n.I18n}
     */
    public String displayKey() {
        return displayKey;
    }
}
