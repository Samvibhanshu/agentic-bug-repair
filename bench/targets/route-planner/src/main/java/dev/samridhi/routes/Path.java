package dev.samridhi.routes;

import java.util.List;

/**
 * Result of a route search.
 *
 * @param nodes    junctions from start to target inclusive; empty if the target is unreachable
 * @param cost     total travel cost, or {@link #UNREACHABLE}
 * @param settled  how many nodes the search finalised: a machine-independent measure of the work it did,
 *                 used to compare algorithms fairly (wall-clock time depends on the computer and the JIT)
 */
public record Path(List<Integer> nodes, long cost, int settled) {
    public static final long UNREACHABLE = Long.MAX_VALUE;

    public Path {
        nodes = List.copyOf(nodes);
    }

    public boolean found() {
        return cost != UNREACHABLE;
    }

    static Path unreachable(int settled) {
        return new Path(List.of(), UNREACHABLE, settled);
    }
}
