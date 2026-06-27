package com.meshtastic.client.lua;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import com.meshtastic.client.service.ConnectionManager;

/**
 * Factory for MeshApp IDE Lua script data sources.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaScriptDataSources {

    private LuaScriptDataSources() {
    }

    public static LuaScriptDataSource forCurrentConnection() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = manager.getSelectedConnectionEntry();
        if (entry != null && entry.isConnected()) {
            ProtocolRuntime<?> runtime = manager.getProtocolRuntime(entry.getId());
            if (runtime != null && runtime.getState() instanceof RemoteRpcState remoteState) {
                return new RemoteLuaScriptDataSource(remoteState);
            }
        }
        return new LocalLuaScriptDataSource();
    }
}
