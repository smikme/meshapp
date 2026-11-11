package com.meshtastic.client.utils;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class OutgoingTextTest {
    @Test
    void usesTheCompleteMeshtasticTableAndLeavesOtherCharactersAlone() {
        assertEquals("SIJABEKMHOPCTXaeopcyxsijY3",
                OutgoingText.prepare("ЅІЈАВЕКМНОРСТХаеорсухѕіјҮЗ", true));
        String unchanged = "БГДЁЖИЙЛПФЦЧШЩЪЫЬЭЮЯ бвгдёжзийклмнптфцчшщъыьэюя Hello Grüße 😀";
        assertEquals(unchanged, OutgoingText.prepare(unchanged, true));
    }

    @Test
    void countsTheActualMixedLanguagePayloadWithoutMutatingTheOriginal() {
        String text = "Всему свое время";
        assertEquals(30, OutgoingText.byteLength(text, false));
        assertEquals(21, OutgoingText.byteLength(text, true));
        assertEquals(text, OutgoingText.prepare(text, false));
        String mixed = text + " Hello Grüße 👩‍💻";
        String wire = OutgoingText.prepare(mixed, true);
        assertEquals(wire.getBytes(StandardCharsets.UTF_8).length, OutgoingText.byteLength(mixed, true));
        assertEquals(wire, OutgoingText.prepare(wire, true));
        assertEquals(0, OutgoingText.byteLength(null, true));
    }

    @Test
    void preservesLinksEmailAndInlineCommands() {
        for (String token : new String[]{
                "https://пример.рф/ссылка?текст=Москва", "www.пример.рф/Москва", "пример.рф/ссылка",
                "mailto:почта@пример.рф", "почта@пример.рф", "mesh://Москва/узел", "/команда", "@бот",
                "https://meshpic.org/аВС_123"}) {
            assertEquals("тecт " + token + " тecт", OutgoingText.prepare("тест " + token + " тест", true));
        }
    }

    @Test
    void preservesCommandArgumentsAndMeasuresTheirFullCost() {
        for (String command : new String[]{"/отправить Москва", "@бот передать сообщение", "!команда Москва"}) {
            String text = "тест\n  " + command + "\nтест";
            assertEquals("тecт\n  " + command + "\nтecт", OutgoingText.prepare(text, true));
            assertEquals(command.getBytes(StandardCharsets.UTF_8).length, OutgoingText.byteLength(command, true));
        }
    }
}
