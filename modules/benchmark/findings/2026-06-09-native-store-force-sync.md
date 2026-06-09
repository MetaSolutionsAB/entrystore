# EntryStore Benchmark — Recommendation #3: NativeStore `forceSync` Toggle

Date: 2026-06-09
Module: `core/core-impl`, `modules/benchmark/{benchmark-common,benchmark-entrystore}`
Branch: `feature/benchmark-ai`

Implements recommendation #3 from
`2026-06-08-native-store-slowdown.md`: expose `NativeStore.forceSync`
so bulk-import / benchmark modes can be explicit about the durability
/ throughput trade-off.

## Why this is the smallest recommendation

`NativeStore.forceSync` defaults to **false** in RDF4J. The benchmark
was already getting "no fsync per commit" out of the box — the
original findings doc overstated the fsync cost on Windows / NTFS.
This recommendation therefore boils down to:

1. Expose the setting so a deployment can opt **in** to fsync for
   durability when needed (e.g., production write paths).
2. Measure what the on-position actually costs, to size the
   trade-off honestly.

## What was added

### `core/core-impl`

- New config setting:
  `Settings.STORE_FORCE_SYNC = "entrystore.repository.store.force-sync"`.
- `RepositoryManagerImpl` reads it after constructing the `NativeStore`
  and calls `store.setForceSync(...)` only when the property is
  present, preserving the legacy "don't touch the default" behaviour
  when no override is supplied. The chosen value is logged.

### Benchmark

- New `-f`/`--force-sync` CLI flag on benchmark-common.
- `Arguments` gained a `Boolean forceSync` field (boxed — null means
  "use NativeStore default").
- `Benchmark.java` (benchmark-entrystore) passes the value through to
  `Settings.STORE_FORCE_SYNC` when set.

## Results

### Native batched (10 000 persons, 3 fresh runs each)

| forceSync | Run 1 | Run 2 | Run 3 | avg |
|---|---:|---:|---:|---:|
| default (off) | 9 169 | 9 310 | 9 073 | **9 184 ms** |
| `-f true` | 11 621 | 11 659 | 11 220 | **11 500 ms** |

~**25% slower** with fsync forced. Only one commit happens for the
whole batch, but the single fsync of ~50 MB of newly written index
pages is non-trivial (~2.3 s on this NTFS volume). Read times
unchanged.

### Native unbatched (2 000 persons, 2 fresh runs each)

| forceSync | Run 1 | Run 2 | avg |
|---|---:|---:|---:|
| default (off) | 40 534 | 42 009 | **41 272 ms** |
| `-f true` | 95 766 | 95 535 | **95 651 ms** |

~**2.3× slower**. With 4 commits per person × 2 000 persons = ~8 000
commits, each paying its own fsync (~6.7 ms on average from the
delta), the cost stacks linearly with the number of commits. This is
exactly what the original `2026-06-08-native-store-slowdown.md` doc
predicted, but only realised when fsync is explicitly forced — not
during the unbatched default-run that the original investigation
benchmarked. The dramatic native-vs-memory gap that started this
investigation was *not* explained by fsync amplification at all; it
was per-commit OS-write pressure + the EntryStore-API overhead, both
of which Rec 1 + Rec 2 already addressed.

## Implication

- For **bulk-import jobs and benchmarks**: leave the default (off) on.
  The single-commit penalty in batched mode is small; the per-commit
  penalty in unbatched mode is large.
- For **production-style writes that must survive a crash**: opt **in**
  via `entrystore.repository.store.force-sync=true`. Expect:
  - Roughly 1.25× slower under heavy batching.
  - Roughly 2× slower under per-operation commits.
- The default — don't set the property at all — leaves NativeStore's
  own default in place. The new code path is dormant unless someone
  asks for it.

This makes the previously-implicit "default off" assumption
explicit and measurable; it does not change behaviour for any
existing caller.

## How to reproduce

```powershell
$jar = "modules\benchmark\benchmark-entrystore\target\entrystore-benchmark-entrystore-6.0-SNAPSHOT.jar"

# Default (fsync off — fast, less durable):
java -Xmx2g -jar $jar -s native -u 10000 -m 2000 -B true `
    -p "$env:TEMP\entrystore-bench\fsync-off"

# Forced fsync (slower, durable):
java -Xmx2g -jar $jar -s native -u 10000 -m 2000 -B true -f true `
    -p "$env:TEMP\entrystore-bench\fsync-on"
```

## Recommendation list status

All five original recommendations from
`2026-06-08-native-store-slowdown.md` are now implemented or
measured:

| # | Recommendation | Commit | Headline result |
|---|---|---|---|
| 1 | Batch transactions (`inBatch`) | `5965f5e1` | 6× on native batched |
| 2 | Skip empty-graph `removeGraphSynchronized` | `184baeeb` | 10× more on native batched, 63× on memory batched (fixed the regression) |
| 3 | `forceSync` toggle | this commit | Measured: 25% off→on penalty batched, 2.3× unbatched |
| 4 | `cspo`-only indexes | `d68a1996` | ~5% on native batched |
| 5 | `SingleTransaction` baseline mode | `2ac2a90d` | Lower-bound reference point |

Carry-over from Rec 2's "next-largest" follow-up:
`registerEntryModified` overwrite-in-place (commit `34b06385`) — ~5–7% across all batched modes.

Combined effect against the original unbatched baseline (~460 s for
10 000 native persons): **~9 s with all optimisations on, ~49× faster**.
