package com.meshtastic.client.components;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Кеш изображений эмодзи: загружает PNG из ресурсов {@code /emoji/} и кеширует как {@link Image}.
 *
 * <p>Имена файлов соответствуют формату Twemoji: hex-кодпоинты через дефис.
 * Для совместимости с разными версиями ассетов loader умеет искать и точное имя,
 * и вариант без U+FE0F.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class EmojiImageCache {

    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> KNOWN_EMOJI_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> RESOURCE_NAMES = loadResourceNames();
    private static final Set<String> AVAILABLE_EMOJIS = loadAvailableEmojis();
    private static final int MAX_EMOJI_CODEPOINTS = computeMaxEmojiCodePointCount();

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
        if (s == null || s.isEmpty()) {
            return false;
        }
        return KNOWN_EMOJI_CACHE.computeIfAbsent(s, EmojiImageCache::hasResourceForEmoji);
    }

    /** Все emoji, для которых в ресурсах есть локальный Twemoji PNG. */
    public static Set<String> getAvailableEmojis() {
        return AVAILABLE_EMOJIS;
    }

    /** Максимальная длина emoji-последовательности в кодпоинтах среди локальных ресурсов. */
    public static int getMaxEmojiCodePointCount() {
        return MAX_EMOJI_CODEPOINTS;
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
        iv.setUserData(emoji);
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
                getImage(emoji);
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
     * Преобразовать строку эмодзи в имя файла ресурса.
     * Кодпоинты через дефис, строчные hex, U+FE0F опционально пропускается.
     */
    static String emojiToFilename(String emoji) {
        return emojiToFilename(emoji, false);
    }

    private static String emojiToFilename(String emoji, boolean preserveVariationSelectors) {
        return emojiToResourceName(emoji, preserveVariationSelectors) + ".png";
    }

    private static String emojiToResourceName(String emoji, boolean preserveVariationSelectors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < emoji.length(); ) {
            int cp = emoji.codePointAt(i);
            // Часть ресурсов хранится без FE0F, часть — с ним внутри ZWJ-последовательностей.
            // Поэтому в loadImage() пробуем обе формы имени файла.
            if (preserveVariationSelectors || cp != 0xFE0F) {
                if (!sb.isEmpty()) {
                    sb.append('-');
                }
                sb.append(Integer.toHexString(cp));
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static List<String> emojiToCandidateResourceNames(String emoji) {
        String exact = emojiToResourceName(emoji, true);
        String normalized = emojiToResourceName(emoji, false);
        if (exact.equals(normalized)) {
            return List.of(exact);
        }
        return List.of(exact, normalized);
    }

    private static List<String> emojiToCandidateFilenames(String emoji) {
        return emojiToCandidateResourceNames(emoji).stream()
                .map(name -> name + ".png")
                .toList();
    }

    private static boolean hasResourceForEmoji(String emoji) {
        if (!hasEmojiPresentationHint(emoji)) {
            return false;
        }
        for (String resourceName : emojiToCandidateResourceNames(emoji)) {
            if (RESOURCE_NAMES.contains(resourceName)) {
                return true;
            }
        }

        if (!RESOURCE_NAMES.isEmpty()) {
            return false;
        }

        for (String filename : emojiToCandidateFilenames(emoji)) {
            try (InputStream is = EmojiImageCache.class.getResourceAsStream("/emoji/" + filename)) {
                if (is != null) {
                    return true;
                }
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean hasEmojiPresentationHint(String emoji) {
        for (int i = 0; i < emoji.length(); ) {
            int cp = emoji.codePointAt(i);
            if (cp == 0xFE0F || cp == 0x20E3 || cp == 0x200D || cp >= 0x1F000) {
                return true;
            }
            i += Character.charCount(cp);
        }
        int first = emoji.codePointAt(0);
        return first >= 0x2600;
    }

    private static Image loadImage(String emoji) {
        for (String filename : emojiToCandidateFilenames(emoji)) {
            String resourceName = filename.substring(0, filename.length() - ".png".length());
            if (!RESOURCE_NAMES.isEmpty() && !RESOURCE_NAMES.contains(resourceName)) {
                continue;
            }
            InputStream is = EmojiImageCache.class.getResourceAsStream("/emoji/" + filename);
            if (is != null) {
                return new Image(is);
            }
        }
        return NOT_FOUND;
    }

    private static Set<String> loadResourceNames() {
        try (InputStream is = EmojiImageCache.class.getResourceAsStream("/emoji/index.txt")) {
            if (is == null) {
                return Set.of();
            }
            Set<String> names = new LinkedHashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String name = line.trim();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
            return Collections.unmodifiableSet(names);
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private static Set<String> loadAvailableEmojis() {
        if (RESOURCE_NAMES.isEmpty()) {
            return Set.of();
        }
        Set<String> emojis = new LinkedHashSet<>();
        for (String resourceName : RESOURCE_NAMES) {
            String emoji = resourceNameToEmoji(resourceName);
            if (!emoji.isEmpty()) {
                emojis.add(emoji);
            }
        }
        return Collections.unmodifiableSet(emojis);
    }

    private static int computeMaxEmojiCodePointCount() {
        int max = 1;
        for (String resourceName : RESOURCE_NAMES) {
            int count = resourceName.isEmpty() ? 0 : resourceName.split("-").length;
            max = Math.max(max, count);
        }
        return Math.max(max, 10);
    }

    private static String resourceNameToEmoji(String resourceName) {
        StringBuilder emoji = new StringBuilder();
        for (String part : resourceName.split("-")) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                emoji.appendCodePoint(Integer.parseInt(part, 16));
            } catch (NumberFormatException ignored) {
                return "";
            }
        }
        return emoji.toString();
    }
}
