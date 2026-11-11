package com.meshtastic.client.components.chat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatSpellCheckerTest {
    private static ChatSpellChecker checker;

    @BeforeAll
    static void loadDictionaries() throws Exception { checker = new ChatSpellChecker(); }

    @Test
    void acceptsMixedRussianEnglishAndGerman() {
        assertEquals(List.of(), checker.check("Привет сообщение hello message Hallo Nachricht Straße 😀"));
    }

    @Test
    void findsAndSuggestsCorrectionsInAllThreeLanguages() {
        String draft = "превет helllo Nachrciht";
        var words = checker.check(draft);
        assertEquals(List.of("превет", "helllo", "Nachrciht"), words.stream().map(ChatSpellChecker.Word::text).toList());
        for (var word : words) { assertEquals(word.text(), draft.substring(word.start(), word.end())); }
        assertTrue(checker.suggestions("превет").contains("привет"));
        assertTrue(checker.suggestions("helllo").contains("hello"));
        assertTrue(checker.suggestions("Nachrciht").contains("Nachricht"));
    }

    @Test
    void skipsUrlsAddressesCommandsNodeIdsAndUnsupportedScripts() {
        assertEquals(List.of(), checker.check(
                "https://meshpic.org/feZ @tracebot !abcdef12 custom_node42 somebody@meshapp.test 中文 😀"));
    }
}
