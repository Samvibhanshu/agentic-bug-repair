package dev.samridhi.routes;

/**
 * Heuristics for A*. A heuristic that over-estimates even once can make A* return a worse route, so the
 * cost-per-unit-distance used here is <em>measured from the graph</em>, not assumed.
 */
public final class Heuristics {
    private Heuristics() {}

    /**
     * The largest number k such that every open road costs at least k times the grid (Manhattan) distance it
     * covers. With that k, {@code k * manhattan(node, target)} never over-estimates: any route to the target covers at
     * least the Manhattan distance, and each unit of distance costs at least k. It is also consistent, because for a
     * road u->v, k * |manhattan(u) - manhattan(v)| <= k * manhattan(u, v) <= cost(u, v).
     */
    public static double safeCostPerUnit(Graph g) {
        double k = Double.POSITIVE_INFINITY;
        for (int u = 0; u < g.size(); u++) {
            for (Graph.Road r : g.roadsFrom(u)) {
                int d = Math.abs(g.x(r.from) - g.x(r.to)) + Math.abs(g.y(r.from) - g.y(r.to));
                if (d > 0) k = Math.min(k, (double) r.cost / d);
            }
        }
        return k == Double.POSITIVE_INFINITY ? 0 : k;
    }

    /** k * Manhattan distance to {@code target}, rounded down so rounding can never push it above the true cost. */
    public static ShortestPaths.Heuristic manhattan(Graph g, int target, double costPerUnit) {
        int tx = g.x(target), ty = g.y(target);
        return node -> (long) Math.floor(costPerUnit * (Math.abs(g.x(node) - tx) + Math.abs(g.y(node) - ty)));
    }

    /** Convenience: Manhattan heuristic with the measured safe cost per unit. */
    public static ShortestPaths.Heuristic manhattan(Graph g, int target) {
        return manhattan(g, target, safeCostPerUnit(g));
    }
}
