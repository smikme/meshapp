package com.meshtastic.client.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.Consumer;

public class TcpConnection implements MeshtasticConnection {

    private static final Logger log = LoggerFactory.getLogger(TcpConnection.class);

    public static final int DEFAULT_PORT = 4403;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final String host;
    private final int port;

    private volatile Socket socket;
    private volatile OutputStream outputStream;
    private volatile Consumer<byte[]> dataListener;
    private volatile ConnectionListener connectionListener;
    private volatile boolean running;
    private Thread readerThread;

    public TcpConnection(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public TcpConnection(String host) {
        this(host, DEFAULT_PORT);
    }

    @Override
    public void connect() throws ConnectionException {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            outputStream = socket.getOutputStream();

            log.info("Connected to {}:{}", host, port);

            running = true;
            readerThread = new Thread(this::readLoop, "tcp-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            ConnectionListener listener = connectionListener;
            if (listener != null) {
                listener.onConnected();
            }
        } catch (IOException e) {
            closeSocket();
            throw new ConnectionException("Failed to connect to " + host + ":" + port, e);
        }
    }

    @Override
    public void disconnect() {
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
            try {
                readerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readerThread = null;
        }

        closeSocket();

        ConnectionListener listener = connectionListener;
        if (listener != null) {
            listener.onDisconnected();
        }
    }

    @Override
    public boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed() && running;
    }

    @Override
    public synchronized void sendBytes(byte[] data) {
        if (!isConnected()) {
            log.warn("Cannot send: not connected");
            return;
        }
        try {
            outputStream.write(data);
            outputStream.flush();
            log.debug("Sent {} bytes to TCP {}:{}", data.length, host, port);
        } catch (IOException e) {
            log.error("Write failed to {}:{}", host, port, e);
            ConnectionListener listener = connectionListener;
            if (listener != null) {
                listener.onConnectionError("Write failed: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void setDataListener(Consumer<byte[]> listener) {
        this.dataListener = listener;
    }

    @Override
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    private void readLoop() {
        log.debug("TCP reader thread started for {}:{}", host, port);
        FrameParser parser = new FrameParser();

        byte[] buf = new byte[256];
        try {
            InputStream inputStream = socket.getInputStream();
            while (running && !Thread.currentThread().isInterrupted()) {
                int bytesRead = inputStream.read(buf);
                if (bytesRead < 0) {
                    if (running) {
                        log.info("TCP connection closed by remote host");
                        ConnectionListener listener = connectionListener;
                        if (listener != null) {
                            listener.onConnectionError("Connection closed by remote host", null);
                        }
                    }
                    break;
                }
                for (int i = 0; i < bytesRead; i++) {
                    byte[] packet = parser.processByte(buf[i]);
                    if (packet != null) {
                        Consumer<byte[]> listener = dataListener;
                        if (listener != null) {
                            listener.accept(packet);
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                log.error("TCP reader error", e);
                ConnectionListener listener = connectionListener;
                if (listener != null) {
                    listener.onConnectionError("Read error: " + e.getMessage(), e);
                }
            }
        }
        log.debug("TCP reader thread exiting");
    }

    private void closeSocket() {
        outputStream = null;
        Socket s = socket;
        socket = null;
        if (s != null && !s.isClosed()) {
            try {
                s.close();
                log.info("Closed TCP connection to {}:{}", host, port);
            } catch (IOException e) {
                log.debug("Error closing socket", e);
            }
        }
    }
}
