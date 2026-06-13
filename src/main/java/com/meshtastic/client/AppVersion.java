package com.meshtastic.client;

import com.meshtastic.client.update.SelfUpdateEnvironment;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.stream.Stream;

final class AppVersion {

    private AppVersion() {}

    static Info resolve(Class<?> resourceAnchor) {
        return resolve(System.getenv(), System.getProperties(), resourceAnchor);
    }

    static Info resolve(Map<String, String> env,
                        Properties properties,
                        Class<?> resourceAnchor) {
        Optional<SelfUpdateEnvironment> updateEnvironment =
                SelfUpdateEnvironment.from(env, properties);
        if (updateEnvironment.isPresent()) {
            SelfUpdateEnvironment selfUpdate = updateEnvironment.get();
            Optional<Info> managedInfo = managedPayloadInfo(selfUpdate);
            if (managedInfo.isPresent()) {
                return managedInfo.get();
            }
            return new Info(selfUpdate.version(), 0);
        }

        return embeddedInfo(resourceAnchor);
    }

    private static Optional<Info> managedPayloadInfo(SelfUpdateEnvironment environment) {
        Path versionDir = environment.versionsDir().resolve(environment.version());
        Path libDir = Files.isDirectory(versionDir.resolve("lib"))
                ? versionDir.resolve("lib")
                : versionDir;
        if (!Files.isDirectory(libDir)) {
            return Optional.empty();
        }

        try (Stream<Path> stream = Files.list(libDir)) {
            List<Path> jars = stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .sorted((left, right) -> jarPriority(left).compareTo(jarPriority(right)))
                    .toList();
            for (Path jar : jars) {
                Optional<Info> info = jarInfo(jar);
                if (info.isPresent()) {
                    return info;
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private static Info embeddedInfo(Class<?> resourceAnchor) {
        try (InputStream input = resourceAnchor.getResourceAsStream("/version.properties")) {
            if (input != null) {
                Properties props = new Properties();
                props.load(input);
                String version = props.getProperty("version", "").trim();
                int versionCode = parseVersionCode(props.getProperty("versionCode"));
                if (!version.isBlank()) {
                    return new Info(version, versionCode);
                }
            }
        } catch (Exception ignored) {}

        Package pkg = resourceAnchor.getPackage();
        String version = pkg != null && pkg.getImplementationVersion() != null
                ? pkg.getImplementationVersion()
                : "dev";
        return new Info(version, 0);
    }

    private static Optional<Info> jarInfo(Path jar) {
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
            if (version.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Info(version, parseVersionCode(props.getProperty("versionCode"))));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static String jarPriority(Path jar) {
        String name = jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("meshapp-") || name.equals("meshapp.jar")
                ? "0-" + name
                : "1-" + name;
    }

    private static int parseVersionCode(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    record Info(String version, int versionCode) {}
}
