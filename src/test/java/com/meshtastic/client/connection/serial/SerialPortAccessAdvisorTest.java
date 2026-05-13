package com.meshtastic.client.connection.serial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerialPortAccessAdvisorTest {

    @Test
    void linuxPermissionFailureIncludesRemediationCommand() {
        String message = SerialPortAccessAdvisor.openFailureMessage(
                "/dev/ttyUSB0",
                "Permission denied",
                13,
                true
        );

        assertTrue(message.contains("Нет доступа к serial-порту /dev/ttyUSB0"));
        assertTrue(message.contains("sudo usermod -aG"));
        assertTrue(message.contains("$USER"));
    }

    @Test
    void nonLinuxFailureKeepsNativeErrorMessage() {
        String message = SerialPortAccessAdvisor.openFailureMessage(
                "/dev/cu.usbserial-1",
                "Device busy",
                16,
                false
        );

        assertEquals("Cannot open /dev/cu.usbserial-1: Device busy", message);
    }

    @Test
    void linuxBusyFailureMentionsCompetingProcess() {
        String message = SerialPortAccessAdvisor.openFailureMessage(
                "/dev/ttyUSB0",
                "Device or resource busy",
                16,
                true
        );

        assertTrue(message.contains("занят другим процессом"));
        assertTrue(message.contains("ModemManager"));
    }

    @Test
    void permissionErrorAcceptsErrnoAndNativeText() {
        assertTrue(SerialPortAccessAdvisor.isPermissionError(1, ""));
        assertTrue(SerialPortAccessAdvisor.isPermissionError(13, ""));
        assertTrue(SerialPortAccessAdvisor.isPermissionError(0, "Operation not permitted"));
        assertFalse(SerialPortAccessAdvisor.isPermissionError(16, "Device busy"));
    }

    @Test
    void busyErrorAcceptsErrnoAndNativeText() {
        assertTrue(SerialPortAccessAdvisor.isBusyError(16, ""));
        assertTrue(SerialPortAccessAdvisor.isBusyError(0, "Device or resource busy"));
        assertFalse(SerialPortAccessAdvisor.isBusyError(13, "Permission denied"));
    }

    @Test
    void missingErrorAcceptsErrnoAndNativeText() {
        assertTrue(SerialPortAccessAdvisor.isMissingError(2, ""));
        assertTrue(SerialPortAccessAdvisor.isMissingError(0, "No such file or directory"));
        assertFalse(SerialPortAccessAdvisor.isMissingError(13, "Permission denied"));
    }
}
