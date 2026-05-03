package com.meshtastic.client.components;

import com.meshtastic.client.utils.AppPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Хранение недавно использованных эмодзи в AppPreferences.
 * Максимум 32 элемента. Самый новый — первый в списке.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class EmojiRecentStore {

    private EmojiRecentStore() {} // utility class

    private static final String KEY_RECENT_EMOJI = "recentEmoji";
    private static final int MAX_RECENT = 32;
    private static final String SEPARATOR = "\\|";
    private static final String JOIN_SEPARATOR = "|";

    /** Получить список недавних эмодзи (новые первыми) */
    public static List<String> getRecent() {
        String raw = AppPreferences.getState().get(KEY_RECENT_EMOJI, "");
        if (raw.isEmpty()) { return new ArrayList<>(); }
        return new ArrayList<>(Arrays.asList(raw.split(SEPARATOR)));
    }

    /** Добавить эмодзи в начало списка недавних, удалить дубликат если есть */
    public static void addRecent(String emoji) {
        List<String> list = getRecent();
        list.remove(emoji);
        list.add(0, emoji);
        if (list.size() > MAX_RECENT) {
            list = new ArrayList<>(list.subList(0, MAX_RECENT));
        }
        AppPreferences.getState().put(KEY_RECENT_EMOJI, String.join(JOIN_SEPARATOR, list));
    }
}
