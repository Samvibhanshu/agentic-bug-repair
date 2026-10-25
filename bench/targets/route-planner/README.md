# route-planner

[![build-and-test](https://github.com/Samvibhanshu/route-planner/actions/workflows/ci.yml/badge.svg)](https://github.com/Samvibhanshu/route-planner/actions/workflows/ci.yml)

Last-mile delivery routing in plain Java 17: find the cheapest route across a city road network, then plan a van's
round through many stops. It covers four shortest-path algorithms and the travelling-salesman problem, each tested
against a slow but obviously correct reference. No dependencies, just the JDK.

```bash
./build.sh                                              # compile with -Xlint:all and run all 19 tests
java -cp out/main:out/test dev.samridhi.routes.Bench    # reproduce the numbers below
```

## Part 1: one route

| Algorithm | Idea | Use when |
|---|---|---|
| **BFS** | Explore level by level; the first time you see a node is the fewest roads to it | Every road costs the same |
| **Dijkstra** | Always finalise the closest unfinished node, using a priority queue | Costs differ (traffic) and must be non-negative |
| **A\*** | Dijkstra, but ordered by *cost so far + under-estimate of cost to go*, so it heads for the target | You can cheaply under-estimate the remaining cost (here: grid distance) |
| **Bidirectional Dijkstra** | Search from both ends and stop when they provably can't improve | No geometry, but roads can be followed backwards |

**Measured** on a 200 × 200 city (40,000 junctions, 102,282 roads, 20% of junctions blocked, road costs 10–30),
over 300 random routes. All three always returned the same cost. Work is counted in nodes settled, which does not
depend on the machine:

| | Nodes settled per route | Time per route |
|---|---|---|
| Dijkstra | 15,753 | 4.3 ms |
| A\* (Manhattan heuristic) | 7,263 (2.2× fewer) | 4.0 ms |
| Bidirectional | 10,346 (1.5× fewer) | 4.0 ms |

Two things worth knowing from these numbers:

- **A\* explores half as much, but is barely faster in wall-clock time.** Each step costs more (computing the heuristic, and more queue entries per settled node). Fewer nodes settled is not automatically less time.
- **A\* is only as good as its heuristic.** Roads cost 10–30, but the heuristic has to assume the cheapest (10 per block) to stay safe, so it under-estimates by about half on average. With uniform costs it would do far better.

### The two traps, and how the code avoids them

1. **An A\* heuristic that over-estimates returns wrong routes.** So the cost per unit of distance is *measured from the graph* (`Heuristics.safeCostPerUnit`: the minimum over all roads of cost ÷ distance covered), not assumed. A test shows that assuming 40 per block on a city whose roads cost 5–40 does return worse routes, and another checks the measured heuristic never over-estimates and is consistent on every road.
2. **Bidirectional search must not stop at the first meeting node.** The first node reached by both searches is often not on the best route. The search keeps the best total seen through any node reached from both sides, and stops only when the two frontiers' smallest costs add up to at least that total. `bidirectionalMustNotStopAtTheFirstMeetingNode` is a 5-node graph where stopping early returns 10 instead of 9.

Roads can be closed and reopened (an accident, roadworks) and every algorithm routes around them.

## Part 2: a delivery round

Start at the depot, visit every stop once and return. That is the travelling salesman problem, which is NP-hard.

| Method | Cost | Result |
|---|---|---|
| **Held–Karp** (dynamic programming over subsets) | O(2ᵏ · k²) | Optimal. `best[set][last]` = cheapest way to visit exactly `set`, ending at `last` |
| **Nearest neighbour** | O(k²) | Fast, often noticeably worse |
| **Nearest neighbour + 2-opt** | Repeat until no improvement: reverse any stretch of the tour if that makes it cheaper | Never worse than its input; no guarantee of optimal |

Trying every order is k!: 1.3 trillion at 15 stops. Held–Karp at 15 stops is about 7 million steps.

**Measured** with real road costs (one Dijkstra from the depot and from each stop builds the cost matrix), 40 random rounds each:

| Stops | Nearest neighbour above optimal | + 2-opt above optimal | 2-opt found the optimum | Held–Karp time | 2-opt time |
|---|---|---|---|---|---|
| 8 | 9.2% | 0.4% (worst 7.6%) | 34 / 40 | 0.17 ms | 0.04 ms |
| 12 | 12.7% | 1.2% (worst 10.9%) | 24 / 40 | 2.0 ms | 0.05 ms |
| 15 | 13.1% | 1.5% (worst 9.1%) | 20 / 40 | 21 ms | 0.06 ms |

Held–Karp's time grows about 10× from 12 to 15 stops, as 2ᵏ · k² predicts. So `plan()` uses it up to 16 stops
and switches to nearest neighbour + 2-opt beyond that. Costs may be one-way (asymmetric), so 2-opt re-costs the
whole tour for each candidate move instead of using the usual symmetric shortcut. Stops that can't be reached, or
can't be returned from, raise an error naming them instead of being silently skipped.

## Tests

| Test class | What it checks |
|---|---|
| `ShortestPathTests` | Dijkstra, A\* and bidirectional match **Floyd–Warshall** (a different, O(n³) algorithm) on 1,500 random graphs with parallel roads, self-loops, zero-cost and closed roads, and unreachable parts. BFS matches fewest-hops Floyd–Warshall. Every returned path is checked to exist and to cost what it claims. Also: start = target, unreachable targets, road closures, negative costs rejected, and a 90,000-node route whose cost overflows `int` |
| `AStarTests` | Same cost as Dijkstra on 600 grid routes while settling under two-thirds as many nodes; the heuristic is admissible and consistent; an over-estimating heuristic really does return worse routes |
| `DeliveryPlannerTests` | Held–Karp equals trying **every permutation** (400 random asymmetric matrices, up to 7 stops). 2-opt never worsens a tour and never beats the optimum. A 4-house example where greedy is provably worse (14 vs 12). Full plans on a grid drive only real roads and cost exactly what they report. Unreachable stops, empty rounds, repeated stops |

**Do the tests catch real bugs?** I planted 11 bugs one at a time, for example: stopping bidirectional search at
the first meeting, A\* ignoring closed roads, a heuristic using the maximum instead of the minimum cost per unit,
Held–Karp forgetting the drive back to the depot, nearest neighbour revisiting stops, and unreachable stops passing
silently. 9 were caught. The other 2 behave identically to the original, so no test can fail them. For example,
letting BFS update a node it has already seen changes nothing, because BFS never finds a shorter way to a node later.

## Layout

```
src/main/java/dev/samridhi/routes/
  Graph            adjacency lists, one-way roads and two-way streets, close/open roads, coordinates
  ShortestPaths    BFS, Dijkstra (single target and all nodes), A*, bidirectional Dijkstra
  Heuristics       Manhattan heuristic with a cost per unit measured from the graph
  DeliveryPlanner  cost matrix, Held-Karp, nearest neighbour, 2-opt, plan()
  GridCity         seeded random city generator
  Path             nodes, cost, nodes settled
src/test/java/dev/samridhi/routes/   19 tests, Floyd-Warshall and permutation oracles, Bench
```
