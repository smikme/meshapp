package com.meshtastic.client.connection.serial;

import com.meshtastic.client.connection.ConnectionException;

/**
 * Нативный доступ к serial-порту через JNA (без jSerialComm).
 * <p>
 * Ключевое отличие от jSerialComm: порт открывается <b>без активации DTR</b>.
 * Это предотвращает сброс ESP32-устройств на USB-Serial мостах (CH340, CP210x и др.),
 * где линия DTR подключена к цепи автосброса (EN/RST).
 * <p>
 * Реализации:
 * <ul>
 *   <li>{@link WinSerialPort} — Windows (kernel32: CreateFileW, SetCommState)</li>
 *   <li>{@link PosixSerialPort} — macOS / Linux (libc: open, tcsetattr)</li>
 * </ul>
 */
public interface NativeSerialPort {

    /**
     * Открывает порт с заданной скоростью (8N1, без flow control, DTR не активируется).
     *
     * @param portName системное имя порта ("COM3", "cu.usbserial-1234", "ttyUSB0")
     * @param baudRate скорость (напр. 115200)
     */
    void open(String portName, int baudRate) throws ConnectionException;

    /**
     * Чтение с таймаутом.
     *
     * @return количество прочитанных байт ({@code 0} = таймаут, {@code -1} = ошибка/закрыт)
     */
    int read(byte[] buf, int len, int timeoutMs);

    /**
     * Запись в порт.
     */
    void write(byte[] data, int offset, int len) throws ConnectionException;

    /** Сброс входного буфера. */
    void drainInput();

    boolean isOpen();

    /** Закрывает порт и освобождает нативный handle/fd. */
    void close();
}
