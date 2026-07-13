# Fable Investigation (Findings 08) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement every actionable finding from `modules/benchmark/findings/08-fable-investigation.md`, one point at a time, benchmarking after each point and recording results in `modules/benchmark/findings/09+...` docs in the style of docs 01–07.

**Architecture:** Work proceeds in the order suggested by doc 08's "Suggested sequencing", adjusted for what has already been fixed on the branch since the doc was written. Each point = one commit + one benchmark cycle + one findings-doc section. Measurement uses the existing `benchmark-entrystore` harness (native/memory × batched/unbatched), a repaired `benchmark-solr` harness against a Dockerized Solr 10, and targeted harness extensions (auth-read pass, reindex pass) added in this plan.

**Tech Stack:** Java 25, RDF4J NativeStore/MemoryStore, Solr 10 (Docker, `solr:10.0.0` image + IT core config), Maven wrapper, Spring Boot 4.1 REST layer (Jetty 12), Caffeine for caches.

---

## Ground rules

- **Branch:** `feature/benchmark-ai`. Commit style on this branch for benchmark work: plain imperative message, no JIRA prefix, no AI attribution (matches `5965f5e1`, `184baeeb`, `d68a1996`).
- **License headers:** when editing a file, bump the year range to `2007-2026` if older.
- **Build commands (Windows, PowerShell):**
  - Core: `.\mvnw.cmd clean install -pl core/core-impl -am -Dmaven.test.skip=true -DskipDependencyCheck=true`
  - Benchmarks: `.\mvnw.cmd clean install -pl modules/benchmark/benchmark-common,modules/benchmark/benchmark-entrystore,modules/benchmark/benchmark-solr -Dmaven.test.skip=true -DskipDependencyCheck=true`
  - Spring Boot module (for D/E REST work): `.\mvnw.cmd clean install -pl modules/rest/spring-boot -am -Dmaven.test.skip=true -DskipDependencyCheck=true`
  - Unit tests for touched core code: `.\mvnw.cmd test -pl core/core-impl -Dtest=<TestClass>`
- **Benchmark protocol (per measured point):** fresh store dir per run under `$env:TEMP\entrystore-bench\`, `-Xmx2g`.
  - `NB` native batched: `java -Xmx2g -jar modules\benchmark\benchmark-entrystore\target\entrystore-benchmark-entrystore-6.0-SNAPSHOT.jar -s native -u 10000 -m 2000 -B true -p <fresh>` — 3 runs
  - `NU` native unbatched: same jar, `-s native -u 2000 -m 500 -p <fresh>` — 2 runs
  - `MU` memory unbatched: `-s memory -u 10000 -m 2000 -p <fresh>` — 2 runs
  - `SOLR` (after Task 0.2): `java -Xmx2g -jar modules\benchmark\benchmark-solr\target\entrystore-benchmark-solr-6.0-SNAPSHOT.jar -s native -u 2000 -m 500 -S http://localhost:8983/solr/entrystore-core -p <fresh>` — 3 runs, purge core between runs (`curl -X POST 'http://localhost:8983/solr/entrystore-core/update?commit=true' -H 'Content-Type: text/xml' --data '<delete><query>*:*</query></delete>'`)
  - Record: total "Adding to context took", "Reading from database took", per-sample Peter Griffin lines, and (SOLR) queue-drain time.
- **Findings docs:** `modules/benchmark/findings/NN-<slug>.md`, one per implemented point-group, following the structure of docs 03/07 (What was added / Results tables with runs + averages / Implication / How to reproduce).
- **Regression gate before every commit:** core unit tests for touched classes must pass; REST changes additionally run affected ITs (`.\mvnw.cmd clean verify -pl modules/rest/integration-test -Dtest=<IT>`).

## Verified current state (2026-07-10, HEAD d7c369b2)

Already fixed since doc 08 was written — **do not re-implement**, only record in the wrap-up doc:
- **C1** unused connection in `loadFromStatements` — fixed in `03b14c65`.
- **A11** thread-unsafe `SimpleDateFormat` — now `static final DateTimeFormatter SOLR_DATE_FORMATTER` (SolrSearchIndex.java:111).
- **C5 (guard part)** `getFileSize` guard is now `if (this.fileSize < 0)` — correct. The 3-transaction upload finalization + fileSize-in-loadFromStatements parts remain open (Task 8.4).
- Modified-date overwrite-in-place (`34b06385`) — the doc-01 follow-up, already merged.

Confirmed still open (self-verified in code): C7 (`updateModificationDate()` has no begin/commit — EntryImpl.java:1178), A6 (dead widening branch — SolrSearchIndex.java:1370-1377), A5, A16, D8, F1, A1, A2, A3, A9, A10, A13, B1–B6, C2–C4, C6, C8–C12, D1–D7, D9–D16, E1–E5, E7–E9, F2–F7.

**Harness gaps found:**
1. `benchmark-solr` cannot run: `RepositoryManagerImpl` requires an `http(s)` Solr URL (embedded Solr removed) but the module passes a temp filesystem path. Fix in Task 0.2.
2. The `-a` (ACL) benchmark mode authenticates as **admin**, which short-circuits `checkAuthenticatedUserAuthorized` before `hasAccess`/`getGroupUris` — B-theme changes are invisible to the current harness. Fix in Task 5.1 (read-as-benchmark-user pass; the benchmark user + per-context Administer ACL already exist in `MultipleTransactions`).

## Full disposition table (every finding ID in doc 08)

| ID | Disposition | Task | Findings doc |
|----|-------------|------|--------------|
| A1 | Implement — queue URIs, build docs in submitter outside locks | 3.1 | 12 |
| A2 | Implement — Model-accepting EntryUtil overloads, fetch graph once per doc build | 2.1 | 11 |
| A3 | Implement — bounded virtual-thread fan-out for reindex (with G2) | 4.1 | 13 |
| A4 | Implement — principal-based `fq` pre-filter, app check kept as backstop | 6.3 | 15 |
| A5 | Implement — `deleteById` for routine deletes | 1.3 | 09 |
| A6 | Implement — fix dead rows-widening branch | 1.2 | 09 |
| A7 | Implement — per-run projectType cache during reindex | 4.2 | 13 |
| A8 | Implement — cache context set for `solr.related`, invalidate on context create/delete | 4.3 | 13 |
| A9 | Implement — folded into A1 (URI queue bounds heap) | 3.1 | 12 |
| A10 | Implement — derive `public` without exception control flow (verify context-ACL semantics preserved) | 3.2 | 12 |
| A11 | Already fixed — record only | — | 19 |
| A12 | Implement — raise default commitWithin, make configurable | 3.4 | 12 |
| A13 | Implement — wait/notify submitter (mirror PublicRepository pattern) | 3.3 | 12 |
| A14 | Re-evaluate after A1; expected obsolete — record decision | — | 19 |
| A15 | Implement — context-purge DBQ off the request thread (purgeExecutor) | 3.5 | 12 |
| A16 | Implement — memoize predicate→MD5-trunc8 map | 1.4 | 09 |
| A17 | Implement — cap client-supplied offset in SearchController | 6.4 | 15 |
| B1 | Implement — memoized user→groups with explicit invalidation | 5.2 | 14 |
| B2 | Implement — membership HashSet mirror in ListImpl (with C9) | 5.3 | 14 |
| B3 | Implement — parse 5 ACL principal sets in loadFromStatements | 5.4 | 14 |
| B4 | Implement — short-TTL UserDetails cache + register session at login only | 7.1 | 16 |
| B5 | Implement — cache `isDisabled` like sibling getters | 5.5 | 14 |
| B6 | Implement if low-risk after B1–B5 measurements; else record rationale | 5.6 | 14 |
| C1 | Already fixed — record only | — | 19 |
| C2 | Implement — targeted single-child remove + tail renumber | 8.1 | 17 |
| C3 | Implement — one-tx ACL copy; honor batch connection in mutation setters (with C12) | 8.2 | 17 |
| C4 | Implement — conditional oldGraph fetch; update cached fields from in-memory statements | 8.3 | 17 |
| C5 | Implement remainder — combined size/mime/name setter (one tx, one event); fileSize in loadFromStatements | 8.4 | 17 |
| C6 | Implement — fix quota fill-level cache (config-gated; correctness) | 8.5 | 17 |
| C7 | Implement — wrap updateModificationDate in begin/commit | 1.1 | 09 |
| C8 | Implement — context-deletion de-bloat (skip cache population, single clear, skip intra-context inverse scans) | 8.6 | 17 |
| C9 | Implement with B2 (same data structure change) | 5.3 | 14 |
| C10 | Implement — reuse active connection + owl:sameAs direct lookup (config-gated) | 8.7 | 17 |
| C11 | Skip — rare admin operation, 4–6 tx acceptable; record rationale | — | 19 |
| C12 | Implement with C3 (batch-honoring setters make it one tx/child) | 8.2 | 17 |
| D1 | Implement — `ServletWebRequest.checkNotModified` before load/serialize | 6.1 | 15 |
| D2 | Implement — slice child URIs to page before loading (no default-limit change; record API note) | 6.2 | 15 |
| D3 | Implement — `toString(0)` + drop redundant per-hit loads where safe; Solr-doc-served fields recorded as future work | 6.5 | 15 |
| D4 | Implement — derive context-entry IDs from URIs | 6.6 | 15 |
| D5 | Implement — apply feed-size cut before metadata extraction | 6.7 | 15 |
| D6 | Implement — pooled JDK HttpClient + streaming with size cap | 7.2 | 16 |
| D7 | Implement — writer factory map (streaming rewrite recorded as future work) | 6.8 | 15 |
| D8 | Implement — `toString(0)` in RDFJSON | 1.5 | 09 |
| D9 | Skip in this pass — controller-signature/API change with quota semantics; record rationale | — | 19 |
| D10 | Investigate at 7.3; implement only if timing-channel-safe, else record rationale | 7.3 | 16 |
| D11 | Skip in this pass — larger serializer rewrite; record rationale + pointer to dormant graphToRdfJsonJackson | — | 19 |
| D12 | Implement — decorate-sort-undecorate with precomputed title map | 6.9 | 15 |
| D13 | Implement only if semantics-preserving (visited keyed per (entry,level) may be intentional); verify then decide | 6.10 | 15 |
| D14 | Implement — Set-based dedup fix (correctness: current compare never matches) | 6.11 | 15 |
| D15 | Skip — low impact; record rationale | — | 19 |
| D16 | Implement — gate request/response logging behind config, default off | 6.12 | 15 |
| E1 | Implement — export via snapshot reads per context NG, off the global monitor | 9.1 | 18 |
| E2 | Implement partial — drop per-triple hasStatement+INFO log, chunked commits; async job recorded as future work | 9.2 | 18 |
| E3 | Implement — write-lockout only around RDF export phase | 9.3 | 18 |
| E4 | Implement — reIndex scoped to the context's named graphs | 9.4 | 18 |
| E5 | Implement partial — cascade only on actual ACL change; URI-queue rework recorded as future work | 9.5 | 18 |
| E6 | Skip — harvesting module not wired into Spring Boot; record rationale | — | 19 |
| E7 | Skip — one-off migration path; record rationale | — | 19 |
| E8 | Skip in this pass — async execution API design needed; record rationale | — | 19 |
| E9 | Implement — `@DisallowConcurrentExecution` on BackupJob | 9.6 | 18 |
| F1 | Implement — precompiled Patterns + single toString | 1.6 | 09 |
| F2 | Implement — cache hot config reads in final fields (content-disposition, proxy whitelist) | 10.1 | 19 |
| F3 | Implement — slf4j placeholders + hoisted IRI conversion in per-entry loops | 10.2 | 19 |
| F4 | Implement — single map lookup in NS | 10.3 | 19 |
| F5 | Implement — precompile password-rule patterns | 10.4 | 19 |
| F6 | Implement — buffered streams on zip export / marker writes | 10.5 | 19 |
| F7 | Implement — drop redundant synchronized on ConcurrentHashMap; throttle cleanup | 10.6 | 19 |
| G1 | Do not flip default (doc 08 requires load test); document property + prerequisites | — | 19 |
| G2 | Implement as part of A3 | 4.1 | 13 |
| G3 | No change (deliberate DoS guard) — record | — | 19 |
| G4 | No change — record | — | 19 |
| G5 | Implement — VT executor simplification in AuthService | 7.4 | 16 |
| G6 | No change — record | — | 19 |
| G7 | Skip — latent module; record | — | 19 |
| G8 | Skip — forward-looking only; record | — | 19 |

---

## Phase 0 — Measurement infrastructure

### Task 0.1: Baseline benchmark runs at HEAD

- [ ] Build: core + benchmark modules (commands above).
- [ ] Run NB ×3, NU ×2, MU ×2; save console outputs under `modules/benchmark/findings/raw/baseline-<date>/` (dir is scratch; git-ignore if noisy — check `.gitignore`).
- [ ] Record the numbers in a scratch table; they are the "before" column for findings docs 09/11/12.

### Task 0.2: Repair benchmark-solr for external Solr + Docker runbook

**Files:**
- Modify: `modules/benchmark/benchmark-common/src/main/java/org/entrystore/BenchmarkCommons.java` (add `-S`/`--solr-url` option)
- Modify: `modules/benchmark/benchmark-common/src/main/java/org/entrystore/model/Arguments.java` (add `String solrUrl`)
- Modify: `modules/benchmark/benchmark-solr/src/main/java/org/entrystore/Benchmark.java` (use `arguments.getSolrUrl()` for `Settings.SOLR_URL`; only delete temp dirs it created; fail fast with a clear message when `-S` is missing)

- [ ] Add the CLI flag following the existing `-f/--force-sync` pattern in BenchmarkCommons (optional arg, stored in Arguments, logged via `log.solrUrl` system property).
- [ ] In benchmark-solr `Benchmark.createConfiguration`, set `Settings.SOLR_URL` from the new flag.
- [ ] Start Solr (one-time per session):
  ```powershell
  docker run -d --name bench-solr -p 8983:8983 -e SOLR_MODULES=analysis-extras solr:10.0.0
  docker cp modules\rest\integration-test\src\test\resources\solr\. bench-solr:/entrystore-core/conf
  docker exec bench-solr solr create -c entrystore-core -d /entrystore-core
  ```
- [ ] Verify: SOLR protocol run completes and logs queue-drain; record baseline SOLR ×3 (this is the "before" for docs 11–13).
- [ ] Commit: `Run benchmark-solr against an external Solr URL (-S flag)`.
- [ ] Findings doc `10-solr-benchmark-enablement.md`: what was broken, the fix, Docker runbook, baseline numbers.

## Phase 1 — Quick wins (findings doc 09, one commit + benchmark per item)

### Task 1.1: C7 — transactional updateModificationDate

**Files:** Modify `core/core-impl/src/main/java/org/entrystore/impl/EntryImpl.java:1178-1182`

- [ ] Wrap in explicit tx (mirror the `setResourceType` caller pattern at EntryImpl.java:1100-1113):
  ```java
  public void updateModificationDate() {
  	try (RepositoryConnection rc = repository.getConnection()) {
  		rc.begin();
  		try {
  			this.updateModifiedDateSynchronized(rc, getRepositoryManager().getValueFactory());
  			rc.commit();
  		} catch (Exception e) {
  			rc.rollback();
  			throw e;
  		}
  	}
  }
  ```
- [ ] Grep callers to confirm none already hold a tx on the same connection (they can't — the connection is created here).
- [ ] Run core unit tests (`EntryImplTest` if present) + NB/NU/MU.
- [ ] Commit: `Wrap updateModificationDate in an explicit transaction`.

### Task 1.2: A6 — fix dead rows-widening branch

**Files:** Modify `core/core-impl/src/main/java/org/entrystore/repository/util/SolrSearchIndex.java:1368-1384`

- [ ] Restructure so the widening branch is reachable on the first refill iteration (post-increment currently makes `resultFillIteration == 1` impossible inside the `> 0` block):
  ```java
  int resultFillIteration = 0;
  do {
  	if (resultFillIteration > 0) {
  		if (resultFillIteration == 1 && limit <= 10) {
  			query.setRows(100);
  		}
  		if (resultFillIteration > 10) {
  			log.warn("Breaking after 10 result fill iterations to prevent too many loops");
  			break;
  		}
  		offset += Math.min(limit, 50);
  		log.warn("Increasing offset to {} in an attempt to fill the result limit", offset);
  	}
  	resultFillIteration++;
  	...
  ```
  (increment moved to the end of the guard so the counter counts completed iterations; verify loop-exit conditions unchanged).
- [ ] Benchmark: not exercised by write harness — run NB ×1 as regression only; note in doc 09 that this is a search-path correctness fix.
- [ ] Commit: `Fix unreachable rows-widening branch in Solr result fill loop`.

### Task 1.3: A5 — deleteById for routine deletes

**Files:** Modify `core/core-impl/src/main/java/org/entrystore/repository/util/SolrSearchIndex.java:206-254` (`processDeleteBatch`)

- [ ] Replace the `uri:(a OR b …)` deleteByQuery with `UpdateRequest.deleteById(List<String>)`; keep DBQ for context-wide purges (`clearSolrIndex`) untouched.
- [ ] Run SOLR ×3 (deletes happen on entry updates? verify — if the insert-only workload never deletes, note that and validate via a unit-level check instead).
- [ ] Commit: `Use deleteById instead of deleteByQuery for routine Solr deletes`.

### Task 1.4: A16 — memoize predicate hash

**Files:** Modify `core/core-impl/src/main/java/org/entrystore/repository/util/SolrSearchIndex.java` (caller, ~line 1152) — add a `private static final ConcurrentHashMap<String, String> PREDICATE_MD5_CACHE`; leave `Hashing.java` generic API untouched.

- [ ] `String predMD5Trunc8 = PREDICATE_MD5_CACHE.computeIfAbsent(predString, s -> Hashing.hash(s, HashType.MD5).substring(0, 8));` — check all doc-build call sites computing predicate hashes (also `addGenericMetadataFields`) and route them through the cache.
- [ ] Run SOLR ×3 (this is on the doc-build path — measurable).
- [ ] Commit: `Memoize predicate MD5 hash used for Solr dynamic field names`.

### Task 1.5: D8 — drop pretty-printing in RDFJSON

**Files:** Modify `modules/rest/spring-boot/src/main/java/org/entrystore/rest/springboot/util/RDFJSON.java:238`

- [ ] `obj.toString(2)` → `obj.toString(0)`; grep the module for other `toString(2)` on org.json objects in response paths (D3's SearchService instance is handled in Task 6.5 — only change RDFJSON here).
- [ ] Run affected ITs (whichever cover RDF/JSON responses — grep ITs for rdf+json). NB ×1 regression.
- [ ] Commit: `Serialize RDF/JSON responses without pretty-printing`.

### Task 1.6: F1 — precompiled URISplit patterns

**Files:** Modify `core/core-impl/src/main/java/org/entrystore/repository/util/URISplit.java`

- [ ] `private static final Pattern URI_PATTERN = Pattern.compile("^_?[a-zA-Z0-9-_]+/?");` (and params variant); use `.matcher(s).matches()`; hoist `anyURI.toString()` into a local reused for `startsWith`/`substring`.
- [ ] Run NB ×3, NU ×2, MU ×2 — URISplit is on the entry-resolution path, potentially visible in the read phase.
- [ ] Commit: `Precompile URISplit regexes and reuse URI string`.
- [ ] Write findings doc `09-quick-wins-batch.md` with per-item before/after tables.

## Phase 2 — A2: metadata graph once per Solr doc build (findings doc 11)

### Task 2.1

**Files:**
- Modify: `core/core-impl/src/main/java/org/entrystore/repository/util/EntryUtil.java` — add Model-accepting overloads: `getTitles(Model, URI)`, `getFirstName(Model, URI)`, `getLastName(Model, URI)`, `getEmail(Model, URI)`, `getDescriptions(Model, URI)`, `getTagLiterals(Model, URI)`, `getTagResources(Model, URI)` (existing Entry-accepting methods delegate: fetch graph once, call the Model overload).
- Modify: `core/core-impl/src/main/java/org/entrystore/repository/util/SolrSearchIndex.java:902-1134` (`constructSolrInputDocument`) — reuse the already-fetched `mdGraph` for all EntryUtil calls and `addRelatedFields`.

- [ ] Add overloads (keep exact filtering semantics — copy the existing method bodies, parameterize the graph + resource URI).
- [ ] Route all 8+ call sites in the doc build through `mdGraph`.
- [ ] Unit-test one overload pair for equivalence if a test fixture exists; else add a focused test.
- [ ] Run SOLR ×3 before/after (before = Task 0.2 baseline). NB ×1 regression.
- [ ] Commit: `Fetch metadata graph once per Solr document build`.
- [ ] Findings doc `11-solr-doc-build-single-graph-load.md`.

## Phase 3 — A1/A9/A10/A13/A12/A15: Solr write-path decoupling (findings doc 12)

### Task 3.1: A1+A9 — queue URIs, build documents in the submitter

**Files:** Modify `core/core-impl/src/main/java/org/entrystore/repository/util/SolrSearchIndex.java`

- [ ] Change `postQueue` to hold entry URIs (keep Caffeine keyed by URI, value = marker or timestamp), `postEntry` enqueues the URI only (cheap, still under the existing locks but O(1)).
- [ ] `processAddBatch` (submitter thread) drains URIs, loads each entry via ContextManager as admin (reuse the existing save/set/restore auth pattern from `reindexSync`), builds the doc outside all repository/listener locks, then submits the batch.
- [ ] Deleted-while-queued races: entry may be gone at build time — skip with debug log; `removeEntry` invalidation semantics preserved (URI key identical).
- [ ] `getPostQueueSize()` semantics unchanged (benchmark-solr polls it).
- [ ] Run SOLR ×3; also NB ×3 with `-s native` + solr OFF as regression (core changes only affect solr-on paths, verify).
- [ ] Commit: `Build Solr documents in the submitter thread outside repository locks`.

### Task 3.2: A10 — non-throwing guest-readability derivation

- [ ] Replace the guest-probe try/catch with a boolean check: extract a non-throwing `PrincipalManager` capability if one exists (check `getRights(entry)` / `hasAccess` visibility); otherwise add `PrincipalManagerImpl.isUserAuthorized(URI userURI, Entry, AccessProperty)` that runs the same logic as `checkAuthenticatedUserAuthorized` minus the throw, and use it with the guest URI. Semantics (context-ACL inheritance, admin overrides) must be identical — assert with a unit test comparing both paths on entries with entry-level ACL, context-level ACL, and no ACL.
- [ ] Run SOLR ×3. Commit: `Derive Solr public flag without exception control flow`.

### Task 3.3: A13 — signal-based submitter

- [ ] Mirror the `queueSignal` wait/notify pattern from `PublicRepository.java` (recheck-under-lock, `wait(IDLE_TIMEOUT_MS)` heartbeat); `postEntry`/`removeEntry` signal after enqueue.
- [ ] Run SOLR ×3 (expect lower queue-drain latency tail). Commit: `Replace Solr submitter idle polling with wait/notify`.

### Task 3.4: A12 — configurable commitWithin

- [ ] New setting `entrystore.solr.commit-within` (ms), default raised to 5000; adaptive backlog logic preserved. Add to `Settings`, read in SolrSearchIndex init, document in `entrystore.properties_example`.
- [ ] Run SOLR ×3. Commit: `Make Solr commitWithin configurable and raise default to 5s`.

### Task 3.5: A15 — context purge off the request thread

- [ ] Route the context-deletion `deleteByQuery` through `purgeExecutor` (already a VT executor); log completion/failure.
- [ ] Run affected ITs (context deletion + search). Commit: `Run context-wide Solr purge on the background purge executor`.
- [ ] Findings doc `12-solr-write-path-decoupling.md` (cumulative table across 3.1–3.5).

## Phase 4 — A3/G2/A7/A8: parallel reindex (findings doc 13)

### Task 4.1: A3+G2 — virtual-thread reindex fan-out

- [ ] In `reindexSync`/`postContextEntriesToQueue`: fan doc-building out to `Executors.newVirtualThreadPerTaskExecutor()` bounded by a `Semaphore` (default ~2× cores, configurable `entrystore.solr.reindex-concurrency`); each task sets admin auth (ThreadLocal!) via the existing save/set/restore pattern; after A1 lands, reindex can enqueue URIs and let the submitter build — prefer that simpler shape if it preserves the "reindex complete" semantics the callers rely on.
- [ ] Extend benchmark-solr with a timed reindex phase: after insert+drain, call `((SolrSearchIndex) rm.getIndex()).reindexSync(contextURI, true)` and log duration (new `REINDEX` log section).
- [ ] Run SOLR (now incl. reindex timing) ×3 before/after. Commit in two steps: harness extension, then fan-out.

### Task 4.2: A7 — projectType per-context cache for the reindex run
### Task 4.3: A8 — cached context set for solr.related

- [ ] Both: small caches with documented invalidation (A7: per-reindex-run map; A8: invalidate on context create/delete events). Run SOLR reindex ×3 after each. One commit each.
- [ ] Findings doc `13-parallel-reindex.md`.

## Phase 5 — B-theme: authorization caching (findings doc 14)

### Task 5.1: Harness — authenticated read pass as benchmark user

**Files:** `BenchmarkCommons.java`/`Arguments.java` (flag `-r`/`--read-as-user`), `benchmark-entrystore/Benchmark.java` (after insert, when flag set and ACL mode on: create a group, add benchmark user as member, grant the group ReadMetadata on the context, authenticate as benchmark user, run the existing read loop; time it separately).

- [ ] This makes `hasAccess` → `getGroupUris` fire per entry read (non-admin, group-granted access). Baseline run before B-changes: `-s native -u 5000 -m 1000 -B true -a true -r true` ×3.
- [ ] Commit: `Add read-as-user benchmark pass exercising ACL group resolution`.

### Task 5.2: B1 — memoized user→groups

**Files:** `core/core-impl/src/main/java/org/entrystore/impl/PrincipalManagerImpl.java`, `GroupImpl.java`, principal mutation sites.

- [ ] `private final Map<URI, Set<URI>> userGroupsCache = new ConcurrentHashMap<>();` in PrincipalManagerImpl; `getGroupUris` computes-if-absent (defensive copy on return — callers mutate via `retainAll`!).
- [ ] Invalidate (clear whole map — groups are few, correctness first): on group membership mutation (GroupImpl.addMember/removeMember + underlying ListImpl.addChild/removeChild/setChildren when owner is a Group), group create/delete, user delete. Enumerate sites by grepping `GraphType.Group` mutations and `removeChild` callers; hook via PrincipalManagerImpl or repository events (`RepositoryEvent.ResourceUpdated` on Group entries) — choose the narrowest reliable hook and document it.
- [ ] Unit test: membership change invalidates; concurrent access safe.
- [ ] Run 5.1 read benchmark ×3. Commit: `Memoize user-to-group resolution in PrincipalManagerImpl`.

### Task 5.3: B2+C9 — O(1) membership + list child sets
- [ ] ListImpl: maintain `HashSet<URI> childrenSet` mirror alongside the Vector (loadChildren/addChild/removeChild/moveChild/setChildren); `GroupImpl.isMember` and `addChild`'s contains-check use it.
- [ ] Run 5.1 read benchmark ×3 + NB ×3 (addChild is on write path via lists? only when lists used — note). Commit: `Maintain a hash set of list children for O(1) membership checks`.

### Task 5.4: B3 — ACL principal sets from loadFromStatements
- [ ] In the `loadFromStatements` statement loop, collect es:read/es:write objects per subject (entryURI/localMdURI/resURI) into the five sets (see `getAccessSubject`/`getAccessPredicate` mapping) and assign instead of nulls; keep lazy path as fallback.
- [ ] Unit test: entry loaded from statements returns identical principal sets vs lazy path.
- [ ] Run 5.1 read benchmark ×3 + NU ×2 (loadFromStatements is on every entry load). Commit: `Parse ACL principal sets during loadFromStatements`.

### Task 5.5: B5 — cache isDisabled
- [ ] `private Boolean disabled;` in UserImpl, populate on first read, set in setDisabled; mirror getLanguage pattern.
- [ ] Run 5.1 read benchmark ×1 (marginal) — mainly REST-path win. Commit: `Cache UserImpl.isDisabled like sibling getters`.

### Task 5.6: B6 — evaluate after measurements; implement pre-authorized internal accessor only if 5.1 numbers show remaining getGraph hotspots. Record either way.
- [ ] Findings doc `14-authorization-caching.md`.

## Phase 6 — D-theme REST batch 1 (findings doc 15)

Measurement: REST paths aren't covered by the harness. Protocol: run the exec jar locally (native store, seeded via a bulk-import script or the benchmark store dir), time endpoints with PowerShell `Measure-Command` loops (100 requests warm). Where impractical, ITs assert behavior and the doc records "not measured, reasoned estimate" honestly (docs 01–07 precedent is empirical — keep claims modest).

### Task 6.1: D1 — conditional GET → 304
- [ ] In controllers that emit Last-Modified/ETag via `HttpUtil.setLastModifiedAndETag` (grep callers: MetadataController, EntryController, ResourceController…), call `ServletWebRequest.checkNotModified(etag, lastModifiedMillis)` **after authorization, before load/serialize**; return 304 with empty body when true. Add IT: GET with `If-None-Match` returns 304; changed entry returns 200.
- [ ] Commit: `Honor If-None-Match/If-Modified-Since with 304 responses`.

### Task 6.2: D2 — paginate before loading list children
- [ ] `ResourceJsonSerializer.serializeResourceList`: slice the children **URI list** to [offset, offset+limit) before any `getByEntryURI`; keep unlimited default (API compatibility) but record the recommendation. IT: existing list ITs must pass; add one asserting page contents equal pre-change behavior.
- [ ] Commit: `Load only the requested page of list children`.

### Tasks 6.3–6.12 (one commit each, same pattern — implement, IT, measure where feasible):
- [ ] 6.3 A4: principal `fq` (`acl.metadata.r:(<principals>) OR public:true`) added server-side per search; app-level check retained; IT: guest/user search results identical to pre-change on a seeded ACL matrix.
- [ ] 6.4 A17: cap offset (config `entrystore.search.max-offset`, default 10000, 400 above).
- [ ] 6.5 D3: `toString(0)` in SearchService + drop per-hit loads that duplicate Solr-returned data where the response is provably identical (compare serialized output on the IT seed before/after).
- [ ] 6.6 D4: derive IDs by substring as the deleted-branch does.
- [ ] 6.7 D5: cut to feed size before metadata extraction in SyndicationService.
- [ ] 6.8 D7: static writer-factory map in GraphUtil.
- [ ] 6.9 D12: precompute title map, sort by it (EntryUtil.186-206 comparator + ResourceJsonSerializer caller).
- [ ] 6.10 D13: verify (entry,level) semantics; implement global-visited only if output equivalence holds on traversal tests.
- [ ] 6.11 D14: Set<URI>-based dedup in ContextManagerImpl SPARQL search (fixes never-matching contains).
- [ ] 6.12 D16: config-gate RequestResponseLoggingFilter (default off in prod profile).
- [ ] Findings doc `15-rest-read-path.md`.

## Phase 7 — REST auth & proxy (findings doc 16)

- [ ] 7.1 B4: Caffeine cache (TTL 2s, configurable) in ESUserDetailsService.loadUserByUsername keyed by lowercase username; invalidate on logout/user-modification where reachable; move `sessionRegistry.registerNewSession` to login success handler; keep disabled-check per request (reads the cached UserDetails). ITs: login/session ITs + a new one asserting a disabled-mid-session user is rejected within TTL bounds (document the ≤TTL staleness window in the code and findings doc).
- [ ] 7.2 D6: JDK HttpClient (shared, pooled) in ProxyService; stream body with 10 MB cap enforced during copy; redirects use the same client. ITs: ProxyIT.
- [ ] 7.3 D10: read CheckUsernamePasswordFilter:74-84; if the double PBKDF2 is a stored-hash format probe, replace with regex/length validation of the *stored format* (not secret-dependent → no timing channel); if it hashes the submitted password twice, document and leave (constant-time concerns) — decide from code, record decision.
- [ ] 7.4 G5: `Executors.newVirtualThreadPerTaskExecutor()` for the password-reset executor in AuthService.
- [ ] Findings doc `16-rest-auth-and-proxy.md`.

## Phase 8 — C-theme core mutation (findings doc 17)

- [ ] 8.1 C2: `ListImpl.removeChild`: remove the child's `rdf:_n` triple, renumber only indices > n (remove+add per shifted index) in one tx; full rewrite stays for `setChildren`. Unit tests: remove head/mid/tail, order preserved; O-behavior asserted by op-count via a spy connection if practical. Extend harness minimally if needed for measurement (a `--list-mode` creating one list of N entries then deleting M) — decide when measuring.
- [ ] 8.2 C3+C12: single tx for the 5-setter ACL copy in ContextImpl create-in-list; make `updateAllowedPrincipalsFor` + RDFResource/UserImpl/quota setters honor `getActiveBatchConnection` (same pattern as doSetGraph); C12 rides on that.
- [ ] 8.3 C4: fetch oldGraph in `setGraph` only when needed (Context-type entries / provenance enabled); update cached fields from the in-memory model instead of re-reading post-commit.
- [ ] 8.4 C5 remainder: `EntryImpl.setFileMetadata(long size, String mime, String name)` — one tx, one modified-date, one event; ResourceService upload finalization uses it; populate fileSize in loadFromStatements.
- [ ] 8.5 C6: fix quota fill-level cache (populate on read, return the right variable; exact-remove write inside the upload's tx).
- [ ] 8.6 C8: context deletion — skip SoftCache population, single graph clear, skip intra-context inverse-relation scans.
- [ ] 8.7 C10: provenance — reuse active connection; resolve latest revision via the `owl:sameAs` triple.
- [ ] Each: unit tests + NB/NU/MU (8.1/8.2 measurable if harness touches lists — extend if not; 8.3/8.4 visible in NU). Findings doc `17-core-mutation-paths.md`.

## Phase 9 — E-theme maintenance ops (findings doc 18)

- [ ] 9.1 E1: export reads per-context named graphs on a snapshot connection without `synchronized(repository)`.
- [ ] 9.2 E2 partial: import drops per-triple `hasStatement` + INFO log; commits in chunks (~5k triples).
- [ ] 9.3 E3: BackupJob holds write-lockout only during RDF export.
- [ ] 9.4 E4: `ContextImpl.reIndex` iterates the context's own entry index instead of repo-wide scan.
- [ ] 9.5 E5 partial: PublicRepository cascades context-entry updates only when the ACL actually changed (compare read-set before/after).
- [ ] 9.6 E9: `@DisallowConcurrentExecution` on BackupJob.
- [ ] Measurement: micro-timings via tests (export/import of a 10k-entry context dump) — build the dump once with the harness store. ITs for import/export/backup. Findings doc `18-maintenance-operations.md`.

## Phase 10 — F-theme mechanical + wrap-up (findings doc 19)

- [ ] 10.1–10.6: F2–F7 as listed in the disposition table, one commit each, NB ×1 regression each.
- [ ] Findings doc `19-mechanical-cleanups-and-wrapup.md`: F results + the record-only/skip table (C1, A11, C5-guard, A14, C11, D9, D11, D15, E6–E8, G1, G3–G4, G6–G8) with one-line rationales, + a cumulative before/after summary table across docs 09–19 (docs 01–07 style).

## Self-review notes

- Spec coverage: every finding ID A1–A17, B1–B6, C1–C12, D1–D16, E1–E9, F1–F7, G1–G8 appears in the disposition table with a task or an explicit record-only/skip rationale. ✓
- Known risks called out: A10 semantic equivalence (context-ACL inheritance), B1 invalidation completeness, B4 staleness window, D2/D16/A12 behavior changes are config-gated or compatibility-preserving, D10/D13 are investigate-first.
- Later-phase tasks (6–10) intentionally carry approach-level detail; each instructs re-reading the target file before editing because line numbers drift and several findings have already been invalidated by branch evolution — the Phase-verification step (read before edit, confirm the problem still exists) is mandatory per task.
