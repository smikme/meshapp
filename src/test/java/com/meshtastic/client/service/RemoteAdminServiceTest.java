package com.meshtastic.client.service;

import com.google.protobuf.ByteString;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteAdminServiceTest {

    @TempDir
    Path tempHome;

    private final List<ProtocolHandler> handlersToShutdown = new ArrayList<>();
    private final List<DeviceState> statesToShutdown = new ArrayList<>();
    private final List<RemoteAdminService> servicesToClose = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        MessageDbService.getInstance();
    }

    @AfterEach
    void tearDown() {
        for (RemoteAdminService service : servicesToClose) {
            service.close();
        }
        for (ProtocolHandler handler : handlersToShutdown) {
            handler.shutdown();
        }
        for (DeviceState state : statesToShutdown) {
            state.shutdown();
        }
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void loadSnapshotSendsAdminPacketToRemoteNodeOverPki() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState localState = stateWithLocalNode(0x11111111);
        NodeData remoteNode = remoteNode(0x22222222);
        RemoteAdminService service = track(new RemoteAdminService(handler, localState, remoteNode));

        CompletableFuture<RemoteAdminSession> future = service.loadSnapshot();

        MeshProtos.ToRadio sent = connection.awaitToRadioAt(0);
        MeshProtos.MeshPacket packet = sent.getPacket();
        assertEquals(0x11111111, packet.getFrom());
        assertEquals(0x22222222, packet.getTo());
        assertTrue(packet.getWantAck());
        assertTrue(packet.getPkiEncrypted());
        assertEquals(Portnums.PortNum.ADMIN_APP, packet.getDecoded().getPortnum());
        assertTrue(packet.getDecoded().getWantResponse());

        future.cancel(true);
    }

    @Test
    void loadSnapshotEmitsProgressForRequestedBlocks() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState localState = stateWithLocalNode(0x11111111);
        NodeData remoteNode = remoteNode(0x22222222);
        RemoteAdminService service = track(new RemoteAdminService(handler, localState, remoteNode));
        List<RemoteAdminService.QueryProgress> progress = new CopyOnWriteArrayList<>();

        CompletableFuture<RemoteAdminSession> future = service.loadSnapshot(progress::add);
        connection.awaitToRadioAt(0);

        assertFalse(progress.isEmpty());
        RemoteAdminService.QueryProgress first = progress.get(0);
        assertEquals("get_device_metadata", first.key());
        assertEquals(RemoteAdminSession.QueryState.SENT, first.state());
        assertTrue(first.total() > RemoteAdminService.editableConfigTypes().size());

        future.cancel(true);
    }

    @Test
    void requestConfigSectionSendsSingleRemoteQueryAndStoresResponse() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState localState = stateWithLocalNode(0x11111111);
        NodeData remoteNode = remoteNode(0x22222222);
        RemoteAdminService service = track(new RemoteAdminService(handler, localState, remoteNode));

        CompletableFuture<Void> future = service.requestConfigSection(
                AdminProtos.AdminMessage.ConfigType.POWER_CONFIG);

        MeshProtos.ToRadio sent = connection.awaitToRadioAt(0);
        MeshProtos.MeshPacket packet = sent.getPacket();
        AdminProtos.AdminMessage request =
                AdminProtos.AdminMessage.parseFrom(packet.getDecoded().getPayload());
        assertTrue(request.hasGetConfigRequest());
        assertEquals(AdminProtos.AdminMessage.ConfigType.POWER_CONFIG, request.getGetConfigRequest());
        assertTrue(packet.getPkiEncrypted());
        assertTrue(packet.getDecoded().getWantResponse());

        AdminProtos.AdminMessage response = AdminProtos.AdminMessage.newBuilder()
                .setGetConfigResponse(ConfigProtos.Config.newBuilder()
                        .setPower(ConfigProtos.Config.PowerConfig.newBuilder()))
                .build();
        service.onMeshPacket(remoteAdminResponse(
                0x22222222,
                0x11111111,
                packet.getId(),
                response));

        future.get(1, TimeUnit.SECONDS);
        assertEquals(1, service.session().remoteState().getConfigs().size());
        assertEquals(RemoteAdminSession.QueryState.RECEIVED,
                service.session().queryStatuses().stream()
                        .filter(status -> status.key().equals("get_config/POWER_CONFIG"))
                        .findFirst()
                        .orElseThrow()
                        .state());
    }

    @Test
    void editableConfigTypesTrackGeneratedAdminEnum() {
        List<AdminProtos.AdminMessage.ConfigType> expected = Arrays.stream(
                        AdminProtos.AdminMessage.ConfigType.values())
                .filter(type -> type != AdminProtos.AdminMessage.ConfigType.UNRECOGNIZED)
                .filter(type -> type != AdminProtos.AdminMessage.ConfigType.SESSIONKEY_CONFIG)
                .toList();

        assertEquals(expected, RemoteAdminService.editableConfigTypes());
    }

    @Test
    void editableModuleConfigTypesTrackGeneratedAdminEnum() {
        List<AdminProtos.AdminMessage.ModuleConfigType> expected = Arrays.stream(
                        AdminProtos.AdminMessage.ModuleConfigType.values())
                .filter(type -> type != AdminProtos.AdminMessage.ModuleConfigType.UNRECOGNIZED)
                .toList();

        assertEquals(expected, RemoteAdminService.editableModuleConfigTypes());
    }

    @Test
    void saveOwnerUsesRemoteSessionPasskeyAndKeepsLocalStateUntouched() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState localState = stateWithLocalNode(0x11111111);
        NodeData remoteNode = remoteNode(0x22222222);
        RemoteAdminService service = track(new RemoteAdminService(handler, localState, remoteNode));

        CompletableFuture<Void> saveFuture = service.saveOwner("Remote Long", "RMT", true);
        MeshProtos.ToRadio sessionRequest = connection.awaitToRadioAt(0);
        int sessionPacketId = sessionRequest.getPacket().getId();

        AdminProtos.AdminMessage sessionResponse = AdminProtos.AdminMessage.newBuilder()
                .setSessionPasskey(ByteString.copyFromUtf8("remote-passkey"))
                .build();
        service.onMeshPacket(remoteAdminResponse(
                0x22222222,
                0x11111111,
                sessionPacketId,
                sessionResponse));

        MeshProtos.ToRadio setOwner = connection.awaitToRadioAt(1);
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(setOwner.getPacket().getDecoded().getPayload());
        assertTrue(admin.hasSetOwner());
        assertEquals("remote-passkey", admin.getSessionPasskey().toStringUtf8());
        assertEquals("Remote Long", admin.getSetOwner().getLongName());
        assertEquals("RMT", admin.getSetOwner().getShortName());
        assertEquals(0x22222222, setOwner.getPacket().getTo());
        assertTrue(setOwner.getPacket().getPkiEncrypted());

        assertTrue(localState.completePendingPacketAck(
                setOwner.getPacket().getId(),
                MeshProtos.Routing.Error.NONE));
        saveFuture.get(1, TimeUnit.SECONDS);

        assertNull(localState.getSessionPasskey());
        assertNull(localState.getOwnerInfo());
        assertEquals("Remote Long", service.session().remoteState().getOwnerInfo().getLongName());
    }

    @Test
    void rebootUsesCachedRemoteSessionPasskey() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState localState = stateWithLocalNode(0x11111111);
        NodeData remoteNode = remoteNode(0x22222222);
        RemoteAdminService service = track(new RemoteAdminService(handler, localState, remoteNode));
        cacheRemotePasskey(service);

        CompletableFuture<Void> rebootFuture = service.reboot(10);

        MeshProtos.ToRadio sent = connection.awaitToRadioAt(0);
        MeshProtos.MeshPacket packet = sent.getPacket();
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(packet.getDecoded().getPayload());
        assertTrue(admin.hasRebootSeconds());
        assertEquals(10, admin.getRebootSeconds());
        assertEquals("remote-passkey", admin.getSessionPasskey().toStringUtf8());
        assertEquals(0x11111111, packet.getFrom());
        assertEquals(0x22222222, packet.getTo());
        assertTrue(packet.getPkiEncrypted());
        assertFalse(packet.getDecoded().getWantResponse());

        assertTrue(localState.completePendingPacketAck(packet.getId(), MeshProtos.Routing.Error.NONE));
        rebootFuture.get(1, TimeUnit.SECONDS);
    }

    @Test
    void backupAndFactoryResetCommandsUseExpectedPayloads() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState localState = stateWithLocalNode(0x11111111);
        NodeData remoteNode = remoteNode(0x22222222);
        RemoteAdminService service = track(new RemoteAdminService(handler, localState, remoteNode));
        cacheRemotePasskey(service);

        CompletableFuture<Void> backupFuture = service.backupPreferences(
                AdminProtos.AdminMessage.BackupLocation.SD);
        MeshProtos.ToRadio backup = connection.awaitToRadioAt(0);
        AdminProtos.AdminMessage backupAdmin =
                AdminProtos.AdminMessage.parseFrom(backup.getPacket().getDecoded().getPayload());
        assertTrue(backupAdmin.hasBackupPreferences());
        assertEquals(AdminProtos.AdminMessage.BackupLocation.SD, backupAdmin.getBackupPreferences());
        assertEquals("remote-passkey", backupAdmin.getSessionPasskey().toStringUtf8());
        assertTrue(localState.completePendingPacketAck(
                backup.getPacket().getId(),
                MeshProtos.Routing.Error.NONE));
        backupFuture.get(1, TimeUnit.SECONDS);

        CompletableFuture<Void> resetFuture = service.factoryResetConfig();
        MeshProtos.ToRadio reset = connection.awaitToRadioAt(1);
        AdminProtos.AdminMessage resetAdmin =
                AdminProtos.AdminMessage.parseFrom(reset.getPacket().getDecoded().getPayload());
        assertTrue(resetAdmin.hasFactoryResetConfig());
        assertEquals(1, resetAdmin.getFactoryResetConfig());
        assertEquals("remote-passkey", resetAdmin.getSessionPasskey().toStringUtf8());
        assertTrue(localState.completePendingPacketAck(
                reset.getPacket().getId(),
                MeshProtos.Routing.Error.NONE));
        resetFuture.get(1, TimeUnit.SECONDS);
    }

    @Test
    void localMessageListenerIgnoresRemoteAdminOwnerResponse() {
        DeviceState localState = stateWithLocalNode(0x11111111);
        MessageListenerService listener = new MessageListenerService(localState);
        AdminProtos.AdminMessage admin = AdminProtos.AdminMessage.newBuilder()
                .setSessionPasskey(ByteString.copyFromUtf8("remote-passkey"))
                .setGetOwnerResponse(MeshProtos.User.newBuilder()
                        .setLongName("Remote Long")
                        .setShortName("RMT")
                        .build())
                .build();

        listener.onMeshPacket(remoteAdminResponse(0x22222222, 0x11111111, 123, admin));

        assertNull(localState.getSessionPasskey());
        assertNull(localState.getOwnerInfo());
    }

    private static void cacheRemotePasskey(RemoteAdminService service) {
        service.session().remoteState().setSessionPasskey(ByteString.copyFromUtf8("remote-passkey"));
        service.session().markSessionPasskeyReceived();
    }

    private ProtocolHandler track(ProtocolHandler handler) {
        handlersToShutdown.add(handler);
        return handler;
    }

    private RemoteAdminService track(RemoteAdminService service) {
        servicesToClose.add(service);
        return service;
    }

    private DeviceState stateWithLocalNode(int nodeNum) {
        DeviceState state = new DeviceState();
        statesToShutdown.add(state);
        state.setMyNodeNum(nodeNum);
        state.getOrCreateNode(nodeNum).setLongName("Local");
        return state;
    }

    private static NodeData remoteNode(int nodeNum) {
        NodeData node = new NodeData(nodeNum);
        node.setLongName("Remote");
        node.setShortName("RMT");
        node.setPublicKey(new byte[] {1, 2, 3});
        return node;
    }

    private static MeshProtos.MeshPacket remoteAdminResponse(int from,
                                                             int to,
                                                             int requestId,
                                                             AdminProtos.AdminMessage adminMessage) {
        MeshProtos.Data data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.ADMIN_APP)
                .setPayload(adminMessage.toByteString())
                .setRequestId(requestId)
                .build();
        return MeshProtos.MeshPacket.newBuilder()
                .setFrom(from)
                .setTo(to)
                .setDecoded(data)
                .build();
    }

    private static MeshProtos.ToRadio parseToRadio(byte[] frame) throws Exception {
        int payloadLength = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        byte[] payload = new byte[payloadLength];
        System.arraycopy(frame, 4, payload, 0, payloadLength);
        return MeshProtos.ToRadio.parseFrom(payload);
    }

    private static final class RecordingConnection implements MeshtasticConnection {
        private final List<byte[]> sentFrames = new ArrayList<>();

        @Override
        public void connect() throws ConnectionException {
            // no-op
        }

        @Override
        public void disconnect() {
            // no-op
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void sendBytes(byte[] data) {
            synchronized (sentFrames) {
                sentFrames.add(data);
                sentFrames.notifyAll();
            }
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            // no-op
        }

        @Override
        public void setConnectionListener(ConnectionListener listener) {
            // no-op
        }

        MeshProtos.ToRadio awaitToRadioAt(int index) throws Exception {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            synchronized (sentFrames) {
                while (sentFrames.size() <= index && System.nanoTime() < deadlineNanos) {
                    long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                    sentFrames.wait(Math.max(1, remainingMillis));
                }
                if (sentFrames.size() <= index) {
                    throw new AssertionError("Timed out waiting for outbound frame " + index);
                }
                return parseToRadio(sentFrames.get(index));
            }
        }
    }
}
