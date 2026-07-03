package com.meshtastic.client;

import com.meshtastic.client.logging.JfrDiagnosticSupport;
import com.meshtastic.client.logging.JavaFxCssWarningGuard;
import com.meshtastic.client.logging.SessionCrashLogManager;
import com.meshtastic.client.server.RpcServerApp;
import com.meshtastic.client.terminal.TerminalApp;
import com.meshtastic.client.update.SelfUpdateLauncher;
import com.meshtastic.client.utils.AppPreferences;

/**
 * Entry point that runs before the JavaFX Application class is loaded.
 * Used for early JVM and system properties such as {@code prism.order}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshAppLauncher {

    private MeshAppLauncher() {}

    public static void main(String[] args) {
        if (!MeshApp.isSingleInstanceGuardDisabled(args) && SelfUpdateLauncher.launchPayloadIfNeeded(args)) {
            return;
        }
        if (RpcServerApp.isRpcServerMode(args)) {
            System.exit(RpcServerApp.run(RpcServerApp.stripRpcServerFlag(
                    MeshApp.stripSingleInstanceArguments(args))));
            return;
        }
        if (isTerminalMode(args)) {
            System.exit(TerminalApp.run(MeshApp.stripSingleInstanceArguments(args)));
            return;
        }
        AppPreferences.init();
        if (!MeshApp.isSingleInstanceGuardDisabled(args) && !MeshApp.acquireSingleInstanceGuard()) {
            return;
        }
        SessionCrashLogManager.prepareForLaunch();
        JavaFxCssWarningGuard.install();
        JfrDiagnosticSupport.start();
        if (System.getProperty("prism.order") == null && AppPreferences.isSoftwareRendering()) {
            System.setProperty("prism.order", "sw");
        }
        MeshApp.main(args);
    }

    private static boolean isTerminalMode(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if ("--terminal".equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
