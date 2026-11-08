package com.meshtastic.client.protocol.meshcore;

import com.meshtastic.client.connection.*;
import com.meshtastic.client.model.*;
import com.meshtastic.client.protocol.*;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.PacketMonitorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Companion session pinned to meshcore-dev/MeshCore 81dbae5 (protocol 14). */
public final class MeshCoreCompanionProtocolRuntime
        implements ProtocolRuntime<MeshCoreCompanionState> {
    private static final Logger log =
            LoggerFactory.getLogger(MeshCoreCompanionProtocolRuntime.class);
    private final ProtocolRuntimeContext context;
    private final TransportConnection transport;
    private final MeshCoreCompanionState state = new MeshCoreCompanionState();
    private final MeshCoreStorage storage = new MeshCoreStorage();
    private final CompletableFuture<MeshCoreCompanionState> readyFuture = new CompletableFuture<>();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService streamWriter =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "meshcore-companion-write");
                t.setDaemon(true);
                return t;
            });
    private final MeshCoreCommandQueue commands;
    private final Map<Long, PendingDelivery> deliveries = new HashMap<>();
    private final Set<MeshMessage> sending = new HashSet<>();
    private final CopyOnWriteArrayList<Consumer<byte[]>> eventListeners =
            new CopyOnWriteArrayList<>();
    private final Set<Integer> disabledCommands =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Set<String> syncContacts;
    private boolean contactsSyncing;
    private final Set<Integer> changingChannels = ConcurrentHashMap.newKeySet();
    private boolean draining, drainRequested, started, signing;
    private volatile boolean closed;
    private long lastTimestamp;

    MeshCoreCompanionProtocolRuntime(ProtocolRuntimeContext context) {
        this(
                context,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    var t = new Thread(r, "meshcore-companion-" + context.connectionId());
                    t.setDaemon(true);
                    return t;
                }));
    }

    MeshCoreCompanionProtocolRuntime(
            ProtocolRuntimeContext context, ScheduledExecutorService scheduler) {
        this.context = context;
        this.transport = context.transportConnection();
        this.scheduler = scheduler;
        this.commands = new MeshCoreCommandQueue(scheduler, this::write, this::sessionFailed, this);
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.MESHCORE_COMPANION;
    }

    @Override
    public MeshCoreCompanionState getState() {
        return state;
    }

    @Override
    public CompletableFuture<MeshCoreCompanionState> getReadyFuture() {
        return readyFuture;
    }

    @Override
    public String getOwnerId() {
        return state.getOwnerId();
    }

    @Override
    public synchronized CompletableFuture<MeshCoreCompanionState> start() {
        if (started) return readyFuture;
        started = true;
        if (transport instanceof FrameFormatAwareConnection stream)
            stream.setFrameFormat(FrameFormat.MESHCORE_COMPANION);
        state.setPhase(MeshCoreCompanionState.Phase.HANDSHAKE);
        transport.setDataListener(this::handlePacket);
        request(MeshCoreCompanionFrames.appStart("MeshApp"), 5)
                .thenCompose(_ -> {
                    storage.bindAlias(state.getOwnerId(), state.getOwnerId(), true);
                    for (byte[] contact : storage.contacts(state.getOwnerId()))
                        parseContact(contact);
                    return request(MeshCoreCompanionFrames.deviceQuery(), 13);
                })
                .thenCompose(_ -> {
                    state.setPhase(MeshCoreCompanionState.Phase.SYNCING);
                    return refreshContacts();
                })
                .thenCompose(_ -> refreshChannels())
                .thenCompose(_ -> request(MeshCoreCompanionFrames.getBattery(), 12))
                .whenComplete((_, error) -> {
                    if (error != null) {
                        sessionFailed(error);
                        return;
                    }
                    if (closed) return;
                    state.setReady(true);
                    state.setPhase(MeshCoreCompanionState.Phase.READY);
                    readyFuture.complete(state);
                    drainMessages();
                });
        return readyFuture;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        transport.setDataListener(null);
        commands.close();
        failSends("DISCONNECTED");
        scheduler.shutdownNow();
        streamWriter.shutdownNow();
        state.getDeviceState().shutdown();
        state.setReady(false);
        state.setPhase(MeshCoreCompanionState.Phase.DISCONNECTED);
        eventListeners.clear();
        readyFuture.completeExceptionally(new CancellationException("MeshCore disconnected"));
    }

    private synchronized void sessionFailed(Throwable error) {
        if (closed) return;
        state.setReady(false);
        state.setLastError(errorMessage(error));
        state.setPhase(MeshCoreCompanionState.Phase.ERROR);
        failSends(errorMessage(error));
        readyFuture.completeExceptionally(error);
        if (!scheduler.isShutdown()) scheduler.execute(transport::disconnect);
    }

    private void failSends(String reason) {
        for (var d : deliveries.values()) d.timer.cancel(false);
        deliveries.clear();
        for (var message : List.copyOf(sending))
            updateStatus(message, MeshMessage.DeliveryStatus.FAILED, reason);
        sending.clear();
    }

    private CompletableFuture<Void> write(byte[] payload) {
        if (closed)
            return CompletableFuture.failedFuture(
                    new IllegalStateException("MeshCore disconnected"));
        if (payload.length < 1 || payload.length > MeshCoreCompanionFrameParser.MAX_FRAME_SIZE)
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Companion frame exceeds 176 bytes"));
        record(payload, false);
        byte[] bytes =
                transport instanceof FrameFormatAwareConnection
                        ? MeshCoreCompanionFrameParser.encode(payload)
                        : payload;
        if (transport instanceof FrameFormatAwareConnection)
            return CompletableFuture.supplyAsync(
                            () -> transport.sendBytesAsync(bytes, true), streamWriter)
                    .thenCompose(java.util.function.Function.identity());
        return transport.sendBytesAsync(bytes, true);
    }

    public MeshCoreCapabilities capabilities() {
        return new MeshCoreCapabilities(
                state.getFirmwareProtocolVersion(),
                state.getMaxContacts(),
                maxChannels(),
                state.getMaxTxPowerDbm(),
                disabledCommands);
    }

    private CompletableFuture<byte[]> submit(
            byte[] payload, int terminal, long timeout, Consumer<byte[]> receive, int... progress) {
        String reason = capabilities().unsupportedReason(payload[0] & 255);
        if (reason != null)
            return CompletableFuture.failedFuture(new UnsupportedOperationException(reason));
        return commands.submit(payload, terminal, timeout, receive, progress)
                .whenComplete((_, e) -> {
                    Throwable cause = e;
                    while (cause != null && cause.getCause() != null) cause = cause.getCause();
                    if (cause instanceof MeshCoreCommandQueue.MeshCoreException failure
                            && (failure.code == 1 || failure.code == -1))
                        disabledCommands.add(payload[0] & 255);
                });
    }

    private CompletableFuture<byte[]> request(byte[] payload, int terminal, int... progress) {
        return submit(payload, terminal, 5_000, this::parseResponse, progress);
    }

    public synchronized CompletableFuture<Void> refreshContacts() {
        if (contactsSyncing)
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Contact sync already in progress"));
        contactsSyncing = true;
        syncContacts = new HashSet<>();
        return request(MeshCoreCompanionFrames.getContacts(), 4, 2, 3)
                .thenAccept(_ -> {
                    for (String id : state.getContacts().keySet())
                        if (!syncContacts.contains(id)) {
                            state.removeContact(id);
                            storage.deleteContact(state.getOwnerId(), id.substring(3));
                        }
                    migrateContactAliases();
                })
                .whenComplete((_, _) -> {
                    syncContacts = null;
                    contactsSyncing = false;
                    state.fireChanged();
                });
    }

    private void migrateContactAliases() {
        var contacts = state.getContacts();
        for (String id : contacts.keySet())
            if (contacts.keySet().stream()
                            .filter(k -> k.substring(0, 15).equals(id.substring(0, 15)))
                            .count()
                    == 1) {
                storage.bindAlias(state.getOwnerId(), id, false);
                String legacy = id.substring(0, 15), pending = "mc:pending:" + id.substring(3, 15);
                var device = state.getDeviceState();
                int count =
                        device.getDirectMessages(legacy).size()
                                + device.getDirectMessages(pending).size();
                if (count > 0) {
                    for (var message :
                            MessageDbService.getInstance()
                                    .loadLast("dm", id, Math.max(count, 200), state.getOwnerId()))
                        device.getMessageStore().addDirectMessage(message, id);
                    device.removeDirectMessages(legacy);
                    device.removeDirectMessages(pending);
                    device.fireMessageListeners();
                }
            }
    }

    public synchronized CompletableFuture<Void> refreshContactsSince(long since) {
        if (since < 0 || since > 0xffffffffL)
            throw new IllegalArgumentException("Invalid contact timestamp");
        if (contactsSyncing)
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Contact sync already in progress"));
        contactsSyncing = true;
        byte[] command = new byte[5];
        command[0] = 4;
        MeshCoreCompanionFrames.writeIntLe(command, 1, since);
        return request(command, 4, 2, 3)
                .thenAccept(_ -> migrateContactAliases())
                .whenComplete((_, _) -> {
                    synchronized (this) {
                        contactsSyncing = false;
                    }
                });
    }

    public CompletableFuture<Void> refreshChannels() {
        state.getDeviceState().setChannelCatalogReady(false);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < maxChannels(); i++) {
            final int index = i;
            chain = chain.thenCompose(_ -> getChannel(index).thenAccept(_ -> {}));
        }
        return chain.thenRun(() -> {
            state.getDeviceState().setChannelCatalogReady(true);
            state.getDeviceState().fireMessageListeners();
            state.fireChanged();
        });
    }

    public int maxChannels() {
        return state.getMaxChannels() == null ? 8 : state.getMaxChannels();
    }

    public int maxTextBytes(boolean direct) {
        return direct
                ? 160
                : Math.max(
                        0,
                        160
                                - (state.getDeviceName() == null
                                        ? 0
                                        : state.getDeviceName()
                                                .getBytes(StandardCharsets.UTF_8)
                                                .length)
                                - 2);
    }

    public synchronized MeshMessage sendChannelMessage(int channel, String text, int replyId) {
        if (replyId != 0)
            throw new IllegalArgumentException("MeshCore does not support message replies");
        if (changingChannels.contains(channel)
                || cannotSend(text, false)
                || !state.getDeviceState().hasEnabledChannel(channel)) return null;
        return sendNew(state.getOwnerId(), "!ffffffff", channel, text, false);
    }

    public synchronized MeshMessage sendDirectMessage(String peer, String text, int replyId) {
        if (replyId != 0)
            throw new IllegalArgumentException("MeshCore does not support message replies");
        if (cannotSend(text, true)) return null;
        byte[] prefix = state.publicKeyPrefixForNode(peer);
        if (prefix.length != 6) return null;
        String canonical =
                state.getContacts().keySet().stream()
                        .filter(x -> x.startsWith("mc:" + MeshCoreCompanionFrames.hex(prefix)))
                        .findFirst()
                        .orElse(null);
        if (canonical == null) return null;
        return sendNew(state.getOwnerId(), canonical, 0, text, true);
    }

    private boolean cannotSend(String text, boolean direct) {
        return closed
                || !state.isReady()
                || text == null
                || text.isBlank()
                || text.indexOf(0) >= 0
                || text.getBytes(StandardCharsets.UTF_8).length > maxTextBytes(direct);
    }

    private MeshMessage sendNew(String from, String to, int channel, String text, boolean direct) {
        long timestamp =
                lastTimestamp = Math.max(System.currentTimeMillis() / 1000, lastTimestamp + 1);
        MeshMessage message = new MeshMessage(from, to, channel, text, timestamp, true);
        message.setPacketId(storage.messageId(state.getOwnerId(), "out:" + UUID.randomUUID()));
        message.setStatus(MeshMessage.DeliveryStatus.SENDING);
        if (direct) state.getDeviceState().addDirectMessage(message, to);
        else state.getDeviceState().addMessage(message);
        requirePersisted(message);
        sendAttempt(message, 0);
        return message;
    }

    public synchronized boolean retryMessage(MeshMessage message) {
        if (message == null
                || !message.isOutgoing()
                || !Objects.equals(message.getFromNodeId(), state.getOwnerId())
                || cannotSend(message.getText(), message.isDirectMessage())
                || sending.contains(message)) return false;
        if (!message.isDirectMessage()) {
            var channel = state.getMeshCoreChannels().get(message.getChannelIndex());
            var saved = storage.messageDetails(state.getOwnerId(), message.getDbId());
            if (changingChannels.contains(message.getChannelIndex())
                    || channel == null
                    || !channel.enabled()) return false;
            if (saved.has("channelFingerprint")
                    && !saved.get("channelFingerprint").isJsonNull()
                    && !saved.get("channelFingerprint")
                            .getAsString()
                            .equals(MeshCoreStorage.digest(channel.secret()))) {
                updateStatus(
                        message,
                        MeshMessage.DeliveryStatus.FAILED,
                        "Channel key changed; create a new message explicitly");
                return false;
            }
        }
        int attempt = Math.max(1, storage.lastAttempt(state.getOwnerId(), message.getDbId()) + 1);
        if (attempt > 255
                || message.isDirectMessage()
                        && attempt > 3
                        && message.getText().getBytes(StandardCharsets.UTF_8).length > 158)
            return false;
        sendAttempt(message, attempt);
        return true;
    }

    private void sendAttempt(MeshMessage message, int attempt) {
        byte[] payload;
        if (message.isDirectMessage()) {
            byte[] prefix = state.publicKeyPrefixForNode(message.getToNodeId());
            if (prefix.length != 6) {
                updateStatus(
                        message, MeshMessage.DeliveryStatus.FAILED, "Contact missing or ambiguous");
                return;
            }
            payload =
                    MeshCoreCompanionFrames.sendDirectText(
                            prefix, message.getTimestamp(), message.getText());
            payload[2] = (byte) attempt;
        } else
            payload =
                    MeshCoreCompanionFrames.sendChannelText(
                            message.getChannelIndex(), message.getTimestamp(), message.getText());
        storage.saveEnvelope(
                state.getOwnerId(),
                message.getDbId(),
                payload,
                message.isDirectMessage()
                        ? null
                        : state.getMeshCoreChannels().get(message.getChannelIndex()));
        storage.beginAttempt(state.getOwnerId(), message.getDbId(), attempt);
        sending.add(message);
        updateStatus(message, MeshMessage.DeliveryStatus.SENDING, null);
        request(payload, message.isDirectMessage() ? 6 : 0)
                .whenComplete((response, error) -> {
                    synchronized (this) {
                        if (error != null) {
                            sending.remove(message);
                            updateStatus(
                                    message,
                                    MeshMessage.DeliveryStatus.FAILED,
                                    errorMessage(error));
                            return;
                        }
                        updateStatus(message, MeshMessage.DeliveryStatus.DELIVERED, null);
                        if (!message.isDirectMessage()) {
                            sending.remove(message);
                            return;
                        }
                        long hash = MeshCoreCompanionFrames.unsignedIntLe(response, 2);
                        long timeout =
                                Math.clamp(
                                        MeshCoreCompanionFrames.unsignedIntLe(response, 6),
                                        500,
                                        300_000);
                        storage.attemptAck(state.getOwnerId(), message.getDbId(), hash, timeout);
                        if (hash == 0) {
                            sending.remove(message);
                            return;
                        }
                        if (deliveries.containsKey(hash)) {
                            sending.remove(message);
                            updateStatus(
                                    message,
                                    MeshMessage.DeliveryStatus.FAILED,
                                    "Ambiguous ACK hash");
                            return;
                        }
                        ScheduledFuture<?> timer =
                                scheduler.schedule(
                                        () -> {
                                            synchronized (this) {
                                                PendingDelivery pending = deliveries.remove(hash);
                                                if (pending != null) {
                                                    sending.remove(message);
                                                    updateStatus(
                                                            message,
                                                            MeshMessage.DeliveryStatus.FAILED,
                                                            "ACK_TIMEOUT");
                                                }
                                            }
                                        },
                                        timeout,
                                        TimeUnit.MILLISECONDS);
                        deliveries.put(hash, new PendingDelivery(message, timer));
                    }
                });
    }

    private void updateStatus(
            MeshMessage message, MeshMessage.DeliveryStatus status, String error) {
        message.setStatus(status);
        message.setErrorReason(error);
        try {
            MessageDbService.getInstance()
                    .updateStatusById(message.getDbId(), state.getOwnerId(), status, error);
            storage.attemptStatus(state.getOwnerId(), message.getDbId(), status.name(), error);
        } catch (RuntimeException e) {
            state.setLastError(e.getMessage());
            log.error("MeshCore status persistence failed", e);
        }
        state.getDeviceState()
                .fireMessageChange(
                        MessageChangeEvent.statusChanged(
                                message.isDirectMessage() ? "dm" : "channel",
                                message.isDirectMessage()
                                        ? message.getToNodeId()
                                        : String.valueOf(message.getChannelIndex()),
                                state.getOwnerId(),
                                message));
        state.getDeviceState().fireMessageListeners();
    }

    private synchronized void handlePacket(byte[] packet) {
        if (closed || packet == null || packet.length == 0) return;
        record(packet, true);
        int type = packet[0] & 255;
        try {
            if (type == 0x82) {
                requireLength(packet, 9);
                long hash = MeshCoreCompanionFrames.unsignedIntLe(packet, 1);
                PendingDelivery pending = deliveries.remove(hash);
                if (pending != null) {
                    pending.timer.cancel(false);
                    sending.remove(pending.message);
                    updateStatus(pending.message, MeshMessage.DeliveryStatus.CONFIRMED, null);
                }
            } else if (type == 0x83) {
                drainRequested = true;
                if (state.isReady()) drainMessages();
            } else if (type == 0x8a) {
                parseContact(packet);
                if (syncContacts == null) migrateContactAliases();
            } else if (type == 0x80 || type == 0x81) {
                requireLength(packet, 33);
                request(MeshCoreCommands.keyCommand(30, Arrays.copyOfRange(packet, 1, 33)), 3);
            } else if (type == 0x8f) {
                requireLength(packet, 33);
                String key = MeshCoreCompanionFrames.hex(Arrays.copyOfRange(packet, 1, 33));
                state.removeContact("mc:" + key);
                storage.deleteContact(state.getOwnerId(), key);
            } else if (type == 0x90) {
                state.setLastError("Contact storage full");
            } else {
                if (type >= 0x84 && type <= 0x8e && state.getOwnerId() != null)
                    storage.saveEvent(state.getOwnerId(), packet);
                commands.accept(packet);
            }
            if (type == 0x8b) applyTelemetry(packet);
            if (type >= 0x80) for (var listener : eventListeners) listener.accept(packet.clone());
        } catch (RuntimeException error) {
            state.setLastError(error.getMessage());
            log.warn(
                    "Invalid MeshCore packet type=0x{} bytes={}",
                    Integer.toHexString(type),
                    packet.length);
        }
    }

    private void parseResponse(byte[] packet) {
        switch (packet[0] & 255) {
            case 0 -> requireLength(packet, 1);
            case 2 -> {
                requireLength(packet, 5);
                parseContactsStart(packet);
            }
            case 3 -> parseContact(packet);
            case 4 -> {
                requireLength(packet, 5);
                parseContactsEnd(packet);
            }
            case 5 -> parseSelfInfo(packet);
            case 6 -> requireLength(packet, 10);
            case 7, 16 -> parseContactMessage(packet);
            case 8, 17 -> parseChannelMessage(packet);
            case 12 -> parseBattery(packet);
            case 13 -> {
                requireLength(packet, 2);
                if ((packet[1] & 255) >= 3) requireLength(packet, 80);
                parseDeviceInfo(packet);
            }
            case 18 -> parseChannelInfo(packet);
            case 27 -> {
                requireLength(packet, 9);
                requireLength(packet, 9 + (packet[8] & 255));
                storage.saveEvent(state.getOwnerId(), packet);
                for (var listener : eventListeners) listener.accept(packet.clone());
            }
            default -> {}
        }
        state.fireChanged();
    }

    private synchronized void drainMessages() {
        if (closed || !state.isReady()) return;
        if (draining) {
            drainRequested = true;
            return;
        }
        draining = true;
        drainRequested = false;
        request(MeshCoreCompanionFrames.getMessage(), -1, 7, 8, 10, 16, 17, 27)
                .whenComplete((packet, error) -> {
                    synchronized (this) {
                        draining = false;
                        if (error != null) {
                            state.setLastError(errorMessage(error));
                            return;
                        }
                        if ((packet[0] & 255) != 10 || drainRequested)
                            scheduler.execute(this::drainMessages);
                    }
                });
    }

    private CompletableFuture<byte[]> getChannel(int index) {
        return submit(
                MeshCoreCompanionFrames.getChannel(index),
                18,
                5000,
                p -> {
                    requireLength(p, 50);
                    if ((p[1] & 255) != index)
                        throw new IllegalArgumentException("Unrelated channel response");
                    parseResponse(p);
                });
    }

    public CompletableFuture<Void> setChannel(int index, String name, byte[] secret) {
        if (index < 0 || index >= maxChannels())
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid channel index"));
        byte[] command = MeshCoreCommands.channel(index, name, secret);
        if (!changingChannels.add(index))
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Channel update already in progress"));
        return request(command, 0)
                .thenCompose(_ -> getChannel(index))
                .thenAccept(_ -> {
                    var actual = state.getMeshCoreChannels().get(index);
                    if (!Objects.equals(actual.name(), name == null ? "" : name)
                            || !Arrays.equals(actual.secret(), secret))
                        throw new IllegalStateException(
                                "Channel readback differs from requested settings");
                })
                .whenComplete((_, _) -> changingChannels.remove(index));
    }

    public CompletableFuture<Void> setName(String name) {
        return setting(MeshCoreCommands.text(8, name, 31));
    }

    public CompletableFuture<Void> setPosition(double lat, double lon) {
        return setting(MeshCoreCommands.position(lat, lon));
    }

    public CompletableFuture<Void> setRadio(double mhz, double bw, int sf, int cr, boolean repeat) {
        return setting(MeshCoreCommands.radio(mhz, bw, sf, cr, repeat))
                .thenCompose(_ -> request(MeshCoreCompanionFrames.deviceQuery(), 13))
                .thenAccept(_ -> {});
    }

    public CompletableFuture<Void> setTxPower(int dbm) {
        if (dbm < -9 || state.getMaxTxPowerDbm() != null && dbm > state.getMaxTxPowerDbm())
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("TX power outside device limits"));
        return setting(MeshCoreCommands.bytes(12, dbm));
    }

    public CompletableFuture<Void> setOtherParameters(
            boolean manual, int telemetryModes, int locationPolicy, int multiAcks) {
        if (telemetryModes < 0
                || telemetryModes > 63
                || locationPolicy < 0
                || locationPolicy > 2
                || multiAcks < 0
                || multiAcks > 255)
            throw new IllegalArgumentException("Invalid advertisement/telemetry settings");
        return setting(
                MeshCoreCommands.bytes(
                        38, manual ? 1 : 0, telemetryModes, locationPolicy, multiAcks));
    }

    public CompletableFuture<Void> setPin(int pin) {
        return request(MeshCoreCommands.pin(pin), 0)
                .thenCompose(_ -> request(MeshCoreCompanionFrames.deviceQuery(), 13))
                .thenAccept(_ -> {});
    }

    public CompletableFuture<Void> synchronizeTime() {
        return request(MeshCoreCommands.time(System.currentTimeMillis() / 1000), 0)
                .thenCompose(_ -> request(new byte[] {5}, 9))
                .thenAccept(v -> requireLength(v, 5));
    }

    public CompletableFuture<Void> sendAdvertisement(boolean flood) {
        return request(MeshCoreCommands.bytes(7, flood ? 1 : 0), 0).thenAccept(_ -> {});
    }

    private CompletableFuture<Void> setting(byte[] command) {
        return request(command, 0)
                .thenCompose(_ -> request(MeshCoreCompanionFrames.appStart("MeshApp"), 5))
                .thenAccept(_ -> {});
    }

    public CompletableFuture<Void> updateContact(MeshCoreContact contact) {
        return request(MeshCoreCommands.contact(contact), 0)
                .thenCompose(_ -> request(MeshCoreCommands.keyCommand(30, contact.publicKey()), 3))
                .thenAccept(_ -> {});
    }

    public CompletableFuture<Void> removeContact(String id) {
        return request(MeshCoreCommands.keyCommand(15, MeshCoreCommands.key(id)), 0)
                .thenRun(() -> {
                    state.removeContact(id);
                    storage.deleteContact(state.getOwnerId(), id.substring(3));
                });
    }

    public CompletableFuture<Void> resetPath(String id) {
        return request(MeshCoreCommands.keyCommand(13, MeshCoreCommands.key(id)), 0)
                .thenCompose(
                        _ -> request(MeshCoreCommands.keyCommand(30, MeshCoreCommands.key(id)), 3))
                .thenAccept(_ -> {});
    }

    public CompletableFuture<Void> shareContact(String id) {
        return request(MeshCoreCommands.keyCommand(16, MeshCoreCommands.key(id)), 0)
                .thenAccept(_ -> {});
    }

    public CompletableFuture<byte[]> exportContact(String id) {
        return request(
                        id == null
                                ? new byte[] {17}
                                : MeshCoreCommands.keyCommand(17, MeshCoreCommands.key(id)),
                        11)
                .thenApply(v -> Arrays.copyOfRange(v, 1, v.length));
    }

    public CompletableFuture<Void> importContact(byte[] advert) {
        if (advert.length < 98)
            throw new IllegalArgumentException("Contact advertisement too short");
        return request(MeshCoreCommands.withBody(18, advert), 0)
                .thenCompose(_ -> refreshContacts());
    }

    public CompletableFuture<String> runCli(String command) {
        if (state.getFirmwareProtocolVersion() == null || state.getFirmwareProtocolVersion() < 14)
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Local CLI requires Companion protocol 14"));
        return request(concat(MeshCoreCommands.text(66, command, 174), new byte[] {0}), 29)
                .thenApply(v -> new String(v, 1, v.length - 1, StandardCharsets.UTF_8));
    }

    public CompletableFuture<byte[]> getStatistics(int type) {
        if (type < 0 || type > 2) throw new IllegalArgumentException("Unknown statistics group");
        return request(MeshCoreCommands.bytes(56, type), 24);
    }

    public CompletableFuture<byte[]> getCustomVariables() {
        return request(new byte[] {40}, 21);
    }

    public CompletableFuture<Void> setCustomVariable(String name, String value) {
        if (name.contains(":")) throw new IllegalArgumentException("Invalid setting name");
        return request(MeshCoreCommands.text(41, name + ":" + value, 174), 0).thenAccept(_ -> {});
    }

    public CompletableFuture<Void> reboot() {
        return submit(MeshCoreCommands.text(19, "reboot", 6), -2, 5_000, _ -> {})
                .thenRun(this::disconnectAfterReset);
    }

    public com.google.gson.JsonObject messageDetails(long row) {
        return storage.messageDetails(state.getOwnerId(), row);
    }

    public List<MeshCoreSendAttempt> attempts(long row) {
        return storage.attempts(state.getOwnerId(), row);
    }

    public List<MeshCoreEvent> recentEvents() {
        return storage.events(state.getOwnerId());
    }

    public CompletableFuture<byte[]> login(String id, String password) {
        return remote(
                MeshCoreCommands.login(MeshCoreCommands.key(id), password),
                0x85,
                MeshCoreCommands.key(id));
    }

    public CompletableFuture<Void> logout(String id) {
        return request(MeshCoreCommands.keyCommand(29, MeshCoreCommands.key(id)), 0)
                .thenAccept(_ -> {});
    }

    public CompletableFuture<Boolean> hasConnection(String id) {
        return request(MeshCoreCommands.keyCommand(28, MeshCoreCommands.key(id)), 0)
                .handle((_, e) -> {
                    if (e == null) return true;
                    Throwable t = e;
                    while (t.getCause() != null) t = t.getCause();
                    if (t instanceof MeshCoreCommandQueue.MeshCoreException me && me.code == 2)
                        return false;
                    throw new CompletionException(t);
                });
    }

    public CompletableFuture<byte[]> remoteStatus(String id) {
        return remote(
                MeshCoreCommands.keyCommand(27, MeshCoreCommands.key(id)),
                0x87,
                MeshCoreCommands.key(id));
    }

    public CompletableFuture<byte[]> discoverPath(String id) {
        return remote(
                MeshCoreCommands.withBody(52, concat(new byte[] {0}, MeshCoreCommands.key(id))),
                0x8d,
                MeshCoreCommands.key(id));
    }

    public CompletableFuture<byte[]> advertPath(String id) {
        return request(
                MeshCoreCommands.withBody(42, concat(new byte[] {0}, MeshCoreCommands.key(id))),
                22);
    }

    public CompletableFuture<byte[]> telemetry(String id) {
        byte[] key = id == null ? null : MeshCoreCommands.key(id);
        return remote(
                MeshCoreCommands.withBody(39, concat(new byte[3], key == null ? new byte[0] : key)),
                0x8b,
                key);
    }

    private CompletableFuture<byte[]> remote(byte[] command, int response, byte[] key) {
        return submit(
                command,
                response,
                5_000,
                p -> {
                    int type = p[0] & 255;
                    if (type == 6) requireLength(p, 10);
                    else {
                        requireLength(p, 8);
                        if (key != null
                                && !Arrays.equals(
                                        Arrays.copyOfRange(p, 2, 8), Arrays.copyOf(key, 6)))
                            throw new IllegalArgumentException("Unrelated remote response");
                    }
                },
                6);
    }

    public CompletableFuture<byte[]> remoteCli(String id, String text) {
        byte[] p =
                MeshCoreCompanionFrames.sendDirectText(
                        state.publicKeyPrefixForNode(id), System.currentTimeMillis() / 1000, text);
        p[1] =
                (byte)
                        (state.getFirmwareProtocolVersion() != null
                                        && state.getFirmwareProtocolVersion() >= 14
                                ? 3
                                : 1);
        return request(p, 6); // CLI reply arrives through the persistent offline message queue.
    }

    public CompletableFuture<byte[]> binaryRequest(String id, byte[] payload, boolean anonymous) {
        final long[] tag = {-1};
        return submit(
                MeshCoreCommands.withBody(
                        anonymous ? 57 : 50, concat(MeshCoreCommands.key(id), payload)),
                0x8c,
                5_000,
                p -> {
                    if ((p[0] & 255) == 6) {
                        requireLength(p, 10);
                        tag[0] = MeshCoreCompanionFrames.unsignedIntLe(p, 2);
                    } else {
                        requireLength(p, 6);
                        if (MeshCoreCompanionFrames.unsignedIntLe(p, 2) != tag[0])
                            throw new IllegalArgumentException("Unrelated binary response");
                    }
                },
                6);
    }

    public CompletableFuture<byte[]> getAutoAdd() {
        return request(new byte[] {59}, 25);
    }

    public CompletableFuture<Void> setAutoAdd(int flags, int hops) {
        if (flags < 0 || flags > 31 || hops < 0 || hops > 64)
            throw new IllegalArgumentException("Invalid contact discovery settings");
        return request(MeshCoreCommands.bytes(58, flags, hops), 0)
                .thenCompose(_ -> getAutoAdd())
                .thenAccept(_ -> {});
    }

    public CompletableFuture<byte[]> getTuning() {
        return request(new byte[] {43}, 23);
    }

    public CompletableFuture<Void> setTuning(double rxDelay, double airtime) {
        if (!Double.isFinite(rxDelay)
                || rxDelay < 0
                || rxDelay > 20
                || !Double.isFinite(airtime)
                || airtime < 0
                || airtime > 9) throw new IllegalArgumentException("Invalid tuning parameters");
        byte[] p = new byte[9];
        p[0] = 21;
        MeshCoreCompanionFrames.writeIntLe(p, 1, Math.round(rxDelay * 1000));
        MeshCoreCompanionFrames.writeIntLe(p, 5, Math.round(airtime * 1000));
        return request(p, 0).thenCompose(_ -> getTuning()).thenAccept(_ -> {});
    }

    public CompletableFuture<Void> setPathHashMode(int mode) {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("Path hash mode must be 0..2");
        return request(MeshCoreCommands.bytes(61, 0, mode), 0).thenAccept(_ -> {});
    }

    public CompletableFuture<byte[]> allowedRepeatFrequencies() {
        return request(new byte[] {60}, 26);
    }

    public CompletableFuture<byte[]> defaultScope() {
        return request(new byte[] {64}, 28);
    }

    public CompletableFuture<Void> setDefaultScope(String name, byte[] key) {
        if (name == null || name.isEmpty()) return request(new byte[] {63}, 0).thenAccept(_ -> {});
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        if (n.length > 30 || name.indexOf(0) >= 0 || key.length != 16)
            throw new IllegalArgumentException(
                    "Scope requires a name of 1..30 UTF-8 bytes and 16-byte key");
        byte[] body = new byte[47];
        System.arraycopy(n, 0, body, 0, n.length);
        System.arraycopy(key, 0, body, 31, 16);
        return request(MeshCoreCommands.withBody(63, body), 0)
                .thenCompose(_ -> defaultScope())
                .thenAccept(_ -> {});
    }

    public CompletableFuture<Void> setFloodScope(byte[] key, boolean unscoped) {
        if (key != null && key.length != 16)
            throw new IllegalArgumentException("Scope key must contain 16 bytes");
        return request(
                        MeshCoreCommands.withBody(
                                54,
                                concat(
                                        new byte[] {(byte) (unscoped ? 1 : 0)},
                                        key == null ? new byte[0] : key)),
                        0)
                .thenAccept(_ -> {});
    }

    public CompletableFuture<byte[]> trace(byte[] path, int hashSize) {
        if ((hashSize != 1 && hashSize != 2 && hashSize != 4 && hashSize != 8)
                || path.length == 0
                || path.length % hashSize != 0
                || path.length > 64)
            throw new IllegalArgumentException("Invalid trace path/hash size");
        long tag = Integer.toUnsignedLong(new java.security.SecureRandom().nextInt());
        byte[] p = new byte[10 + path.length];
        p[0] = 36;
        MeshCoreCompanionFrames.writeIntLe(p, 1, tag);
        p[9] = (byte) (hashSize == 1 ? 0 : hashSize == 2 ? 1 : hashSize == 4 ? 2 : 3);
        System.arraycopy(path, 0, p, 10, path.length);
        return submit(
                p,
                0x89,
                5_000,
                response -> {
                    if ((response[0] & 255) == 6) requireLength(response, 10);
                    else {
                        requireLength(response, 12);
                        if (MeshCoreCompanionFrames.unsignedIntLe(response, 4) != tag)
                            throw new IllegalArgumentException("Unrelated trace response");
                    }
                },
                6);
    }

    public CompletableFuture<Void> sendChannelData(
            int index, byte[] path, int encodedPathLength, int dataType, byte[] payload) {
        if (index < 0
                || index >= maxChannels()
                || dataType < 1
                || dataType > 65535
                || payload.length > 165) throw new IllegalArgumentException("Invalid datagram");
        validatePath(path, encodedPathLength, true);
        byte[] body =
                concat(
                        new byte[] {(byte) index, (byte) encodedPathLength},
                        concat(
                                path,
                                concat(
                                        new byte[] {(byte) dataType, (byte) (dataType >>> 8)},
                                        payload)));
        return request(MeshCoreCommands.withBody(62, body), 0).thenAccept(_ -> {});
    }

    public CompletableFuture<Void> sendRawData(byte[] path, int encodedPathLength, byte[] payload) {
        validatePath(path, encodedPathLength, false);
        if (payload.length < 4)
            throw new IllegalArgumentException("Raw data requires at least four bytes");
        return request(
                        MeshCoreCommands.withBody(
                                25,
                                concat(
                                        new byte[] {(byte) encodedPathLength},
                                        concat(path, payload))),
                        0)
                .thenAccept(_ -> {});
    }

    public CompletableFuture<Void> sendControlData(byte[] payload) {
        if (payload.length == 0 || (payload[0] & 128) == 0)
            throw new IllegalArgumentException("Invalid control payload");
        return request(MeshCoreCommands.withBody(55, payload), 0).thenAccept(_ -> {});
    }

    public CompletableFuture<Void> sendRawPacket(int priority, byte[] packet) {
        if (priority < 0 || priority > 255 || packet.length < 2)
            throw new IllegalArgumentException("Invalid raw packet");
        return request(
                        MeshCoreCommands.withBody(65, concat(new byte[] {(byte) priority}, packet)),
                        0)
                .thenAccept(_ -> {});
    }

    public CompletableFuture<byte[]> exportIdentity() {
        return request(new byte[] {23}, 14)
                .thenApply(v -> {
                    requireLength(v, 65);
                    return Arrays.copyOfRange(v, 1, 65);
                });
    }

    public CompletableFuture<Void> importIdentity(byte[] key) {
        if (key.length != 64) throw new IllegalArgumentException("Identity must contain 64 bytes");
        return request(MeshCoreCommands.withBody(24, key), 0).thenRun(this::disconnectAfterReset);
    }

    public CompletableFuture<Void> factoryReset() {
        return submit(MeshCoreCommands.text(51, "reset", 5), -2, 5_000, _ -> {})
                .thenRun(this::disconnectAfterReset);
    }

    private void disconnectAfterReset() {
        close();
        transport.disconnect();
    }

    public synchronized CompletableFuture<byte[]> sign(byte[] data) {
        if (data.length == 0 || data.length > 8192)
            throw new IllegalArgumentException("Signing requires 1..8192 bytes");
        if (signing)
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Signing already in progress"));
        signing = true;
        CompletableFuture<byte[]> chain =
                request(new byte[] {33}, 19)
                        .thenApply(v -> {
                            requireLength(v, 6);
                            if (data.length > MeshCoreCompanionFrames.unsignedIntLe(v, 2))
                                throw new IllegalArgumentException(
                                        "Data exceeds device signing capacity");
                            return v;
                        });
        for (int i = 0; i < data.length; i += 175) {
            byte[] part = Arrays.copyOfRange(data, i, Math.min(data.length, i + 175));
            chain = chain.thenCompose(_ -> request(MeshCoreCommands.withBody(34, part), 0));
        }
        return chain.thenCompose(_ -> request(new byte[] {35}, 20))
                .thenApply(v -> {
                    requireLength(v, 65);
                    return Arrays.copyOfRange(v, 1, 65);
                })
                .whenComplete((_, _) -> {
                    synchronized (this) {
                        signing = false;
                    }
                });
    }

    private static void validatePath(byte[] path, int encoded, boolean flood) {
        if (encoded == 255 && flood && path.length == 0) return;
        int bytes = (encoded & 63) * ((encoded >>> 6) + 1);
        if (encoded < 0 || encoded > 191 || bytes > 64 || path.length != bytes)
            throw new IllegalArgumentException("Invalid encoded path");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void applyTelemetry(byte[] packet) {
        requireLength(packet, 8);
        byte[] prefix = Arrays.copyOfRange(packet, 2, 8);
        String id =
                Arrays.equals(prefix, Arrays.copyOf(MeshCoreCommands.key(state.getOwnerId()), 6))
                        ? state.getOwnerId()
                        : state.resolveNodeIdFromPrefix(prefix);
        if (id == null) return;
        long timestamp = System.currentTimeMillis() / 1000;
        var deviceEntry = new TelemetryEntry(timestamp, id);
        deviceEntry.setTelemetryVariant("DEVICE_METRICS");
        var environmentEntry = new TelemetryEntry(timestamp, id);
        environmentEntry.setTelemetryVariant("ENVIRONMENT_METRICS");
        boolean deviceMetrics = false, environmentMetrics = false;
        NodeData node = state.getDeviceState().getNodeByNodeId(id);
        for (var reading : MeshCoreTelemetry.decode(Arrays.copyOfRange(packet, 8, packet.length))) {
            if (reading.values().isEmpty()) continue;
            float value = reading.values().getFirst().floatValue();
            switch (reading.type()) {
                case 116 -> {
                    deviceEntry.setVoltage(value);
                    deviceMetrics = true;
                    if (node != null) node.setVoltage(value);
                }
                case 103 -> {
                    environmentEntry.setTemperature(value);
                    environmentMetrics = true;
                    if (node != null) node.setTemperature(value);
                }
                case 104 -> {
                    environmentEntry.setRelativeHumidity(value);
                    environmentMetrics = true;
                    if (node != null) node.setRelativeHumidity(value);
                }
                case 115 -> {
                    environmentEntry.setBarometricPressure(value);
                    environmentMetrics = true;
                    if (node != null) node.setBarometricPressure(value);
                }
                case 117 -> {
                    environmentEntry.setEnvironmentCurrent(value);
                    environmentMetrics = true;
                }
                case 101 -> {
                    environmentEntry.setLux(value);
                    environmentMetrics = true;
                }
                case 136 -> {
                    if (node != null) {
                        node.setLatitude(reading.values().get(0));
                        node.setLongitude(reading.values().get(1));
                        node.setAltitude(reading.values().get(2).intValue());
                    }
                }
                default -> {}
            }
        }
        if (deviceMetrics) state.getDeviceState().addTelemetryEntry(deviceEntry);
        if (environmentMetrics) state.getDeviceState().addTelemetryEntry(environmentEntry);
        if (node != null) state.getDeviceState().fireNodeUpdateListeners(node.getNodeNum());
    }

    public void addEventListener(Consumer<byte[]> listener) {
        eventListeners.add(listener);
    }

    // Counterpart of addEventListener for integrations that subscribe temporarily.
    @SuppressWarnings("unused")
    public void removeEventListener(Consumer<byte[]> listener) {
        eventListeners.remove(listener);
    }

    private int incomingId(String identity) {
        return storage.messageId(state.getOwnerId(), "in:" + MeshCoreStorage.digest(identity));
    }

    private static void requirePersisted(MeshMessage message) {
        if (message == null || message.getDbId() <= 0)
            throw new IllegalStateException(
                    "Incoming/outgoing message was not saved; queue stopped");
    }

    static void requireLength(byte[] packet, int length) {
        if (packet.length < length)
            throw new IllegalArgumentException("Truncated Companion packet");
    }

    private static String errorMessage(Throwable e) {
        while (e.getCause() != null && e instanceof CompletionException) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private record PendingDelivery(MeshMessage message, ScheduledFuture<?> timer) {}

    private void record(byte[] packet, boolean incoming) {
        PacketMonitorService monitor = PacketMonitorService.getIfInitialized();
        if (monitor == null) return;
        // Control traffic may carry channel secrets, keys, PINs or passwords. Persist only the
        // type.
        if (incoming && (packet[0] & 255) == 0x88 && packet.length >= 3) {
            monitor.recordRawIncoming(
                    context.connectionId(),
                    "MeshCore Radio RX",
                    "RSSI=%d dBm, SNR=%.2f dB".formatted((int) packet[2], packet[1] / 4.0),
                    Arrays.copyOfRange(packet, 3, packet.length));
        }
        // Host control is not LoRa traffic. Operation failures are exposed through futures/state,
        // and received radio events are available in the separate MeshCore diagnostic journal.

    }

    private void parseSelfInfo(byte[] packet) {
        requireLength(packet, 58);
        state.setTxPowerDbm((int) packet[2]);
        state.setMaxTxPowerDbm((int) packet[3]);
        state.setPublicKeyHex(
                MeshCoreCompanionFrames.hex(MeshCoreCompanionFrames.publicKey(packet)));
        state.setAdvertisementType(packet[1] & 0xFF);
        if (packet.length >= 58) {
            double latitude = MeshCoreCompanionFrames.signedIntLe(packet, 36) / 1_000_000.0;
            double longitude = MeshCoreCompanionFrames.signedIntLe(packet, 40) / 1_000_000.0;
            state.setAdvertisementPosition(latitude, longitude);
            state.setMultiAckCount(packet[44] & 0xFF);
            state.setAdvertisementLocationPolicy(packet[45] & 0xFF);
            int telemetryModes = packet[46] & 0xFF;
            state.setTelemetryModes(
                    telemetryModes & 0b11,
                    (telemetryModes >>> 2) & 0b11,
                    (telemetryModes >>> 4) & 0b11);
            state.setManualAddContacts((packet[47] & 0xFF) != 0);
            state.setRadioParameters(
                    MeshCoreCompanionFrames.unsignedIntLe(packet, 48) / 1000.0,
                    MeshCoreCompanionFrames.unsignedIntLe(packet, 52) / 1000.0,
                    packet[56] & 0xFF,
                    packet[57] & 0xFF);
        }
        if (packet.length >= 58) {
            state.setDeviceName(MeshCoreCompanionFrames.text(packet, 58));
        }
        log.info(
                "MeshCore Companion self-info received: name={}, publicKey={}",
                state.getDeviceName(),
                state.getPublicKeyHex() == null ? "?" : "present");
    }

    /**
     * Parses {@code DEVICE_INFO} and schedules battery/storage metadata retrieval.
     */
    private void parseDeviceInfo(byte[] packet) {
        requireLength(packet, 2);
        int protocolVersion = packet[1] & 0xFF;
        state.setFirmwareProtocolVersion(protocolVersion);
        if (protocolVersion >= 3 && packet.length >= 80) {
            state.setMaxContacts((packet[2] & 0xFF) * 2);
            state.setMaxChannels(packet[3] & 0xFF);
            state.setBlePin((int) MeshCoreCompanionFrames.unsignedIntLe(packet, 4));
            state.setFirmwareBuild(MeshCoreCompanionFrames.fixedText(packet, 8, 12));
            state.setModel(MeshCoreCompanionFrames.fixedText(packet, 20, 40));
            state.setFirmwareVersion(MeshCoreCompanionFrames.fixedText(packet, 60, 20));
            if (protocolVersion >= 9 && packet.length >= 81)
                state.setRepeatEnabled(packet[80] != 0);
            if (protocolVersion >= 10 && packet.length >= 82)
                state.setPathHashMode(packet[81] & 255);
        }
    }

    /**
     * Parses battery voltage and optional storage counters.
     */
    private void parseBattery(byte[] packet) {
        requireLength(packet, 3);
        state.setBatteryMillivolts(MeshCoreCompanionFrames.unsignedShortLe(packet, 1));
        if (packet.length >= 11) {
            state.setStorage(
                    MeshCoreCompanionFrames.unsignedIntLe(packet, 3),
                    MeshCoreCompanionFrames.unsignedIntLe(packet, 7));
        }
    }

    /**
     * Parses the beginning of the MeshCore contact list.
     */
    private void parseContactsStart(byte[] packet) {
        if (packet.length >= 5) {
            state.setContactCount((int) MeshCoreCompanionFrames.unsignedIntLe(packet, 1));
        }
    }

    /**
     * Parses one contact from the MeshCore sync response.
     */
    private void parseContact(byte[] packet) {
        requireLength(packet, 148);
        MeshCoreContact contact = new MeshCoreContact(packet);
        storage.saveContact(state.getOwnerId(), packet);
        state.putContact(contact);
        state.updateContact(
                contact.publicKey(),
                contact.type(),
                contact.name(),
                contact.lastAdvert(),
                contact.latitude(),
                contact.longitude());
        if (syncContacts != null) syncContacts.add(contact.id());
        state.fireChanged();
    }

    /**
     * Completes contact-list synchronization.
     */
    private void parseContactsEnd(byte[] packet) {
        if (packet.length >= 5) {
            state.setContactsLastModified(MeshCoreCompanionFrames.unsignedIntLe(packet, 1));
        }
    }

    /**
     * Parses a MeshCore channel descriptor.
     */
    private void parseChannelInfo(byte[] packet) {
        requireLength(packet, 50);
        int index = packet[1] & 255;
        if (index >= maxChannels())
            throw new IllegalArgumentException("Channel index outside device capacity");
        state.putChannel(
                new MeshCoreChannel(
                        index,
                        MeshCoreCompanionFrames.nullTerminatedText(packet, 2, 32),
                        Arrays.copyOfRange(packet, 34, 50)));
    }

    /**
     * Parses an incoming MeshCore direct message.
     */
    private void parseContactMessage(byte[] packet) {
        boolean v3 = (packet[0] & 0xFF) == MeshCoreCompanionFrames.PACKET_CONTACT_MSG_RECV_V3;
        int offset = v3 ? 4 : 1;
        int minLength = v3 ? 16 : 13;
        requireLength(packet, minLength);
        Float snr = v3 ? ((float) packet[1]) / 4.0f : null;
        byte[] prefix = Arrays.copyOfRange(packet, offset, offset + 6);
        int pathLength = packet[offset + 6] & 0xFF;
        int textType = packet[offset + 7] & 0xFF;
        long timestamp = MeshCoreCompanionFrames.unsignedIntLe(packet, offset + 8);
        int textOffset = offset + 12;
        if (textType == 2) {
            requireLength(packet, textOffset + 4);
            textOffset += 4;
        }
        String text = MeshCoreCompanionFrames.text(packet, textOffset);
        if (text == null) {
            return;
        }
        String signer =
                textType == 2
                        ? MeshCoreCompanionFrames.hex(
                                Arrays.copyOfRange(packet, offset + 12, textOffset))
                        : "";
        String identity =
                "dm:"
                        + MeshCoreCompanionFrames.hex(prefix)
                        + ":"
                        + textType
                        + ":"
                        + signer
                        + ":"
                        + timestamp
                        + ":"
                        + text;
        MeshMessage message =
                state.addIncomingDirectMessage(
                        prefix, text, timestamp, pathLength, snr, incomingId(identity), signer);
        requirePersisted(message);
        storage.saveEnvelope(state.getOwnerId(), message.getDbId(), packet, null);
    }

    /**
     * Parses an incoming MeshCore channel message.
     */
    private void parseChannelMessage(byte[] packet) {
        boolean v3 = (packet[0] & 0xFF) == MeshCoreCompanionFrames.PACKET_CHANNEL_MSG_RECV_V3;
        int offset = v3 ? 4 : 1;
        int minLength = v3 ? 11 : 8;
        requireLength(packet, minLength);
        Float snr = v3 ? ((float) packet[1]) / 4.0f : null;
        int channelIndex = packet[offset] & 0xFF;
        int pathLength = packet[offset + 1] & 0xFF;
        int textType = packet[offset + 2] & 0xFF;
        long timestamp = MeshCoreCompanionFrames.unsignedIntLe(packet, offset + 3);
        int textOffset = offset + 7;
        if (textType == 2) {
            requireLength(packet, textOffset + 4);
            textOffset += 4;
        }
        String text = MeshCoreCompanionFrames.text(packet, textOffset);
        if (text == null) {
            return;
        }
        MeshCoreChannel channel = state.getMeshCoreChannels().get(channelIndex);
        String channelIdentity =
                channel == null
                        ? String.valueOf(channelIndex)
                        : MeshCoreStorage.digest(channel.secret());
        String signer =
                textType == 2
                        ? MeshCoreCompanionFrames.hex(
                                Arrays.copyOfRange(packet, offset + 7, textOffset))
                        : "";
        String identity =
                "channel:"
                        + channelIndex
                        + ":"
                        + channelIdentity
                        + ":"
                        + textType
                        + ":"
                        + signer
                        + ":"
                        + timestamp
                        + ":"
                        + text;
        MeshMessage message =
                state.addIncomingChannelMessage(
                        channelIndex, text, timestamp, pathLength, snr, incomingId(identity));
        requirePersisted(message);
        storage.saveEnvelope(state.getOwnerId(), message.getDbId(), packet, channel);
    }
}
