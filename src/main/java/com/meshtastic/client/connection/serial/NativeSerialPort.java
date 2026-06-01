package com.meshtastic.client.connection.serial;

import com.meshtastic.client.connection.ConnectionException;

/**
 * Native serial-port access through JNA, without jSerialComm I/O.
 * <p>
 * The key difference from jSerialComm is explicit DTR/RTS control at open time.
 * This prevents ESP32 resets on USB-Serial bridges such as CH340 and CP210x,
 * where DTR/RTS lines are often wired into the auto-reset circuit.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface NativeSerialPort {

    /**
     * Opens a port at the requested speed using 8N1 and no flow control.
     *
     * @param portName system port name such as {@code "COM3"}, {@code "cu.usbserial-1234"}, or {@code "ttyUSB0"}
     * @param baudRate port speed, for example {@code 115200}
     * @param modemLinePolicy DTR/RTS policy for the selected adapter
     */
    void open(String portName, int baudRate, SerialModemLinePolicy modemLinePolicy) throws ConnectionException;

    /**
     * Reads with a timeout.
     *
     * @return number of bytes read; {@code 0} means timeout and {@code -1} means error or closed port
     */
    int read(byte[] buf, int len, int timeoutMs);

    /**
     * Writes to the port.
     */
    void write(byte[] data, int offset, int len) throws ConnectionException;

    /** Clears the input buffer. */
    void drainInput();

    boolean isOpen();

    /** Closes the port and releases the native handle or file descriptor. */
    void close();
}
