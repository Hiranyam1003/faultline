package edu.atlas.pwaudit.algo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrieTest {

    private static Trie trieOf(String... words) {
        Trie trie = new Trie();
        for (String w : words) trie.insert(w, "dict");
        return trie;
    }

    private static List<String> tokens(List<Trie.Match> matches) {
        return matches.stream().map(m -> m.token() + "@" + m.start()).toList();
    }

    @Test
    void findsSingleWordInsideText() {
        List<Trie.Match> matches = trieOf("dragon").findAll("xxdragon99");
        assertEquals(1, matches.size());
        assertEquals(new Trie.Match(2, 8, "dragon", "dict"), matches.get(0));
    }

    @Test
    void findsOverlappingAndNestedWords() {
        Trie trie = trieOf("pass", "password", "word", "sword");
        assertEquals(List.of("pass@0", "password@0", "sword@3", "word@4"), tokens(trie.findAll("password")));
    }

    @Test
    void noMatchReturnsEmpty() {
        assertTrue(trieOf("monkey", "shadow").findAll("tK9#vQ2!").isEmpty());
    }

    @Test
    void boundaryEmptyTextAndEmptyWord() {
        Trie trie = trieOf("abc");
        assertTrue(trie.findAll("").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> trie.insert("", "dict"));
    }

    @Test
    void symbolsDigitsAndRepeatedMatches() {
        Trie trie = trieOf("123", "!@#");
        assertEquals(List.of("123@0", "!@#@3", "123@6"), tokens(trie.findAll("123!@#123")));
    }

    @Test
    void duplicateInsertKeepsFirstCategoryAndSize() {
        Trie trie = new Trie();
        trie.insert("2019", "year");
        trie.insert("2019", "leaked");
        assertEquals(1, trie.size());
        assertTrue(trie.contains("2019"));
        assertFalse(trie.contains("201"));
        assertEquals("year", trie.findAll("2019").get(0).category());
    }

    @Test
    void leetNormalisedTextMatchesDictionaryWord() {
        Trie trie = trieOf("password");
        String normalised = LeetNormalizer.normalize("P@$$w0rd");
        assertEquals("password", normalised);
        assertEquals(1, trie.findAll(normalised).size());
    }
}
