package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ble.BleDevice;
import com.meshtastic.client.connection.ble.BlePlatform;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.connection.ble.BleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BleDeviceDiscoveryServiceTest {

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
    void startScanningReturnsBeforePlatformFactoryCompletes() throws Exception {
        BleDeviceDiscoveryService discovery = BleDeviceDiscoveryService.getInstance();
        CountDownLatch factoryStarted = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch failurePublished = new CountDownLatch(1);

        discovery.setParallelConnectionSupportForTests(true);
        discovery.setPlatformFactoryForTests(() -> {
            factoryStarted.countDown();
            awaitUnchecked(releaseFactory);
            throw new UnsupportedOperationException("BlueZ init timed out");
        });
        discovery.addListener(devices -> {
            if (!discovery.isScanning()
                    && "BlueZ init timed out".equals(discovery.getLastErrorMessage())) {
                failurePublished.countDown();
            }
        });

        long startedAt = System.nanoTime();
        discovery.startScanning();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(elapsedMs < 200, "startScanning should not block on native BLE initialization");
        assertTrue(discovery.isScanning());
        assertTrue(factoryStarted.await(1, TimeUnit.SECONDS));

        releaseFactory.countDown();

        assertTrue(failurePublished.await(1, TimeUnit.SECONDS));
        assertFalse(discovery.isScanning());
        assertEquals("BlueZ init timed out", discovery.getLastErrorMessage());
    }

    @Test
    void stopScanningBeforeDelayedPlatformFactoryReturnsDoesNotStartScan() throws Exception {
        BleDeviceDiscoveryService discovery = BleDeviceDiscoveryService.getInstance();
        CountDownLatch factoryStarted = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        RecordingBlePlatform platform = new RecordingBlePlatform();

        discovery.setParallelConnectionSupportForTests(true);
        discovery.setPlatformFactoryForTests(() -> {
            factoryStarted.countDown();
            awaitUnchecked(releaseFactory);
            return platform;
        });

        discovery.startScanning();
        assertTrue(factoryStarted.await(1, TimeUnit.SECONDS));

        discovery.stopScanning();
        releaseFactory.countDown();

        assertFalse(platform.startScanCalled.await(300, TimeUnit.MILLISECONDS));
        assertFalse(discovery.isScanning());
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class RecordingBlePlatform implements BlePlatform {
        private final CountDownLatch startScanCalled = new CountDownLatch(1);

        @Override
        public void startScan(Consumer<BleDevice> onDeviceFound) {
            startScanCalled.countDown();
        }

        @Override
        public void stopScan() {}

        @Override
        public void connect(String address) throws ConnectionException {}

        @Override
        public void setProfile(BleProtocolProfile profile) {}

        @Override
        public void disconnect() {}

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public boolean writeToRadio(byte[] protobufPayload) {
            return false;
        }

        @Override
        public void setFromRadioListener(Consumer<byte[]> listener) {}

        @Override
        public void setStateListener(Consumer<BleState> listener) {}

        @Override
        public AdapterState getAdapterState() {
            return AdapterState.POWERED_ON;
        }

        @Override
        public void dispose() {}
    }
}
