# EntryStore Benchmark — Solr Write-Path Decoupling (A1/A9/A10/A12/A13/A15)

Date: 2026-07-13
Module: `core/core-api`, `core/core-impl`
Branch: `feature/benchmark-ai`
Commit: `203154ed`

Implements the Solr write-path decoupling batch from findings doc 08.

## What changed

- **A1 / A9** — `postEntry` now enqueues only the **entry URI**; the
  submitter thread builds the `SolrInputDocument` in
  `drainAndBuildPostBatch`, off the
  `synchronized(repository) → synchronized(repositoryListeners) →
  synchronized(postQueue)` chain that `postEntry` runs under (via
  `MetadataImpl.setGraph → fireRepositoryEvent`). The post queue is a
  `LinkedHashSet<URI>` instead of an unbounded cache of fully-built
  documents, so a large reindex no longer pins millions of
  `SolrInputDocument`s on the heap. The reindex path
  (`postContextEntriesToQueue`) enqueues URIs the same way.
- **A10** — the guest-readability probe uses a new **non-throwing**
  `PrincipalManager.isUserAuthorized(userURI, entry, prop)`, extracted so
  it and `checkAuthenticatedUserAuthorized` share one decision path
  (admin, admin-group, context-ACL inheritance, read/write implications
  all preserved), instead of catching an `AuthorizationException` as
  control flow for every non-public entry.
- **A12** — `commitWithin` is configurable
  (`entrystore.solr.commit-within`, `-max`) and the default raised
  1000 → 5000 ms to cut commit/segment churn under steady write load.
- **A13** — the submitter blocks on a `queueSignal` wait/notify with an
  idle heartbeat instead of `Thread.sleep(500)` polling (mirrors
  `PublicRepository`), removing up to 500 ms of latency per idle→work
  transition and the 2 wakeups/second idle spin.
- **A15** — context-removal purge runs on the background `purgeExecutor`
  instead of the caller's request thread.

## Correctness

The full integration-test suite — **759 tests, 0 failures** — passes
with these changes, including `SearchIT` (47), `LookupIT`,
`IndexResourceIT`, `SolrManagementIT` (11) and the auth/login ITs that
exercise `checkAuthenticatedUserAuthorized` (now delegating to the
extracted `isUserAuthorized`). Core unit tests: 60 across
`SolrSearchIndexTest`, `PrincipalManagerImplTest`, `GroupImplTest`,
`ContextManagerImplTest`, `EntryUtilTest`.

ITs pin `entrystore.solr.commit-within=1000` (in `entrystore-it.properties`)
so search results stay visible quickly for the assertions; production
keeps the new 5 s default.

## Measurement — and why it is flat here

Paired interleaved A/B, `benchmark-solr`, native store, 2 000 simple
persons (4 000 documents), external Solr 10, core purged between runs.
`before` = parent commit (A2 applied, this batch not); `after` = this
commit. Metric: insert **+ Solr queue drain** wall-clock. The machine's
clock changed partway through the session (rounds 1–3 ran ~54 k, rounds
4–5 ~42 k), so **only within-round paired comparison is valid** — cross
-round averaging would mix machine states.

| Round | after | before | Δ (before − after) |
|---|---:|---:|---:|
| 1 | 53 150 | 61 447 | +8 297 |
| 2 | 55 468 | 50 201 | −5 267 |
| 3 | 55 089 | 55 177 | +88 |
| 5 | 41 372 | 43 415 | +2 043 |

(Round 4's `after` run was discarded: the **benchmark harness** threw an
NPE in `ObjectMapper.mapObjectToContext` — a pre-existing race in the
benchmark's own ACL/principal setup during startup indexing, unrelated
to these changes.)

`after` wins three of four rounds, but round 2 reverses and the spread
swamps the mean → **flat within noise at this scale**.

This is the expected result, not a disappointment: **the benchmark is
single-writer**. A1's win is that *concurrent* writers no longer
serialise behind full document construction held under three nested
global locks. With one insert thread there is no lock contention to
relieve — the same CPU work simply moves from the writer thread to the
submitter thread, and the `insert → drain` gap stays ~1 s because the
submitter keeps pace. The throughput lever A1 pulls only appears under
concurrent write load, which this harness does not generate.

What the single-writer run *does* confirm: the restructure adds no
wall-clock regression, the queue drains promptly, and end-to-end
indexing correctness is intact (759 ITs).

## Addendum (2026-07-13) — AC re-run: flat confirmed

Re-run under stable AC conditions (same snapshots, interleaved,
order-alternated, canaries 218–246 ms): before avg 42 634 ms, after avg
43 835 ms — rounds mixed (+907 / +3 733 / −1 037) with spread larger than
the delta, i.e. **flat within noise**, matching the original verdict.
The single-writer harness cannot exhibit this change's benefit either on
battery or on AC; the value remains the lock-scope, heap-bounding and
latency arguments below.

## Value that the benchmark cannot show

- **A9 (bounded heap)** — the queue holds URIs, not built documents; a
  million-entry reindex no longer risks pinning a million
  `SolrInputDocument`s. Structural, not timed.
- **A1 (concurrency)** — needs a concurrent-writer benchmark mode
  (future work: add `--writers N` to the harness) to quantify; the lock
  scope reduction is visible in code review and the passing ITs.
- **A13 (latency)** — removes up to 500 ms per idle→work transition;
  matters for interactive single writes, invisible in a saturated bulk
  loop.
- **A12 (commit churn)** — fewer Solr commits under sustained load;
  needs a long-running steady-write test to observe segment counts.

## How to reproduce

```powershell
# Docker Solr setup: see findings doc 10.
$jar = "modules\benchmark\benchmark-solr\target\entrystore-benchmark-solr-6.0-SNAPSHOT.jar"
curl.exe -s -X POST "http://localhost:8983/solr/entrystore-core/update?commit=true" `
  -H "Content-Type: text/xml" --data "<delete><query>*:*</query></delete>"
java -Xmx2g -jar $jar -s native -u 2000 -m 500 `
    -S http://localhost:8983/solr/entrystore-core -p "$env:TEMP\entrystore-bench\solr-p3"
```
