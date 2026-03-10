package com.meshtastic.client.system;

public class DrawerManager {

    private DrawerManager() {}

    private static DrawerPane drawerPane;

    public static void setDrawerPane(DrawerPane pane) {
        drawerPane = pane;
    }

    public static void toggleDrawer() {
        if (drawerPane != null) {
            drawerPane.setCompact(!drawerPane.isCompact());
        }
    }

    public static void setSelectedItemClass(Class<?> cls) {
        if (drawerPane != null) {
            drawerPane.setSelectedItemClass(cls);
        }
    }

    public static DrawerPane getDrawerPane() {
        return drawerPane;
    }
}
