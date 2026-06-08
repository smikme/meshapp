package com.meshtastic.client.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Local firmware image metadata collected before asking a device to reboot into
 * an update bootloader.
 *
 * @param path normalized absolute path to the selected file
 * @param type detected image type
 * @param sizeBytes file size in bytes
 * @param sha256 SHA-256 digest of the selected file
 * @param zipEntries first file entries from a ZIP package, when applicable
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record FirmwareImage(
    Path path,
    FirmwareImageType type,
    long sizeBytes,
    byte[] sha256,
    List<String> zipEntries
) {
    private static final int MAX_ZIP_ENTRIES_TO_KEEP = 40;

    /**
     * Creates immutable image metadata and defensively copies mutable values.
     */
    public FirmwareImage {
        path = path != null ? path.toAbsolutePath().normalize() : null;
        type = type != null ? type : FirmwareImageType.UNKNOWN;
        sha256 = sha256 != null ? sha256.clone() : new byte[0];
        zipEntries = zipEntries != null ? List.copyOf(zipEntries) : List.of();
    }

    /**
     * Returns a defensive copy of the SHA-256 digest.
     *
     * @return 32-byte SHA-256 digest, or an empty array when unavailable
     */
    @Override
    public byte[] sha256() {
        return sha256.clone();
    }

    /**
     * Reads local file metadata, computes SHA-256, detects the image type, and
     * samples ZIP package entries when the selected file is an archive.
     *
     * @param path selected firmware file
     * @return analyzed firmware image metadata
     * @throws IOException when the file cannot be read
     */
    public static FirmwareImage analyze(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        long sizeBytes = Files.size(normalized);
        byte[] hash = sha256(normalized);
        FirmwareImageType type = detectType(normalized);
        List<String> entries = type == FirmwareImageType.ZIP
            ? readZipEntries(normalized)
            : List.of();
        return new FirmwareImage(normalized, type, sizeBytes, hash, entries);
    }

    /**
     * Returns the display file name without parent directories.
     *
     * @return selected file name, or an empty string when unavailable
     */
    public String fileName() {
        return path != null && path.getFileName() != null
            ? path.getFileName().toString()
            : "";
    }

    /**
     * Returns the selected file SHA-256 as lowercase hexadecimal text.
     *
     * @return hexadecimal SHA-256 digest
     */
    public String sha256Hex() {
        return HexFormat.of().formatHex(sha256);
    }

    /**
     * Checks whether sampled ZIP entries contain a binary firmware image.
     *
     * @return {@code true} when a sampled ZIP entry ends with {@code .bin}
     */
    public boolean zipContainsBin() {
        return zipEntries.stream().anyMatch(entry -> hasExtension(entry, ".bin"));
    }

    /**
     * Checks whether sampled ZIP entries contain a UF2 firmware image.
     *
     * @return {@code true} when a sampled ZIP entry ends with {@code .uf2}
     */
    public boolean zipContainsUf2() {
        return zipEntries.stream().anyMatch(entry -> hasExtension(entry, ".uf2"));
    }

    /**
     * Formats the file size for compact UI display.
     *
     * @return human-readable file size
     */
    public String displaySize() {
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        double kib = sizeBytes / 1024.0;
        if (kib < 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", kib);
        }
        return String.format(Locale.ROOT, "%.1f MiB", kib / 1024.0);
    }

    private static byte[] sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (
                InputStream input = Files.newInputStream(path);
                DigestInputStream digestInput = new DigestInputStream(
                    input,
                    digest
                )
            ) {
                digestInput.transferTo(OutputStreamSink.INSTANCE);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static FirmwareImageType detectType(Path path) {
        String name = path.getFileName() != null
            ? path.getFileName().toString().toLowerCase(Locale.ROOT)
            : "";
        if (name.endsWith(".bin")) {
            return FirmwareImageType.ESP32_BIN;
        }
        if (name.endsWith(".uf2")) {
            return FirmwareImageType.UF2;
        }
        if (name.endsWith(".zip")) {
            return FirmwareImageType.ZIP;
        }
        return FirmwareImageType.UNKNOWN;
    }

    private static List<String> readZipEntries(Path path) throws IOException {
        java.util.ArrayList<String> entries = new java.util.ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            while (
                entries.size() < MAX_ZIP_ENTRIES_TO_KEEP &&
                (entry = zip.getNextEntry()) != null
            ) {
                if (!entry.isDirectory()) {
                    entries.add(entry.getName());
                }
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static boolean hasExtension(String entry, String extension) {
        return entry != null &&
            entry.toLowerCase(Locale.ROOT).endsWith(extension);
    }

    private static final class OutputStreamSink extends java.io.OutputStream {

        private static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int b) {}

        @Override
        public void write(byte[] b, int off, int len) {}
    }
}
