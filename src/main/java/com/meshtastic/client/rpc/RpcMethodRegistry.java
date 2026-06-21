package com.meshtastic.client.rpc;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of host-side RPC methods.
 * <p>
 * Only methods explicitly registered here can be called by a remote peer.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RpcMethodRegistry {

    private final Map<String, RpcMethod> methods = new ConcurrentHashMap<>();

    /**
     * Registers or replaces one RPC method.
     *
     * @param name stable RPC method name, for example {@code messages.loadLast}
     * @param method handler
     * @return this registry for fluent setup
     */
    public RpcMethodRegistry register(String name, RpcMethod method) {
        String normalized = normalizeMethodName(name);
        methods.put(normalized, Objects.requireNonNull(method, "method"));
        return this;
    }

    /**
     * Returns a registered method.
     *
     * @param name method name from a request
     * @return registered handler, if present
     */
    public Optional<RpcMethod> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(methods.get(name.trim()));
    }

    /**
     * @return immutable snapshot of registered method names
     */
    public Set<String> methodNames() {
        return Set.copyOf(methods.keySet());
    }

    private static String normalizeMethodName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("RPC method name is required");
        }
        return name.trim();
    }
}
