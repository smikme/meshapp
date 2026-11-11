package com.meshtastic.client.components.chat;

import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.Hunspell;
import org.apache.lucene.analysis.hunspell.TimeoutPolicy;
import org.apache.lucene.store.ByteBuffersDirectory;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Offline spelling for Russian, English and German using bundled Hunspell dictionaries.
 * Cyrillic words use the Russian dictionary; Latin words are accepted by either the
 * English or German dictionary. Calls are serialized by {@link ChatSpellingSupport};
 * no draft text leaves the process.
 */
final class ChatSpellChecker {
    /**
     * A misspelled word and its offsets in the original draft.
     *
     * @param start inclusive UTF-16 offset
     * @param end exclusive UTF-16 offset
     * @param text original spelling
     */
    record Word(int start, int end, String text) {}

    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}\\p{M}]*(?:['’][\\p{L}\\p{M}]+)*");
    private static final Pattern NON_PROSE = Pattern.compile(
            "(?i)(?:https?://|www\\.)\\S+|\\S+@\\S+|[!@/][\\p{L}\\p{N}_-]+"
                    + "|\\b[\\p{L}\\p{N}_]*[\\p{N}_][\\p{L}\\p{N}_]*\\b");
    private final Hunspell russian;
    private final List<Hunspell> latin;

    /**
     * Loads all three dictionaries from bundled resources.
     *
     * @throws IOException if a dictionary is missing, unreadable or malformed
     */
    ChatSpellChecker() throws IOException {
        russian = load("ru");
        latin = List.of(load("en"), load("de"));
    }

    /**
     * Loads a language dictionary and configures bounded suggestion searches.
     *
     * @param language resource basename such as ru, en or de
     * @return initialized spelling dictionary
     * @throws IOException if dictionary resources cannot be loaded or parsed
     */
    private static Hunspell load(String language) throws IOException {
        try (var directory = new ByteBuffersDirectory();
             var affix = resource(language + ".aff.gz");
             var words = resource(language + ".dic.gz")) {
            return new Hunspell(new Dictionary(directory, "chat-spelling", affix, words),
                    TimeoutPolicy.RETURN_PARTIAL_RESULT, () -> {});
        } catch (ParseException error) {
            throw new IOException("Invalid spelling dictionary: " + language, error);
        }
    }

    /**
     * Opens a compressed dictionary resource. The caller must close the returned stream.
     *
     * @param name resource filename beneath /spelling/
     * @return stream of decompressed dictionary data
     * @throws IOException if the resource is missing or not readable as gzip
     */
    private static InputStream resource(String name) throws IOException {
        InputStream stream = ChatSpellChecker.class.getResourceAsStream("/spelling/" + name);
        if (stream == null) { throw new IOException("Missing spelling dictionary: " + name); }
        return new GZIPInputStream(stream);
    }

    /**
     * Checks prose words while skipping links, email addresses, commands and identifiers.
     * Unsupported scripts and words outside the supported length range are ignored.
     *
     * @param text non-null original draft
     * @return immutable list of misspelled words in source order
     */
    List<Word> check(String text) {
        List<Word> result = new ArrayList<>();
        List<int[]> excluded = NON_PROSE.matcher(text).results()
                .map(match -> new int[]{match.start(), match.end()}).toList();
        var words = WORD.matcher(text);
        while (words.find()) {
            int start = words.start();
            int end = words.end();
            if (end - start < 2 || end - start > 64
                    || excluded.stream().anyMatch(range -> start < range[1] && end > range[0])) { continue; }
            String word = words.group();
            List<Hunspell> dictionaries = dictionaries(word);
            if (!dictionaries.isEmpty() && dictionaries.stream().noneMatch(dictionary -> dictionary.spell(normalize(word)))) {
                result.add(new Word(start, end, word));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Finds spelling alternatives with a 150 ms search budget per applicable dictionary.
     *
     * @param word non-null misspelled word
     * @return at most six distinct suggestions, in dictionary order
     */
    List<String> suggestions(String word) {
        var result = new LinkedHashSet<String>();
        for (Hunspell dictionary : dictionaries(word)) {
            result.addAll(dictionary.suggest(normalize(word), 150));
        }
        return result.stream().limit(6).toList();
    }

    /**
     * Selects dictionaries by the scripts of the word's letters.
     *
     * @param word word to classify
     * @return applicable dictionaries, or an empty list for mixed or unsupported scripts
     */
    private List<Hunspell> dictionaries(String word) {
        if (word.codePoints().allMatch(cp -> !Character.isLetter(cp)
                || Character.UnicodeScript.of(cp) == Character.UnicodeScript.CYRILLIC)) { return List.of(russian); }
        if (word.codePoints().allMatch(cp -> !Character.isLetter(cp)
                || Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN)) { return latin; }
        return List.of();
    }

    /**
     * Normalizes typographic apostrophes for dictionary lookup.
     *
     * @param word original word
     * @return word with ASCII apostrophes
     */
    private static String normalize(String word) { return word.replace('’', '\''); }
}
