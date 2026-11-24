package edu.atlas.pwaudit.algo;

import edu.atlas.pwaudit.algo.AhoCorasick.Match;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AhoCorasickTest {

    private static Set<String> found(AhoCorasick ac, String text) {
        Set<String> out = new HashSet<>();
        for (Match m : ac.findAll(text)) out.add(ac.pattern(m.pattern()) + "@" + m.start());
        return out;
    }

    /** The textbook example: "he", "she", "his", "hers" in "ushers". */
    @Test
    void classicExample() {
        AhoCorasick ac = new AhoCorasick(List.of("he", "she", "his", "hers"));
        assertEquals(Set.of("she@1", "he@2", "hers@2"), found(ac, "ushers"));
    }

    @Test
    void patternThatIsASuffixOfAnotherIsReportedThroughOutputLinks() {
        AhoCorasick ac = new AhoCorasick(List.of("password", "word", "or", "d"));
        assertEquals(Set.of("password@0", "word@4", "or@5", "d@7"), found(ac, "password"));
    }

    @Test
    void overlappingAndRepeatedOccurrences() {
        AhoCorasick ac = new AhoCorasick(List.of("aa", "aaa"));
        List<Match> matches = ac.findAll("aaaa");
        assertEquals(5, matches.size());
        assertEquals(new Match(0, 2, 0), matches.get(0));
    }

    @Test
    void emptyTextAndNoMatches() {
        AhoCorasick ac = new AhoCorasick(List.of("pin", "pwd"));
        assertTrue(ac.findAll("").isEmpty());
        assertTrue(ac.findAll("nothing to see").isEmpty());
        assertTrue(new AhoCorasick(List.of()).findAll("abc").isEmpty());
    }

    @Test
    void rejectsEmptyPatternAndKeepsFirstDuplicate() {
        assertThrows(IllegalArgumentException.class, () -> new AhoCorasick(List.of("ok", "")));
        AhoCorasick ac = new AhoCorasick(List.of("token", "token"));
        assertEquals(List.of(new Match(4, 9, 0)), ac.findAll("new token"));
    }

    @Test
    void handlesNonAsciiCharacters() {
        AhoCorasick ac = new AhoCorasick(List.of("पासवर्ड", "café"));
        assertEquals(Set.of("पासवर्ड@5", "café@0"), found(ac, "café पासवर्ड"));
    }

    @Test
    void agreesWithNaiveSearchOnRandomInput() {
        Random random = new Random(5);
        for (int trial = 0; trial < 300; trial++) {
            List<String> patterns = new ArrayList<>();
            for (int p = 0; p < 1 + random.nextInt(8); p++) patterns.add(randomString(random, 1 + random.nextInt(4)));
            String text = randomString(random, random.nextInt(60));
            AhoCorasick ac = new AhoCorasick(patterns);

            Set<String> expected = new HashSet<>();
            for (String p : new HashSet<>(patterns)) {
                for (int i = text.indexOf(p); i >= 0; i = text.indexOf(p, i + 1)) expected.add(p + "@" + i);
            }
            assertEquals(expected, found(ac, text), "trial " + trial);
        }
    }

    @Test
    void matchesComeOutInEndOrder() {
        AhoCorasick ac = new AhoCorasick(List.of("ab", "b", "abc", "c"));
        List<Match> matches = ac.findAll("abcabc");
        List<Match> sorted = new ArrayList<>(matches);
        sorted.sort(Comparator.comparingInt(Match::end));
        assertEquals(sorted.stream().map(Match::end).toList(), matches.stream().map(Match::end).toList());
        assertEquals(8, matches.size());
    }

    private static String randomString(Random random, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append((char) ('a' + random.nextInt(3)));
        return sb.toString();
    }
}
