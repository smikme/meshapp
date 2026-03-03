package com.meshtastic.client.components;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.InputStream;
import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Кеш изображений эмодзи: загружает PNG из ресурсов {@code /emoji/} и кеширует как {@link Image}.
 *
 * <p>Имена файлов соответствуют формату Twemoji: hex-кодпоинты через дефис,
 * без вариационного селектора U+FE0F, например {@code 1f600.png}.
 */
public final class EmojiImageCache {

    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    /** Маркер «изображение не найдено» чтобы не пытаться загружать повторно. */
    private static final Image NOT_FOUND = new Image(
            new java.io.ByteArrayInputStream(new byte[0]));

    private EmojiImageCache() {}

    /** Проверить, является ли строка известным эмодзи с изображением. */
    public static boolean isKnownEmoji(String s) {
        return EmojiData.getAllEmojis().contains(s);
    }

    /**
     * Получить кешированное изображение для эмодзи.
     *
     * @return {@link Image} или {@code null} если файл не найден
     */
    public static Image getImage(String emoji) {
        Image img = CACHE.computeIfAbsent(emoji, EmojiImageCache::loadImage);
        return img == NOT_FOUND ? null : img;
    }

    /**
     * Создать новый {@link ImageView} заданного размера.
     *
     * @return ImageView или {@code null} если изображение не найдено
     */
    public static ImageView createImageView(String emoji, double size) {
        Image img = getImage(emoji);
        if (img == null) {
            return null;
        }
        ImageView iv = new ImageView(img);
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        return iv;
    }

    /**
     * Преобразовать строку эмодзи в имя файла Twemoji.
     * Кодпоинты через дефис, строчные hex, U+FE0F пропускается.
     */
    static String emojiToFilename(String emoji) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < emoji.length(); ) {
            int cp = emoji.codePointAt(i);
            // Пропускаем Variation Selector 16 (U+FE0F)
            if (cp != 0xFE0F) {
                if (!sb.isEmpty()) {
                    sb.append('-');
                }
                sb.append(Integer.toHexString(cp));
            }
            i += Character.charCount(cp);
        }
        return sb + ".png";
    }

    private static Image loadImage(String emoji) {
        String filename = emojiToFilename(emoji);
        InputStream is = EmojiImageCache.class.getResourceAsStream("/emoji/" + filename);
        if (is == null) {
            return NOT_FOUND;
        }
        return new Image(is);
    }
}
