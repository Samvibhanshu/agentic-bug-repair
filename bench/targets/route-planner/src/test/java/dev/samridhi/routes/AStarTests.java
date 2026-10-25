package dev.samridhi.routes;

import static dev.samridhi.routes.testkit.Assert.assertEquals;
import static dev.samridhi.routes.testkit.Assert.assertTrue;

import dev.samridhi.routes.testkit.Test;
import java.util.Random;

public class AStarTests {
    @Test
    void aStarReturnsTheSameCostAsDijkstraWhileSettlingFewerNodes() {
        long settledDijkstra = 0, settledAStar = 0;
        for (int seed = 0; seed < 20; seed++) {
            Graph g = GridCity.generate(40, 40, 10, 30, 0.25, seed);
            Random rnd = new Random(seed);
            for (int q = 0; q < 30; q++) {
                int s = rnd.nextInt(g.size()), t = rnd.nextInt(g.size());
                Path d = ShortestPaths.dijkstra(g, s, t);
                Path a = ShortestPaths.aStar(g, s, t, Heuristics.manhattan(g, t));
                assertEquals(d.cost(), a.cost());
                Oracles.assertValidPath(g, a, s, t, false);
                if (d.found()) {
                    settledDijkstra += d.settled();
                    settledAStar += a.settled();
                }
            }
        }
        assertTrue(settledAStar * 3 < settledDijkstra * 2, "A* settled " + settledAStar + " vs Dijkstra " + settledDijkstra);
    }

    @Test
    void theManhattanHeuristicNeverOverEstimates() {
        for (int seed = 0; seed < 10; seed++) {
            Graph g = GridCity.generate(25, 25, 5, 40, 0.2, seed);
            int t = new Random(seed).nextInt(g.size());
            long[] trueCost = ShortestPaths.dijkstraAll(g, t);               // streets are two-way: cost(u,t) == cost(t,u)
            ShortestPaths.Heuristic h = Heuristics.manhattan(g, t);
            for (int u = 0; u < g.size(); u++) {
                if (trueCost[u] != Path.UNREACHABLE) assertTrue(h.estimate(u) <= trueCost[u], "h(" + u + ") over-estimates");
            }
            // ... and is consistent: along any road it drops by at most the road's cost
            for (int u = 0; u < g.size(); u++) {
                for (Graph.Road r : g.roadsFrom(u)) assertTrue(h.estimate(u) <= r.cost + h.estimate(r.to), "inconsistent");
            }
        }
    }

    @Test
    void theSafeCostPerUnitIsMeasuredFromTheGraph() {
        assertEquals(5.0, Heuristics.safeCostPerUnit(GridCity.generate(30, 30, 5, 40, 0.1, 3)));
        Graph g = new Graph(2, new int[] {0, 3}, new int[] {0, 4});            // one road covering 7 grid units
        g.addRoad(0, 1, 14);
        assertEquals(2.0, Heuristics.safeCostPerUnit(g));
    }

    @Test
    void anOverEstimatingHeuristicGivesWrongAnswers() {
        // Why the measured cost per unit matters: assume 40 per unit when roads cost 5..40 and A* misses cheaper routes.
        int wrong = 0;
        for (int seed = 0; seed < 10; seed++) {
            Graph g = GridCity.generate(30, 30, 5, 40, 0.2, seed);
            Random rnd = new Random(seed);
            for (int q = 0; q < 20; q++) {
                int s = rnd.nextInt(g.size()), t = rnd.nextInt(g.size());
                Path greedy = ShortestPaths.aStar(g, s, t, Heuristics.manhattan(g, t, 40));
                Path right = ShortestPaths.dijkstra(g, s, t);
                assertTrue(greedy.cost() >= right.cost(), "can never beat the true optimum");
                if (greedy.cost() > right.cost()) wrong++;
            }
        }
        assertTrue(wrong > 0, "expected the inadmissible heuristic to return some suboptimal routes");
    }
}
