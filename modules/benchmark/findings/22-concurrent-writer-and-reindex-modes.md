# Finding 22: Concurrent-writer and reindex-timing benchmark modes (ENTRYSTORE-1084)

Date: 2026-07-16
Branch: `feature/benchmark-ai-deferred`
Ticket: [ENTRYSTORE-1084](https://metasolutions.atlassian.net/browse/ENTRYSTORE-1084)

## What was added

Two harness modes, deferred from the round-2 work (doc 20 §5 ranked this first
because everything Solr-side was unmeasurable without it):

- **`-w/--writers <N>`** (default 1): the person list is split into N
  contiguous chunks, each written by its own platform thread into the same
  context. Every worker authenticates itself first (`setAuthenticatedUserURI`
  is a ThreadLocal), and with `-B true` each worker wraps its whole chunk in
  its own `inBatch` — a batch must stay on one thread (doc 08 Theme G).
  Incompatible with `-i true`; `-m` per-insert sampling is skipped when N > 1.
  Implemented as `ConcurrentWriters` (benchmark-common, generic fan-out) plus
  one `ConcurrentMultipleTransactions` per module mirroring the existing
  runner shape. In benchmark-solr, `-B true` now also routes through the
  concurrent runner at N = 1 (the module previously had no batched
  single-writer path — `-B` was silently ignored).
- **`-R/--reindex <boolean>`** (benchmark-solr): after the write phase and
  queue drain, a timed `reindexSync(true)` (purge + rebuild of every context)
  followed by `waitForQueueDrain()` — reindexSync returns once documents are
  queued, so the drain is part of the observable duration. Logged as
  `Reindexing took N milliseconds`.

## How it was measured

Paired interleaved A/B per the doc 20/21 protocol: fat-jar snapshots per side,
alternating run order, fresh native store per run, Solr core purged between
runs (`bench-solr` Docker container, `solr:10.0.0`, IT core config), canary =
"Generating data took" (all runs 203–265 ms → comparable), AC power, first
warm-up run discarded. `-s native -u 2000` (4 000 person/address entries,
4 008 Solr docs).

The **before** side for the A1 proof is a hybrid: core at `4b913041` (the
parent of `c71e025d`, the A1/A9/A13 write-path decoupling) with this branch's
benchmark module copied in — the identical harness drives both sides, and all
APIs the harness uses (`waitForQueueDrain`, `reindexSync`,
`getPostQueueSize`) already existed pre-A1, so no shim was needed.

## Results

### A1 decoupling proof (4 writers, before `4b913041` vs after tip)

| Mode | before (ms, add+drain) | after (ms, add+drain) | Δ |
|---|---:|---:|---:|
| Unbatched | 44 076 / 45 609 / 43 805 → **44 497** | 46 007 / 45 073 / 44 510 → **45 197** | +1.6% (**flat**, spreads overlap) |
| Batched | 7 563 / 7 311 / 7 728 → **7 534** | 5 140 / 5 515 / 5 686 → **5 447** | **−27.7% (1.38×)**, distributions disjoint |

### Writers scaling on the tip (u=2000, add+drain ms, avg of 3)

| Writers | Unbatched | Batched |
|---:|---:|---:|
| 1 | 45 263 | 5 656 |
| 2 | 45 157 | 5 386 |
| 4 | 45 197 | 5 447 |
| 8 | 46 196 | 5 420 |

### Reindex baseline (tip, 4 008 docs, purge + rebuild + drain)

1 864 / 1 821 / 1 785 → **1 823 ms** (≈ 2 200 docs/s).

## Implication

- **The A1 throughput claim is confirmed, but only where commit cost doesn't
  mask it.** In the batched concurrent workload the decoupling is worth
  −27.7% wall-clock: pre-A1, writer threads paid Solr document building
  inline (pre-A1 `add ≈ add+drain` — the queue was already drained when the
  inserts finished, i.e. the writers had done all the work); post-A1 the
  writers only queue URIs and the submitter builds documents overlapped with
  subsequent batches (~500 ms drain tail).
- **In the unbatched workload the decoupling is invisible** — per-entry
  RDF4J native-store transactions under the repository monitor dominate
  (~22 ms/entry), and doc building is noise next to them. The doc-20
  hypothesis "the benefit only appears under concurrent writers" needs
  sharpening: it appears under concurrent writers *whose commit overhead is
  batched away*.
- **Write throughput does not scale with writer count in either mode** —
  the repository monitor serializes the write path entirely (flat 45.2 s
  unbatched, flat ~5.4 s batched across 1–8 writers; w8 unbatched slightly
  worse from contention overhead). Concurrency headroom on the write path
  requires narrowing the monitor, not more threads; this quantifies the
  motivation for ENTRYSTORE-1086 (maintenance ops off the monitor) and the
  deferred store-level work.
- The reindex phase gives the first reproducible baseline for evaluating
  A3/G2 (parallel reindex fan-out, deferred): 1.8 s at 4 008 docs.

## How to reproduce

```powershell
# Solr (doc 10 setup, container reused)
docker start bench-solr

# scaling row (fresh store dir per run, purge core between runs)
java -Xmx2g -jar modules\benchmark\benchmark-solr\target\entrystore-benchmark-solr-6.0-SNAPSHOT.jar `
  -s native -u 2000 -w 4 -B true -R true -S http://localhost:8983/solr/entrystore-core -p <fresh-dir>

# before-side hybrid for the A1 pair
git worktree add ..\entrystore-pre-a1 4b913041
Copy-Item -Recurse modules\benchmark ..\entrystore-pre-a1\modules\benchmark -Force
# build benchmark modules in the worktree, snapshot target\*.jar + libs\ per side, interleave runs
```
