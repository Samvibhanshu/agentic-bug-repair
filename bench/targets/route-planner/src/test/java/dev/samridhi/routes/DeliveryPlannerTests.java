package dev.samridhi.routes;

import static dev.samridhi.routes.testkit.Assert.assertEquals;
import static dev.samridhi.routes.testkit.Assert.assertThrows;
import static dev.samridhi.routes.testkit.Assert.assertTrue;

import dev.samridhi.routes.testkit.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class DeliveryPlannerTests {
    @Test
    void heldKarpMatchesTryingEveryOrder() {
        Random rnd = new Random(3);
        for (int trial = 0; trial < 400; trial++) {
            int k = rnd.nextInt(8);                                              // up to 7! = 5040 orders
            long[][] m = Oracles.randomMatrix(rnd, k);
            int[] tour = DeliveryPlanner.exactTour(m);
            assertVisitsEachStopOnce(tour, k);
            assertEquals(Oracles.bruteForceTour(m), DeliveryPlanner.tourCost(tour, m));
        }
    }

    @Test
    void twoOptNeverMakesATourWorseAndNeverBeatsTheOptimum() {
        Random rnd = new Random(4);
        for (int trial = 0; trial < 400; trial++) {
            int k = 1 + rnd.nextInt(10);
            long[][] m = Oracles.randomMatrix(rnd, k);
            int[] nn = DeliveryPlanner.nearestNeighbour(m);
            int[] improved = DeliveryPlanner.twoOpt(nn, m);
            assertVisitsEachStopOnce(nn, k);
            assertVisitsEachStopOnce(improved, k);
            long opt = DeliveryPlanner.tourCost(DeliveryPlanner.exactTour(m), m);
            assertTrue(DeliveryPlanner.tourCost(improved, m) <= DeliveryPlanner.tourCost(nn, m), "2-opt made it worse");
            assertTrue(DeliveryPlanner.tourCost(improved, m) >= opt, "beat the optimum: the optimum is wrong");
        }
    }

    @Test
    void greedyIsNotOptimal() {
        // Houses on one street at positions 0 (depot), 1, -2 and 4. Greedy goes 0 -> 1 -> -2 -> 4 -> 0 = 14,
        // zig-zagging past the depot; the best round, 0 -> 1 -> 4 -> -2 -> 0, is 12.
        int[] pos = {0, 1, -2, 4};
        long[][] m = new long[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) m[i][j] = Math.abs(pos[i] - pos[j]);
        int[] nn = DeliveryPlanner.nearestNeighbour(m);
        int[] exact = DeliveryPlanner.exactTour(m);
        assertEquals(14L, DeliveryPlanner.tourCost(nn, m));
        assertEquals(12L, DeliveryPlanner.tourCost(exact, m));
        assertEquals(Oracles.bruteForceTour(m), DeliveryPlanner.tourCost(exact, m));
    }

    @Test
    void planOnARealGridDrivesARouteThatExistsAndCostsWhatItSays() {
        Graph g = GridCity.generate(30, 30, 10, 30, 0.15, 11);
        Random rnd = new Random(11);
        int planned = 0;
        while (planned < 25) {
            int depot = rnd.nextInt(g.size());
            int[] stops = new int[rnd.nextInt(9)];
            for (int i = 0; i < stops.length; i++) stops[i] = rnd.nextInt(g.size());
            DeliveryPlanner.Round round;
            try {
                round = DeliveryPlanner.plan(g, depot, stops);
            } catch (DeliveryPlanner.UnreachableStopException e) {
                continue;                                                        // some cells are walled in
            }
            planned++;
            List<Integer> roads = round.roads();
            assertEquals(depot, roads.get(0));
            assertEquals(depot, roads.get(roads.size() - 1));
            long total = 0;
            for (int i = 0; i + 1 < roads.size(); i++) {
                int c = g.cost(roads.get(i), roads.get(i + 1));
                assertTrue(c >= 0, "no road " + roads.get(i) + "->" + roads.get(i + 1));
                total += c;
            }
            assertEquals(round.cost(), total);
            for (int stop : stops) assertTrue(roads.contains(stop), "stop " + stop + " never visited");
            int[] order = round.order();
            Arrays.sort(order);
            for (int i = 0; i < order.length; i++) assertEquals(i, order[i]);    // each stop index exactly once
        }
    }

    @Test
    void manyStopsFallBackToTheHeuristic() {
        Graph g = GridCity.generate(20, 20, 1, 9, 0, 2);
        int[] stops = new int[25];                                               // over the exact limit
        for (int i = 0; i < stops.length; i++) stops[i] = i * 13 % g.size();
        DeliveryPlanner.Round r = DeliveryPlanner.plan(g, 0, stops);
        assertEquals(25, r.order().length);
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryPlanner.exactTour(DeliveryPlanner.costMatrix(g, 0, stops)));
    }

    @Test
    void unreachableStopsAreReportedNotIgnored() {
        Graph g = new Graph(3);
        g.addStreet(0, 1, 5);                                                    // node 2 is cut off
        assertThrows(DeliveryPlanner.UnreachableStopException.class, () -> DeliveryPlanner.plan(g, 0, new int[] {1, 2}));
        Graph oneWay = new Graph(2);
        oneWay.addRoad(0, 1, 5);                                                 // can get there, cannot come back
        assertThrows(DeliveryPlanner.UnreachableStopException.class, () -> DeliveryPlanner.plan(oneWay, 0, new int[] {1}));
    }

    @Test
    void emptyAndTrivialRounds() {
        Graph g = new Graph(2);
        g.addStreet(0, 1, 7);
        DeliveryPlanner.Round none = DeliveryPlanner.plan(g, 0, new int[0]);
        assertEquals(0L, none.cost());
        assertEquals(List.of(0), none.roads());
        DeliveryPlanner.Round one = DeliveryPlanner.plan(g, 0, new int[] {1});
        assertEquals(14L, one.cost());
        assertEquals(List.of(0, 1, 0), one.roads());
        DeliveryPlanner.Round dup = DeliveryPlanner.plan(g, 0, new int[] {1, 1, 0});   // repeats and the depot itself
        assertEquals(14L, dup.cost());
    }

    private static void assertVisitsEachStopOnce(int[] tour, int k) {
        int[] sorted = tour.clone();
        Arrays.sort(sorted);
        assertEquals(k, sorted.length);
        for (int i = 0; i < k; i++) assertEquals(i + 1, sorted[i]);
    }
}
