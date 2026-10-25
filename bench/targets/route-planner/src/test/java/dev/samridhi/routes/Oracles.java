package dev.samridhi.routes;

import java.util.Arrays;
import java.util.Random;

/** Slow, obviously-correct reference answers, and random inputs. */
final class Oracles {
    private Oracles() {}

    static final long INF = Path.UNREACHABLE;

    /** All-pairs cheapest costs by Floyd-Warshall, O(n^3): a completely different algorithm from the ones under test. */
    static long[][] floydWarshall(Graph g, boolean unitCosts) {
        int n = g.size();
        long[][] d = new long[n][n];
        for (long[] row : d) Arrays.fill(row, INF);
        for (int i = 0; i < n; i++) d[i][i] = 0;
        for (int u = 0; u < n; u++) {
            for (Graph.Road r : g.roadsFrom(u)) {
                if (r.isOpen()) d[u][r.to] = Math.min(d[u][r.to], unitCosts ? (u == r.to ? 0 : 1) : r.cost);
            }
        }
        for (int k = 0; k < n; k++)
            for (int i = 0; i < n; i++)
                if (d[i][k] != INF)
                    for (int j = 0; j < n; j++)
                        if (d[k][j] != INF && d[i][k] + d[k][j] < d[i][j]) d[i][j] = d[i][k] + d[k][j];
        return d;
    }

    /** A random directed graph: some parallel roads, self-loops, zero-cost roads, closed roads and unreachable parts. */
    static Graph randomGraph(Random rnd, int maxNodes) {
        int n = 1 + rnd.nextInt(maxNodes);
        Graph g = new Graph(n);
        int roads = rnd.nextInt(n * 3 + 1);
        for (int i = 0; i < roads; i++) {
            int a = rnd.nextInt(n), b = rnd.nextInt(n);
            g.addRoad(a, b, rnd.nextInt(4) == 0 ? 0 : rnd.nextInt(20));
        }
        for (int i = 0; i < n / 4; i++) g.closeRoad(rnd.nextInt(n), rnd.nextInt(n));
        return g;
    }

    /** Checks the path really exists in g, starts and ends where it should, and costs what it says. */
    static void assertValidPath(Graph g, Path p, int s, int t, boolean unitCosts) {
        if (!p.found()) {
            if (!p.nodes().isEmpty()) throw new AssertionError("unreachable but has nodes");
            return;
        }
        if (p.nodes().get(0) != s || p.nodes().get(p.nodes().size() - 1) != t) throw new AssertionError("wrong endpoints " + p.nodes());
        long total = 0;
        for (int i = 0; i + 1 < p.nodes().size(); i++) {
            int c = g.cost(p.nodes().get(i), p.nodes().get(i + 1));
            if (c < 0) throw new AssertionError("no open road " + p.nodes().get(i) + "->" + p.nodes().get(i + 1));
            total += unitCosts ? 1 : c;
        }
        if (total != p.cost()) throw new AssertionError("path costs " + total + " but reported " + p.cost());
    }

    /** Cheapest tour by trying every order: k! of them, so only for small k. */
    static long bruteForceTour(long[][] m) {
        int k = m.length - 1;
        int[] order = new int[k];
        for (int i = 0; i < k; i++) order[i] = i + 1;
        long[] best = {Long.MAX_VALUE};
        permute(order, 0, m, best);
        return k == 0 ? 0 : best[0];
    }

    private static void permute(int[] a, int i, long[][] m, long[] best) {
        if (i == a.length) {
            best[0] = Math.min(best[0], DeliveryPlanner.tourCost(a, m));
            return;
        }
        for (int j = i; j < a.length; j++) {
            int t = a[i]; a[i] = a[j]; a[j] = t;
            permute(a, i + 1, m, best);
            t = a[i]; a[i] = a[j]; a[j] = t;
        }
    }

    /** A random asymmetric cost matrix (one-way streets make A->B and B->A differ). */
    static long[][] randomMatrix(Random rnd, int k) {
        long[][] m = new long[k + 1][k + 1];
        for (int i = 0; i <= k; i++) for (int j = 0; j <= k; j++) m[i][j] = i == j ? 0 : 1 + rnd.nextInt(100);
        return m;
    }
}
