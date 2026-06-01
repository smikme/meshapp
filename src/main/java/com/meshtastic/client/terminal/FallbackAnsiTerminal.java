package com.meshtastic.client.terminal;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.terminal.ansi.ANSITerminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * ANSI terminal fallback for line-input environments without a controlling TTY.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class FallbackAnsiTerminal extends ANSITerminal {

    private final TerminalSize terminalSize;

    FallbackAnsiTerminal(InputStream input,
                         OutputStream output,
                         Charset charset,
                         TerminalSize terminalSize) {
        super(input, output, charset);
        this.terminalSize = terminalSize;
    }

    @Override
    protected TerminalSize findTerminalSize() {
        return terminalSize;
    }

    @Override
    public void putString(String string) throws IOException {
        if (string != null && !string.isEmpty()) {
            writeToTerminal(string.getBytes(getCharset()));
        }
    }
}
