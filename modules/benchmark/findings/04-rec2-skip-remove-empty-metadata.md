# EntryStore Benchmark — Recommendation #2: Skip `removeGraphSynchronized` for Empty Graphs

Date: 2026-06-08
Module: `core/core-impl`
Branch: `feature/benchmark-ai`

Follow-up to `2026-06-08-batched-transaction-api.md`. The MemoryStore
regression introduced by recommendation #1 (whole-loop batching went
from 15 s to 277 s) pointed straight at `MetadataImpl.removeGraphSynchronized`
running unconditionally on every fresh entry. Recommendation #2
short-circuits that read/clear path when the metadata graph is known
empty.

## What was added

### `core/core-impl/src/main/java/org/entrystore/impl/MetadataImpl.java`

- New `volatile boolean knownEmpty` field. False by default — safe for
  the loaded-from-repo case where the mdContext may already hold
  triples.
- New package-private `markKnownEmpty()` hook to flip the flag.
- `removeGraphSynchronized(rc)` returns an empty `LinkedHashModel`
  immediately when `knownEmpty` is true, skipping the
  `rc.getStatements(null, null, null, false, mdContext)` scan, the
  inverse-relation removal loop, and the `rc.clear(mdContext)` call.
- `doSetGraph` refreshes `knownEmpty = graph.isEmpty()` after
  `addGraphSynchronized`, so the optimisation also applies if a setter
  later writes an empty graph.

### `core/core-impl/src/main/java/org/entrystore/impl/EntryImpl.java`

- `create(...)` (the only path that constructs a fresh, empty
  mdContext) now calls `markKnownEmpty()` on the `localMetadata` and on
  the `cachedExternalMetadata` when it is a real `MetadataImpl` (not a
  `LocalMetadataWrapper`, which delegates elsewhere).
- The load / `setGraphRaw` paths are intentionally untouched —
  there, the mdContext can already hold data and the original code
  path remains correct.

## Results (10 000 simple persons, `-Xmx2g`, Java 25, back-to-back same-machine runs)

| Store | Mode | Rec 1 only | Rec 1 + Rec 2 | Δ |
|---|---|---:|---:|---|
| memory | unbatched | 15 089 ms | **6 633 ms** | 2.3× faster |
| memory | batched | 277 089 ms | **4 394 ms** | **63× faster** |
| native | unbatched | 600 296 ms | **487 891 ms** | 1.2× faster |
| native | batched | 99 015 ms | **9 304 ms** | **10.6× faster** |

### vs. the original unbatched baseline (`2026-06-08-native-store-slowdown.md`)

| Store | Mode | Original | Rec 1 + Rec 2 | Δ |
|---|---|---:|---:|---|
| native | unbatched | 460 260 ms | 487 891 ms | (same order; native unbatched not the optimisation target) |
| native | batched | n/a | **9 304 ms** | **~49× faster than baseline** |
| memory | unbatched | 7 604 ms | 6 633 ms | 1.15× faster |
| memory | batched | n/a | **4 394 ms** | 1.7× faster than the pre-batching memory baseline |

## Why the two-recommendation combo is so much better than either alone

Recommendation #1 alone made one read inside `createNewMinimalItem`
(the free-slot check) noticeably more expensive on MemoryStore when
it ran inside a long-held transaction, because MemoryStore's snapshot
isolation filters every statement against the per-statement tx
metadata.

The real cost-multiplier, though, was `removeGraphSynchronized` running
on every fresh entry — that one ran a `null,null,null` statement
iteration scoped only by `mdContext`, which on MemoryStore is an
unindexed scan over all statements plus a context filter. With 10 000
persons × ~30 triples each, that scan grew the worse the further into
the batch we got — turning the whole batch into roughly O(N²).

Skipping that scan when the context is known empty:

- Eliminates the dominant per-insert cost on memory store with
  batching (drops from 27.7 ms to 0.44 ms per person).
- Removes a wasted read on every other path too: even unbatched
  inserts and native inserts pay no scan for fresh entries any more.
- Makes the recommendation #1 win on native much bigger: 99 s → 9.3 s
  (since the long-held tx no longer accumulates the wasted scan work).

## Caveats

- `knownEmpty` is local to the `MetadataImpl` instance. If application
  code bypasses `MetadataImpl` and writes directly to the underlying
  `RepositoryConnection` for the same `mdContext`, the flag will be
  stale and `removeGraphSynchronized` may incorrectly skip work. The
  internal API contract is that all metadata writes go through
  `MetadataImpl.setGraph` / `addGraphSynchronized`.
- The flag is `volatile` but not synchronised against concurrent
  `setGraph` calls — that ordering is the caller's responsibility, and
  in practice `setGraph` already takes the per-repository monitor.
- For `LocalMetadataWrapper` (used when a Reference's cached external
  metadata delegates to another local entry), the flag is not set on
  the wrapper. That entry's own `MetadataImpl` retains its real state.

## How to reproduce

```powershell
$jar = "modules\benchmark\benchmark-entrystore\target\entrystore-benchmark-entrystore-6.0-SNAPSHOT.jar"

foreach ($store in @("memory","native")) {
  foreach ($mode in @("unbatched","batched")) {
    $extra = if ($mode -eq "batched") { @("-B","true") } else { @() }
    $dir = "$env:TEMP\entrystore-bench\$store-$mode"
    java -Xmx2g -jar $jar -s $store -u 10000 -m 2000 -p $dir @extra
  }
}
```

## Recommended follow-ups

- Recommendation #4 (`registerEntryModified` rebuilds the modified-date
  triple via read + remove + add — overwrite in place) is now the
  next-largest piece of avoidable work in the per-insert path.
- A microbenchmark of the empty-context `getStatements + clear` cost on
  MemoryStore vs LMDB would help size further optimisation work; the
  numbers above suggest LMDB might already win without batching.
