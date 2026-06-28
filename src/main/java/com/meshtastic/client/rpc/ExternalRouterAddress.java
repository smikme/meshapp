package com.meshtastic.client.rpc;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Normalizes user-entered ERPC Router addresses into WebSocket endpoint URIs.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class ExternalRouterAddress {

    private static final int DEFAULT_ROUTER_PORT = 8080;
    private static final String DEFAULT_PATH = "/rpc";

    private ExternalRouterAddress() {
    }

    /**
     * Builds the remote-client endpoint URI for a router room derived from the key.
     *
     * @param server user-entered router server address
     * @param port fallback port when {@code server} has no explicit port
     * @param accessKey shared MeshApp RPC access key
     * @return client WebSocket endpoint URI
     */
    static URI clientUri(String server, int port, RpcAccessKey accessKey) {
        return uri(server, port, accessKey.roomId(), "client");
    }

    /**
     * Builds the host endpoint URI for a router room derived from the key.
     *
     * @param server user-entered router server address
     * @param accessKey shared MeshApp RPC access key
     * @return host WebSocket endpoint URI
     */
    static URI hostUri(String server, RpcAccessKey accessKey) {
        return uri(server, DEFAULT_ROUTER_PORT, accessKey.roomId(), "host");
    }

    /**
     * Builds a normalized ERPC Router WebSocket URI.
     *
     * @param server user-entered router server address
     * @param fallbackPort fallback port when {@code server} has no explicit port
     * @param roomId room identifier
     * @param role router role, usually {@code host} or {@code client}
     * @return WebSocket endpoint URI
     */
    static URI uri(String server, int fallbackPort, String roomId, String role) {
        String value = requireServer(server);
        String withScheme = hasScheme(value) ? value : "ws://" + value;
        URI parsed = URI.create(withScheme);
        String scheme = parsed.getScheme() == null ? "ws" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!"ws".equals(scheme) && !"wss".equals(scheme)) {
            throw new IllegalArgumentException("ERPC Router address must use ws:// or wss://");
        }
        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("ERPC Router host is required");
        }
        int port = parsed.getPort() > 0
                ? parsed.getPort()
                : fallbackPort > 0 ? fallbackPort : DEFAULT_ROUTER_PORT;
        String path = parsed.getPath() == null || parsed.getPath().isBlank() || "/".equals(parsed.getPath())
                ? DEFAULT_PATH
                : parsed.getPath();
        String query = "roomId=" + url(roomId) + "&role=" + url(role);
        return URI.create(scheme + "://" + host + ":" + port + path + "?" + query);
    }

    private static boolean hasScheme(String value) {
        int index = value.indexOf("://");
        return index > 0;
    }

    private static String requireServer(String server) {
        String normalized = server == null ? "" : server.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("ERPC Router server is required");
        }
        return normalized;
    }

    private static String url(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8);
    }
}
