package com.meshtastic.client.connection.ble.windows;

import com.google.gson.Gson;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ble.BleDevice;
import com.meshtastic.client.connection.ble.BlePlatform;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.connection.ble.BleState;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Out-of-process Windows BLE backend.
 * <p>
 * The parent process talks to this helper over stdin/stderr JSON lines. The
 * helper keeps WinRT/JNA/DLL state outside the JavaFX process, so a native BLE
 * crash terminates only this child process.
 */
public final class WinBleHelperMain {

    private static final Gson GSON = new Gson();

    private final BufferedReader input =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private final BufferedWriter protocol =
            new BufferedWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8));
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "win-ble-helper-worker");
        t.setDaemon(false);
        return t;
    });

    private WinBle platform;
    private volatile boolean running = true;

    private WinBleHelperMain() {
    }

    public static void main(String[] args) {
        int exitCode = new WinBleHelperMain().run();
        System.exit(exitCode);
    }

    private int run() {
        try {
            platform = new WinBle();
            installCallbacks();
            sendEvent("ready", Map.of());
            commandLoop();
            return 0;
        } catch (Throwable t) {
            sendEvent("fatal", Map.of("message", safeMessage(t)));
            return 1;
        } finally {
            shutdownPlatform();
            worker.shutdownNow();
        }
    }

    private void installCallbacks() {
        platform.setFromRadioListener(data -> sendEvent("data",
                Map.of("data", Base64.getEncoder().encodeToString(data))));
        platform.setStateListener(state -> {
            if (state instanceof BleState.Connected) {
                sendEvent("state", Map.of("state", 0));
            } else if (state instanceof BleState.Disconnected) {
                sendEvent("state", Map.of("state", 1));
            } else if (state instanceof BleState.Error error) {
                sendEvent("state", Map.of("state", 2, "message", safeText(error.message())));
            }
        });
        platform.setPasskeyRequestHandler(address -> sendEvent("passkey",
                Map.of("address", safeText(address))));
    }

    private void commandLoop() throws Exception {
        String line;
        while (running && (line = input.readLine()) != null) {
            Request request = GSON.fromJson(line, Request.class);
            if (request == null || request.cmd == null) {
                continue;
            }
            if ("respond_passkey".equals(request.cmd) || "cancel_passkey".equals(request.cmd)) {
                handleImmediate(request);
            } else {
                worker.execute(() -> handleWorkerCommand(request));
            }
        }
    }

    private void handleImmediate(Request request) {
        try {
            switch (request.cmd) {
                case "respond_passkey" -> platform.respondPasskey(request.passkey != null ? request.passkey : 0);
                case "cancel_passkey" -> platform.cancelPasskey();
                default -> throw new IllegalArgumentException("Unsupported immediate command: " + request.cmd);
            }
            sendResponse(request.id, true, Map.of());
        } catch (Throwable t) {
            sendResponse(request.id, false, Map.of("message", safeMessage(t)));
        }
    }

    private void handleWorkerCommand(Request request) {
        try {
            switch (request.cmd) {
                case "set_profile" -> {
                    platform.setProfile(profileFromNativeCode(request.profile != null ? request.profile : 0));
                    sendResponse(request.id, true, Map.of());
                }
                case "start_scan" -> {
                    platform.startScan(this::sendDevice);
                    sendResponse(request.id, true, Map.of());
                }
                case "stop_scan" -> {
                    platform.stopScan();
                    sendResponse(request.id, true, Map.of());
                }
                case "connect" -> {
                    try {
                        platform.connect(request.address);
                        sendResponse(request.id, true, Map.of());
                    } catch (ConnectionException e) {
                        sendResponse(request.id, false, Map.of("message", safeMessage(e)));
                    }
                }
                case "disconnect" -> {
                    platform.disconnect();
                    sendResponse(request.id, true, Map.of());
                }
                case "is_connected" -> sendResponse(request.id, true,
                        Map.of("value", platform.isConnected()));
                case "write" -> {
                    byte[] data = request.data != null
                            ? Base64.getDecoder().decode(request.data)
                            : new byte[0];
                    sendResponse(request.id, true, Map.of("value", platform.writeToRadio(data)));
                }
                case "adapter_state" -> sendResponse(request.id, true,
                        Map.of("adapterState", platform.getAdapterState().name()));
                case "dispose" -> {
                    running = false;
                    shutdownPlatform();
                    sendResponse(request.id, true, Map.of());
                }
                default -> sendResponse(request.id, false,
                        Map.of("message", "Unknown command: " + request.cmd));
            }
        } catch (Throwable t) {
            sendResponse(request.id, false, Map.of("message", safeMessage(t)));
        }
    }

    private void sendDevice(BleDevice device) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("address", safeText(device.address()));
        event.put("name", safeText(device.name()));
        event.put("rssi", device.rssi());
        sendEvent("device", event);
    }

    private synchronized void sendResponse(Integer id, boolean ok, Map<String, ?> fields) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("ok", ok);
        if (fields != null) {
            message.putAll(fields);
        }
        writeProtocol(message);
    }

    private synchronized void sendEvent(String event, Map<String, ?> fields) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("event", event);
        if (fields != null) {
            message.putAll(fields);
        }
        writeProtocol(message);
    }

    private void writeProtocol(Map<String, ?> message) {
        try {
            protocol.write(GSON.toJson(message));
            protocol.newLine();
            protocol.flush();
        } catch (Exception ignored) {
            running = false;
        }
    }

    private void shutdownPlatform() {
        WinBle current = platform;
        platform = null;
        if (current != null) {
            try {
                current.dispose();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static BleProtocolProfile profileFromNativeCode(int code) {
        return code == BleProtocolProfile.MESHCORE_COMPANION.nativeCode()
                ? BleProtocolProfile.MESHCORE_COMPANION
                : BleProtocolProfile.MESHTASTIC;
    }

    private static final class Request {
        int id;
        String cmd;
        String address;
        String data;
        Integer profile;
        Integer passkey;
    }
}
