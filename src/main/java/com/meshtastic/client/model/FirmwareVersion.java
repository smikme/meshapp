package com.meshtastic.client.model;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Three-component Meshtastic firmware version used for protocol capability checks.
 */
public record FirmwareVersion(int major, int minor, int patch)
        implements Comparable<FirmwareVersion> {

    private static final Pattern VERSION_PREFIX =
            Pattern.compile("^\\s*[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:\\D.*|$)");

    public static Optional<FirmwareVersion> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = VERSION_PREFIX.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new FirmwareVersion(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public boolean isAtLeast(FirmwareVersion minimum) {
        return minimum != null && compareTo(minimum) >= 0;
    }

    @Override
    public int compareTo(FirmwareVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) {
            result = Integer.compare(minor, other.minor);
        }
        if (result == 0) {
            result = Integer.compare(patch, other.patch);
        }
        return result;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
