package com.meshtastic.client.rpc;

/**
 * Exception used by {@link RpcClient} when the remote peer returns an error
 * response.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class RpcRemoteException extends RuntimeException {

    private final String code;

    public RpcRemoteException(String code, String message) {
        super(message);
        this.code = RpcException.normalizeCode(code);
    }

    /**
     * @return stable machine-readable error code returned by the remote peer
     */
    public String getCode() {
        return code;
    }
}
