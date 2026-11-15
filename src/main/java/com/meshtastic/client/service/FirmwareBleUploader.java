package com.meshtastic.client.service;

import com.meshtastic.client.connection.ble.*;
import com.meshtastic.client.i18n.I18n;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Transfers ESP32 Unified OTA and Nordic legacy application DFU images over native GATT.
 * Each upload owns a separate BLE backend after the application connection has been released.
 * The two loaders use different framing and acknowledgements; neither uses Meshtastic protobuf
 * framing. Device reconnection and firmware-version verification belong to {@link FirmwareUpdateService}.
 */
final class FirmwareBleUploader {
    private final Supplier<BlePlatform> factory;
    /** Uses the native BLE backend for the current operating system. */
    FirmwareBleUploader() { this(BlePlatformFactory::create); }
    /** Supplies a fresh backend per upload, allowing protocol tests without physical hardware. */
    FirmwareBleUploader(Supplier<BlePlatform> factory) { this.factory = factory; }

    /**
     * Discovers the loader, transfers the payload and disposes the backend on every exit path.
     *
     * @param payload validated binary and optional legacy DFU init packet
     * @param request original device address and optional manual bootloader selector
     * @param progress optional callback for transfer progress
     * @throws Exception if discovery, connection, transfer or cancellation prevents completion
     */
    void upload(FirmwarePayload payload, FirmwareUpdateRequest request, Consumer<FirmwareUpdateProgress> progress) throws Exception {
        BlePlatform platform = factory.get();
        boolean nordic = payload.initPacket != null;
        try {
            platform.setProfile(nordic ? BleProtocolProfile.NORDIC_DFU : BleProtocolProfile.ESP32_OTA);
            String address = discover(platform, request, nordic);
            platform.connect(address);
            if (nordic) uploadNordic(platform, payload, progress);
            else uploadEsp(platform, payload.image, progress);
        } finally {
            try { platform.disconnect(); } finally { platform.dispose(); }
        }
    }

    /**
     * Scans for the selected loader service and waits twenty seconds for the original address
     * or its Nordic bootloader alternative. Other addresses require explicit selection within
     * two minutes; a nearby device is never chosen merely because it advertises the service.
     */
    private String discover(BlePlatform platform, FirmwareUpdateRequest request, boolean nordic) throws Exception {
        String original = request.connectionEntry().getBleAddress();
        String alternate = nordic ? incrementMac(original) : original;
        Map<String, BleDevice> found = new ConcurrentHashMap<>();
        var matching = new CompletableFuture<String>();
        platform.startScan(d -> {
            found.put(d.address(), d);
            if (d.address().equalsIgnoreCase(original) || d.address().equalsIgnoreCase(alternate)) matching.complete(d.address());
        });
        try {
            try { return matching.get(20, TimeUnit.SECONDS); }
            catch (TimeoutException e) {
                if (request.bleTargetSelector() == null || found.isEmpty()) throw new IOException(I18n.t("settings.firmware.status.bleNotFound"));
                BleDevice selected = request.bleTargetSelector().apply(List.copyOf(found.values())).get(120, TimeUnit.SECONDS);
                if (selected == null || !found.containsKey(selected.address())) throw new IOException(I18n.t("settings.firmware.status.cancelled"));
                return selected.address();
            }
        } finally { platform.stopScan(); }
    }

    /**
     * Computes the legacy Nordic bootloader address by incrementing the full 48-bit MAC.
     *
     * @param address colon-separated MAC, or a platform-specific identifier
     * @return incremented MAC with wraparound, or an empty string for non-MAC identifiers
     */
    static String incrementMac(String address) {
        if (address == null || !address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) return "";
        long n = (Long.parseLong(address.replace(":", ""), 16) + 1) & 0xffffffffffffL;
        String h = String.format(Locale.ROOT, "%012X", n);
        return String.join(":", h.split("(?<=\\G..)")).replaceAll(":$", "");
    }

    /**
     * Sends the OTA size/hash command and binary using minimum-MTU packets.
     * Notification fragments are assembled into lines. Each data packet waits for an ACK;
     * the final packet waits for OK after verification, with bounded erase and verify deadlines.
     *
     * @param platform connected backend using {@link BleProtocolProfile#ESP32_OTA}
     * @param image validated ESP binary snapshot
     * @param progress optional transfer callback
     * @throws Exception if a write, response, deadline or interruption aborts the transfer
     */
    static void uploadEsp(BlePlatform platform, FirmwareImage image, Consumer<FirmwareUpdateProgress> progress) throws Exception {
        var replies = new ReplyQueue<>("ERR OTA response overflow");
        StringBuilder pending = new StringBuilder();
        platform.setStateListener(state -> {
            if (!(state instanceof BleState.Connected))
                replies.add("ERR BLE disconnected during OTA");
        });
        platform.setFromRadioListener(bytes -> {
            synchronized (pending) {
                pending.append(new String(bytes, StandardCharsets.UTF_8));
                int newline;
                while ((newline = pending.indexOf("\n")) >= 0) {
                    replies.add(pending.substring(0, newline).trim());
                    pending.delete(0, newline + 1);
                }
                if (pending.length() > 1024) {
                    pending.setLength(0);
                    replies.add("ERR Invalid OTA response");
                }
            }
        });
        byte[] command = ("OTA " + image.sizeBytes() + " " + image.sha256Hex() + "\n").getBytes(StandardCharsets.US_ASCII);
        // Twenty bytes works with the minimum ATT MTU, including fragmented text commands.
        for (int p = 0; p < command.length; p += 20) write(platform, Arrays.copyOfRange(command, p, Math.min(p + 20, command.length)), false);
        awaitText(replies, "OK", 120);
        try (var input = Files.newInputStream(image.path())) {
            long sent = 0; byte[] buffer = new byte[20]; int n; int lastPercent = -1;
            while ((n = input.read(buffer)) != -1) {
                write(platform, Arrays.copyOf(buffer, n), false);
                sent += n;
                // The final packet receives OK instead of ACK.
                awaitText(replies, sent == image.sizeBytes() ? "OK" : "ACK", sent == image.sizeBytes() ? 180 : 15);
                int percent = (int)(sent * 100 / image.sizeBytes());
                if (percent != lastPercent) { transferProgress(progress, percent); lastPercent = percent; }
            }
        }
    }

    /** Waits for one expected reply; ERASING messages never extend the original deadline. */
    private static void awaitText(ReplyQueue<String> replies, String wanted, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            String response = replies.pollUntil(deadline);
            if (response == null) throw new IOException("OTA timed out waiting for " + wanted);
            if (response.equals(wanted)) return;
            if (response.equals("ERASING") && wanted.equals("OK")) continue;
            if (response.startsWith("ERR")) throw new IOException(response);
            throw new IOException("Unexpected OTA response: " + response);
        }
    }

    /**
     * Runs legacy Nordic START, INIT, RECEIVE, VALIDATE and ACTIVATE operations.
     * Commands use the acknowledged control point, while sizes, init data and image bytes
     * use the packet characteristic. Packet receipt notifications confirm every ten packets.
     * Secure DFU and updates containing a SoftDevice or bootloader are not supported here.
     *
     * @param platform connected backend using {@link BleProtocolProfile#NORDIC_DFU}
     * @param payload validated application binary with a legacy init packet
     * @param progress optional transfer callback
     * @throws Exception if the loader rejects an operation, disconnects early or times out
     */
    @SuppressWarnings("BusyWait") // WriteWithoutResponse requires packet pacing in addition to receipt notifications.
    static void uploadNordic(BlePlatform platform, FirmwarePayload payload, Consumer<FirmwareUpdateProgress> progress) throws Exception {
        var replies = new ReplyQueue<>(new byte[]{16,0,6});
        platform.setStateListener(state -> {
            if (!(state instanceof BleState.Connected))
                replies.add(new byte[]{16,0,6});
        });
        platform.setFromRadioListener(bytes -> replies.add(bytes.clone()));
        write(platform, new byte[]{1,4}, true);
        write(platform, ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(0).putInt((int)payload.image.sizeBytes()).array(), false);
        awaitDfu(replies, 1, -1);
        write(platform, new byte[]{2,0}, true);
        for (int p = 0; p < payload.initPacket.length; p += 20)
            write(platform, Arrays.copyOfRange(payload.initPacket, p, Math.min(p+20, payload.initPacket.length)), false);
        write(platform, new byte[]{2,1}, true); awaitDfu(replies, 2, -1);
        write(platform, new byte[]{8,10,0}, true);
        write(platform, new byte[]{3}, true);
        try (var input = Files.newInputStream(payload.image.path())) {
            long sent = 0; int packets = 0, lastPercent = -1; byte[] buffer = new byte[20]; int n;
            while ((n = input.read(buffer)) != -1) {
                write(platform, Arrays.copyOf(buffer, n), false); sent += n; packets++;
                // GATT command writes need pacing, plus receipt notifications to bound outstanding data.
                Thread.sleep(2);
                if (packets % 10 == 0 && sent < payload.image.sizeBytes()) awaitDfu(replies, 17, sent);
                int percent = (int)(sent * 100 / payload.image.sizeBytes());
                if (percent != lastPercent) { transferProgress(progress, percent); lastPercent = percent; }
            }
        }
        awaitDfu(replies, 3, -1);
        write(platform, new byte[]{4}, true); awaitDfu(replies, 4, -1);
        try {
            write(platform, new byte[]{5}, true);
        } catch (IOException e) {
            // Reset may close GATT before its write response. Application reconnect is still required.
            if (platform.isConnected()) throw e;
        }
    }

    /**
     * Waits for a successful control response or an exact packet-receipt byte count.
     * When awaiting a control response, a trailing receipt may be skipped without extending
     * the two-minute deadline. The count is used only for receipt opcode {@code 17}.
     */
    private static void awaitDfu(ReplyQueue<byte[]> replies, int opcode, long count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (true) {
            byte[] b = replies.pollUntil(deadline);
            if (b == null) throw new IOException("DFU timed out waiting for opcode " + opcode);
            if (b.length == 5 && b[0] == 17) {
                if (opcode != 17) continue; // final receipt may precede the completion response
                if (FirmwarePayload.u32(b,1) != count) throw new IOException("DFU byte count mismatch");
                return;
            }
            if (b.length == 3 && b[0] == 16 && b[1] == opcode && b[2] == 1) return;
            throw new IOException("DFU rejected operation " + opcode + ": " + HexFormat.of().formatHex(b));
        }
    }

    /**
     * Bounded loader replies with an internal producer lock. Replacing a full queue with
     * a terminal error must be atomic with respect to both notification and disconnect
     * callbacks. Consumers only remove entries and do not acquire the producer lock.
     */
    private static final class ReplyQueue<T> {
        private final BlockingQueue<T> replies = new LinkedBlockingQueue<>(256);
        private final Object producerLock = new Object();
        private final T overflowError;

        private ReplyQueue(T overflowError) {
            this.overflowError = overflowError;
        }

        /** Adds a reply, preserving an error marker when the bounded queue overflows. */
        private void add(T reply) {
            synchronized (producerLock) {
                if (!replies.offer(reply)) {
                    replies.clear();
                    replies.add(overflowError);
                }
            }
        }

        /** Waits until the monotonic deadline without extending it for intermediate replies. */
        private T pollUntil(long deadline) throws InterruptedException {
            return replies.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
    }

    /** Checks interruption before routing raw bytes to the DFU control or outbound data characteristic. */
    private static void write(BlePlatform platform, byte[] bytes, boolean control) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!(control ? platform.writeFirmwareControl(bytes) : platform.writeToRadio(bytes))) throw new IOException("BLE firmware write failed");
    }
    /** Maps a transfer percentage to the 35–90% interval, leaving room for setup and verification. */
    static void transferProgress(Consumer<FirmwareUpdateProgress> consumer, int percent) {
        if (consumer != null) consumer.accept(new FirmwareUpdateProgress(FirmwareUpdateStage.UPLOADING,
                .35 + percent * .0055, I18n.t("settings.firmware.status.uploading", percent)));
    }
}
