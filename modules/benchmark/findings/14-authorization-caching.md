# EntryStore Benchmark — Authorization Caching (B1/B3/B5)

Date: 2026-07-13
Module: `core/core-impl`, `core/core-api`, `modules/benchmark`
Branch: `feature/benchmark-ai`
Commit: `844be061`

Implements findings **B1**, **B3**, **B5** from findings doc 08, and adds
the harness pass needed to measure them.

## Changes

### B1 — per-decision group memoization

`PrincipalManagerImpl.getGroupUris(userUri)` scans the **entire principals
context** (loads every entry, calls `isMember` on every group). One
authorization decision calls `hasAccess` 4–6 times, and each `hasAccess`
called `getGroupUris` up to twice → up to ~12 full scans per decision,
per entry, per search hit.

`hasAccess` now takes a `UserGroupsMemo` that resolves the caller's group
set **at most once per `isUserAuthorized`/`getRights` decision** and hands
out copies (callers mutate via `retainAll`). Because the memo lives only
for one decision, it needs **no cross-request invalidation** — the
zero-risk way to collapse the redundant scans. `checkAuthenticatedUserAuthorized`
delegates to `isUserAuthorized` (extracted in doc 12 / A10), so both the
throwing and non-throwing paths benefit.

### B3 — ACL principal sets parsed at load

`EntryImpl.loadFromStatements` already holds the entry's `es:read`/
`es:write` triples; it now buckets them into the five principal sets
(mirroring `getAccessSubject`/`getAccessPredicate` exactly) and sets the
`readOrWrite` flag, so the first authorization check issues **no** extra
per-property queries. When an entry carries no ACL, the sets stay null
and the lazy path (which caches an empty set) still applies.

### B5 — cached `isDisabled`

`UserImpl.isDisabled` is now cached like the other UserImpl getters
(a repository read per authenticated request otherwise), invalidated in
`setDisabled`.

### Harness — read-as-group-member pass

The existing read phases run as admin or as a user with a **direct**
grant, both of which short-circuit before `getGroupUris`. The new
`-r/--read-as-user` flag adds a post-write pass that creates a group,
adds a fresh non-admin user to it, grants the **group** `ReadResource` on
the context, authenticates as that user, and reads every entry — so each
read runs a full group-based authorization decision.

## Correctness

Full IT suite **759 tests, 0 failures** (the auth refactor is the
highest-blast-radius change in this series). Core unit tests: 82 across
`EntryImplTest`, `PrincipalManagerImplTest`, `UserImplTest`,
`GroupImplTest`, `ContextImplTest`, `RegularContextTest`, `ListImplTest`,
etc.

## Results

Paired interleaved A/B, `benchmark-entrystore`, native batched, 5 000
persons (**10 000 entries**), `-a true -r true`. `before` = Phase-4
commit core + the same harness; `after` = this commit. Metric: **reading
as a group-member user** (each read = one group-based authorization
decision). Round 1 is excluded — a concurrent build contaminated it
(canaries: round-1 before `gen=338`; rounds 2–3 matched at 231–263 ms).

| Round | after (ms) | before (ms) | speedup |
|---|---:|---:|---:|
| 2 | 1 045 | 1 253 | 16.6% |
| 3 | 1 037 | 1 194 | 13.1% |
| **mean** | **1 041** | **1 224** | **~15%** |

Admin/direct-grant read (`readAllFromDatabase`) was ~900–1 000 ms on both
sides — unchanged, confirming that path never touched `getGroupUris`.

### ⚠ Addendum (2026-07-13) — AC re-run revises the headline down to ~5%

The table above was measured **on battery power** (~4× reduced sustained
clock). Re-run under stable AC conditions with the **same jar snapshots**,
interleaved, order-alternated, canaries matched (232–251 ms):

| Round | after (ms) | before (ms) |
|---|---:|---:|
| 1 | 1 170 | 1 166 |
| 2 | 1 104 | 1 220 |
| 3 | 1 075 | 1 148 |
| **avg** | **1 116** | **1 178** |

**−5.3% on AC** (round 1 flat, rounds 2–3 clearly faster). The
whole-branch A/B (doc 20 §6) independently shows −2.0% on the same
metric. Honest headline: at this benchmark's directory size the measured
win is **~2–5%**, not 15% — the battery's slow CPU amplified the
CPU-bound principals scan roughly 3×. The mechanism (≤1 scan per decision
instead of ~12) is unchanged and its absolute cost grows with
principals-context size, so the production argument now rests explicitly
on scaling, not on the toy measurement. Doc 20's assessment is revised
accordingly.

### Addendum 2 (2026-07-13) — scaling measured: −49% at a 2 207-principal directory

The scaling argument above is now empirical, not reasoned. A new
`-P/--principals` harness flag (commit `3cf5031d`) seeds N secret-less
users plus one group per ten users before the group-read pass. A/B of the
pre-B1 core (`26933b31`) vs the tip, both with this harness, on AC,
order-alternated, canaries 192–227 ms, **2 000 seeded users + 200 groups
(≈ 2 207 principals total)**, 4 000 entries read per run:

| Round | after (B1) | before (no B1) |
|---|---:|---:|
| 1 | 5 667 | 11 696 |
| 2 (after ran first) | 5 753 | 10 821 |
| 3 | 5 617 | 10 825 |
| **avg** | **5 679** | **11 114** |

**−48.9% — group-authorized reads are 2.0× faster** — with tight,
non-overlapping distributions (after 5.6–5.8 k, before 10.8–11.7 k).
Per read: 2.78 ms → 1.42 ms.

Putting the three measurements together:

| Principals in directory | B1 effect on group-authorized reads |
|---|---|
| ~7 (built-ins only, AC) | −5.3% |
| ~7 (built-ins only, battery) | −15% (CPU-starved, amplified) |
| **~2 207 (seeded, AC)** | **−48.9%** |

The memo's win grows with directory size exactly as the O(principals ×
group members) analysis predicts. Note the "after" side still pays **one**
scan per decision (1.42 ms/read at this size) — that remaining linear
cost is what the deferred cross-request user→groups cache would remove,
which quantifies the headroom of that follow-up.

### Why the toy-scale number (whatever the envelope) understates production

The benchmark's principals context is **tiny** — roughly seven principals
and three groups — so a single `getGroupUris` scan is cheap, and
collapsing ~12 scans to 1 per decision saves only ~15% on this workload.
`getGroupUris` cost is O(all principals × members per group); in a
deployment with thousands of users and groups, the same 12→1 collapse
scales with that product and the win is proportionally larger. Even at
this small scale the effect is clean and consistent (non-overlapping,
same direction both rounds).

## Deferred: B2 / C9 and the cross-request group cache

Two related items are **deliberately not implemented**:

- **Cross-request user→group cache (full B1).** A cache that survives
  across authorization decisions would drive the per-decision scan toward
  zero, but it is **security-sensitive**: a stale entry after a user is
  removed from a group grants access they should no longer have. Correct
  invalidation must cover membership changes, group create/delete and
  user delete, several of which flow through inherited `ContextImpl`
  paths. The per-decision memo already removes the dominant redundancy
  with zero staleness risk; the cross-request cache should be a focused,
  separately-reviewed change (single-instance EntryStore has no
  cross-node invalidation problem, so a listener-based local invalidation
  is feasible).
- **B2/C9 — O(1) membership via a HashSet mirror in `ListImpl`.** Keeping
  a `HashSet` in sync with the `children` `Vector` across every mutation
  site (add/remove/move/setChildren/clear/rollback) is desync-prone in a
  class where `GroupImpl.isMember` desync would be a security bug. With
  B1 collapsing `getGroupUris` to one scan per decision, the remaining
  O(members) `isMember` cost is minor for realistic group sizes, so the
  risk/benefit does not favour it in this round.

## How to reproduce

```powershell
$jar = "modules\benchmark\benchmark-entrystore\target\entrystore-benchmark-entrystore-6.0-SNAPSHOT.jar"
java -Xmx2g -jar $jar -s native -u 5000 -m 1000 -B true -a true -r true `
    -p "$env:TEMP\entrystore-bench\es-p5"
# look for "Reading as group-member user took" in the output
```
