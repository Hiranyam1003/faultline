package edu.atlas.pwaudit.service;

import java.util.List;

public record AuditResult(
        int length,
        BreachStatus breachStatus,
        boolean bloomHit,
        int diskProbes,
        Integer leakRank,
        List<Pattern> patterns,
        Repeat repetition,
        boolean containsUsername,
        double entropyBits,
        int score,
        String verdict,
        List<String> suggestions,
        Timings timingsMicros,
        BloomProbe bloomProbe) {

    public enum BreachStatus { NOT_PRESENT, CONFIRMED, BLOOM_FALSE_POSITIVE }

    /** A weak segment password[start, end) matching a dictionary token. */
    public record Pattern(int start, int end, String token, String category, boolean leet) {}

    /** password[start, start + count * block.length()) is the block repeated count times. */
    public record Repeat(int start, String block, int count) {}

    /** What the Bloom lookup touched: the SHA-1 key, the k probed bit positions and whether each was set. */
    public record BloomProbe(String sha1, long bits, List<Long> positions, List<Boolean> set) {}

    public record Timings(double bloom, double exact, double trie, double kmp) {}
}
