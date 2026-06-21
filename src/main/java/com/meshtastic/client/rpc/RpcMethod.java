package com.meshtastic.client.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletionStage;

/**
 * One whitelisted RPC method exposed by the MeshApp host.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@FunctionalInterface
public interface RpcMethod {

    /**
     * Executes the method.
     *
     * @param params request parameters, never {@code null}
     * @param context call metadata
     * @return future with a JSON-serializable result, or {@code null} for JSON null
     * @throws Exception when validation or local execution fails
     */
    CompletionStage<JsonElement> invoke(JsonObject params, RpcCallContext context) throws Exception;
}
