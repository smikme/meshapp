package com.meshtastic.client.components.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatUrlParserTest {

    @Test
    void separatesMeshpicPageFromBinaryImageAndThumbnail() {
        ChatImage image = ChatUrlParser.chatImage("https://meshpic.org/feZ").orElseThrow();
        assertEquals("https://meshpic.org/feZ", image.shareUrl());
        assertEquals("https://meshpic.org/image/feZ", image.fullUrl());
        assertEquals("https://meshpic.org/image/feZ?s=t", image.previewUrl());
        assertEquals(Optional.of(image), ChatUrlParser.chatImage("https://MESHPIC.ORG/image/feZ?s=t"));
        ChatImage item = ChatUrlParser.chatImage("https://meshpic.org/image/feZ/WHm?s=t").orElseThrow();
        assertEquals(image.shareUrl(), item.shareUrl());
        assertEquals("https://meshpic.org/image/feZ/WHm", item.fullUrl());
    }

    @Test
    void deduplicatesMeshpicLinksAndKeepsLegacyImagesInMessageOrder() {
        var images = ChatUrlParser.findChatImages("""
                Фото (https://meshpic.org/feZ). https://meshpic.org/image/feZ?s=t
                https://meshpic.org/image/feZ/WHm https://d.privatepractice.app/abCD_123/preview
                https://meshpic.org/7L3!
                """);
        assertEquals(List.of("feZ", "abCD_123", "7L3"), images.stream().map(ChatImage::id).toList());
    }

    @Test
    void rejectsSpoofedHostsCredentialsPortsAndNonImageRoutes() {
        for (String url : List.of("http://meshpic.org/feZ", "https://meshpic.org.evil.test/feZ",
                "https://evil.test/meshpic.org/feZ", "https://meshpic.org@evil.test/feZ",
                "https://user@meshpic.org/feZ", "https://meshpic.org:444/feZ",
                "https://meshpic.org/upload", "https://meshpic.org/_ui/my-images",
                "https://meshpic.org/feZ?next=evil", "https://meshpic.org/feZ#fragment",
                "https://meshpic.org/image/feZ?other=t", "https://meshpic.org/image/feZ/../abc",
                "https://meshpic.org/%66eZ", "https://meshpic.org//feZ",
                "https://user@d.privatepractice.app/abCD_123")) {
            assertEquals(Optional.empty(), ChatUrlParser.chatImage(url), url);
        }
    }

    @Test
    void splitsHttpAndHttpsUrls() {
        assertEquals(
                List.of(
                        new ChatUrlParser.Segment("one ", false),
                        new ChatUrlParser.Segment("http://example.com/a", true),
                        new ChatUrlParser.Segment(" two ", false),
                        new ChatUrlParser.Segment("https://meshapp.ru", true)
                ),
                ChatUrlParser.split("one http://example.com/a two https://meshapp.ru"));
    }

    @Test
    void keepsTrailingPunctuationOutsideUrl() {
        assertEquals(
                List.of(
                        new ChatUrlParser.Segment("see ", false),
                        new ChatUrlParser.Segment("https://example.com/path", true),
                        new ChatUrlParser.Segment(").", false)
                ),
                ChatUrlParser.split("see https://example.com/path)."));
    }

    @Test
    void keepsBalancedClosingParenthesisInsideUrl() {
        assertEquals(
                List.of(
                        new ChatUrlParser.Segment("see ", false),
                        new ChatUrlParser.Segment("https://example.com/path(a)", true),
                        new ChatUrlParser.Segment(".", false)
                ),
                ChatUrlParser.split("see https://example.com/path(a)."));
    }

    @Test
    void doesNotTreatBareSchemeAsUrlAfterTrimming() {
        assertEquals(
                List.of(new ChatUrlParser.Segment("bad http://.", false)),
                ChatUrlParser.split("bad http://."));
    }

    @Test
    void detectsChatImageUrls() {
        ChatImage image = new ChatImage(
                "abCD_123",
                "https://d.privatepractice.app/abCD_123",
                "https://d.privatepractice.app/abCD_123/preview", "https://d.privatepractice.app/abCD_123", null);

        assertEquals(
                Optional.of(image),
                ChatUrlParser.chatImage("https://d.privatepractice.app/abCD_123"));
        assertEquals(
                Optional.of(image),
                ChatUrlParser.chatImage("https://d.privatepractice.app/abCD_123/preview"));
    }

    @Test
    void findsDistinctChatImagesInMessageText() {
        assertEquals(
                List.of(new ChatImage(
                        "abCD_123",
                        "https://d.privatepractice.app/abCD_123",
                        "https://d.privatepractice.app/abCD_123/preview", "https://d.privatepractice.app/abCD_123", null)),
                ChatUrlParser.findChatImages("""
                        first https://d.privatepractice.app/abCD_123
                        duplicate https://d.privatepractice.app/abCD_123/preview
                        external https://example.com/image
                        """));
    }

    @Test
    void rejectsNonPublicMeshFilesRoutes() {
        assertEquals(Optional.empty(), ChatUrlParser.chatImage("http://d.privatepractice.app/abCD_123"));
        assertEquals(Optional.empty(), ChatUrlParser.chatImage("https://d.privatepractice.app/api/files"));
        assertEquals(Optional.empty(), ChatUrlParser.chatImage("https://example.com/abCD_123"));
    }
}
