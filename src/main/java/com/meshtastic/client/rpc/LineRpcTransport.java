package com.meshtastic.client.rpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP transport for direct MeshApp RPC connections.
 * <p>
 * Plain test transports serialize each RPC envelope as compact JSON followed by
 * one newline. Direct remote sessions wrap those envelopes in AES-GCM frames
 * before they reach the socket.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LineRpcTransport implements RpcTransport {

    private static final Logger log = LoggerFactory.getLogger(LineRpcTransport.class);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final long READER_JOIN_TIMEOUT_MS = 500;

    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final String description;
    private final RpcSessionCipher sessionCipher;
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile RpcTransportListener listener;
    private volatile Thread readerThread;

    LineRpcTransport(Socket socket, BufferedReader reader, BufferedWriter writer, String description) {
        this(socket, reader, writer, description, null);
    }

    private LineRpcTransport(Socket socket,
                             BufferedReader reader,
                             BufferedWriter writer,
                             String description,
                             RpcSessionCipher sessionCipher) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.description = description != null ? description : "rpc-line";
        this.sessionCipher = sessionCipher;
    }

    /**
     * Opens an unauthenticated direct TCP transport.
     * <p>
     * Production remote access should use {@link DirectRpcClient}, which
     * authenticates with {@link RpcAccessKey} and enables encrypted frames before
     * the RPC stream starts.
     */
    public static LineRpcTransport connect(String host, int port) throws IOException {
        return connect(host, port, DEFAULT_CONNECT_TIMEOUT);
    }

    /**
     * Opens an unauthenticated direct TCP transport.
     *
     * @param host host name or address
     * @param port TCP port
     * @param timeout connect timeout
     * @return open transport
     */
    public static LineRpcTransport connect(String host, int port, Duration timeout) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis(timeout));
        socket.setTcpNoDelay(true);
        return fromConnectedSocket(socket, "direct-rpc-client " + host + ":" + port);
    }

    static LineRpcTransport fromConnectedSocket(Socket socket, String description) throws IOException {
        socket.setTcpNoDelay(true);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        return new LineRpcTransport(socket, reader, writer, description);
    }

    static LineRpcTransport fromAuthenticatedSocket(Socket socket,
                                                    BufferedReader reader,
                                                    BufferedWriter writer,
                                                    String description,
                                                    RpcSessionCipher sessionCipher) throws IOException {
        socket.setTcpNoDelay(true);
        return new LineRpcTransport(socket, reader, writer, description, sessionCipher);
    }

    @Override
    public void setListener(RpcTransportListener listener) {
        this.listener = listener;
        if (listener != null && readerThread == null && isOpen()) {
            startReader();
        }
    }

    @Override
    public void send(String message) {
        if (!isOpen()) {
            throw new IllegalStateException("RPC transport is closed");
        }
        Objects.requireNonNull(message, "message");
        try {
            String outbound = sessionCipher != null ? sessionCipher.encrypt(message) : message;
            synchronized (writer) {
                writer.write(outbound);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            RpcTransportListener currentListener = listener;
            if (currentListener != null) {
                currentListener.onError("RPC write failed: " + e.getMessage(), e);
            }
            close();
        }
    }

    @Override
    public boolean isOpen() {
        return open.get() && !socket.isClosed();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("Error closing {}", description, e);
        }
        Thread thread = readerThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(READER_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closedLatch.countDown();
        RpcTransportListener currentListener = listener;
        if (currentListener != null) {
            currentListener.onClosed();
        }
    }

    /**
     * Waits until the transport closes.
     *
     * @param timeout wait timeout
     * @return {@code true} if closed before timeout
     */
    public boolean awaitClosed(Duration timeout) {
        try {
            return closedLatch.await(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void startReader() {
        readerThread = new Thread(this::readLoop, description + "-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try {
            String line;
            while (isOpen() && (line = reader.readLine()) != null) {
                String message = sessionCipher != null ? sessionCipher.decrypt(line) : line;
                RpcTransportListener currentListener = listener;
                if (currentListener != null) {
                    currentListener.onMessage(message);
                }
            }
        } catch (IOException e) {
            if (open.get()) {
                RpcTransportListener currentListener = listener;
                if (currentListener != null) {
                    currentListener.onError("RPC read failed: " + e.getMessage(), e);
                }
            }
        } finally {
            close();
        }
    }

    private static int connectTimeoutMillis(Duration timeout) {
        Duration effective = timeout != null ? timeout : DEFAULT_CONNECT_TIMEOUT;
        long millis = Math.max(1L, effective.toMillis());
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }
}
