package edu.atlas.pwaudit.algo;

/**
 * Bloom filter over byte-array keys.
 *
 * Bits live in a hand-managed long[] (no java.util.BitSet). The k probe positions come from
 * Kirsch–Mitzenmacher double hashing: g_i(x) = h1(x) + i * h2(x) mod m, which keeps the
 * asymptotic false-positive rate of k independent hashes while computing only two.
 *
 * A positive answer means "possibly present"; a negative answer is always correct.
 */
public final class BloomFilter {

    private static final long SEED_1 = 0x9E3779B97F4A7C15L;
    private static final long SEED_2 = 0xC2B2AE3D27D4EB4FL;

    private final long[] words;
    private final long m;
    private final int k;
    private long inserted;

    public BloomFilter(long m, int k) {
        if (m <= 0) throw new IllegalArgumentException("bit count m must be positive");
        if (k <= 0) throw new IllegalArgumentException("hash count k must be positive");
        this.m = m;
        this.k = k;
        long wordCount = (m + 63) / 64;
        if (wordCount > Integer.MAX_VALUE) throw new IllegalArgumentException("filter too large");
        this.words = new long[(int) wordCount];
    }

    /** Sizes the filter for n expected items at target false-positive rate p. */
    public static BloomFilter forCapacity(long n, double p) {
        return new BloomFilter(optimalBits(n, p), optimalHashes(n, optimalBits(n, p)));
    }

    /** m = ceil(-n ln p / (ln 2)^2). */
    public static long optimalBits(long n, double p) {
        if (n <= 0) throw new IllegalArgumentException("expected item count n must be positive");
        if (!(p > 0 && p < 1)) throw new IllegalArgumentException("false-positive rate p must be in (0, 1)");
        return (long) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2)));
    }

    /** k = round((m / n) ln 2), at least 1. */
    public static int optimalHashes(long n, long m) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }

    public void add(byte[] key) {
        long h1 = hash64(key, SEED_1);
        long h2 = hash64(key, SEED_2) | 1L;
        for (int i = 0; i < k; i++) {
            long bit = Math.floorMod(h1 + i * h2, m);
            words[(int) (bit >>> 6)] |= 1L << (bit & 63);
        }
        inserted++;
    }

    public boolean mightContain(byte[] key) {
        long h1 = hash64(key, SEED_1);
        long h2 = hash64(key, SEED_2) | 1L;
        for (int i = 0; i < k; i++) {
            long bit = Math.floorMod(h1 + i * h2, m);
            if ((words[(int) (bit >>> 6)] & (1L << (bit & 63))) == 0) return false;
        }
        return true;
    }

    /** The k bit positions a key maps to, in probe order. Used to show a lookup in the UI. */
    public long[] probePositions(byte[] key) {
        long h1 = hash64(key, SEED_1);
        long h2 = hash64(key, SEED_2) | 1L;
        long[] positions = new long[k];
        for (int i = 0; i < k; i++) positions[i] = Math.floorMod(h1 + i * h2, m);
        return positions;
    }

    public boolean isSet(long bit) {
        if (bit < 0 || bit >= m) throw new IndexOutOfBoundsException("bit " + bit + " outside [0, " + m + ")");
        return (words[(int) (bit >>> 6)] & (1L << (bit & 63))) != 0;
    }

    /** Expected false-positive rate after the current number of insertions: (1 - e^(-kn/m))^k. */
    public double theoreticalFpr() {
        return Math.pow(1 - Math.exp(-(double) k * inserted / m), k);
    }

    public long bitCount() { return m; }
    public int hashCount() { return k; }
    public long insertedCount() { return inserted; }
    public long memoryBytes() { return (long) words.length * Long.BYTES; }

    /** FNV-1a style byte mixing followed by the MurmurHash3 64-bit finaliser for avalanche. */
    static long hash64(byte[] data, long seed) {
        long h = seed ^ (data.length * 0x100000001B3L);
        for (byte b : data) {
            h ^= (b & 0xFF);
            h *= 0x100000001B3L;
        }
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }
}
