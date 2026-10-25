package dev.samridhi.routes;

import java.util.Random;

/**
 * Measures the README numbers:   java -cp out/main:out/test dev.samridhi.routes.Bench
 * Work is counted as nodes settled, which does not depend on the machine; times are indicative only.
 */
public final class Bench {
    public static void main(String[] args) {
        int w = 200, h = 200;
        Graph g = GridCity.generate(w, h, 10, 30, 0.2, 7);
        Random rnd = new Random(1);
        long sD = 0, sA = 0, sB = 0, sBfs = 0, tD = 0, tA = 0, tB = 0;
        int queries = 0;
        while (queries < 300) {
            int s = rnd.nextInt(w * h), t = rnd.nextInt(w * h);
            long t0 = System.nanoTime();
            Path d = ShortestPaths.dijkstra(g, s, t);
            long t1 = System.nanoTime();
            if (!d.found() || d.nodes().size() < 2) continue;
            Path a = ShortestPaths.aStar(g, s, t, Heuristics.manhattan(g, t));
            long t2 = System.nanoTime();
            Path b = ShortestPaths.bidirectional(g, s, t);
            long t3 = System.nanoTime();
            if (a.cost() != d.cost() || b.cost() != d.cost()) throw new AssertionError("algorithms disagree");
            sD += d.settled(); sA += a.settled(); sB += b.settled(); sBfs += ShortestPaths.bfs(g, s, t).settled();
            tD += t1 - t0; tA += t2 - t1; tB += t3 - t2;
            queries++;
        }
        System.out.printf("%dx%d grid, %d junctions, %d roads, %d random routes%n", w, h, g.size(), g.roadCount(), queries);
        System.out.printf("  Dijkstra       %7.0f nodes settled per query  %6.2f ms%n", (double) sD / queries, tD / 1e6 / queries);
        System.out.printf("  A* (Manhattan) %7.0f nodes settled per query  %6.2f ms  (%.1fx fewer)%n", (double) sA / queries, tA / 1e6 / queries, (double) sD / sA);
        System.out.printf("  Bidirectional  %7.0f nodes settled per query  %6.2f ms  (%.1fx fewer)%n", (double) sB / queries, tB / 1e6 / queries, (double) sD / sB);
        System.out.printf("  safe cost per unit distance for A*: %.1f%n", Heuristics.safeCostPerUnit(g));

        for (int k : new int[] {8, 12, 15}) {
            double gapNN = 0, gap2 = 0, worst2 = 0;
            int optimal2 = 0, trials = 40;
            long tExact = 0, t2opt = 0;
            for (int trial = 0; trial < trials; trial++) {
                int depot;
                do depot = rnd.nextInt(w * h); while (g.roadsFrom(depot).isEmpty());
                int[] stops = new int[k];
                long[][] m = null;
                while (m == null) {
                    for (int i = 0; i < k; i++) stops[i] = rnd.nextInt(w * h);
                    try { m = DeliveryPlanner.costMatrix(g, depot, stops); } catch (DeliveryPlanner.UnreachableStopException e) { m = null; }
                }
                long e0 = System.nanoTime();
                long opt = DeliveryPlanner.tourCost(DeliveryPlanner.exactTour(m), m);
                long e1 = System.nanoTime();
                int[] nn = DeliveryPlanner.nearestNeighbour(m);
                long two = DeliveryPlanner.tourCost(DeliveryPlanner.twoOpt(nn, m), m);
                long e2 = System.nanoTime();
                tExact += e1 - e0; t2opt += e2 - e1;
                double gn = 100.0 * (DeliveryPlanner.tourCost(nn, m) - opt) / opt, g2 = 100.0 * (two - opt) / opt;
                gapNN += gn; gap2 += g2; worst2 = Math.max(worst2, g2);
                if (two == opt) optimal2++;
            }
            System.out.printf("%2d stops: nearest-neighbour %5.1f%% above optimal, +2-opt %4.1f%% (worst %4.1f%%, optimal in %d/%d); exact %7.2f ms, 2-opt %5.2f ms%n",
                    k, gapNN / trials, gap2 / trials, worst2, optimal2, trials, tExact / 1e6 / trials, t2opt / 1e6 / trials);
        }
    }
}
