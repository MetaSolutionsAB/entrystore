# EntryStore Benchmark — Session Context & Handoff

Date: 2026-07-14
Branch: `feature/benchmark-ai` (tip `a6d64770`)
Epic: [ENTRYSTORE-1070](https://metasolutions.atlassian.net/browse/ENTRYSTORE-1070)
PR: [#322](https://bitbucket.org/metasolutions/entrystore/pull-requests/322) → `develop-spring` (draft)

This document captures the working context of the whole performance round so
anyone (or any future session) can pick the work up without re-deriving it.
It is the index and state snapshot; the technical detail lives in docs 01–20.

## What this branch is

Two rounds of benchmark-driven performance work:

1. **Round 1 (docs 01–07):** bulk-import write path. Root-caused the
   native-store slowdown, added the `inBatch` batched-transaction API, skipped
   the empty-metadata scan, overwrote the modified-date in place, added the
   index/forceSync knobs. Combined effect: native batched bulk import
   **~49× faster** than the original baseline (460 s → 9.3 s, 10 000 persons).
2. **Round 2 (docs 08–20):** codebase-wide survey (doc 08, ~60 findings,
   themes A–G) implemented phase by phase — Solr write-path decoupling,
   authorization caching, REST read-path trims, security/availability fixes,
   core mutation quick wins, benchmark-harness infrastructure. Headline
   measured wins: A2 **−6.9%** on Solr-indexed inserts; B1 group-authorized
   reads **−48.9% (2.0×)** at a 2 207-principal directory (−5.3% at toy scale —
   the win scales with directory size).

## JIRA structure (all issues assigned to Patrik Kompuš)

| Ticket | Scope | Status |
|---|---|---|
| ENTRYSTORE-1070 | Epic for the whole branch | Open |
| ENTRYSTORE-1071–1077 | Round 1, one ticket per step (docs 01–07) | Resolved/Fixed |
| ENTRYSTORE-1078 | Solr write path: A2, A1/A9/A13 decoupling, A7/A8 caches (docs 11–13) | Resolved/Fixed |
| ENTRYSTORE-1079 | Authorization caching B1/B3/B5 + at-scale measurement (doc 14) | Resolved/Fixed |
| ENTRYSTORE-1080 | REST read path: D-theme + A17 (doc 15) | Resolved/Fixed |
| ENTRYSTORE-1081 | Security/availability: D10 login double-hash, E9 backup guard (docs 16, 18) | Resolved/Fixed |
| ENTRYSTORE-1082 | Core mutation quick wins: C5/C7/A5/A6/A16/F-theme (docs 09, 17) | Resolved/Fixed |
| ENTRYSTORE-1083 | Benchmark harness: `-S`/`-r`/`-P`/`-a` fix (docs 10, 14) | Resolved/Fixed |
| ENTRYSTORE-1084 | Concurrent-writer + reindex-timing benchmark modes | Open (deferred) |
| ENTRYSTORE-1085 | Cross-request user-to-groups cache (security-sensitive invalidation) | Open (deferred) |
| ENTRYSTORE-1086 | Maintenance-op isolation E1–E4 (export/import/reIndex/backup lockout) | Open (deferred) |
| ENTRYSTORE-1087 | Conditional GET / 304 with representation-aware ETags (D1) | Open (deferred) |
| ENTRYSTORE-1088 | Solr principal-based fq pre-filter for search ACLs (A4) | Open (deferred) |
| ENTRYSTORE-1089 | List single-child renumber + batch-aware setters (C2/C3/C12) | Open (deferred) |
| ENTRYSTORE-1090 | UserDetails TTL cache, pooled proxy client, virtual threads, `inBatch` API (B4/D6/G1) | Open (deferred) |

Every commit subject on this branch is prefixed with the ticket it implements
(history rewritten once via `git filter-branch --msg-filter`, content verified
byte-identical to the previously CI-green tip before force-push). Cross-cutting
commits (survey, plans, summaries, re-measurements) carry the epic key.

## Findings docs index (`modules/benchmark/findings/`)

- **01–07** — Round 1: investigation, SingleTransaction baseline, batching,
  empty-metadata skip, modified-date overwrite, indexes, forceSync.
- **08-fable-investigation** — the ~60-finding survey (themes A–G).
- **08-implementation-plan** — phase-by-phase plan mapping every finding to a
  task or explicit deferral; documents the A/B protocol.
- **09** quick wins · **10** benchmark-solr enablement · **11** A2 ·
  **12** Solr write-path decoupling · **13** A7/A8 caches ·
  **14** authorization caching (+ at-scale addendum) · **15** REST read path ·
  **16** auth/proxy · **17** core mutations · **18** maintenance ops ·
  **19** mechanical cleanups + full disposition table.
- **20-deep-summary-and-assessment** — per-improvement win comparison, chart,
  MUST HAVE (10) vs NICE TO HAVE (12) classification, end-to-end before/after
  (§6), value-per-risk ranking of deferred items.

## Measurement protocol (used for every claim)

Paired **interleaved A/B** runs of before/after fat-jar snapshots (copy
`target/*.jar` + `libs/` per side), alternating run order, with the pure-CPU
"Generating data took" canary checked per run; cold first rounds and
inflated-canary rounds discarded. Absolute times are never compared across
sessions or power states — an early battery-vs-AC discrepancy inflated one
result 3× until everything was re-measured under identical AC conditions
(doc 20 §6). Whole-branch end-to-end comparison drove the same harness against
the branch base and tip.

Benchmark flags added this round: `-S/--solr-url` (external Solr; benchmark-solr
needs Docker `solr:10.0.0` with the integration-test core config), `-r/--read-as-user`
(group-authorized read pass), `-P/--principals` (seed N users + N/10 groups),
`-B` batched, `-x` indexes, `-f` forceSync, and the `-a` option-parsing fix.

## Implementation gotchas worth remembering

- **Solr submitter startup gate:** the decoupled submitter must not build
  documents before `RepositoryManagerImpl` finishes initializing —
  `markRepositoryInitialized()` opens the gate at the end of the constructor
  (or just before the synchronous startup reindex, which would otherwise
  deadlock on `waitForQueueDrain`). This fixed a CI-only
  `ConcurrentModificationException` in `ContextImpl.getEntries` during
  `PublicRepository.rebuildRepository`.
- **ITs pin `entrystore.solr.commit-within=1000`** (and `-max`) because the
  production default was raised to 5000 ms.
- **`-P` seeding:** users/groups are created inside `inBatch` transactions, but
  memberships are added outside them — `ListImpl.addChild` manages its own
  connection and must see committed entries.
- **ITs run in password-whitelist mode:** any new IT that logs in a created
  user must add it to `entrystore.auth.password.whitelist.N` in
  `entrystore-it.properties`.
- **`UserGroupsMemo.copy()`** returns a fresh `HashSet` because callers
  `retainAll` on it; the memo is per-decision by design (no invalidation
  needed) — the cross-request cache is ENTRYSTORE-1085.

## Current state / what's next

- All Round-1 and Round-2 implementation work is complete, unit- and
  IT-gated (759 integration tests), measured, and documented.
- PR #322 is a **draft**; its description maps the work to the epic and
  tickets. Undrafting: the Bitbucket PR API `PUT {"draft": false}` (the MCP
  wrapper has no draft parameter).
- The deferred items are fully specified in ENTRYSTORE-1084–1090, each with
  its risk rationale in the corresponding findings doc; doc 20 ranks them by
  value per risk. ENTRYSTORE-1084 (concurrent-writer benchmark mode) is the
  natural first pick — it unlocks measuring the already-merged Solr
  decoupling's concurrency win.
