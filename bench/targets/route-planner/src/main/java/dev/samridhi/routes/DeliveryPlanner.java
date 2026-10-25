package dev.samridhi.routes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plans a delivery round: start at the depot, visit every stop once, return to the depot, as cheaply as possible.
 * This is the Travelling Salesman Problem, which is NP-hard, so there are two tools:
 *
 * <ul>
 *   <li><b>Exact (Held-Karp dynamic programming)</b>, O(2^k * k^2) for k stops. Fine up to about 16 stops
 *       (65,536 subsets), hopeless beyond that. Brute force over all k! orders would already be 20 trillion at k = 16.</li>
 *   <li><b>Heuristic</b>: nearest-neighbour (always drive to the closest unvisited stop) to get a reasonable tour fast,
 *       then <b>2-opt</b> (reverse any stretch of the tour if that makes it cheaper; repeat until nothing helps).
 *       Polynomial, no optimality guarantee; the benchmark measures how far from optimal it lands.</li>
 * </ul>
 *
 * <p>Both work on a cost matrix between the depot and the stops, built with one Dijkstra run from each of them.
 * Costs may be asymmetric (one-way streets), so every tour cost is computed in its direction of travel.
 */
public final class DeliveryPlanner {
    public static final int MAX_EXACT_STOPS = 16;

    private DeliveryPlanner() {}

    /** A planned round. {@code order} lists stop indexes (into the stops given) in visiting order. */
    public record Round(int[] order, long cost, List<Integer> roads) {
        public Round {
            order = order.clone();
            roads = List.copyOf(roads);
        }

        @Override
        public int[] order() {
            return order.clone();
        }
    }

    public static final class UnreachableStopException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnreachableStopException(String message) {
            super(message);
        }
    }

    /**
     * Cost matrix: row/column 0 is the depot, i+1 is stops[i]. One Dijkstra per point: O(k * (V + E) log V).
     * Throws if some stop cannot be reached from, or cannot get back to, the others.
     */
    public static long[][] costMatrix(Graph g, int depot, int[] stops) {
        int[] points = points(depot, stops);
        long[][] m = new long[points.length][];
        for (int i = 0; i < points.length; i++) {
            long[] all = ShortestPaths.dijkstraAll(g, points[i]);
            m[i] = new long[points.length];
            for (int j = 0; j < points.length; j++) {
                m[i][j] = all[points[j]];
                if (m[i][j] == Path.UNREACHABLE) {
                    throw new UnreachableStopException("no route from node " + points[i] + " to node " + points[j]);
                }
            }
        }
        return m;
    }

    /** Cheapest possible round (Held-Karp). Returns the visiting order of points 1..k (0 is the depot). */
    public static int[] exactTour(long[][] m) {
        int k = m.length - 1;
        if (k > MAX_EXACT_STOPS) throw new IllegalArgumentException(k + " stops: exact planning is limited to " + MAX_EXACT_STOPS);
        if (k == 0) return new int[0];
        int full = 1 << k;
        // best[set][last] = cheapest way to leave the depot, visit exactly the stops in `set`, and end at stop `last`
        long[][] best = new long[full][k];
        int[][] prev = new int[full][k];
        for (long[] row : best) Arrays.fill(row, Long.MAX_VALUE);
        for (int j = 0; j < k; j++) best[1 << j][j] = m[0][j + 1];
        for (int set = 1; set < full; set++) {                    // increasing order: every subset comes before its supersets
            for (int last = 0; last < k; last++) {
                if ((set & (1 << last)) == 0 || best[set][last] == Long.MAX_VALUE) continue;
                for (int next = 0; next < k; next++) {
                    if ((set & (1 << next)) != 0) continue;
                    int grown = set | (1 << next);
                    long c = best[set][last] + m[last + 1][next + 1];
                    if (c < best[grown][next]) {
                        best[grown][next] = c;
                        prev[grown][next] = last;
                    }
                }
            }
        }
        int last = 0;
        long bestTotal = Long.MAX_VALUE;
        for (int j = 0; j < k; j++) {
            long total = best[full - 1][j] + m[j + 1][0];             // and drive home
            if (total < bestTotal) {
                bestTotal = total;
                last = j;
            }
        }
        int[] order = new int[k];
        int set = full - 1;
        for (int pos = k - 1; pos >= 0; pos--) {                       // walk the choices backwards
            order[pos] = last + 1;
            int before = prev[set][last];
            set &= ~(1 << last);
            last = before;
        }
        return order;
    }

    /** Greedy: from wherever you are, go to the closest stop not yet visited. O(k^2). */
    public static int[] nearestNeighbour(long[][] m) {
        int k = m.length - 1;
        boolean[] visited = new boolean[k + 1];
        int[] order = new int[k];
        int at = 0;
        for (int pos = 0; pos < k; pos++) {
            int pick = -1;
            for (int j = 1; j <= k; j++) if (!visited[j] && (pick < 0 || m[at][j] < m[at][pick])) pick = j;
            visited[pick] = true;
            order[pos] = pick;
            at = pick;
        }
        return order;
    }

    /**
     * 2-opt: try reversing every stretch order[i..j]; keep any reversal that lowers the total; repeat until a full pass
     * finds nothing. Each kept move strictly lowers the cost, so it always terminates, and the result is never worse
     * than the input. The whole tour is re-costed per move because with one-way streets reversing a stretch also
     * changes the cost of driving it.
     */
    public static int[] twoOpt(int[] order, long[][] m) {
        int[] best = order.clone();
        long bestCost = tourCost(best, m);
        boolean improved = true;
        while (improved) {
            improved = false;
            for (int i = 0; i < best.length - 1; i++) {
                for (int j = i + 1; j < best.length; j++) {
                    int[] candidate = best.clone();
                    reverse(candidate, i, j);
                    long c = tourCost(candidate, m);
                    if (c < bestCost) {
                        best = candidate;
                        bestCost = c;
                        improved = true;
                    }
                }
            }
        }
        return best;
    }

    /** Depot -> order[0] -> ... -> order[last] -> depot. */
    public static long tourCost(int[] order, long[][] m) {
        long c = 0;
        int at = 0;
        for (int p : order) {
            c += m[at][p];
            at = p;
        }
        return c + m[at][0];
    }

    /**
     * Plans a round on the road network: exact up to {@link #MAX_EXACT_STOPS} stops, nearest-neighbour + 2-opt beyond.
     * Repeated stops and stops at the depot are allowed (they cost nothing extra). Returns the order of
     * {@code stops} indexes, the total cost, and the full list of junctions to drive through.
     */
    public static Round plan(Graph g, int depot, int[] stops) {
        long[][] m = costMatrix(g, depot, stops);
        int[] tour = stops.length <= MAX_EXACT_STOPS ? exactTour(m) : twoOpt(nearestNeighbour(m), m);
        int[] points = points(depot, stops);
        List<Integer> roads = new ArrayList<>(List.of(depot));
        int at = depot;
        for (int idx : tour) {
            appendLeg(g, at, points[idx], roads);
            at = points[idx];
        }
        appendLeg(g, at, depot, roads);
        int[] order = new int[tour.length];
        for (int i = 0; i < tour.length; i++) order[i] = tour[i] - 1;
        return new Round(order, tourCost(tour, m), roads);
    }

    private static void appendLeg(Graph g, int from, int to, List<Integer> roads) {
        List<Integer> leg = ShortestPaths.dijkstra(g, from, to).nodes();
        roads.addAll(leg.subList(1, leg.size()));                     // leg starts where the previous one ended
    }

    private static int[] points(int depot, int[] stops) {
        int[] p = new int[stops.length + 1];
        p[0] = depot;
        System.arraycopy(stops, 0, p, 1, stops.length);
        return p;
    }

    private static void reverse(int[] a, int i, int j) {
        for (; i < j; i++, j--) {
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }
}
