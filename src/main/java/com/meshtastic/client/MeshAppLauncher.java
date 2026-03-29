package com.meshtastic.client;

import com.meshtastic.client.utils.AppPreferences;

/**
 * Точка входа до загрузки JavaFX Application.
 * Нужна для ранней установки JVM/System properties вроде prism.order.
 */
public final class MeshAppLauncher {

    private MeshAppLauncher() {}

    public static void main(String[] args) {
        AppPreferences.init();
        if (System.getProperty("prism.order") == null && AppPreferences.isSoftwareRendering()) {
            System.setProperty("prism.order", "sw");
        }
        MeshApp.main(args);
    }
}
