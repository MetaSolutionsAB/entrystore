# EntryStore Investigation — REST Read-Path Batch 1 (D-theme)

Date: 2026-07-13
Module: `core/core-impl`, `modules/rest/spring-boot`
Branch: `feature/benchmark-ai`

Implements the tractable, low-risk items of Theme D (REST layer) from
findings doc 08, plus the search-offset cap (A17).

## Measurement note

These are **REST request-path** changes. The `benchmark-entrystore` /
`benchmark-solr` harness exercises the core write/read path, not HTTP
endpoints, so it cannot A/B these. The gate here is the **integration
test suite** (759 tests, Testcontainers Solr + Keycloak + the Spring Boot
app) plus complexity/allocation reasoning. Where a doc-08 estimate was
"X× fewer loads", that is a structural claim verified by reading the code
and confirmed to preserve behaviour by the ITs — not a measured
wall-clock delta. Claims are kept to what is demonstrable.

## Implemented

### D14 — SPARQL search dedup (correctness + O(n²)→O(n))

`ContextManagerImpl.searchMetadataQuery`/`searchEntryQuery` deduped hits
with `List.contains` (O(n²)). Worse, `searchMetadataQuery` tested a
`List<Entry>` against a `URI` (`entryURIs.contains(entryURI)`), which can
never match — so the URI-level dedup was dead code and **duplicate
entries leaked** into SPARQL search results. Both methods now dedup by
entry URI through a `HashSet` (O(1)), and null entries are no longer
added. This is primarily a **correctness fix**; the O(n²)→O(n) is a
bonus. (No IT covered SPARQL search dedup specifically; the change is
covered by `SparqlIT` for overall behaviour.)

### D12 — title-sort decorate-sort-undecorate

`EntryUtil.sortAfterTitle`'s comparator called `getTitle(entry)` — a
metadata-graph load plus ACL check — on **every comparison**, i.e.
O(N log N) graph loads to sort N children. It now precomputes each
title once into an `IdentityHashMap` (O(N) loads) and the comparator is a
pure string compare. For a 500-child list this drops from ~4 500 title
lookups to 500.

### D2 — paginate list children before loading

`ResourceJsonSerializer.serializeResourceList` loaded **every** child
entry (`context.get(id)`) before applying offset/limit. Now:
- the `allUnsorted` id list is derived from the child URIs by substring
  (no entry load);
- when **not** sorting, only the requested page of non-null children is
  loaded (skip the first `offset` successfully-loaded children, stop once
  `limit` are collected) — the tail beyond the page is never loaded;
- when sorting (still capped at <501 children), all are loaded, sorted
  (via the faster D12 path), then the page is sliced.

Pagination still operates over successfully-loaded children (matching the
prior semantics), and `offset+limit` uses long arithmetic to avoid int
overflow on a hostile offset. Default limit is unchanged (0 = unlimited)
for API compatibility.

### A17 — cap search offset

`SearchController` validated `offset >= 0` but had no upper bound, so a
client could force Solr to deep-page across millions of documents on a
public endpoint. Offsets above `entrystore.solr.max-offset` (default
10 000) now return 400.

### D3 (partial) — compact search JSON

`SearchService.generateJson` returned `result.toString(2)` (pretty). Now
`toString(0)`, as in EntryService/RDFJSON. The per-hit graph reloads
(entry graph + cached-external + local metadata + relations + `getRights`
per Solr hit) are **not** eliminated in this pass — serving those fields
from the Solr documents instead would change the response shape and needs
its own equivalence work; recorded as follow-up.

### D4 — derive context-entry ids from URIs

`ContextService.getContextEntries` loaded every entry via
`getByEntryURI` only to call `getId()`. The id is the last URI segment
(the deleted-entries branch already derives it that way), so it now maps
the URIs directly — no entry loads for a plain context-entry listing.

### D5 — apply feed size before metadata extraction

`SyndicationService.createFeedFromEntries` extracted title, description
and creator name for each entry and only then checked the feed-size limit
(and off-by-one: it emitted `limit+1` items). The size check now runs at
the top of the loop, so no metadata is loaded for entries beyond the feed
size, and the feed contains exactly `limit` items.

## Deferred (with rationale)

- **D1 — conditional GET / 304.** The metadata ETag is contractually a
  representation-independent timestamp: `LocalMetadataResourceIT` asserts
  `etag ==~ /"\d+"/` (quoted digits only). But the metadata
  representation varies by `Accept`/`format`, `graphQuery`, `depth`,
  `recursive`, `scope` and `revision`. Honouring `If-None-Match` against a
  timestamp-only ETag would risk returning **304 for a different
  representation** than the client holds. Doing D1 safely requires first
  making the ETag representation-aware — a contract change the ITs
  currently pin — so it is deferred as its own change rather than bolted
  onto a weak validator.
- **A4 — Solr principal `fq` pre-filter.** Building an `fq` from the
  caller's principals is security-sensitive (a wrong filter changes which
  results a user can see). It must be proven to exactly match the
  application-level ACL check on a seeded ACL matrix before the app check
  can be treated as a backstop. Deferred to a focused, test-heavy change.
- **D3 per-hit loads** and **D13** (metadata-traversal visited set keyed
  by (entry, level) — may be intentional for depth-correct expansion):
  both need output-equivalence proof; deferred.
- **D16 — request/response logging.** Already gated behind
  `logging.http.enabled` (`@ConditionalOnProperty`, default on); operators
  can disable it in production today. Left as-is to avoid surprising
  deployments that rely on the access log.

## Correctness

Full IT suite: **759 tests, 0 failures** (`ResourceIT` for list
pagination/sort, `ContextIT` for entry listing, `SearchIT`/`SparqlIT` for
search, `ResourceSyndicationIT` for feeds, `LocalMetadataResourceIT` for
ETag headers). Core unit tests for the touched core code
(`EntryUtilTest`, `ContextManagerImplTest`, `EntryImplTest`) green.

## How to reproduce (behaviour)

REST-path timing needs a running instance; these are verified via the IT
suite rather than the write/read benchmark. Run:
`./mvnw clean verify -pl modules/rest/integration-test`.
