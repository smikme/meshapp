package com.meshtastic.client;

import javafx.application.Platform;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TestEnvironmentSupport {

    // JavaFX toolkit можно поднять только один раз на JVM.
    private static final AtomicBoolean FX_STARTED = new AtomicBoolean(false);

    private TestEnvironmentSupport() {}

    public static void setUserHome(Path userHome) {
        System.setProperty("user.home", userHome.toString());
    }

    public static void ensureJavaFxStarted() {
        if (FX_STARTED.compareAndSet(false, true)) {
            CountDownLatch latch = new CountDownLatch(1);
            try {
                Platform.startup(latch::countDown);
                await(latch);
            } catch (IllegalStateException e) {
                if (!"Toolkit already initialized".equals(e.getMessage())) {
                    throw e;
                }
            }
            return;
        }

        // Для повторных вызовов достаточно убедиться, что FX event loop жив.
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        await(latch);
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
