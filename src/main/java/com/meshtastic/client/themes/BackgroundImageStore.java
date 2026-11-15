package com.meshtastic.client.themes;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import javafx.scene.image.Image;

/**
 * Imports, loads and removes application-managed chat wallpaper images.
 *
 * <p>Importing creates a normalized PNG copy under {@link #directory()} without
 * modifying the source. Input size and pixel-count limits bound decoding work;
 * stored images have a maximum edge of {@value #MAX_EDGE} pixels. Decoded images
 * are held in an access-ordered cache of at most three entries.
 *
 * <p>All decoding, scaling and disk operations run on one daemon executor. Public
 * asynchronous methods may be called from the JavaFX thread, but their futures
 * must not be awaited there. UI continuations must dispatch through
 * {@link javafx.application.Platform#runLater(Runnable)}.
 */
public final class BackgroundImageStore {
    /** Maximum accepted source-file size in bytes (20 MiB). */
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    /** Maximum accepted source width multiplied by height, before subsampling. */
    public static final long MAX_PIXELS = 80_000_000L;
    /** Maximum width or height of a normalized stored image, in pixels. */
    public static final int MAX_EDGE = 2048;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "chat-wallpapers");
        thread.setDaemon(true);
        return thread;
    });
    // Accessed only by the serial IO executor; at most 48 MiB of decoded pixels.
    private static final Map<Path, Image> CACHE = new LinkedHashMap<>(4, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, Image> entry) {
            return size() > 3;
        }
    };

    private BackgroundImageStore() {}

    /**
     * Resolves the managed wallpaper directory using the current {@code user.home} property.
     * This method does not create the directory or access its contents.
     *
     * @return path to {@code .meshapp/wallpapers} below the current user home
     */
    public static Path directory() {
        return Path.of(System.getProperty("user.home"), ".meshapp", "wallpapers");
    }

    /**
     * Asynchronously validates a PNG or JPEG and saves a normalized local PNG copy.
     * The destination directory is captured when this method is called. The source
     * remains unchanged, and the returned filename can be stored in
     * {@link ChatBackground#imageId()}.
     *
     * <p>The future completes exceptionally if validation, decoding or storage fails.
     * Expected size-limit failures carry {@code appearance.image.tooLarge} in the
     * underlying {@link IOException}; unsupported formats use
     * {@code appearance.image.invalid}. Other I/O failures retain their cause.
     *
     * @param source non-null path to the user's source image
     * @return future containing the managed PNG filename after the copy is fully written
     */
    public static CompletableFuture<String> importImage(Path source) {
        Path directory = directory();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return importFile(source, directory);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, IO);
    }

    private static String importFile(Path source, Path directory) throws IOException {
        if (Files.size(source) > MAX_FILE_BYTES) throw new IOException("appearance.image.tooLarge");
        BufferedImage decoded;
        try (var input = ImageIO.createImageInputStream(source.toFile())) {
            if (input == null) throw new IOException("appearance.image.invalid");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("appearance.image.invalid");
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) {
                    throw new IOException("appearance.image.invalid");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw new IOException("appearance.image.tooLarge");
                }
                var parameters = reader.getDefaultReadParam();
                int sample = Math.max(1, (Math.max(width, height) + MAX_EDGE - 1) / MAX_EDGE);
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                decoded = reader.read(0, parameters);
            } finally {
                reader.dispose();
            }
        }
        if (decoded == null) throw new IOException("appearance.image.invalid");
        double scale = Math.min(1, MAX_EDGE / (double) Math.max(decoded.getWidth(), decoded.getHeight()));
        BufferedImage normalized = new BufferedImage(Math.max(1, (int) (decoded.getWidth() * scale)),
                Math.max(1, (int) (decoded.getHeight() * scale)), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(decoded, 0, 0, normalized.getWidth(), normalized.getHeight(), null);
        } finally {
            graphics.dispose();
            decoded.flush();
        }
        Files.createDirectories(directory);
        String id = UUID.randomUUID() + ".png";
        Path target = directory.resolve(id);
        try {
            if (!ImageIO.write(normalized, "png", target.toFile())) throw new IOException("appearance.image.invalid");
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        } finally {
            normalized.flush();
        }
        return id;
    }

    /**
     * Asynchronously loads a managed image, reusing the decoded cache when possible.
     * The image retains its aspect ratio and is limited to {@value #MAX_EDGE} pixels
     * in either dimension. Cached images are returned without rechecking the file.
     *
     * @param id managed PNG filename; invalid identifiers, including {@code null}, are rejected
     * @return future containing the decoded image, or {@code null} for an invalid identifier,
     *         an unreadable file or an image decoding error
     */
    public static CompletableFuture<Image> load(String id) {
        Path directory = directory();
        return CompletableFuture.supplyAsync(() -> {
            if (id == null || !id.matches("[a-f0-9-]{36}\\.png")) return null;
            Path path = directory.resolve(id);
            if (CACHE.containsKey(path)) return CACHE.get(path);
            try (var input = Files.newInputStream(path)) {
                Image image = new Image(input, MAX_EDGE, MAX_EDGE, true, true);
                if (image.isError()) return null;
                CACHE.put(path, image);
                return image;
            } catch (IOException e) {
                return null;
            }
        }, IO);
    }

    /**
     * Asynchronously removes specified managed copies that no saved wallpaper uses.
     *
     * <p>The candidate set and directory are captured at invocation; current global
     * and per-chat references are checked by the worker before deletion. Only valid
     * managed filenames in the candidate set are considered, protecting other open
     * drafts and the user's original files. Deleted entries are also evicted from
     * the decoded-image cache.
     *
     * <p>Cleanup is best effort: preference-enumeration and I/O failures are suppressed,
     * leaving any remaining files available for a later cleanup attempt.
     *
     * @param candidates non-null set of non-null filenames owned or replaced by the closing editor
     * @return future completing after the cleanup attempt, including handled storage failures
     */
    public static CompletableFuture<Void> removeUnused(Set<String> candidates) {
        Path directory = directory();
        Set<String> snapshot = Set.copyOf(candidates);
        return CompletableFuture.runAsync(() -> {
            try {
                var referenced = AppearancePreferences.referencedImages();
                for (String id : snapshot) {
                    if (id.matches("[a-f0-9-]{36}\\.png") && !referenced.contains(id)) {
                        Path path = directory.resolve(id);
                        Files.deleteIfExists(path);
                        CACHE.remove(path);
                    }
                }
            } catch (IOException | java.util.prefs.BackingStoreException ignored) {
                // Retain files when references cannot be read; a later cleanup can retry.
            }
        }, IO);
    }
}
