package dev.samridhi.routes;

import static dev.samridhi.routes.testkit.Assert.assertEquals;
import static dev.samridhi.routes.testkit.Assert.assertThrows;
import static dev.samridhi.routes.testkit.Assert.assertTrue;

import dev.samridhi.routes.testkit.Test;
import java.util.List;
import java.util.Random;

/** Every algorithm against Floyd-Warshall on thousands of random graphs, plus the edge cases. */
public class ShortestPathTests {
    @Test
    void dijkstraAStarAndBidirectionalMatchFloydWarshallOnRandomGraphs() {
        Random rnd = new Random(1);
        for (int trial = 0; trial < 1500; trial++) {
            Graph g = Oracles.randomGraph(rnd, 12);
            long[][] fw = Oracles.floydWarshall(g, false);
            for (int q = 0; q < 6; q++) {
                int s = rnd.nextInt(g.size()), t = rnd.nextInt(g.size());
                for (Path p : List.of(ShortestPaths.dijkstra(g, s, t), ShortestPaths.bidirectional(g, s, t),
                        ShortestPaths.aStar(g, s, t, node -> 0))) {
                    assertEquals(fw[s][t], p.cost());
                    Oracles.assertValidPath(g, p, s, t, false);
                }
                long[] all = ShortestPaths.dijkstraAll(g, s);
                for (int v = 0; v < g.size(); v++) assertEquals(fw[s][v], all[v]);
            }
        }
    }

    @Test
    void bfsFindsTheFewestRoads() {
        Random rnd = new Random(2);
        for (int trial = 0; trial < 1500; trial++) {
            Graph g = Oracles.randomGraph(rnd, 12);
            long[][] hops = Oracles.floydWarshall(g, true);
            int s = rnd.nextInt(g.size()), t = rnd.nextInt(g.size());
            Path p = ShortestPaths.bfs(g, s, t);
            assertEquals(hops[s][t], p.cost());
            Oracles.assertValidPath(g, p, s, t, true);
        }
    }

    @Test
    void fewestRoadsIsNotCheapest() {
        // 0 -> 3 directly costs 100; 0 -> 1 -> 2 -> 3 costs 3. BFS picks the direct road, Dijkstra the detour.
        Graph g = new Graph(4);
        g.addRoad(0, 3, 100);
        g.addRoad(0, 1, 1);
        g.addRoad(1, 2, 1);
        g.addRoad(2, 3, 1);
        assertEquals(List.of(0, 3), ShortestPaths.bfs(g, 0, 3).nodes());
        assertEquals(List.of(0, 1, 2, 3), ShortestPaths.dijkstra(g, 0, 3).nodes());
        assertEquals(3L, ShortestPaths.bidirectional(g, 0, 3).cost());
    }

    @Test
    void bidirectionalMustNotStopAtTheFirstMeetingNode() {
        // The two searches first meet at node 2 (route 0-2-4 costs 10), but 0-1-3-4 costs 9.
        Graph g = new Graph(5);
        g.addRoad(0, 2, 5);
        g.addRoad(2, 4, 5);
        g.addRoad(0, 1, 3);
        g.addRoad(1, 3, 3);
        g.addRoad(3, 4, 3);
        Path p = ShortestPaths.bidirectional(g, 0, 4);
        assertEquals(9L, p.cost());
        assertEquals(List.of(0, 1, 3, 4), p.nodes());
    }

    @Test
    void startEqualsTargetAndUnreachableTargets() {
        Graph g = new Graph(3);
        g.addRoad(0, 1, 4);
        for (Path p : List.of(ShortestPaths.dijkstra(g, 2, 2), ShortestPaths.bidirectional(g, 2, 2), ShortestPaths.bfs(g, 2, 2))) {
            assertEquals(0L, p.cost());
            assertEquals(List.of(2), p.nodes());
        }
        for (Path p : List.of(ShortestPaths.dijkstra(g, 1, 0), ShortestPaths.bidirectional(g, 1, 0), ShortestPaths.bfs(g, 0, 2))) {
            assertTrue(!p.found() && p.nodes().isEmpty());
        }
    }

    @Test
    void closingARoadReroutesAndReopeningRestoresIt() {
        Graph g = new Graph(3);
        g.addStreet(0, 1, 1);
        g.addStreet(1, 2, 1);
        g.addStreet(0, 2, 10);
        assertEquals(2L, ShortestPaths.dijkstra(g, 0, 2).cost());
        assertEquals(1, g.closeRoad(1, 2));                                   // accident on 1 -> 2
        assertEquals(10L, ShortestPaths.dijkstra(g, 0, 2).cost());
        assertEquals(2L, ShortestPaths.dijkstra(g, 2, 0).cost());             // the other direction is still open
        assertEquals(1, g.openRoad(1, 2));
        assertEquals(2L, ShortestPaths.dijkstra(g, 0, 2).cost());
        assertEquals(0, g.openRoad(1, 2));                                    // already open: nothing changes
    }

    @Test
    void negativeCostsAndBadInputAreRejected() {
        Graph g = new Graph(2);
        assertThrows(IllegalArgumentException.class, () -> g.addRoad(0, 1, -1));   // Dijkstra would be wrong
        assertThrows(IndexOutOfBoundsException.class, () -> g.addRoad(0, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new Graph(2, new int[] {0}, new int[] {0, 1}));
        assertThrows(IllegalStateException.class, () -> g.x(0));                   // no coordinates
    }

    @Test
    void longRoutesOnALargeGridDoNotOverflowOrRecurse() {
        Graph g = GridCity.generate(300, 300, 5_000_000, 10_000_000, 0, 5);          // 90,000 nodes, big costs
        int s = GridCity.node(300, 0, 0), t = GridCity.node(300, 299, 299);
        Path d = ShortestPaths.dijkstra(g, s, t);
        assertEquals(d.cost(), ShortestPaths.bidirectional(g, s, t).cost());
        assertEquals(d.cost(), ShortestPaths.aStar(g, s, t, Heuristics.manhattan(g, t)).cost());
        assertTrue(d.cost() > Integer.MAX_VALUE, "costs are summed in long");
        assertEquals(599, d.nodes().size());                                  // 598 roads: no route on a grid is shorter
    }
}
