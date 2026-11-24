package edu.atlas.pwaudit.service;

import edu.atlas.pwaudit.algo.AhoCorasick;
import edu.atlas.pwaudit.algo.BloomFilter;
import edu.atlas.pwaudit.algo.GuessGenerator;
import edu.atlas.pwaudit.algo.Kmp;
import edu.atlas.pwaudit.algo.MaxFlow;
import edu.atlas.pwaudit.algo.Trie;
import edu.atlas.pwaudit.index.PasswordIndex;
import edu.atlas.pwaudit.index.Wordlists;
import edu.atlas.pwaudit.store.SortedHashStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Reproducible experiments on three input sizes, each against a baseline:
 * 1. Bloom filter: measured vs theoretical false-positive rate, memory vs an estimated HashSet.
 * 2. Lookup: Bloom precheck + disk confirm vs disk binary search for every query.
 * 3. Pattern scan: trie vs naive per-word substring search, for growing dictionaries.
 * 4. Substring search: KMP vs naive on adversarial input.
 * 5. Guess ordering: heap-ordered best-first guessing vs brute force, accounts cracked per guess budget.
 * 6. Repeat filter: Bloom filter vs HashSet for skipping repeated guesses (memory, time, wrong skips).
 * 7. Attack-graph min cut: Edmonds–Karp vs Ford–Fulkerson (DFS) on synthetic organisations.
 * 8. Multi-pattern search: Aho–Corasick vs KMP once per pattern, for growing pattern sets.
 * Timings are the median of several runs after warm-up; random inputs use fixed seeds.
 */
@Service
public class BenchmarkService {

    private static final int[] SIZES = {10_000, 100_000, 1_000_000};
    private static final double[] FPRS = {0.01, 0.001};
    private static final int NON_MEMBER_QUERIES = 100_000;
    private static final int LOOKUP_QUERIES = 20_000;
    private static final double MEMBER_SHARE = 0.05;
    private static final int RUNS = 5;

    public record BloomRow(int n, double targetFpr, long bits, int hashes, long bloomBytes, long hashSetBytesEstimate,
                           double theoreticalFpr, double measuredFpr, long falsePositives, int queries) {}

    public record LookupRow(int n, int queries, double memberShare, double bloomPlusDiskMillis, double diskOnlyMillis,
                            long bloomPlusDiskProbes, long diskOnlyProbes, int bloomFalsePositives) {}

    public record TrieRow(int dictionaryWords, int passwords, double trieMillis, double naiveMillis,
                          long trieMatches, long naiveMatches) {}

    public record KmpRow(int textLength, int patternLength, double kmpMillis, double naiveMillis, long naiveComparisons) {}

    public record GuessRow(long guesses, int accounts, int bestFirstCracked, int bruteForceCracked) {}

    public record RepeatRow(long guesses, long bloomBytes, long hashSetBytesEstimate, double bloomMillis,
                            double hashSetMillis, long repeatsSkippedExact, long repeatsSkippedBloom,
                            long newGuessesWronglySkipped) {}

    public record FlowRow(int users, int vertices, int edges, int entryAccounts, long minCut, double edmondsKarpMillis,
                          double fordFulkersonMillis, int edmondsKarpPaths, int fordFulkersonPaths) {}

    public record MultiPatternRow(int patterns, int textLength, int automatonStates, double ahoCorasickMillis,
                                  double kmpPerPatternMillis, long ahoCorasickMatches, long kmpMatches) {}

    public record Report(List<BloomRow> bloom, List<LookupRow> lookup, List<TrieRow> trie, List<KmpRow> kmp,
                         List<GuessRow> guessing, List<RepeatRow> repeats, List<FlowRow> flow,
                         List<MultiPatternRow> multiPattern, long elapsedMillis) {}

    private static final long[] GUESS_BUDGETS = {100_000, 1_000_000, 5_000_000};
    private static final long[] REPEAT_SIZES = {250_000, 1_000_000, 2_000_000};
    private static final int[] ORG_SIZES = {100, 1_000, 10_000};
    private static final int[] PATTERN_COUNTS = {10, 100, 1_000};
    private static final int SCAN_TEXT_LENGTH = 100_000;
    private static final String BRUTE_CHARSET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final Path dataDir;
    private final Wordlists wordlists;
    private final GuessModel guessModel;

    public BenchmarkService(@Value("${pwaudit.data-dir}") String dataDir, Wordlists wordlists) {
        this.dataDir = Path.of(dataDir);
        this.wordlists = wordlists;
        this.guessModel = new GuessModel(wordlists);
    }

    public synchronized Report run() {
        long started = System.nanoTime();
        List<String> leaked = readLines(dataDir.resolve("leaked-1M.txt"));
        byte[][] hashes = new byte[leaked.size()][];
        for (int i = 0; i < hashes.length; i++) hashes[i] = PasswordIndex.sha1(leaked.get(i));
        try {
            return new Report(bloomExperiment(leaked, hashes), lookupExperiment(leaked, hashes),
                    trieExperiment(leaked), kmpExperiment(), guessExperiment(), repeatExperiment(), flowExperiment(),
                    multiPatternExperiment(leaked), (System.nanoTime() - started) / 1_000_000);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<BloomRow> bloomExperiment(List<String> leaked, byte[][] hashes) {
        List<BloomRow> rows = new ArrayList<>();
        for (int n : sizes(leaked.size())) {
            Set<String> members = new HashSet<>(leaked.subList(0, n));
            long hashSetBytes = estimateHashSetBytes(leaked.subList(0, n));
            for (double p : FPRS) {
                BloomFilter bloom = BloomFilter.forCapacity(n, p);
                for (int i = 0; i < n; i++) bloom.add(hashes[i]);
                long falsePositives = 0;
                int queries = 0;
                for (int i = 0; queries < NON_MEMBER_QUERIES; i++) {
                    String candidate = "nonmember#" + i;
                    if (members.contains(candidate)) continue;
                    queries++;
                    if (bloom.mightContain(PasswordIndex.sha1(candidate))) falsePositives++;
                }
                rows.add(new BloomRow(n, p, bloom.bitCount(), bloom.hashCount(), bloom.memoryBytes(), hashSetBytes,
                        round(bloom.theoreticalFpr(), 6), round((double) falsePositives / queries, 6),
                        falsePositives, queries));
            }
        }
        return rows;
    }

    List<LookupRow> lookupExperiment(List<String> leaked, byte[][] hashes) throws IOException {
        List<LookupRow> rows = new ArrayList<>();
        Path dir = Files.createTempDirectory("pwaudit-bench");
        try {
            for (int n : sizes(leaked.size())) {
                Set<String> members = new HashSet<>(leaked.subList(0, n));
                BloomFilter bloom = BloomFilter.forCapacity(n, 0.01);
                List<SortedHashStore.Entry> entries = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    bloom.add(hashes[i]);
                    entries.add(new SortedHashStore.Entry(hashes[i], i + 1));
                }
                Path file = dir.resolve("store-" + n + ".bin");
                SortedHashStore.build(entries, file);

                Random random = new Random(42);
                byte[][] queries = new byte[LOOKUP_QUERIES][];
                for (int q = 0, miss = 0; q < LOOKUP_QUERIES; q++) {
                    if (random.nextDouble() < MEMBER_SHARE) {
                        queries[q] = hashes[random.nextInt(n)];
                    } else {
                        String candidate;
                        do candidate = "query-miss#" + miss++; while (members.contains(candidate));
                        queries[q] = PasswordIndex.sha1(candidate);
                    }
                }

                try (SortedHashStore store = SortedHashStore.open(file)) {
                    long[] bloomProbes = new long[1];
                    int[] falsePositives = new int[1];
                    double withBloom = medianMillis(() -> {
                        long probes = 0;
                        int fp = 0;
                        for (byte[] q : queries) {
                            if (!bloom.mightContain(q)) continue;
                            SortedHashStore.Lookup l = store.lookup(q);
                            probes += l.probes();
                            if (!l.found()) fp++;
                        }
                        bloomProbes[0] = probes;
                        falsePositives[0] = fp;
                        return probes;
                    });
                    long[] diskProbes = new long[1];
                    double diskOnly = medianMillis(() -> {
                        long probes = 0;
                        for (byte[] q : queries) probes += store.lookup(q).probes();
                        diskProbes[0] = probes;
                        return probes;
                    });
                    rows.add(new LookupRow(n, LOOKUP_QUERIES, MEMBER_SHARE, withBloom, diskOnly,
                            bloomProbes[0], diskProbes[0], falsePositives[0]));
                }
                Files.delete(file);
            }
        } finally {
            Files.deleteIfExists(dir);
        }
        return rows;
    }

    List<TrieRow> trieExperiment(List<String> leaked) {
        List<String> dictionary = new ArrayList<>();
        for (String name : List.of("keyboard.txt", "years.txt", "names.txt", "english.txt")) {
            for (String w : readLines(dataDir.resolve("dictionary").resolve(name))) {
                String word = w.trim().toLowerCase(Locale.ROOT);
                if (word.length() >= 3) dictionary.add(word);
            }
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(dictionary));
        Random random = new Random(7);
        List<String> passwords = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            passwords.add(leaked.get(leaked.size() / 2 + random.nextInt(leaked.size() / 2)).toLowerCase(Locale.ROOT));
        }

        List<TrieRow> rows = new ArrayList<>();
        for (int size : new int[]{1_000, 10_000, distinct.size()}) {
            List<String> words = distinct.subList(0, Math.min(size, distinct.size()));
            Trie trie = new Trie();
            for (String w : words) trie.insert(w, "dict");
            long[] trieMatches = new long[1];
            double trieMillis = medianMillis(() -> {
                long count = 0;
                for (String p : passwords) count += trie.findAll(p).size();
                trieMatches[0] = count;
                return count;
            });
            long[] naiveMatches = new long[1];
            double naiveMillis = medianMillis(() -> {
                long count = 0;
                for (String p : passwords) {
                    for (String w : words) {
                        for (int from = p.indexOf(w); from >= 0; from = p.indexOf(w, from + 1)) count++;
                    }
                }
                naiveMatches[0] = count;
                return count;
            });
            rows.add(new TrieRow(words.size(), passwords.size(), trieMillis, naiveMillis, trieMatches[0], naiveMatches[0]));
        }
        return rows;
    }

    List<KmpRow> kmpExperiment() {
        List<KmpRow> rows = new ArrayList<>();
        String pattern = "a".repeat(999) + "b";
        for (int n : SIZES) {
            String text = "a".repeat(n);
            double kmpMillis = medianMillis(() -> Kmp.allMatches(text, pattern).size());
            long[] comparisons = new long[1];
            double naiveMillis = medianMillis(() -> {
                long[] result = naiveSearch(text, pattern);
                comparisons[0] = result[1];
                return result[0];
            });
            rows.add(new KmpRow(n, pattern.length(), kmpMillis, naiveMillis, comparisons[0]));
        }
        return rows;
    }

    /**
     * Passwords of a 1,000-person synthetic organisation, attacked two ways: best-first guesses from
     * the heap, and brute force over [a-z0-9] in length-then-alphabetical order. The brute-force guess
     * number of a password is computed directly rather than enumerated.
     */
    List<GuessRow> guessExperiment() {
        List<String> passwords = OrgDemoGenerator.generate(1_000, 7, wordlists).users().stream().map(u -> u[3]).toList();
        Map<String, Integer> remaining = new HashMap<>();
        for (String p : passwords) remaining.merge(p, 1, Integer::sum);
        long last = GUESS_BUDGETS[GUESS_BUDGETS.length - 1];
        List<Long> crackedAt = new ArrayList<>();
        GuessGenerator generator = new GuessGenerator(guessModel.bases(List.of("kestrel", "pune")),
                GuessModel.rules(), last, OrgSimulationService.REPEAT_FILTER_FPR);
        for (GuessGenerator.Guess g = generator.next(); g != null && generator.produced() <= last; g = generator.next()) {
            Integer count = remaining.remove(g.text());
            if (count != null) for (int i = 0; i < count; i++) crackedAt.add(generator.produced());
        }
        List<GuessRow> rows = new ArrayList<>();
        for (long budget : GUESS_BUDGETS) {
            int bestFirst = (int) crackedAt.stream().filter(at -> at <= budget).count();
            int brute = (int) passwords.stream().filter(p -> bruteForceIndex(p) <= budget).count();
            rows.add(new GuessRow(budget, passwords.size(), bestFirst, brute));
        }
        return rows;
    }

    /** 1-based position of a password in brute-force order over BRUTE_CHARSET, or Long.MAX_VALUE if unreachable. */
    static long bruteForceIndex(String password) {
        long before = 0, power = 1;
        for (int len = 1; len < password.length(); len++) {
            power *= BRUTE_CHARSET.length();
            before += power;
            if (before > Long.MAX_VALUE / 64) return Long.MAX_VALUE;
        }
        long rank = 0;
        for (char c : password.toCharArray()) {
            int digit = BRUTE_CHARSET.indexOf(c);
            if (digit < 0) return Long.MAX_VALUE;
            rank = rank * BRUTE_CHARSET.length() + digit;
        }
        return before + rank + 1;
    }

    /**
     * Draws the same number of guesses with each repeat filter. Both see the same cell order, so the
     * extra skips of the Bloom run approximate the new guesses it wrongly treated as repeats.
     */
    List<RepeatRow> repeatExperiment() {
        List<GuessGenerator.Base> bases = guessModel.bases(List.of());
        List<GuessGenerator.Rule> rules = GuessModel.rules();
        List<RepeatRow> rows = new ArrayList<>();
        for (long n : REPEAT_SIZES) {
            long t0 = System.nanoTime();
            GuessGenerator bloom = new GuessGenerator(bases, rules, n, OrgSimulationService.REPEAT_FILTER_FPR, false);
            while (bloom.produced() < n && bloom.next() != null) { }
            long t1 = System.nanoTime();
            GuessGenerator exact = new GuessGenerator(bases, rules, n, OrgSimulationService.REPEAT_FILTER_FPR, true);
            List<String> texts = new ArrayList<>();
            for (GuessGenerator.Guess g; exact.produced() < n && (g = exact.next()) != null; ) texts.add(g.text());
            long t2 = System.nanoTime();
            rows.add(new RepeatRow(n, bloom.filterBytes(), estimateHashSetBytes(texts), round((t1 - t0) / 1e6, 1),
                    round((t2 - t1) / 1e6, 1), exact.skippedRepeats(), bloom.skippedRepeats(),
                    Math.max(0, bloom.skippedRepeats() - exact.skippedRepeats())));
        }
        return rows;
    }

    /** Min cut to Payroll DB on synthetic organisations, with a seeded 40% of accounts as entry points. */
    List<FlowRow> flowExperiment() {
        List<FlowRow> rows = new ArrayList<>();
        for (int size : ORG_SIZES) {
            OrgDemoGenerator.Org org = OrgDemoGenerator.generate(size, 11, wordlists);
            OrgSimulationService.Graph graph = new OrgSimulationService.Graph(
                    org.users().stream().map(u -> u[0]).toList(), org.access());
            Random random = new Random(size);
            boolean[] entry = new boolean[graph.names.size()];
            int entries = 0;
            for (int v = 0; v < entry.length; v++) {
                entry[v] = graph.userOf[v] >= 0 && random.nextDouble() < 0.4;
                if (entry[v]) entries++;
            }
            int sink = graph.sink(graph.find("Payroll DB"));
            MaxFlow flow = OrgSimulationService.flowNetwork(graph, entry);
            long[] cut = new long[1];
            double ek = medianMillis(() -> {
                flow.reset();
                cut[0] = flow.edmondsKarp(0, sink);
                return cut[0];
            });
            int ekPaths = flow.augmentingPaths();
            double ff = medianMillis(() -> {
                flow.reset();
                return flow.fordFulkersonDfs(0, sink);
            });
            rows.add(new FlowRow(size, flow.vertexCount(), flow.edgeCount(), entries, cut[0], ek, ff, ekPaths,
                    flow.augmentingPaths()));
        }
        return rows;
    }

    /**
     * Scans 100,000 characters of synthetic chat text (English words with leaked passwords mixed in)
     * for the k most common leaked passwords of 6+ characters: one Aho–Corasick pass against k KMP
     * passes. Both must report the same number of matches.
     */
    List<MultiPatternRow> multiPatternExperiment(List<String> leaked) {
        List<String> words = wordlists.dictionaries().get("english");
        Random random = new Random(13);
        StringBuilder sb = new StringBuilder(SCAN_TEXT_LENGTH + 64);
        while (sb.length() < SCAN_TEXT_LENGTH) {
            String w = random.nextInt(20) == 0 ? leaked.get(random.nextInt(2_000)) : words.get(random.nextInt(words.size()));
            sb.append(w.toLowerCase(Locale.ROOT)).append(' ');
        }
        String text = sb.substring(0, SCAN_TEXT_LENGTH);
        List<String> candidates = leaked.stream().map(p -> p.toLowerCase(Locale.ROOT)).filter(p -> p.length() >= 6)
                .distinct().toList();

        List<MultiPatternRow> rows = new ArrayList<>();
        for (int k : PATTERN_COUNTS) {
            List<String> patterns = candidates.subList(0, Math.min(k, candidates.size()));
            AhoCorasick automaton = new AhoCorasick(patterns);
            long[] acMatches = new long[1];
            double ac = medianMillis(() -> acMatches[0] = automaton.findAll(text).size());
            long[] kmpMatches = new long[1];
            double kmp = medianMillis(() -> {
                long count = 0;
                for (String p : patterns) count += Kmp.allMatches(text, p).size();
                return kmpMatches[0] = count;
            });
            rows.add(new MultiPatternRow(patterns.size(), text.length(), automaton.stateCount(), ac, kmp,
                    acMatches[0], kmpMatches[0]));
        }
        return rows;
    }

    /** Character-by-character search without String.indexOf intrinsics; returns {matches, comparisons}. */
    static long[] naiveSearch(String text, String pattern) {
        long matches = 0, comparisons = 0;
        for (int i = 0; i + pattern.length() <= text.length(); i++) {
            int j = 0;
            while (j < pattern.length()) {
                comparisons++;
                if (text.charAt(i + j) != pattern.charAt(j)) break;
                j++;
            }
            if (j == pattern.length()) matches++;
        }
        return new long[]{matches, comparisons};
    }

    /**
     * Approximate retained size of a HashSet&lt;String&gt; on a 64-bit JVM with compressed oops:
     * HashMap.Node 32 B + String 24 B + Latin-1 byte[] (16 B header + data, 8-byte aligned)
     * + table slot 4 B / 0.75 load factor.
     */
    static long estimateHashSetBytes(List<String> items) {
        long total = 0;
        for (String s : items) total += 32 + 24 + align8(16 + s.length()) + 6;
        return total;
    }

    private static long align8(long bytes) {
        return (bytes + 7) & ~7L;
    }

    /** The three sizes, capped at the dataset size (the 1M list has 999,999 non-empty lines). */
    private static int[] sizes(int available) {
        return Arrays.stream(SIZES).map(n -> Math.min(n, available)).distinct().toArray();
    }

    private static double medianMillis(LongSupplier work) {
        long sink = 0;
        for (int i = 0; i < 2; i++) sink += work.getAsLong();
        double[] times = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long t = System.nanoTime();
            sink += work.getAsLong();
            times[i] = (System.nanoTime() - t) / 1e6;
        }
        if (sink == Long.MIN_VALUE) System.out.print("");
        Arrays.sort(times);
        return round(times[RUNS / 2], 3);
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isEmpty()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
