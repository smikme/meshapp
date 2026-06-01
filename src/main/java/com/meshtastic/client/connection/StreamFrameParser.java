package com.meshtastic.client.connection;

/**
 * Stateful parser that reconstructs protocol messages from a byte stream.
 * <p>
 * TCP and Serial can deliver arbitrary byte chunks, so parsers keep intermediate
 * state between calls and return a frame only after a complete message boundary.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface StreamFrameParser {

    /**
     * Processes one byte from the underlying stream.
     *
     * @param b next byte
     * @return completed frame payload, or {@code null} while the frame is incomplete
     */
    byte[] processByte(byte b);

    /**
     * Returns whether the parser holds an incomplete frame.
     *
     * @return {@code true} when partial data is buffered
     */
    boolean hasPartialFrame();

    /**
     * Lets the parser complete a frame whose boundary is defined by read timeout
     * or inter-byte silence rather than an explicit delimiter.
     *
     * @return completed frame payload, or {@code null} when nothing can be flushed
     */
    default byte[] flushPartialFrame() {
        return null;
    }

    /** Resets parser state and discards any incomplete frame. */
    void reset();
}
