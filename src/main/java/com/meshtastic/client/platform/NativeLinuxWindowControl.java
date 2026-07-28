package com.meshtastic.client.platform;

import com.sun.jna.Memory;
import com.sun.jna.platform.unix.X11;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Linux-specific access to the X11 window owned by JavaFX.
 *
 * <p>Glass passes Java strings to GTK through JNI modified UTF-8. Supplementary
 * characters such as emoji are encoded there as surrogate code units, while GTK
 * expects standard UTF-8 and replaces the six invalid bytes with U+FFFD. Replacing
 * the EWMH title property with standard UTF-8 avoids that conversion.
 */
final class NativeLinuxWindowControl {

    private static final Logger log = LoggerFactory.getLogger(NativeLinuxWindowControl.class);

    private static final int PROP_MODE_REPLACE = 0;

    private final long windowId;

    NativeLinuxWindowControl(Window window) {
        windowId = extractWindowId(window);
    }

    boolean setTitle(String title) {
        if (windowId == 0) {
            return false;
        }

        X11.Display display = null;
        try {
            byte[] utf8 = utf8CString(title);
            display = X11.INSTANCE.XOpenDisplay(null);
            if (display == null) {
                return false;
            }

            X11.Atom netWmName = X11.INSTANCE.XInternAtom(display, "_NET_WM_NAME", false);
            X11.Atom utf8String = X11.INSTANCE.XInternAtom(display, "UTF8_STRING", false);
            X11.Window window = new X11.Window(windowId);
            try (Memory titleMemory = new Memory(utf8.length)) {
                titleMemory.write(0, utf8, 0, utf8.length);
                X11.INSTANCE.XChangeProperty(
                        display,
                        window,
                        netWmName,
                        utf8String,
                        8,
                        PROP_MODE_REPLACE,
                        titleMemory,
                        utf8.length - 1
                );
            }
            X11.INSTANCE.XFlush(display);
            return true;
        } catch (Throwable t) {
            log.debug("Failed to set the Linux window title through X11", t);
            return false;
        } finally {
            if (display != null) {
                X11.INSTANCE.XCloseDisplay(display);
            }
        }
    }

    static byte[] utf8CString(String title) {
        byte[] content = (title == null ? "" : title)
                .replace('\0', ' ')
                .getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(content, content.length + 1);
    }

    static long extractWindowId(Window window) {
        if (window == null || !window.isShowing()) {
            return 0;
        }

        try {
            Method getPeer = Window.class.getDeclaredMethod("getPeer");
            getPeer.setAccessible(true);
            Object tkStage = getPeer.invoke(window);
            if (tkStage == null) {
                return 0;
            }

            Method getPlatformWindow = tkStage.getClass().getDeclaredMethod("getPlatformWindow");
            getPlatformWindow.setAccessible(true);
            Object platformWindow = getPlatformWindow.invoke(tkStage);
            if (platformWindow == null) {
                return 0;
            }

            Method getNativeWindow = platformWindow.getClass()
                    .getSuperclass()
                    .getMethod("getNativeWindow");
            getNativeWindow.setAccessible(true);
            return (long) getNativeWindow.invoke(platformWindow);
        } catch (ReflectiveOperationException e) {
            log.debug("Failed to obtain the JavaFX X11 window", e);
            return 0;
        }
    }
}
