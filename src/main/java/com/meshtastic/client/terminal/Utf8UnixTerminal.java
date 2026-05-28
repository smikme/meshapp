package com.meshtastic.client.terminal;

import com.googlecode.lanterna.terminal.ansi.UnixTerminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Unix terminal variant that writes strings with the configured UTF-8 charset.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class Utf8UnixTerminal extends UnixTerminal {

    Utf8UnixTerminal(InputStream input, OutputStream output, Charset charset) throws IOException {
        super(input, output, charset);
    }

    @Override
    public void putString(String string) throws IOException {
        if (string != null && !string.isEmpty()) {
            writeToTerminal(string.getBytes(getCharset()));
        }
    }
}
