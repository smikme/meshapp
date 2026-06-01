package com.meshtastic.client.components;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Emoji image cache that loads PNG files from {@code /emoji/} resources and stores them as {@link Image}.
 *
 * <p>File names follow the Twemoji convention: lowercase hex code points joined
 * with hyphens. To stay compatible with different asset revisions, the loader
 * checks both the exact resource name and the variant with U+FE0F removed.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class EmojiImageCache {

    private static final double TEXT_BASELINE_RATIO = 0.80;
    private static final int KNOWN_EMOJI_CACHE_MAX_ENTRIES = 2048;

    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> KNOWN_EMOJI_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(KNOWN_EMOJI_CACHE_MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > KNOWN_EMOJI_CACHE_MAX_ENTRIES;
                }
            });
    private static final Set<String> RESOURCE_NAMES = loadResourceNames();
    private static final Set<String> AVAILABLE_EMOJIS = loadAvailableEmojis();
    private static final int MAX_EMOJI_CODEPOINTS = computeMaxEmojiCodePointCount();

    /** Sentinel for a missing image, used to avoid repeated load attempts. */
    private static final Image NOT_FOUND = new Image(
            new java.io.ByteArrayInputStream(new byte[0]));

    /** Cache for frequently used emoji, primarily common expressions. */
    private static final Map<String, Image> PRIORITY_CACHE = new ConcurrentHashMap<>();

    /** Dedicated preload task for emoji images. */
    private static volatile Future<?> preloadFuture;

    /** Executor used for background preloading. */
    private static final ExecutorService preloadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Emoji-Preloader");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private EmojiImageCache() {}

    /** Checks whether the string is a known emoji with a local image. */
    public static boolean isKnownEmoji(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (!hasEmojiPresentationHint(s)) {
            return false;
        }
        synchronized (KNOWN_EMOJI_CACHE) {
            return KNOWN_EMOJI_CACHE.computeIfAbsent(s, EmojiImageCache::hasResourceForEmoji);
        }
    }

    static void clearKnownEmojiCacheForTests() {
        synchronized (KNOWN_EMOJI_CACHE) {
            KNOWN_EMOJI_CACHE.clear();
        }
    }

    static int knownEmojiCacheSizeForTests() {
        synchronized (KNOWN_EMOJI_CACHE) {
            return KNOWN_EMOJI_CACHE.size();
        }
    }

    static int knownEmojiCacheLimitForTests() {
        return KNOWN_EMOJI_CACHE_MAX_ENTRIES;
    }

    /** All emoji that have a local Twemoji PNG resource. */
    public static Set<String> getAvailableEmojis() {
        return AVAILABLE_EMOJIS;
    }

    /** Maximum local emoji-sequence length, measured in code points. */
    public static int getMaxEmojiCodePointCount() {
        return MAX_EMOJI_CODEPOINTS;
    }

    /**
     * Returns a cached image for the emoji.
     * Priority emoji are promoted into the fast cache after loading.
 *
     * @return {@link Image}, or {@code null} when no resource exists
     */
    public static Image getImage(String emoji) {
        // Check the priority cache first; these are the most common glyphs.
        Image img = PRIORITY_CACHE.get(emoji);
        if (img != null) {
            return img == NOT_FOUND ? null : img;
        }
        
        // Then fall back to the main cache.
        img = CACHE.get(emoji);
        if (img != null) {
            return img == NOT_FOUND ? null : img;
        }
        
        // Load on demand when no cache entry exists.
        img = CACHE.computeIfAbsent(emoji, EmojiImageCache::loadImage);
        
        // Promote frequently used emoji to the priority cache.
        if (isPriorityEmoji(emoji)) {
            PRIORITY_CACHE.put(emoji, img);
        }
        
        return img == NOT_FOUND ? null : img;
    }

    /**
     * Checks whether the emoji belongs in the priority cache.
     * The priority set covers common smileys and people expressions.
     */
    private static boolean isPriorityEmoji(String emoji) {
        if (emoji.length() == 0) return false;
        
        // Smileys (U+1F600 through U+1F64A).
        int cp = emoji.codePointAt(0);
        return (cp >= 0x1F600 && cp <= 0x1F64A);
    }

    /**
     * Creates a new {@link ImageView} with the requested size.
 *
     * @return ImageView, or {@code null} when the image is unavailable
     */
    public static ImageView createImageView(String emoji, double size) {
        Image img = getImage(emoji);
        if (img == null) {
            return null;
        }
        ImageView iv = new InlineEmojiImageView(img);
        iv.setUserData(emoji);
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        return iv;
    }

    /**
     * Preloads common emoji on a background thread.
     * Called once during application initialization.
     */
    public static void preloadCommonEmojis() {
        if (preloadFuture != null && !preloadFuture.isDone()) {
            return; // Already running.
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
     * Builds the common emoji list used for preloading.
     */
    private static List<String> getCommonEmojis() {
        List<String> result = new ArrayList<>();
        
        // Smileys, which are the most frequently used set.
        result.add("😀"); // smile
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
        
        // Common hand gestures.
        result.add("👋"); // wave
        result.add("👍");
        result.add("👎");
        result.add("👏");
        result.add("🙌");
        result.add("🤝");
        result.add("🙏");
        result.add("💪");
        
        // Hearts.
        result.add("❤️");
        result.add("🧡");
        result.add("💛");
        result.add("💚");
        result.add("💙");
        result.add("💜");
        result.add("🖤");
        
        // Other popular symbols.
        result.add("🔥");
        result.add("✨");
        result.add("⭐");
        result.add("🎉");
        result.add("❤️‍🔥");
        result.add("❤️‍🩹");
        
        return result;
    }

    /**
     * Converts an emoji string into a resource file name.
     * Code points are lowercase hex values joined by hyphens; U+FE0F can be
     * omitted when the caller asks for the normalized form.
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
            // Some resources omit FE0F, while others keep it inside ZWJ sequences.
            // loadImage() therefore tries both file-name forms.
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

    private static final class InlineEmojiImageView extends ImageView {
        private InlineEmojiImageView(Image image) {
            super(image);
        }

        @Override
        public double getBaselineOffset() {
            double height = getFitHeight() > 0 ? getFitHeight() : getLayoutBounds().getHeight();
            return height * TEXT_BASELINE_RATIO;
        }
    }
}
