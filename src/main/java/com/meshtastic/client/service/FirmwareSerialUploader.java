package com.meshtastic.client.service;

import com.meshtastic.client.i18n.I18n;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Runs external esptool with an explicit serial port and the application offset {@code 0x10000}.
 * Arguments are passed directly to {@link ProcessBuilder}; paths are never interpreted by a shell.
 * The caller must release the application's serial connection before uploading and verify
 * the node's return afterwards. This uploader does not erase or replace the partition table.
 */
final class FirmwareSerialUploader {
    private final List<String> command;
    private final boolean version5;
    /**
     * Retains the executable invocation and detected command spelling.
     *
     * @param command executable and optional fixed arguments, such as {@code python3 -m esptool}
     * @param version5 whether the help output advertises {@code write-flash} instead of {@code write_flash}
     */
    FirmwareSerialUploader(List<String> command, boolean version5) { this.command = List.copyOf(command); this.version5 = version5; }

    /**
     * Probes esptool before the device is disconnected, allowing five seconds per invocation.
     * {@code MESHAPP_ESPTOOL}, when set, is treated as a single executable path and overrides
     * discovery through PATH and Python modules.
     *
     * @return uploader with a verified command invocation
     * @throws IOException if no candidate advertises a supported flash command
     * @throws InterruptedException if discovery is cancelled
     */
    static FirmwareSerialUploader discover() throws IOException, InterruptedException {
        List<List<String>> candidates = new ArrayList<>();
        String configured = System.getenv("MESHAPP_ESPTOOL");
        if (configured != null && !configured.isBlank()) candidates.add(List.of(configured));
        else {
            candidates.add(List.of("esptool")); candidates.add(List.of("esptool.py"));
            candidates.add(List.of("python3", "-m", "esptool")); candidates.add(List.of("python", "-m", "esptool"));
            if (System.getProperty("os.name", "").startsWith("Windows")) candidates.add(List.of("py", "-3", "-m", "esptool"));
        }
        for (var candidate : candidates) {
            var args = new ArrayList<>(candidate); args.add("--help");
            try {
                String output = run(args, Duration.ofSeconds(5), null);
                if (output.contains("write-flash") || output.contains("write_flash"))
                    return new FirmwareSerialUploader(candidate, output.contains("write-flash"));
            } catch (IOException ignored) { }
        }
        throw new IOException(I18n.t("settings.firmware.validation.esptoolMissing"));
    }

    /**
     * Builds an application-only write command at 115200 baud.
     *
     * @param port explicitly selected serial port
     * @param image validated ESP application snapshot
     * @return immutable process argument list
     * @throws IllegalArgumentException if no port is supplied
     */
    List<String> arguments(String port, FirmwareImage image) {
        if (port == null || port.isBlank()) throw new IllegalArgumentException("Serial port is required");
        List<String> args = new ArrayList<>(command);
        args.addAll(List.of("--port", port, "--baud", "115200", version5 ? "write-flash" : "write_flash", "0x10000", image.path().toString()));
        return List.copyOf(args);
    }

    /**
     * Writes the application with a fifteen-minute process deadline.
     *
     * @param port serial port already released by the application
     * @param image validated application snapshot
     * @param progress optional callback invoked on the process-output reader thread
     * @throws IOException if esptool fails, times out or its output cannot be read
     * @throws InterruptedException if the waiting worker is cancelled
     */
    void upload(String port, FirmwareImage image, Consumer<FirmwareUpdateProgress> progress) throws IOException, InterruptedException {
        run(arguments(port, image), Duration.ofMinutes(15), progress);
    }

    /**
     * Runs a bounded child process and drains merged stdout/stderr concurrently to avoid
     * blocking on full pipes. Retains the last 32 KiB of text for failure diagnostics.
     * Timeout or interruption terminates a still-running process and requests descendant exit.
     *
     * @param args executable and arguments, with no shell quoting
     * @param timeout maximum process runtime
     * @param progress optional callback for percentages parsed from process output
     * @return retained output from a successful process
     * @throws IOException if launch, execution or output collection fails
     * @throws InterruptedException if the waiting thread is interrupted
     */
    static String run(List<String> args, Duration timeout, Consumer<FirmwareUpdateProgress> progress) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var output = new StringBuilder();
        var reader = new FutureTask<Void>(() -> {
            var percent = Pattern.compile("(\\d{1,3})(?:\\.\\d+)?\\s*%");
            try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                        if (output.length() > 32768) output.delete(0, output.length() - 32768);
                    }
                    var m = percent.matcher(line);
                    if (m.find()) FirmwareBleUploader.transferProgress(progress, Math.min(100, Integer.parseInt(m.group(1))));
                }
            }
            return null;
        });
        Thread thread = new Thread(reader, "firmware-esptool-output"); thread.setDaemon(true); thread.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("esptool timed out");
            try { reader.get(5, TimeUnit.SECONDS); }
            catch (ExecutionException | TimeoutException e) { throw new IOException("Could not read esptool output", e); }
            String text;
            synchronized (output) { text = output.toString(); }
            if (process.exitValue() != 0) throw new IOException("esptool (" + process.exitValue() + "): " + text);
            return text;
        } finally {
            if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroy); process.destroyForcibly(); }
            try { process.getInputStream().close(); } catch (IOException ignored) { }
        }
    }
}
