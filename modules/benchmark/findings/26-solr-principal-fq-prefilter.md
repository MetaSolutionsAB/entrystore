# Finding 26: Solr principal fq pre-filter for search ACLs (ENTRYSTORE-1088, A4)

Date: 2026-07-17
Branch: `feature/benchmark-ai-deferred`
Ticket: [ENTRYSTORE-1088](https://metasolutions.atlassian.net/browse/ENTRYSTORE-1088)

## What was changed

`SolrSearchIndex.sendQuery` now adds a principal-based filter query before
querying Solr (config `entrystore.solr.acl-prefilter`, default on), so hits
the caller provably cannot read never leave Solr. **The per-hit
application-level check remains the decider for every returned hit** — the
filter may only over-include, never under-include. Clauses:

- `public:true` — guest-readable incl. context inheritance (index-time);
- the caller's principals (self, guest, users-group for non-guest, groups
  via the ENTRYSTORE-1085 cache) against `acl.metadata.r`, `acl.metadata.rw`
  (WriteMetadata implies ReadMetadata) and `acl.admin`;
- `context:(...)` for every context the caller administers — the owner
  bypass in `isUserAuthorized` grants context admins every entry regardless
  of entry ACLs, resolved per caller and cached (30 s TTL; ACL changes fire
  no repository events to hook a listener on, so recall staleness is
  TTL-bounded, in the same class as the Solr index lag);
- `resource:<caller>` — the self-access grant in `isUserAuthorized` (a user
  reads its own user entry regardless of its entry ACLs);
- a final `(*:* -acl.admin -acl.metadata.r -acl.metadata.rw)` group keeping
  entries with no read-relevant entry ACL — their access is decided by
  context fallbacks that are not indexed per entry.

Admin and admin-group callers get no filter, and neither do callers whose
clause count would exceed `MAX_PREFILTER_CLAUSES` (512) — hundreds of
groups or administered contexts would otherwise trip Solr's
`maxBooleanClauses`/request-line limits and hard-fail their searches; the
backstop alone decides for them. Principal resolution runs under a
temporary admin switch (the isUserAuthorized pattern).

Two subtleties discovered while proving equivalence:

- **References need no special clause.** The backstop requires ReadMetadata
  on the referring entry itself (`ContextManagerImpl.getEntry` checks it
  before the `LocalMetadataWrapper` branch ever consults the target), i.e.
  search shows a reference iff *both* the reference and its local target are
  readable — which the standard clauses model exactly. An earlier draft
  blanket-kept all references; the matrix IT forced the correction.
- **`ContextManagerImpl.getEntry`'s built-in ACL check** is what actually
  enforces per-hit authorization for the common case; the explicit
  `checkAuthenticatedUserAuthorized` calls in `sendQuery` add the
  target-side check for references.

## Equivalence proof

`SearchAclMatrixIT` seeds one entry per ACL shape — no entry ACL
(context-inherited), direct user grant, group grant, foreign grant, guest
grant, an entry in a context the caller administers but whose entry ACL
denies them, and both reference conjunction cases (self+target readable /
target denied) — and pins the exact visible set per caller (guest, user,
admin, admin-group member) **and** that search visibility equals the
metadata-endpoint authorization ground truth for every (caller, entry)
pair. `SearchIT` (47 tests) stays green. `SolrAclPreFilterTest` unit-tests
the fq string (clauses, escaping, no resource-ACL fields, self-access
clause, oversized-clause skip).

## Results (same jar, flag on vs off, identical seeded corpus)

Corpus: 1 050 marker entries in one context — 1 000 with an explicit
foreign-principal ACL (deny case), 50 guest-readable. Caller: guest.
100 sequential `/search` requests after warm-up, memory store, external
Solr (`bench-solr`), reseeded identically per run.

| Metric | prefilter off (old behaviour) | prefilter on |
|---|---:|---:|
| Latency per search | 27.43 / 27.11 → **27.3 ms** | 11.84 / 11.22 → **11.5 ms** (**−58%, 2.4×**) |
| Results returned to guest | **0 of 50** | **50 of 50** |

The zero-recall row is not a typo: the old post-filter's result-fill loop
gives up after 10 refill iterations (~550 scanned docs), so the 50
readable entries behind 1 000 denied hits were **never returned at all** —
on deny-heavy corpora the old path was not just slow but silently
truncated legitimate results (and its hit-count anti-probing guard then
reported 0). The pre-filter makes Solr return only eligible docs, which
also makes the reported hit count exact whenever denials come from
explicit entry ACLs; entries whose denial comes from a context fallback
still pass the fq's no-entry-ACL fallthrough clause (dropped only by the
per-hit backstop), so they can still inflate the count.

## Implication

- Search latency for non-admin callers no longer scales with the number of
  *denied* hits ahead of the visible ones — the common hardened-deployment
  case (broad corpora, narrow grants) gains the most; admin searches are
  unchanged.
- The recall fix matters more than the speed: any deployment where a
  caller's visible entries sit behind >~550 denied hits got empty/truncated
  search results before this change.
- Cost: one administered-contexts resolution per caller per 30 s
  (O(contexts), SoftCache-backed, Caffeine-cached) plus the group cache
  lookup. Kill switch: `entrystore.solr.acl-prefilter=false` restores the
  old behaviour.

## How to reproduce

```powershell
# equivalence: SearchAclMatrixIT (+ SearchIT), unit: SolrAclPreFilterTest
.\mvnw.cmd clean verify -pl modules/rest/integration-test "-Dit.test=SearchAclMatrixIT,SearchIT"
# latency/recall: seed 1000 foreign-ACL + 50 guest-readable marker entries via REST,
# run 100 guest /search?type=solr&query=title:<marker> with
# --entrystore.solr.acl-prefilter=true|false against the same jar and compare
# latency and result counts.
```
