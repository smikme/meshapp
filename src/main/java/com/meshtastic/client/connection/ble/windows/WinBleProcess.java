package com.meshtastic.client.connection.ble.windows;

import com.google.gson.Gson;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ble.BleDevice;
import com.meshtastic.client.connection.ble.BlePlatform;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.connection.ble.BleState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Windows BLE platform implementation backed by a child process.
 * <p>
 * WinRT/JNA crashes terminate the helper process instead of the main JavaFX
 * process. This keeps autoconnect enabled while containing native BLE failures.
 */
public final class WinBleProcess implements BlePlatform {

    private static final Logger log = LoggerFactory.getLogger(WinBleProcess.class);
    private static final Gson GSON = new Gson();
    private static final String START_TIMEOUT_SECONDS_PROPERTY =
            "meshapp.windowsBle.helperStartTimeoutSeconds";
    private static final Duration START_TIMEOUT =
            configuredPositiveDuration(START_TIMEOUT_SECONDS_PROPERTY, Duration.ofSeconds(45));
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CONNECT_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(15);
    private static final int RECENT_HELPER_OUTPUT_LINES = 12;

    private final Object processLock = new Object();
    private final Object writeLock = new Object();
    private final Object helperOutputLock = new Object();
    private final AtomicInteger nextRequestId = new AtomicInteger(1);
    private final AtomicBoolean connectInProgress = new AtomicBoolean(false);
    private final Map<Integer, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();
    private final Deque<String> recentHelperOutput = new ArrayDeque<>();

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile CompletableFuture<Void> readyFuture;
    private volatile boolean disposed;
    private volatile boolean connected;
    private volatile BleProtocolProfile profile = BleProtocolProfile.MESHTASTIC;
    private volatile Consumer<BleDevice> scanConsumer;
    private volatile Consumer<byte[]> fromRadioListener;
    private volatile Consumer<BleState> stateListener;
    private volatile Consumer<String> passkeyRequestHandler;

    @Override
    public void startScan(Consumer<BleDevice> onDeviceFound) {
        scanConsumer = onDeviceFound;
        try {
            sendProfileToHelper();
            call("start_scan", Map.of(), COMMAND_TIMEOUT);
        } catch (RuntimeException e) {
            log.error("Windows BLE helper scan failed: {}", e.getMessage());
            scanConsumer = null;
            throw e;
        }
    }

    @Override
    public void stopScan() {
        scanConsumer = null;
        try {
            call("stop_scan", Map.of(), COMMAND_TIMEOUT);
        } catch (RuntimeException e) {
            log.debug("Windows BLE helper stop scan failed: {}", e.getMessage());
        }
    }

    @Override
    public void connect(String address) throws ConnectionException {
        connectInProgress.set(true);
        try {
            sendProfileToHelper();
            Message response = call("connect", Map.of("address", Objects.toString(address, "")), CONNECT_TIMEOUT);
            if (!Boolean.TRUE.equals(response.ok)) {
                throw new ConnectionException(responseMessage(response, "Windows BLE helper connect failed"));
            }
        } catch (RuntimeException e) {
            throw new ConnectionException("Windows BLE helper connect failed: " + e.getMessage(), e);
        } finally {
            connectInProgress.set(false);
        }
    }

    @Override
    public void setProfile(BleProtocolProfile profile) {
        this.profile = profile == null ? BleProtocolProfile.MESHTASTIC : profile;
    }

    @Override
    public BleProtocolProfile getProfile() {
        return profile;
    }

    @Override
    public void disconnect() {
        try {
            call("disconnect", Map.of(), COMMAND_TIMEOUT);
        } catch (RuntimeException e) {
            log.debug("Windows BLE helper disconnect failed: {}", e.getMessage());
        } finally {
            connected = false;
        }
    }

    @Override
    public boolean isConnected() {
        try {
            Message response = call("is_connected", Map.of(), COMMAND_TIMEOUT);
            return Boolean.TRUE.equals(response.value);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean writeToRadio(byte[] protobufPayload) {
        try {
            String data = Base64.getEncoder().encodeToString(
                    protobufPayload != null ? protobufPayload : new byte[0]);
            Message response = call("write", Map.of("data", data), WRITE_TIMEOUT);
            return Boolean.TRUE.equals(response.value);
        } catch (RuntimeException e) {
            log.warn("Windows BLE helper write failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void setFromRadioListener(Consumer<byte[]> listener) {
        fromRadioListener = listener;
    }

    @Override
    public void setStateListener(Consumer<BleState> listener) {
        stateListener = listener;
    }

    @Override
    public AdapterState getAdapterState() {
        try {
            Message response = call("adapter_state", Map.of(), COMMAND_TIMEOUT);
            return response.adapterState != null ? AdapterState.valueOf(response.adapterState) : AdapterState.UNKNOWN;
        } catch (RuntimeException e) {
            return AdapterState.UNKNOWN;
        }
    }

    @Override
    public void setPasskeyRequestHandler(Consumer<String> handler) {
        passkeyRequestHandler = handler;
    }

    @Override
    public void respondPasskey(int passkey) {
        try {
            call("respond_passkey", Map.of("passkey", passkey), COMMAND_TIMEOUT);
        } catch (RuntimeException e) {
            log.warn("Windows BLE helper passkey response failed: {}", e.getMessage());
        }
    }

    @Override
    public void cancelPasskey() {
        try {
            call("cancel_passkey", Map.of(), COMMAND_TIMEOUT);
        } catch (RuntimeException e) {
            log.warn("Windows BLE helper passkey cancel failed: {}", e.getMessage());
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        stopHelper();
    }

    private Message call(String command, Map<String, ?> fields, Duration timeout) {
        ensureHelperStarted();
        int id = nextRequestId.getAndIncrement();
        CompletableFuture<Message> future = new CompletableFuture<>();
        pending.put(id, future);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", id);
        request.put("cmd", command);
        if (fields != null) {
            request.putAll(fields);
        }

        try {
            synchronized (writeLock) {
                BufferedWriter currentWriter = writer;
                if (currentWriter == null) {
                    throw new IllegalStateException("Windows BLE helper is not running");
                }
                currentWriter.write(GSON.toJson(request));
                currentWriter.newLine();
                currentWriter.flush();
            }
            Message response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!Boolean.TRUE.equals(response.ok)) {
                throw new IllegalStateException(responseMessage(response, command + " failed"));
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(command + " interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(command + " failed: " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            stopHelper();
            throw new IllegalStateException(command + " timed out", e);
        } catch (IOException e) {
            stopHelper();
            throw new IllegalStateException(command + " failed: " + e.getMessage(), e);
        } finally {
            pending.remove(id);
        }
    }

    private void sendProfileToHelper() {
        call("set_profile", Map.of("profile", profile.nativeCode()), COMMAND_TIMEOUT);
    }

    private void ensureHelperStarted() {
        if (disposed) {
            throw new IllegalStateException("Windows BLE helper is disposed");
        }
        Process current = process;
        if (current != null && current.isAlive()) {
            return;
        }
        synchronized (processLock) {
            current = process;
            if (current != null && current.isAlive()) {
                return;
            }
            startHelperLocked();
        }
    }

    private void startHelperLocked() {
        try {
            CompletableFuture<Void> ready = new CompletableFuture<>();
            readyFuture = ready;
            clearRecentHelperOutput();

            java.util.List<String> command = helperCommand();
            log.debug("Starting Windows BLE helper: {}", command);
            ProcessBuilder builder = new ProcessBuilder(command);
            Process started = builder.start();
            process = started;
            writer = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));

            Thread protocolThread = new Thread(() -> readProtocolLoop(started), "win-ble-helper-protocol");
            protocolThread.setDaemon(true);
            protocolThread.start();

            Thread logThread = new Thread(() -> readHelperStdout(started), "win-ble-helper-log");
            logThread.setDaemon(true);
            logThread.start();

            ready.get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopHelper();
            throw new IllegalStateException("Windows BLE helper start interrupted", e);
        } catch (ExecutionException e) {
            stopHelper();
            throw new IllegalStateException("Windows BLE helper failed to start: " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            String details = recentHelperOutputSummary();
            stopHelper();
            throw new IllegalStateException("Windows BLE helper start timed out after "
                    + START_TIMEOUT.toSeconds() + "s"
                    + " (set -D" + START_TIMEOUT_SECONDS_PROPERTY + "=<seconds> to adjust)"
                    + (details.isBlank() ? "" : ". Recent helper output: " + details), e);
        } catch (IOException e) {
            stopHelper();
            throw new IllegalStateException("Windows BLE helper start failed: " + e.getMessage(), e);
        }
    }

    private void readProtocolLoop(Process owner) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(owner.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Message message;
                try {
                    message = GSON.fromJson(line, Message.class);
                } catch (RuntimeException e) {
                    rememberHelperOutput("stderr", line);
                    log.debug("[win-ble-helper stderr] {}", line);
                    continue;
                }
                if (message == null) {
                    continue;
                }
                handleMessage(message);
            }
        } catch (Exception e) {
            if (!disposed) {
                log.warn("Windows BLE helper protocol ended: {}", e.getMessage());
            }
        } finally {
            failHelper("Windows BLE helper exited");
        }
    }

    private void readHelperStdout(Process owner) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(owner.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rememberHelperOutput("stdout", line);
                log.debug("[win-ble-helper] {}", line);
            }
        } catch (IOException ignored) {
        }
    }

    private void handleMessage(Message message) {
        if (message.id != null) {
            CompletableFuture<Message> future = pending.remove(message.id);
            if (future != null) {
                future.complete(message);
            }
            return;
        }
        if ("ready".equals(message.event)) {
            CompletableFuture<Void> ready = readyFuture;
            if (ready != null) {
                ready.complete(null);
            }
            return;
        }
        if ("fatal".equals(message.event)) {
            CompletableFuture<Void> ready = readyFuture;
            if (ready != null) {
                ready.completeExceptionally(new IllegalStateException(responseMessage(message, "fatal helper error")));
            }
            failHelper(responseMessage(message, "Windows BLE helper fatal error"));
            return;
        }
        switch (Objects.toString(message.event, "")) {
            case "device" -> {
                Consumer<BleDevice> consumer = scanConsumer;
                if (consumer != null && message.address != null) {
                    consumer.accept(new BleDevice(
                            message.address,
                            message.name != null ? message.name : "Unknown",
                            message.rssi != null ? message.rssi : 0,
                            profile.protocolType()));
                }
            }
            case "data" -> {
                Consumer<byte[]> listener = fromRadioListener;
                if (listener != null && message.data != null) {
                    listener.accept(Base64.getDecoder().decode(message.data));
                }
            }
            case "state" -> handleStateEvent(message);
            case "passkey" -> {
                Consumer<String> handler = passkeyRequestHandler;
                if (handler != null) {
                    handler.accept(message.address);
                }
            }
            default -> log.debug("Ignoring Windows BLE helper event: {}", message.event);
        }
    }

    private void handleStateEvent(Message message) {
        Consumer<BleState> listener = stateListener;
        if (listener == null || message.state == null) {
            return;
        }
        switch (message.state) {
            case 0 -> {
                connected = true;
                listener.accept(new BleState.Connected());
            }
            case 1 -> {
                connected = false;
                if (connectInProgress.get()) {
                    return;
                }
                listener.accept(new BleState.Disconnected());
            }
            case 2 -> {
                connected = false;
                if (connectInProgress.get()) {
                    return;
                }
                listener.accept(new BleState.Error(
                        message.message != null ? message.message : "Windows BLE helper error", null));
            }
            default -> {
            }
        }
    }

    private void failHelper(String message) {
        CompletableFuture<Void> ready = readyFuture;
        if (ready != null && !ready.isDone()) {
            ready.completeExceptionally(new IllegalStateException(message));
        }
        for (CompletableFuture<Message> future : pending.values()) {
            future.completeExceptionally(new IllegalStateException(message));
        }
        pending.clear();
        if (!disposed && connected && !connectInProgress.get()) {
            connected = false;
            Consumer<BleState> listener = stateListener;
            if (listener != null) {
                listener.accept(new BleState.Error(message, null));
            }
        }
    }

    private void rememberHelperOutput(String stream, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        synchronized (helperOutputLock) {
            recentHelperOutput.addLast(stream + ": " + line);
            while (recentHelperOutput.size() > RECENT_HELPER_OUTPUT_LINES) {
                recentHelperOutput.removeFirst();
            }
        }
    }

    private void clearRecentHelperOutput() {
        synchronized (helperOutputLock) {
            recentHelperOutput.clear();
        }
    }

    private String recentHelperOutputSummary() {
        synchronized (helperOutputLock) {
            return String.join(" | ", recentHelperOutput);
        }
    }

    private void stopHelper() {
        synchronized (processLock) {
            Process current = process;
            process = null;
            writer = null;
            if (current != null && current.isAlive()) {
                current.destroy();
                try {
                    if (!current.waitFor(2, TimeUnit.SECONDS)) {
                        current.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    current.destroyForcibly();
                }
            }
        }
    }

    private static String responseMessage(Message response, String fallback) {
        return response != null && response.message != null && !response.message.isBlank()
                ? response.message
                : fallback;
    }

    private static Duration configuredPositiveDuration(String propertyName, Duration fallback) {
        String raw = System.getProperty(propertyName);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static java.util.List<String> helperCommand() {
        String bin = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        ArrayList<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", bin).toString());
        command.add("-XX:+IgnoreUnrecognizedVMOptions");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("--sun-misc-unsafe-memory-access=allow");
        command.add("-cp");
        command.add(helperClasspath());
        command.add(WinBleHelperMain.class.getName());
        return java.util.List.copyOf(command);
    }

    private static String helperClasspath() {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            addClasspathEntry(entries, entry);
        }

        try {
            var source = WinBleProcess.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                addClasspathEntry(entries, Path.of(source.getLocation().toURI()).toString());
            }
        } catch (Exception ignored) {
        }

        return String.join(File.pathSeparator, entries);
    }

    private static void addClasspathEntry(Set<String> entries, String rawEntry) {
        if (rawEntry == null || rawEntry.isBlank()) {
            return;
        }
        entries.add(rawEntry);

        File file = new File(rawEntry);
        File directory = file.isDirectory() ? file : file.getParentFile();
        if (directory != null) {
            addSiblingJars(entries, directory);
        }
    }

    private static void addSiblingJars(Set<String> entries, File directory) {
        File[] jars = directory.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return;
        }
        Arrays.sort(jars, java.util.Comparator.comparing(File::getName));
        for (File jar : jars) {
            entries.add(jar.getPath());
        }
    }

    private static final class Message {
        Integer id;
        String event;
        Boolean ok;
        Boolean value;
        String message;
        String data;
        String address;
        String name;
        Integer rssi;
        Integer state;
        String adapterState;
    }
}
