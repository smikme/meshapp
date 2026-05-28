package com.meshtastic.client.terminal;

/**
 * Remembered terminal chat viewport for restoring selection after channel switches.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
record ChatViewport(long selectedDbId, int topIndex) {
}
