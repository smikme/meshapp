package com.meshtastic.client.service;

import com.meshtastic.client.i18n.I18n;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
    private static final int COMMAND_READ_TIMEOUT_MS = 10_000;
    private static final int ERASING_READ_TIMEOUT_MS = 60_000;
    private static final int VERIFY_READ_TIMEOUT_MS = 15_000;
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
     *
     * @param image firmware image metadata
     * @param progressConsumer progress callback for the UI
     * @throws IOException when connection, command exchange, transfer, or verification fails
     * @throws InterruptedException when the upload delay is interrupted
     */
    public void upload(
        FirmwareImage image,
        Consumer<FirmwareUpdateProgress> progressConsumer
    ) throws IOException, InterruptedException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(COMMAND_READ_TIMEOUT_MS);
            OutputStream output = socket.getOutputStream();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    socket.getInputStream(),
                    StandardCharsets.UTF_8
                )
            );

            startOta(image, socket, output, reader, progressConsumer);
            streamFirmware(image, output, progressConsumer);
            waitForVerification(socket, reader, progressConsumer);
        }
    }

    private void startOta(
        FirmwareImage image,
        Socket socket,
        OutputStream output,
        BufferedReader reader,
        Consumer<FirmwareUpdateProgress> progressConsumer
    ) throws IOException {
        String command = String.format(
            Locale.ROOT,
            "OTA %d %s%n",
            image.sizeBytes(),
            image.sha256Hex()
        );
        output.write(command.getBytes(StandardCharsets.UTF_8));
        output.flush();

        emit(
            progressConsumer,
            FirmwareUpdateStage.CONNECTING_UPLOADER,
            0.55,
            I18n.t("settings.firmware.status.startingOta")
        );

        boolean complete = false;
        while (!complete) {
            String line = readLine(socket, reader, ERASING_READ_TIMEOUT_MS);
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
        OutputStream output,
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
                output.write(buffer, 0, read);
                output.flush();
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
        Socket socket,
        BufferedReader reader,
        Consumer<FirmwareUpdateProgress> progressConsumer
    ) throws IOException {
        emit(
            progressConsumer,
            FirmwareUpdateStage.VERIFYING,
            0.95,
            I18n.t("settings.firmware.status.verifying")
        );
        boolean complete = false;
        while (!complete) {
            String line = readLine(socket, reader, VERIFY_READ_TIMEOUT_MS);
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

    private String readLine(Socket socket, BufferedReader reader, int timeoutMs)
        throws IOException {
        socket.setSoTimeout(timeoutMs);
        String line = reader.readLine();
        if (line == null) {
            throw new IOException(
                I18n.t("settings.firmware.status.loaderClosed")
            );
        }
        log.debug("Wi-Fi OTA response: {}", line);
        return line;
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
            if (trimmed.equals("ERASING")) {
                return new FirmwareOtaResponse(Kind.ERASING, trimmed);
            }
            if (trimmed.equals("ACK")) {
                return new FirmwareOtaResponse(Kind.ACK, trimmed);
            }
            if (trimmed.equals("ERR")) {
                return new FirmwareOtaResponse(Kind.ERROR, "Unknown error");
            }
            if (trimmed.startsWith("ERR ")) {
                return new FirmwareOtaResponse(
                    Kind.ERROR,
                    trimmed.substring(4)
                );
            }
            return new FirmwareOtaResponse(
                Kind.ERROR,
                "Unknown response: " + trimmed
            );
        }
    }
}
