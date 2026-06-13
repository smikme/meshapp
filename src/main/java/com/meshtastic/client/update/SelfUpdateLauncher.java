package com.meshtastic.client.update;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Stable native-package launcher that starts the user-writable application
 * payload from the managed self-update layout.
 */
public final class SelfUpdateLauncher {

    public static final String ENV_PAYLOAD_ACTIVE = "MESHAPP_PAYLOAD_ACTIVE";
    public static final String ENV_PAYLOAD_ROOT = "MESHAPP_PAYLOAD_ROOT";
    public static final String PROP_PAYLOAD_ACTIVE = "meshapp.payload.active";
    public static final String PROP_PAYLOAD_ROOT = "meshapp.payload.root";
    public static final String PROP_DISABLE = "meshapp.payloadLauncher.disabled";

    private static final String APP_NAME = "MeshApp";
    private static final String MAIN_CLASS = "com.meshtastic.client.MeshAppLauncher";

    private SelfUpdateLauncher() {}

    public static boolean launchPayloadIfNeeded(String[] args) {
        Optional<PayloadPlan> plan = plan(System.getenv(), System.getProperties(), readVersionInfo());
        if (plan.isEmpty()) {
            return false;
        }

        try {
            String currentVersion = ensurePayload(plan.get());
            Process process = startPayload(plan.get(), currentVersion, args);
            int exitCode = process.waitFor();
            System.exit(exitCode);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return false;
        }
    }

    static Optional<PayloadPlan> plan(Map<String, String> env,
                                      Properties properties,
                                      VersionInfo versionInfo) {
        if (isEnabled(firstNonBlank(
                properties.getProperty(PROP_DISABLE),
                env.get("MESHAPP_PAYLOAD_LAUNCHER_DISABLED")
        ))) {
            return Optional.empty();
        }
        if (isEnabled(firstNonBlank(
                properties.getProperty(PROP_PAYLOAD_ACTIVE),
                env.get(ENV_PAYLOAD_ACTIVE)
        ))) {
            return Optional.empty();
        }
        if (hasManagedSelfUpdateEnvironment(env, properties) || isFlatpak(env)) {
            return Optional.empty();
        }
        if (versionInfo == null
                || versionInfo.versionCode() <= 0
                || versionInfo.version() == null
                || versionInfo.version().isBlank()
                || "dev".equalsIgnoreCase(versionInfo.version().trim())) {
            return Optional.empty();
        }

        Optional<PackagedApp> packagedApp = detectPackagedApp(properties);
        if (packagedApp.isEmpty()) {
            return Optional.empty();
        }

        Path root = payloadRoot(env, properties);
        return Optional.of(new PayloadPlan(
                root,
                stagingDir(env, properties, root),
                packagedApp.get().appDir(),
                sanitizeVersion(versionInfo.version()),
                versionInfo.versionCode(),
                packagedApp.get().launcher()
        ));
    }

    static String ensurePayload(PayloadPlan plan) throws IOException {
        Files.createDirectories(plan.versionsDir());
        Files.createDirectories(plan.stagingDir());

        Optional<String> current = readCurrent(plan.currentFile());
        if (current.isPresent()) {
            Path currentDir = plan.versionDir(current.get());
            if (Files.isDirectory(currentDir) && isCurrentPayloadAtLeastBundled(currentDir, current.get(), plan)) {
                return current.get();
            }
        }

        Path targetDir = plan.versionDir(plan.bundledVersion());
        if (!Files.isDirectory(targetDir)) {
            Path bootstrapDir = plan.stagingDir()
                    .resolve("bootstrap-" + plan.bundledVersion());
            SelfUpdateInstaller.deleteTree(bootstrapDir);
            Files.createDirectories(bootstrapDir);
            copyInitialPayload(plan.packagedAppDir(), bootstrapDir);
            move(bootstrapDir, targetDir);
        }

        SelfUpdateInstaller.writeCurrent(plan.currentFile(), plan.bundledVersion());
        return plan.bundledVersion();
    }

    static Process startPayload(PayloadPlan plan, String currentVersion, String[] args) throws IOException {
        Path versionDir = plan.versionDir(currentVersion);
        Path libDir = payloadLibDir(versionDir);
        Path fxDir = Files.isDirectory(libDir.resolve("fx"))
                ? libDir.resolve("fx")
                : versionDir.resolve("fx");
        String classPath = payloadClassPath(libDir);
        if (classPath.isBlank()) {
            throw new IOException("Payload classpath is empty: " + libDir);
        }

        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.addAll(filteredJvmArgs());
        if (Files.isDirectory(fxDir)) {
            command.add("--module-path");
            command.add(fxDir.toAbsolutePath().toString());
            command.add("--add-modules");
            command.add("javafx.controls");
        }
        command.add("-D" + SelfUpdateEnvironment.PROP_ROOT + "="
                + plan.root().toAbsolutePath());
        command.add("-D" + SelfUpdateEnvironment.PROP_VERSION + "="
                + currentVersion);
        command.add("-D" + SelfUpdateEnvironment.PROP_STAGING_DIR + "="
                + plan.stagingDir().toAbsolutePath());
        if (plan.launcher() != null && !plan.launcher().isBlank()) {
            command.add("-D" + SelfUpdateEnvironment.PROP_LAUNCHER + "="
                    + plan.launcher());
        }
        command.add("-DjSerialComm.library.path=" + libDir.toAbsolutePath());
        command.add("-cp");
        command.add(classPath);
        command.add(MAIN_CLASS);
        if (args != null) {
            command.addAll(List.of(args));
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.inheritIO();
        Map<String, String> childEnv = builder.environment();
        childEnv.put(ENV_PAYLOAD_ACTIVE, "true");
        childEnv.put(SelfUpdateEnvironment.ENV_ROOT, plan.root().toAbsolutePath().toString());
        childEnv.put(SelfUpdateEnvironment.ENV_VERSION, currentVersion);
        if (plan.launcher() != null && !plan.launcher().isBlank()) {
            childEnv.put(SelfUpdateEnvironment.ENV_LAUNCHER, plan.launcher());
        }
        return builder.start();
    }

    static Optional<PackagedApp> detectPackagedApp(Properties properties) {
        String classPath = properties.getProperty("java.class.path", "");
        if (classPath.isBlank()) {
            return Optional.empty();
        }

        for (String entry : classPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path path;
            try {
                path = Path.of(entry).toAbsolutePath().normalize();
            } catch (Exception ignored) {
                continue;
            }

            Optional<PackagedApp> macApp = macAppFromClasspathEntry(path);
            if (macApp.isPresent()) {
                return macApp;
            }
        }

        if (!hasJpackageMarker(properties)) {
            return Optional.empty();
        }

        for (String entry : classPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(entry).toAbsolutePath().normalize();
                Path parent = path.getParent();
                if (parent != null
                        && Files.isDirectory(parent)
                        && path.getFileName() != null
                        && path.getFileName().toString().endsWith(".jar")) {
                    return Optional.of(new PackagedApp(parent, launcherPath(properties, null)));
                }
            } catch (Exception ignored) {}
        }

        return Optional.empty();
    }

    private static Optional<PackagedApp> macAppFromClasspathEntry(Path path) {
        Path root = path.getRoot();
        Path current = root == null ? Path.of("") : root;
        for (int i = 0; i < path.getNameCount(); i++) {
            current = current.resolve(path.getName(i).toString());
            if (current.getFileName() != null
                    && current.getFileName().toString().endsWith(".app")) {
                Path appDir = current.resolve("Contents").resolve("app");
                if (path.startsWith(appDir)) {
                    return Optional.of(new PackagedApp(appDir, current.toString()));
                }
            }
        }
        return Optional.empty();
    }

    private static String launcherPath(Properties properties, Path macAppBundle) {
        if (macAppBundle != null) {
            return macAppBundle.toString();
        }

        String configured = firstNonBlank(properties.getProperty("jpackage.app-path"));
        if (configured != null) {
            Optional<Path> appBundle = macBundleFromExecutable(Path.of(configured));
            return appBundle.map(Path::toString).orElse(configured);
        }

        return ProcessHandle.current()
                .info()
                .command()
                .map(command -> macBundleFromExecutable(Path.of(command))
                        .map(Path::toString)
                        .orElse(command))
                .orElse("");
    }

    private static Optional<Path> macBundleFromExecutable(Path executable) {
        try {
            Path path = executable.toAbsolutePath().normalize();
            Path root = path.getRoot();
            Path current = root == null ? Path.of("") : root;
            for (int i = 0; i < path.getNameCount(); i++) {
                current = current.resolve(path.getName(i).toString());
                if (current.getFileName() != null
                        && current.getFileName().toString().endsWith(".app")
                        && path.startsWith(current.resolve("Contents").resolve("MacOS"))) {
                    return Optional.of(current);
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private static void copyInitialPayload(Path packagedAppDir, Path targetDir) throws IOException {
        Path libDir = targetDir.resolve("lib");
        copyDirectory(packagedAppDir, libDir);
    }

    private static void copyDirectory(Path sourceDir, Path targetDir) throws IOException {
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            List<Path> paths = stream.sorted(Comparator.naturalOrder()).toList();
            for (Path source : paths) {
                Path relative = sourceDir.relativize(source);
                Path target = targetDir.resolve(relative);
                if (Files.isSymbolicLink(source)) {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.deleteIfExists(target);
                    Files.createSymbolicLink(target, Files.readSymbolicLink(source));
                } else if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(source, target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static Optional<String> readCurrent(Path currentFile) {
        try {
            if (Files.isRegularFile(currentFile)) {
                String version = Files.readString(currentFile).trim();
                if (!version.isBlank() && isSafeVersion(version)) {
                    return Optional.of(version);
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private static Path payloadLibDir(Path versionDir) {
        Path lib = versionDir.resolve("lib");
        return Files.isDirectory(lib) ? lib : versionDir;
    }

    private static String payloadClassPath(Path libDir) throws IOException {
        try (Stream<Path> stream = Files.list(libDir)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .map(path -> path.toAbsolutePath().toString())
                    .reduce((left, right) -> left + java.io.File.pathSeparator + right)
                    .orElse("");
        }
    }

    private static boolean isCurrentPayloadAtLeastBundled(Path currentDir,
                                                          String currentVersion,
                                                          PayloadPlan plan) {
        Optional<VersionInfo> currentInfo = payloadVersionInfo(currentDir);
        if (currentInfo.isPresent() && currentInfo.get().versionCode() > 0) {
            return currentInfo.get().versionCode() >= plan.bundledVersionCode();
        }
        return compareVersionNames(currentVersion, plan.bundledVersion()) >= 0;
    }

    private static Optional<VersionInfo> payloadVersionInfo(Path versionDir) {
        Path libDir = payloadLibDir(versionDir);
        if (!Files.isDirectory(libDir)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(libDir)) {
            List<Path> jars = stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .sorted((left, right) -> jarPriority(left).compareTo(jarPriority(right)))
                    .toList();
            for (Path jar : jars) {
                Optional<VersionInfo> info = jarVersionInfo(jar);
                if (info.isPresent()) {
                    return info;
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private static String jarPriority(Path jar) {
        String name = jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("meshapp-") || name.equals("meshapp.jar")
                ? "0-" + name
                : "1-" + name;
    }

    private static Optional<VersionInfo> jarVersionInfo(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entry = jarFile.getEntry("version.properties");
            if (entry == null) {
                return Optional.empty();
            }
            Properties props = new Properties();
            try (InputStream input = jarFile.getInputStream(entry)) {
                props.load(input);
            }
            String version = props.getProperty("version", "").trim();
            int versionCode = Integer.parseInt(props.getProperty("versionCode", "0").trim());
            if (version.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new VersionInfo(version, versionCode));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static int compareVersionNames(String left, String right) {
        int[] leftParts = numericVersionParts(left);
        int[] rightParts = numericVersionParts(right);
        for (int i = 0; i < Math.max(leftParts.length, rightParts.length); i++) {
            int l = i < leftParts.length ? leftParts[i] : 0;
            int r = i < rightParts.length ? rightParts[i] : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static int[] numericVersionParts(String version) {
        if (version == null) {
            return new int[0];
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\d+")
                .matcher(version);
        List<Integer> parts = new ArrayList<>();
        while (matcher.find() && parts.size() < 4) {
            try {
                parts.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
                parts.add(0);
            }
        }
        return parts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static List<String> filteredJvmArgs() {
        List<String> result = new ArrayList<>();
        List<String> input = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (int i = 0; i < input.size(); i++) {
            String arg = input.get(i);
            if (skipJvmArgWithValue(arg)) {
                i++;
                continue;
            }
            if (skipJvmArg(arg)) {
                continue;
            }
            result.add(arg);
        }
        return result;
    }

    private static boolean skipJvmArgWithValue(String arg) {
        return "--module-path".equals(arg)
                || "-p".equals(arg)
                || "--add-modules".equals(arg)
                || "-m".equals(arg)
                || "-cp".equals(arg)
                || "-classpath".equals(arg)
                || "--class-path".equals(arg);
    }

    private static boolean skipJvmArg(String arg) {
        return arg.startsWith("--module-path=")
                || arg.startsWith("--add-modules=")
                || arg.startsWith("-D" + SelfUpdateEnvironment.PROP_ROOT + "=")
                || arg.startsWith("-D" + SelfUpdateEnvironment.PROP_VERSION + "=")
                || arg.startsWith("-D" + SelfUpdateEnvironment.PROP_LAUNCHER + "=")
                || arg.startsWith("-D" + SelfUpdateEnvironment.PROP_STAGING_DIR + "=")
                || arg.startsWith("-DjSerialComm.library.path=");
    }

    private static Path javaExecutable() {
        String bin = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", bin);
    }

    private static Path payloadRoot(Map<String, String> env, Properties properties) {
        String configured = firstNonBlank(
                properties.getProperty(PROP_PAYLOAD_ROOT),
                env.get(ENV_PAYLOAD_ROOT)
        );
        if (configured != null) {
            return Path.of(configured);
        }

        String osName = properties.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String home = properties.getProperty("user.home", "");
        if (osName.contains("mac")) {
            return Path.of(home, "Library", "Application Support", APP_NAME);
        }
        if (osName.contains("win")) {
            String localAppData = firstNonBlank(env.get("LOCALAPPDATA"));
            if (localAppData != null) {
                return Path.of(localAppData, APP_NAME);
            }
            return Path.of(home, "AppData", "Local", APP_NAME);
        }
        String xdgDataHome = firstNonBlank(env.get("XDG_DATA_HOME"));
        if (xdgDataHome != null) {
            return Path.of(xdgDataHome, "meshapp");
        }
        return Path.of(home, ".local", "share", "meshapp");
    }

    private static Path stagingDir(Map<String, String> env, Properties properties, Path root) {
        String configured = firstNonBlank(
                properties.getProperty(SelfUpdateEnvironment.PROP_STAGING_DIR),
                env.get("MESHAPP_UPDATE_STAGING_DIR")
        );
        if (configured != null) {
            return Path.of(configured);
        }

        String osName = properties.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String home = properties.getProperty("user.home", "");
        if (osName.contains("mac")) {
            return Path.of(home, "Library", "Caches", APP_NAME, "self-update");
        }
        if (osName.contains("win")) {
            return root.resolve("staging");
        }
        String xdgCacheHome = firstNonBlank(env.get("XDG_CACHE_HOME"));
        if (xdgCacheHome != null) {
            return Path.of(xdgCacheHome, "meshapp", "self-update");
        }
        return Path.of(home, ".cache", "meshapp", "self-update");
    }

    private static boolean hasManagedSelfUpdateEnvironment(Map<String, String> env,
                                                           Properties properties) {
        return firstNonBlank(
                properties.getProperty(SelfUpdateEnvironment.PROP_ROOT),
                env.get(SelfUpdateEnvironment.ENV_ROOT)
        ) != null
                && firstNonBlank(
                properties.getProperty(SelfUpdateEnvironment.PROP_VERSION),
                env.get(SelfUpdateEnvironment.ENV_VERSION)
        ) != null;
    }

    private static boolean hasJpackageMarker(Properties properties) {
        return firstNonBlank(
                properties.getProperty("jpackage.app-version"),
                properties.getProperty("jpackage.app-path")
        ) != null;
    }

    private static boolean isFlatpak(Map<String, String> env) {
        return firstNonBlank(env.get("FLATPAK_ID"), env.get("FLATPAK_APP_ID")) != null
                || "flatpak".equalsIgnoreCase(firstNonBlank(env.get("container")));
    }

    private static boolean isEnabled(String value) {
        return value != null && (
                "1".equals(value.trim())
                        || "true".equalsIgnoreCase(value.trim())
                        || "yes".equalsIgnoreCase(value.trim())
        );
    }

    static VersionInfo readVersionInfo() {
        try (InputStream input = SelfUpdateLauncher.class.getResourceAsStream("/version.properties")) {
            if (input != null) {
                Properties props = new Properties();
                props.load(input);
                String version = props.getProperty("version", "dev").trim();
                int versionCode = Integer.parseInt(props.getProperty("versionCode", "0").trim());
                return new VersionInfo(version, versionCode);
            }
        } catch (Exception ignored) {}
        Package pkg = SelfUpdateLauncher.class.getPackage();
        String version = pkg != null && pkg.getImplementationVersion() != null
                ? pkg.getImplementationVersion()
                : "dev";
        return new VersionInfo(version, 0);
    }

    private static String sanitizeVersion(String version) {
        String sanitized = version.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        if (!isSafeVersion(sanitized)) {
            throw new IllegalArgumentException("Unsafe payload version: " + version);
        }
        return sanitized;
    }

    private static boolean isSafeVersion(String version) {
        return version != null
                && !version.isBlank()
                && !version.contains("/")
                && !version.contains("\\")
                && !version.contains("..");
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    record VersionInfo(String version, int versionCode) {}

    record PackagedApp(Path appDir, String launcher) {}

    record PayloadPlan(Path root,
                       Path stagingDir,
                       Path packagedAppDir,
                       String bundledVersion,
                       int bundledVersionCode,
                       String launcher) {
        Path versionsDir() { return root.resolve("versions"); }
        Path versionDir(String version) { return versionsDir().resolve(version); }
        Path currentFile() { return root.resolve("current"); }
    }
}
