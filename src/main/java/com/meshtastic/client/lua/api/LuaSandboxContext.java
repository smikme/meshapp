package com.meshtastic.client.lua.api;

import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.lua.LuaAutomationCommand;
import com.meshtastic.client.lua.LuaCanvasBridge;
import com.meshtastic.client.lua.LuaFormBridge;
import com.meshtastic.client.lua.LuaNodeInfoBridge;
import com.meshtastic.client.lua.LuaRemoteAdminBridge;
import com.meshtastic.client.lua.LuaTimerBridge;
import com.meshtastic.client.lua.LuaTracerouteBridge;
import com.meshtastic.client.lua.LuaUiBridge;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocolRuntime;

import java.util.function.Consumer;

/**
 * Execution context for the Lua sandbox application API.
 * <p>
 * Exposes only the services and application state that approved {@code mesh.*}
 * extensions may use while a script is running.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaSandboxContext(long scriptId,
                                String connectionId,
                                DeviceState state,
                                ProtocolHandler handler,
                                MeshCoreCompanionProtocolRuntime meshCoreRuntime,
                                String ownerNodeId,
                                LuaScriptService scriptService,
                                Consumer<String> outputSink,
                                LuaAutomationCommand command,
                                LuaUiBridge uiBridge,
                                LuaTracerouteBridge tracerouteBridge,
                                LuaNodeInfoBridge nodeInfoBridge,
                                LuaRemoteAdminBridge remoteAdminBridge,
                                LuaTimerBridge timerBridge,
                                LuaCanvasBridge canvasBridge,
                                LuaFormBridge formBridge,
                                Runnable executionDeadlineDeferrer) {

    /**
     * Returns whether an active transport is available for sending messages.
     *
     * @return {@code true} when Meshtastic or MeshCore Companion can send
     */
    public boolean hasChatTransport() {
        return state != null && (handler != null || meshCoreRuntime != null);
    }

    /**
     * Returns the connection owner node id or an empty string.
     *
     * @return safe owner node id value
     */
    public String ownerNodeIdOrEmpty() {
        return ownerNodeId != null ? ownerNodeId : "";
    }

    /**
     * Sends a sandbox API output line to the script runtime.
     *
     * @param message output line
     */
    public void emitOutput(String message) {
        if (outputSink != null) {
            outputSink.accept(message);
        }
    }

    /**
     * Extends the execution budget after an allowed blocking API call.
     */
    public void deferExecutionDeadline() {
        if (executionDeadlineDeferrer != null) {
            executionDeadlineDeferrer.run();
        }
    }
}
