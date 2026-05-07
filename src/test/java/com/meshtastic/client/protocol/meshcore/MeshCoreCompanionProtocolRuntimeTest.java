package com.meshtastic.client.protocol.meshcore;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class MeshCoreCompanionProtocolRuntimeTest {

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void exposesDeviceStateForChatNodesAndDashboard() throws Exception {
        FakeTransport transport = new FakeTransport();
        MeshCoreCompanionProtocolRuntime runtime = runtime(transport);

        runtime.start();
        transport.feed(selfInfo("MeshCore Radio"));
        runtime.getReadyFuture().get();

        transport.feed(channelInfo(0, "Public"));
        transport.feed(contactPacket("Alice Node"));
        transport.feed(new byte[]{(byte) MeshCoreCompanionFrames.PACKET_BATTERY, 0x34, 0x12});
        transport.feed(channelMessage(0, "hello channel"));
        transport.feed(contactMessage("hello dm"));

        DeviceState state = runtime.getState().getDeviceState();
        assertEquals("mc:a0a1a2a3a4a5", state.getOwnerNodeId());
        assertEquals("MeshCore Radio", state.getOwnerInfo().getLongName());
        assertTrue(state.hasEnabledChannel(0));
        assertFalse(state.getMessages(0).isEmpty());
        assertEquals("hello channel", state.getMessages(0).getLast().getText());

        NodeData contact = state.getNodeByNodeId("mc:b0b1b2b3b4b5");
        assertNotNull(contact);
        assertEquals("Alice Node", contact.getLongName());
        assertEquals("hello dm", state.getDirectMessages("mc:b0b1b2b3b4b5").getLast().getText());
        assertFalse(state.getTelemetryHistory().isEmpty());

        runtime.close();
    }

    @Test
    void sendsChannelAndDirectMessagesWithCompanionCommands() throws Exception {
        FakeTransport transport = new FakeTransport();
        MeshCoreCompanionProtocolRuntime runtime = runtime(transport);

        runtime.start();
        transport.feed(selfInfo("MeshCore Radio"));
        runtime.getReadyFuture().get();
        transport.feed(contactPacket("Alice Node"));
        Thread.sleep(1_400);
        transport.sent.clear();

        MeshMessage channel = runtime.sendChannelMessage(0, "channel tx", 0);
        MeshMessage direct = runtime.sendDirectMessage("mc:b0b1b2b3b4b5", "dm tx", 0);

        assertNotNull(channel);
        assertNotNull(direct);
        assertEquals(MeshCoreCompanionFrames.CMD_SEND_CHANNEL_TEXT, transport.sent.get(0)[0] & 0xFF);
        assertEquals(MeshCoreCompanionFrames.CMD_SEND_DIRECT_TEXT, transport.sent.get(1)[0] & 0xFF);
        assertArrayEquals(new byte[]{(byte) 0xB0, (byte) 0xB1, (byte) 0xB2,
                (byte) 0xB3, (byte) 0xB4, (byte) 0xB5},
                java.util.Arrays.copyOfRange(transport.sent.get(1), 7, 13));

        transport.feed(new byte[]{(byte) MeshCoreCompanionFrames.PACKET_MSG_SENT});
        assertEquals(MeshMessage.DeliveryStatus.DELIVERED, channel.getStatus());

        runtime.close();
    }

    private static MeshCoreCompanionProtocolRuntime runtime(FakeTransport transport) {
        ConnectionEntry entry = new ConnectionEntry("meshcore", "127.0.0.1", 4403);
        return new MeshCoreCompanionProtocolRuntime(
                new ProtocolRuntimeContext("meshcore", entry, transport, "test"));
    }

    private static byte[] selfInfo(String name) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[58 + nameBytes.length];
        packet[0] = (byte) MeshCoreCompanionFrames.PACKET_SELF_INFO;
        packet[1] = 1;
        packet[2] = 10;
        packet[3] = 20;
        for (int i = 0; i < 32; i++) {
            packet[4 + i] = (byte) (0xA0 + i);
        }
        packet[48] = (byte) 0x80;
        packet[49] = 0x1A;
        packet[56] = 7;
        packet[57] = 5;
        System.arraycopy(nameBytes, 0, packet, 58, nameBytes.length);
        return packet;
    }

    private static byte[] contactPacket(String name) {
        byte[] packet = new byte[148];
        packet[0] = (byte) MeshCoreCompanionFrames.PACKET_CONTACT;
        for (int i = 0; i < 32; i++) {
            packet[1 + i] = (byte) (0xB0 + i);
        }
        packet[33] = 1;
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, packet, 100, nameBytes.length);
        writeIntLe(packet, 132, 100);
        return packet;
    }

    private static byte[] channelInfo(int channelIndex, String name) {
        byte[] packet = new byte[50];
        packet[0] = (byte) MeshCoreCompanionFrames.PACKET_CHANNEL_INFO;
        packet[1] = (byte) channelIndex;
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, packet, 2, nameBytes.length);
        return packet;
    }

    private static byte[] channelMessage(int channelIndex, String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[8 + textBytes.length];
        packet[0] = (byte) MeshCoreCompanionFrames.PACKET_CHANNEL_MSG_RECV;
        packet[1] = (byte) channelIndex;
        packet[2] = 1;
        packet[3] = 0;
        writeIntLe(packet, 4, 200);
        System.arraycopy(textBytes, 0, packet, 8, textBytes.length);
        return packet;
    }

    private static byte[] contactMessage(String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[13 + textBytes.length];
        packet[0] = (byte) MeshCoreCompanionFrames.PACKET_CONTACT_MSG_RECV;
        for (int i = 0; i < 6; i++) {
            packet[1 + i] = (byte) (0xB0 + i);
        }
        packet[7] = 1;
        packet[8] = 0;
        writeIntLe(packet, 9, 201);
        System.arraycopy(textBytes, 0, packet, 13, textBytes.length);
        return packet;
    }

    private static void writeIntLe(byte[] packet, int offset, long value) {
        packet[offset] = (byte) (value & 0xFF);
        packet[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        packet[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        packet[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static final class FakeTransport implements TransportConnection {
        private final List<byte[]> sent = new ArrayList<>();
        private Consumer<byte[]> listener;

        @Override
        public void connect() throws ConnectionException {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void sendBytes(byte[] data) {
            sent.add(data);
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            this.listener = listener;
        }

        @Override
        public void setConnectionListener(ConnectionListener listener) {
        }

        void feed(byte[] packet) {
            listener.accept(packet);
        }
    }
}
