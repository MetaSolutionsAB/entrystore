# EntryStore Investigation — Core Mutation Paths (C-theme)

Date: 2026-07-13
Module: `core/core-api`, `core/core-impl`, `modules/rest/spring-boot`
Branch: `feature/benchmark-ai`

Theme C covers RDF4J core mutation paths (findings C1–C12). C1 and C7
were already handled (C1 pre-existing on the branch; C7 in findings doc
09). This doc covers the rest.

## Measurement reality for Theme C

The `benchmark-entrystore` harness exercises exactly one write shape:
`ContextManager.createResource` (context-direct) followed by
`Metadata.setGraph`. It does **not** create entries inside lists, upload
files, delete entries/contexts, or use quotas or provenance. So most
C-theme items sit on paths the benchmark never touches (C2 list rewrite,
C5 upload, C6 quota, C8 context deletion, C10 provenance, C11 resource-URI
change). These are gated by unit tests and the IT suite, not the
write-throughput benchmark. Items C3/C12 (batch-honouring setters) and C4
(setGraph old-graph handling) are closer to the benchmarked path but are
high-blast-radius core changes; see the decisions below.

## Implemented

### C5 (remainder) — one transaction for upload finalization

The file-upload finalization called `entry.setFileSize`,
`entry.setMimetype` and `entry.setFilename` in sequence. Each ran its own
`replaceStatement` — a separate connection, `begin`/`commit`,
`registerEntryModified` (modification-date write) and `EntryUpdated`
event. So finalizing one upload cost **three** transactions, three
modified-date writes and three index events.

New `Entry.setFileMetadata(Long size, String mimeType, String filename)`
(implemented in `EntryImpl`) writes all present fields in **one**
transaction with a single `registerEntryModified` and a single
`EntryUpdated` event; null arguments are skipped. `ResourceService`'s
upload path now calls it. Mime-type-from-metadata precedence and the
cached-field updates match the individual setters exactly.

(The C5 `getFileSize` guard — `if (this.fileSize < 0)` — was already
correct on the branch; see findings doc 09.)

Gate: `EntryImplTest`/`ResourceImplTest`/`DataImplTest` green; full IT
suite green (`ResourceIT` covers multipart and raw uploads).

## Deferred (with rationale)

- **C2 — targeted list child remove/renumber.** `saveChildren` clears and
  rewrites the whole `rdf:Seq` for a single-child remove/move (2N B-tree
  mutations; deleting N entries sharing a list is O(N²)). A targeted
  remove + tail renumber is the right fix but touches the list-ordering
  invariant that `ListImpl` and `GroupImpl` depend on (and group
  membership is security-relevant). It deserves its own change with
  dedicated ordering/`GroupImpl` tests, and is not exercised by the write
  benchmark. Deferred.
- **C3 / C12 — one-transaction ACL copy + batch-honouring mutation
  setters.** The create-in-list ACL copy runs several
  `updateAllowedPrincipalsFor` transactions, and several mutation setters
  ignore `getActiveBatchConnection`. Making every setter batch-aware is
  valuable for REST-driven bulk import, but it is a broad change across
  many setters in the security-relevant ACL path; correctness (each
  setter must behave identically inside and outside a batch) needs
  careful per-setter testing. Deferred to a focused change.
- **C4 — conditional old-graph fetch in `setGraph`.** `EntryImpl.setGraph`
  reads the full old entry graph unconditionally (only needed for
  Context-type entries) and re-reads the just-written graph after commit.
  This is the entry-graph write path; the benchmark drives
  `MetadataImpl.setGraph` (metadata) and `createNewMinimalItem`, not
  `EntryImpl.setGraph`, so it is not measurable here, and it is a
  high-blast-radius core method. Deferred.
- **C6 — quota fill-level cache.** Config-gated (quotas); the cache is
  never populated on read and returns the wrong variable when it is.
  Correctness-adjacent but off by default and unexercised by the
  benchmark or default ITs. Deferred.
- **C8 — context-deletion de-bloat.** Loads every entry fully (graph read
  + SoftCache insert) just to delete it, double-clears graphs, and scans
  intra-context inverse relations. Real, but only on the context-delete
  path (not benchmarked) and intertwined with inverse-relation
  correctness. Deferred.
- **C10 — provenance connection reuse.** Config-gated (provenance off by
  default); opens a second connection inside the open transaction and
  does an O(revisions) scan resolvable via the single `owl:sameAs` triple.
  Deferred (config-gated, unexercised).
- **C11 — `setResourceURI`/`setExternalMetadataURI` single transaction.**
  Explicitly a rare admin operation in doc 08; the 4–6 transactions are
  acceptable. Recorded, not changed.

## Correctness

`EntryImplTest`, `ResourceImplTest`, `DataImplTest` green; full IT suite
green.
