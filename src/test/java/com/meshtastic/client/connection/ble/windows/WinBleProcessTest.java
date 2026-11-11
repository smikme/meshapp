package com.meshtastic.client.connection.ble.windows;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.meshtastic.client.connection.ble.BleConnection;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.connection.ble.BleState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class WinBleProcessTest {

    @Test
    void meshCoreNotificationCanSendTheNextCommand() throws Exception {
        WinBleProcess platform = helper();
        BleConnection connection = new BleConnection("device", platform, BleProtocolProfile.MESHCORE_COMPANION);
        CompletableFuture<byte[]> reply = new CompletableFuture<>();
        connection.setDataListener(data -> {
            if (data[0] == 1) {
                // Companion sends DEVICE_QUERY from the SELF_INFO callback. sendBytesAsync
                // checks isConnected synchronously before queuing the write.
                connection.sendBytesAsync(new byte[] {22}, true).whenComplete((_, error) -> {
                    if (error != null) reply.completeExceptionally(error);
                });
            } else {
                reply.complete(data);
            }
        });
        try {
            connection.connect();
            connection.sendBytesAsync(new byte[] {1}, true).get(3, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] {22}, reply.get(3, TimeUnit.SECONDS));
            assertTrue(connection.isConnected());
        } finally {
            platform.dispose();
            connection.disconnect();
        }
    }

    @Test
    void disposeStopsCallbacksAndDiscardsQueuedNotifications() throws Exception {
        WinBleProcess platform = helper();
        CompletableFuture<Thread> firstEntered = new CompletableFuture<>();
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> received = new CopyOnWriteArrayList<>();
        platform.setFromRadioListener(data -> {
            received.add((int) data[0]);
            if (data[0] == 1) {
                firstEntered.complete(Thread.currentThread());
                try {
                    releaseFirst.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            platform.connect("device");
            assertTrue(platform.writeToRadio(new byte[] {1}));
            Thread callbackThread = firstEntered.get(3, TimeUnit.SECONDS);
            assertTrue(platform.writeToRadio(new byte[] {2}));
            // This reply follows the second notification on the same protocol stream.
            assertTrue(platform.isConnected());
            platform.dispose();
            callbackThread.join(3_000);
            assertFalse(callbackThread.isAlive());
            assertEquals(List.of(1), received);
        } finally {
            releaseFirst.countDown();
            platform.dispose();
        }
    }

    @Test
    void connectedListenerCanQueryTheHelper() throws Exception {
        WinBleProcess platform = helper();
        CompletableFuture<Boolean> connected = new CompletableFuture<>();
        platform.setStateListener(state -> {
            if (state instanceof BleState.Connected) connected.complete(platform.isConnected());
        });
        try {
            platform.connect("device");
            assertTrue(connected.get(3, TimeUnit.SECONDS));
        } finally {
            platform.dispose();
        }
    }

    @Test
    void passkeyListenerCanRespondWhileConnectIsPending() throws Exception {
        WinBleProcess platform = helper();
        List<BleState> states = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> paired = new CompletableFuture<>();
        platform.setStateListener(state -> {
            states.add(state);
            if (state instanceof BleState.Connected) paired.complete(null);
        });
        platform.setPasskeyRequestHandler(address -> platform.respondPasskey(123456));
        try {
            platform.connect("pairing");
            paired.get(3, TimeUnit.SECONDS);
            assertEquals(1, states.size());
            assertInstanceOf(BleState.Connected.class, states.getFirst());
            assertTrue(platform.isConnected());
        } finally {
            platform.dispose();
        }
    }

    @Test
    void slowOrFailingListenerPreservesEventOrderAndDoesNotBlockReplies() throws Exception {
        WinBleProcess platform = helper();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> secondReceived = new CompletableFuture<>();
        platform.setFromRadioListener(data -> {
            received.add((int) data[0]);
            if (data[0] == 1) {
                firstEntered.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                throw new IllegalStateException("Listener failed");
            }
            secondReceived.complete(null);
        });
        try {
            platform.connect("device");
            assertTrue(platform.writeToRadio(new byte[] {1}));
            assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
            assertTrue(platform.writeToRadio(new byte[] {2}));
            assertTrue(platform.isConnected());
            assertEquals(List.of(1), received);
            releaseFirst.countDown();
            secondReceived.get(3, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2), received);
        } finally {
            releaseFirst.countDown();
            platform.dispose();
        }
    }

    private static WinBleProcess helper() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String classpath = classpathEntry(FakeHelper.class) + File.pathSeparator + classpathEntry(Gson.class);
        return new WinBleProcess(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", classpath, FakeHelper.class.getName()));
    }

    private static String classpathEntry(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    /** Exercises the real stdin/stderr protocol without WinRT or BLE hardware. */
    public static final class FakeHelper {
        private static final Gson GSON = new Gson();

        public static void main(String[] args) throws Exception {
            send(Map.of("event", "ready"));
            int pairingRequest = 0;
            try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    JsonObject request = GSON.fromJson(line, JsonObject.class);
                    int id = request.get("id").getAsInt();
                    switch (request.get("cmd").getAsString()) {
                        case "connect" -> {
                            if ("pairing".equals(request.get("address").getAsString())) {
                                pairingRequest = id;
                                send(Map.of("event", "state", "state", 1));
                                send(Map.of("event", "passkey", "address", "pairing"));
                            } else {
                                send(Map.of("event", "state", "state", 0));
                                send(Map.of("id", id, "ok", true));
                            }
                        }
                        case "respond_passkey" -> {
                            send(Map.of("id", id, "ok", true));
                            send(Map.of("event", "state", "state", 0));
                            send(Map.of("id", pairingRequest, "ok", true));
                        }
                        case "is_connected" -> send(Map.of("id", id, "ok", true, "value", true));
                        case "write" -> {
                            send(Map.of("id", id, "ok", true, "value", true));
                            send(Map.of("event", "data", "data", request.get("data").getAsString()));
                        }
                        default -> send(Map.of("id", id, "ok", true));
                    }
                }
            }
        }

        private static void send(Map<String, ?> message) {
            System.err.println(GSON.toJson(message));
            System.err.flush();
        }
    }
}
