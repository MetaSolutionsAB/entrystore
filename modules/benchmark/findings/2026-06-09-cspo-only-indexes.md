# EntryStore Benchmark — Recommendation #4: cspo-only Indexes

Date: 2026-06-09
Module: `modules/benchmark/benchmark-entrystore` + `benchmark-common`
Branch: `feature/benchmark-ai`

Implements recommendation #4 from
`2026-06-08-native-store-slowdown.md`: re-evaluate whether
`cspo,spoc` (the default `BenchmarkCommons.INDEXES`) is needed, since
fewer indexes means less write amplification per commit on NativeStore
/ LmdbStore.

## Query audit

Grep over `core/core-impl/src/main/java/org/entrystore/impl/` confirms
every hot-path `getStatements(...)` call is **context-scoped** —
i.e. has a non-null final argument:

- `MetadataImpl.getGraph` / `removeGraphSynchronized`:
  `getStatements(null, null, null, false, mdContext)`
- `MetadataImpl.addGraphSynchronized` relation lookups: route through
  `SoftCache`, not the store.
- `ContextImpl.createNewMinimalItem` counter read / candidate-free
  check: `(s, p, null, false, ctx)` and `(null, null, null, false, ctx)`.
- `EntryImpl.load` / `loadFromStatements`:
  `(null, null, null, false, entryURI)`.
- `EntryImpl.registerEntryModified` (post-Rec-3): exact-remove
  `(entryURI, Modified, literal, entryURI)`.

Non-context-scoped queries exist (`null, P, null, false`) in
`ContextImpl.reIndex`, `ContextManagerImpl.getEntry` (alias scan), and
`RepositoryManagerImpl` migration code, but these are one-off
maintenance operations, not benchmark hot-path.

Conclusion: `cspo` alone covers every benchmark query efficiently.
Non-context-scoped queries fall back to a context-list scan, paid
only on reindex / migrate.

## What was added

- New `--indexes` / `-x` CLI option on the benchmark
  (`BenchmarkCommons.processArguments`).
- `Arguments` gained a `String indexes` field; null means use the
  legacy `BenchmarkCommons.INDEXES` default.
- `Benchmark.java` (benchmark-entrystore) passes the override to
  `Settings.STORE_INDEXES` when present.

Same machinery exists in the SAIL config: `Settings.STORE_INDEXES`
is already read by `RepositoryManagerImpl` for the native and LMDB
constructors, so no core change was needed.

## Results (10 000 simple persons, native, batched, 3 fresh runs each)

| Run | `cspo,spoc` (default) | `cspo` only |
|---|---:|---:|
| 1 | 9 210 ms | 8 875 ms |
| 2 | 9 239 ms | 8 405 ms |
| 3 | 9 031 ms | 8 744 ms |
| **avg** | **9 160 ms** | **8 675 ms** |

`cspo` only is **~5% faster** on the write phase. Read time
unchanged (~1 900 ms both — all reads are context-scoped, so the
narrower index handles them just as well).

### Native unbatched (5 000 persons, 2 runs each)

| Run | `cspo,spoc` | `cspo` only |
|---|---:|---:|
| 1 | 151 019 ms | 160 022 ms |
| 2 | 153 786 ms | 151 762 ms |
| **avg** | **152 403 ms** | **155 892 ms** |

Statistically indistinguishable — within run-to-run noise. On
unbatched mode, the per-commit overhead (20 000 commits for 5 000
persons) dwarfs the index work, so halving the index count doesn't
move the needle.

## Interpretation

`cspo` alone is the better default for **write-heavy EntryStore
workloads on NativeStore / LmdbStore**:

- Roughly halves write amplification per commit (one B-tree update
  instead of two).
- No measurable read regression: every hot-path query is
  context-scoped.
- Modest ~5% wall-clock improvement on batched native, indistinguishable
  on unbatched native (commit overhead dominates).

The win is small relative to recommendations #1 and #2 because the
preceding optimisations already collapsed the per-insert work down
to where commit overhead, not index maintenance, is the dominant
cost. With more indexes (e.g., `cspo,spoc,posc`) the relative gap
from dropping to `cspo` would be larger; this experiment only
removes one of two.

## Compatibility caveat

Two callers in `core-impl` issue non-context-scoped predicate-first
queries on the live store (not just during migration):

- `ContextManagerImpl.getEntry` (around line 1085): scans
  `(null, p, null, false)` against the full repository to resolve an
  alias by name. With `cspo` only, this becomes a context list scan
  followed by per-context filtering — O(contexts × triples-per-context).
- `ContextImpl.reIndex`: full repository scan with predicate first.

For a typical deployment these aren't hot paths. For an alias-heavy
workload (lots of `getContext("name")` calls) the `cspo` choice
could be costly. Recommended: keep `cspo,spoc` as the production
default; switch to `cspo` only for bulk-import jobs that know they
won't trigger alias lookups.

## How to reproduce

```powershell
$jar = "modules\benchmark\benchmark-entrystore\target\entrystore-benchmark-entrystore-6.0-SNAPSHOT.jar"

# default cspo,spoc
java -Xmx2g -jar $jar -s native -u 10000 -m 2000 -B true `
    -p "$env:TEMP\entrystore-bench\cspo-spoc"

# cspo only
java -Xmx2g -jar $jar -s native -u 10000 -m 2000 -B true -x cspo `
    -p "$env:TEMP\entrystore-bench\cspo"
```

## What's left from the recommendation list

- #3 — `forceSync=false` on NativeStore for bulk-import mode (opt-in,
  data-loss trade-off). Configuration knob — exposing it through a
  benchmark flag would let us measure the disk-sync ceiling.
