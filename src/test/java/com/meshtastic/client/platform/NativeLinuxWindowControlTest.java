package com.meshtastic.client.platform;

import com.meshtastic.client.TestEnvironmentSupport;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.platform.unix.X11;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeLinuxWindowControlTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void titleUsesStandardUtf8ForSupplementaryEmoji() {
        assertArrayEquals(
                new byte[]{
                        'C', 'O', 'V', 'O', 'X', ' ',
                        (byte) 0xF0, (byte) 0x9F, (byte) 0x93, (byte) 0xA1,
                        0
                },
                NativeLinuxWindowControl.utf8CString("COVOX 📡")
        );
    }

    @Test
    void embeddedNullCannotTruncateX11Title() {
        assertArrayEquals(
                "Mesh App\0".getBytes(StandardCharsets.UTF_8),
                NativeLinuxWindowControl.utf8CString("Mesh\0App")
        );
    }

    @Test
    void repairsNativeX11TitleWithSupplementaryEmoji() {
        assumeTrue(OsDetect.isLinux());
        assumeTrue(System.getenv("DISPLAY") != null && !System.getenv("DISPLAY").isBlank());

        onFxThread(() -> {
            String title = "MeshApp: COVOX BASE 📡 (!04c5b420)";
            Stage stage = new Stage();
            stage.setTitle("MeshApp");
            stage.show();
            try {
                long windowId = NativeLinuxWindowControl.extractWindowId(stage);
                NativeWindowHelper.setWindowTitle(stage, title);

                // A successful native update must not also call Stage.setTitle(),
                // otherwise the window manager receives a corrupted intermediate
                // title and redraws the system frame twice.
                assertEquals("MeshApp", stage.getTitle());
                assertTrue(stage.isShowing());
                assertEquals(windowId, NativeLinuxWindowControl.extractWindowId(stage));
                assertEquals(title, readNetWmName(windowId));
            } finally {
                stage.close();
            }
            return null;
        });
    }

    private static String readNetWmName(long windowId) {
        X11.Display display = X11.INSTANCE.XOpenDisplay(null);
        assertTrue(display != null);
        Pointer propertyData = null;
        try {
            X11.Atom property = X11.INSTANCE.XInternAtom(display, "_NET_WM_NAME", false);
            X11.Atom utf8String = X11.INSTANCE.XInternAtom(display, "UTF8_STRING", false);
            X11.AtomByReference actualType = new X11.AtomByReference();
            IntByReference actualFormat = new IntByReference();
            NativeLongByReference itemCount = new NativeLongByReference();
            NativeLongByReference bytesAfter = new NativeLongByReference();
            PointerByReference data = new PointerByReference();

            int status = X11.INSTANCE.XGetWindowProperty(
                    display,
                    new X11.Window(windowId),
                    property,
                    new NativeLong(0),
                    new NativeLong(4096),
                    false,
                    utf8String,
                    actualType,
                    actualFormat,
                    itemCount,
                    bytesAfter,
                    data
            );
            assertEquals(0, status);
            assertEquals(8, actualFormat.getValue());
            propertyData = data.getValue();
            byte[] bytes = propertyData.getByteArray(0, itemCount.getValue().intValue());
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            if (propertyData != null) {
                X11.INSTANCE.XFree(propertyData);
            }
            X11.INSTANCE.XCloseDisplay(display);
        }
    }

    private static <T> T onFxThread(FxSupplier<T> supplier) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for JavaFX");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for JavaFX", e);
        }
        if (failure.get() != null) {
            throw new AssertionError("JavaFX operation failed", failure.get());
        }
        return result.get();
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }
}
