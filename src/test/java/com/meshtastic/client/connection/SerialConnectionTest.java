package com.meshtastic.client.connection;

import com.meshtastic.client.connection.serial.NativeSerialPort;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerialConnectionTest {

    @Test
    void noIncomingBytesAfterWriteTriggersConnectionError() throws Exception {
        FakeSerialPort port = new FakeSerialPort();
        CountDownLatch errorLatch = new CountDownLatch(1);
        SerialConnection connection = new SerialConnection(
                "COM3", 115200, () -> port, System::currentTimeMillis, 5, 0, 40);
        connection.setConnectionListener(new TestConnectionListener(errorLatch));

        connection.connect();
        connection.sendBytes(new byte[]{0x01, 0x02});

        assertTrue(errorLatch.await(1, TimeUnit.SECONDS));
        assertFalse(connection.isConnected());
        assertTrue(port.awaitClose());
    }

    @Test
    void idleSilenceWithoutPendingWriteDoesNotTriggerConnectionError() throws Exception {
        FakeSerialPort port = new FakeSerialPort();
        CountDownLatch errorLatch = new CountDownLatch(1);
        SerialConnection connection = new SerialConnection(
                "COM3", 115200, () -> port, System::currentTimeMillis, 5, 0, 40);
        connection.setConnectionListener(new TestConnectionListener(errorLatch));

        connection.connect();

        Thread.sleep(120);

        assertFalse(errorLatch.await(50, TimeUnit.MILLISECONDS));

        connection.disconnect();
        assertTrue(port.awaitClose());
    }

    @Test
    void incomingBytesAfterWriteClearStallDetector() throws Exception {
        FakeSerialPort port = new FakeSerialPort();
        CountDownLatch errorLatch = new CountDownLatch(1);
        SerialConnection connection = new SerialConnection(
                "COM3", 115200, () -> port, System::currentTimeMillis, 5, 0, 80);
        connection.setConnectionListener(new TestConnectionListener(errorLatch));

        connection.connect();
        connection.sendBytes(new byte[]{0x01, 0x02});
        port.enqueueIncoming((byte) 0x55);

        Thread.sleep(140);

        assertFalse(errorLatch.await(50, TimeUnit.MILLISECONDS));

        connection.disconnect();
        assertTrue(port.awaitClose());
    }

    @Test
    void blockedReadCallTriggersConnectionErrorWithoutWrite() throws Exception {
        BlockingReadSerialPort port = new BlockingReadSerialPort();
        CountDownLatch errorLatch = new CountDownLatch(1);
        SerialConnection connection = new SerialConnection(
                "COM3", 115200, () -> port, System::currentTimeMillis, 5, 0, 80, 40, 10);
        connection.setConnectionListener(new TestConnectionListener(errorLatch));

        connection.connect();

        assertTrue(errorLatch.await(1, TimeUnit.SECONDS));
        assertFalse(connection.isConnected());
        assertTrue(port.awaitClose());
    }

    private static final class TestConnectionListener implements ConnectionListener {
        private final CountDownLatch errorLatch;

        private TestConnectionListener(CountDownLatch errorLatch) {
            this.errorLatch = errorLatch;
        }

        @Override
        public void onConnected() {
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onConnectionError(String message, Throwable cause) {
            errorLatch.countDown();
        }
    }

    private static final class FakeSerialPort implements NativeSerialPort {
        private final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private volatile boolean open;

        @Override
        public void open(String portName, int baudRate, boolean assertDtr) {
            open = true;
        }

        @Override
        public int read(byte[] buf, int len, int timeoutMs) {
            if (!open) {
                return -1;
            }

            byte[] chunk = incoming.poll();
            if (chunk == null) {
                try {
                    Thread.sleep(Math.max(1, timeoutMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                return open ? 0 : -1;
            }

            int copyLen = Math.min(len, chunk.length);
            System.arraycopy(chunk, 0, buf, 0, copyLen);
            if (copyLen < chunk.length) {
                incoming.offer(Arrays.copyOfRange(chunk, copyLen, chunk.length));
            }
            return copyLen;
        }

        @Override
        public void write(byte[] data, int offset, int len) {
        }

        @Override
        public void drainInput() {
            incoming.clear();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
            closedLatch.countDown();
        }

        void enqueueIncoming(byte... chunk) {
            incoming.offer(Arrays.copyOf(chunk, chunk.length));
        }

        boolean awaitClose() throws InterruptedException {
            return closedLatch.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class BlockingReadSerialPort implements NativeSerialPort {
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private volatile boolean open;

        @Override
        public void open(String portName, int baudRate, boolean assertDtr) {
            open = true;
        }

        @Override
        public int read(byte[] buf, int len, int timeoutMs) {
            try {
                closedLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }

        @Override
        public void write(byte[] data, int offset, int len) {
        }

        @Override
        public void drainInput() {
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
            closedLatch.countDown();
        }

        boolean awaitClose() throws InterruptedException {
            return closedLatch.await(1, TimeUnit.SECONDS);
        }
    }
}
