package com.meshtastic.client.protocol.meshcore;

import com.google.gson.*;

import java.util.*;
import java.util.concurrent.*;

/** MeshCore API for authenticated host RPC clients and the local diagnostic console.
 * State snapshots deliberately exclude keys, PINs and passwords; export requires a named operation. */
public final class MeshCoreRpc {
    private MeshCoreRpc() {}

    public static JsonObject status(MeshCoreCompanionProtocolRuntime runtime) {
        var s = runtime.getState();
        JsonObject result = new JsonObject();
        result.add("capabilities", new Gson().toJsonTree(runtime.capabilities()));
        result.addProperty("phase", s.getPhase().name());
        result.addProperty("ownerId", s.getOwnerId());
        result.addProperty("name", s.getDeviceName());
        result.addProperty("protocolVersion", s.getFirmwareProtocolVersion());
        result.addProperty("maxChannels", runtime.maxChannels());
        result.addProperty("maxContacts", s.getMaxContacts());
        result.addProperty("advertisementType", s.getAdvertisementType());
        result.addProperty("contactsLastModified", s.getContactsLastModified());
        result.addProperty("lastError", s.getLastError());
        result.addProperty("frequencyMHz", s.getRadioFrequencyMhz());
        result.addProperty("bandwidthKHz", s.getRadioBandwidthKhz());
        result.addProperty("spreadingFactor", s.getRadioSpreadingFactor());
        result.addProperty("codingRate", s.getRadioCodingRate());
        result.addProperty("txPowerDbm", s.getTxPowerDbm());
        result.addProperty("repeatEnabled", s.isRepeatEnabled());
        result.addProperty("pathHashMode", s.getPathHashMode());
        JsonArray contacts = new JsonArray();
        for (var c : s.getContacts().values()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", c.id());
            row.addProperty("name", c.name());
            row.addProperty("type", c.type());
            row.addProperty("flags", c.flags());
            row.addProperty("encodedPathLength", c.encodedPathLength());
            row.addProperty("pathHex", HexFormat.of().formatHex(c.path()));
            row.addProperty("latitude", c.latitude());
            row.addProperty("longitude", c.longitude());
            row.addProperty("lastAdvert", c.lastAdvert());
            row.addProperty("lastModified", c.lastModified());
            contacts.add(row);
        }
        result.add("contacts", contacts);
        JsonArray channels = new JsonArray();
        for (var c : s.getMeshCoreChannels().values()) {
            JsonObject row = new JsonObject();
            row.addProperty("index", c.index());
            row.addProperty("name", c.name());
            row.addProperty("enabled", c.enabled());
            channels.add(row);
        }
        result.add("channels", channels);
        return result;
    }

    public static CompletableFuture<JsonObject> execute(
            MeshCoreCompanionProtocolRuntime r, JsonObject p) {
        if (!r.getState().isReady())
            return CompletableFuture.failedFuture(
                    new IllegalStateException("MeshCore session is not ready"));
        String op = text(p, "operation");
        CompletableFuture<?> work =
                switch (op) {
                    case "contacts.refresh" -> r.refreshContacts();
                    case "contacts.refreshDelta" ->
                            r.refreshContactsSince(number(p, "since").longValue());
                    case "channels.refresh" -> r.refreshChannels();
                    case "channel.set" ->
                            r.setChannel(integer(p, "index"), text(p, "name"), hex(p, "secretHex"));
                    case "channel.clear" -> r.setChannel(integer(p, "index"), "", new byte[16]);
                    case "channel.export" ->
                            CompletableFuture.completedFuture(
                                    MeshCoreSharing.channelLink(
                                            Objects.requireNonNull(
                                                    r.getState()
                                                            .getMeshCoreChannels()
                                                            .get(integer(p, "index")),
                                                    "Channel missing")));
                    case "channel.import" -> {
                        var c =
                                MeshCoreSharing.channelFromLink(
                                        integer(p, "index"), text(p, "link"));
                        yield r.setChannel(c.index(), c.name(), c.secret());
                    }
                    case "contact.set" ->
                            r.updateContact(new MeshCoreContact(hex(p, "snapshotHex")));
                    case "contact.add" ->
                            r.updateContact(
                                    MeshCoreContact.create(
                                            MeshCoreCommands.key(text(p, "id")),
                                            text(p, "name"),
                                            integer(p, "type"),
                                            integer(p, "flags"),
                                            number(p, "latitude").doubleValue(),
                                            number(p, "longitude").doubleValue()));
                    case "contact.favorite" ->
                            r.updateContact(
                                    Objects.requireNonNull(
                                                    r.getState().getContacts().get(text(p, "id")),
                                                    "Contact missing")
                                            .withFavorite(bool(p, "favorite")));
                    case "contact.remove" -> r.removeContact(text(p, "id"));
                    case "contact.share" -> r.shareContact(text(p, "id"));
                    case "contact.export" -> r.exportContact(p.has("id") ? text(p, "id") : null);
                    case "contact.import" -> r.importContact(hex(p, "advertHex"));
                    case "contact.importLink" ->
                            r.updateContact(MeshCoreSharing.contactFromLink(text(p, "link")));
                    case "path.reset" -> r.resetPath(text(p, "id"));
                    case "path.discover" -> r.discoverPath(text(p, "id"));
                    case "path.advert" -> r.advertPath(text(p, "id"));
                    case "path.trace" -> r.trace(hex(p, "pathHex"), integer(p, "hashSize"));
                    case "device.name" -> r.setName(text(p, "name"));
                    case "device.position" ->
                            r.setPosition(
                                    number(p, "latitude").doubleValue(),
                                    number(p, "longitude").doubleValue());
                    case "device.radio" ->
                            r.setRadio(
                                    number(p, "frequencyMHz").doubleValue(),
                                    number(p, "bandwidthKHz").doubleValue(),
                                    integer(p, "sf"),
                                    integer(p, "cr"),
                                    p.has("repeat")
                                            ? bool(p, "repeat")
                                            : r.getState().isRepeatEnabled());
                    case "device.power" -> r.setTxPower(integer(p, "dbm"));
                    case "device.time" -> r.synchronizeTime();
                    case "device.pin" -> r.setPin(integer(p, "pin"));
                    case "device.advert" -> r.sendAdvertisement(bool(p, "flood"));
                    case "device.policies" ->
                            r.setOtherParameters(
                                    bool(p, "manualContacts"),
                                    integer(p, "telemetryModes"),
                                    integer(p, "locationPolicy"),
                                    integer(p, "multiAcks"));
                    case "device.reboot" -> r.reboot();
                    case "device.factoryReset" -> {
                        confirm(p);
                        yield r.factoryReset();
                    }
                    case "identity.export" -> {
                        confirm(p);
                        yield r.exportIdentity();
                    }
                    case "identity.import" -> {
                        confirm(p);
                        yield r.importIdentity(hex(p, "privateKeyHex"));
                    }
                    case "identity.sign" -> r.sign(hex(p, "dataHex"));
                    case "remote.login" -> r.login(text(p, "id"), text(p, "password"));
                    case "remote.logout" -> r.logout(text(p, "id"));
                    case "remote.connected" -> r.hasConnection(text(p, "id"));
                    case "remote.status" -> r.remoteStatus(text(p, "id"));
                    case "remote.cli" -> r.remoteCli(text(p, "id"), text(p, "command"));
                    case "remote.binary" ->
                            r.binaryRequest(text(p, "id"), hex(p, "dataHex"), bool(p, "anonymous"));
                    case "telemetry" -> r.telemetry(p.has("id") ? text(p, "id") : null);
                    case "statistics" -> r.getStatistics(integer(p, "group"));
                    case "cli" -> r.runCli(text(p, "command"));
                    case "variables.get" -> r.getCustomVariables();
                    case "variables.set" -> r.setCustomVariable(text(p, "name"), text(p, "value"));
                    case "autoAdd.get" -> r.getAutoAdd();
                    case "autoAdd.set" -> r.setAutoAdd(integer(p, "flags"), integer(p, "hops"));
                    case "tuning.get" -> r.getTuning();
                    case "tuning.set" ->
                            r.setTuning(
                                    number(p, "rxDelay").doubleValue(),
                                    number(p, "airtimeFactor").doubleValue());
                    case "path.hashMode" -> r.setPathHashMode(integer(p, "mode"));
                    case "repeat.frequencies" -> r.allowedRepeatFrequencies();
                    case "scope.get" ->
                            r.defaultScope()
                                    .thenApply(v -> {
                                        JsonObject o = new JsonObject();
                                        o.addProperty(
                                                "name",
                                                v.length > 1
                                                        ? MeshCoreCompanionFrames
                                                                .nullTerminatedText(v, 1, 31)
                                                        : "");
                                        return o;
                                    });
                    case "scope.set" -> r.setDefaultScope(text(p, "name"), hex(p, "keyHex"));
                    case "scope.override" ->
                            r.setFloodScope(
                                    p.has("keyHex") ? hex(p, "keyHex") : null, bool(p, "unscoped"));
                    case "data.channel" ->
                            r.sendChannelData(
                                    integer(p, "index"),
                                    hex(p, "pathHex"),
                                    integer(p, "encodedPathLength"),
                                    integer(p, "dataType"),
                                    hex(p, "dataHex"));
                    case "data.raw" ->
                            r.sendRawData(
                                    hex(p, "pathHex"),
                                    integer(p, "encodedPathLength"),
                                    hex(p, "dataHex"));
                    case "data.control" -> r.sendControlData(hex(p, "dataHex"));
                    case "data.packet" ->
                            r.sendRawPacket(integer(p, "priority"), hex(p, "dataHex"));
                    case "message.details" ->
                            CompletableFuture.completedFuture(
                                    r.messageDetails(number(p, "messageId").longValue()));
                    case "message.attempts" ->
                            CompletableFuture.completedFuture(
                                    new Gson()
                                            .toJsonTree(
                                                    r.attempts(
                                                            number(p, "messageId").longValue())));
                    case "events" -> CompletableFuture.completedFuture(events(r));
                    default ->
                            throw new IllegalArgumentException("Unknown MeshCore operation: " + op);
                };
        return work.thenApply(value -> {
            JsonObject result = new JsonObject();
            result.addProperty("operation", op);
            result.addProperty("success", true);
            if (value instanceof byte[] bytes)
                result.addProperty("payloadHex", HexFormat.of().formatHex(bytes));
            else if (value instanceof JsonElement json) result.add("result", json);
            else if (value instanceof Boolean b) result.addProperty("result", b);
            else if (value != null) result.addProperty("result", String.valueOf(value));
            return result;
        });
    }

    private static JsonArray events(MeshCoreCompanionProtocolRuntime r) {
        JsonArray events = new JsonArray();
        for (var event : r.recentEvents()) {
            JsonObject o = new JsonObject();
            o.addProperty("receivedAt", event.receivedAt());
            o.addProperty("type", event.type());
            o.addProperty("name", event.name());
            o.addProperty("payloadHex", HexFormat.of().formatHex(event.payload()));
            events.add(o);
        }
        return events;
    }

    private static void confirm(JsonObject p) {
        if (!bool(p, "confirm"))
            throw new IllegalArgumentException(
                    "Explicit confirm=true required for identity/factory operation");
    }

    private static String text(JsonObject p, String k) {
        if (!p.has(k) || p.get(k).isJsonNull()) throw new IllegalArgumentException("Missing " + k);
        return p.get(k).getAsString();
    }

    private static Number number(JsonObject p, String k) {
        if (!p.has(k)) throw new IllegalArgumentException("Missing " + k);
        return p.get(k).getAsBigDecimal();
    }

    private static int integer(JsonObject p, String k) {
        return ((java.math.BigDecimal) number(p, k)).intValueExact();
    }

    private static boolean bool(JsonObject p, String k) {
        return p.has(k) && p.get(k).getAsBoolean();
    }

    private static byte[] hex(JsonObject p, String k) {
        return HexFormat.of().parseHex(text(p, k));
    }
}
