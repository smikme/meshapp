package com.meshtastic.client.menu;

import com.meshtastic.client.components.PacketMonitorWindow;
import com.meshtastic.client.forms.FormChat;
import com.meshtastic.client.forms.FormConnections;
import com.meshtastic.client.forms.FormDashboard;
import com.meshtastic.client.forms.FormLogs;
import com.meshtastic.client.forms.FormMap;
import com.meshtastic.client.forms.FormMeshAppIde;
import com.meshtastic.client.forms.FormNodes;
import com.meshtastic.client.forms.FormSetting;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.lua.LuaExtensionManager;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.system.DrawerManager;
import com.meshtastic.client.system.DrawerPane;
import com.meshtastic.client.system.FormManager;
import javafx.application.Platform;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class MyDrawerBuilder {

    private MyDrawerBuilder() {}

    private static final MenuManager menuManager = new MenuManager();
    private static boolean scriptChangeListenerInstalled;

    public static MenuManager getMenuManager() {
        return menuManager;
    }

    public static void init(DrawerPane drawerPane) {
        installScriptChangeListener();
        initMenuStructure();
        menuManager.rebuildMenu(drawerPane);
    }

    public static void rebuild() {
        initMenuStructure();
        menuManager.rebuildMenu();
        LuaExtensionManager.getInstance().reconcileEnabledExtensions();
    }

    public static void updateHeader(String shortName, String longName, String nodeId) {
        DrawerPane pane = DrawerManager.getDrawerPane();
        if (pane != null) {
            pane.updateHeader(shortName, longName, nodeId);
        }
    }

    private static void initMenuStructure() {
        menuManager.clear()
                .addLabel("")
                .add(menuItem("chat", "drawer.chat", "/drawer/icon/chat.svg", FormChat.class))
                .add(menuItem("nodes", "drawer.nodes", "/drawer/icon/nodes.svg", FormNodes.class))
                .add(menuItem("map", "drawer.map", "/drawer/icon/map.svg", FormMap.class))
                .add(menuItem("telemetry", "drawer.telemetry", "/drawer/icon/chart.svg", FormDashboard.class))
                .add(menuItem("connections", "drawer.connections", "/drawer/icon/plugin.svg", FormConnections.class))
                .add(menuItem("logs", "drawer.logs", "/drawer/icon/eye.svg", FormLogs.class))
                .add(menuItem("packet-monitor", "drawer.packetMonitor", "/drawer/icon/packet-monitor.svg", null));

        menuManager.add(menuItem("ide", "drawer.ide", "/drawer/icon/lua.svg", FormMeshAppIde.class));

        addExtensionMenuItems();

        menuManager
                .add(menuItem("settings", "drawer.settings", "/drawer/icon/setting.svg", FormSetting.class))
                .add(menuItem("about", "drawer.help", "/drawer/icon/about.svg", null));

        menuManager.registerAction("packet-monitor", PacketMonitorWindow::showWindow);
        menuManager.registerAction("about", FormManager::showAbout);
    }

    private static void addExtensionMenuItems() {
        for (LuaScript script : LuaExtensionManager.enabledExtensionScripts()) {
            String itemId = LuaExtensionManager.navigationKey(script.getId());
            menuManager.add(new MenuManager.MenuItem(
                    itemId,
                    script.getName(),
                    script.getIcon(),
                    null,
                    true,
                    MenuManager.MenuItem.Type.ITEM,
                    null,
                    itemId));
            menuManager.registerAction(itemId, () -> LuaExtensionManager.getInstance().openExtension(script.getId()));
        }
    }

    private static void installScriptChangeListener() {
        if (scriptChangeListenerInstalled) {
            return;
        }
        scriptChangeListenerInstalled = true;
        LuaScriptService.getInstance().addScriptChangeListener(() -> Platform.runLater(MyDrawerBuilder::rebuild));
    }

    private static MenuManager.MenuItem menuItem(String id,
                                                 String labelKey,
                                                 String iconPath,
                                                 Class<?> formClass) {
        return new MenuManager.MenuItem(
                id,
                I18n.t(labelKey),
                null,
                iconPath,
                MenuManager.MenuItem.Type.ITEM,
                formClass);
    }
}
