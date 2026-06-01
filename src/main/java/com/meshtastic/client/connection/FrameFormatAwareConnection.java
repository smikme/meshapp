package com.meshtastic.client.connection;

/**
 * Transport connection whose stream parser can be switched before protocol runtime startup.
 * <p>
 * Implemented by byte-stream transports such as TCP and Serial. The connection
 * can be opened first, then configured with the framing required by the chosen protocol.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface FrameFormatAwareConnection {

    /**
     * Switches the framing format for subsequent inbound bytes.
     *
     * @param frameFormat new framing format
     */
    void setFrameFormat(FrameFormat frameFormat);

    /**
     * Returns the transport's current framing format.
     *
     * @return active framing format
     */
    FrameFormat getFrameFormat();
}
