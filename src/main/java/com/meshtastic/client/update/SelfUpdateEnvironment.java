package com.meshtastic.client.update;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime contract supplied by the stable launcher in a self-updating layout.
 */
public final class SelfUpdateEnvironment {

    public static final String ENV_ROOT = "MESHAPP_UPDATE_ROOT";
    public static final String ENV_VERSION = "MESHAPP_UPDATE_VERSION";
    public static final String ENV_LAUNCHER = "MESHAPP_UPDATE_LAUNCHER";
    public static final String PROP_ROOT = "meshapp.update.root";
    public static final String PROP_VERSION = "meshapp.update.version";
    public static final String PROP_LAUNCHER = "meshapp.update.launcher";

    private final Path root;
    private final String version;
    private final String launcher;

    private SelfUpdateEnvironment(Path root, String version, String launcher) {
        this.root = root;
        this.version = version;
        this.launcher = launcher;
    }

    public static Optional<SelfUpdateEnvironment> current() {
        return from(System.getenv(), System.getProperties());
    }

    static Optional<SelfUpdateEnvironment> from(Map<String, String> env,
                                               java.util.Properties properties) {
        String root = firstNonBlank(
                properties.getProperty(PROP_ROOT),
                env.get(ENV_ROOT)
        );
        String version = firstNonBlank(
                properties.getProperty(PROP_VERSION),
                env.get(ENV_VERSION)
        );
        String launcher = firstNonBlank(
                properties.getProperty(PROP_LAUNCHER),
                env.get(ENV_LAUNCHER)
        );
        if (root == null || version == null) {
            return Optional.empty();
        }
        return Optional.of(new SelfUpdateEnvironment(Path.of(root), version, launcher));
    }

    public Path root() { return root; }
    public String version() { return version; }
    public String launcher() { return launcher; }

    public Path stagingDir() { return root.resolve("staging"); }
    public Path versionsDir() { return root.resolve("versions"); }
    public Path currentFile() { return root.resolve("current"); }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }
}
