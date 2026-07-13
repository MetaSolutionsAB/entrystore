# EntryStore Benchmark — Deep Summary & Must-Have Assessment (Docs 08–19)

Date: 2026-07-13
Branch: `feature/benchmark-ai` (commits `1991a688`..`a4d5e06f`, 28 commits)
Scope: retrospective across the whole doc-08 implementation round

This document compares the wins of every improvement implemented in docs
09–19, charts which changes were most beneficial, and classifies each
shipped change as **MUST HAVE** or **NICE TO HAVE** for merging/production.

---

## 1. How to read the numbers

Two facts constrain every comparison in this round:

1. **The machine's performance envelope changed mid-round** (AC → battery
   → variable clock; sustained CPU dropped ~4× at times: native batched
   10k insert ranged 23 s on AC to ~95 s on battery for identical code).
   **Absolute numbers are therefore never compared across phases.** Every
   claim below comes from a **paired interleaved A/B**: parent-commit jar
   vs point jar built as separate snapshots, run alternately in the same
   envelope, with a pure-CPU canary ("Generating data took") that had to
   match across sides for a round to count. Cold first rounds and
   inflated-canary rounds were discarded.
2. **The harness measures one path**: core context-create + `setGraph`
   (+ Solr indexing in `benchmark-solr`) and entry reads. REST endpoints,
   list operations, uploads, deletes, backups and maintenance ops are not
   on it. For those, the gate was the **759-test integration suite** and
   the impact assessment is structural (complexity/IO counting), which is
   stated explicitly wherever used.

## 2. Win comparison — every improvement, one table

"Measured Δ" is the paired A/B result where one exists. "Structural
effect" is the counted reduction in work (I/O, transactions, locks) for
items the harness cannot time.

| Doc | Change | Path it affects | Measured Δ (paired A/B) | Structural effect | Risk |
|----|--------|-----------------|--------------------------|-------------------|------|
| 09 | C7 — transactional `updateModificationDate` | REST resource PUT | not on harness path (regression clean) | 2–3 auto-commits → 1 per PUT | none |
| 09 | A6 — fix dead rows-widening branch | ACL-filtered search | n/a (correctness) | intended refill behaviour now executes; up to 10 sequential round-trips avoided | none |
| 09 | A5 — `deleteById` for routine deletes | Solr deletes | n/a (delete path not in workload) | no DBQ version-bucket blocking during concurrent adds/merges | none |
| 09 | A16 — predicate-hash memo | Solr doc build | **0%** (within noise: 47.7/48.8 vs 50.6 baseline) | 1 MessageDigest lookup per statement removed | none |
| 09 | D8 — compact RDF/JSON | REST serialization | not measurable by harness | 10–30% smaller payloads (reasoned, doc 08) | none |
| 09 | F1 — precompiled `URISplit` | entry-URI resolution | **−2.7%** (98 141 → 95 492; within ±7% spread → noise) | regex compile per call removed | none |
| 11 | **A2 — one metadata-graph load per Solr doc** | every Solr-indexed write | **−7.7%** (61 341 → 56 632 ms; non-overlapping distributions; held in reversed order) | 8–10 graph materialisations → 1 per document | low |
| 12 | A1/A9 — build docs off writer locks, URI queue | concurrent writes; reindex heap | **flat** on single-writer bench (by design — see §3) | doc build leaves `synchronized(repository)`→`listeners`→`postQueue` chain; queue holds URIs not built docs (heap bounded) | med, gated by 759 ITs |
| 12 | A10 — non-throwing guest probe | Solr doc build | (inside A1 bundle) | 1 exception-as-control-flow per non-public entry removed | low |
| 12 | A12 — configurable commitWithin (1 s → 5 s) | Solr commit churn | needs long steady-load test | ~5× fewer Solr commits under steady write load | low (config) |
| 12 | A13 — wait/notify submitter | indexing latency | not visible in saturated bulk loop | up to 500 ms enqueue-to-index latency removed; idle wakeups 2/s → 0 | low |
| 12 | A15 — context purge off request thread | context DELETE | not on harness path | synchronous Solr DBQ round-trip leaves the request thread | low |
| 13 | A7 — per-batch projectType cache | Solr doc build | **0%** (45 502 vs 47 036; rounds split 2–2) | context-graph reads per batch: ~100 → ~1 per context (win scales with context size/count; benchmark's single tiny context is cache-hot) | none |
| 13 | A8 — cached related-context set | global `solr.related` mode | config-gated, off in bench/ITs | all-context enumeration per document → cached set | none |
| 14 | **B1 — per-decision group memo** | every group-based authorization | **−15.0%** on group-authorized reads (1 224 → 1 041 ms; 16.6%/13.1% in the two clean rounds) | up to ~12 full principals-context scans per decision → ≤1 | low (decision-scoped, no invalidation needed) |
| 14 | B3 — ACL sets parsed at load | first auth check per entry | (inside B1 bundle) | up to 6 lazy ACL queries per entry → 0 | low |
| 14 | B5 — cached `isDisabled` | authenticated requests | (inside B1 bundle) | 1 repo read per authenticated request removed | none |
| 15 | D14 — SPARQL search dedup | SPARQL search results | n/a (correctness) | **duplicate results no longer leak**; O(n²) → O(n) | none |
| 15 | D12 — decorate-sort-undecorate titles | sorted list GET | not on harness path | 500-child sort: ~4 500 title lookups (graph load + ACL each) → 500 | none |
| 15 | D2 — paginate before loading children | list GET | not on harness path | page of k from an N-child list: N entry loads → ~k | low |
| 15 | A17 — cap search offset | public search endpoint | n/a (DoS guard) | unbounded Solr deep paging → 400 above 10 000 | none |
| 15 | D3 (partial) — compact search JSON | search response | not measurable by harness | pretty-print CPU + payload inflation removed | none |
| 15 | D4 — ids from URIs in context listing | context entry listing | not on harness path | N entry loads → 0 for a plain listing | none |
| 15 | D5 — feed cut before metadata extraction | syndication feeds | not on harness path | metadata loads for entries beyond feed size → 0; feed no longer emits limit+1 | none |
| 16 | D10 — no double PBKDF2 on login | every login attempt | not on harness path | 2 PBKDF2 (~20 ms CPU) per attempt → 0; credential-stuffing amplifier removed | none |
| 17 | C5 — combined file-metadata setter | upload finalization | not on harness path | 3 transactions + 3 events + 3 modified-writes → 1 each | low |
| 18 | E9 — `@DisallowConcurrentExecution` on backups | backup scheduling | n/a (availability) | overlapping backups no longer stack write-lockouts (503 windows) | none |
| 19 | F4/F5 — NS lookup, symbol pattern | micro | below noise floor | hygiene | none |

## 3. Chart — which improvement was most beneficial

### 3a. Measured wall-clock wins (paired A/B, same-envelope, honest)

Only items on the harness path can appear here. Bars are percent
improvement on the metric each change targets.

```
                                            0%        5%        10%       15%
                                            |         |         |         |
B1/B3/B5  auth caching (group-auth reads)   ██████████████████████████████  ~15.0%  REAL
A2        1 graph load per Solr doc (insert)████████████████                ~7.7%   REAL
F1        URISplit precompile (insert)      █████▌                          ~2.7%   noise
A16       predicate-hash memo (insert)      ·                                0%     noise
A7/A8     indexing caches (insert)          ·                                0%     noise
P3 bundle Solr lock decoupling (insert)     ·                                0%*    single-writer
F4/F5     mechanical micro                  ·                                0%     noise

* flat BY DESIGN on this harness: the P3 bundle's benefit is lock-scope for
  CONCURRENT writers, bounded reindex heap (A9), and enqueue latency (A13) —
  none of which a single-threaded insert loop can exhibit. See §3b.
```

Two changes cleared the noise floor, and both are exactly the two doc-08
predicted as the top read-path and per-write levers: **B1 (authorization
group resolution)** and **A2 (metadata-graph loads in the Solr doc
build)**.

Both measured percentages **understate production impact**:

- **B1** was measured against a principals context of ~7 principals and
  3 groups. `getGroupUris` is O(principals × group members) per scan, and
  the change collapses up to ~12 scans per decision to ≤1. In a
  deployment with thousands of users the avoided work grows by orders of
  magnitude while the measured saving here (~0.02 ms/read) reflects the
  tiny test directory. Direction and mechanism are confirmed; magnitude
  scales with directory size.
- **A2** was measured on ~6-triple metadata graphs. The saving is
  proportional to graph size × writes; production entries with rich
  metadata pay proportionally more per avoided materialisation.

### 3b. Structural/unmeasurable impact ranking (reasoned, IT-gated)

For paths the harness cannot time, ranked by how much work the change
removes on its own path, worst-case first:

```
rank  change   worst-case work removed on its path
----  -------  ------------------------------------------------------------------
 1    D2       list GET: N child loads → page size k     (N unbounded, user-facing)
 2    A1/A9    reindex heap: O(repo) built docs → O(queue) URIs; doc build off
               the global write monitor (all concurrent writers unblock)
 3    D12      sorted list GET: ~9 000 repo reads → ~500  (bounded by the 500 cap)
 4    D14      SPARQL search: duplicate results eliminated (correctness, not speed)
 5    D10      login: 2×PBKDF2 (~20 ms CPU) per attempt → 0 (also DoS-relevant)
 6    A17      public search: unbounded deep paging → bounded (DoS guard)
 7    E9       backups: stacked 503 write-lockout windows → single window
 8    D4/D5    context listing / feeds: N entry+metadata loads → 0 / page-bounded
 9    C5/C7    uploads / resource PUT: 3 tx → 1, 2-3 commits → 1
10    A13/A15  indexing latency (≤500 ms) / context-delete request latency
11    D8/D3    payload size (10-30%) + serialization CPU
12    A5/A12   Solr server internals (version buckets, commit churn)
13    A6       ACL-filtered search refill finally works as intended
14    A16/F1/  micro-hygiene, below noise
      F4/F5
```

## 4. MUST HAVE vs NICE TO HAVE

Classification criteria:

- **MUST HAVE** — at least one of: (a) fixes wrong behaviour visible to
  users (correctness), (b) closes a security/availability exposure,
  (c) removes *unbounded* resource use on a user-facing or write path,
  (d) has a measured, reproducible win on a hot path at low risk.
- **NICE TO HAVE** — bounded constant-factor improvements, hygiene,
  config-gated or rare-path items; keep them (they are merged, tested and
  free), but none would justify holding a release.

### MUST HAVE (10)

| Change | Commit | Why it is a must |
|--------|--------|------------------|
| **D14** SPARQL dedup fix | `43447622` | Correctness: duplicate entries leaked into SPARQL search results (the dedup compared `Entry` objects to a URI list — never matched). Users see wrong results without it. |
| **A6** dead widening branch | `542747b0` | Correctness: the search result-fill windowing that the code *documents* never executed; ACL-filtered small-limit searches silently paid up to 10 sequential Solr round-trips. |
| **B1/B3/B5** auth caching | `844be061` | Measured **~15%** on group-authorized reads at toy directory size; mechanism (≤1 principals scan per decision instead of ~12) scales with user count. Zero staleness risk (decision-scoped memo). Read-path lever of the whole round. |
| **A2** one graph load per Solr doc | `49324e13` | Measured **~7.7%** on every Solr-indexed write; non-overlapping distributions, order-robust. Paid on *every* write, not just reindex. Low risk (delegating overloads). |
| **A1/A9/A13/A15** Solr write-path decoupling | `203154ed` | (a) A9 removes an **OOM class**: reindexing a large repo previously pinned every fully-built `SolrInputDocument` in an unbounded queue; (b) A1 takes the doc build (10+ repo reads + ACL eval) out of the global `synchronized(repository)` chain every concurrent writer serializes behind — the doc-08 #1 finding; (c) ≤500 ms indexing latency removed (A13). Flat on the single-writer bench *by design*; gated by 759 ITs. |
| **D2** paginate before loading list children | `43447622` | Removes unbounded work from a user-facing endpoint: a page of 20 from a 10 000-child list loaded 10 000 entries before; now ~20. |
| **A17** search offset cap | `43447622` | DoS guard on a public endpoint (unbounded Solr deep paging). |
| **D10** login double-PBKDF2 removal | `8f8e5e3e` | Security-relevant: an attacker could force 2 deliberately-slow PBKDF2 computations per guess *before* authentication — a free CPU amplifier for credential stuffing. Also ~20 ms saved per legitimate login. |
| **E9** no overlapping backups | `cb02e3cc` | Availability: overlapping backup fires stacked repeated global write-lockouts (503 for all writes). One annotation, no logic change. |
| **D12** title-sort decorate-sort-undecorate | `43447622` | ~9× fewer repo reads on sorted list GETs (~4 500 graph-load+ACL pairs → 500 for the 500-child cap); user-visible latency on a common EntryScape operation, zero-risk change. |

### NICE TO HAVE (12)

| Change | Commit | Why merely nice |
|--------|--------|-----------------|
| C7 transactional `updateModificationDate` | `38e2d47c` | Real (2–3 commits → 1 per resource PUT) but a small constant factor on one endpoint. |
| C5 combined file-metadata setter | `03f73487` | Same shape: 3 tx → 1 per upload finalization. |
| A5 `deleteById` | `52fec8ac` | Benefits Solr internals under concurrent delete+add load; invisible otherwise. |
| A10 non-throwing guest probe | `203154ed` (bundle) | Removes exception-as-control-flow per non-public doc; cleanliness + minor CPU. |
| A12 commitWithin 1 s → 5 s | `203154ed` (bundle) | Fewer commits/segment churn under steady load; needs a long-running test to quantify; configurable either way. |
| A7/A8 indexing caches | `c950f2cd` | Measured flat here; value is workload-dependent (many/large contexts, `related=global`). Correct and free. |
| D3 compact search JSON + D8 compact RDF/JSON | `43447622`, `84c01049` | 10–30% payload + serialization CPU; bandwidth win, not server-load win. |
| D4 ids from URIs | `43447622` | Admin-ish endpoint; N loads → 0 but N is context-sized and the endpoint is not hot. |
| D5 feed cut before extraction | `43447622` | Feeds are cold paths; also fixed the limit+1 off-by-one. |
| A16 predicate-hash memo | `89d9f29e` | Measured 0%; hygiene. |
| F1/F4/F5 mechanical | `0179cf5b`, `f7803f8c` | Measured/reasoned below noise; hygiene. |
| Harness work: `-S` solr-url, `-a` fix, `-r` read-as-user | `1991a688`, `844be061` | Not production code — but note the `-a` fix repaired a CLI option that never worked, and without `-S`/`-r` neither A-theme nor B-theme could have been measured at all. Must-have for *future benchmarking*, N/A for production. |

### Borderline call, made explicit

- **A1 bundle as MUST HAVE despite a flat benchmark**: the honest
  benchmark result (doc 12) is "no measurable single-writer delta". It is
  classified must-have on the strength of (a) the A9 unbounded-heap fix,
  which is an OOM waiting for a large reindex, and (b) the lock-scope
  argument doc 08 rated the single largest normal-operation write lever —
  with the explicit caveat that the concurrency win remains **unproven
  until a concurrent-writer benchmark exists**. Anyone re-assessing this
  should weigh that caveat; the 759-IT gate covers correctness, not the
  throughput claim.
- **D12 as MUST vs NICE**: bounded by the existing 500-child sort cap, so
  it cannot run away — but ~4 500 graph loads per sorted-list request is
  a user-visible stall on a common operation, and the fix is risk-free.
  Kept in MUST; demoting it to NICE would be defensible.

## 5. What to do next (deferred items ranked)

If a follow-up round is planned, the deferred items in likely
value-per-risk order:

1. **Concurrent-writer + reindex-timing harness modes** (`--writers N`;
   reindex phase) — prerequisite to *prove* A1's throughput claim and to
   evaluate A3/G2 (parallel reindex). Everything Solr-side is blocked on
   measurement without it.
2. **Cross-request user→groups cache** (full B1) — would push the ~15%
   further; needs listener-based invalidation on membership change,
   group/user delete. Security-sensitive; single-instance deployment
   makes local invalidation feasible.
3. **E1–E4 maintenance-op isolation** — removes worst-case multi-minute
   write stalls (export/import/reIndex under the global monitor, backup
   lockout scope). High operational value, needs its own test rig.
4. **D1 conditional GET (304)** — requires making the ETag
   representation-aware first (current contract: timestamp-only, pinned
   by ITs). High polling-traffic win once the contract change is agreed.
5. **A4 principal-`fq` Solr pre-filter** — kills the per-hit ACL
   post-filtering; must be proven equivalent to the app-level check on a
   seeded ACL matrix (security).
6. **C2/C3/C12** list rewrite + batch-honouring setters — pairs with the
   doc-03 batch API production wiring.
7. **B4** UserDetails TTL cache; **D6** pooled proxy client; **G1**
   virtual threads on the request path (after load test + D9 buffering).

## 6. Bottom line

- **28 commits, 12 findings docs, 759/759 ITs green** at the tip.
- The round confirmed doc 08's two headline predictions with real
  measurements: **authorization group resolution (B1, ~15%)** and
  **Solr doc-build graph loads (A2, ~7.7%)** were the biggest provable
  wins — and both scale up with deployment size beyond what the toy
  benchmark can show.
- Four shipped changes fix actual wrong behaviour (D14 duplicates, A6
  dead branch, D5 off-by-one, plus the `-a` CLI fix), and three close
  security/availability exposures (D10, A17, E9). Those, plus the two
  measured wins and the unbounded-work removals (D2, A9), are the ten
  changes worth insisting on in any merge discussion. Everything else is
  earned, tested marginal gain.
