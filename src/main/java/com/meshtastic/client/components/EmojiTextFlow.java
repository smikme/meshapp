package com.meshtastic.client.components;

import com.meshtastic.client.utils.UnicodeTextUtils;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TextFlow с поддержкой отображения эмодзи как изображений.
 *
 * <p>Парсит текст, разбивает на сегменты (обычный текст и эмодзи),
 * рендерит обычный текст как {@link Text}, а эмодзи как {@link ImageView}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class EmojiTextFlow extends TextFlow {

    private String rawText;
    private double emojiSize = 18;
    private final List<String> textStyleClasses = new ArrayList<>();
    private final List<String> appliedTextStyleClasses = new ArrayList<>();
    private javafx.scene.text.Font textFont;
    private Paint textFill;

    // Кеш для сегментов текста (уменьшает CPU при повторном рендере)
    private static final int SEGMENT_CACHE_MAX_ENTRIES = 512;
    private static final Map<String, List<Segment>> SEGMENT_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(SEGMENT_CACHE_MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Segment>> eldest) {
                    return size() > SEGMENT_CACHE_MAX_ENTRIES;
                }
            });

    public EmojiTextFlow() {
        setMinHeight(Region.USE_PREF_SIZE);
    }

    public EmojiTextFlow(String text) {
        this();
        setText(text);
    }

    public EmojiTextFlow(String text, double emojiSize) {
        this();
        this.emojiSize = emojiSize;
        setText(text);
    }

    public void setText(String text) {
        String sanitized = UnicodeTextUtils.sanitize(text);
        if ((sanitized == null && this.rawText == null)
                || (sanitized != null && sanitized.equals(this.rawText))) {
            return; // Тот же текст - ничего не делать
        }
        this.rawText = sanitized;
        rebuild();
    }

    public String getRawText() {
        return rawText;
    }

    public void setEmojiSize(double size) {
        if (this.emojiSize == size) {
            return; // Тот же размер - ничего не делать
        }
        this.emojiSize = size;
        if (rawText != null) {
            rebuild();
        }
    }

    public void setTextStyleClass(String styleClass) {
        setTextStyleClasses(styleClass == null || styleClass.isBlank() ? List.of() : List.of(styleClass));
    }

    public void setTextStyleClasses(Collection<String> styleClasses) {
        List<String> next = styleClasses == null
                ? List.of()
                : styleClasses.stream()
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        if (!textStyleClasses.equals(next)) {
            textStyleClasses.clear();
            textStyleClasses.addAll(next);
            applyTextStylesToExistingNodes();
        }
    }

    public void setTextFont(javafx.scene.text.Font font) {
        if (font == textFont || (font != null && font.equals(textFont))) {
            return;
        }
        textFont = font;
        applyTextStylesToExistingNodes();
    }

    public void setTextFill(Paint fill) {
        if (fill == textFill || (fill != null && fill.equals(textFill))) {
            return;
        }
        textFill = fill;
        applyTextStylesToExistingNodes();
    }

    private void rebuild() {
        // Получаем сегменты из кеша или парсим новый текст
        List<Segment> segments = getSegmentsForText(rawText);
        
        // Оптимизация: если children пусты или размер совпадает, обновляем инлайн
        boolean shouldUpdateInline = getChildren().size() == segments.size();
        
        if (shouldUpdateInline) {
            updateChildrenInline(segments);
        } else {
            getChildren().clear();
            for (Segment seg : segments) {
                if (seg.isEmoji) {
                    ImageView iv = EmojiImageCache.createImageView(seg.text, emojiSize);
                    if (iv != null) {
                        getChildren().add(iv);
                    } else {
                        addTextNode(seg.text);
                    }
                } else {
                    addTextNode(seg.text);
                }
            }
        }
    }

    /**
     * Получить сегменты из кеша или создать новые.
     */
    private List<Segment> getSegmentsForText(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        synchronized (SEGMENT_CACHE) {
            List<Segment> segments = SEGMENT_CACHE.get(text);
            if (segments != null) {
                return segments;
            }
        }

        List<Segment> parsed = List.copyOf(parseSegments(text));
        synchronized (SEGMENT_CACHE) {
            List<Segment> existing = SEGMENT_CACHE.get(text);
            if (existing != null) {
                return existing;
            }
            SEGMENT_CACHE.put(text, parsed);
            return parsed;
        }
    }

    static void clearSegmentCacheForTests() {
        synchronized (SEGMENT_CACHE) {
            SEGMENT_CACHE.clear();
        }
    }

    static int segmentCacheSizeForTests() {
        synchronized (SEGMENT_CACHE) {
            return SEGMENT_CACHE.size();
        }
    }

    static int segmentCacheLimitForTests() {
        return SEGMENT_CACHE_MAX_ENTRIES;
    }

    /**
     * Обновить children инлайн без полной перестройки.
     */
    private void updateChildrenInline(List<Segment> segments) {
        int i = 0;
        for (Segment seg : segments) {
            if (i >= getChildren().size()) {
                break;
            }
            
            Object child = getChildren().get(i);
            if (seg.isEmoji) {
                if (!(child instanceof ImageView)) {
                    getChildren().remove(i);
                    ImageView iv = EmojiImageCache.createImageView(seg.text, emojiSize);
                    if (iv != null) {
                        getChildren().add(i, iv);
                    } else {
                        addTextNode(seg.text);
                        i++; // Сдвигаемся после добавления
                    }
                } else {
                    ImageView iv = (ImageView) child;
                    // Проверить, тот ли эмодзи
                    String currentEmoji = findEmojiInImageView(iv);
                    if (currentEmoji == null || !currentEmoji.equals(seg.text)) {
                        iv.setImage(EmojiImageCache.getImage(seg.text));
                        iv.setUserData(seg.text);
                    }
                }
            } else {
                if (child instanceof Text) {
                    Text textNode = (Text) child;
                    String safeText = UnicodeTextUtils.sanitizeForJavaFxDisplay(seg.text);
                    if (!textNode.getText().equals(safeText)) {
                        textNode.setText(safeText);
                    }
                } else {
                    getChildren().remove(i);
                    addTextNode(seg.text);
                    i++;
                }
            }
            i++;
        }
        
        // Удалить лишние children
        while (getChildren().size() > segments.size()) {
            getChildren().remove(getChildren().size() - 1);
        }
    }

    /**
     * Найти эмодзи из ImageView (для сравнения).
     */
    private String findEmojiInImageView(ImageView iv) {
        Object userData = iv.getUserData();
        if (userData instanceof String emoji) {
            return emoji;
        }
        return null;
    }

    private void addTextNode(String text) {
        Text t = new Text(UnicodeTextUtils.sanitizeForJavaFxDisplay(text));
        applyTextStyles(t);
        getChildren().add(t);
    }

    private void applyTextStylesToExistingNodes() {
        List<String> previousStyleClasses = List.copyOf(appliedTextStyleClasses);
        for (javafx.scene.Node child : getChildren()) {
            if (child instanceof Text text) {
                applyTextStyles(text, previousStyleClasses);
            }
        }
        appliedTextStyleClasses.clear();
        appliedTextStyleClasses.addAll(textStyleClasses);
    }

    private void applyTextStyles(Text text) {
        applyTextStyles(text, appliedTextStyleClasses);
    }

    private void applyTextStyles(Text text, List<String> previousStyleClasses) {
        text.getStyleClass().removeAll(previousStyleClasses);
        text.getStyleClass().addAll(textStyleClasses);
        if (textFont != null) {
            text.setFont(textFont);
        }
        if (textFill != null) {
            text.setFill(textFill);
        }
    }

    /**
     * Разбить текст на сегменты: обычный текст и известные эмодзи.
     * Использует жадный поиск — сначала пробует самую длинную последовательность.
     */
    public static List<Segment> parseSegments(String text) {
        text = UnicodeTextUtils.sanitize(text);
        List<Segment> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return segments;
        }
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            String emoji = tryMatchEmoji(text, i);
            if (emoji != null) {
                if (!plain.isEmpty()) {
                    segments.add(new Segment(plain.toString(), false));
                    plain.setLength(0);
                }
                segments.add(new Segment(emoji, true));
                i += emoji.length();
            } else {
                int cp = text.codePointAt(i);
                plain.appendCodePoint(cp);
                i += Character.charCount(cp);
            }
        }
        if (!plain.isEmpty()) {
            segments.add(new Segment(plain.toString(), false));
        }
        return segments;
    }

    /**
     * Попытаться найти известный эмодзи начиная с позиции startIndex.
     * Пробует от длинных подстрок к коротким (до 8 кодпоинтов для ZWJ-последовательностей).
     */
    private static String tryMatchEmoji(String text, int startIndex) {
        int maxCps = EmojiImageCache.getMaxEmojiCodePointCount();

        // Собираем кодпоинты и позиции
        int[] endPositions = new int[maxCps + 1];
        int cpCount = 0;
        int pos = startIndex;
        endPositions[0] = startIndex;
        while (cpCount < maxCps && pos < text.length()) {
            pos += Character.charCount(text.codePointAt(pos));
            cpCount++;
            endPositions[cpCount] = pos;
        }

        // Пробуем от самой длинной подстроки к самой короткой
        for (int len = cpCount; len >= 1; len--) {
            String candidate = text.substring(startIndex, endPositions[len]);
            if (EmojiImageCache.isKnownEmoji(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Сегмент текста: обычный текст или эмодзи. */
    public record Segment(String text, boolean isEmoji) {}
}
