package com.meshtastic.client.protocol.rpc;

import com.google.gson.JsonObject;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.RemoteAdminSession;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteAdminRpcJsonTest {

    @Test
    void roundTripsRemoteAdminSessionSnapshot() {
        NodeData node = remoteNode();
        RemoteAdminSession source = new RemoteAdminSession(node.getNodeNum(), node);
        source.applyOwner(MeshProtos.User.newBuilder()
                .setId("!22222222")
                .setLongName("Remote Admin Node")
                .setShortName("RAN")
                .setIsLicensed(true)
                .build());
        source.remoteState().addConfig(ConfigProtos.Config.getDefaultInstance());
        source.remoteState().addModuleConfig(ModuleConfigProtos.ModuleConfig.getDefaultInstance());
        source.remoteState().addChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(3)
                .setRole(ChannelProtos.Channel.Role.SECONDARY)
                .build());
        source.remoteState().setChannelCatalogReady(true);
        source.remoteState().setRingtone(" ringtone:d=4,o=5,b=120:c ");
        source.setCannedMessages(" one|two ");
        source.markQueryReceived("get_owner");
        source.markQueryFailed("get_channel/1", "timeout");

        JsonObject json = RemoteAdminRpcJson.sessionToJson(source);
        RemoteAdminSession target = new RemoteAdminSession(node.getNodeNum(), new NodeData(node.getNodeNum()));
        RemoteAdminRpcJson.applySnapshot(target, json, true);

        assertEquals("Remote Admin Node", target.targetNode().getLongName());
        assertEquals("RAN", target.targetNode().getShortName());
        assertTrue(target.targetNode().isLicensed());
        assertEquals(1, target.remoteState().getConfigs().size());
        assertEquals(1, target.remoteState().getModuleConfigs().size());
        assertEquals(1, target.remoteState().getChannels().size());
        assertTrue(target.remoteState().isChannelCatalogReady());
        assertEquals(" ringtone:d=4,o=5,b=120:c ", target.remoteState().getRingtone());
        assertEquals(" one|two ", target.getCannedMessages());
        assertEquals(2, target.queryStatuses().size());
    }

    @Test
    void roundTripsAdminMessagePayload() {
        AdminProtos.AdminMessage message = AdminProtos.AdminMessage.newBuilder()
                .setGetDeviceConnectionStatusRequest(true)
                .build();
        JsonObject json = new JsonObject();
        json.addProperty("adminMessage", RemoteAdminRpcJson.encodeAdminMessage(message));

        assertEquals(message, RemoteAdminRpcJson.adminMessageFromJson(json));
    }

    private static NodeData remoteNode() {
        NodeData node = new NodeData(0x22222222);
        node.setNodeId("!22222222");
        node.setLongName("Remote");
        node.setShortName("R");
        node.setPublicKey(new byte[] {1, 2, 3});
        return node;
    }
}
