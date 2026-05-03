package com.meshtastic.client;

import javafx.application.Platform;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class TestEnvironmentSupport {

    // JavaFX toolkit можно поднять только один раз на JVM.
    private static final AtomicBoolean FX_START_REQUESTED = new AtomicBoolean(false);
    private static final CountDownLatch FX_READY = new CountDownLatch(1);

    private TestEnvironmentSupport() {}

    public static void setUserHome(Path userHome) {
        System.setProperty("user.home", userHome.toString());
    }

    public static void ensureJavaFxStarted() {
        configureHeadlessJavaFxIfNeeded();
        if (FX_START_REQUESTED.compareAndSet(false, true)) {
            try {
                Platform.startup(() -> {
                    Platform.setImplicitExit(false);
                    FX_READY.countDown();
                });
            } catch (IllegalStateException e) {
                if (!"Toolkit already initialized".equals(e.getMessage())) {
                    throw e;
                }
                FX_READY.countDown();
            }
        }

        await(FX_READY);
        Platform.setImplicitExit(false);

        // Для повторных вызовов и гонок во время старта достаточно убедиться, что FX event loop жив.
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        await(latch);
    }

    private static void configureHeadlessJavaFxIfNeeded() {
        if (!isLinuxHeadlessEnvironment()) {
            return;
        }

        setIfMissing("java.awt.headless", "true");
        setIfMissing("glass.platform", "Monocle");
        setIfMissing("monocle.platform", "Headless");
        setIfMissing("prism.order", "sw");
        setIfMissing("testfx.robot", "glass");
        setIfMissing("testfx.headless", "true");
    }

    private static boolean isLinuxHeadlessEnvironment() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("linux")
                && isBlank(System.getenv("DISPLAY"))
                && isBlank(System.getenv("WAYLAND_DISPLAY"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void setIfMissing(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    public static void resetSingletons() {
        try {
            // Тесты поднимают реальные singleton-сервисы с H2/threads, поэтому
            // каждый кейс должен стартовать с полностью чистого runtime-состояния.
            Class<?> messageDbService = Class.forName("com.meshtastic.client.service.MessageDbService");
            Object messageDbInstance = readStaticField(messageDbService, "instance");
            if (messageDbInstance != null) {
                messageDbService.getMethod("close").invoke(messageDbInstance);
            }
            writeStaticField(messageDbService, "instance", null);

            Class<?> nodeCacheService = Class.forName("com.meshtastic.client.service.NodeCacheService");
            Object nodeCacheInstance = readStaticField(nodeCacheService, "instance");
            if (nodeCacheInstance != null) {
                nodeCacheService.getMethod("close").invoke(nodeCacheInstance);
            }
            writeStaticField(nodeCacheService, "instance", null);

            Class<?> packetMonitorService = Class.forName("com.meshtastic.client.service.PacketMonitorService");
            Object packetMonitorInstance = readStaticField(packetMonitorService, "instance");
            if (packetMonitorInstance != null) {
                packetMonitorService.getMethod("close").invoke(packetMonitorInstance);
            }
            writeStaticField(packetMonitorService, "instance", null);

            Class<?> databaseProvider = Class.forName("com.meshtastic.client.service.DatabaseProvider");
            databaseProvider.getMethod("close").invoke(null);
            writeStaticField(databaseProvider, "connection", null);

            Class<?> reconnectService = Class.forName("com.meshtastic.client.service.ReconnectService");
            Object reconnectInstance = readStaticField(reconnectService, "instance");
            if (reconnectInstance != null) {
                ExecutorService scheduler = (ExecutorService) readField(reconnectInstance, "scheduler");
                scheduler.shutdownNow();
            }
            writeStaticField(reconnectService, "instance", null);

            Class<?> connectionManager = Class.forName("com.meshtastic.client.service.ConnectionManager");
            Object connectionManagerInstance = readStaticField(connectionManager, "instance");
            if (connectionManagerInstance != null) {
                connectionManager.getMethod("shutdownAll").invoke(connectionManagerInstance);
            }
            writeStaticField(connectionManager, "instance", null);

            Class<?> bleDiscoveryService = Class.forName("com.meshtastic.client.service.BleDeviceDiscoveryService");
            Object bleDiscoveryInstance = readStaticField(bleDiscoveryService, "instance");
            if (bleDiscoveryInstance != null) {
                Object platform = readField(bleDiscoveryInstance, "platform");
                if (platform != null) {
                    Method disposeMethod = platform.getClass().getDeclaredMethod("dispose");
                    disposeMethod.setAccessible(true);
                    disposeMethod.invoke(platform);
                }
            }
            writeStaticField(bleDiscoveryService, "instance", null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to reset test singletons", e);
        }
    }

    private static Object readStaticField(Class<?> type, String fieldName) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void writeStaticField(Class<?> type, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static Object readField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for JavaFX initialization");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for JavaFX initialization", e);
        }
    }
}
