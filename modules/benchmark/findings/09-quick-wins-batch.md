# EntryStore Benchmark — Doc-08 Quick Wins Batch

Date: 2026-07-10
Module: `core/core-impl`, `modules/rest/spring-boot`
Branch: `feature/benchmark-ai`

Implements the "quick wins" batch from findings doc 08's suggested
sequencing. Doc 08 listed nine candidates; three were already fixed on
the branch before this round started (verified by code reading):

| Doc-08 item | Status before this round |
|---|---|
| C1 — unused connection in `loadFromStatements` | already fixed (`03b14c65`) |
| A11 — thread-unsafe `SimpleDateFormat` | already fixed (`DateTimeFormatter`, static final) |
| C5 (guard) — inverted `getFileSize` guard | already fixed (`< 0` sentinel check) |

The remaining six were implemented here, one commit and one benchmark
cycle each.

## Environment note (important for reading the numbers)

Midway through this batch the machine switched from AC to **battery
power**, which cut sustained CPU/disk performance roughly 4× (native
batched 10k insert: ~23 s on AC → ~95 s on battery). Two consequences:

1. Absolute numbers before/after that point are not comparable.
2. From F1 onwards, every measured comparison is a **paired interleaved
   A/B**: the parent-commit jar and the point jar run alternately
   back-to-back in the same envelope, and every run carries a canary
   (the pure-CPU "Generating data took" time) that must match across
   sides for the pair to count.

Baselines (AC, from findings doc 10): NB 23 203 ms, NU 42 052 ms,
MU 7 006 ms, SOLR 50 633 ms insert / 51 635 ms incl. queue drain.

## Item results

### C7 — `updateModificationDate` runs in one explicit transaction (`38e2d47c`)

The method executed remove+add (plus a possible dcterms:contributor add)
on an auto-committing connection — 2–3 commits per call. Now a single
begin/commit following the `setResourceType` caller pattern.

Callers are the REST resource PUT paths (`ResourceController`), which the
write harness does not exercise — the full suite was run as a regression
gate and came back clean (NB avg 20 133 ms, NU 38 073 ms, MU 6 687 ms —
all at or below the AC baseline, i.e., pure noise). `EntryImplTest`
13/13 green. Expected effect: one fewer fsync-able commit per resource
PUT in production, not visible in this harness.

### A6 — unreachable rows-widening branch in the Solr result-fill loop (`542747b0`)

`if (resultFillIteration++ > 0)` post-incremented before the inner
`resultFillIteration == 1` check, so the branch that widens the Solr
rows window from a small limit to 100 was dead code — ACL-filtered
searches with small limits paged through up to 10 sequential Solr
round-trips at the original page size. The counter now increments after
the refill guard; the 10-query cap timing is unchanged (verified by
iteration trace). Search-path fix — not measurable by the write
harness; `SolrSearchIndexTest` 16/16 green, NB regression clean
(21 191 ms).

### A5 — `deleteById` for routine Solr deletes (`52fec8ac`)

Routine entry deletions built a `uri:(a OR b …)` `deleteByQuery`,
although `uri` is the schema `uniqueKey` (verified in
`integration-test/src/test/resources/solr/schema.xml`). DBQ blocks Solr
version buckets and concurrent merges; `deleteById` does not.
Context-wide purges keep DBQ.

The insert-only SOLR workload never deletes, so the benchmark can only
regression-gate this: one SOLR run at 49 610 ms (baseline avg 51 635) —
clean, 4 004 docs indexed. Functional delete-path validation is
deferred to the integration-test batch after the Solr write-path
decoupling lands (docs 12–13).

### A16 — memoized predicate→MD5 hash for Solr field names (`89d9f29e`)

`MessageDigest.getInstance("MD5")` ran per indexed statement for the
`metadata.predicate.*` dynamic-field names. Predicates repeat heavily
and the vocabulary is bounded, so a `ConcurrentHashMap` memo removes the
per-statement provider lookup + digest.

SOLR ×3: 47 667 / 48 784 / 61 450 ms (run 3 caught the AC→battery
switch) vs 50 633 baseline — **no measurable change**; the doc-build
cost is dominated by the ~8–10 metadata-graph loads per document, which
is exactly what findings docs 11–12 attack.

### D8 — RDF/JSON responses without pretty-printing (`84c01049`)

`RDFJSON.graphToRdfJson` returned `obj.toString(2)`; response paths
(`ResourceService`, `GraphUtil`) shipped indented JSON. Now
`toString(0)`, matching EntryService. Payload reduction (doc 08
estimates 10–30%) is reasoned, not measured — the harness does not
cover REST serialization. All 807 spring-boot unit tests green; the
RDF/JSON fixtures in `ValidatorIT`/`LocalEntryIT` are inputs, not output
assertions, so IT behavior is unaffected.

### F1 — precompiled `URISplit` patterns (`0179cf5b`)

`String.matches` recompiled both URI regexes per entry-URI resolution
and `toString()` ran three times per constructor call. `URISplit` sits
on every entry lookup (`ContextManagerImpl.getEntry`, principal-name
resolution, relation handling), including the benchmark's insert path.

First paired interleaved A/B measurement (battery envelope, NB 10k ×3
per side, canaries 407–509 ms on both sides):

| Side | Run 1 | Run 2 | Run 3 | avg |
|---|---:|---:|---:|---:|
| before (`84c01049`) | 106 939 | 94 536 | 92 947 | **98 141 ms** |
| after (F1) | 94 029 | 98 434 | 94 012 | **95 492 ms** |

−2.7%, inside the ±7% run spread → **below the noise floor**. Kept as
allocation hygiene; `URISplitTest` 30/30 green.

## Batch verdict

Four of the six items are correctness or production-path fixes the
write harness structurally cannot see (C7, A6, A5, D8); the two that sit
on measured paths (A16, F1) are individually below the noise floor, as
doc 08's "quick wins" framing predicted ("small, low-risk, individually
measurable" turned out optimistic on the last point — *measurable* here
means "regression-gated", not "shows a delta"). The heavy hitters are
the graph-load and lock-scope findings, next in docs 11–13.

## How to reproduce

```powershell
$jar = "modules\benchmark\benchmark-entrystore\target\entrystore-benchmark-entrystore-6.0-SNAPSHOT.jar"
java -Xmx2g -jar $jar -s native -u 10000 -m 2000 -B true -p "$env:TEMP\entrystore-bench\nb"

$solrJar = "modules\benchmark\benchmark-solr\target\entrystore-benchmark-solr-6.0-SNAPSHOT.jar"
java -Xmx2g -jar $solrJar -s native -u 2000 -m 500 `
    -S http://localhost:8983/solr/entrystore-core -p "$env:TEMP\entrystore-bench\solr"
```

Paired A/B: build the parent commit (`git stash` the point, `mvnw clean
install -pl modules/benchmark/benchmark-entrystore -am`), copy
`target/{*.jar,libs}` aside, restore, rebuild, then alternate runs
before/after ×3 and compare averages plus canaries.
