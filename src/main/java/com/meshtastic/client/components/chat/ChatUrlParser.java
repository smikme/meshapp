package com.meshtastic.client.components.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits chat text into plain segments and HTTP/HTTPS URL segments.
 */
final class ChatUrlParser {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final String ALWAYS_TRIM_SUFFIX = ".,!?;:";
    private static final String TRAILING_QUOTES = "'\"`";
    private static final String HTTP_SCHEME = "http://";
    private static final String HTTPS_SCHEME = "https://";

    static List<Segment> split(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<Segment> segments = new ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                addSegment(segments, text.substring(cursor, matcher.start()), false);
            }

            String candidate = matcher.group();
            UrlAndSuffix urlAndSuffix = splitTrailingSuffix(candidate);
            if (isBareScheme(urlAndSuffix.url())) {
                addSegment(segments, candidate, false);
            } else {
                addSegment(segments, urlAndSuffix.url(), true);
                if (!urlAndSuffix.suffix().isEmpty()) {
                    addSegment(segments, urlAndSuffix.suffix(), false);
                }
            }
            cursor = matcher.end();
        }

        if (cursor < text.length()) {
            addSegment(segments, text.substring(cursor), false);
        }
        return segments;
    }

    private static void addSegment(List<Segment> segments, String text, boolean url) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!segments.isEmpty()) {
            Segment previous = segments.getLast();
            if (previous.url() == url) {
                segments.set(segments.size() - 1, new Segment(previous.text() + text, url));
                return;
            }
        }
        segments.add(new Segment(text, url));
    }

    private static UrlAndSuffix splitTrailingSuffix(String candidate) {
        int end = candidate.length();
        boolean changed;
        do {
            changed = false;
            while (end > 0 && shouldAlwaysTrim(candidate.charAt(end - 1))) {
                end--;
                changed = true;
            }
            while (end > 0 && isTrailingQuote(candidate.charAt(end - 1))) {
                end--;
                changed = true;
            }
            if (end > 0 && hasUnmatchedClosing(candidate, end, '(', ')')) {
                end--;
                changed = true;
            }
            if (end > 0 && hasUnmatchedClosing(candidate, end, '[', ']')) {
                end--;
                changed = true;
            }
            if (end > 0 && hasUnmatchedClosing(candidate, end, '{', '}')) {
                end--;
                changed = true;
            }
        } while (changed);

        return new UrlAndSuffix(candidate.substring(0, end), candidate.substring(end));
    }

    private static boolean shouldAlwaysTrim(char ch) {
        return ALWAYS_TRIM_SUFFIX.indexOf(ch) >= 0;
    }

    private static boolean isTrailingQuote(char ch) {
        return TRAILING_QUOTES.indexOf(ch) >= 0;
    }

    private static boolean hasUnmatchedClosing(String value, int end, char opening, char closing) {
        if (value.charAt(end - 1) != closing) {
            return false;
        }
        int balance = 0;
        for (int i = 0; i < end; i++) {
            char ch = value.charAt(i);
            if (ch == opening) {
                balance++;
            } else if (ch == closing) {
                balance--;
            }
        }
        return balance < 0;
    }

    private static boolean isBareScheme(String value) {
        return value.equalsIgnoreCase(HTTP_SCHEME) || value.equalsIgnoreCase(HTTPS_SCHEME);
    }

    record Segment(String text, boolean url) {}

    private record UrlAndSuffix(String url, String suffix) {}

    private ChatUrlParser() {}
}
