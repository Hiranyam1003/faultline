package edu.atlas.pwaudit.service;

import edu.atlas.pwaudit.algo.Kmp;
import edu.atlas.pwaudit.algo.LeetNormalizer;
import edu.atlas.pwaudit.algo.Trie;
import edu.atlas.pwaudit.index.PasswordIndex;
import edu.atlas.pwaudit.service.AuditResult.BreachStatus;
import edu.atlas.pwaudit.service.AuditResult.Pattern;
import edu.atlas.pwaudit.service.AuditResult.Repeat;
import edu.atlas.pwaudit.store.SortedHashStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Audit pipeline for one password:
 * Bloom precheck → exact disk confirmation (only on a Bloom hit) → trie pattern scan
 * (plain + leetspeak) → KMP username and repetition checks → entropy score + advice.
 */
@Service
public class AuditService {

    static final int MAX_PASSWORD_LENGTH = 128;
    static final int MAX_USERNAME_LENGTH = 64;
    private static final int MIN_USERNAME_MATCH = 3;
    private static final int MIN_REPEAT_LENGTH = 4;
    private static final String[] VERDICTS = {"Very weak", "Weak", "Fair", "Strong", "Very strong"};

    private final PasswordIndex index;

    public AuditService(PasswordIndex index) {
        this.index = index;
    }

    public AuditResult audit(String password, String username) {
        validatePassword(password);
        List<String> userTokens = usernameTokens(username);

        long t0 = System.nanoTime();
        byte[] hash = PasswordIndex.sha1(password);
        boolean bloomHit = index.bloom().mightContain(hash);
        long t1 = System.nanoTime();
        SortedHashStore.Lookup lookup = bloomHit ? index.store().lookup(hash) : new SortedHashStore.Lookup(false, -1, 0);
        long t2 = System.nanoTime();

        BreachStatus status = !bloomHit ? BreachStatus.NOT_PRESENT
                : lookup.found() ? BreachStatus.CONFIRMED : BreachStatus.BLOOM_FALSE_POSITIVE;

        String lower = LeetNormalizer.lowerCase(password);
        String leet = LeetNormalizer.normalize(password);
        List<Pattern> candidates = new ArrayList<>();
        for (Trie.Match m : index.trie().findAll(lower)) {
            candidates.add(new Pattern(m.start(), m.end(), m.token(), m.category(), false));
        }
        for (Trie.Match m : index.trie().findAll(leet)) {
            if (!lower.regionMatches(m.start(), leet, m.start(), m.length())) {
                candidates.add(new Pattern(m.start(), m.end(), m.token(), m.category(), true));
            }
        }
        long t3 = System.nanoTime();

        boolean containsUsername = false;
        for (String user : userTokens) {
            for (int start : Kmp.allMatches(lower, user)) {
                candidates.add(new Pattern(start, start + user.length(), user, "username", false));
                containsUsername = true;
            }
            for (int start : Kmp.allMatches(leet, user)) {
                if (!lower.regionMatches(start, leet, start, user.length())) {
                    candidates.add(new Pattern(start, start + user.length(), user, "username", true));
                    containsUsername = true;
                }
            }
        }
        Repeat repeat = longestRepeat(lower);
        long t4 = System.nanoTime();

        List<Pattern> patterns = StrengthScorer.cheapestCover(password, candidates, index.categorySizes());
        boolean breached = status == BreachStatus.CONFIRMED;
        double entropy = StrengthScorer.entropyBits(password, patterns, repeat, index.categorySizes());
        int score = StrengthScorer.score(entropy, breached);

        return new AuditResult(
                password.length(), status, bloomHit, lookup.probes(), lookup.found() ? lookup.rank() : null,
                patterns, repeat, containsUsername, Math.round(entropy * 10) / 10.0, score,
                breached ? "Breached" : VERDICTS[score],
                suggestions(password, status, lookup.rank(), patterns, repeat, containsUsername, score),
                new AuditResult.Timings(micros(t0, t1), micros(t1, t2), micros(t2, t3), micros(t3, t4)),
                bloomProbe(hash));
    }

    private AuditResult.BloomProbe bloomProbe(byte[] hash) {
        List<Long> positions = new ArrayList<>();
        List<Boolean> set = new ArrayList<>();
        for (long bit : index.bloom().probePositions(hash)) {
            positions.add(bit);
            set.add(index.bloom().isSet(bit));
        }
        return new AuditResult.BloomProbe(HexFormat.of().formatHex(hash), index.bloom().bitCount(), positions, set);
    }

    /**
     * Longest run of a block repeated back-to-back anywhere in the text: the KMP repeated-prefix
     * check applied at every start position. O(n²) overall, fine for passwords of at most 128 chars.
     */
    static Repeat longestRepeat(String text) {
        Repeat best = null;
        int bestLength = MIN_REPEAT_LENGTH - 1;
        for (int start = 0; start + bestLength < text.length(); start++) {
            Kmp.Repetition rep = Kmp.longestRepeatedPrefix(text.substring(start));
            if (rep != null && rep.length() > bestLength) {
                best = new Repeat(start, rep.block(), rep.count());
                bestLength = rep.length();
            }
        }
        return best;
    }

    private static List<String> suggestions(String password, BreachStatus status, int rank, List<Pattern> patterns,
                                            Repeat repeat, boolean containsUsername, int score) {
        List<String> tips = new ArrayList<>();
        if (status == BreachStatus.CONFIRMED) {
            tips.add("This exact password is in the leaked list (rank #" + rank + " by frequency). Attackers try these first; never use it.");
        }
        if (containsUsername) tips.add("Remove your username from the password.");
        if (score >= 3 && status != BreachStatus.CONFIRMED) {
            if (tips.isEmpty()) tips.add("Looks strong. Keep it unique to this account and store it in a password manager.");
            return tips;
        }
        Set<String> categories = new LinkedHashSet<>();
        boolean anyLeet = false;
        for (Pattern p : patterns) {
            categories.add(p.category());
            anyLeet |= p.leet();
        }
        if (categories.contains("keyboard")) tips.add("Avoid keyboard walks and sequences like qwerty or 12345.");
        if (categories.contains("year")) tips.add("Avoid years; birth and graduation years are guessed early.");
        if (categories.contains("leaked")) tips.add("Part of this password is a commonly leaked password.");
        if (categories.contains("name") || categories.contains("english")) {
            tips.add("Dictionary words and names are cheap to guess; combine several unrelated words instead.");
        }
        if (anyLeet) tips.add("Leetspeak swaps (@ for a, 0 for o) are built into cracking tools and add almost no strength.");
        if (repeat != null) tips.add("Avoid repeating the same block (\"" + repeat.block() + "\" × " + repeat.count() + ").");
        if (password.length() < 12) tips.add("Use at least 12 characters; length adds strength fastest.");
        if (tips.isEmpty()) tips.add("Add length or unrelated words to raise the estimated strength.");
        return tips;
    }

    static void validatePassword(String password) {
        if (password == null || password.isEmpty()) throw new InvalidInputException("Password must not be empty.");
        if (password.isBlank()) throw new InvalidInputException("Password must not be only whitespace.");
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new InvalidInputException("Password must be at most " + MAX_PASSWORD_LENGTH + " characters.");
        }
        if (hasControlChars(password)) throw new InvalidInputException("Password contains control characters.");
    }

    /**
     * Lower-cased username (local part of an email) plus its parts split on separators and digits,
     * e.g. "priya.nair@x.com" → [priya.nair, priya, nair]. Tokens under 3 characters are dropped.
     */
    static List<String> usernameTokens(String username) {
        if (username == null || username.isBlank()) return List.of();
        String u = username.trim();
        if (u.length() > MAX_USERNAME_LENGTH) {
            throw new InvalidInputException("Username must be at most " + MAX_USERNAME_LENGTH + " characters.");
        }
        if (hasControlChars(u)) throw new InvalidInputException("Username contains control characters.");
        int at = u.indexOf('@');
        if (at >= 0) u = u.substring(0, at);
        u = LeetNormalizer.lowerCase(u);
        Set<String> tokens = new LinkedHashSet<>();
        tokens.add(u);
        tokens.addAll(List.of(u.split("[^\\p{L}]+")));
        tokens.removeIf(t -> t.length() < MIN_USERNAME_MATCH);
        return List.copyOf(tokens);
    }

    private static boolean hasControlChars(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i))) return true;
        return false;
    }

    private static double micros(long from, long to) {
        return Math.round((to - from) / 100.0) / 10.0;
    }
}
