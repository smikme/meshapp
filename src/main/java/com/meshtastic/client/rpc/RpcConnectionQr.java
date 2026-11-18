package com.meshtastic.client.rpc;

import com.google.gson.JsonObject;
import com.meshtastic.client.model.ConnectionEntry;
import java.net.URI;

/**
 * Encodes MeshApp RPC connection settings for transfer to another device by QR code.
 *
 * <p>The version-1 payload is a JSON object with {@code type: "meshapp.rpc"},
 * {@code version: 1}, {@code mode}, {@code address}, and {@code accessKey}.
 * Direct connections also include a numeric {@code port}; router connections use
 * the cloud WebSocket URL and omit the local TCP port.
 *
 * <p>Encoding is local: this class neither resolves hostnames nor opens a connection
 * or changes saved settings. The returned JSON contains the unencrypted access key
 * and must not be written to application logs.
 *
 * @see RpcAccessKey
 * @see ConnectionEntry#CLOUD_RPC_ROUTER_SERVER
 */
public final class RpcConnectionQr {
    /** Prevents instantiation of the stateless encoder. */
    private RpcConnectionQr() {}

    /**
     * Encodes the current host settings as a version-1 connection payload.
     *
     * <p>Cloud External takes precedence over direct RPC, including when both
     * transports are enabled. In cloud mode the direct address and port are neither
     * validated nor included in the result. The address is
     * {@link ConnectionEntry#CLOUD_RPC_ROUTER_SERVER} and {@code mode} is {@code router}.
     *
     * <p>In direct mode, the address must identify a specific host rather than a
     * wildcard listener such as {@code 0.0.0.0} or {@code ::}. Surrounding whitespace
     * is removed from the address, port, and key; IPv6 brackets are removed from the
     * exported address. Address validation checks syntax only, not reachability.
     *
     * @param cloudEnabled whether to export the Cloud External connection instead of direct TCP
     * @param directAddress direct hostname, IPv4 address, or IPv6 literal; ignored in cloud mode
     * @param directPort decimal TCP port from 1 through 65535; ignored in cloud mode
     * @param accessKey shared {@code mra1_} access key, validated in both modes
     * @return compact JSON containing the connection settings and the unencrypted access key
     * @throws IllegalArgumentException if the key is missing or invalid, or if direct mode
     *         is selected and its address or port is missing or invalid
     */
    public static String encode(boolean cloudEnabled, String directAddress,
                                String directPort, String accessKey) {
        String key = accessKey == null ? "" : accessKey.trim();
        RpcAccessKey.parse(key);
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "meshapp.rpc");
        payload.addProperty("version", 1);
        payload.addProperty("mode", cloudEnabled ? "router" : "direct");
        if (cloudEnabled) {
            payload.addProperty("address", ConnectionEntry.CLOUD_RPC_ROUTER_SERVER);
        } else {
            String address = normalizeDirectAddress(directAddress);
            int port;
            try {
                port = Integer.parseInt(directPort == null ? "" : directPort.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid direct RPC port");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Invalid direct RPC port");
            }
            payload.addProperty("address", address);
            payload.addProperty("port", port);
        }
        payload.addProperty("accessKey", key);
        return payload.toString();
    }

    /**
     * Normalizes a direct host address without performing a DNS lookup.
     *
     * <p>Removes surrounding whitespace and optional IPv6 brackets, rejects
     * wildcard listeners, and checks that the result can be used as a URI host.
     * A scheme, path, credentials, query, or appended TCP port is not accepted.
     *
     * @param input hostname or IP literal entered in the connection dialog
     * @return trimmed host, with IPv6 brackets removed
     * @throws IllegalArgumentException if the input is null, blank, a wildcard
     *         listener, longer than 253 characters, or not a valid host
     */
    private static String normalizeDirectAddress(String input) {
        String address = input == null ? "" : input.trim();
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        if (address.isBlank() || address.length() > 253
                || address.equals("0.0.0.0") || address.matches("[0:]+")
                || address.matches(".*[\\s/?#@].*")) {
            throw new IllegalArgumentException("A connectable direct RPC host is required");
        }
        // URI validation is syntactic only; generating a QR never resolves or contacts a host.
        String authority = address.contains(":") ? "[" + address + "]" : address;
        URI uri = URI.create("tcp://" + authority + ":1");
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid direct RPC host");
        }
        return address;
    }
}
