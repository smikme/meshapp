package com.meshtastic.client.connection;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpConnectionTest {

    @Test
    void disconnectDoesNotWaitForFullJoinTimeoutWhenReaderIgnoresInterrupt() throws Exception {
        TcpConnection connection = new TcpConnection("127.0.0.1");
        CountDownLatch disconnectNotified = new CountDownLatch(1);
        connection.setConnectionListener(new ConnectionListener() {
            @Override
            public void onDisconnected() {
                disconnectNotified.countDown();
            }

            @Override
            public void onConnectionError(String message, Throwable cause) {
            }

            @Override
            public void onConnected() {
            }
        });

        CountDownLatch releaseReader = new CountDownLatch(1);
        Thread stuckReader = new Thread(() -> {
            while (releaseReader.getCount() > 0) {
                try {
                    releaseReader.await();
                } catch (InterruptedException ignored) {
                    // Simulate a reader that keeps blocking even after interrupt().
                }
            }
        }, "tcp-reader-test");
        stuckReader.setDaemon(true);
        stuckReader.start();

        setField(connection, "readerThread", stuckReader);
        setField(connection, "running", true);

        long startedAt = System.nanoTime();
        connection.disconnect();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(elapsedMs < 1_200, "disconnect should not wait for the full reader join timeout");
        assertTrue(disconnectNotified.await(100, TimeUnit.MILLISECONDS),
                "disconnect should still notify the connection listener");

        releaseReader.countDown();
        stuckReader.join(1_000);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
