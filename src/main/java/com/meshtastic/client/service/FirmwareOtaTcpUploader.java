package com.meshtastic.client.service;

import com.meshtastic.client.i18n.I18n;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.intellij.lang.annotations.MagicConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uploads an ESP32 firmware binary through the Meshtastic Unified OTA TCP
 * bootloader protocol.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class FirmwareOtaTcpUploader {

    public static final int DEFAULT_PORT = 3232;

    private static final Logger log = LoggerFactory.getLogger(
        FirmwareOtaTcpUploader.class
    );
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int ERASING_READ_TIMEOUT_MS = 120_000;
    private static final int VERIFY_READ_TIMEOUT_MS = 180_000;
    private static final int CHUNK_SIZE = 1024;
    private static final long WRITE_DELAY_MS = 10;

    private final String host;
    private final int port;

    /**
     * Creates a TCP OTA uploader.
     *
     * @param host OTA bootloader host or IP address
     * @param port OTA bootloader TCP port
     */
    public FirmwareOtaTcpUploader(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("OTA host is required");
        }
        this.host = host.trim();
        this.port = port > 0 ? port : DEFAULT_PORT;
    }

    /**
     * Uploads a firmware image to the already-running OTA bootloader.
     * Network operations use a non-blocking channel and bounded selector waits. Cancellation
     * is checked explicitly between waits, so it does not depend on interrupting a native
     * socket read. The entire upload has a fifteen-minute deadline.
     * Failure after connection establishment must not be retried automatically because the
     * loader may have accepted bytes or completed flashing before its reply was lost.
     *
     * @param image firmware image metadata
     * @param progressConsumer optional callback invoked on the uploading thread
     * @throws IOException when connection, command exchange, transfer or verification fails,
     *                     including cancellation or an expired operation deadline
     * @throws InterruptedException when the upload delay is interrupted
     */
    public void upload(
        FirmwareImage image,
        Consumer<FirmwareUpdateProgress> progressConsumer
    ) throws IOException, InterruptedException {
        try (SocketChannel channel = SocketChannel.open(); Selector selector = Selector.open()) {
            var connection = new OtaConnection(channel, selector);
            try {
                connection.connect(new InetSocketAddress(host, port));
            } catch (ClosedByInterruptException e) {
                throw e;
            } catch (IOException e) {
                throw new ConnectFailedException(e);
            }
            startOta(image, connection, progressConsumer);
            streamFirmware(image, connection, progressConsumer);
            waitForVerification(connection, progressConsumer);
        }
    }

    /**
     * Marks connection failure before any OTA command bytes were sent. Only this failure
     * permits the service to retry automatically; an established session may already have
     * modified flash even when its final acknowledgement is lost.
     */
    static final class ConnectFailedException extends IOException {
        ConnectFailedException(IOException cause) { super(cause.getMessage(), cause); }
    }

    private void startOta(
        FirmwareImage image,
        OtaConnection connection,
        Consumer<FirmwareUpdateProgress> progressConsumer
    ) throws IOException {
        String command = String.format(
            Locale.ROOT,
            "OTA %d %s\n",
            image.sizeBytes(),
            image.sha256Hex()
        );
        connection.write(ByteBuffer.wrap(command.getBytes(StandardCharsets.UTF_8)));

        emit(
            progressConsumer,
            FirmwareUpdateStage.CONNECTING_UPLOADER,
            0.55,
            I18n.t("settings.firmware.status.startingOta")
        );

        long deadline = connection.deadlineAfter(ERASING_READ_TIMEOUT_MS);
        boolean complete = false;
        while (!complete) {
            String line = readLine(connection, deadline);
            FirmwareOtaResponse response = FirmwareOtaResponse.parse(line);
            switch (response.kind()) {
                case OK -> complete = true;
                case ERASING -> emit(
                    progressConsumer,
                    FirmwareUpdateStage.CONNECTING_UPLOADER,
                    0.58,
                    I18n.t("settings.firmware.status.erasing")
                );
                case ACK -> log.debug("Ignoring unexpected OTA ACK during handshake");
                case ERROR -> throw otaError(response.message(), image.sha256Hex());
            }
        }
    }

    private void streamFirmware(
        FirmwareImage image,
        OtaConnection connection,
        Consumer<FirmwareUpdateProgress> progressConsumer
    ) throws IOException, InterruptedException {
        long totalBytes = image.sizeBytes();
        long sentBytes = 0;
        byte[] buffer = new byte[CHUNK_SIZE];
        try (InputStream input = Files.newInputStream(image.path())) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                connection.write(ByteBuffer.wrap(buffer, 0, read));
                sentBytes += read;
                double transferProgress = totalBytes > 0
                    ? (double) sentBytes / (double) totalBytes
                    : 1.0;
                emit(
                    progressConsumer,
                    FirmwareUpdateStage.UPLOADING,
                    0.60 + transferProgress * 0.33,
                    I18n.t(
                        "settings.firmware.status.uploading",
                        (int) Math.round(transferProgress * 100.0)
                    )
                );
                TimeUnit.MILLISECONDS.sleep(WRITE_DELAY_MS);
            }
        }
        log.info("Wi-Fi OTA upload stream complete: {} bytes", sentBytes);
    }

    private void waitForVerification(
        OtaConnection connection,
        Consumer<FirmwareUpdateProgress> progressConsumer
    ) throws IOException {
        emit(
            progressConsumer,
            FirmwareUpdateStage.VERIFYING,
            0.95,
            I18n.t("settings.firmware.status.verifying")
        );
        long deadline = connection.deadlineAfter(VERIFY_READ_TIMEOUT_MS);
        boolean complete = false;
        while (!complete) {
            String line = readLine(connection, deadline);
            FirmwareOtaResponse response = FirmwareOtaResponse.parse(line);
            switch (response.kind()) {
                case OK -> complete = true;
                case ACK -> log.debug("Ignoring late OTA ACK during verification");
                case ERASING -> log.debug("Ignoring late OTA ERASING during verification");
                case ERROR -> throw otaError(response.message(), null);
            }
        }
    }

    private IOException otaError(String message, String sha256Hex) {
        String detail = message != null && !message.isBlank()
            ? message
            : "unknown OTA error";
        if (
            sha256Hex != null &&
            detail.toLowerCase(Locale.ROOT).contains("hash rejected")
        ) {
            return new IOException(
                I18n.t("settings.firmware.status.hashRejected")
            );
        }
        if (detail.toLowerCase(Locale.ROOT).contains("hash mismatch")) {
            return new IOException(
                I18n.t("settings.firmware.status.hashMismatch")
            );
        }
        return new IOException(detail);
    }

    private String readLine(OtaConnection connection, long deadline) throws IOException {
        String line = connection.readLine(deadline);
        log.debug("Wi-Fi OTA response: {}", line);
        return line;
    }

    /**
     * Single-worker non-blocking OTA transport. The caller owns the channel and selector.
     * Every native wait is limited to 100 ms, with interruption and absolute deadlines
     * checked before and after waiting. No socket streams or timed blocking reads are used.
     * On macOS, jSerialComm 2.11.4 replaces the SIGIO handler used to interrupt blocking
     * socket I/O. Selector wakeups and bounded waits avoid relying on that signal handler.
     */
    static final class OtaConnection {
        private static final int WAIT_SLICE_MS = 100;
        private static final int MAX_RESPONSE_BYTES = 1024;
        private final SocketChannel channel;
        private final Selector selector;
        private final SelectionKey key;
        private final long uploadDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
        private final ByteBuffer inbound = ByteBuffer.allocate(MAX_RESPONSE_BYTES);

        OtaConnection(SocketChannel channel, Selector selector) throws IOException {
            this.channel = channel;
            this.selector = selector;
            channel.configureBlocking(false);
            key = channel.register(selector, 0);
            inbound.limit(0);
        }

        /** Connects without sending protocol bytes, under the separate connection deadline. */
        void connect(InetSocketAddress address) throws IOException {
            long deadline = deadlineAfter(CONNECT_TIMEOUT_MS);
            checkActive(deadline);
            if (!channel.connect(address)) {
                while (!channel.finishConnect()) awaitReady(SelectionKey.OP_CONNECT, deadline);
            }
            checkActive(deadline);
        }

        /** Caps a phase deadline by the total upload deadline; replies never extend it. */
        long deadlineAfter(int timeoutMs) {
            return Math.min(uploadDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        }

        /** Drains a buffer, allowing cancellation even when the peer stops receiving data. */
        void write(ByteBuffer data) throws IOException {
            while (data.hasRemaining()) {
                checkActive(uploadDeadline);
                if (channel.write(data) == 0) awaitReady(SelectionKey.OP_WRITE, uploadDeadline);
            }
        }

        /**
         * Reads a complete LF-terminated response, retaining fragments and any following lines.
         * CRLF is accepted. An incomplete line at EOF is a failure, not an acknowledgement.
         */
        String readLine(long deadline) throws IOException {
            var line = new ByteArrayOutputStream();
            while (true) {
                checkActive(deadline);
                while (inbound.hasRemaining()) {
                    byte next = inbound.get();
                    if (next == '\n') {
                        String text = line.toString(StandardCharsets.UTF_8);
                        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
                    }
                    if (line.size() >= MAX_RESPONSE_BYTES) throw new IOException("OTA response is too long");
                    line.write(next);
                }
                inbound.clear();
                int received = channel.read(inbound);
                inbound.flip();
                if (received < 0) throw new IOException(I18n.t("settings.firmware.status.loaderClosed"));
                if (received == 0) awaitReady(SelectionKey.OP_READ, deadline);
            }
        }

        /** Uses short waits as a fallback even if a platform fails to wake a selector on interrupt. */
        private void awaitReady(
            @MagicConstant(flagsFromClass = SelectionKey.class) int operation,
            long deadline
        ) throws IOException {
            key.interestOps(operation);
            while (true) {
                checkActive(deadline);
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                int ready = selector.select(Math.clamp(remainingMs, 1, WAIT_SLICE_MS));
                checkActive(deadline);
                selector.selectedKeys().clear();
                if (ready > 0) return;
            }
        }

        /** Preserves the interrupt flag while reporting cancellation as an I/O failure. */
        private static void checkActive(long deadline) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new ClosedByInterruptException();
            if (System.nanoTime() >= deadline) throw new SocketTimeoutException("OTA operation timed out");
        }
    }

    private void emit(
        Consumer<FirmwareUpdateProgress> consumer,
        FirmwareUpdateStage stage,
        double progress,
        String message
    ) {
        if (consumer != null) {
            consumer.accept(new FirmwareUpdateProgress(stage, progress, message));
        }
    }

    /**
     * Parsed Unified OTA text response.
     *
     * @param kind response kind
     * @param message optional error or detail text
     */
    record FirmwareOtaResponse(Kind kind, String message) {

        enum Kind {
            OK,
            ERASING,
            ACK,
            ERROR
        }

        static FirmwareOtaResponse parse(String line) {
            String trimmed = line != null ? line.trim() : "";
            if (trimmed.equals("OK") || trimmed.startsWith("OK ")) {
                return new FirmwareOtaResponse(Kind.OK, trimmed);
            }
            return switch (trimmed) {
                case "ERASING" -> new FirmwareOtaResponse(Kind.ERASING, trimmed);
                case "ACK" -> new FirmwareOtaResponse(Kind.ACK, trimmed);
                case "ERR" -> new FirmwareOtaResponse(Kind.ERROR, "Unknown error");
                default -> new FirmwareOtaResponse(Kind.ERROR,
                        trimmed.startsWith("ERR ") ? trimmed.substring(4) : "Unknown response: " + trimmed);
            };
        }
    }
}
