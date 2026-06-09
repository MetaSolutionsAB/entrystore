# Fable Investigation — Codebase-Wide Optimization Survey

Date: 2026-06-09
Scope: entire codebase (core, Solr integration, REST layer, background jobs)
Branch: `feature/benchmark-ai`

## Relationship to previous findings (01–07)

Docs 01–07 covered the **bulk-import RDF4J write path** (transaction batching,
empty-metadata scan, modified-date overwrite, triple indexes, forceSync).
This survey covers **everything else**: the Solr indexing pipeline, the
authorization/read path, the REST layer, list/resource mutation, and
background/maintenance jobs. Production profile assumed throughout:
single instance, NativeStore + external Solr.

## Method

Six parallel subsystem audits (RDF4J write path, RDF4J read/auth path, Solr
indexing/search, Spring Boot REST hot paths, mechanical Java anti-pattern
sweep, background jobs/harvesting/backup), findings deduplicated and ranked.
Items marked **✓ verified** were confirmed by direct code reading after the
audit; items where two or three audits independently converged are marked
**(×2)**/**(×3)**. Everything here is *static analysis* — no profiling was
done. Measure before optimizing; the benchmark harness from docs 01–07 can
be extended for the write-path items.

---

## Executive summary — the six big themes

1. **Solr document construction runs synchronously under three global locks
   on every write.** ✓ verified (×3). `MetadataImpl.setGraph` holds
   `synchronized(repository)`, calls `fireRepositoryEvent` which holds
   `synchronized(repositoryListeners)` and dispatches listeners synchronously
   (`RepositoryManagerImpl.java:609` — with a 15-year-old comment admitting
   the doubt), and `SolrSearchIndex.postEntry` then builds the **full**
   `SolrInputDocument` inside `synchronized(postQueue)`
   (`SolrSearchIndex.java:1274`). One doc build does 10+ RDF4J reads. Every
   writer in the JVM serializes behind this. Likely the single largest
   untapped win for normal (non-bulk) write throughput.

2. **ACL group resolution scans the entire principals context per check.**
   ✓ verified (×3). `PrincipalManagerImpl.getGroupUris` (line 239) iterates
   *all* principal entries, loads each one, and calls `isMember` per group —
   per call. `hasAccess` calls it up to twice, and one
   `checkAuthenticatedUserAuthorized` can invoke `hasAccess` 4–6 times:
   up to ~8–12 full scans per authorization decision. Authorization runs per
   entry per request — and per search hit, per list child. A memoized
   user→groups map (invalidated on group mutation) would collapse this.

3. **The same RDF graph is loaded from the store many times per operation.**
   (×2 each). One Solr doc build re-materializes the entry's metadata graph
   ~8–10× via `EntryUtil.getTitles/getFirstName/...` each calling
   `getMetadataGraph()` (fresh connection + full copy each time, and
   Model-accepting overloads already exist). `loadFromStatements` parses the
   full entry graph but discards the ACL principal sets, which are then
   lazily re-fetched with up to 6 extra queries. Several getters
   (`isDisabled`, `getHomeContext` null case, quota fill level) re-query
   per call due to missing or broken caching.

4. **N+1 / load-everything-before-paginating in the REST layer.** (×2).
   List serialization loads *all* children before applying offset/limit
   (`ResourceJsonSerializer.serializeResourceList`); search hits trigger ~5
   RDF4J loads each after Solr already returned the data; sorting loads
   metadata graphs *inside the comparator* (O(N log N) graph loads); feeds
   load the full recursive entry set before cutting to feed size.

5. **Conditional GET is dead weight: ETags are emitted but never checked.**
   No endpoint handles `If-None-Match`/`If-Modified-Since`, so polling
   clients pay full load + serialization + transfer on every revalidation
   that could be a 304.

6. **Maintenance operations hold the global write monitor for their whole
   duration.** Context export holds `synchronized(repository)` while
   enumerating every named graph in the repo on the request thread; context
   import runs one giant transaction with a per-triple `hasStatement` + INFO
   log; `ContextImpl.reIndex` scans the whole repository; the backup job
   keeps the write-lockout (503 for all writes) through the full data-folder
   copy instead of just the RDF export.

---

## Top 10 by expected impact

| # | Finding | Theme | Impact | Risk | Effort |
|---|---------|-------|--------|------|--------|
| 1 | Build Solr docs outside the three locks (queue URI, build in submitter) | A1 | High | Med | Med |
| 2 | Memoize user→groups; compute principal set once per authorization | B1 | High | Med | Med |
| 3 | Load metadata graph once per Solr doc build (use Model overloads) | A2 | High | Low | Low |
| 4 | Conditional GET → 304 via `ServletWebRequest.checkNotModified` | D1 | High | Low | Low |
| 5 | Paginate before loading list children; cap/sane default limit | D2 | High | Low | Low |
| 6 | Parse ACL sets in `loadFromStatements` instead of 6 lazy re-fetches | B3 | High | Low | Low |
| 7 | Solr search pre-filter by principal `fq` instead of per-hit ACL loads | A4 | High | Med | Med |
| 8 | Parallelize Solr reindex (currently single-threaded, serial per doc) | A3 | High | Med | Med |
| 9 | `ListImpl` single-child remove/move without full-list rewrite | C2 | High | Med | Med |
| 10 | Stop export/import/reIndex holding the global monitor on request threads | E1–E4 | High | Med | Med |

Quick wins (small, low risk, do anytime): remove the unused connection in
`loadFromStatements` ✓; fix the inverted `getFileSize` guard ✓; fix the
search result-fill off-by-one (A6); `deleteById` instead of `deleteByQuery`
(A5); `toString(0)` instead of pretty-print `toString(2)` (D8); precompile
`URISplit` regexes (F1); thread-safe `DateTimeFormatter` for Solr dates
(A11); cache the MD5 predicate-hash (A16).

---

## Theme A — Solr indexing & search

| ID | Location | Problem | Fix | Impact / Risk |
|----|----------|---------|-----|----------------|
| A1 ✓×3 | `SolrSearchIndex.java:1261-1285`, `RepositoryManagerImpl.java:609-629`, `MetadataImpl.java:128` | Full Solr doc construction (10+ RDF4J reads, ACL evaluation, guest-auth probe) runs synchronously on the writer thread under `synchronized(repository)` → `synchronized(repositoryListeners)` → `synchronized(postQueue)` | Enqueue only the entry URI; build documents in the submitter thread outside all locks | High / Med |
| A2 ×2 | `SolrSearchIndex.java:903-1118` + `EntryUtil.java:406-603` | One doc build calls `entry.getMetadataGraph()` ~8–10× (getTitles, getFirstName, getLastName, getDescriptions, getTagLiterals, getTagResources, getEmail…), each opening a connection and copying the graph | Fetch the Model once, pass it to the existing graph-accepting `EntryUtil` overloads | High / Low |
| A3 | `SolrSearchIndex.java:137,611-651,798-822` | Reindex executor is `newSingleThreadExecutor`; context ACL flips reindex every entry serially at full A1/A2 per-doc cost — hours for millions of entries | Worker pool / virtual threads feeding the existing batch submitter | High / Med |
| A4 | `SolrSearchIndex.java:1377-1415` | ACL post-filtering: per Solr hit, load entry from RDF4J + `checkAuthenticatedUserAuthorized` with `AuthorizationException` as control flow | Build an `fq` from the caller's principals against the indexed `acl.metadata.r`/`public` fields; keep app check as backstop | High / Med |
| A5 | `SolrSearchIndex.java:216-221` | Routine deletes use `deleteByQuery uri:(…)` although `uri` is the uniqueKey — DBQ blocks version buckets and merges | `UpdateRequest.deleteById(List)`; keep DBQ for context-wide purges only | Med / Low |
| A6 | `SolrSearchIndex.java:1360-1376` | Result-fill loop off-by-one: `if (resultFillIteration++ > 0)` then checks `== 1` — the rows-widening branch is dead; ACL-filtered small-limit searches page up to 10 sequential Solr round-trips | Fix the counter logic so the window widens on first refill | Med / Low |
| A7 | `SolrSearchIndex.java:958-978` | Per-doc N+1: projectType loads the surrounding context entry's full graph for every indexed entry (acknowledged FIXME) | Cache projectType per context for the reindex run | Med / Low |
| A8 ×2 | `SolrSearchIndex.java:1195-1244` | With global `solr.related` config, every indexed doc enumerates ALL contexts (acknowledged TODO) | Cache the context set, invalidate on context create/delete | Med / Low |
| A9 | `SolrSearchIndex.java:129` | `postQueue` is an unbounded cache of fully-built `SolrInputDocument`s — reindex of a large repo can pin millions of docs on the heap | Bound the queue or store URIs and build at drain time (same fix as A1) | Med / Med |
| A10 | `SolrSearchIndex.java:1096-1112` | Guest-readability probe per doc via thrown `AuthorizationException` + principal lookups | Derive `public` from the already-fetched ACL read set | Med / Low |
| A11 ×2 | `SolrSearchIndex.java:135,1258` | Shared `SimpleDateFormat` instance used from multiple threads (indexer, purge, request-thread context purge) — not thread-safe, can corrupt date strings | `static final DateTimeFormatter` (UTC) | Low / Low (correctness) |
| A12 | `SolrSearchIndex.java:103-105,269-274` | `commitWithin=1000 ms` under steady load → ~1 commit/s, tiny segments, searcher churn (adaptive 10 s only above 500-doc backlog) | Raise default (5–15 s), make configurable | Low / Low |
| A13 | `SolrSearchIndex.java:162-178` | Submitter idle-polls with `Thread.sleep(500)` — 2 wakes/s forever, up to 500 ms added latency | wait/notify as already done in `PublicRepository` | Low / Med |
| A14 | `ListImpl.java:538-541,684-687` | List mutations fire one event per child → K full doc builds for a K-child reorder/delete (compounded by A1; mostly disappears once A1 lands) | Coalesce per-child events or rely on A1 | Med / Low |
| A15 | `SolrSearchIndex.java:1301-1306` | Context deletion runs a synchronous `deleteByQuery` HTTP round-trip on the caller's request thread | Route through the background delete path | Low / Low |
| A16 | `Hashing.java:54-76` ← `SolrSearchIndex.java:1153` | `MessageDigest.getInstance("MD5")` provider lookup per metadata statement for predicate hashing; predicates repeat heavily | Memoize predicate→hash in a ConcurrentHashMap | Med / Low |
| A17 | `SearchController.java:147-149` | No cap on client-supplied `offset` → unbounded Solr deep paging on a public endpoint | Cap offset (~10k) or cursorMark | Med / Low |

## Theme B — Authorization & principal resolution

| ID | Location | Problem | Fix | Impact / Risk |
|----|----------|---------|-----|----------------|
| B1 ✓×3 | `PrincipalManagerImpl.java:239-268,393-436` | `getGroupUris` scans ALL principal entries per call (load each + `isMember` per group); called up to 2× per `hasAccess`, which runs 4–6× per `checkAuthenticatedUserAuthorized` → ~8–12 scans per decision; per list child and per search hit this explodes (100-item listing ≈ ~1000 scans) | Memoize user→groups (invalidate on group mutation); compute the caller's principal set once per authorization and intersect | High / Med |
| B2 | `GroupImpl.java:180-189` | `isMember` is `getChildren().contains(uri)` — O(members) Vector scan, multiplied inside B1's loop | Companion HashSet for membership | Med / Low |
| B3 | `EntryImpl.java:273-415,925-947` | `loadFromStatements` has the ACL triples in hand but nulls all five principal sets; first ACL check re-fetches them with up to 6 connections/queries | Parse the principal sets and `readOrWrite` flag during `loadFromStatements` | High / Low |
| B4 ×2 | `ReloadUserPropertiesFilter.java:47-72`, `ESUserDetailsService.java:68-100` | Every authenticated request fully reloads the user from RDF4J (principal entry + secret + admin-group membership) and re-registers the session; the reloaded copy isn't even the one used for this request's authorization (filter ordering) | Short-TTL UserDetails cache (1–5 s); register session once at login; fold disabled-check into the first filter | High / Med |
| B5 | `UserImpl.java:562-585` | `isDisabled` is the only uncached UserImpl getter — repo read per authenticated request and per serialized user row | Cache like the other fields, invalidate in `setDisabled` | Med / Low |
| B6 | `MetadataImpl.java:81-106`, `EntryUtil.java:357-380` | `getGraph()` runs a full ACL check + fresh read every call; `EntryUtil.getName` calls it up to 3×, serialization 1–2× after the controller already authorized | Internal pre-authorized accessor; fetch once and reuse in getName/getStructuredName | Med / Med |

## Theme C — RDF4J core mutation paths

| ID | Location | Problem | Fix | Impact / Risk |
|----|----------|---------|-----|----------------|
| C1 ✓×2 | `EntryImpl.java:304-308` | `loadFromStatements` acquires a `RepositoryConnection` whose only use is `rc.close()` — pure churn on every entry load | Delete it | Med / Low |
| C2 | `ListImpl.java:155-168,576-658` | `saveChildren` clears and rewrites the whole `rdf:Seq` graph for a single-child remove/move — 2N B-tree mutations per op; deleting N entries sharing a list is O(N²) with N commits | Targeted remove + tail renumber for removeChild; full rewrite only for setChildren | High / Med |
| C3 | `ContextImpl.java:671-709` | Create-in-list path: ACL copy = 5 separate `updateAllowedPrincipalsFor` transactions (plus 3 more for principal entries, plus addChild tx) — ~4–8 commits where one would do; these setters also ignore `getActiveBatchConnection`, so `inBatch` doesn't help them | Single transaction for ACL copy; honor the batch connection in all mutation setters (also `RDFResource`, `UserImpl`, quota setters — same gap) | High / Low |
| C4 | `EntryImpl.java:1246,1396-1397` | `setGraph` reads the full old graph unconditionally (only needed for Context-type entries) and re-reads the entire just-written graph after commit | Conditional oldGraph fetch; update cached fields from the in-memory statements | Med / Low |
| C5 ✓×2 | `EntryImpl.java:1625-1704` + `ResourceService.java:298-304` | Upload finalization = 3 separate transactions (size/mime/name), each with own modified-date write + Solr event; `getFileSize` guard inverted (`> 0` should be `< 0`): cached values re-query, unknown values never load (also makes file-size sorting a no-op) | Combined setter, one tx, one event; fix the guard and populate fileSize in loadFromStatements | Med / Low |
| C6 | `ContextImpl.java:1046-1159` | Quota fill-level cache never populated on read (and when populated, the wrong variable is returned); every file write does getStatements+remove+add in its own tx under the monitor | Fix the cache, exact-remove write in the upload's tx | Med / Low |
| C7 | `EntryImpl.java:1185-1189` | `updateModificationDate` runs remove+add without begin/commit → 2 auto-commits per resource PUT | Wrap in begin/commit | Med / Low |
| C8 | `ContextImpl.java:896-916` | Context deletion loads every entry fully (graph read + SoftCache insert) just to delete it, clears each entry graph twice, and scans inverse relations for entries whose targets die with the context | Skip cache population, drop redundant clear, skip intra-context inverse-relation scans | Med / Med |
| C9 | `ListImpl.java:60,197,469-475` | `children` is a `Vector` with `contains()` O(N) in addChild/move, `removeAll` O(N×M) in setChildren — O(N²) to build a large list | Companion HashSet; ArrayList + explicit sync | Med / Med |
| C10 | `ProvenanceImpl.java:268-298` | Per metadata write (provenance on): a second connection+tx *inside* the open transaction and O(revisions) read+sort to find the latest revision (resolvable via the single `owl:sameAs` triple) | Reuse the active connection, direct lookup | Med / Low (config-gated) |
| C11 | `EntryImpl.java:670-870` | `setResourceURI`/`setExternalMetadataURI` = 4–6 sequential transactions with full graph rewrites + reloads (also non-atomic) | One transaction | Low / Med (rare op) |
| C12 | `ListImpl.java:716-739` | `applyACLtoChildren`: 5 transactions per child, recursive | One tx per child (or rely on C3's batch-honoring setters) | Low-Med / Low |

## Theme D — REST layer

| ID | Location | Problem | Fix | Impact / Risk |
|----|----------|---------|-----|----------------|
| D1 | `MetadataController.java:97-107`, `HttpUtil.java:116-123` | ETag/Last-Modified emitted, never checked — no 304 path anywhere; pollers pay full load+serialize+transfer per revalidation | `ServletWebRequest.checkNotModified(...)` before loading/serializing | High / Low |
| D2 ×2 | `ResourceJsonSerializer.java:169-294`, `ListFilter` | List GET loads ALL children before offset/limit; default limit 0 = unlimited; with `includeAll` each child costs ~5 more reads | Slice children URIs to the page before loading; non-zero default limit | High / Low |
| D3 | `SearchService.java:196-302` | Per Solr hit: entry graph + metadata + cached-external + relations + getRights all re-loaded from RDF4J; response pretty-printed `toString(2)` | `toString(0)` now; serve list fields from the Solr docs longer-term | High / Low |
| D4 | `ContextService.java:109-120` | Context entry listing loads every entry via `getByEntryURI` only to substring its ID from the URI (the deleted-branch above already does it by substring) | Derive IDs from URIs directly | High / Low (admin endpoint) |
| D5 ×2 | `SyndicationService.java:107-209` | Feeds: up to 1000 Solr hits, recursive child resolution, 4–6 metadata loads per item — all before the feed-size cut (default 50) | Apply the limit before metadata extraction; cache creator names | Med / Low |
| D6 | `ProxyService.java:95-171` | Proxy buffers up to 10 MB ×3 copies; `disconnect()` kills keep-alive → fresh TLS handshake per request and per redirect hop | Stream with size cap; pooled JDK HttpClient | Med / Med |
| D7 | `GraphUtil.java:133-189` | RDF writer instantiated via reflection per call; always serializes to String (full materialization); JSON-LD namespace pass iterates statements × NS map | Writer factory map; stream to the response OutputStream | Med / Low |
| D8 | `RDFJSON.java:241-248` | RDF/JSON responses pretty-printed `toString(2)` — 10–30% larger payloads + CPU (EntryService already uses `toString(0)`) | `toString(0)` | Med / Low |
| D9 | `ResourceController.java:149`, `ContextController.java:131` | `@RequestBody byte[]` buffers whole uploads (files, import zips) in heap, plus extra `new String(...)` copies; quota checked only after full buffering | Stream `InputStream` → `Data.setData`; pre-check Content-Length | Med / Med |
| D10 | `CheckUsernamePasswordFilter.java:74-84` | Login runs PBKDF2 twice as a format probe before the real PBKDF2 match — 3× CPU per attempt, amplifies credential-stuffing CPU load | Validate format by character/length rule (verify timing-channel implications) | Med / Med |
| D11 | `EntryService.java:135-254`, `GraphUtil.java:349-351` | Entry JSON: each graph materialized 3× (Model → JSONObject → String → Jackson raw copy); JSON-LD path re-parses its own String output | Custom JsonSerializer streaming into the active JsonGenerator (dormant `graphToRdfJsonJackson` is a starting point) | Med / Med |
| D12 | `EntryUtil.java:186-206` ← `ResourceJsonSerializer.java:193` | Title sort comparator loads metadata graphs + runs 2 full ACL checks *per comparison* — sorting 500 children ≈ ~9000 repo reads | Decorate-sort-undecorate with a precomputed title map | High / Low |
| D13 | `EntryUtil.java:669-792` | Metadata traversal's visited-map keyed by (entry, level): same entry re-fetched once per depth level | Global visited set | Med / Low |
| D14 | `ContextManagerImpl.java:1231-1358` | SPARQL search results: `List.contains` dedup O(n²), and it compares URIs against a list of Entry objects so it never matches (dup leak); N+1 eager entry loads | Set-based dedup; lazy/paged resolution | Med / Low |
| D15 | `MetadataService.java:257-290` | `graphQuery` spins up a new MemoryStore SailRepository per request | Evaluate against the in-memory Model | Low / Low |
| D16 | `RequestResponseLoggingFilter.java:42-64` | Two unconditional INFO logs + redaction regex per request, on by default | Gate or default off in production | Low / Low |

## Theme E — Background jobs & maintenance

| ID | Location | Problem | Fix | Impact / Risk |
|----|----------|---------|-----|----------------|
| E1 | `ContextManagerImpl.java:228-242` | Context export holds `synchronized(repository)` for the entire export, enumerates ALL named graphs in the repo, runs on the request thread | Snapshot-read without the monitor; per-context NG reads; async job + download handle | High / Low |
| E2 | `ContextManagerImpl.java:397-580`, `ContextController.java:129-135` | Import: zip buffered as byte[], full dump parsed into memory, one giant transaction under the monitor with per-triple `hasStatement` + INFO log, per-entry remove transactions before, full `reIndex` after — all synchronous on the request thread | Stream-parse in batches, drop per-triple checks/logging, async job | High / Med |
| E3 | `BackupJob.java:84,215` | Write-lockout (503 for all writes) held through the full data-folder copy (full re-copy each run), though only the RDF export needs it — and that uses a snapshot-isolated connection anyway | Lockout only the export phase (or drop it); incremental/hardlink file copy | High / Med |
| E4 | `ContextImpl.java:112-189` | `reIndex()` scans ALL `es:resource`/`es:externalMetadata` statements of the **whole repository** under the monitor — O(repo) per single-context reindex; runs at the end of every context import | Scope to the context's named graphs; chunk the monitor | High / Med |
| E5 ×2 | `PublicRepository.java:391-419,72,485-569` | Any update to a Context-type entry re-mirrors EVERY entry of that context (remove+add, guest ACL check each) — repeats on every touch; postQueue is unbounded holding strong Entry refs; startup rebuild is one giant transaction blocking boot | Cascade only on actual ACL change; queue URIs, bounded; chunked commits on background thread | Med-High / Med |
| E6 | `ListRecordsJob.java:109-448` (harvesting) | Per-record create+setGraph transactions (the exact pre-batching pattern), no overlap guard (`synchronized run()` on a per-fire instance), per-record XPathFactory SPI lookup + compile, O(n) entry loads for `from` auto-detect. **Latent**: module not on the Spring Boot classpath today | Batch per N records; `@DisallowConcurrentExecution`; compile XPath once; single query for max date — when re-wiring | High-if-wired / Low |
| E7 | `RepositoryManagerImpl.java:892-904` | NativeStore upgrade copies the whole store in one implicit transaction (giant changeset spill) | Chunked commits (one-off migration path) | Med / Low |
| E8 | `Graph2Entries.java:174-185` ← `ExecutionService.java:98` | Pipeline execution: per-entry transactions, synchronous on the request thread, no timeout | Batch API; async execution with status polling | Med / Med |
| E9 | `BackupJob.java:50,108` | No `@DisallowConcurrentExecution` — overlapping fires queue on a static lock then run redundant backups back-to-back, each re-triggering lockout | `@DisallowConcurrentExecution` (skip semantics) | Med / Low |

## Theme F — Mechanical / cross-cutting

| ID | Location | Problem | Fix | Impact / Risk |
|----|----------|---------|-----|----------------|
| F1 | `URISplit.java:34-60` | URI regexes via `String.matches` — `Pattern.compile` per call, on every entry-URI resolution; `toString()` called twice | `static final Pattern`; reuse toString | Med / Low |
| F2 | `Configurations.java:52-135` + callers | All config reads behind `SynchronizedConfiguration`'s single mutex; per-request lookups (content-disposition, proxy whitelist parse) take the global lock and re-parse | Cache in final fields / `@Value` at construction | Med / Low |
| F3 | `EntryUtil.java:757-761`, `SolrSearchIndex.java:1238-1297` | Eager debug/info string building (Set.toString, concat) in per-entry loops; `createIRI(prop.toString())` re-conversion per entry per level | slf4j placeholders; hoist IRI conversion | Low / Low |
| F4 | `NS.java:93-100` | `containsKey`+`get` double lookup + `split(":")` per predicate parameter | single get; indexOf/substring | Low / Low |
| F5 | `Password.java:241,263` | Password-rule `Pattern.compile` per call (bcrypt dominates, marginal) | Compile at config load | Low / Low |
| F6 | `ContextController.java:116`, `FileOperations.java:405` | Unbuffered streams on zip export and marker writes | Buffered/NIO equivalents | Low / Low |
| F7 | `TokenCache.java:27-52` | `synchronized` around ConcurrentHashMap ops; `cleanup()` per read (cold today, base class invites reuse) | Drop locks; throttle cleanup | Low / Low |

---

## Theme G — Virtual threads: where they help, where they don't

Context: Java 25 (JDK 24's JEP 491 removed the `synchronized` carrier-pinning
problem — relevant because EntryStore synchronizes pervasively; `ScopedValue`
is final in 25), Spring Boot 3.5.13 / Jetty 12 (supports
`spring.threads.virtual.enabled=true`). One site already uses virtual
threads: `SolrSearchIndex.purgeExecutor` (`SolrSearchIndex.java:139`,
`newVirtualThreadPerTaskExecutor`).

**Hard constraint to respect everywhere:** two load-bearing ThreadLocals —
`PrincipalManagerImpl.authenticatedUserURI` (`PrincipalManagerImpl.java:55`)
and `RepositoryManagerImpl.activeBatchConnection`
(`RepositoryManagerImpl.java:130`). Virtual threads each get their own
ThreadLocals, so thread-per-request works fine, but **fanning work out to
other threads (virtual or not) silently drops auth context and batch
membership**. Any fan-out must save/set/restore auth explicitly — the
pattern already exists in `SolrSearchIndex.reindex` (lines 634-642). A batch
(`inBatch`) must stay on one thread.

**Structural caveat:** virtual threads improve *capacity to wait*, not write
throughput — the global `synchronized(repository)` monitor makes write
concurrency 1 regardless of threading model. The platform request pool
(~200) currently acts as an accidental global concurrency cap; removing it
without backpressure lets unbounded concurrent requests pile up on the
monitor and on per-request memory buffers (see D6/D9 buffering).

| ID | Site | Verdict | Rationale |
|----|------|---------|-----------|
| G1 | Jetty request threads (`spring.threads.virtual.enabled=true`, currently unset) | **Beneficial — recommended, after load-testing** | Requests are I/O-heavy (NativeStore disk reads, Solr HTTP, proxy upstreams, file serving); slow requests (proxy redirect chains, search, exports) can no longer exhaust a 200-thread pool. Bonus: a fresh thread per request structurally eliminates the stale-ThreadLocal-leak class that `SetUserURIAfterAuthenticationFilter` defends against. Pair with request-level backpressure and ideally fix D6/D9 buffering first (per-request heap × unbounded concurrency). |
| G2 | Solr reindex fan-out (with A3; `reindexExecutor` is a single platform thread, `SolrSearchIndex.java:137`) | **Beneficial — use VTs when implementing A3** | Doc building is I/O-bound (RDF4J reads + Solr HTTP) — ideal VT fan-out shape: `newVirtualThreadPerTaskExecutor` + `Semaphore` to bound concurrency. Each task must set admin auth (existing save/set/restore pattern). |
| G3 | MVC async executor for StreamingResponseBody (`MvcConfiguration.java:37-52`, bounded platform pool) | **Neutral-positive — only with a concurrency limit kept** | The bound is a deliberate DoS guard for anonymous `/sparql` (see in-code comment). VTs remove the "pin native threads" cost but unbounded concurrent SPARQL is still repo/CPU DoS. If changed: `SimpleAsyncTaskExecutor` with `setVirtualThreads(true)` + `setConcurrencyLimit(...)` — simpler, same protection. |
| G4 | Long-running daemons: `documentSubmitter`, `delayedContextIndexer` (`SolrSearchIndex.java:461-465`), SoftCache remover (`SoftCache.java:209`) | **No benefit — leave as platform daemons** | Singleton infinite loops; VTs help when you have *many* blocking tasks, not three permanent ones. |
| G5 | Password-reset async executor (`AuthService.java:174-176`, custom ThreadFactory) | **Simplification only** | Replace pool + factory with `newVirtualThreadPerTaskExecutor`; traffic is tiny and rate-limited, no perf gain — just less code. |
| G6 | Quartz scheduler pool (backup, harvester jobs) | **No change** | Few long jobs dominated by repo-monitor waits and file I/O; Quartz's pool model doesn't compose with VTs without a custom ThreadPool impl — not worth it. |
| G7 | Harvester per-record pool (`ListRecordsJob.java:172`, latent) | **VTs when rewired, but batching (E6) matters more** | Record processing is HTTP-fetch-bound (VT-friendly), but writes serialize on the repo monitor anyway — concurrency beyond ~2-4 buys nothing until E6 lands. |
| G8 | `authenticatedUserURI` / `activeBatchConnection` ThreadLocal → `ScopedValue` | **Forward-looking only — don't do now** | ScopedValue (final in 25) would make context propagation explicit and structured-concurrency-safe, but the imperative set/clear API is used across the whole codebase; only worth it bundled with a broader auth-context refactor. |

Net: virtual threads are an **enabler for A3/G2 and a capacity win for the
request path (G1)** — not a fix for any of the throughput findings above,
which are dominated by lock scope and redundant I/O.

---

## Checked and found NOT to be an issue

- **Alias/name→context resolution** is backed by an in-memory per-context map
  (`EntryNamesContext`), loaded once — the suspected per-call repo scan does
  not exist on the hot path. (`ContextManagerImpl.searchLiterals` *does* do a
  whole-repo scan but has zero callers — dead code; remove rather than fix.)
- **SolrClient** is a single shared Jetty client with timeouts; adds are
  batched 100 docs/UpdateRequest with commitWithin; no per-doc commit.
- **SPARQL endpoints** stream with bounded async pool, per-query timeout and
  size-limited output.
- **File downloads** stream via FileSystemResource; ObjectMappers are shared;
  rate limiters are Caffeine-based without hot locks.
- **ContextImpl.getEntries / getByResourceURI** are in-memory index + SoftCache.
- **Group membership recursion**: groups are flat (User-only members) —
  no recursive traversal on the membership path itself.
- **`ListImpl.addChild`** appends a single `rdf:_n` triple (no full rewrite —
  only remove/move/set rewrite, see C2).
- **Backups/exports** (`exportToFile`) stream without holding the write
  monitor (the *context*-level export E1 is the exception).
- **Quartz jobs** never run on the request path; transforms module is
  largely dead code (CSV transform fully commented out).
- **PublicRepository submitter** is signal-based (wait/notify) with 1000-doc
  batches — the queue *content* (E5) is the issue, not the loop.

## Suggested sequencing

1. **Quick wins batch** (days): C1, C5-guard, A6, A5, D8, F1, A11, A16, C7
   — all small, low-risk, individually measurable.
2. **Solr write-path decoupling** (A1 + A2 + A9 together): biggest
   normal-operation write-throughput lever; benchmark with docs 01–07 harness
   extended with a Solr-enabled mode. Implement the A3 reindex fan-out with
   virtual threads (G2) while in there.
3. **Authorization caching** (B1 + B3 + B4 + B2): biggest read-path lever;
   needs careful invalidation design (group mutation, ACL change, user
   disable) — but EntryStore is single-instance (no cross-node invalidation
   problem).
4. **REST pagination + 304s** (D1, D2, D3, D12): user-visible latency on
   large lists and search.
5. **Maintenance-op isolation** (E1–E4): removes the worst-case multi-minute
   write stalls.
6. Re-evaluate **C2/C3** (list rewrite, ACL-copy transactions) together with
   the batch-API production wiring from doc 03's follow-ups.
7. **Virtual threads on the request path** (G1): enable
   `spring.threads.virtual.enabled=true` behind a load test, after the
   D6/D9 buffering fixes provide per-request memory bounds.

## Caveats

- Static analysis only — no profiling or measurement was performed.
  Individual impact estimates are reasoned, not measured. Items in the
  top-10 deserve a measurement harness before/after.
- Line numbers are as of `17b92b04` on `feature/benchmark-ai`.
- Some findings are config-gated (provenance C10, quotas C6, solr.related A8)
  or latent (harvesting E6 — module currently not wired into Spring Boot).
- A few findings are correctness-adjacent rather than pure performance
  (A11 thread-unsafe date format, C5 inverted guard, D14 broken dedup,
  A6 dead widening branch) — these are worth fixing regardless of speed.
