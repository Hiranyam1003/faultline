package edu.atlas.pwaudit.algo;

import edu.atlas.pwaudit.algo.GuessGenerator.Base;
import edu.atlas.pwaudit.algo.GuessGenerator.Guess;
import edu.atlas.pwaudit.algo.GuessGenerator.Rule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GuessGeneratorTest {

    private static final List<Base> BASES = List.of(
            new Base("password", 0.5, "leaked", 1), new Base("priya", 0.3, "name", 1),
            new Base("dragon", 0.15, "leaked", 2), new Base("password", 0.05, "english", 1));

    private static final List<Rule> RULES = List.of(
            new Rule("as is", 0.6, w -> w),
            new Rule("+ 1", 0.3, w -> w + "1"),
            new Rule("Capitalised + 2003!", 0.1, w -> Character.toUpperCase(w.charAt(0)) + w.substring(1) + "2003!"));

    private static List<Guess> drain(GuessGenerator g) {
        List<Guess> out = new ArrayList<>();
        for (Guess guess = g.next(); guess != null; guess = g.next()) out.add(guess);
        return out;
    }

    @Test
    void guessesComeOutInNonIncreasingProbability() {
        List<Guess> guesses = drain(new GuessGenerator(BASES, RULES, 100, 0.001));
        for (int i = 1; i < guesses.size(); i++) {
            assertTrue(guesses.get(i).probability() <= guesses.get(i - 1).probability() + 1e-12, "at " + i);
        }
        assertEquals("password", guesses.get(0).text());
    }

    @Test
    void everyCellIsVisitedOnceAndRepeatStringsAreSkipped() {
        GuessGenerator g = new GuessGenerator(BASES, RULES, 100, 1e-6);
        List<Guess> guesses = drain(g);
        // 4 bases × 3 rules = 12 cells; the English "password" repeats 3 strings already produced.
        assertEquals(9, guesses.size());
        assertEquals(3, g.skippedRepeats());
        Set<String> texts = new HashSet<>();
        for (Guess guess : guesses) assertTrue(texts.add(guess.text()), "duplicate " + guess.text());
    }

    @Test
    void producesMangledNamePasswords() {
        List<String> texts = drain(new GuessGenerator(BASES, RULES, 100, 0.001)).stream().map(Guess::text).toList();
        assertTrue(texts.contains("Priya2003!"));
        assertTrue(texts.contains("dragon1"));
    }

    @Test
    void inputOrderDoesNotMatter() {
        List<Base> reversed = new ArrayList<>(BASES);
        Collections.reverse(reversed);
        List<String> a = drain(new GuessGenerator(BASES, RULES, 100, 1e-6)).stream().map(Guess::text).toList();
        List<String> b = drain(new GuessGenerator(reversed, RULES, 100, 1e-6)).stream().map(Guess::text).toList();
        assertEquals(new HashSet<>(a), new HashSet<>(b));
        assertEquals(a.get(0), b.get(0));
    }

    @Test
    void inapplicableRulesAreSkipped() {
        List<Rule> rules = List.of(new Rule("as is", 0.9, w -> w), new Rule("never", 0.1, w -> null));
        List<Guess> guesses = drain(new GuessGenerator(BASES.subList(0, 2), rules, 10, 0.001));
        assertEquals(List.of("password", "priya"), guesses.stream().map(Guess::text).toList());
    }

    @Test
    void emptyInputsProduceNothing() {
        assertNull(new GuessGenerator(List.of(), RULES, 10, 0.01).next());
        assertNull(new GuessGenerator(BASES, List.of(), 10, 0.01).next());
    }

    @Test
    void producedCountTracksGuesses() {
        GuessGenerator g = new GuessGenerator(BASES, RULES, 100, 0.001);
        g.next();
        g.next();
        assertEquals(2, g.produced());
        assertTrue(g.filterBytes() > 0);
    }
}
