# EntryStore Benchmark — Recommendation #1: Batched Transaction API

Date: 2026-06-08
Module: `core/core-impl`, `modules/benchmark/benchmark-entrystore`
Branch: `feature/benchmark-ai`

Implements the headline recommendation from
`2026-06-08-native-store-slowdown.md`: an opt-in batch transaction API
that lets a caller collapse the four commits per `createResource +
setGraph` chain into one.

## What was added

### Core (`core/core-impl`)

- `RepositoryManagerImpl.inBatch(Runnable work)` — opens one
  `RepositoryConnection`, calls `begin()`, runs the work, and commits at
  the end (or rolls back on throw). A `ThreadLocal<RepositoryConnection>`
  publishes the connection so nested EntryStore operations can reuse it.
  Reentrant on the same thread.
- `RepositoryManagerImpl.getActiveBatchConnection()` — package-private
  hook for batch-aware code to discover the ambient connection.
- `ContextImpl.createNewMinimalItem(...)` — refactored: the body extracted
  into `doCreateNewMinimalItem(rc, manageTx, ...)`. When an ambient batch
  connection is present, the helper reuses it and skips `begin()` /
  `commit()`. Outside a batch, behaviour is unchanged.
- `MetadataImpl.setGraph(...)` — same pattern. Body extracted into
  `doSetGraph(rc, graph, manageTx)`.

### Benchmark (`modules/benchmark/benchmark-entrystore`)

- New `MultipleTransactionsBatched` — same code path as `MultipleTransactions`,
  but the entire `persons.forEach(...)` block runs inside a single
  `repositoryManager.inBatch(...)`.
- New `-B`/`--batched` CLI flag (parsed in `BenchmarkCommons`,
  stored as `Arguments.batched`).
- `Benchmark.main` branches on `arguments.isBatched()` to select the
  batched variant.

## Results (10 000 simple persons, `-Xmx2g`, Java 25, Windows 11 / NTFS)

Back-to-back runs on the same machine state, fresh store directory each run:

| Store | Mode | Insert time | Read | vs. its unbatched baseline |
|---|---|---:|---:|---|
| native | unbatched (`MultipleTransactions`) | **600 296 ms** | 1 919 ms | 1× |
| native | batched (`MultipleTransactionsBatched`) | **99 015 ms** | 2 219 ms | **6.1× faster** |
| memory | unbatched (`MultipleTransactions`) | **15 089 ms** | 2 301 ms | 1× |
| memory | batched (`MultipleTransactionsBatched`) | **277 089 ms** | 2 381 ms | **18× slower** |

### Native: 6× speedup ✓

This is the headline win and lands close to recommendation #1's
prediction. The 10 000 × 4 = 40 000 individual commits collapse into one,
so every per-commit cost (`SailRepositoryConnection.begin/commit`, the
internal SAIL transaction bookkeeping, the OS write pressure for the
NativeStore index files) is paid once. Even with `forceSync=false`
defaulting in NativeStore, the per-commit overhead was meaningful.

The 99 s figure is *not* further reducible by batching alone — that floor
is the actual work of writing ~30 triples per person across the index
files plus the EntryStore per-entry abstractions (counter read/update,
context-index `resHasEntry` triple, inverse-relation writes, modified-
date triple, `SoftCache.put`).

### Memory: 18× regression ✗ (unexpected)

This was the surprise. Profiling-by-inspection points at MemoryStore's
snapshot isolation:

- Every `rc.getStatements(...)` inside an open transaction has to filter
  candidate statements against the per-statement *tx visibility* metadata
  (added in this tx? removed? since which snapshot?).
- The two reads inside `createNewMinimalItem` —
  `rc.getStatements(null, null, null, false, candidateEntryUri)` (free
  slot check) and `rc.getStatements(this.resourceURI, ...counter..., this.resourceURI)`
  (counter read) — therefore pay a cost that grows with the number of
  uncommitted statements in the transaction.
- Across 10 000 persons in one batch, ~30 triples per person ≈ 300 000
  uncommitted statements by the end. The per-read scan cost is roughly
  linear in that count, making the total batch cost roughly
  *O(N²)* in person count.
- NativeStore doesn't have this problem because its B-tree indexes are
  consulted regardless of transaction state; per-read cost stays
  *O(log N)* whether or not there are uncommitted writes.

So the batching trade-off is **store-dependent**: native benefits
substantially from larger batches; memory store penalises them.

## Per-insert sampling (modulo=500, native)

Unbatched baseline shows the gradual ~5× growth that motivated the
original investigation. Batched run is much faster overall but per-batch
samples lose meaning (samples sit inside a long-running outer tx). For
production sizing on native, use ~10 000 person batches when feasible.

## Recommended follow-ups

1. **Document the trade-off.** Any `Context.bulk(...)`-style API exposed
   to callers should note that on `MemoryStore` (typical in tests),
   large batches are slower, not faster. Tests should not assume the
   same constants as production native runs.
2. **Tunable batch size.** A future iteration of
   `MultipleTransactionsBatched` could accept a batch-size N and call
   `inBatch` every N persons — a sweet spot probably exists around
   100–1000 persons per batch where MemoryStore's per-read scan cost
   stays bounded and NativeStore still amortises commit overhead well.
3. **Expose `inBatch` on the `RepositoryManager` interface**, not just
   the impl, so application code (REST controllers doing bulk imports)
   can take advantage without casting.
4. **Investigate further wins inside one batch** — even in batched mode
   on native, ~10 ms per person is the floor. Likely candidates:
   `MetadataImpl.removeGraphSynchronized` running unconditionally for
   first-time writes; `EntryImpl.registerEntryModified` doing
   read+remove+add for the modified-date.

## How to reproduce

```powershell
$jar = "modules\benchmark\benchmark-entrystore\target\entrystore-benchmark-entrystore-6.0-SNAPSHOT.jar"

# Native unbatched (current baseline)
java -Xmx2g -jar $jar -s native -u 10000 -m 2000 `
    -p "$env:TEMP\entrystore-bench\native-unbatched"

# Native batched (whole loop in one inBatch — 6× faster)
java -Xmx2g -jar $jar -s native -u 10000 -m 2000 -B true `
    -p "$env:TEMP\entrystore-bench\native-batched"

# Memory unbatched
java -Xmx2g -jar $jar -s memory -u 10000 -m 2000 `
    -p "$env:TEMP\entrystore-bench\memory-unbatched"

# Memory batched (18× slower — do not use for memory store)
java -Xmx2g -jar $jar -s memory -u 10000 -m 2000 -B true `
    -p "$env:TEMP\entrystore-bench\memory-batched"
```

## Caveats

- `inBatch` rollback discards every change made inside the batch but
  does **not** undo `SoftCache.put` calls or repository events that
  fired during the work runnable. Treat the cache as potentially stale
  after a failed batch and refresh caller-visible state if needed.
- The synchronized block in `inBatch` (`synchronized(repository)`) is
  held for the entire batch duration. For multi-threaded callers,
  that serialises all other writes against the same repository. Choose
  batch size accordingly.
