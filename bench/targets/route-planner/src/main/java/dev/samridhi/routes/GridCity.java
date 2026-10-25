package dev.samridhi.routes;

import java.util.Random;

/**
 * Generates a test city: a width x height grid of junctions joined by two-way streets, each costing between
 * {@code minCost} and {@code maxCost} (travel time varies with traffic), with a fraction of junctions blocked
 * (parks, rivers, buildings) so routes have to go around obstacles. Seeded, so the same seed gives the same city.
 */
public final class GridCity {
    private GridCity() {}

    public static int node(int width, int x, int y) {
        return y * width + x;
    }

    public static Graph generate(int width, int height, int minCost, int maxCost, double blockedFraction, long seed) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("empty grid");
        if (minCost < 0 || maxCost < minCost) throw new IllegalArgumentException("need 0 <= minCost <= maxCost");
        Random rnd = new Random(seed);
        int n = width * height;
        int[] xs = new int[n], ys = new int[n];
        boolean[] blocked = new boolean[n];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = node(width, x, y);
                xs[v] = x;
                ys[v] = y;
                blocked[v] = rnd.nextDouble() < blockedFraction;
            }
        }
        Graph g = new Graph(n, xs, ys);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = node(width, x, y);
                if (blocked[v]) continue;
                if (x + 1 < width && !blocked[v + 1]) g.addStreet(v, v + 1, minCost + rnd.nextInt(maxCost - minCost + 1));
                if (y + 1 < height && !blocked[v + width]) g.addStreet(v, v + width, minCost + rnd.nextInt(maxCost - minCost + 1));
            }
        }
        return g;
    }
}
