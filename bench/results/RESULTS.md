### Fault localization (20 bugs)

Rank of the true buggy method among the suspects. top-k = share of bugs where it is in the first k.

| Signals | top-1 | top-3 | top-5 | MRR |
|---|---|---|---|---|
| trace only (baseline) | 15% | 15% | 15% | 0.15 |
| text search only | 15% | 30% | 35% | 0.28 |
| trace + text (no graph) | 25% | 30% | 45% | 0.34 |
| call-graph spectrum only | 15% | 30% | 45% | 0.31 |
| full (graph + trace + text) | 20% | 40% | 40% | 0.34 |

dev split (10 bugs): full top-1 20%, top-3 30%; trace-only top-1 10%, top-3 10%.

test split (10 bugs): full top-1 20%, top-3 50%; trace-only top-1 20%, top-3 20%.

### Repair (20 bugs, offline mutation synthesizer, no model)

Resolved (passes every test on a clean checkout): **4 / 20 (20%)**; source identical to the original: 4. Latency of resolved runs: median 35 s, p95 142 s.

- Bugs fixable by one small edit: 4 / 15 resolved
- Bugs needing new code or a different kind of edit: 0 / 5 resolved

| Case | Kind | Resolved | Rounds | Candidates verified | Time | Fix found |
|---|---|---|---|---|---|---|
| 01-rp-bfs-hops | operator | yes (original restored) | 2 | 10 | 35 s | change constant 2 to 1 (line 54) |
| 02-rp-astar-closed | operator | no | 4 | 32 | 82 s | - |
| 03-rp-tsp-return | other | no | 4 | 32 | 72 s | - |
| 04-rp-two-opt-hang | operator | no | 4 | 32 | 511 s | - |
| 05-rp-cheapest-parallel | operator | no | 4 | 32 | 119 s | - |
| 06-rp-heuristic-k | other | no | 4 | 32 | 56 s | - |
| 07-rp-zero-cost | operator | yes (original restored) | 1 | 2 | 8 s | replace `<=` with `<` (line 69) |
| 08-os-reserve-exact | operator | no | 4 | 32 | 98 s | - |
| 09-os-settle-sign | operator | no | 4 | 32 | 106 s | - |
| 10-os-refund-flag | operator | no | 4 | 32 | 117 s | - |
| 11-os-paid-cancel | other | no | 4 | 32 | 114 s | - |
| 12-os-idem-mismatch | operator | no | 4 | 32 | 103 s | - |
| 13-os-total-add | other | no | 4 | 21 | 59 s | - |
| 14-sq-dedup-window | operator | yes (original restored) | 2 | 14 | 142 s | replace `<` with `<=` (line 121) |
| 15-sq-visible-edge | operator | no | 4 | 32 | 203 s | - |
| 16-sq-dlq-count | operator | no | 4 | 7 | 41 s | - |
| 17-sq-retention | operator | no | 4 | 16 | 93 s | - |
| 18-sq-receive-count | other | no | 4 | 32 | 460 s | - |
| 19-sq-visibility-sign | operator | no | 4 | 32 | 467 s | - |
| 20-sq-max-batch | operator | yes (original restored) | 1 | 4 | 95 s | replace `>=` with `>` (line 167) |
