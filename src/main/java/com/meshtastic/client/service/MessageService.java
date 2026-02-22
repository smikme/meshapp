package com.meshtastic.client.service;

import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.meshtastic.proto.Portnums;
import com.google.protobuf.ByteString;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.MessageDbService;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

public class MessageService {

    private MessageService() {}

    public static MeshMessage sendChannelMessage(ProtocolHandler handler, DeviceState state, int channelIndex, String text, int replyId) {
        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        long now = System.currentTimeMillis() / 1000;

        MeshProtos.Data.Builder dataBuilder = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                .setPayload(ByteString.copyFrom(text, StandardCharsets.UTF_8));
        if (replyId != 0) dataBuilder.setReplyId(replyId);
        MeshProtos.Data data = dataBuilder.build();

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(0xFFFFFFFF)
                .setChannel(channelIndex)
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .build();

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(packet)
                .build();

        handler.sendToRadio(toRadio);

        MeshMessage msg = new MeshMessage(state.getMyNodeNum(), 0xFFFFFFFF, channelIndex, text, now, true);
        msg.setStatus(MeshMessage.DeliveryStatus.SENDING);
        msg.setPacketId(packetId);
        if (replyId != 0) {
            msg.setReplyId(replyId);
            MeshMessage original = state.findMessageByPacketId(replyId);
            if (original != null) msg.setReplyText(original.getText());
        }

        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        if (myNode != null && myNode.getLongName() != null) {
            msg.setSenderName(myNode.getLongName());
        }

        MessageDbService.getInstance().save(msg, "channel", channelIndex);
        state.addMessage(msg);
        state.registerPendingAck(packetId, msg);
        return msg;
    }

    public static MeshMessage sendDirectMessage(ProtocolHandler handler, DeviceState state, int peerNodeNum, String text, int replyId) {
        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        long now = System.currentTimeMillis() / 1000;

        MeshProtos.Data.Builder dataBuilder = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                .setPayload(ByteString.copyFrom(text, StandardCharsets.UTF_8));
        if (replyId != 0) dataBuilder.setReplyId(replyId);
        MeshProtos.Data data = dataBuilder.build();

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(peerNodeNum)
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .build();

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(packet)
                .build();

        handler.sendToRadio(toRadio);

        MeshMessage msg = new MeshMessage(state.getMyNodeNum(), peerNodeNum, 0, text, now, true);
        msg.setStatus(MeshMessage.DeliveryStatus.SENDING);
        msg.setPacketId(packetId);
        if (replyId != 0) {
            msg.setReplyId(replyId);
            MeshMessage original = state.findMessageByPacketId(replyId);
            if (original != null) msg.setReplyText(original.getText());
        }

        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        if (myNode != null && myNode.getLongName() != null) {
            msg.setSenderName(myNode.getLongName());
        }

        MessageDbService.getInstance().save(msg, "dm", peerNodeNum);
        state.addDirectMessage(msg, peerNodeNum);
        state.registerPendingAck(packetId, msg);
        return msg;
    }

    /**
     * Запрашивает информацию о ноде, отправляя NODEINFO_APP пакет с want_response=true.
     * Удалённая нода ответит пакетом NODEINFO_APP с данными User.
     */
    public static void requestNodeInfo(ProtocolHandler handler, DeviceState state, int targetNodeNum) {
        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.NODEINFO_APP)
                .setWantResponse(true)
                .build();

        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(targetNodeNum)
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .build();

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(packet)
                .build();

        handler.sendToRadio(toRadio);
    }

    /**
     * Запрашивает трассировку маршрута до ноды, отправляя TRACEROUTE_APP пакет с want_response=true.
     * Ответ содержит RouteDiscovery с маршрутом и SNR на каждом хопе.
     */
    public static void requestTraceroute(ProtocolHandler handler, DeviceState state, int targetNodeNum) {
        MeshProtos.RouteDiscovery emptyRoute = MeshProtos.RouteDiscovery.newBuilder().build();

        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.TRACEROUTE_APP)
                .setWantResponse(true)
                .setDest(targetNodeNum)
                .setPayload(emptyRoute.toByteString())
                .build();

        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(targetNodeNum)
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .build();

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(packet)
                .build();

        handler.sendToRadio(toRadio);
    }

    /**
     * Запрашивает owner info (User) и session_passkey у подключённого радио.
     * Ответ придёт как AdminMessage.get_owner_response через ADMIN_APP.
     */
    public static void requestOwnerInfo(ProtocolHandler handler, DeviceState state) {
        AdminProtos.AdminMessage adminMsg = AdminProtos.AdminMessage.newBuilder()
                .setGetOwnerRequest(true)
                .build();

        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.ADMIN_APP)
                .setPayload(adminMsg.toByteString())
                .setWantResponse(true)
                .build();

        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(state.getMyNodeNum())
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .build();

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(packet)
                .build();

        handler.sendToRadio(toRadio);
    }

    /**
     * Устанавливает owner info (longName, shortName) на подключённом радио.
     * Требует session_passkey, полученный из предварительного get_owner_request.
     */
    public static void setOwnerInfo(ProtocolHandler handler, DeviceState state,
                                    String longName, String shortName, ByteString sessionPasskey) {
        MeshProtos.User user = MeshProtos.User.newBuilder()
                .setLongName(longName)
                .setShortName(shortName)
                .build();

        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetOwner(user);
        if (sessionPasskey != null) {
            adminBuilder.setSessionPasskey(sessionPasskey);
        }
        AdminProtos.AdminMessage adminMsg = adminBuilder.build();

        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.ADMIN_APP)
                .setPayload(adminMsg.toByteString())
                .build();

        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(state.getMyNodeNum())
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .build();

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(packet)
                .build();

        handler.sendToRadio(toRadio);
    }

    /**
     * Создаёт/обновляет канал на подключённом радио через AdminMessage.set_channel.
     * Требует session_passkey для защищённых устройств (может быть null для локальных).
     */
    public static void setChannel(ProtocolHandler handler, DeviceState state,
                                   ChannelProtos.Channel channel, ByteString sessionPasskey) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetChannel(channel);
        if (sessionPasskey != null) {
            adminBuilder.setSessionPasskey(sessionPasskey);
        }
        AdminProtos.AdminMessage adminMsg = adminBuilder.build();

        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.ADMIN_APP)
                .setPayload(adminMsg.toByteString())
                .build();

        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(state.getMyNodeNum())
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .build();

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(packet)
                .build();

        handler.sendToRadio(toRadio);
    }

    // ==================== Config Admin Methods ====================

    /**
     * Отправляет begin_edit_settings для начала транзакции изменения настроек.
     * Предотвращает перезагрузку устройства между отдельными set_config/set_module_config.
     */
    public static void beginEditSettings(ProtocolHandler handler, DeviceState state) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setBeginEditSettings(true);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Отправляет commit_edit_settings для завершения транзакции настроек.
     * Устройство применит все изменения и перезагрузится.
     */
    public static void commitEditSettings(ProtocolHandler handler, DeviceState state) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setCommitEditSettings(true);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Отправляет set_config (одна секция Config) на устройство.
     */
    public static void setConfig(ProtocolHandler handler, DeviceState state, ConfigProtos.Config config) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetConfig(config);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Отправляет set_module_config (одна секция ModuleConfig) на устройство.
     */
    public static void setModuleConfig(ProtocolHandler handler, DeviceState state,
                                        ModuleConfigProtos.ModuleConfig moduleConfig) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetModuleConfig(moduleConfig);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Вспомогательный метод для отправки AdminMessage на локальное устройство.
     */
    private static void sendAdminMessage(ProtocolHandler handler, DeviceState state,
                                          AdminProtos.AdminMessage adminMsg) {
        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.ADMIN_APP)
                .setPayload(adminMsg.toByteString())
                .build();

        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(state.getMyNodeNum())
                .setDecoded(data)
                .setId(packetId)
                .setWantAck(true)
                .build();

        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setPacket(packet)
                .build();

        handler.sendToRadio(toRadio);
    }
}
