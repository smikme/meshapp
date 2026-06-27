package com.meshtastic.client.protocol.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.RemoteAdminSession;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ConnStatusProtos;
import org.meshtastic.proto.DeviceUIProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

/**
 * JSON mapping helpers for Remote Admin RPC calls.
 */
public final class RemoteAdminRpcJson {

    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private RemoteAdminRpcJson() {
    }

    public static JsonObject sessionToJson(RemoteAdminSession session) {
        JsonObject object = new JsonObject();
        if (session == null) {
            return object;
        }
        DeviceState remoteState = session.remoteState();
        object.addProperty("targetNodeNum", session.targetNodeNum());
        object.add("node", RemoteNodeJson.nodeToJson(session.targetNode(), false, false));
        putBytes(object, "owner", remoteState.getOwnerInfo());
        putBytes(object, "deviceMetadata", remoteState.getDeviceMetadata());
        object.add("configs", configsToJson(remoteState.getConfigs()));
        object.add("moduleConfigs", moduleConfigsToJson(remoteState.getModuleConfigs()));
        object.add("channels", channelsToJson(remoteState.getChannels()));
        object.addProperty("channelCatalogReady", remoteState.isChannelCatalogReady());
        object.addProperty("ringtone", remoteState.getRingtone());
        object.addProperty("ringtoneLoaded", remoteState.isRingtoneLoaded());
        object.addProperty("cannedMessages", session.getCannedMessages());
        object.addProperty("cannedMessagesLoaded", session.isCannedMessagesLoaded());
        putBytes(object, "uiConfig", session.getUiConfig());
        putBytes(object, "connectionStatus", session.getConnectionStatus());

        JsonArray statuses = new JsonArray();
        for (RemoteAdminSession.QueryStatus status : session.queryStatuses()) {
            JsonObject statusObject = new JsonObject();
            statusObject.addProperty("key", status.key());
            statusObject.addProperty("state", status.state().name());
            statusObject.addProperty("detail", status.detail());
            statuses.add(statusObject);
        }
        object.add("queryStatuses", statuses);
        return object;
    }

    public static void applySnapshot(RemoteAdminSession session, JsonElement result, boolean clearFirst) {
        if (session == null) {
            return;
        }
        JsonObject object = objectOrEmpty(result);
        if (clearFirst) {
            session.clearSnapshot();
        }
        JsonObject nodeObject = objectField(object, "node");
        if (nodeObject != null) {
            copyNode(RemoteNodeJson.parseNode(nodeObject), session.targetNode());
        }

        DeviceState remoteState = session.remoteState();
        parseBytes(object, "owner", MeshProtos.User::parseFrom, session::applyOwner);
        parseBytes(object, "deviceMetadata", MeshProtos.DeviceMetadata::parseFrom, remoteState::setDeviceMetadata);
        for (ConfigProtos.Config config : configsFromJson(object, "configs")) {
            remoteState.addConfig(config);
        }
        for (ModuleConfigProtos.ModuleConfig moduleConfig : moduleConfigsFromJson(object, "moduleConfigs")) {
            remoteState.addModuleConfig(moduleConfig);
        }
        for (ChannelProtos.Channel channel : channelsFromJson(object, "channels")) {
            remoteState.updateChannel(channel);
        }
        if (object.has("channelCatalogReady")) {
            remoteState.setChannelCatalogReady(booleanField(object, "channelCatalogReady"));
        }
        if (booleanField(object, "ringtoneLoaded")) {
            remoteState.setRingtone(rawStringField(object, "ringtone"));
        }
        if (booleanField(object, "cannedMessagesLoaded")) {
            session.setCannedMessages(rawStringField(object, "cannedMessages"));
        }
        parseBytes(object, "uiConfig", DeviceUIProtos.DeviceUIConfig::parseFrom, session::setUiConfig);
        parseBytes(object, "connectionStatus", ConnStatusProtos.DeviceConnectionStatus::parseFrom,
                session::setConnectionStatus);
        applyQueryStatuses(session, object);
    }

    public static JsonArray configsToJson(List<ConfigProtos.Config> configs) {
        return messagesToJson(configs);
    }

    public static JsonArray moduleConfigsToJson(List<ModuleConfigProtos.ModuleConfig> moduleConfigs) {
        return messagesToJson(moduleConfigs);
    }

    public static JsonArray channelsToJson(List<ChannelProtos.Channel> channels) {
        return messagesToJson(channels);
    }

    public static List<ConfigProtos.Config> configsFromJson(JsonObject object, String field) {
        return messagesFromJson(object, field, ConfigProtos.Config::parseFrom);
    }

    public static List<ModuleConfigProtos.ModuleConfig> moduleConfigsFromJson(JsonObject object, String field) {
        return messagesFromJson(object, field, ModuleConfigProtos.ModuleConfig::parseFrom);
    }

    public static List<ChannelProtos.Channel> channelsFromJson(JsonObject object, String field) {
        return messagesFromJson(object, field, ChannelProtos.Channel::parseFrom);
    }

    public static String encodeAdminMessage(AdminProtos.AdminMessage adminMessage) {
        return adminMessage == null ? "" : ENCODER.encodeToString(adminMessage.toByteArray());
    }

    public static AdminProtos.AdminMessage adminMessageFromJson(JsonElement result) {
        JsonObject object = objectOrEmpty(result);
        String encoded = stringField(object, "adminMessage");
        if (encoded.isBlank()) {
            return AdminProtos.AdminMessage.getDefaultInstance();
        }
        try {
            return AdminProtos.AdminMessage.parseFrom(DECODER.decode(encoded));
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid RPC admin message payload", e);
        }
    }

    private static void applyQueryStatuses(RemoteAdminSession session, JsonObject object) {
        JsonElement statusesElement = object.get("queryStatuses");
        if (statusesElement == null || !statusesElement.isJsonArray()) {
            return;
        }
        for (JsonElement element : statusesElement.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject statusObject = element.getAsJsonObject();
            String key = stringField(statusObject, "key");
            String state = stringField(statusObject, "state");
            if (key.isBlank() || state.isBlank()) {
                continue;
            }
            try {
                switch (RemoteAdminSession.QueryState.valueOf(state)) {
                    case SENT -> session.markQuerySent(key);
                    case RECEIVED -> session.markQueryReceived(key);
                    case FAILED -> session.markQueryFailed(key, rawStringField(statusObject, "detail"));
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore statuses from newer hosts using an unknown state.
            }
        }
    }

    private static <T extends com.google.protobuf.MessageLite> JsonArray messagesToJson(List<T> messages) {
        JsonArray array = new JsonArray();
        if (messages == null) {
            return array;
        }
        for (T message : messages) {
            if (message != null) {
                array.add(ENCODER.encodeToString(message.toByteArray()));
            }
        }
        return array;
    }

    private static <T> List<T> messagesFromJson(JsonObject object, String field, Parser<T> parser) {
        JsonElement element = object != null ? object.get(field) : null;
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<T> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item == null || item.isJsonNull() || !item.isJsonPrimitive()) {
                continue;
            }
            try {
                values.add(parser.parse(DECODER.decode(item.getAsString())));
            } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid RPC protobuf payload in " + field, e);
            }
        }
        return values;
    }

    private static <T> void parseBytes(JsonObject object,
                                       String field,
                                       Parser<T> parser,
                                       java.util.function.Consumer<T> consumer) {
        String encoded = stringField(object, field);
        if (encoded.isBlank()) {
            return;
        }
        try {
            consumer.accept(parser.parse(DECODER.decode(encoded)));
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid RPC protobuf payload in " + field, e);
        }
    }

    private static void putBytes(JsonObject object, String field, com.google.protobuf.MessageLite message) {
        object.addProperty(field, message != null ? ENCODER.encodeToString(message.toByteArray()) : "");
    }

    private static JsonObject objectOrEmpty(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static JsonObject objectField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String stringField(JsonObject object, String field) {
        String value = rawStringField(object, field);
        return value == null ? "" : value.trim();
    }

    private static String rawStringField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString();
        return value == null ? "" : value;
    }

    private static boolean booleanField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                && element.getAsBoolean();
    }

    private static void copyNode(NodeData source, NodeData target) {
        if (source == null || target == null) {
            return;
        }
        target.setLongName(source.getLongName());
        target.setShortName(source.getShortName());
        target.setNodeId(source.getNodeId());
        target.setLatitude(source.getLatitude());
        target.setLongitude(source.getLongitude());
        target.setAltitude(source.getAltitude());
        target.setSnr(source.getSnr());
        target.setLastHeard(source.getLastHeard());
        target.setBatteryLevel(source.getBatteryLevel());
        target.setExternallyPowered(source.isExternallyPowered());
        target.setVoltage(source.getVoltage());
        target.setChannelUtilization(source.getChannelUtilization());
        target.setAirUtilTx(source.getAirUtilTx());
        target.setUptimeSeconds(source.getUptimeSeconds());
        target.setTemperature(source.getTemperature());
        target.setRelativeHumidity(source.getRelativeHumidity());
        target.setBarometricPressure(source.getBarometricPressure());
        if (source.hasHopsAway()) {
            target.setHopsAway(source.getHopsAway());
        } else {
            target.clearHopsAway();
        }
        target.setChannel(source.getChannel());
        target.setRole(source.getRole());
        target.setHwModel(source.getHwModel());
        target.setPublicKey(source.getPublicKey() != null ? source.getPublicKey().clone() : null);
        target.setUnmessagable(source.getUnmessagable());
        target.setLicensed(source.getLicensed());
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse(byte[] bytes) throws InvalidProtocolBufferException;
    }
}
