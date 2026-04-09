package com.meshtastic.client.utils;

import com.meshtastic.client.platform.OsDetect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;

/**
 * Открывает внешние URL через нативные системные команды, не затрагивая AWT/Desktop.
 */
public final class ExternalUrlLauncher {

    private static final Logger log = LoggerFactory.getLogger(ExternalUrlLauncher.class);

    public static void open(String url) {
        if (url == null || url.isBlank()) {
            return;
        }

        Thread.ofVirtual().start(() -> {
            try {
                URI uri = URI.create(url.trim());
                List<String> command = buildCommand(uri, OsDetect.current());
                if (command == null || command.isEmpty()) {
                    log.debug("Skipping external URL open on unsupported OS: {}", uri);
                    return;
                }
                new ProcessBuilder(command).start();
            } catch (Exception e) {
                log.debug("Failed to open external URL '{}'", url, e);
            }
        });
    }

    static List<String> buildCommand(URI uri, OsDetect.OsType osType) {
        if (uri == null || osType == null) {
            return null;
        }

        String target = uri.toString();
        return switch (osType) {
            case MACOS -> List.of("open", target);
            case WINDOWS -> List.of("rundll32", "url.dll,FileProtocolHandler", target);
            case LINUX -> List.of("xdg-open", target);
            case UNKNOWN -> null;
        };
    }

    private ExternalUrlLauncher() {}
}
