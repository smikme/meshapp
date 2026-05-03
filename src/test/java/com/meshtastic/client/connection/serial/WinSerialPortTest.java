package com.meshtastic.client.connection.serial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class WinSerialPortTest {

    @Test
    void formatCommErrorsReturnsNoneForZeroMask() {
        assertEquals("none", WinSerialPort.formatCommErrors(0));
    }

    @Test
    void formatCommErrorsExpandsKnownFlagsAndKeepsUnknownBits() {
        assertEquals("RXOVER|FRAME|0x2000", WinSerialPort.formatCommErrors(0x0001 | 0x0008 | 0x2000));
    }
}
