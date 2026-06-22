package com.meshtastic.client.rpc;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import java.util.concurrent.CompletableFuture;

/**
 * Direct RPC client for connecting to {@link DirectRpcServer} with an access-key
 * authenticated encrypted session and without the external router.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class DirectRpcClient implements AutoCloseable {

    private static final Gson GSON = new Gson();
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final LineRpcTransport transport;
    private final RpcClient client;

    private DirectRpcClient(LineRpcTransport transport, RpcClient client) {
        this.transport = transport;
        this.client = client;
    }

    /**
     * Connects to a direct RPC server and authenticates with an access key.
     *
     * @param host host name or address
     * @param port TCP port
     * @param accessKey shared access key
     * @return connected client
     */
    public static DirectRpcClient connect(String host,
                                          int port,
                                          RpcAccessKey accessKey) throws IOException {
        return connect(host, port, accessKey, DEFAULT_CONNECT_TIMEOUT);
    }

    /**
     * Connects to a direct RPC server and authenticates with an access key.
     *
     * @param host host name or address
     * @param port TCP port
     * @param accessKey shared access key
     * @param timeout connect timeout
     * @return connected client
     */
    public static DirectRpcClient connect(String host,
                                          int port,
                                          RpcAccessKey accessKey,
                                          Duration timeout) throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(accessKey, "accessKey");

        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis(timeout));
        socket.setTcpNoDelay(true);

        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        try {
            RpcSessionCipher sessionCipher = authenticateClientSide(reader, writer, accessKey);
            LineRpcTransport transport = LineRpcTransport.fromAuthenticatedSocket(
                    socket,
                    reader,
                    writer,
                    "direct-rpc-client " + host + ":" + port,
                    sessionCipher);
            return new DirectRpcClient(transport, new RpcClient(transport));
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /**
     * Calls a host method using the default RPC timeout.
     */
    public CompletableFuture<JsonElement> call(String method, JsonElement params) {
        return client.call(method, params);
    }

    /**
     * Calls a host method with a custom RPC timeout.
     */
    public CompletableFuture<JsonElement> call(String method, JsonElement params, Duration timeout) {
        return client.call(method, params, timeout);
    }

    /**
     * Adds a host event listener.
     */
    public void addEventListener(RpcEventListener listener) {
        client.addEventListener(listener);
    }

    /**
     * Removes a host event listener.
     */
    public void removeEventListener(RpcEventListener listener) {
        client.removeEventListener(listener);
    }

    /**
     * @return low-level RPC client
     */
    public RpcClient rpcClient() {
        return client;
    }

    /**
     * @return whether the underlying authenticated transport is still open
     */
    public boolean isOpen() {
        return transport.isOpen();
    }

    @Override
    public void close() {
        client.close();
        transport.close();
    }

    private static RpcSessionCipher authenticateClientSide(BufferedReader reader,
                                                           BufferedWriter writer,
                                                           RpcAccessKey accessKey) throws IOException {
        String challengeLine = reader.readLine();
        if (challengeLine == null) {
            throw new IOException("server closed before authentication challenge");
        }
        JsonObject challenge = JsonParser.parseString(challengeLine).getAsJsonObject();
        String type = challenge.has("type") ? challenge.get("type").getAsString() : "";
        if (!"auth_challenge".equals(type)) {
            throw new IOException("unexpected authentication challenge");
        }
        int version = challenge.has("version") && challenge.get("version").isJsonPrimitive()
                ? challenge.get("version").getAsInt()
                : 0;
        if (version != 2) {
            throw new IOException("unsupported authentication challenge version");
        }
        String serverNonce = challenge.has("nonce") ? challenge.get("nonce").getAsString() : "";
        String clientNonce = RpcAccessKey.newNonce();

        JsonObject response = new JsonObject();
        response.addProperty("type", "auth_response");
        response.addProperty("version", 2);
        response.addProperty("clientNonce", clientNonce);
        response.addProperty("proof", accessKey.clientProof(serverNonce, clientNonce));
        writeControl(writer, response);

        String resultLine = reader.readLine();
        if (resultLine == null) {
            throw new IOException("server closed during authentication");
        }
        JsonObject result = JsonParser.parseString(resultLine).getAsJsonObject();
        String resultType = result.has("type") ? result.get("type").getAsString() : "";
        if (!"auth_ok".equals(resultType)) {
            String message = result.has("message") ? result.get("message").getAsString() : "authentication failed";
            throw new IOException(message);
        }
        String proof = result.has("proof") ? result.get("proof").getAsString() : "";
        if (!accessKey.verifyServerProof(serverNonce, clientNonce, proof)) {
            throw new IOException("server authentication failed");
        }
        return RpcSessionCipher.client(accessKey, serverNonce, clientNonce);
    }

    private static void writeControl(BufferedWriter writer, JsonObject object) throws IOException {
        writer.write(GSON.toJson(object));
        writer.newLine();
        writer.flush();
    }

    private static int connectTimeoutMillis(Duration timeout) {
        Duration effective = timeout != null ? timeout : DEFAULT_CONNECT_TIMEOUT;
        long millis = Math.max(1L, effective.toMillis());
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }
}
