package com.meshtastic.client.server;

import com.meshtastic.client.system.AppUi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless presentation bridge for console RPC server mode.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class ConsoleAppUiBridge implements AppUi.Bridge {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAppUiBridge.class);

    @Override
    public void showStatus(AppUi.StatusType type, String message) {
        AppUi.StatusType level = type != null ? type : AppUi.StatusType.INFO;
        String text = message != null ? message : "";
        switch (level) {
            case ERROR -> log.error(text);
            case WARNING -> log.warn(text);
            case SUCCESS, INFO -> log.info(text);
        }
    }

    @Override
    public void updateHeader(String shortName, String longName, String nodeId) {
        log.info("Active node: {} ({}, {})",
                longName != null && !longName.isBlank() ? longName : "?",
                shortName != null && !shortName.isBlank() ? shortName : "?",
                nodeId != null && !nodeId.isBlank() ? nodeId : "?");
    }

    @Override
    public void requestBlePasskey(String deviceAddress, java.util.function.IntConsumer onSubmit, Runnable onCancel) {
        log.warn("BLE passkey requested for {}, but console RPC server mode cannot prompt interactively",
                deviceAddress != null && !deviceAddress.isBlank() ? deviceAddress : "unknown device");
        if (onCancel != null) {
            onCancel.run();
        }
    }

    @Override
    public boolean isTerminal() {
        return true;
    }
}
