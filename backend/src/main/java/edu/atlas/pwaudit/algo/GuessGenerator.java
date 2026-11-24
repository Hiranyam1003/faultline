package edu.atlas.pwaudit.algo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Produces password guesses in decreasing estimated probability, the way a smart attacker orders them.
 *
 * A guess is a base word (leaked password, name, word, ...) with probability p_i, transformed by a
 * mangling rule (Capitalise, append a year, ...) with probability q_j, so it scores p_i · q_j.
 * Bases and rules are each sorted in decreasing order, which turns the problem into walking a sorted
 * matrix in decreasing order. A binary max-heap holds the frontier of (i, j) cells. Popping (i, j)
 * pushes (i, j + 1), and pushes (i + 1, 0) only when j = 0, so every cell is reached exactly once
 * (along (0,0) → (i,0) → (i,j)) and no visited set is needed. Each successor scores no more than
 * its parent, so cells come out in non-increasing order. The cost is O(log h) per guess, where h is
 * the frontier size (at most the number of bases started).
 *
 * Different cells can spell the same string ("password" is both a leaked password and an English
 * word). A Bloom filter over emitted strings skips these repeats in constant memory. A false positive
 * wrongly skips a new guess: the price of not storing millions of strings, and it is counted.
 */
public final class GuessGenerator {

    public record Base(String word, double probability, String source, int rank) {}

    /** {@code transform} returns null when the rule does not apply to a word. */
    public record Rule(String name, double probability, UnaryOperator<String> transform) {}

    public record Guess(String text, double probability, Base base, Rule rule) {}

    private record Cell(int base, int rule, double probability) {}

    private final List<Base> bases;
    private final List<Rule> rules;
    private final BinaryHeap<Cell> frontier = new BinaryHeap<>(Comparator.comparingDouble(Cell::probability));
    private final BloomFilter emitted;
    private final Set<String> exact;
    private long produced;
    private long skippedRepeats;

    /**
     * @param expectedGuesses how many guesses will be drawn; sizes the Bloom filter
     * @param repeatFpr       target false-positive rate of the repeat filter
     */
    public GuessGenerator(List<Base> bases, List<Rule> rules, long expectedGuesses, double repeatFpr) {
        this(bases, rules, expectedGuesses, repeatFpr, false);
    }

    /** @param exactRepeats baseline: remember every emitted string in a HashSet instead of a Bloom filter */
    public GuessGenerator(List<Base> bases, List<Rule> rules, long expectedGuesses, double repeatFpr,
                          boolean exactRepeats) {
        this.bases = new ArrayList<>(bases);
        this.rules = new ArrayList<>(rules);
        this.bases.sort(Comparator.comparingDouble(Base::probability).reversed());
        this.rules.sort(Comparator.comparingDouble(Rule::probability).reversed());
        this.emitted = exactRepeats ? null : BloomFilter.forCapacity(Math.max(1, expectedGuesses), repeatFpr);
        this.exact = exactRepeats ? new HashSet<>() : null;
        if (!this.bases.isEmpty() && !this.rules.isEmpty()) push(0, 0);
    }

    /** The next most likely guess not produced before, or null when every cell has been used. */
    public Guess next() {
        while (!frontier.isEmpty()) {
            Cell cell = frontier.poll();
            if (cell.rule() + 1 < rules.size()) push(cell.base(), cell.rule() + 1);
            if (cell.rule() == 0 && cell.base() + 1 < bases.size()) push(cell.base() + 1, 0);

            Base base = bases.get(cell.base());
            Rule rule = rules.get(cell.rule());
            String text = rule.transform().apply(base.word());
            if (text == null || text.isEmpty()) continue;
            if (isRepeat(text)) {
                skippedRepeats++;
                continue;
            }
            produced++;
            return new Guess(text, cell.probability(), base, rule);
        }
        return null;
    }

    public long produced() { return produced; }
    public long skippedRepeats() { return skippedRepeats; }
    public long filterBytes() { return emitted == null ? 0 : emitted.memoryBytes(); }

    /** Records the string and reports whether it (possibly, for the Bloom filter) was seen before. */
    private boolean isRepeat(String text) {
        if (exact != null) return !exact.add(text);
        byte[] key = text.getBytes(StandardCharsets.UTF_8);
        if (emitted.mightContain(key)) return true;
        emitted.add(key);
        return false;
    }

    private void push(int base, int rule) {
        frontier.insert(new Cell(base, rule, bases.get(base).probability() * rules.get(rule).probability()));
    }
}
