package com.meshtastic.client.connection;

/**
 * Factory for stream parsers used by TCP and Serial transports.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class FrameParsers {

    private FrameParsers() {
    }

    /**
     * Creates a parser for the requested framing format.
     *
     * @param frameFormat format selected by the protocol runtime
     * @return fresh parser with empty internal state
     */
    static StreamFrameParser create(FrameFormat frameFormat) {
        return switch (frameFormat) {
            case MESHTASTIC -> new FrameParser();
            case KISS -> new KissFrameParser();
            case MESHCORE_COMPANION -> new MeshCoreCompanionFrameParser();
        };
    }
}
