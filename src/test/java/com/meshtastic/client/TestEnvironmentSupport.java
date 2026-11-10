package com.meshtastic.client;

import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
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
        System.setProperty(
                AppPreferences.PROP_PREFERENCES_ROOT_PATH,
                testPreferencesRoot(userHome)
        );
        resetAppPreferencesState();
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
        // Prefer JavaFX 26+ Headless; retain the Monocle setup for JVMs with older bundled JavaFX.
        try {
            Class.forName("com.sun.glass.ui.headless.HeadlessPlatformFactory", false,
                    Platform.class.getClassLoader());
            setIfMissing("glass.platform", "headless");
        } catch (ClassNotFoundException e) {
            setIfMissing("glass.platform", "Monocle");
            setIfMissing("monocle.platform", "Headless");
        }
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
            Object luaRuntimeInstance = readSingleton(luaRuntimeService);
            if (luaRuntimeInstance != null) {
                luaRuntimeService.getMethod("stopAll").invoke(luaRuntimeInstance);
            }
            clearStaticField(luaRuntimeService, "instance");

            Class<?> luaScriptService = Class.forName("com.meshtastic.client.lua.LuaScriptService");
            clearStaticField(luaScriptService, "instance");

            Class<?> messageDbService = Class.forName("com.meshtastic.client.service.MessageDbService");
            Object messageDbInstance = readSingleton(messageDbService);
            if (messageDbInstance != null) {
                messageDbService.getMethod("close").invoke(messageDbInstance);
            }
            clearStaticField(messageDbService, "instance");

            Class<?> nodeCacheService = Class.forName("com.meshtastic.client.service.NodeCacheService");
            Object nodeCacheInstance = readSingleton(nodeCacheService);
            if (nodeCacheInstance != null) {
                nodeCacheService.getMethod("close").invoke(nodeCacheInstance);
            }
            clearStaticField(nodeCacheService, "instance");

            Class<?> favoriteNodeService = Class.forName("com.meshtastic.client.service.FavoriteNodeService");
            clearStaticField(favoriteNodeService, "instance");

            Class<?> ignoredNodeService = Class.forName("com.meshtastic.client.service.IgnoredNodeService");
            clearStaticField(ignoredNodeService, "instance");

            Class<?> packetMonitorService = Class.forName("com.meshtastic.client.service.PacketMonitorService");
            Object packetMonitorInstance = readSingleton(packetMonitorService);
            if (packetMonitorInstance != null) {
                packetMonitorService.getMethod("close").invoke(packetMonitorInstance);
            }
            clearStaticField(packetMonitorService, "instance");

            Class<?> remoteRpcHostService = Class.forName("com.meshtastic.client.service.RemoteRpcHostService");
            Object remoteRpcHostInstance = readSingleton(remoteRpcHostService);
            if (remoteRpcHostInstance != null) {
                remoteRpcHostService.getMethod("stop").invoke(remoteRpcHostInstance);
            }
            clearStaticField(remoteRpcHostService, "instance");

            Class<?> databaseProvider = Class.forName("com.meshtastic.client.service.DatabaseProvider");
            databaseProvider.getMethod("close").invoke(null);
            clearStaticField(databaseProvider, "connection");
            Class<?> recoveryExecutor = Class.forName(
                    "com.meshtastic.client.service.DatabaseProvider$RecoveryExecutor");
            databaseProvider.getMethod("setRecoveryExecutor", recoveryExecutor)
                    .invoke(null, new Object[]{null});

            Class<?> configHelpRepository = Class.forName("com.meshtastic.client.utils.ConfigHelpRepository");
            Object configHelpInstance = configHelpRepository.getMethod("getInstance").invoke(null);
            configHelpRepository.getMethod("invalidateLoadedState").invoke(configHelpInstance);

            Class<?> reconnectService = Class.forName("com.meshtastic.client.service.ReconnectService");
            Object reconnectInstance = readSingleton(reconnectService);
            if (reconnectInstance != null) {
                ExecutorService scheduler = (ExecutorService) readScheduler(reconnectInstance);
                scheduler.shutdownNow();
            }
            clearStaticField(reconnectService, "instance");

            Class<?> connectionManager = Class.forName("com.meshtastic.client.service.ConnectionManager");
            Object connectionManagerInstance = readSingleton(connectionManager);
            if (connectionManagerInstance != null) {
                connectionManager.getMethod("shutdownAll").invoke(connectionManagerInstance);
            }
            clearStaticField(connectionManager, "instance");

            Class<?> bleDiscoveryService = Class.forName("com.meshtastic.client.service.BleDeviceDiscoveryService");
            Object bleDiscoveryInstance = readSingleton(bleDiscoveryService);
            if (bleDiscoveryInstance != null) {
                bleDiscoveryService.getMethod("dispose").invoke(bleDiscoveryInstance);
            }
            clearStaticField(bleDiscoveryService, "instance");

            resetAppPreferencesState();
            resetDiagnostics();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to reset test singletons", e);
        }
    }

    private static String testPreferencesRoot(Path userHome) {
        String source = userHome.toAbsolutePath().normalize().toString();
        UUID id = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        return AppPreferences.PREFERENCES_ROOT_PATH + "-test/" + id;
    }

    private static void resetAppPreferencesState() {
        try {
            clearStaticField(AppPreferences.class, "state");
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to reset AppPreferences state", e);
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

    private static Object readSingleton(Class<?> type) throws ReflectiveOperationException {
        Field field = type.getDeclaredField("instance");
        field.setAccessible(true);
        return field.get(null);
    }

    private static void clearStaticField(Class<?> type, String fieldName) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, null);
    }

    private static Object readScheduler(Object target) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField("scheduler");
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
