package com.meshtastic.client.connection.serial;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;

/**
 * macOS shim for safe modem-line control through a non-variadic C API.
 * <p>
 * macOS declares the system {@code ioctl()} function as variadic. On Apple
 * Silicon, calling it directly through JNA can abort the JVM because of ABI
 * differences. Java therefore calls a small native library with fixed
 * signatures; that library performs {@code ioctl(TIOCMBIS/TIOCMBIC/TIOCMGET)}
 * from C.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class MacOsSerialLibrary {

    interface Api extends Library {
        int meshserial_set_modem_bits(int fd, int bits);
        int meshserial_clear_modem_bits(int fd, int bits);
        int meshserial_get_modem_bits(int fd, IntByReference bits);
    }

    private static volatile Api instance;

    private MacOsSerialLibrary() {}

    /**
     * Lazily loads the macOS serial shim from classpath resources through JNA.
     *
     * @return singleton JNA mapping for {@code libmeshapp-serial.dylib}
     * @throws IllegalStateException if the library is missing or cannot be loaded
     */
    static Api instance() {
        Api library = instance;
        if (library != null) {
            return library;
        }
        synchronized (MacOsSerialLibrary.class) {
            if (instance == null) {
                try {
                    instance = Native.load("meshapp-serial", Api.class);
                } catch (UnsatisfiedLinkError e) {
                    throw new IllegalStateException("libmeshapp-serial.dylib недоступен", e);
                }
            }
            return instance;
        }
    }
}
