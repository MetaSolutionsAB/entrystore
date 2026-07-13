# EntryStore Benchmark — Solr Indexing Caches (A7/A8) + A3/G2 Decision

Date: 2026-07-13
Module: `core/core-impl`
Branch: `feature/benchmark-ai`
Commit: `c950f2cd`

Covers findings **A7**, **A8**, and the **A3/G2** (parallel reindex)
decision from findings doc 08.

## A7 — per-batch projectType cache

`constructSolrInputDocument` derived each document's `projectType` by
reading the **surrounding context entry's graph** — once per indexed
entry. Every entry in a context shares that one context graph, so a
context with N entries did N identical context-graph reads.

`resolveProjectType` now consults a per-batch
`Map<contextResourceURI, projectType>`. Because a submit batch (≤100
entries) is heavily context-clustered — and a full reindex processes a
context's entries consecutively — this collapses the repeated reads to
roughly one per context per batch. An empty-string sentinel caches the
"context has no projectType" answer too, and Context-type entries (whose
projectType lives in their own graph, not shared) bypass the cache.

## A8 — cached related-context set

When the global related index is enabled
(`entrystore.solr.related=...,global`), `addRelatedFields` enumerated
**all contexts** for every indexed document. The `RegularContext` set is
now cached in a `volatile` field and invalidated when a Context entry is
posted or removed (`invalidateRelatedContextCacheIfNeeded`). This path is
config-gated and off by default (and in the ITs), so it is exercised by
code review + the invalidation hook rather than a benchmark.

## Measurement

Paired interleaved A/B, `benchmark-solr`, native, 2 000 persons (4 000
documents, all in one context), external Solr 10, purged between runs.
`before` = Phase-3 commit; `after` = this commit. Metric: insert + queue
drain. Canaries clean (209–249 ms) and tight throughout.

| Round | after | before |
|---|---:|---:|
| 1 | 45 852 | 43 672 |
| 2 | 45 668 | 47 183 |
| 3 | 52 572 | 49 099 |
| 4 | 44 051 | 42 054 |
| **avg** | **47 036** | **45 502** |

Rounds split 2–2; the spread (before 42–49 k, after 44–52 k) swamps the
1.5 k difference → **within noise, no measurable delta**.

**Addendum (2026-07-13) — AC re-run: flat confirmed.** Under stable AC
conditions (same snapshots, interleaved, order-alternated, canaries
228–255 ms): before avg 43 494 ms, after avg 43 268 ms → **−0.5%, within
noise** — same verdict as above, now free of the envelope caveat.

Why A7 doesn't move the needle *here*: the benchmark uses a **single
context** whose entry graph is tiny (a handful of context-metadata
triples) and stays SoftCache-hot, so each avoided read costs microseconds
against a ~10 ms/entry RDF4J write floor. A7 eliminates ~99% of the
context-graph reads during indexing (4 000 → ~40), but that only becomes
a wall-clock win when the context graph is large or there are many
contexts — neither of which this workload has. The optimization is
correct and free; its benefit is workload-dependent and simply invisible
at this scale, consistent with the A16 micro-cache result (doc 09). The
one Solr-doc-build change that *did* clear the noise floor was A2 (doc
11), because it removed 8–10 full graph *materialisations* (connection +
copy + ACL check each), not just repeated reads of one small cached
graph.

Correctness: full IT suite **759 tests, 0 failures** (SearchIT 47,
IndexResourceIT, LookupIT, SolrManagementIT), plus SolrSearchIndexTest
16/16.

## A3 / G2 — parallel reindex fan-out: deferred

Finding A3 ("reindex executor is single-threaded, serial per doc at full
A1/A2 per-doc cost") was **partially addressed by Phase 3**: reindex's
`postContextEntriesToQueue` now enqueues URIs and the submitter builds
the documents, so the per-document build no longer runs inline on the
reindex thread — the original complaint.

The remaining step — fanning document *building* across bounded virtual
threads (G2) — is **deliberately not implemented in this round**:

1. **Risk.** It parallelises the indexing hot path, which carries the
   auth ThreadLocal, RDF4J connection handling and Solr client
   concurrency. A defect there risks index corruption or leaked auth
   context. That warrants its own focused change with dedicated tests.
2. **Unmeasurable here.** Demonstrating a reindex-throughput win needs a
   reindex-timing harness and a large multi-context corpus; the current
   single-context 4 000-entry benchmark on a variable-clock laptop cannot
   isolate the effect.
3. **Sequencing.** It should land together with a concurrent-writer
   benchmark mode (`--writers N`, noted in doc 12) that can actually
   quantify both A1's lock-scope win and A3's fan-out.

Recommendation: implement A3/G2 as a standalone change once the harness
grows a reindex-timing phase and a concurrent-writer mode. The
infrastructure it needs (admin-auth save/set/restore per task) already
exists in `reindexSync`.

## How to reproduce

```powershell
# Docker Solr: see findings doc 10.
$jar = "modules\benchmark\benchmark-solr\target\entrystore-benchmark-solr-6.0-SNAPSHOT.jar"
java -Xmx2g -jar $jar -s native -u 2000 -m 500 `
    -S http://localhost:8983/solr/entrystore-core -p "$env:TEMP\entrystore-bench\solr-p4"
```
