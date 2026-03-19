package com.meshtastic.client.connection.serial;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;

/**
 * macOS shim для безопасного управления modem lines через не-variadic C API.
 * <p>
 * Системный {@code ioctl()} на macOS объявлен как variadic. На Apple Silicon
 * прямой вызов этого API через JNA может завершить JVM abort'ом из-за отличий ABI.
 * Поэтому Java-код вызывает маленькую native-библиотеку с фиксированными сигнатурами,
 * а уже она делает {@code ioctl(TIOCMBIS/TIOCMBIC/TIOCMGET)} внутри C.
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
     * Лениво загружает macOS serial shim из classpath resources через JNA.
     *
     * @return singleton JNA-мэппинг {@code libmeshapp-serial.dylib}
     * @throws IllegalStateException если библиотека не собрана или не может быть загружена
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
