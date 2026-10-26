### Fault localization (20 bugs)

Rank of the true buggy method among the suspects. top-k = share of bugs where it is in the first k.

| Signals | top-1 | top-3 | top-5 | MRR |
|---|---|---|---|---|
| trace only (baseline) | 15% | 15% | 15% | 0.15 |
| text search only | 15% | 30% | 35% | 0.28 |
| trace + text (no coverage) | 25% | 30% | 45% | 0.34 |
| spectrum only, call-graph coverage | 15% | 30% | 45% | 0.31 |
| spectrum only, measured coverage | 30% | 45% | 75% | 0.48 |
| all signals, old weights (before) | 20% | 40% | 40% | 0.34 |
| tuned weights + logic, call-graph coverage | 40% | 70% | 80% | 0.57 |
| tuned weights + logic, measured coverage (default) | 55% | 70% | 80% | 0.66 |

dev split (10 bugs): full with measured coverage top-1 60%, top-3 80%; trace-only top-1 10%, top-3 10%.

test split (10 bugs): full with measured coverage top-1 50%, top-3 60%; trace-only top-1 20%, top-3 20%.

### Repair (20 bugs, offline mutation synthesizer, no model)

Resolved (passes every test on a clean checkout): **9 / 20 (45%)**; source identical to the original: 7. Latency of resolved runs: median 73 s, p95 140 s.

- Bugs fixable by one small edit: 9 / 15 resolved
- Bugs needing new code or a different kind of edit: 0 / 5 resolved

| Case | Kind | Resolved | Rounds | Candidates verified | Time | Fix found |
|---|---|---|---|---|---|---|
| 01-rp-bfs-hops | operator | yes (original restored) | 2 | 10 | 38 s | change constant 2 to 1 (line 54) |
| 02-rp-astar-closed | operator | no | 4 | 32 | 100 s | - |
| 03-rp-tsp-return | other | no | 4 | 32 | 101 s | - |
| 04-rp-two-opt-hang | operator | yes (differs from original) | 1 | 2 | 93 s | replace `<` with `>` (line 141) |
| 05-rp-cheapest-parallel | operator | yes (original restored) | 3 | 22 | 56 s | replace `>` with `<` (line 119) |
| 06-rp-heuristic-k | other | no | 4 | 32 | 57 s | - |
| 07-rp-zero-cost | operator | yes (original restored) | 1 | 2 | 10 s | replace `<=` with `<` (line 69) |
| 08-os-reserve-exact | operator | no | 4 | 32 | 123 s | - |
| 09-os-settle-sign | operator | yes (original restored) | 1 | 8 | 45 s | replace `+=` with `-=` (line 85) |
| 10-os-refund-flag | operator | no | 4 | 24 | 91 s | - |
| 11-os-paid-cancel | other | no | 4 | 32 | 107 s | - |
| 12-os-idem-mismatch | operator | yes (differs from original) | 3 | 24 | 90 s | negate the condition (line 31) |
| 13-os-total-add | other | no | 4 | 32 | 118 s | - |
| 14-sq-dedup-window | operator | yes (original restored) | 2 | 12 | 140 s | replace `<` with `<=` (line 121) |
| 15-sq-visible-edge | operator | yes (original restored) | 1 | 4 | 73 s | replace `<` with `<=` (line 196) |
| 16-sq-dlq-count | operator | no | 4 | 32 | 263 s | - |
| 17-sq-retention | operator | no | 4 | 32 | 337 s | - |
| 18-sq-receive-count | other | no | 4 | 32 | 389 s | - |
| 19-sq-visibility-sign | operator | no | 4 | 32 | 390 s | - |
| 20-sq-max-batch | operator | yes (original restored) | 1 | 4 | 125 s | replace `>=` with `>` (line 167) |
