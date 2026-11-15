package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

    @Test
    void neverReplaysAnUploadAfterLosingFinalResponse() throws Exception {
        Path file = tempHome.resolve("firmware.bin");
        byte[] payload = FirmwareTestImages.esp(); Files.write(file, payload);
        FirmwareImage image = FirmwareImage.analyze(file);
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            var task = new FutureTask<>(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    readLine(socket.getInputStream());
                    socket.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    assertArrayEquals(payload, socket.getInputStream().readNBytes(payload.length));
                    // All bytes arrived, but the final response is lost.
                }
                server.setSoTimeout(500);
                org.junit.jupiter.api.Assertions.assertThrows(java.net.SocketTimeoutException.class, server::accept);
                return 1;
            });
            Thread thread = new Thread(task); thread.setDaemon(true); thread.start();
            var uploader = new FirmwareOtaTcpUploader("127.0.0.1", server.getLocalPort());
            Exception error = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> new FirmwareUpdateService().uploadWifi(image, uploader, null));
            assertFalse(error instanceof FirmwareOtaTcpUploader.ConnectFailedException);
            assertEquals(1, task.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void preservesPermanentHandshakeErrorWithoutRetrying() throws Exception {
        Path file = tempHome.resolve("firmware.bin"); Files.write(file, FirmwareTestImages.esp());
        try (ServerSocket server = new ServerSocket(0)) {
            var task = new FutureTask<Void>(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000); readLine(socket.getInputStream());
                    socket.getOutputStream().write("ERR No Partition\n".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                }
                server.setSoTimeout(500);
                org.junit.jupiter.api.Assertions.assertThrows(java.net.SocketTimeoutException.class, server::accept);
                return null;
            });
            Thread thread = new Thread(task); thread.setDaemon(true); thread.start();
            var uploader = new FirmwareOtaTcpUploader("127.0.0.1", server.getLocalPort());
            Exception error = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> new FirmwareUpdateService().uploadWifi(FirmwareImage.analyze(file), uploader, null));
            assertEquals("No Partition", error.getMessage());
            task.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void interruptClosesSocketWhileWaitingForBootloaderResponse() throws Exception {
        Path file = tempHome.resolve("firmware.bin");
        Files.write(file, FirmwareTestImages.esp());
        FirmwareImage image = FirmwareImage.analyze(file);
        CountDownLatch commandReceived = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            FutureTask<Integer> serverTask = new FutureTask<>(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    readLine(socket.getInputStream());
                    commandReceived.countDown();
                    // Leave the uploader blocked awaiting OK; cancellation must close this socket.
                    return socket.getInputStream().read();
                }
            });
            Thread serverThread = new Thread(serverTask, "ota-cancel-server");
            serverThread.setDaemon(true);
            serverThread.start();
            FutureTask<Void> upload = new FutureTask<>(() -> {
                new FirmwareOtaTcpUploader("127.0.0.1", server.getLocalPort()).upload(image, null);
                return null;
            });
            Thread worker = new Thread(upload, "ota-cancel-worker");
            worker.setDaemon(true);
            worker.start();
            try {
                assertTrue(commandReceived.await(3, TimeUnit.SECONDS));
                worker.interrupt();
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> upload.get(3, TimeUnit.SECONDS));
                assertInstanceOf(IOException.class, error.getCause());
                assertEquals(-1, serverTask.get(3, TimeUnit.SECONDS));
                worker.join(3000);
                assertFalse(worker.isAlive());
            } finally {
                worker.interrupt();
                worker.join(3000);
            }
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
