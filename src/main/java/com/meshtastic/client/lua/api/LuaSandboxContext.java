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
import java.util.function.Supplier;

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
                                Runnable executionDeadlineDeferrer,
                                Supplier<ConnectionSnapshot> targetResolver) {

    public LuaSandboxContext(long scriptId,
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
        this(scriptId,
                connectionId,
                state,
                handler,
                meshCoreRuntime,
                ownerNodeId,
                scriptService,
                outputSink,
                command,
                uiBridge,
                tracerouteBridge,
                nodeInfoBridge,
                remoteAdminBridge,
                timerBridge,
                canvasBridge,
                formBridge,
                executionDeadlineDeferrer,
                null);
    }

    /**
     * Returns whether an active transport is available for sending messages.
     *
     * @return {@code true} when Meshtastic or MeshCore Companion can send
     */
    public boolean hasChatTransport() {
        ConnectionSnapshot target = currentTarget();
        return target.state() != null && (target.handler() != null || target.meshCoreRuntime() != null);
    }

    /**
     * Returns the connection owner node id or an empty string.
     *
     * @return safe owner node id value
     */
    public String ownerNodeIdOrEmpty() {
        String currentOwnerNodeId = currentOwnerNodeId();
        return currentOwnerNodeId != null ? currentOwnerNodeId : "";
    }

    /**
     * Returns the current active target for APIs that can outlive connection changes.
     *
     * @return live connection snapshot when available, otherwise the startup snapshot
     */
    public ConnectionSnapshot currentTarget() {
        ConnectionSnapshot current = targetResolver != null ? targetResolver.get() : null;
        return current != null ? current : startupTarget();
    }

    /**
     * Returns the current connection id.
     *
     * @return connection id, or {@code null} when no active connection is selected
     */
    public String currentConnectionId() {
        return currentTarget().connectionId();
    }

    /**
     * Returns the current UI-compatible device state.
     *
     * @return current state, or {@code null} without an active connection
     */
    public DeviceState currentState() {
        return currentTarget().state();
    }

    /**
     * Returns the current Meshtastic protocol handler.
     *
     * @return current handler, or {@code null} for non-Meshtastic/no connection
     */
    public ProtocolHandler currentHandler() {
        return currentTarget().handler();
    }

    /**
     * Returns the current MeshCore Companion runtime.
     *
     * @return current runtime, or {@code null} for other protocols/no connection
     */
    public MeshCoreCompanionProtocolRuntime currentMeshCoreRuntime() {
        return currentTarget().meshCoreRuntime();
    }

    /**
     * Returns the current owner node id.
     *
     * @return owner node id, or {@code null} when unknown
     */
    public String currentOwnerNodeId() {
        return currentTarget().ownerNodeId();
    }

    private ConnectionSnapshot startupTarget() {
        return new ConnectionSnapshot(connectionId, state, handler, meshCoreRuntime, ownerNodeId);
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

    public record ConnectionSnapshot(String connectionId,
                                     DeviceState state,
                                     ProtocolHandler handler,
                                     MeshCoreCompanionProtocolRuntime meshCoreRuntime,
                                     String ownerNodeId) {}
}
