package com.meshtastic.client.rpc;

/**
 * Exception thrown by local RPC method handlers.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class RpcException extends Exception {

    private final String code;

    public RpcException(String code, String message) {
        super(message);
        this.code = normalizeCode(code);
    }

    public RpcException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = normalizeCode(code);
    }

    /**
     * @return stable machine-readable error code
     */
    public String getCode() {
        return code;
    }

    static String normalizeCode(String code) {
        return code == null || code.isBlank() ? "INTERNAL_ERROR" : code.trim();
    }
}
