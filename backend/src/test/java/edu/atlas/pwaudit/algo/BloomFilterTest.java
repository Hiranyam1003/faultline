package edu.atlas.pwaudit.algo;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BloomFilterTest {

    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void neverReturnsFalseNegatives() {
        BloomFilter bloom = BloomFilter.forCapacity(50_000, 0.01);
        for (int i = 0; i < 50_000; i++) bloom.add(key("member-" + i));
        for (int i = 0; i < 50_000; i++) assertTrue(bloom.mightContain(key("member-" + i)), "member-" + i);
    }

    @Test
    void emptyFilterContainsNothing() {
        BloomFilter bloom = BloomFilter.forCapacity(1_000, 0.01);
        for (int i = 0; i < 1_000; i++) assertFalse(bloom.mightContain(key("x" + i)));
        assertEquals(0.0, bloom.theoreticalFpr());
    }

    @Test
    void sizingFormulasMatchTheory() {
        // n = 1,000,000, p = 1%  →  m = ceil(-n ln p / ln²2) = 9,585,059, k = round(m/n · ln 2) = 7
        assertEquals(9_585_059, BloomFilter.optimalBits(1_000_000, 0.01));
        assertEquals(7, BloomFilter.optimalHashes(1_000_000, 9_585_059));
        assertEquals(10, BloomFilter.optimalHashes(1_000, BloomFilter.optimalBits(1_000, 0.001)));
    }

    @Test
    void measuredFalsePositiveRateIsCloseToTarget() {
        int n = 100_000;
        BloomFilter bloom = BloomFilter.forCapacity(n, 0.01);
        for (int i = 0; i < n; i++) bloom.add(key("in-" + i));
        int falsePositives = 0, queries = 100_000;
        for (int i = 0; i < queries; i++) if (bloom.mightContain(key("out-" + i))) falsePositives++;
        double measured = (double) falsePositives / queries;
        assertEquals(bloom.theoreticalFpr(), measured, 0.003, "measured FPR " + measured);
    }

    @Test
    void invalidParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forCapacity(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forCapacity(-5, 0.01));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forCapacity(100, 0));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forCapacity(100, 1));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(64, 0));
    }

    @Test
    void boundaryTinyFilterSaturatesToAllPositive() {
        BloomFilter bloom = new BloomFilter(1, 1);
        bloom.add(key("only"));
        assertTrue(bloom.mightContain(key("anything else")));
        // The closed form assumes large m; with m = 1 the real FPR is 1 but the formula gives 1 - 1/e.
        assertEquals(1 - Math.exp(-1), bloom.theoreticalFpr(), 1e-9);
    }

    @Test
    void emptyKeyIsSupported() {
        BloomFilter bloom = BloomFilter.forCapacity(10, 0.01);
        bloom.add(new byte[0]);
        assertTrue(bloom.mightContain(new byte[0]));
    }

    @Test
    void probePositionsAreTheBitsThatAddSets() {
        BloomFilter bloom = new BloomFilter(10_007, 7);
        long[] positions = bloom.probePositions(key("hunter2"));
        assertEquals(7, positions.length);
        for (long bit : positions) {
            assertTrue(bit >= 0 && bit < 10_007);
            assertFalse(bloom.isSet(bit));
        }
        bloom.add(key("hunter2"));
        for (long bit : positions) assertTrue(bloom.isSet(bit));
        assertArrayEquals(positions, bloom.probePositions(key("hunter2")));
        assertThrows(IndexOutOfBoundsException.class, () -> bloom.isSet(10_007));
    }
}
