package com.meshtastic.client.service;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Reproduces native-library signal interference in a child JVM, never in the test JVM. */
@Timeout(25)
class FirmwareOtaSignalInterferenceTest {
    @Test
    void cancellationSurvivesAnIgnoredNativeIoSignal(@TempDir Path temp) throws Exception {
        runProbe(temp, "ota", "Confirmed: OTA cancellation survives ignored native signal");
    }

    @Test
    void ignoredNativeIoSignalStallsBlockingRead(@TempDir Path temp) throws Exception {
        runProbe(temp, "blocking", "Confirmed: ignored native signal stalls blocking I/O");
    }

    /** Each probe owns a JVM so a deliberately stuck control cannot prevent the OTA check. */
    private static void runProbe(Path temp, String probe, String confirmation) throws Exception {
        assumeTrue(Platform.isLinux() || Platform.isMac());
        String classpath = String.join(File.pathSeparator,
                classpathEntry(SignalProbe.class), classpathEntry(FirmwareOtaTcpUploader.class),
                classpathEntry(Native.class), classpathEntry(LoggerFactory.class));
        Path output = temp.resolve(probe + "-signal-probe.log");
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", classpath, SignalProbe.class.getName(), probe)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), () -> "Signal probe stalled: " + readOutput(output));
            assertEquals(0, child.exitValue(), () -> readOutput(output));
            String log = readOutput(output);
            assertTrue(log.contains(confirmation), () -> "Missing probe confirmation: " + log);
            System.out.print(log);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(3, TimeUnit.SECONDS), "Signal probe process did not terminate");
            }
        }
    }

    private static String classpathEntry(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static String readOutput(Path output) {
        try { return Files.readString(output); }
        catch (Exception e) { return e.toString(); }
    }

    /**
     * jSerialComm 2.11.4 ignores SIGIO, used by macOS OpenJDK to wake blocking socket I/O.
     * Linux uses SIGRTMAX-2 instead, so disable that equivalent signal to reproduce the
     * same failure locally. The blocking control and OTA regression run in separate JVMs:
     * only the OTA worker is expected to recover and release its own resources.
     */
    public static final class SignalProbe {
        static void main(String[] args) throws Exception {
            // Install OpenJDK's native I/O handler before replacing it, as happens when
            // serial discovery loads jSerialComm after the JVM has already used sockets.
            try (ServerSocket server = new ServerSocket(0);
                 SocketChannel warmup = SocketChannel.open(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
                 Socket peer = server.accept()) {
                if (warmup.write(ByteBuffer.wrap(new byte[]{1})) != 1) throw new AssertionError("Socket warmup write failed");
                if (peer.getInputStream().read() != 1) throw new AssertionError("Socket warmup failed");
            }
            try (NativeLibrary libc = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME)) {
                int signal = Platform.isMac() ? 23
                        : libc.getFunction("__libc_current_sigrtmax").invokeInt(new Object[0]) - 2;
                var signalFunction = libc.getFunction("signal");
                Pointer previous = signalFunction.invokePointer(new Object[]{signal, new Pointer(1)}); // SIG_IGN
                if (Pointer.nativeValue(previous) == -1) throw new AssertionError("Could not disable native I/O signal");
                try {
                    switch (args[0]) {
                        case "blocking" -> verifyBlockingReadStalls();
                        case "ota" -> verifyOtaReadCancels();
                        default -> throw new IllegalArgumentException("Unknown signal probe: " + args[0]);
                    }
                } finally {
                    signalFunction.invokePointer(new Object[]{signal, previous});
                }
            }
        }

        /**
         * Confirms the injected failure, then halts this disposable control JVM. Once the
         * native wakeup signal is ignored, neither another interrupt nor peer EOF is a
         * portable way to release the stuck read. The OS reclaims its sockets on exit.
         */
        private static void verifyBlockingReadStalls() throws Exception {
            try (ServerSocket server = new ServerSocket(0);
                 SocketChannel channel = SocketChannel.open(); Socket socket = channel.socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
                socket.setSoTimeout(60_000);
                try (Socket _ = server.accept()) {
                    FutureTask<Integer> read = new FutureTask<>(() -> socket.getInputStream().read());
                    Thread worker = new Thread(read, "blocking-signal-probe");
                    worker.setDaemon(true);
                    worker.start();
                    awaitNativePoll(worker);
                    worker.interrupt();
                    try {
                        read.get(250, TimeUnit.MILLISECONDS);
                        throw new AssertionError("Blocking read unexpectedly completed");
                    } catch (TimeoutException expected) {
                        System.out.println("Confirmed: ignored native signal stalls blocking I/O");
                        System.out.flush();
                        // Success for this control is a stuck worker. Do not run Java cleanup
                        // or shutdown hooks that could depend on that worker completing.
                        Runtime.getRuntime().halt(0);
                    }
                }
            }
        }

        @SuppressWarnings("BusyWait") // Observe the actual native read before injecting the signal failure.
        private static void awaitNativePoll(Thread worker) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (Arrays.stream(worker.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().equals("sun.nio.ch.Net") && frame.getMethodName().equals("poll"))) return;
                Thread.sleep(10);
            }
            throw new AssertionError("Blocking probe did not reach native poll: " + Arrays.toString(worker.getStackTrace()));
        }

        private static void verifyOtaReadCancels() throws Exception {
            try (ServerSocket server = new ServerSocket(0);
                 SocketChannel channel = SocketChannel.open(); Selector selector = Selector.open()) {
                var connection = new FirmwareOtaTcpUploader.OtaConnection(channel, selector);
                connection.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(5000);
                    FutureTask<String> read = new FutureTask<>(() -> {
                        try (channel; selector) {
                            return connection.readLine(connection.deadlineAfter(10_000));
                        }
                    });
                    Thread worker = new Thread(read, "ota-signal-probe");
                    worker.setDaemon(true);
                    worker.start();
                    try {
                        Thread.sleep(250);
                        worker.interrupt();
                        try {
                            read.get(5, TimeUnit.SECONDS);
                            throw new AssertionError("OTA read completed without cancellation");
                        } catch (ExecutionException e) {
                            if (!(e.getCause() instanceof ClosedByInterruptException)) throw e;
                        }
                        if (peer.getInputStream().read() != -1) throw new AssertionError("OTA socket remained open");
                        System.out.println("Confirmed: OTA cancellation survives ignored native signal");
                    } finally {
                        worker.interrupt();
                        worker.join(5000);
                    }
                    if (worker.isAlive()) throw new AssertionError("OTA worker did not exit");
                }
            }
        }
    }
}
