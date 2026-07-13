# EntryStore Investigation — Maintenance Operations (E-theme)

Date: 2026-07-13
Module: `core/core-impl`
Branch: `feature/benchmark-ai`

Theme E covers background/maintenance jobs (findings E1–E9). None of
these run on the `benchmark-entrystore` write/read path — they are
export, import, backup, reindex and public-repository-mirroring
operations — so the gate here is unit tests + the IT suite + reasoning,
not the write benchmark.

## Implemented

### E9 — no overlapping backups

`BackupJob` now carries `@DisallowConcurrentExecution`. Overlapping fires
previously queued on the class's `synchronized` static methods and then
ran redundant backups back-to-back, each re-triggering the write lockout
(503 for all writes). Quartz now refuses to start a new backup while one
is still running, so a slow backup no longer stacks duplicates behind
itself. Trivial and safe (a Quartz scheduling annotation; no logic
change).

Gate: `BackupJobTest`, `BackupSchedulerTest` green.

## Deferred (with rationale)

All of these are real, but each is a maintenance-path change that the
write benchmark cannot measure and that carries correctness risk
disproportionate to bundling into this round:

- **E1 — context export off the global monitor.** Export holds
  `synchronized(repository)` for its whole duration and enumerates every
  named graph in the repo on the request thread. A snapshot-isolated
  read without the monitor is the right shape but changes locking on the
  export path; deferred to a focused change with export ITs.
- **E2 — import batching.** Import parses the full dump into memory and
  runs one giant transaction under the monitor with a per-triple
  `hasStatement` + INFO log. Dropping the per-triple probe/log and
  chunking commits is worthwhile, but import correctness (entry
  reconstruction, reindex-after) needs careful testing; deferred.
- **E3 — backup write-lockout scope.** The lockout is held through the
  whole data-folder copy, though only the RDF export needs it (and that
  export uses a snapshot-isolated connection anyway). Narrowing the
  lockout window is valuable but must be verified against the backup
  consistency guarantees; deferred (pairs naturally with E1).
- **E4 — scoped `ContextImpl.reIndex`.** `reIndex` scans all
  `es:resource`/`es:externalMetadata` statements of the **whole**
  repository under the monitor — O(repo) per single-context reindex, run
  at the end of every context import. Scoping it to the context's named
  graphs is the fix, but it is entangled with inverse-relation
  correctness; deferred to its own change.
- **E5 — PublicRepository cascade only on ACL change.** Any update to a
  Context-type entry re-mirrors every entry of that context. Cascading
  only when the guest ACL actually changed is the right fix; it is
  config-gated (the public repository feature) and unexercised by the
  benchmark/default ITs. Deferred.
- **E6 — harvester batching.** The OAI-PMH `ListRecordsJob` uses the
  exact pre-batching per-record transaction pattern, but the harvesting
  module is **not wired into the Spring Boot classpath today** (latent).
  Deferred until the module is re-wired.
- **E7 — NativeStore upgrade chunked commits.** A one-off migration path
  (store format upgrade); not a steady-state cost. Recorded, not changed.
- **E8 — pipeline execution batching / async.** Per-entry transactions on
  the request thread with no timeout; a proper fix needs an async
  execution API with status polling — a design change beyond this
  performance pass. Deferred.

## Correctness

`BackupJobTest`, `BackupSchedulerTest` green (E9 is a scheduling
annotation with no logic change).
