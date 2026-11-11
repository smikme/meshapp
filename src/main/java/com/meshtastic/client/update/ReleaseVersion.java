package com.meshtastic.client.update;

import java.util.Optional;
import java.util.regex.Pattern;

/** Release ordering, independent of the legacy updater code and native installer version. */
public record ReleaseVersion(int major, int minor, int patch, boolean unstable, long buildNumber)
        implements Comparable<ReleaseVersion> {
    private static final Pattern FORMAT = Pattern.compile(
            "v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-unstable\\.([1-9]\\d*)|\\+build\\.([1-9]\\d*))?");

    public static Optional<ReleaseVersion> parse(String value) {
        if (value == null) return Optional.empty();
        var match = FORMAT.matcher(value);
        if (!match.matches()) return Optional.empty();
        try {
            var version = new ReleaseVersion(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                    Integer.parseInt(match.group(3)), match.group(4) != null,
                    Long.parseLong(match.group(4) != null ? match.group(4) : match.group(5) != null ? match.group(5) : "0"));
            if (version.major > 255 || version.minor > 255 || version.patch > 999
                    || version.buildNumber > 4244635647L || version.legacyCode() <= 0) return Optional.empty();
            return Optional.of(version);
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public String channel() { return unstable ? "unstable" : "stable"; }
    public String baseVersion() { return major + "." + minor + "." + patch; }
    public int legacyCode() { return major * 1_000_000 + minor * 1000 + patch - (unstable ? 1 : 0); }

    @Override
    public int compareTo(ReleaseVersion other) {
        int order = Integer.compare(major, other.major);
        if (order == 0) order = Integer.compare(minor, other.minor);
        if (order == 0) order = Integer.compare(patch, other.patch);
        if (order == 0) order = Boolean.compare(other.unstable, unstable);
        if (order == 0) order = Long.compare(buildNumber, other.buildNumber);
        return order;
    }

    /** Old payloads without a parseable release name retain their previous code-based ordering. */
    public static int compare(String left, int leftCode, String right, int rightCode) {
        var a = parse(left);
        var b = parse(right);
        return a.isPresent() && b.isPresent()
                ? a.get().compareTo(b.get()) : Integer.compare(leftCode, rightCode);
    }
}
