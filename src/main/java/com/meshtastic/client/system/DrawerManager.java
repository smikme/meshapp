package com.meshtastic.client.system;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
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

    public static void setSelectedItemKey(Object key) {
        if (drawerPane != null) {
            drawerPane.setSelectedItemKey(key);
        }
    }

    public static void setChatUnreadDot(boolean visible) {
        if (drawerPane != null) {
            drawerPane.setChatUnreadDot(visible);
        }
    }

    public static DrawerPane getDrawerPane() {
        return drawerPane;
    }
}
