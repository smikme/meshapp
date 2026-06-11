package com.meshtastic.client.forms.settings;

import com.meshtastic.client.utils.UnicodeTextUtils;

/**
 * Text normalization rules for read-only node cache values shown in settings.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class CacheDisplayText {

    private CacheDisplayText() {}

    /**
     * Sanitizes a cached display value for JavaFX labels/table cells.
     *
     * @param value raw cached text
     * @return JavaFX-safe text
     */
    public static String sanitize(String value) {
        return UnicodeTextUtils.sanitizeForJavaFxDisplay(value);
    }
}
