package edu.atlas.pwaudit.algo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aho–Corasick multi-pattern matcher: finds every occurrence of every pattern in one left-to-right
 * pass over the text.
 *
 * The patterns form a trie. A breadth-first pass then gives each node a failure link: the node for
 * the longest proper suffix of its string that is also a trie prefix, the multi-pattern version of
 * KMP's prefix function. On a mismatch the scan follows failure links instead of restarting, so the
 * text pointer never moves back. Each node also gets an output link to the nearest node on its failure
 * chain that ends a pattern, so reporting matches costs nothing beyond the matches themselves.
 *
 * Build: O(total pattern length). Search: O(n + z) for a text of length n with z matches,
 * against O(k · n) for running KMP once per pattern over k patterns.
 */
public final class AhoCorasick {

    /** text[start, end) equals patterns.get(pattern). */
    public record Match(int start, int end, int pattern) {}

    private final List<Map<Character, Integer>> next = new ArrayList<>();
    private final List<Integer> patternAt = new ArrayList<>();
    private final List<String> patterns;
    private final int[] fail;
    private final int[] outLink;
    private final int[] depth;

    /** Duplicate patterns are allowed; each occurrence is reported for the first copy. */
    public AhoCorasick(List<String> patterns) {
        this.patterns = List.copyOf(patterns);
        List<Integer> depths = new ArrayList<>();
        newNode(depths, 0);
        for (int p = 0; p < this.patterns.size(); p++) {
            String pattern = this.patterns.get(p);
            if (pattern.isEmpty()) throw new IllegalArgumentException("patterns must be non-empty");
            int node = 0;
            for (int i = 0; i < pattern.length(); i++) {
                Integer child = next.get(node).get(pattern.charAt(i));
                if (child == null) {
                    child = newNode(depths, i + 1);
                    next.get(node).put(pattern.charAt(i), child);
                }
                node = child;
            }
            if (patternAt.get(node) < 0) patternAt.set(node, p);
        }

        int nodes = next.size();
        fail = new int[nodes];
        outLink = new int[nodes];
        depth = depths.stream().mapToInt(Integer::intValue).toArray();
        outLink[0] = -1;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int child : next.get(0).values()) {
            fail[child] = 0;
            outLink[child] = -1;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            int node = queue.poll();
            for (Map.Entry<Character, Integer> edge : next.get(node).entrySet()) {
                int child = edge.getValue();
                int f = fail[node];
                while (f > 0 && !next.get(f).containsKey(edge.getKey())) f = fail[f];
                Integer target = next.get(f).get(edge.getKey());
                fail[child] = target != null && target != child ? target : 0;
                outLink[child] = patternAt.get(fail[child]) >= 0 ? fail[child] : outLink[fail[child]];
                queue.add(child);
            }
        }
    }

    /** Every occurrence of every pattern, in order of end position (then longest first). */
    public List<Match> findAll(CharSequence text) {
        List<Match> matches = new ArrayList<>();
        int node = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            while (node > 0 && !next.get(node).containsKey(c)) node = fail[node];
            node = next.get(node).getOrDefault(c, 0);
            for (int hit = patternAt.get(node) >= 0 ? node : outLink[node]; hit > 0; hit = outLink[hit]) {
                matches.add(new Match(i + 1 - depth[hit], i + 1, patternAt.get(hit)));
            }
        }
        return matches;
    }

    public String pattern(int index) { return patterns.get(index); }
    public int patternCount() { return patterns.size(); }
    public int stateCount() { return next.size(); }

    private int newNode(List<Integer> depths, int d) {
        next.add(new HashMap<>(4));
        patternAt.add(-1);
        depths.add(d);
        return next.size() - 1;
    }
}
