package com.meshtastic.client.components.chat;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;

/**
 * Resolves node and sender names for chat UI.
 *
 * <p>The instance keeps a {@link DeviceState} reference that is replaced after
 * reconnect through {@link #setState(DeviceState)}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ChatNameResolver {

    private DeviceState state;

    public ChatNameResolver(DeviceState state) {
        this.state = state;
    }

    /** Updates DeviceState after reconnect. */
    public void setState(DeviceState state) {
        this.state = state;
    }

    /**
     * Resolves a node name by numeric id, using longName or {@code !hex} fallback.
     *
     * @param nodeNum numeric node id
     * @return display name
     */
    public String resolveNodeName(int nodeNum) {
        return ChatNodeDisplayHelper.resolveNodeName(state, nodeNum);
    }

    /**
     * Resolves the sender name shown in a reply quote.
     *
     * @param msg message
     * @return local-user label for outgoing messages, or longName/senderName/nodeId for incoming messages
     */
    public String resolveSenderName(MeshMessage msg) {
        return ChatNodeDisplayHelper.resolveReplySenderName(state, msg);
    }
}
