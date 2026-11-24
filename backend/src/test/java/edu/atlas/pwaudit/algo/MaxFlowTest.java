package edu.atlas.pwaudit.algo;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MaxFlowTest {

    /** CLRS Figure 26.1: max flow 23. */
    private static MaxFlow clrs() {
        MaxFlow f = new MaxFlow(6);
        f.addEdge(0, 1, 16);
        f.addEdge(0, 2, 13);
        f.addEdge(2, 1, 4);
        f.addEdge(1, 3, 12);
        f.addEdge(3, 2, 9);
        f.addEdge(2, 4, 14);
        f.addEdge(4, 3, 7);
        f.addEdge(3, 5, 20);
        f.addEdge(4, 5, 4);
        return f;
    }

    @Test
    void textbookNetwork() {
        MaxFlow f = clrs();
        assertEquals(23, f.edmondsKarp(0, 5));
        f.reset();
        assertEquals(23, f.fordFulkersonDfs(0, 5));
    }

    @Test
    void minCutCapacityEqualsFlow() {
        MaxFlow f = clrs();
        long flow = f.edmondsKarp(0, 5);
        boolean[] side = f.minCutSourceSide(0);
        assertTrue(side[0]);
        assertFalse(side[5]);
        long[][] edges = {{0, 1, 16}, {0, 2, 13}, {2, 1, 4}, {1, 3, 12}, {3, 2, 9}, {2, 4, 14}, {4, 3, 7}, {3, 5, 20}, {4, 5, 4}};
        long cut = 0;
        for (long[] e : edges) if (side[(int) e[0]] && !side[(int) e[1]]) cut += e[2];
        assertEquals(flow, cut);
    }

    @Test
    void disconnectedSinkHasZeroFlow() {
        MaxFlow f = new MaxFlow(4);
        f.addEdge(0, 1, 5);
        f.addEdge(2, 3, 5);
        assertEquals(0, f.edmondsKarp(0, 3));
        assertFalse(f.minCutSourceSide(0)[3]);
        assertEquals(0, f.augmentingPaths());
    }

    @Test
    void parallelEdgesAddUp() {
        MaxFlow f = new MaxFlow(2);
        f.addEdge(0, 1, 3);
        f.addEdge(0, 1, 4);
        assertEquals(7, f.edmondsKarp(0, 1));
    }

    @Test
    void infiniteCapacityPathIsReportedAsInf() {
        MaxFlow f = new MaxFlow(3);
        f.addEdge(0, 1, MaxFlow.INF);
        f.addEdge(1, 2, MaxFlow.INF);
        assertEquals(MaxFlow.INF, f.edmondsKarp(0, 2));
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new MaxFlow(1));
        MaxFlow f = new MaxFlow(3);
        assertThrows(IllegalArgumentException.class, () -> f.addEdge(0, 1, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> f.addEdge(0, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> f.edmondsKarp(1, 1));
    }

    @Test
    void edmondsKarpAgreesWithFordFulkersonOnRandomGraphs() {
        Random random = new Random(3);
        for (int trial = 0; trial < 200; trial++) {
            int n = 2 + random.nextInt(12);
            MaxFlow f = new MaxFlow(n);
            for (int e = 0; e < n * 3; e++) f.addEdge(random.nextInt(n), random.nextInt(n), random.nextInt(20));
            long ek = f.edmondsKarp(0, n - 1);
            f.reset();
            assertEquals(ek, f.fordFulkersonDfs(0, n - 1), "trial " + trial);
        }
    }

    /**
     * Unit vertex capacities, as in the attack graph: the flow must equal the smallest set of middle
     * vertices whose removal disconnects s from t, found here by trying every subset.
     */
    @Test
    void minVertexCutMatchesBruteForceOnSmallRandomGraphs() {
        Random random = new Random(11);
        for (int trial = 0; trial < 150; trial++) {
            int k = 1 + random.nextInt(10);
            boolean[][] adj = new boolean[k][k];
            boolean[] fromS = new boolean[k], toT = new boolean[k];
            for (int v = 0; v < k; v++) {
                fromS[v] = random.nextDouble() < 0.4;
                toT[v] = random.nextDouble() < 0.4;
                for (int w = 0; w < k; w++) adj[v][w] = v != w && random.nextDouble() < 0.25;
            }
            // s = 0, t = 1, vertex v = in 2 + 2v, out 3 + 2v.
            MaxFlow f = new MaxFlow(2 + 2 * k);
            for (int v = 0; v < k; v++) {
                f.addEdge(2 + 2 * v, 3 + 2 * v, 1);
                if (fromS[v]) f.addEdge(0, 2 + 2 * v, MaxFlow.INF);
                if (toT[v]) f.addEdge(3 + 2 * v, 1, MaxFlow.INF);
                for (int w = 0; w < k; w++) if (adj[v][w]) f.addEdge(3 + 2 * v, 2 + 2 * w, MaxFlow.INF);
            }
            long flow = f.edmondsKarp(0, 1);
            assertEquals(bruteForceCut(k, adj, fromS, toT), flow, "trial " + trial);
        }
    }

    private static int bruteForceCut(int k, boolean[][] adj, boolean[] fromS, boolean[] toT) {
        int best = k;
        for (int mask = 0; mask < (1 << k); mask++) {
            if (Integer.bitCount(mask) >= best) continue;
            boolean[] seen = new boolean[k];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int v = 0; v < k; v++) if (fromS[v] && (mask & 1 << v) == 0) { seen[v] = true; queue.add(v); }
            boolean reached = false;
            while (!queue.isEmpty() && !reached) {
                int v = queue.poll();
                if (toT[v]) reached = true;
                for (int w = 0; w < k; w++) {
                    if (adj[v][w] && !seen[w] && (mask & 1 << w) == 0) { seen[w] = true; queue.add(w); }
                }
            }
            if (!reached) best = Integer.bitCount(mask);
        }
        return best;
    }
}
