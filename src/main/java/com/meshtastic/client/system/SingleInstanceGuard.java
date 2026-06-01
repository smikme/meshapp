package com.meshtastic.client.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Inter-process guard that prevents MeshApp from running twice.
 * <p>
 * The guard uses an operating-system file lock rather than a stale-prone PID
 * file. If the process crashes, the kernel releases the lock and the next launch
 * can proceed. A second instance sends a local activation command to the first
 * instance and exits immediately.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class SingleInstanceGuard implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuard.class);

    private static final String LOCK_FILE_NAME = "meshapp.lock";
    private static final String INSTANCE_FILE_NAME = "meshapp.instance";
    private static final String ACTIVATE_COMMAND = "ACTIVATE";
    private static final int ACTIVATION_CONNECT_ATTEMPTS = 30;
    private static final int ACTIVATION_CONNECT_DELAY_MS = 50;

    private final Path lockPath;
    private final Path instancePath;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final ServerSocket activationServer;
    private final String activationToken;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean pendingActivation = new AtomicBoolean(false);

    private volatile Runnable activationHandler;

    private SingleInstanceGuard(Path lockPath,
                                Path instancePath,
                                FileChannel lockChannel,
                                FileLock lock,
                                ServerSocket activationServer,
                                String activationToken) {
        this.lockPath = lockPath;
        this.instancePath = instancePath;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.activationServer = activationServer;
        this.activationToken = activationToken;
    }

    /**
     * Attempts to acquire the single-instance lock.
     *
     * @param appDirectory directory that stores MeshApp service files
     * @return guard for the first instance, or {@link Optional#empty()} if an
     *         active instance was found and received an activation command
     * @throws IOException if the service directory or lock file cannot be opened
     */
    public static Optional<SingleInstanceGuard> acquire(Path appDirectory) throws IOException {
        Files.createDirectories(appDirectory);

        Path lockPath = appDirectory.resolve(LOCK_FILE_NAME);
        Path instancePath = appDirectory.resolve(INSTANCE_FILE_NAME);
        FileChannel lockChannel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);

        FileLock lock;
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }

        if (lock == null) {
            closeQuietly(lockChannel);
            requestActivation(instancePath);
            return Optional.empty();
        }

        SingleInstanceGuard guard = null;
        try {
            guard = createLockedGuard(lockPath, instancePath, lockChannel, lock);
            guard.writeInstanceInfo();
            guard.startActivationLoop();
            return Optional.of(guard);
        } catch (IOException | RuntimeException e) {
            if (guard != null) {
                guard.close();
            } else {
                releaseQuietly(lock);
                closeQuietly(lockChannel);
            }
            throw e;
        }
    }

    /**
     * Sets the callback invoked when another launch attempt is detected.
     * <p>
     * If a second instance connected before the window was created, activation
     * is performed immediately after this callback is registered.
     *
     * @param handler action that activates the already running application
     */
    public void setActivationHandler(Runnable handler) {
        activationHandler = handler;
        if (handler != null && pendingActivation.compareAndSet(true, false)) {
            runActivationHandler(handler);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        closeQuietly(activationServer);
        releaseQuietly(lock);
        closeQuietly(lockChannel);
        deleteQuietly(instancePath);
        deleteQuietly(lockPath);
    }

    private static SingleInstanceGuard createLockedGuard(Path lockPath,
                                                         Path instancePath,
                                                         FileChannel lockChannel,
                                                         FileLock lock) throws IOException {
        ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        String token = UUID.randomUUID().toString();
        return new SingleInstanceGuard(lockPath, instancePath, lockChannel, lock, server, token);
    }

    private void startActivationLoop() {
        Thread thread = new Thread(() -> {
            while (!closed.get()) {
                try {
                    Socket socket = activationServer.accept();
                    handleActivationRequest(socket);
                } catch (IOException e) {
                    if (!closed.get()) {
                        log.debug("Single-instance activation server stopped unexpectedly", e);
                    }
                    return;
                }
            }
        }, "meshapp-single-instance");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleActivationRequest(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if ((ACTIVATE_COMMAND + " " + activationToken).equals(line)) {
                activate();
            }
        } catch (IOException e) {
            log.debug("Failed to handle single-instance activation request", e);
        }
    }

    private void activate() {
        Runnable handler = activationHandler;
        if (handler == null) {
            pendingActivation.set(true);
            return;
        }
        runActivationHandler(handler);
    }

    private void runActivationHandler(Runnable handler) {
        try {
            handler.run();
        } catch (RuntimeException e) {
            log.warn("Single-instance activation handler failed", e);
        }
    }

    private void writeInstanceInfo() throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(instancePath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writer.write("pid=" + ProcessHandle.current().pid());
            writer.newLine();
            writer.write("port=" + activationServer.getLocalPort());
            writer.newLine();
            writer.write("token=" + activationToken);
            writer.newLine();
            writer.write("startedAt=" + Instant.now());
            writer.newLine();
        }
    }

    private static void requestActivation(Path instancePath) {
        for (int attempt = 0; attempt < ACTIVATION_CONNECT_ATTEMPTS; attempt++) {
            InstanceInfo info = readInstanceInfo(instancePath);
            if (info != null && sendActivation(info)) {
                return;
            }
            sleepBeforeRetry();
        }
        log.warn("Another MeshApp instance is running, but activation request could not be delivered");
    }

    private static InstanceInfo readInstanceInfo(Path instancePath) {
        if (!Files.isRegularFile(instancePath)) {
            return null;
        }
        try {
            int port = -1;
            String token = null;
            for (String line : Files.readAllLines(instancePath, StandardCharsets.UTF_8)) {
                if (line.startsWith("port=")) {
                    port = Integer.parseInt(line.substring("port=".length()).trim());
                } else if (line.startsWith("token=")) {
                    token = line.substring("token=".length()).trim();
                }
            }
            if (port <= 0 || port > 65535 || token == null || token.isBlank()) {
                return null;
            }
            return new InstanceInfo(port, token);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private static boolean sendActivation(InstanceInfo info) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), info.port()), 250);
            socket.setSoTimeout(250);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(ACTIVATE_COMMAND);
                writer.write(' ');
                writer.write(info.token());
                writer.newLine();
                writer.flush();
            }
            return true;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(ACTIVATION_CONNECT_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void releaseQuietly(FileLock lock) {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private record InstanceInfo(int port, String token) {
    }
}
