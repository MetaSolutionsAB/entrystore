# EntryStore Benchmark — A2: One Metadata Graph Load per Solr Document

Date: 2026-07-13
Module: `core/core-impl`
Branch: `feature/benchmark-ai`
Commit: `49324e13`

Implements finding **A2** from findings doc 08.

## The problem

`SolrSearchIndex.constructSolrInputDocument` fetched the metadata graph
once into `mdGraph`, but then called `EntryUtil` helpers that each
re-fetched it:

- `getTitles(entry)`, `getFirstName(entry)`, `getLastName(entry)`,
  `getEmail(entry)`, `getDescriptions(entry)`, `getTagLiterals(entry)`,
  `getTagResources(entry)` — each calling `entry.getMetadataGraph()`
  internally,
- plus `addRelatedFields` calling `EntryUtil.getResourceValues(entry, …)`
  (another fetch).

`Entry.getMetadataGraph()` opens a `RepositoryConnection`, runs an ACL
check, and copies the full named graph into a new `Model` every call —
so a single document build did **~8–10 full metadata-graph
materialisations** where one would do.

## The fix

Added `Model`+`URI`-accepting overloads to `EntryUtil` for each helper
(`getTitles`, `getFirstName`, `getLastName`, `getEmail`,
`getDescriptions`, `getTagLiterals`, `getTagResources`, and a
`getLiteralValues(Model, URI, List)` core). The predicate lists were
extracted into private helpers so the `Entry` and `Model` variants share
them and cannot drift. The `Entry`-accepting methods now delegate to the
`Model` overloads (fetch once, then call), so every existing external
caller keeps working unchanged.

`constructSolrInputDocument` passes the `mdGraph` it already holds to all
of them, and `addRelatedFields` takes `mdGraph`+`resourceURI` for its
`getResourceValues` call. Net: **one** `getMetadataGraph()` per document
instead of ~8–10.

## Results

`benchmark-solr`, native store, 2 000 simple persons (= 4 000 indexed
documents), `-Xmx2g`, external Solr 10 (Docker), Solr core purged before
each run. Paired **interleaved A/B**: the parent-commit jar (`before`)
and the A2 jar (`after`) built as separate fat-jar snapshots and run
alternately. Machine was on battery throughout (reduced clock — absolute
numbers run higher than the doc-10 AC baseline, but the paired
comparison is unaffected).

Runs are filtered by the data-generation **canary** (`Generating data
took`): any run whose canary exceeded ~350 ms (background interference)
or was the JIT-cold first round is excluded, leaving three clean runs
per side.

| Side | Run | Canary | Insert | Insert + drain |
|---|---|---:|---:|---:|
| before | a | 309 | 60 846 | 61 848 |
| before | b | 305 | 61 617 | 62 618 |
| before | c | 273 | 61 560 | 62 562 |
| **before avg** | | | **61 341** | **62 343** |
| after | a | 343 | 56 996 | 57 998 |
| after | b | 344 | 55 185 | 56 188 |
| after | c | 314 | 57 715 | 58 716 |
| **after avg** | | | **56 632** | **57 634** |

**~7.7% faster** on the insert phase (61 341 → 56 632 ms). The two
distributions do not overlap (before 60.8–61.6 k, after 55.2–57.7 k),
and the direction held in reversed-order rounds (A2 running first still
won), so this is a real effect rather than ordering bias.

Excluded runs, for the record: `before-1` (cold, canary 1 362 ms,
129 732 ms), `after-1` (cold, 115 725 ms), `before-2` (canary clean but
elevated at 74 041 ms — single background spike), `after-4` (canary
786 ms).

## Interpretation

The saving (~4.7 s over 4 000 documents ≈ **~1.2 ms/document**) is the
cost of the 7–9 eliminated graph materialisations per document at this
scale. It scales with metadata-graph size and document count, and it is
paid on **every** write (each `setGraph` fires an index event), not only
during reindex — so the real-world win on entries with larger metadata
graphs than the benchmark's ~6-triple persons is proportionally bigger.

This is a strict subset of the work finding **A1** removes (building the
document off the writer thread entirely); A2 lands first because it is
low-risk and independently valuable, and it reduces the payload A1 later
moves off the lock.

## How to reproduce

```powershell
# See findings doc 10 for the Docker Solr setup.
$jar = "modules\benchmark\benchmark-solr\target\entrystore-benchmark-solr-6.0-SNAPSHOT.jar"
curl.exe -s -X POST "http://localhost:8983/solr/entrystore-core/update?commit=true" `
  -H "Content-Type: text/xml" --data "<delete><query>*:*</query></delete>"
java -Xmx2g -jar $jar -s native -u 2000 -m 500 `
    -S http://localhost:8983/solr/entrystore-core -p "$env:TEMP\entrystore-bench\solr-a2"
```
