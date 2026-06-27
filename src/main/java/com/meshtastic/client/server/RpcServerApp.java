package com.meshtastic.client.server;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.lua.LuaScriptRuntimeService;
import com.meshtastic.client.rpc.RpcAccessKey;
import com.meshtastic.client.service.BleDeviceDiscoveryService;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.DatabaseProvider;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.service.PacketMonitorService;
import com.meshtastic.client.service.RemoteRpcHostService;
import com.meshtastic.client.system.AppUi;
import com.meshtastic.client.utils.AppPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless MeshApp Host process that exposes only the direct RPC server.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RpcServerApp {

    private static final Logger log = LoggerFactory.getLogger(RpcServerApp.class);

    private final ServerConfig config;
    private final CountDownLatch stopLatch = new CountDownLatch(1);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    private RpcServerApp(ServerConfig config) {
        this.config = config;
    }

    public static int run(String[] args) {
        AppPreferences.init();
        I18n.initFromPreferences();
        AppUi.install(new ConsoleAppUiBridge());

        RpcServerOptions options;
        try {
            options = RpcServerOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.println(RpcServerOptions.usage());
            return 2;
        }

        if (options.isHelp()) {
            System.out.print(RpcServerOptions.usage());
            return 0;
        }

        try {
            return new RpcServerApp(resolveConfig(options)).runServer();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (Exception e) {
            log.error("RPC server mode failed", e);
            return 1;
        }
    }

    public static boolean isRpcServerMode(String[] args) {
        return RpcServerOptions.isRpcServerMode(args);
    }

    public static String[] stripRpcServerFlag(String[] args) {
        return RpcServerOptions.stripRpcServerFlag(args);
    }

    private int runServer() throws InterruptedException {
        installUncaughtExceptionHandler();
        initializeStorage();

        RemoteRpcHostService hostService = RemoteRpcHostService.getInstance();
        hostService.start(config.bindAddress(), config.port(), config.accessKey().value());
        if (!hostService.isRunning()) {
            String error = hostService.getLastError();
            log.error("Remote RPC host server did not start{}", error == null || error.isBlank() ? "" : ": " + error);
            shutdown();
            return 1;
        }

        Thread shutdownHook = new Thread(() -> {
            stopLatch.countDown();
            shutdown();
        }, "rpc-server-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        log.info("MeshApp RPC server mode started on {}:{}", config.bindAddress(), hostService.getPort());
        if (config.generatedAccessKey()) {
            log.info("Generated and saved RPC access key: {}", config.accessKey().value());
        } else if (config.accessKeyFromPreferences()) {
            log.info("Using saved RPC access key");
        } else {
            log.info("Using RPC access key from command line or environment");
        }
        boolean printKey = config.generatedAccessKey() || config.printAccessKey();
        log.info("Remote clients can connect with address {}:{}, key {}",
                config.bindAddress(),
                hostService.getPort(),
                printKey ? config.accessKey().value() : "<configured>");
        if (!printKey) {
            log.info("Pass --print-rpc-key to print the configured key in console logs");
        }

        if (config.autoconnect()) {
            ConnectionManager.getInstance().connectAutoconnectEntries();
        } else {
            log.info("Saved auto-connect profiles are disabled for this server run");
        }
        log.info("RPC server is running. Press Ctrl+C to stop.");

        try {
            stopLatch.await();
        } finally {
            shutdown();
            removeShutdownHook(shutdownHook);
        }
        return 0;
    }

    private static ServerConfig resolveConfig(RpcServerOptions options) {
        String bindAddress = firstText(
                options.bindAddressOverride(),
                firstText(env(RpcServerOptions.ENV_BIND), AppPreferences.getRemoteRpcServerBindAddress()));
        int port = options.portOverride() != null
                ? options.portOverride()
                : parsePortOrDefault(env(RpcServerOptions.ENV_PORT), AppPreferences.getRemoteRpcServerPort());

        KeySource keySource = KeySource.ARGUMENT_OR_ENVIRONMENT;
        String keyText = firstText(options.accessKeyOverride(), env(RpcServerOptions.ENV_KEY));
        if (keyText == null || keyText.isBlank()) {
            keyText = AppPreferences.getRemoteRpcAccessKey();
            keySource = KeySource.PREFERENCES;
        }
        if (keyText == null || keyText.isBlank()) {
            RpcAccessKey generated = RpcAccessKey.generate();
            AppPreferences.setRemoteRpcAccessKey(generated.value());
            return new ServerConfig(bindAddress, port, generated, options.autoconnect(), options.printAccessKey(), KeySource.GENERATED);
        }

        RpcAccessKey accessKey = RpcAccessKey.parse(keyText.trim());
        return new ServerConfig(bindAddress, port, accessKey, options.autoconnect(), options.printAccessKey(), keySource);
    }

    private static int parsePortOrDefault(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 1 && parsed <= 65_535) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Report as a command-line style validation error below.
        }
        throw new IllegalArgumentException(RpcServerOptions.ENV_PORT + " must be a TCP port between 1 and 65535");
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void installUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                log.error("Uncaught exception in thread '{}'", thread.getName(), throwable));
    }

    private static void initializeStorage() {
        MessageDbService.getInstance().migrateFromJsonHistory();
        MessageDbService.getInstance().markStaleSendingAsFailed();
    }

    private void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        log.info("Stopping MeshApp RPC server mode");
        try {
            LuaScriptRuntimeService.getInstance().stopAll();
        } catch (RuntimeException e) {
            log.warn("Failed to stop Lua runtime service", e);
        }
        RemoteRpcHostService.getInstance().stop();
        ConnectionManager.getInstance().shutdownAll();
        BleDeviceDiscoveryService.getInstance().dispose();
        MessageDbService.closeIfInitialized();
        NodeCacheService.closeIfInitialized();
        PacketMonitorService.closeIfInitialized();
        DatabaseProvider.close();
        log.info("MeshApp RPC server mode stopped");
    }

    private static void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown is already in progress.
        }
    }

    private enum KeySource {
        ARGUMENT_OR_ENVIRONMENT,
        PREFERENCES,
        GENERATED
    }

    private record ServerConfig(String bindAddress,
                                int port,
                                RpcAccessKey accessKey,
                                boolean autoconnect,
                                boolean printAccessKey,
                                KeySource keySource) {
        boolean generatedAccessKey() {
            return keySource == KeySource.GENERATED;
        }

        boolean accessKeyFromPreferences() {
            return keySource == KeySource.PREFERENCES;
        }
    }
}
