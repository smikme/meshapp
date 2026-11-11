package com.meshtastic.client.terminal;

import com.meshtastic.client.utils.OutgoingText;

/**
 * Shared terminal chat input limits matching the GUI message payload budget.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class TerminalInputLimits {

    private static final int DATA_PAYLOAD_LEN = 233;
    private static final int PROTO_OVERHEAD = 5;
    private static final int REPLY_ID_OVERHEAD = 5;

    private TerminalInputLimits() {
    }

    /**
     * Returns the message input byte budget used by the GUI chat input.
     *
     * @param replying whether the outgoing message reserves room for reply metadata
     * @return maximum UTF-8 payload length available for user text
     */
    static int maxInputBytes(boolean replying) {
        return DATA_PAYLOAD_LEN - PROTO_OVERHEAD - (replying ? REPLY_ID_OVERHEAD : 0);
    }

    /**
     * Counts the UTF-8 bytes used by the text payload after applying the current
     * Cyrillic compaction preference, matching the GUI counter and sending services.
     *
     * @param text input text, may be {@code null}
     * @return transmitted text size without protocol overhead, or {@code 0} for {@code null}
     */
    static int textByteLength(String text) {
        return OutgoingText.byteLength(text);
    }
}
