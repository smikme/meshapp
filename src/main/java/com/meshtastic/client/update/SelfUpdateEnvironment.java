package com.meshtastic.client.update;

import java.io.File;
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

        return inferMacAppBundle(properties);
    }

    public Path root() { return root; }
    public String version() { return version; }
    public String launcher() { return launcher; }
    public Layout layout() { return layout; }

    public Path stagingDir() { return stagingDir; }
    public Path versionsDir() { return root.resolve("versions"); }
    public Path currentFile() { return root.resolve("current"); }

    public enum Layout {
        MANAGED("managed"),
        MAC_APP_BUNDLE("mac-app-bundle");

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

    private static Optional<SelfUpdateEnvironment> inferMacAppBundle(java.util.Properties properties) {
        String osName = properties.getProperty("os.name", "");
        if (!osName.toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            return Optional.empty();
        }

        String classPath = properties.getProperty("java.class.path", "");
        for (String entry : classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Optional<Path> appBundle = appBundleFromClasspathEntry(entry);
            if (appBundle.isPresent()) {
                Path app = appBundle.get();
                return Optional.of(new SelfUpdateEnvironment(
                        app,
                        firstNonBlank(
                                properties.getProperty(PROP_VERSION),
                                properties.getProperty("jpackage.app-version"),
                                "current"
                        ),
                        app.toString(),
                        macStagingDir(properties),
                        Layout.MAC_APP_BUNDLE
                ));
            }
        }

        return Optional.empty();
    }

    private static Optional<Path> appBundleFromClasspathEntry(String entry) {
        if (entry == null || entry.isBlank() || !entry.contains(".app")) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(entry).toAbsolutePath().normalize();
            Path root = path.getRoot();
            Path current = root == null ? Path.of("") : root;
            for (int i = 0; i < path.getNameCount(); i++) {
                current = current.resolve(path.getName(i).toString());
                if (current.getFileName() != null
                        && current.getFileName().toString().endsWith(".app")) {
                    Path contentsApp = current.resolve("Contents").resolve("app");
                    if (path.startsWith(contentsApp)) {
                        return Optional.of(current);
                    }
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private static Path macStagingDir(java.util.Properties properties) {
        String home = properties.getProperty("user.home", "");
        if (home == null || home.isBlank()) {
            return Path.of(System.getProperty("java.io.tmpdir"), "MeshApp", "self-update");
        }
        return Path.of(home, "Library", "Caches", "MeshApp", "self-update");
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
