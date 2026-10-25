package dev.samridhi.routes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Four ways to find a route, from simplest to fastest. All return the same cost (the tests check this against
 * Floyd-Warshall on thousands of random graphs); they differ in how much of the map they explore to find it.
 *
 * <table>
 *   <caption>Which one when</caption>
 *   <tr><th>Algorithm</th><th>Use when</th><th>Cost</th></tr>
 *   <tr><td>BFS</td><td>every road costs the same (fewest turns, fewest hops)</td><td>O(V + E)</td></tr>
 *   <tr><td>Dijkstra</td><td>costs differ, nothing known about where the target is</td><td>O((V + E) log V)</td></tr>
 *   <tr><td>A*</td><td>you can cheaply under-estimate the remaining cost (straight-line or grid distance)</td>
 *       <td>same worst case, far fewer nodes in practice</td></tr>
 *   <tr><td>Bidirectional Dijkstra</td><td>no geometry, but you can search backwards from the target</td>
 *       <td>about two half-radius searches instead of one full-radius search</td></tr>
 * </table>
 *
 * <p>Closed roads are skipped by every algorithm.
 */
public final class ShortestPaths {
    private ShortestPaths() {}

    /** Lower bound on the remaining cost from a node to the target. */
    @FunctionalInterface
    public interface Heuristic {
        long estimate(int node);
    }

    // ------------------------------------------------------------------------------------------------- BFS

    /** Fewest roads from {@code source} to {@code target}, ignoring costs. {@code cost} is the number of roads. */
    public static Path bfs(Graph g, int source, int target) {
        int[] parent = new int[g.size()];
        int[] hops = new int[g.size()];
        Arrays.fill(hops, -1);
        hops[source] = 0;
        parent[source] = -1;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(source);
        int settled = 0;
        while (!queue.isEmpty()) {
            int u = queue.poll();
            settled++;
            if (u == target) return new Path(walkBack(parent, target), hops[target], settled);
            for (Graph.Road r : g.roadsFrom(u)) {
                if (r.open && hops[r.to] < 0) {             // first time seen = fewest hops (BFS goes level by level)
                    hops[r.to] = hops[u] + 1;
                    parent[r.to] = u;
                    queue.add(r.to);
                }
            }
        }
        return Path.unreachable(settled);
    }

    // -------------------------------------------------------------------------------------- Dijkstra and A*

    /** Cheapest route. Stops as soon as the target is settled. */
    public static Path dijkstra(Graph g, int source, int target) {
        return aStar(g, source, target, node -> 0);           // Dijkstra is A* with no hint
    }

    /**
     * A*: Dijkstra that explores nodes in order of (cost so far + estimated cost to go), so it heads toward the
     * target instead of spreading out evenly in every direction.
     *
     * <p>Correct (returns the cheapest route) when the heuristic never over-estimates. Efficient (each node settled
     * once) when it is also consistent: {@code h(u) <= cost(u,v) + h(v)} for every road. {@link Heuristics#manhattan}
     * is built to satisfy both.
     */
    public static Path aStar(Graph g, int source, int target, Heuristic h) {
        int n = g.size();
        long[] dist = new long[n];
        int[] parent = new int[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, Long.MAX_VALUE);
        dist[source] = 0;
        parent[source] = -1;
        // entries are {priority, node}; a node can be queued more than once, and stale entries are skipped
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        open.add(new long[] {h.estimate(source), source});
        int settled = 0;
        while (!open.isEmpty()) {
            int u = (int) open.poll()[1];
            if (done[u]) continue;                             // stale: a cheaper entry for u was already settled
            done[u] = true;
            settled++;
            if (u == target) return new Path(walkBack(parent, target), dist[target], settled);
            for (Graph.Road r : g.roadsFrom(u)) {
                if (!r.open || done[r.to]) continue;
                long candidate = dist[u] + r.cost;
                if (candidate < dist[r.to]) {
                    dist[r.to] = candidate;
                    parent[r.to] = u;
                    open.add(new long[] {candidate + h.estimate(r.to), r.to});
                }
            }
        }
        return Path.unreachable(settled);
    }

    /** Cheapest cost from {@code source} to every node ({@link Path#UNREACHABLE} where there is no route). */
    public static long[] dijkstraAll(Graph g, int source) {
        long[] dist = new long[g.size()];
        Arrays.fill(dist, Long.MAX_VALUE);
        dist[source] = 0;
        boolean[] done = new boolean[g.size()];
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        open.add(new long[] {0, source});
        while (!open.isEmpty()) {
            int u = (int) open.poll()[1];
            if (done[u]) continue;
            done[u] = true;
            for (Graph.Road r : g.roadsFrom(u)) {
                if (r.open && dist[u] + r.cost < dist[r.to]) {
                    dist[r.to] = dist[u] + r.cost;
                    open.add(new long[] {dist[r.to], r.to});
                }
            }
        }
        return dist;
    }

    // ------------------------------------------------------------------------------------ bidirectional

    /**
     * Searches forward from the source and backward (along reversed roads) from the target at the same time, always
     * advancing whichever frontier is cheaper, until they meet.
     *
     * <p><b>The subtle part is when to stop.</b> The first node settled by both searches is NOT necessarily on the
     * cheapest route. Instead we keep {@code best}, the cheapest source-to-target total seen through any node reached
     * by both sides, and stop only when {@code topForward + topBackward >= best}: at that point any route not yet seen
     * would have to cost at least that much, so {@code best} is optimal. (A planted bug that stops at the first meeting
     * node is caught by the tests.)
     */
    public static Path bidirectional(Graph g, int source, int target) {
        int n = g.size();
        if (source == target) return new Path(List.of(source), 0, 1);
        long[][] dist = {new long[n], new long[n]};            // [0] forward from source, [1] backward from target
        int[][] parent = {new int[n], new int[n]};
        boolean[][] done = {new boolean[n], new boolean[n]};
        Arrays.fill(dist[0], Long.MAX_VALUE);
        Arrays.fill(dist[1], Long.MAX_VALUE);
        dist[0][source] = 0;
        dist[1][target] = 0;
        parent[0][source] = -1;
        parent[1][target] = -1;
        List<PriorityQueue<long[]>> open = List.of(
                new PriorityQueue<long[]>((a, b) -> Long.compare(a[0], b[0])),
                new PriorityQueue<long[]>((a, b) -> Long.compare(a[0], b[0])));
        open.get(0).add(new long[] {0, source});
        open.get(1).add(new long[] {0, target});
        long best = Long.MAX_VALUE;
        int meet = -1, settled = 0;

        // If one side runs out, stopping is safe: e.g. a finished forward search has set the final cost of the target
        // itself, and every such update was checked against the backward cost of the target, which is 0.
        while (!open.get(0).isEmpty() && !open.get(1).isEmpty()) {
            long topF = open.get(0).peek()[0], topB = open.get(1).peek()[0];
            if (best != Long.MAX_VALUE && topF + topB >= best) break;
            int side = topF <= topB ? 0 : 1;
            int u = (int) open.get(side).poll()[1];
            if (done[side][u]) continue;
            done[side][u] = true;
            settled++;
            for (Graph.Road r : side == 0 ? g.roadsFrom(u) : g.roadsInto(u)) {
                if (!r.open) continue;
                int v = side == 0 ? r.to : r.from;
                long candidate = dist[side][u] + r.cost;
                if (candidate < dist[side][v]) {
                    dist[side][v] = candidate;
                    parent[side][v] = u;
                    open.get(side).add(new long[] {candidate, v});
                }
                if (dist[1 - side][v] != Long.MAX_VALUE && dist[side][v] + dist[1 - side][v] < best) {
                    best = dist[side][v] + dist[1 - side][v];
                    meet = v;
                }
            }
        }
        if (meet < 0) return Path.unreachable(settled);
        List<Integer> nodes = walkBack(parent[0], meet);        // source .. meet
        for (int v = parent[1][meet]; v != -1; v = parent[1][v]) nodes.add(v);   // meet .. target via backward parents
        return new Path(nodes, best, settled);
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static List<Integer> walkBack(int[] parent, int target) {
        List<Integer> nodes = new ArrayList<>();
        for (int v = target; v != -1; v = parent[v]) nodes.add(v);
        Collections.reverse(nodes);
        return nodes;
    }
}
