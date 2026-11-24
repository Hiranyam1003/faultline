package edu.atlas.pwaudit.service;

import edu.atlas.pwaudit.service.AuditResult.Pattern;
import edu.atlas.pwaudit.service.AuditResult.Repeat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Explainable guess-count entropy estimate.
 *
 * Characters not covered by a pattern cost log2(charset) bits each (brute force). A pattern
 * segment costs log2(size of its dictionary category) bits (0 for the username), plus 1 bit for leetspeak and 1 bit
 * for any upper-case letter in it, because an attacker enumerates dictionaries, not characters.
 * A repeated run costs the block's entropy plus log2(repeat count).
 */
final class StrengthScorer {

    private static final int[] SCORE_THRESHOLDS = {28, 36, 60, 80};

    private StrengthScorer() {}

    static double entropyBits(String password, List<Pattern> patterns, Repeat repeat, Map<String, Integer> categorySizes) {
        double charsetBits = log2(charsetSize(password));
        if (repeat == null) return entropy(password, 0, password.length(), patterns, categorySizes, charsetBits);
        int blockEnd = repeat.start() + repeat.block().length();
        int runEnd = repeat.start() + repeat.block().length() * repeat.count();
        return entropy(password, 0, repeat.start(), patterns, categorySizes, charsetBits)
                + entropy(password, repeat.start(), blockEnd, patterns, categorySizes, charsetBits)
                + log2(repeat.count())
                + entropy(password, runEnd, password.length(), patterns, categorySizes, charsetBits);
    }

    /**
     * Chooses the non-overlapping set of patterns an attacker would exploit: the cover of the
     * password with the lowest total guessing cost. Dynamic programming over suffixes:
     * best[i] = min(charsetBits + best[i+1], cost(p) + best[p.end] for each pattern p starting at i).
     * O(n + number of candidates) after grouping candidates by start position.
     */
    static List<Pattern> cheapestCover(String password, List<Pattern> candidates, Map<String, Integer> categorySizes) {
        int n = password.length();
        double charsetBits = log2(charsetSize(password));
        List<List<Pattern>> byStart = new ArrayList<>(n);
        for (int i = 0; i < n; i++) byStart.add(new ArrayList<>());
        for (Pattern p : candidates) byStart.get(p.start()).add(p);

        double[] best = new double[n + 1];
        Pattern[] choice = new Pattern[n];
        for (int i = n - 1; i >= 0; i--) {
            best[i] = charsetBits + best[i + 1];
            for (Pattern p : byStart.get(i)) {
                double cost = patternBits(password, p, categorySizes) + best[p.end()];
                if (cost < best[i] - 1e-9) {
                    best[i] = cost;
                    choice[i] = p;
                }
            }
        }
        List<Pattern> chosen = new ArrayList<>();
        for (int i = 0; i < n; ) {
            if (choice[i] == null) {
                i++;
            } else {
                chosen.add(choice[i]);
                i = choice[i].end();
            }
        }
        return chosen;
    }

    /** 0 = very weak … 4 = strong. Any confirmed breach is 0 regardless of entropy. */
    static int score(double entropyBits, boolean breached) {
        if (breached) return 0;
        int score = 0;
        while (score < SCORE_THRESHOLDS.length && entropyBits >= SCORE_THRESHOLDS[score]) score++;
        return score;
    }

    private static double entropy(String password, int from, int to, List<Pattern> patterns,
                                  Map<String, Integer> categorySizes, double charsetBits) {
        double bits = 0;
        int i = from;
        while (i < to) {
            Pattern p = patternStartingAt(patterns, i, to);
            if (p == null) {
                bits += charsetBits;
                i++;
            } else {
                bits += patternBits(password, p, categorySizes);
                i = p.end();
            }
        }
        return bits;
    }

    private static double patternBits(String password, Pattern p, Map<String, Integer> categorySizes) {
        // An attacker targeting this account already knows the username, so it costs nothing to guess.
        double bits = p.category().equals("username") ? 0 : log2(Math.max(2, categorySizes.getOrDefault(p.category(), 2)));
        if (p.leet()) bits += 1;
        if (hasUpper(password, p.start(), p.end())) bits += 1;
        return bits;
    }

    private static Pattern patternStartingAt(List<Pattern> patterns, int start, int limit) {
        for (Pattern p : patterns) if (p.start() == start && p.end() <= limit) return p;
        return null;
    }

    static int charsetSize(String password) {
        boolean lower = false, upper = false, digit = false, symbol = false, other = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (c >= 'a' && c <= 'z') lower = true;
            else if (c >= 'A' && c <= 'Z') upper = true;
            else if (c >= '0' && c <= '9') digit = true;
            else if (c >= 0x20 && c <= 0x7E) symbol = true;
            else other = true;
        }
        int size = (lower ? 26 : 0) + (upper ? 26 : 0) + (digit ? 10 : 0) + (symbol ? 33 : 0) + (other ? 100 : 0);
        return Math.max(size, 2);
    }

    private static boolean hasUpper(String s, int from, int to) {
        for (int i = from; i < to; i++) if (Character.isUpperCase(s.charAt(i))) return true;
        return false;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
