package com.meshtastic.client.connection.serial;

import com.meshtastic.client.i18n.I18n;
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

        assertTrue(message.contains(I18n.t("connection.serial.permission", "/dev/ttyUSB0")));
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

        assertEquals(I18n.t("connection.serial.error.open", "/dev/cu.usbserial-1", "Device busy"), message);
    }

    @Test
    void linuxBusyFailureMentionsCompetingProcess() {
        String message = SerialPortAccessAdvisor.openFailureMessage(
                "/dev/ttyUSB0",
                "Device or resource busy",
                16,
                true
        );

        assertEquals(I18n.t("connection.serial.busy", "/dev/ttyUSB0"), message);
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
