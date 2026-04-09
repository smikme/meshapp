package com.meshtastic.client.tray;

import com.meshtastic.client.platform.OsDetect;

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
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Подбор и масштабирование tray-иконки под фактический размер системного слота.
 */
final class TrayIconResources {

    static final int[] BASE_ICON_SIZES = {16, 32, 64, 128, 256};
    static final int[] LINUX_TRAY_ICON_SIZES = {16, 20, 22, 24, 32, 48, 64};
    private static final int DEFAULT_TRAY_SIZE = 16;
    private static final int MACOS_STATUS_ICON_SIZE = 36;

    private enum TrayAsset {
        APP("/logo/icon_%d.png", BASE_ICON_SIZES),
        LINUX_TRAY("/tray/linux/icon_%d.png", LINUX_TRAY_ICON_SIZES);

        private final String pathTemplate;
        private final int[] sizes;

        TrayAsset(String pathTemplate, int[] sizes) {
            this.pathTemplate = pathTemplate;
            this.sizes = sizes;
        }

        String resourcePath(int size) {
            return pathTemplate.formatted(size);
        }
    }

    static Image loadAwtTrayImage(SystemTray tray) throws IOException {
        if (OsDetect.isLinux()) {
            return loadLinuxTrayImage();
        }
        Dimension size = tray != null ? tray.getTrayIconSize() : new Dimension(DEFAULT_TRAY_SIZE, DEFAULT_TRAY_SIZE);
        return loadScaledImage(size.width, size.height, TrayAsset.APP);
    }

    static Path extractMacOsTrayIcon() {
        try {
            int sourceSize = chooseSourceIconSize(MACOS_STATUS_ICON_SIZE, TrayAsset.APP);
            String resourcePath = TrayAsset.APP.resourcePath(sourceSize);
            Path extracted = Files.createTempFile("meshapp-tray-icon-", ".png");
            try (InputStream input = TrayIconResources.class.getResourceAsStream(resourcePath)) {
                Files.copy(Objects.requireNonNull(
                        input, "Tray icon resource " + resourcePath + " is missing"),
                        extracted,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            extracted.toFile().deleteOnExit();
            return extracted;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare macOS tray icon", e);
        }
    }

    static BufferedImage loadScaledImage(int width, int height) throws IOException {
        return loadScaledImage(width, height, TrayAsset.APP);
    }

    static BufferedImage loadLinuxTrayImage() throws IOException {
        return loadBaseImage(DEFAULT_TRAY_SIZE, TrayAsset.LINUX_TRAY);
    }

    private static BufferedImage loadScaledImage(int width, int height, TrayAsset asset) throws IOException {
        int safeWidth = sanitizeDimension(width);
        int safeHeight = sanitizeDimension(height);
        int sourceSize = chooseSourceIconSize(Math.max(safeWidth, safeHeight), asset);
        BufferedImage source = loadBaseImage(sourceSize, asset);
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
        return chooseSourceIconSize(targetSize, TrayAsset.APP);
    }

    private static int chooseSourceIconSize(int targetSize, TrayAsset asset) {
        int safeTarget = sanitizeDimension(targetSize);
        for (int size : asset.sizes) {
            if (size >= safeTarget) {
                return size;
            }
        }
        return asset.sizes[asset.sizes.length - 1];
    }

    private static BufferedImage loadBaseImage(int size, TrayAsset asset) throws IOException {
        String resourcePath = asset.resourcePath(size);
        try (InputStream input = TrayIconResources.class.getResourceAsStream(resourcePath)) {
            BufferedImage image = ImageIO.read(Objects.requireNonNull(
                    input, "Tray icon resource " + resourcePath + " is missing"));
            if (image == null) {
                throw new IOException("Unable to decode tray icon resource " + resourcePath);
            }
            return normalizeImage(image);
        }
    }

    private static BufferedImage normalizeImage(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }

        BufferedImage normalized = new BufferedImage(
                source.getWidth(),
                source.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    private static int sanitizeDimension(int value) {
        return value > 0 ? value : DEFAULT_TRAY_SIZE;
    }

    private TrayIconResources() {}
}
