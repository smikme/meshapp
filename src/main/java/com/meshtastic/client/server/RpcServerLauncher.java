package com.meshtastic.client.server;

/**
 * Console entry point for packaged MeshApp RPC server launchers.
 * <p>
 * Windows jpackage builds the main {@code MeshApp.exe} as a GUI launcher, so
 * it does not attach standard output/error to the invoking console. This entry
 * point is used by the dedicated console launcher and always starts headless
 * RPC server mode without requiring the routing {@code --rpc-server} flag.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RpcServerLauncher {

    private RpcServerLauncher() {
    }

    public static void main(String[] args) {
        System.exit(RpcServerApp.run(args));
    }
}
