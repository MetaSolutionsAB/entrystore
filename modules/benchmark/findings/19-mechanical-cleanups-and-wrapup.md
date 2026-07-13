# EntryStore Investigation — Mechanical Cleanups (F-theme) + Wrap-up

Date: 2026-07-13
Module: `core/core-impl`
Branch: `feature/benchmark-ai`

Closes out the doc-08 ("Fable investigation") implementation round: the
Theme F mechanical items, and a full disposition of **every** finding ID
with its outcome.

## F-theme — implemented

- **F1** (findings doc 09): precompiled `URISplit` regexes + reused URI
  string.
- **F4** (commit `f7803f8c`): `NS.expand` now does a single map `get`
  instead of `containsKey` + `get`.
- **F5** (commit `f7803f8c`): `Password.containsSymbol`'s regex is a
  precompiled `static final Pattern` instead of `Pattern.compile` per
  call.

## F-theme — recorded (not changed)

These are genuine micro-observations from doc 08 but sit below the
benchmark noise floor and/or on cold paths; the risk/churn is not
justified:

- **F2 — config-read caching.** Hot config reads (content-disposition,
  proxy whitelist) go through `SynchronizedConfiguration`'s single mutex.
  Real, but the fix touches shared config access broadly; most hot
  services on the Spring Boot side already read config once via `@Value`
  at construction, so the remaining callers are few. Left as-is; migrate
  opportunistically when touching those services (per CLAUDE.md guidance).
- **F3 — eager log-string building in per-entry loops.** Worth using
  slf4j placeholders, but the per-entry loops that mattered
  (`SolrSearchIndex` doc build, `EntryUtil` traversal) are dominated by
  I/O; the string building is negligible against that. Recorded.
- **F6 — buffered streams on zip export / marker writes.** Small I/O
  hygiene on the export path (not benchmarked). Recorded.
- **F7 — `TokenCache` redundant `synchronized` on a ConcurrentHashMap.**
  The class is cold today (base class invites future reuse). Dropping the
  locks is safe but zero-value now. Recorded.

## Virtual threads (Theme G) — dispositions

- **G2** implemented conceptually via Phase 3: reindex no longer builds
  documents on the reindex thread (they build on the submitter). The
  bounded-VT fan-out itself is deferred (see doc 13).
- **G1** (`spring.threads.virtual.enabled`): not flipped — doc 08 requires
  a load test first, and the per-request buffering fixes (D6/D9) it
  depends on are not all in. Documented as an ops decision, not a code
  change.
- **G5** deferred (doc 16 — cosmetic, existing executor hardened).
- **G3, G4, G6, G7, G8**: "no change" / "forward-looking only" in doc 08;
  recorded, unchanged.

## Full disposition of every finding

Legend: **Done** = implemented this round (or already on the branch);
**Deferred** = real, but risk/scope/measurability warranted a separate
change (rationale in the referenced doc); **Recorded** = intentionally
left as-is.

| ID | Outcome | Where |
|----|---------|-------|
| A1 | Done | doc 12 |
| A2 | Done | doc 11 |
| A3 | Deferred | doc 13 |
| A4 | Deferred | doc 15 |
| A5 | Done | doc 09 |
| A6 | Done | doc 09 |
| A7 | Done | doc 13 |
| A8 | Done | doc 13 |
| A9 | Done | doc 12 |
| A10 | Done | doc 12 |
| A11 | Already fixed on branch | doc 09 |
| A12 | Done | doc 12 |
| A13 | Done | doc 12 |
| A14 | Obsolete after A1 | doc 12 (A1 coalesces the per-child builds) |
| A15 | Done | doc 12 |
| A16 | Done | doc 09 |
| A17 | Done | doc 15 |
| B1 | Done (per-decision memo; cross-request cache deferred) | doc 14 |
| B2 | Deferred | doc 14 |
| B3 | Done | doc 14 |
| B4 | Deferred | doc 16 |
| B5 | Done | doc 14 |
| B6 | Not needed after B1/B3 (getGraph no longer the hotspot) | doc 14 |
| C1 | Already fixed on branch (`03b14c65`) | doc 09 |
| C2 | Deferred | doc 17 |
| C3 | Deferred | doc 17 |
| C4 | Deferred | doc 17 |
| C5 | Done (guard already fixed; combined setter added) | docs 09, 17 |
| C6 | Deferred | doc 17 |
| C7 | Done | doc 09 |
| C8 | Deferred | doc 17 |
| C9 | Deferred (with B2) | doc 14 |
| C10 | Deferred | doc 17 |
| C11 | Recorded (rare admin op) | doc 17 |
| C12 | Deferred (with C3) | doc 17 |
| D1 | Deferred (ETag contract) | doc 15 |
| D2 | Done | doc 15 |
| D3 | Partial (compact JSON done; per-hit loads deferred) | doc 15 |
| D4 | Done | doc 15 |
| D5 | Done | doc 15 |
| D6 | Deferred (size cap already present) | doc 16 |
| D7 | Recorded (writer-factory; low value) | this doc |
| D8 | Done | doc 09 |
| D9 | Deferred (streaming upload API change) | this doc |
| D10 | Done | doc 16 |
| D11 | Deferred (serializer rewrite) | this doc |
| D12 | Done | doc 15 |
| D13 | Deferred (visited-set semantics) | doc 15 |
| D14 | Done (correctness) | doc 15 |
| D15 | Recorded (low impact) | this doc |
| D16 | Recorded (already gated by `logging.http.enabled`) | doc 15 |
| E1 | Deferred | doc 18 |
| E2 | Deferred | doc 18 |
| E3 | Deferred | doc 18 |
| E4 | Deferred | doc 18 |
| E5 | Deferred | doc 18 |
| E6 | Deferred (latent module) | doc 18 |
| E7 | Recorded (one-off migration) | doc 18 |
| E8 | Deferred (async API design) | doc 18 |
| E9 | Done | doc 18 |
| F1 | Done | doc 09 |
| F2 | Recorded | this doc |
| F3 | Recorded | this doc |
| F4 | Done | this doc |
| F5 | Done | this doc |
| F6 | Recorded | this doc |
| F7 | Recorded | this doc |
| G1 | Recorded (needs load test) | this doc |
| G2 | Partial via Phase 3 | docs 12, 13 |
| G3–G8 | Recorded / deferred per doc 08 | this doc |

Additional recorded items (D7/D9/D11/D15) — each a larger rewrite
(streaming RDF serialization, streaming uploads, custom JsonSerializer)
or low-impact, and none measurable by the write/read benchmark; left for
focused follow-ups.

## What moved the needle (measured)

Across the whole round, the write/read benchmark could only *measure*
the core write/read path (context-create + `setGraph` + entry reads).
On that harness:

- **A2** (one metadata-graph load per Solr document) — **~7.7% faster**
  Solr-on insert at 2 000 persons (doc 11), non-overlapping distributions.
- **B1/B3/B5** (authorization caching) — **~15% faster** group-authorized
  reads (doc 14), understated by the benchmark's tiny principals context;
  scales with principal/group count in production.
- **A1/A9/A10/A12/A13/A15** (Solr write-path decoupling) — flat within
  noise on a *single-writer* benchmark (doc 12), because the win is
  concurrency/heap/latency that a single writer cannot exercise; verified
  correct by 759 ITs.
- **A5/A6/A7/A8/A16/C5/C7/D-theme/F-theme** — below the noise floor
  individually, or on paths the benchmark does not exercise (search,
  lists, uploads, deletes); verified by unit + integration tests.

The honest headline: the biggest *measured* wins were **A2** and the
**authorization caching (B1)**; the Solr lock-scope decoupling (A1) is a
correctness-preserving concurrency/heap improvement whose throughput
benefit needs a concurrent-writer benchmark (recommended future work:
`--writers N` and a reindex-timing phase for the harness, noted in docs
12–13).

## Test status

- Core unit tests: green across every touched class.
- Integration tests: **759 tests, 0 failures** on the final branch state
  (re-run after each core/REST-affecting phase).
- Benchmark harness: `benchmark-entrystore` (native/memory) and
  `benchmark-solr` (external Solr 10 via Docker, see doc 10) both build
  and run clean at the branch tip.
