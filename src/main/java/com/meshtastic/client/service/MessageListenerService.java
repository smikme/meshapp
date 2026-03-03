package com.meshtastic.client.service;

import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import com.google.protobuf.InvalidProtocolBufferException;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.protocol.FromRadioListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Сервис обработки входящих mesh-пакетов ({@code MeshPacket}).
 * <p>
 * Реализует {@link FromRadioListener#onMeshPacket} и распределяет пакеты
 * по типу portnum:
 * <ul>
 *   <li>{@code TEXT_MESSAGE_APP} — текстовые сообщения (канальные и DM)</li>
 *   <li>{@code ROUTING_APP} — ACK/NAK для отправленных сообщений</li>
 *   <li>{@code NODEINFO_APP} — обновление информации о ноде</li>
 *   <li>{@code POSITION_APP} — обновление координат ноды</li>
 *   <li>{@code TELEMETRY_APP} — метрики устройства и окружения</li>
 *   <li>{@code TRACEROUTE_APP} — ответ на traceroute</li>
 *   <li>{@code ADMIN_APP} — admin-ответы (owner info и др.)</li>
 * </ul>
 * Обновляет {@link com.meshtastic.client.model.DeviceState}, сохраняет сообщения
 * в БД через {@link MessageDbService}, синхронизирует кэш нод через {@link NodeCacheService}.
 */
public class MessageListenerService implements FromRadioListener {

    private static final Logger log = LoggerFactory.getLogger(MessageListenerService.class);

    private final DeviceState deviceState;

    public MessageListenerService(DeviceState deviceState) {
        this.deviceState = deviceState;
    }

    @Override
    public void onMeshPacket(MeshProtos.MeshPacket packet) {
        if (!packet.hasDecoded()) { return; }

        MeshProtos.Data data = packet.getDecoded();

        if (data.getPortnum() == Portnums.PortNum.TEXT_MESSAGE_APP) {
            handleTextMessage(packet, data);
        } else if (data.getPortnum() == Portnums.PortNum.ROUTING_APP) {
            handleRoutingAck(packet, data);
        } else if (data.getPortnum() == Portnums.PortNum.NODEINFO_APP) {
            handleNodeInfoResponse(packet, data);
        } else if (data.getPortnum() == Portnums.PortNum.POSITION_APP) {
            handlePositionResponse(packet, data);
        } else if (data.getPortnum() == Portnums.PortNum.TELEMETRY_APP) {
            handleTelemetryResponse(packet, data);
        } else if (data.getPortnum() == Portnums.PortNum.TRACEROUTE_APP) {
            handleTracerouteResponse(packet, data);
        } else if (data.getPortnum() == Portnums.PortNum.ADMIN_APP) {
            handleAdminResponse(packet, data);
        }
    }

    private void handleTextMessage(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        int from = packet.getFrom();
        int to = packet.getTo();
        int channel = packet.getChannel();
        String text = data.getPayload().toString(StandardCharsets.UTF_8);
        long timestamp = packet.getRxTime() > 0 ? packet.getRxTime() : System.currentTimeMillis() / 1000;
        boolean outgoing = from == deviceState.getMyNodeNum();

        if (outgoing) { return; } // outgoing messages are already added by MessageService

        // Lookup nodeId через NodeData (не математическая конвертация)
        NodeData fromNode = deviceState.getOrCreateNode(from);
        String fromNodeId = fromNode.getNodeId();
        String toNodeId = (to == 0xFFFFFFFF) ? "!ffffffff" : deviceState.getOrCreateNode(to).getNodeId();

        MeshMessage msg = new MeshMessage(fromNodeId, toNodeId, channel, text, timestamp, false);
        msg.setPacketId(packet.getId());
        msg.setHopStart(packet.getHopStart());
        msg.setHopLimit(packet.getHopLimit());
        msg.setRxRssi(packet.getRxRssi());
        msg.setRxSnr(packet.getRxSnr());

        if (data.getReplyId() != 0) {
            log.info("REPLY_DEBUG recv: reply_id={} (0x{}) from {}",
                    data.getReplyId(), Integer.toHexString(data.getReplyId()), fromNodeId);
            msg.setReplyId(data.getReplyId());
            MeshMessage original = deviceState.findMessageByPacketId(data.getReplyId());
            if (original != null) { msg.setReplyText(original.getText()); }
        }

        if (fromNode.getLongName() != null) {
            msg.setSenderName(fromNode.getLongName());
        }

        boolean isDirect = to != 0xFFFFFFFF;
        if (isDirect) {
            msg.setStatus(MeshMessage.DeliveryStatus.DELIVERED);
            MessageDbService.getInstance().save(msg, "dm", fromNodeId);
            deviceState.addDirectMessage(msg, fromNodeId);
            log.info("Received DM from {}: {}", fromNodeId, text);
        } else {
            msg.setStatus(MeshMessage.DeliveryStatus.DELIVERED);
            MessageDbService.getInstance().save(msg, "channel", String.valueOf(channel));
            deviceState.addMessage(msg);
            log.info("Received channel {} message from {}: {}", channel, fromNodeId, text);
        }
    }

    private void handleRoutingAck(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        int requestId = data.getRequestId();
        if (requestId == 0) {
            log.debug("Ignoring routing packet with requestId=0 from !{}",
                    Integer.toHexString(packet.getFrom()));
            return;
        }

        MeshMessage pending = deviceState.resolvePendingAck(requestId);
        if (pending == null) {
            log.debug("No pending message found for ACK requestId={}", requestId);
            return;
        }

        try {
            MeshProtos.Routing routing = MeshProtos.Routing.parseFrom(data.getPayload());
            if (routing.getErrorReason() == MeshProtos.Routing.Error.NONE) {
                pending.setStatus(MeshMessage.DeliveryStatus.DELIVERED);
                MessageDbService.getInstance().updateStatus(requestId, pending.getStatus(), null);
                deviceState.fireMessageListeners();
                log.debug("ACK received for packet {}", requestId);
            } else {
                pending.setStatus(MeshMessage.DeliveryStatus.FAILED);
                pending.setErrorReason(routing.getErrorReason().name());
                MessageDbService.getInstance().updateStatus(requestId, pending.getStatus(), pending.getErrorReason());
                deviceState.fireMessageListeners();
                log.warn("NAK received for packet {}: {}", requestId, routing.getErrorReason());
            }
        } catch (Exception e) {
            log.warn("Failed to parse routing ACK for packet {}", requestId, e);
        }
    }

    private void handleNodeInfoResponse(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        int fromNum = packet.getFrom();
        try {
            MeshProtos.User user = MeshProtos.User.parseFrom(data.getPayload());
            NodeData node = deviceState.getOrCreateNode(fromNum);
            int rxTime = packet.getRxTime() > 0 ? packet.getRxTime() : (int)(System.currentTimeMillis() / 1000);
            node.setLastHeard(rxTime);
            // Protobuf возвращает "" для незаполненных строковых полей —
            // пустые значения не должны затирать существующие данные
            if (!user.getLongName().isEmpty()) { node.setLongName(user.getLongName()); }
            if (!user.getShortName().isEmpty()) { node.setShortName(user.getShortName()); }
            if (!user.getId().isEmpty()) { node.setNodeId(user.getId()); }
            if (user.getRole() != ConfigProtos.Config.DeviceConfig.Role.CLIENT || node.getRole() == null) {
                node.setRole(user.getRole().name());
            }
            if (user.getHwModel() != MeshProtos.HardwareModel.UNSET || node.getHwModel() == null) {
                node.setHwModel(user.getHwModel().name());
            }
            if (!user.getPublicKey().isEmpty()) {
                node.setPublicKey(user.getPublicKey().toByteArray());
            }
            deviceState.fireNodeUpdateListeners(fromNum);
            NodeCacheService.getInstance().update(node);
            log.info("Received NODEINFO_APP from !{}: {}", Integer.toHexString(fromNum), user.getLongName());
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse User from NODEINFO_APP packet from !{}", Integer.toHexString(fromNum), e);
        }
    }

    private void handlePositionResponse(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        int fromNum = packet.getFrom();
        try {
            MeshProtos.Position position = MeshProtos.Position.parseFrom(data.getPayload());
            NodeData node = deviceState.getOrCreateNode(fromNum);
            if (!node.hasName()) {
                NodeCacheService.getInstance().enrichFromCache(node);
            }
            int rxTime = packet.getRxTime() > 0 ? packet.getRxTime() : (int)(System.currentTimeMillis() / 1000);
            node.setLastHeard(rxTime);
            // Нулевые координаты означают отсутствие данных — не затираем существующие
            if (position.getLatitudeI() != 0) { node.setLatitude(position.getLatitudeI() * 1e-7); }

            if (position.getLongitudeI() != 0) { node.setLongitude(position.getLongitudeI() * 1e-7); }

            if (position.getAltitude() != 0) { node.setAltitude(position.getAltitude()); }

            deviceState.fireNodeUpdateListeners(fromNum);
            NodeCacheService.getInstance().update(node);
            log.info("Received POSITION_APP from !{}", Integer.toHexString(fromNum));
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse Position from POSITION_APP packet from !{}", Integer.toHexString(fromNum), e);
        }
    }

    private void handleTelemetryResponse(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        int fromNum = packet.getFrom();
        try {
            org.meshtastic.proto.TelemetryProtos.Telemetry telemetry =
                    org.meshtastic.proto.TelemetryProtos.Telemetry.parseFrom(data.getPayload());

            long ts = telemetry.getTime() > 0 ? telemetry.getTime()
                    : packet.getRxTime() > 0 ? packet.getRxTime() : System.currentTimeMillis() / 1000;

            NodeData node = deviceState.getOrCreateNode(fromNum);
            TelemetryEntry entry = new TelemetryEntry(ts, node.getNodeId());
            if (!node.hasName()) {
                NodeCacheService.getInstance().enrichFromCache(node);
            }
            node.setLastHeard((int) ts);

            if (telemetry.hasDeviceMetrics()) {
                org.meshtastic.proto.TelemetryProtos.DeviceMetrics dm = telemetry.getDeviceMetrics();
                node.setBatteryLevel(dm.getBatteryLevel());
                node.setVoltage(dm.getVoltage());
                node.setChannelUtilization(dm.getChannelUtilization());
                node.setAirUtilTx(dm.getAirUtilTx());
                node.setUptimeSeconds(dm.getUptimeSeconds());

                entry.setBatteryLevel(dm.getBatteryLevel());
                entry.setVoltage(dm.getVoltage());
                entry.setChannelUtilization(dm.getChannelUtilization());
                entry.setAirUtilTx(dm.getAirUtilTx());

                deviceState.fireNodeUpdateListeners(fromNum);
                NodeCacheService.getInstance().update(node);
                log.info("Received TELEMETRY_APP (device) from !{}", Integer.toHexString(fromNum));
            }

            if (telemetry.hasEnvironmentMetrics()) {
                org.meshtastic.proto.TelemetryProtos.EnvironmentMetrics em = telemetry.getEnvironmentMetrics();
                if (em.getTemperature() != 0) { node.setTemperature(em.getTemperature()); }

                if (em.getRelativeHumidity() != 0) { node.setRelativeHumidity(em.getRelativeHumidity()); }

                if (em.getBarometricPressure() != 0) { node.setBarometricPressure(em.getBarometricPressure()); }


                entry.setTemperature(em.getTemperature());
                entry.setRelativeHumidity(em.getRelativeHumidity());
                entry.setBarometricPressure(em.getBarometricPressure());

                deviceState.fireNodeUpdateListeners(fromNum);
                NodeCacheService.getInstance().update(node);
                log.info("Received TELEMETRY_APP (environment) from !{}", Integer.toHexString(fromNum));
            }

            deviceState.addTelemetryEntry(entry);
            NodeCacheService.getInstance().persistTelemetry(entry);
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse Telemetry from TELEMETRY_APP packet from !{}", Integer.toHexString(fromNum), e);
        }
    }

    @SuppressWarnings("PMD.UnusedFormalParameter") // consistent handler signature
    private void handleAdminResponse(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        try {
            AdminProtos.AdminMessage adminMsg = AdminProtos.AdminMessage.parseFrom(data.getPayload());

            if (adminMsg.hasGetOwnerResponse()) {
                MeshProtos.User owner = adminMsg.getGetOwnerResponse();
                deviceState.setOwnerInfo(owner);
                if (!adminMsg.getSessionPasskey().isEmpty()) {
                    deviceState.setSessionPasskey(adminMsg.getSessionPasskey());
                }
                deviceState.fireOwnerInfoListeners();
                log.info("Received owner info: longName='{}', shortName='{}'",
                        owner.getLongName(), owner.getShortName());
            } else {
                log.debug("Received ADMIN_APP response: {}", adminMsg.getPayloadVariantCase());
            }
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse AdminMessage from ADMIN_APP packet", e);
        }
    }

    private void handleTracerouteResponse(MeshProtos.MeshPacket packet, MeshProtos.Data data) {
        // Игнорируем исходящий запрос (эхо от радио) — обрабатываем только ответ
        int myNodeNum = deviceState.getMyNodeNum();
        if (packet.getFrom() == myNodeNum) {
            log.debug("Ignoring outgoing TRACEROUTE_APP echo from self");
            return;
        }
        if (data.getRequestId() == 0) {
            log.debug("Ignoring TRACEROUTE_APP packet without requestId (not a response)");
            return;
        }

        try {
            MeshProtos.RouteDiscovery route = MeshProtos.RouteDiscovery.parseFrom(data.getPayload());
            deviceState.fireTracerouteListeners(packet.getFrom(), route);
            log.info("Received TRACEROUTE_APP response from !{}: route={}, route_back={}",
                    Integer.toHexString(packet.getFrom()), route.getRouteList(), route.getRouteBackList());
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse RouteDiscovery from TRACEROUTE_APP packet", e);
        }
    }
}
