package com.meshtastic.client.menu;

import com.meshtastic.client.components.PacketMonitorWindow;
import com.meshtastic.client.forms.FormChat;
import com.meshtastic.client.forms.FormConnections;
import com.meshtastic.client.forms.FormDashboard;
import com.meshtastic.client.forms.FormLogs;
import com.meshtastic.client.forms.FormMap;
import com.meshtastic.client.forms.FormNodes;
import com.meshtastic.client.forms.FormSetting;
import com.meshtastic.client.system.DrawerManager;
import com.meshtastic.client.system.DrawerPane;
import com.meshtastic.client.system.FormManager;

public class MyDrawerBuilder {

    private MyDrawerBuilder() {}

    private static final MenuManager menuManager = new MenuManager();

    public static MenuManager getMenuManager() {
        return menuManager;
    }

    public static void init(DrawerPane drawerPane) {
        initMenuStructure();
        menuManager.rebuildMenu(drawerPane);
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
                .add(new MenuManager.MenuItem("Чаты", null, "/drawer/icon/chat.svg",
                        MenuManager.MenuItem.Type.ITEM, FormChat.class))
                .add(new MenuManager.MenuItem("Ноды", null, "/drawer/icon/nodes.svg",
                        MenuManager.MenuItem.Type.ITEM, FormNodes.class))
                .add(new MenuManager.MenuItem("Карты", null, "/drawer/icon/map.svg",
                        MenuManager.MenuItem.Type.ITEM, FormMap.class))
                .add(new MenuManager.MenuItem("Телеметрия", null, "/drawer/icon/chart.svg",
                        MenuManager.MenuItem.Type.ITEM, FormDashboard.class))
                .add(new MenuManager.MenuItem("Подключение", null, "/drawer/icon/plugin.svg",
                        MenuManager.MenuItem.Type.ITEM, FormConnections.class))
                .add(new MenuManager.MenuItem("Логирование", null, "/drawer/icon/eye.svg",
                        MenuManager.MenuItem.Type.ITEM, FormLogs.class))
                .add(new MenuManager.MenuItem("LoRa пакеты", null, "/drawer/icon/packet-monitor.svg",
                        MenuManager.MenuItem.Type.ITEM, null))
                .add(new MenuManager.MenuItem("Настройки", null, "/drawer/icon/setting.svg",
                        MenuManager.MenuItem.Type.ITEM, FormSetting.class))
                .add(new MenuManager.MenuItem("Помощь", null, "/drawer/icon/about.svg",
                        MenuManager.MenuItem.Type.ITEM, null));

        menuManager.registerAction("LoRa пакеты", PacketMonitorWindow::showWindow);
        menuManager.registerAction("Помощь", FormManager::showAbout);
    }
}
