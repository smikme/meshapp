package com.meshtastic.client.service;

import com.meshtastic.client.i18n.I18n;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Copies validated UF2 blocks to an explicitly selected bootloader volume.
 * A successful copy confirms only filesystem delivery; {@link FirmwareUpdateService}
 * verifies that the original node returns to the application afterwards.
 */
final class FirmwareUf2Uploader {
    /**
     * Finds candidate bootloader roots in platform mount locations using {@code INFO_UF2.TXT}.
     * Discovery is advisory; a user selection and family validation are still required.
     *
     * @return distinct candidate roots, possibly empty if no volume is mounted yet
     */
    static List<Path> volumes() {
        Set<Path> roots = new LinkedHashSet<>();
        if (System.getProperty("os.name", "").startsWith("Windows"))
            FileSystems.getDefault().getRootDirectories().forEach(roots::add);
        for (Path base : List.of(Path.of("/Volumes"), Path.of("/media"), Path.of("/run/media"), Path.of("/mnt"))) {
            if (!Files.isDirectory(base)) continue;
            try (var paths = Files.walk(base, 2)) { paths.filter(Files::isDirectory).forEach(roots::add); }
            catch (IOException ignored) { }
        }
        return roots.stream().filter(p -> Files.isRegularFile(p.resolve("INFO_UF2.TXT"))).toList();
    }

    /**
     * Waits up to two minutes for explicit volume selection, then copies the payload.
     *
     * @param payload validated UF2 snapshot
     * @param request request containing the required volume selector
     * @param progress optional transfer callback
     * @throws Exception if selection is absent, cancelled or timed out, or copying fails
     */
    void upload(FirmwarePayload payload, FirmwareUpdateRequest request, Consumer<FirmwareUpdateProgress> progress) throws Exception {
        if (request.uf2TargetSelector() == null) throw new IOException(I18n.t("settings.firmware.validation.uf2TargetRequired"));
        Path target = request.uf2TargetSelector().apply(volumes()).get(120, TimeUnit.SECONDS);
        if (target == null) throw new IOException(I18n.t("settings.firmware.status.cancelled"));
        copy(payload, target, progress);
    }

    /**
     * Checks the volume's board descriptor and copies complete 512-byte blocks to a new
     * {@code MESHAPP.UF2}, forcing buffered writes before return. An existing destination
     * is never overwritten. Interruption stops future writes but cannot undo blocks already
     * consumed by the bootloader.
     *
     * @param payload validated UF2 snapshot with a known processor family
     * @param target user-selected mounted bootloader root
     * @param progress optional transfer callback
     * @throws IOException if the volume is incompatible, the destination exists or I/O fails
     * @throws InterruptedException if cancellation is observed between blocks
     */
    static void copy(FirmwarePayload payload, Path target, Consumer<FirmwareUpdateProgress> progress) throws IOException, InterruptedException {
        Path root = target.toRealPath();
        Path info = root.resolve("INFO_UF2.TXT");
        if (!Files.isRegularFile(info) || Files.size(info) > 16384 || !Files.isWritable(root))
            throw new IOException(I18n.t("settings.firmware.validation.uf2Volume"));
        String description = Files.readString(info).toLowerCase(Locale.ROOT);
        if (!description.contains("board-id:")) throw new IOException(I18n.t("settings.firmware.validation.uf2Volume"));
        if ((payload.uf2Family == 0xada52840 && !description.contains("nrf52840"))
                || (payload.uf2Family == 0xe48bff56 && !description.contains("rp2040")))
            throw new IOException(I18n.t("settings.firmware.validation.wrongHardware"));
        Path destination = root.resolve("MESHAPP.UF2");
        // Do not follow symlinks or overwrite an unrelated existing file on a mounted disk.
        try (var out = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             var in = Files.newInputStream(payload.image.path())) {
            byte[] block = new byte[512]; long sent = 0; int lastPercent = -1;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                int n = in.readNBytes(block, 0, block.length);
                if (n == 0) break;
                if (n != 512) throw new IOException("Truncated UF2 block");
                ByteBuffer data = ByteBuffer.wrap(block);
                while (data.hasRemaining()) {
                    if (out.write(data) == 0) throw new IOException("UF2 volume stopped accepting data");
                }
                sent += n;
                int percent = (int)(sent * 100 / payload.image.sizeBytes());
                if (percent != lastPercent) { FirmwareBleUploader.transferProgress(progress, percent); lastPercent = percent; }
            }
            out.force(true);
        }
        // Mass storage does not acknowledge image validation; the service verifies reboot separately.
    }
}
