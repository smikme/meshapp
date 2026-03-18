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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Сервис отправки сообщений и admin-команд на Meshtastic-устройство.
 * <p>
 * Статический утилитный класс (без состояния). Формирует protobuf-пакеты
 * {@code ToRadio} для различных типов операций:
 * <ul>
 *   <li>Текстовые сообщения (канальные и личные)</li>
 *   <li>Запросы информации о нодах (NODEINFO_APP)</li>
 *   <li>Traceroute (TRACEROUTE_APP)</li>
 *   <li>Admin-операции: owner info, каналы, конфигурация</li>
 * </ul>
 */
public final class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private MessageService() {}

    /**
     * Отправляет текстовое сообщение в канал (broadcast, {@code to=0xFFFFFFFF}).
     * Создаёт {@link MeshMessage}, сохраняет в БД, добавляет в {@link DeviceState}
     * и регистрирует для отслеживания ACK.
     *
     * @param handler      протокол-обработчик для отправки
     * @param state        состояние устройства
     * @param channelIndex индекс канала (0 — Primary)
     * @param text         текст сообщения
     * @param replyId      packetId цитируемого сообщения (0 — без цитаты)
     * @return созданное сообщение в статусе {@code SENDING}
     */
    public static MeshMessage sendChannelMessage(ProtocolHandler handler, DeviceState state, int channelIndex, String text, int replyId) {
        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        long now = System.currentTimeMillis() / 1000;

        MeshProtos.Data.Builder dataBuilder = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                .setPayload(ByteString.copyFrom(text, StandardCharsets.UTF_8));
        if (replyId != 0) { dataBuilder.setReplyId(replyId); }
        MeshProtos.Data data = dataBuilder.build();

        if (replyId != 0) {
            byte[] dataBytes = data.toByteArray();
            log.info("REPLY_DEBUG send channel: replyId={} (0x{}), data bytes={}",
                    replyId, Integer.toHexString(replyId), bytesToHex(dataBytes));
        }

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

        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        String myNodeId = myNode != null ? myNode.getNodeId() : null;

        MeshMessage msg = new MeshMessage(myNodeId, "!ffffffff", channelIndex, text, now, true);
        msg.setStatus(MeshMessage.DeliveryStatus.SENDING);
        msg.setPacketId(packetId);
        if (replyId != 0) {
            msg.setReplyId(replyId);
            MeshMessage original = state.findMessageByPacketId(replyId);
            if (original != null) { msg.setReplyText(original.getText()); }
        }

        if (myNode != null && myNode.getLongName() != null) {
            msg.setSenderName(myNode.getLongName());
        }

        String ownerNodeId = String.format("!%08x", state.getMyNodeNum());
        MessageDbService.getInstance().save(msg, "channel", String.valueOf(channelIndex), ownerNodeId);
        state.addMessage(msg);
        state.registerPendingAck(packetId, msg);
        return msg;
    }

    /**
     * Отправляет личное текстовое сообщение (DM) конкретной ноде.
     * Создаёт {@link MeshMessage}, сохраняет в БД, добавляет в {@link DeviceState}
     * и регистрирует для отслеживания ACK.
     *
     * @param handler      протокол-обработчик для отправки
     * @param state        состояние устройства
     * @param peerNodeId   node_id получателя (например {@code "!9e755af0"})
     * @param text         текст сообщения
     * @param replyId      packetId цитируемого сообщения (0 — без цитаты)
     * @return созданное сообщение в статусе {@code SENDING}
     */
    public static MeshMessage sendDirectMessage(ProtocolHandler handler, DeviceState state, String peerNodeId, String text, int replyId) {
        int packetId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        long now = System.currentTimeMillis() / 1000;

        // Для протокола нужен int nodeNum — получаем через lookup NodeData
        NodeData peerNode = state.getNodeByNodeId(peerNodeId);
        int peerNodeNum = peerNode != null ? peerNode.getNodeNum() : 0;

        MeshProtos.Data.Builder dataBuilder = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                .setPayload(ByteString.copyFrom(text, StandardCharsets.UTF_8));
        if (replyId != 0) { dataBuilder.setReplyId(replyId); }
        MeshProtos.Data data = dataBuilder.build();

        if (replyId != 0) {
            byte[] dataBytes = data.toByteArray();
            log.info("REPLY_DEBUG send DM: replyId={} (0x{}), data bytes={}",
                    replyId, Integer.toHexString(replyId), bytesToHex(dataBytes));
        }

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

        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        String myNodeId = myNode != null ? myNode.getNodeId() : null;

        MeshMessage msg = new MeshMessage(myNodeId, peerNodeId, 0, text, now, true);
        msg.setStatus(MeshMessage.DeliveryStatus.SENDING);
        msg.setPacketId(packetId);
        if (replyId != 0) {
            msg.setReplyId(replyId);
            MeshMessage original = state.findMessageByPacketId(replyId);
            if (original != null) { msg.setReplyText(original.getText()); }
        }

        if (myNode != null && myNode.getLongName() != null) {
            msg.setSenderName(myNode.getLongName());
        }

        String ownerNodeId = String.format("!%08x", state.getMyNodeNum());
        MessageDbService.getInstance().save(msg, "dm", peerNodeId, ownerNodeId);
        state.addDirectMessage(msg, peerNodeId);
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
     * Устанавливает фиксированную позицию на устройстве.
     * Отправляет AdminMessage.set_fixed_position с координатами.
     * Прошивка автоматически установит position.fixed_position = true.
     *
     * @param latDegrees широта в градусах (-90..90)
     * @param lonDegrees долгота в градусах (-180..180)
     * @param altMeters  высота в метрах над уровнем моря
     */
    public static void setFixedPosition(ProtocolHandler handler, DeviceState state,
                                         double latDegrees, double lonDegrees, int altMeters) {
        int latI = (int) Math.round(latDegrees * 1e7);
        int lonI = (int) Math.round(lonDegrees * 1e7);
        log.info("setFixedPosition: lat={}→latI={}, lon={}→lonI={}, alt={}",
                latDegrees, latI, lonDegrees, lonI, altMeters);

        MeshProtos.Position position = MeshProtos.Position.newBuilder()
                .setLatitudeI(latI)
                .setLongitudeI(lonI)
                .setAltitude(altMeters)
                .setTime((int) (System.currentTimeMillis() / 1000))
                .setLocationSource(MeshProtos.Position.LocSource.LOC_MANUAL)
                .setAltitudeSource(MeshProtos.Position.AltSource.ALT_MANUAL)
                .build();

        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetFixedPosition(position);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Удаляет фиксированную позицию с устройства.
     * Отправляет AdminMessage.remove_fixed_position = true.
     * Прошивка автоматически установит position.fixed_position = false.
     */
    public static void removeFixedPosition(ProtocolHandler handler, DeviceState state) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setRemoveFixedPosition(true);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }

        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Отправляет AdminMessage.set_favorite_node на устройство.
     */
    public static void setFavoriteNode(ProtocolHandler handler, DeviceState state, int nodeNum) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetFavoriteNode(nodeNum);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }
        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Отправляет AdminMessage.remove_favorite_node на устройство.
     */
    public static void removeFavoriteNode(ProtocolHandler handler, DeviceState state, int nodeNum) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setRemoveFavoriteNode(nodeNum);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }
        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Отправляет AdminMessage.set_ignored_node на устройство.
     */
    public static void setIgnoredNode(ProtocolHandler handler, DeviceState state, int nodeNum) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setSetIgnoredNode(nodeNum);
        ByteString passkey = state.getSessionPasskey();
        if (passkey != null) {
            adminBuilder.setSessionPasskey(passkey);
        }
        sendAdminMessage(handler, state, adminBuilder.build());
    }

    /**
     * Отправляет AdminMessage.remove_ignored_node на устройство.
     */
    public static void removeIgnoredNode(ProtocolHandler handler, DeviceState state, int nodeNum) {
        AdminProtos.AdminMessage.Builder adminBuilder = AdminProtos.AdminMessage.newBuilder()
                .setRemoveIgnoredNode(nodeNum);
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

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) { sb.append(String.format("%02x", b)); }
        return sb.toString();
    }
}
