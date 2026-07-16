# Finding 25: Conditional GET with representation-aware ETags (ENTRYSTORE-1087, D1)

Date: 2026-07-17
Branch: `feature/benchmark-ai-deferred`
Ticket: [ENTRYSTORE-1087](https://metasolutions.atlassian.net/browse/ENTRYSTORE-1087)

## What was discovered first

Doc 08's D1 premise ("no endpoint handles If-None-Match") was wrong at the
transfer level: Spring MVC's `HttpEntityMethodProcessor` automatically
evaluates conditional headers for controllers that return a `ResponseEntity`
carrying an ETag — so the metadata endpoint already answered 304s. **But it
did so against the timestamp-only ETag**, which is exactly the unsafe case
doc 15 warned about, demonstrated live against the pre-change jar:

```
[before] json ETag: "1784239667516"
[before] GET turtle with the json ETag -> 304 NotModified
```

The server confirmed a JSON representation as valid *for a Turtle request* —
any client or shared cache that revalidates across representations is served
the wrong body indefinitely. This turns the ticket from a pure performance
item into a correctness fix.

## What was changed

- **Representation-aware strong ETags** (`HttpUtil.createRepresentationETag`):
  `"{modifiedMillis}-{sha256/12hex of representation key}"`. The key covers
  the negotiated media type and every representation-affecting parameter
  (metadata: type, format, graphQuery, depth, recursive, scope, rev;
  lookup: scope, media type). Two representations of the same entity can no
  longer share an ETag; the cross-representation revalidation now correctly
  returns 200 (verified against both jars).
- **Pre-load fast path** on the polling-heavy local-metadata GET: after an
  explicit `checkAuthenticatedUserAuthorized(entry, ReadMetadata)` — the
  same property the load path enforces — `WebRequest.checkNotModified`
  answers the revalidation *before* the graph is loaded and serialized.
  Guest/anonymous still gets 404, authenticated-without-access 403 (pinned
  by `ConditionalGetIT`: authorization always precedes the 304 decision).
- **Post-load conditional** for cached-external/merged metadata (their
  authorization involves the referenced entry via `LocalMetadataWrapper`, so
  the load must run) and for `/lookup` — saves the transfer, never skips
  authorization.
- **Exclusions, by design**: recursive representations (aggregate other
  entries; their latest-modified is not monotonic for this ETag), explicit
  revisions, and JSONP callback requests (body wrapped by a filter,
  contractually non-cacheable) carry **no ETag/Last-Modified at all** — merely
  skipping the explicit `checkNotModified` would not be enough, because the
  same Spring auto-evaluation shown above answers 304 for any `ResponseEntity`
  carrying an ETag. **The entry and resource GET endpoints deliberately get no
  conditional handling**: their bodies embed relations and list children that
  change without bumping the entry's own modification date — honoring
  If-None-Match there would serve stale 304s. This deviates from the original
  plan (which included the entry GET) for correctness reasons.
- `Vary: Accept` set on the participating GETs. Contract ITs updated:
  `LocalMetadataResourceIT` now pins `"\d+-[0-9a-f]{12}"` and asserts the
  recursive shape carries no conditional headers; new `ConditionalGetIT`
  covers 304 on match, 200 across representations, header-free JSONP, new
  ETag after PUT, If-Modified-Since, and the two authorization cases.

## How it was measured

Two exec-jar app instances (before = branch tip, after = this change),
memory store, external Solr, fresh instance per run (a stale-listener guard
was added to the driver after an earlier run silently measured one app for
both sides — same-timestamp ETags gave it away). In-process HTTP client,
admin session, `GET /_principals/metadata/_admin`; 25 warm-up, 100
unconditional (control), 500 conditional with If-None-Match. Two rounds per
side, interleaved.

## Results

| Metric | before | after |
|---|---|---|
| Cross-representation revalidation | **304 (wrong body confirmed)** | **200 + correct body** |
| Conditional GET (500 × If-None-Match) | 1 946 / 1 929 ms → 3.88 ms/req, 0 B | 1 871 / 1 557 ms → 3.43 ms/req, 0 B (**−11.5%**) |
| Unconditional GET (100×) | 620 / 629 ms | 674 / 657 ms (≈ flat) |
| 304 rate on unchanged entity | 500/500 (Spring auto-handling) | 500/500 |

## Implication

- **The headline is correctness**: representation-aware ETags close a live
  false-304 bug that the old timestamp ETag exposed through Spring's
  automatic conditional handling.
- The fast path trims ~12% off revalidation latency for a minimal metadata
  graph — the saving is the skipped graph load + serialization, so it grows
  with metadata size; transfer savings were already delivered by the Spring
  auto-handling.
- ETag semantics are unchanged for writers (`PUT` responses still carry the
  plain timestamp ETag as an advisory value; only GET revalidation keys on
  the representation).

## How to reproduce

```powershell
# contract: ConditionalGetIT, LocalMetadataResourceIT, LookupIT, JsonpIT (56/56 green)
.\mvnw.cmd clean verify -pl modules/rest/integration-test "-Dit.test=ConditionalGetIT"
# cross-representation check + polling driver: see run-polling-1087.ps1 /
# check-cross-rep-1087.ps1 pattern in this doc's session — GET json, capture ETag,
# GET turtle with If-None-Match=<json ETag>: before 304, after 200.
```
