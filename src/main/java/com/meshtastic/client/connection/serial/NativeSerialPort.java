package com.meshtastic.client.connection.serial;

import com.meshtastic.client.connection.ConnectionException;

/**
 * Нативный доступ к serial-порту через JNA (без jSerialComm).
 * <p>
 * Ключевое отличие от jSerialComm: приложение явно управляет DTR/RTS при открытии.
 * Это предотвращает сброс ESP32-устройств на USB-Serial мостах (CH340, CP210x и др.),
 * где линии DTR/RTS часто подключены к цепи автосброса (EN/RST/GPIO0).
 * <p>
 * Реализации:
 * <ul>
 *   <li>{@link WinSerialPort} — Windows (kernel32: CreateFileW, SetCommState)</li>
 *   <li>{@link PosixSerialPort} — macOS / Linux (libc: open, tcsetattr)</li>
 * </ul>
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface NativeSerialPort {

    /**
     * Открывает порт с заданной скоростью (8N1, без flow control) и modem-line policy.
     *
     * @param portName  системное имя порта ("COM3", "cu.usbserial-1234", "ttyUSB0")
     * @param baudRate  скорость (напр. 115200)
     * @param modemLinePolicy политика DTR/RTS для выбранного адаптера
     */
    void open(String portName, int baudRate, SerialModemLinePolicy modemLinePolicy) throws ConnectionException;

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
