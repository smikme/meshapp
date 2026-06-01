package com.meshtastic.client.protocol;

import org.meshtastic.proto.*;

/**
 * Listener for incoming {@code FromRadio} messages from a Meshtastic device.
 * <p>
 * Every method has an empty default implementation so callers can implement
 * only the callbacks they need. Methods are invoked after transport input has
 * been parsed into {@code FromRadio}.
 *
 * @see ProtocolHandler
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface FromRadioListener {

    /** Receives local node information such as node number and firmware version. */
    default void onMyNodeInfo(MeshProtos.MyNodeInfo myInfo) {}

    /** Receives information about a mesh node, including name, position, and metrics. */
    default void onNodeInfo(MeshProtos.NodeInfo nodeInfo) {}

    /** Receives a device configuration section such as Device, Position, or LoRa. */
    default void onConfig(ConfigProtos.Config config) {}

    /** Receives a module configuration section such as MQTT, Serial, or Telemetry. */
    default void onModuleConfig(ModuleConfigProtos.ModuleConfig moduleConfig) {}

    /** Receives a PRIMARY or SECONDARY channel. */
    default void onChannel(ChannelProtos.Channel channel) {}

    /** Receives config-exchange completion; {@code configCompleteId} should match the sent {@code want_config_id}. */
    default void onConfigComplete(int configCompleteId) {}

    /** Receives the radio reboot marker. */
    default void onRebooted() {}

    /** Receives a mesh packet such as text, telemetry, routing ACK, or similar payload. */
    default void onMeshPacket(MeshProtos.MeshPacket packet) {}

    /** Receives an MQTT proxy message to publish through the desktop bridge. */
    default void onMqttClientProxyMessage(MeshProtos.MqttClientProxyMessage proxyMessage) {}

    /** Receives one device log record. */
    default void onLogRecord(MeshProtos.LogRecord logRecord) {}

    /** Receives the device transmit queue status. */
    default void onQueueStatus(MeshProtos.QueueStatus queueStatus) {}
}
