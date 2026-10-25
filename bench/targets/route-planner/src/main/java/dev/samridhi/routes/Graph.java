package dev.samridhi.routes;

import java.util.ArrayList;
import java.util.List;

/**
 * A road network: nodes 0..n-1 (junctions) and directed roads with non-negative travel costs (seconds).
 * Two-way streets are two directed roads. Nodes may have grid coordinates, which A* uses as a hint.
 *
 * <p>Stored as adjacency lists: memory O(V + E), and iterating a node's roads costs O(its degree). An adjacency
 * matrix would cost O(V^2) memory, which for a 10,000-junction city is 100 million cells, almost all empty.
 *
 * <p>Roads can be closed and reopened (an accident, roadworks) without rebuilding the graph.
 */
public final class Graph {
    /** One directed road. {@code open} is flipped by {@link #closeRoad}/{@link #openRoad}. */
    public static final class Road {
        public final int from, to;
        public final int cost;
        boolean open = true;

        Road(int from, int to, int cost) {
            this.from = from;
            this.to = to;
            this.cost = cost;
        }

        public boolean isOpen() {
            return open;
        }
    }

    private final List<List<Road>> out = new ArrayList<>();
    private final List<List<Road>> in = new ArrayList<>();        // reverse roads, for searching backwards from the target
    private final int[] x, y;                                     // coordinates, or null
    private int roadCount;

    /** A network with {@code n} junctions and no coordinates. */
    public Graph(int n) {
        this(n, null, null);
    }

    /** A network whose junction i is at (x[i], y[i]). */
    public Graph(int n, int[] x, int[] y) {
        if (n < 0) throw new IllegalArgumentException("n must be >= 0");
        if ((x == null) != (y == null) || (x != null && (x.length != n || y.length != n))) {
            throw new IllegalArgumentException("need one x and one y per node");
        }
        this.x = x == null ? null : x.clone();
        this.y = y == null ? null : y.clone();
        for (int i = 0; i < n; i++) {
            out.add(new ArrayList<>());
            in.add(new ArrayList<>());
        }
    }

    public int size() {
        return out.size();
    }

    public int roadCount() {
        return roadCount;
    }

    /** Adds a one-way road. Costs must be non-negative: Dijkstra's "closest unsettled node is final" relies on it. */
    public void addRoad(int from, int to, int cost) {
        check(from);
        check(to);
        if (cost < 0) throw new IllegalArgumentException("negative cost " + cost + " on " + from + "->" + to);
        Road r = new Road(from, to, cost);
        out.get(from).add(r);
        in.get(to).add(r);
        roadCount++;
    }

    /** Adds a two-way street (two one-way roads with the same cost). */
    public void addStreet(int a, int b, int cost) {
        addRoad(a, b, cost);
        addRoad(b, a, cost);
    }

    /** Closes every road from {@code from} to {@code to}. Returns how many were closed. */
    public int closeRoad(int from, int to) {
        return setOpen(from, to, false);
    }

    public int openRoad(int from, int to) {
        return setOpen(from, to, true);
    }

    private int setOpen(int from, int to, boolean open) {
        check(from);
        check(to);
        int n = 0;
        for (Road r : out.get(from)) {
            if (r.to == to && r.open != open) {
                r.open = open;
                n++;
            }
        }
        return n;
    }

    /** Roads leaving {@code node}, including closed ones (callers skip those). */
    public List<Road> roadsFrom(int node) {
        check(node);
        return out.get(node);
    }

    /** Roads arriving at {@code node}. */
    public List<Road> roadsInto(int node) {
        check(node);
        return in.get(node);
    }

    /** Cheapest open road from {@code from} to {@code to}, or -1 if there is none. */
    public int cost(int from, int to) {
        int best = -1;
        for (Road r : roadsFrom(from)) if (r.to == to && r.open && (best < 0 || r.cost < best)) best = r.cost;
        return best;
    }

    public boolean hasCoordinates() {
        return x != null;
    }

    public int x(int node) {
        check(node);
        if (x == null) throw new IllegalStateException("this graph has no coordinates");
        return x[node];
    }

    public int y(int node) {
        check(node);
        if (y == null) throw new IllegalStateException("this graph has no coordinates");
        return y[node];
    }

    private void check(int node) {
        if (node < 0 || node >= out.size()) throw new IndexOutOfBoundsException("no node " + node);
    }
}
