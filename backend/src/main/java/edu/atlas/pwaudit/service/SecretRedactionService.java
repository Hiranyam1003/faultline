package edu.atlas.pwaudit.service;

import edu.atlas.pwaudit.algo.AhoCorasick;
import edu.atlas.pwaudit.index.PasswordIndex;
import edu.atlas.pwaudit.store.SortedHashStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds passwords and other secrets in a document (chat export, meeting notes, config file) and
 * replaces each with a placeholder before the document is shared.
 *
 * 1. Aho–Corasick scans the lower-cased text once for every credential cue ("password", "pwd", "pin",
 *    "api key") and every known secret-token prefix ("ghp_", "akia", "xoxb-").
 * 2. After a cue, the value is the token following a separator (":", "=", "is", "was") on the same
 *    line, or the very next token when it is password-shaped. Common words after a cue ("reset",
 *    "policy") are not values. A prefix match takes the whole token when it is long enough.
 * 3. Every other password-shaped token (letters plus digits or symbols, 6–64 characters) is checked
 *    against the 1M leaked passwords: the Bloom filter answers most of them in memory, and only Bloom
 *    hits are confirmed by binary search on disk.
 * 4. Overlapping findings keep the earliest, then the cue-based one. The same secret always gets the
 *    same placeholder, e.g. [PASSWORD-1]. Secret values are never returned, only a masked preview.
 */
@Service
public class SecretRedactionService {

    static final int MAX_CHARS = 1_000_000;
    private static final int SEPARATOR_WINDOW = 48;

    private enum Kind { PASSWORD, PIN, OTP, API_KEY, TOKEN, SECRET, LEAKED_PASSWORD }

    /** A cue: either a word that a value follows, or a prefix that starts the secret itself. */
    private record Cue(String text, Kind kind, boolean prefix, int minRest) {}

    private static final List<Cue> CUES = List.of(
            value("password", Kind.PASSWORD), value("passwd", Kind.PASSWORD), value("passcode", Kind.PASSWORD),
            value("pwd", Kind.PASSWORD), value("pass", Kind.PASSWORD), value("pw", Kind.PASSWORD),
            value("credentials", Kind.PASSWORD), value("wifi key", Kind.PASSWORD),
            value("wi-fi key", Kind.PASSWORD),
            value("pin", Kind.PIN), value("otp", Kind.OTP), value("one-time code", Kind.OTP),
            value("api key", Kind.API_KEY), value("api_key", Kind.API_KEY), value("apikey", Kind.API_KEY),
            value("access key", Kind.API_KEY), value("token", Kind.TOKEN), value("bearer", Kind.TOKEN),
            value("secret", Kind.SECRET), value("client_secret", Kind.SECRET), value("private key", Kind.SECRET),
            prefix("ghp_", Kind.TOKEN, 30), prefix("github_pat_", Kind.TOKEN, 30), prefix("gho_", Kind.TOKEN, 30),
            prefix("xoxb-", Kind.TOKEN, 20), prefix("xoxp-", Kind.TOKEN, 20), prefix("akia", Kind.API_KEY, 16),
            prefix("aiza", Kind.API_KEY, 30), prefix("sk_live_", Kind.API_KEY, 20), prefix("sk-", Kind.API_KEY, 30));

    /** Words that follow a cue but are not its value ("password reset", "token expired"). */
    private static final Set<String> NOT_VALUES = Set.of("reset", "change", "changed", "changes", "policy", "policies",
            "manager", "managers", "expired", "expires", "expiry", "hint", "field", "protected", "required", "strength",
            "rotation", "rotate", "rotated", "please", "will", "should", "must", "the", "a", "an", "your", "my", "his",
            "her", "our", "their", "for", "to", "and", "or", "of", "in", "on", "with", "not", "same", "new", "old",
            "here", "there", "below", "above", "attached", "shared", "sent", "again", "later", "soon", "yet", "it",
            "this", "that", "is", "was", "are", "were", "be", "been", "set", "page", "screen", "link", "email",
            "updated", "update", "issue", "issues", "problem", "problems", "length", "rules", "complexity", "number",
            "code", "only", "has", "have", "had", "via", "from", "by", "at", "as", "you", "we", "they", "i");

    private static final Set<String> SEPARATOR_WORDS = Set.of("is", "was", "are", "=", ":", "-", "->", "=>");

    public record Finding(String placeholder, String kind, int line, int column, int length, String masked,
                          String reason, String breachStatus, Integer leakRank, String verdict) {}

    public record Stats(int characters, int lines, int cuePatterns, int automatonStates, int cueMatches,
                        int tokensChecked, int bloomHits, int diskLookups, int bloomFalsePositives, double millis) {}

    public record Result(String redacted, List<Finding> findings, Map<String, Integer> byKind, Stats stats) {}

    private record Span(int start, int end, Kind kind, String reason, int priority) {}

    private final PasswordIndex index;
    private final AuditService auditService;
    private final AhoCorasick automaton;

    public SecretRedactionService(PasswordIndex index, AuditService auditService) {
        this.index = index;
        this.auditService = auditService;
        this.automaton = new AhoCorasick(CUES.stream().map(Cue::text).toList());
    }

    public Result redact(String text) {
        if (text == null || text.isBlank()) throw new InvalidInputException("Paste some text or choose a file to scan.");
        if (text.length() > MAX_CHARS) throw new InvalidInputException("Text is longer than 1,000,000 characters.");
        long started = System.nanoTime();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.length() != text.length()) lower = perCharLower(text);

        List<Span> spans = new ArrayList<>();
        List<AhoCorasick.Match> matches = automaton.findAll(lower);
        for (AhoCorasick.Match m : matches) {
            Cue cue = CUES.get(m.pattern());
            if (!boundaryBefore(lower, m.start())) continue;
            Span span = cue.prefix() ? prefixSpan(text, m, cue) : valueSpan(text, m, cue);
            if (span != null) spans.add(span);
        }

        int[] counts = new int[4];
        spans.addAll(leakedTokens(text, counts));
        List<Span> kept = resolveOverlaps(spans);

        List<Integer> lineStarts = lineStarts(text);
        Map<String, String> placeholders = new HashMap<>();
        Map<String, Integer> perKind = new HashMap<>();
        Map<String, Integer> byKind = new LinkedHashMap<>();
        List<Finding> findings = new ArrayList<>();
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (Span s : kept) {
            String secret = text.substring(s.start(), s.end());
            String label = s.kind().name().replace('_', '-');
            String placeholder = placeholders.computeIfAbsent(secret,
                    k -> "[" + label + "-" + perKind.merge(label, 1, Integer::sum) + "]");
            out.append(text, cursor, s.start()).append(placeholder);
            cursor = s.end();
            byKind.merge(label, 1, Integer::sum);

            AuditResult audit = auditOrNull(secret);
            int line = lineOf(lineStarts, s.start());
            findings.add(new Finding(placeholder, label, line + 1, s.start() - lineStarts.get(line) + 1,
                    secret.length(), mask(secret), s.reason(), audit == null ? null : audit.breachStatus().name(),
                    audit == null ? null : audit.leakRank(), audit == null ? null : audit.verdict()));
        }
        out.append(text, cursor, text.length());

        Stats stats = new Stats(text.length(), lineStarts.size(), CUES.size(), automaton.stateCount(),
                matches.size(), counts[0], counts[1], counts[2], counts[3],
                Math.round((System.nanoTime() - started) / 1e4) / 100.0);
        return new Result(out.toString(), findings, byKind, stats);
    }

    /**
     * The value of a cue: the token after the first separator (":", "=", "is", "was") within
     * SEPARATOR_WINDOW characters on the same line, so "password for the VPN is X" finds X. With no
     * separator, the token right after the cue counts only when it is password-shaped ("pwd X7#k").
     */
    private static Span valueSpan(String text, AhoCorasick.Match m, Cue cue) {
        int end = m.end();
        if (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) return null;
        int lineEnd = text.indexOf('\n', end);
        if (lineEnd < 0) lineEnd = text.length();
        String cueText = text.substring(m.start(), m.end());

        int[] first = null;
        boolean takeNext = false;
        for (int[] tok = nextToken(text, end, lineEnd); tok != null; tok = nextToken(text, tok[1], lineEnd)) {
            String word = text.substring(tok[0], tok[1]).toLowerCase(Locale.ROOT);
            if (isSeparatorOnly(word)) {
                takeNext = true;
                continue;
            }
            if (takeNext || word.startsWith(":") || word.startsWith("=")) {
                if (word.replaceFirst("^[:=]+", "").startsWith("[")) return null;
                int[] value = trimToken(text, tok[0], tok[1]);
                return value == null ? null : accept(value, cue, "after “" + cueText + "”", text);
            }
            if (tok[0] - end > SEPARATOR_WINDOW) break;
            if (SEPARATOR_WORDS.contains(word) || word.endsWith(":") || word.endsWith("=")) {
                takeNext = true;
                continue;
            }
            if (first == null) first = tok;
        }
        if (first == null) return null;
        int[] value = trimToken(text, first[0], first[1]);
        if (value == null || !passwordShaped(text.substring(value[0], value[1]), cue.kind())) return null;
        return accept(value, cue, "next to “" + cueText + "”", text);
    }

    private static Span accept(int[] value, Cue cue, String reason, String text) {
        if (!plausibleValue(text.substring(value[0], value[1]), cue.kind())) return null;
        return new Span(value[0], value[1], cue.kind(), reason, 0);
    }

    private static Span prefixSpan(String text, AhoCorasick.Match m, Cue cue) {
        int end = m.end();
        while (end < text.length() && isTokenChar(text.charAt(end))) end++;
        if (end - m.end() < cue.minRest()) return null;
        return new Span(m.start(), end, cue.kind(), "starts with “" + text.substring(m.start(), m.end()) + "”, a known key format", 0);
    }

    /** Password-shaped tokens with no cue, kept only when the exact token is a confirmed leaked password. */
    private List<Span> leakedTokens(String text, int[] counts) {
        List<Span> spans = new ArrayList<>();
        int pos = 0;
        while (true) {
            int[] tok = nextToken(text, pos, text.length());
            if (tok == null) break;
            pos = tok[1];
            int[] t = trimToken(text, tok[0], tok[1]);
            if (t == null) continue;
            String token = text.substring(t[0], t[1]);
            if (token.length() < 6 || token.length() > 64 || !passwordShaped(token, Kind.LEAKED_PASSWORD)) continue;
            if (token.contains("@") && token.indexOf('.', token.indexOf('@')) > 0) continue;
            if (token.contains("://") || token.startsWith("www.")) continue;
            counts[0]++;
            byte[] hash = PasswordIndex.sha1(token);
            if (!index.bloom().mightContain(hash)) continue;
            counts[1]++;
            counts[2]++;
            SortedHashStore.Lookup lookup = index.store().lookup(hash);
            if (!lookup.found()) {
                counts[3]++;
                continue;
            }
            spans.add(new Span(t[0], t[1], Kind.LEAKED_PASSWORD, "in the leaked list (#" + lookup.rank() + ")", 1));
        }
        return spans;
    }

    private static List<Span> resolveOverlaps(List<Span> spans) {
        spans.sort(Comparator.comparingInt(Span::start).thenComparingInt(Span::priority)
                .thenComparing(Comparator.comparingInt(Span::end).reversed()));
        List<Span> kept = new ArrayList<>();
        int lastEnd = -1;
        for (Span s : spans) {
            if (s.start() < lastEnd) continue;
            kept.add(s);
            lastEnd = s.end();
        }
        return kept;
    }

    private AuditResult auditOrNull(String secret) {
        try {
            return auditService.audit(secret, null);
        } catch (InvalidInputException e) {
            return null;
        }
    }

    /** Letters plus a digit or symbol (or all digits for a PIN/OTP), the shape of a typed password. */
    static boolean passwordShaped(String s, Kind kind) {
        if (kind == Kind.PIN || kind == Kind.OTP) return s.length() >= 4 && s.length() <= 8 && s.chars().allMatch(Character::isDigit);
        boolean letter = false, other = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) letter = true;
            else if (!Character.isWhitespace(c)) other = true;
        }
        return letter && other && s.length() >= 4;
    }

    private static boolean plausibleValue(String s, Kind kind) {
        if (kind == Kind.PIN || kind == Kind.OTP) return passwordShaped(s, kind);
        if (s.length() < 4 || s.length() > 128) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.chars().allMatch(c -> c == '*' || c == 'x' || c == '•' || c == '.')) return false;
        return !NOT_VALUES.contains(lower);
    }

    /** Next run of non-whitespace characters in [from, limit), as {start, end}, or null. */
    private static int[] nextToken(String text, int from, int limit) {
        int start = from;
        while (start < limit && Character.isWhitespace(text.charAt(start))) start++;
        if (start >= limit) return null;
        int end = start;
        while (end < limit && !Character.isWhitespace(text.charAt(end))) end++;
        return new int[]{start, end};
    }

    /** Drops wrapping quotes, brackets, separators and trailing sentence punctuation. */
    private static int[] trimToken(String text, int start, int end) {
        while (start < end && "\"'`([{<:=".indexOf(text.charAt(start)) >= 0) start++;
        while (end > start && "\"'`)]}>,.;:".indexOf(text.charAt(end - 1)) >= 0) end--;
        return end > start ? new int[]{start, end} : null;
    }

    private static boolean isSeparatorOnly(String word) {
        return word.chars().allMatch(c -> c == ':' || c == '=' || c == '-' || c == '>');
    }

    private static boolean boundaryBefore(String lower, int start) {
        return start == 0 || !Character.isLetterOrDigit(lower.charAt(start - 1));
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }

    private static String mask(String secret) {
        return secret.charAt(0) + "•".repeat(Math.min(secret.length() - 1, 11));
    }

    private static List<Integer> lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        return starts;
    }

    /** 0-based line containing pos: binary search over the line start offsets. */
    private static int lineOf(List<Integer> starts, int pos) {
        int lo = 0, hi = starts.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts.get(mid) <= pos) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private static String perCharLower(String text) {
        char[] out = new char[text.length()];
        for (int i = 0; i < out.length; i++) out[i] = Character.toLowerCase(text.charAt(i));
        return new String(out);
    }

    private static Cue value(String text, Kind kind) {
        return new Cue(text, kind, false, 0);
    }

    private static Cue prefix(String text, Kind kind, int minRest) {
        return new Cue(text, kind, true, minRest);
    }
}
