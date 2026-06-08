package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class FirmwareOtaTcpUploaderTest {

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void uploadStreamsFirmwareAfterOtaHandshake() throws Exception {
        byte[] firmwareBytes = new byte[3000];
        for (int i = 0; i < firmwareBytes.length; i++) {
            firmwareBytes[i] = (byte) (i & 0xff);
        }
        Path firmwarePath = tempHome.resolve("firmware.bin");
        Files.write(firmwarePath, firmwareBytes);
        FirmwareImage image = FirmwareImage.analyze(firmwarePath);

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            FutureTask<ServerResult> serverTask = new FutureTask<>(
                () -> runOtaServer(serverSocket, firmwareBytes.length)
            );
            Thread serverThread = new Thread(serverTask, "ota-uploader-test");
            serverThread.setDaemon(true);
            serverThread.start();

            List<FirmwareUpdateProgress> progress = new ArrayList<>();
            FirmwareOtaTcpUploader uploader = new FirmwareOtaTcpUploader(
                "127.0.0.1",
                serverSocket.getLocalPort()
            );

            uploader.upload(image, progress::add);

            ServerResult result = serverTask.get();
            assertEquals(
                "OTA " + firmwareBytes.length + " " + image.sha256Hex(),
                result.command()
            );
            assertArrayEquals(firmwareBytes, result.payload());
            assertFalse(progress.isEmpty());
            assertTrue(
                progress
                    .stream()
                    .anyMatch(p -> p.stage() == FirmwareUpdateStage.UPLOADING)
            );
        }
    }

    private static ServerResult runOtaServer(
        ServerSocket serverSocket,
        int expectedBytes
    ) throws Exception {
        try (Socket socket = serverSocket.accept()) {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            String command = readLine(input);
            output.write("ERASING\n".getBytes(StandardCharsets.UTF_8));
            output.write("OK\n".getBytes(StandardCharsets.UTF_8));
            output.flush();

            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            while (payload.size() < expectedBytes) {
                int read = input.read(
                    buffer,
                    0,
                    Math.min(buffer.length, expectedBytes - payload.size())
                );
                if (read < 0) {
                    break;
                }
                payload.write(buffer, 0, read);
            }
            output.write("OK\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            return new ServerResult(command, payload.toByteArray());
        }
    }

    private static String readLine(InputStream input) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) >= 0) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    private record ServerResult(String command, byte[] payload) {}
}
