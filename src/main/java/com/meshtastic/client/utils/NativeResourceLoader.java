package com.meshtastic.client.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Загрузка нативных библиотек и бинарных ресурсов из classpath resources.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class NativeResourceLoader {

    private static final Map<String, Path> LOADED_LIBRARIES = new ConcurrentHashMap<>();

    public static Path loadLibrary(String baseName) {
        return LOADED_LIBRARIES.computeIfAbsent(baseName, NativeResourceLoader::extractAndLoadLibrary);
    }

    public static Path extractLibraryResource(String baseName) {
        String mappedName = System.mapLibraryName(baseName);
        String resourcePath = "/" + resourcePlatformPrefix() + "/" + mappedName;
        return extractResource(resourcePath, baseName + "-", mappedName.substring(mappedName.lastIndexOf('.')));
    }

    public static Path extractResource(String resourcePath, String prefix, String suffix) {
        String safePrefix = sanitizePrefix(prefix);
        String safeSuffix = sanitizeSuffix(suffix);
        try (InputStream input = NativeResourceLoader.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Resource not found: " + resourcePath);
            }

            Path extracted = Files.createTempFile(safePrefix, safeSuffix);
            Files.copy(input, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            extracted.toFile().deleteOnExit();
            return extracted;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract native resource " + resourcePath, e);
        }
    }

    private static Path extractAndLoadLibrary(String baseName) {
        Path libraryPath = extractLibraryResource(baseName);
        System.load(libraryPath.toAbsolutePath().toString());
        return libraryPath;
    }

    private static String resourcePlatformPrefix() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        if (os.contains("mac")) {
            return "darwin-" + mapDarwinArch(arch);
        }
        if (os.contains("win")) {
            return "win32-" + mapCommonArch(arch);
        }
        if (os.contains("linux")) {
            return "linux-" + mapCommonArch(arch);
        }

        throw new IllegalStateException("Unsupported platform for native resources: " + os + "/" + arch);
    }

    private static String mapDarwinArch(String arch) {
        return switch (arch) {
            case "amd64", "x86_64" -> "x86-64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
    }

    private static String mapCommonArch(String arch) {
        return switch (arch) {
            case "amd64", "x86_64" -> "x86-64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
    }

    private static String sanitizePrefix(String prefix) {
        String safe = prefix == null ? "native-" : prefix.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() >= 3 ? safe : (safe + "___").substring(0, 3);
    }

    private static String sanitizeSuffix(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return ".bin";
        }
        return suffix.startsWith(".") ? suffix : "." + suffix;
    }

    private NativeResourceLoader() {}
}
