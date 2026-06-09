# EntryStore Benchmark — SingleTransaction Baseline

Date: 2026-06-08
Module: `modules/benchmark/benchmark-entrystore`
Branch: `feature/benchmark-ai`

Follow-up to `2026-06-08-native-store-slowdown.md`, implementing
recommendation #5 ("Add a `SingleTransaction` mode to `benchmark-entrystore`").

## What was added

- `modules/benchmark/benchmark-entrystore/src/main/java/org/entrystore/SingleTransaction.java`
  — bulk-loads the entire person dataset into the underlying RDF4J repository
  (obtained via `RepositoryManager.getRepository()`) in one transaction,
  bypassing `Context.createResource` and `MetadataImpl.setGraph`.
- `Benchmark.main` now branches on the `-t` flag (already parsed by
  `BenchmarkCommons`): the existing `MultipleTransactions` path runs by
  default, `-t true` selects the new `SingleTransaction` mode.
- `Benchmark.readAllFromRepository` performs a triple-scan read for the
  single-tx mode (no per-entry context indexes exist for that data).

## Results (10 000 simple persons, `-Xmx2g`, Java 25, Windows 11 / NTFS)

| Mode | Store | Insert time |
|---|---|---:|
| MultipleTransactions (EntryStore APIs) | native | **460 260 ms** |
| MultipleTransactions (EntryStore APIs) | memory | **7 604 ms** |
| SingleTransaction (raw RDF4J via RM) | native | **3 003 ms** |
| SingleTransaction (raw RDF4J via RM) | memory | **317 ms** |

Reading (10 000):

| Mode | Store | Read time |
|---|---|---:|
| MultipleTransactions | native | 1 937 ms |
| MultipleTransactions | memory | 1 001 ms |
| SingleTransaction | native | 778 ms |
| SingleTransaction | memory | 28 ms |

## What this is — and is not — comparing

These two modes do **different amounts of work**, so the absolute ratio is
not a clean speedup. Per simple person:

| | MultipleTransactions (EntryStore) | SingleTransaction (raw RDF4J) |
|---|---|---|
| Entry-info triples (entry/res/md/relation URIs, created, modified, creator) | ~10 per entry × 2 entries | 0 |
| Context-index triples (`resHasEntry`, `counter`) | ~2 per entry × 2 entries | 0 |
| Metadata triples | ~5 (in entry's mdContext) | ~9 (in a named graph) |
| Inverse-relation writes (`HAS_ADDRESS` back to address) | yes | no |
| `SoftCache` bookkeeping | yes | no |
| ACL / `registerEntryModified` reads + writes | yes | no |
| Commits | **4** | **1 total** for the whole run |

So the 460 s → 3 s gap conflates two distinct effects:

1. **Less work**: the single-tx mode skips entry / index / relation /
   modified-date machinery entirely. That work is *useful* — any honest
   batching design for EntryStore would still need to do it.
2. **Fewer commits**: 4 commits per person × 10 000 = 40 000 fsyncs on
   native vs. 1 fsync at the end. At ~10 ms / fsync on NTFS that's
   ~400 s of the 460 s gap on native.

The fsync-amplification component (effect 2) is the avoidable cost a
future `Context.bulk(...)` API (recommendation #1) can capture. The
entry-info / index / relation triples (effect 1) cannot be skipped without
breaking EntryStore semantics, and writing them still costs B-tree work
in whatever transaction structure ends up being used.

## What the numbers do establish

- **Memory single-tx (317 ms)** is the floor for *this specific workload
  if EntryStore did nothing*: the raw RDF4J cost of putting 10 000
  persons' worth of metadata triples into a `MemoryStore` in one batch.
  No amount of EntryStore-level work can be faster than that.
- **Memory multi-tx (7 604 ms) vs native multi-tx (460 260 ms)**
  is the apples-to-apples measurement of *EntryStore APIs, same code
  path*, swapping the storage backend. That 60× gap on otherwise-identical
  workloads is the fsync amplification — and it is the figure to target
  with batching.
- **Native single-tx (3 003 ms)** is informational: it shows that on this
  machine, with this store, persisting "roughly enough triples for 10 000
  persons" to disk in one transaction is in the low single-digit seconds.
  The actual EntryStore batching target will be higher than that because
  it must also write the entry-info / index / relation triples that the
  raw mode skips.

## Caveat

The `SingleTransaction` mode does **not** create EntryStore entries — no
context index, no entry / metadata / resource URI triples, no inverse
relations. It is not a functional replacement for the multi-tx mode; it
is intentionally bypassing EntryStore to provide an order-of-magnitude
reference point for the store underneath. Treat the single-tx number as a
loose lower bound on what bulk loading can ever achieve on this machine,
not as a "what EntryStore could do if it batched."

## How to reproduce

```powershell
$jar = "modules\benchmark\benchmark-entrystore\target\entrystore-benchmark-entrystore-6.0-SNAPSHOT.jar"

# Multi-transaction (current behaviour, default):
java -Xmx2g -jar $jar -s native -u 10000 -m 500 -p "$env:TEMP\entrystore-bench\multi-native"
java -Xmx2g -jar $jar -s memory -u 10000 -m 500 -p "$env:TEMP\entrystore-bench\multi-memory"

# Single-transaction (new baseline mode):
java -Xmx2g -jar $jar -s native -u 10000 -t true -p "$env:TEMP\entrystore-bench\single-native"
java -Xmx2g -jar $jar -s memory -u 10000 -t true -p "$env:TEMP\entrystore-bench\single-memory"
```

Note: the existing CLI inverts `-t`. `-t` absent or `-t false` runs
`MultipleTransactions`; `-t true` runs `SingleTransaction`. Convention
preserved from `benchmark-rdf4j`.
