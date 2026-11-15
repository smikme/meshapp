package com.meshtastic.client.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class FirmwareOtaTcpConnectionTest {
    @Test
    void preservesFragmentsAcrossWaitsAndBuffersFollowingLines() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             SocketChannel channel = SocketChannel.open(); Selector selector = Selector.open()) {
            var connection = new FirmwareOtaTcpUploader.OtaConnection(channel, selector);
            connection.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
            assertFalse(channel.isBlocking());
            try (Socket peer = server.accept()) {
                peer.getOutputStream().write("ERAS".getBytes(StandardCharsets.UTF_8));
                FutureTask<List<String>> replies = new FutureTask<>(() -> {
                    long deadline = connection.deadlineAfter(5000);
                    return List.of(connection.readLine(deadline), connection.readLine(deadline));
                });
                Thread reader = new Thread(replies, "ota-fragment-test");
                reader.setDaemon(true);
                reader.start();
                try {
                    // Cross more than one selector wait while keeping a partial response.
                    Thread.sleep(250);
                    peer.getOutputStream().write("ING\r\nOK\n".getBytes(StandardCharsets.UTF_8));
                    assertEquals(List.of("ERASING", "OK"), replies.get(5, TimeUnit.SECONDS));
                } finally {
                    reader.interrupt();
                    reader.join(5000);
                }
                assertFalse(reader.isAlive());
            }
        }
    }

    @Test
    void partialResponseCannotExtendTheDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             SocketChannel channel = SocketChannel.open(); Selector selector = Selector.open()) {
            var connection = new FirmwareOtaTcpUploader.OtaConnection(channel, selector);
            connection.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
            try (Socket peer = server.accept()) {
                peer.getOutputStream().write("ERAS".getBytes(StandardCharsets.UTF_8));
                assertThrows(SocketTimeoutException.class,
                        () -> connection.readLine(connection.deadlineAfter(150)));
            }
        }
    }

    @Test
    void incompleteAcknowledgementAtEofIsRejected() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             SocketChannel channel = SocketChannel.open(); Selector selector = Selector.open()) {
            var connection = new FirmwareOtaTcpUploader.OtaConnection(channel, selector);
            connection.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
            try (Socket peer = server.accept()) {
                peer.getOutputStream().write("OK".getBytes(StandardCharsets.UTF_8));
                peer.shutdownOutput();
                IOException error = assertThrows(IOException.class,
                        () -> connection.readLine(connection.deadlineAfter(5000)));
                assertFalse(error instanceof SocketTimeoutException);
            }
        }
    }

    @Test
    void cancelsWriteWhenThePeerStopsReceiving() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             SocketChannel channel = SocketChannel.open(); Selector selector = Selector.open()) {
            server.setReceiveBufferSize(1024);
            channel.setOption(StandardSocketOptions.SO_SNDBUF, 1024);
            var connection = new FirmwareOtaTcpUploader.OtaConnection(channel, selector);
            connection.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(5000);
                ByteBuffer payload = ByteBuffer.allocate(8 * 1024 * 1024);
                FutureTask<Void> write = new FutureTask<>(() -> {
                    connection.write(payload);
                    return null;
                });
                Thread writer = new Thread(write, "ota-backpressure-test");
                writer.setDaemon(true);
                writer.start();
                try {
                    assertEquals(0, peer.getInputStream().read());
                    // Do not drain the rest: the payload exceeds the small socket buffers.
                    assertFalse(write.isDone());
                    writer.interrupt();
                    ExecutionException error = assertThrows(ExecutionException.class,
                            () -> write.get(5, TimeUnit.SECONDS));
                    assertInstanceOf(ClosedByInterruptException.class, error.getCause());
                    assertTrue(payload.hasRemaining());
                } finally {
                    writer.interrupt();
                    writer.join(5000);
                }
                assertFalse(writer.isAlive());
            }
        }
    }
}
