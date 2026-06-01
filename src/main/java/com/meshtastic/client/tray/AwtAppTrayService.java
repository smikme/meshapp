package com.meshtastic.client.tray;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.platform.OsDetect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;

/**
 * Windows and Linux tray icon implemented through AWT {@link SystemTray}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class AwtAppTrayService implements AppTrayService {

    private static final Logger log = LoggerFactory.getLogger(AwtAppTrayService.class);

    private volatile TrayIcon trayIcon;

    @Override
    public boolean install(Runnable onActivate, Runnable onExit) {
        if (trayIcon != null) {
            return true;
        }
        if (!SystemTray.isSupported()) {
            log.warn("SystemTray is not supported on this system");
            return false;
        }

        try {
            SystemTray systemTray = SystemTray.getSystemTray();
            Image image = TrayIconResources.loadAwtTrayImage(systemTray);

            PopupMenu menu = new PopupMenu();
            MenuItem openItem = new MenuItem(I18n.t("tray.open"));
            openItem.addActionListener(e -> onActivate.run());
            MenuItem exitItem = new MenuItem(I18n.t("tray.exit"));
            exitItem.addActionListener(e -> onExit.run());
            menu.add(openItem);
            menu.addSeparator();
            menu.add(exitItem);

            TrayIcon icon = new TrayIcon(image, I18n.t("app.title"), menu);
            icon.setImageAutoSize(!OsDetect.isLinux());
            icon.addActionListener(e -> onActivate.run());

            systemTray.add(icon);
            trayIcon = icon;
            return true;
        } catch (AWTException | IOException e) {
            log.error("Failed to initialize tray icon", e);
            return false;
        }
    }

    @Override
    public void showNotification(String title, String message) {
        TrayIcon icon = trayIcon;
        if (icon == null) {
            return;
        }
        try {
            icon.displayMessage(
                    title != null ? title : I18n.t("app.title"),
                    message != null ? message : "",
                    TrayIcon.MessageType.INFO);
        } catch (Exception e) {
            log.warn("Failed to show tray notification", e);
        }
    }

    @Override
    public void dispose() {
        TrayIcon icon = trayIcon;
        if (icon == null) {
            return;
        }
        SystemTray.getSystemTray().remove(icon);
        trayIcon = null;
    }
}
