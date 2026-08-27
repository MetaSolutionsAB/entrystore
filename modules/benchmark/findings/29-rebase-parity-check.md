# Finding 29: Rebase-onto-develop-spring parity check (ENTRYSTORE-1070)

Date: 2026-08-27
Branch: `feature/benchmark-ai`
Ticket: [ENTRYSTORE-1070](https://metasolutions.atlassian.net/browse/ENTRYSTORE-1070) (epic — cross-cutting re-measurement)

## What was compared

The branch was rebased onto `origin/develop-spring` (59 commits replayed; `ecdd7b38` →
`5d9be3e8`). This is a **before-rebase vs after-rebase** A/B: the measured delta is exactly what
the rebase changed, i.e. develop-spring's 9 incoming commits plus the one merge-conflict
resolution:

- **ENTRYSTORE-1064 (final, PR #320)** — atomic context import. The pre-rebase branch carried an
  earlier shape of this work (per-entry `cont.remove()` purge *outside* the add transaction); the
  merged final moves removal *inside* the transaction (`removeNonSystemEntries`), defers file
  deletions and Solr events until after commit, and adds rollback recovery
  (`recoverFromFailedRemoval`, `refreshFromRepository`).
- **Conflict resolution** (only conflict of the rebase, in `ContextManagerImpl`'s import): 1064's
  same-transaction removal was kept, composed with E2's chunked commits (doc 24). Removal rides in
  the first chunk, so imports below `IMPORT_COMMIT_CHUNK` (10 000) statements keep 1064's full
  atomicity; beyond it the failure path branches — no chunk committed →
  `recoverFromFailedRemoval` (rollback restored everything), chunks durable → log + scoped
  `reIndex()` of the partial context (E2's recovery).
- **ENTRYSTORE-1055** — REST-layer exception collapse + header helpers (not on any core-harness
  code path), version bump to 6.1-SNAPSHOT, Docker/site-config housekeeping.

Expected result was parity: nothing in the incoming delta touches the harness's write/read paths,
and the changed import/removal code does not execute in the harness's import shape (see below).

## How it was measured

Doc 24's maintenance command, standard interleaved A/B protocol (docs 20/21): fat-jar snapshots of
both tips (`before` = `ecdd7b38` built in a detached worktree at 6.0-SNAPSHOT, `after` =
`5d9be3e8` at 6.1-SNAPSHOT), 1 discarded warm-up pair + **8 measured pairs** with alternating run
order, fresh native store per run, AC power throughout (`Win32_Battery.BatteryStatus = 2`),
canaries 164–224 ms (before) / 179–196 ms (after) — no inflated rounds to discard. Both sides
imported identical entry counts (4 000) in all 16 runs.

```powershell
java -Xmx2g -jar bench.jar -s native -u 2000 -B true -E true -p <fresh-dir>
```

## Results (u=2000, ms, n=8 per side, mean with median in parentheses)

| Phase | before `ecdd7b38` | after `5d9be3e8` | Δ | Verdict |
|---|---:|---:|---:|---|
| Write (add, batched) | 3 891 (3 871) | 3 851 (3 894) | −1.0% | **parity** |
| Export | 984 (981) | 1 028 (1 005) | +4.4% | noise — ranges fully overlap (862–1 103 vs 948–1 169) |
| Import | 2 223 (2 092) | 2 442 (2 449) | +9.8% mean, **+2.4% paired median** | noise — see below |
| Reindex | 187 (192) | 178 (182) | −4.6% | **parity** (after side tighter: 155–195 vs 133–230) |
| Read | 499 (488) | 555 (568) | +11% | noise — before side alone spans 399–635 (47%) |

**On the import number** — the only phase whose code the rebase touched, so the one worth
scrutiny: the +9.8% mean is driven by outlier rounds (paired per-round deltas split 4 parity /
3 slower / 1 faster; the before side alone spans 1 951–2 714 ms, a 39% within-side spread).
Decisively, the changed code **does not execute in this import shape**: the `-E` phase imports
into a *fresh* context, so `removeNonSystemEntries` (and the conflict-resolution recovery branch)
is a no-op and both sides run the identical E2 chunked-add loop. Architecturally expected parity,
statistically consistent with parity.

## Implication

- **The rebase is performance-neutral** on every phase the core harness measures. No re-baselining
  of docs 24/27 numbers is needed; their improvements sit on both sides of this A/B.
- Absolute times here are **not comparable to doc 24's** (import 3 263 → 1 840 there) — different
  session and machine state (doc 24 canaries 208–344 ms vs ~180 here), per the protocol rule that
  absolutes never cross sessions.
- **Unmeasured shape:** replace-import into a *populated* context — where the pre-rebase per-entry
  removal transactions became one shared transaction — has no harness mode. It is
  correctness-gated (`ContextImportIT` 13/13; core-impl 429 and spring-boot 1 265 unit tests green
  post-rebase) but not timed. A `-E` variant that re-imports over an existing context would close
  that; natural home is the ENTRYSTORE-1086 follow-up.

## How to reproduce

```powershell
# before-side jar: build the pre-rebase tip in a worktree
git worktree add C:\tmp\es-prebase <pre-rebase-sha>
cd C:\tmp\es-prebase; .\mvnw clean package -pl modules/benchmark/benchmark-entrystore -am -DskipTests
# snapshot target\entrystore-benchmark-entrystore-*.jar + target\libs per side, then interleave:
java -Xmx2g -jar bench.jar -s native -u 2000 -B true -E true -p <fresh-dir>
# compare "Adding to context/Exporting/Importing/Reindexing context took" and the
# "Generating data took" canary; discard the cold first pair; alternate run order per pair
```
