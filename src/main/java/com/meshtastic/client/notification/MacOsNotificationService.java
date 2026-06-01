package com.meshtastic.client.notification;

import com.meshtastic.client.connection.ble.macos.ObjCRuntime;
import com.sun.jna.NativeLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS notifications through {@code UNUserNotificationCenter} using JNA and ObjCRuntime.
 * <p>
 * Notifications are sent from the MeshApp process, so clicking them activates
 * MeshApp rather than Script Editor. If the native API is unavailable, the
 * service falls back to {@code osascript}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class MacOsNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(MacOsNotificationService.class);

    private final boolean nativeAvailable;

    public MacOsNotificationService() {
        boolean ok = false;
        try {
        // UNUserNotificationCenter requires an .app bundle. Without it,
        // currentNotificationCenter can raise an ObjC exception and crash the JVM.
        // A Homebrew JDK has a bundleIdentifier but is not an .app; jpackage bundles are safe.
            long mainBundle = ObjCRuntime.msgSend(ObjCRuntime.cls("NSBundle"), "mainBundle");
            long bundlePath = ObjCRuntime.msgSend(mainBundle, "bundlePath");
            String path = ObjCRuntime.toJavaString(bundlePath);
            if (path == null || !path.endsWith(".app")) {
                log.info("Not an .app bundle ({}) — using osascript for notifications", path);
            } else {
                NativeLibrary.getInstance(
                        "/System/Library/Frameworks/UserNotifications.framework/UserNotifications");

                ok = ObjCRuntime.cls("UNUserNotificationCenter") != 0
                        && ObjCRuntime.cls("UNMutableNotificationContent") != 0
                        && ObjCRuntime.cls("UNNotificationSound") != 0
                        && ObjCRuntime.cls("UNNotificationRequest") != 0;

                if (ok) {
            // Request alert and sound authorization: alert (4) | sound (2) = 6.
                    long center = ObjCRuntime.msgSend(
                            ObjCRuntime.cls("UNUserNotificationCenter"), "currentNotificationCenter");
                    if (center != 0) {
                        ObjCRuntime.msgSend(center,
                                "requestAuthorizationWithOptions:completionHandler:", 6L, 0L);
                    }
                    log.info("macOS UNUserNotificationCenter initialized");
                }
            }
        } catch (Throwable t) {
            log.warn("Native notifications unavailable, will use osascript", t);
        }
        this.nativeAvailable = ok;
    }

    @Override
    public void showNotification(String title, String message) {
        if (!nativeAvailable) {
            showViaOsascript(title, message);
            return;
        }
        try {
            showViaNative(title, message);
        } catch (Throwable t) {
            log.warn("Native notification failed, falling back to osascript", t);
            showViaOsascript(title, message);
        }
    }

    private void showViaNative(String title, String message) {
        long pool = ObjCRuntime.createAutoreleasePool();
        long content = 0;
        long titleString = 0;
        long bodyString = 0;
        try {
            // content = [[UNMutableNotificationContent alloc] init]
            content = ObjCRuntime.allocInit("UNMutableNotificationContent");
            if (content == 0) {
                showViaOsascript(title, message);
                return;
            }

            // content.title = title
            titleString = ObjCRuntime.nsString(title != null ? title : "MeshApp");
            ObjCRuntime.msgSend(content, "setTitle:", titleString);

            // content.body = message
            bodyString = ObjCRuntime.nsString(message != null ? message : "");
            ObjCRuntime.msgSend(content, "setBody:", bodyString);

            // content.sound = [UNNotificationSound defaultSound]
            long defaultSound = ObjCRuntime.msgSend(
                    ObjCRuntime.cls("UNNotificationSound"), "defaultSound");
            if (defaultSound != 0) {
                ObjCRuntime.msgSend(content, "setSound:", defaultSound);
            }

            // identifier = [[NSUUID UUID] UUIDString]
            long uuid = ObjCRuntime.msgSend(ObjCRuntime.cls("NSUUID"), "UUID");
            long identifier = ObjCRuntime.msgSend(uuid, "UUIDString");
            if (identifier == 0) {
                showViaOsascript(title, message);
                return;
            }

            // request = [UNNotificationRequest requestWithIdentifier:id content:content trigger:nil]
            long request = ObjCRuntime.msgSend(
                    ObjCRuntime.cls("UNNotificationRequest"),
                    "requestWithIdentifier:content:trigger:",
                    identifier, content, 0L);
            if (request == 0) {
                showViaOsascript(title, message);
                return;
            }

            // [[UNUserNotificationCenter currentNotificationCenter]
            //     addNotificationRequest:request withCompletionHandler:nil]
            long center = ObjCRuntime.msgSend(
                    ObjCRuntime.cls("UNUserNotificationCenter"), "currentNotificationCenter");
            if (center == 0) {
                showViaOsascript(title, message);
                return;
            }
            ObjCRuntime.msgSend(center, "addNotificationRequest:withCompletionHandler:", request, 0L);
        } finally {
            ObjCRuntime.release(bodyString);
            ObjCRuntime.release(titleString);
            ObjCRuntime.release(content);
            ObjCRuntime.drainAutoreleasePool(pool);
        }
    }

    private void showViaOsascript(String title, String message) {
        try {
            String safeTitle = escapeAppleScript(title);
            String safeMsg = escapeAppleScript(message);
            String script = "display notification \"" + safeMsg
                    + "\" with title \"" + safeTitle
                    + "\" sound name \"default\"";
            new ProcessBuilder("osascript", "-e", script)
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception e) {
            log.warn("Failed to show macOS notification via osascript", e);
        }
    }

    private static String escapeAppleScript(String s) {
        if (s == null) { return ""; }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
