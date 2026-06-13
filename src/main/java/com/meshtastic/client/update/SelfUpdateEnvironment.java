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
    public static final String PROP_STAGING_DIR = "meshapp.update.stagingDir";

    private final Path root;
    private final String version;
    private final String launcher;
    private final Path stagingDir;
    private final Layout layout;

    private SelfUpdateEnvironment(Path root,
                                  String version,
                                  String launcher,
                                  Path stagingDir,
                                  Layout layout) {
        this.root = root;
        this.version = version;
        this.launcher = launcher;
        this.stagingDir = stagingDir;
        this.layout = layout;
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
        String stagingDir = properties.getProperty(PROP_STAGING_DIR);
        if (root != null && version != null) {
            Path rootPath = Path.of(root);
            Path stagingPath = stagingDir == null || stagingDir.isBlank()
                    ? rootPath.resolve("staging")
                    : Path.of(stagingDir);
            return Optional.of(new SelfUpdateEnvironment(
                    rootPath,
                    version,
                    launcher,
                    stagingPath,
                    Layout.MANAGED
            ));
        }

        return Optional.empty();
    }

    public Path root() { return root; }
    public String version() { return version; }
    public String launcher() { return launcher; }
    public Layout layout() { return layout; }

    public Path stagingDir() { return stagingDir; }
    public Path versionsDir() { return root.resolve("versions"); }
    public Path currentFile() { return root.resolve("current"); }

    public enum Layout {
        MANAGED("managed");

        private final String id;

        Layout(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        static Layout fromId(String id) {
            if (id == null || id.isBlank()) {
                return MANAGED;
            }
            for (Layout layout : values()) {
                if (layout.id.equalsIgnoreCase(id.trim())) {
                    return layout;
                }
            }
            throw new IllegalArgumentException("Unknown self-update layout: " + id);
        }
    }

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
