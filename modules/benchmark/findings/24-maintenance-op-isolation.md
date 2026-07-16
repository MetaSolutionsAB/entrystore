# Finding 24: Maintenance-op isolation E1–E4 (ENTRYSTORE-1086)

Date: 2026-07-16
Branch: `feature/benchmark-ai-deferred`
Ticket: [ENTRYSTORE-1086](https://metasolutions.atlassian.net/browse/ENTRYSTORE-1086)

## What was changed

The four maintenance findings deferred in doc 18:

- **E1 — export off the global monitor.** `ContextManagerImpl.exportContext` no longer takes
  `synchronized(repository)` for its whole duration; it reads inside a
  `SNAPSHOT_READ` transaction instead, so the exported data is still one
  consistent view while concurrent writers proceed unblocked.
  `ContextExportConcurrencyTest` proves the property deterministically: the
  test holds the repository monitor (what every core write path locks) and
  the export must still complete — pre-change this deadlocks.
- **E2 — import de-probed and chunked.** The per-triple
  `rc.hasStatement` probe and per-triple INFO log are gone — RDF4J statement
  storage has set semantics, so re-adding an existing statement is a no-op
  and the probe only fed the imported/skipped counters. Commits are chunked
  every 10 000 statements to bound transaction size. Trade documented in
  code: a repository failure mid-import now leaves a partial context rather
  than an emptied one (entry removal already happened outside the
  transaction pre-change; full atomicity is ENTRYSTORE-1064).
- **E3 — narrower backup lockout.** The write lockout (503 for all writes)
  now covers only the RDF export phase (main + provenance repository dumps)
  and is released before the data-folder copy, which can take minutes on
  large installations. Files created after the RDF snapshot become benign
  extras in the backup; a file deleted mid-copy surfaces as a logged copy
  error. `BackupLockoutWindowTest` intercepts the copy statically and
  asserts the lockout is already off; the `execute()` safety net still
  force-releases on failure.
- **E4 — scoped reIndex.** `ContextImpl.reIndex` restricted both repo-wide
  scans (`es:resource`, `es:externalMetadata`) to the context's own entry
  named graphs (`{contextResourceURI}/entry/*`), enumerated once via
  `getContextIDs`. O(repo) → O(context) per reindex — and reIndex runs at
  the end of every context import. (Implementation note: RDF4J treats an
  empty contexts vararg as "all graphs", so the empty-context guard is what
  keeps the scoping sound.) `ContextReIndexTest` pins index equivalence:
  same entry set, working resource/external-md lookups, preserved id
  counter, no cross-context bleed.

Correctness gates: new unit tests above plus `ContextExportIT` (9/9) and
`ContextImportIT` (13/13) — including the parse-failure-preserves-entries
and missing-triples contracts — green against the new core.

## How it was measured

New harness phase **`-E/--maintenance`** (benchmark-entrystore): after the
write phase, timed `exportContext` (TriG), assembly of a REST-style import
ZIP, timed `importContext` into a fresh context, then one more timed
explicit `reIndex` of the imported context. Interleaved A/B (3v3 at u=2000
plus 2v2 at u=10000, discarded warm-up), fresh native store per run, AC
power, canaries 208–344 ms. Both sides imported identical entry counts
(4 000 / 20 000).

## Results

u=2000 (4 000 entries, ~60 k statements):

| Metric (ms) | before | after | Δ |
|---|---:|---:|---:|
| Import | 3 120 / 3 452 / 3 217 → **3 263** | 1 847 / 1 859 / 1 815 → **1 840** | **−43.6% (1.77×)** |
| Export | 819 / 833 / 835 → 829 | 815 / 974 / 787 → 859 | flat |
| reIndex | 154 / 162 / 146 → 154 | 157 / 124 / 150 → 144 | −6.6% (repo:context ratio only 2:1) |

u=10000 (20 000 entries, ~300 k statements):

| Metric (ms) | before | after | Δ |
|---|---:|---:|---:|
| Import | 12 415 / 12 961 → **12 688** | 8 505 / 8 266 → **8 386** | **−33.9%** |
| reIndex | 741 / 738 → **740** | 562 / 577 → **570** | **−23.0%** |
| Export | 2 419 | 2 371 | flat |

## Implication

- **Import is 1.5–1.8× faster** from dropping the per-triple probe alone —
  and this understates production: the benchmark's log config suppresses the
  per-triple INFO line the old code emitted, which a production INFO config
  pays on every imported triple.
- **The E4 reindex win scales with how much repository surrounds the
  context**: at a 2:1 repo:context statement ratio it's already −23%; a
  production repository with dozens of contexts pays the old O(repo) scan
  per import, the new cost stays O(context).
- **Export throughput is unchanged by design** — E1's value is availability,
  not speed: a multi-minute export no longer stalls every write in the
  instance (deterministically pinned by `ContextExportConcurrencyTest`), and
  E3 shrinks the backup write-outage from O(RDF export + file copy) to
  O(RDF export).
- The remaining monitor-holder among maintenance ops is import (kept: it
  mutates the repository and relies on the monitor for exclusion). Its held
  time is now bounded by chunked commits rather than one giant transaction.

## How to reproduce

```powershell
java -Xmx2g -jar modules\benchmark\benchmark-entrystore\target\entrystore-benchmark-entrystore-6.0-SNAPSHOT.jar `
  -s native -u 2000 -B true -E true -p <fresh-dir>
# compare "Exporting/Importing/Reindexing context took" across jar snapshots;
# unit gates: ContextReIndexTest, ContextExportConcurrencyTest, BackupLockoutWindowTest
# IT gates: ContextImportIT, ContextExportIT
```
