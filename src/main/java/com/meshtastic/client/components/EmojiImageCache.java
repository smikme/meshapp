package com.meshtastic.client.components;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    /** Кеш для часто используемых эмодзи (обычные выражения) */
    private static final Map<String, Image> PRIORITY_CACHE = new ConcurrentHashMap<>();

    /** Выделенный поток для предзагрузки эмодзи */
    private static volatile Future<?> preloadFuture;

    /** Пул для фоновой предзагрузки */
    private static final ExecutorService preloadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Emoji-Preloader");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private EmojiImageCache() {}

    /** Проверить, является ли строка известным эмодзи с изображением. */
    public static boolean isKnownEmoji(String s) {
        return EmojiData.getAllEmojis().contains(s);
    }

    /**
     * Получить кешированное изображение для эмодзи.
     * Приоритизированные эмодзи загружаются первыми.
     *
     * @return {@link Image} или {@code null} если файл не найден
     */
    public static Image getImage(String emoji) {
        // Сначала проверяем priority cache (чаще используемые)
        Image img = PRIORITY_CACHE.get(emoji);
        if (img != null) {
            return img == NOT_FOUND ? null : img;
        }
        
        // Затем основной кеш
        img = CACHE.get(emoji);
        if (img != null) {
            return img == NOT_FOUND ? null : img;
        }
        
        // Загрузка если не найдено
        img = CACHE.computeIfAbsent(emoji, EmojiImageCache::loadImage);
        
        // Если это частый эмодзи, копируем в priority cache
        if (isPriorityEmoji(emoji)) {
            PRIORITY_CACHE.put(emoji, img);
        }
        
        return img == NOT_FOUND ? null : img;
    }

    /**
     * Проверить, является ли эмодзи приоритетным (часто используемым).
     * Включает стандартные эмодзи из категории smileys и people.
     */
    private static boolean isPriorityEmoji(String emoji) {
        if (emoji.length() == 0) return false;
        
        // Смайлики (😀-🥲)
        int cp = emoji.codePointAt(0);
        return (cp >= 0x1F600 && cp <= 0x1F64A);
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
     * Предзагрузить часто используемые эмодзи в фоновом потоке.
     * Вызывается один раз при инициализации приложения.
     */
    public static void preloadCommonEmojis() {
        if (preloadFuture != null && !preloadFuture.isDone()) {
            return; // Уже запущена
        }

        List<String> emojisToPreload = getCommonEmojis();
        
        preloadFuture = preloadExecutor.submit(() -> {
            for (String emoji : emojisToPreload) {
                if (Thread.interrupted()) break;
                
                // Загружаем в основной кеш (priority cache будет заполнен при первом get)
                Image img = CACHE.get(emoji);
                if (img == null || img == NOT_FOUND) {
                    loadImage(emoji);
                }
            }
        });
    }

    /**
     * Получить список часто используемых эмодзи для предзагрузки.
     */
    private static List<String> getCommonEmojis() {
        List<String> result = new ArrayList<>();
        
        // Добавляем смайлики (наиболее популярные)
        result.add("😀"); // улыбка
        result.add("😃");
        result.add("😄");
        result.add("😁");
        result.add("😆");
        result.add("😅");
        result.add("🤣");
        result.add("😂");
        result.add("🙂");
        result.add("🙃");
        result.add("😉");
        result.add("😊");
        result.add("😇");
        result.add("🥰");
        result.add("😍");
        result.add("🤩");
        result.add("😘");
        result.add("😗");
        result.add("😚");
        result.add("😙");
        result.add("😋");
        result.add("😛");
        result.add("😜");
        result.add("🤪");
        result.add("😝");
        result.add("🤑");
        result.add("🤗");
        result.add("🤭");
        result.add("🤫");
        result.add("🤔");
        result.add("🫡");
        result.add("🤐");
        result.add("🤨");
        result.add("😐");
        result.add("😑");
        result.add("😶");
        result.add("🫥");
        result.add("😏");
        result.add("😒");
        result.add("🙄");
        result.add("😬");
        result.add("😮‍💨");
        result.add("🤥");
        result.add("😌");
        result.add("😔");
        result.add("😪");
        result.add("🤤");
        result.add("😴");
        result.add("😷");
        result.add("🤒");
        result.add("🤕");
        result.add("🤢");
        result.add("🤮");
        result.add("🥵");
        result.add("🥶");
        result.add("🥴");
        result.add("😵");
        result.add("🤯");
        
        // Добавляем часто используемые выражения рук
        result.add("👋"); // привет
        result.add("👍");
        result.add("👎");
        result.add("👏");
        result.add("🙌");
        result.add("🤝");
        result.add("🙏");
        result.add("💪");
        
        // Добавляем сердечки
        result.add("❤️");
        result.add("🧡");
        result.add("💛");
        result.add("💚");
        result.add("💙");
        result.add("💜");
        result.add("🖤");
        
        // Добавляем другие популярные
        result.add("🔥");
        result.add("✨");
        result.add("⭐");
        result.add("🎉");
        result.add("❤️‍🔥");
        result.add("❤️‍🩹");
        
        return result;
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
