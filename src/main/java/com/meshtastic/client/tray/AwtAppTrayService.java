package com.meshtastic.client.tray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.util.Objects;

/**
 * Tray icon для Windows/Linux через AWT {@link SystemTray}.
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
            Image image = ImageIO.read(Objects.requireNonNull(
                    getClass().getResourceAsStream("/logo/icon_32.png"),
                    "Tray icon resource /logo/icon_32.png is missing"));

            PopupMenu menu = new PopupMenu();
            MenuItem openItem = new MenuItem("Открыть");
            openItem.addActionListener(e -> onActivate.run());
            MenuItem exitItem = new MenuItem("Выход");
            exitItem.addActionListener(e -> onExit.run());
            menu.add(openItem);
            menu.addSeparator();
            menu.add(exitItem);

            TrayIcon icon = new TrayIcon(image, "MeshApp", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> onActivate.run());

            SystemTray.getSystemTray().add(icon);
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
                    title != null ? title : "MeshApp",
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
