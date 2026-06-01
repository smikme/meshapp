package com.meshtastic.client.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;

/**
 * Parser for standard KISS TNC frames.
 * <p>
 * Input is a byte stream containing {@code FEND}/{@code FESC} delimiters and
 * escape sequences. Output is the unescaped frame body, whose first byte is the
 * KISS command/type.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class KissFrameParser implements StreamFrameParser {

    private static final Logger log = LoggerFactory.getLogger(KissFrameParser.class);

    public static final byte FEND = (byte) 0xC0;
    public static final byte FESC = (byte) 0xDB;
    public static final byte TFEND = (byte) 0xDC;
    public static final byte TFESC = (byte) 0xDD;
    public static final int MAX_FRAME_SIZE = 512;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(MAX_FRAME_SIZE);
    private boolean inFrame;
    private boolean escaping;

    /**
     * Advances the KISS state machine by one byte.
     *
     * @param b next byte from a TCP or Serial stream
     * @return completed KISS frame body, or {@code null} until a closing delimiter arrives
     */
    @Override
    public byte[] processByte(byte b) {
        if (b == FEND) {
            if (!inFrame) {
                inFrame = true;
                resetBuffer();
                return null;
            }

            if (buffer.size() == 0) {
                escaping = false;
                return null;
            }

            byte[] frame = buffer.toByteArray();
            resetBuffer();
            inFrame = true;
            return frame;
        }

        if (!inFrame) {
            return null;
        }

        if (escaping) {
            escaping = false;
            if (b == TFEND) {
                return append(FEND);
            }
            if (b == TFESC) {
                return append(FESC);
            }
            return append(b);
        }

        if (b == FESC) {
            escaping = true;
            return null;
        }

        return append(b);
    }

    /**
     * Returns whether a KISS frame is open and already has buffered data.
     *
     * @return {@code true} while the parser is between opening and closing {@code FEND}
     */
    @Override
    public boolean hasPartialFrame() {
        return inFrame && (buffer.size() > 0 || escaping);
    }

    /**
     * Resets the parser and clears the buffered frame.
     */
    @Override
    public void reset() {
        inFrame = false;
        escaping = false;
        resetBuffer();
    }

    /**
     * Adds a byte to the current frame while enforcing the maximum frame size.
     *
     * @param b byte after escape processing
     * @return always {@code null}, because frames complete only on {@code FEND}
     */
    private byte[] append(byte b) {
        if (buffer.size() >= MAX_FRAME_SIZE) {
            log.warn("KISS frame exceeded {} bytes, resetting parser", MAX_FRAME_SIZE);
            reset();
            return null;
        }
        buffer.write(b);
        return null;
    }

    /**
     * Clears buffered payload without closing the surrounding KISS frame.
     */
    private void resetBuffer() {
        buffer.reset();
        escaping = false;
    }
}
