package com.meshtastic.client.model;

/**
 * Packet tree node optionally tied to a byte range in a serialized
 * {@code MeshPacket}.
 * <p>
 * Ranges are stored as half-open intervals, {@code [startByte, endByte)}, and
 * are used to highlight the corresponding fragment in the HEX/ASCII preview.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class PacketTreeNode {

    private final String label;
    private final int startByte;
    private final int endByte;

    /**
     * Creates a node with no byte range.
     * <p>
     * Such nodes participate only in the textual tree view and should not
     * trigger byte highlighting.
     *
     * @param label node label
     */
    public PacketTreeNode(String label) {
        this(label, -1, -1);
    }

    /**
     * Creates a node bound to a byte range.
     *
     * @param label     node label
     * @param startByte inclusive start of the range
     * @param endByte   exclusive end of the range
     */
    public PacketTreeNode(String label, int startByte, int endByte) {
        this.label = label;
        this.startByte = startByte;
        this.endByte = endByte;
    }

    public String getLabel() {
        return label;
    }

    public int getStartByte() {
        return startByte;
    }

    public int getEndByte() {
        return endByte;
    }

    /**
     * @return {@code true} when the node maps to packet bytes and can be highlighted
     */
    public boolean hasByteRange() {
        return startByte >= 0 && endByte > startByte;
    }

    @Override
    public String toString() {
        return label;
    }
}
