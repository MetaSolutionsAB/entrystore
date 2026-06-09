# EntryStore Benchmark — Native Store Slowdown Investigation

Date: 2026-06-08
Module: `modules/benchmark/benchmark-entrystore`
Branch: `feature/benchmark-ai`

## Empirical results

Workload: 10 000 simple persons (`-u 10000`), modulo=500 sampling (`-m 500`),
2 GB heap (`-Xmx2g`), Java 25, Windows 11 / NTFS.
Each "simple person" creates two entries (person + address) and writes two metadata graphs.

### Native store (`-s native`)

| Sampled insert # | Time |
|---:|---:|
| 0 | 42 ms (JIT cold) |
| 500 | 12 ms |
| 1000 | 17 ms |
| 1500 | 15 ms |
| 2000 | 22 ms |
| 2500 | 21 ms |
| 3000 | 24 ms |
| 3500 | 28 ms |
| 4000 | 30 ms |
| 4500 | 39 ms |
| 5000 | 55 ms |
| 5500 | 469 ms (GC / merge outlier) |
| 6000 | 42 ms |
| 6500 | 44 ms |
| 7000 | 47 ms |
| 7500 | 52 ms |
| 8000 | 55 ms |
| 8500 | 55 ms |
| 9000 | 68 ms |
| 9500 | 60 ms |

Total `Adding to context took`: **460 260 ms** (~46 ms/person averaged).
Reading: 1 937 ms.

Per-insert time grows from ~12–15 ms (warm) to ~60+ ms — a **4–5× slowdown**
over the run.

### Memory store (`-s memory`), same workload

| Sampled insert # | Time |
|---:|---:|
| 0 | 23 ms |
| 500 | 2 ms |
| 1000 | 0 ms |
| 1500 | 1 ms |
| … (all in 0–2 ms range) | |
| 9000 | 1 ms |
| 9500 | 1 ms |

Total `Adding to context took`: **7 604 ms** (~0.76 ms/person).
Reading: 1 001 ms.

**60× faster overall, and no degradation with N.**

That ratio is the smoking gun: the application path is essentially free at
this scale — disk fsync is the wall.

## Root cause: fsync amplification — 4 commits per "simple person"

Each `ObjectMapper.mapObjectToContext(context, fakePerson)` triggers **four
separate RDF4J transactions**, each with its own `getConnection()` +
`rc.begin()` + `rc.commit()` (and therefore one fsync on `NativeStore`):

1. `ContextImpl.createNewMinimalItem(...)` for the **person** entry
   (`core/core-impl/.../impl/ContextImpl.java:431`).
2. `ContextImpl.createNewMinimalItem(...)` for the **address** entry
   (recursive call in `ObjectMapper.mapObjectToContext`,
   `modules/benchmark/.../mapper/ObjectMapper.java:89`).
3. `MetadataImpl.setGraph(...)` for the **address** metadata
   (`core/core-impl/.../impl/MetadataImpl.java:103`).
4. `MetadataImpl.setGraph(...)` for the **person** metadata
   (called in the `finally` block at `ObjectMapper.java:97`).

On Windows/NTFS each fsync is roughly 10 ms, so ≈ 40 ms / person from fsync
alone — matching the observed ~46 ms average.

## Why per-insert time keeps growing on native store

- Native store keeps its B-trees in two indexes (`cspo,spoc` —
  `BenchmarkCommons.INDEXES`). Depth grows with `log N` — minor on its own.
- Larger data files = more dirty pages per commit and slower OS-level fsync
  (the dominant growth factor).
- Every `MetadataImpl.setGraph` runs `removeGraphSynchronized` first, which
  scans `mdContext` and calls `rc.clear(mdContext)` even for brand-new entries
  that have no prior graph.
- Every metadata write calls `EntryImpl.registerEntryModified`
  (`EntryImpl.java:1140`) which does `getStatements` + `remove` + `add` for
  the modified-date — three store operations per write.
- `MetadataImpl.addGraphSynchronized` (`MetadataImpl.java:170`) walks every
  statement and, for any IRI in the repo base, calls
  `ContextManagerImpl.getEntryIgnoreACL(...)` and writes to that entry's
  relation graph — extra reads + writes inside the same transaction,
  multiplying with metadata fan-out.

## Other suspect spots

- `ContextImpl.createNewMinimalItem` lines 437–462: per insert it does
  `rc.getStatements(null, null, null, false, entryUri).stream().toList()` to
  verify the candidate entry URI is free, plus a `getStatements`+`remove`+`add`
  cycle for the context counter. Each traversal scales with `log N`.
- `EntryImpl.registerEntryModified` rebuilds the modified-date triple instead
  of overwriting in place.
- `MetadataImpl.removeGraphSynchronized` runs unconditionally on every
  `setGraph` — wasted work for first-time writes (the common path in bulk
  import).

## Recommendations (ordered by expected impact)

1. **Batch transactions.** Expose a `Context.bulk(Consumer<Context>)` (or
   similar) that opens one connection, calls `rc.begin()`, lets the caller
   run many `createResource`/`setGraph` operations through the same `rc`, and
   commits at the end. This single change should drop the 4 commits per
   person to 1, and a bulk-import caller could do thousands of inserts per
   commit. Memory-store numbers (~0.7 ms/insert) show the application path
   is fast — fsync is the wall.
2. **Skip `removeGraphSynchronized` for empty graphs** — when the entry was
   just created and the metadata context is known empty, bypass the
   read + `rc.clear` cycle.
3. **`forceSync=false` for bulk import.** `NativeStore` can be configured
   with `forceSync=false`. Acceptable trade-off for benchmarks and explicit
   bulk-import modes; must be opt-in because of the data-loss risk on crash.
4. **Re-evaluate `cspo,spoc`.** With the observed access patterns
   (`getStatements` by context, and by S+P+context), `cspo` alone may
   suffice and halves write amplification on every commit.
5. **Add a `SingleTransaction` mode to `benchmark-entrystore`.** The
   pure-RDF4J module already has both modes
   (`modules/benchmark/benchmark-rdf4j/.../SingleTransaction.java`). Mirroring
   that gives a baseline to measure batching gains against.

## How to reproduce

```powershell
$jar = "modules\benchmark\benchmark-entrystore\target\entrystore-benchmark-entrystore-6.0-SNAPSHOT.jar"
$storeDir = "$env:TEMP\entrystore-bench\store-$([guid]::NewGuid())"
New-Item -ItemType Directory -Path $storeDir | Out-Null

# native: ~460 s, per-insert grows 4-5x
java -Xmx2g -jar $jar -s native -u 10000 -m 500 -p $storeDir

# memory: ~7.6 s, flat
java -Xmx2g -jar $jar -s memory -u 10000 -m 500 -p $storeDir
```
