package com.meshtastic.client.protocol.meshcore;

import static org.junit.jupiter.api.Assertions.*;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.*;
import com.meshtastic.client.model.*;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;
import com.meshtastic.client.service.DatabaseProvider;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

// runtime() registers every session for cleanup in @AfterEach, including failed assertions.
@SuppressWarnings({"resource", "SqlNoDataSourceInspection", "SqlResolve"})
class MeshCoreCompanionProtocolRuntimeTest {
    @TempDir Path home;
    final List<MeshCoreCompanionProtocolRuntime> runtimes = new ArrayList<>();

    @BeforeEach
    void setup() {
        TestEnvironmentSupport.setUserHome(home);
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void cleanup() {
        runtimes.forEach(MeshCoreCompanionProtocolRuntime::close);
        TestEnvironmentSupport.resetSingletons();
    }

    MeshCoreCompanionProtocolRuntime runtime(Radio radio) throws Exception {
        var rt =
                new MeshCoreCompanionProtocolRuntime(
                        new ProtocolRuntimeContext(
                                UUID.randomUUID().toString(),
                                new ConnectionEntry("test", "127.0.0.1", 5000),
                                radio,
                                "test"));
        runtimes.add(rt);
        rt.start().get(2, TimeUnit.SECONDS);
        return rt;
    }

    @Test
    void syncWaitsForCatalogAndUsesDeviceCapacity() throws Exception {
        for (int count : new int[] {1, 8, 40}) {
            Radio radio = new Radio();
            radio.channelCount = count;
            var rt = runtime(radio);
            assertEquals(MeshCoreCompanionState.Phase.READY, rt.getState().getPhase());
            assertEquals(count, rt.getState().getMeshCoreChannels().size());
            assertTrue(rt.getState().getDeviceState().isChannelCatalogReady());
            assertEquals(count, radio.sent.stream().filter(p -> p[0] == 31).count());
            rt.close();
        }
    }

    @Test
    void malformedSelfInfoCannotCompleteHandshake() {
        Radio radio = new Radio();
        radio.auto = false;
        var rt =
                new MeshCoreCompanionProtocolRuntime(
                        new ProtocolRuntimeContext(
                                "bad", new ConnectionEntry("test", "x", 1), radio, "test"));
        runtimes.add(rt);
        rt.start();
        radio.feed(new byte[] {5});
        assertFalse(rt.getReadyFuture().isDone());
        assertNull(rt.getOwnerId());
    }

    @Test
    void ackIsMatchedByHashAndDoesNotAcknowledgeAnotherMessage() throws Exception {
        Radio radio = new Radio();
        var rt = runtime(radio);
        String peer = rt.getState().getContacts().keySet().iterator().next();
        MeshMessage first = rt.sendDirectMessage(peer, "first", 0),
                second = rt.sendDirectMessage(peer, "second", 0);
        assertNotNull(first);
        assertNotNull(second);
        radio.feed(sent(0x12345678));
        assertEquals(MeshMessage.DeliveryStatus.DELIVERED, first.getStatus());
        assertEquals(MeshMessage.DeliveryStatus.SENDING, second.getStatus());
        radio.feed(sent(0x12345679));
        radio.feed(ack(0x12345679));
        assertEquals(MeshMessage.DeliveryStatus.CONFIRMED, second.getStatus());
        assertEquals(MeshMessage.DeliveryStatus.DELIVERED, first.getStatus());
        radio.feed(ack(0x12345679));
        radio.feed(ack(0x12345678));
        assertEquals(MeshMessage.DeliveryStatus.CONFIRMED, first.getStatus());
        assertEquals(2, rt.getState().getDeviceState().getDirectMessages(peer).size());
    }

    @Test
    void errorsFailOnlyCurrentSendAndRetryKeepsSameRow() throws Exception {
        Radio radio = new Radio();
        var rt = runtime(radio);
        MeshMessage a = rt.sendChannelMessage(0, "one", 0), b = rt.sendChannelMessage(0, "two", 0);
        assertNotNull(a);
        assertNotNull(b);
        long row = a.getDbId();
        radio.feed(new byte[] {1, 2});
        assertEquals(MeshMessage.DeliveryStatus.FAILED, a.getStatus());
        assertEquals(MeshMessage.DeliveryStatus.SENDING, b.getStatus());
        radio.feed(new byte[] {0});
        assertEquals(MeshMessage.DeliveryStatus.DELIVERED, b.getStatus());
        assertTrue(rt.retryMessage(a));
        radio.feed(new byte[] {0});
        assertEquals(row, a.getDbId());
        assertEquals(2, rt.getState().getDeviceState().getMessages(0).size());
        assertEquals(2, messageCount());
    }

    @Test
    void receivePersistsRegardlessOfOldSignedHashAndDeduplicatesAcrossReconnect() throws Exception {
        Radio radio = new Radio();
        var rt = runtime(radio);
        byte[] packet = channelText();
        assertTrue(Arrays.hashCode(packet) < 0);
        radio.incoming.add(packet);
        radio.feed(new byte[] {(byte) 0x83});
        await(() -> rt.getState().getDeviceState().getMessages(0).size() == 1);
        MeshMessage message = rt.getState().getDeviceState().getMessages(0).getFirst();
        assertTrue(message.getDbId() > 0);
        assertEquals("Alice", message.getSenderName());
        rt.close();
        Radio fresh = new Radio();
        fresh.incoming.add(packet);
        var again = runtime(fresh);
        await(() -> again.getState().getDeviceState().getMessages(0).size() == 1);
        assertEquals(1, messageCount());
        assertEquals(
                message.getDbId(),
                again.getState().getDeviceState().getMessages(0).getFirst().getDbId());
    }

    @Test
    void newSessionsUseDistinctIdsAndScopedStatusUpdates() throws Exception {
        Radio first = new Radio();
        var r1 = runtime(first);
        MeshMessage a = r1.sendChannelMessage(0, "old", 0);
        first.feed(new byte[] {1, 2});
        r1.close();
        Radio second = new Radio();
        var r2 = runtime(second);
        MeshMessage b = r2.sendChannelMessage(0, "new", 0);
        second.feed(new byte[] {0});
        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a.getPacketId(), b.getPacketId());
        assertNotEquals(a.getDbId(), b.getDbId());
        try (var s =
                DatabaseProvider.getConnection()
                        .prepareStatement("SELECT status FROM messages WHERE id=?")) {
            s.setLong(1, a.getDbId());
            try (var rows = s.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("FAILED", rows.getString(1));
            }
        }
    }

    @Test
    void duplicatePrefixDoesNotOverwriteNodes() throws Exception {
        Radio radio = new Radio();
        var rt = runtime(radio);
        byte[] a = contact(), b = contact();
        Arrays.fill(b, 7, 33, (byte) 0x55);
        b[0] = (byte) 0x8a;
        a[0] = (byte) 0x8a;
        radio.feed(a);
        radio.feed(b);
        String first = "mc:" + HexFormat.of().formatHex(a, 1, 33),
                second = "mc:" + HexFormat.of().formatHex(b, 1, 33);
        assertNotNull(rt.getState().getDeviceState().getNodeByNodeId(first));
        assertNotNull(rt.getState().getDeviceState().getNodeByNodeId(second));
        assertNotEquals(
                rt.getState().getDeviceState().getNodeByNodeId(first).getNodeNum(),
                rt.getState().getDeviceState().getNodeByNodeId(second).getNodeNum());
        assertThrows(
                IllegalStateException.class, () -> rt.sendDirectMessage(first, "ambiguous", 0));
    }

    @Test
    void byteLimitsAndChannelWritesAreValidatedAndReadBack() throws Exception {
        Radio radio = new Radio();
        var rt = runtime(radio);
        String peer = rt.getState().getContacts().keySet().iterator().next();
        assertNull(rt.sendChannelMessage(0, "я".repeat(100), 0));
        assertNull(rt.sendDirectMessage(peer, "a".repeat(161), 0));
        rt.setChannel(
                        0,
                        "Private",
                        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
                .get(1, TimeUnit.SECONDS);
        assertEquals("Private", rt.getState().getMeshCoreChannels().get(0).name());
        rt.setChannel(0, "", new byte[16]).get(1, TimeUnit.SECONDS);
        assertFalse(rt.getState().getMeshCoreChannels().get(0).enabled());
    }

    @Test
    void repliesAreRejectedBeforeSendingOrSaving() throws Exception {
        Radio radio = new Radio();
        var rt = runtime(radio);
        String peer = rt.getState().getContacts().keySet().iterator().next();
        int commandsBefore = radio.sent.size();
        assertThrows(IllegalArgumentException.class, () -> rt.sendChannelMessage(0, "reply", 42));
        assertThrows(IllegalArgumentException.class, () -> rt.sendDirectMessage(peer, "reply", 42));
        assertEquals(commandsBefore, radio.sent.size());
        assertEquals(0, messageCount());
    }

    @Test
    void disconnectFailsPendingAndStopsStateTimers() throws Exception {
        Radio radio = new Radio();
        var rt = runtime(radio);
        MeshMessage message = rt.sendChannelMessage(0, "pending", 0);
        assertNotNull(message);
        rt.close();
        assertEquals(MeshMessage.DeliveryStatus.FAILED, message.getStatus());
        var f = DeviceState.class.getDeclaredField("ackTimeoutExecutor");
        f.setAccessible(true);
        assertTrue(((ExecutorService) f.get(rt.getState().getDeviceState())).isShutdown());
    }

    @Test
    void ackTimeoutUsesFirmwareEstimateAndRetryAttemptSurvivesRestart() throws Exception {
        Radio radio = new Radio();
        MeshCoreTestClock clock = new MeshCoreTestClock();
        var rt =
                new MeshCoreCompanionProtocolRuntime(
                        new ProtocolRuntimeContext(
                                "clock", new ConnectionEntry("test", "x", 1), radio, "test"),
                        clock);
        runtimes.add(rt);
        rt.start().join();
        String peer = rt.getState().getContacts().keySet().iterator().next();
        MeshMessage message = rt.sendDirectMessage(peer, "retry", 0);
        assertNotNull(message);
        byte[] sent = sent(42);
        put(sent, 6, 500);
        radio.feed(sent);
        clock.advance(499);
        assertEquals(MeshMessage.DeliveryStatus.DELIVERED, message.getStatus());
        clock.advance(1);
        assertEquals(MeshMessage.DeliveryStatus.FAILED, message.getStatus());
        rt.close();
        Radio fresh = new Radio();
        var next = runtime(fresh);
        assertTrue(next.retryMessage(message));
        assertEquals(1, fresh.sent.getLast()[2]);
        fresh.feed(sent(43));
        fresh.feed(ack(43));
        assertEquals(2, next.attempts(message.getDbId()).size());
        assertEquals("CONFIRMED", next.attempts(message.getDbId()).getLast().status());
        assertEquals(1, messageCount());
    }

    @Test
    void remoteResponseMatchesFullRequestPrefixAndQueuesOtherCommands() throws Exception {
        Radio radio = new Radio();
        var rt = runtime(radio);
        radio.auto = false;
        String peer = rt.getState().getContacts().keySet().iterator().next();
        var login = rt.login(peer, "secret");
        var status = rt.remoteStatus(peer);
        radio.feed(sent(51));
        byte[] wrong = new byte[8];
        wrong[0] = (byte) 0x86;
        radio.feed(wrong);
        assertFalse(login.isDone());
        byte[] reply = new byte[14];
        reply[0] = (byte) 0x85;
        Arrays.fill(reply, 2, 8, (byte) 0xbb);
        radio.feed(reply);
        assertTrue(login.isDone());
        assertFalse(status.isDone());
        radio.feed(sent(52));
        reply[0] = (byte) 0x87;
        radio.feed(reply);
        assertTrue(status.isDone());
        assertTrue(rt.getState().isReady());
    }

    @Test
    void telemetryAndChannelDatagramsAreRetainedBeforeNextQueueRead() throws Exception {
        Radio radio = new Radio();
        var rt = runtime(radio);
        byte[] telemetry = HexFormat.of().parseHex("8b00aaaaaaaaaaaa0174019f0267ff9c");
        radio.feed(telemetry);
        assertTrue(
                rt.getState().getDeviceState().getTelemetryHistory().stream()
                        .anyMatch(t ->
                                "ENVIRONMENT_METRICS".equals(t.getTelemetryVariant())
                                        && t.getTemperature() == -10f));
        radio.incoming.add(HexFormat.of().parseHex("1b08000000ffffff03aabbcc"));
        radio.feed(new byte[] {(byte) 0x83});
        await(() -> rt.recentEvents().stream().anyMatch(e -> e.type() == 27));
        assertTrue(rt.recentEvents().stream().anyMatch(e -> e.type() == 0x8b));
    }

    @Test
    void contactsUpdateAndDeleteWithoutReconnectAndDeltaDoesNotPrune() throws Exception {
        Radio radio = new Radio();
        var rt = runtime(radio);
        byte[] newContact = contact();
        Arrays.fill(newContact, 1, 33, (byte) 0xcc);
        newContact[0] = (byte) 0x8a;
        radio.feed(newContact);
        assertEquals(2, rt.getState().getContacts().size());
        rt.refreshContactsSince(10).get(1, TimeUnit.SECONDS);
        assertEquals(2, rt.getState().getContacts().size());
        byte[] deleted = new byte[33];
        deleted[0] = (byte) 0x8f;
        Arrays.fill(deleted, 1, 33, (byte) 0xcc);
        radio.feed(deleted);
        assertEquals(1, rt.getState().getContacts().size());
    }

    @Test
    void rpcSnapshotRedactsSecretsAndExplicitExportWorks() throws Exception {
        Radio radio = new Radio();
        var rt = runtime(radio);
        var snapshot = MeshCoreRpc.status(rt);
        assertEquals("Fixture Radio", snapshot.get("model").getAsString());
        assertEquals("test-fw", snapshot.get("firmwareVersion").getAsString());
        assertEquals("fixturebuild", snapshot.get("firmwareBuild").getAsString());
        assertEquals(4660, snapshot.get("batteryMillivolts").getAsInt());
        assertEquals(2, snapshot.get("usedStorageKb").getAsLong());
        assertEquals(64, snapshot.get("totalStorageKb").getAsLong());
        assertEquals(1, snapshot.get("contactCount").getAsInt());
        assertFalse(snapshot.toString().contains("secret"));
        assertFalse(snapshot.toString().contains("pin"));
        assertFalse(snapshot.toString().contains("123456"));
        var request = new com.google.gson.JsonObject();
        request.addProperty("operation", "channel.export");
        request.addProperty("index", 0);
        assertTrue(
                MeshCoreRpc.execute(rt, request)
                        .join()
                        .get("result")
                        .getAsString()
                        .startsWith("meshcore://channel/add?"));
        request.addProperty("operation", "identity.export");
        assertThrows(IllegalArgumentException.class, () -> MeshCoreRpc.execute(rt, request));
    }

    @Test
    void resolvedUnknownSenderMovesItsHistoryAndSignedEnvelopeIsRetained() throws Exception {
        Radio radio = new Radio();
        var rt = runtime(radio);
        byte[] packet = new byte[24];
        packet[0] = 16;
        Arrays.fill(packet, 4, 10, (byte) 0xdd);
        packet[10] = (byte) 255;
        packet[11] = 2;
        packet[12] = 1;
        packet[16] = 1;
        packet[17] = 2;
        packet[18] = 3;
        packet[19] = 4;
        packet[20] = 't';
        packet[21] = 'e';
        packet[22] = 's';
        packet[23] = 't';
        radio.incoming.add(packet);
        radio.feed(new byte[] {(byte) 0x83});
        String pending = "mc:pending:" + "dd".repeat(6);
        await(() -> !rt.getState().getDeviceState().getDirectMessages(pending).isEmpty());
        var message = rt.getState().getDeviceState().getDirectMessages(pending).getFirst();
        assertTrue(message.getSenderName().endsWith("[01020304]"));
        assertEquals(
                HexFormat.of().formatHex(packet),
                rt.messageDetails(message.getDbId()).get("payloadHex").getAsString());
        byte[] contact = contact();
        contact[0] = (byte) 0x8a;
        Arrays.fill(contact, 1, 33, (byte) 0xdd);
        radio.feed(contact);
        assertTrue(rt.getState().getDeviceState().getDirectMessages(pending).isEmpty());
        assertEquals(
                message.getDbId(),
                rt.getState()
                        .getDeviceState()
                        .getDirectMessages("mc:" + "dd".repeat(32))
                        .getFirst()
                        .getDbId());
    }

    @Test
    void retryCannotSendOldTextToAReplacedChannel() throws Exception {
        Radio radio = new Radio();
        var rt = runtime(radio);
        var message = rt.sendChannelMessage(0, "private text", 0);
        assertNotNull(message);
        radio.feed(new byte[] {1, 2});
        rt.setChannel(0, "Replacement", MeshCoreCommands.channelKey("#replacement", null)).join();
        assertFalse(rt.retryMessage(message));
        assertTrue(message.getErrorReason().contains("Channel key changed"));
        assertEquals(1, messageCount());
    }

    int messageCount() throws Exception {
        try (var s = DatabaseProvider.getConnection().createStatement();
                var r = s.executeQuery("SELECT COUNT(*) FROM messages")) {
            r.next();
            return r.getInt(1);
        }
    }

    static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(condition.getAsBoolean());
    }

    static byte[] sent(int hash) {
        byte[] p = {6, 1, 0, 0, 0, 0, 0x60, (byte) 0xea, 0, 0};
        put(p, 2, hash);
        return p;
    }

    static byte[] ack(int hash) {
        byte[] p = new byte[9];
        p[0] = (byte) 0x82;
        put(p, 1, hash);
        return p;
    }

    static void put(byte[] b, int offset, int value) {
        for (int i = 0; i < 4; i++) b[offset + i] = (byte) (value >>> (i * 8));
    }

    static byte[] contact() {
        byte[] b = new byte[148];
        b[0] = 3;
        Arrays.fill(b, 1, 33, (byte) 0xbb);
        b[33] = 1;
        b[35] = (byte) 255;
        b[100] = 'A';
        b[101] = 'l';
        b[102] = 'i';
        b[103] = 'c';
        b[104] = 'e';
        return b;
    }

    static byte[] channelText() {
        byte[] t = "Alice: audit message 10".getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[8 + t.length];
        b[0] = 8;
        b[4] = 1;
        System.arraycopy(t, 0, b, 8, t.length);
        return b;
    }

    static final class Radio implements TransportConnection {
        Consumer<byte[]> listener;
        boolean auto = true;
        int channelCount = 1;
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        Queue<byte[]> incoming = new ConcurrentLinkedQueue<>();
        Map<Integer, byte[]> channelValues = new HashMap<>();

        public void connect() {}

        public void disconnect() {}

        public boolean isConnected() {
            return true;
        }

        public void setDataListener(Consumer<byte[]> l) {
            listener = l;
        }

        public void setConnectionListener(ConnectionListener l) {}

        public void sendBytes(byte[] p) {
            sent.add(p.clone());
            if (!auto) return;
            switch (p[0] & 255) {
                case 1 -> {
                    byte[] b = new byte[63];
                    b[0] = 5;
                    b[1] = 1;
                    b[2] = 10;
                    b[3] = 22;
                    Arrays.fill(b, 4, 36, (byte) 0xaa);
                    b[56] = 7;
                    b[57] = 5;
                    System.arraycopy("Radio".getBytes(StandardCharsets.UTF_8), 0, b, 58, 5);
                    feed(b);
                }
                case 22 -> {
                    byte[] b = new byte[82];
                    b[0] = 13;
                    b[1] = 14;
                    b[2] = 16;
                    b[3] = (byte) channelCount;
                    put(b, 4, 123456);
                    System.arraycopy("fixturebuild".getBytes(StandardCharsets.UTF_8), 0, b, 8, 12);
                    System.arraycopy("Fixture Radio".getBytes(StandardCharsets.UTF_8), 0, b, 20, 13);
                    System.arraycopy("test-fw".getBytes(StandardCharsets.UTF_8), 0, b, 60, 7);
                    feed(b);
                }
                case 4 -> {
                    feed(new byte[] {2, 1, 0, 0, 0});
                    feed(contact());
                    feed(new byte[] {4, 1, 0, 0, 0});
                }
                case 31 -> {
                    int i = p[1] & 255;
                    byte[] b = channelValues.get(i);
                    if (b == null) {
                        b = new byte[50];
                        b[0] = 18;
                        b[1] = (byte) i;
                        b[2] = 'P';
                        b[34] = 1;
                    }
                    feed(b);
                }
                case 32 -> {
                    byte[] b = p.clone();
                    b[0] = 18;
                    channelValues.put(p[1] & 255, b);
                    feed(new byte[] {0});
                }
                case 20 -> feed(new byte[] {12, 0x34, 0x12, 2, 0, 0, 0, 64, 0, 0, 0});
                case 10 -> {
                    byte[] next = incoming.poll();
                    feed(next == null ? new byte[] {10} : next);
                }
                case 2, 3 -> {}
                default -> feed(new byte[] {0});
            }
        }

        void feed(byte[] packet) {
            var l = listener;
            if (l != null) l.accept(packet);
        }
    }
}
