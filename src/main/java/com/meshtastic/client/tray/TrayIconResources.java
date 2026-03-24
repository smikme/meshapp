package com.meshtastic.client.tray;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Подбор и масштабирование tray-иконки под фактический размер системного слота.
 */
final class TrayIconResources {

    static final int[] BASE_ICON_SIZES = {16, 32, 64, 128, 256};
    private static final int DEFAULT_TRAY_SIZE = 16;
    private static final int MACOS_STATUS_ICON_SIZE = 36;

    static Image loadAwtTrayImage(SystemTray tray) throws IOException {
        Dimension size = tray != null ? tray.getTrayIconSize() : new Dimension(DEFAULT_TRAY_SIZE, DEFAULT_TRAY_SIZE);
        return loadScaledImage(size.width, size.height);
    }

    static Path extractMacOsTrayIcon() {
        try {
            BufferedImage image = loadScaledImage(MACOS_STATUS_ICON_SIZE, MACOS_STATUS_ICON_SIZE);
            Path extracted = Files.createTempFile("meshapp-tray-icon-", ".png");
            ImageIO.write(image, "png", extracted.toFile());
            extracted.toFile().deleteOnExit();
            return extracted;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare macOS tray icon", e);
        }
    }

    static BufferedImage loadScaledImage(int width, int height) throws IOException {
        int safeWidth = sanitizeDimension(width);
        int safeHeight = sanitizeDimension(height);
        int sourceSize = chooseSourceIconSize(Math.max(safeWidth, safeHeight));
        BufferedImage source = loadBaseImage(sourceSize);
        if (source.getWidth() == safeWidth && source.getHeight() == safeHeight) {
            return source;
        }

        BufferedImage scaled = new BufferedImage(safeWidth, safeHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, safeWidth, safeHeight, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    static int chooseSourceIconSize(int targetSize) {
        int safeTarget = sanitizeDimension(targetSize);
        for (int size : BASE_ICON_SIZES) {
            if (size >= safeTarget) {
                return size;
            }
        }
        return BASE_ICON_SIZES[BASE_ICON_SIZES.length - 1];
    }

    private static BufferedImage loadBaseImage(int size) throws IOException {
        String resourcePath = "/logo/icon_" + size + ".png";
        try (InputStream input = TrayIconResources.class.getResourceAsStream(resourcePath)) {
            BufferedImage image = ImageIO.read(Objects.requireNonNull(
                    input, "Tray icon resource " + resourcePath + " is missing"));
            if (image == null) {
                throw new IOException("Unable to decode tray icon resource " + resourcePath);
            }
            return image;
        }
    }

    private static int sanitizeDimension(int value) {
        return value > 0 ? value : DEFAULT_TRAY_SIZE;
    }

    private TrayIconResources() {}
}
