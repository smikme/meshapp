package com.meshtastic.client.tray;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.utils.NativeResourceLoader;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Linux tray integration through a GTK status icon.
 * Falls back to the AWT tray when GTK is unavailable.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LinuxGtkTrayService implements AppTrayService {

    private static final Logger log = LoggerFactory.getLogger(LinuxGtkTrayService.class);

    private final AppTrayService fallback = new AwtAppTrayService();

    private volatile GtkLibrary gtk;
    private volatile GObjectLibrary gObject;
    private volatile GLibLibrary gLib;
    private volatile Pointer statusIcon;
    private volatile Pointer menu;
    private volatile Thread gtkThread;
    private volatile boolean usingFallback;

    private StatusIconActivateCallback statusActivateCallback;
    private StatusIconPopupMenuCallback statusPopupMenuCallback;
    private MenuItemActivateCallback openItemActivateCallback;
    private MenuItemActivateCallback exitItemActivateCallback;
    private IdleCallback disposeCallback;

    @Override
    public boolean install(Runnable onActivate, Runnable onExit) {
        if (usingFallback) {
            return fallback.install(onActivate, onExit);
        }
        if (gtkThread != null) {
            return true;
        }

        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean installed = new AtomicBoolean(false);

        Thread thread = new Thread(() -> runGtk(onActivate, onExit, started, installed), "meshapp-gtk-tray");
        thread.setDaemon(true);
        gtkThread = thread;
        thread.start();

        try {
            if (!started.await(5, TimeUnit.SECONDS)) {
                log.warn("GTK tray initialization timed out, falling back to AWT");
                return fallbackToAwt(onActivate, onExit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallbackToAwt(onActivate, onExit);
        }

        if (installed.get()) {
            return true;
        }

        return fallbackToAwt(onActivate, onExit);
    }

    @Override
    public void showNotification(String title, String message) {
        if (usingFallback) {
            fallback.showNotification(title, message);
        }
    }

    @Override
    public void dispose() {
        if (usingFallback) {
            fallback.dispose();
            return;
        }

        GLibLibrary currentGLib = gLib;
        IdleCallback currentDispose = disposeCallback;
        if (currentGLib == null || currentDispose == null) {
            return;
        }

        try {
            currentGLib.g_idle_add(currentDispose, null);
        } catch (Throwable t) {
            log.debug("Failed to schedule GTK tray disposal", t);
        }
    }

    private void runGtk(Runnable onActivate,
                        Runnable onExit,
                        CountDownLatch started,
                        AtomicBoolean installed) {
        try {
            gtk = loadGtk();
            gObject = Native.load("gobject-2.0", GObjectLibrary.class);
            gLib = Native.load("glib-2.0", GLibLibrary.class);

            if (!gtk.gtk_init_check(null, null)) {
                log.warn("gtk_init_check returned false");
                return;
            }

            Path iconPath = NativeResourceLoader.extractResource("/tray/linux/icon_16.png", "meshapp-tray-", ".png");

            statusIcon = gtk.gtk_status_icon_new_from_file(iconPath.toAbsolutePath().toString());
            if (statusIcon == null) {
                log.warn("gtk_status_icon_new_from_file returned null");
                return;
            }

            gtk.gtk_status_icon_set_tooltip_text(statusIcon, I18n.t("app.title"));
            gtk.gtk_status_icon_set_visible(statusIcon, true);

            Pointer gtkMenu = gtk.gtk_menu_new();
            menu = gtkMenu;
            Pointer openItem = gtk.gtk_menu_item_new_with_label(I18n.t("tray.open"));
            Pointer separator = gtk.gtk_separator_menu_item_new();
            Pointer exitItem = gtk.gtk_menu_item_new_with_label(I18n.t("tray.exit"));
            gtk.gtk_menu_shell_append(gtkMenu, openItem);
            gtk.gtk_menu_shell_append(gtkMenu, separator);
            gtk.gtk_menu_shell_append(gtkMenu, exitItem);
            gtk.gtk_widget_show_all(gtkMenu);

            statusActivateCallback = (widget, userData) -> dispatch("tray-activate", onActivate);
            statusPopupMenuCallback = (widget, button, activateTime, userData) -> {
                try {
                    if (menu != null) {
                        gtk.gtk_menu_popup_at_pointer(menu, null);
                    }
                } catch (Throwable t) {
                    log.error("Failed to show GTK tray menu", t);
                }
            };
            openItemActivateCallback = (widget, userData) -> dispatch("tray-open", onActivate);
            exitItemActivateCallback = (widget, userData) -> dispatch("tray-exit", onExit);
            disposeCallback = userData -> {
                try {
                    if (statusIcon != null) {
                        gtk.gtk_status_icon_set_visible(statusIcon, false);
                        gObject.g_object_unref(statusIcon);
                        statusIcon = null;
                    }
                    if (menu != null) {
                        gtk.gtk_widget_destroy(menu);
                        menu = null;
                    }
                    gtk.gtk_main_quit();
                } catch (Throwable t) {
                    log.debug("Failed to dispose GTK tray", t);
                }
                return false;
            };

            gObject.g_signal_connect_data(statusIcon, "activate", statusActivateCallback, null, null, 0);
            gObject.g_signal_connect_data(statusIcon, "popup-menu", statusPopupMenuCallback, null, null, 0);
            gObject.g_signal_connect_data(openItem, "activate", openItemActivateCallback, null, null, 0);
            gObject.g_signal_connect_data(exitItem, "activate", exitItemActivateCallback, null, null, 0);

            installed.set(true);
        } catch (Throwable t) {
            log.warn("Failed to initialize GTK tray, will fall back to AWT", t);
        } finally {
            started.countDown();
        }

        if (!installed.get()) {
            cleanupGtkObjects();
            return;
        }

        try {
            gtk.gtk_main();
        } catch (Throwable t) {
            log.warn("GTK tray loop exited with error", t);
        } finally {
            gtkThread = null;
        }
    }

    private void cleanupGtkObjects() {
        try {
            if (menu != null && gtk != null) {
                gtk.gtk_widget_destroy(menu);
                menu = null;
            }
            if (statusIcon != null && gtk != null && gObject != null) {
                gtk.gtk_status_icon_set_visible(statusIcon, false);
                gObject.g_object_unref(statusIcon);
                statusIcon = null;
            }
        } catch (Throwable t) {
            log.debug("Failed to cleanup GTK tray objects", t);
        }
    }

    private boolean fallbackToAwt(Runnable onActivate, Runnable onExit) {
        usingFallback = true;
        gtkThread = null;
        return fallback.install(onActivate, onExit);
    }

    private void dispatch(String actionName, Runnable action) {
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                log.error("GTK tray action failed: {}", actionName, t);
            }
        }, "meshapp-" + actionName);
        thread.setDaemon(true);
        thread.start();
    }

    private GtkLibrary loadGtk() {
        UnsatisfiedLinkError lastError = null;
        for (String name : new String[]{"gtk-3", "gtk-x11-2.0"}) {
            try {
                return Native.load(name, GtkLibrary.class);
            } catch (UnsatisfiedLinkError e) {
                lastError = e;
            }
        }
        throw new IllegalStateException("GTK library is not available", lastError);
    }

    private interface GtkLibrary extends Library {
        boolean gtk_init_check(Pointer argc, Pointer argv);
        Pointer gtk_status_icon_new_from_file(String filename);
        void gtk_status_icon_set_visible(Pointer statusIcon, boolean visible);
        void gtk_status_icon_set_tooltip_text(Pointer statusIcon, String text);
        Pointer gtk_menu_new();
        Pointer gtk_menu_item_new_with_label(String label);
        Pointer gtk_separator_menu_item_new();
        void gtk_menu_shell_append(Pointer menuShell, Pointer child);
        void gtk_widget_show_all(Pointer widget);
        void gtk_widget_destroy(Pointer widget);
        void gtk_menu_popup_at_pointer(Pointer menu, Pointer triggerEvent);
        void gtk_main();
        void gtk_main_quit();
    }

    private interface GObjectLibrary extends Library {
        long g_signal_connect_data(Pointer instance,
                                   String detailedSignal,
                                   Callback handler,
                                   Pointer data,
                                   Pointer destroyData,
                                   int connectFlags);
        void g_object_unref(Pointer object);
    }

    private interface GLibLibrary extends Library {
        int g_idle_add(IdleCallback function, Pointer data);
    }

    private interface StatusIconActivateCallback extends Callback {
        void invoke(Pointer widget, Pointer userData);
    }

    private interface StatusIconPopupMenuCallback extends Callback {
        void invoke(Pointer widget, int button, int activateTime, Pointer userData);
    }

    private interface MenuItemActivateCallback extends Callback {
        void invoke(Pointer widget, Pointer userData);
    }

    private interface IdleCallback extends Callback {
        boolean invoke(Pointer userData);
    }
}
