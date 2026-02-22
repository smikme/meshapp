package com.meshtastic.client.protocol;

import org.meshtastic.proto.*;

public interface FromRadioListener {

    default void onMyNodeInfo(MeshProtos.MyNodeInfo myInfo) {}

    default void onNodeInfo(MeshProtos.NodeInfo nodeInfo) {}

    default void onConfig(ConfigProtos.Config config) {}

    default void onModuleConfig(ModuleConfigProtos.ModuleConfig moduleConfig) {}

    default void onChannel(ChannelProtos.Channel channel) {}

    default void onConfigComplete(int configCompleteId) {}

    default void onMeshPacket(MeshProtos.MeshPacket packet) {}

    default void onLogRecord(MeshProtos.LogRecord logRecord) {}

    default void onQueueStatus(MeshProtos.QueueStatus queueStatus) {}
}
