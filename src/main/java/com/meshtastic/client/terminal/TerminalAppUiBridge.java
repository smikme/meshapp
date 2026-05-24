package com.meshtastic.client.terminal;

import com.meshtastic.client.system.AppUi;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Terminal presentation bridge. It never touches JavaFX.
 */
final class TerminalAppUiBridge implements AppUi.Bridge {

    private static final int MAX_STATUS_MESSAGES = 120;

    private final Queue<String> statusMessages = new ConcurrentLinkedQueue<>();

    @Override
    public void showStatus(AppUi.StatusType type, String message) {
        String text = "[" + (type != null ? type.name() : AppUi.StatusType.INFO.name()) + "] "
                + (message != null ? message : "");
        statusMessages.offer(text);
        trim();
    }

    @Override
    public boolean isTerminal() {
        return true;
    }

    String pollStatusMessage() {
        return statusMessages.poll();
    }

    private void trim() {
        while (statusMessages.size() > MAX_STATUS_MESSAGES) {
            statusMessages.poll();
        }
    }
}
