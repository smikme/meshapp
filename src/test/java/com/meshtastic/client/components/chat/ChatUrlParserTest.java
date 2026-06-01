package com.meshtastic.client.components.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
