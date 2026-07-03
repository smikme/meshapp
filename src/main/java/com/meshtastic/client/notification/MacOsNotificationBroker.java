package com.meshtastic.client.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Loopback bridge from the self-updated payload to the stable macOS app process.
 */
public final class MacOsNotificationBroker {

    public static final String ENV_PORT = "MESHAPP_NOTIFICATION_BROKER_PORT";
    public static final String ENV_TOKEN = "MESHAPP_NOTIFICATION_BROKER_TOKEN";

    private static final Logger log = LoggerFactory.getLogger(MacOsNotificationBroker.class);
    private static final int SOCKET_TIMEOUT_MS = 2_000;
    private static final int MAX_TEXT_CHARS = 4_096;
    private static final SecureRandom RANDOM = new SecureRandom();

    private MacOsNotificationBroker() {}

    public static Server start(NotificationService service) throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Server server = new Server(serverSocket, generateToken(), service);
        server.start();
        return server;
    }

    public static Optional<Client> clientFromEnvironment() {
        return clientFrom(System.getenv(ENV_PORT), System.getenv(ENV_TOKEN));
    }

    static Optional<Client> clientFrom(String portValue, String token) {
        if (portValue == null || portValue.isBlank()
                || token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            int port = Integer.parseInt(portValue.trim());
            if (port <= 0 || port > 65_535) {
                return Optional.empty();
            }
            return Optional.of(new Client(port, token.trim()));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String protocolText(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_TEXT_CHARS ? value : value.substring(0, MAX_TEXT_CHARS);
    }

    public record Endpoint(int port, String token) {}

    public record Client(int port, String token) {
        public boolean showNotification(String title, String message) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                        SOCKET_TIMEOUT_MS);
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeUTF(token);
                output.writeUTF(protocolText(title));
                output.writeUTF(protocolText(message));
                output.flush();

                DataInputStream input = new DataInputStream(socket.getInputStream());
                return input.readBoolean();
            } catch (IOException e) {
                log.debug("macOS notification broker request failed", e);
                return false;
            }
        }
    }

    public static final class Server implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final String token;
        private final NotificationService service;
        private volatile boolean closed;
        private Thread thread;

        private Server(ServerSocket serverSocket, String token, NotificationService service) {
            this.serverSocket = serverSocket;
            this.token = token;
            this.service = service;
        }

        private void start() {
            thread = new Thread(this::run, "macos-notification-broker");
            thread.setDaemon(true);
            thread.start();
            log.info("macOS notification broker started on loopback port {}", port());
        }

        public Endpoint endpoint() {
            return new Endpoint(port(), token);
        }

        public int port() {
            return serverSocket.getLocalPort();
        }

        private void run() {
            while (!closed) {
                try (Socket socket = serverSocket.accept()) {
                    socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                    handle(socket);
                } catch (SocketException e) {
                    if (!closed) {
                        log.debug("macOS notification broker socket closed unexpectedly", e);
                    }
                    return;
                } catch (IOException e) {
                    log.debug("macOS notification broker request failed", e);
                }
            }
        }

        private void handle(Socket socket) throws IOException {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            String requestToken = input.readUTF();
            String title = input.readUTF();
            String message = input.readUTF();
            if (!token.equals(requestToken)) {
                output.writeBoolean(false);
                output.flush();
                return;
            }
            try {
                service.showNotification(title, message);
                output.writeBoolean(true);
            } catch (Throwable t) {
                log.warn("macOS notification broker failed to show notification", t);
                output.writeBoolean(false);
            }
            output.flush();
        }

        @Override
        public void close() {
            closed = true;
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
    }
}
