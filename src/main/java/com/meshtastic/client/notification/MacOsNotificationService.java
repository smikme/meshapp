package com.meshtastic.client.notification;

import com.meshtastic.client.connection.ble.macos.ObjCRuntime;
import com.meshtastic.client.update.SelfUpdateEnvironment;
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
    private static final String APP_BUNDLE_IDENTIFIER = "com.meshtastic.meshapp";
    private static final String CF_BUNDLE_IDENTIFIER_ENV = "__CFBundleIdentifier";

    private final boolean nativeAvailable;

    public MacOsNotificationService() {
        boolean ok = false;
        try {
            // UNUserNotificationCenter requires a real application identity.
            // In the self-updating layout, jpackage starts a stable .app process
            // which then launches the payload through Contents/runtime/.../bin/java.
            // There NSBundle.mainBundle is the nested runtime directory, not the
            // top-level .app, so checking only bundlePath.endsWith(".app") wrongly
            // sends notifications through osascript.
            MacOsNotificationContext context = detectNotificationContext();
            String reason = context.nativeNotificationReason();
            if (reason == null) {
                log.info("Not a macOS app notification context ({}) — using osascript for notifications",
                        context.describe());
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
                    log.info("macOS UNUserNotificationCenter initialized ({}, reason={})",
                            context.describe(), reason);
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

    private static MacOsNotificationContext detectNotificationContext() {
        long mainBundle = ObjCRuntime.msgSend(ObjCRuntime.cls("NSBundle"), "mainBundle");
        String bundlePath = objcString(mainBundle, "bundlePath");
        String bundleIdentifier = objcString(mainBundle, "bundleIdentifier");
        String envBundleIdentifier = System.getenv(CF_BUNDLE_IDENTIFIER_ENV);
        String launcher = SelfUpdateEnvironment.current()
                .map(SelfUpdateEnvironment::launcher)
                .orElse(null);
        return new MacOsNotificationContext(
                bundlePath,
                bundleIdentifier,
                envBundleIdentifier,
                launcher
        );
    }

    private static String objcString(long receiver, String selector) {
        if (receiver == 0) {
            return null;
        }
        return ObjCRuntime.toJavaString(ObjCRuntime.msgSend(receiver, selector));
    }

    static boolean hasAppBundleAncestor(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        if (normalized.endsWith(".app")) {
            return true;
        }
        int searchFrom = 0;
        int appIndex;
        while ((appIndex = normalized.indexOf(".app/", searchFrom)) >= 0) {
            String afterApp = normalized.substring(appIndex + ".app/".length());
            if ("Contents".equals(afterApp) || afterApp.startsWith("Contents/")) {
                return true;
            }
            searchFrom = appIndex + ".app/".length();
        }
        return false;
    }

    static boolean isMeshAppBundleIdentifier(String value) {
        return APP_BUNDLE_IDENTIFIER.equals(value);
    }

    record MacOsNotificationContext(String bundlePath,
                                    String bundleIdentifier,
                                    String envBundleIdentifier,
                                    String launcher) {
        String nativeNotificationReason() {
            if (hasAppBundleAncestor(bundlePath)) {
                return "main-bundle-inside-app";
            }
            if (hasAppBundleAncestor(launcher)) {
                return "self-update-launcher-app";
            }
            return null;
        }

        String describe() {
            return "bundlePath=" + display(bundlePath)
                    + ", bundleIdentifier=" + display(bundleIdentifier)
                    + ", envBundleIdentifier=" + display(envBundleIdentifier)
                    + ", launcher=" + display(launcher);
        }

        private static String display(String value) {
            return value == null || value.isBlank() ? "<none>" : value;
        }
    }
}
