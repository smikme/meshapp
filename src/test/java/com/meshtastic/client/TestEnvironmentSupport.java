package com.meshtastic.client;

import javafx.application.Platform;

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

    // The JavaFX toolkit can be started only once per JVM.
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

        // Repeated calls and startup races only need to prove that the FX event loop is alive.
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
            resetDiagnostics();

            // Tests start real singleton services backed by H2 and background threads, so
            // every case must begin from a clean runtime state.
            Class<?> luaRuntimeService = Class.forName("com.meshtastic.client.lua.LuaScriptRuntimeService");
            Object luaRuntimeInstance = readStaticField(luaRuntimeService, "instance");
            if (luaRuntimeInstance != null) {
                luaRuntimeService.getMethod("stopAll").invoke(luaRuntimeInstance);
            }
            writeStaticField(luaRuntimeService, "instance", null);

            Class<?> luaScriptService = Class.forName("com.meshtastic.client.lua.LuaScriptService");
            writeStaticField(luaScriptService, "instance", null);

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

            Class<?> favoriteNodeService = Class.forName("com.meshtastic.client.service.FavoriteNodeService");
            writeStaticField(favoriteNodeService, "instance", null);

            Class<?> ignoredNodeService = Class.forName("com.meshtastic.client.service.IgnoredNodeService");
            writeStaticField(ignoredNodeService, "instance", null);

            Class<?> packetMonitorService = Class.forName("com.meshtastic.client.service.PacketMonitorService");
            Object packetMonitorInstance = readStaticField(packetMonitorService, "instance");
            if (packetMonitorInstance != null) {
                packetMonitorService.getMethod("close").invoke(packetMonitorInstance);
            }
            writeStaticField(packetMonitorService, "instance", null);

            Class<?> remoteRpcHostService = Class.forName("com.meshtastic.client.service.RemoteRpcHostService");
            Object remoteRpcHostInstance = readStaticField(remoteRpcHostService, "instance");
            if (remoteRpcHostInstance != null) {
                remoteRpcHostService.getMethod("stop").invoke(remoteRpcHostInstance);
            }
            writeStaticField(remoteRpcHostService, "instance", null);

            Class<?> databaseProvider = Class.forName("com.meshtastic.client.service.DatabaseProvider");
            databaseProvider.getMethod("close").invoke(null);
            writeStaticField(databaseProvider, "connection", null);
            Class<?> recoveryExecutor = Class.forName(
                    "com.meshtastic.client.service.DatabaseProvider$RecoveryExecutor");
            databaseProvider.getMethod("setRecoveryExecutor", recoveryExecutor)
                    .invoke(null, new Object[]{null});

            Class<?> configHelpRepository = Class.forName("com.meshtastic.client.utils.ConfigHelpRepository");
            Object configHelpInstance = configHelpRepository.getMethod("getInstance").invoke(null);
            configHelpRepository.getMethod("invalidateLoadedState").invoke(configHelpInstance);

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
                bleDiscoveryService.getMethod("dispose").invoke(bleDiscoveryInstance);
            }
            writeStaticField(bleDiscoveryService, "instance", null);

            resetDiagnostics();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to reset test singletons", e);
        }
    }

    private static void resetDiagnostics() throws ReflectiveOperationException {
        Class<?> jfrDiagnosticSupport = Class.forName("com.meshtastic.client.logging.JfrDiagnosticSupport");
        jfrDiagnosticSupport.getMethod("stop").invoke(null);

        Class<?> sessionCrashLogManager = Class.forName("com.meshtastic.client.logging.SessionCrashLogManager");
        var suspendForTests = sessionCrashLogManager.getDeclaredMethod("suspendForTests");
        suspendForTests.setAccessible(true);
        suspendForTests.invoke(null);
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
