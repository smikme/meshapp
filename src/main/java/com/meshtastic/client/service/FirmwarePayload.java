package com.meshtastic.client.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validated firmware snapshot owned by a single validation or update operation.
 *
 * <p>The source and selected archive members are copied to a private temporary directory.
 * Archive member names are used only for lookup, never as extraction destinations. The
 * source hash identifies the user's file; {@link #image} identifies the bytes sent to the
 * device, which may differ when the source is a ZIP package.
 *
 * <p>Use with try-with-resources. Paths and payload data are intended for internal use only;
 * callers must not modify them or retain the temporary paths after {@link #close()}.
 */
final class FirmwarePayload implements AutoCloseable {
    /** Maximum size of a source file or an individually extracted archive member. */
    static final int MAX_BYTES = 32 * 1024 * 1024;
    private final Path directory;
    /** Validated application binary or UF2 file in the temporary directory. */
    final FirmwareImage image;
    /** Legacy Nordic DFU initialization packet, or {@code null} for ESP/UF2 payloads. */
    final byte[] initPacket;
    /** ESP image-header chip identifier, or {@code -1} for a non-ESP payload. */
    final int chipId;
    /** UF2 family identifier, or zero for a non-UF2 payload. */
    final int uf2Family;
    /** SHA-256 of the copied source, including the complete ZIP when applicable. */
    final byte[] sourceHash;

    private FirmwarePayload(Path directory, Path image, byte[] initPacket) throws IOException {
        this.directory = directory;
        this.image = FirmwareImage.analyze(image);
        this.initPacket = initPacket;
        Path archive = directory.resolve("package.zip");
        sourceHash = Files.exists(archive) ? FirmwareImage.analyze(archive).sha256() : this.image.sha256();
        byte[] bytes = Files.readAllBytes(image);
        if (initPacket != null) {
            if (bytes.length < 8 || initPacket.length < 12 || initPacket.length > 512) throw invalid("Invalid Nordic DFU application package");
            // Legacy init packet: device/revision/version, SoftDevice IDs, CRC16 of the image.
            int count = u16(initPacket, 8);
            if (initPacket.length != 12 + count * 2 || crc16(bytes) != u16(initPacket, initPacket.length - 2))
                throw invalid("Invalid legacy DFU init packet or CRC16 (Secure DFU packages are not compatible)");
            chipId = -1; uf2Family = 0;
        } else if (this.image.type() == FirmwareImageType.UF2) {
            uf2Family = validateUf2(bytes); chipId = -1;
        } else {
            chipId = validateEsp(bytes); uf2Family = 0;
        }
    }

    /**
     * Copies a source, selects its application payload and validates the binary structure.
     * Nordic packages must contain an application-only legacy DFU manifest. Other archives
     * must yield exactly one candidate after optional hardware-name filtering. Failed
     * preparation removes the temporary files before propagating the error.
     *
     * @param source user-selected BIN, UF2 or ZIP file
     * @param state optional device metadata used to disambiguate archive members
     * @return owned snapshot that the caller must close
     * @throws IOException if reading, extraction, selection or format validation fails
     */
    static FirmwarePayload prepare(Path source, DeviceState state) throws IOException {
        Path dir = Files.createTempDirectory("meshapp-firmware-");
        try {
            if (Files.size(source) > MAX_BYTES) throw invalid("Image exceeds 32 MiB");
            if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                String suffix = source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".uf2") ? ".uf2" : ".bin";
                Path target = dir.resolve("firmware" + suffix);
                try (var input = Files.newInputStream(source)) { writeBounded(input, target); }
                return new FirmwarePayload(dir, target, null);
            }
            Path archive = dir.resolve("package.zip");
            try (var in = Files.newInputStream(source)) { writeBounded(in, archive); }
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                if (zip.size() > 4096) throw invalid("Too many archive entries");
                List<? extends ZipEntry> entries = Collections.list(zip.entries());
                if (entries.stream().map(ZipEntry::getName).distinct().count() != entries.size())
                    throw invalid("Duplicate archive members");
                var manifests = entries.stream().filter(e -> e.getName().equals("manifest.json")).toList();
                if (!manifests.isEmpty()) {
                    JsonObject manifest;
                    try (var in = zip.getInputStream(manifests.getFirst())) {
                        byte[] json = in.readNBytes(65537);
                        if (json.length > 65536) throw invalid("Manifest too large");
                        manifest = JsonParser.parseString(new String(json, java.nio.charset.StandardCharsets.UTF_8))
                                .getAsJsonObject().getAsJsonObject("manifest");
                    }
                    if (manifest == null || !manifest.has("application") || manifest.size() != 1)
                        throw invalid("Select an application-only Nordic DFU package");
                    var app = manifest.getAsJsonObject("application");
                    Path bin = dir.resolve("application.bin");
                    extract(zip, app.get("bin_file").getAsString(), bin);
                    Path dat = dir.resolve("application.dat");
                    extract(zip, app.get("dat_file").getAsString(), dat);
                    return new FirmwarePayload(dir, bin, Files.readAllBytes(dat));
                }
                var candidates = entries.stream().filter(e -> !e.isDirectory()).filter(e -> {
                    String n = Path.of(e.getName()).getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".uf2") || (n.endsWith(".bin") && !n.contains("factory")
                            && !n.contains("bootloader") && !n.contains("partition") && !n.contains("-ota.bin"));
                }).toList();
                if (candidates.size() > 1 && state != null && state.getDeviceMetadata() != null) {
                    String model = normalize(state.getDeviceMetadata().getHwModel().name());
                    candidates = candidates.stream().filter(e -> normalize(e.getName()).contains(model)).toList();
                }
                if (candidates.size() != 1) throw new IOException(I18n.t("settings.firmware.validation.archiveAmbiguous"));
                String name = candidates.getFirst().getName();
                Path target = dir.resolve(name.toLowerCase(Locale.ROOT).endsWith(".uf2") ? "firmware.uf2" : "firmware.bin");
                extract(zip, name, target);
                return new FirmwarePayload(dir, target, null);
            }
        } catch (Exception e) {
            delete(dir);
            if (e instanceof IOException io) throw io;
            throw invalid("Invalid archive: " + e.getMessage());
        }
    }

    /** Removes punctuation and case differences when comparing hardware names to archive paths. */
    static String normalize(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    /** Extracts one named member into a caller-chosen path, subject to the decompressed size limit. */
    private static void extract(ZipFile zip, String name, Path target) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() > MAX_BYTES) throw invalid("Missing or oversized archive member: " + name);
        try (var in = zip.getInputStream(entry)) { writeBounded(in, target); }
    }

    /** Enforces the byte limit while streaming, even when an archive reports an unknown size. */
    private static void writeBounded(java.io.InputStream input, Path target) throws IOException {
        try (var out = Files.newOutputStream(target)) {
            long count = 0; byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) {
                count += n;
                if (count > MAX_BYTES) throw invalid("Unpacked image exceeds 32 MiB");
                out.write(buffer, 0, n);
            }
        }
    }

    /**
     * Checks an ESP application header, segment bounds, XOR checksum and optional SHA-256.
     * The application descriptor at the first segment excludes factory images and bootloaders.
     * This checks integrity and layout, not a cryptographic publisher signature.
     *
     * @param b complete application binary
     * @return chip identifier from the extended image header
     * @throws IOException if the layout or an integrity check is invalid
     */
    static int validateEsp(byte[] b) throws IOException {
        if (b.length < 288 || (b[0] & 255) != 0xe9 || b[1] < 1 || b[1] > 16)
            throw invalid("Not an ESP32 application image");
        if (u32(b, 32) != 0xabcd5432L) throw invalid("Select the application/update binary, not a factory image or bootloader");
        int pos = 24, checksum = 0xef;
        for (int i = 0; i < (b[1] & 255); i++) {
            if (pos + 8 > b.length) throw invalid("Truncated ESP segment header");
            long size = u32(b, pos + 4); pos += 8;
            if (size > b.length - pos) throw invalid("Truncated ESP segment");
            for (int j = 0; j < size; j++) checksum ^= b[pos++] & 255;
        }
        int end = pos | 15;
        if (end >= b.length || (b[end] & 255) != checksum) throw invalid("ESP image checksum mismatch");
        if (b[23] == 1) {
            if (end + 33 > b.length) throw invalid("Truncated ESP image digest");
            try {
                var sha = MessageDigest.getInstance("SHA-256");
                sha.update(b, 0, end + 1);
                if (!MessageDigest.isEqual(sha.digest(), Arrays.copyOfRange(b, end + 1, end + 33))) throw invalid("ESP image digest mismatch");
            } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        } else if (b[23] != 0) throw invalid("Invalid ESP image header");
        return u16(b, 12);
    }

    /**
     * Checks complete UF2 blocks, a consistent family and application flash address bounds.
     * Blocks may arrive out of order but must have unique indices covering the declared total.
     * Only nRF52840 and RP2040 family identifiers are accepted.
     *
     * @param b complete UF2 file
     * @return UF2 processor-family identifier
     * @throws IOException if a block, family or application address range is invalid
     */
    static int validateUf2(byte[] b) throws IOException {
        if (b.length == 0 || b.length % 512 != 0) throw invalid("UF2 must contain complete 512-byte blocks");
        Set<Long> seen = new HashSet<>(); int family = 0;
        for (int p = 0; p < b.length; p += 512) {
            if (u32(b,p) != 0x0a324655L || u32(b,p+4) != 0x9e5d5157L || u32(b,p+508) != 0x0ab16f30L)
                throw invalid("Invalid UF2 block signature");
            long flags = u32(b,p+8), size = u32(b,p+16), block = u32(b,p+20), total = u32(b,p+24);
            if ((flags & 0x2000) == 0 || (flags & 0x1001) != 0 || size == 0 || size > 476 || size % 4 != 0
                    || total != b.length / 512 || block >= total || !seen.add(block)) throw invalid("Invalid UF2 block layout");
            int f = (int)u32(b,p+28);
            if (p > 0 && f != family) throw invalid("Mixed UF2 families");
            family = f;
        }
        if (family != 0xada52840 && family != 0xe48bff56) throw invalid("Unsupported UF2 processor family");
        for (int p = 0; p < b.length; p += 512) {
            long address = u32(b,p+12), end = address + u32(b,p+16);
            if ((family == 0xada52840 && (address < 0x26000 || end > 0xf4000))
                    || (family == 0xe48bff56 && (address < 0x10000000L || end > 0x11000000L)))
                throw invalid("UF2 contains addresses outside application flash");
        }
        return family;
    }

    /** Reads an unsigned little-endian 32-bit field after the caller has checked its bounds. */
    static long u32(byte[] b, int p) { return Integer.toUnsignedLong(ByteBuffer.wrap(b,p,4).order(ByteOrder.LITTLE_ENDIAN).getInt()); }
    /** Reads an unsigned little-endian 16-bit field after the caller has checked its bounds. */
    static int u16(byte[] b, int p) { return (b[p] & 255) | ((b[p+1] & 255) << 8); }

    /**
     * Reads the version string from the validated ESP application descriptor.
     *
     * @return trimmed, NUL-terminated version, or an empty string for a non-ESP payload
     * @throws IOException if the snapshot can no longer be read
     */
    String applicationVersion() throws IOException {
        if (chipId < 0) return "";
        byte[] bytes;
        try (var input = Files.newInputStream(image.path())) { bytes = input.readNBytes(80); }
        int end = 48;
        while (end < 80 && bytes[end] != 0) end++;
        return new String(bytes, 48, end - 48, java.nio.charset.StandardCharsets.US_ASCII).trim();
    }

    /**
     * Computes the legacy Nordic init-packet CRC (CRC-16/CCITT with initial value {@code 0xffff}).
     *
     * @param b application bytes covered by the init packet
     * @return unsigned 16-bit checksum
     */
    static int crc16(byte[] b) {
        int crc = 0xffff;
        for (byte value : b) {
            crc = ((crc >>> 8) | (crc << 8)) & 0xffff;
            crc ^= value & 255; crc ^= (crc & 255) >>> 4;
            crc ^= (crc << 12) & 0xffff; crc ^= ((crc & 255) << 5) & 0xffff;
        }
        return crc;
    }
    private static IOException invalid(String reason) { return new IOException(I18n.t("settings.firmware.validation.invalidImage", reason)); }
    /**
     * Deletes the source snapshot and extracted members.
     *
     * @throws IOException if a temporary file or its directory cannot be removed
     */
    @Override public void close() throws IOException { delete(directory); }
    private static void delete(Path directory) throws IOException {
        try (var files = Files.list(directory)) { for (Path p : files.toList()) Files.deleteIfExists(p); }
        Files.deleteIfExists(directory);
    }
}
