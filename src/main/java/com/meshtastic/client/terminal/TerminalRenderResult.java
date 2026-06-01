package com.meshtastic.client.terminal;

/**
 * Mutable viewport values calculated while rendering terminal UI.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
record TerminalRenderResult(int selectedConnectionIndex,
                            int selectedMessageIndex,
                            int messageTopIndex,
                            int lastVisibleMessageCount) {
}
