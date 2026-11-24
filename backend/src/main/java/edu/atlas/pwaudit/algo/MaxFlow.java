package edu.atlas.pwaudit.algo;

import java.util.Arrays;

/**
 * Maximum flow on a directed graph with long capacities, stored as a residual graph in flat arrays:
 * edge e goes to {@code to[e]} with remaining capacity {@code cap[e]}, and e ^ 1 is its reverse edge.
 *
 * Edmonds–Karp augments along a shortest (fewest-edges) path found by BFS: O(V·E²) in general, and
 * O(F·E) when the flow value F is small. Ford–Fulkerson with DFS is kept as the baseline. By the
 * max-flow min-cut theorem, once no augmenting path exists the vertices still reachable from s in
 * the residual graph form the source side of a minimum cut whose capacity equals the flow.
 */
public final class MaxFlow {

    public static final long INF = Long.MAX_VALUE / 4;

    private final int n;
    private int[] head;
    private int[] next = new int[16];
    private int[] to = new int[16];
    private long[] cap = new long[16];
    private long[] original = new long[16];
    private int edges;
    private int augmentingPaths;

    public MaxFlow(int vertices) {
        if (vertices < 2) throw new IllegalArgumentException("need at least 2 vertices");
        n = vertices;
        head = new int[n];
        Arrays.fill(head, -1);
    }

    /** Adds edge u → v and returns its id. Parallel edges are allowed and simply add up. */
    public int addEdge(int u, int v, long capacity) {
        if (u < 0 || u >= n || v < 0 || v >= n) throw new IndexOutOfBoundsException("vertex outside [0, " + n + ")");
        if (capacity < 0) throw new IllegalArgumentException("capacity must be non-negative");
        int id = edges;
        link(u, v, Math.min(capacity, INF));
        link(v, u, 0);
        return id;
    }

    public long edmondsKarp(int s, int t) {
        return augment(s, t, true);
    }

    /** Baseline: augment along any path found by depth-first search. */
    public long fordFulkersonDfs(int s, int t) {
        return augment(s, t, false);
    }

    /**
     * Repeatedly finds an s→t path with spare capacity, pushes its bottleneck along it, and updates the
     * reverse edges. The frontier is a queue (BFS, shortest path) or a stack (DFS, any path).
     */
    private long augment(int s, int t, boolean breadthFirst) {
        checkTerminals(s, t);
        long flow = 0;
        int[] parentEdge = new int[n];
        int[] frontier = new int[n];
        while (true) {
            Arrays.fill(parentEdge, -1);
            parentEdge[s] = -2;
            int first = 0, last = 0;
            frontier[last++] = s;
            while (first < last && parentEdge[t] == -1) {
                int u = breadthFirst ? frontier[first++] : frontier[--last];
                for (int e = head[u]; e != -1; e = next[e]) {
                    if (cap[e] > 0 && parentEdge[to[e]] == -1) {
                        parentEdge[to[e]] = e;
                        frontier[last++] = to[e];
                    }
                }
            }
            if (parentEdge[t] == -1) return flow;
            long bottleneck = INF;
            for (int v = t; v != s; v = to[parentEdge[v] ^ 1]) bottleneck = Math.min(bottleneck, cap[parentEdge[v]]);
            for (int v = t; v != s; v = to[parentEdge[v] ^ 1]) {
                cap[parentEdge[v]] -= bottleneck;
                cap[parentEdge[v] ^ 1] += bottleneck;
            }
            augmentingPaths++;
            if (bottleneck >= INF) return INF;
            flow = Math.min(INF, flow + bottleneck);
        }
    }

    /** After a max-flow run: true for vertices reachable from s in the residual graph (the cut's source side). */
    public boolean[] minCutSourceSide(int s) {
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        int qHead = 0, qTail = 0;
        queue[qTail++] = s;
        seen[s] = true;
        while (qHead < qTail) {
            int u = queue[qHead++];
            for (int e = head[u]; e != -1; e = next[e]) {
                if (cap[e] > 0 && !seen[to[e]]) {
                    seen[to[e]] = true;
                    queue[qTail++] = to[e];
                }
            }
        }
        return seen;
    }

    /** Restores every edge to its original capacity so another algorithm can run on the same graph. */
    public void reset() {
        for (int e = 0; e < edges; e++) cap[e] = original[e];
        augmentingPaths = 0;
    }

    public int vertexCount() { return n; }
    public int edgeCount() { return edges / 2; }
    public int augmentingPaths() { return augmentingPaths; }

    private void link(int u, int v, long capacity) {
        if (edges == to.length) {
            int size = edges * 2;
            next = Arrays.copyOf(next, size);
            to = Arrays.copyOf(to, size);
            cap = Arrays.copyOf(cap, size);
            original = Arrays.copyOf(original, size);
        }
        to[edges] = v;
        cap[edges] = capacity;
        original[edges] = capacity;
        next[edges] = head[u];
        head[u] = edges++;
    }

    private void checkTerminals(int s, int t) {
        if (s < 0 || s >= n || t < 0 || t >= n) throw new IndexOutOfBoundsException("vertex outside [0, " + n + ")");
        if (s == t) throw new IllegalArgumentException("source and sink must differ");
    }
}
