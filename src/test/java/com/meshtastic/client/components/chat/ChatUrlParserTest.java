package com.meshtastic.client.components.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatUrlParserTest {

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
    void detectsMeshFilesImageUrls() {
        MeshFilesImage image = new MeshFilesImage(
                "abCD_123",
                "https://d.privatepractice.app/abCD_123",
                "https://d.privatepractice.app/abCD_123/preview");

        assertEquals(
                Optional.of(image),
                ChatUrlParser.meshFilesImage("https://d.privatepractice.app/abCD_123"));
        assertEquals(
                Optional.of(image),
                ChatUrlParser.meshFilesImage("https://d.privatepractice.app/abCD_123/preview"));
    }

    @Test
    void findsDistinctMeshFilesImagesInMessageText() {
        assertEquals(
                List.of(new MeshFilesImage(
                        "abCD_123",
                        "https://d.privatepractice.app/abCD_123",
                        "https://d.privatepractice.app/abCD_123/preview")),
                ChatUrlParser.findMeshFilesImages("""
                        first https://d.privatepractice.app/abCD_123
                        duplicate https://d.privatepractice.app/abCD_123/preview
                        external https://example.com/image
                        """));
    }

    @Test
    void rejectsNonPublicMeshFilesRoutes() {
        assertEquals(Optional.empty(), ChatUrlParser.meshFilesImage("http://d.privatepractice.app/abCD_123"));
        assertEquals(Optional.empty(), ChatUrlParser.meshFilesImage("https://d.privatepractice.app/api/files"));
        assertEquals(Optional.empty(), ChatUrlParser.meshFilesImage("https://example.com/abCD_123"));
    }
}
