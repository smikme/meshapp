package com.meshtastic.client.components.chat;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    private static final String IMAGE_SCHEME = "https";
    private static final String MESHFILES_HOST = "d.privatepractice.app";
    private static final String MESHFILES_ORIGIN = "https://d.privatepractice.app";
    private static final Pattern MESHFILES_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{6,64}");
    private static final Pattern MESHPIC_PATH = Pattern.compile("/([A-Za-z0-9]{1,64})/?");
    private static final Pattern MESHPIC_IMAGE_PATH = Pattern.compile("/image/([A-Za-z0-9]{1,64})(?:/([A-Za-z0-9]{1,64}))?/?");

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

    /** Extracts distinct public image links in message order, including legacy history. */
    static List<ChatImage> findChatImages(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        Map<String, ChatImage> images = new LinkedHashMap<>();
        for (Segment segment : split(text)) {
            if (!segment.url()) {
                continue;
            }
            chatImage(segment.text()).ifPresent(image -> images.putIfAbsent(image.shareUrl(), image));
        }
        return List.copyOf(images.values());
    }

    /**
     * Parses a public image URL, without making network requests.
     *
     * @param url candidate URL segment
     * @return normalized descriptor for Meshpic or legacy MeshFiles URLs
     */
    static Optional<ChatImage> chatImage(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }

        if (!IMAGE_SCHEME.equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getFragment() != null) {
            return Optional.empty();
        }

        if ("meshpic.org".equals(uri.getHost().toLowerCase(Locale.ROOT))) {
            Matcher direct = MESHPIC_IMAGE_PATH.matcher(uri.getRawPath());
            Matcher page = MESHPIC_PATH.matcher(uri.getRawPath());
            String id;
            String imageId = null;
            if (direct.matches()) {
                if (uri.getRawQuery() != null && !"s=t".equals(uri.getRawQuery())) return Optional.empty();
                id = direct.group(1);
                imageId = direct.group(2);
            } else if (page.matches() && uri.getRawQuery() == null) {
                id = page.group(1);
                if (List.of("upload", "static", "image", "api").contains(id)) return Optional.empty();
            } else return Optional.empty();
            String share = "https://meshpic.org/" + id;
            String full = "https://meshpic.org/image/" + id + (imageId == null ? "" : "/" + imageId);
            return Optional.of(new ChatImage(id, share, full + "?s=t", full, null));
        }
        if (!MESHFILES_HOST.equals(uri.getHost().toLowerCase(Locale.ROOT)) || uri.getQuery() != null) {
            return Optional.empty();
        }

        List<String> pathParts = pathParts(uri.getRawPath());
        if (pathParts.size() != 1
                && !(pathParts.size() == 2 && "preview".equals(pathParts.getLast()))) {
            return Optional.empty();
        }

        String id = pathParts.getFirst();
        if (!MESHFILES_ID_PATTERN.matcher(id).matches()) {
            return Optional.empty();
        }

        String originalUrl = MESHFILES_ORIGIN + "/" + id;
        return Optional.of(new ChatImage(id, originalUrl, originalUrl + "/preview", originalUrl, null));
    }

    private static List<String> pathParts(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : rawPath.split("/")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
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
