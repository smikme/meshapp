package com.meshtastic.client.service;

import java.util.List;

/**
 * Result of local firmware image and connection validation.
 *
 * @param image analyzed firmware image, or {@code null} when image analysis failed
 * @param errors blocking validation errors
 * @param warnings non-blocking warnings shown before confirmation
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record FirmwareValidationResult(
    FirmwareImage image,
    List<String> errors,
    List<String> warnings
) {
    /**
     * Creates an immutable validation result and normalizes missing lists.
     */
    public FirmwareValidationResult {
        errors = errors != null ? List.copyOf(errors) : List.of();
        warnings = warnings != null ? List.copyOf(warnings) : List.of();
    }

    /**
     * Checks whether the update flow can continue.
     *
     * @return {@code true} when an image was analyzed and no blocking errors exist
     */
    public boolean valid() {
        return errors.isEmpty() && image != null;
    }
}
