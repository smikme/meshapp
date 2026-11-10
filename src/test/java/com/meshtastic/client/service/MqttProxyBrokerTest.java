package com.meshtastic.client.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.google.protobuf.ByteString;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.utils.AppPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real Paho MQTT handshake against an isolated loopback broker; no mesh/radio traffic. */
class MqttProxyBrokerTest {
    @TempDir Path home;
    private DeviceState state;
    private ProtocolHandler handler;
    private MqttProxyService proxy;
    private final CompletableFuture<MeshProtos.ToRadio> forwarded = new CompletableFuture<>();

    @BeforeEach void setUp() {
        TestEnvironmentSupport.setUserHome(home);
        TestEnvironmentSupport.resetSingletons();
        AppPreferences.setMqttDownlinkFilterMode(AppPreferences.MqttDownlinkFilterMode.NO_FILTER);
        state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        handler = new ProtocolHandler(new TransportConnection() {
            public void connect() {}
            public void disconnect() {}
            public boolean isConnected() { return true; }
            public void sendBytes(byte[] bytes) {
                try {
                    forwarded.complete(MeshProtos.ToRadio.parseFrom(ByteString.copyFrom(bytes, 4, bytes.length - 4)));
                } catch (Exception error) { forwarded.completeExceptionally(error); }
            }
            public void setDataListener(Consumer<byte[]> listener) {}
            public void setConnectionListener(ConnectionListener listener) {}
        });
    }

    @AfterEach void tearDown() {
        if (proxy != null) proxy.close();
        handler.shutdown();
        state.shutdown();
        TestEnvironmentSupport.resetSingletons();
    }

    @Test void initialConnectionFailureRetriesWhenBrokerBecomesAvailable() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = reservation.getLocalPort();
        }
        var failed = new CountDownLatch(1);
        var logger = (Logger) LoggerFactory.getLogger(MqttProxyService.class);
        var appender = new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().startsWith("Initial MQTT proxy connect failed")) failed.countDown();
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            startProxy(port);
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            try (var broker = new Broker(port, false)) {
                assertForwarded();
                assertEquals(1, broker.subscriptions);
                proxy.close();
                broker.finished.get(5, TimeUnit.SECONDS);
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test void rejectedSubscriptionIsRetriedWithoutPretendingItSucceeded() throws Exception {
        try (var broker = new Broker(0, true)) {
            startProxy(broker.server.getLocalPort());
            assertForwarded();
            assertEquals(2, broker.subscriptions);
            proxy.close();
            broker.finished.get(5, TimeUnit.SECONDS);
        }
    }

    private void startProxy(int port) {
        state.addModuleConfig(ModuleConfigProtos.ModuleConfig.newBuilder().setMqtt(
                ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder().setEnabled(true)
                        .setProxyToClientEnabled(true).setAddress("127.0.0.1:" + port).setRoot("msh/test")).build());
        proxy = new MqttProxyService("mqtt-loopback-test", "MQTT Loopback Test", handler, state);
        proxy.startIfEnabled();
    }

    private void assertForwarded() throws Exception {
        var packet = forwarded.get(12, TimeUnit.SECONDS).getMqttClientProxyMessage();
        assertEquals("msh/test/message", packet.getTopic());
        assertEquals(ByteString.copyFromUtf8("loopback fixture"), packet.getData());
    }

    private static class Broker implements AutoCloseable {
        final ServerSocket server;
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        volatile int subscriptions;
        volatile Socket socket;

        Broker(int port, boolean rejectFirstSubscription) throws Exception {
            server = new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
            Thread.startVirtualThread(() -> {
                try (Socket accepted = server.accept()) {
                    socket = accepted;
                    accepted.setSoTimeout(15_000);
                    var in = new DataInputStream(accepted.getInputStream());
                    var out = new DataOutputStream(accepted.getOutputStream());
                    assertEquals(1, readPacket(in).type);
                    out.write(new byte[] {0x20, 2, 0, 0}); // CONNACK accepted
                    out.flush();
                    while (true) {
                        Packet packet = readPacket(in);
                        if (packet.type == 14) break; // DISCONNECT
                        if (packet.type == 12) { out.write(new byte[] {(byte) 0xd0, 0}); out.flush(); continue; }
                        assertEquals(8, packet.type); // SUBSCRIBE
                        subscriptions++;
                        boolean rejected = rejectFirstSubscription && subscriptions == 1;
                        out.write(new byte[] {(byte) 0x90, 3, packet.payload[0], packet.payload[1],
                                (byte) (rejected ? 0x80 : 0)});
                        out.flush();
                        if (!rejected) {
                            var bytes = new java.io.ByteArrayOutputStream();
                            var body = new DataOutputStream(bytes);
                            body.writeUTF("msh/test/message");
                            body.write("loopback fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            out.writeByte(0x30); // PUBLISH, QoS 0
                            out.writeByte(bytes.size());
                            out.write(bytes.toByteArray());
                            out.flush();
                        }
                    }
                    finished.complete(null);
                } catch (Throwable error) { finished.completeExceptionally(error); }
            });
        }

        private static Packet readPacket(DataInputStream in) throws Exception {
            int type = in.readUnsignedByte() >> 4;
            int length = 0, multiplier = 1, digit;
            do {
                digit = in.readUnsignedByte();
                length += (digit & 127) * multiplier;
                multiplier *= 128;
                if (multiplier > 268435456) throw new IllegalArgumentException("invalid MQTT length");
            } while ((digit & 128) != 0);
            return new Packet(type, in.readNBytes(length));
        }
        private record Packet(int type, byte[] payload) {}

        @Override public void close() throws Exception {
            server.close();
            if (socket != null) socket.close();
        }
    }
}
