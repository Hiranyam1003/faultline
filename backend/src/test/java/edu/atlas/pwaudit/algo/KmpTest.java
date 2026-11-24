package edu.atlas.pwaudit.algo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KmpTest {

    @Test
    void prefixFunctionKnownValues() {
        assertArrayEquals(new int[]{0, 0, 1, 2, 3, 4, 0}, Kmp.prefixFunction("abababc"));
        assertArrayEquals(new int[]{0, 1, 2, 3}, Kmp.prefixFunction("aaaa"));
        assertArrayEquals(new int[0], Kmp.prefixFunction(""));
    }

    @Test
    void matchesAtStartMiddleAndEnd() {
        assertEquals(List.of(0, 6, 12), Kmp.allMatches("alice-alice-alice", "alice"));
        assertEquals(0, Kmp.indexOf("john1990", "john"));
        assertEquals(4, Kmp.indexOf("1990john", "john"));
    }

    @Test
    void overlappingMatches() {
        assertEquals(List.of(0, 1, 2), Kmp.allMatches("aaaa", "aa"));
        assertEquals(List.of(0, 2), Kmp.allMatches("ababa", "aba"));
    }

    @Test
    void boundaryEmptyAndLongerPattern() {
        assertThrows(IllegalArgumentException.class, () -> Kmp.allMatches("abc", ""));
        assertThrows(IllegalArgumentException.class, () -> Kmp.indexOf("abc", ""));
        assertTrue(Kmp.allMatches("ab", "abc").isEmpty());
        assertEquals(-1, Kmp.indexOf("", "a"));
    }

    @Test
    void smallestPeriod() {
        assertEquals(3, Kmp.smallestPeriod("abcabcabc"));
        assertEquals(1, Kmp.smallestPeriod("aaaa"));
        assertEquals(4, Kmp.smallestPeriod("abcd"));
        assertEquals(5, Kmp.smallestPeriod("abcab"), "partial repeat is not a full period");
        assertEquals(0, Kmp.smallestPeriod(""));
    }

    @Test
    void longestRepeatedPrefix() {
        assertEquals(new Kmp.Repetition("abc", 2, 6), Kmp.longestRepeatedPrefix("abcabc1"));
        assertEquals(new Kmp.Repetition("a", 4, 4), Kmp.longestRepeatedPrefix("aaaa"));
        assertNull(Kmp.longestRepeatedPrefix("abcd"));
        assertNull(Kmp.longestRepeatedPrefix(""));
    }

    @Test
    void adversarialInputAgreesWithNaive() {
        String text = "a".repeat(20_000) + "b";
        String pattern = "a".repeat(500) + "b";
        assertEquals(List.of(20_000 - 500), Kmp.allMatches(text, pattern));
        assertEquals(text.indexOf(pattern), Kmp.indexOf(text, pattern));
    }
}
