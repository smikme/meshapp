package com.meshtastic.client.components;

import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;

/**
 * TextFlow с поддержкой отображения эмодзи как изображений.
 *
 * <p>Парсит текст, разбивает на сегменты (обычный текст и эмодзи),
 * рендерит обычный текст как {@link Text}, а эмодзи как {@link ImageView}.
 */
public class EmojiTextFlow extends TextFlow {

    private String rawText;
    private double emojiSize = 18;
    private String textStyleClass;

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
        this.rawText = text;
        rebuild();
    }

    public String getRawText() {
        return rawText;
    }

    public void setEmojiSize(double size) {
        this.emojiSize = size;
        if (rawText != null) {
            rebuild();
        }
    }

    public void setTextStyleClass(String styleClass) {
        this.textStyleClass = styleClass;
    }

    private void rebuild() {
        getChildren().clear();
        if (rawText == null || rawText.isEmpty()) {
            return;
        }

        List<Segment> segments = parseSegments(rawText);
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

    private void addTextNode(String text) {
        Text t = new Text(text);
        if (textStyleClass != null) {
            t.getStyleClass().add(textStyleClass);
        }
        getChildren().add(t);
    }

    /**
     * Разбить текст на сегменты: обычный текст и известные эмодзи.
     * Использует жадный поиск — сначала пробует самую длинную последовательность.
     */
    public static List<Segment> parseSegments(String text) {
        List<Segment> segments = new ArrayList<>();
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
        int maxCps = 8;

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
