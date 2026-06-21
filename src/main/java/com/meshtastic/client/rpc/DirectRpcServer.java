package com.meshtastic.client.rpc;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Direct one-host/one-remote RPC server that does not require the external
 * router. It listens on a TCP port, authenticates a single remote peer with a
 * {@link RpcAccessKey}, derives an encrypted session, and then runs
 * {@link RpcServer} over that session.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class DirectRpcServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DirectRpcServer.class);
    private static final Gson GSON = new Gson();
    private static final Duration SESSION_WATCH_INTERVAL = Duration.ofMillis(250);

    private final ServerSocket serverSocket;
    private final RpcMethodRegistry registry;
    private final RpcAccessKey accessKey;
    private final Executor methodExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object activeLock = new Object();
    private volatile ActiveSession activeSession;
    private volatile Thread acceptThread;

    private DirectRpcServer(ServerSocket serverSocket,
                            RpcMethodRegistry registry,
                            RpcAccessKey accessKey,
                            Executor methodExecutor) {
        this.serverSocket = serverSocket;
        this.registry = registry;
        this.accessKey = accessKey;
        this.methodExecutor = methodExecutor;
    }

    /**
     * Starts a direct RPC server bound to loopback.
     */
    public static DirectRpcServer start(int port,
                                        RpcAccessKey accessKey,
                                        RpcMethodRegistry registry) throws IOException {
        return start(InetAddress.getLoopbackAddress(), port, accessKey, registry, ForkJoinPool.commonPool());
    }

    /**
     * Starts a direct RPC server.
     *
     * @param bindAddress address to bind
     * @param port TCP port, or {@code 0} for an ephemeral port
     * @param accessKey shared access key
     * @param registry RPC method registry
     * @param methodExecutor executor for method handlers
     * @return running server
     */
    public static DirectRpcServer start(InetAddress bindAddress,
                                        int port,
                                        RpcAccessKey accessKey,
                                        RpcMethodRegistry registry,
                                        Executor methodExecutor) throws IOException {
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(accessKey, "accessKey");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(methodExecutor, "methodExecutor");

        ServerSocket serverSocket = new ServerSocket(port, 1, bindAddress);
        DirectRpcServer server = new DirectRpcServer(serverSocket, registry, accessKey, methodExecutor);
        server.startAcceptThread();
        return server;
    }

    /**
     * @return actual TCP port
     */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /**
     * @return bound address
     */
    public InetAddress getBindAddress() {
        return serverSocket.getInetAddress();
    }

    /**
     * @return {@code true} when one authenticated direct remote client is active
     */
    public boolean hasActiveClient() {
        ActiveSession session = activeSession;
        return session != null && session.transport().isOpen();
    }

    /**
     * Publishes a host event to the active direct remote client, if connected.
     *
     * @param event event name
     * @param payload event payload, or {@code null}
     */
    public void publishEvent(String event, JsonElement payload) {
        ActiveSession session = activeSession;
        if (session != null && session.transport().isOpen()) {
            session.server().publishEvent(event, payload);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException e) {
            log.debug("Error closing direct RPC server socket", e);
        }
        ActiveSession session = activeSession;
        if (session != null) {
            session.close();
        }
        Thread thread = acceptThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void startAcceptThread() {
        acceptThread = new Thread(this::acceptLoop, "direct-rpc-server");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        log.info("Direct RPC server listening on {}:{}", getBindAddress().getHostAddress(), getPort());
        while (!closed.get()) {
            try {
                Socket socket = serverSocket.accept();
                handleAccepted(socket);
            } catch (IOException e) {
                if (!closed.get()) {
                    log.warn("Direct RPC accept failed", e);
                }
            }
        }
    }

    private void handleAccepted(Socket socket) {
        synchronized (activeLock) {
            if (activeSession != null && activeSession.transport().isOpen()) {
                log.info("Rejecting direct RPC client: active remote session already exists");
                closeQuietly(socket);
                return;
            }
            activeSession = null;
        }

        try {
            socket.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            RpcSessionCipher sessionCipher = authenticateServerSide(reader, writer);

            LineRpcTransport transport = LineRpcTransport.fromAuthenticatedSocket(
                    socket,
                    reader,
                    writer,
                    "direct-rpc-server-session",
                    sessionCipher);
            RpcServer rpcServer = new RpcServer(transport, registry, methodExecutor);
            ActiveSession session = new ActiveSession(transport, rpcServer);
            synchronized (activeLock) {
                activeSession = session;
            }
            startSessionWatcher(session);
            log.info("Direct RPC client connected");
        } catch (Exception e) {
            log.warn("Direct RPC client handshake failed: {}", e.getMessage());
            closeQuietly(socket);
        }
    }

    private RpcSessionCipher authenticateServerSide(BufferedReader reader, BufferedWriter writer) throws IOException {
        String serverNonce = RpcAccessKey.newNonce();
        JsonObject challenge = new JsonObject();
        challenge.addProperty("type", "auth_challenge");
        challenge.addProperty("version", 2);
        challenge.addProperty("cipher", "AES-256-GCM");
        challenge.addProperty("nonce", serverNonce);
        writeControl(writer, challenge);

        String responseLine = reader.readLine();
        if (responseLine == null) {
            throw new IOException("remote closed during authentication");
        }
        JsonObject response = JsonParser.parseString(responseLine).getAsJsonObject();
        String type = response.has("type") ? response.get("type").getAsString() : "";
        String clientNonce = response.has("clientNonce") ? response.get("clientNonce").getAsString() : "";
        String proof = response.has("proof") ? response.get("proof").getAsString() : "";
        if (!"auth_response".equals(type) || !accessKey.verifyClientProof(serverNonce, clientNonce, proof)) {
            JsonObject denied = new JsonObject();
            denied.addProperty("type", "auth_error");
            denied.addProperty("message", "invalid access key");
            writeControl(writer, denied);
            throw new IOException("invalid access key");
        }

        JsonObject ok = new JsonObject();
        ok.addProperty("type", "auth_ok");
        ok.addProperty("version", 2);
        ok.addProperty("cipher", "AES-256-GCM");
        ok.addProperty("proof", accessKey.serverProof(serverNonce, clientNonce));
        writeControl(writer, ok);
        return RpcSessionCipher.server(accessKey, serverNonce, clientNonce);
    }

    private void startSessionWatcher(ActiveSession session) {
        Thread watcher = new Thread(() -> {
            while (!closed.get() && session.transport().isOpen()) {
                session.transport().awaitClosed(SESSION_WATCH_INTERVAL);
            }
            synchronized (activeLock) {
                if (activeSession == session) {
                    activeSession = null;
                }
            }
            log.info("Direct RPC client disconnected");
        }, "direct-rpc-session-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void writeControl(BufferedWriter writer, JsonObject object) throws IOException {
        writer.write(GSON.toJson(object));
        writer.newLine();
        writer.flush();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private record ActiveSession(LineRpcTransport transport, RpcServer server) implements AutoCloseable {
        @Override
        public void close() {
            server.close();
        }
    }
}
